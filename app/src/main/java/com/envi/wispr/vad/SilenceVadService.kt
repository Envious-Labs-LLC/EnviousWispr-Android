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
import java.util.concurrent.locks.LockSupport

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

    /**
     * The receiver-side answer to stale callers, and the reason this class exists rather than another
     * check at another call site.
     *
     * Two feeders from different takes can be alive at once after an immediate restart, and AIDL only
     * orders calls made from the SAME client thread, so their calls can arrive in either order. Guarding
     * each site the old caller might reach is a description of a population. Refusing anything older
     * than the newest take is the population.
     */
    private val tokenOrder = CaptureTokenOrder()

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SilenceVadWatchdog").apply { isDaemon = true }
        }


    private val binder = object : ISilenceVadService.Stub() {

        // The lock is OUTSIDE the deadline, not inside it. A call that waits for the lock must not have
        // armed a watchdog while it waited, or it can kill this process while a newer take owns the lock.
        override fun start(captureToken: Long, pauseSeconds: Float): Int = synchronized(lock) {
            if (!tokenOrder.accept(captureToken)) {
                DebugLogger.warn(TAG, "Rejected a start from an older take, token $captureToken")
                return@synchronized STATUS_UNAVAILABLE
            }
            guarded(STATUS_UNAVAILABLE) {
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

        override fun processBlock(captureToken: Long, pcm16: ByteArray?): Int = synchronized(lock) {
            // A token that is not the active one belongs to a take that already ended. Its result is
            // discarded rather than applied to whatever is recording now.
            if (captureToken != activeToken || unavailable) return@synchronized RESULT_UNAVAILABLE
            // A short, oversized or missing block is a broken contract, not audio. Scoring it would be
            // scoring something the microphone never produced.
            if (pcm16 == null || pcm16.size != PCM_BYTES_PER_BLOCK) {
                unavailable = true
                return@synchronized RESULT_UNAVAILABLE
            }
            val detector = session ?: return@synchronized RESULT_UNAVAILABLE
            guarded(RESULT_UNAVAILABLE) {
                if (detector.processBlock(pcm16)) RESULT_SILENCE else RESULT_CONTINUE
            }
        }

        override fun finish(captureToken: Long) = synchronized(lock) {
            if (captureToken != activeToken) return@synchronized
            guarded(Unit) { releaseLocked() }
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
                if (active.compareAndSet(true, false)) terminateDetectorProcess()
            },
            CALL_DEADLINE_MS,
            TimeUnit.MILLISECONDS,
        )
        return try {
            val result = try {
                block()
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Detector call failed", e)
                onDeadline
            }

            // The deadline may have won while this call was running. If it did, this transaction must
            // NOT return: a successful-looking return would let a later take start work inside a process
            // that is already scheduled to die.
            if (!active.compareAndSet(true, false)) terminateDetectorProcess()
            result
        } finally {
            armed.cancel(false)
        }
    }

    /**
     * Decide this process must die, and never return from the transaction that decided it.
     *
     * Returning would release the lock and let a newer take start work inside a process that is already
     * scheduled to end. The park is only for the interval between the signal and the process actually
     * going away.
     */
    private fun terminateDetectorProcess(): Nothing {
        DebugLogger.error(
            TAG,
            "Detector call exceeded ${CALL_DEADLINE_MS}ms; terminating the detector process",
        )
        Process.killProcess(Process.myPid())
        while (true) {
            LockSupport.park()
            Thread.interrupted()
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

/**
 * A total order over capture tokens, so the detector can refuse work from a take that is over.
 *
 * Held only while `SilenceVadService`'s lock is held, which is why it needs no synchronisation of its
 * own. Separate from the service so the ordering rule can be tested without an Android runtime.
 */
internal class CaptureTokenOrder {

    private var newest = 0L

    /** @return true only for a token strictly newer than every token accepted before it. */
    fun accept(token: Long): Boolean {
        if (token <= newest) return false
        newest = token
        return true
    }
}
