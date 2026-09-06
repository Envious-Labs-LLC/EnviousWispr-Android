package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. When this fails the user watches a meter that does not move while they speak, or one
 * that sits full in a silent room, and either one says the microphone is broken when it is not.
 *
 * The amplitudes here are the units `AudioCaptureService` actually publishes: the mean absolute 16-bit
 * sample divided by [Short.MAX_VALUE], so 1.0 is a permanently clipped signal and ordinary speech is a
 * small fraction.
 */
class AudioLevelScaleTest {

    /** Room tone on a quiet phone, well under the scale's floor. */
    private val quietRoom = 0.0005f

    /** Ordinary speech at arm's length. */
    private val speech = 0.05f

    /** A raised voice close to the microphone. */
    private val loudSpeech = 0.32f

    @Test
    fun aQuietRoomLeavesTheMeterEmpty() {
        assertEquals(0f, AudioLevelScale.display(quietRoom), 0f)
        assertEquals(0f, AudioLevelScale.display(0f), 0f)
    }

    @Test
    fun ordinarySpeechLightsMoreThanHalfTheMeter() {
        val lit = AudioLevelScale.display(speech)
        assertTrue("speech read as $lit, which is not a visible meter", lit in 0.5f..0.85f)
    }

    @Test
    fun theSameSpeechDrawnLinearlyWouldBeInvisible() {
        // The one property that stops this becoming a linear map again. Speech at 0.05 would light five
        // percent of the meter, which on a 28dp meter is under two pixels and reads as a dead microphone.
        assertTrue(
            "the scale must lift speech far above its linear amplitude",
            AudioLevelScale.display(speech) > speech * 5f,
        )
    }

    @Test
    fun aRaisedVoiceFillsTheMeterWithoutNeedingToClip() {
        assertEquals(1f, AudioLevelScale.display(loudSpeech), 0f)
        assertEquals(1f, AudioLevelScale.display(1f), 0f)
    }

    @Test
    fun theMeterRisesFasterThanItFalls() {
        val rise = AudioLevelScale.smooth(0f, 1f)
        val fall = 1f - AudioLevelScale.smooth(1f, 0f)
        assertTrue("rise $rise must outpace fall $fall", rise > fall)
    }

    @Test
    fun aBrokenReadingReadsAsSilenceRatherThanAsFull() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f).forEach { reading ->
            assertEquals("reading $reading", 0f, AudioLevelScale.display(reading), 0f)
        }
        assertEquals(0f, AudioLevelScale.smooth(Float.NaN, 0f), 0f)
    }

    @Test
    fun smoothingNeverLeavesTheDrawableRange() {
        var level = 0f
        listOf(1f, 0f, Float.NaN, 5f, -3f, 0.4f).forEach { target ->
            level = AudioLevelScale.smooth(level, target)
            assertTrue("level $level left 0..1 on target $target", level in 0f..1f)
        }
    }

    @Test
    fun aHeldLevelIsReachedRatherThanApproachedForever() {
        // Ten ticks is one second at the session owner's polling rate. A meter that has not arrived by
        // then lags the voice visibly.
        var level = 0f
        repeat(10) { level = AudioLevelScale.smooth(level, 1f) }
        assertTrue("after one second of speech the meter reached only $level", level > 0.99f)
    }
}
