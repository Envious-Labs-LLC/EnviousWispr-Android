package com.envi.wispr.snippets

import java.io.InputStream

/** Import only the selected snippets. The current command word and existing entries belong to this phone. */
internal data class SnippetImport(
    val before: List<Snippet>,
    val candidates: List<Snippet>,
    val alreadySaved: List<Snippet>,
    val skippedLines: Int,
    val ambiguousInput: String? = null,
    val readAs: SnippetFormat = SnippetFormat.AUTO,
) {
    fun commit(current: SnippetLibrary, selected: Set<String>): SnippetLibrary {
        require(current.snippets == before) { "Your snippets changed. Review the import again." }
        return current.copy(snippets = candidates.filter { it.id in selected } + current.snippets).also(SnippetTransfer::validateLibrary)
    }
}

internal enum class SnippetFormat { AUTO, LIST, CSV, JSON }
internal object SnippetTransfer {
    private const val TEXT_LIMIT = 16 * 1024 * 1024
    private const val EXPORT_LIMIT = 64 * 1024 * 1024
    private const val CANDIDATE_LIMIT = 5_000
    private val separators = Regex("\\t|=>|->|→|=")
    private val quotes = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')

    private fun entrySize(item: Snippet): Int {
        val trigger = item.trigger.codePointCount(0, item.trigger.length)
        val body = item.expansion.codePointCount(0, item.expansion.length)
        require(item.words.isNotEmpty() && item.expansion.isNotBlank()) { "Enter a spoken trigger and text to insert." }
        require(trigger <= 512 && body <= 20_000) { "Use up to 512 characters for the trigger and 20,000 for the text." }
        return trigger + body
    }

    fun validateLibrary(library: SnippetLibrary) {
        require(library.snippets.size <= CANDIDATE_LIMIT) { "A snippet library can hold up to 5,000 entries." }
        require(library.snippets.sumOf(::entrySize) <= 4_000_000) { "This snippet library is too large." }
    }

