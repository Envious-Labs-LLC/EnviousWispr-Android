package com.envi.wispr.ui

import com.envi.wispr.paste.InsertionHandoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observability Contract (#560, #563): driven through the real [DictationSessionCoordinator] on the rig.
 *
 * 1. (#563) The session owner reports a take that proves setup works — handed to auto-paste in another
 *    app's field — and no other. `app.launched`'s `onboarding_complete` is settled from this report
 *    (`OnboardingSettlement`, covered in `OnboardingTelemetryTest`).
 * 2. (#560) The session owner publishes each take's `takeId` at `showStarting` and marks `takeId` as
 *    ended-without-row on wordless/non-completed endings and failed History saves, and never on a healthy
 *    completed take.
 *
 * REVERTS that turn rows red: drop the `dictationProvesSetup` call after `finalizer.deliver`; drop the
 * practice-box exclusion from `dictationProvesSetup`; report before `deliver` corrects the handoff; omit
 * `takeId` from `showStarting`; omit `markTakeEndedWithoutRow` from `arbiter` or `saved.onAnswered`.
 */
class SetupProvenByDictationTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() = rig.close()

    /** One completed take; returns how many times the owner reported it proved setup. */
    private fun completedTake(): Long {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("tell grandma i will call")
        rig.polish.awaitRequest().onOutcome(rig.polish.outcome("Tell Grandma I will call."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        return rig.host.setupProven.get()
    }

    @Test fun aTakeHandedToAutoPasteInAnotherAppProvesSetupOnce() {
        assertEquals(1L, completedTake())
        assertEquals("precondition: the take was handed off", 1, rig.insertion.requests.size)
    }

    @Test fun aPracticeBoxTakeNeverProvesSetup() {
        rig.insertion.fieldId = OnboardingViewModel.PRACTICE_FIELD_ID
        assertEquals(0L, completedTake())
        assertEquals("precondition: the take was handed off", 1, rig.insertion.requests.size)
    }

    @Test fun aTakeTheServiceCouldNotTakeNeverProvesSetup() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        assertEquals(0L, completedTake())
    }

    @Test fun aCompletedTakePublishesItsTakeIdAndNeverMarksEndedWithoutRow() {
        completedTake()
        rig.awaitHistoryIdle()
        val takeId = rig.surface.startedTakeIds.single()
        assertTrue("takeId must be non-blank", takeId.isNotBlank())
        assertEquals(takeId, rig.dao.rows.values.single().takeId)
        assertEquals(emptyList<String>(), rig.surface.endedWithoutRow.toList())
    }

    @Test fun aWordlessTakeMarksItsTakeIdAsEndedWithoutRow() {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("")
        assertEquals(TerminalReason.ASR_EMPTY_DESPITE_AUDIO, rig.endings.awaitOne())
        rig.host.awaitServiceStopped()
        rig.awaitHistoryIdle()
        val takeId = rig.surface.startedTakeIds.single()
        assertEquals(emptyMap<Long, com.envi.wispr.history.TranscriptEntity>(), rig.dao.rows.toMap())
        assertEquals(listOf(takeId), rig.surface.endedWithoutRow.toList())
    }

    @Test fun aFailedHistorySaveMarksItsTakeIdAsEndedWithoutRow() {
        rig.dao.failInserts = true
        completedTake()
        rig.awaitHistoryIdle()
        val takeId = rig.surface.startedTakeIds.single()
        assertEquals(listOf(takeId), rig.surface.endedWithoutRow.toList())
    }
}
