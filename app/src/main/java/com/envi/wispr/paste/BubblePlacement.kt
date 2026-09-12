package com.envi.wispr.paste

import kotlin.math.roundToInt

/** Which screen edge the bubble is docked to. */
internal enum class BubbleSide { LEFT, RIGHT }

/**
 * Where the user put the bubble: a side, and how far down the usable range, 0 at the top and 1 at the
 * bottom. A fraction rather than a pixel so the same preference survives rotation, a keyboard, and a
 * different screen. Owner of the rules that turn this into a rectangle: [BubblePlacement].
 */
internal data class BubblePosition(val side: BubbleSide, val fraction: Float) {
    companion object {
        /** Right edge, at the bottom of the range: just above the keyboard, clear of the composer. */
        val DEFAULT = BubblePosition(BubbleSide.RIGHT, 1f)

        /**
         * Parse a stored position, or null when it cannot be trusted. Null is the caller's cue to use
         * [DEFAULT]; a malformed or out-of-range value must never place the bubble off screen.
         */
        fun parse(side: String?, fraction: Float?): BubblePosition? {
            val parsedSide = when (side) {
                BubbleSide.LEFT.name -> BubbleSide.LEFT
                BubbleSide.RIGHT.name -> BubbleSide.RIGHT
                else -> return null
            }
            if (fraction == null || !fraction.isFinite() || fraction < 0f || fraction > 1f) return null
            return BubblePosition(parsedSide, fraction)
        }
    }
}

/** An axis-aligned box in window pixels. Our own type so the pure rules need no Android class. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/**
 * What the bubble may occupy: the screen with the status bar, navigation bar, cutout and any
 * multi-window insets already removed, plus the top of a docked keyboard when one is showing.
 *
 * [keyboardTop] is absolute, in the same coordinates as [usable], or null when no keyboard is docked at
 * the bottom edge. A floating or split keyboard is reported as null by the caller: its rectangle does
 * not touch the bottom edge, so clamping above it would push the bubble into the middle of the screen.
 */
internal data class BubbleBounds(val usable: Box, val keyboardTop: Int?)

/**
 * The placement rules, pure and testable.
 *
 * A keyboard shifts the bubble upward WITHOUT changing the stored fraction: the fraction is measured
 * against the range that exists when the keyboard is closed, and the keyboard only clamps the result.
 * Otherwise every keyboard would silently rewrite where the user put the bubble.
 */
internal object BubblePlacement {

    /**
     * The bubble's rectangle, or null when the usable range is shorter than the bubble, in which case
     * the bubble is not shown for this layout rather than drawn somewhere it cannot be reached.
     */
    fun bubbleBox(position: BubblePosition, bounds: BubbleBounds, sizePx: Int, marginPx: Int): Box? {
        val usable = bounds.usable
        val minTop = usable.top + marginPx
        val fullRangeBottom = usable.bottom - marginPx - sizePx
        if (fullRangeBottom < minTop) return null
        val preferredTop = minTop + ((fullRangeBottom - minTop) * position.fraction.coerceIn(0f, 1f)).roundToInt()
        val keyboardLimit = bounds.keyboardTop?.let { it - marginPx - sizePx } ?: fullRangeBottom
        if (keyboardLimit < minTop) return null
        val top = preferredTop.coerceIn(minTop, keyboardLimit)
        val left = when (position.side) {
            BubbleSide.LEFT -> usable.left + marginPx
            BubbleSide.RIGHT -> usable.right - marginPx - sizePx
        }
        return Box(left, top, left + sizePx, top + sizePx)
    }

    /**
     * The recorder pill's rectangle: anchored to the docked side at the bubble's centre line, growing
     * inward, and pulled back inside the usable rectangle when the bubble sits near the top or bottom.
     */
    fun pillBox(position: BubblePosition, bubble: Box, bounds: BubbleBounds, widthPx: Int, heightPx: Int, marginPx: Int): Box {
        val usable = bounds.usable
        val maxWidth = (usable.width - 2 * marginPx).coerceAtLeast(1)
        val width = widthPx.coerceAtMost(maxWidth)
        val left = when (position.side) {
            BubbleSide.LEFT -> bubble.left
            BubbleSide.RIGHT -> bubble.right - width
        }
        val minTop = usable.top + marginPx
        val maxTop = (usable.bottom - marginPx - heightPx).coerceAtLeast(minTop)
        val top = (bubble.centerY - heightPx / 2).coerceIn(minTop, maxTop)
        return Box(left, top, left + width, top + heightPx)
    }

    /**
     * Where a drag that ended with the bubble's top-left at ([dragLeft], [dragTop]) docks: the nearer
     * side, and the fraction of the keyboard-free range the bubble's top landed on.
     */
    fun snap(dragLeft: Int, dragTop: Int, bounds: BubbleBounds, sizePx: Int, marginPx: Int): BubblePosition {
        val usable = bounds.usable
        val centreX = dragLeft + sizePx / 2
        val side = if (centreX < usable.centerX) BubbleSide.LEFT else BubbleSide.RIGHT
        val minTop = usable.top + marginPx
        val fullRangeBottom = usable.bottom - marginPx - sizePx
        val range = fullRangeBottom - minTop
        val fraction = if (range <= 0) 1f else ((dragTop - minTop).toFloat() / range).coerceIn(0f, 1f)
        return BubblePosition(side, fraction)
    }

    /**
     * Whether a drag is over the hide target: a strip along the bottom of the usable rectangle, above
     * the keyboard when one is docked. [stripPx] is the strip's height.
     */
    fun overHideTarget(dragTop: Int, bounds: BubbleBounds, sizePx: Int, stripPx: Int): Boolean {
        val floor = bounds.keyboardTop ?: bounds.usable.bottom
        val centreY = dragTop + sizePx / 2
        return centreY >= floor - stripPx
    }
}
