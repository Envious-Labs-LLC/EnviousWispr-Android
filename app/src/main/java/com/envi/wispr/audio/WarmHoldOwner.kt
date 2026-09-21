package com.envi.wispr.audio

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.envi.wispr.debug.DebugLogger

/**
 * The warm hold BETWEEN takes, as one owner on the service (#188): the `WarmHold` state machine plus the
 * expiry, the two platform listeners and the identity of the sink it keeps warm (type and product name,
 * never id, which changes between reads on this phone). Written under the service's session lock, which
 * comes in as [locked]; the three `warmHold === hold` checks are the hold's own identity and stay here.
 *
 * The one decision that crosses ownership, service lifetime, is [onIdle] (the service's `stopSelf` when
 * no session is open) and [keepAlive] (the service's `startService` so a hold outlives the owner's
 * unbind). The platform edges ([addCommListener], [removeCommListener], [registerDeviceCallback],
 * [unregisterDeviceCallback], [scheduler], [newTrack]) are injected so a JVM test can count what [close]
 * removes.
 */
internal class WarmHoldOwner(
    private val tag: String,
    private val scheduler: RouteScheduler,
    private val locked: (() -> Unit) -> Unit,
    private val addCommListener: (AudioManager.OnCommunicationDeviceChangedListener) -> Unit,
    private val removeCommListener: (AudioManager.OnCommunicationDeviceChangedListener) -> Unit,
    private val registerDeviceCallback: (AudioDeviceCallback) -> Unit,
    private val unregisterDeviceCallback: (AudioDeviceCallback) -> Unit,
    private val newTrack: () -> WarmHold.SilentTrack = { AudioTrackSilence() },
    private val keepAlive: () -> Unit,
    private val onIdle: () -> Unit,
) {
    /**
     * The warm hold between takes, or null. Written under the session lock. The sink it holds is
     * identified by type and product name (never by id, which changes between reads on this phone).
     */
    @Volatile private var warmHold: WarmHold? = null
    @Volatile private var heldSinkType: Int = -1
    @Volatile private var heldSinkName: String = ""
    @Volatile private var holdExpiry: Runnable? = null
    private var holdCommListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var holdDeviceCallback: AudioDeviceCallback? = null

    /** A hold is playing and owns the route. */
    val isActive: Boolean get() = warmHold?.isActive == true

    /**
     * Under the session lock. A warm hold hands its route to the next take (the link stays up; V13:
     * live at ~120 ms). The identity is read BEFORE the handover: handOver ends the hold, and the hold's
     * end clears its bookkeeping synchronously (Codex review 1).
     */
    fun handOver(): HandedRoute? = warmHold?.let { hold ->
        val type = heldSinkType
        val name = heldSinkName
        hold.handOver()?.let { HandedRoute(it, type, name) }
    }

    /** Under the session lock. */
    fun eligible(route: TakeRoute, ending: CaptureEnding, keepEarbudsReady: Boolean, destroyed: Boolean): Boolean {
        if (destroyed) return false
        if (!keepEarbudsReady || !route.targetBluetooth || route.sink == null) return false
        if (route.hold.isReleased) return false
        if (route.effective.currentKind != InputRouteKind.BLUETOOTH) return false
        // Exhaustive, no else: a new ending decides here whether it keeps the earbuds warm.
        return when (ending) {
            CaptureEnding.Manual, CaptureEnding.Silence, CaptureEnding.MaxDuration -> true
            CaptureEnding.StillRunning, CaptureEnding.Failure -> false
        }
    }

    /** Under the session lock. True when the hold is playing and now owns the route. */
    fun start(route: TakeRoute): Boolean {
        val sink = route.sink ?: return false
        val label = route.effective.label()
        val hold = WarmHold(
            route = route.hold,
            track = newTrack(),
            onEnded = { reason -> onHoldEnded(reason, label) },
        )
        heldSinkType = sink.type
        heldSinkName = sink.productName?.toString().orEmpty()
        warmHold = hold
        if (!hold.start()) {
            clearHoldBookkeeping()
            return false
        }
        val expiry = Runnable { locked { if (warmHold === hold) hold.end(WarmHold.END_EXPIRED) } }
        holdExpiry = expiry
        scheduler.postDelayed(expiry, WarmHold.HOLD_MS)
        val commListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            val ours = device != null && device.type == heldSinkType && device.productName?.toString().orEmpty() == heldSinkName
            if (!ours) locked { if (warmHold === hold) hold.end(WarmHold.END_DEVICE_CHANGED) }
        }
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                val gone = removed.any { it.type == heldSinkType && it.productName?.toString().orEmpty() == heldSinkName }
                if (gone) locked { if (warmHold === hold) hold.end(WarmHold.END_DEVICE_REMOVED) }
            }
        }
        holdCommListener = commListener
        holdDeviceCallback = deviceCallback
        runCatching { addCommListener(commListener) }
            .onFailure { DebugLogger.warn(tag, "hold listener not registered: ${it.message}") }
        runCatching { registerDeviceCallback(deviceCallback) }
            .onFailure { DebugLogger.warn(tag, "hold device callback not registered: ${it.message}") }
        DebugLogger.log(tag, "route hold start=$label ms=${WarmHold.HOLD_MS}")
        return true
    }

    /** Runs inside `WarmHold.end` or `handOver`, under the session lock. */
    private fun onHoldEnded(reason: String, label: String) {
        DebugLogger.log(tag, "route hold end=$reason device=$label")
        clearHoldBookkeeping()
        // A new take keeps the service; every other end lets it go once no session is open.
        if (reason != WarmHold.END_NEW_TAKE) onIdle()
    }

    /** Under the session lock. Forgets the hold's listeners and identity; the hold object itself is done. */
    private fun clearHoldBookkeeping() {
        holdExpiry?.let { runCatching { scheduler.removeCallbacks(it) } }
        holdExpiry = null
        holdCommListener?.let { l -> runCatching { removeCommListener(l) } }
        holdCommListener = null
        holdDeviceCallback?.let { c -> runCatching { unregisterDeviceCallback(c) } }
        holdDeviceCallback = null
        warmHold = null
        heldSinkType = -1
        heldSinkName = ""
    }

    /**
     * The session owner is done with this take. When a hold is running the service gives itself a
     * started lifetime, so the owner's unbind does not destroy it; the hold's end stops it. False means
     * "nothing to keep", and the owner stops the service as it always did. Under the session lock.
     */
    fun finishTake(): Boolean {
        val hold = warmHold ?: return false
        if (!hold.isActive) return false
        return runCatching {
            keepAlive()
            true
        }.getOrElse { e ->
            DebugLogger.warn(tag, "hold could not keep the service: ${e.message}")
            hold.end(WarmHold.END_TRACK_FAILED)
            false
        }
    }

    /**
     * End the hold for [reason], under the session lock. Idempotent: a hold that already ended, or no
     * hold at all, does nothing (`WarmHold.end` guards on its own compare-and-set, and the bookkeeping is
     * cleared by `onHoldEnded`). The service calls it from `onDestroy`, twice around its join, as before.
     */
    fun close(reason: String) {
        warmHold?.end(reason)
    }

    /**
     * The platform half of the hold: a silent `VOICE_COMMUNICATION` stream, which is what Android keys
     * the communication route on (any active playback for the uid). Its own thread paces on the blocking
     * write; `stop()` unblocks it.
     */
    private class AudioTrackSilence : WarmHold.SilentTrack {
        private var track: AudioTrack? = null
        private var thread: Thread? = null
        @Volatile private var stopped = false

        override fun play() {
            val rate = PcmAudio.SAMPLE_RATE
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, rate * PcmAudio.BYTES_PER_SAMPLE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                built.release()
                throw IllegalStateException("silent track not initialized")
            }
            track = built
            built.play()
            val zeros = ByteArray(rate / 10 * PcmAudio.BYTES_PER_SAMPLE)
            thread = Thread({
                while (!stopped) {
                    val n = built.write(zeros, 0, zeros.size)
                    if (n < 0) break
                }
            }, "WarmHoldSilence").apply { start() }
        }

        override fun stop() {
            stopped = true
            track?.let { t ->
                runCatching { t.stop() }
                runCatching { t.release() }
            }
            track = null
        }
    }
}
