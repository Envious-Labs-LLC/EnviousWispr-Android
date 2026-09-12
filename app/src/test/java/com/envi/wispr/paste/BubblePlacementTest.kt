package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PRODUCT OUTCOME. When these fail, the user sees the bubble behind the keyboard, off the edge of the
 * screen, or forgetting where it was put once the keyboard closes.
 */
class BubblePlacementTest {

    // A 1000 x 2000 screen with a 100 px status bar and a 100 px navigation bar already removed.
    private val usable = Box(left = 0, top = 100, right = 1000, bottom = 1900)
    private val size = 56
    private val margin = 12

    @Test
    fun theDefaultSitsAtTheRightEdgeAboveTheKeyboard() {
        val bounds = BubbleBounds(usable, keyboardTop = 1200)
        val box = BubblePlacement.bubbleBox(BubblePosition.DEFAULT, bounds, size, margin)!!
        assertEquals(1000 - margin - size, box.left)
        assertEquals(1200 - margin - size, box.top)
    }

    @Test
    fun theKeyboardClampsTheBubbleUpwardWithoutChangingTheStoredFraction() {
        val position = BubblePosition(BubbleSide.LEFT, 0.9f)
        val open = BubblePlacement.bubbleBox(position, BubbleBounds(usable, keyboardTop = 1200), size, margin)!!
        val closed = BubblePlacement.bubbleBox(position, BubbleBounds(usable, keyboardTop = null), size, margin)!!
        assertEquals(1200 - margin - size, open.top)
        // Closed: 0.9 of the full range, well below where the keyboard had clamped it.
        val minTop = 100 + margin
        val maxTop = 1900 - margin - size
        assertEquals(minTop + Math.round((maxTop - minTop) * 0.9f), closed.top)
        assertEquals(margin, closed.left)
    }

    @Test
    fun aRangeShorterThanTheBubbleHidesItInsteadOfPlacingItOffScreen() {
        val tiny = Box(0, 100, 1000, 160)
        assertNull(BubblePlacement.bubbleBox(BubblePosition.DEFAULT, BubbleBounds(tiny, null), size, margin))
        val keyboardCoversAll = BubbleBounds(usable, keyboardTop = 130)
        assertNull(BubblePlacement.bubbleBox(BubblePosition.DEFAULT, keyboardCoversAll, size, margin))
    }

    @Test
    fun snapPicksTheNearerSideAndKeepsTheHeightAsAFraction() {
        val bounds = BubbleBounds(usable, null)
        val left = BubblePlacement.snap(dragLeft = 300, dragTop = 100 + margin, bounds, size, margin)
        assertEquals(BubbleSide.LEFT, left.side)
        assertEquals(0f, left.fraction)
        val right = BubblePlacement.snap(dragLeft = 600, dragTop = 1900 - margin - size, bounds, size, margin)
        assertEquals(BubbleSide.RIGHT, right.side)
        assertEquals(1f, right.fraction)
    }

    @Test
    fun snapDuringAnOpenKeyboardStillMeasuresAgainstTheFullRange() {
        // Dropped just above the keyboard: that is about 0.6 of the keyboard-free range, so when the
        // keyboard closes the bubble stays where the finger left it rather than jumping to the bottom.
        val bounds = BubbleBounds(usable, keyboardTop = 1200)
        val snapped = BubblePlacement.snap(dragLeft = 900, dragTop = 1200 - margin - size, bounds, size, margin)
        val minTop = 100 + margin
        val maxTop = 1900 - margin - size
        val expected = (1200 - margin - size - minTop).toFloat() / (maxTop - minTop)
        assertEquals(expected, snapped.fraction, 0.0001f)
    }

    @Test
    fun thePillAnchorsToTheDockedSideAndGrowsInward() {
        val bounds = BubbleBounds(usable, keyboardTop = 1200)
        val bubble = BubblePlacement.bubbleBox(BubblePosition(BubbleSide.RIGHT, 0.5f), bounds, size, margin)!!
        val pill = BubblePlacement.pillBox(BubblePosition(BubbleSide.RIGHT, 0.5f), bubble, bounds, widthPx = 976, heightPx = 60, marginPx = margin)
        assertEquals(bubble.right, pill.right)
        assertEquals(976, pill.width)
        assertEquals(bubble.centerY, pill.centerY)

        val leftBubble = BubblePlacement.bubbleBox(BubblePosition(BubbleSide.LEFT, 0.5f), bounds, size, margin)!!
        val leftPill = BubblePlacement.pillBox(BubblePosition(BubbleSide.LEFT, 0.5f), leftBubble, bounds, 976, 60, margin)
        assertEquals(leftBubble.left, leftPill.left)
    }

    @Test
    fun thePillIsPulledInsideTheScreenWhenTheBubbleSitsAtTheVeryTop() {
        val bounds = BubbleBounds(usable, null)
        val bubble = BubblePlacement.bubbleBox(BubblePosition(BubbleSide.RIGHT, 0f), bounds, size, margin)!!
        val pill = BubblePlacement.pillBox(BubblePosition(BubbleSide.RIGHT, 0f), bubble, bounds, 976, 120, margin)
        assertEquals(100 + margin, pill.top)
    }

    @Test
    fun theHideTargetIsTheStripAboveTheKeyboardOrTheBottomEdge() {
        val withKeyboard = BubbleBounds(usable, keyboardTop = 1200)
        assertEquals(true, BubblePlacement.overHideTarget(dragTop = 1200 - 72 - size / 2 + 1, withKeyboard, size, stripPx = 72))
        assertEquals(false, BubblePlacement.overHideTarget(dragTop = 900, withKeyboard, size, stripPx = 72))
        val noKeyboard = BubbleBounds(usable, null)
        assertEquals(true, BubblePlacement.overHideTarget(dragTop = 1900 - 60, noKeyboard, size, stripPx = 72))
    }

    @Test
    fun aStoredPositionIsTrustedOnlyWhenItIsWellFormedAndInRange() {
        assertEquals(BubblePosition(BubbleSide.LEFT, 0.25f), BubblePosition.parse("LEFT", 0.25f))
        assertNull(BubblePosition.parse("MIDDLE", 0.25f))
        assertNull(BubblePosition.parse(null, 0.25f))
        assertNull(BubblePosition.parse("RIGHT", null))
        assertNull(BubblePosition.parse("RIGHT", 1.5f))
        assertNull(BubblePosition.parse("RIGHT", -0.1f))
        assertNull(BubblePosition.parse("RIGHT", Float.NaN))
    }
}
