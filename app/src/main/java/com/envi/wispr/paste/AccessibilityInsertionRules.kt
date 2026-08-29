package com.envi.wispr.paste

/** Pure safety rules shared by the accessibility insertion path and its tests. */
internal object AccessibilityInsertionRules {
    data class EditorSelection(val start: Int, val end: Int)

    enum class Action {
        SET_TEXT,
        PASTE,
    }

    data class Verification(
        val action: Action,
        val beforeText: String,
        val insertedText: String,
        val expectedText: String?,
        val expectedCaret: Int? = null,
    )

    fun isExpectedWindow(
        packageName: String?,
        windowId: Int,
        expectedPackageName: String,
        expectedWindowId: Int,
    ): Boolean = packageName == expectedPackageName && windowId == expectedWindowId

    /** Android may expose an empty editor's hint through node.text. It is not user content. */
    fun observableEditorText(text: CharSequence?, isShowingHintText: Boolean): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    /** Samsung reports -1/-1 for some focused empty editors. Treat that as the start. */
    fun normalizedSelection(text: String, selectionStart: Int, selectionEnd: Int): EditorSelection? {
        if (selectionStart < 0 || selectionEnd < 0) {
            return if (text.isEmpty()) EditorSelection(0, 0) else null
        }
        if (selectionStart !in 0..text.length || selectionEnd !in selectionStart..text.length) {
            return null
        }
        return EditorSelection(selectionStart, selectionEnd)
    }

    fun isVerified(
        verification: Verification,
        actualText: CharSequence?,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        val actual = actualText?.toString() ?: return false
        return when (verification.action) {
            Action.SET_TEXT -> {
                val caret = verification.expectedCaret ?: return false
                actual == verification.expectedText &&
                    selectionStart == caret &&
                    selectionEnd == caret
            }

            Action.PASTE -> {
                val expected = verification.expectedText
                if (expected != null) {
                    val caret = verification.expectedCaret ?: return false
                    actual == expected && selectionStart == caret && selectionEnd == caret
                } else {
                    isSingleInsertion(
                        before = verification.beforeText,
                        inserted = verification.insertedText,
                        after = actual,
                        selectionStart = selectionStart,
                        selectionEnd = selectionEnd,
                    )
                }
            }
        }
    }

    private fun isSingleInsertion(
        before: String,
        inserted: String,
        after: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (inserted.isEmpty() || after.length != before.length + inserted.length) return false
        var index = after.indexOf(inserted)
        while (index >= 0) {
            val caret = index + inserted.length
            if (after.removeRange(index, caret) == before &&
                selectionStart == caret && selectionEnd == caret
            ) {
                return true
            }
            index = after.indexOf(inserted, startIndex = index + 1)
        }
        return false
    }
}
