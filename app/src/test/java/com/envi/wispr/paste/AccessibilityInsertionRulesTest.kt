package com.envi.wispr.paste

import com.envi.wispr.paste.AccessibilityInsertionRules.Action
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorRead
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorSelection
import com.envi.wispr.paste.AccessibilityInsertionRules.Judgement
import com.envi.wispr.paste.AccessibilityInsertionRules.SurroundingWindow
import com.envi.wispr.paste.AccessibilityInsertionRules.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME tests on the judge (#141). When one of these fails the user sees "Copied too, if it
 * did not arrive" on a dictation that landed, or a success haptic on words that landed twice.
 */
class AccessibilityInsertionRulesTest {
    @Test
    fun visibleHintIsNotTreatedAsTypedEditorContent() {
        assertTrue(
            AccessibilityInsertionRules.observableEditorText(
                text = "Write something",
                isShowingHintText = true,
            ).isEmpty(),
        )
        assertTrue(
            AccessibilityInsertionRules.observableEditorText(
                text = "Write something",
                isShowingHintText = false,
            ) == "Write something",
        )
    }

    @Test
    fun emptyFocusedEditorNormalizesSamsungMissingCaretToStart() {
        assertTrue(
            AccessibilityInsertionRules.normalizedSelection("", -1, -1) ==
                EditorSelection(0, 0),
        )
        assertTrue(
            AccessibilityInsertionRules.normalizedSelection("hello", 5, 5) ==
                EditorSelection(5, 5),
        )
        assertTrue(AccessibilityInsertionRules.normalizedSelection("hello", -1, -1) == null)
        assertTrue(AccessibilityInsertionRules.normalizedSelection("hello", 6, 6) == null)
    }

    @Test
    fun requiresExactPackageAndWindowIdentity() {
        assertTrue(
            AccessibilityInsertionRules.isExpectedWindow(
                packageName = "com.example.editor",
                windowId = 42,
                expectedPackageName = "com.example.editor",
                expectedWindowId = 42,
            ),
        )
        assertFalse(
            AccessibilityInsertionRules.isExpectedWindow(
                packageName = "com.example.editor",
                windowId = 43,
                expectedPackageName = "com.example.editor",
                expectedWindowId = 42,
            ),
        )
        assertFalse(
            AccessibilityInsertionRules.isExpectedWindow(
                packageName = "com.other.editor",
                windowId = 42,
                expectedPackageName = "com.example.editor",
                expectedWindowId = 42,
            ),
        )
    }

    /**
     * The REAL Gmail shape, measured 2026-08-30: the editor reports a collapsed caret of 0/0 before
     * AND after the write while the true caret is at the end. The words landed at the end. Revert
     * that turns this red: a caret clause, or trusting the reported collapsed index as the splice.
     */
    @Test
    fun gmailReportingCaretZeroIsStillVerifiedWhenTheWordsLandedOnce() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "Hi team, ",
            beforeWasHint = false,
            selection = EditorSelection(0, 0),
            insertedText = "and I will",
        )
        assertEquals(
            Judgement.VERIFIED,
            AccessibilityInsertionRules.judge(record, EditorRead("Hi team, and I will", false)),
        )
        // The same field with nothing added is not a verified insertion.
        assertEquals(
            Judgement.MISS,
            AccessibilityInsertionRules.judge(record, EditorRead("Hi team, ", false)),
        )
    }

    /** A trusted range is replaced at its exact position, even when the result is shorter. */
    @Test
    fun rangeReplacementThatShortensTheFieldIsVerified() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "Hi team",
            beforeWasHint = false,
            selection = EditorSelection(3, 7),
            insertedText = "Jo",
        )
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judge(record, EditorRead("Hi Jo", false)))
    }

    /** Replacing a range with identical text changes nothing, so nothing was observed to land. */
    @Test
    fun identicalReplacementIsAMiss() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "Hi team, old",
            beforeWasHint = false,
            selection = EditorSelection(9, 12),
            insertedText = "old",
        )
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judge(record, EditorRead("Hi team, old", false)))
    }

    /** A range replacement that landed somewhere else is not the replacement the user asked for. */
    @Test
    fun rangeReplacementLandingElsewhereIsAMiss() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "abcDEFghi",
            beforeWasHint = false,
            selection = EditorSelection(3, 6),
            insertedText = "X",
        )
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judge(record, EditorRead("Xabcghi", false)))
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judge(record, EditorRead("abcXghi", false)))
    }

    /** A range outside the baseline cannot be trusted as a position. */
    @Test
    fun rangeOutsideTheBaselineIsUnreadable() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "abc",
            beforeWasHint = false,
            selection = EditorSelection(2, 9),
            insertedText = "X",
        )
        assertEquals(Judgement.UNREADABLE, AccessibilityInsertionRules.judge(record, EditorRead("abX", false)))
    }

    /**
     * The length clause is what refuses a double paste. Revert that turns this red: replacing the
     * length-and-splice rule with `after.contains(inserted)`.
     */
    @Test
    fun doublePasteIsAMiss() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "Hi ",
            beforeWasHint = false,
            selection = EditorSelection(3, 3),
            insertedText = "there",
        )
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judge(record, EditorRead("Hi therethere", false)))
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judge(record, EditorRead("Hi there", false)))
    }

    /** A single insertion of a repeated string is indistinguishable from a landed one, and accepted. */
    @Test
    fun repeatedStringSingleInsertionIsVerified() {
        assertTrue(AccessibilityInsertionRules.isSingleInsertion("ab", EditorSelection(2, 2), "ab", "abab"))
    }

    /** The splice may sit anywhere in the field, and the judge finds it without the caret. */
    @Test
    fun insertionInTheMiddleOfTheFieldIsVerified() {
        val before = "one two three"
        assertTrue(
            AccessibilityInsertionRules.isSingleInsertion(before, EditorSelection(0, 0), "and a half ", "one two and a half three"),
        )
        assertFalse(
            AccessibilityInsertionRules.isSingleInsertion(before, EditorSelection(0, 0), "and a half ", "one and a half two three x"),
        )
    }

    /**
     * A long draft made of repeats of the dictated phrase: every repeat is a candidate splice point.
     * The judge is correct on it, and (Codex code review round 3) linear rather than one whole-field
     * copy per candidate; no wall-clock assertion, because a timing bound flakes under machine load
     * (#110) and the linearity is a property of the code, not of this run.
     */
    @Test
    fun longRepetitiveDraftIsJudgedCorrectly() {
        val phrase = "thanks, talk soon. "
        val before = phrase.repeat(5_000)
        assertTrue(
            AccessibilityInsertionRules.isSingleInsertion(before, EditorSelection(0, 0), phrase, before + phrase),
        )
        assertFalse(
            AccessibilityInsertionRules.isSingleInsertion(
                before,
                EditorSelection(0, 0),
                phrase,
                before + "thanks, talk soon! ",
            ),
        )
        // Codex code review round 5: a payload that differs from the draft only at its last character
        // makes every candidate index a near-miss. Correct answers on both sides, in linear time.
        val run = "a".repeat(20_000)
        val nearMiss = "a".repeat(999) + "b"
        assertFalse(AccessibilityInsertionRules.isSingleInsertion(run, EditorSelection(0, 0), nearMiss, "a".repeat(21_000)))
        assertTrue(
            AccessibilityInsertionRules.isSingleInsertion(run, EditorSelection(0, 0), nearMiss, "a".repeat(5_000) + nearMiss + "a".repeat(15_000)),
        )
    }

    /** "Cannot see" is not "not there": a null read, a failed baseline, or a hint after the write. */
    @Test
    fun unreadableReadsAreNeverJudgedAsMissOrVerified() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "Hi ",
            beforeWasHint = false,
            selection = EditorSelection(3, 3),
            insertedText = "there",
        )
        assertEquals(Judgement.UNREADABLE, AccessibilityInsertionRules.judge(record, EditorRead(null, false)))
        assertEquals(
            Judgement.UNREADABLE,
            AccessibilityInsertionRules.judge(record, EditorRead("Write something", true)),
        )
        val unknownBaseline = record.copy(beforeText = null)
        assertEquals(
            Judgement.UNREADABLE,
            AccessibilityInsertionRules.judge(unknownBaseline, EditorRead("there", false)),
        )
    }

    /**
     * Codex code review round 9: the composing read and the write-boundary read must derive the
     * baseline the same way, or a null text (no hint) compares as changed against itself and no
     * paste ever happens in that editor. One function, so it cannot diverge.
     */
    @Test
    fun nullTextSnapshotsEqualThemselvesAtTheWriteBoundary() {
        val prepared = AccessibilityInsertionRules.snapshot(null, false, -1, -1)
        val boundary = AccessibilityInsertionRules.snapshot(null, false, -1, -1)
        assertEquals(prepared, boundary)
        assertEquals(null, prepared.baseline)
        assertEquals(EditorSelection(0, 0), prepared.selection)
        val hint = AccessibilityInsertionRules.snapshot("Write something", true, 0, 0)
        assertEquals("", hint.baseline)
        val text = AccessibilityInsertionRules.snapshot("Hi team,", false, 8, 8)
        assertEquals(AccessibilityInsertionRules.Snapshot("Hi team,", EditorSelection(8, 8)), text)
    }

    /** A positively identified hint before the write IS an empty baseline. */
    @Test
    fun hintBeforeTheWriteIsAnEmptyBaseline() {
        val read = EditorRead("Write something", true)
        assertEquals("", AccessibilityInsertionRules.baseline(read))
        val record = Verification(
            action = Action.PASTE,
            beforeText = AccessibilityInsertionRules.baseline(read),
            beforeWasHint = true,
            selection = EditorSelection(0, 0),
            insertedText = "hello",
        )
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judge(record, EditorRead("hello", false)))
    }

    /**
     * REPRODUCIBLE, read off the founder's Gmail draft 2026-09-13 with `uiautomator dump` after build 107
     * reported `judgement=MISS actualLen=67 beforeLen=46 insertedLen=21`: Gmail stores the seam space
     * as U+00A0. Same length, one character class apart, and the words are there. Revert that turns
     * this red: removing the no-break-space fold.
     */
    @Test
    fun gmailNoBreakSpaceAtTheSeamIsStillVerified() {
        val before = "Testing in Gmail now, let's see what happens."
        val record = Verification(
            action = Action.PASTE,
            beforeText = before,
            beforeWasHint = false,
            selection = EditorSelection(before.length, before.length),
            insertedText = " Trying again. 12345. ",
        )
        val gmail = "Testing in Gmail now, let's see what happens. Trying again. 12345. "
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judge(record, EditorRead(gmail, false)))
        // Only the space class is folded: a changed word is still a miss.
        val changedWord = "Testing in Gmail now, let's see what happens. Trying against. 12345. "
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judge(record, EditorRead(changedWord, false)))
    }

    /** The editor transformed the payload: the words are very likely there, and that is a MISS, not a false success. */
    @Test
    fun editorTransformationIsAMissNotAVerified() {
        val record = Verification(
            action = Action.PASTE,
            beforeText = "",
            beforeWasHint = false,
            selection = EditorSelection(0, 0),
            insertedText = "don't",
        )
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judge(record, EditorRead("don’t", false)))
    }

    // ---- The commit route's window judge: two reads off the same input connection. ----

    private fun commitRecord(before: SurroundingWindow?, inserted: String = " and I will") = Verification(
        action = Action.COMMIT,
        beforeText = null,
        beforeWasHint = false,
        selection = null,
        insertedText = inserted,
        beforeWindow = before,
    )

    private fun window(before: String, after: String = "", documentStart: Boolean = true) =
        SurroundingWindow(before = before, selected = "", after = after, atDocumentStart = documentStart)

    /** The everyday case: a short draft, caret at the end, the words appear right before the caret. */
    @Test
    fun aCommitThatLandedAtTheCaretIsVerifiedByTheWindows() {
        val record = commitRecord(window("Hi team,"))
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judgeWindow(record, window("Hi team, and I will")))
    }

    /** Mid-draft: the text after the caret must be untouched. */
    @Test
    fun aCommitInTheMiddleKeepsTheTextAfterTheCaret() {
        val record = commitRecord(window("Hi team,", after = "\n\nThanks"))
        assertEquals(
            Judgement.VERIFIED,
            AccessibilityInsertionRules.judgeWindow(record, window("Hi team, and I will", after = "\n\nThanks")),
        )
        assertEquals(
            Judgement.MISS,
            AccessibilityInsertionRules.judgeWindow(record, window("Hi team, and I will", after = "\n\nThank!")),
        )
    }

    /** A tail that shrank or grew is not the windows' to weigh: the node judge, which sees the field, decides. */
    @Test
    fun aLostOrGrownTailHandsOverToTheNodeJudge() {
        val record = commitRecord(window("Hello ", after = "TAIL"), inserted = "world")
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window("Hello world", after = "")))
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window("Hello world", after = "TAIL more")))
    }

    /** Replacing a selection with the same words is not observable by the windows; the node judge holds the range. */
    @Test
    fun aSelectionHandsOverToTheNodeJudge() {
        val before = SurroundingWindow(before = "Hello ", selected = "world", after = "", atDocumentStart = true)
        val record = commitRecord(before, inserted = "world")
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window("Hello world")))
        val collapsed = commitRecord(window("Hello "), inserted = "world")
        val stillSelected = SurroundingWindow(before = "Hello world", selected = "x", after = "", atDocumentStart = true)
        assertNull(AccessibilityInsertionRules.judgeWindow(collapsed, stillSelected))
    }

    /** Nothing landed: the whole field is visible (document start) and it does not hold the words. */
    @Test
    fun anUnchangedFieldReadFromTheDocumentStartIsAMiss() {
        val record = commitRecord(window("Hi team,"))
        assertEquals(Judgement.MISS, AccessibilityInsertionRules.judgeWindow(record, window("Hi team,")))
    }

    /** The editor cut the post-write read short of the payload and it is not the document start: the node decides. */
    @Test
    fun aTruncatedPostWriteReadHandsOverToTheNodeJudge() {
        val long = "x".repeat(80)
        val record = commitRecord(window(long, documentStart = false))
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window("will", documentStart = false)))
    }

    /** A pre-write read too short to show what the draft ended with cannot rule out a false match. */
    @Test
    fun aTruncatedPreWriteReadHandsOverToTheNodeJudge() {
        val record = commitRecord(window("Hi team,", documentStart = false))
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window("Hi team, and I will", documentStart = false)))
    }

    /** A repetitive draft that already ended in the expected shape cannot be judged by shape. */
    @Test
    fun aDraftThatAlreadyEndedWithTheExpectedShapeHandsOverToTheNodeJudge() {
        val periodic = "ab".repeat(60)
        val record = commitRecord(window(periodic), inserted = "ab")
        assertNull(AccessibilityInsertionRules.judgeWindow(record, window(periodic + "ab")))
    }

    /** The judge never runs against a windowless record (the paste route). */
    @Test
    fun aRecordWithoutAPreWriteWindowIsNotJudgedByWindows() {
        assertNull(AccessibilityInsertionRules.judgeWindow(commitRecord(null), window("anything")))
    }

    /** Gmail's no-break seam space is folded on the window path too. */
    @Test
    fun gmailNoBreakSpaceIsFoldedInTheWindowJudge() {
        val record = commitRecord(window("Hi team,"))
        assertEquals(Judgement.VERIFIED, AccessibilityInsertionRules.judgeWindow(record, window("Hi team,\u00a0and\u00a0I will")))
    }

    /** The one derivation from what getSurroundingText reports. */
    @Test
    fun aSurroundingTextReadBecomesAWindowOnlyWhenItsNumbersAgree() {
        val window = AccessibilityInsertionRules.window("Hi team, |sel| rest", 9, 14, 40)
        assertEquals(SurroundingWindow("Hi team, ", "|sel|", " rest", atDocumentStart = false), window)
        assertNull(AccessibilityInsertionRules.window("abc", 4, 4, 0))
        assertNull(AccessibilityInsertionRules.window("abc", 2, 1, 0))
        assertNull(AccessibilityInsertionRules.window(null, 0, 0, 0))
        assertTrue(checkNotNull(AccessibilityInsertionRules.window("", 0, 0, 0)).composable)
        assertFalse(checkNotNull(AccessibilityInsertionRules.window("", 0, 0, 12)).composable)
    }
}
