package com.envi.wispr.insertion

object InsertionText {
    private const val CONTEXT_LIMIT = 64

    data class SmartPayloadPlan(
        val text: String,
        val changesDictatedText: Boolean,
    )

    /**
     * Conservatively repairs only unambiguous editor seams. The full editor text is never
     * retained or logged, and only a small window on either side of the selection is inspected.
     */
    fun smartPayload(
        existing: String,
        inserted: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): String = smartPayloadPlan(existing, inserted, selectionStart, selectionEnd).text

    fun smartPayloadPlan(
        existing: String,
        inserted: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): SmartPayloadPlan {
        if (inserted.isBlank()) return SmartPayloadPlan(inserted, false)
        if (selectionStart !in 0..existing.length ||
            selectionEnd !in selectionStart..existing.length
        ) {
            return SmartPayloadPlan(inserted, false)
        }
        val start = selectionStart
        val end = selectionEnd
        val left = existing.substring(0, start).takeLast(CONTEXT_LIMIT)
        val right = existing.substring(end).take(CONTEXT_LIMIT)

        // A caret inside an existing word is ambiguous. Preserve the dictated text exactly.
        if (start == end) {
            val betweenWordCharacters = left.lastOrNull()?.isLetterOrDigit() == true &&
                right.firstOrNull()?.isLetterOrDigit() == true
            val afterWordJoiner = left.lastOrNull()?.let { it in "'\u2019-" } == true &&
                left.dropLast(1).lastOrNull()?.isLetterOrDigit() == true
            val beforeWordJoiner = right.firstOrNull()?.let { it in "'\u2019-" } == true &&
                right.drop(1).firstOrNull()?.isLetterOrDigit() == true
            if (betweenWordCharacters || afterWordJoiner || beforeWordJoiner) {
                return SmartPayloadPlan(inserted, false)
            }
        }

        var payload = removeDuplicateSeamWord(inserted, left, fromStart = true)
        payload = removeDuplicateSeamWord(payload, right, fromStart = false)
        payload = capitalizeAtSentenceStart(payload, left)

        val first = payload.firstOrNull()
        val leftLast = left.lastOrNull()
        if (leftLast != null && !leftLast.isWhitespace() && first != null &&
            !first.isWhitespace() && !attachesToLeft(first) && !opensRight(leftLast)
        ) {
            payload = " $payload"
        }

        val last = payload.lastOrNull()
        val rightFirst = right.firstOrNull()
        val needsTrailingSpace = last != null && !last.isWhitespace() &&
            !opensRight(last) && when {
                rightFirst == null -> true
                rightFirst.isWhitespace() -> false
                attachesToLeft(rightFirst) -> false
                else -> true
            }
        if (needsTrailingSpace) payload += " "
        return SmartPayloadPlan(payload, payload != inserted)
    }

    fun mergeAtSelection(
        existing: String,
        inserted: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): String {
        val start = selectionStart.coerceIn(0, existing.length)
        val end = selectionEnd.coerceIn(start, existing.length)
        return existing.substring(0, start) + inserted + existing.substring(end)
    }

    private fun capitalizeAtSentenceStart(value: String, left: String): String {
        val trimmedLeft = left.trimEnd()
        val sentenceStart = left.lastOrNull()?.let { it in "\n\r" } == true ||
            trimmedLeft.isEmpty() || trimmedLeft.last() in ".!?"
        if (!sentenceStart) return value
        val index = value.indexOfFirst(Char::isLetter)
        if (index < 0 || value[index].isUpperCase()) return value
        return value.replaceRange(index, index + 1, value[index].uppercase())
    }

    private fun removeDuplicateSeamWord(value: String, context: String, fromStart: Boolean): String {
        val word = if (fromStart) {
            Regex("[\\p{L}\\p{N}']+$").find(context)?.value
        } else {
            Regex("^[\\p{L}\\p{N}']+").find(context)?.value
        } ?: return value
        val match = if (fromStart) {
            Regex("^[\\p{L}\\p{N}']+").find(value)
        } else {
            Regex("[\\p{L}\\p{N}']+$").find(value)
        } ?: return value
        if (!word.equals(match.value, ignoreCase = true)) return value
        return value.removeRange(match.range).trimStartIf(fromStart).trimEndIf(!fromStart)
    }

    private fun String.trimStartIf(enabled: Boolean): String = if (enabled) trimStart() else this
    private fun String.trimEndIf(enabled: Boolean): String = if (enabled) trimEnd() else this

    private fun attachesToLeft(char: Char): Boolean = char in ".,!?;:%)]}'\"\u2019\u201D-/"
    private fun opensRight(char: Char): Boolean = char in "([{'\"\u2018\u201C-/"
}
