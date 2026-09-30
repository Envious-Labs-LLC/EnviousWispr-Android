package com.envi.wispr.polish

import android.content.Context
import android.system.Os
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How one generation ended (#385): the engine's own stop word and token counts, never text. */
internal data class GenerationEnd(val stopReason: String, val generatedTokens: Long, val promptTokens: Long, val cap: Int) {
    /** True when the answer used the whole budget, so it may have been cut off rather than finished. */
    val reachedCap: Boolean get() = generatedTokens >= cap

    /** One line for the local log: counts and the engine's stop word only. */
    fun logLine(): String =
        "S1 generation ended: stop=$stopReason generated=$generatedTokens prompt=$promptTokens cap=$cap reachedCap=$reachedCap"
}

/** Single owner for S1 inference. PolishService serializes every call onto one worker thread. */
internal class S1GenieXRuntime(private val context: Context) {
    private var llm: LlmWrapper? = null

    var activeComputeUnit: String = "unloaded"
        private set

    /**
     * How the last [generate] ended, as the engine reported it: counts and the engine's own stop word, never
     * text (#385: whether a cut-off answer stopped on the cap or on its own end of sequence). Null until a
     * generation completes; [generate] clears it first, so a timed-out or failed call leaves it null.
     */
    @Volatile
    var lastGeneration: GenerationEnd? = null
        private set

    fun load(modelPath: String, computeUnits: List<String>): String {
        close()
        initializeSdk()

        var lastFailure: Throwable? = null
        for (computeUnit in computeUnits) {
            val candidate = runCatching {
                runBlocking {
                    LlmWrapper.builder()
                        .llmCreateInput(
                            LlmCreateInput(
                                model_path = modelPath,
                                config = ModelConfig(
                                    nCtx = S1Config.CONTEXT_SIZE,
                                    nThreads = S1Config.THREAD_COUNT,
                                    nThreadsBatch = S1Config.THREAD_COUNT,
                                    nBatch = S1Config.BATCH_SIZE,
                                    nUBatch = S1Config.UBATCH_SIZE,
                                    nSeqMax = 1,
                                    nGpuLayers = -1,
                                ),
                                runtime_id = "llama_cpp",
                                compute_unit = computeUnit,
                            ),
                        )
                        .build()
                        .getOrThrow()
                }
            }
            candidate.onSuccess { loaded ->
                llm = loaded
                activeComputeUnit = computeUnit
                return "GenieX 0.4.0 llama.cpp on $computeUnit"
            }
            lastFailure = candidate.exceptionOrNull()
        }
        throw IllegalStateException("No S1 compute backend could load", lastFailure)
    }

    /**
     * @return the generated text, or null when [timeoutMs] expired first. The timeout is COOPERATIVE: it
     * cancels the collecting coroutine at a suspension point and cannot prove the native generation
     * stopped, so the caller treats null exactly like a hard expiry and never reuses this runtime (#75).
     */
    fun generate(systemPrompt: String, userPrompt: String, maxTokens: Int, timeoutMs: Long): String? {
        val active = checkNotNull(llm) { "S1 runtime is not loaded" }
        lastGeneration = null
        val output = StringBuilder()
        val completed = runBlocking {
          withTimeoutOrNull(timeoutMs) {
            check(active.reset() == 0) { "S1 context reset failed" }
            val formatted = active.applyChatTemplate(
                messages = arrayOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userPrompt),
                ),
                tools = null,
                enableThinking = false,
                addGenerationPrompt = true,
            ).getOrThrow().formattedText

            val sampler = SamplerConfig().apply {
                temperature = 0.000001f
                topK = 1
                topP = 1.0f
                minP = 0.000001f
                repetitionPenalty = 1.0f
                seed = 1
            }
            val generation = GenerationConfig().apply {
                this.maxTokens = maxTokens
                samplerConfig = sampler
            }
            active.generateStreamFlow(formatted, generation).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> output.append(result.text)
                    is LlmStreamResult.Completed -> lastGeneration = GenerationEnd(
                        stopReason = result.profile.stopReason,
                        generatedTokens = result.profile.generatedTokens,
                        promptTokens = result.profile.promptTokens,
                        cap = maxTokens,
                    )
                    is LlmStreamResult.Error -> throw result.throwable
                }
            }
            true
          }
        }
        return if (completed == true) output.toString() else null
    }

    fun close() {
        llm?.close()
        llm = null
        activeComputeUnit = "unloaded"
    }

    private fun initializeSdk() {
        // GenieX can emit full prompts at debug/verbose levels. Disable its logger before init;
        // EnviousWispr keeps its own content-free timing and backend diagnostics.
        Os.setenv("GENIEX_LOG", "none", true)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val sdk = GenieXSdk.getInstance()
        check(S1NativeLog.installContentFreeLogger() == 0) { "Unable to silence GenieX content logs" }
        sdk.init(context.applicationContext, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                completed.countDown()
            }

            override fun onFailure(reason: String) {
                failure.set(reason)
                completed.countDown()
            }
        })
        check(completed.await(10, TimeUnit.SECONDS)) { "GenieX initialization timed out" }
        failure.get()?.let { error(it) }
    }
}
