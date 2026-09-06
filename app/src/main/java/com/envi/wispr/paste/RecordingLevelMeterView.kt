package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The live level rail: a row of thin bars across the full brand rainbow, rising with the microphone.
 *
 * It answers one question the timer cannot: is the app hearing me. A running clock proves only that a
 * take is open, so a dead microphone and a working one look the same until an empty transcript comes
 * back.
 *
 * **The rainbow belongs here specifically.** Recording is the one moment the product is doing the thing
 * it exists to do, and this is the only surface a user sees while not looking at the app
 * (`design-language.md` RULE: the-recorder-is-the-face-of-the-product). The gradient is painted ACROSS
 * the whole rail rather than per bar, so the colour of a bar depends on where it sits and not on how
 * loud it is: the rail must not look like a warning when someone speaks up.
 *
 * Decorative to a screen reader. Everything it conveys is already announced by the timer and by the
 * recorder's own label, so it is hidden rather than read out several times a second.
 */
internal class RecordingLevelMeterView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val restingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrandPalette.METER_RESTING
    }
    private val bar = RectF()
    private var level = 0f

    /**
     * The rainbow, rebuilt in LAYOUT and never while drawing.
     *
     * Keyed on the two horizontal endpoints rather than on the width, because padding moves them
     * without the width changing and the gradient then spans the wrong span while looking correct.
     */
    private var gradientLeft = Float.NaN
    private var gradientRight = Float.NaN

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [value] is the already-scaled 0..1 level from `AudioLevelScale`, never a raw amplitude. */
    fun setLevel(value: Float) {
        val safe = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (safe == level) return
        level = safe
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val start = paddingLeft.toFloat()
        val end = width.toFloat() - paddingRight.toFloat()
        if (end <= start) {
            paint.shader = null
        } else if (paint.shader == null || gradientLeft != start || gradientRight != end) {
            paint.shader = LinearGradient(
                start,
                0f,
                end,
                0f,
                BrandPalette.RAINBOW,
                null,
                Shader.TileMode.CLAMP,
            )
        }
        gradientLeft = start
        gradientRight = end
    }

    override fun onDraw(canvas: Canvas) {
        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableWidth <= 0f || usableHeight <= 0f) return

        // The bars and the gaps between them share the width, so the rail keeps its shape at whatever
        // size the pill gives it and nothing here depends on a measured density.
        val barWidth = usableWidth / (BAR_COUNT + (BAR_COUNT - 1) * GAP_RATIO)
        val step = barWidth * (1f + GAP_RATIO)
        val radius = barWidth / 2f
        // A resting bar is a dot rather than nothing: an empty rail and a hidden rail must not look
        // alike, or a silent room reads as a broken recorder.
        val restingHeight = barWidth.coerceAtMost(usableHeight)
        val centreY = paddingTop + usableHeight / 2f

        for (index in 0 until BAR_COUNT) {
            val reach = (level * weightAt(index)).coerceIn(0f, 1f)
            val barHeight = restingHeight + (usableHeight - restingHeight) * reach
            val left = paddingLeft + index * step
            bar.set(left, centreY - barHeight / 2f, left + barWidth, centreY + barHeight / 2f)
            // A bar at rest is drawn in the resting grey, so silence reads as silence rather than as a
            // rainbow sitting at its floor.
            canvas.drawRoundRect(bar, radius, radius, if (reach > 0f) paint else restingPaint)
        }
    }

    /**
     * How tall the bar at [index] goes at a given level.
     *
     * A rail whose bars all move together reads as one block sliding up and down. The shape here is a
     * shallow arch, tallest in the middle, which is what makes it read as a voice. It is a fixed
     * function of position, so the rail does not shimmer at a steady level.
     */
    private fun weightAt(index: Int): Float {
        val middle = (BAR_COUNT - 1) / 2f
        val distance = kotlin.math.abs(index - middle) / middle
        return MIN_WEIGHT + (1f - MIN_WEIGHT) * (1f - distance * distance)
    }

    private companion object {
        /** Enough bars to read as a rail rather than as a handful of blocks. */
        const val BAR_COUNT = 22

        /** A gap is this fraction of one bar's width. */
        const val GAP_RATIO = 0.55f

        /** How much of the full height the outermost bars reach. */
        const val MIN_WEIGHT = 0.35f
    }
}
