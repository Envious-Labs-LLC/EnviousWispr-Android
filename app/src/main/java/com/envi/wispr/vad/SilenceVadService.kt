package com.envi.wispr.vad

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the silence detector, alone, in the `:vad` process.
 *
 * **This process exists to be killable.** sherpa-onnx calls `exit(-1)` on a model or input contract
 * violation, 21 times in its VAD loader alone, and that is not catchable from Kotlin. A detector sharing
 * a process with audio capture could therefore take a live recording down with it. Here its death is
 * ordinary binder death, which the caller already handles by turning auto-stop off for that take and
 * carrying on recording.
 *
 * It holds no capture file, no file path and no `AudioRecord`. It receives copied PCM and returns an
 * integer, which is why a wrong answer cannot touch what was recorded.
 */
class SilenceVadService : Service() {

    private val lock = Any()

    private var session: SileroVadSession? = null
    private var activeToken: Long = NO_TOKEN
    private var unavailable = false

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SilenceVadWatchdog").apply { isDaemon = true }
        }


    private val binder = object : ISilenceVadService.Stub() {

        override fun start(captureToken: Long, pauseSeconds: Float): Int = guarded(STATUS_UNAVAILABLE) {
            synchronized(lock) {
                releaseLocked()
                unavailable = false
                activeToken = captureToken
                val opened = SileroVadSession.open(assets, pauseSeconds)
                if (opened == null) {
                    unavailable = true
                    DebugLogger.warn(TAG, "Detector unavailable for token $captureToken")
                    return@guarded STATUS_UNAVAILABLE
                }
                session = opened
                DebugLogger.log(
                    TAG,
                    "Detector ready (PID: ${Process.myPid()}, token: $captureToken, pause: ${pauseSeconds}s)",
                )
                STATUS_READY
            }
        }

        override fun processBlock(captureToken: Long, pcm16: ByteArray?): Int =
            guarded(RESULT_UNAVAILABLE) {
                synchronized(lock) {
                    // A token that is not the active one belongs to a take that already ended. Its
                    // result is discarded rather than applied to whatever is recording now.
                    if (captureToken != activeToken || unavailable) return@guarded RESULT_UNAVAILABLE
                    // A short, oversized or missing block is a broken contract, not audio. Scoring it
                    // would be scoring something the microphone never produced.
                    if (pcm16 == null || pcm16.size != PCM_BYTES_PER_BLOCK) {
                        unavailable = true
                        return@guarded RESULT_UNAVAILABLE
                    }
                    val detector = session ?: return@guarded RESULT_UNAVAILABLE
                    if (detector.processBlock(pcm16)) RESULT_SILENCE else RESULT_CONTINUE
                }
            }

        override fun finish(captureToken: Long) {
            guarded(Unit) {
                synchronized(lock) {
                    if (captureToken != activeToken) return@guarded Unit
                    releaseLocked()
                    Unit
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) { releaseLocked() }
        watchdog.shutdownNow()
        super.onDestroy()
    }

    private fun releaseLocked() {
        session?.release()
        session = null
        activeToken = NO_TOKEN
        unavailable = false
    }

    /**
     * Run one detector call under a hard deadline, and kill this process if it overruns.
     *
     * An AIDL call is synchronous and has no timeout of its own, so a stalled one would block the
     * caller's feeder thread forever. An abandoned thread and binder call is not an acceptable resting
     * state: the standard for idle cost here is zero, not small. Killing only this process converts an
     * unbounded call into binder death, which the caller already knows how to survive.
     *
     * The deadline matches the caller's ring capacity: past that point the detector cannot catch up
     * without a gap, and a gap breaks the model's recurrent continuity anyway.
     */
    private fun <T> guarded(onDeadline: T, block: () -> T): T {
        // One flag per call, owned by that call. A shared counter would let a later call disarm an
        // earlier stalled one, which is exactly the situation the deadline exists for.
        val active = AtomicBoolean(true)
        val armed = watchdog.schedule(
            {
                if (active.compareAndSet(true, false)) {
                    DebugLogger.error(
                        TAG,
                        "Detector call exceeded ${CALL_DEADLINE_MS}ms; terminating the detector process",
                    )
                    Process.killProcess(Process.myPid())
                }
            },
            CALL_DEADLINE_MS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block()
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Detector call failed", e)
            synchronized(lock) { unavailable = true }
            onDeadline
        } finally {
            active.set(false)
            armed.cancel(false)
        }
    }

    companion object {
        private const val TAG = "SilenceVad"
        private const val NO_TOKEN = 0L

        /** Matches the caller's eight-slot ring: 8 blocks of 256 ms is 2.048 seconds. */
        const val CALL_DEADLINE_MS = 2_000L

        const val STATUS_READY = 2
        const val STATUS_UNAVAILABLE = 3

        const val RESULT_CONTINUE = 0
        const val RESULT_SILENCE = 1
        const val RESULT_UNAVAILABLE = 2

        /** 4096 samples of 16 kHz mono PCM16. Anything else is not one of our blocks. */
        const val PCM_BYTES_PER_BLOCK = 8_192
    }
}
