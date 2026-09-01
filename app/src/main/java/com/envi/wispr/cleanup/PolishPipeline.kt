package com.envi.wispr.cleanup

/** Why the pipeline ended where it did, so a consumer never infers it from two booleans. */
enum class PipelineOutcome {
    /** Deterministic cleanup returned the original text; the model was not consulted. */
    CLEANUP_RECOVERED,
    /** Cleanup left nothing to polish; the model was not consulted. */
    EMPTY_AFTER_CLEANUP,
    /** No model was offered. */
    NO_MODEL,
    /** The model returned null, blank, or threw. */
    MODEL_DECLINED,
    /** The model returned text that failed the safety check. */
    MODEL_REJECTED,
    MODEL_ACCEPTED,
}

data class PolishPipelineResult(
    val text: String,
    val usedModel: Boolean,
    val recovered: Boolean,
    val outcome: PipelineOutcome,
)

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
        if (cleanup.recovered) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = true, outcome = PipelineOutcome.CLEANUP_RECOVERED)
        }
        if (cleaned.isBlank()) {
            return PolishPipelineResult(cleaned, usedModel = false, recovered = false, outcome = PipelineOutcome.EMPTY_AFTER_CLEANUP)
        }
        if (model == null) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = false, outcome = PipelineOutcome.NO_MODEL)
        }
        val candidate = runCatching { model.invoke(cleaned)?.substringAfterLast("</think>")?.trim() }.getOrNull()
        if (candidate.isNullOrBlank()) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = true, outcome = PipelineOutcome.MODEL_DECLINED)
        }
        if (!TextSafety.isSafe(cleaned, candidate)) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = true, outcome = PipelineOutcome.MODEL_REJECTED)
        }
        return PolishPipelineResult(candidate, usedModel = true, recovered = false, outcome = PipelineOutcome.MODEL_ACCEPTED)
    }
}
