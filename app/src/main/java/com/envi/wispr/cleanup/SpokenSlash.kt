package com.envi.wispr.cleanup

/** The reference's ordered slash reading table. Backslash remains under spoken punctuation. */
internal object SpokenSlash {
    private enum class Reading { WORD, PAIR, PREFIX, GLUE, UNRESOLVED }
    private data class Token(val raw: String, val core: String, val edge: Int)
    private val t = CleanupReferenceTables
    private val marker = cleaningRegex("[^\\S\\r\\n]*(?:(?<=\\S),)?[^\\S\\r\\n]*(?<![^\\s\"'(\\[{“‘«])(back[^\\S\\r\\n]*slash|(?:forward[^\\S\\r\\n]+)?slash)(?![^\\s.,;:!?])(?![.,;:!?]\\S)[^\\S\\r\\n]*", RegexOption.IGNORE_CASE)
    private val sentenceBreak = cleaningRegex("(?:(?<![^\\p{L}\\p{M}][\\p{L}\\p{M}])(?<!^[\\p{L}\\p{M}])(?<!\\b(?i:${t.slashAbbreviations.joinToString("|")}))\\.|[!?…])[\"'”’»)\\]}]*\\s|\\R")
    private fun horizontal(c: Char) = c.isWhitespace() && c != '\r' && c != '\n'
    private fun core(raw: String, left: Boolean): String {
        var value = raw.trim { !it.isLetterOrDigit() && it !in "_/\\" }
        if (left) value = value.substringAfterLast('/').substringAfterLast('\\')
        else value = value.substringBefore('/').substringBefore('\\')
        return value.replace('’', '\'').lowercase()
    }
    private fun before(text: String, position: Int): Token {
        var end = position
        while (end > 0 && horizontal(text[end - 1])) end--
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        val raw = text.substring(start, end)
        return Token(raw, core(raw, true), start)
    }
    private fun after(text: String, position: Int): Token {
        var start = position
        while (start < text.length && horizontal(text[start])) start++
        var end = start
        while (end < text.length && !text[end].isWhitespace()) end++
        val raw = text.substring(start, end)
        return Token(raw, core(raw, false), end)
    }
    private fun prefixLeft(word: String) = word in t.slashAuxiliaries || (word in t.slashPrepositions && word != "to") || word in t.slashAdverbs || word in t.slashSubordinators || word in t.slashCommandVerbs
    private fun spelledScheme(text: String, end: Int): Boolean {
        var position = end; var letters = ""
        repeat(5) {
            val word = before(text, position)
            if (word.core.length != 1 || word.core[0] !in 'a'..'z') return false
            letters = word.core + letters
            if (letters in t.slashSchemes) return true
            position = word.edge
        }
        return false
    }
    private fun startsSentence(text: String, position: Int): Boolean {
        var i = position
        while (i > 0 && text[i - 1].isWhitespace()) {
            if (text[i - 1] == '\n' || text[i - 1] == '\r') return true
            i--
        }
        if (i == 0) return true
        val token = before(text, position)
        return if (token.core.isEmpty()) token.raw.lastOrNull()?.let { it in ".!?…" } == true else sentenceBreak.containsMatchIn(token.raw + " ")
    }
    private fun reading(left: Token, right: Token, beforeLeft: Token, afterRight: String, previous: Reading?, capitalName: Boolean, leftScheme: Boolean, earlierScheme: Boolean): Reading {
        val endSentence = left.core.isNotEmpty() && sentenceBreak.containsMatchIn(left.raw + " ")
        val l = if (endSentence) "" else left.core; val r = right.core
        val leftMarker = l == "backslash" || (l == "slash" && beforeLeft.core == "back")
        val clause = left.raw.lastOrNull()?.let { it in ",;:.!?" } == true
        val adjacent = !endSentence && (beforeLeft.core == "slash" || leftMarker) && (previous == Reading.GLUE || previous == Reading.PAIR || (previous == Reading.PREFIX && !clause))
        if (r.isEmpty()) return if (leftMarker && previous == Reading.GLUE) Reading.GLUE else Reading.UNRESOLVED
        if (capitalName) return Reading.WORD
        if (r == "slash") return if (l in t.slashSchemes || leftScheme) {
            if ('.' in afterRight || leftScheme) Reading.GLUE else Reading.UNRESOLVED
        } else if (l.isEmpty() || prefixLeft(l)) Reading.PREFIX else Reading.UNRESOLVED
        if (l == "slash") return if ((beforeLeft.core in t.slashSchemes || earlierScheme) && previous == Reading.GLUE) Reading.GLUE else if (previous == Reading.PREFIX) Reading.WORD else Reading.UNRESOLVED
        val chain = afterRight == "slash"
        val pronouns = t.slashSubjectPronouns + t.slashObjectPronouns
        if (("$l/$r" in t.slashFunctionPairs || (l in pronouns && r in pronouns) || (l in t.slashPossessives && r in t.slashPossessives)) && (!clause || chain)) return Reading.PAIR
        if ((left.raw.isNotEmpty() && l.isEmpty() && !endSentence) || left.raw == "A") return Reading.GLUE
        if (l in t.slashModalsAndNegators || l in t.slashSubjectPronouns || l == "back") return Reading.WORD
        if (r in t.slashDeterminers || r in pronouns || r in t.slashAuxiliaries || r in t.slashModalsAndNegators || r in t.slashConjunctions || r in t.slashIndefinites) return Reading.WORD
        val proseAfter = afterRight in t.slashDeterminers || afterRight in pronouns
        if (r in t.slashPrepositions && (!adjacent || proseAfter)) return Reading.WORD
        if (r in t.slashParticles) return Reading.WORD
        if (afterRight in t.slashCommandNouns) return Reading.PREFIX
        if (l in t.slashDeterminers) return Reading.WORD
        if (adjacent) return Reading.GLUE
        if (l == "to") return if (chain || beforeLeft.core in t.slashDestinationWords) Reading.PREFIX else Reading.UNRESOLVED
        if (l in t.slashConjunctions) return if (previous == Reading.PREFIX || beforeLeft.core == "ahead") Reading.PREFIX else Reading.UNRESOLVED
        if (previous == Reading.PREFIX && clause) return Reading.PREFIX
        if (clause && !chain) return Reading.PREFIX
        if (l == "off" && beforeLeft.core in t.slashParticleVerbs) return Reading.PREFIX
        if (l in t.slashDiscourse) return if (beforeLeft.core.isEmpty()) Reading.PREFIX else Reading.GLUE
        if (l in t.slashCommandVerbs && beforeLeft.core in t.slashDeterminers) return Reading.GLUE
        if (l.isEmpty() || prefixLeft(l)) return Reading.PREFIX
        return Reading.GLUE
    }
    fun apply(text: String, punctuation: Boolean): String {
        var previous: Reading? = null; var previousEnd = 0; var wrote = false
        val result = marker.replace(text) { m ->
            val group = m.groups[1]!!
            if (sentenceBreak.containsMatchIn(text.substring(previousEnd, group.range.first))) previous = null
            previousEnd = group.range.last + 1
            val command = group.value
            if (command.lowercase().startsWith("back")) {
                previous = if (punctuation) Reading.GLUE else Reading.WORD
                if (punctuation) (if (',' in m.value) ",\\" else "\\") else m.value
            } else {
                val left = before(text, group.range.first)
                var right = after(text, group.range.last + 1)
                if (right.core == "forward" && after(text, right.edge).core == "slash") right = after(text, right.edge)
                var earlier = before(text, left.edge)
                if (left.core == "slash" && earlier.core == "forward") earlier = before(text, earlier.edge)
                val next = after(text, right.edge)
                val nextCore = if (next.core == "forward" && after(text, next.edge).core == "slash") "slash" else next.core
                val title = command.split(cleaningRegex("\\s+")).all { it.first().isUpperCase() && it.drop(1).none(Char::isUpperCase) }
                val decision = reading(left, right, earlier, nextCore, previous, title && !startsSentence(text, group.range.first), spelledScheme(text, left.edge + left.raw.length), spelledScheme(text, earlier.edge + earlier.raw.length))
                previous = decision
                when (decision) {
                    Reading.WORD, Reading.UNRESOLVED -> m.value
                    Reading.PAIR, Reading.GLUE -> if (right.core == "slash" && left.core in t.slashSchemes && !left.raw.endsWith(':')) ":/" else "/"
                    Reading.PREFIX -> {
                        wrote = true
                        if (left.raw.isEmpty()) "/" else text.substring(m.range.first, group.range.first) + "/"
                    }
                }
            }
        }
        return if (wrote && cleaningRegex("^\\s*(/[\\p{L}\\p{N}_][\\p{L}\\p{N}_-]*)\\s*\\.?\\s*$").matches(result)) result.trim().removeSuffix(".").trim().lowercase() else result
    }
}
