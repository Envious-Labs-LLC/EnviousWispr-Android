package com.envi.wispr.asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
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
        private const val MAX_AUDIO_BYTES = 120L * SAMPLE_RATE * 2
    }

    private var recognizer: OfflineRecognizer? = null
    @Volatile
    private var modelReady = false
    private val transcriptionExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "AsrTranscriptionThread").apply { isDaemon = true }
    }

    private val binder = object : IAsrService.Stub() {

        /**
         * Transcribe audio from a file path.
         * Preferred method — avoids AIDL 1MB transaction limit.
         */
        override fun transcribeFile(audioFilePath: String, callback: IAsrCallback?) {
            val file = File(audioFilePath)
            if (!file.exists()) {
                val msg = "Audio file not found: $audioFilePath"
                DebugLogger.error(TAG, msg)
                callback?.onError(msg)
                return
            }
            if (file.length() > MAX_AUDIO_BYTES) {
                val msg = "Audio recording exceeds the 120 second limit"
                DebugLogger.warn(TAG, msg)
                callback?.onError(msg)
                return
            }

            transcriptionExecutor.execute {
                try {
                    val audioData = file.readBytes()
                    val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
                    DebugLogger.log(TAG, "Read ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) from $audioFilePath")
                    DebugLogger.mark(TAG, "asr_file_read")
                    doTranscribe(audioData, durationSec, callback)
                } catch (e: Exception) {
                    DebugLogger.error(TAG, "Failed to read audio file", e)
                    callback?.onError(e.message ?: "Unable to read audio")
                }
            }
        }

        /**
         * Legacy method — kept for backward compatibility.
         * Will hit AIDL transaction limit for recordings >~30s.
         */
        override fun transcribe(audioData: ByteArray, callback: IAsrCallback?) {
            DebugLogger.warn(TAG, "Legacy transcribe(ByteArray) called — prefer transcribeFile()")
            val durationSec = PcmAudio.durationSeconds(audioData.size.toLong())
            transcriptionExecutor.execute { doTranscribe(audioData, durationSec, callback) }
        }

        override fun isReady(): Boolean = modelReady
    }

    private fun doTranscribe(audioData: ByteArray, durationSec: Float, callback: IAsrCallback?) {
        DebugLogger.log(TAG, "Transcribing ${audioData.size} bytes (${String.format("%.1f", durationSec)}s) (PID: ${android.os.Process.myPid()})")

        val rec = recognizer
        if (rec == null) {
            DebugLogger.error(TAG, "Recognizer not initialized")
            callback?.onError("ASR model not loaded")
            return
        }

        try {
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
            val rawText = result.text.trim()
            val rtf = if (durationSec > 0) decodeMs / (durationSec * 1000) else 0f

            DebugLogger.mark(TAG, "asr_decode")
            // Transcript text is user content and must never enter logs. Keep only the
            // aggregate needed to diagnose decode latency and empty-result behavior.
            DebugLogger.log(TAG, "Decode: ${decodeMs}ms, RTF=${String.format("%.2f", rtf)}, textChars=${rawText.length}")

            DebugLogger.log(TAG, DebugLogger.pipelineSummary())
            callback?.onResult(rawText)
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Transcription failed", e)
            callback?.onError(e.message ?: "Unknown transcription error")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DebugLogger.log(TAG, "AsrService created (PID: ${android.os.Process.myPid()})")
        // Receipt verification and native model loading are deliberately off the service main thread.
        transcriptionExecutor.execute { initRecognizer() }
    }

    override fun onDestroy() {
        transcriptionExecutor.shutdownNow()
        super.onDestroy()
        recognizer?.release()
        recognizer = null
        modelReady = false
        DebugLogger.log(TAG, "AsrService destroyed, recognizer released")
    }

    private fun initRecognizer() {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val modelDir = ModelStorage.directory(this, ModelManifest.parakeet)
            if (!ModelStorage.isReady(this, ModelManifest.parakeet)) {
                DebugLogger.warn(TAG, "Parakeet model is not verified in app-private storage")
                modelReady = false
                return
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

            recognizer = OfflineRecognizer(null, config)
            modelReady = true

            val elapsed = SystemClock.elapsedRealtime() - t0
            DebugLogger.log(TAG, "Recognizer initialized in ${elapsed}ms")
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Failed to initialize recognizer", e)
            modelReady = false
        }
    }

}
