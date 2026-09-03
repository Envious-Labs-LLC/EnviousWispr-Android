package com.envi.wispr.cleanup

import com.envi.wispr.ui.FILLER_TOGGLE_SUBTITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. When this fails the settings screen promises to remove a word the app keeps, so the
 * user turns the setting on and does not get what it said. Added after review caught exactly that drift:
 * `um` left the filler set and the subtitle went on naming it (#36).
 */
class FillerCopyTest {
    @Test fun everyWordTheSettingNamesIsAWordCleanupActuallyRemoves() {
        val named = Regex("such as (.+)\\.").find(FILLER_TOGGLE_SUBTITLE)
            ?.groupValues?.get(1)
            ?.split(",", " and ")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        assertTrue("The subtitle names no example words: $FILLER_TOGGLE_SUBTITLE", named.isNotEmpty())

        named.forEach { word ->
            val cleaned = DeterministicCleanup.apply("$word hello there", CleanupOptions()).text
            assertEquals("The setting names \"$word\" but cleanup keeps it", "hello there", cleaned)
        }
    }
}
