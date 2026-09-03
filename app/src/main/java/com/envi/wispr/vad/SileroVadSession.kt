package com.envi.wispr.vad

import android.content.res.AssetManager
import com.envi.wispr.debug.DebugLogger
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.security.MessageDigest

/**
 * One take's detector: the model handle, the framing, and the decision.
 *
 * The only file in this app that touches the sherpa VAD API. Everything above it sees a block of PCM go
 * in and a verdict come out.
 *
 * **The model is verified before the library is allowed near it.** sherpa-onnx does not throw on a model
 * or input contract violation, it calls `exit(-1)`, which is not catchable from Kotlin. Verification does
 * not make that safe on its own, which is why this whole class lives in its own process; it makes the
 * failure rare rather than survivable. Both, not either.
 */
internal class SileroVadSession private constructor(
    private val vad: Vad,
    private val detector: SilenceStopDetector,
) {

    private val samples = FloatArray(SilenceStopDetector.SAMPLES_PER_BLOCK)
    private val window = FloatArray(SilenceStopDetector.WINDOW_SAMPLES)
    private val windowProbabilities = FloatArray(SilenceStopDetector.WINDOWS_PER_BLOCK)

    /** True when this block ended the take. */
    fun processBlock(pcm16: ByteArray): Boolean {
        val sampleCount = decodeInto(pcm16, samples)
        if (sampleCount < SilenceStopDetector.WINDOW_SAMPLES) return false

        var produced = 0
        var offset = 0
        while (offset + SilenceStopDetector.WINDOW_SAMPLES <= sampleCount &&
            produced < windowProbabilities.size
        ) {
            System.arraycopy(samples, offset, window, 0, SilenceStopDetector.WINDOW_SAMPLES)
            // compute() advances the model's own recurrent state, so calling it in order IS the
            // streaming contract. acceptWaveform must never also be called on this handle: each
            // advances that state, and both would advance it twice for the same audio.
            windowProbabilities[produced] = vad.compute(window)
            produced++
            offset += SilenceStopDetector.WINDOW_SAMPLES
        }
        if (produced == 0) return false

        val block = SilenceStopDetector.blockProbability(windowProbabilities.copyOf(produced))
        return detector.onBlock(block)
    }

    fun release() {
        runCatching { vad.release() }
            .onFailure { DebugLogger.warn(TAG, "Detector release failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "SileroVad"

        const val ASSET_NAME = "silero_vad.onnx"

        /**
         * The pinned artifact. From the sherpa-onnx asr-models release, fetched 2026-09-03, and
         * confirmed by its own embedded metadata to be silero-vad v4 exported to ONNX by k2-fsa with only
         * the 16 kHz branch kept. The bytes are the pin; the URL is only provenance, because the file
         * ships inside the signed APK and cannot change under us.
         */
        const val EXPECTED_BYTES = 643_854L
        const val EXPECTED_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

        /**
         * Segmentation values sherpa validates at construction but which this path never uses: the
         * decision comes from compute()'s raw probability through our own state machine, not from
         * sherpa's segment queue. They are set to values sherpa accepts and nothing more.
         */
        private const val UNUSED_MIN_SILENCE_SECONDS = 0.5f
        private const val UNUSED_MIN_SPEECH_SECONDS = 0.25f
        private const val UNUSED_MAX_SPEECH_SECONDS = 20f

        /**
         * Open a detector for one take, or null if the model is not exactly the file we shipped.
         *
         * Returning null rather than throwing is the point: the caller turns it into "auto-stop is
         * unavailable for this take" and carries on recording.
         */
        fun open(assets: AssetManager, pauseSeconds: Float): SileroVadSession? {
            if (!modelIsExactlyOurs(assets)) return null

            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ASSET_NAME,
                    threshold = SilenceStopDetector.SENSITIVITY,
                    minSilenceDuration = UNUSED_MIN_SILENCE_SECONDS,
                    minSpeechDuration = UNUSED_MIN_SPEECH_SECONDS,
                    windowSize = SilenceStopDetector.WINDOW_SAMPLES,
                    maxSpeechDuration = UNUSED_MAX_SPEECH_SECONDS,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )

            val vad = runCatching { Vad(assets, config) }
                .onFailure { DebugLogger.error(TAG, "Detector could not be created", it) }
                .getOrNull() ?: return null

            runCatching { vad.reset() }
            return SileroVadSession(vad, SilenceStopDetector(pauseSeconds))
        }

        /**
         * Exact size and exact hash, then use; otherwise take the safe path. The same shape
         * `S1ModelSelector.resolve` already uses for the development polish model.
         */
        private fun modelIsExactlyOurs(assets: AssetManager): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val outcome = runCatching {
                assets.open(ASSET_NAME).buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        total += count
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val hash = outcome.getOrElse {
                DebugLogger.error(TAG, "Detector model could not be read", it)
                return false
            }
            if (total != EXPECTED_BYTES || hash != EXPECTED_SHA256) {
                DebugLogger.error(
                    TAG,
                    "Detector model is not the one we shipped: $total bytes, refusing to load it",
                )
                return false
            }
            return true
        }

        /** Little-endian PCM16 to float. Returns how many samples were written. */
        internal fun decodeInto(pcm16: ByteArray, out: FloatArray): Int {
            val count = minOf(pcm16.size / 2, out.size)
            for (i in 0 until count) {
                val offset = i * 2
                val value = (pcm16[offset].toInt() and 0xFF) or (pcm16[offset + 1].toInt() shl 8)
                out[i] = value / 32768.0f
            }
            return count
        }
    }
}
