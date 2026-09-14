package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The live level rail: a row of thin bars across the full brand rainbow, showing the last couple of
 * seconds of the microphone, oldest on the left and the newest sample on the right.
 *
 * It answers one question the timer cannot: is the app hearing me. A running clock proves only that a
 * take is open, so a dead microphone and a working one look the same until an empty transcript comes
 * back.
 *
 * **It is a record, not a level.** Each bar is one poll of the microphone, and a new poll pushes the
 * picture one bar to the left. The rail must be fed EVERY poll, including one whose level equals the
 * last: silence is the one passage where consecutive samples are identical, and a rail fed only on
 * change stops scrolling exactly when the user stops talking, leaving the shape of their last words
 * frozen until they speak again (the macOS `RainbowLevelMeter` records the same trap).
 *
 * **The rainbow belongs here specifically.** Recording is the one moment the product is doing the thing
 * it exists to do, and this is the only surface a user sees while not looking at the app
 * (`design-language.md` RULE: the-recorder-is-the-face-of-the-product). The gradient is painted ACROSS
 * the whole rail rather than per bar, so a colour belongs to a position and never to a loudness: the
 * rail must not look like a warning when someone speaks up, and colours crawling sideways would read
 * as a progress bar rather than as our spectrum.
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
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrandMarkView.INK }
    private val history = LevelHistory(BAR_COUNT)

    /** A dark edge behind every bar, in pixels; 0 draws none. See `BrandMarkView.inkEdgePx`. */
    var inkEdgePx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private var levels = FloatArray(BAR_COUNT)

    /**
     * How many of the newest bars the rail draws, at most [BAR_COUNT]. The history keeps every
     * sample either way, so the bars keep their width and only the rail's reach changes: the tap
     * pill shows half the hold pill's reach (founder 2026-09-13, build 116 phone pass: "half the
     * size of the audio bar ... the length is fine for the push to talk").
     */
    var barCount: Int = BAR_COUNT
        set(value) {
            val clamped = value.coerceIn(1, BAR_COUNT)
            if (field == clamped) return
            field = clamped
            levels = history.bars(clamped)
            invalidate()
        }

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

    /**
     * Record one poll of the microphone. [level] is the already-scaled 0..1 level from
     * `AudioLevelScale`, never a raw amplitude. Called once per poll whether or not the level changed.
     */
    fun pushSample(level: Float) {
        history.push(level)
        levels = history.bars(barCount)
        invalidate()
    }

    /** A new take starts with an empty record, not the tail of the last one. */
    fun reset() {
        history.clear()
        levels = FloatArray(barCount)
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
        val count = barCount
        val barWidth = usableWidth / (count + (count - 1) * GAP_RATIO)
        val step = barWidth * (1f + GAP_RATIO)
        val radius = barWidth / 2f
        val centreY = paddingTop + usableHeight / 2f

        val edge = inkEdgePx
        if (edge > 0f) {
            for (index in 0 until count) {
                val barHeight = usableHeight * fill(levels[index])
                val left = paddingLeft + index * step
                bar.set(left - edge, centreY - barHeight / 2f - edge, left + barWidth + edge, centreY + barHeight / 2f + edge)
                canvas.drawRoundRect(bar, radius + edge, radius + edge, inkPaint)
            }
        }
        for (index in 0 until count) {
            val level = levels[index]
            // Symmetric about the centre line rather than growing off a floor, so the rail's visual
            // weight does not shift down the pill as the level drops. A silent sample is a short bar,
            // never nothing: a rail that collapses between words reads as "it stopped hearing me".
            val barHeight = usableHeight * fill(level)
            val left = paddingLeft + index * step
            bar.set(left, centreY - barHeight / 2f, left + barWidth, centreY + barHeight / 2f)
            // A silent sample is drawn in the resting grey, so silence reads as silence rather than as
            // a rainbow sitting at its floor.
            canvas.drawRoundRect(bar, radius, radius, if (level > 0f) paint else restingPaint)
        }
    }

    companion object {
        /** About 2.2 seconds at the owner's 100 ms poll: long enough to read as a shape, short enough to be what you just said. */
        const val BAR_COUNT = 22

        /** A gap is this fraction of one bar's width. */
        const val GAP_RATIO = 0.55f

        /** A silent sample's share of the rail's height. */
        const val SILENCE_FRACTION = 0.14f

        /** The additional share available at full level. */
        const val PEAK_FRACTION = 0.86f

        /** The share of the rail's height a sample at [level] occupies. Pure, so it can be asserted without a canvas. */
        fun fill(level: Float): Float = SILENCE_FRACTION + PEAK_FRACTION * level.coerceIn(0f, 1f)
    }
}
