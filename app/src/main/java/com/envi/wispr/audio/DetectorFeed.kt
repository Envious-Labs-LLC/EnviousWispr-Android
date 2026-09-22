package com.envi.wispr.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.vad.ISilenceVadService
import com.envi.wispr.vad.SilenceVadService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The silence detector's feed for ONE take: the ring the capture thread stages whole blocks into, the
 * binding to the detector process, the one thread allowed to talk to it, and the status the binder reads.
 *
 * Owned by the take's `CaptureSession`, started after capture is up, closed exactly once by the service
 * (#188). Everything here belongs to the take that started it, so a feeder or a connection callback
 * belonging to a finished take cannot set the status of, or unbind the detector of, the take running now:
 * the service's identity checks come in as [isCurrent] and [stillLive], and the one decision that
 * crosses ownership, ending the take on silence, as [endOnSilence].
 *
 * [bind], [unbind] and [interrupt] are the platform edges, injected so a JVM test can count that [close]
 * runs each once. Null [ring] means auto-stop is off: no ring, no feeder, no detector process.
 */
internal class DetectorFeed(
    private val tag: String,
    autoStop: Boolean,
    private val bind: (ServiceConnection) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val interrupt: (Thread) -> Unit = { it.interrupt() },
    /** Fired with the new code at each of the three status writes, for the take-event push (#115). */
    private val onStatus: (Int) -> Unit = {},
) {
    companion object {
        /**
         * The silence detector's block: 4096 samples, 256 ms at 16 kHz, macOS's detector chunk and what
         * the silence state machine ticks on. Reads are staged into whole blocks; the detector never
         * sees a partial one.
         */
        const val READ_BLOCK_BYTES = 8_192

        /**
         * Eight blocks, 2.048 seconds of audio, and the detector's own call deadline is set against it.
         * It is the client-owned deadline for the one binding failure Android gives no signal for: a
         * bind that succeeds and then never connects.
         */
        const val RING_BLOCKS = 8

        /** How long the feeder waits when there is nothing to do. It is not the capture thread. */
        const val FEEDER_IDLE_MS = 20L

        /** The production [bind]: the detector process, bound with auto-create. */
        fun bindingThrough(context: Context): (ServiceConnection) -> Boolean = { connection ->
            context.bindService(Intent(context, SilenceVadService::class.java), connection, Context.BIND_AUTO_CREATE)
        }

        /** The production [unbind]. */
        fun unbindingThrough(context: Context): (ServiceConnection) -> Unit = { connection -> context.unbindService(connection) }
    }

    /** Null when the user has auto-stop off: no ring, no feeder, no detector process. */
    private val ring: BlockRing? = if (autoStop) BlockRing(RING_BLOCKS, READ_BLOCK_BYTES) else null

    /** Staging for a read that did not land on a block boundary. Preallocated, like everything else. */
    private val pendingBlock: ByteArray? = if (autoStop) ByteArray(READ_BLOCK_BYTES) else null

    /** Capture thread only. */
    private var pendingBytes: Int = 0

    /** Capture thread only. The take position of the first byte staged in [pendingBlock]. */
    private var pendingPosition: Long = 0L

    private val detectorAbandoned = AtomicBoolean(false)
    private val silenceStatus = AtomicInteger(
        if (ring == null) AudioCaptureService.SILENCE_STATUS_DISABLED else AudioCaptureService.SILENCE_STATUS_PREPARING,
    )
    @Volatile private var vadService: ISilenceVadService? = null
    /** Exactly one unbind per bind: the feeder's exit and a teardown close can both reach [unbindVad]. */
    private val vadBound = AtomicBoolean(false)
    @Volatile private var feederThread: Thread? = null
    @Volatile private var vadConnection: ServiceConnection? = null
    private val closed = AtomicBoolean(false)

    /** The detector's status for the binder, one of the `SILENCE_STATUS_*` codes. */
    val status: Int get() = silenceStatus.get()

    /** The caller asked for auto-stop and cannot have it (an out-of-range pause): the notice's state. */
    fun markRequestedButRefused() {
        silenceStatus.set(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
        onStatus(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
    }

    /**
     * Capture thread only. Copies audio toward the detector and never waits for it.
     *
     * A read that does not land on a block boundary is staged, so the detector always sees whole 256 ms
     * blocks in order. **Nothing here logs, allocates, locks or calls across a process.**
     *
     * A full ring means the detector has fallen further behind than it can recover from. Auto-stop is
     * abandoned for the rest of the take and never resumed: resuming across dropped audio breaks the
     * model's recurrent continuity, and speech that resumed inside the gap could then read as silence.
     */
    fun offer(buffer: ByteArray, bytesRead: Int, position: Long) {
        val ring = ring ?: return
        val pending = pendingBlock ?: return
        if (detectorAbandoned.get()) return

        var consumed = 0
        while (consumed < bytesRead) {
            if (pendingBytes == 0) pendingPosition = position + consumed
            val room = READ_BLOCK_BYTES - pendingBytes
            val take = minOf(room, bytesRead - consumed)
            System.arraycopy(buffer, consumed, pending, pendingBytes, take)
            pendingBytes += take
            consumed += take
            if (pendingBytes == READ_BLOCK_BYTES) {
                pendingBytes = 0
                if (!ring.offer(pending, READ_BLOCK_BYTES, pendingPosition)) {
                    // Flag only. The feeder notices and does the logging, off this thread.
                    abandon()
                    return
                }
            }
        }
    }

    /**
     * Every way the binding can fail, and they all mean the same thing to a take: auto-stop is off for
     * it, and recording continues.
     *
     * The connection is built PER TAKE and captures this feed. A callback that arrives after its take
     * ended can then do nothing at all, rather than clearing the status or unbinding the detector of
     * whatever is recording now. `onNullBinding` and `onBindingDied` unbind explicitly, because Android
     * reconnects a disconnected binding on its own.
     */
    private fun connectionFor(isCurrent: () -> Boolean) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!isCurrent()) {
                unbindVad()
                return
            }
            vadService = ISilenceVadService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vadService = null
            abandon()
        }

        override fun onNullBinding(name: ComponentName?) {
            vadService = null
            abandon()
            unbindVad()
        }

        override fun onBindingDied(name: ComponentName?) {
            vadService = null
            abandon()
            unbindVad()
        }
    }

    /**
     * Bind the detector process for one take and start the one thread allowed to talk to it.
     *
     * Capture has already started by the time this runs, so a slow or failed detector delays nothing. A
     * take whose detector never becomes ready is simply a take the user stops by hand.
     */
    fun start(
        pauseSeconds: Float,
        token: Long,
        takeId: String,
        isCurrent: () -> Boolean,
        stillLive: () -> Boolean,
        endOnSilence: () -> Unit,
    ) {
        val connection = connectionFor(isCurrent)
        vadConnection = connection

        val bound = runCatching { bind(connection) }.getOrDefault(false)
        vadBound.set(bound)

        if (!bound) {
            vadConnection = null
            abandon()
            DebugLogger.warn(tag, "Auto-stop unavailable: detector service could not be bound")
            return
        }

        val thread = Thread({ feederLoop(pauseSeconds, token, takeId, stillLive, endOnSilence) }, "SilenceFeederThread")
        feederThread = thread
        runCatching { thread.start() }
            .onFailure {
                feederThread = null
                abandon()
                unbindVad()
                DebugLogger.warn(tag, "Auto-stop unavailable: detector feeder could not start")
            }
    }

    /**
     * The only thread that calls the detector. It is allowed to block; the capture thread is not.
     *
     * The abandonment flag is checked at the top of every pass AND immediately after every remote call,
     * because a gap can open while a call is in flight and a verdict computed from the blocks before a
     * gap must never be applied to the audio after it.
     */
    private fun feederLoop(
        pauseSeconds: Float,
        token: Long,
        takeId: String,
        stillLive: () -> Boolean,
        endOnSilence: () -> Unit,
    ) {
        val ring = ring ?: return
        val block = ByteArray(READ_BLOCK_BYTES)
        var started = false
        var reportedAbandon = false

        fun shouldStop(): Boolean {
            if (detectorAbandoned.get()) {
                if (!reportedAbandon) {
                    reportedAbandon = true
                    DebugLogger.warn(tag, "Auto-stop abandoned for this take")
                }
                return true
            }
            return !stillLive()
        }

        try {
            while (!shouldStop()) {
                val remote = vadService
                if (remote == null) {
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                if (!started) {
                    // The versioned start carries the take's id as detector context; a legacy start (no
                    // id) keeps the old transaction (issue #176).
                    val status = runCatching {
                        if (takeId.isEmpty()) remote.start(token, pauseSeconds)
                        else remote.startForTake(token, pauseSeconds, takeId)
                    }
                        .getOrElse {
                            abandon()
                            DebugLogger.warn(tag, "Auto-stop unavailable: start failed, ${it.javaClass.simpleName}")
                            return
                        }
                    if (shouldStop()) return
                    if (status != SilenceVadService.STATUS_READY) {
                        abandon()
                        DebugLogger.warn(tag, "Auto-stop unavailable: the detector reported so")
                        return
                    }
                    started = true
                    if (silenceStatus.compareAndSet(
                            AudioCaptureService.SILENCE_STATUS_PREPARING,
                            AudioCaptureService.SILENCE_STATUS_READY,
                        )
                    ) {
                        onStatus(AudioCaptureService.SILENCE_STATUS_READY)
                    }
                }

                val length = ring.poll(block)
                if (length <= 0) {
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                val result = runCatching { remote.processBlock(token, block) }
                    .getOrElse {
                        abandon()
                        DebugLogger.warn(tag, "Auto-stop unavailable: the detector call failed")
                        return
                    }

                if (shouldStop()) return

                when (result) {
                    SilenceVadService.RESULT_SILENCE -> {
                        // endTake re-checks that this session is still the live one, under the lock, so
                        // a verdict from a finished take cannot end the take running now.
                        endOnSilence()
                        return
                    }

                    SilenceVadService.RESULT_UNAVAILABLE -> {
                        abandon()
                        DebugLogger.warn(tag, "Auto-stop unavailable: the detector gave up mid-take")
                        return
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            abandon()
            DebugLogger.warn(tag, "Auto-stop unavailable: the feeder failed, ${e.javaClass.simpleName}")
        } finally {
            if (started) runCatching { vadService?.finish(token) }
            unbindVad()
            if (feederThread === Thread.currentThread()) feederThread = null
        }
    }

    /**
     * Auto-stop is off for the rest of THIS take, and is never resumed within it.
     *
     * The status it lands on records whether the detector ever worked. A take that never got one tells
     * the user; a take that had one and lost it does not, because that recording is still correct and a
     * message part way through is an interruption for nothing.
     */
    fun abandon() {
        detectorAbandoned.set(true)
        while (true) {
            val previous = silenceStatus.get()
            val next = when (previous) {
                AudioCaptureService.SILENCE_STATUS_DISABLED,
                AudioCaptureService.SILENCE_STATUS_UNAVAILABLE,
                AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY -> return

                AudioCaptureService.SILENCE_STATUS_READY -> AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY
                else -> AudioCaptureService.SILENCE_STATUS_UNAVAILABLE
            }
            if (silenceStatus.compareAndSet(previous, next)) {
                onStatus(next)
                return
            }
        }
    }

    /** Unbinds only this feed's own connection, so a finished take cannot unbind a running one's. */
    private fun unbindVad() {
        if (!vadBound.compareAndSet(true, false)) return
        val connection = vadConnection ?: return
        vadConnection = null
        vadService = null
        runCatching { unbind(connection) }
            .onFailure { DebugLogger.warn(tag, "Detector unbind failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Abandon and interrupt the feeder. Idempotent. Nothing here blocks: the feeder is told to stop and
     * abandoned, and it holds no recorder, no stream, no ring slot and no reference to a later take.
     * Read [status] BEFORE calling this, because abandoning can move it.
     *
     * The unbind is the feeder's, in its `finally`, on an ordinary release: the take's file is already
     * closed and nothing waits on the detector. [unbindNow] is for teardown, where the process may not
     * give the feeder another turn; [unbindVad] admits exactly one of the two.
     */
    fun close(unbindNow: Boolean) {
        if (closed.compareAndSet(false, true)) {
            detectorAbandoned.set(true)
            feederThread?.let { runCatching { interrupt(it) } }
        }
        if (unbindNow) runCatching { unbindVad() }
    }
}
