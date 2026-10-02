package com.envi.wispr.cleanup

internal enum class EnglishSpelling {
    AMERICAN, BRITISH;
    companion object {
        fun fromStored(value: String?): EnglishSpelling = entries.firstOrNull { it.name == value } ?: AMERICAN
    }
}

/** VarCon's sense-independent forms, with the reference's name/code/user-word exclusions. */
internal object BritishSpelling {
    private fun asciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun apostrophe(c: Char) = c == '\'' || c == '’'
    private fun codeNeighbour(cp: Int) = Character.isLetterOrDigit(cp) || Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt()) || cp.toChar() in "_@/\\#$=<>"
    fun convert(text: String, spelling: EnglishSpelling, language: CleanupLanguage, protectedWords: Set<String>): String {
        if (spelling != EnglishSpelling.BRITISH || language != CleanupLanguage.Known("en")) return text
        val table = CleanupResources.british ?: return text
        val out = StringBuilder(text.length); var index = 0; var changed = false
        while (index < text.length) {
            if (!asciiLetter(text[index])) { out.append(text[index++]); continue }
            val start = index++
            while (index < text.length && (asciiLetter(text[index]) || (apostrophe(text[index]) && index + 1 < text.length && asciiLetter(text[index + 1])))) index++
            val token = text.substring(start, index)
            val key = token.lowercase(java.util.Locale.ROOT).replace('’', '\'')
            val replacement = table[key]
            val lower = token.none(Char::isUpperCase)
            val title = token.first().isUpperCase() && token.drop(1).none(Char::isUpperCase)
            val inCode = (start > 0 && codeNeighbour(text.codePointBefore(start))) || (index < text.length && codeNeighbour(text.codePointAt(index))) ||
                (start > 1 && text[start - 1] in ".:" && !text[start - 2].isWhitespace()) ||
                (index + 1 < text.length && text[index] in ".:" && !text[index + 1].isWhitespace() && text[index + 1] !in "\"')]”’")
            if (replacement == null || key in protectedWords || inCode || !(lower || title && sentenceStart(text, start))) out.append(token)
            else {
                var rendered = if (title) replacement.replaceFirstChar(Char::uppercase) else replacement
                if ('’' in token) rendered = rendered.replace('\'', '’')
                out.append(rendered); changed = true
            }
        }
        return if (changed) out.toString() else text
    }
    private fun sentenceStart(text: String, start: Int): Boolean {
        var cursor = start - 1
        while (cursor >= 0 && (text[cursor] == ' ' || text[cursor] == '\t' || text[cursor] in "\"'([“‘")) cursor--
        if (cursor < 0 || text[cursor] in ".!?\n\r\u2028\u2029") return true
        var markerStart = cursor
        when (text[cursor]) {
            '-', '*', '+', '•' -> Unit
            '#' -> while (markerStart > 0 && text[markerStart - 1] == '#') markerStart--
            ')' -> {
                var digit = cursor - 1
                while (digit >= 0 && text[digit] in '0'..'9') digit--
                if (digit == cursor - 1) return false
                markerStart = digit + 1
            }
            else -> return false
        }
        var previous = markerStart - 1
        while (previous >= 0 && text[previous] in " \t") previous--
        return previous < 0 || text[previous] in "\n\r\u2028\u2029"
    }
    fun protectedWords(userCanonicals: Collection<String>): Set<String> = userCanonicals.flatMap { canonical ->
        val lower = canonical.lowercase(java.util.Locale.ROOT).replace('’', '\'')
        listOf(lower) + lower.split(Regex("[^\\p{L}']+")).filter(String::isNotEmpty)
    }.toSet()
}
