package com.envi.wispr.paste

import com.envi.wispr.shortcuts.BubbleRequestToken

/** One thing a bubble gesture asks the overlay to do (#360), in the order it must be done. */
internal sealed class BubbleCommand {
    /** Start a take for this request, minted only while the owner was IDLE. */
    data class StartDictation(val request: BubbleRequestToken) : BubbleCommand()
    object HoldHaptic : BubbleCommand()
    /** Stop the take THIS hold started, never another. */
    data class Stop(val request: BubbleRequestToken) : BubbleCommand()
    data class Cancel(val request: BubbleRequestToken) : BubbleCommand()
    object ArmHoldTimer : BubbleCommand()
    object DisarmHoldTimer : BubbleCommand()
    data class ShowHideTarget(val bounds: BubbleBounds) : BubbleCommand()
    object RemoveHideTarget : BubbleCommand()
    /**
     * Light the hide target when the bubble at ([left], [top]) is over it. Judged when applied, after a
     * [ShowHideTarget] earlier in the same list has placed the target (review round 1).
     */
    data class HideTargetEmphasis(val left: Int, val top: Int) : BubbleCommand()
    /** The bubble was dropped on "Drop to hide": hidden until the next text box. */
    object HideForField : BubbleCommand()
    data class Snap(val position: BubblePosition) : BubbleCommand()
    object Render : BubbleCommand()
}

/**
 * What each bubble gesture DOES (#360). [BubbleGestureClassifier] decides what a touch meant; this decides which
 * request a tap or hold starts, which stop or cancel belongs to which hold, where a drag puts the bubble and where a
 * drop leaves it, and hands the overlay a list of [BubbleCommand]s to apply. Pure Kotlin over [Geometry], so every
 * rule runs in a JVM test with literal coordinates and timestamps.
 */
