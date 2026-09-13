package com.envi.wispr.paste

import com.envi.wispr.paste.AccessibilityInsertionRules.Action
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorRead
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorSelection
import com.envi.wispr.paste.AccessibilityInsertionRules.Judgement
import com.envi.wispr.paste.AccessibilityInsertionRules.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
