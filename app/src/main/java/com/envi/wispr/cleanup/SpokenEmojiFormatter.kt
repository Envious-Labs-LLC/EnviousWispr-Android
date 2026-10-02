package com.envi.wispr.cleanup

import com.envi.wispr.vocabulary.StructuredTermRestorer

/** Explicit trigger first, then canonical/synonym lookup, then guarded phonetic lookup. */
internal object SpokenEmojiFormatter {
    private val trigger = cleaningRegex("\\b(?:emoji|emoticon)\\b", RegexOption.IGNORE_CASE)
    private val token = cleaningRegex("[\\p{L}\\p{N}'-]+")
    private val discussion = setOf("category", "categories", "feature", "features", "name", "names", "symbol", "symbols", "word", "words", "button", "buttons", "glyph", "glyphs", "icon", "icons", "character", "characters", "version", "format", "library", "set", "picker", "keyboard", "meaning", "description", "usage", "shortcode", "unicode", "code")
    private data class Surface(val text: String, val entry: CleanupResources.Emoji)
    private data class Dictionary(val canonical: Map<String, Surface>, val synonyms: Map<String, Surface>, val phonetic: Map<String, List<Surface>>, val longest: Int)
    private val dictionary: Dictionary? by lazy {
        CleanupResources.emoji?.let { rows ->
            val canon = rows.map { Surface(normalize(it.phrase), it) }
            val synonyms = rows.flatMap { entry -> entry.synonyms.map { Surface(normalize(it), entry) } }
            val all = canon + synonyms
            val phoneticSurfaces = rows.flatMap { row -> (listOf(row.phrase) + row.synonyms).flatMap { surface -> listOf(surface.lowercase()) + surface.lowercase().split(' ') }.map { Surface(it, row) } }
            Dictionary(canon.associateBy { it.text }, synonyms.associateBy { it.text }, phoneticSurfaces.groupBy { StructuredTermRestorer.soundex(it.text.filter(Char::isLetter)) }, all.maxOf { it.text.split(' ').size })
        }
    }
    private fun normalize(text: String) = text.lowercase(java.util.Locale.ROOT).replace(cleaningRegex("[\\s,.!?—–-]+"), " ").trim()
    fun format(text: String): String {
        if (!trigger.containsMatchIn(text)) return text
        val data = dictionary ?: return text
        val out = StringBuilder(); var cursor = 0
        val tokens = token.findAll(text).toList(); var tokenCursor = 0
        for (match in trigger.findAll(text)) {
            if (match.range.first < cursor) continue
            val following = text.substring(match.range.last + 1).dropWhile(Char::isWhitespace)
            val noun = token.find(following)?.takeIf { it.range.first == 0 }?.value?.lowercase()
            if (noun in discussion) continue
            while (tokenCursor < tokens.size && tokens[tokenCursor].range.last < match.range.first) tokenCursor++
            val lookStart = 0
            val preceding = tokens.subList(maxOf(0, tokenCursor - data.longest), tokenCursor).filter { it.range.first >= cursor }
            var chosen: Surface? = null; var chosenStart = match.range.first
            // Canonical owns overlaps before synonyms, matching the reference's two exact tiers.
            for (pool in listOf(data.canonical, data.synonyms)) {
                for (size in minOf(data.longest, preceding.size) downTo 1) {
                    val span = preceding.takeLast(size)
                    val start = lookStart + span.first().range.first
                    val raw = text.substring(start, match.range.first).trimEnd()
                    val normalized = normalize(raw)
                    val exact = pool[normalized] ?: continue
                    chosen = exact; chosenStart = start; break
                }
                if (chosen != null) break
            }
            if (chosen == null) {
                val tail = preceding.filter { it.range.first >= maxOf(cursor, match.range.first - 80) }.takeLast(4)
                for (size in tail.size downTo 1) {
                    val span = tail.takeLast(size)
                    val phrase = span.joinToString(" ") { it.value.lowercase() }
                    val candidates = data.phonetic[StructuredTermRestorer.soundex(phrase.filter(Char::isLetter))].orEmpty()
                    val byGlyph = candidates.groupBy { it.entry.phrase }.map { (_, forms) ->
                        forms.maxBy { StructuredTermRestorer.levenshteinSimilarity(phrase, it.text) }
                    }.map { it to StructuredTermRestorer.levenshteinSimilarity(phrase, it.text) }.sortedByDescending { it.second }
                    val best = byGlyph.firstOrNull() ?: continue
                    val second = byGlyph.getOrNull(1)?.second ?: 0.0
                    val distance = ((1.0 - best.second) * maxOf(phrase.length, best.first.text.length)).toInt()
                    if (best.second - second < 0.05 || distance > 2) continue
                    chosen = best.first; chosenStart = lookStart + span.first().range.first; break
                }
            }
            val selected = chosen ?: continue
            out.append(text, cursor, chosenStart)
            val before = out.lastOrNull()
            if (before != null && !before.isWhitespace() && before !in "([{\"“‘") out.append(' ')
            out.append(selected.entry.glyph)
            val next = text.getOrNull(match.range.last + 1)
            if (next != null && !next.isWhitespace() && next !in ".!? ,;:)]}\"”’—–") out.append(' ')
            cursor = match.range.last + 1
        }
        if (cursor == 0) return text
        return out.append(text, cursor, text.length).toString()
    }
}
