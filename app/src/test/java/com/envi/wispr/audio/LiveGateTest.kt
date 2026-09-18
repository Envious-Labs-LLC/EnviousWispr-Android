package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live gate against the shapes measured on the S26 with AirPods Pro 3 on 2026-09-18
 * (`bluetooth-capture-android.md`): zeros before the link, 2 to 6 while the earbud microphone is muted,
 * 12 to 22 live and quiet, 20 to 110 on a deaf link under speech, one click block of 155.
 */
class LiveGateTest {

    /** One 100 ms window of 16-bit samples at the given peak, as 32 ms reads (512 samples, 1,024 bytes). */
    private fun reads(peak: Int, windows: Int): List<ByteArray> {
        val samples = LiveGate.WINDOW_SAMPLES * windows
        val bytes = ByteArray(samples * PcmAudio.BYTES_PER_SAMPLE)
        for (i in 0 until samples) {
            // Alternate sign so the peak test sees magnitude, not sign; every sample at the peak.
            val v = if (i % 2 == 0) peak else -peak
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes.toList().chunked(1_024).map { it.toByteArray() }
    }

    private fun feed(gate: LiveGate, peak: Int, windows: Int, admissible: Boolean = true): Boolean {
        var opened = false
        for (r in reads(peak, windows)) if (gate.offer(r, r.size, admissible)) opened = true
        return opened
    }

    @Test
    fun zerosAndMutedBlocksNeverOpenTheGate() {
        val gate = LiveGate(gated = true)
        assertFalse(feed(gate, 0, 8))
        assertFalse(feed(gate, 6, 12))
        assertEquals(LiveGate.State.WAITING, gate.state)
    }

    @Test
    fun oneClickBlockThenQuietStaysWaiting() {
        val gate = LiveGate(gated = true)
        feed(gate, 0, 5)
        assertFalse(feed(gate, 155, 1))
        assertFalse(feed(gate, 4, 12))
        assertEquals(LiveGate.State.WAITING, gate.state)
    }

    @Test
    fun twoLiveBlocksOpenIt() {
        val gate = LiveGate(gated = true)
        feed(gate, 0, 8)
        feed(gate, 4, 12)
        assertFalse("one live window is not enough", feed(gate, 15, 1))
        assertEquals(LiveGate.State.WAITING, gate.state)
        assertFalse("a low window resets the count", feed(gate, 4, 1))
        assertFalse(feed(gate, 15, 1))
        assertEquals(LiveGate.State.WAITING, gate.state)
        assertTrue("the second consecutive live window opens it", feed(gate, 15, 1))
        assertEquals(LiveGate.State.READY, gate.state)
    }

    @Test
    fun theThresholdIsTenAndTwelveIsLive() {
        assertEquals(10, LiveGate.LIVE_PEAK)
        val gate = LiveGate(gated = true)
        assertTrue(feed(gate, 12, 2))
    }

    @Test
    fun aPhoneRouteIsLiveAtOnce() {
        val gate = LiveGate(gated = false)
        assertEquals(LiveGate.State.READY, gate.state)
        assertFalse("nothing to open", feed(gate, 0, 1))
    }

    @Test
    fun theMeasuredDeafTraceOpensTheGate() {
        // The limit, written down: a deaf link under speech (20 to 110) reads as sound. The gate promises
        // sound, not speech; the hold is what makes this shape rare (plan §2.2).
        val gate = LiveGate(gated = true)
        feed(gate, 0, 5)
        feed(gate, 3, 12)
        assertTrue(feed(gate, 38, 1) || feed(gate, 83, 1))
        assertEquals(LiveGate.State.READY, gate.state)
    }

    @Test
    fun aPhoneWindowDoesNotCountForAnEarbudTarget() {
        val gate = LiveGate(gated = true)
        assertFalse(feed(gate, 500, 4, admissible = false))
        assertEquals(LiveGate.State.WAITING, gate.state)
        assertTrue("the same sound on an admissible route opens it", feed(gate, 500, 2, admissible = true))
    }

    @Test
    fun partialReadsAccumulateIntoWindows() {
        val gate = LiveGate(gated = true)
        val all = reads(15, 2).flatMap { it.toList() }.toByteArray()
        var opened = false
        var offset = 0
        val step = 700 // not a multiple of the window
        while (offset < all.size) {
            val n = minOf(step, all.size - offset)
            val chunk = all.copyOfRange(offset, offset + n)
            if (gate.offer(chunk, n, true)) opened = true
            offset += n
        }
        assertTrue(opened)
    }

    @Test
    fun theDeadlineResetsOnceThenForces() {
        val gate = LiveGate(gated = true)
        assertEquals(LiveGate.DeadlineAction.RESET, gate.deadlinePassed())
        assertEquals(1, gate.resetsUsed)
        assertEquals("still waiting after the reset", LiveGate.State.WAITING, gate.state)
        assertEquals(LiveGate.DeadlineAction.FORCE, gate.deadlinePassed())
        assertEquals("the gate does not force itself", LiveGate.State.WAITING, gate.state)
        gate.force()
        assertEquals(LiveGate.State.FORCED, gate.state)
        assertEquals(LiveGate.DeadlineAction.NONE, gate.deadlinePassed())
        assertFalse("a forced gate ignores sound", feed(gate, 500, 2))
    }

    @Test
    fun aDeadlineOnAnOpenGateDoesNothing() {
        val gate = LiveGate(gated = true)
        feed(gate, 15, 2)
        assertEquals(LiveGate.DeadlineAction.NONE, gate.deadlinePassed())
        assertEquals(LiveGate.State.READY, gate.state)
    }

    @Test
    fun theConstantsAreTheMeasuredOnes() {
        assertEquals(1_600, LiveGate.WINDOW_SAMPLES)
        assertEquals(2, LiveGate.WINDOWS_REQUIRED)
        assertEquals(3_500L, LiveGate.DEADLINE_MS)
        assertEquals(1, LiveGate.MAX_RESETS)
    }
}
