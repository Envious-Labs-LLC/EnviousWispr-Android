package com.envi.wispr.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
                AccessibilityInsertionRules.EditorSelection(0, 0),
        )
        assertTrue(
            AccessibilityInsertionRules.normalizedSelection("hello", 5, 5) ==
                AccessibilityInsertionRules.EditorSelection(5, 5),
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

    @Test
    fun setTextRequiresExpectedTextAndCaret() {
        val verification = AccessibilityInsertionRules.Verification(
            action = AccessibilityInsertionRules.Action.SET_TEXT,
            beforeText = "hello ",
            insertedText = "world",
            expectedText = "hello world",
            expectedCaret = 11,
        )

        assertTrue(AccessibilityInsertionRules.isVerified(verification, "hello world", 11, 11))
        assertFalse(AccessibilityInsertionRules.isVerified(verification, "hello world", 6, 6))
        assertFalse(AccessibilityInsertionRules.isVerified(verification, "hello ", 11, 11))
    }

    @Test
    fun pasteRequiresObservableExpectedInsertion() {
        val exact = AccessibilityInsertionRules.Verification(
            action = AccessibilityInsertionRules.Action.PASTE,
            beforeText = "hello ",
            insertedText = "world",
            expectedText = "hello world",
            expectedCaret = 11,
        )
        assertTrue(AccessibilityInsertionRules.isVerified(exact, "hello world", 11, 11))
        assertFalse(AccessibilityInsertionRules.isVerified(exact, "hello world", 6, 6))
        assertFalse(AccessibilityInsertionRules.isVerified(exact, null, -1, -1))

        val unknownCaret = exact.copy(beforeText = "hello", expectedText = null, expectedCaret = null, insertedText = " world")
        assertTrue(AccessibilityInsertionRules.isVerified(unknownCaret, "hello world", 11, 11))
        assertFalse(AccessibilityInsertionRules.isVerified(unknownCaret, "hello world", -1, -1))
        assertFalse(AccessibilityInsertionRules.isVerified(unknownCaret, "hello", -1, -1))
        assertFalse(AccessibilityInsertionRules.isVerified(unknownCaret, "world hello world", -1, -1))
    }
}
