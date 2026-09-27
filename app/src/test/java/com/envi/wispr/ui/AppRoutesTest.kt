package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome (#190). When this fails the app opens on the wrong tab after an update whose saved tab
 * name no longer exists, or drops the user onto a settings page that no longer exists instead of the tab
 * beneath it. Every expected value is a literal enum member; the inputs are the members' literal names,
 * so nothing here passes through the resolution under test.
 *
 * #378: a saved Developer route while the Developer options are locked resolves to the tab beneath, and
 * after the unlock the page is reachable.
 */
class AppRoutesTest {
    @Test fun anUnknownSavedTabNameLandsOnHistory() {
        assertEquals(AppDestination.History, AppRoutes.destination("Home"))
        assertEquals(AppDestination.History, AppRoutes.destination(""))
        assertEquals(AppDestination.History, AppRoutes.destination("History"))
        assertEquals(AppDestination.Dictionary, AppRoutes.destination("Dictionary"))
        assertEquals(AppDestination.Transcription, AppRoutes.destination("Transcription"))
        assertEquals(AppDestination.Polish, AppRoutes.destination("Polish"))
        // The saved value is the enum NAME, never the label the tab shows.
        assertEquals(AppDestination.History, AppRoutes.destination("AI Polish"))
    }

    @Test fun anUnknownSavedPageNameShowsTheTab() {
        for (unlocked in listOf(false, true)) {
            assertNull(AppRoutes.settingsPage(null, unlocked))
            assertNull(AppRoutes.settingsPage("Home", unlocked))
            assertNull(AppRoutes.settingsPage("", unlocked))
            assertEquals(SettingsPage.WhatsNew, AppRoutes.settingsPage("WhatsNew", unlocked))
            assertEquals(SettingsPage.Appearance, AppRoutes.settingsPage("Appearance", unlocked))
            assertEquals(SettingsPage.Microphone, AppRoutes.settingsPage("Microphone", unlocked))
            assertEquals(SettingsPage.Sounds, AppRoutes.settingsPage("Sounds", unlocked))
            assertEquals(SettingsPage.Clipboard, AppRoutes.settingsPage("Clipboard", unlocked))
            assertEquals(SettingsPage.Permissions, AppRoutes.settingsPage("Permissions", unlocked))
            assertEquals(SettingsPage.Privacy, AppRoutes.settingsPage("Privacy", unlocked))
            assertEquals(SettingsPage.Storage, AppRoutes.settingsPage("Storage", unlocked))
            assertEquals(SettingsPage.Licenses, AppRoutes.settingsPage("Licenses", unlocked))
            // The saved value is the enum NAME, never the title the page shows.
            assertNull(AppRoutes.settingsPage("Open Source Licenses", unlocked))
        }
    }

    /** REVERT: drop `.takeIf { it.visible(developerUnlocked) }` from `AppRoutes.settingsPage`, and the locked row goes red. */
    @Test fun aSavedDeveloperRouteShowsTheTabUntilUnlocked() {
        assertNull(AppRoutes.settingsPage("Developer", developerUnlocked = false))
        assertEquals(SettingsPage.Developer, AppRoutes.settingsPage("Developer", developerUnlocked = true))
    }
}
