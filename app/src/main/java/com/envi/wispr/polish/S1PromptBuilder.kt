package com.envi.wispr.polish

import kotlin.math.roundToInt

object S1PromptBuilder {
    private const val MAX_CUSTOM_WORDS = 50
    private const val MAX_CUSTOM_WORD_LENGTH = 50

    fun buildUserPrompt(rawText: String): String {
        // S1-mini was trained with a closed control-line vocabulary. Custom terms are restored
        // after generation, so adding them to Context makes the prompt unsupported and less stable.
        // The trained `lists` mode is conservative: it formats clear enumerations of at least
        // three items, while ordinary dictation remains prose. Using `prose` here disabled list
        // formatting for every phone dictation regardless of what the user said.
        return "[Styling: semi-formal] [Structure: lists] [Context: general]\n${rawText.trim()}"
    }

    fun maxOutputTokens(rawText: String): Int {
        val estimatedInputTokens = (rawText.length / 3.5).roundToInt().coerceAtLeast(1)
        return (estimatedInputTokens * 1.3 + 32).roundToInt().coerceIn(64, 512)
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
