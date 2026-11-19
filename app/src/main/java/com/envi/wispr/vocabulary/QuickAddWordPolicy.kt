package com.envi.wispr.vocabulary

/**
 * Pure normalization and match-ranking policy for adding selected text from another app
 * via Android's `ACTION_PROCESS_TEXT` menu (#31).
 */
internal object QuickAddWordPolicy {
    const val MAX_SPELLING_LENGTH: Int = 200
    const val MAX_RAW_INPUT_CHARS: Int = 4_096

    /** Matches ASCII whitespace, Unicode space separators (including NBSP), and line separators. */
    private val UNICODE_WHITESPACE_RUN = Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029\\uFEFF]+")

    sealed interface Normalization {
        data object Blank : Normalization
        data object TooLong : Normalization
        data class Valid(val spelling: String) : Normalization
    }

    /**
     * Caps raw untrusted input before regex normalization so a huge selection from an external app
     * cannot stall the UI thread, then collapses all Unicode whitespace runs into single spaces.
     */
    fun normalize(raw: CharSequence?): Normalization {
        if (raw == null) return Normalization.Blank
        val capped = if (raw.length > MAX_RAW_INPUT_CHARS) raw.subSequence(0, MAX_RAW_INPUT_CHARS) else raw
        val collapsed = UNICODE_WHITESPACE_RUN.replace(capped.toString(), " ").trim()
        return when {
            collapsed.isEmpty() -> if (raw.length > MAX_RAW_INPUT_CHARS) Normalization.TooLong else Normalization.Blank
            raw.length > MAX_RAW_INPUT_CHARS || collapsed.length > MAX_SPELLING_LENGTH -> Normalization.TooLong
            else -> Normalization.Valid(collapsed)
        }
    }

    fun normalizeCandidate(raw: CharSequence?): String? = when (val result = normalize(raw)) {
        is Normalization.Valid -> result.spelling
        Normalization.Blank, Normalization.TooLong -> null
    }

    /**
     * Normalizes [query] with the exact same Unicode whitespace rule used on save so live
     * suggestions, duplicate previews, and saved spellings always agree.
     */
    fun normalizeQuery(query: String): String {
        val capped = if (query.length > MAX_RAW_INPUT_CHARS) query.substring(0, MAX_RAW_INPUT_CHARS) else query
        return UNICODE_WHITESPACE_RUN.replace(capped, " ").trim()
    }

    /**
     * Returns up to [limit] existing terms related to [query], ordered by [QuickAddRanking].
     */
    fun matchingTerms(
        query: String,
        existing: List<CustomTerm>,
        limit: Int = 3,
    ): List<CustomTerm> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val matches = existing.filter { term ->
            normalizeQuery(term.spelling).contains(needle, ignoreCase = true) ||
                term.aliases.any { normalizeQuery(it).contains(needle, ignoreCase = true) }
        }
        // Rank using the same Unicode whitespace normalization applied to the query, while keeping
        // the original `CustomTerm` objects for display.
        val normalizedToOriginals = LinkedHashMap<CustomTerm, ArrayDeque<CustomTerm>>()
        val normalizedMatches = matches.map { original ->
            val key = original.copy(spelling = normalizeQuery(original.spelling))
            normalizedToOriginals.getOrPut(key) { ArrayDeque() }.addLast(original)
            key
        }
        return QuickAddRanking.rank(needle, normalizedMatches)
            .take(limit)
            .map { key -> normalizedToOriginals.getValue(key).removeFirst() }
    }

    fun hasExactMatch(query: String, existing: List<CustomTerm>): Boolean {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return false
        return existing.any { normalizeQuery(it.spelling).equals(needle, ignoreCase = true) }
    }
}
