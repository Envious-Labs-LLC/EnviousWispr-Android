package com.envi.wispr.paste

import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT as MATCH
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT as WRAP
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.envi.wispr.R
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.BubbleRequests
import com.envi.wispr.shortcuts.RecordingOverlayState
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.ui.VoiceInputActivity

/**
 * The one floating window, in two shapes. Its window never takes editor or IME focus.
 *
 * At idle, while another app's editable field is focused, it is the lips bubble: the primary way to
 * start a dictation (issue #135). During a take it is the recorder pill, anchored to the edge the
 * bubble was docked at. Both shapes are sized to exactly what they paint, because with
 * FLAG_NOT_TOUCH_MODAL the WINDOW rectangle is the touch area and anything transparent inside it eats
 * taps meant for the app underneath.
 *
 * Everything that decides WHETHER the bubble shows comes from [PasteAccessibilityService], which owns
 * the accessibility events; this class never reads the node tree. Everything that decides what a touch
 * MEANT is [BubbleGestureClassifier]; everything that decides WHERE a shape goes is [BubblePlacement].
 * This class wires them to Android.
 */
internal class RecordingAccessibilityOverlay(
    private val service: PasteAccessibilityService,
) : RecordingOverlayState.Listener {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    /** Declared before the views: `buildPill` applies it while the views are still being built. */
    private var look = BubbleLook.DEFAULT
    private val timer = InkEdgedTextView(service)
    private val meter = RecordingLevelMeterView(service)
    private val notice = TextView(service)
    private val bubbleMark = BrandMarkView(service)
    private val cancelButton = actionButton(ActionGlyph.CROSS, "Cancel", BrandPalette.NEUTRAL_CONTROL) {
        DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL)
    }
    private val acceptButton = actionButton(ActionGlyph.CHECK, "Stop and use these words", BrandPalette.ACCENT) {
        DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP)
    }
    private val bubble = buildBubble()
    private val pillColumn = buildPillColumn()
    private val root = buildRoot()
    private val hideTarget = buildHideTarget()
    private val layoutParams = WindowManager.LayoutParams(
        // Set per shape in `render`, never MATCH_PARENT and never WRAP_CONTENT for the width: with
        // FLAG_NOT_TOUCH_MODAL, transparent room inside the window still swallows the taps under it.
        1,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = WINDOW_TITLE
    }
    private val hideTargetParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "EnviousWispr hide target"
    }

    private val classifier = BubbleGestureClassifier(
        slopPx = ViewConfiguration.get(service).scaledTouchSlop.toFloat(),
        holdTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
    )
    private val holdRunnable = Runnable { onGesture(classifier.holdTimeout(nowMs())) }

    private var attached = false
    private var hideTargetAttached = false
    private var active = false
    private var snapshot = RecordingOverlayState.Snapshot()
    /** What the slow half of the recorder was last set to. -1 and null mean it is not shown. */
    private var lastElapsedSeconds = -1
    private var lastLevelTick = -1
    private var lastNotice: String? = null

    /** The service's word on whether another app's editable field is focused, and which one. */
    private var fieldActive = false
    private var fieldKey: Any? = null
    /** Set when the user drops the bubble on the hide target; cleared by a DIFFERENT field key. */
    private var hiddenForKey: Any? = null
    private var keyboardTop: Int? = null
    private var position = BubblePosition.DEFAULT
    /** The bubble's box while a drag is in progress, in screen pixels; null otherwise. */
    private var dragBox: Box? = null
    private var dragOrigin: Box? = null
    private var lastBounds: BubbleBounds? = null

    fun start() {
        active = true
        RecordingOverlayState.attach(this)
    }

    fun stop() {
        active = false
        RecordingOverlayState.detach(this)
        cancelGesture()
        removeHideTarget()
        remove()
    }

    // ---- what the service tells the overlay ----

    /** Another app's editable field is focused. [key] identifies the editor node, never the window alone. */
    fun fieldActivated(key: Any) {
        if (hiddenForKey != null && hiddenForKey != key) hiddenForKey = null
        val changed = !fieldActive || fieldKey != key
        fieldActive = true
        fieldKey = key
        if (changed) render()
    }

    fun fieldLost() {
        if (!fieldActive) return
        fieldActive = false
        fieldKey = null
        render()
    }

    /** The top of a docked keyboard in screen pixels, or null when none is showing. */
    fun keyboardBounds(top: Int?) {
        if (keyboardTop == top) return
        keyboardTop = top
        render()
    }

    /** The persisted position, loaded by the service off the main thread. */
    fun setPosition(loaded: BubblePosition) {
        if (position == loaded) return
        position = loaded
        render()
    }

    /** What the service persists after a drag. Set by the service so the store stays out of this class. */
    var onPositionChanged: ((BubblePosition) -> Unit)? = null

    /** The look the user chose in Settings > Appearance, delivered by the service on the main thread. */
    fun setLook(look: BubbleLook) {
        if (this.look == look) return
        this.look = look
        applyLook()
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
            InsetDrawable(roundedBackground(ground, dp(BUBBLE_RADIUS_DP).toFloat()), dp(BUBBLE_INSET_DP))
        }
        bubble.elevation = shadow
        bubble.outlineSpotShadowColor = BrandPalette.PILL_BACKGROUND
        bubble.outlineAmbientShadowColor = BrandPalette.PILL_BACKGROUND
        val lipsInset = dp((BUBBLE_DP - look.lipsDp) / 2)
        bubbleMark.setPadding(lipsInset, lipsInset, lipsInset, lipsInset)
        bubbleMark.inkEdgePx = look.inkEdgeDp * density
        pill.background = if (ground ushr 24 == 0) null else roundedBackground(ground, dp(PILL_RADIUS_DP).toFloat())
        pill.elevation = shadow
        pill.outlineSpotShadowColor = BrandPalette.PILL_BACKGROUND
        pill.outlineAmbientShadowColor = BrandPalette.PILL_BACKGROUND
        meter.inkEdgePx = look.inkEdgeDp * density
        // The clock's edge is heavier than the bars': 1.5 dp, Codex's value, so the digits hold their
        // shape on a white page at 15 sp.
        timer.inkEdgePx = if (look.inkEdgeDp > 0f) CLOCK_INK_EDGE_DP * density else 0f
        // The two controls are see-through like the ground they sit on; only their glyphs are solid.
        cancelButton.background = roundedBackground(look.cancelFill, dp(CONTROL_RADIUS_DP).toFloat())
        acceptButton.background = roundedBackground(look.acceptFill, dp(CONTROL_RADIUS_DP).toFloat())
    }

    // ---- what the session owner tells the overlay ----

    override fun onChanged(snapshot: RecordingOverlayState.Snapshot) {
        if (!active) return
        val previous = this.snapshot
        this.snapshot = snapshot
        if (!snapshot.visible) {
            lastElapsedSeconds = -1
            lastNotice = null
            lastLevelTick = -1
            if (previous.visible != snapshot.visible || previous.phase != snapshot.phase) render()
            return
        }
        // A new take starts with an empty record, not the tail of the last one.
        if (!previous.visible) meter.reset()
        // The rail is the only thing that moves at speaking rate. It redraws itself and touches
        // nothing else, so it is handled before the early return below. One bar per POLL, read off
        // the tick rather than the level, so a silent stretch scrolls out instead of freezing.
        if (snapshot.levelTick != lastLevelTick) {
            lastLevelTick = snapshot.levelTick
            meter.pushSample(snapshot.level)
        }

        // Everything past here changes about once a second at most, and one part of it reads the
        // window metrics, which is framework work on the main thread. Doing it on every level change
        // would run it ten times a second to write the same string back.
        if (attached && previous.visible &&
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
        render()
    }

    // ---- rendering: one decision, one window ----

    /**
     * Decide the shape and place the window. Pill while a take is visible; bubble while a field is
     * active and the user has not hidden it; nothing otherwise. A drag in progress keeps the bubble
     * where the finger is.
     */
    private fun render() {
        if (!active) return
        val bounds = runCatching { readBounds() }
            .getOrElse { error ->
                // A failure HERE returns rather than carrying on. The window starts at one pixel wide,
                // so attaching after a failed sizing puts a sliver on screen with the controls inside
                // it unreachable. The next event or tick tries again.
                Log.w(TAG, "Unable to read window bounds", error)
                return
            }
        lastBounds = bounds
        when {
            snapshot.visible -> {
                val bubbleBox = BubblePlacement.bubbleBox(position, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))
                    ?: Box(bounds.usable.right - dp(MARGIN_DP) - dp(BUBBLE_DP), bounds.usable.top + dp(MARGIN_DP), bounds.usable.right - dp(MARGIN_DP), bounds.usable.top + dp(MARGIN_DP) + dp(BUBBLE_DP))
                // Measure the whole column, notice line included, so a warning that grows it is
                // placed above the keyboard rather than hanging over the keys (Play-branch review).
                val compact = snapshot.requestToken != null && snapshot.requestToken == heldTake
                layOutPill(compact, mirrored = position.side == BubbleSide.LEFT)
                val width = if (compact) dp(COMPACT_PILL_DP) else dp(FULL_PILL_DP)
                pillColumn.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val height = pillColumn.measuredHeight.coerceAtLeast(dp(PILL_HEIGHT_DP))
                val pill = BubblePlacement.pillBox(position, bubbleBox, bounds, width, height, dp(MARGIN_DP))
                showShape(pill = true, box = pill, width = pill.width, height = pill.height)
            }
            dragBox != null -> {
                val box = dragBox ?: return
                showShape(pill = false, box = box, width = box.width, height = box.height)
            }
            fieldActive && hiddenForKey == null -> {
                val box = BubblePlacement.bubbleBox(position, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))
                if (box == null) {
                    remove()
                } else {
                    // STARTING and PROCESSING: the bubble stays and ignores taps, and the lips roll
                    // their rainbow to say the words are being worked on (founder 2026-09-13, after
                    // Wispr Flow's spinning icon). Where animations are off at the system level the
                    // mock's dimmed working state stands in for the motion.
                    val working = snapshot.phase != RecordingOverlayState.Phase.IDLE
                    val animated = bubbleMark.setBusy(working)
                    bubble.alpha = if (working && !animated) WORKING_ALPHA else 1f
                    showShape(pill = false, box = box, width = box.width, height = box.height)
                }
            }
            else -> remove()
        }
    }

    private fun showShape(pill: Boolean, box: Box, width: Int, height: Int) {
        if (pill) bubbleMark.setBusy(false)
        pillColumn.visibility = if (pill) View.VISIBLE else View.GONE
        bubble.visibility = if (pill) View.GONE else View.VISIBLE
        val unchanged = layoutParams.x == box.left && layoutParams.y == box.top &&
            layoutParams.width == width && layoutParams.height == height
        layoutParams.x = box.left
        layoutParams.y = box.top
        layoutParams.width = width
        layoutParams.height = height
        if (!attached) {
            runCatching {
                windowManager.addView(root, layoutParams)
                attached = true
                root.requestApplyInsets()
            }.onFailure { error -> Log.w(TAG, "Unable to show the floating window", error) }
        } else if (!unchanged) {
            // Never update a window whose bounds did not change: the update itself is a windows
            // change, and the service must not hear about a move it did not make.
            runCatching { windowManager.updateViewLayout(root, layoutParams) }
                .onFailure { error -> Log.w(TAG, "Unable to move the floating window", error) }
        }
    }

    private fun remove() {
        bubbleMark.setBusy(false)
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(root) }
        attached = false
    }

    /**
     * The usable rectangle: the screen minus the bars it must not sit under. The keyboard is not an
     * inset here; it is the service's answer, delivered through [keyboardBounds], because only the
     * accessibility windows list says where a docked keyboard ends.
     */
    private fun readBounds(): BubbleBounds {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
        )
        val screen = metrics.bounds
        val usable = Box(
            left = screen.left + insets.left,
            top = screen.top + insets.top,
            right = screen.right - insets.right,
            bottom = screen.bottom - insets.bottom,
        )
        val keyboard = keyboardTop?.takeIf { it in usable.top until usable.bottom }
        return BubbleBounds(usable, keyboard)
    }

    // ---- gestures on the bubble ----

    private fun onTouch(event: MotionEvent): Boolean {
        // Only the primary pointer drives the gesture. A second finger arriving or leaving is ignored,
        // and the primary finger leaving while a second is down reads as up.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onGesture(classifier.down(event.rawX, event.rawY, nowMs()))
                root.removeCallbacks(holdRunnable)
                root.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val bounds = lastBounds ?: return true
                val origin = dragOrigin ?: currentBubbleBox(bounds) ?: return true
                val left = origin.left + (event.rawX - downRawX).toInt()
                val top = origin.top + (event.rawY - downRawY).toInt()
                onGesture(classifier.move(event.rawX, event.rawY, nowMs(), overHideTarget(left, top)))
            }
            MotionEvent.ACTION_UP -> onGesture(classifier.up(nowMs()))
            MotionEvent.ACTION_CANCEL -> onGesture(classifier.cancel())
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionIndex == 0 && event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    onGesture(classifier.up(nowMs()))
                }
            }
        }
        return true
    }

    /** Where the primary finger went down, in screen pixels; a drag is measured from here. */
    private var downRawX = 0f
    private var downRawY = 0f

    /**
     * The request the hold in progress created, or null when that hold created none (the owner was
     * not IDLE). Its release or cancel goes to this request and no other; the owner's ledger orders
     * everything else.
     */
    private var holdRequest: BubbleRequestToken? = null

    /**
     * The request the LAST hold created, kept past its release. The pill drawn for that request is the
     * compact one (just the level rail), and it must stay compact between the finger lifting and the
     * owner hearing the STOP, which is why this is not [holdRequest]. Tokens are unique per request, so
     * a later take, from a tap or from the side button, never matches it.
     */
    private var heldTake: BubbleRequestToken? = null
    /** What the pill's parts were last laid out for; null until the first pill. */
    private var pillCompact: Boolean? = null

    private fun onGesture(gesture: BubbleGesture) {
        when (gesture) {
            BubbleGesture.Nothing -> Unit
            BubbleGesture.Tap -> {
                root.removeCallbacks(holdRunnable)
                startDictation(held = false)
            }
            BubbleGesture.HoldStart -> {
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Only THIS gesture's own request may be released or cancelled by this gesture. A hold
                // on the dimmed bubble during an earlier take mints nothing, so its release cannot stop
                // that take (Codex code review, round 2).
                holdRequest = startDictation(held = true)
                heldTake = holdRequest ?: heldTake
            }
            BubbleGesture.HoldRelease -> {
                // Sent at once, whatever the snapshot shows: the owner's ledger orders it against the
                // start, so a release before capture began still finishes the take (#135 §3).
                holdRequest?.let { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP, it.encode()) }
                holdRequest = null
            }
            BubbleGesture.HoldCancelled -> {
                holdRequest?.let { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL, it.encode()) }
                holdRequest = null
            }
            is BubbleGesture.DragMove -> {
                root.removeCallbacks(holdRunnable)
                val bounds = lastBounds ?: return
                val origin = dragOrigin ?: (currentBubbleBox(bounds) ?: return).also {
                    dragOrigin = it
                    showHideTarget(bounds)
                }
                val size = dp(BUBBLE_DP)
                val left = (origin.left + gesture.dx.toInt()).coerceIn(bounds.usable.left, bounds.usable.right - size)
                val top = (origin.top + gesture.dy.toInt()).coerceIn(bounds.usable.top, bounds.usable.bottom - size)
                dragBox = Box(left, top, left + size, top + size)
                hideTarget.alpha = if (overHideTarget(left, top)) 1f else 0.7f
                render()
            }
            is BubbleGesture.DragEnd -> {
                val bounds = lastBounds
                val box = dragBox
                dragBox = null
                dragOrigin = null
                removeHideTarget()
                if (bounds == null || box == null || gesture.cancelled) {
                    render()
                    return
                }
                if (gesture.overHideTarget) {
                    hiddenForKey = fieldKey ?: HIDDEN_WITHOUT_KEY
                    Toast.makeText(service, "Hidden until your next text box", Toast.LENGTH_SHORT).show()
                    render()
                    return
                }
                val snapped = BubblePlacement.snap(box.left, box.top, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))
                position = snapped
                onPositionChanged?.invoke(snapped)
                render()
            }
        }
    }

    private fun cancelGesture() {
        root.removeCallbacks(holdRunnable)
        onGesture(classifier.cancel())
    }

    private fun currentBubbleBox(bounds: BubbleBounds): Box? =
        dragBox ?: BubblePlacement.bubbleBox(position, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))

    /**
     * Start the take. The service owns the two routes: straight to the session owner, which leaves the
     * keyboard exactly where it is, or through the transparent launcher when Android refuses a
     * foreground start from here. Measured 2026-09-12 on the emulator: launching the activity makes
     * Chrome hide its keyboard, so the direct route is tried first.
     */
    /** Returns the request this gesture created, or null when the owner was not IDLE and nothing was sent. */
    private fun startDictation(held: Boolean): BubbleRequestToken? {
        // Only an IDLE owner takes a new request; a tap while starting or processing does nothing.
        if (snapshot.phase != RecordingOverlayState.Phase.IDLE) return null
        val request = BubbleRequests.mint(held)
        if (!service.startDictationFromBubble(request.encode())) {
            val intent = Intent(service, VoiceInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(VoiceInputActivity.EXTRA_START, true)
                .putExtra(VoiceInputActivity.EXTRA_REQUEST, request.encode())
            runCatching { service.startActivity(intent) }
                .onFailure { error ->
                    Log.w(TAG, "Unable to start dictation from the bubble", error)
                    Toast.makeText(service, "Dictation could not start", Toast.LENGTH_SHORT).show()
                }
        }
        return request
    }

    private fun nowMs(): Long = android.os.SystemClock.uptimeMillis()

    // ---- views ----

    private fun buildRoot(): View {
        // The root listens for configuration changes itself. A rotation must cancel any gesture in
        // flight BEFORE relayout, and must not wait for a tick or an event to re-place the window.
        return object : FrameLayout(service) {
            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                if (!active) return
                cancelGesture()
                render()
            }
        }.apply {
            addView(pillColumn, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(bubble, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)))
        }
    }

    /**
     * The idle lips on a 56 dp touch target. Ground, shadow, lips size and ink edge come from the
     * chosen [BubbleLook] through [applyLook]; nothing here paints. The founder dropped the
     * violet-ringed dark circle on 2026-09-14 for Wispr Flow's lighter shape and then chose to
     * offer three looks rather than one.
     */
    private fun buildBubble(): View {
        return FrameLayout(service).apply {
            contentDescription = "EnviousWispr. Double tap to dictate. Touch and hold to talk. Drag to move."
            isClickable = true
            isFocusable = false
            addView(bubbleMark, FrameLayout.LayoutParams(MATCH, MATCH))
            // The accessibility click action (a TalkBack double tap) arrives here, never through the
            // touch listener below, which consumes every real touch and resolves taps itself. So the
            // two routes cannot fire twice for one gesture (Codex review of the Play branch, round 3).
            setOnClickListener { startDictation(held = false) }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                onTouch(event)
            }
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

        return LinearLayout(service).apply {
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
        val container = LinearLayout(service).apply {
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
            LinearLayout.LayoutParams(0, dp(RAIL_HEIGHT_DP), 1f).apply { marginEnd = dp(10) },
        )
        container.addView(cancelButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        container.addView(acceptButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        pill = container
        applyLook()
        return container
    }

    private lateinit var pill: LinearLayout

    /**
     * Two layouts of the one pill, either of them mirrored. Full: time, rail, cancel, accept, at the
     * bubble's edge. Compact, for a hold: the rail alone in a shorter pill, because the finger is already
     * the control and everything else was noise while it was down (founder 2026-09-13, from Wispr Flow's
     * hold pill). Same height either way, so the pill never jumps between the two.
     *
     * [mirrored] flips the row for a bubble docked on the LEFT, so accept sits at the left edge under
     * the thumb that put the bubble there, rather than across the pill (founder 2026-09-13). The row's
     * layout direction does the flipping, which keeps every margin between the same two neighbours.
     */
    private fun layOutPill(compact: Boolean, mirrored: Boolean) {
        pill.layoutDirection = if (mirrored) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (pillCompact == compact) return
        pillCompact = compact
        val partsVisibility = if (compact) View.GONE else View.VISIBLE
        timer.visibility = partsVisibility
        cancelButton.visibility = partsVisibility
        acceptButton.visibility = partsVisibility
        (meter.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (compact) dp(COMPACT_RAIL_HEIGHT_DP) else dp(RAIL_HEIGHT_DP)
            marginEnd = if (compact) 0 else dp(10)
        }
        meter.layoutParams = meter.layoutParams
        meter.barCount = if (compact) RecordingLevelMeterView.BAR_COUNT else FULL_PILL_BARS
        val vertical = if (compact) dp(COMPACT_PILL_PADDING_DP) else dp(8)
        pill.setPadding(if (compact) dp(16) else dp(10), vertical, if (compact) dp(16) else dp(10), vertical)
        pill.contentDescription = if (compact) "Recording. Let go to finish." else "Recording controls"
    }

    /** "Drop to hide", in its own untouchable window, shown only while a drag is in progress. */
    private fun buildHideTarget(): TextView = TextView(service).apply {
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

    /** The drawn "Drop to hide" rectangle while a drag is in progress; the ONLY place a drop hides. */
    private var hideTargetBox: Box? = null

    private fun overHideTarget(left: Int, top: Int): Boolean {
        val target = hideTargetBox ?: return false
        return BubblePlacement.overHideTarget(left, top, dp(BUBBLE_DP), target, dp(HIDE_SLACK_DP))
    }

    private fun showHideTarget(bounds: BubbleBounds) {
        if (hideTargetAttached) return
        hideTarget.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val box = BubblePlacement.hideTargetBox(bounds, hideTarget.measuredWidth, hideTarget.measuredHeight, dp(HIDE_STRIP_DP))
        hideTargetBox = box
        hideTargetParams.width = box.width
        hideTargetParams.height = box.height
        hideTargetParams.x = box.left
        hideTargetParams.y = box.top
        hideTarget.alpha = 0.7f
        runCatching {
            windowManager.addView(hideTarget, hideTargetParams)
            hideTargetAttached = true
        }.onFailure { error -> Log.w(TAG, "Unable to show the hide target", error) }
    }

    private fun removeHideTarget() {
        hideTargetBox = null
        if (!hideTargetAttached) return
        runCatching { windowManager.removeViewImmediate(hideTarget) }
        hideTargetAttached = false
    }

    /** A round control whose symbol is drawn, not typed: a text glyph sits where its font puts it, not at the centre. */
    private fun actionButton(
        glyph: ActionGlyph,
        accessibilityLabel: String,
        color: Int,
        action: () -> Unit,
    ) = ActionGlyphView(service, glyph).apply {
        contentDescription = accessibilityLabel
        isClickable = true
        isFocusable = false
        background = roundedBackground(color, dp(CONTROL_RADIUS_DP).toFloat())
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

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val TAG = "RecordingOverlay"

        /** Unchanged on purpose: the device harness finds the window by this title. */
        const val WINDOW_TITLE = "EnviousWispr recording controls"

        const val BUBBLE_DP = 56
        /** The bubble's visible ground sits this far inside the 56 dp touch target: a 48 dp square. */
        const val BUBBLE_INSET_DP = 4
        /** The ground's corner, a rounded square rather than a circle. */
        const val BUBBLE_RADIUS_DP = 14
        /** Both pills are fully rounded at their 60 dp height. */
        const val PILL_RADIUS_DP = 30
        /** The 40 dp cancel and accept circles. */
        const val CONTROL_RADIUS_DP = 20
        const val CLOCK_INK_EDGE_DP = 1.5f
        const val MARGIN_DP = 12
        const val PILL_HEIGHT_DP = 60
        const val RAIL_HEIGHT_DP = 22

        /**
         * The tap pill: clock, rail, cancel, accept. The rail gets half the hold pill's reach, so the
         * pill is 232 dp: 166 dp of fixed parts plus a 66 dp rail of [FULL_PILL_BARS] bars, the same
         * bar width as the hold pill's 22 bars in 134 dp (founder 2026-09-13, build 116 phone pass).
         */
        const val FULL_PILL_DP = 232
        const val FULL_PILL_BARS = 11
        /** The hold pill: the rail alone, about the width of the finger's neighbourhood. */
        const val COMPACT_PILL_DP = 168
        /** A taller rail, padded so the compact pill stands exactly [PILL_HEIGHT_DP] tall. */
        const val COMPACT_RAIL_HEIGHT_DP = 28
        const val COMPACT_PILL_PADDING_DP = 16
        const val HIDE_STRIP_DP = 72

        /** How far outside the drawn hide label a drop still counts as on it. */
        const val HIDE_SLACK_DP = 16

        /** The bubble while the owner is starting or processing: present, quiet, not tappable. */
        const val WORKING_ALPHA = 0.55f

        /** A hide with no field key on record: cleared by the next field, whatever it is. */
        val HIDDEN_WITHOUT_KEY = Any()
    }
}
