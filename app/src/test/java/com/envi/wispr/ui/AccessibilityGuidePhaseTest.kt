package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drift guard (#476): the guide's saved phase and its recreation rules. When this fails, a recreated guide
 * relaunches Settings by itself or loses the user's place.
 */
class AccessibilityGuidePhaseTest {
    private val every = listOf(
        GuidePhase.Disclosure,
        GuidePhase.Ready(GuideHistory.NeverOpened), GuidePhase.Ready(GuideHistory.Opened),
        GuidePhase.Launching(GuideHistory.NeverOpened), GuidePhase.Launching(GuideHistory.Opened),
        GuidePhase.AwaitingSettings,
        GuidePhase.LaunchFailed(GuideHistory.NeverOpened), GuidePhase.LaunchFailed(GuideHistory.Opened),
        GuidePhase.Complete,
    )

    @Test fun everyPhaseSurvivesSaving() {
        every.forEach { assertEquals(it, GuidePhase.decode(GuidePhase.encode(it))) }
    }

    @Test fun unknownSavedValuesStartFresh() {
        listOf(null, "", "ready", "ready:sometimes", "launching:", "pip").forEach { assertNull(it, GuidePhase.decode(it)) }
    }

    @Test fun aHandOffInFlightIsNotRepeatedAfterRecreation() {
        assertEquals(GuidePhase.Ready(GuideHistory.NeverOpened), GuidePhase.restored(GuidePhase.Launching(GuideHistory.NeverOpened)))
        assertEquals(GuidePhase.Ready(GuideHistory.Opened), GuidePhase.restored(GuidePhase.Launching(GuideHistory.Opened)))
        every.filter { it !is GuidePhase.Launching }.forEach { assertEquals(it, GuidePhase.restored(it)) }
    }

    @Test fun comingBackFromSettingsOffersSettingsAgain() {
        assertEquals(GuidePhase.Ready(GuideHistory.Opened), GuidePhase.returned(GuidePhase.AwaitingSettings))
        every.filter { it != GuidePhase.AwaitingSettings }.forEach { assertEquals(it, GuidePhase.returned(it)) }
    }
}
