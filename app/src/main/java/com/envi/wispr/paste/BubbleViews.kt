package com.envi.wispr.paste

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT as MATCH
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT as WRAP
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.envi.wispr.R

/**
 * The floating recorder's views, built and painted (#360): the lips bubble, the pill with its clock, rail and two
 * controls, the notice under it, and the "Drop to hide" label. Nothing here decides where a window goes or what a
 * touch meant; [RecordingAccessibilityOverlay] owns the windows and hands every touch and click back through the
 * callbacks it passes in.
 */
internal class BubbleViews(
    private val context: Context,
    private val onBubbleTouch: (MotionEvent) -> Boolean,
    private val onBubbleClick: () -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    private val onConfigurationChanged: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    /** Declared before the views: `buildPill` applies it while the views are still being built. */
    private var look = BubbleLook.DEFAULT
    val timer = InkEdgedTextView(context)
    val meter = RecordingLevelMeterView(context)
    private val recordMark = RecordMarkView(context)
    val notice = TextView(context)
    val bubbleMark = BrandMarkView(context)
    private val cancelButton = actionButton(ActionGlyph.CROSS, "Cancel", BrandPalette.NEUTRAL_CONTROL) { onCancel() }
    private val acceptButton = actionButton(ActionGlyph.CHECK, "Stop and use these words", BrandPalette.ACCENT) { onAccept() }
    val bubble = buildBubble()
    val pillColumn = buildPillColumn()
    val root = buildRoot()
    val hideTarget = buildHideTarget()
    /** What the pill's parts were last laid out for; null until the first pill. */
    private var pillCompact: Boolean? = null

    /** The look the user chose in Settings > Appearance. Idempotent. */
    fun setLook(look: BubbleLook) {
        if (this.look == look) return
        this.look = look
        applyLook()
    }

    /**
     * Whether the earbuds are the chosen microphone right now (#171): the lips and the rail take the earbud rainbow,
     * and the bubble's spoken label says so. Idempotent.
     */
    fun setEarbuds(earbuds: Boolean) {
        val palette = if (earbuds) BrandPalette.RAINBOW_EARBUDS else BrandPalette.RAINBOW
        bubbleMark.palette = palette
        meter.palette = palette
        bubble.contentDescription = if (earbuds) RecordingAccessibilityOverlay.BUBBLE_LABEL_EARBUDS else RecordingAccessibilityOverlay.BUBBLE_LABEL
    }

    /**
     * Paint the three surfaces for [look]. One ground colour and one shadow depth shared by the bubble
     * and both pills, no outline on any of them; the lips, the rail and the clock carry the look's ink
     * edge, so all three read on a white page and a dark one alike. The values are
     * Codex's from `docs/mockups/android-bubble-v2/README.md`, ported one to one.
     */
    private fun applyLook() {
        val ground = look.surfaceFill
        val shadow = dp(look.surfaceElevationDp).toFloat()
        // The bubble's visible ground is a 48 dp square inside the 56 dp touch target.
        bubble.background = if (ground ushr 24 == 0) {
            null
        } else {
            InsetDrawable(roundedBackground(ground, dp(RecordingAccessibilityOverlay.BUBBLE_RADIUS_DP).toFloat()), dp(RecordingAccessibilityOverlay.BUBBLE_INSET_DP))
        }
        bubble.elevation = shadow
        bubble.outlineSpotShadowColor = BrandPalette.PILL_BACKGROUND
        bubble.outlineAmbientShadowColor = BrandPalette.PILL_BACKGROUND
        val lipsInset = dp((RecordingAccessibilityOverlay.BUBBLE_DP - look.lipsDp) / 2)
        bubbleMark.setPadding(lipsInset, lipsInset, lipsInset, lipsInset)
        bubbleMark.inkEdgePx = look.inkEdgeDp * density
        pill.background = if (ground ushr 24 == 0) null else roundedBackground(ground, dp(RecordingAccessibilityOverlay.PILL_RADIUS_DP).toFloat())
        pill.elevation = shadow
        pill.outlineSpotShadowColor = BrandPalette.PILL_BACKGROUND
        pill.outlineAmbientShadowColor = BrandPalette.PILL_BACKGROUND
        meter.inkEdgePx = look.inkEdgeDp * density
        // The clock's edge is heavier than the bars': 1.5 dp, Codex's value, so the digits hold their
        // shape on a white page at 15 sp.
        timer.inkEdgePx = if (look.inkEdgeDp > 0f) RecordingAccessibilityOverlay.CLOCK_INK_EDGE_DP * density else 0f
        // The two controls are see-through like the ground they sit on; only their glyphs are solid.
        cancelButton.background = roundedBackground(look.cancelFill, dp(RecordingAccessibilityOverlay.CONTROL_RADIUS_DP).toFloat())
        acceptButton.background = roundedBackground(look.acceptFill, dp(RecordingAccessibilityOverlay.CONTROL_RADIUS_DP).toFloat())
    }

    /**
     * Two layouts of the one pill, either of them mirrored. Full: time, rail, cancel, accept, at the
     * bubble's edge. Compact, for a hold: the rail and a recording mark, because the finger is already
     * the control and everything else was noise while it was down (founder 2026-09-13, from Wispr Flow's
     * hold pill). The mark sits at the thumb end, under the finger, and the rail keeps the rest: build
     * 130 ran the rail under the thumb and half of it was hidden (founder 2026-09-15). Same height
     * either way, so the pill never jumps between the two.
     *
     * [mirrored] flips the row for a bubble docked on the LEFT, so accept sits at the left edge under
     * the thumb that put the bubble there, rather than across the pill (founder 2026-09-13). The row's
     * layout direction does the flipping, which keeps every margin between the same two neighbours.
     */
    fun layOutPill(compact: Boolean, mirrored: Boolean) {
        pill.layoutDirection = if (mirrored) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (pillCompact == compact) return
        pillCompact = compact
        val partsVisibility = if (compact) View.GONE else View.VISIBLE
        timer.visibility = partsVisibility
        cancelButton.visibility = partsVisibility
        acceptButton.visibility = partsVisibility
        recordMark.visibility = if (compact) View.VISIBLE else View.GONE
        (meter.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (compact) dp(RecordingAccessibilityOverlay.COMPACT_RAIL_HEIGHT_DP) else dp(RecordingAccessibilityOverlay.RAIL_HEIGHT_DP)
            marginEnd = dp(10)
        }
        meter.layoutParams = meter.layoutParams
        meter.barCount = if (compact) RecordingLevelMeterView.BAR_COUNT else RecordingAccessibilityOverlay.FULL_PILL_BARS
        val vertical = if (compact) dp(RecordingAccessibilityOverlay.COMPACT_PILL_PADDING_DP) else dp(8)
        pill.setPadding(if (compact) dp(16) else dp(10), vertical, if (compact) dp(16) else dp(10), vertical)
        pill.contentDescription = if (compact) "Recording. Let go to finish." else "Recording controls"
    }

    private fun buildRoot(): View {
        // The root listens for configuration changes itself. A rotation must cancel any gesture in
        // flight BEFORE relayout, and must not wait for a tick or an event to re-place the window.
        return object : FrameLayout(context) {
            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                onConfigurationChanged()
            }
        }.apply {
            addView(pillColumn, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(bubble, FrameLayout.LayoutParams(dp(RecordingAccessibilityOverlay.BUBBLE_DP), dp(RecordingAccessibilityOverlay.BUBBLE_DP)))
        }
    }

    /**
     * The idle lips on a 56 dp touch target. Ground, shadow, lips size and ink edge come from the
     * chosen [BubbleLook] through [applyLook]; nothing here paints. The founder dropped the
     * violet-ringed dark circle on 2026-09-14 for Wispr Flow's lighter shape and then chose to
     * offer three looks rather than one.
     */
    private fun buildBubble(): View {
        return FrameLayout(context).apply {
            contentDescription = RecordingAccessibilityOverlay.BUBBLE_LABEL
            isClickable = true
            isFocusable = false
            addView(bubbleMark, FrameLayout.LayoutParams(MATCH, MATCH))
            // The accessibility click action (a TalkBack double tap) arrives here, never through the
            // touch listener below, which consumes every real touch and resolves taps itself. So the
            // two routes cannot fire twice for one gesture (Codex review of the Play branch, round 3).
            setOnClickListener { onBubbleClick() }
            setOnTouchListener { _, event -> onBubbleTouch(event) }
        }
    }

    private fun buildPillColumn(): View {
        val pill = buildPill()

        // The notice sits BELOW the pill rather than inside it, so the pill keeps its shape and the
        // line can wrap. Hidden by default: an empty slot must not change what the recorder looks like.
        notice.apply {
            gravity = Gravity.CENTER
            setTextColor(BrandPalette.TEXT)
            typeface = brandTypeface(R.font.plus_jakarta_sans_medium)
            textSize = 12f
            maxLines = 2
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(BrandPalette.PILL_BACKGROUND, dp(12).toFloat())
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(notice, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        }
    }

    /**
     * The pill: elapsed time, level rail, cancel, accept, in that order from the far edge towards the
     * dock, so the accept control is always the one nearest the thumb that docked the bubble.
     *
     * Layout from `docs/mockups/android-v2/06-floating-recorder.png` and that folder's README, minus the
     * mark and the LISTENING word: the founder dropped both on 2026-09-13 after using build 114, so the
     * bar is smaller and less in the way. The lips are on the bubble; the pill does not need them twice.
     */
    private fun buildPill(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // Ground and shadow come from the chosen look through applyLook. The violet outline and
            // violet glow of the first recorder are retired (founder 2026-09-14): none of the three
            // looks carries a border.
            contentDescription = "Recording controls"
        }

        timer.apply {
            gravity = Gravity.CENTER
            setTextColor(BrandPalette.TEXT)
            textSize = 15f
            // Plus Jakarta Sans, the brand typeface, which is already bundled and which every Compose
            // screen uses. `minWidth` holds the column steady as the digits change, so the rail beside
            // it does not shift every second.
            typeface = brandTypeface(R.font.plus_jakarta_sans_semibold)
            minWidth = dp(48)
            // The clock reads left to right whichever way the pill is mirrored.
            textDirection = View.TEXT_DIRECTION_LTR
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        container.addView(
            timer,
            LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(10) },
        )
        // The rail takes the room the fixed parts leave, so the two pill widths share one layout.
        container.addView(
            meter,
            LinearLayout.LayoutParams(0, dp(RecordingAccessibilityOverlay.RAIL_HEIGHT_DP), 1f).apply { marginEnd = dp(10) },
        )
        container.addView(cancelButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        container.addView(acceptButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        // The hold pill's thumb end: the pill is anchored to the bubble, so this slot plus the end
        // padding is exactly the bubble's footprint, under the finger that is holding it.
        // As tall as the compact rail, so the compact pill's 16 dp paddings still make exactly
        // PILL_HEIGHT_DP: a 40 dp child would grow the hold pill to 72 dp (Codex review, 2026-09-15).
        container.addView(recordMark, LinearLayout.LayoutParams(dp(RecordingAccessibilityOverlay.RECORD_MARK_DP), dp(RecordingAccessibilityOverlay.COMPACT_RAIL_HEIGHT_DP)))
        recordMark.visibility = View.GONE
        pill = container
        applyLook()
        return container
    }

    /** "Drop to hide", in its own untouchable window, shown only while a drag is in progress. */
    private fun buildHideTarget(): TextView = TextView(context).apply {
        text = "Drop to hide"
        setTextColor(BrandPalette.TEXT)
        typeface = brandTypeface(R.font.plus_jakarta_sans_medium)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(BrandPalette.PILL_BACKGROUND)
            setStroke(dp(1).coerceAtLeast(1), BrandPalette.TEXT_MUTED)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A round control whose symbol is drawn, not typed: a text glyph sits where its font puts it, not at the centre. */
    private fun actionButton(
        glyph: ActionGlyph,
        accessibilityLabel: String,
        color: Int,
        action: () -> Unit,
    ) = ActionGlyphView(context, glyph).apply {
        contentDescription = accessibilityLabel
        isClickable = true
        isFocusable = false
        background = roundedBackground(color, dp(RecordingAccessibilityOverlay.CONTROL_RADIUS_DP).toFloat())
        setOnClickListener { action() }
    }

    /**
     * The bundled brand font, or the platform default if it cannot be loaded.
     *
     * A missing font must never take the recorder down: it is the window the heart path draws in, and
     * a typeface is the most cosmetic thing on it.
     */
    private fun brandTypeface(fontRes: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull() ?: Typeface.DEFAULT

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private lateinit var pill: LinearLayout

    fun dp(value: Int): Int = (value * density).toInt()
}
