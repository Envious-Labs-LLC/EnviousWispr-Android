package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo's clock: five scenes in the approved order, each read in its own seconds, and a clean end. */
class OnboardingDemoScriptTest {
    @Test fun everySecondFallsInOneSceneInOrder() {
        assertEquals(24f, DemoScript.total)
        assertEquals(DemoMoment(DemoScene.APPS, 0f), DemoScript.at(0f))
        assertEquals(DemoMoment(DemoScene.APPS, 3.9f), DemoScript.at(3.9f))
        assertEquals(DemoMoment(DemoScene.BUBBLE, 0f), DemoScript.at(4f))
        assertEquals(DemoMoment(DemoScene.TAP, 0.5f), DemoScript.at(11.5f))
        assertEquals(DemoMoment(DemoScene.HOLD, 0f), DemoScript.at(16f))
        assertEquals(DemoMoment(DemoScene.YOURS, 1f), DemoScript.at(22f))
        // The clock stops on the last scene's last second; it never wraps to the first.
        assertEquals(DemoMoment(DemoScene.YOURS, 3f), DemoScript.at(24f))
        assertEquals(DemoMoment(DemoScene.YOURS, 3f), DemoScript.at(99f))
    }

    @Test fun theWordsLandAfterThePillHasFolded() {
        // Tap: the pill folds 2.62 to 2.88; hold: 2.55 to 2.82 (the mock's timings).
        assertTrue(DemoScript.TAP_LANDS > 2.88f)
        assertTrue(DemoScript.HOLD_LANDS > 2.82f)
        assertTrue(DemoScript.TAP_LANDS < DemoScene.TAP.seconds)
        assertTrue(DemoScript.HOLD_LANDS < DemoScene.HOLD.seconds)
    }

    @Test fun theBubbleSceneShowsThreeLooksAfterItsThreeCaptions() {
        assertEquals("This is your recording bubble.", DemoScript.bubbleCaption(0f).first)
        assertEquals("It lives on the left or the right side of your screen.", DemoScript.bubbleCaption(3f).first)
        assertEquals("It comes in three looks.", DemoScript.bubbleCaption(5f).first)
        assertEquals(listOf(0, 1, 2), listOf(5f, 5.6f, 6.5f).map(DemoScript::bubbleLookIndex))
    }

    @Test fun aStalledFrameIsNotDemoTimeAndAShortWindowScalesTheScene() {
        val demo = java.io.File("src/main/java/com/envi/wispr/ui/OnboardingDemo.kt").readText()
        // Home and back resumes the demo where it left off: one frame carries at most a tenth of a second.
        assertTrue(demo.contains("coerceAtMost(MAX_FRAME_SECONDS)"))
        assertTrue(demo.contains("private const val MAX_FRAME_SECONDS = 0.1f"))
        // A window too short for the header, the real bubble and a keyboard scales the whole scene down.
        assertTrue(demo.contains("val fit = (maxHeight / GMAIL_MIN_HEIGHT).coerceAtMost(1f)"))
        assertTrue(demo.contains("private fun keyboardHeight(height: Dp): Dp = minOf(KEYBOARD, height * 0.4f)"))
    }

    @Test fun betweenIsClampedAndSmooth() {
        assertEquals(0f, DemoScript.between(0f, 1f, 2f))
        assertEquals(1f, DemoScript.between(3f, 1f, 2f))
        assertEquals(0.5f, DemoScript.between(1.5f, 1f, 2f))
    }
}
