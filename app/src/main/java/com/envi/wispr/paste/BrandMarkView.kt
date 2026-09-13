package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The EnviousWispr mark: a small fixed waveform in the brand rainbow.
 *
 * It is what makes the recorder say whose software this is. Without it the pill is a control panel that
 * could belong to anything, which is the state `design-language.md`
 * RULE: the-recorder-is-the-face-of-the-product describes.
 *
 * Deliberately STATIC, and that is the difference between it and the level rail beside it. Two things
 * moving with the voice read as two meters, and the user then cannot tell which one is the signal.
 *
 * Decorative to a screen reader: the recorder's own label already says what this window is.
 */
internal class BrandMarkView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** See `RecordingLevelMeterView`: built in layout, keyed on the endpoints, never while drawing. */
    private var gradientLeft = Float.NaN
    private var gradientRight = Float.NaN

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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

        val barWidth = usableWidth / (HEIGHTS.size + (HEIGHTS.size - 1) * GAP_RATIO)
        val step = barWidth * (1f + GAP_RATIO)
        val radius = barWidth / 2f
        val centreY = paddingTop + usableHeight / 2f

        for (index in HEIGHTS.indices) {
            val barHeight = usableHeight * HEIGHTS[index]
            val left = paddingLeft + index * step
            bar.set(left, centreY - barHeight / 2f, left + barWidth, centreY + barHeight / 2f)
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        /**
         * The mark's fixed silhouette, as a fraction of the height.
         *
         * Uneven on purpose: an even shape reads as a chart and a symmetrical one reads as a meter at
         * rest. This reads as a voice that has been captured rather than one being measured now.
         */
        val HEIGHTS = floatArrayOf(0.35f, 0.62f, 1f, 0.48f, 0.85f, 0.3f, 0.55f)

        /** A gap is this fraction of one bar's width. */
        const val GAP_RATIO = 0.5f
    }
}
