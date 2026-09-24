package com.envi.wispr.paste

import com.envi.wispr.shortcuts.BubbleRequestToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#360): what each touch on the lips bubble DOES. When this fails, a tap starts nothing or starts
 * a second take, lifting the finger after a hold stops the wrong take, or a drag leaves the bubble somewhere the user
 * did not put it. Literal coordinates and timestamps: a 10 px slop, a 500 ms hold, a 56 px bubble with a 12 px
 * margin on a 1000 by 2000 screen. Each row names its mutation.
 */
class BubbleGestureControllerTest {
    private val screen = BubbleBounds(Box(0, 0, 1000, 2000), keyboardTop = null)
    private var idle = true
    private var overHide = false
    private var seq = 0L
    private val minted = mutableListOf<BubbleRequestToken>()

    private val controller = BubbleGestureController(
        classifier = BubbleGestureClassifier(slopPx = 10f, holdTimeoutMs = 500L),
        geometry = object : BubbleGestureController.Geometry {
            override val bubblePx = 56
            override val marginPx = 12
            override fun lastBounds(): BubbleBounds? = screen
            override fun restingBox(bounds: BubbleBounds): Box? = Box(932, 400, 988, 456)
            override fun overHideTarget(left: Int, top: Int): Boolean = overHide
            override fun idle(): Boolean = idle
        },
        mint = { held -> BubbleRequestToken("e", ++seq, held).also { minted += it } },
    )

    private fun tap() = controller.down(950f, 420f, 0L) + controller.up(100L)

    /** Row 1: a tap at IDLE starts one tapped take and disarms the hold timer. MUTATION m1: a tap starts nothing. */
    @Test fun aTapAtIdleStartsOneTake() {
        val commands = tap()
        assertEquals(listOf(BubbleRequestToken("e", 1L, held = false)), minted)
        assertEquals(
            listOf(BubbleCommand.ArmHoldTimer, BubbleCommand.DisarmHoldTimer, BubbleCommand.StartDictation(BubbleRequestToken("e", 1L, held = false))),
            commands,
        )
    }

    /** Row 2: a tap while a take is starting or processing mints nothing and starts nothing. MUTATION m2: the IDLE check dropped. */
    @Test fun aTapWhileBusyStartsNothing() {
        idle = false
        val commands = tap()
        assertTrue(minted.isEmpty())
        assertTrue(commands.none { it is BubbleCommand.StartDictation })
    }

    /** Row 3: a hold starts a held take with a haptic, and lifting the finger stops THAT take. MUTATION m3: the release sends nothing. */
    @Test fun aHoldStartsAHeldTakeAndItsReleaseStopsIt() {
        controller.down(950f, 420f, 0L)
        val held = controller.holdTimeout(500L)
        val request = BubbleRequestToken("e", 1L, held = true)
        assertEquals(listOf(BubbleCommand.HoldHaptic, BubbleCommand.StartDictation(request)), held)
        assertTrue("its pill is the compact one", controller.isHeldTake(request))
        assertEquals(listOf(BubbleCommand.Stop(request)), controller.up(900L))
        assertTrue("still compact until the owner hears the stop", controller.isHeldTake(request))
    }

    /**
     * Row 4: a hold on the dimmed bubble during an earlier take mints nothing, so its release cannot stop that take.
     * MUTATION m4: the release stops the last held take instead of this hold's own.
     */
    @Test fun aHoldDuringAnotherTakeCannotStopIt() {
        controller.down(950f, 420f, 0L)
        controller.holdTimeout(500L)
        val first = BubbleRequestToken("e", 1L, held = true)
        controller.up(900L)
        idle = false
        controller.down(950f, 420f, 1_000L)
        assertEquals(listOf(BubbleCommand.HoldHaptic), controller.holdTimeout(1_500L))
        assertEquals("the second release sends nothing", emptyList<BubbleCommand>(), controller.up(1_900L))
        assertTrue("the earlier held take's pill stays compact", controller.isHeldTake(first))
    }

    /** Row 5: a cancelled hold cancels its own take. */
    @Test fun aCancelledHoldCancelsItsTake() {
        controller.down(950f, 420f, 0L)
        controller.holdTimeout(500L)
        val request = BubbleRequestToken("e", 1L, held = true)
        assertEquals(listOf(BubbleCommand.DisarmHoldTimer, BubbleCommand.Cancel(request)), controller.cancel())
    }

    /**
     * Row 6: a drag moves the bubble with the finger, clamped to the usable screen, shows the hide target once, and a
     * drop snaps it to a side. MUTATION m5: the drag is not clamped.
     */
    @Test fun aDragFollowsTheFingerWithinTheScreenAndSnapsOnDrop() {
        controller.down(950f, 420f, 0L)
        val first = controller.move(700f, 820f, 50L)
        assertEquals(BubbleCommand.DisarmHoldTimer, first.first())
        assertTrue(first.contains(BubbleCommand.ShowHideTarget(screen)))
        assertEquals("from the resting box by the finger's travel", Box(682, 800, 738, 856), controller.dragBox)
        val second = controller.move(-400f, 820f, 80L)
        assertFalse("the hide target is shown once per drag", second.any { it is BubbleCommand.ShowHideTarget })
        assertEquals("clamped to the left edge", Box(0, 800, 56, 856), controller.dragBox)
        val drop = controller.up(120L)
        assertEquals(BubbleCommand.RemoveHideTarget, drop.first())
        assertEquals(BubbleCommand.Snap(BubblePlacement.snap(0, 800, screen, 56, 12)), drop[1])
        assertEquals(BubbleCommand.Render, drop.last())
        assertNull("the drag is over", controller.dragBox)
        assertTrue("a drag starts no take", minted.isEmpty())
    }

    /** Row 7: a drop on "Drop to hide" hides the bubble for this field and does not move it. MUTATION m6: a drop over the target snaps instead. */
    @Test fun aDropOnTheHideTargetHidesForTheField() {
        controller.down(950f, 420f, 0L)
        controller.move(700f, 1900f, 50L)
        overHide = true
        val over = controller.move(700f, 1950f, 60L)
        assertTrue(over.contains(BubbleCommand.HideTargetEmphasis(true)))
        assertEquals(listOf(BubbleCommand.RemoveHideTarget, BubbleCommand.HideForField, BubbleCommand.Render), controller.up(90L))
    }

    /** Row 8: the accessibility double tap starts a tapped take at IDLE, through the same IDLE rule. */
    @Test fun theAccessibilityTapStartsATappedTake() {
        assertEquals(listOf(BubbleCommand.StartDictation(BubbleRequestToken("e", 1L, held = false))), controller.accessibilityTap())
        idle = false
        assertEquals(emptyList<BubbleCommand>(), controller.accessibilityTap())
    }
}
