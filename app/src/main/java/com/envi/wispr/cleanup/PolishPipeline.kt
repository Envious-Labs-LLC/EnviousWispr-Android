package com.envi.wispr.cleanup

data class PolishPipelineResult(val text: String, val usedModel: Boolean, val recovered: Boolean)

/** Pure ordering boundary for cleanup, optional S1, safety validation, and spelling restoration. */
object PolishPipeline {
    fun run(
        rawText: String,
        options: CleanupOptions = CleanupOptions(),
        model: ((cleanedText: String) -> String?)? = null,
    ): PolishPipelineResult {
        val cleanup = DeterministicCleanup.apply(rawText, options)
        val cleaned = cleanup.text
        val fallback = cleaned
        if (cleanup.recovered) return PolishPipelineResult(fallback, usedModel = false, recovered = true)
        if (cleaned.isBlank()) return PolishPipelineResult(cleaned, usedModel = false, recovered = false)
        val candidate = runCatching { model?.invoke(cleaned)?.substringAfterLast("</think>")?.trim() }.getOrNull()
        if (candidate.isNullOrBlank()) return PolishPipelineResult(fallback, usedModel = false, recovered = model != null)
        val restored = candidate
        if (!TextSafety.isSafe(cleaned, restored)) return PolishPipelineResult(fallback, usedModel = false, recovered = true)
        return PolishPipelineResult(restored, usedModel = true, recovered = false)
    }
}
