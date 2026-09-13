package com.envi.wispr.paste

/**
 * Pure safety rules shared by the accessibility insertion path and its tests.
 *
 * The judge here never reads the caret. Gmail reports `0/0` before and after a write however far into
 * the field the words went (`device-testing.md` FACT: what-gmail-actually-does-to-insertion-2026-08-30),
 * so a caret clause turned every landed dictation into "Copied too, if it did not arrive". What the
 * judge reads instead is the CHANGED REGION: the field after the write must equal the field before it
 * with the dictated text spliced in exactly once (#141).
 */
internal object AccessibilityInsertionRules {
    data class EditorSelection(val start: Int, val end: Int)

    /** The ONE write a dictation is allowed to make (#141: one route, one write). */
    enum class Action {
        COMMIT,
        PASTE,
    }

    /**
     * Three answers, because "not there" and "cannot see" are different facts to the caller: both keep
     * judging to the deadline without another write, but only one of them says the editor was readable.
     */
    enum class Judgement {
        VERIFIED,
        MISS,
        UNREADABLE,
    }

    /**
     * A raw editor read. `text == null` is what the node gave back, not a folded empty string, so the
     * judge can tell an unknown baseline from an empty field.
     */
    data class EditorRead(val text: String?, val isShowingHintText: Boolean)

    /**
     * What the field looked like immediately before the write, and what was written.
     *
     * `beforeText == null` is an UNKNOWN baseline: the node judge cannot verify against it and answers
     * [Judgement.UNREADABLE]. A positively identified hint is an EMPTY baseline (`""`, `beforeWasHint`).
     */
    data class Verification(
        val action: Action,
        val beforeText: String?,
        val beforeWasHint: Boolean,
        val selection: EditorSelection?,
        val insertedText: String,
    )

    fun isExpectedWindow(
        packageName: String?,
        windowId: Int,
        expectedPackageName: String,
        expectedWindowId: Int,
    ): Boolean = packageName == expectedPackageName && windowId == expectedWindowId

    /**
     * Android may expose an empty editor's hint through node.text. It is not user content.
     *
     * This fold is for the PRE-WRITE snapshot and for composing the smart payload only. The judge takes
     * the raw [EditorRead] on both sides, because a hint AFTER the write is not "the field is empty",
     * it is "the words are not visible", which is a different answer.
     */
    fun observableEditorText(text: CharSequence?, isShowingHintText: Boolean): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    /** The baseline a pre-write read establishes: `""` for a hint, the text otherwise, null when unread. */
    fun baseline(read: EditorRead): String? = if (read.isShowingHintText) "" else read.text

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

    /**
     * Judges a write against a fresh read of the same field.
     *
     * The reported COLLAPSED caret is never trusted as a position: Gmail reports `0/0` while the caret
     * is at the end, so the splice may be anywhere. A non-collapsed RANGE is trusted, because the user
     * made it, and the replacement must land exactly there. A read that is null or a hint is
     * [Judgement.UNREADABLE]; a complete read that fails the delta is [Judgement.MISS], which covers an
     * editor that transformed the payload (smart quotes, autocorrect) and is reported with the hedged
     * line rather than a false success.
     */
    fun judge(verification: Verification, after: EditorRead): Judgement {
        val before = verification.beforeText ?: return Judgement.UNREADABLE
        val actual = after.text ?: return Judgement.UNREADABLE
        if (after.isShowingHintText) return Judgement.UNREADABLE
        val selection = verification.selection
        if (selection != null && selection.start != selection.end &&
            (selection.start !in 0..before.length || selection.end !in selection.start..before.length)
        ) {
            return Judgement.UNREADABLE
        }
        return if (isSingleInsertion(before, selection, verification.insertedText, actual)) {
            Judgement.VERIFIED
        } else {
            Judgement.MISS
        }
    }

    /**
     * Whether [after] is [before] with [inserted] spliced in exactly once.
     *
     * A trusted range (`start != end`) must be replaced at its exact position. A collapsed or absent
     * selection accepts the splice at any one index, and the length clause is what refuses a double
     * paste. An unchanged field is never a verified insertion, so replacing a range with identical
     * text is a miss.
     */
    fun isSingleInsertion(
        before: String,
        selection: EditorSelection?,
        inserted: String,
        after: String,
    ): Boolean {
        if (inserted.isEmpty() || after == before) return false
        if (selection != null && selection.start != selection.end) {
            if (selection.start !in 0..before.length || selection.end !in selection.start..before.length) {
                return false
            }
            return after == before.substring(0, selection.start) + inserted + before.substring(selection.end)
        }
        if (after.length != before.length + inserted.length) return false
        var index = after.indexOf(inserted)
        while (index >= 0) {
            if (after.removeRange(index, index + inserted.length) == before) return true
            index = after.indexOf(inserted, startIndex = index + 1)
        }
        return false
    }
}
