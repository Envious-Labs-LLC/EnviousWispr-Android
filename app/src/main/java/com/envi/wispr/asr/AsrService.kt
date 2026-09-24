package com.envi.wispr.asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.process.EngineDeadline
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ASR service running in a separate process (:asr).
 *
 * Accepts audio via file path (not byte array) to avoid AIDL size limits.
 * Runs sherpa-onnx OfflineRecognizer (Parakeet nemo_transducer, int8 quantized).
 * Returns raw text so the isolated polish service can run S1-mini or a safe fallback.
 */
class AsrService : Service() {

    companion object {
        private const val TAG = "AsrService"
        private const val SAMPLE_RATE = 16000

        /**
         * The refusal the user reads if a file somehow arrives longer than the cap.
         *
         * Built from the limit rather than written out, because the two used to be separate: the number
         * in the sentence was 120 while the capture process was free to be changed to anything else.
         */
        private val OVER_LIMIT_MESSAGE =
            "This recording is longer than the ${RecordingLimits.MAX_DURATION_MINUTES} minute limit."
    }

    private val transcriptionExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "AsrTranscriptionThread").apply { isDaemon = true }
    }

    /** The watchdog's own thread (#357): never the worker it bounds. */
    private val watchdogScheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "AsrWatchdog").apply { isDaemon = true }
    }

    private val watchdog = AsrWatchdog(EngineDeadline(watchdogScheduler)) { why ->
        DebugLogger.warn(TAG, "Ending the speech process: $why")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Load, every decode and the release run on [transcriptionExecutor], in that order (#212). */
    private val owner = RecognizerOwner<OfflineRecognizer>(
        transcriptionExecutor,
        free = { recognizer ->
            watchdog.guard(AsrBounds.RELEASE_BOUND_MS, "the recognizer release") { recognizer.release() }
            DebugLogger.log(TAG, "Recognizer released")
        },
        discarded = { DebugLogger.log(TAG, "ASR answer discarded: the service closed during the decode") },
    )

    /**
     * How a request wants to hear about a failure. The legacy transactions answer `onError` with the
     * sentences they always sent, so the separately installed instrumentation client keeps working; the
     * versioned request answers `onFailure` with a closed code (issue #176). One decode path, two adapters.
     */
    private fun interface FailureReporter {
        fun report(reason: AsrFailureReason, detail: String)
    }

    private fun legacyReporter(callback: IAsrCallback?) = FailureReporter { reason, detail ->
        // The exact strings the legacy callback has always carried, chosen by reason, not by exception.
        val sentence = when (reason) {
            AsrFailureReason.AUDIO_MISSING -> detail
            AsrFailureReason.OVER_LIMIT -> OVER_LIMIT_MESSAGE
            AsrFailureReason.AUDIO_UNREADABLE -> detail.ifBlank { "Unable to read audio" }
            AsrFailureReason.MODEL_NOT_LOADED -> "ASR model not loaded"
            AsrFailureReason.DECODE_FAILED -> detail.ifBlank { "Unknown transcription error" }
            AsrFailureReason.UNKNOWN -> "Unknown transcription error"
        }
        runCatching { callback?.onError(sentence) }
            .onFailure { DebugLogger.warn(TAG, "Legacy failure callback threw: ${it.javaClass.simpleName}") }
    }

    private fun typedReporter(callback: IAsrCallback?) = FailureReporter { reason, detail ->
        // `detail` stays on the phone: the client logs it and never forwards it (IAsrCallback.aidl).
        runCatching { callback?.onFailure(reason.code, detail) }
            .onFailure { DebugLogger.warn(TAG, "Typed failure callback threw: ${it.javaClass.simpleName}") }
    }

    private val binder = object : IAsrService.Stub() {

        /**
         * Transcribe audio from a file path.
         * Preferred method — avoids AIDL 1MB transaction limit.
         */
        override fun transcribeFile(audioFilePath: String, callback: IAsrCallback?) {
            transcribeFromFile(audioFilePath, takeId = "", callback, legacyReporter(callback))
        }

        /** The versioned request: same decode, typed failures, the take's id as request context. */
        override fun transcribeFileForTake(audioFilePath: String, takeId: String?, callback: IAsrCallback?) {
            transcribeFromFile(audioFilePath, takeId.orEmpty(), callback, typedReporter(callback))
        }

        /**
         * Legacy method — kept for backward compatibility.
         * Will hit AIDL transaction limit for recordings >~30s.
         */
        override fun transcribe(audioData: ByteArray, callback: IAsrCallback?) {
            DebugLogger.warn(TAG, "Legacy transcribe(ByteArray) called — prefer transcribeFile()")
            val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
            val failure = legacyReporter(callback)
            owner.use(refused = { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }) { rec ->
                bounded(audioData.size.toLong()) { doTranscribe(rec, audioData, durationSec, callback, failure) }
            }
        }

        override fun isReady(): Boolean = owner.isReady && !watchdog.wedged
    }

    private fun transcribeFromFile(audioFilePath: String, takeId: String, callback: IAsrCallback?, failure: FailureReporter) {
        val file = File(audioFilePath)
        if (!file.exists()) {
            DebugLogger.error(TAG, "Audio file not found (take=$takeId)")
            failure.report(AsrFailureReason.AUDIO_MISSING, "Audio file not found: $audioFilePath")
            return
        }
        // Not an independent limit. `RecordingLimits` owns the number and the capture process
        // stops a take before this can be reached, so arriving here means something upstream is
        // wrong rather than that the user talked for too long.
        if (file.length() > RecordingLimits.MAX_AUDIO_BYTES) {
            DebugLogger.warn(
                TAG,
                "Audio file is ${file.length()} bytes, over the " +
                    "${RecordingLimits.MAX_AUDIO_BYTES} byte ceiling",
            )
            failure.report(AsrFailureReason.OVER_LIMIT, OVER_LIMIT_MESSAGE)
            return
        }

        owner.use(refused = { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }) { rec ->
            bounded(file.length()) {
                // The read is its own boundary (G1 D4): a failure here is the FILE, never the decoder.
                val audioData = try {
                    file.readBytes()
                } catch (e: Exception) {
                    DebugLogger.error(TAG, "Failed to read audio file", e)
                    val detail = e.javaClass.simpleName
                    return@bounded { failure.report(AsrFailureReason.AUDIO_UNREADABLE, detail) }
                }
                val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
                DebugLogger.log(TAG, "Read ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) for take $takeId")
                DebugLogger.mark(TAG, "asr_file_read")
                doTranscribe(rec, audioData, durationSec, callback, failure)
            }
        }
    }

    /**
     * One worker task under one hard bound (#357 review round 1): the file read, the conversion and the decode, so
     * nothing on the only worker that can block natively is outside it. [audioBytes] sizes the bound before any of
     * them runs, past the owner's own request bound; a task that outlives it ends the process and delivers nothing.
     */
    private fun bounded(audioBytes: Long, work: () -> () -> Unit): () -> Unit {
        val boundMs = AsrBounds.requestBoundMs((PcmAudio.durationSeconds(audioBytes) * 1000f).toLong()) + AsrBounds.DECODE_GRACE_MS
        return watchdog.guard(boundMs, "a transcription") { work() }
            ?: { DebugLogger.warn(TAG, "A transcription returned after its bound; the process is ending") }
    }

    /**
     * Decodes on the worker and RETURNS the answer's delivery rather than making it, so the owner can
     * discard an answer that finished after the service closed (#212). Every path returns exactly one.
     */
    private fun doTranscribe(
        rec: OfflineRecognizer?,
        audioData: ByteArray,
        durationSec: Float,
        callback: IAsrCallback?,
        failure: FailureReporter,
    ): () -> Unit {
        DebugLogger.log(TAG, "Transcribing ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) (PID: ${android.os.Process.myPid()})")

        if (rec == null) {
            DebugLogger.error(TAG, "Recognizer not initialized")
            return { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }
        }

        // The decode is its own boundary (G1 D4): only the recogniser's own work is inside this try, so
        // a throwing DELIVERY below can never be reported as a decode failure or produce a second callback.
        val rawText = try {
            val samples = PcmAudio.toFloatSamples(audioData)
            DebugLogger.mark(TAG, "pcm_to_float")

            val t0 = SystemClock.elapsedRealtime()

            val stream = rec.createStream()
            val result = try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream)
            } finally {
                stream.release()
            }

            val decodeMs = SystemClock.elapsedRealtime() - t0
            val text = result.text.trim()
            val rtf = if (durationSec > 0) decodeMs / (durationSec * 1000) else 0f

            DebugLogger.mark(TAG, "asr_decode")
            // Transcript text is user content and must never enter logs. Keep only the
            // aggregate needed to diagnose decode latency and empty-result behavior.
            DebugLogger.log(TAG, "Decode: ${decodeMs}ms, RTF=${String.format("%.2f", rtf)}, textChars=${text.length}")
            text
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Transcription failed", e)
            val detail = e.javaClass.simpleName
            return { failure.report(AsrFailureReason.DECODE_FAILED, detail) }
        }

        DebugLogger.log(TAG, DebugLogger.pipelineSummary())
        return {
            runCatching { callback?.onResult(rawText) }
                .onFailure { DebugLogger.warn(TAG, "Result delivery threw: ${it.javaClass.simpleName}; not reported as a decode failure") }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DebugLogger.log(TAG, "AsrService created (PID: ${android.os.Process.myPid()})")
        // Receipt verification and native model loading are deliberately off the service main thread.
        owner.load { watchdog.guard(AsrBounds.LOAD_BOUND_MS, "the model load") { initRecognizer() } }
    }

    override fun onDestroy() {
        // Never waits and never frees here: the release is queued behind the decode in flight (#212).
        // The watchdog's thread stops only after the release, which it also bounds (review round 1); a bound still
        // armed for a wedged task survives `shutdown` (never `shutdownNow`) and still ends the process.
        owner.close { watchdogScheduler.shutdown() }
        super.onDestroy()
        DebugLogger.log(TAG, "AsrService destroyed; recognizer release queued")
    }

    /** Returns the loaded recognizer, or null when the model is not verified or fails to load. */
    private fun initRecognizer(): OfflineRecognizer? {
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val modelDir = ModelStorage.directory(this, ModelManifest.parakeet)
            if (!ModelStorage.isReady(this, ModelManifest.parakeet)) {
                DebugLogger.warn(TAG, "Parakeet model is not verified in app-private storage")
                return null
            }

            val transducerConfig = OfflineTransducerModelConfig(
                encoder = File(modelDir, "encoder.int8.onnx").path,
                decoder = File(modelDir, "decoder.int8.onnx").path,
                joiner = File(modelDir, "joiner.int8.onnx").path,
            )

            val modelConfig = OfflineModelConfig()
            modelConfig.transducer = transducerConfig
            modelConfig.tokens = File(modelDir, "tokens.txt").path
            modelConfig.modelType = "nemo_transducer"
            modelConfig.numThreads = 4
            modelConfig.debug = false

            val config = OfflineRecognizerConfig()
            config.modelConfig = modelConfig
            // Pinned, not defaulted. sherpa-onnx already defaults to greedy_search (read from the
            // v1.12.29 tag, 2026-09-02), but sherpa-onnx issue 3267 reports modified_beam_search
            // hallucinating or returning empty text on Parakeet TDT about a fifth of the time. An AAR
            // upgrade that changed the default would otherwise reach this model silently.
            config.decodingMethod = "greedy_search"

            val recognizer = OfflineRecognizer(null, config)

            val elapsed = SystemClock.elapsedRealtime() - t0
            DebugLogger.log(TAG, "Recognizer initialized in ${elapsed}ms")
            recognizer
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Failed to initialize recognizer", e)
            null
        }
    }

}
