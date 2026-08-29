package com.envi.wispr.benchmark

import android.content.Context
import com.envi.wispr.llama.S1Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FlavorBenchmarkEngine(
    private val context: Context
) : BenchmarkEngine {
    override val backend = "llama_cpp_cpu"
    override val computeUnit = "cpu"

    override suspend fun load(modelPath: String): String = withContext(Dispatchers.IO) {
        val systemInfo = S1Native.initialize(context.applicationInfo.nativeLibraryDir)
        val status = S1Native.loadModel(modelPath, CONTEXT_SIZE, THREAD_COUNT)
        if (status.startsWith("ERROR:")) error(status)
        "$status | $systemInfo"
    }

    override suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): EngineGeneration = withContext(Dispatchers.IO) {
        val output = S1Native.generate(systemPrompt, userPrompt, maxTokens)
        if (output.startsWith("ERROR:")) error(output)
        EngineGeneration(output = output.substringAfterLast("</think>").trim())
    }

    override fun close() {
        S1Native.unload()
    }

    companion object {
        private const val CONTEXT_SIZE = 2048
        private const val THREAD_COUNT = 4
    }
}
