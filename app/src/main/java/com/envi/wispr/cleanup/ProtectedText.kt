package com.envi.wispr.cleanup

/** Per-transform protection, minted against the input and restored in one non-recursive pass. */
internal class ProtectedText(input: String) {
    private val prefix = generateSequence("\uE000") { it + "\uE000" }.first { it !in input }
    private val values = mutableListOf<String>()
    private val pattern = cleaningRegex(Regex.escape(prefix) + "([0-9]+)\uE001")
    fun protect(value: String): String {
        val index = values.size
        values += value
        return "$prefix$index\uE001"
    }
    fun restore(text: String): String = pattern.replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.let { values.getOrNull(it) } ?: match.value
    }
}
