package com.envi.wispr.asr

import java.io.File

/**
 * The speech engine `:asr` owns (#374): Parakeet TDT on ONNX Runtime ([TdtModel]) run through the macOS batch recipe
 * ([TdtRecipe]). Replaces the sherpa-onnx `OfflineRecognizer`; `AsrService` keeps its owner, watchdog and bounds.
 */
internal class ParakeetEngine private constructor(private val model: TdtModel) : AutoCloseable {
    private val recipe = TdtRecipe(model)

    fun transcribe(samples: FloatArray): TdtRecipe.Result = recipe.transcribe(samples)

    override fun close() = model.close()

    companion object {
        /** Name of the audio front end shipped in the APK (onnx-asr 0.12.0, MIT). */
        const val PREPROCESSOR_ASSET = "nemo128.onnx"
        const val THREADS = 4

        fun open(modelDirectory: File, preprocessor: ByteArray): ParakeetEngine =
            ParakeetEngine(TdtModel.open(modelDirectory, preprocessor, THREADS))
    }
}
