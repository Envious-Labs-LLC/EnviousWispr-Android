package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AccessibilityPermissionCheck
import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Product Outcome: setup cannot skip missing requirements, and practice is judged by what really landed. */
class OnboardingPolicyTest {
    private val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true)

    @Test fun restoredPracticeReturnsToDownloadsWhenAModelIsMissing() {
        assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(4, ready.copy(polishModelReady = false), AutoPasteAvailability.LIVE))
    }

    @Test fun enabledButDisconnectedAccessibilityDoesNotAllowPractice() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(4, ready, AutoPasteAvailability.PERMITTED_NOT_RUNNING))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready, AutoPasteAvailability.PERMITTED_NOT_RUNNING))
    }

    /** #131: a restored Demo or Practice step returns to Permissions when Android switched auto-paste off. */
    @Test fun switchedOffAccessibilityDoesNotAllowPractice() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(4, ready, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY))
    }

    /** #131: the setup row says it was switched off and how to turn it back on, never the first-time copy. */
    @Test fun theSetupRowNamesEachAutoPasteState() {
        val expected = mapOf(
            AutoPasteAvailability.LIVE to ACCESSIBILITY_CARD_COPY,
            AutoPasteAvailability.NOT_PERMITTED to ACCESSIBILITY_CARD_COPY,
            AutoPasteAvailability.PERMITTED_NOT_RUNNING to "Access is on. Waiting for the service to connect.",
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY to "Switched off. Turn it back on in Accessibility settings.",
        )
        assertEquals(AutoPasteAvailability.entries.toSet(), expected.keys)
        expected.forEach { (autoPaste, copy) -> assertEquals("$autoPaste", copy, accessibilityRowCopy(autoPaste)) }
    }

    @Test fun notificationsAreOptionalButMicrophoneIsRequired() {
        assertEquals(OnboardingStage.PRACTICE, onboardingStage(4, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.DEMO, onboardingStage(3, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(4, ready.copy(microphoneGranted = false), AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready.copy(microphoneGranted = false), AutoPasteAvailability.LIVE))
    }

    @Test fun verifiedDownloadsAdvanceAndWelcomeRemainsAvailable() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(1, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.WELCOME, onboardingStage(0, ready, AutoPasteAvailability.LIVE))
    }

    @Test fun anOlderModelCheckCannotRevokeANewerPermissionAnswer() {
        val granted = AppReadiness(microphoneGranted = true, notificationsGranted = true, accessibility = AccessibilityPermissionCheck.GRANTED)
        val checkedBeforeGrant = AppReadiness(speechModelReady = true, polishModelReady = true)
        assertEquals(granted.copy(speechModelReady = true, polishModelReady = true), granted.withVerifiedModels(checkedBeforeGrant))
    }

    // ---- practice is judged from the row the owner named for the take, and nothing else ----

    private val ended = PracticeTake(held = false, transcriptId = 6L, ended = true)

    private fun row(status: String, result: String, id: Long = 6L) = TranscriptEntity(
        id = id, originalText = "um words", finalText = "Words.", createdAtMs = 1_000_000L, durationMs = 1_000L,
        speechEngine = "Parakeet", polishEngine = "none", polishLatencyMs = 0L, insertionResult = result, status = status,
    )

    @Test fun aRunningTakeHasNoVerdictYet() {
        assertNull(judgePracticeTake(ended.copy(ended = false), PracticeLesson.TAP, listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))))
    }

    @Test fun wordsThatReachedTheBoxByEitherRouteLand() {
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))))
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED))))
    }

    @Test fun onlyTheRowTheOwnerNamedCounts() {
        // Another row, however it landed, is not this take's; a take the owner gave no row landed nothing.
        val other = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED, id = 9L)
        assertEquals(PracticeOutcome.NOTHING_LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(other)))
        assertEquals(PracticeOutcome.NOTHING_LANDED, judgePracticeTake(ended.copy(transcriptId = null), PracticeLesson.TAP, listOf(other)))
        // The owner deleted the row (a silent or cancelled take): nothing landed.
        assertEquals(PracticeOutcome.NOTHING_LANDED, judgePracticeTake(ended, PracticeLesson.TAP, emptyList()))
    }

    @Test fun anInsertionStillRunningKeepsTheScreenWorking() {
        listOf(TranscriptEntity.STATUS_DRAFT, TranscriptEntity.STATUS_PROCESSING, TranscriptEntity.STATUS_READY_FOR_INSERTION).forEach { status ->
            assertEquals(status, PracticeOutcome.WORKING, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(status, "pending"))))
        }
    }

    @Test fun everyRowThatDidNotPutWordsInTheBoxIsNothingLanded() {
        // Copy-only after a missed insertion, an interrupted insertion, and the empty row a failed
        // recognition keeps: none of them landed words, and none claims to.
        listOf(
            row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COPY_ONLY),
            row(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, InsertionResults.INSERTION_INTERRUPTED),
            row(TranscriptEntity.STATUS_ASR_ERROR, "pending").copy(finalText = ""),
        ).forEach { r -> assertEquals(r.status, PracticeOutcome.NOTHING_LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(r))) }
    }

    @Test fun theHoldLessonTellsATapFromAHold() {
        val landed = listOf(row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))
        assertEquals(PracticeOutcome.LANDED_BY_TAP, judgePracticeTake(ended.copy(held = false), PracticeLesson.HOLD, landed))
        assertEquals(PracticeOutcome.LANDED_BY_TAP, judgePracticeTake(ended.copy(held = null), PracticeLesson.HOLD, landed))
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended.copy(held = true), PracticeLesson.HOLD, landed))
        // The tap lesson does not care how the take was started.
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended.copy(held = true), PracticeLesson.TAP, landed))
    }
}
