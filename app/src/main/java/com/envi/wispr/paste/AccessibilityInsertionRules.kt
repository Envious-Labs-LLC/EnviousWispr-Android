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
        /** The commit route's pre-write read off the input connection; null on the paste route. */
        val beforeWindow: SurroundingWindow? = null,
    )

    /**
     * How many characters the commit route reads on each side of the caret: the same window the smart
     * composer inspects (`InsertionText` CONTEXT_LIMIT), so a seam it repairs was really seen.
     */
    const val WINDOW_CHARS = 64

    /**
     * A read off the input connection: the text before the caret, the selected text, the text after
     * it, and whether [before] starts at the document start. The commit route composes against this
     * and judges against a second one, so it never depends on the node exposing its text.
     */
    data class SurroundingWindow(
        val before: String,
        val selected: String,
        val after: String,
        val atDocumentStart: Boolean,
    ) {
        /**
         * An empty left context that is NOT the document start is an editor that gave nothing back,
         * and the smart composer would read it as a sentence start; such a window composes nothing.
         */
        val composable: Boolean
            get() = atDocumentStart || before.isNotEmpty()
    }

    /**
     * The one derivation of a [SurroundingWindow] from what `getSurroundingText` reports: the text
     * with the selection inside it, the selection relative to that text, and the text's offset in the
     * document. Inconsistent numbers give null, which the caller treats as "no window".
     */
    fun window(text: CharSequence?, selectionStart: Int, selectionEnd: Int, offset: Int): SurroundingWindow? {
        val whole = text?.toString() ?: return null
        if (selectionStart !in 0..whole.length || selectionEnd !in selectionStart..whole.length) return null
        return SurroundingWindow(
            before = whole.substring(0, selectionStart),
            selected = whole.substring(selectionStart, selectionEnd),
            after = whole.substring(selectionEnd),
            atDocumentStart = offset == 0,
        )
    }

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

    /**
     * The ONE way a node read becomes the `(baseline, selection)` pair the paste route composes against
     * and compares at the write boundary. Two call sites deriving it separately disagreed on a null
     * text (one kept null, one folded it to `""`), which made every boundary check report a changed
     * context on editors that expose no text (Codex code review round 9).
     */
    data class Snapshot(val baseline: String?, val selection: EditorSelection?)

    fun snapshot(text: CharSequence?, isShowingHintText: Boolean, selectionStart: Int, selectionEnd: Int): Snapshot {
        val read = EditorRead(text?.toString(), isShowingHintText)
        return Snapshot(
            baseline = baseline(read),
            selection = normalizedSelection(observableEditorText(text, isShowingHintText), selectionStart, selectionEnd),
        )
    }

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
        val before = verification.beforeText?.let(::foldSpaces) ?: return Judgement.UNREADABLE
        val actual = after.text?.let(::foldSpaces) ?: return Judgement.UNREADABLE
        if (after.isShowingHintText) return Judgement.UNREADABLE
        val inserted = foldSpaces(verification.insertedText)
        val selection = verification.selection
        if (selection != null && selection.start != selection.end &&
            (selection.start !in 0..before.length || selection.end !in selection.start..before.length)
        ) {
            return Judgement.UNREADABLE
        }
        return if (isSingleInsertion(before, selection, inserted, actual)) {
            Judgement.VERIFIED
        } else {
            Judgement.MISS
        }
    }

    /**
     * Judges a commit against a second read off the same input connection, or answers null when the
     * windows cannot decide and the node judge must.
     *
     * The write landed when the text immediately before the caret went from `tail` to
     * `tail + inserted` and the text immediately after the caret is unchanged, where `tail` is the
     * last [WINDOW_CHARS] characters the pre-write read held before the caret. Three cases hand over
     * to the node judge: a pre-write read too short to show whether the draft ALREADY ended with
     * `tail + inserted`; a draft that did (a repetitive one, where a landed write and no write look
     * the same); and a post-write read too short to hold `tail + inserted` that is not the document
     * start (the editor truncated it). A too-short post-write read that IS the document start is a
     * complete read of a field that does not hold the words: MISS.
     */
    fun judgeWindow(verification: Verification, after: SurroundingWindow): Judgement? {
        val before = verification.beforeWindow ?: return null
        // A selection is replaced by the commit, and the windows cannot tell "replaced with the same
        // words" from "nothing happened and the selection collapsed": the node judge, which holds
        // the range, decides those (Codex code review round 1 of chunk 2).
        if (before.selected.isNotEmpty() || after.selected.isNotEmpty()) return null
        val inserted = foldSpaces(verification.insertedText)
        if (inserted.isEmpty()) return null
        val pre = foldSpaces(before.before)
        val expected = pre.takeLast(WINDOW_CHARS) + inserted
        if (!before.atDocumentStart && pre.length < expected.length) return null
        if (pre.endsWith(expected)) return null
        val actual = foldSpaces(after.before)
        if (actual.length < expected.length) {
            return if (after.atDocumentStart) Judgement.MISS else null
        }
        if (!actual.endsWith(expected)) return Judgement.MISS
        // The text after the caret must be the same read as before: both windows asked for the same
        // length, so a different length is evidence the windows cannot weigh (a lost or grown tail,
        // or an editor that truncated one read), never a prefix to be waved through.
        if (before.after.length != after.after.length) return null
        if (foldSpaces(before.after) != foldSpaces(after.after)) return Judgement.MISS
        return Judgement.VERIFIED
    }

    /**
     * The ONE editor transformation the judge tolerates, and only because it was read off the founder's
     * own draft: Gmail's compose stores a seam space as a NO-BREAK SPACE (U+00A0), so a landed
     * dictation read back with the same length and failed the exact delta on that one character
     * (build 107, 2026-09-13: `judgement=MISS actualLen=67 beforeLen=46 insertedLen=21`, the node
     * text holding `\\xa0` where the payload had a space). Both spaces are folded to U+0020 on every
     * side of the comparison. Nothing else is normalised: a word, a quote or a full stop the editor
     * changed is still a MISS, because those are content.
     */
    private fun foldSpaces(text: String): String =
        if (text.indexOf(' ') < 0) text else text.replace(' ', ' ')

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
        // Linear, on the accessibility service's main thread: `after` is `before` with `inserted`
        // spliced at index i exactly when the first i characters and the last (before.length - i)
        // characters are shared, and `inserted` sits between them. The shared prefix and suffix are
        // measured once; the candidate indices are the overlap of the two, and each is checked in
        // place without rebuilding the field.
        var prefix = 0
        while (prefix < before.length && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.length &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) {
            suffix++
        }
        val lowest = maxOf(0, before.length - suffix)
        val highest = minOf(prefix, before.length)
        // The candidate indices are a contiguous window, so this is one substring search inside
        // `after[lowest, highest + inserted.length)`, done with a failure table so a repetitive draft
        // ("aaaa…" with an insertion that differs only at its last character) costs the window plus the
        // payload, never their product. A naive scan re-compared most of the payload at every index.
        return firstOccurrence(after, inserted, lowest, highest + inserted.length) in lowest..highest
    }

    /**
     * Knuth-Morris-Pratt: the first index in `[from, to)` of [text] at which [pattern] starts, or -1.
     * Linear in the window plus the pattern, whatever the text looks like.
     */
    private fun firstOccurrence(text: String, pattern: String, from: Int, to: Int): Int {
        val end = minOf(to, text.length)
        if (pattern.isEmpty() || end - from < pattern.length) return -1
        val failure = IntArray(pattern.length)
        var k = 0
        for (i in 1 until pattern.length) {
            while (k > 0 && pattern[i] != pattern[k]) k = failure[k - 1]
            if (pattern[i] == pattern[k]) k++
            failure[i] = k
        }
        var matched = 0
        for (i in from until end) {
            while (matched > 0 && text[i] != pattern[matched]) matched = failure[matched - 1]
            if (text[i] == pattern[matched]) matched++
            if (matched == pattern.length) return i - pattern.length + 1
        }
        return -1
    }
}
