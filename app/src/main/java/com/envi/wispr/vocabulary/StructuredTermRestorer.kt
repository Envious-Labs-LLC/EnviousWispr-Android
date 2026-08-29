package com.envi.wispr.vocabulary

import kotlin.math.abs

/**
 * Pure custom-word matcher shared by the pre-model and post-model dictation paths.
 *
 * Authority is deterministic: exact compound, exact multi-word alias, fuzzy multi-word
 * alias, exact single-word alias, fuzzy single-word alias, then fuzzy canonical fallback.
 * Earlier replacements are protected so a later pass can never rewrite them.
 */
object StructuredTermRestorer {
    const val DEFAULT_THRESHOLD = 0.82
    const val MULTI_WORD_THRESHOLD = 0.85
    const val SHORT_TOKEN_THRESHOLD = 0.90
    const val AMBIGUITY_MARGIN = 0.05
    const val SHORT_TOKEN_MAX_LENGTH = 4

    // Match the same whitespace-delimited token cores as macOS while leaving
    // leading/trailing punctuation outside the replacement range.
    private val tokenRegex = Regex("[\\p{L}\\p{N}](?:\\S*[\\p{L}\\p{N}])?")
    private val stopwords = setOf(
        "the", "and", "or", "is", "to", "for", "in",
        "a", "at", "on", "of", "we", "you", "it",
    )
    private val reservedTriggerWords = setOf("emoji", "emoticon")
    private val knownDomainSuffixes = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name",
        "pro", "coop", "museum", "aero", "jobs", "mobi", "travel", "tel", "asia", "cat", "xxx",
        "io", "co", "dev", "app", "xyz", "me", "tv", "cc", "ai", "tech",
        "online", "site", "store", "shop", "cloud", "live", "life", "world", "media", "news",
        "agency", "studio", "design", "digital", "email", "expert", "guide", "help",
        "network", "software", "systems", "tools", "works", "so", "academy",
        "us", "uk", "ca", "au", "de", "fr", "jp", "cn", "in", "br", "ru", "es",
        "it", "nl", "se", "no", "dk", "fi", "pl", "ch", "at", "be", "pt", "gr",
        "ie", "nz", "sg", "hk", "tw", "kr", "mx", "ar", "za", "il", "ae", "sa",
        "tr", "id", "my", "th", "ph", "vn", "eu", "uy", "cl", "pe", "ec", "ve", "cr",
    )

    private data class Surface(val value: String, val term: CustomTerm) {
        val key = value.lowercase()
        val tokenCount = value.split(" ").size
        val joined = value.replace(" ", "")
    }

    private data class ScoredSurface(val surface: Surface, val score: Double)
    private data class ChosenSurface(val scored: ScoredSurface, val usedPeeled: Boolean)

    private sealed interface FuzzyOutcome {
        data class Candidate(val value: ScoredSurface) : FuzzyOutcome
        data object AlreadyCorrect : FuzzyOutcome
        data object Ambiguous : FuzzyOutcome
        data object None : FuzzyOutcome
    }

    private sealed interface FuzzyChoice {
        data class Candidate(val value: ChosenSurface) : FuzzyChoice
        data object AlreadyCorrect : FuzzyChoice
        data object None : FuzzyChoice
    }

    private data class DomainSplit(val bare: String, val suffix: String)

    class Matcher internal constructor(rawTerms: List<CustomTerm>) {
        private val terms = rawTerms.filter { it.spelling.isNotEmpty() }

        private val exactSingle = linkedMapOf<String, MutableList<Surface>>()
        private val exactMulti = linkedMapOf<String, MutableList<Surface>>()
        private val exactJoined = linkedMapOf<String, MutableList<Surface>>()
        private val fuzzySingleAliases: List<Surface>
        private val fuzzyMultiByCount: Map<Int, List<Surface>>
        private val fuzzyCanonicals: List<Surface>
        private val maxMultiSpan: Int

        init {
            terms.forEach { term -> term.aliases.forEach { alias -> addAlias(Surface(alias, term)) } }
            terms.forEach { term ->
                val canonical = Surface(term.spelling, term)
                if (canonical.tokenCount == 1) {
                    exactSingle.getOrPut(canonical.key) { mutableListOf() }.add(canonical)
                }
                addJoined(canonical, replaceOwner = true)
            }
            fuzzySingleAliases = exactSingle.values.mapNotNull(List<Surface>::firstOrNull)
            fuzzyMultiByCount = exactMulti.values.mapNotNull(List<Surface>::firstOrNull).groupBy(Surface::tokenCount)
            fuzzyCanonicals = terms.map { Surface(it.spelling, it) }
            maxMultiSpan = exactMulti.values.flatten().maxOfOrNull(Surface::tokenCount) ?: 1
        }

        fun restore(text: String): String {
            if (text.isBlank() || terms.isEmpty()) return text
            val protected = mutableListOf<Pair<String, String>>()
            fun markerFor(replacement: String, current: String): String {
                var marker = "\uE000" + "\uE001".repeat(protected.size + 1) + "\uE002"
                while (marker in text || marker in current) marker += "\uE001"
                protected += marker to replacement
                return marker
            }

            var result = replaceCompounds(text, ::markerFor)
            result = replaceMulti(result, ::markerFor, fuzzy = false)
            result = replaceMulti(result, ::markerFor, fuzzy = true)
            result = replaceSingleExact(result, ::markerFor)
            result = replaceSingleFuzzy(result, ::markerFor)
            protected.asReversed().forEach { (marker, replacement) -> result = result.replace(marker, replacement) }
            return result
        }

        private fun addAlias(surface: Surface) {
            if (surface.value.isBlank()) return
            val destination = if (surface.tokenCount > 1) exactMulti else exactSingle
            // macOS ordinary alias authority is last-writer-wins.
            destination[surface.key] = mutableListOf(surface)
            addJoined(surface, replaceOwner = false)
        }

        private fun addJoined(surface: Surface, replaceOwner: Boolean) {
            val key = surface.joined.lowercase()
            if (key.length < 3) return
            if (replaceOwner) exactJoined[key] = mutableListOf(surface)
            else exactJoined.getOrPut(key) { mutableListOf() }.add(surface)
        }

        private fun replaceCompounds(text: String, markerFor: (String, String) -> String): String {
            var current = text
            var tokens = tokenRegex.findAll(current).toList()
            var start = 0
            while (start < tokens.size) {
                var replaced = false
                for (count in minOf(3, tokens.size - start) downTo 1) {
                    val window = tokens.subList(start, start + count)
                    if (!isWhitespaceSeparated(current, window)) continue
                    if (window.any { it.value.lowercase() in reservedTriggerWords }) continue
                    val raw = current.substring(window.first().range.first, window.last().range.last + 1)
                    val joined = window.joinToString("") { it.value }
                    val owner = exactOwner(exactJoined[joined.lowercase()].orEmpty(), joined) ?: continue
                    val canonicalJoined = owner.term.spelling.replace(" ", "")
                    // Pass 0 owns the key but deliberately declines an already-correct
                    // canonical, allowing the ordinary alias passes to decide it.
                    if (joined == canonicalJoined) break
                    current = current.replaceRange(
                        window.first().range.first..window.last().range.last,
                        markerFor(owner.term.spelling, current),
                    )
                    tokens = tokenRegex.findAll(current).toList()
                    replaced = true
                    break
                }
                if (!replaced) start++
            }
            return current
        }

        private fun replaceMulti(
            text: String,
            markerFor: (String, String) -> String,
            fuzzy: Boolean,
        ): String {
            if (maxMultiSpan < 2) return text
            var current = text
            var tokens = tokenRegex.findAll(current).toList()
            var start = 0
            while (start < tokens.size) {
                var replaced = false
                for (count in minOf(maxMultiSpan, tokens.size - start) downTo 2) {
                    val window = tokens.subList(start, start + count)
                    if (!isWhitespaceSeparated(current, window)) continue
                    if (window.any { it.value.lowercase() in reservedTriggerWords }) continue
                    val raw = window.joinToString(" ") { it.value }
                    val domain = splitDomainSuffix(window.last().value)
                    if (domain?.bare?.lowercase() in reservedTriggerWords) continue
                    val peeled = domain?.let { split ->
                        window.dropLast(1).joinToString(" ", postfix = " ") { it.value } + split.bare
                    }
                    val outcome: FuzzyChoice = if (fuzzy) {
                        chooseFuzzyAcrossDomain(
                            raw,
                            peeled,
                            fuzzyMultiByCount[count].orEmpty(),
                            domainOnlyForRaw = domain != null,
                            multiWord = true,
                        )
                    } else {
                        val rawOwner = exactOwner(exactMulti[raw.lowercase()].orEmpty(), raw)
                        val peeledOwner = peeled?.let { exactOwner(exactMulti[it.lowercase()].orEmpty(), it) }
                        rawOwner?.let { FuzzyChoice.Candidate(ChosenSurface(ScoredSurface(it, 1.0), false)) }
                            ?: peeledOwner?.let { FuzzyChoice.Candidate(ChosenSurface(ScoredSurface(it, 1.0), true)) }
                            ?: FuzzyChoice.None
                    }
                    if (outcome is FuzzyChoice.None) continue
                    val replacement = when (outcome) {
                        is FuzzyChoice.AlreadyCorrect -> raw
                        is FuzzyChoice.Candidate -> reattachDomain(
                            outcome.value.scored.surface.term.spelling,
                            domain,
                            outcome.value.usedPeeled,
                        )
                        FuzzyChoice.None -> error("handled above")
                    }
                    current = current.replaceRange(
                        window.first().range.first..window.last().range.last,
                        markerFor(replacement, current),
                    )
                    tokens = tokenRegex.findAll(current).toList()
                    replaced = true
                    break
                }
                if (!replaced) start++
            }
            return current
        }

        private fun replaceSingleExact(text: String, markerFor: (String, String) -> String): String {
            var current = text
            tokenRegex.findAll(current).toList().asReversed().forEach { token ->
                val raw = token.value
                if (raw.lowercase() in reservedTriggerWords) return@forEach
                val domain = splitDomainSuffix(raw)
                if (domain?.bare?.lowercase() in reservedTriggerWords) return@forEach
                val rawOwner = exactOwner(exactSingle[raw.lowercase()].orEmpty(), raw)
                val peeledOwner = domain?.let { exactOwner(exactSingle[it.bare.lowercase()].orEmpty(), it.bare) }
                val owner = rawOwner ?: peeledOwner ?: return@forEach
                val replacement = reattachDomain(owner.term.spelling, domain, rawOwner == null)
                current = current.replaceRange(token.range, markerFor(replacement, current))
            }
            return current
        }

        private fun replaceSingleFuzzy(text: String, markerFor: (String, String) -> String): String {
            var current = text
            tokenRegex.findAll(current).toList().asReversed().forEach { token ->
                val raw = token.value
                if (raw.length < 3) return@forEach
                if (raw.lowercase() in reservedTriggerWords) return@forEach
                val domain = splitDomainSuffix(raw)
                if (domain?.bare?.lowercase() in reservedTriggerWords) return@forEach
                val outcome = chooseSingleFuzzy(raw, domain?.bare, domain != null)
                if (outcome is FuzzyChoice.None) return@forEach
                val replacement = when (outcome) {
                    FuzzyChoice.AlreadyCorrect -> raw
                    is FuzzyChoice.Candidate -> reattachDomain(
                        outcome.value.scored.surface.term.spelling,
                        domain,
                        outcome.value.usedPeeled,
                    )
                    FuzzyChoice.None -> error("handled above")
                }
                current = current.replaceRange(token.range, markerFor(replacement, current))
            }
            return current
        }

        private fun exactOwner(candidates: List<Surface>, raw: String): Surface? =
            candidates.firstOrNull()

        private fun chooseSingleFuzzy(raw: String, peeled: String?, domainOnlyForRaw: Boolean): FuzzyChoice {
            val rawOutcome = singleFuzzyAttempt(raw, domainOnlyForRaw)
            val peeledOutcome = peeled?.let { singleFuzzyAttempt(it, false) } ?: FuzzyOutcome.None
            return combineFuzzy(rawOutcome, peeledOutcome)
        }

        private fun singleFuzzyAttempt(raw: String, domainOnly: Boolean): FuzzyOutcome {
            if (raw.length < 3) return FuzzyOutcome.None
            var aliasAmbiguous = false
            when (val alias = bestFuzzy(raw, fuzzySingleAliases, domainOnly, multiWord = false)) {
                is FuzzyOutcome.Candidate -> return alias
                FuzzyOutcome.AlreadyCorrect -> return FuzzyOutcome.AlreadyCorrect
                FuzzyOutcome.Ambiguous -> aliasAmbiguous = true
                FuzzyOutcome.None -> Unit
            }
            return when (val canonical = bestFuzzy(raw, fuzzyCanonicals, domainOnly, multiWord = false)) {
                is FuzzyOutcome.Candidate -> canonical
                FuzzyOutcome.AlreadyCorrect -> FuzzyOutcome.AlreadyCorrect
                FuzzyOutcome.Ambiguous -> FuzzyOutcome.Ambiguous
                FuzzyOutcome.None -> if (aliasAmbiguous) FuzzyOutcome.Ambiguous else FuzzyOutcome.None
            }
        }

        private fun chooseFuzzyAcrossDomain(
            raw: String,
            peeled: String?,
            candidates: List<Surface>,
            domainOnlyForRaw: Boolean,
            multiWord: Boolean,
        ): FuzzyChoice {
            val rawOutcome = bestFuzzy(raw, candidates, domainOnlyForRaw, multiWord)
            val peeledOutcome = peeled?.let { bestFuzzy(it, candidates, false, multiWord) } ?: FuzzyOutcome.None
            return combineFuzzy(rawOutcome, peeledOutcome)
        }

        private fun combineFuzzy(first: FuzzyOutcome, second: FuzzyOutcome): FuzzyChoice = when {
            first is FuzzyOutcome.AlreadyCorrect || second is FuzzyOutcome.AlreadyCorrect -> FuzzyChoice.AlreadyCorrect
            first is FuzzyOutcome.Ambiguous || second is FuzzyOutcome.Ambiguous -> FuzzyChoice.None
            first is FuzzyOutcome.Candidate && second is FuzzyOutcome.Candidate -> {
                if (first.value.surface.term.spelling != second.value.surface.term.spelling &&
                    abs(first.value.score - second.value.score) < AMBIGUITY_MARGIN
                ) FuzzyChoice.None else if (first.value.score >= second.value.score) {
                    FuzzyChoice.Candidate(ChosenSurface(first.value, false))
                } else {
                    FuzzyChoice.Candidate(ChosenSurface(second.value, true))
                }
            }
            first is FuzzyOutcome.Candidate -> FuzzyChoice.Candidate(ChosenSurface(first.value, false))
            second is FuzzyOutcome.Candidate -> FuzzyChoice.Candidate(ChosenSurface(second.value, true))
            else -> FuzzyChoice.None
        }

        private fun bestFuzzy(
            raw: String,
            candidates: List<Surface>,
            domainOnly: Boolean,
            multiWord: Boolean,
        ): FuzzyOutcome {
            val normalized = raw.lowercase()
            var best: ScoredSurface? = null
            var second: ScoredSurface? = null
            candidates.forEach { candidate ->
                if (domainOnly && !isDomainShaped(candidate.value)) return@forEach
                if (!multiWord) {
                    val shorter = minOf(normalized.length, candidate.key.length)
                    val longer = maxOf(normalized.length, candidate.key.length)
                    if (longer == 0 || shorter.toDouble() / longer < 0.5) return@forEach
                }
                val scored = ScoredSurface(candidate, score(normalized, candidate.key))
                val currentBest = best
                if (currentBest == null || scored.score > currentBest.score) {
                    if (currentBest != null && currentBest.surface.term.spelling != scored.surface.term.spelling) {
                        second = currentBest
                    }
                    best = scored
                } else if (scored.surface.term.spelling != currentBest.surface.term.spelling &&
                    (second == null || scored.score > second!!.score)
                ) {
                    second = scored
                }
            }
            val winner = best ?: return FuzzyOutcome.None
            if (winner.score <= 0.0) return FuzzyOutcome.None
            val base = if (multiWord) {
                MULTI_WORD_THRESHOLD + if (normalized.split(' ').any(stopwords::contains)) 0.05 else 0.0
            } else if (raw.length <= SHORT_TOKEN_MAX_LENGTH) {
                SHORT_TOKEN_THRESHOLD
            } else {
                DEFAULT_THRESHOLD
            }
            val threshold = winner.surface.term.minSimilarityOverride ?: if (multiWord) {
                base
            } else {
                base + largeVocabularyPenalty(candidates.size) - lengthAdjustment(winner.surface.term.spelling.length)
            }
            if (raw == winner.surface.term.spelling) return FuzzyOutcome.AlreadyCorrect
            if (winner.score < threshold) return FuzzyOutcome.None
            if (second != null && winner.score - second!!.score < AMBIGUITY_MARGIN) return FuzzyOutcome.Ambiguous
            return FuzzyOutcome.Candidate(winner)
        }

        private fun reattachDomain(canonical: String, split: DomainSplit?, usedPeeled: Boolean): String {
            if (!usedPeeled || split == null || isDomainShaped(canonical)) return canonical
            return canonical + split.suffix
        }
    }

    fun compile(terms: List<CustomTerm>): Matcher = Matcher(terms)

    fun restore(text: String, terms: List<CustomTerm>): String = compile(terms).restore(text)

    fun score(candidate: String, target: String): Double {
        val left = candidate.lowercase()
        val right = target.lowercase()
        return levenshteinSimilarity(left, right) * 0.40 +
            bigramDice(left, right) * 0.40 +
            soundexScore(left, right) * 0.20
    }

    private fun isWhitespaceSeparated(text: String, matches: List<MatchResult>): Boolean =
        matches.zipWithNext().all { (left, right) ->
            text.substring(left.range.last + 1, right.range.first).all(Char::isWhitespace)
        }

    private fun largeVocabularyPenalty(poolSize: Int): Double {
        if (poolSize <= 100) return 0.0
        return minOf(0.06, ((poolSize - 100) / 500) * 0.02)
    }

    private fun lengthAdjustment(candidateLength: Int): Double =
        minOf(0.04, 0.005 * maxOf(0, candidateLength - 8))

    private fun levenshteinSimilarity(left: String, right: String): Double {
        if (left.isEmpty()) return if (right.isEmpty()) 1.0 else 0.0
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] = if (leftCharacter == rightCharacter) {
                    previous[rightIndex]
                } else {
                    1 + minOf(previous[rightIndex], previous[rightIndex + 1], current[rightIndex])
                }
            }
            previous = current
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private fun bigramDice(left: String, right: String): Double {
        fun bigrams(value: String): Set<String> =
            if (value.length < 2) emptySet() else (0 until value.lastIndex).mapTo(mutableSetOf()) { value.substring(it, it + 2) }
        val first = bigrams(left)
        val second = bigrams(right)
        if (first.isEmpty() && second.isEmpty()) return if (left == right) 1.0 else 0.0
        return 2.0 * first.intersect(second).size / (first.size + second.size)
    }

    private fun soundexScore(left: String, right: String): Double = if (soundex(left) == soundex(right)) 1.0 else 0.0

    private fun soundex(value: String): String {
        val lower = value.lowercase()
        val first = lower.firstOrNull() ?: return "0000"
        fun digit(character: Char): Char? = when (character) {
            in "bfpv" -> '1'
            in "cgjkqsxz" -> '2'
            in "dt" -> '3'
            in "eiouyhw" -> '0'
            'l' -> '4'
            in "mn" -> '5'
            'r' -> '6'
            else -> null
        }
        val result = StringBuilder().append(first.uppercaseChar())
        var last = digit(first) ?: '0'
        lower.drop(1).forEach { character ->
            val current = digit(character) ?: return@forEach
            if (current != '0' && current != last && result.length < 4) result.append(current)
            last = current
        }
        while (result.length < 4) result.append('0')
        return result.toString()
    }

    private fun splitDomainSuffix(value: String): DomainSplit? {
        val dot = value.indexOf('.')
        if (dot <= 0 || dot == value.lastIndex) return null
        val tail = value.substring(dot + 1)
        val labels = tail.split('.')
        if (labels.any(String::isEmpty)) return null
        val last = labels.last()
        val plausible = last.all(Char::isLetter) ||
            (last.lowercase().startsWith("xn--") && last.drop(4).isNotEmpty() && last.drop(4).all { it.isLetterOrDigit() || it == '-' })
        if (!plausible || labels.any { label -> label.any { !it.isLetterOrDigit() && it != '-' } }) return null
        return DomainSplit(value.substring(0, dot), value.substring(dot))
    }

    private fun isDomainShaped(value: String): Boolean {
        val split = splitDomainSuffix(value) ?: return false
        val last = split.suffix.substringAfterLast('.').lowercase()
        return last.startsWith("xn--") || last.any { it.code > 127 } || last in knownDomainSuffixes
    }
}
