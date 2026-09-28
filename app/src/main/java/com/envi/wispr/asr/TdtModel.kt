package com.envi.wispr.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Parakeet TDT 0.6b v3 on ONNX Runtime (#374): the onnx-asr layout of three sessions, the audio front end
 * (`nemo128.onnx`, shipped in the APK), the SmoothQuant int8 encoder and the combined decoder/joint.
 *
 * Every tensor and result is closed; the three sessions are closed by [close]. A partial construction closes what it
 * created before rethrowing, so a failed load never leaks a session.
 */
internal class TdtModel private constructor(
    private val environment: OrtEnvironment,
    private val preprocessor: OrtSession,
    private val encoder: OrtSession,
    private val decoderJoint: OrtSession,
    override val vocab: List<String>,
) : TdtRunner {

    private val stateShape = decoderJoint.inputInfo.getValue("input_states_1").info.let {
        (it as ai.onnxruntime.TensorInfo).shape.copyOf().also { shape -> shape[1] = 1 }
    }
    private val stateSize = stateShape.fold(1L) { a, b -> a * b }.toInt()
    private val outputs = setOf("outputs", "output_states_1", "output_states_2")

    override fun encode(samples: FloatArray, length: Int): Encoded {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { wave ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(length.toLong())), longArrayOf(1)).use { waveLength ->
                preprocessor.run(mapOf("waveforms" to wave, "waveforms_lens" to waveLength)).use { features ->
                    val encoded = encoder.run(
                        mapOf(
                            "audio_signal" to features.get("features").get() as OnnxTensor,
                            "length" to features.get("features_lens").get() as OnnxTensor,
                        ),
                    )
                    try {
                        val output = encoded.get("outputs").get() as OnnxTensor
                        val shape = output.info.shape // [1, D, T]
                        val dims = shape[1].toInt()
                        val total = shape[2].toInt()
                        val valid = (encoded.get("encoded_lengths").get() as OnnxTensor).longBuffer.get(0).toInt()
                        val values = FloatArray(dims * total).also { output.floatBuffer.get(it) }
                        return Window(values, dims, total, min(valid, total))
                    } finally {
                        encoded.close()
                    }
                }
            }
        }
    }

    override fun initialState(): DecoderState = DecoderState(FloatArray(stateSize), FloatArray(stateSize))

    override fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step {
        val window = encoded as Window
        val column = FloatArray(window.dims) { d -> window.values[d * window.total + frame] }
        // Registered one by one inside the try, so a creation that throws still closes the ones made before it.
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["encoder_outputs"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(column), longArrayOf(1, window.dims.toLong(), 1))
            inputs["targets"] = OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(previousToken)), longArrayOf(1, 1))
            inputs["target_length"] = OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
            inputs["input_states_1"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state.first), stateShape)
            inputs["input_states_2"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state.second), stateShape)
            decoderJoint.run(inputs, outputs).use { result ->
                val logits = (result.get("outputs").get() as OnnxTensor).floatBuffer
                val size = logits.remaining()
                val all = FloatArray(size).also { logits.get(it) }
                val vocabSize = vocab.size
                var token = 0
                for (i in 1 until vocabSize) if (all[i] > all[token]) token = i
                var bin = 0
                for (i in vocabSize + 1 until size) if (all[i] > all[vocabSize + bin]) bin = i - vocabSize
                val next = DecoderState(
                    FloatArray(stateSize).also { (result.get("output_states_1").get() as OnnxTensor).floatBuffer.get(it) },
                    FloatArray(stateSize).also { (result.get("output_states_2").get() as OnnxTensor).floatBuffer.get(it) },
                )
                return Step(token, bin, next)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        runCatching { decoderJoint.close() }
        runCatching { encoder.close() }
        runCatching { preprocessor.close() }
    }

    private class Window(val values: FloatArray, val dims: Int, val total: Int, override val frames: Int) : Encoded {
        override fun close() = Unit
    }

    companion object {
        const val ENCODER = "encoder-model.int8.onnx"
        const val DECODER_JOINT = "decoder_joint-model.int8.onnx"
        const val VOCAB = "vocab.txt"

        /** Opens the three sessions, or throws after closing any it opened. [preprocessor] is the asset's bytes. */
        fun open(modelDirectory: File, preprocessor: ByteArray, threads: Int): TdtModel {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val opened = ArrayList<OrtSession>(3)
            try {
                val front = environment.createSession(preprocessor, options).also { opened += it }
                val encoder = environment.createSession(File(modelDirectory, ENCODER).path, options).also { opened += it }
                val joint = environment.createSession(File(modelDirectory, DECODER_JOINT).path, options).also { opened += it }
                return TdtModel(environment, front, encoder, joint, readVocab(File(modelDirectory, VOCAB)))
            } catch (failure: Throwable) {
                opened.asReversed().forEach { runCatching { it.close() } }
                throw failure
            } finally {
                options.close()
            }
        }

        /** `vocab.txt`: one `<piece> <id>` per line. */
        fun readVocab(file: File): List<String> {
            val pieces = HashMap<Int, String>()
            file.forEachLine { line ->
                val split = line.lastIndexOf(' ')
                if (split > 0) pieces[line.substring(split + 1).toInt()] = line.substring(0, split)
            }
            return List((pieces.keys.maxOrNull() ?: -1) + 1) { pieces[it] ?: "" }
        }
    }
}

private fun min(a: Int, b: Int) = if (a < b) a else b
