package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME. When these fail, the user sees a drag start a recording, a hold that never stops,
 * or a quick tap that does nothing. Every threshold is a literal here; on the phone they come from
 * `ViewConfiguration`.
 */
class BubbleGestureClassifierTest {

    private val slop = 10f
    private val hold = 500L

    private fun classifier() = BubbleGestureClassifier(slopPx = slop, holdTimeoutMs = hold)

    @Test
    fun anOrdinaryShortTapIsATapAndNotAHoldReleasedEarly() {
        val c = classifier()
        assertEquals(BubbleGesture.Nothing, c.down(100f, 100f, 0L))
        assertEquals(BubbleGesture.Nothing, c.move(103f, 101f, 40L, overHideTarget = false))
        assertEquals(BubbleGesture.Tap, c.up(120L))
        assertFalse(c.isPressed)
    }

    @Test
    fun movingPastSlopBeforeTheTimeoutIsADragAndNeverRecords() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        val first = c.move(100f, 130f, 200L, overHideTarget = false)
        assertEquals(BubbleGesture.DragMove(0f, 30f), first)
        // The timer fires anyway; a drag must swallow it.
        assertEquals(BubbleGesture.Nothing, c.holdTimeout(600L))
        assertTrue(c.isDragging)
        assertEquals(BubbleGesture.DragEnd(overHideTarget = false, cancelled = false), c.up(700L))
    }

    @Test
    fun holdingPastTheTimeoutStartsAndReleasingFinishes() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Nothing, c.holdTimeout(499L))
        assertEquals(BubbleGesture.HoldStart, c.holdTimeout(500L))
        assertTrue(c.isHolding)
        // Movement after a hold is not a drag and not a cancel.
        assertEquals(BubbleGesture.Nothing, c.move(100f, 200f, 900L, overHideTarget = true))
        assertEquals(BubbleGesture.HoldRelease, c.up(1500L))
    }

    @Test
    fun aReleaseTwoHundredMillisecondsAfterHoldStartIsStillAHoldRelease() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.HoldStart, c.holdTimeout(500L))
        assertEquals(BubbleGesture.HoldRelease, c.up(700L))
    }

    @Test
    fun aLostTouchDuringAHoldCancelsRatherThanFinishes() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        c.holdTimeout(500L)
        assertEquals(BubbleGesture.HoldCancelled, c.cancel())
        assertFalse(c.isPressed)
    }

    @Test
    fun aLostTouchBeforeTheTimeoutIsNothing() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Nothing, c.cancel())
        // And the timer firing afterwards must not start anything.
        assertEquals(BubbleGesture.Nothing, c.holdTimeout(600L))
    }

    @Test
    fun aLostTouchDuringADragEndsItCancelledSoTheDockIsRestored() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        c.move(150f, 100f, 100L, overHideTarget = false)
        assertEquals(BubbleGesture.DragEnd(overHideTarget = false, cancelled = true), c.cancel())
    }

    @Test
    fun aDragEndReportsTheLastHideTargetAnswer() {
        val c = classifier()
        c.down(100f, 100f, 0L)
        c.move(100f, 400f, 100L, overHideTarget = false)
        c.move(100f, 900f, 200L, overHideTarget = true)
        assertEquals(BubbleGesture.DragEnd(overHideTarget = true, cancelled = false), c.up(300L))
    }

    @Test
    fun theTimerFiringWithNoFingerDownIsNothing() {
        val c = classifier()
        assertEquals(BubbleGesture.Nothing, c.holdTimeout(1000L))
        assertEquals(BubbleGesture.Nothing, c.up(1001L))
    }
}
