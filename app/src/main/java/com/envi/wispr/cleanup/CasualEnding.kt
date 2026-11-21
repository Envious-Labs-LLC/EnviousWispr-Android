package com.envi.wispr.cleanup

/**
 * Casual mode (#3737): leave off the full stop at the very end of a dictation.
 *
 * Removes the last non-whitespace character when it is a full stop (`.`, `。`, or `।`) and none
 * of the preservation rules hold (ellipsis, dotted abbreviation like `a.m.` or `U.S.`, an ordinal
 * dot right after a number outside English, a spoken `"period"`/`"full stop"` command, or blank output).
 */
internal object CasualEnding {
    private val fullStops = setOf('.', '\u3002', '\u0964')
    private val dottedAbbreviation = Regex("""^(?:\p{L}{1,2}\.){2,}$""")
    private val leadingOpeners = setOf('(', '[', '"', '\'', '\u201C', '\u2018')

    fun removingFinalFullStop(
        text: String,
        language: String?,
        spokenFinalFullStop: Boolean,
    ): String {
        if (spokenFinalFullStop) return text
        val lastIndex = text.indexOfLast { !it.isWhitespace() }
        if (lastIndex < 0) return text
        val mark = text[lastIndex]
        if (mark !in fullStops) return text

        val before = text.substring(0, lastIndex)
        if (before.lastOrNull() == '.') return text
        if (mark == '.') {
            if (endsWithNumber(before) && CleanupLanguagePolicy.baseCode(language) != "en") {
                return text
            }
            if (isDottedAbbreviation(finalWord(text.substring(0, lastIndex + 1)))) {
                return text
            }
        }

        val remaining = before + text.substring(lastIndex + 1)
        if (remaining.none { !it.isWhitespace() }) return text
        return remaining
    }

    /** Applies Casual mode once using exact cleanup provenance ([languageCode] and [spokenFinalFullStop]). */
    fun applyWithProvenance(
        text: String,
        casualMode: Boolean,
        languageCode: String?,
        spokenFinalFullStop: Boolean,
    ): String = if (casualMode && text.isNotBlank()) {
        removingFinalFullStop(text, languageCode, spokenFinalFullStop)
    } else {
        text
    }

    /** Applies Casual mode once using resolved language and original spoken-period provenance. */
    fun applyAfterCleanup(
        text: String,
        rawText: String,
        options: CleanupOptions,
        language: CleanupLanguage,
    ): String {
        if (!options.casualMode || text.isBlank()) return text
        val languageCode = (language as? CleanupLanguage.Known)?.code
        val spokenFinal = DeterministicCleanup.hasSpokenFinalFullStop(rawText, options, language)
        return applyWithProvenance(text, true, languageCode, spokenFinal)
    }

    /** Fails open to [text] if language detection or cleanup inspection throws. */
    fun applyFailOpen(
        text: String,
        rawText: String,
        options: CleanupOptions,
        detector: LanguageDetector,
    ): String {
        if (!options.casualMode || text.isBlank()) return text
        return runCatching {
            val language = CleanupLanguagePolicy.resolve(detector.detect(rawText))
            applyAfterCleanup(text, rawText, options, language)
        }.getOrDefault(text)
    }

    private fun endsWithNumber(before: String): Boolean {
        if (before.isEmpty()) return false
        return when (Character.getType(before.codePointBefore(before.length)).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            -> true
            else -> false
        }
    }

    private fun finalWord(text: String): String {
        val start = text.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        return text.substring(start).trimStart { it in leadingOpeners }
    }

    private fun isDottedAbbreviation(word: String): Boolean = dottedAbbreviation.matches(word)
}