internal class BubbleGestureController(
    private val classifier: BubbleGestureClassifier,
    private val geometry: Geometry,
    private val mint: (held: Boolean) -> BubbleRequestToken,
) {
    /** What the overlay knows about the screen and the owner, read at the moment a gesture needs it. */
    interface Geometry {
        val bubblePx: Int
        val marginPx: Int
        /** The usable screen as the overlay last placed a window in it; null before the first placement. */
        fun lastBounds(): BubbleBounds?
        /** Where the bubble rests at its saved position. */
        fun restingBox(bounds: BubbleBounds): Box?
        fun overHideTarget(left: Int, top: Int): Boolean
        fun idle(): Boolean
    }

    /** The bubble's box while a drag is in progress, in screen pixels; null otherwise. */
    var dragBox: Box? = null
        private set
    private var dragOrigin: Box? = null

    /** Where the primary finger went down, in screen pixels; a drag is measured from here. */
    private var downX = 0f
    private var downY = 0f

    /**
     * The request the hold in progress created, or null when that hold created none (the owner was not IDLE). Its
     * release or cancel goes to this request and no other; the owner's ledger orders everything else.
     */
    private var holdRequest: BubbleRequestToken? = null

    /**
     * The request the LAST hold created, kept past its release. The pill drawn for that request is the compact one,
     * and it must stay compact between the finger lifting and the owner hearing the STOP, which is why this is not
     * [holdRequest]. Tokens are unique per request, so a later take never matches it.
     */
    private var heldTake: BubbleRequestToken? = null

    /** Whether [request] is the one the last hold started: its pill is the compact one. */
    fun isHeldTake(request: BubbleRequestToken?): Boolean = request != null && request == heldTake

    fun down(x: Float, y: Float, atMs: Long): List<BubbleCommand> {
        downX = x
        downY = y
        return apply(classifier.down(x, y, atMs)) + BubbleCommand.ArmHoldTimer
    }

    fun move(x: Float, y: Float, atMs: Long): List<BubbleCommand> {
        val bounds = geometry.lastBounds() ?: return emptyList()
        val origin = dragOrigin ?: currentBox(bounds) ?: return emptyList()
        val left = origin.left + (x - downX).toInt()
        val top = origin.top + (y - downY).toInt()
        return apply(classifier.move(x, y, atMs, geometry.overHideTarget(left, top)))
    }

    fun up(atMs: Long): List<BubbleCommand> = apply(classifier.up(atMs))

    fun holdTimeout(atMs: Long): List<BubbleCommand> = apply(classifier.holdTimeout(atMs))

    /** A cancelled touch, a rotation or the overlay stopping: the gesture in flight ends as cancelled. */
    fun cancel(): List<BubbleCommand> = listOf(BubbleCommand.DisarmHoldTimer) + apply(classifier.cancel())

    /**
     * The accessibility click (a TalkBack double tap). It never arrives through the touch path, which consumes every
     * real touch and resolves taps itself, so the two routes cannot fire twice for one gesture.
     */
    fun accessibilityTap(): List<BubbleCommand> = start(held = false).second

    private fun apply(gesture: BubbleGesture): List<BubbleCommand> = when (gesture) {
        BubbleGesture.Nothing -> emptyList()
        BubbleGesture.Tap -> listOf(BubbleCommand.DisarmHoldTimer) + start(held = false).second
        BubbleGesture.HoldStart -> {
            // Only THIS gesture's own request may be released or cancelled by this gesture. A hold on the dimmed
            // bubble during an earlier take mints nothing, so its release cannot stop that take.
            val (request, started) = start(held = true)
            holdRequest = request
            heldTake = request ?: heldTake
            listOf(BubbleCommand.HoldHaptic) + started
        }
        BubbleGesture.HoldRelease -> listOfNotNull(holdRequest?.let { BubbleCommand.Stop(it) }).also { holdRequest = null }
        BubbleGesture.HoldCancelled -> listOfNotNull(holdRequest?.let { BubbleCommand.Cancel(it) }).also { holdRequest = null }
        is BubbleGesture.DragMove -> dragTo(gesture)
        is BubbleGesture.DragEnd -> drop(gesture)
    }

    private fun start(held: Boolean): Pair<BubbleRequestToken?, List<BubbleCommand>> {
        if (!geometry.idle()) return null to emptyList()
        val request = mint(held)
        return request to listOf(BubbleCommand.StartDictation(request))
    }

    private fun dragTo(gesture: BubbleGesture.DragMove): List<BubbleCommand> {
        val commands = mutableListOf<BubbleCommand>(BubbleCommand.DisarmHoldTimer)
        val bounds = geometry.lastBounds() ?: return commands
        val origin = dragOrigin ?: (currentBox(bounds) ?: return commands).also {
            dragOrigin = it
            commands += BubbleCommand.ShowHideTarget(bounds)
        }
        val size = geometry.bubblePx
        val left = (origin.left + gesture.dx.toInt()).coerceIn(bounds.usable.left, bounds.usable.right - size)
        val top = (origin.top + gesture.dy.toInt()).coerceIn(bounds.usable.top, bounds.usable.bottom - size)
        dragBox = Box(left, top, left + size, top + size)
        commands += BubbleCommand.HideTargetEmphasis(left, top)
        commands += BubbleCommand.Render
        return commands
    }

    private fun drop(gesture: BubbleGesture.DragEnd): List<BubbleCommand> {
        val bounds = geometry.lastBounds()
        val box = dragBox
        dragBox = null
        dragOrigin = null
        val commands = mutableListOf<BubbleCommand>(BubbleCommand.RemoveHideTarget)
        when {
            bounds == null || box == null || gesture.cancelled -> Unit
            gesture.overHideTarget -> commands += BubbleCommand.HideForField
            else -> commands += BubbleCommand.Snap(BubblePlacement.snap(box.left, box.top, bounds, geometry.bubblePx, geometry.marginPx))
        }
        commands += BubbleCommand.Render
        return commands
    }

    private fun currentBox(bounds: BubbleBounds): Box? = dragBox ?: geometry.restingBox(bounds)
}
