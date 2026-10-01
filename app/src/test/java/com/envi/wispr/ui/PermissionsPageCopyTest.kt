package com.envi.wispr.ui

import com.envi.wispr.paste.AutoPasteAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME. When this fails, the Permissions page tells a user whose auto-paste Android switched
 * off to finish first-time setup, or claims a cause it cannot know (#131). Literals, so the founder's
 * approved wording (Gate 2, 2026-10-01) is the oracle rather than the code that renders it.
 */
class PermissionsPageCopyTest {

    @Test
    fun eachAutoPasteStateHasItsApprovedCardAndRow() {
        val cards = mapOf(
            AutoPasteAvailability.LIVE to null,
            AutoPasteAvailability.NOT_PERMITTED to null,
            AutoPasteAvailability.PERMITTED_NOT_RUNNING to AutoPasteNotice(
                "Auto-paste is not connected",
                "Your words will not go into the field until it reconnects. If it stays disconnected, " +
                    "turn EnviousWispr off and then on in Accessibility settings.",
            ),
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY to AutoPasteNotice(
                "Auto-paste was switched off",
                "This can happen when the app is stopped. Turn EnviousWispr back on in Accessibility settings.",
            ),
        )
        assertEquals(AutoPasteAvailability.entries.toSet(), cards.keys)
        cards.forEach { (autoPaste, card) -> assertEquals("card for $autoPaste", card, autoPasteNotice(autoPaste)) }

        val rows = mapOf(
            AutoPasteAvailability.LIVE to "Ready for side-button dictation",
            AutoPasteAvailability.NOT_PERMITTED to "Needs accessibility permission",
            AutoPasteAvailability.PERMITTED_NOT_RUNNING to
                "Turned on but not connected. Words will not go into the field until it reconnects.",
            AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY to "Switched off. Turn it back on in Accessibility settings.",
        )
        assertEquals(AutoPasteAvailability.entries.toSet(), rows.keys)
        rows.forEach { (autoPaste, row) -> assertEquals("row for $autoPaste", row, autoPasteRowSubtitle(autoPaste)) }
        assertEquals("Needs attention", AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY.statusDescription())
    }
}
