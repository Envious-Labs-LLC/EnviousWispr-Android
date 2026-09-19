package com.envi.wispr.paste

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The EnviousWispr mark: the rainbow lips, two rows of nine bars.
 *
 * It is what makes the bubble and the recorder say whose software this is. The geometry is the
 * approved brand geometry, the same 256-unit drawing as the launcher icon
 * (`res/drawable/ic_launcher_monochrome.xml`), the onboarding lips (`ui/OnboardingLips.kt`), the
 * approved bubble mock (`docs/mockups/android-lips-bubble-v1/index.html`) and the macOS
 * `RainbowLipsIcon`. The founder's first look at the Play build (2026-09-12) caught a seven-bar
 * waveform here instead of the lips: "why did you not use our token lips".
 *
 * STATIC while the voice is being captured, and that is the difference between it and the level rail
 * beside it. Two things moving with the voice read as two meters, and the user then cannot tell which
 * one is the signal. It also costs nothing while the bubble idles over the keyboard
 * (`architecture-rules.md` RULE: no-idle-cost).
 *
 * The one motion it has is [setBusy]: while the owner is starting or working on the words, the rainbow
 * rolls along the bars, the lips' own version of Wispr Flow's spinning icon (founder 2026-09-13). The
 * geometry never moves, so it still reads as the mark, and the roll runs only between the finger lifting
 * and the words landing.
 *
 * Decorative to a screen reader: the bubble's and the recorder's own labels already say what they are.
 */
internal class BrandMarkView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** How far the rainbow has rolled along the bars, in bars; 0 is the brand drawing. */
    private var colourShift = 0

    /**
     * The nine colours on the bars: `BrandPalette.RAINBOW` unless the earbuds are the chosen
     * microphone, then `BrandPalette.RAINBOW_EARBUDS` (#171). Nine entries, one per bar; the roll
     * indexes into whichever array is set.
     */
    var palette: IntArray = BrandPalette.RAINBOW
        set(value) {
            require(value.size == BAR_COUNT) { "a lips palette has $BAR_COUNT colours, got ${value.size}" }
            if (field === value) return
            field = value
            invalidate()
        }

    /**
     * A dark edge drawn behind every bar, in pixels; 0 draws none. The look's way of keeping the
     * bright bars readable on a white page without a ground under them.
     */
    var inkEdgePx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private var roll: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Roll the rainbow while [busy], and rest on the brand colours otherwise. Idempotent. Returns
     * whether the lips are now moving: false when the system has animations off, so the caller can
     * show its still working state instead.
     */
    fun setBusy(busy: Boolean): Boolean {
        if (!busy) {
            roll?.cancel()
            roll = null
            if (colourShift != 0) {
                colourShift = 0
                invalidate()
            }
            return false
        }
        if (roll != null) return true
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        roll = ValueAnimator.ofInt(0, BAR_COUNT).apply {
            duration = ROLL_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                val shift = (animator.animatedValue as Int) % BAR_COUNT
                if (shift != colourShift) {
                    colourShift = shift
                    invalidate()
                }
            }
            start()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        setBusy(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        val usableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (usableWidth <= 0f || usableHeight <= 0f) return

        // The 256-unit drawing, scaled to fit and centred, so the lips keep their proportions in a
        // 34 dp bubble slot and a 26 dp pill slot alike.
        val unit = minOf(usableWidth, usableHeight) / DRAWING_SIZE
        val originX = paddingLeft + (usableWidth - DRAWING_SIZE * unit) / 2f
        val originY = paddingTop + (usableHeight - DRAWING_SIZE * unit) / 2f
        val radius = BAR_RADIUS * unit

        val edge = inkEdgePx
        // Every edge first, then every bar, so no bar's colour is dimmed by its neighbour's edge.
        if (edge > 0f) {
            for (index in 0 until BAR_COUNT) {
                val left = originX + (BAR_LEFT + index * BAR_STEP) * unit
                bar.set(left - edge, originY + UPPER_TOP[index] * unit - edge, left + BAR_WIDTH * unit + edge, originY + (UPPER_TOP[index] + UPPER_HEIGHT[index]) * unit + edge)
                canvas.drawRoundRect(bar, radius + edge, radius + edge, inkPaint)
                bar.set(left - edge, originY + LOWER_TOP[index] * unit - edge, left + BAR_WIDTH * unit + edge, originY + (LOWER_TOP[index] + LOWER_HEIGHT[index]) * unit + edge)
                canvas.drawRoundRect(bar, radius + edge, radius + edge, inkPaint)
            }
        }
        for (index in 0 until BAR_COUNT) {
            val left = originX + (BAR_LEFT + index * BAR_STEP) * unit
            paint.color = palette[(index + colourShift) % BAR_COUNT]
            bar.set(left, originY + UPPER_TOP[index] * unit, left + BAR_WIDTH * unit, originY + (UPPER_TOP[index] + UPPER_HEIGHT[index]) * unit)
            canvas.drawRoundRect(bar, radius, radius, paint)
            paint.color = palette[(LOWER_COLOUR[index] + colourShift) % BAR_COUNT]
            bar.set(left, originY + LOWER_TOP[index] * unit, left + BAR_WIDTH * unit, originY + (LOWER_TOP[index] + LOWER_HEIGHT[index]) * unit)
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    companion object {
        /** The brand drawing is authored on a 256 by 256 grid. */
        const val DRAWING_SIZE = 256f
        const val BAR_COUNT = 9

        /** One full roll of the rainbow across the nine bars. */
        const val ROLL_MS = 1080L

        /** The ink edge's colour: the recorder ground at 85 percent. */
        const val INK = 0xD9131019.toInt()
        const val BAR_WIDTH = 14f
        const val BAR_RADIUS = 5f
        const val BAR_LEFT = 24f
        const val BAR_STEP = 24f

        /** Upper lip: top edge and height per bar, in drawing units. */
        val UPPER_TOP = floatArrayOf(97.2f, 78.75f, 56.36f, 76.04f, 94.43f, 76.04f, 56.36f, 78.75f, 97.2f)
        val UPPER_HEIGHT = floatArrayOf(20f, 32f, 48f, 36f, 24f, 36f, 48f, 32f, 20f)

        /** Lower lip: top edge and height per bar. Coloured in the reverse order, violet on the last bar of both. */
        val LOWER_TOP = floatArrayOf(138.8f, 132.35f, 125.96f, 133.64f, 140.03f, 133.64f, 125.96f, 132.35f, 138.8f)
        val LOWER_HEIGHT = floatArrayOf(20f, 36f, 48f, 60f, 68f, 60f, 48f, 36f, 20f)
        val LOWER_COLOUR = intArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 8)
    }
}
