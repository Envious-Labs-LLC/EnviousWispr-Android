package com.envi.wispr.polish

/**
 * Cleans up raw ASR output with regex-based rules.
 * Ported from macOS EnviousWispr pipeline.
 */
object RegexPolisher {

    // Filler words to remove (case insensitive, word-boundary matched)
    private val fillerPatterns = listOf(
        Regex("\\b(um|uh|erm|err|ah|hmm|hm|mhm)\\b", RegexOption.IGNORE_CASE),
    )

    fun polish(rawText: String, removeFillers: Boolean = true): String {
        if (rawText.isBlank()) return rawText

        var text = rawText.trim()

        // 1. Remove filler words only when the take's saved cleanup setting allows it.
        if (removeFillers) {
            for (pattern in fillerPatterns) {
                text = pattern.replace(text, "")
            }
        }

        // 2. Normalize whitespace (collapse multiple spaces, trim)
        text = text.replace(Regex("\\s{2,}"), " ").trim()

        // 3. Capitalize first letter of each sentence
        text = capitalizeSentences(text)

        // 4. Ensure terminal punctuation
        if (text.isNotEmpty() && text.last() !in ".!?") {
            text += "."
        }

        // 5. Fix spacing around punctuation
        text = text.replace(Regex("\\s+([.,!?;:])"), "$1")
        text = text.replace(Regex("([.,!?;:])(?=[A-Za-z])"), "$1 ")

        // 6. Final whitespace cleanup
        text = text.replace(Regex("\\s{2,}"), " ").trim()

        return text
    }

    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text

        val result = StringBuilder()
        var capitalizeNext = true

        for (char in text) {
            if (capitalizeNext && char.isLetter()) {
                result.append(char.uppercaseChar())
                capitalizeNext = false
            } else {
                result.append(char)
                if (char in ".!?") {
                    capitalizeNext = true
                }
            }
        }

        return result.toString()
    }
}