    fun read(input: InputStream, format: SnippetFormat): String {
        val limit = if (format == SnippetFormat.JSON || format == SnippetFormat.AUTO) EXPORT_LIMIT else TEXT_LIMIT
        val bytes = input.readNBytes(limit + 1)
        require(bytes.size <= limit) { "This file is too large." }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    fun preview(text: String, format: SnippetFormat, current: SnippetLibrary): SnippetImport {
        val detected = if (format == SnippetFormat.AUTO) detect(text) else format
        val ambiguous = detected == SnippetFormat.AUTO
        val chosen = if (ambiguous) SnippetFormat.LIST else detected
        val limit = if (chosen == SnippetFormat.JSON) EXPORT_LIMIT else TEXT_LIMIT
        require(text.length <= limit && text.toByteArray().size <= limit) { "This file is too large." }
        val candidates = mutableListOf<Snippet>()
        val existing = mutableListOf<Snippet>()
        val keys = current.snippets.mapTo(mutableSetOf()) { it.words }
        var skipped = 0
        var scalars = 0
        fun row(trigger: String, expansion: String) {
            if (trigger.isBlank() || expansion.isBlank()) { skipped++; return }
            val item = newSnippet(trigger, expansion)
            scalars += entrySize(item)
            require(scalars <= 4_000_000) { "This snippet import is too large." }
            require(candidates.size + existing.size < CANDIDATE_LIMIT) { "Import up to 5,000 snippets at once." }
            if (keys.add(item.words)) candidates += item else existing += item
        }
        when (chosen) {
            SnippetFormat.JSON -> SnippetSerializer.decode(text).snippets.forEach { row(it.trigger, it.expansion) }
            SnippetFormat.CSV -> {
                var first = true
                scanCsv(text) { fields ->
                    val left = fields.getOrNull(0).orEmpty()
                    val right = fields.getOrNull(1).orEmpty()
                    if (!(first && header(left, right))) row(left, right)
                    first = false
                }
            }
            SnippetFormat.LIST -> {
                var first = true
                for (line in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
                    val pair = splitLine(line)
                    if (pair == null) skipped++
                    else if (!(first && header(pair.first, pair.second))) row(pair.first, pair.second.replace("\\n", "\n"))
                    first = false
                }
            }
            SnippetFormat.AUTO -> error("Format must be resolved before import")
        }
        require(candidates.isNotEmpty() || existing.isNotEmpty()) { "No snippets found. Use a trigger and text on each row." }
        return SnippetImport(current.snippets, candidates, existing, skipped, if (ambiguous) text else null, chosen)
    }

    private fun header(left: String, right: String): Boolean =
        left.trim().lowercase() in setOf("trigger", "name", "snippet") && right.trim().lowercase() in setOf("text", "expansion")

    private fun detect(text: String): SnippetFormat {
        if (text.trimStart().startsWith("{") && runCatching { org.json.JSONObject(text) }.isSuccess) return SnippetFormat.JSON
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val firstLine = lines.firstOrNull().orEmpty()
        val firstJoin = quotedJoin(firstLine)
        if (firstLine.startsWith('"') && firstJoin != null && firstJoin.value != ",") return SnippetFormat.LIST
        var ambiguous = false
        for (line in lines) {
            val leading = quotedJoin(line)
            if (line.startsWith('"') && (leading == null || leading.value == ",")) return SnippetFormat.CSV
            val quotedComma = line.indexOf(",\"")
            val explicit = separators.find(line)
            if (quotedComma >= 0 && (explicit == null || explicit.range.first >= quotedComma)) return SnippetFormat.CSV
            if (',' in line && explicit != null) ambiguous = true
        }
        var csvHeader = false
        try {
            scanCsv(firstLine) { record ->
                csvHeader = record.size >= 2 && header(record[0], record[1])
            }
        } catch (_: IllegalArgumentException) {
            // An incomplete first line does not establish a CSV header.
        }
        if (csvHeader) return SnippetFormat.CSV
        return if (ambiguous) SnippetFormat.AUTO else SnippetFormat.LIST
    }

    private data class QuotedJoin(val triggerEnd: Int, val expansionStart: Int, val value: String)

    /** A delimiter immediately after the closing quote owns the split, even if the trigger contains one. */
    private fun quotedJoin(line: String): QuotedJoin? {
        val closer = quotes[line.firstOrNull()] ?: return null
        var offset = 1
        while (offset < line.length) {
            val close = line.indexOf(closer, offset)
            if (close < 0) return null
            if (line.getOrNull(close + 1) == closer) {
                offset = close + 2
                continue
            }
            var start = close + 1
            while (line.getOrNull(start) == ' ') start++
            val delimiter = listOf("=>", "->", "\t", "→", "=", ",", ":").firstOrNull {
                line.startsWith(it, start) && !(it == ":" && line.startsWith("://", start))
            } ?: return null
            return QuotedJoin(close + 1, start + delimiter.length, delimiter)
        }
        return null
    }

    private fun unquote(input: String): String {
        val s = input.trim()
        return if (s.length >= 2 && quotes[s.first()] == s.last()) s.substring(1, s.lastIndex) else s
    }

    private fun splitLine(line: String): Pair<String, String>? {
        quotedJoin(line)?.let { return unquote(line.substring(0, it.triggerEnd)) to unquote(line.substring(it.expansionStart)) }
        val sep = separators.find(line) ?: Regex(",").find(line) ?: Regex(":(?!//)").find(line) ?: return null
        return unquote(line.substring(0, sep.range.first)) to unquote(line.substring(sep.range.last + 1))
    }

    private fun scanCsv(text: String, accept: (List<String>) -> Unit) {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var index = 0
        fun endField() { fields += field.toString(); field.clear(); closed = false }
        fun endRow() { endField(); accept(fields); fields.clear() }
        while (index < text.length) {
            val c = text[index++]
            if (quoted) {
                if (c != '"') field.append(c)
                else if (text.getOrNull(index) == '"') { field.append('"'); index++ }
                else { quoted = false; closed = true }
            } else when (c) {
                ',' -> endField()
                '\n', '\r' -> {
                    if (c == '\r' && text.getOrNull(index) == '\n') index++
                    if (field.isNotEmpty() || fields.isNotEmpty() || closed) endRow()
                }
                '"' -> { require(!closed) { "Invalid quoted CSV field." }; if (field.isEmpty()) quoted = true else field.append(c) }
                else -> { require(!closed) { "Unexpected text after a quoted CSV field." }; field.append(c) }
            }
        }
        require(!quoted) { "A quoted CSV field is unfinished." }
        if (field.isNotEmpty() || fields.isNotEmpty() || closed) endRow()
    }
}
