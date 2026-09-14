package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Product Outcome: setup cannot skip missing requirements, and practice is judged by what really landed. */
class OnboardingPolicyTest {
    private val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true)

    @Test fun restoredPracticeReturnsToDownloadsWhenAModelIsMissing() {
        assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(3, ready.copy(polishModelReady = false), AutoPasteAvailability.LIVE))
    }

    @Test fun enabledButDisconnectedAccessibilityDoesNotAllowPractice() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready, AutoPasteAvailability.PERMITTED_NOT_RUNNING))
    }

    @Test fun notificationsAreOptionalButMicrophoneIsRequired() {
        assertEquals(OnboardingStage.PRACTICE, onboardingStage(3, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(3, ready.copy(microphoneGranted = false), AutoPasteAvailability.LIVE))
    }

    @Test fun verifiedDownloadsAdvanceAndWelcomeRemainsAvailable() {
        assertEquals(OnboardingStage.PERMISSIONS, onboardingStage(1, ready, AutoPasteAvailability.LIVE))
        assertEquals(OnboardingStage.WELCOME, onboardingStage(0, ready, AutoPasteAvailability.LIVE))
    }

    @Test fun anOlderModelCheckCannotRevokeANewerPermissionAnswer() {
        val granted = AppReadiness(microphoneGranted = true, notificationsGranted = true, accessibilityPermitted = true)
        val checkedBeforeGrant = AppReadiness(speechModelReady = true, polishModelReady = true)
        assertEquals(granted.copy(speechModelReady = true, polishModelReady = true), granted.withVerifiedModels(checkedBeforeGrant))
    }

    // ---- practice is judged from the History row the take wrote ----

    private val takeStart = 1_000_000L
    private val ended = PracticeTake(startedAtMs = takeStart, held = false, processed = true, ended = true)

    private fun row(createdAt: Long, status: String, result: String) = TranscriptEntity(
        id = createdAt, originalText = "um words", finalText = "Words.", createdAtMs = createdAt, durationMs = 1_000L,
        speechEngine = "Parakeet", polishEngine = "none", polishLatencyMs = 0L, insertionResult = result, status = status,
    )

    @Test fun aRunningTakeHasNoVerdictYet() {
        assertNull(judgePracticeTake(ended.copy(ended = false), PracticeLesson.TAP, listOf(row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))))
    }

    @Test fun wordsThatReachedTheBoxByEitherRouteLand() {
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))))
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.PASTED))))
    }

    @Test fun aRowFromAnEarlierTakeIsNotThisTakesVerdict() {
        // The tap lesson's row is older than this take; with no row of its own the take produced no words.
        val earlier = row(takeStart - 5_000, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED)
        assertEquals(PracticeOutcome.NO_WORDS, judgePracticeTake(ended, PracticeLesson.HOLD, listOf(earlier)))
    }

    @Test fun noRowMeansNoWordsWhenTranscribedAndNothingAddedWhenNot() {
        assertEquals(PracticeOutcome.NO_WORDS, judgePracticeTake(ended, PracticeLesson.TAP, emptyList()))
        assertEquals(PracticeOutcome.NOTHING_ADDED, judgePracticeTake(ended.copy(processed = false), PracticeLesson.TAP, emptyList()))
    }

    @Test fun anInsertionStillRunningKeepsTheScreenWorking() {
        listOf(TranscriptEntity.STATUS_DRAFT, TranscriptEntity.STATUS_PROCESSING, TranscriptEntity.STATUS_READY_FOR_INSERTION).forEach { status ->
            assertEquals(status, PracticeOutcome.WORKING, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(takeStart + 10, status, "pending"))))
        }
    }

    @Test fun wordsSavedButNotInTheBoxAreAMiss() {
        assertEquals(PracticeOutcome.MISSED_BOX, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COPY_ONLY))))
        assertEquals(PracticeOutcome.MISSED_BOX, judgePracticeTake(ended, PracticeLesson.TAP, listOf(row(takeStart + 10, TranscriptEntity.STATUS_INSERTION_INTERRUPTED, InsertionResults.INSERTION_INTERRUPTED))))
    }

    @Test fun theHoldLessonTellsATapFromAHold() {
        val landed = listOf(row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED))
        assertEquals(PracticeOutcome.LANDED_BY_TAP, judgePracticeTake(ended.copy(held = false), PracticeLesson.HOLD, landed))
        assertEquals(PracticeOutcome.LANDED_BY_TAP, judgePracticeTake(ended.copy(held = null), PracticeLesson.HOLD, landed))
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended.copy(held = true), PracticeLesson.HOLD, landed))
        // The tap lesson does not care how the take was started.
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended.copy(held = true), PracticeLesson.TAP, landed))
    }

    @Test fun theNewestRowOfTheTakeIsTheOneJudged() {
        val rows = listOf(
            row(takeStart + 10, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COPY_ONLY),
            row(takeStart + 20, TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED),
        )
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(ended, PracticeLesson.TAP, rows))
    }
}
