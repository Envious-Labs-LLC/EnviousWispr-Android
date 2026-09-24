package com.envi.wispr.paste

import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.envi.wispr.debug.DebugLogger
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
 * [AccessibilityBubbleHost] decides when the bubble shows and supplies its position, look, earbuds and
 * keyboard bounds; [EditorTargetTracker] answers which editor is focused (#217). This overlay does not read
 * the node tree; the direct start still goes through the [PasteAccessibilityService] it is built with. Everything that decides what a touch
 * MEANT is [BubbleGestureClassifier]; everything that decides WHERE a shape goes is [BubblePlacement].
 * This class wires them to Android.
 */
internal class RecordingAccessibilityOverlay(
    private val service: PasteAccessibilityService,
) : RecordingOverlayState.Listener {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    /** The views, built and painted (#360); every touch and click comes back here. */
    private val views = BubbleViews(
        context = service,
        onBubbleTouch = ::onTouch,
        onBubbleClick = { perform(gestures.accessibilityTap()) },
        onCancel = { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL) },
        onAccept = { DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP) },
        onConfigurationChanged = {
            // A rotation cancels any gesture in flight BEFORE relayout, and re-places the window at once.
            if (active) {
                cancelGesture()
                render()
            }
        },
    )
    private val timer get() = views.timer
    private val meter get() = views.meter
    private val notice get() = views.notice
    private val bubbleMark get() = views.bubbleMark
    private val bubble get() = views.bubble
    private val pillColumn get() = views.pillColumn
    private val root get() = views.root
    private val hideTarget get() = views.hideTarget
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

    /** What each gesture does (#360): which request starts, which stop goes with which hold, where a drag lands. */
    private val gestures = BubbleGestureController(
        classifier = BubbleGestureClassifier(
            slopPx = ViewConfiguration.get(service).scaledTouchSlop.toFloat(),
            holdTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
        ),
        geometry = object : BubbleGestureController.Geometry {
            override val bubblePx: Int get() = dp(BUBBLE_DP)
            override val marginPx: Int get() = dp(MARGIN_DP)
            override fun lastBounds(): BubbleBounds? = lastBounds
            override fun restingBox(bounds: BubbleBounds): Box? = BubblePlacement.bubbleBox(position, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))
            override fun overHideTarget(left: Int, top: Int): Boolean = this@RecordingAccessibilityOverlay.overHideTarget(left, top)
            // Only an IDLE owner takes a new request; a tap while starting or processing does nothing.
            override fun idle(): Boolean = snapshot.phase == RecordingOverlayState.Phase.IDLE
        },
        mint = BubbleRequests::mint,
    )
    private val holdRunnable = Runnable { perform(gestures.holdTimeout(nowMs())) }

    private var attached = false
    private var hideTargetAttached = false
    private var active = false
    private var snapshot = RecordingOverlayState.Snapshot()
    /** What the slow half of the recorder was last set to. -1 and null mean it is not shown. */
    private var lastElapsedSeconds = -1
    private var lastNotice: String? = null

    /** The bubble host's word on whether another app's editable field is focused, and which one. */
    private var fieldActive = false
    private var fieldKey: Any? = null
    /** Set when the user drops the bubble on the hide target; cleared by a DIFFERENT field key. */
    private var hiddenForKey: Any? = null
    private var keyboardTop: Int? = null
    private var position = BubblePosition.DEFAULT
    /** The bubble's box while a drag is in progress, in screen pixels; null otherwise. */
    private val dragBox: Box? get() = gestures.dragBox
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

    // ---- what the bubble host tells the overlay ----

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

    /** The persisted position, loaded by the bubble host off the main thread. */
    fun setPosition(loaded: BubblePosition) {
        if (position == loaded) return
        position = loaded
        render()
    }

    /** What the bubble host persists after a drag. Set by the host so the store stays out of this class. */
    var onPositionChanged: ((BubblePosition) -> Unit)? = null

    /** The look the user chose in Settings > Appearance, delivered by the bubble host on the main thread. */
    fun setLook(look: BubbleLook) = views.setLook(look)

    /**
     * Whether the earbuds are the chosen microphone right now, delivered by the bubble host on the main
     * thread whenever the pick or the connected inputs change (#171). The lips and the rail take the
     * earbud rainbow, and the bubble's spoken label says so; anything else (the phone, a wired or USB
     * headset, nothing known) is the brand rainbow and the plain label. Idempotent.
     */
    fun setEarbuds(earbuds: Boolean) = views.setEarbuds(earbuds)


    // ---- what the session owner tells the overlay ----

    override fun onChanged(snapshot: RecordingOverlayState.Snapshot) {
        if (!active) return
        val previous = this.snapshot
        this.snapshot = snapshot
        if (!snapshot.visible) {
            lastElapsedSeconds = -1
            lastNotice = null
            if (previous.visible != snapshot.visible || previous.phase != snapshot.phase) render()
            return
        }
        // A new take starts at rest, not at the last picture of the previous one.
        if (!previous.visible) meter.reset()
        // The rail is the only thing that moves at speaking rate. It redraws itself and touches
        // nothing else, so it is handled before the early return below. Every delivery hands it the
        // latest picture, equal pictures included: the rail eases toward what it is given, and a
        // silent picture is what lets it settle to rest.
        meter.setBands(snapshot.bands)

        // Everything past here changes about once a second at most, and one part of it reads the
        // window metrics, which is framework work on the main thread. Doing it on every picture
        // would run it thirty times a second to write the same string back.
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
                DebugLogger.error(TAG, "Unable to read window bounds", error)
                return
            }
        lastBounds = bounds
        when {
            snapshot.visible -> {
                val bubbleBox = BubblePlacement.bubbleBox(position, bounds, dp(BUBBLE_DP), dp(MARGIN_DP))
                    ?: Box(bounds.usable.right - dp(MARGIN_DP) - dp(BUBBLE_DP), bounds.usable.top + dp(MARGIN_DP), bounds.usable.right - dp(MARGIN_DP), bounds.usable.top + dp(MARGIN_DP) + dp(BUBBLE_DP))
                // Measure the whole column, notice line included, so a warning that grows it is
                // placed above the keyboard rather than hanging over the keys (Play-branch review).
                val compact = gestures.isHeldTake(snapshot.requestToken)
                views.layOutPill(compact, mirrored = position.side == BubbleSide.LEFT)
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
            }.onFailure { error -> DebugLogger.error(TAG, "Unable to show the floating window", error) }
        } else if (!unchanged) {
            // Never update a window whose bounds did not change: the update itself is a windows
            // change, and the service must not hear about a move it did not make.
            runCatching { windowManager.updateViewLayout(root, layoutParams) }
                .onFailure { error -> DebugLogger.error(TAG, "Unable to move the floating window", error) }
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
     * inset here; the bubble host supplies its top through [keyboardBounds], using the accessibility
     * windows list to find a docked keyboard.
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
            MotionEvent.ACTION_DOWN -> perform(gestures.down(event.rawX, event.rawY, nowMs()))
            MotionEvent.ACTION_MOVE -> perform(gestures.move(event.rawX, event.rawY, nowMs()))
            MotionEvent.ACTION_UP -> perform(gestures.up(nowMs()))
            MotionEvent.ACTION_CANCEL -> perform(gestures.cancel())
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionIndex == 0 && event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    perform(gestures.up(nowMs()))
                }
            }
        }
        return true
    }

    /** Applies what a gesture decided, in order; the only place a gesture reaches Android. */
    private fun perform(commands: List<BubbleCommand>) {
        for (command in commands) {
            when (command) {
                is BubbleCommand.StartDictation -> launch(command.request)
                BubbleCommand.HoldHaptic -> bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Sent at once, whatever the snapshot shows: the owner's ledger orders it against the
                // start, so a release before capture began still finishes the take (#135 §3).
                is BubbleCommand.Stop -> DictationSessionService.sendCommand(service, DictationSessionService.ACTION_STOP, command.request.encode())
                is BubbleCommand.Cancel -> DictationSessionService.sendCommand(service, DictationSessionService.ACTION_CANCEL, command.request.encode())
                BubbleCommand.ArmHoldTimer -> {
                    root.removeCallbacks(holdRunnable)
                    root.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                BubbleCommand.DisarmHoldTimer -> root.removeCallbacks(holdRunnable)
                is BubbleCommand.ShowHideTarget -> showHideTarget(command.bounds)
                BubbleCommand.RemoveHideTarget -> removeHideTarget()
                is BubbleCommand.HideTargetEmphasis -> hideTarget.alpha = if (overHideTarget(command.left, command.top)) 1f else 0.7f
                BubbleCommand.HideForField -> {
                    hiddenForKey = fieldKey ?: HIDDEN_WITHOUT_KEY
                    Toast.makeText(service, "Hidden until your next text box", Toast.LENGTH_SHORT).show()
                }
                is BubbleCommand.Snap -> {
                    position = command.position
                    onPositionChanged?.invoke(command.position)
                }
                BubbleCommand.Render -> render()
            }
        }
    }

    private fun cancelGesture() = perform(gestures.cancel())

    /**
     * Start the take by asking the service to start the session owner directly, which leaves the
     * keyboard in place. If that fails, this overlay launches the transparent activity using the service
     * as its context. On the emulator, launching the activity makes Chrome hide its keyboard, so the
     * direct route is tried first (measured 2026-09-12).
     */
    private fun launch(request: BubbleRequestToken) {
        if (!service.startDictationFromBubble(request.encode())) {
            val intent = Intent(service, VoiceInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(VoiceInputActivity.EXTRA_START, true)
                .putExtra(VoiceInputActivity.EXTRA_REQUEST, request.encode())
            runCatching { service.startActivity(intent) }
                .onFailure { error ->
                    DebugLogger.error(TAG, "Unable to start dictation from the bubble", error)
                    Toast.makeText(service, "Dictation could not start", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun nowMs(): Long = android.os.SystemClock.uptimeMillis()







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
        }.onFailure { error -> DebugLogger.error(TAG, "Unable to show the hide target", error) }
    }

    private fun removeHideTarget() {
        hideTargetBox = null
        if (!hideTargetAttached) return
        runCatching { windowManager.removeViewImmediate(hideTarget) }
        hideTargetAttached = false
    }



    private fun dp(value: Int): Int = (value * density).toInt()

    /** Sizes are read by the setup demo (`ui/OnboardingDemo.kt`), so it draws the bubble and the pill at their real size. */
    internal companion object {
        const val TAG = "RecordingOverlay"

        /** Unchanged on purpose: the device harness finds the window by this title. */
        const val WINDOW_TITLE = "EnviousWispr recording controls"

        /** The idle bubble's spoken label, and the same label naming the earbuds while they are the microphone. */
        const val BUBBLE_LABEL = "EnviousWispr. Double tap to dictate. Touch and hold to talk. Drag to move."
        const val BUBBLE_LABEL_EARBUDS = "EnviousWispr, using your earbuds. Double tap to dictate. Touch and hold to talk. Drag to move."

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
        /**
         * The hold pill: 16 dp padding, the 134 dp rail of [RecordingLevelMeterView.BAR_COUNT] bars at the
         * tap pill's bar width, a 10 dp gap, then the [RECORD_MARK_DP] mark and 16 dp padding, which
         * together are the [BUBBLE_DP] footprint of the thumb holding the bubble.
         */
        const val COMPACT_PILL_DP = 216
        const val RECORD_MARK_DP = 40
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
