package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome (#190). When this fails the app opens on the wrong tab after an update whose saved tab
 * name no longer exists, or drops the user onto a settings page that no longer exists instead of the tab
 * beneath it. Every expected value is a literal enum member; the inputs are the members' literal names,
 * so nothing here passes through the resolution under test.
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
        assertNull(AppRoutes.settingsPage(null))
        assertNull(AppRoutes.settingsPage("Home"))
        assertNull(AppRoutes.settingsPage(""))
        assertEquals(SettingsPage.WhatsNew, AppRoutes.settingsPage("WhatsNew"))
        assertEquals(SettingsPage.Appearance, AppRoutes.settingsPage("Appearance"))
        assertEquals(SettingsPage.Microphone, AppRoutes.settingsPage("Microphone"))
        assertEquals(SettingsPage.Sounds, AppRoutes.settingsPage("Sounds"))
        assertEquals(SettingsPage.Clipboard, AppRoutes.settingsPage("Clipboard"))
        assertEquals(SettingsPage.Permissions, AppRoutes.settingsPage("Permissions"))
        assertEquals(SettingsPage.Privacy, AppRoutes.settingsPage("Privacy"))
        assertEquals(SettingsPage.Storage, AppRoutes.settingsPage("Storage"))
        assertEquals(SettingsPage.Licenses, AppRoutes.settingsPage("Licenses"))
        // The saved value is the enum NAME, never the title the page shows.
        assertNull(AppRoutes.settingsPage("Open Source Licenses"))
    }
}
