package com.envi.wispr.paste

import com.envi.wispr.audio.SpectrumAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product outcome: when these fail, the centre bar shows the wrong pitch, the picture is lopsided, or
 * the bars snap and never settle. The pure halves of the view, asserted without a canvas.
 */
class RecordingLevelMeterViewTest {

    @Test
    fun theLowestBandSitsInTheMiddleAndTheHighestAtBothEdges() {
        for (count in listOf(RecordingLevelMeterView.BAR_COUNT, RecordingAccessibilityOverlay.FULL_PILL_BARS, 16)) {
            val bands = (0 until count).map { RecordingLevelMeterView.barBand(it, count) }
            assertEquals("edge bar, count $count", SpectrumAnalyzer.BAND_COUNT - 1, bands.first())
            assertEquals("other edge, count $count", SpectrumAnalyzer.BAND_COUNT - 1, bands.last())
            val middle = (count - 1) / 2
            assertEquals("middle bar, count $count", 0, bands[middle])
            assertEquals("mirrored, count $count", bands, bands.reversed())
            for (i in 1..middle) assertTrue("bands fall toward the middle, count $count", bands[i] <= bands[i - 1])
        }
    }

    @Test
    fun theHoldPillShowsEveryBandAndTheTapPillShowsSixOfThem() {
        val hold = (0 until RecordingLevelMeterView.BAR_COUNT).map { RecordingLevelMeterView.barBand(it, RecordingLevelMeterView.BAR_COUNT) }.toSet()
        assertEquals((0 until SpectrumAnalyzer.BAND_COUNT).toSet(), hold)
        val tap = (0 until RecordingAccessibilityOverlay.FULL_PILL_BARS).map { RecordingLevelMeterView.barBand(it, RecordingAccessibilityOverlay.FULL_PILL_BARS) }.toSet()
        assertEquals("six distinct bands on the tap pill", 6, tap.size)
        assertTrue("from the lowest", 0 in tap)
        assertTrue("to the highest", SpectrumAnalyzer.BAND_COUNT - 1 in tap)
    }

    @Test
    fun aBarRisesFasterThanItFalls() {
        val up = RecordingLevelMeterView.ease(0f, 1f, 16f)
        val down = 1f - RecordingLevelMeterView.ease(1f, 0f, 16f)
        assertTrue("one frame up ($up) covers more than one frame down ($down)", up > down)
        assertTrue("and neither overshoots", up in 0f..1f && down in 0f..1f)
    }

    @Test
    fun easingIsFrameRateIndependent() {
        val one = RecordingLevelMeterView.ease(0f, 1f, 16f)
        val two = RecordingLevelMeterView.ease(RecordingLevelMeterView.ease(0f, 1f, 8f), 1f, 8f)
        assertEquals("two 8 ms steps land where one 16 ms step does", one, two, 0.0001f)
    }

    @Test
    fun zerosEaseEveryBarToTheFloor() {
        var shown = 0.9f
        repeat(60) { shown = RecordingLevelMeterView.ease(shown, 0f, 16f) }
        assertTrue("a second of silence rests the bar (was $shown)", shown < RecordingLevelMeterView.RESTING_EPSILON)
    }

    @Test
    fun aRestingBarStillHasHeight() {
        assertEquals(RecordingLevelMeterView.SILENCE_FRACTION, RecordingLevelMeterView.fill(0f), 0f)
        assertEquals(1f, RecordingLevelMeterView.fill(1f), 0.0001f)
        assertEquals(RecordingLevelMeterView.fill(1f), RecordingLevelMeterView.fill(7f), 0f)
    }
}
