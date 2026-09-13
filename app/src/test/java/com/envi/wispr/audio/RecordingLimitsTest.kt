package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. When this fails a user either loses the end of a long dictation with no warning, or
 * is refused a recording the app told them it would accept.
 *
 * The cap is a stated promise about capacity, so it gets a test that fails when the promise stops being
 * true (`testing-philosophy.md` RULE: a-public-product-promise-needs-a-binding-test).
 */
class RecordingLimitsTest {

    @Test
    fun theCapIsTenMinutes() {
        // Not a restatement of the constant: it is the number the user is promised, and changing it is
        // a product decision that must be made deliberately rather than reached by editing a file.
        assertEquals(600_000L, RecordingLimits.MAX_DURATION_MS)
        assertEquals(10, RecordingLimits.MAX_DURATION_MINUTES)
    }

    @Test
    fun theCapFitsInsideTheHeapTheSpeechProcessIsAllowed() {
        // Measured on the founder's S26 Ultra 2026-09-05: dalvik.vm.heapgrowthlimit is 256m and the
        // manifest declares no largeHeap. AsrService holds the take twice at once, as bytes and as
        // floats, which is 6 bytes of Java heap per 2-byte sample.
        val bytesPerSecond = PcmAudio.SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE
        val peakHeapBytes = RecordingLimits.MAX_AUDIO_BYTES * 3
        val growthLimitBytes = 256L * 1024 * 1024
        assertEquals(32_000L, bytesPerSecond.toLong())
        assertTrue(
            "a full take needs $peakHeapBytes bytes of heap, against a $growthLimitBytes limit",
            peakHeapBytes < growthLimitBytes / 2,
        )
    }

    @Test
    fun theByteCeilingIsDerivedFromTheDurationAndNotChosenSeparately() {
        val expected = 600L * 16_000L * 2L
        assertEquals(
            "the ceiling must be exactly the cap's worth of audio",
            expected,
            RecordingLimits.MAX_AUDIO_BYTES,
        )
        assertEquals(
            RecordingLimits.MAX_DURATION_MS / 1000f,
            PcmAudio.durationSeconds(RecordingLimits.MAX_AUDIO_BYTES),
            0.001f,
        )
    }

    @Test
    fun theWarningArrivesWithTimeLeftToFinishASentence() {
        // These relationships are enforced HERE, at test time, and not by the object itself. Every
        // value in RecordingLimits is a `const val`, which the compiler inlines, so the reads this app
        // makes never trigger an `init` block there.
        assertTrue("the warning must fire before the cap", RecordingLimits.WARNING_LEAD_MS > 0)
        assertTrue(
            "the warning must fire inside the take, not before it starts",
            RecordingLimits.WARNING_LEAD_MS < RecordingLimits.MAX_DURATION_MS,
        )
        assertTrue(
            "the warning must arrive after the take has started",
            RecordingLimits.WARNING_AT_MS > 0L,
        )
        assertEquals(
            "the warning moment must be the cap minus the lead",
            RecordingLimits.MAX_DURATION_MS - RecordingLimits.WARNING_LEAD_MS,
            RecordingLimits.WARNING_AT_MS,
        )
        assertTrue(
            "a cap under a minute cannot be named in minutes",
            RecordingLimits.MAX_DURATION_MINUTES >= 1,
        )
        assertEquals(60_000L, RecordingLimits.WARNING_LEAD_MS)
    }
}
