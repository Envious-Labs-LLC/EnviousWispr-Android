package com.envi.wispr.about

import com.envi.wispr.telemetry.InstallSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome (#38). When this fails, "Check for updates on Google Play" opens some other listing, or a copy
 * Google Play cannot update says nothing about it (or a Play copy is told it might not update). Every expected value
 * is a literal.
 */
class PlayStoreLinkTest {
    @Test fun theLinksNameTheProductionListing() {
        assertEquals("market://details?id=com.envi.wispr", PlayStoreLink.MARKET_URL)
        assertEquals("https://play.google.com/store/apps/details?id=com.envi.wispr", PlayStoreLink.WEB_URL)
        assertEquals("com.android.vending", PlayStoreLink.PLAY_STORE_PACKAGE)
    }

    @Test fun onlyACopyFromElsewhereGetsTheNote() {
        val note = "Your phone does not list Google Play as where this copy came from, so Google Play may not update it."
        val expected = mapOf(
            InstallSource.PLAY to null,
            InstallSource.UNKNOWN to null,
            InstallSource.GALAXY_STORE to note,
            InstallSource.PACKAGE_INSTALLER to note,
            InstallSource.NONE to note,
            InstallSource.OTHER to note,
        )
        assertEquals(InstallSource.entries.toSet(), expected.keys)
        expected.forEach { (source, line) -> assertEquals("note for $source", line, PlayStoreLink.installNote(source)) }
    }
}
