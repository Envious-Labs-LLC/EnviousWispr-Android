package com.envi.wispr

/**
 * A small Kotlin source lexer for the JVM shape rows that read device-test sources (#215, shared since #305): every
 * character is CODE, COMMENT or STRING, and a call is a whole CODE identifier followed by `(`. Moved unchanged out of
 * `VoicePipelineDeviceShapeTest` so `SilenceDeviceRowsShapeTest` uses the same detector.
 */
internal object KotlinSourceLexer {
    enum class Kind { CODE, COMMENT, STRING }

    /**
     * The class of every character of [text]: `//` line comments and nested `/* */` block comments are
     * COMMENT; `"..."` and `"""..."""` strings (delimiters, literal text, escapes, `$name` references), `'.'`
     * char literals and backticked names are STRING; the body of every `${...}` template is lexed again as
     * CODE, to any depth; everything else is CODE.
     */
    fun classify(text: String): Array<Kind> {
        val kinds = Array(text.length) { Kind.CODE }
        fun mark(from: Int, until: Int, kind: Kind) {
            for (k in from until minOf(until, text.length)) kinds[k] = kind
        }
        fun blockEnd(start: Int): Int {
            var depth = 0
            var j = start
            while (j < text.length) {
                if (text.startsWith("/*", j)) { depth++; j += 2 }
                else if (text.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) return j }
                else j++
            }
            return text.length
        }
        lateinit var string: (Int, Boolean) -> Int
        // CODE from [start]; inside a template it returns the index of the template's closing `}`.
        fun code(start: Int, inTemplate: Boolean): Int {
            var i = start
            var depth = 0
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                        mark(i, end, Kind.COMMENT); i = end
                    }
                    text.startsWith("/*", i) -> { val end = blockEnd(i); mark(i, end, Kind.COMMENT); i = end }
                    text.startsWith("\"\"\"", i) -> i = string(i, true)
                    text[i] == '"' -> i = string(i, false)
                    text[i] == '\'' || text[i] == '`' -> {
                        val quote = text[i]
                        var j = i + 1
                        while (j < text.length && text[j] != quote) { if (quote == '\'' && text[j] == '\\') j++; j++ }
                        mark(i, j + 1, Kind.STRING); i = j + 1
                    }
                    text[i] == '{' -> { depth++; i++ }
                    text[i] == '}' -> { if (inTemplate && depth == 0) return i; depth--; i++ }
                    else -> i++
                }
            }
            return text.length
        }
        string = { start, raw ->
            val open = if (raw) 3 else 1
            mark(start, start + open, Kind.STRING)
            var i = start + open
            var end = text.length
            while (i < text.length) {
                if (raw && text.startsWith("\"\"\"", i)) {
                    var e = i + 3
                    while (e < text.length && text[e] == '"') e++
                    mark(i, e, Kind.STRING); end = e; break
                } else if (!raw && text[i] == '"') {
                    mark(i, i + 1, Kind.STRING); end = i + 1; break
                } else if (!raw && text[i] == '\\') {
                    mark(i, i + 2, Kind.STRING); i += 2
                } else if (text.startsWith("\${", i)) {
                    mark(i, i + 2, Kind.STRING)
                    val close = code(i + 2, true)
                    mark(close, close + 1, Kind.STRING); i = close + 1
                } else {
                    mark(i, i + 1, Kind.STRING); i++
                }
            }
            end
        }
        code(0, false)
        return kinds
    }

    /** [text] keeping only characters of [keep] (others become spaces, newlines stay): same length, same indices. */
    fun view(text: String, vararg keep: Kind): String {
        val kinds = classify(text)
        return String(CharArray(text.length) { i -> if (kinds[i] in keep || text[i] == '\n') text[i] else ' ' })
    }

    fun codeMask(text: String) = view(text, Kind.CODE)

    /**
     * The start of every CODE call of [name] in [text]: the identifier, whole (no identifier character on
     * either side), followed by optional whitespace or comments and then `(`.
     */
    fun callStarts(text: String, name: String): List<Pair<Int, Int>> {
        val code = codeMask(text)
        fun identifier(c: Char) = c.isLetterOrDigit() || c == '_'
        val starts = mutableListOf<Pair<Int, Int>>()
        var at = code.indexOf(name)
        while (at >= 0) {
            val end = at + name.length
            val before = at == 0 || !identifier(code[at - 1])
            var open = end
            while (open < code.length && code[open].isWhitespace()) open++
            if (before && (end == code.length || !identifier(code[end])) && open < code.length && code[open] == '(') {
                starts += at to open
            }
            at = code.indexOf(name, at + 1)
        }
        return starts
    }
}
