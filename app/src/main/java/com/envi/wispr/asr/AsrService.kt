package com.envi.wispr.asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.debug.TakeLog
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.models.LegacyModelSweep
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.process.EngineDeadline
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ASR service running in a separate process (:asr).
 *
 * Accepts audio via file path (not byte array) to avoid AIDL size limits.
 * Runs [ParakeetEngine]: Parakeet TDT (SmoothQuant int8) on ONNX Runtime through the macOS batch recipe (#374).
 * Returns raw text so the isolated polish service can run S1-mini or a safe fallback.
 */
class AsrService : Service() {

    companion object {
        private const val TAG = "AsrService"

        /**
         * Hands the text over; [onDelivered] runs only when a callback existed and did not throw. The legacy model
         * sweep (#374) hangs off it, so a phone never loses the old model for a result nobody received.
         */
        internal fun deliverResult(callback: IAsrCallback?, text: String, onDelivered: () -> Unit, onThrew: (Throwable) -> Unit) {
            if (callback == null) return
            runCatching { callback.onResult(text) }.onSuccess { onDelivered() }.onFailure(onThrew)
        }

        /** The take id a take-less legacy byte-array request carries in the local log (#378). */
        private const val LEGACY_TAKE = "untracked"

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

    /**
     * Load, every decode and the release run on [transcriptionExecutor], in that order (#212), and each runs whole
     * under the watchdog (#357): a task that outlives its bound ends this process and its late result delivers nothing.
     */
    private val owner = RecognizerOwner<ParakeetEngine>(
        transcriptionExecutor,
        free = { engine ->
            engine.close()
            DebugLogger.log(TAG, "Speech engine closed")
        },
        discarded = { DebugLogger.log(TAG, "ASR answer discarded: the service closed during the decode") },
        releaseBoundMs = AsrBounds.RELEASE_BOUND_MS,
        bounded = { boundMs, what, task ->
            if (watchdog.guard(boundMs, what, task) == null) {
                DebugLogger.warn(TAG, "A task returned after its bound; the process is ending")
            }
        },
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
            owner.use(transcriptionBoundMs(audioData.size.toLong()), refused = { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }) { rec ->
                doTranscribe(rec, audioData, durationSec, callback, failure, TakeLog(LEGACY_TAKE, TAG))
            }
        }

        override fun isReady(): Boolean = owner.isReady && !watchdog.wedged
    }

    private fun transcribeFromFile(audioFilePath: String, takeId: String, callback: IAsrCallback?, failure: FailureReporter) {
        // This process's start of the take (#378): the take id travels to the local log as a field.
        val log = TakeLog(takeId, TAG).also { it.startPipeline() }
        val file = File(audioFilePath)
        if (!file.exists()) {
            log.error("Audio file not found (take=$takeId)")
            failure.report(AsrFailureReason.AUDIO_MISSING, "Audio file not found: $audioFilePath")
            return
        }
        // Not an independent limit. `RecordingLimits` owns the number and the capture process
        // stops a take before this can be reached, so arriving here means something upstream is
        // wrong rather than that the user talked for too long.
        // Read once, on the binder thread, never on the worker (#357 review round 2): it sizes the task's bound too.
        val audioBytes = file.length()
        if (audioBytes > RecordingLimits.MAX_AUDIO_BYTES) {
            log.warn(
                "Audio file is $audioBytes bytes, over the " +
                    "${RecordingLimits.MAX_AUDIO_BYTES} byte ceiling",
            )
            failure.report(AsrFailureReason.OVER_LIMIT, OVER_LIMIT_MESSAGE)
            return
        }

        owner.use(transcriptionBoundMs(audioBytes), refused = { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }) { rec ->
            // The read is its own boundary (G1 D4): a failure here is the FILE, never the decoder.
            val audioData = try {
                file.readBytes()
            } catch (e: Exception) {
                log.error("Failed to read audio file", e)
                val detail = e.javaClass.simpleName
                return@use { failure.report(AsrFailureReason.AUDIO_UNREADABLE, detail) }
            }
            val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
            log.log("Read ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) for take $takeId")
            log.mark("asr_file_read")
            doTranscribe(rec, audioData, durationSec, callback, failure, log)
        }
    }

    /** One whole transcription task's bound (#357): past the owner's own request bound, sized before the task runs. */
    private fun transcriptionBoundMs(audioBytes: Long): Long =
        AsrBounds.requestBoundMs((PcmAudio.durationSeconds(audioBytes) * 1000f).toLong()) + AsrBounds.DECODE_GRACE_MS

    /**
     * Decodes on the worker and RETURNS the answer's delivery rather than making it, so the owner can
     * discard an answer that finished after the service closed (#212). Every path returns exactly one.
     */
    private fun doTranscribe(
        rec: ParakeetEngine?,
        audioData: ByteArray,
        durationSec: Float,
        callback: IAsrCallback?,
        failure: FailureReporter,
        log: TakeLog,
    ): () -> Unit {
        log.log("Transcribing ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) (PID: ${android.os.Process.myPid()})")

        if (rec == null) {
            log.error("Recognizer not initialized")
            return { failure.report(AsrFailureReason.MODEL_NOT_LOADED, "") }
        }

        // The decode is its own boundary (G1 D4): only the recogniser's own work is inside this try, so
        // a throwing DELIVERY below can never be reported as a decode failure or produce a second callback.
        val rawText = try {
            val samples = PcmAudio.toFloatSamples(audioData)
            log.mark("pcm_to_float")

            val t0 = SystemClock.elapsedRealtime()

            val result = rec.transcribe(samples)

            val decodeMs = SystemClock.elapsedRealtime() - t0
            val text = result.text.trim()
            val rtf = if (durationSec > 0) decodeMs / (durationSec * 1000) else 0f

            log.mark("asr_decode")
            // Transcript text is user content and never enters logcat: logcat keeps only the aggregate. The
            // words go to the local log file alone, and only while Detailed log is on (#378).
            log.log("Decode: ${decodeMs}ms, RTF=${String.format("%.2f", rtf)}, textChars=${text.length}, windows=${result.windows}, repairs=${result.repairs}")
            log.words("raw_speech") { text }
            text
        } catch (e: Exception) {
            log.error("Transcription failed", e)
            val detail = e.javaClass.simpleName
            return { failure.report(AsrFailureReason.DECODE_FAILED, detail) }
        }

        log.log(DebugLogger.pipelineSummary())
        return {
            deliverResult(callback, rawText, onDelivered = ::sweepLegacyModelOnce) {
                log.warn("Result delivery threw: ${it.javaClass.simpleName}; not reported as a decode failure")
            }
        }
    }

    /** Set once this process has swept the model the engine swap replaced (#374). */
    private var legacySwept = false

    /**
     * After the first successful decode on the new engine, remove the sherpa-onnx model it replaced (#374), so a
     * phone is never left with neither. Runs on the worker, after the result was handed over; never throws.
     */
    private fun sweepLegacyModelOnce() {
        if (legacySwept) return
        legacySwept = true
        val removed = LegacyModelSweep.sweep(ModelStorage.root(this))
        if (removed > 0) DebugLogger.log(TAG, "Removed the replaced speech model: $removed bytes")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DebugLogger.log(TAG, "AsrService created (PID: ${android.os.Process.myPid()})")
        // Receipt verification and native model loading are deliberately off the service main thread.
        owner.load(AsrBounds.LOAD_BOUND_MS) { initRecognizer() }
    }

    override fun onDestroy() {
        // Never waits and never frees here: the release is queued behind the decode in flight (#212).
        // The watchdog's thread stops only after the release, which it also bounds (review round 1); a bound still
        // armed for a wedged task survives `shutdown` (never `shutdownNow`) and still ends the process.
        owner.close { watchdogScheduler.shutdown() }
        super.onDestroy()
        DebugLogger.log(TAG, "AsrService destroyed; recognizer release queued")
    }

    /** Returns the loaded engine, or null when the model is not verified or fails to load. */
    private fun initRecognizer(): ParakeetEngine? {
        return try {
            val t0 = SystemClock.elapsedRealtime()
            if (!ModelStorage.isReady(this, ModelManifest.parakeet)) {
                DebugLogger.warn(TAG, "Parakeet model is not verified in app-private storage")
                return null
            }
            val preprocessor = assets.open(ParakeetEngine.PREPROCESSOR_ASSET).use { it.readBytes() }
            val engine = ParakeetEngine.open(ModelStorage.directory(this, ModelManifest.parakeet), preprocessor)
            DebugLogger.log(TAG, "Speech engine initialized in ${SystemClock.elapsedRealtime() - t0}ms")
            engine
        } catch (e: Throwable) {
            // The class name only: an ONNX Runtime message can quote paths or model internals.
            DebugLogger.warn(TAG, "Failed to initialize the speech engine: ${e.javaClass.simpleName}")
            null
        }
    }

}
