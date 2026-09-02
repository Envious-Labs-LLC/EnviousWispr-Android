package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, the user either sees a self-hosted tile that no longer works
 * from this screen, or opens an already-configured provider and is asked to retype a key they
 * already saved.
 */
class PolishScreenProviderTilesTest {

    @Test fun excludesSelfHostedFromTheTileRow() {
        assertFalse(CloudProviders.contains(Provider.SELF_HOSTED_POLISH))
        assertEquals(Provider.entries.size - 1, CloudProviders.size)
    }
    @Test fun everyVisibleProviderIsInTheTileRow() {
        assertEquals(setOf(Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE), CloudProviders.toSet())
    }
    @Test fun savedModelDoesNotCarryOverToADifferentProviderTile() {
        // Regression for a real bug caught in code review: after switching from a saved OpenAI
        // config to the Gemini tile, the OpenAI model name must not appear as a pickable Gemini row.
        val settings = ProviderSettingsUiState(provider = Provider.OPENAI, model = "gpt-5.4-mini", configured = true)
        assertEquals("", savedModelFor(Provider.GEMINI, settings))
    }
    @Test fun savedModelIsKeptForItsOwnProvider() {
        val settings = ProviderSettingsUiState(provider = Provider.OPENAI, model = "gpt-5.4-mini", configured = true)
        assertEquals("gpt-5.4-mini", savedModelFor(Provider.OPENAI, settings))
    }
}
