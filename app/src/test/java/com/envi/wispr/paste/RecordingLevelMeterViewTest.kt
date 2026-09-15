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
            val ranges = (0 until count).map { RecordingLevelMeterView.barBands(it, count) }
            assertTrue("edge bar holds the top band, count $count", SpectrumAnalyzer.BAND_COUNT - 1 in ranges.first())
            assertTrue("other edge too, count $count", SpectrumAnalyzer.BAND_COUNT - 1 in ranges.last())
            val middle = (count - 1) / 2
            assertTrue("middle bar holds band 0, count $count", 0 in ranges[middle])
            assertEquals("mirrored, count $count", ranges, ranges.reversed())
            for (i in 1..middle) {
                assertTrue("bands fall toward the middle, count $count", ranges[i].last <= ranges[i - 1].first || ranges[i].isEmpty())
            }
        }
    }

    @Test
    fun everyBandLandsOnSomeBarWhateverTheCount() {
        // Five of the eleven bands had no bar on the tap pill, so a steady 1 kHz tone drew nothing
        // (emulator, 2026-09-15). A bar shows the loudest of its bands, so the union must be everything.
        for (count in listOf(RecordingLevelMeterView.BAR_COUNT, RecordingAccessibilityOverlay.FULL_PILL_BARS, 16, 3)) {
            val covered = (0 until count).flatMap { RecordingLevelMeterView.barBands(it, count).toList() }.toSet()
            assertEquals("count $count", (0 until SpectrumAnalyzer.BAND_COUNT).toSet(), covered)
        }
        val hold = (0 until RecordingLevelMeterView.BAR_COUNT).map { RecordingLevelMeterView.barBands(it, RecordingLevelMeterView.BAR_COUNT) }
        assertTrue("the hold pill gives every band its own bar", hold.all { it.count() == 1 })
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
