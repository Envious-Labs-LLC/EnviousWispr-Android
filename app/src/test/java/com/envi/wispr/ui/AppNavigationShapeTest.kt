package com.envi.wispr.ui

import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File

/**
 * Drift Guard (#190), counted as such: it pins the drawer's direct derivation from BOTH complete enum entry
 * sets by reading the source as text. A JVM calculation that grouped `SettingsPage.entries` itself would
 * rebuild the expected value with the mechanism under test, so the pin is the literal; the emulator walk of
 * every page is the Product Outcome evidence for the drawer.
 */
class AppNavigationShapeTest {
    private val source = File("src/main/java/com/envi/wispr/ui/AppNavigation.kt").readText()

    @Test fun drawerIsDerivedFromTheCompletePageEnums() {
        assertTrue(
            "the drawer no longer walks every group, so a group can vanish from the menu",
            source.contains("SettingsPageGroup.entries.forEach { group ->"),
        )
        assertTrue(
            "the drawer no longer takes every page of a group unfiltered, so a page can vanish from the menu",
            source.contains("SettingsPage.entries.filter { it.group == group }"),
        )
    }
}
