package com.envi.wispr.asr

import ai.onnxruntime.OrtException
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Parakeet TDT 0.6b v3 on ONNX Runtime (#374): the onnx-asr layout of three sessions, the audio front end
 * (`nemo128.onnx`, shipped in the APK), the int8 encoder and the combined decoder/joint of the shipped speech model (`ModelManifest.parakeet`).
 *
 * Tensors/results and invocation-owned options close on the native worker. Required cleanup failure
 * remains typed so the owner can poison the process instead of treating incomplete free as success.
 */
internal class TdtModel private constructor(
    private val environment: OrtEnvironment,
    private val preprocessor: OrtSession,
    private val encoder: OrtSession,
    private val decoderJoint: OrtSession,
    override val vocab: List<String>,
) : TdtRunner {

    private class Invocation(
        val cancellation: AsrCancellation,
        val shrinking: OrtSession.RunOptions,
        val decoder: OrtSession.RunOptions,
        val observer: AsrObserver,
        val number: Long,
    ) { var serial = 0L }
    /** Accessed only by the native worker; cancellation receives only protected option controls. */
    private var invocation: Invocation? = null
    private var nextInvocation = 0L
    val modelIdentity: Long = identities.incrementAndGet()
    override val cancellation: AsrCancellation get() = checkNotNull(invocation).cancellation
    override val observer: AsrObserver get() = checkNotNull(invocation).observer

    fun <T> withInvocation(cancellation: AsrCancellation, observer: AsrObserver = AsrObserver.NONE, work: () -> T): T {
        check(invocation == null) { "Speech model already has an invocation" }
        cancellation.check()
        val options = ArrayList<OrtSession.RunOptions>(2)
        var lease: AutoCloseable? = null
        val retirement = AutoCloseable {
            invocation = null
            lease?.close() // Detach waits for any live control call before the worker closes its handles.
            closeAsrNative(options.asReversed())
        }
        return retirement.useAsrNative {
            val shrinking = OrtSession.RunOptions().also { options += it }
            shrinking.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0")
            val decoder = OrtSession.RunOptions().also { options += it }
            lease = cancellation.attach {
                var failure: Throwable? = null
                options.forEach { option ->
                    try { option.setTerminate(true) } catch (error: Throwable) {
                        val previous = failure
                        if (previous == null) failure = error else if (error !== previous) previous.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
            invocation = Invocation(cancellation, shrinking, decoder, observer, ++nextInvocation)
            try { cancellation.check(); work() }
            catch (failure: OrtException) {
                if (cancellation.isCancelled) throw AsrCancelled()
                throw failure
            }
        }
    }

    private fun run(stage: AsrObserver.Stage, session: OrtSession, options: OrtSession.RunOptions, work: () -> OrtSession.Result): OrtSession.Result {
        val active = checkNotNull(invocation)
        active.cancellation.check()
        val call = AsrObserver.Call(modelIdentity, active.number, ++active.serial, stage,
            System.identityHashCode(session), active.number * 2 + if (options === active.decoder) 1 else 0, Thread.currentThread().id)
        active.observer.entered(call)
        val result = try { work() } catch (failure: OrtException) {
            active.observer.failed(call, failure) // SAME run's raw status, before cancellation mapping.
            throw failure
        }
        try { active.observer.returned(call) }
        catch (failure: Throwable) { result.useAsrNative { throw failure } }
        return result
    }

    private val stateShape = decoderJoint.inputInfo.getValue("input_states_1").info.let {
        (it as ai.onnxruntime.TensorInfo).shape.copyOf().also { shape -> shape[1] = 1 }
    }
    private val stateSize = stateShape.fold(1L) { a, b -> a * b }.toInt()
    private val outputs = setOf("outputs", "output_states_1", "output_states_2")

    override fun encode(samples: FloatArray, length: Int): Encoded {
        cancellation.check()
        val active = checkNotNull(invocation)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).useAsrNative { wave ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(length.toLong())), longArrayOf(1)).useAsrNative { waveLength ->
                run(AsrObserver.Stage.PREPROCESSOR, preprocessor, active.shrinking) {
                    preprocessor.run(mapOf("waveforms" to wave, "waveforms_lens" to waveLength), active.shrinking)
                }.useAsrNative { features ->
                    val encoded = run(AsrObserver.Stage.ENCODER, encoder, active.shrinking) { encoder.run(
                        mapOf(
                            "audio_signal" to features.get("features").get() as OnnxTensor,
                            "length" to features.get("features_lens").get() as OnnxTensor,
                        ),
                        active.shrinking,
                    ) }
                    encoded.useAsrNative {
                        cancellation.check()
                        val output = encoded.get("outputs").get() as OnnxTensor
                        val shape = output.info.shape // [1, D, T]
                        val dims = shape[1].toInt()
                        val total = shape[2].toInt()
                        val valid = (encoded.get("encoded_lengths").get() as OnnxTensor).longBuffer.get(0).toInt()
                        val values = FloatArray(dims * total).also { output.floatBuffer.get(it) }
                        return Window(values, dims, total, min(valid, total))
                    }
                }
            }
        }
    }

    override fun initialState(): DecoderState = DecoderState(FloatArray(stateSize), FloatArray(stateSize))

    override fun step(encoded: Encoded, frame: Int, previousToken: Int, state: DecoderState): Step {
        cancellation.check()
        val active = checkNotNull(invocation)
        val window = encoded as Window
        val column = FloatArray(window.dims) { d -> window.values[d * window.total + frame] }
        // Registered one by one inside the try, so a creation that throws still closes the ones made before it.
        val inputs = LinkedHashMap<String, OnnxTensor>()
        AutoCloseable { closeAsrNative(inputs.values) }.useAsrNative {
            inputs["encoder_outputs"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(column), longArrayOf(1, window.dims.toLong(), 1))
            inputs["targets"] = OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(previousToken)), longArrayOf(1, 1))
            inputs["target_length"] = OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
            inputs["input_states_1"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state.first), stateShape)
            inputs["input_states_2"] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state.second), stateShape)
            run(AsrObserver.Stage.DECODER, decoderJoint, active.decoder) {
                decoderJoint.run(inputs, outputs, active.decoder)
            }.useAsrNative { result ->
                cancellation.check()
                val logits = (result.get("outputs").get() as OnnxTensor).floatBuffer
                val size = logits.remaining()
                val all = FloatArray(size).also { logits.get(it) }
                val vocabSize = vocab.size
                var token = 0
                for (i in 1 until vocabSize) { if (i % 256 == 0) cancellation.check(); if (all[i] > all[token]) token = i }
                var bin = 0
                for (i in vocabSize + 1 until size) { if (i % 256 == 0) cancellation.check(); if (all[i] > all[vocabSize + bin]) bin = i - vocabSize }
                val next = DecoderState(
                    FloatArray(stateSize).also { (result.get("output_states_1").get() as OnnxTensor).floatBuffer.get(it) },
                    FloatArray(stateSize).also { (result.get("output_states_2").get() as OnnxTensor).floatBuffer.get(it) },
                )
                return Step(token, bin, next)
            }
        }
    }

    override fun close() {
        check(invocation == null) { "Cannot close a speech model during native work" }
        closeAsrNative(listOf(decoderJoint, encoder, preprocessor))
    }

    private class Window(val values: FloatArray, val dims: Int, val total: Int, override val frames: Int) : Encoded {
        override fun close() = Unit
    }

    companion object {
        private val identities = AtomicLong()
        const val ENCODER = "encoder-model.int8.onnx"
        const val DECODER_JOINT = "decoder_joint-model.int8.onnx"
        const val VOCAB = "vocab.txt"

        /** Opens the three sessions, or throws after closing any it opened. [preprocessor] is the asset's bytes. */
        fun open(modelDirectory: File, preprocessor: ByteArray, threads: Int): TdtModel {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val opened = ArrayList<OrtSession>(3)
            try {
                options.useAsrNative {
                    options.setIntraOpNumThreads(threads)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    opened += environment.createSession(preprocessor, options)
                    opened += environment.createSession(File(modelDirectory, ENCODER).path, options)
                    opened += environment.createSession(File(modelDirectory, DECODER_JOINT).path, options)
                }
                return TdtModel(environment, opened[0], opened[1], opened[2], readVocab(File(modelDirectory, VOCAB)))
            } catch (failure: Throwable) {
                try { closeAsrNative(opened.asReversed()) } catch (cleanup: AsrCleanupFailure) {
                    if (cleanup !== failure) cleanup.addSuppressed(failure)
                    throw cleanup
                }
                throw failure
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
