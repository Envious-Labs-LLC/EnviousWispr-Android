package com.envi.wispr.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift guard (#37): the stored keys never change (a renamed key silently resets every user to the default), the
 * default is Never (founder 2026-10-21, as on macOS), and the labels are the macOS ones.
 */
class SpeechModelUnloadTest {
    @Test fun storedKeysLabelsAndTimesArePinned() {
        assertEquals(
            listOf(
                Triple("never", "Never", null),
                Triple("immediately", "Immediately", null),
                Triple("two_minutes", "After 2 minutes", 120_000L),
                Triple("five_minutes", "After 5 minutes", 300_000L),
                Triple("ten_minutes", "After 10 minutes", 600_000L),
                Triple("fifteen_minutes", "After 15 minutes", 900_000L),
                Triple("sixty_minutes", "After 1 hour", 3_600_000L),
            ),
            SpeechModelUnload.entries.map { Triple(it.storageKey, it.title, it.holdMillis) },
        )
    }

    @Test fun theDefaultIsNeverAndUnknownValuesFallBackToIt() {
        assertEquals(SpeechModelUnload.NEVER, SpeechModelUnload.DEFAULT)
        assertEquals(SpeechModelUnload.NEVER, AppPreferencesState().speechModelUnload)
        listOf(null, "", "5m", "FIVE_MINUTES").forEach { assertEquals(it.toString(), SpeechModelUnload.NEVER, SpeechModelUnload.fromStorage(it)) }
        assertEquals(SpeechModelUnload.FIVE_MINUTES, SpeechModelUnload.fromStorage("five_minutes"))
    }
}
