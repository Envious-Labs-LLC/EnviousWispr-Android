package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLookTest {
    @Test
    fun everyStoredKeyRoundTripsAndAnythingElseFallsToTheDefault() {
        BubbleLook.entries.forEach { look -> assertEquals(look, BubbleLook.fromStorage(look.storageKey)) }
        listOf(null, "", "cushion", "SMOKE", " smoke").forEach { key ->
            assertEquals("'$key' must fall to the default", BubbleLook.DEFAULT, BubbleLook.fromStorage(key))
        }
    }

    @Test
    fun theKeysAreDistinctAndTheDefaultReadsOnEveryGround() {
        assertEquals(BubbleLook.entries.size, BubbleLook.entries.map { it.storageKey }.toSet().size)
        // The default is the one look with a dark ground, the one that reads on a white page, a dark
        // page and a photo alike (Codex round 1, 2026-09-14).
        assertEquals(BubbleLook.SMOKE, BubbleLook.DEFAULT)
        assertTrue(BubbleLook.DEFAULT.surfaceFill ushr 24 > 0)
    }

    @Test
    fun theControlsAreSeeThroughInEveryLook() {
        // Founder 2026-09-14: "the checkmark and x buttons need to also be transparent".
        BubbleLook.entries.forEach { look ->
            assertTrue("$look cancel", look.cancelFill ushr 24 in 1..254)
            assertTrue("$look accept", look.acceptFill ushr 24 in 1..254)
        }
    }

    @Test
    fun bareHasNoGroundAndNoShadowSoTheLipsFloat() {
        assertEquals(0, BubbleLook.BARE.surfaceFill ushr 24)
        assertEquals(0, BubbleLook.BARE.surfaceElevationDp)
        assertTrue("the lips need their ink edge with nothing under them", BubbleLook.BARE.inkEdgeDp > 0f)
    }
}
