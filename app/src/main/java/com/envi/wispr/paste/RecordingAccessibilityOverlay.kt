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
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT as WRAP
import android.widget.TextView
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.ui.DictationSessionService

/** Small trusted overlay. Its window never takes editor or IME focus. */
internal class RecordingAccessibilityOverlay(
    private val service: PasteAccessibilityService,
) : RecordingOverlayState.Listener {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val timer = TextView(service)
    private val notice = TextView(service)
    private val root = buildRoot()
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
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
            remove()
            return
        }
        timer.text = "${snapshot.elapsedSeconds}s"
        timer.contentDescription = "${snapshot.elapsedSeconds} seconds elapsed"
        val line = snapshot.notice
        if (line.isNullOrBlank()) {
            notice.visibility = View.GONE
        } else {
            notice.text = line
            notice.contentDescription = line
            notice.visibility = View.VISIBLE
        }
        runCatching { updateTopOffset() }
            .onFailure { error -> Log.w(TAG, "Unable to position recording controls", error) }
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
            setTextColor(Color.rgb(230, 230, 230))
            textSize = 12f
            maxLines = 2
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.rgb(36, 39, 43), dp(12).toFloat())
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(notice, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        }
    }

    private fun buildPill(): View {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(10).toFloat()
            background = roundedBackground(Color.rgb(36, 39, 43), dp(32).toFloat())
            contentDescription = "Recording controls"
        }

        timer.apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            minWidth = dp(36)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(
            timer,
            LinearLayout.LayoutParams(dp(36), dp(48)).apply { marginEnd = dp(6) },
        )
        container.addView(
            actionButton("■", "Stop", Color.rgb(46, 125, 50)) {
                DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP)
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) },
        )
        container.addView(
            actionButton("×", "Cancel", Color.rgb(198, 40, 40)) {
                DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL)
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        return container
    }

    private fun actionButton(
        glyph: String,
        accessibilityLabel: String,
        color: Int,
        action: () -> Unit,
    ) = TextView(service).apply {
        text = glyph
        textSize = if (glyph == "×") 30f else 19f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = accessibilityLabel
        isClickable = true
        isFocusable = false
        background = roundedBackground(color, dp(24).toFloat())
        setOnClickListener { action() }
    }

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

    private fun updateTopOffset() {
        val metrics = windowManager.currentWindowMetrics
        val desired = metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top + dp(12)
        if (layoutParams.y == desired) return
        layoutParams.y = desired
        if (attached) runCatching { windowManager.updateViewLayout(root, layoutParams) }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val TAG = "RecordingOverlay"
    }
}
