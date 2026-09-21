package com.envi.wispr.providers

/**
 * The one reader of provider-authored JSON (#189). A body that is not JSON, carries trailing content, an
 * unescaped control character, a bad escape or a bad number, or opens more than [MAX_DEPTH] containers, is
 * null: every caller treats null as a malformed body, so a hostile reply is refused deterministically rather
 * than recovered from a JVM `StackOverflowError` on a pool thread.
 *
 * Depth counts open objects and arrays; the root container is depth 1, so 64 nested containers parse and a
 * 65th is refused. The deepest envelope any [ProviderReplyFormat] reads is Gemini's six.
 */
internal object ProviderJson {
    const val MAX_DEPTH = 64

    /** Null when [body] is malformed; a well-formed JSON `null` is a [ParsedJson] whose root is null. */
    fun parseOrNull(body: String): ParsedJson? = try {
        ParsedJson(JsonParser(body).parse())
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** A body that parsed. The wrapper exists so a caller can tell "malformed" from "the JSON literal null". */
internal class ParsedJson(val root: Any?)

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        require(index == input.length) { "trailing JSON" }
        return value
    }

    private fun parseValue(depth: Int): Any? {
        skipWhitespace()
        require(index < input.length) { "missing value" }
        return when (input[index]) {
            '{' -> {
                require(depth < ProviderJson.MAX_DEPTH) { "JSON nesting exceeds ${ProviderJson.MAX_DEPTH}" }
                parseObject(depth + 1)
            }
            '[' -> {
                require(depth < ProviderJson.MAX_DEPTH) { "JSON nesting exceeds ${ProviderJson.MAX_DEPTH}" }
                parseArray(depth + 1)
            }
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("invalid JSON")
        }
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue(depth)
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (consume(']')) return result
        while (true) {
            result += parseValue(depth)
            skipWhitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            val char = input[index++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < input.length) { "unfinished escape" }
                    when (val escaped = input[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= input.length) { "short unicode escape" }
                            result.append(input.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("invalid escape")
                    }
                }
                else -> {
                    require(char.code >= 0x20) { "control in string" }
                    result.append(char)
                }
            }
        }
        throw IllegalArgumentException("unfinished string")
    }

    private fun parseNumber(): Number {
        val start = index
        consume('-')
        if (consume('0')) Unit else {
            require(index < input.length && input[index] in '1'..'9') { "invalid number" }
            while (index < input.length && input[index].isDigit()) index++
        }
        if (consume('.')) {
            require(index < input.length && input[index].isDigit()) { "invalid fraction" }
            while (index < input.length && input[index].isDigit()) index++
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            require(index < input.length && input[index].isDigit()) { "invalid exponent" }
            while (index < input.length && input[index].isDigit()) index++
        }
        return input.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException("invalid number")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        require(input.startsWith(literal, index)) { "invalid literal" }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun expect(char: Char) {
        require(index < input.length && input[index++] == char) { "expected $char" }
    }

    private fun consume(char: Char): Boolean {
        if (index < input.length && input[index] == char) {
            index++
            return true
        }
        return false
    }
}
