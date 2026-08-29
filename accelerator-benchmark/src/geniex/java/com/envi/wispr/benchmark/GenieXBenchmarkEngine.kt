package com.envi.wispr.benchmark

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.flow.collect

internal class FlavorBenchmarkEngine(
    private val context: Context
) : BenchmarkEngine {
    override val backend = "geniex_llama_cpp"
    override val computeUnit = BuildConfig.BENCHMARK_COMPUTE_UNIT

    private var llm: LlmWrapper? = null

    override suspend fun load(modelPath: String): String {
        var initFailure: String? = null
        GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
            override fun onSuccess() = Unit
            override fun onFailure(reason: String) {
                initFailure = reason
            }
        })
        initFailure?.let { error(it) }

        llm = LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_path = modelPath,
                    config = ModelConfig(
                        nCtx = BuildConfig.BENCHMARK_CONTEXT_SIZE,
                        nThreads = BuildConfig.BENCHMARK_THREAD_COUNT,
                        nThreadsBatch = BuildConfig.BENCHMARK_THREAD_COUNT,
                        nBatch = BuildConfig.BENCHMARK_BATCH_SIZE,
                        nUBatch = BuildConfig.BENCHMARK_UBATCH_SIZE,
                        nSeqMax = 1,
                        nGpuLayers = -1,
                        spec_type = BuildConfig.BENCHMARK_SPEC_TYPE,
                        spec_n_max = BuildConfig.BENCHMARK_SPEC_MAX
                    ),
                    runtime_id = "llama_cpp",
                    compute_unit = computeUnit
                )
            )
            .build()
            .getOrThrow()
        return "GenieX 0.4.0 llama_cpp $computeUnit, ctx=${BuildConfig.BENCHMARK_CONTEXT_SIZE}, " +
            "threads=${BuildConfig.BENCHMARK_THREAD_COUNT}, batch=${BuildConfig.BENCHMARK_BATCH_SIZE}, " +
            "ubatch=${BuildConfig.BENCHMARK_UBATCH_SIZE}, " +
            "spec=${BuildConfig.BENCHMARK_SPEC_TYPE.ifEmpty { "none" }}"
    }

    override suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): EngineGeneration {
        val activeLlm = checkNotNull(llm) { "GenieX model is not loaded" }
        check(activeLlm.reset() == 0) { "GenieX context reset failed" }

        val formatted = activeLlm.applyChatTemplate(
            messages = arrayOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            tools = null,
            enableThinking = false,
            addGenerationPrompt = true
        ).getOrThrow().formattedText

        val sampler = SamplerConfig().apply {
            temperature = 0.000001f
            topK = 1
            topP = 1.0f
            minP = 0.000001f
            repetitionPenalty = 1.0f
            seed = 1
        }
        val generationConfig = GenerationConfig().apply {
            this.maxTokens = maxTokens
            samplerConfig = sampler
        }

        val output = StringBuilder()
        var profile: ProfilingData? = null
        activeLlm.generateStreamFlow(formatted, generationConfig).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> output.append(result.text)
                is LlmStreamResult.Completed -> profile = result.profile
                is LlmStreamResult.Error -> throw result.throwable
            }
        }

        val measured = checkNotNull(profile) { "GenieX completed without profiling data" }
        return EngineGeneration(
            output = output.toString().substringAfterLast("</think>").trim(),
            firstTokenMs = measured.ttftMs,
            promptMs = measured.promptTimeMs,
            decodeMs = measured.decodeTimeMs,
            promptTokens = measured.promptTokens,
            outputTokens = measured.generatedTokens,
            prefillTokensPerSecond = measured.prefillSpeed,
            decodeTokensPerSecond = measured.decodingSpeed,
            stopReason = measured.stopReason
        )
    }

    override fun close() {
        llm?.close()
        llm = null
    }
}
