package com.envi.wispr.providers

import com.envi.wispr.vocabulary.CustomTerm

/**
 * Renders user custom vocabulary entries into a prompt-safe block for cloud polish (#88), matching
 * macOS `CustomVocabularyFormatter.render` (max 50 words, max 2,000 characters, injection-safe
 * sanitization). Returns null when no usable entries remain.
 */
internal object CustomVocabularyFormatter {
    const val MAX_WORDS = 50
    const val MAX_CHARS = 2000

    const val FULL_HEADER =
        "CUSTOM VOCABULARY: The following are the user's preferred spellings. " +
            "When the transcript contains similar-sounding words, use these exact spellings:"

    private val LINE_BREAK = Regex("[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]+")
    private val MULTI_SPACE = Regex("\\s{2,}")

    fun render(words: List<CustomTerm>): String? {
        if (words.isEmpty()) return null
        // Sort higher priority first (`priority DESC`), matching CustomTermDao and VocabularyCore.rank,
        // so user-added terms (priority 0) are never crowded out by lower-priority imports (-10).
        val sorted = words
            .sortedWith(compareByDescending<CustomTerm> { it.priority }.thenBy { it.spelling })
            .take(MAX_WORDS)
        val lines = ArrayList<String>(sorted.size)
        var charCount = FULL_HEADER.length
        for (word in sorted) {
            val clean = sanitize(word.spelling)
            if (clean.isEmpty()) continue
            val cleanAliases = word.aliases.map(::sanitize).filter(String::isNotEmpty)
            val line = if (cleanAliases.isEmpty()) {
                "- $clean"
            } else {
                "- $clean (may be misheard as: ${cleanAliases.joinToString(", ")})"
            }
            if (charCount + 1 + line.length > MAX_CHARS) break
            lines.add(line)
            charCount += 1 + line.length
        }
        if (lines.isEmpty()) return null
        return FULL_HEADER + "\n" + lines.joinToString("\n")
    }

    /** Strip prompt-injection-risky characters and collapse whitespace, matching macOS. */
    fun sanitize(text: String): String =
        text.trim()
            .replace(LINE_BREAK, " ")
            .replace("`", "'")
            .replace("<", "")
            .replace(">", "")
            .replace(MULTI_SPACE, " ")
            .trim()
}
