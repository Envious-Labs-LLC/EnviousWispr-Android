package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * A TextView whose glyphs carry a dark edge: each draw strokes the text in ink first, then fills it
 * in the text colour on top. The recorder's clock uses it so the light digits read on a white page
 * when the chosen look puts no ground under them (Codex's "clock ink edge", `docs/mockups/
 * android-bubble-v2/README.md`). A shadow layer was tried first and read as a faint smudge.
 *
 * [inkEdgePx] is the edge's width on each side; 0 draws plain text.
 */
internal class InkEdgedTextView(context: Context) : TextView(context) {

    var inkEdgePx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** True only inside the stroke pass, so the colour change it needs cannot recurse into a relayout. */
    private var stroking = false

    override fun onDraw(canvas: Canvas) {
        val edge = inkEdgePx
        if (edge > 0f) {
            val fill = currentTextColor
            stroking = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = edge * 2f
            paint.strokeJoin = Paint.Join.ROUND
            setTextColor(BrandMarkView.INK)
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            setTextColor(fill)
            stroking = false
        }
        super.onDraw(canvas)
    }

    override fun invalidate() {
        // setTextColor invalidates; inside the stroke pass that would queue a redraw per frame forever.
        if (!stroking) super.invalidate()
    }
}
