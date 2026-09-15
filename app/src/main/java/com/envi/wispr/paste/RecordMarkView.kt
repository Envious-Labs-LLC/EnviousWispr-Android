package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The recording mark at the thumb end of the hold pill: a ring with a dot, the shape every recorder
 * uses for "recording".
 *
 * The hold pill is anchored to the bubble, so its last 56 dp sit under the thumb that is holding it.
 * Build 130 ran the rail across that whole width and the thumb covered half of it (founder
 * 2026-09-15: "my thumb is covering half of the lighting up process"; Wispr Flow puts a recording
 * icon under the thumb and the voice bars to the left of it). So this mark takes the thumb's spot and
 * the rail keeps everything to its left, where it can be seen.
 *
 * Decorative to a screen reader: the pill itself already says "Recording. Let go to finish."
 */
internal class RecordMarkView(context: Context) : View(context) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BrandPalette.TEXT
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BrandPalette.TEXT
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        ring.strokeWidth = RING_STROKE_DP * density
        canvas.drawCircle(cx, cy, RING_RADIUS_DP * density, ring)
        canvas.drawCircle(cx, cy, DOT_RADIUS_DP * density, dot)
    }

    companion object {
        const val RING_RADIUS_DP = 8f
        const val RING_STROKE_DP = 2f
        const val DOT_RADIUS_DP = 4f
    }
}
