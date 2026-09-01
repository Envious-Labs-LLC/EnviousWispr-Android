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

    @Test fun anAlreadyConfiguredVisibleProviderWithAStoredKeyOpensStraightToTheModelList() {
        assertEquals(
            KeyRung.CONNECTED,
            initialKeyRung(configured = true, credentialStored = true, provider = Provider.OPENAI),
        )
    }

    @Test fun anUnconfiguredProviderStartsAtKeyEntry() {
        assertEquals(
            KeyRung.TYPING,
            initialKeyRung(configured = false, credentialStored = false, provider = Provider.OPENAI),
        )
    }

    @Test fun configuredButNoStoredKeyStartsAtKeyEntryNotConnected() {
        // Regression for a real bug caught in code review: removing a saved provider's key resets
        // `settings.provider` back to its default (OPENAI) rather than to null, and
        // `ProviderConfigurationRepository.load()` can also return `configured=true` with no stored
        // key. Either way "Key connected" must never be shown without an actual stored key.
        assertEquals(
            KeyRung.TYPING,
            initialKeyRung(configured = true, credentialStored = false, provider = Provider.OPENAI),
        )
    }

    @Test fun selfHostedNeverStartsConnectedEvenIfConfiguredAndCredentialStoredAreTrue() {
        // Guards against a future caller passing settings.configured=true for a stored self-hosted
        // config and accidentally reaching the model-list rung, which this screen no longer supports.
        assertEquals(
            KeyRung.TYPING,
            initialKeyRung(configured = true, credentialStored = true, provider = Provider.SELF_HOSTED_POLISH),
        )
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

    @Test fun cloudReactivatesImmediatelyWhenTheDisplayedTileIsTheSavedConnectedProvider() {
        val settings = ProviderSettingsUiState(
            provider = Provider.OPENAI,
            configured = true,
            credentialStored = true,
        )
        assertTrue(cloudReactivatesImmediately(Provider.OPENAI, settings))
    }

    @Test fun cloudDoesNotReactivateForADifferentTileThanTheSavedProvider() {
        // Regression for a real bug caught in code review: browsing a different provider tile, then
        // switching to Off or This phone and back to Cloud, must not silently reactivate the SAVED
        // provider while the screen keeps showing a different one's setup.
        val settings = ProviderSettingsUiState(
            provider = Provider.OPENAI,
            configured = true,
            credentialStored = true,
        )
        assertFalse(cloudReactivatesImmediately(Provider.GEMINI, settings))
    }

    @Test fun cloudDoesNotReactivateWhenTheSavedProviderItselfIsNotYetConnected() {
        val settings = ProviderSettingsUiState(provider = Provider.OPENAI, configured = false)
        assertFalse(cloudReactivatesImmediately(Provider.OPENAI, settings))
    }
}
