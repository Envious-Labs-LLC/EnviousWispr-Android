package com.envi.wispr.paste

import com.envi.wispr.paste.RefocusGuards.Guard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#201): when one of these fails, a focus action is sent to the wrong place (another app
 * on top, a field that is not there) or the words are refocused when no sibling holds focus.
 */
class RefocusGuardsTest {
    /** Every guard passes unless [failing] names it; [asked] records the order guards were evaluated in. */
    private fun run(failing: Guard?, asked: MutableList<Guard>): Guard? = RefocusGuards.firstRefusal(
        windowFocused = { asked += Guard.WINDOW_FOCUSED; failing != Guard.WINDOW_FOCUSED },
        windowFound = { asked += Guard.WINDOW_FOUND; failing != Guard.WINDOW_FOUND },
        pinRefreshed = { asked += Guard.PIN_REFRESHED; failing != Guard.PIN_REFRESHED },
        pinUsable = { asked += Guard.PIN_USABLE; failing != Guard.PIN_USABLE },
        advertisesFocus = { asked += Guard.ADVERTISES_FOCUS; failing != Guard.ADVERTISES_FOCUS },
        siblingHoldsFocus = { asked += Guard.SIBLING_HOLDS_FOCUS; failing != Guard.SIBLING_HOLDS_FOCUS },
    )

    @Test fun whenEveryGuardPassesNothingRefuses() {
        val asked = mutableListOf<Guard>()
        assertNull(run(null, asked))
        assertEquals(Guard.entries.toList(), asked)
    }

    @Test fun eachGuardCanRefuseAndNamesItself() {
        for (guard in Guard.entries) {
            assertEquals("refusal for $guard", guard, run(guard, mutableListOf()))
        }
    }

    @Test fun theFirstRefusalStopsTheRestSoNoLaterNodeIsReadOrTouched() {
        for ((index, guard) in Guard.entries.withIndex()) {
            val asked = mutableListOf<Guard>()
            run(guard, asked)
            assertEquals("guards run after $guard must not be asked", Guard.entries.take(index + 1), asked)
        }
    }

    @Test fun theWindowCheckIsTheVeryFirstSoAnotherAppOnTopIsNeverTouched() {
        val asked = mutableListOf<Guard>()
        assertEquals(Guard.WINDOW_FOCUSED, run(Guard.WINDOW_FOCUSED, asked))
        assertEquals(listOf(Guard.WINDOW_FOCUSED), asked)
    }

    @Test fun theTrackerFeedsTheRealFocusedWindowCheckIntoTheFirstGuard() {
        // Comments stripped, so a commented-out call cannot satisfy this. The pure rows above prove the
        // guard order; this row proves the tracker still supplies `isInFocusedWindow` as that first guard
        // and sends `ACTION_FOCUS` only when no guard refused.
        val source = (File("src/main/java/com/envi/wispr/paste/EditorTargetTracker.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/envi/wispr/paste/EditorTargetTracker.kt")).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val refocus = source.substringAfter("fun refocusPinnedNode(): RefocusResult {")
        assertTrue(
            "windowFocused must be the real focused-window check",
            Regex("""windowFocused\s*=\s*\{\s*isInFocusedWindow\(expected\.windowId\)\s*\}""").containsMatchIn(refocus),
        )
        assertTrue(
            "a refusal must return DECLINED before any action is sent",
            Regex("""if \(refusal != null\) return RefocusResult\.DECLINED""").containsMatchIn(refocus),
        )
        val refusalCheck = refocus.indexOf("if (refusal != null) return RefocusResult.DECLINED")
        val action = refocus.indexOf("performAction(AccessibilityNodeInfo.ACTION_FOCUS)")
        assertTrue("the focus action must come after the guards", refusalCheck in 0 until action)
    }
}
