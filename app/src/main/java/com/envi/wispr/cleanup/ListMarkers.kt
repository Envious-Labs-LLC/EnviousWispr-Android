package com.envi.wispr.cleanup

/** Classifies the entire alternating run before gluing a cardinal and a letter. */
internal object ListMarkers {
    private val wordSet = DeterministicCleanup.units.keys + DeterministicCleanup.tens.keys + setOf("hundred", "thousand", "million", "billion")
    private val words = SpokenIdentifiers.alt(wordSet)
    private val cardinal = "(?:$words)(?:[ \\t]+(?:$words|and))*"
    private val countNouns = CleanupReferenceTables.markerCountNouns
    private data class UnitWord(val kind: Char, val word: String?)
    private fun horizontal(c: Char) = c.isWhitespace() && c != '\r' && c != '\n'
    private fun next(text: String, end: Int): String {
        if (end >= text.length || !horizontal(text[end])) return ""
        var start = end
        while (start < text.length && horizontal(text[start])) start++
        var finish = start
        while (finish < text.length && !text[finish].isWhitespace() && finish - start <= 64) finish++
        return text.substring(start, finish)
    }
    private fun allowedEnd(text: String, end: Int) = end == text.length || text[end].isWhitespace() || text[end] in ".,;:!?)]}\"'”’»"
    private fun paired(text: String, start: Int, end: Int, inner: List<Char>): Boolean {
        fun walk(from: Int, left: Boolean): List<UnitWord>? {
            val output = mutableListOf<UnitWord>(); var position = from
            while (output.size < 64) {
                val word: String
                if (left) {
                    if (position <= 0) break
                    if (!horizontal(text[position - 1])) {
                        if (text[position - 1].isWhitespace()) break
                        val glued = text.substring(0, position).takeLastWhile { !it.isWhitespace() }
                        if (glued.all { it in ".,;:!?\"'()[]{}“”‘’»«" }) break else return null
                    }
                    while (position > 0 && horizontal(text[position - 1])) position--
                    val stop = position
                    while (position > 0 && !text[position - 1].isWhitespace() && stop - position <= 64) position--
                    if (stop - position > 64) return null
                    word = text.substring(position, stop).trim { it in ".,;:!?\"'()[]{}“”‘’»«" }
                } else {
                    if (position < text.length && !horizontal(text[position])) {
                        if (text[position].isWhitespace()) break
                        val glued = text.substring(position).takeWhile { !it.isWhitespace() }
                        if (glued.all { it in ".,;:!?\"'()[]{}“”‘’»«" }) break else return null
                    }
                    val rawWord = next(text, position)
                    word = rawWord.trim { it in ".,;:!?\"'()[]{}“”‘’»«" }
                    if (word.length > 64) return null
                    while (position < text.length && horizontal(text[position])) position++
                    position += rawWord.length
                }
                if (word.isEmpty() || !word.all { it in 'A'..'Z' || it in 'a'..'z' }) break
                val kind = when {
                    word.length == 1 && word !in setOf("a", "i", "I") -> 'L'
                    word.lowercase() in wordSet -> 'N'
                    word.equals("and", true) && output.lastOrNull()?.kind == 'N' -> 'N'
                    else -> break
                }
                output += UnitWord(kind, word)
            }
            return if (left) output.reversed() else output
        }
        val left = walk(start, true) ?: return false; val right = walk(end, false) ?: return false
        if (left.size + inner.size + right.size > 64) return false
        fun collapse(seq: List<UnitWord>): List<Char>? {
            val kinds = mutableListOf<Char>(); val number = mutableListOf<String>()
            fun flush(): Boolean {
                if (number.isNotEmpty() && DeterministicCleanup.wordsToLong(number.joinToString(" ")) == null) return false
                number.clear(); return true
            }
            for (u in seq) {
                if (u.kind == 'N') {
                    u.word?.let { number += it }
                    if (kinds.lastOrNull() != 'N') kinds += 'N'
                } else {
                    if (!flush()) return null
                    kinds += u.kind
                }
            }
            return if (flush()) kinds else null
        }
        val all = collapse(left + inner.map { UnitWord(it, null) } + right) ?: return false
        var leftCount = collapse(left)?.size ?: return false
        if (left.lastOrNull()?.kind == 'N' && inner.first() == 'N') leftCount--
        return all.size % 2 == 0 && all.zipWithNext().none { it.first == it.second } && leftCount % 2 == 0
    }
    fun apply(input: String): String {
        var text = Regex("\\b(?<card>$cardinal)[ \\t]+(?<letter>[A-Za-z])\\b(?!\\.[ \\t]*[A-Za-z]\\b)", RegexOption.IGNORE_CASE).replace(input) { m ->
            val letter = m.groups["letter"]!!.value
            val raw = m.groups["card"]!!.value
            val following = next(input, m.range.last + 1)
            val value = DeterministicCleanup.wordsToLong(raw)
            if (letter == "I" || letter != letter.uppercase() || value == null || raw.trimEnd().substringAfterLast(' ').equals("and", true) || !allowedEnd(input, m.range.last + 1) || (raw.first().isUpperCase() && following.firstOrNull()?.isUpperCase() == true) || following.trim { !it.isLetter() }.lowercase() in countNouns || !paired(input, m.range.first, m.range.last + 1, listOf('N', 'L'))) m.value else "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)}$letter"
        }
        val original = text
        text = Regex("\\b(?<letter>[A-Za-z])[ \\t]+(?<card>$cardinal)\\b", RegexOption.IGNORE_CASE).replace(original) { m ->
            val letter = m.groups["letter"]!!.value; val raw = m.groups["card"]!!.value
            val parts = raw.split(Regex("[ \\t]+" )).toMutableList(); var tail = ""
            while (parts.lastOrNull()?.equals("and", true) == true) tail = " " + parts.removeAt(parts.lastIndex) + tail
            val value = DeterministicCleanup.wordsToLong(parts.joinToString(" "))
            val tagEnd = m.range.last + 1 - tail.length
            val following = next(original, tagEnd)
            val before = original.substring(0, m.range.first).trimEnd()
            val positional = before.isEmpty() || before.last() in ".!?\n\r\"'([:"
            if (letter == "I" || letter != letter.uppercase() || (letter == "A" && positional) || value == null || !allowedEnd(original, m.range.last + 1) || (raw.first().isUpperCase() && next(original, m.range.last + 1).firstOrNull()?.isUpperCase() == true) || following.trim { !it.isLetter() }.lowercase() in countNouns || !paired(original, m.range.first, tagEnd, listOf('L', 'N'))) m.value else "$letter${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)}$tail"
        }
        return text
    }
}
