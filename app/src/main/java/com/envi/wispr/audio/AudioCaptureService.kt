package com.envi.wispr.audio

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
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
        const val TERMINAL_REASON_NONE = 0
        const val TERMINAL_REASON_MAX_DURATION = 1
        const val TERMINAL_REASON_MANUAL = 2
        const val TERMINAL_REASON_ERROR = 3
    }

    /** Every native and file resource for one take has one owner and one lifetime. */
    private class CaptureSession(
        val record: AudioRecord,
        val file: File,
        val output: FileOutputStream,
        val startedAtMs: Long,
    ) {
        @Volatile var bytesWritten: Long = 0L
        @Volatile var stopRequested: Boolean = false
    }

    private val sessionLock = Any()
    @Volatile private var session: CaptureSession? = null
    private val isRecording = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var lastAudioFile: File? = null
    @Volatile private var currentAmplitude = 0f
    @Volatile private var terminalReason = TERMINAL_REASON_NONE

    /** Callback for recording events (max duration reached). */
    var onMaxDurationReached: (() -> Unit)? = null

    private val binder = object : IAudioCaptureService.Stub() {
        override fun startCapture(): Boolean = this@AudioCaptureService.startRecording()
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

    private fun startRecording(): Boolean {
        synchronized(sessionLock) {
            // A stopped session remains here until its reader has closed both resources.
            // Starting another take before that point would make the old thread write into
            // the new take's file or release the new take's AudioRecord.
            if (session != null) return false

            val bufferSize = try {
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                    .takeIf { it > 0 }
                    ?.coerceAtLeast(SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE)
                    ?: throw IllegalStateException("AudioRecord buffer size unavailable")
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
                    bufferSize,
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
                )
                session = newSession
                lastAudioFile = file
                isRecording.set(true)
                terminalReason = TERMINAL_REASON_NONE
                currentAmplitude = 0f
                DebugLogger.startPipeline()
                DebugLogger.mark(TAG, "recording_start")
                DebugLogger.log(TAG, "Recording started (PID: ${android.os.Process.myPid()}, max: ${MAX_RECORDING_DURATION_MS}ms)")

                val thread = Thread({ captureLoop(newSession, bufferSize) }, "AudioCaptureThread")
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

    private fun captureLoop(active: CaptureSession, bufferSize: Int) {
        var reachedMaxDuration = false
        val buffer = ByteArray(bufferSize)
        try {
            while (isRecording.get() && session === active) {
                val elapsed = SystemClock.elapsedRealtime() - active.startedAtMs
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    reachedMaxDuration = true
                    terminalReason = TERMINAL_REASON_MAX_DURATION
                    DebugLogger.log(TAG, "Max duration reached (${elapsed}ms), auto-stopping")
                    isRecording.set(false)
                    active.stopRequested = true
                    break
                }

                val bytesRead = active.record.read(buffer, 0, buffer.size)
                if (bytesRead < 0) throw IOException("AudioRecord.read failed: $bytesRead")
                if (bytesRead == 0) continue

                active.output.write(buffer, 0, bytesRead)
                active.bytesWritten += bytesRead

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
                if (session === active) {
                    terminalReason = TERMINAL_REASON_ERROR
                    isRecording.set(false)
                }
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

    /** Signal the reader to finish. The reader performs the one final native release. */
    private fun stopRecording() {
        val active: CaptureSession
        synchronized(sessionLock) {
            active = session ?: return
            if (!isRecording.compareAndSet(true, false)) return
            terminalReason = TERMINAL_REASON_MANUAL
            active.stopRequested = true
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
        synchronized(sessionLock) {
            if (session !== active) return
            closeResources(active)
            session = null
            if (captureThread === Thread.currentThread()) captureThread = null
            currentAmplitude = 0f
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
