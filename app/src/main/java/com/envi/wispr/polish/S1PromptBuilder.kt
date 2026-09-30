package com.envi.wispr.polish

import kotlin.math.roundToInt

internal object S1PromptBuilder {
    private const val MAX_CUSTOM_WORDS = 50
    private const val MAX_CUSTOM_WORD_LENGTH = 50
    internal const val MAX_OUTPUT_TOKENS = 1024

    /**
     * The control line is the caller's choice, carried on the session's latched
     * [PolishPolicy.LocalS1]; this builder never picks a value itself (#152).
     *
     * S1-mini was trained with a closed control-line vocabulary. Custom terms are restored after
     * generation, so adding them to Context makes the prompt unsupported and less stable.
     */
    fun buildUserPrompt(rawText: String, control: S1ControlSettings): String =
        "${control.controlLine()}\n${rawText.trim()}"

    /**
     * The cap is a runaway guard, not a length target: the cooperative deadline bounds time. It
     * should not bind on a faithful answer. Prose costs about one token per 4 chars, but spoken
     * number lists cost several tokens per item (digits split, comma, space) and polish also turns
     * number words into digits. The old chars/3.5 estimate is the SUSPECTED cause of a twenty-item
     * count ending after the seventeenth item (#385; cap versus end of sequence is not yet logged).
     * Two chars per token is an estimate for such lists; the multiplier and floor add headroom. The
     * ceiling leaves room in the model's context ([S1Config.CONTEXT_SIZE]) for the system prompt and
     * the input.
     */
    fun maxOutputTokens(rawText: String): Int {
        val estimatedTokens = (rawText.length / 2.0).roundToInt().coerceAtLeast(1)
        return (estimatedTokens * 1.5 + 64).roundToInt().coerceIn(128, MAX_OUTPUT_TOKENS)
    }

    /** Legacy flat-word migration sanitizer. It does not participate in runtime matching. */
    fun sanitizeCustomWords(words: List<String>): List<String> = words
        .asSequence()
        .map { it.replace(Regex("[\\r\\n\\[\\]]"), " ").trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(MAX_CUSTOM_WORD_LENGTH) }
        .distinctBy { it.lowercase() }
        .take(MAX_CUSTOM_WORDS)
        .toList()

}
