package com.envi.wispr.paste

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.envi.wispr.audio.SpectrumAnalyzer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The live voice rail: a row of thin bars across the full brand rainbow, each one a pitch band of the
 * sound RIGHT NOW. The lowest band sits in the middle and the highest at the two edges, so a voice
 * swells from the centre and an "s" flicks at the ends.
 *
 * It answers one question the timer cannot: is the app hearing me. A running clock proves only that a
 * take is open, so a dead microphone and a working one look the same until an empty transcript comes
 * back.
 *
 * **It is a picture, not a record.** The rail this replaced scrolled a history of one loudness number,
 * and that number was a quarter-second average, so it could not follow a syllable and did not look
 * like a voice (founder 2026-09-14, #151: "I can see my actual voice in their bars"). Here every
 * delivery is the newest picture and every bar EASES toward its band on each frame: it rises fast, so
 * a syllable lands on time, and falls slower, so the gaps between words read as breath rather than as
 * a fault. The frame loop runs only while a bar is still moving and stops by itself, so a silent rail
 * costs nothing.
 *
 * **The rainbow belongs here specifically.** Recording is the one moment the product is doing the thing
 * it exists to do, and this is the only surface a user sees while not looking at the app
 * (`design-language.md` RULE: the-recorder-is-the-face-of-the-product). The gradient is painted ACROSS
 * the whole rail rather than per bar, so a colour belongs to a position and never to a loudness: the
 * rail must not look like a warning when someone speaks up.
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

    /** A dark edge behind every bar, in pixels; 0 draws none. See `BrandMarkView.inkEdgePx`. */
    var inkEdgePx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Where each bar is heading, and where it is drawn now. Both sized to [BAR_COUNT]; [barCount] bars are used. */
    private val target = FloatArray(BAR_COUNT)
    private val shown = FloatArray(BAR_COUNT)

    /** Each bar's bands for the current [barCount], rebuilt only when the count changes. */
    private val ranges = Array(BAR_COUNT) { IntRange.EMPTY }
    private var rangesFor = -1

    /**
     * How many bars the rail draws, at most [BAR_COUNT]. The picture is mapped onto whatever count the
     * pill gives it, so the tap pill shows half the hold pill's reach (founder 2026-09-13, build 116
     * phone pass: "half the size of the audio bar ... the length is fine for the push to talk") with
     * the same centre-out shape.
     */
    var barCount: Int = BAR_COUNT
        set(value) {
            val clamped = value.coerceIn(1, BAR_COUNT)
            if (field == clamped) return
            field = clamped
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

    private var animating = false
    private var lastFrameNanos = 0L
    private val frame = Runnable { step() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Hand the rail the newest picture: `SpectrumAnalyzer.BAND_COUNT` levels 0..1, lowest band first,
     * already scaled for display. Called on every delivery whether or not the picture changed; a
     * picture of silence is what lets the bars settle.
     */
    fun setBands(bands: FloatArray) {
        val count = barCount
        if (count != rangesFor) {
            for (index in 0 until BAR_COUNT) ranges[index] = barBands(index, count)
            rangesFor = count
        }
        for (index in 0 until BAR_COUNT) {
            var level = 0f
            for (band in ranges[index]) {
                val value = bands.getOrElse(band) { 0f }
                if (value.isFinite() && value > level) level = value
            }
            target[index] = level.coerceIn(0f, 1f)
        }
        startAnimating()
    }

    /** A new take starts at rest, not at the last picture of the previous one. */
    fun reset() {
        target.fill(0f)
        shown.fill(0f)
        stopAnimating()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    private fun startAnimating() {
        if (animating || !isAttachedToWindow) return
        animating = true
        lastFrameNanos = 0L
        postOnAnimation(frame)
    }

    private fun stopAnimating() {
        animating = false
        removeCallbacks(frame)
    }

    /**
     * One frame: move every bar toward its target by a time-constant step, redraw, and book the next
     * frame only while something is still moving.
     */
    private fun step() {
        if (!animating) return
        val now = System.nanoTime()
        val dtMs = if (lastFrameNanos == 0L) FRAME_MS else ((now - lastFrameNanos) / 1_000_000f).coerceIn(1f, 100f)
        lastFrameNanos = now
        var moving = false
        for (index in 0 until BAR_COUNT) {
            val next = ease(shown[index], target[index], dtMs)
            shown[index] = next
            if (abs(target[index] - next) > SETTLED) moving = true else shown[index] = target[index]
        }
        invalidate()
        if (moving && isAttachedToWindow) postOnAnimation(frame) else animating = false
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
                val barHeight = usableHeight * fill(shown[index])
                val left = paddingLeft + index * step
                bar.set(left - edge, centreY - barHeight / 2f - edge, left + barWidth + edge, centreY + barHeight / 2f + edge)
                canvas.drawRoundRect(bar, radius + edge, radius + edge, inkPaint)
            }
        }
        for (index in 0 until count) {
            val level = shown[index]
            // Symmetric about the centre line rather than growing off a floor, so the rail's visual
            // weight does not shift down the pill as the level drops. A silent band is a short bar,
            // never nothing: a rail that collapses between words reads as "it stopped hearing me".
            val barHeight = usableHeight * fill(level)
            val left = paddingLeft + index * step
            bar.set(left, centreY - barHeight / 2f, left + barWidth, centreY + barHeight / 2f)
            // A bar at rest is drawn in the resting grey, so silence reads as silence rather than as
            // a rainbow sitting at its floor.
            canvas.drawRoundRect(bar, radius, radius, if (level > RESTING_EPSILON) paint else restingPaint)
        }
    }

    companion object {
        /** The hold pill's full reach: every band twice, mirrored about the centre. */
        const val BAR_COUNT = 2 * SpectrumAnalyzer.BAND_COUNT

        /** A gap is this fraction of one bar's width. */
        const val GAP_RATIO = 0.55f

        /** A silent band's share of the rail's height. */
        const val SILENCE_FRACTION = 0.14f

        /** The additional share available at full level. */
        const val PEAK_FRACTION = 0.86f

        /** Below this a bar is at rest and drawn grey. */
        const val RESTING_EPSILON = 0.02f

        /** A bar within this of its target has arrived, and the frame loop may stop. */
        const val SETTLED = 0.005f

        /** How fast a bar rises toward a louder band: the time constant, so a syllable lands within a frame or two. */
        const val ATTACK_MS = 35f

        /**
         * How fast a bar falls toward a quieter band: slower than the rise, so the gaps between words
         * read as breath, but not so slow that syllables blur into one. Measured on the emulator
         * 2026-09-15 with a 300 Hz tone switched four times a second: at 110 ms the bar only fell to
         * about half between bursts, because the analyser's 64 ms window already holds the last burst's
         * tail; at 75 ms the dip is deep enough to read as a beat.
         */
        const val RELEASE_MS = 75f

        /** The step assumed for the first frame, before a real frame interval exists. */
        private const val FRAME_MS = 16f

        /** The share of the rail's height a band at [level] occupies. Pure, so it can be asserted without a canvas. */
        fun fill(level: Float): Float = SILENCE_FRACTION + PEAK_FRACTION * level.coerceIn(0f, 1f)

        /**
         * Which bar of [count] shows [band] on the RIGHT half of the rail (the left half mirrors it): band 0
         * in the middle, the last band at the edge. Pure, so the mapping can be asserted without a view.
         */
        fun barForBand(band: Int, count: Int): Int {
            val safe = band.coerceIn(0, SpectrumAnalyzer.BAND_COUNT - 1)
            if (count <= 2) return count - 1
            return if (count % 2 == 1) {
                val mid = (count - 1) / 2
                mid + (safe.toFloat() / (SpectrumAnalyzer.BAND_COUNT - 1) * mid).roundToInt()
            } else {
                // Two middle bars, half a bar either side of the centre; the right one is count / 2.
                val side = count / 2 - 1
                count / 2 + (safe.toFloat() / (SpectrumAnalyzer.BAND_COUNT - 1) * side).roundToInt()
            }
        }

        /**
         * The bands bar [index] of [count] shows, as an inclusive range; a bar draws the LOUDEST of them.
         *
         * Every band lands on some bar, whatever the count: the hold pill has a bar per band and the
         * tap pill, with half as many, gives most bars two. The first version picked ONE nearest band per
         * bar, which left five of the eleven bands with no bar at all on the tap pill, so a steady 1 kHz
         * tone drew NOTHING (measured on the emulator, 2026-09-15, with the published picture reading
         * 0.88 in band 5 the whole time). Pure, so the coverage can be asserted without a view.
         */
        fun barBands(index: Int, count: Int): IntRange {
            if (count <= 2) return 0 until SpectrumAnalyzer.BAND_COUNT
            val mirrored = if (index < count / 2f) count - 1 - index else index
            var low = -1
            var high = -1
            for (band in 0 until SpectrumAnalyzer.BAND_COUNT) {
                if (barForBand(band, count) == mirrored) {
                    if (low < 0) low = band
                    high = band
                }
            }
            return if (low < 0) IntRange.EMPTY else low..high
        }

        /** The band bar [index] of [count] is named after: the middle of its range. For the demo's shape. */
        fun barBand(index: Int, count: Int): Int {
            val range = barBands(index, count)
            return if (range.isEmpty()) 0 else (range.first + range.last) / 2
        }

        /**
         * Move [from] toward [to] over [dtMs] with a time-constant easing, faster up than down. Pure and
         * frame-rate independent: two 8 ms steps land where one 16 ms step does.
         */
        fun ease(from: Float, to: Float, dtMs: Float): Float {
            val tau = if (to > from) ATTACK_MS else RELEASE_MS
            val rate = 1f - exp(-dtMs / tau)
            return from + (to - from) * rate
        }
    }
}
