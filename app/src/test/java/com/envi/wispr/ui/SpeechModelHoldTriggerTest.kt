package com.envi.wispr.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness Contract with the coordinator's DECISIONS (#37): the session tells the application's speech-model hold
 * when a take's speech bind succeeded, when its transcript arrived with words, and when it ended, always with the
 * same take id. When the "used" call is missing, the next dictation waits for a model reload; when it fires on a
 * cancel, a take nobody used keeps 700 MB loaded. Fakes from [DictationSessionRig]; the device run owns the binding.
 * REVERT: drop `host.speechModelUsed` from `onResult` (first row red); call it for blank text (third row red).
 */
class SpeechModelHoldTriggerTest {
    private val rig = DictationSessionRig()

    @After
    fun tearDown() = rig.close()

    private fun startAndGoLive(coordinator: DictationSessionCoordinator) {
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
    }

    private fun takeIds(): Set<String> = rig.host.speechHold.map { it.substringAfter(':') }.toSet()

    @Test fun aCompletedTakeStartsUsesAndEndsTheHoldWithOneId() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("hello world")
        rig.polish.awaitRequest { "log: ${rig.log.lines}" }
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        rig.host.awaitStopped()
        assertEquals(listOf("started", "used", "ended"), rig.host.speechHold.map { it.substringBefore(':') })
        assertEquals("one take id across the three calls", 1, takeIds().size)
    }

    @Test fun aCancelledTakeEndsTheHoldWithoutUsingIt() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        rig.host.awaitStopped()
        assertTrue(rig.host.speechHold.none { it.startsWith("used:") })
        assertTrue("the take's end still reaches the hold", rig.host.speechHold.any { it.startsWith("ended:") })
    }

    @Test fun aBlankTranscriptNeverStartsTheCountdown() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("   ")
        rig.host.awaitStopped()
        assertTrue(rig.host.speechHold.none { it.startsWith("used:") })
        assertTrue(rig.host.speechHold.any { it.startsWith("ended:") })
    }
}
