package com.envi.wispr.benchmark

import android.content.Context

internal interface BenchmarkEngine : AutoCloseable {
    val backend: String
    val computeUnit: String

    suspend fun load(modelPath: String): String

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): EngineGeneration
}

internal data class EngineGeneration(
    val output: String,
    val firstTokenMs: Double? = null,
    val promptMs: Double? = null,
    val decodeMs: Double? = null,
    val promptTokens: Long? = null,
    val outputTokens: Long? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val stopReason: String? = null
)

internal object BenchmarkEngineFactory {
    fun create(context: Context): BenchmarkEngine = FlavorBenchmarkEngine(context)
}
