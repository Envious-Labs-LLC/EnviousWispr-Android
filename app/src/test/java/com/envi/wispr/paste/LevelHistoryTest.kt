package com.envi.wispr.paste

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. When these fail, the recorder's rail stops being a record of the last two seconds
 * of the user's voice and goes back to a block of bars that only rise and fall together, or one that
 * slides sideways for the first second of every take.
 */
class LevelHistoryTest {

    @Test
    fun eachPollIsOneBarAndTheNewestSitsAtTheRightEdge() {
        val history = LevelHistory(4)
        history.push(0.2f)
        history.push(0.5f)
        // Right-aligned: missing history is silence on the LEFT, the newest sample stays on the right.
        assertArrayEquals(floatArrayOf(0f, 0f, 0.2f, 0.5f), history.bars(4), 0f)
        history.push(0.9f)
        history.push(0.1f)
        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.9f, 0.1f), history.bars(4), 0f)
    }

    @Test
    fun aFullRecordScrollsAndDropsTheOldestSample() {
        val history = LevelHistory(3)
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).forEach(history::push)
        assertEquals(3, history.size)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f, 0.5f), history.bars(3), 0f)
    }

    @Test
    fun identicalSamplesStillScroll() {
        // Silence is the passage where every sample equals the last. The shape of the last words must
        // move out to the left, not freeze.
        val history = LevelHistory(3)
        history.push(0.8f)
        history.push(0f)
        history.push(0f)
        history.push(0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), history.bars(3), 0f)
    }

    @Test
    fun aBadSampleIsClampedOnTheWayIn() {
        val history = LevelHistory(2)
        history.push(Float.NaN)
        history.push(7f)
        assertArrayEquals(floatArrayOf(0f, 1f), history.bars(2), 0f)
        history.push(-3f)
        assertArrayEquals(floatArrayOf(1f, 0f), history.bars(2), 0f)
    }

    @Test
    fun barsForAnotherCountKeepTheNewestSamples() {
        val history = LevelHistory(5)
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).forEach(history::push)
        assertArrayEquals(floatArrayOf(0.4f, 0.5f), history.bars(2), 0f)
        assertArrayEquals(floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f), history.bars(6), 0f)
        assertEquals(0, history.bars(0).size)
        assertEquals(0, LevelHistory(0).also { it.push(1f) }.size)
    }

    @Test
    fun aSilentSampleIsStillAVisibleBar() {
        // A rail that collapses to nothing between words reads as "it stopped hearing me".
        assertEquals(0.14f, RecordingLevelMeterView.fill(0f), 0.0001f)
        assertEquals(1f, RecordingLevelMeterView.fill(1f), 0.0001f)
        assertEquals(0.57f, RecordingLevelMeterView.fill(0.5f), 0.0001f)
        assertEquals(1f, RecordingLevelMeterView.fill(4f), 0.0001f)
    }
}
