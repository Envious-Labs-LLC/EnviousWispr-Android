package com.envi.wispr.ui

import android.os.DeadObjectException
import com.envi.wispr.audio.LiveGate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A fact the capture side reports to the session owner (#216), always on the main thread and only for the
 * take the controller was begun with. Facts only: what the owner does with each one is the owner's
 * decision, in its exhaustive `when`.
 */
internal sealed interface CaptureEvent {
    /** The capture process published live: the route is ready (or forced). */
    data class Live(val forced: Boolean, val routeKind: Int, val routeReason: Int, val liveAfterMs: Long) : CaptureEvent

    /** A heartbeat from the capture loop. */
    data class Tick(val elapsedMs: Long) : CaptureEvent

    /** The silence detector's status for this take. */
    data class SilenceStatus(val status: Int) : CaptureEvent

    /** The take's FIRST ending: the capture side is over and the file is closed (#115). */
    data class Ended(val ending: TakeEnding) : CaptureEvent

    /** The capture process published nothing for [CaptureSessionController.TAKE_SILENT_BOUND_MS]. */
    data object Silent : CaptureEvent

    /** The STARTING bound passed with no live event. */
    data object LiveDeadlinePassed : CaptureEvent

    /** The start call threw; [processDied] when the capture process died during it (#213). */
    data class StartFailed(val processDied: Boolean) : CaptureEvent
}

/**
 * What the start's lane may READ of the session (#216): the owner's answers, never its state. Exactly the
 * two checks the lane made before and after `startCaptureForTake`.
 */
internal interface TakePhaseView {
    /** [takeId] is the current take's id AND the take is still STARTING. */
    fun isStarting(takeId: String): Boolean

    /** [takeId] is NOT the current take's id, OR the take is IDLE, FINISHING or ERROR. */
    fun hasEnded(takeId: String): Boolean
}

/**
 * The session owner's line to the capture process (#216): the command lane, the take listener, the
 * silence bound and the live deadline. It talks to `:audio` and reports what it hears as [CaptureEvent]s;
 * it never decides how a take ends and cannot see the session's state.
 *
 * Since #115 the owner never blocks and never polls: every call into `:audio` runs on this lane (one
 * daemon thread per instance, issue order), and a 3 s silence bound re-armed by every pushed event
 * reports a capture process that stopped answering. Both timers run on the main thread through the
 * owner's [SessionHost], and their events are delivered inline in the timer's own task.
 */
internal class CaptureSessionController(
    private val host: SessionHost,
    private val surface: RecorderSurface,
    private val log: SessionLog,
    private val pipeline: PipelineController,
    /** Always invoked on the main thread. */
    private val events: (CaptureEvent) -> Unit,
) {
    companion object {
        /**
         * How long the capture process may stay SILENT before the take treats it as unresponsive (#115).
         * The capture loop heartbeats once a second from its first read; three missed beats is a process
         * that is frozen or wedged, not slow (the emulator's measured gaps are recorded in the #115 plan).
         * Armed before the take's listener is registered and disarmed when the binding is released, so it
         * also covers a command outstanding after the ending; no timer exists outside a take.
         */
        const val TAKE_SILENT_BOUND_MS = 3_000L

        /**
         * The STARTING bound: two live deadlines (one reset) plus a second, after which a take that never
         * went live fails rather than spins. A main-thread timer since #115; the live waiter thread is gone.
         */
        val LIVE_WAIT_BOUND_MS = 2 * LiveGate.DEADLINE_MS + 1_000L
    }

    /** The begun take's id; an event carrying any other id is discarded. */
    @Volatile private var takeId = ""
    @Volatile private var phase: TakePhaseView? = null

    /** Set once the ending has been claimed for this take, so a duplicate or a late event changes nothing. */
    private val endingConsumed = AtomicBoolean(false)
    /** Whether the silence bound is armed; the runnable is re-posted on every event from the capture process. */
    private val silenceBoundArmed = AtomicBoolean(false)
    private val silenceBound = Runnable { onCaptureSilent() }
    private val liveDeadline = Runnable { events(CaptureEvent.LiveDeadlinePassed) }

    /**
     * Every synchronous call INTO the capture process runs here, one at a time, in the order issued, and
     * never on the main thread (#115 review round 1, F1): a call into a process that has stopped answering
     * has no timeout, and on main it would park the very thread the silence bound fires on. One lane, not
     * the shared scope, so a stop can never overtake the start it belongs to. A lane parked in a wedged
     * process stays parked; the instance ends through the bound and the next take has its own lane.
     */
    private val captureCommands: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CaptureCommands").apply { isDaemon = true }
    }

    /** Whether the capture process is bound; a cancel before it was bound has nothing to stop. */
    val isBound: Boolean get() = pipeline.capture != null

    /** Whether this take's ending has been claimed; read on main by the owner's cancel. */
    val endingArrived: Boolean get() = endingConsumed.get()

    /** Main thread, at admission: the take whose events this controller reports. */
    fun begin(takeId: String, phase: TakePhaseView) {
        this.takeId = takeId
        this.phase = phase
        endingConsumed.set(false)
    }

    /**
     * Main thread, from the owner's STARTING take. The bound is armed and the listener registered BEFORE
     * the start command (#115): a start that never returns, a registration that hangs, and an event that
     * precedes registration are all covered. Returns whether the start was issued on the lane.
     *
     * [preferences] is the take's frozen snapshot, never the live source: a settings emission after the
     * take's answer belongs to the next take (#193).
     */
    fun start(preferences: SessionPreferences): Boolean {
        armSilenceBound()
        host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)
        val id = takeId
        val phase = checkNotNull(phase) { "start before begin" }
        return command("start") { capture ->
            capture.listenForTake(takeListener)
            // A registration that wedged and then returned: the silence bound may already have
            // ended this take and released the binding, and a start now would record for nobody
            // (review round 2, F1). Checked on the lane, before the start and again after it.
            if (!phase.isStarting(id)) {
                // Named, so the one outcome of this check that leaves no other trace can be read (#210).
                log.warn("Capture start skipped: the take is no longer starting")
                return@command
            }
            val started = try {
                capture.startCaptureForTake(
                    preferences.autoStopOnSilence,
                    preferences.silencePauseSeconds,
                    preferences.inputDevicePick,
                    preferences.keepEarbudsReady,
                    id,
                )
            } catch (error: Exception) {
                // A binder that threw: the process is gone or broken. Its ServiceConnection or the
                // silence bound would end the take too; this is the same ending, sooner. A capture process
                // that DIED during the start (it ends itself to recover a recorder that was never
                // released, #213) is the same ending onCaptureDisconnected proposes, whichever arrives
                // first; any other throw stays a start exception.
                log.error("Failed to start recording", error)
                val processDied = error is DeadObjectException
                host.postToMain { events(CaptureEvent.StartFailed(processDied)) }
                return@command
            }
            if (!started) {
                // Every refused start publishes its own ending with the failure code (the #115 plan's
                // table, one publisher per exit). Nothing is read here.
                log.warn("Capture start refused; the ending event carries why")
            } else if (phase.hasEnded(id)) {
                // The take ENDED while the start was in flight: no owner is listening for it. Only the
                // ended states count: the pushed live event routinely lands on main before this call
                // returns (live after 1 ms on the emulator), so RECORDING here is the ordinary case,
                // and PROCESSING or CANCELLING already sent their own stop (found by the hosted runner,
                // which stopped a healthy take here and cancelled it as CANCELLED_PROCESSING).
                log.warn("Capture started for a take that already ended; stopping it")
                runCatching { capture.stopCapture() }
                pipeline.stopAudioService()
            }
        }
    }

    /**
     * Issue one call into the capture process on [captureCommands]; returns whether it was issued (the
     * binding is present and the lane accepts work). The link is read HERE, on the calling thread, and the
     * lane keeps that link: a stop queued at destroy still reaches the process after the unbind. The
     * block's own failure is logged and costs nothing else: the take's ending comes from the process's
     * events or from the silence bound, never from here.
     */
    fun command(what: String, block: (CaptureLink) -> Unit): Boolean {
        val capture = pipeline.capture ?: return false
        return runCatching {
            captureCommands.execute {
                runCatching { block(capture) }.onFailure { log.warn("Capture command failed ($what): ${it.javaClass.simpleName}") }
            }
        }.onFailure { log.warn("Capture command not issued ($what): ${it.javaClass.simpleName}") }.isSuccess
    }

    /** Tell the capture process to stop the take, on the lane. */
    fun stop(what: String): Boolean = command(what) { it.stopCapture() }

    /**
     * The recorder's live picture, PUSHED by the audio process for exactly as long as the take is open.
     *
     * The audio process publishes each picture as its analyser finishes it (#187); until then this
     * owner asked for it thirty times a second over a synchronous binder call, which the audio process
     * answered under a lock with a copy. The picture is a limb and this is not the polling tick, so a
     * failed registration can delay nothing the take depends on (`architecture-rules.md` RULE:
     * isolate-limbs). It is also the ONLY subscriber in the app: the recorder is handed finished numbers,
     * never a service to reach for, so a second surface cannot become a second reader (RULE: no-idle-cost).
     *
     * Take identity is the snapshot's serial, captured here after `show()` stamped it and stamped on every
     * picture; `updateBands` refuses a picture whose serial is not the visible take's, under its own lock.
     * A picture that never arrives leaves the rail holding its last shape until the pill hides; a capture
     * process that also stops publishing take events ends the take through the silence bound (#115). The
     * registration dies with the binding.
     *
     * Failing to register costs the picture only: the take carries on.
     */
    fun listenForPicture() {
        // The serial is read HERE, on main, right after show() stamped it; only the binder call moves to
        // the lane (#115 review round 1, F1).
        val takeSerial = surface.currentTakeSerial()
        command("listen for the picture") { capture ->
            capture.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
        }
    }

    /**
     * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
     * lifetime and the unbind must not stop it), otherwise stop it as before. Issued on the lane and never
     * awaited (#115 review round 1, F3): a process that answered its ending and then wedged must not hold
     * the transcription; a call that fails or is never issued stops the service by intent.
     */
    fun finishTakeOrStop() {
        val issued = command("finishTake") { capture ->
            val held = runCatching { capture.finishTake() }.getOrDefault(false)
            if (!held) pipeline.stopAudioService()
        }
        if (!issued) pipeline.stopAudioService()
    }

    /** Main thread, at live: the STARTING bound is no longer needed. */
    fun cancelLiveDeadline() {
        host.cancelMainDelayed(liveDeadline)
    }

    /**
     * Main thread, when the take's binding is released and only then (a command may still be outstanding).
     * On an ordinary ending the bound is still pending (the process answered every second and the take
     * ended by its own ending), so the cancel is the ordinary path, not a defence; both runnables are
     * cancelled because neither may outlive the binding they watch.
     */
    fun disarm() {
        silenceBoundArmed.set(false)
        host.cancelMainDelayed(silenceBound)
        host.cancelMainDelayed(liveDeadline)
    }

    /** No further command is accepted; one already queued still runs on the lane's own thread. */
    fun shutdown() {
        captureCommands.shutdown()
    }

    /**
     * The take's events, each posted to the main thread and handled there in delivery order (#115). An
     * event from another take (the previous take's ending, queued in the service-scoped publisher before
     * this owner registered) is discarded before it can re-arm the bound or touch any state. The ending
     * is claimed HERE, on main, after the re-arm: a duplicate re-arms the bound and reports nothing, and
     * the owner's cancel cannot see an ending the owner has not begun to handle.
     */
    private val takeListener = object : TakeListener {
        private fun ours(eventTakeId: String): Boolean = eventTakeId == takeId

        override fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); events(CaptureEvent.Live(forced, routeKind, routeReason, liveAfterMs)) } }
        }

        override fun onTick(takeId: String, elapsedMs: Long) {
            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); events(CaptureEvent.Tick(elapsedMs)) } }
        }

        override fun onSilenceStatus(takeId: String, status: Int) {
            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); events(CaptureEvent.SilenceStatus(status)) } }
        }

        override fun onEnded(ending: TakeEnding) {
            host.postToMain {
                if (ours(ending.takeId)) {
                    rearmSilenceBound()
                    if (endingConsumed.compareAndSet(false, true)) events(CaptureEvent.Ended(ending))
                }
            }
        }
    }

    private fun armSilenceBound() {
        if (silenceBoundArmed.compareAndSet(false, true)) host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
    }

    /** Main thread. Every event from the capture process pushes the bound out; nothing else does. */
    private fun rearmSilenceBound() {
        if (!silenceBoundArmed.get()) return
        host.cancelMainDelayed(silenceBound)
        host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
    }

    /**
     * Main thread. The capture process published nothing for [TAKE_SILENT_BOUND_MS]: frozen, wedged or gone
     * without its ServiceConnection noticing. Reported once; the owner decides what it ends.
     */
    private fun onCaptureSilent() {
        if (!silenceBoundArmed.compareAndSet(true, false)) return
        log.error("Capture process silent for ${TAKE_SILENT_BOUND_MS}ms; ending the take")
        events(CaptureEvent.Silent)
    }
}
