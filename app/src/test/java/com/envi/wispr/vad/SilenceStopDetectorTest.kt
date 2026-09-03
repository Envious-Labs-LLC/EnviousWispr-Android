package com.envi.wispr.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-outcome tests. When these fail the user is cut off mid-sentence, or the recorder never stops.
 *
 * Every expected value is a LITERAL, written out by hand from macOS's constants. Nothing here recomputes
 * an expectation with the production converter, because a row built that way proves only that two paths
 * agree, never that either is right.
 */
class SilenceStopDetectorTest {

    /** Feed blocks until one stops the take. Returns the 1-based block index, or null if none did. */
    private fun stopIndex(detector: SilenceStopDetector, probabilities: List<Float>): Int? {
        probabilities.forEachIndexed { index, p ->
            if (detector.onBlock(p)) return index + 1
        }
        return null
    }

    @Test
    fun theShippedConstantsAreMacOSResolvedNotStructDefaults() {
        // fromSensitivity(0.5): alpha 0.3 + 0.5*0.2, onset 0.6 - 0.5*0.375, offset max(0.1, onset-0.15).
        // The struct defaults macOS never ships are 0.3 / 0.5 / 0.35.
        assertEquals(0.4f, SilenceStopDetector.EMA_ALPHA, 0.0001f)
        assertEquals(0.4125f, SilenceStopDetector.ONSET, 0.0001f)
        assertEquals(0.2625f, SilenceStopDetector.OFFSET, 0.0001f)
        assertEquals(1, SilenceStopDetector.ONSET_CONFIRMATION_BLOCKS)
        assertEquals(3, SilenceStopDetector.MIN_HANGOVER_BLOCKS)
    }

    @Test
    fun theHangoverFloorIsThreeBlocks() {
        // 0.5 / 0.256 = 1.95, ceil 2, floored to 3. So the shortest setting still waits 3 blocks.
        assertEquals(3, SilenceStopDetector.hangoverBlocks(0.5f))
        assertEquals(3, SilenceStopDetector.hangoverBlocks(0.7f))
    }

    @Test
    fun theStopWaitsTheConfiguredPause() {
        assertEquals(4, SilenceStopDetector.hangoverBlocks(1.0f))
        assertEquals(6, SilenceStopDetector.hangoverBlocks(1.5f))
        assertEquals(8, SilenceStopDetector.hangoverBlocks(2.0f))
        assertEquals(12, SilenceStopDetector.hangoverBlocks(3.0f))
    }

