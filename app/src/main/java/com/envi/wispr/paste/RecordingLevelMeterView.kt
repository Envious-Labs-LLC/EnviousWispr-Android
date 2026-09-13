package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Five bars inside the floating recorder that rise and fall with the microphone.
 *
 * It answers one question the timer cannot: is the app hearing me. A running clock proves only that a
 * take is open, so a dead microphone and a working one look the same until the transcript comes back
 * empty.
 *
 * Decorative to a screen reader. Everything it conveys is already announced by the timer and by the
 * recorder's own label, so it is hidden rather than read out several times a second.
 */
internal class RecordingLevelMeterView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_COLOR }
    private val bar = RectF()
    private var level = 0f

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

    override fun onDraw(canvas: Canvas) {
        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableWidth <= 0f || usableHeight <= 0f) return

        // The bars and the gaps between them share the width. Solving for one bar keeps the meter the
        // same shape at any size the pill gives it, so nothing here depends on a measured density.
        val barWidth = usableWidth / (BAR_WEIGHTS.size + (BAR_WEIGHTS.size - 1) * GAP_RATIO)
        val step = barWidth * (1f + GAP_RATIO)
        val radius = barWidth / 2f
        // A resting bar is a dot rather than nothing: an empty meter and a hidden meter must not look
        // alike, or a silent room reads as a broken recorder.
        val restingHeight = barWidth
        val centreY = paddingTop + usableHeight / 2f

        for (index in BAR_WEIGHTS.indices) {
            val reach = (level * BAR_WEIGHTS[index]).coerceIn(0f, 1f)
            val barHeight = restingHeight + (usableHeight - restingHeight) * reach
            val left = paddingLeft + index * step
            bar.set(left, centreY - barHeight / 2f, left + barWidth, centreY + barHeight / 2f)
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        /**
         * The brand lavender, which is the accent this app uses for content on a dark surface. The
         * recorder pill is dark, so the light-theme purple would not carry against it.
         */
        const val BAR_COLOR = 0xFFA78BFA.toInt()

        /**
         * How tall each bar goes at a given level, tallest in the middle.
         *
         * Bars that all move together read as one block. The taper is what makes the meter look like a
         * voice rather than a progress bar.
         */
        val BAR_WEIGHTS = floatArrayOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)

        /** A gap is this fraction of one bar's width. */
        const val GAP_RATIO = 0.7f
    }
}
