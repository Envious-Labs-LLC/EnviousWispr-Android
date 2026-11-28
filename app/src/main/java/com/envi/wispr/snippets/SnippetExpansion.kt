package com.envi.wispr.snippets

import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class Snippet(val id: String, val trigger: String, val expansion: String, val createdAt: Double) {
    val words = snippetWords.findAll(trigger).map { normalize(it.value) }.filter { it.isNotEmpty() }.toList()
}

internal data class SnippetLibrary(val keyword: String = "backslash", val snippets: List<Snippet> = emptyList()) {
    val triggers = snippets.sortedByDescending { it.words.size }.groupBy { it.words.firstOrNull() }
}

private val snippetWords = Regex("[^\\p{javaWhitespace}\\p{Z}\\u0085]+")
private const val OPEN = "([{\"'“‘"
private const val CLOSE = ")]}\"'”’"
private const val END = ".!?"
private const val TRAILING = CLOSE + END + ",;:"

internal fun normalize(token: String): String = Normalizer.normalize(
    token.trim().lowercase(Locale.ROOT).trimStart { it in OPEN }.trimEnd { it in TRAILING }.trim(),
    Normalizer.Form.NFC,
)

private fun endsSentence(text: String): Boolean =
    text.trimEnd().trimEnd { it in CLOSE }.lastOrNull()?.let { it in END } == true

internal data class SnippetRecord(val marker: String, val literal: String, val ownsEnding: Boolean)

internal class SnippetExpansion(val masked: String, private val records: List<SnippetRecord>) {
    val fired: Boolean get() = records.isNotEmpty()

    fun restore(text: String): String? {
        if (!fired) return text
        val positions = records.map { record ->
            val start = text.indexOf(record.marker)
            if (start < 0 || text.indexOf(record.marker, start + record.marker.length) >= 0) return null
            start to record
        }.sortedBy { it.first }
        return buildString {
            var cursor = 0
            for ((start, record) in positions) {
                append(text, cursor, start)
                append(record.literal)
                cursor = start + record.marker.length
                if (record.ownsEnding) {
                    while (cursor < text.length && text[cursor] in TRAILING) {
                        if (text[cursor] !in END) append(text[cursor])
                        cursor++
                    }
                }
            }
            append(text, cursor, text.length)
        }
    }

    companion object {
        fun unchanged(text: String) = SnippetExpansion(text, emptyList())
    }
}

internal enum class SnippetPlaceholder { DATE, TIME, CLIPBOARD }

internal class SnippetFillIns(
    private val now: Date = Date(),
    private val locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    companion object {
        private val fields = Regex(Regex.escape("{{") + "(.*?)" + Regex.escape("}}"), RegexOption.DOT_MATCHES_ALL)
        private fun kind(match: MatchResult): SnippetPlaceholder? =
            SnippetPlaceholder.entries.find { it.name.equals(match.groupValues[1].trim(), ignoreCase = true) }

        fun needsClipboard(text: String): Boolean = fields.findAll(text).any { kind(it) == SnippetPlaceholder.CLIPBOARD }
    }

    fun resolve(template: String): String? = buildString {
        var cursor = 0
        for (match in fields.findAll(template)) {
            append(template, cursor, match.range.first)
            val replacement = when (kind(match)) {
                SnippetPlaceholder.DATE -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(now)
                SnippetPlaceholder.TIME -> DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(now)
                SnippetPlaceholder.CLIPBOARD -> return null
                null -> match.value
            }
            append(replacement)
            cursor = match.range.last + 1
        }
        append(template, cursor, template.length)
    }
}

internal fun SnippetLibrary.expand(text: String, values: SnippetFillIns = SnippetFillIns(), checkActive: () -> Unit = {}): SnippetExpansion {
    val key = normalize(keyword)
    if (key.isEmpty() || snippets.isEmpty()) return SnippetExpansion.unchanged(text)
    val words = snippetWords.findAll(text).toList()
    val records = mutableListOf<SnippetRecord>()
    val markerCouldCollide by lazy { text.contains("EWSNIP", true) || snippets.any { it.expansion.contains("EWSNIP", true) } }
    var cursor = 0
    var endOfCopy = 0
    val masked = buildString {
        while (cursor < words.size) {
            checkActive()
            val word = words[cursor]
            if (normalize(word.value) != key || endsSentence(word.value)) {
                cursor++
                continue
            }
            val start = cursor + 1
            val nextWord = words.getOrNull(start)?.value?.let(::normalize)
            val snippet = triggers[nextWord]?.firstOrNull { entry ->
                checkActive()
                entry.words.isNotEmpty() && start + entry.words.size <= words.size &&
                    entry.words.indices.all { i ->
                        val part = words[start + i].value
                        normalize(part) == entry.words[i] && (i == entry.words.lastIndex || !endsSentence(part))
                    }
            }
            if (snippet == null) {
                cursor++
                continue
            }
            val lastIndex = cursor + snippet.words.size
            val whole = cursor == 0 && lastIndex == words.lastIndex
            cursor = lastIndex + 1
            val literal = values.resolve(snippet.expansion) ?: continue
            val last = words[lastIndex]
            val ownsEnding = whole || endsSentence(literal)
            val marker = generateSequence { "EWSNIP" + UUID.randomUUID().toString().replace("-", "") }
                .first { candidate -> records.none { it.marker == candidate } &&
                    (!markerCouldCollide || (!text.contains(candidate, true) && snippets.none { it.expansion.contains(candidate, true) })) }
            records.add(SnippetRecord(marker, literal, ownsEnding))
            append(text, endOfCopy, word.range.first)
            append(word.value.takeWhile { it in OPEN })
            append(words[start].value.takeWhile { it in OPEN })
            append(marker)
            val punctuation = last.value.takeLastWhile { it in TRAILING }
            append(if (ownsEnding) punctuation.filterNot { it in END } else punctuation)
            endOfCopy = last.range.last + 1
        }
        append(text, endOfCopy, text.length)
    }
    return SnippetExpansion(masked, records)
}
