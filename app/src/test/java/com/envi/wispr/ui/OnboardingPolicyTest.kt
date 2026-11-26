package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.AccessibilityPermissionCheck
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product Outcome: setup cannot skip missing requirements, and practice is judged by what really landed. */
class OnboardingPolicyTest {
    private val ready = AppReadiness(
        microphoneGranted = true,
        speechModelReady = true,
        polishModelReady = true,
        onboardingPolishModelReady = true,
    )

    @Test fun restoredPracticeReturnsToDownloadsWhenAModelIsMissing() {
        assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(4, ready.copy(onboardingPolishModelReady = false), AutoPasteAvailability.LIVE))
    }

    @Test fun setUpLaterUserSwitchingToMissingEg1MicroDoesNotBlockBaselineOnboarding() {
        assertEquals(OnboardingStage.PRACTICE, onboardingStage(4, ready.copy(polishModelReady = false), AutoPasteAvailability.LIVE))
    }

    @Test fun bothRequiredModelsBlockBothRestoredDemoAndPractice() {
        for (step in listOf(OnboardingStage.DEMO.ordinal, OnboardingStage.PRACTICE.ordinal)) {
            assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(step, ready.copy(speechModelReady = false), AutoPasteAvailability.LIVE))
            assertEquals(OnboardingStage.DOWNLOADS, onboardingStage(step, ready.copy(onboardingPolishModelReady = false), AutoPasteAvailability.LIVE))
        }
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
        val checkedBeforeGrant = AppReadiness(
            speechModelReady = true,
            polishModelReady = true,
            onboardingPolishModelReady = true,
            eg1PolishModelReady = true,
            modelsChecked = true,
        )
        assertEquals(
            granted.copy(
                speechModelReady = true,
                polishModelReady = true,
                onboardingPolishModelReady = true,
                eg1PolishModelReady = true,
                modelsChecked = true,
            ),
            granted.withVerifiedModels(checkedBeforeGrant),
        )
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

    /** #560: `saved_unrouted` is the row the owner writes before the route, while the words may still commit. */
    @Test fun anInsertionStillRunningKeepsTheScreenWorking() {
        listOf(
            TranscriptEntity.STATUS_DRAFT,
            TranscriptEntity.STATUS_PROCESSING,
            TranscriptEntity.STATUS_SAVED_UNROUTED,
            TranscriptEntity.STATUS_READY_FOR_INSERTION,
        ).forEach { status ->
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

    /** #560: a take identified by takeId waits for Room's row or an explicit wordless/save-failed ending. */
    @Test fun anIdentifiedTakeWithNoRowYetStaysWorkingUntilRowOrWordlessEnding() {
        val identified = PracticeTake(held = false, transcriptId = null, ended = true, takeId = "take-1")
        val other = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED, id = 9L).copy(takeId = "take-other")
        // Room has not emitted this take's row yet, and attachTranscript never arrived: WORKING, never NOTHING_LANDED.
        assertEquals(PracticeOutcome.WORKING, judgePracticeTake(identified, PracticeLesson.TAP, emptyList()))
        assertEquals(PracticeOutcome.WORKING, judgePracticeTake(identified, PracticeLesson.TAP, listOf(other)))
        // Once Room emits the committed row with matching takeId, it lands even with transcriptId == null.
        val matching = row(TranscriptEntity.STATUS_COMPLETED, InsertionResults.COMMITTED, id = 99L).copy(takeId = "take-1")
        assertEquals(PracticeOutcome.LANDED, judgePracticeTake(identified, PracticeLesson.TAP, listOf(other, matching)))
        // An explicit wordless/save-failed ending yields NOTHING_LANDED even if no row or a stale draft exists.
        assertEquals(PracticeOutcome.NOTHING_LANDED, judgePracticeTake(identified, PracticeLesson.TAP, emptyList(), setOf("take-1")))
        val staleDraft = row(TranscriptEntity.STATUS_DRAFT, "pending", id = 99L).copy(takeId = "take-1")
        assertEquals(PracticeOutcome.NOTHING_LANDED, judgePracticeTake(identified, PracticeLesson.TAP, listOf(staleDraft), setOf("take-1")))
    }

    /**
     * #560 fell through a default: a status the practice table did not list was read as final. Every status
     * History declares must have an explicit reading, open or final, so the next one fails here rather than
     * on the founder's dashboard. The constants are read off the class, never typed out.
     */
    @Test fun everyHistoryStatusHasAnExplicitPracticeReading() {
        val statuses = TranscriptEntity::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.startsWith("STATUS_") }
            .associate { it.name to it.apply { isAccessible = true }.get(null) as String }
        assertTrue("found no STATUS_ constants, so this guard would pass about nothing", statuses.size >= 10)
        val unread = statuses.filterValues { practiceRowStillOpen(it) == null }
        assertEquals("statuses with no practice reading", emptyMap<String, String>(), unread)
        assertEquals(true, practiceRowStillOpen("saved_unrouted"))
        assertEquals(false, practiceRowStillOpen("completed"))
    }

    // ---- #563: the step the downloads move setup to is stored, and a setup finished elsewhere is complete ----

    @Test fun theDownloadsFinishingStoresThePermissionsStep() {
        assertEquals(2, onboardingStepToRecord(OnboardingStage.DOWNLOADS.ordinal, onboardingStage(1, ready, AutoPasteAvailability.LIVE)))
        // Once stored, nothing more to store; and a download still running stores nothing.
        assertNull(onboardingStepToRecord(OnboardingStage.PERMISSIONS.ordinal, onboardingStage(2, ready, AutoPasteAvailability.LIVE)))
        assertNull(onboardingStepToRecord(1, onboardingStage(1, ready.copy(speechModelReady = false), AutoPasteAvailability.LIVE)))
        assertNull(onboardingStepToRecord(0, onboardingStage(0, ready, AutoPasteAvailability.LIVE)))
    }

    /** The cold-start connect window shows Permissions for a restored practice step; the step must survive it. */
    @Test fun aStageShownBehindTheStoredStepIsNeverStored() {
        val shown = onboardingStage(OnboardingStage.PRACTICE.ordinal, ready, AutoPasteAvailability.PERMITTED_NOT_RUNNING)
        assertEquals(OnboardingStage.PERMISSIONS, shown)
        assertNull(onboardingStepToRecord(OnboardingStage.PRACTICE.ordinal, shown))
    }

    /** #563: only a take handed to auto-paste in a field that is not the practice box proves setup. */
    @Test fun onlyAHandoffToAnotherAppsFieldProvesSetup() {
        // Most third-party editors carry no view id; a pinned take into one still counts.
        assertTrue(dictationProvesSetup(DictationTargetPin.PINNED, null, InsertionHandoff.SCHEDULED))
        assertFalse(dictationProvesSetup(DictationTargetPin.PINNED, OnboardingViewModel.PRACTICE_FIELD_ID, InsertionHandoff.SCHEDULED))
        assertFalse(dictationProvesSetup(DictationTargetPin.PINNED, "field-1", InsertionHandoff.SERVICE_NOT_RUNNING))
        assertFalse(dictationProvesSetup(DictationTargetPin.NO_TARGET, null, InsertionHandoff.NO_PINNED_TARGET))
    }
}
