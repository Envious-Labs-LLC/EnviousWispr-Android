package com.envi.wispr.polish

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome: when this fails, the completion surface either drops the locked product sentence on a
 * hard failure, wrongly calls an intentional skip a failure, or loses the sentence the user has to act on.
 */
class PolishFailureNoticeTest {
    private val gemini = PolishContext.Cloud(Provider.GEMINI, ollama = false)

    @Test fun aHardFailureCarriesTheLockedSentenceAsItsToastAndTheFullReasonAsItsNotification() {
        val notice = PolishFailureNotice.notice(PolishFailure.KEY_REJECTED, gemini)
        assertEquals("Polish failed. Using raw text.", notice.toastLine)
        assertEquals("AI polish failed:", notice.title)
        assertEquals("Gemini rejected your API key. Check or replace it in Settings.", notice.detail)
        assertEquals(
            "Polish failed. Using raw text.\nAI polish failed: Gemini rejected your API key. Check or replace it in Settings.",
            notice.text,
        )
    }

    @Test fun aSkipToastsTheReasonLineItselfAndNeverTheLockedSentence() {
        val notice = PolishFailureNotice.notice(PolishFailure.TIMED_OUT, PolishContext.Local)
        assertEquals("AI cleanup skipped: the dictation took too long. Your original text was pasted unchanged.", notice.toastLine)
        assertEquals("AI cleanup skipped:", notice.title)
        assertEquals("the dictation took too long. Your original text was pasted unchanged.", notice.detail)
        assertEquals("AI cleanup skipped: the dictation took too long. Your original text was pasted unchanged.", notice.text)
    }

    @Test fun everyMemberSplitsByItsLeadIn() {
        PolishFailure.entries.forEach { failure ->
            val notice = PolishFailureNotice.notice(failure, gemini)
            assertEquals("$failure title", failure.leadIn.text, notice.title)
            assertEquals("$failure detail", failure.message(gemini), notice.detail)
            assertEquals("$failure toast", failure.leadIn == PolishFailure.LeadIn.FAILED, notice.toastLine == PolishFailureNotice.LOCKED_SENTENCE)
        }
    }
}
