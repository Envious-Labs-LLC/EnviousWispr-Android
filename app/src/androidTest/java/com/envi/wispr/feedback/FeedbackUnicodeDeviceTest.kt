package com.envi.wispr.feedback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Product Outcome: runs the real Android ICU boundaries, not the JVM SDK stubs, for the Mac's two limits. */
@RunWith(AndroidJUnit4::class)
class FeedbackUnicodeDeviceTest {
    @Test fun unicodeLimitsUseRealAndroidCharacterBoundaries() {
        assertEquals(1, feedbackGraphemes("👨‍👩‍👧‍👦"))
        assertEquals(1, feedbackGraphemes("🇮🇳"))
        assertEquals(1, feedbackGraphemes("a\u0301"))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "a".repeat(4000)), ::feedbackGraphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "a".repeat(4001)), ::feedbackGraphemes))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "a\u0301".repeat(2048)), ::feedbackGraphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "a\u0301".repeat(2048) + "b"), ::feedbackGraphemes))
        assertNull(FeedbackValidation.issue(FeedbackDraft(message = "👨‍👩‍👧‍👦".repeat(585)), ::feedbackGraphemes))
        assertEquals(FeedbackValidation.Issue.TOO_LONG, FeedbackValidation.issue(FeedbackDraft(message = "👨‍👩‍👧‍👦".repeat(586)), ::feedbackGraphemes))
    }
}
