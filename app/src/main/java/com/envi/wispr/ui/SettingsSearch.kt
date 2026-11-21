package com.envi.wispr.ui

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Text normalization, word tokenization, and Optimal String Alignment distance for Settings Search.
 *
 * Ports the word-matching half of macOS `SettingsSearch.swift` (`SearchText` and `SettingsSearchIndex`)
 * without shipping a 107 MB ML model on mobile: exact/prefix/substring multi-field weights, intent markers,
 * stop-word pruning, typo tolerance for 5+ character words, 3+ token single-miss allowance, and
 * `"Matches “word”"` hints when a synonym or keyword matched outside the visible card text.
 */
internal object SettingsSearchText {

    /** Unicode-normalized lowercase text: sharp S folded to `ss`, compatibility decomposed, marks stripped. */
    fun normalize(raw: String): String {
        val sharpFolded = raw
            .replace("\u00DF", "ss")
            .replace("\u1E9E", "ss")
        val decomposed = Normalizer.normalize(sharpFolded, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            val charCount = Character.charCount(cp)
            val type = Character.getType(cp)
            if (type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt()
            ) {
                out.appendCodePoint(cp)
            }
            i += charCount
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    /** Normalized lowercase word tokens, folding compatibility forms and diacritics before splitting. */
    fun words(raw: String): List<String> =
        surfaceWords(normalize(raw))

    /** Surface word tokens in original casing, keeping combining marks attached to their base letters. */
    fun surfaceWords(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (isWordCodePoint(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
            i += charCount
        }
        if (current.isNotEmpty()) {
            result += current.toString()
        }
        return result
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * Optimal String Alignment (restricted Damerau-Levenshtein) over Unicode code points, bounded by [cap].
     * An adjacent transposition counts as 1 edit; distances beyond [cap] return `cap + 1`.
     */
    fun editDistance(a: String, b: String, cap: Int): Int {
        val ac = a.codePoints().toArray()
        val bc = b.codePoints().toArray()
        val m = ac.size
        val n = bc.size
        if (abs(m - n) > cap) return cap + 1
        if (m == 0) return min(n, cap + 1)
        if (n == 0) return min(m, cap + 1)

        var prevPrev = IntArray(n + 1)
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            var rowMin = i
            val ca = ac[i - 1]
            for (j in 1..n) {
                val cb = bc[j - 1]
                val cost = if (ca == cb) 0 else 1
                var d = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost,
                )
                if (i > 1 && j > 1 && ca == bc[j - 2] && ac[i - 2] == cb) {
                    d = min(d, prevPrev[j - 2] + 1)
                }
                curr[j] = d
                if (d < rowMin) rowMin = d
            }
            if (rowMin > cap) return cap + 1
            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }
        return min(prev[n], cap + 1)
    }
}

internal enum class SettingsSearchEntryKind(val rank: Int) {
    DESTINATION(0),
    SECTION(1),
    SETTING(2),
    ACTION(3),
}

internal sealed interface SettingsSearchTarget {
    val sectionHint: String?

    data class Tab(
        val destination: AppDestination,
        override val sectionHint: String? = null,
    ) : SettingsSearchTarget

    data class Page(
        val page: SettingsPage,
        override val sectionHint: String? = null,
    ) : SettingsSearchTarget
}

internal data class SettingsSearchEntry(
    val id: String,
    val target: SettingsSearchTarget,
    val kind: SettingsSearchEntryKind,
    val title: String,
    val description: String,
    val breadcrumb: String,
    val developerOnly: Boolean = false,
    val onboardingOnly: Boolean = false,
    val keywords: List<String> = emptyList(),
    val synonymPhrases: List<String> = emptyList(),
)

internal data class SettingsSearchResult(
    val entry: SettingsSearchEntry,
    val matchedHint: String? = null,
)

internal class SettingsSearchIndex(entries: List<SettingsSearchEntry>) {

    private enum class Strength(val level: Int) {
        NONE(0),
        SUBSTRING(1),
        PREFIX(2),
        EXACT(3),
    }

    private data class PhraseRecord(
        val words: List<String>,
        val surface: String,
    )

    private data class IndexedEntry(
        val catalogOrder: Int,
        val entry: SettingsSearchEntry,
        val normTitle: String,
        val titleWords: List<String>,
        val descriptionWords: List<String>,
        val contextWords: List<String>,
        val keywordWords: List<String>,
        val keywordSurfaceByWord: Map<String, String>,
        val phrases: List<PhraseRecord>,
        val phraseWords: List<String>,
        val typoTargets: List<String>,
        val visibleWords: Set<String>,
    )

    private val indexed: List<IndexedEntry>

    init {
        indexed = entries.mapIndexed { order, entry ->
            val titleWords = SettingsSearchText.words(entry.title)
            val descriptionWords = SettingsSearchText.words(entry.description)
            val contextWords = SettingsSearchText.words(entry.breadcrumb)

            val kwSurface = LinkedHashMap<String, String>()
            val kwWords = mutableListOf<String>()
            entry.keywords.forEach { raw ->
                for (word in SettingsSearchText.words(raw)) {
                    kwWords += word
                    kwSurface.putIfAbsent(word, raw)
                }
            }

            val phraseRecords = entry.synonymPhrases.mapNotNull { raw ->
                val normalized = SettingsSearchText.words(raw)
                val content = normalized.filter { it !in STOP_WORDS || it in BASE_MARKERS }.ifEmpty { normalized }
                if (content.isEmpty()) null else PhraseRecord(words = content, surface = raw.trim())
            }
            val phraseWords = phraseRecords.flatMap { it.words }.distinct()

            val typoTargets = (titleWords + kwWords + contextWords + phraseWords)
                .filter { it.codePointCount(0, it.length) >= TYPO_TOKEN_MIN_LENGTH }
                .distinct()
                .sorted()

            val visibleWords = LinkedHashSet<String>().apply {
                addAll(titleWords)
                addAll(descriptionWords)
                addAll(contextWords)
            }

            IndexedEntry(
                catalogOrder = order,
                entry = entry,
                normTitle = titleWords.joinToString(" "),
                titleWords = titleWords,
                descriptionWords = descriptionWords,
                contextWords = contextWords,
                keywordWords = kwWords.distinct(),
                keywordSurfaceByWord = kwSurface,
                phrases = phraseRecords,
                phraseWords = phraseWords,
                typoTargets = typoTargets,
                visibleWords = visibleWords,
            )
        }
    }

    fun search(
        query: String,
        developerUnlocked: Boolean,
        cap: Int = DEFAULT_RESULT_CAP,
    ): List<SettingsSearchResult> {
        val queryWords = SettingsSearchText.words(query)
        if (queryWords.isEmpty()) return emptyList()
        val trimmedNormQuery = queryWords.joinToString(" ")

        val filtered = filterTokens(queryWords)
        if (filtered.isEmpty()) return emptyList()

        val requiredMarkers = filtered.filter { it in BASE_MARKERS }
        val minCovered = if (filtered.size >= 3) filtered.size - 1 else filtered.size

        val scored = mutableListOf<ScoredEntry>()
        for (item in indexed) {
            if (item.entry.developerOnly && !developerUnlocked) continue
            val candidate = scoreEntry(
                item = item,
                trimmedNormQuery = trimmedNormQuery,
                filtered = filtered,
                requiredMarkers = requiredMarkers,
                minCovered = minCovered,
            ) ?: continue
            scored += candidate
        }

        scored.sortWith(
            compareByDescending<ScoredEntry> { it.coverage }
                .thenByDescending { it.score }
                .thenBy { it.item.entry.kind.rank }
                .thenBy { it.item.catalogOrder },
        )

        return scored.take(cap).map {
            SettingsSearchResult(
                entry = it.item.entry,
                matchedHint = findMatchedHint(it.item, filtered),
            )
        }
    }

    private data class ScoredEntry(
        val item: IndexedEntry,
        val coverage: Int,
        val score: Int,
    )

    private fun filterTokens(rawTokens: List<String>): List<String> {
        val kept = rawTokens.filter { it !in STOP_WORDS || it in BASE_MARKERS }
        val base = kept.ifEmpty { rawTokens }
        return base.distinct()
    }

    private fun scoreEntry(
        item: IndexedEntry,
        trimmedNormQuery: String,
        filtered: List<String>,
        requiredMarkers: List<String>,
        minCovered: Int,
    ): ScoredEntry? {
        var totalScore = 0
        var covered = 0

        for (token in filtered) {
            val tokenScore = scoreToken(item, token)
            if (tokenScore > 0) {
                covered += 1
                totalScore += tokenScore
            } else if (token in requiredMarkers) {
                return null
            }
        }

        // Adjacent phrase matches can lift partial coverage and boost ranking cleanly.
        val phraseBonus = scorePhrases(item, filtered)
        totalScore += phraseBonus

        if (covered < minCovered) return null
        if (trimmedNormQuery == item.normTitle) {
            totalScore += WEIGHT_EXACT_TITLE_BONUS
        }
        return ScoredEntry(item = item, coverage = covered, score = totalScore)
    }

    private fun scoreToken(item: IndexedEntry, token: String): Int {
        val titleStr = fieldStrength(token, item.titleWords)
        val keywordStr = fieldStrength(token, item.keywordWords)
        val descStr = fieldStrength(token, item.descriptionWords)
        val ctxStr = fieldStrength(token, item.contextWords)

        var best = 0
        best = maxOf(
            best,
            when (titleStr) {
                Strength.EXACT -> WEIGHT_TITLE_EXACT
                Strength.PREFIX -> WEIGHT_TITLE_PREFIX
                Strength.SUBSTRING -> WEIGHT_TITLE_SUBSTRING
                Strength.NONE -> 0
            },
        )
        best = maxOf(
            best,
            when (keywordStr) {
                Strength.EXACT -> WEIGHT_KEYWORD_EXACT
                Strength.PREFIX -> WEIGHT_KEYWORD_PREFIX
                Strength.SUBSTRING -> WEIGHT_KEYWORD_SUBSTRING
                Strength.NONE -> 0
            },
        )
        best = maxOf(
            best,
            when (descStr) {
                Strength.EXACT, Strength.PREFIX -> WEIGHT_DESCRIPTION_PREFIX
                Strength.SUBSTRING -> WEIGHT_DESCRIPTION_SUBSTRING
                Strength.NONE -> 0
            },
        )
        best = maxOf(
            best,
            when (ctxStr) {
                Strength.EXACT, Strength.PREFIX -> WEIGHT_CONTEXT_PREFIX
                Strength.SUBSTRING -> WEIGHT_CONTEXT_SUBSTRING
                Strength.NONE -> 0
            },
        )

        val phraseScore = when (fieldStrength(token, item.phraseWords)) {
            Strength.EXACT -> WEIGHT_PHRASE_EXACT
            Strength.PREFIX -> WEIGHT_PHRASE_PREFIX
            Strength.SUBSTRING, Strength.NONE -> 0
        }
        best = maxOf(best, phraseScore)

        if (best > 0) return best

        val cpLen = token.codePointCount(0, token.length)
        if (cpLen >= TYPO_TOKEN_MIN_LENGTH) {
            val limit = if (cpLen >= TYPO_TWO_EDITS_MIN_LENGTH) 2 else 1
            for (target in item.typoTargets) {
                if (SettingsSearchText.editDistance(token, target, limit) <= limit) {
                    return WEIGHT_TYPO
                }
            }
        }
        return 0
    }

    private fun scorePhrases(item: IndexedEntry, filtered: List<String>): Int {
        if (filtered.size < 2 || item.phrases.isEmpty()) return 0
        var bonus = 0
        for (phrase in item.phrases) {
            if (phrase.words.size < 2) continue
            when {
                filtered == phrase.words -> bonus += WEIGHT_PHRASE_EXACT
                containsOrderedSubslice(phrase.words, filtered, prefixLast = false) -> bonus += WEIGHT_PHRASE_EXACT
                containsOrderedSubslice(filtered, phrase.words, prefixLast = false) -> bonus += WEIGHT_PHRASE_EXACT
                containsOrderedSubslice(filtered, phrase.words, prefixLast = true) -> bonus += WEIGHT_PHRASE_PREFIX
            }
        }
        return bonus
    }

    private fun containsOrderedSubslice(
        needle: List<String>,
        haystack: List<String>,
        prefixLast: Boolean,
    ): Boolean {
        if (needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            var allMatch = true
            for (offset in needle.indices) {
                val n = needle[offset]
                val h = haystack[start + offset]
                val matches = if (prefixLast && offset == needle.lastIndex) {
                    h.startsWith(n)
                } else {
                    h == n
                }
                if (!matches) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) return true
        }
        return false
    }

    private fun fieldStrength(token: String, words: List<String>): Strength {
        var best = Strength.NONE
        for (word in words) {
            val strength = when {
                word == token -> Strength.EXACT
                word.startsWith(token) -> Strength.PREFIX
                token.length >= SUBSTRING_MIN_LENGTH && word.contains(token) -> Strength.SUBSTRING
                else -> Strength.NONE
            }
            if (strength.level > best.level) {
                best = strength
                if (best == Strength.EXACT) return best
            }
        }
        return best
    }

    private fun findMatchedHint(item: IndexedEntry, filtered: List<String>): String? {
        // Only surface a "Matches “...”" hint when the query wasn't already visibly explained by the title/description/breadcrumb.
        val allExplainedInVisible = filtered.all { token ->
            fieldStrength(token, item.visibleWords.toList()) != Strength.NONE
        }
        if (allExplainedInVisible) return null

        // Prefer matching synonym phrases when multiple tokens hit a phrase.
        if (filtered.size >= 2) {
            for (phrase in item.phrases) {
                val hitsPhrase = containsOrderedSubslice(filtered, phrase.words, prefixLast = true) ||
                    containsOrderedSubslice(phrase.words, filtered, prefixLast = false)
                if (hitsPhrase) {
                    val alreadyVisible = phrase.words.all { it in item.visibleWords }
                    if (!alreadyVisible) return phrase.surface
                }
            }
        }

        // Otherwise pick the strongest non-visible keyword hit (exact > prefix > substring).
        var bestStrength = Strength.NONE
        var bestSurface: String? = null
        for (token in filtered) {
            if (fieldStrength(token, item.visibleWords.toList()) != Strength.NONE) continue
            for (kw in item.keywordWords) {
                if (kw in item.visibleWords) continue
                val strength = when {
                    kw == token -> Strength.EXACT
                    kw.startsWith(token) -> Strength.PREFIX
                    token.length >= SUBSTRING_MIN_LENGTH && kw.contains(token) -> Strength.SUBSTRING
                    else -> Strength.NONE
                }
                if (strength.level > bestStrength.level) {
                    bestStrength = strength
                    bestSurface = item.keywordSurfaceByWord[kw] ?: kw
                }
            }
        }
        if (bestSurface != null) return bestSurface

        val visibleWordList = item.visibleWords.toList()
        item.phrases.firstOrNull { phrase ->
            filtered.any { token ->
                fieldStrength(token, visibleWordList) == Strength.NONE &&
                    fieldStrength(token, phrase.words).level >= Strength.PREFIX.level
            }
        }?.let { return it.surface }

        for (token in filtered) {
            if (fieldStrength(token, item.visibleWords.toList()) != Strength.NONE) continue
            val cpLen = token.codePointCount(0, token.length)
            if (cpLen < TYPO_TOKEN_MIN_LENGTH) continue
            val limit = if (cpLen >= TYPO_TWO_EDITS_MIN_LENGTH) 2 else 1
            val hit = item.typoTargets.firstOrNull { target ->
                target !in item.visibleWords &&
                    target in item.keywordSurfaceByWord &&
                    SettingsSearchText.editDistance(token, target, limit) <= limit
            }
            if (hit != null) return item.keywordSurfaceByWord[hit]
        }
        return null
    }

    companion object {
        const val DEFAULT_RESULT_CAP = 30
        private const val SUBSTRING_MIN_LENGTH = 3
        private const val TYPO_TOKEN_MIN_LENGTH = 5
        private const val TYPO_TWO_EDITS_MIN_LENGTH = 9

        private const val WEIGHT_EXACT_TITLE_BONUS = 150
        private const val WEIGHT_TITLE_EXACT = 130
        private const val WEIGHT_TITLE_PREFIX = 100
        private const val WEIGHT_TITLE_SUBSTRING = 60
        private const val WEIGHT_KEYWORD_EXACT = 110
        private const val WEIGHT_KEYWORD_PREFIX = 50
        private const val WEIGHT_KEYWORD_SUBSTRING = 30
        private const val WEIGHT_PHRASE_EXACT = 45
        private const val WEIGHT_PHRASE_PREFIX = 35
        private const val WEIGHT_DESCRIPTION_PREFIX = 20
        private const val WEIGHT_DESCRIPTION_SUBSTRING = 15
        private const val WEIGHT_CONTEXT_PREFIX = 10
        private const val WEIGHT_CONTEXT_SUBSTRING = 5
        private const val WEIGHT_TYPO = 8

        private val BASE_MARKERS = setOf(
            "stop", "change", "off", "not", "no", "never", "without",
        )

        private val STOP_WORDS = setOf(
            "a", "an", "and", "for", "from", "how", "i", "in", "into",
            "is", "it", "my", "of", "on", "or", "the", "to", "what",
            "when", "where", "which", "with",
        )
    }
}
