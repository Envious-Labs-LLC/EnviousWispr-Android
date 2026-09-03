package com.envi.wispr.audio

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.vad.ISilenceVadService
import com.envi.wispr.vad.SilenceVadService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Audio capture service running in a separate process (:audio). */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = PcmAudio.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_DURATION_MS = 120_000L
        private const val AUDIO_FILENAME = "recording.pcm"

        /**
         * How much audio one `AudioRecord.read` asks for: 4096 samples, 256 ms at 16 kHz.
         *
         * **This is a different quantity from the buffer the AudioRecord is constructed with**, and the
         * two want opposite things. The native buffer is the margin that stops an overrun when the
         * capture thread is descheduled, so it wants to be large. This is the loop's decision
         * granularity, so it wants to be small: the duration ceiling can only fire on a block boundary,
         * and so can a silence stop. Reading the whole native buffer made both coarse to about a second.
         *
         * Android's own guidance is to read in short frequent chunks rather than waiting for the buffer
         * to fill. 256 ms is macOS's detector chunk, which is what the silence state machine ticks on.
         */
        private const val READ_BLOCK_BYTES = 8_192

        /**
         * Eight blocks, 2.048 seconds of audio, and the detector's own call deadline is set against it.
         * It is the client-owned deadline for the one binding failure Android gives no signal for: a
         * bind that succeeds and then never connects.
         */
        private const val RING_BLOCKS = 8

        /** How long the feeder waits when there is nothing to do. It is not the capture thread. */
        private const val FEEDER_IDLE_MS = 20L

        /** A bounded wait, never an unbounded one: the feeder must not be able to hold up a stop. */
        private const val FEEDER_JOIN_MS = 250L

        const val SILENCE_STATUS_DISABLED = 0
        const val SILENCE_STATUS_PREPARING = 1
        const val SILENCE_STATUS_READY = 2
        const val SILENCE_STATUS_UNAVAILABLE = 3
        /**
         * The terminal reasons, re-exported under the names callers already use. `CaptureEnding` owns the
         * values, because they cross a process boundary and must have exactly one definition.
         *
         * [TERMINAL_REASON_SILENCE] is a NORMAL ending in the same class as [TERMINAL_REASON_MANUAL]: a
         * reader that treats it as a failure discards a good transcript. Nothing sets it yet.
         */
        const val TERMINAL_REASON_NONE = CaptureEnding.NONE
        const val TERMINAL_REASON_MAX_DURATION = CaptureEnding.MAX_DURATION
        const val TERMINAL_REASON_MANUAL = CaptureEnding.MANUAL
        const val TERMINAL_REASON_ERROR = CaptureEnding.ERROR
        const val TERMINAL_REASON_SILENCE = CaptureEnding.SILENCE
    }

    /** Every native and file resource for one take has one owner and one lifetime. */
    private class CaptureSession(
        val record: AudioRecord,
        val file: File,
        val output: FileOutputStream,
        val startedAtMs: Long,
        val readBuffer: ByteArray,
        val token: Long,
        /** Null when the user has auto-stop off: no ring, no feeder, no detector process. */
        val ring: BlockRing?,
        /** Staging for a read that did not land on a block boundary. Preallocated, like everything else. */
        val pendingBlock: ByteArray?,
    ) {
        /** Capture thread only. */
        var pendingBytes: Int = 0
        @Volatile var bytesWritten: Long = 0L

        /** The one owner of how this take ended. First claim wins; see `CaptureEndingClaim`. */
        val endingClaim = CaptureEndingClaim()

        val stopRequested: Boolean get() = endingClaim.ended
    }

    private val sessionLock = Any()
    @Volatile private var session: CaptureSession? = null
    private val isRecording = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var lastAudioFile: File? = null
    @Volatile private var currentAmplitude = 0f
    @Volatile private var terminalReason = TERMINAL_REASON_NONE
    @Volatile private var silenceStatus = SILENCE_STATUS_DISABLED
    @Volatile private var vadService: ISilenceVadService? = null
    @Volatile private var vadBound = false
    @Volatile private var feederThread: Thread? = null
    private val tokens = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Every way this binding can fail, and they all mean the same thing to a take: auto-stop is off for
     * it, and recording continues. `onNullBinding` and `onBindingDied` unbind explicitly so Android
     * cannot reconnect into a take that has already given up on the detector.
     */
    private val vadConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            vadService = ISilenceVadService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vadService = null
            markSilenceUnavailable("detector process disconnected")
        }

        override fun onNullBinding(name: ComponentName?) {
            vadService = null
            markSilenceUnavailable("detector returned no binding")
            unbindVad()
        }

        override fun onBindingDied(name: ComponentName?) {
            vadService = null
            markSilenceUnavailable("detector binding died")
            unbindVad()
        }
    }

    /** Callback for recording events (max duration reached). */
    var onMaxDurationReached: (() -> Unit)? = null

    private val binder = object : IAudioCaptureService.Stub() {
        override fun startCapture(): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence = false, pauseSeconds = 0f)

        override fun startCaptureWithSilenceStop(autoStopOnSilence: Boolean, pauseSeconds: Float): Boolean =
            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds)

        override fun getSilenceStopStatus(): Int = this@AudioCaptureService.silenceStatus
        override fun stopCapture() = this@AudioCaptureService.stopRecording()
        override fun isCapturing(): Boolean = this@AudioCaptureService.isRecording.get()
        override fun getTerminalReason(): Int = this@AudioCaptureService.terminalReason
        override fun getCurrentAmplitude(): Float = this@AudioCaptureService.currentAmplitude
        override fun getAudioFilePath(): String? = this@AudioCaptureService.lastAudioFile?.absolutePath

        override fun getElapsedMs(): Long {
            val active = this@AudioCaptureService.session
            return if (this@AudioCaptureService.isRecording.get() && active != null) {
                SystemClock.elapsedRealtime() - active.startedAtMs
            } else 0L
        }

        override fun getMaxDurationMs(): Long = MAX_RECORDING_DURATION_MS
        override fun waitForFileReady(timeoutMs: Long): Boolean =
            this@AudioCaptureService.waitForFileReady(timeoutMs)

        // Legacy method retained for old clients. Audio is now file-backed.
        override fun getAudioData(): ByteArray {
            DebugLogger.warn(TAG, "getAudioData() called, use getAudioFilePath() instead")
            return ByteArray(0)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startRecording(autoStopOnSilence: Boolean, pauseSeconds: Float): Boolean {
        synchronized(sessionLock) {
            // A stopped session remains here until its reader has closed both resources.
            // Starting another take before that point would make the old thread write into
            // the new take's file or release the new take's AudioRecord.
            if (session != null) return false

            val nativeBufferBytes = try {
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                    .takeIf { it > 0 }
                    ?: throw IllegalStateException("AudioRecord buffer size unavailable")
                val coerced = minimum.coerceAtLeast(SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE)
                // Whether the floor binds cannot be settled from source: getMinBufferSize is computed by
                // the platform from the device's own frame count. Log both so the answer comes from the
                // phone rather than from an assumption. Android may also enlarge what it actually
                // allocates, which getBufferSizeInFrames reports once the recorder exists.
                DebugLogger.log(
                    TAG,
                    "Buffer sizes: minimum=$minimum coerced=$coerced read=$READ_BLOCK_BYTES",
                )
                coerced
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to determine audio buffer size", e)
                stopSelf()
                return false
            }

            var record: AudioRecord? = null
            var output: FileOutputStream? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    nativeBufferBytes,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize")
                }

                // cacheDir is app-internal and shared by this package's processes. It is
                // excluded from user backups and is less exposed than shared storage.
                val file = File(cacheDir, AUDIO_FILENAME)
                // There is no active session under sessionLock, so no writer can own this
                // path. Remove a partial take left by process death before opening a new one.
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to remove stale audio recording")
                }
                output = FileOutputStream(file)
                record.startRecording()

                val newSession = CaptureSession(
                    record = record,
                    file = file,
                    output = output,
                    startedAtMs = SystemClock.elapsedRealtime(),
                    // Allocated HERE, before the thread starts, and never inside the capture loop. The
                    // capture thread may not allocate: it must do nothing that can make it late.
                    readBuffer = ByteArray(READ_BLOCK_BYTES),
                    token = tokens.incrementAndGet(),
                    ring = if (autoStopOnSilence) BlockRing(RING_BLOCKS, READ_BLOCK_BYTES) else null,
                    pendingBlock = if (autoStopOnSilence) ByteArray(READ_BLOCK_BYTES) else null,
                )
                session = newSession
                lastAudioFile = file
                isRecording.set(true)
                terminalReason = TERMINAL_REASON_NONE
                silenceStatus =
                    if (autoStopOnSilence) SILENCE_STATUS_PREPARING else SILENCE_STATUS_DISABLED
                currentAmplitude = 0f
                DebugLogger.startPipeline()
                DebugLogger.mark(TAG, "recording_start")
                DebugLogger.log(
                    TAG,
                    "Recording started (PID: ${android.os.Process.myPid()}, " +
                        "max: ${MAX_RECORDING_DURATION_MS}ms, " +
                        "nativeFrames: ${runCatching { record.bufferSizeInFrames }.getOrDefault(-1)})",
                )

                if (autoStopOnSilence) startSilenceDetection(newSession, pauseSeconds)

                val thread = Thread({ captureLoop(newSession) }, "AudioCaptureThread")
                captureThread = thread
                try {
                    thread.start()
                } catch (e: Exception) {
                    isRecording.set(false)
                    session = null
                    closeResources(newSession)
                    captureThread = null
                    stopSelf()
                    DebugLogger.error(TAG, "Failed to start capture thread", e)
                    return false
                }
                return thread.isAlive && session === newSession && isRecording.get()
            } catch (e: SecurityException) {
                DebugLogger.error(TAG, "RECORD_AUDIO permission not granted", e)
                isRecording.set(false)
                closeResources(record, output)
                stopSelf()
                return false
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Failed to start recording", e)
                isRecording.set(false)
                closeResources(record, output)
                stopSelf()
                return false
            }
        }
    }

    private fun captureLoop(active: CaptureSession) {
        var reachedMaxDuration = false
        val buffer = active.readBuffer
        try {
            while (isRecording.get() && session === active) {
                val elapsed = SystemClock.elapsedRealtime() - active.startedAtMs
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    reachedMaxDuration = claimEnding(active, TERMINAL_REASON_MAX_DURATION)
                    DebugLogger.log(TAG, "Max duration reached (${elapsed}ms), auto-stopping")
                    break
                }

                val bytesRead = active.record.read(buffer, 0, buffer.size)
                if (bytesRead < 0) throw IOException("AudioRecord.read failed: $bytesRead")
                if (bytesRead == 0) continue

                active.output.write(buffer, 0, bytesRead)
                active.bytesWritten += bytesRead
                offerToDetector(active, buffer, bytesRead)

                var sum = 0L
                for (i in 0 until bytesRead step PcmAudio.BYTES_PER_SAMPLE) {
                    if (i + 1 < bytesRead) {
                        val sample = (buffer[i].toInt() and 0xFF) or
                            (buffer[i + 1].toInt() shl 8)
                        sum += abs(sample)
                    }
                }
                val numSamples = bytesRead / PcmAudio.BYTES_PER_SAMPLE
                currentAmplitude = if (numSamples > 0) {
                    (sum.toFloat() / numSamples) / Short.MAX_VALUE
                } else 0f
            }
        } catch (e: Exception) {
            synchronized(sessionLock) {
                if (session === active) claimEnding(active, TERMINAL_REASON_ERROR)
            }
            DebugLogger.error(TAG, "Capture thread error", e)
        } finally {
            releaseSession(active)
            if (reachedMaxDuration) {
                runCatching { onMaxDurationReached?.invoke() }
                    .onFailure { DebugLogger.error(TAG, "Max-duration callback failed", it) }
            }
        }
    }

    /**
     * Capture thread only. Copies audio toward the detector and never waits for it.
     *
     * A read that does not land on a block boundary is staged, so the detector always sees whole
     * 256 ms blocks in order. If the ring is full the detector has fallen more than two seconds behind,
     * and auto-stop is abandoned for the rest of the take rather than resumed across the gap: a gap
     * breaks the model's recurrent continuity, and resumed speech could then read as an early stop.
     */
    private fun offerToDetector(active: CaptureSession, buffer: ByteArray, bytesRead: Int) {
        val ring = active.ring ?: return
        val pending = active.pendingBlock ?: return
        if (silenceStatus == SILENCE_STATUS_UNAVAILABLE) return

        var consumed = 0
        while (consumed < bytesRead) {
            val room = READ_BLOCK_BYTES - active.pendingBytes
            val take = minOf(room, bytesRead - consumed)
            System.arraycopy(buffer, consumed, pending, active.pendingBytes, take)
            active.pendingBytes += take
            consumed += take
            if (active.pendingBytes == READ_BLOCK_BYTES) {
                active.pendingBytes = 0
                if (!ring.offer(pending, READ_BLOCK_BYTES)) {
                    markSilenceUnavailable("detector fell behind the ring")
                    return
                }
            }
        }
    }

    /**
     * Bind the detector process and start the one thread allowed to talk to it.
     *
     * Capture has already started by the time this runs, so a slow or failed detector delays nothing.
     * A take whose detector never becomes ready is simply a take the user stops by hand.
     */
    private fun startSilenceDetection(active: CaptureSession, pauseSeconds: Float) {
        vadBound = runCatching {
            bindService(
                Intent(this, SilenceVadService::class.java),
                vadConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!vadBound) {
            markSilenceUnavailable("detector service could not be bound")
            return
        }

        val thread = Thread({ feederLoop(active, pauseSeconds) }, "SilenceFeederThread")
        feederThread = thread
        runCatching { thread.start() }
            .onFailure {
                feederThread = null
                markSilenceUnavailable("detector feeder could not start")
            }
    }

    /**
     * The only thread that calls the detector. It is allowed to block; the capture thread is not.
     *
     * Every result is checked against the live session AND its token before it can end anything. A
     * synchronous call can still return after teardown if it stalled, and that result belongs to a take
     * that is already over.
     */
    private fun feederLoop(active: CaptureSession, pauseSeconds: Float) {
        val ring = active.ring ?: return
        val block = ByteArray(READ_BLOCK_BYTES)
        var started = false
        try {
            while (session === active && !active.stopRequested) {
                val remote = vadService
                if (remote == null) {
                    if (silenceStatus == SILENCE_STATUS_UNAVAILABLE) return
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                if (!started) {
                    val status = runCatching { remote.start(active.token, pauseSeconds) }
                        .getOrElse {
                            markSilenceUnavailable("detector start failed: ${it.message}")
                            return
                        }
                    if (status != SilenceVadService.STATUS_READY) {
                        markSilenceUnavailable("detector reported it is unavailable")
                        return
                    }
                    started = true
                    if (session === active && !active.stopRequested) {
                        silenceStatus = SILENCE_STATUS_READY
                    }
                }

                val length = ring.poll(block)
                if (length <= 0) {
                    Thread.sleep(FEEDER_IDLE_MS)
                    continue
                }

                val result = runCatching { remote.processBlock(active.token, block) }
                    .getOrElse {
                        markSilenceUnavailable("detector call failed: ${it.message}")
                        return
                    }

                when (result) {
                    SilenceVadService.RESULT_SILENCE -> {
                        // The token and the session identity are both checked inside endTake, so a
                        // result from a finished take cannot end the one running now.
                        if (session === active) endTake(TERMINAL_REASON_SILENCE)
                        return
                    }

                    SilenceVadService.RESULT_UNAVAILABLE -> {
                        markSilenceUnavailable("detector gave up mid-take")
                        return
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            markSilenceUnavailable("detector feeder failed: ${e.message}")
        } finally {
            if (started) runCatching { vadService?.finish(active.token) }
        }
    }

    /**
     * Auto-stop is off for the rest of this take, and is never resumed within it.
     *
     * Only a take that never became ready produces a user-visible notice: losing the detector after it
     * was working leaves a correct recording, and a message several seconds into one is an interruption
     * for no gain.
     */
    private fun markSilenceUnavailable(reason: String) {
        if (silenceStatus == SILENCE_STATUS_DISABLED) return
        silenceStatus = SILENCE_STATUS_UNAVAILABLE
        DebugLogger.warn(TAG, "Auto-stop unavailable: $reason")
    }

    private fun unbindVad() {
        if (!vadBound) return
        vadBound = false
        vadService = null
        runCatching { unbindService(vadConnection) }
            .onFailure { DebugLogger.warn(TAG, "Detector unbind failed: ${it.message}") }
    }

    /**
     * Release the detector without waiting on it.
     *
     * The feeder is signalled and joined only briefly. Whether it returns or not, it holds no
     * AudioRecord, no file stream, no ring slot and no reference to a later session, and any result it
     * eventually produces fails the session and token checks. A stalled detector may cost this take its
     * auto-stop; it may not refuse the next recording.
     */
    private fun releaseSilenceDetection() {
        val thread = feederThread
        feederThread = null
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(FEEDER_JOIN_MS) }
            if (thread.isAlive) {
                DebugLogger.warn(TAG, "Detector feeder did not finish; abandoning it")
            }
        }
        unbindVad()
    }

    /**
     * Publish the first ending claimed for [active] and stop the loop.
     *
     * The published reason must outlive [releaseSession], because the client polls `getTerminalReason`
     * only AFTER `isCapturing` has gone false, by which point the session is gone. So the value lives on
     * the service while the CLAIM lives on the session, and the claim is what makes it first-wins.
     */
    private fun claimEnding(active: CaptureSession, reason: Int): Boolean {
        if (!active.endingClaim.claim(reason)) return false
        terminalReason = reason
        isRecording.set(false)
        return true
    }

    /** The user, or anything else with the authority to end a take by hand. */
    private fun stopRecording() = endTake(TERMINAL_REASON_MANUAL)

    /**
     * Signal the reader to finish, for whichever reason got here first. The reader performs the one
     * final native release.
     *
     * The session identity is re-read under the lock, so a caller holding a reference to a take that
     * has already ended cannot end the next one.
     */
    private fun endTake(reason: Int) {
        val active: CaptureSession
        synchronized(sessionLock) {
            active = session ?: return
            if (!isRecording.get()) return
            if (!claimEnding(active, reason)) return
            try {
                // stop() unblocks a pending read. Do not release here while the reader may
                // still be using the same AudioRecord instance.
                active.record.stop()
            } catch (e: Exception) {
                // The reader's finally block still owns and releases the resources if stop
                // itself fails, so a vendor-specific AudioRecord error cannot leak a session.
                DebugLogger.warn(TAG, "AudioRecord stop failed: ${e.message}")
            }
            DebugLogger.mark(TAG, "recording_stop")
            DebugLogger.log(
                TAG,
                "Stopped. ${active.bytesWritten} bytes (${String.format("%.1f", PcmAudio.durationSeconds(active.bytesWritten))}s) -> ${active.file.absolutePath}",
            )
        }
    }

    private fun releaseSession(active: CaptureSession) {
        // Outside the lock, and never joined for long: detector teardown does not own capture teardown.
        if (active.ring != null) releaseSilenceDetection()
        synchronized(sessionLock) {
            if (session !== active) return
            closeResources(active)
            session = null
            if (captureThread === Thread.currentThread()) captureThread = null
            currentAmplitude = 0f
            if (silenceStatus == SILENCE_STATUS_PREPARING || silenceStatus == SILENCE_STATUS_READY) {
                silenceStatus = SILENCE_STATUS_DISABLED
            }
            stopSelf()
        }
    }

    private fun closeResources(active: CaptureSession) = closeResources(active.record, active.output)

    private fun closeResources(record: AudioRecord?, output: FileOutputStream?) {
        runCatching { output?.flush() }
            .onFailure { DebugLogger.warn(TAG, "Failed to flush audio file: ${it.message}") }
        runCatching { output?.close() }
            .onFailure { DebugLogger.warn(TAG, "Failed to close audio file: ${it.message}") }
        runCatching { record?.stop() }
            .onFailure {
                if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.message}")
            }
        runCatching { record?.release() }
            .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
    }

    /** Wait for the capture thread to finish writing and close the file. */
    fun waitForFileReady(timeoutMs: Long = 2_000L): Boolean {
        val thread = captureThread
        if (thread != null && Thread.currentThread() !== thread) {
            thread.join(timeoutMs.coerceAtLeast(0L))
        }
        val ready = thread?.isAlive != true && session == null
        if (ready) captureThread = null
        return ready
    }

    override fun onDestroy() {
        stopRecording()
        releaseSilenceDetection()
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(2_000L)
            if (thread.isAlive) {
                // The capture thread is the sole owner of AudioRecord and the file. Do not
                // close either resource here after the bounded wait. Process termination of
                // the isolated :audio service will reclaim them without an ANR-length wait.
                DebugLogger.warn(TAG, "Capture thread did not finish during service teardown")
            }
        }
        if (captureThread?.isAlive != true) {
            captureThread = null
            synchronized(sessionLock) {
                isRecording.set(false)
            }
        }
        super.onDestroy()
    }
}
