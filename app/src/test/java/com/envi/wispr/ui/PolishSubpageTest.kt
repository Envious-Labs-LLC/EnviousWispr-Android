package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Drift Guard: when this fails, a rotation on the setup page drops the user back to the tab, or a stale saved string throws. */
class PolishSubpageTest {
    @Test fun everyPageRoundTrips() {
        val pages = Provider.entries.map { PolishSubpage.ProviderSetup(it) } + PolishSubpage.LocalModel
        pages.forEach { page -> assertEquals(page, PolishSubpage.fromSaved(page.toSaved())) }
    }

    @Test fun setupTitlesSayEditOnlyForTheSavedProvider() {
        assertEquals("Edit OpenAI", PolishSubpage.ProviderSetup(Provider.OPENAI).title(savedProvider = Provider.OPENAI))
        assertEquals("Set up OpenAI", PolishSubpage.ProviderSetup(Provider.OPENAI).title(savedProvider = Provider.GEMINI))
        assertEquals("Set up OpenAI", PolishSubpage.ProviderSetup(Provider.OPENAI).title(savedProvider = null))
        assertEquals("S1-mini", PolishSubpage.LocalModel.title(savedProvider = Provider.OPENAI))
    }

    @Test fun anUnknownSavedStringIsTheTab() {
        listOf("", "garbage", "setup:", "setup:NOPE", "model:x").forEach { assertNull(it, PolishSubpage.fromSaved(it)) }
    }
}
