package com.envi.wispr.paste

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT as MATCH
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT as WRAP
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.envi.wispr.R
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.ui.DictationSessionService

/** Small trusted overlay. Its window never takes editor or IME focus. */
internal class RecordingAccessibilityOverlay(
    private val service: PasteAccessibilityService,
) : RecordingOverlayState.Listener {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val mark = BrandMarkView(service)
    private val timer = TextView(service)
    private val meter = RecordingLevelMeterView(service)
    private val stateLabel = TextView(service)
    private val notice = TextView(service)
    private val root = buildRoot()
    private val layoutParams = WindowManager.LayoutParams(
        // Set to the pill's own width in `updateWindowBounds`, never MATCH_PARENT and never
        // WRAP_CONTENT. WRAP_CONTENT let the rail's weight resolve against the whole screen and the
        // pill ran edge to edge. MATCH_PARENT fixed the look and broke something worse: a transparent
        // margin inside the window is still TOUCHABLE, because FLAG_NOT_TOUCH_MODAL passes touches
        // outside the WINDOW and not outside the painted pill, so two strips beside the recorder
        // silently ate taps meant for the app underneath.
        1,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(12)
        title = "EnviousWispr recording controls"
    }
    private var attached = false
    private var active = false
    /** What the slow half of the recorder was last set to. -1 and null mean it is not shown. */
    private var lastElapsedSeconds = -1
    private var lastNotice: String? = null

    fun start() {
        active = true
        RecordingOverlayState.attach(this)
    }

    fun stop() {
        active = false
        RecordingOverlayState.detach(this)
        remove()
    }

    override fun onChanged(snapshot: RecordingOverlayState.Snapshot) {
        if (!active) return
        if (!snapshot.visible) {
            lastElapsedSeconds = -1
            lastNotice = null
            remove()
            return
        }
        // The meter is the only thing that moves at speaking rate. It redraws itself and touches
        // nothing else, so it is handled before the early return below.
        meter.setLevel(snapshot.level)

        // Everything past here changes about once a second at most, and one part of it reads the
        // window metrics, which is framework work on the main thread. Doing it on every level change
        // would run it ten times a second to write the same string back.
        //
        // Compared field by field rather than through a holder object, because building one to throw
        // it away is itself an allocation ten times a second on the main thread.
        if (attached &&
            snapshot.elapsedSeconds == lastElapsedSeconds &&
            snapshot.notice == lastNotice
        ) {
            return
        }
        lastElapsedSeconds = snapshot.elapsedSeconds
        lastNotice = snapshot.notice

        timer.text = ElapsedLabels.clock(snapshot.elapsedSeconds)
        timer.contentDescription = ElapsedLabels.spoken(snapshot.elapsedSeconds)
        val line = snapshot.notice
        if (line.isNullOrBlank()) {
            notice.visibility = View.GONE
        } else {
            notice.text = line
            notice.contentDescription = line
            notice.visibility = View.VISIBLE
        }
        // A failure HERE returns rather than logging and carrying on. The window starts at one pixel
        // wide, so attaching after a failed sizing puts a sliver on screen with the controls inside it
        // unreachable. Returning leaves `attached` false, and the next tick tries again a second later.
        runCatching { updateWindowBounds() }
            .getOrElse { error ->
                Log.w(TAG, "Unable to position recording controls", error)
                return
            }
        if (!attached) {
            runCatching {
                windowManager.addView(root, layoutParams)
                attached = true
                root.requestApplyInsets()
            }.onFailure { error -> Log.w(TAG, "Unable to show recording controls", error) }
        }
    }


    private fun buildRoot(): View {
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

        // The root listens for configuration changes itself. Window sizing otherwise rides on the
        // elapsed second, so a rotation would leave a window built for the other orientation until the
        // next tick, and the window's rectangle is its touch area.
        return object : LinearLayout(service) {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
                super.onConfigurationChanged(newConfig)
                if (!active || !attached) return
                runCatching { updateWindowBounds() }
                    .onFailure { error -> Log.w(TAG, "Unable to resize recording controls", error) }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // No padding: the window is already inset to the pill's width, and any transparent room
            // inside it would be touchable.
            addView(pill, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(notice, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        }
    }

    /**
     * The pill, in the founder's own order: mark, elapsed time, level rail, state, cancel, accept.
     *
     * Layout from `docs/mockups/android-v2/06-floating-recorder.png` and that folder's README.
     */
    private fun buildPill(): View {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = dp(12).toFloat()
            // A violet outline and a soft violet glow on a fully rounded pill. The glow is the
            // elevation's own shadow tinted violet, which is what makes the recorder read as ours
            // rather than as a system chip.
            background = pillBackground()
            outlineSpotShadowColor = BrandPalette.VIOLET
            outlineAmbientShadowColor = BrandPalette.VIOLET
            contentDescription = "Recording controls"
        }

        timer.apply {
            gravity = Gravity.CENTER
            setTextColor(BrandPalette.TEXT)
            textSize = 15f
            // Plus Jakarta Sans, the brand typeface, which is already bundled and which every Compose
            // screen uses. The recorder was on the platform monospace, which drew "0 : 05" with gaps
            // wide enough to read as three separate numbers. `minWidth` holds the column steady as the
            // digits change, so the rail beside it does not shift every second.
            typeface = brandTypeface(R.font.plus_jakarta_sans_semibold)
            minWidth = dp(48)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        stateLabel.apply {
            // STATIC, and it can only be right. The recorder exists between `show()` and `hide()`,
            // which is exactly the listening phase; every later phase has already hidden it. A label
            // wired to a live phase would add a way for it to be wrong and buy nothing.
            text = LISTENING_LABEL
            gravity = Gravity.CENTER
            setTextColor(BrandPalette.TEXT_MUTED)
            textSize = 10f
            letterSpacing = 0.14f
            typeface = brandTypeface(R.font.plus_jakarta_sans_bold)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Listening"
        }

        container.addView(
            mark,
            LinearLayout.LayoutParams(dp(22), dp(20)).apply { marginEnd = dp(10) },
        )
        container.addView(
            timer,
            LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(10) },
        )
        // The rail takes the room that is left, so the pill grows with the screen rather than the rail
        // being pinned to one width that is wrong on two of them.
        container.addView(
            meter,
            LinearLayout.LayoutParams(0, dp(22), 1f).apply { marginEnd = dp(10) },
        )
        container.addView(
            stateLabel,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(10) },
        )
        container.addView(
            actionButton("×", "Cancel", BrandPalette.NEUTRAL_CONTROL) {
                DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL)
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) },
        )
        container.addView(
            actionButton("✓", "Stop and use these words", BrandPalette.ACCENT) {
                DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP)
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        return container
    }

    /** The pill's ground plus its violet outline, as one drawable. */
    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(BrandPalette.PILL_BACKGROUND)
        setStroke(dp(1).coerceAtLeast(1), BrandPalette.VIOLET)
    }

    private fun actionButton(
        glyph: String,
        accessibilityLabel: String,
        color: Int,
        action: () -> Unit,
    ) = TextView(service).apply {
        text = glyph
        typeface = brandTypeface(R.font.plus_jakarta_sans_semibold)
        textSize = if (glyph == "×") 22f else 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = accessibilityLabel
        isClickable = true
        isFocusable = false
        background = roundedBackground(color, dp(20).toFloat())
        setOnClickListener { action() }
    }

    /**
     * The bundled brand font, or the platform default if it cannot be loaded.
     *
     * A missing font must never take the recorder down: it is the window the heart path draws in, and
     * a typeface is the most cosmetic thing on it.
     */
    private fun brandTypeface(fontRes: Int): Typeface =
        runCatching { ResourcesCompat.getFont(service, fontRes) }.getOrNull() ?: Typeface.DEFAULT

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun remove() {
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(root) }
        attached = false
    }

    /**
     * Size and place the window to the pill itself.
     *
     * Width as well as position, because the window's rectangle IS its touch area: anything it covers
     * and does not paint is a tap the user's own app never receives. Recomputed on every update so a
     * rotation or a multi-window resize does not leave a window sized for the other shape.
     */
    private fun updateWindowBounds() {
        val metrics = windowManager.currentWindowMetrics
        val desiredWidth = (metrics.bounds.width() - dp(24)).coerceAtLeast(1)
        val desiredY = metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top + dp(12)
        if (layoutParams.width == desiredWidth && layoutParams.y == desiredY) return
        layoutParams.width = desiredWidth
        layoutParams.y = desiredY
        if (attached) runCatching { windowManager.updateViewLayout(root, layoutParams) }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val TAG = "RecordingOverlay"

        /** What the recorder says it is doing. The mockup's own word, in quiet caps. */
        const val LISTENING_LABEL = "LISTENING"
    }
}
