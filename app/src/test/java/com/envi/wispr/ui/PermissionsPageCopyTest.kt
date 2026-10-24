package com.envi.wispr.ui

import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. When this fails, the Permissions page tells a user whose auto-paste Android switched
 * off to finish first-time setup, or claims a cause it cannot know (#131). Literals, so the founder's
 * approved wording (Gate 2, 2026-10-01) is the oracle rather than the code that renders it.
 */
class PermissionsPageCopyTest {

    @Test
    fun eachAutoPasteStateHasItsApprovedCardAndRow() {
        val cards = mapOf(
            AutoPasteAvailability.LIVE to null,
            AutoPasteAvailability.NOT_PERMITTED to null,
            AutoPasteAvailability.PERMITTED_NOT_RUNNING to AutoPasteNotice(
                "Auto-paste is not connected",
                "Your words will not go into the field until it reconnects. If it stays disconnected, " +
                    "turn EnviousWispr off and then on in Accessibility settings.",
            ),
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY to AutoPasteNotice(
                "Auto-paste was switched off",
                "This can happen when the app is stopped, or when Android’s floating accessibility button is tapped. " +
                    "Turn EnviousWispr back on in Accessibility settings, and leave the EnviousWispr shortcut off.",
            ),
        )
        assertEquals(AutoPasteAvailability.entries.toSet(), cards.keys)
        cards.forEach { (autoPaste, card) -> assertEquals("card for $autoPaste", card, autoPasteNotice(autoPaste)) }

        val rows = mapOf(
            AutoPasteAvailability.LIVE to "Ready for side-button dictation",
            AutoPasteAvailability.NOT_PERMITTED to "Needs accessibility permission",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING to
                "Turned on but not connected. Words will not go into the field until it reconnects.",
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY to "Switched off. Turn it back on in Accessibility settings.",
        )
        assertEquals(AutoPasteAvailability.entries.toSet(), rows.keys)
        rows.forEach { (autoPaste, row) -> assertEquals("row for $autoPaste", row, autoPasteRowSubtitle(autoPaste)) }
        assertEquals("Needs attention", AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY.statusDescription())
    }

    /** Plan §3.2: with core setup unfinished the red card shows as today and the calm card does not. */
    @Test
    fun switchedOffWithSetupUnfinishedShowsOnlyTheSetupCard() {
        val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true, modelsChecked = true)
        val switchedOff = AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY
        listOf(ready.copy(microphoneGranted = false), ready.copy(polishModelReady = false)).forEach { unfinished ->
            assertEquals(true, setupCardShows(unfinished, switchedOff))
            assertEquals(null, calmAutoPasteNotice(unfinished, switchedOff))
        }
        assertEquals(false, setupCardShows(ready, switchedOff))
        assertEquals("Auto-paste was switched off", calmAutoPasteNotice(ready, switchedOff)?.title)
        assertEquals("a never-set-up user keeps the setup card", true, setupCardShows(ready, AutoPasteAvailability.NOT_PERMITTED))
    }

    /** #403: opening the page before the model file check answers showed the red card for 2 to 3 seconds. */
    @Test
    fun modelsNotCheckedYetRaiseNoCard() {
        val unchecked = AppReadiness(microphoneGranted = true)
        assertEquals(false, setupCardShows(unchecked, AutoPasteAvailability.LIVE))
        assertEquals(null, calmAutoPasteNotice(unchecked, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY))
        // Control: the same snapshot once the check has answered that a model is missing.
        assertEquals(true, setupCardShows(unchecked.copy(modelsChecked = true, speechModelReady = true), AutoPasteAvailability.LIVE))
        // Facts known without the file check still raise it.
        assertEquals(true, setupCardShows(AppReadiness(), AutoPasteAvailability.LIVE))
        assertEquals(true, setupCardShows(unchecked, AutoPasteAvailability.NOT_PERMITTED))
    }

    /** #406: after setup was completed, Continue resumed a flow that cannot reopen, so it did nothing. */
    @Test
    fun continueGoesToTheFirstUnmetNeedOnceSetupWasCompleted() {
        val ready = AppReadiness(microphoneGranted = true, speechModelReady = true, polishModelReady = true, modelsChecked = true)
        val cases = listOf(
            Triple(false, ready.copy(speechModelReady = false), SetupContinue.GUIDED_SETUP),
            Triple(true, ready.copy(speechModelReady = false, polishModelReady = false), SetupContinue.SPEECH_MODEL),
            Triple(true, ready.copy(polishModelReady = false, microphoneGranted = false), SetupContinue.POLISH_MODEL),
            Triple(true, ready.copy(microphoneGranted = false), SetupContinue.MICROPHONE),
            Triple(true, ready.copy(microphoneGranted = false, modelsChecked = false, speechModelReady = false), SetupContinue.MICROPHONE),
            Triple(true, ready, SetupContinue.AUTO_PASTE),
        )
        assertEquals(SetupContinue.entries.toSet(), cases.map { it.third }.toSet())
        cases.forEach { (complete, readiness, expected) ->
            assertEquals("complete=$complete $readiness", expected, setupContinue(complete, readiness))
        }
    }
}
