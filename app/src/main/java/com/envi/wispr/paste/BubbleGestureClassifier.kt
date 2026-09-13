package com.envi.wispr.paste

import kotlin.math.hypot

/**
 * What one touch sequence on the bubble meant. Exactly one of these per input event, and the overlay's
 * `when` over it has no `else`, so a new member has to be handled where it is produced.
 */
internal sealed class BubbleGesture {
    /** Nothing to do yet, or nothing to do at all. */
    object Nothing : BubbleGesture()

    /** Finger down and up without moving past slop and before the hold timeout. Starts a dictation. */
    object Tap : BubbleGesture()

    /** The finger stayed down past the long-press timeout without moving. Starts a dictation. */
    object HoldStart : BubbleGesture()

    /** The finger lifted after a [HoldStart]. Finishes the dictation. */
    object HoldRelease : BubbleGesture()

    /** The touch was lost after a [HoldStart]. Intent is unknown, so the dictation is cancelled. */
    object HoldCancelled : BubbleGesture()

    /** The finger moved past slop before the hold timeout. Move the bubble by this much from the down point. */
    data class DragMove(val dx: Float, val dy: Float) : BubbleGesture()

    /** The finger lifted or was lost during a drag. [overHideTarget] is what the caller reported on the last move. */
    data class DragEnd(val overHideTarget: Boolean, val cancelled: Boolean) : BubbleGesture()
}

/**
 * Turns pointer events on the bubble into [BubbleGesture]s. Pure: the caller owns the timer and tells
 * this class when the hold timeout fired, so tests drive it with literal timestamps and no clock.
 *
 * The rule that matters most is the order of the two thresholds: movement past [slopPx] BEFORE the hold
 * timeout is a drag and it cancels the hold, so dragging the bubble out of the way can never start a
 * recording. Once a hold has started, movement is ignored: a slide after a hold is not a gesture here
 * (`wispr-flow-android-overlay-study.md` warns against teaching slide-to-cancel without validation).
 */
internal class BubbleGestureClassifier(
    private val slopPx: Float,
    private val holdTimeoutMs: Long,
) {
    private enum class Phase { IDLE, PRESSED, HOLDING, DRAGGING }

    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var overHideTarget = false

    val isPressed: Boolean get() = phase != Phase.IDLE
    val isDragging: Boolean get() = phase == Phase.DRAGGING
    val isHolding: Boolean get() = phase == Phase.HOLDING

    /** The primary pointer went down. The caller arms a timer for [holdTimeoutMs] and later calls [holdTimeout]. */
    fun down(x: Float, y: Float, atMs: Long): BubbleGesture {
        phase = Phase.PRESSED
        downX = x
        downY = y
        downAtMs = atMs
        overHideTarget = false
        return BubbleGesture.Nothing
    }

    /** The primary pointer moved. [overHideTarget] is the caller's geometry answer for this point. */
    fun move(x: Float, y: Float, atMs: Long, overHideTarget: Boolean): BubbleGesture {
        return when (phase) {
            Phase.IDLE, Phase.HOLDING -> BubbleGesture.Nothing
            Phase.PRESSED -> {
                val moved = hypot(x - downX, y - downY) > slopPx
                if (!moved) return BubbleGesture.Nothing
                phase = Phase.DRAGGING
                this.overHideTarget = overHideTarget
                BubbleGesture.DragMove(x - downX, y - downY)
            }
            Phase.DRAGGING -> {
                this.overHideTarget = overHideTarget
                BubbleGesture.DragMove(x - downX, y - downY)
            }
        }
    }

    /** The caller's hold timer fired. Only a still-pressed, un-moved finger becomes a hold. */
    fun holdTimeout(atMs: Long): BubbleGesture {
        if (phase != Phase.PRESSED) return BubbleGesture.Nothing
        if (atMs - downAtMs < holdTimeoutMs) return BubbleGesture.Nothing
        phase = Phase.HOLDING
        return BubbleGesture.HoldStart
    }

    /** The primary pointer lifted. */
    fun up(atMs: Long): BubbleGesture {
        val result = when (phase) {
            Phase.IDLE -> BubbleGesture.Nothing
            Phase.PRESSED -> BubbleGesture.Tap
            Phase.HOLDING -> BubbleGesture.HoldRelease
            Phase.DRAGGING -> BubbleGesture.DragEnd(overHideTarget = overHideTarget, cancelled = false)
        }
        phase = Phase.IDLE
        return result
    }

    /**
     * The touch was lost: the system cancelled it, the window was rebuilt for a configuration change,
     * or the caller decided the sequence cannot continue. Never a tap, never a release.
     */
    fun cancel(): BubbleGesture {
        val result = when (phase) {
            Phase.IDLE, Phase.PRESSED -> BubbleGesture.Nothing
            Phase.HOLDING -> BubbleGesture.HoldCancelled
            Phase.DRAGGING -> BubbleGesture.DragEnd(overHideTarget = false, cancelled = true)
        }
        phase = Phase.IDLE
        return result
    }
}
