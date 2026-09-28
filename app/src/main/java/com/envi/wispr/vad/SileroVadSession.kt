package com.envi.wispr.vad

import android.content.res.AssetManager
import com.envi.wispr.debug.DebugLogger
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * One take's detector: the model handle, the framing, and the decision.
 *
 * The only file in this app that runs the Silero model. Everything above it sees a block of PCM go in
 * and a verdict come out.
 *
 * Since #374 it runs on ONNX Runtime directly, with the recurrent contract sherpa-onnx 1.12.29 used for this
 * v4 export (`silero-vad-model.cc` RunV4 and ResetV4): one 512-sample window `x` per call, the returned `new_h`
 * and `new_c` carried into the next call in order, both zeroed at every take start. **The model is still verified
 * before the runtime is allowed near it**, and the class still lives in its own process.
 */
internal class SileroVadSession private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val detector: SilenceStopDetector,
) {
    private var h = FloatArray(STATE_SIZE)
    private var c = FloatArray(STATE_SIZE)

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
            // Each call advances the recurrent state, so calling it in window order IS the streaming contract.
            val probability = compute(window)
            check(probability.isFinite() && probability in 0f..1f) {
                "Silero returned an invalid speech probability"
            }
            windowProbabilities[produced] = probability
            produced++
            offset += SilenceStopDetector.WINDOW_SAMPLES
        }
        if (produced == 0) return false

        val block = SilenceStopDetector.blockProbability(windowProbabilities.copyOf(produced))
        return detector.onBlock(block)
    }

    /** One window through the model, carrying `h` and `c` to the next call. Closes every tensor and result. */
    private fun compute(window: FloatArray): Float {
        // Registered one by one inside the try, so a creation that throws still closes the ones made before it.
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["x"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(window), longArrayOf(1, window.size.toLong()))
            inputs["h"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(h), STATE_SHAPE)
            inputs["c"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(c), STATE_SHAPE)
            session.run(inputs).use { result ->
                val probability = (result.get("prob").get() as OnnxTensor).floatBuffer.get(0)
                h = FloatArray(STATE_SIZE).also { (result.get("new_h").get() as OnnxTensor).floatBuffer.get(it) }
                c = FloatArray(STATE_SIZE).also { (result.get("new_c").get() as OnnxTensor).floatBuffer.get(it) }
                return probability
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    fun release() {
        runCatching { session.close() }
            .onFailure { DebugLogger.warn(TAG, "Detector release failed: ${it.javaClass.simpleName}") }
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

        /** The v4 export's recurrent state: 2 layers x batch 1 x 64 (SileroVadSessionTest pins the names). */
        private val STATE_SHAPE = longArrayOf(2, 1, 64)
        private const val STATE_SIZE = 2 * 1 * 64

        /**
         * Open a detector for one take, or null if the model is not exactly the file we shipped.
         *
         * Returning null rather than throwing is the point: the caller turns it into "auto-stop is
         * unavailable for this take" and carries on recording.
         */
        fun open(assets: AssetManager, pauseSeconds: Float): SileroVadSession? {
            if (!modelIsExactlyOurs(assets)) return null

            val environment = OrtEnvironment.getEnvironment()
            val session = runCatching {
                val bytes = assets.open(ASSET_NAME).use { it.readBytes() }
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    environment.createSession(bytes, options)
                }
            }
                .onFailure { DebugLogger.warn(TAG, "Detector could not be created: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return null

            return SileroVadSession(environment, session, SilenceStopDetector(pauseSeconds))
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