    @Test
    fun anInvalidPauseValueFallsBackToTheDefault() {
        assertEquals(1.5f, SilenceStopDetector.sanitisePauseSeconds(Float.NaN), 0.0001f)
        assertEquals(1.5f, SilenceStopDetector.sanitisePauseSeconds(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1.5f, SilenceStopDetector.sanitisePauseSeconds(-1f), 0.0001f)
        assertEquals(1.5f, SilenceStopDetector.sanitisePauseSeconds(0.4f), 0.0001f)
        assertEquals(1.5f, SilenceStopDetector.sanitisePauseSeconds(3.01f), 0.0001f)
        assertEquals(0.5f, SilenceStopDetector.sanitisePauseSeconds(0.5f), 0.0001f)
        assertEquals(3.0f, SilenceStopDetector.sanitisePauseSeconds(3.0f), 0.0001f)
    }

    @Test
    fun theEmaTakesTwoLoudBlocksToConfirmSpeech() {
        // Hand-computed: block 1 smooths to 0.4*1.0 = 0.400, which is BELOW onset 0.4125.
        // Block 2 smooths to 0.4*1.0 + 0.6*0.400 = 0.640, which is above it.
        val detector = SilenceStopDetector(1.5f)
        detector.onBlock(1f)
        assertFalse("one loud block must not confirm speech", detector.speechDetected)
        detector.onBlock(1f)
        assertTrue("the second loud block confirms it", detector.speechDetected)
    }

    @Test
    fun theStopConsumesTheTransitionBlockAndConfiguredCountdown() {
        // Hand-computed at the 1.5 s setting, countdown 6.
        //   b1 0.400 idle      b2 0.640 SPEECH
        //   b3 0.384 speech (0.384 >= offset 0.2625, so not yet hangover)
        //   b4 0.230 ENTERS hangover, remaining 6, and does NOT decrement
        //   b5..b10 decrement 6 times; b10 is the stop.
        // Seven below-offset blocks in total, b4 through b10, which is 1.792 s.
        val detector = SilenceStopDetector(1.5f)
        val blocks = listOf(1f, 1f) + List(20) { 0f }
        assertEquals(10, stopIndex(detector, blocks))
    }

    @Test
    fun silenceAfterSpeechStopsTheTakeExactlyOnce() {
        val detector = SilenceStopDetector(1.5f)
        val blocks = listOf(1f, 1f) + List(40) { 0f }
        var stops = 0
        blocks.forEach { if (detector.onBlock(it)) stops++ }
        assertEquals("a take ends once, not repeatedly", 1, stops)
    }

    @Test
    fun silenceWithNoSpeechNeverStops() {
        // The two-way control for the hardware run: a silent room must never auto-stop, because the
        // machine cannot reach hangover without passing through speech.
        val detector = SilenceStopDetector(0.5f)
        assertNull(stopIndex(detector, List(200) { 0f }))
        assertFalse(detector.speechDetected)
    }

    @Test
    fun speechResumingInsideTheHangoverCancelsTheStop() {
        // The Marcus Weber guard: a pause shorter than the setting must not end the take.
        val detector = SilenceStopDetector(3.0f)

        // Speak, then a 6-block gap, which is half the 12-block countdown at the 3.0 s setting.
        assertNull(stopIndex(detector, listOf(1f, 1f) + List(6) { 0f }))
        assertEquals(SilenceStopDetector.Phase.HANGOVER, detector.currentPhase)

        // Speaking again must pull it back out of the countdown, not merely delay it.
        assertNull(stopIndex(detector, List(6) { 1f }))
        assertEquals(SilenceStopDetector.Phase.SPEECH, detector.currentPhase)

        // And the countdown must then start over rather than resume where it left off: six more silent
        // blocks is again only half of it, so the take is still alive.
        assertNull(stopIndex(detector, List(6) { 0f }))
    }

    @Test
    fun hysteresisMeansOnsetAndOffsetDiffer() {
        // A sustained 0.35 sits between offset 0.2625 and onset 0.4125, so the EMA converges there.
        // From idle it must never start speech, and from speech it must never begin the hangover.
        val fromIdle = SilenceStopDetector(1.5f)
        assertNull(stopIndex(fromIdle, List(100) { 0.35f }))
        assertFalse(fromIdle.speechDetected)

        val fromSpeech = SilenceStopDetector(1.5f)
        fromSpeech.onBlock(1f)
        fromSpeech.onBlock(1f)
        assertTrue(fromSpeech.speechDetected)
        assertNull(stopIndex(fromSpeech, List(100) { 0.35f }))
        assertEquals(SilenceStopDetector.Phase.SPEECH, fromSpeech.currentPhase)
    }

    @Test
    fun theBlockProbabilityIsTheLastWindow() {
        // Silero is recurrent, so the eighth window already carries the preceding seven. A maximum would
        // let one spike arm a whole block; this asserts the spike does not survive.
        val spikeThenSilence = floatArrayOf(0.99f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, SilenceStopDetector.blockProbability(spikeThenSilence), 0.0001f)

        val silenceThenSpeech = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.9f)
        assertEquals(0.9f, SilenceStopDetector.blockProbability(silenceThenSpeech), 0.0001f)
    }

    @Test
    fun isolatedSpikesWhileIdleDoNotEndATakeThatNeverStarted() {
        // The failure a maximum would create, driven through the real aggregation rather than asserted
        // about it. Two blocks that each contain one loud 32 ms window and are otherwise silent: a room
        // with a couple of clicks in it, and nobody speaking.
        //
        // Under the maximum, both blocks read 0.99, the smoothing reaches 0.634 by the second, speech is
        // armed, and the silence that follows walks the countdown and ends a take the user never started.
        // TWO blocks are needed to expose it because one only reaches 0.400 against an onset of 0.4125,
        // which is why an earlier single-block version of this test passed with the behaviour removed.
        val clickyBlock = floatArrayOf(0.99f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val detector = SilenceStopDetector(0.5f)
        val blocks = List(2) { SilenceStopDetector.blockProbability(clickyBlock) } + List(50) { 0f }

        assertNull("a room with clicks in it must not end a take nobody started", stopIndex(detector, blocks))
        assertFalse(detector.speechDetected)
    }

    @Test
    fun aNonFiniteProbabilityIsTreatedAsSilenceRatherThanCrashing() {
        val detector = SilenceStopDetector(1.5f)
        detector.onBlock(1f)
        detector.onBlock(1f)
        assertTrue(detector.speechDetected)
        val blocks = List(20) { Float.NaN }
        assertEquals("NaN reads as silence, so the take still ends", 8, stopIndex(detector, blocks))
    }

    @Test
    fun oneBlockIsFourThousandAndNinetySixSamplesAndEightWindows() {
        assertEquals(4096, SilenceStopDetector.SAMPLES_PER_BLOCK)
        assertEquals(512, SilenceStopDetector.WINDOW_SAMPLES)
        assertEquals(8, SilenceStopDetector.WINDOWS_PER_BLOCK)
    }
}
