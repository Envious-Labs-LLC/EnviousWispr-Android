package com.envi.wispr.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Observability contract (issue #176, plan §11.2): the surface a take reports is what the launch
 * named, the side button is read from its action, and a missing or garbage name is `unknown`, never
 * defaulted to a real surface. Expected values are literal wire strings.
 */
class TriggerSourceTest {

    @Test
    fun aMissingOrGarbageExtraIsUnknownNeverASurface() {
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra(null))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra(""))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra("side_button"))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra("unknown"))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra("TILE"))
    }

    @Test
    fun everyRealSurfaceParsesFromItsOwnWireName() {
        assertEquals(TriggerSource.BUBBLE_TAP, TriggerSource.fromExtra("bubble_tap"))
        assertEquals(TriggerSource.BUBBLE_HOLD, TriggerSource.fromExtra("bubble_hold"))
        assertEquals(TriggerSource.ASSIST, TriggerSource.fromExtra("assist"))
        assertEquals(TriggerSource.TILE, TriggerSource.fromExtra("tile"))
        assertEquals(TriggerSource.APP, TriggerSource.fromExtra("app"))
    }

    @Test
    fun theLauncherReadsTheSideButtonFromItsActionAndEveryOtherSurfaceFromItsExtra() {
        // The side button: Android delivers ACTION_ASSIST with no extra of ours.
        assertEquals(TriggerSource.ASSIST, VoiceInputActivity.triggerOf(Intent.ACTION_ASSIST, null))
        // The tile and the app name themselves.
        assertEquals(TriggerSource.TILE, VoiceInputActivity.triggerOf(null, "tile"))
        assertEquals(TriggerSource.APP, VoiceInputActivity.triggerOf(Intent.ACTION_MAIN, "app"))
        // A harness `am start` with neither: unknown, and stays unknown.
        assertEquals(TriggerSource.UNKNOWN, VoiceInputActivity.triggerOf(null, null))
        assertEquals(TriggerSource.UNKNOWN, VoiceInputActivity.triggerOf(Intent.ACTION_VIEW, null))
        // An extra wins over the action, so a surface that launches through the assist path still names itself.
        assertEquals(TriggerSource.APP, VoiceInputActivity.triggerOf(Intent.ACTION_ASSIST, "app"))
        // A garbage extra does not fall back to the action's answer.
        assertEquals(TriggerSource.UNKNOWN, VoiceInputActivity.triggerOf(Intent.ACTION_ASSIST, "garbage"))
    }

    @Test
    fun theWireVocabularyIsPinned() {
        assertEquals(
            listOf("bubble_tap", "bubble_hold", "assist", "tile", "app", "unknown"),
            TriggerSource.entries.map { it.wire },
        )
    }
}
