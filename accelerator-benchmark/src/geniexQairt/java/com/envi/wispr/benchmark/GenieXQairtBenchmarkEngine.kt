package com.envi.wispr.benchmark

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.flow.collect

internal class FlavorBenchmarkEngine(
    private val context: Context
) : BenchmarkEngine {
    override val backend = "geniex_qairt"
    override val computeUnit = "npu"

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

        var importFailure: String? = null
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = MODEL_NAME,
                hub = HubSource.LOCALFS,
                local_path = modelPath
            )
        ).collect { event ->
            if (event is ModelManagerWrapper.PullEvent.Error) {
                importFailure = "QAIRT import failed (${event.code}): ${event.message}"
            }
        }
        importFailure?.let { error(it) }

        val paths = checkNotNull(ModelManagerWrapper.getPaths(MODEL_NAME)) {
            "QAIRT model import completed without registered paths"
        }
        check(paths.runtime_id == "qairt") {
            "Expected qairt runtime, found ${paths.runtime_id}"
        }

        llm = LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_path = paths.model_path,
                    tokenizer_path = paths.tokenizer_path,
                    config = ModelConfig(
                        nCtx = 0,
                        nThreads = 0,
                        nThreadsBatch = 0,
                        nBatch = 0,
                        nUBatch = 0,
                        nSeqMax = 1,
                        nGpuLayers = 0
                    ),
                    runtime_id = paths.runtime_id,
                    compute_unit = null
                )
            )
            .build()
            .getOrThrow()
        return "GenieX 0.4.0 QAIRT NPU, imported=${paths.model_name}"
    }

    override suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): EngineGeneration {
        val activeLlm = checkNotNull(llm) { "GenieX QAIRT model is not loaded" }
        check(activeLlm.reset() == 0) { "GenieX QAIRT context reset failed" }

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

        val measured = checkNotNull(profile) { "GenieX QAIRT completed without profiling data" }
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

    private companion object {
        const val MODEL_NAME = "local/qwen3-0.6b-qairt-s26"
    }
}
