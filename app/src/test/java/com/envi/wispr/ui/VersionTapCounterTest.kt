package com.envi.wispr.ui

import com.envi.wispr.privacy.PrivacyDisclosures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#378 D1, D5). When this fails, the hidden Developer page unlocks by accident or never, or
 * the Privacy page goes quiet while the founder's words are still in a file on the phone.
 */
class VersionTapCounterTest {
    @Test fun sevenTapsWithinThreeSecondsUnlockAndSixDoNot() {
        var now = 0L
        val counter = VersionTapCounter({ now })
        repeat(6) { assertFalse(counter.tap()); now += 400 }
        assertTrue("the seventh tap unlocks", counter.tap())
    }

    @Test fun slowTapsStartOver() {
        var now = 0L
        val counter = VersionTapCounter({ now })
        repeat(6) { counter.tap(); now += 400 }
        now += 5_000
        assertFalse("a tap after the window starts a new count", counter.tap())
    }

    /** REVERT: make `detailedLogSentenceShown` read only the switch, and the off-with-files row goes red. */
    @Test fun theDetailedLogSentenceShowsWhileOnOrWhileFilesRemain() {
        assertTrue(detailedLogSentenceShown(detailedLogOn = true, retainedFilesExist = false))
        assertTrue(detailedLogSentenceShown(detailedLogOn = false, retainedFilesExist = true))
        assertFalse(detailedLogSentenceShown(detailedLogOn = false, retainedFilesExist = false))
        assertEquals(
            "Detailed log keeps what you dictate in a file on this phone. Turning it off stops new logging; " +
                "earlier logs stay on this phone until later logging overwrites them or the app is uninstalled. " +
                "A copy you shared stays in the app you shared it to.",
            PrivacyDisclosures.DETAILED_LOG,
        )
    }
}
