package com.envi.wispr.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product outcome (#108, founder: "pause dictation during any engine swaps"). When this fails, a take starts while a
 * model update renames the folders under the engines, or a start during the update is silently dropped, or a finished
 * take keeps the update waiting forever. Owner decisions against the rig's fakes (see `DictationSessionCoordinatorTest`).
 */
class ModelSwapAdmissionTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() = rig.close()

    /** REVERT: start without asking the gate; the take binds and records during the swap. */
    @Test fun aStartDuringAModelSwapIsRefusedWithOneCalmLine() {
        assertTrue(rig.swapGate.beginSwap())
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.host.awaitApplicationToast()
        rig.host.awaitStopped()
        assertTrue(rig.host.events.any { it.startsWith("toast-app:A model is updating. Try again in a moment.") })
        assertFalse("no engine is bound for a refused start", rig.pipeline.events.contains("bind"))
        assertTrue("nothing was recorded", rig.endings.reasons.isEmpty())
    }

    /** REVERT: drop `releaseSwapPermit()` from the take's end; the swap then never finds the phone idle. */
    @Test fun aTakeHoldsOffTheSwapUntilItEnds() {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        assertFalse("a swap never starts under a take", rig.swapGate.beginSwap())
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        rig.host.awaitStopped()
        assertTrue("the ended take released its permit", rig.swapGate.beginSwap())
    }
}
