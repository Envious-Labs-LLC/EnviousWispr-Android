package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AccessibilityInsertionRunner
import com.envi.wispr.paste.InsertionHandoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#17). Protects which History rows offer the re-insert recovery action,
 * excludes live pending states and already-recovered rows, and ensures every non-scheduled
 * handoff produces a clear, non-silent user message.
 */
class HistoryInsertionRecoveryTest {

    @After
    fun tearDown() {
        HistoryInsertionRecovery.resetSessionRecoveredForTest()
    }

    private fun row(
        id: Long = 7L,
        finalText: String = "Meet me at three.",
        status: String = TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
        insertionResult: String = InsertionResults.COPY_ONLY,
    ) = TranscriptEntity(
        id = id,
        originalText = finalText,
        finalText = finalText,
        createdAtMs = 1_700_000_000_000L,
        durationMs = 2_000L,
        speechEngine = "parakeet-ultra",
        polishEngine = "off",
        polishLatencyMs = 0L,
        insertionResult = insertionResult,
        status = status,
    )

    @Test
    fun canRetryAllowsIncompleteDeliveriesAndExcludesDeliveredInFlightAndSessionRecoveredRows() {
        val delivered = setOf(InsertionResults.COMMITTED, InsertionResults.PASTED)
        val undelivered = listOf(
            InsertionResults.CLIPBOARD,
            InsertionResults.COPY_ONLY,
            InsertionResults.COPY_ONLY_INTERRUPTED,
            InsertionResults.COPY_ONLY_SERVICE_DESTROYED,
            InsertionResults.COPY_ONLY_UNVERIFIED,
            InsertionResults.UNVERIFIED_NOT_COPIED,
            InsertionResults.HISTORY_ONLY,
            InsertionResults.INSERTION_FAILED,
            InsertionResults.INSERTION_INTERRUPTED,
            InsertionResults.DELIVERY_UNKNOWN,
        )
        delivered.forEach { result ->
            assertFalse(
                "delivered result $result should not offer retry",
                HistoryInsertionRecovery.canRetry(
                    row(status = TranscriptEntity.STATUS_COMPLETED, insertionResult = result),
                ),
            )
        }
        undelivered.forEach { result ->
            assertTrue(
                "undelivered result $result should offer retry",
                HistoryInsertionRecovery.canRetry(
                    row(status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED, insertionResult = result),
                ),
            )
        }
        assertFalse(HistoryInsertionRecovery.canRetry(row(finalText = "   ")))
        assertFalse(HistoryInsertionRecovery.canRetry(row(status = TranscriptEntity.STATUS_DRAFT)))
        assertFalse(HistoryInsertionRecovery.canRetry(row(status = TranscriptEntity.STATUS_PROCESSING)))
        assertFalse(
            "saved_unrouted is a live state before routing finishes (tested independently of pending)",
            HistoryInsertionRecovery.canRetry(row(status = TranscriptEntity.STATUS_SAVED_UNROUTED, insertionResult = InsertionResults.COPY_ONLY)),
        )
        assertFalse(
            "ready_for_insertion is a live state while insertion is running (tested independently of pending)",
            HistoryInsertionRecovery.canRetry(row(status = TranscriptEntity.STATUS_READY_FOR_INSERTION, insertionResult = InsertionResults.COPY_ONLY)),
        )
        assertFalse(
            "pending insertionResult must never offer retry",
            HistoryInsertionRecovery.canRetry(row(status = TranscriptEntity.STATUS_INSERTION_INTERRUPTED, insertionResult = "pending")),
        )

        listOf(
            InsertionResults.COPY_ONLY_UNVERIFIED,
            InsertionResults.UNVERIFIED_NOT_COPIED,
            InsertionResults.DELIVERY_UNKNOWN,
            InsertionResults.INSERTION_INTERRUPTED,
            InsertionResults.COPY_ONLY_INTERRUPTED,
            InsertionResults.COPY_ONLY_SERVICE_DESTROYED,
            InsertionResults.INSERTION_FAILED,
        ).forEach { result ->
            assertTrue("uncertain result $result must prompt before reinserting", HistoryInsertionRecovery.needsDuplicateCheck(row(insertionResult = result)))
        }
        listOf(
            InsertionResults.CLIPBOARD,
            InsertionResults.COPY_ONLY,
            InsertionResults.HISTORY_ONLY,
        ).forEach { result ->
            assertFalse("known-unwritten result $result does not need duplicate warning", HistoryInsertionRecovery.needsDuplicateCheck(row(insertionResult = result)))
        }

        val retryable = row(id = 42L, insertionResult = InsertionResults.COPY_ONLY)
        assertTrue(HistoryInsertionRecovery.canRetry(retryable))
        HistoryInsertionRecovery.markSessionUnverified(42L)
        assertTrue("unverified recovery prompts before a second insert", HistoryInsertionRecovery.needsDuplicateCheck(retryable))
        HistoryInsertionRecovery.markSessionRecovered(42L)
        assertFalse("verified recovery blocks immediate resend", HistoryInsertionRecovery.canRetry(retryable))
        assertFalse("verified recovery clears unverified prompt", HistoryInsertionRecovery.needsDuplicateCheck(retryable))
    }

    @Test
    fun everyNonScheduledHandoffProducesNonSilentFeedback() {
        assertNull(HistoryInsertionRecovery.feedbackMessage(InsertionHandoff.SCHEDULED))
        InsertionHandoff.entries
            .filter { it != InsertionHandoff.SCHEDULED }
            .forEach { handoff ->
                val message = HistoryInsertionRecovery.feedbackMessage(handoff)
                assertNotNull("handoff $handoff must not fail silently", message)
                assertTrue("handoff $handoff message must be non-blank", message!!.isNotBlank())
            }
        assertTrue(
            HistoryInsertionRecovery.feedbackMessage(InsertionHandoff.NO_PINNED_TARGET)!!
                .contains("No editable field is focused"),
        )
    }

    @Test
    fun manualSwitchTimeoutExceedsAutoReturnTimeoutAndIsBounded() {
        assertEquals(
            AccessibilityInsertionRunner.INSERTION_TIMEOUT_MS,
            AccessibilityInsertionRunner.recoveryTimeoutMs(manualSwitch = false),
        )
        assertEquals(
            AccessibilityInsertionRunner.MANUAL_RECOVERY_TIMEOUT_MS,
            AccessibilityInsertionRunner.recoveryTimeoutMs(manualSwitch = true),
        )
        assertTrue(
            AccessibilityInsertionRunner.MANUAL_RECOVERY_TIMEOUT_MS >
                AccessibilityInsertionRunner.INSERTION_TIMEOUT_MS,
        )
    }
}
