package com.envi.wispr.polish

import kotlin.math.roundToInt

internal object S1PromptBuilder {
    private const val MAX_CUSTOM_WORDS = 50
    private const val MAX_CUSTOM_WORD_LENGTH = 50

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
     * The cap is a runaway guard, not a length target: the cooperative deadline bounds time. It must
     * never bind on a faithful answer. Prose costs about one token per 4 chars, but spoken number
     * lists cost several tokens per item (digits split, comma, space) and polish also turns number
     * words into digits, so a chars/3.5 estimate cut a twenty-item count off after the seventeenth
     * item (#385). Two chars per token is the worst case; the multiplier and floor add headroom.
     */
    fun maxOutputTokens(rawText: String): Int {
        val worstCaseTokens = (rawText.length / 2.0).roundToInt().coerceAtLeast(1)
        return (worstCaseTokens * 1.5 + 64).roundToInt().coerceIn(128, 2048)
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
