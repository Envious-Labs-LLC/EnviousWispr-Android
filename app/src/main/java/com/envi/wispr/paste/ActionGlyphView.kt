package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** The two recorder controls' symbols. */
internal enum class ActionGlyph { CROSS, CHECK }

/**
 * A round control drawing its own symbol with strokes, centred by geometry.
 *
 * The first version set a "×" or "✓" as TEXT in a centred text view, and the font's own metrics put
 * the cross visibly low in its circle: a glyph is centred on its line box, not on its ink. The
 * founder saw it on the phone (2026-09-12): "the x is off center". Strokes drawn from the view's own
 * centre cannot drift with a typeface.
 */
internal class ActionGlyphView(context: Context, private val glyph: ActionGlyph) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        // The symbol's ink spans this fraction of the control, either way of the centre.
        val reach = size * INK_REACH
        paint.strokeWidth = size * STROKE_FRACTION
        path.reset()
        when (glyph) {
            ActionGlyph.CROSS -> {
                path.moveTo(cx - reach, cy - reach)
                path.lineTo(cx + reach, cy + reach)
                path.moveTo(cx + reach, cy - reach)
                path.lineTo(cx - reach, cy + reach)
            }
            ActionGlyph.CHECK -> {
                // A tick's ink is not symmetric, so it is placed so its bounding box is centred.
                path.moveTo(cx - reach * 1.15f, cy + reach * 0.05f)
                path.lineTo(cx - reach * 0.3f, cy + reach * 0.9f)
                path.lineTo(cx + reach * 1.15f, cy - reach * 0.75f)
            }
        }
        canvas.drawPath(path, paint)
    }

    private companion object {
        /** Half the symbol's span, as a fraction of the control's size. */
        const val INK_REACH = 0.19f

        /** Stroke width as a fraction of the control's size: 2.4 dp on a 40 dp control. */
        const val STROKE_FRACTION = 0.06f
    }
}
