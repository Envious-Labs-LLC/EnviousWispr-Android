package com.envi.wispr.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.settings.SpeechModelUnload

/**
 * "Unload model after" for the speech model (#37; plan `docs/feature-requests/issue-37-2026-10-21-unload-model-after.md`
 * §3.2). Each take binds `AsrService` for its own length and unbinds at its end, and `AsrService.onDestroy` closes the
 * model, so every take used to load it again (4 to 7 s on a Galaxy A15 or A35). This keeps ONE extra registration to
 * `AsrService`, made only at a take's start right after the take's own bind succeeded, and keeps it after the take
 * for as long as the user's choice says. It never binds at any other moment, so it can never create or resurrect
 * `:asr` by itself, and it binds with the priority waived, so while it alone holds `:asr` Android may reclaim it.
 *
 * Confined to the main thread: every call, the setting collector and every connection callback.
 */
internal class SpeechModelKeepAlive(
    private val port: Port,
    private val clock: () -> Long,
    private val timers: Timers,
    private val log: (String) -> Unit,
) {
    /** One registration to the speech service. [bind] returns null when it was refused (the port cleaned it up). */
    internal interface Port {
        fun bind(onConnected: () -> Unit, onGone: () -> Unit): Registration?
    }

    internal fun interface Registration {
        fun unbind()
    }

    internal interface Timers {
        fun schedule(delayMs: Long, action: Runnable)
        fun cancel(action: Runnable)
    }

    /** What the hold has been told to last until. */
    internal sealed interface Deadline {
        data object Forever : Deadline
        data class At(val elapsedMs: Long) : Deadline
    }

    private var setting = SpeechModelUnload.DEFAULT
    private var registration: Registration? = null
    private var generation = 0
    private var activeTake: String? = null
    private var takeDeadline: Deadline? = null
    /** The hold an earlier take earned; suspended (no timer) while a later take runs. */
    private var deadline: Deadline? = null
    private var timer: Runnable? = null

    internal val holding: Boolean get() = registration != null
    internal val currentDeadline: Deadline? get() = deadline

    /** A take's own speech bind succeeded. Binds here, and only here, unless the user chose Immediately. */
    fun takeStarted(takeId: String) {
        activeTake = takeId
        takeDeadline = null
        cancelTimer()
        if (setting == SpeechModelUnload.IMMEDIATELY || registration != null) return
        val mine = ++generation
        registration = port.bind(
            onConnected = {},
            onGone = { if (mine == generation) release("the speech process ended") },
        )
        if (registration == null) log("Speech model hold refused at bind")
    }

    /** The take's transcript arrived with text: the countdown starts now, as on macOS. */
    fun transcriptArrived(takeId: String) {
        if (takeId != activeTake) return
        takeDeadline = setting.holdMillis?.let { Deadline.At(clock() + it) } ?: Deadline.Forever
    }

    /** The take ended, by any path. Idempotent; an id that is not the active take changes nothing. */
    fun takeEnded(takeId: String) {
        if (takeId != activeTake) return
        activeTake = null
        val earned = takeDeadline
        takeDeadline = null
        when {
            setting == SpeechModelUnload.IMMEDIATELY -> release("Immediately")
            registration == null -> deadline = null
            earned != null -> apply(earned)
            deadline != null -> apply(checkNotNull(deadline))
            else -> release("no transcript")
        }
    }

    /** The user changed the setting; applies at once to whatever is held. */
    fun settingChanged(value: SpeechModelUnload) {
        if (value == setting) return
        setting = value
        // A take whose transcript already arrived ends on the new choice, counted from now.
        if (activeTake != null && takeDeadline != null) {
            takeDeadline = value.holdMillis?.let { Deadline.At(clock() + it) } ?: Deadline.Forever
        }
        // A running take keeps its own binding, so the hold can go at once.
        if (value == SpeechModelUnload.IMMEDIATELY) return release("Immediately")
        if (registration == null) return
        val target = value.holdMillis?.let { Deadline.At(clock() + it) } ?: Deadline.Forever
        cancelTimer()
        // An earlier hold suspended by a running take takes the new choice too, still without a timer.
        if (activeTake == null) apply(target) else if (deadline != null) deadline = target
    }

    /** Android says the process is about to go; let the model go with it. A running take keeps its own binding. */
    fun memoryCritical() {
        release("low memory")
    }

    private fun apply(target: Deadline) {
        cancelTimer()
        deadline = target
        if (target !is Deadline.At) {
            log("Speech model kept loaded: ${setting.storageKey}")
            return
        }
        val remaining = target.elapsedMs - clock()
        if (remaining <= 0) return release("timer")
        log("Speech model kept loaded: ${setting.storageKey}")
        val fire = Runnable { if (deadline == target && activeTake == null) release("timer") }
        timer = fire
        timers.schedule(remaining, fire)
    }

    private fun release(reason: String) {
        cancelTimer()
        deadline = null
        generation++
        val held = registration ?: return
        registration = null
        runCatching { held.unbind() }
        log("Speech model released: $reason")
    }

    private fun cancelTimer() {
        timer?.let(timers::cancel)
        timer = null
    }

    companion object {
        private const val TAG = "SpeechModelKeepAlive"

        /** The production wiring: binds `AsrService` from the application context on the main looper. */
        fun create(context: Context): SpeechModelKeepAlive {
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            val port = object : Port {
                override fun bind(onConnected: () -> Unit, onGone: () -> Unit): Registration? {
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = onConnected()
                        override fun onServiceDisconnected(name: ComponentName?) = onGone()
                        override fun onBindingDied(name: ComponentName?) = onGone()
                        override fun onNullBinding(name: ComponentName?) = onGone()
                    }
                    val flags = Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY
                    val bound = runCatching { app.bindService(Intent(app, AsrService::class.java), connection, flags) }
                        .getOrDefault(false)
                    if (!bound) {
                        // Android asks for an unbind even when the bind was refused or threw.
                        runCatching { app.unbindService(connection) }
                        return null
                    }
                    return Registration { app.unbindService(connection) }
                }
            }
            val timers = object : Timers {
                override fun schedule(delayMs: Long, action: Runnable) { handler.postDelayed(action, delayMs) }
                override fun cancel(action: Runnable) { handler.removeCallbacks(action) }
            }
            return SpeechModelKeepAlive(port, SystemClock::elapsedRealtime, timers) { DebugLogger.log(TAG, it) }
        }
    }
}
