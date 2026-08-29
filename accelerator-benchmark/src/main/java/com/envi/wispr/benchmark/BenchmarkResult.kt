package com.envi.wispr.benchmark

import org.json.JSONObject

internal data class BenchmarkResult(
    val engine: String,
    val backend: String,
    val computeUnit: String,
    val model: String,
    val modelSha256: String,
    val cooldownMs: Int,
    val fixture: String,
    val iteration: Int,
    val coldLoadMs: Double?,
    val totalMs: Double,
    val firstTokenMs: Double?,
    val promptMs: Double?,
    val decodeMs: Double?,
    val promptTokens: Long?,
    val outputTokens: Long?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val outputSha256: String,
    val outputChars: Int,
    val syntheticOutput: String,
    val stopReason: String?,
    val pssBeforeKb: Long,
    val pssAfterKb: Long,
    val thermalBefore: Int,
    val thermalAfter: Int,
    val error: String?
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("engine", engine)
        put("backend", backend)
        put("compute_unit", computeUnit)
        put("context_size", BuildConfig.BENCHMARK_CONTEXT_SIZE)
        put("thread_count", BuildConfig.BENCHMARK_THREAD_COUNT)
        put("batch_size", BuildConfig.BENCHMARK_BATCH_SIZE)
        put("ubatch_size", BuildConfig.BENCHMARK_UBATCH_SIZE)
        put("spec_type", BuildConfig.BENCHMARK_SPEC_TYPE)
        put("spec_max", BuildConfig.BENCHMARK_SPEC_MAX)
        put("model", model)
        put("model_sha256", modelSha256)
        put("cooldown_ms", cooldownMs)
        put("fixture", fixture)
        put("iteration", iteration)
        putNullable("cold_load_ms", coldLoadMs)
        put("total_ms", totalMs)
        putNullable("first_token_ms", firstTokenMs)
        putNullable("prompt_ms", promptMs)
        putNullable("decode_ms", decodeMs)
        putNullable("prompt_tokens", promptTokens)
        putNullable("output_tokens", outputTokens)
        putNullable("prefill_tokens_per_second", prefillTokensPerSecond)
        putNullable("decode_tokens_per_second", decodeTokensPerSecond)
        put("output_sha256", outputSha256)
        put("output_chars", outputChars)
        put("synthetic_output", syntheticOutput)
        putNullable("stop_reason", stopReason)
        put("pss_before_kb", pssBeforeKb)
        put("pss_after_kb", pssAfterKb)
        put("thermal_before", thermalBefore)
        put("thermal_after", thermalAfter)
        putNullable("error", error)
    }.toString()

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
