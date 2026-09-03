package com.envi.wispr.cleanup

/** Why the pipeline ended where it did, so a consumer never infers it from two booleans. */
enum class PipelineOutcome {
    /** Deterministic cleanup returned the original text; the model was not consulted. */
    CLEANUP_RECOVERED,
    /** Cleanup left nothing to polish; the model was not consulted. */
    EMPTY_AFTER_CLEANUP,
    /** No model was offered. */
    NO_MODEL,
    /** Three words or fewer (or under ten characters in a script without spaces): never sent to a model (#2). */
    TOO_SHORT,
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
    /** Which guard refused the model's output (counts only, never text), for the log; null otherwise. */
    val refusal: String? = null,
)

/** Pure ordering boundary for cleanup, optional S1, safety validation, and spelling restoration. */
object PolishPipeline {
    fun run(
        rawText: String,
        options: CleanupOptions = CleanupOptions(),
        language: CleanupLanguage = CleanupLanguage.Unknown,
        model: ((cleanedText: String) -> String?)? = null,
    ): PolishPipelineResult {
        val cleanup = DeterministicCleanup.apply(rawText, options, language)
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
        if (tooShortForPolish(cleaned)) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = false, outcome = PipelineOutcome.TOO_SHORT)
        }
        val candidate = runCatching { model.invoke(cleaned)?.substringAfterLast("</think>")?.trim() }.getOrNull()
        if (candidate.isNullOrBlank()) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = true, outcome = PipelineOutcome.MODEL_DECLINED)
        }
        val refusal = TextSafety.refusal(cleaned, candidate)
        if (refusal != null) {
            return PolishPipelineResult(fallback, usedModel = false, recovered = true, outcome = PipelineOutcome.MODEL_REJECTED, refusal = refusal)
        }
        return PolishPipelineResult(candidate, usedModel = true, recovered = false, outcome = PipelineOutcome.MODEL_ACCEPTED)
    }

    /**
     * The Mac's too-short bypass (`LLMPolishStep.minWordsForPolish`, `minCharsForCJKPolish`): models
     * hallucinate on ultra-short input ("Yeah" into an essay). Three words or fewer pass through; text in a
     * script without spaces (CJK, Thai, Lao) is judged by characters, under ten passes through.
     */
    internal fun tooShortForPolish(cleaned: String): Boolean {
        // Code points, not chars: a supplementary-plane Han character is a surrogate pair that no Char test sees.
        val letters = cleaned.codePoints().toArray().filter { Character.isLetter(it) }
        if (letters.isNotEmpty() && letters.count { isSpacelessScript(it) } * 2 >= letters.size) {
            return letters.size < MIN_CHARS_SPACELESS
        }
        return cleaned.split(Regex("\\s+")).count { it.isNotEmpty() } <= MIN_WORDS_FOR_POLISH
    }

    private fun isSpacelessScript(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        -> true
        else -> false
    }

    private const val MIN_WORDS_FOR_POLISH = 3
    private const val MIN_CHARS_SPACELESS = 10
}
