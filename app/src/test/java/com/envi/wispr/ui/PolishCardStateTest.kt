package com.envi.wispr.ui

import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, the phone card lets the user select a model that is not there, or the
 * provider card says Configured with no key, or a card says the wrong thing about where text goes.
 * Logic test only: it proves the helpers, never that the screen calls them (the hardware pass does).
 */
class PolishCardStateTest {
    private fun settings(mode: PolishMode, configured: Boolean = false, provider: Provider = Provider.OPENAI, key: Boolean = false, model: String = "gpt-test", endpoint: String = "") =
        ProviderSettingsUiState(loading = false, mode = mode, provider = provider, model = model, endpoint = endpoint, configured = configured, credentialStored = key)

    @Test fun thePhoneRadioIsSelectableOnlyWhenReadyOrAlreadyActive() {
        ModelHealth.entries.forEach { health ->
            val state = ModelUiState("x", health, action = ModelUiAction.NONE)
            assertEquals("$health while active", true, phoneCard(state, settings(PolishMode.OFFLINE_S1)).selectable)
            assertEquals("$health while not active", health == ModelHealth.READY, phoneCard(state, settings(PolishMode.PROVIDER)).selectable)
        }
    }

    @Test fun theMissingModelSaysModelNeededAndOffersDownload() {
        val missing = ModelUiState("Missing", ModelHealth.NOT_READY, action = ModelUiAction.DOWNLOAD)
        val card = phoneCard(missing, settings(PolishMode.OFFLINE_S1))
        assertEquals("S1-mini · Model needed", card.status)
        assertEquals(PhoneCardAction.DOWNLOAD_MODEL, card.action)
        assertEquals(CardTone.NEUTRAL, card.tone)
    }

    @Test fun readyAndBrokenReadAsTheyAre() {
        val ready = phoneCard(ModelUiState("Ready", ModelHealth.READY, action = ModelUiAction.REMOVE), settings(PolishMode.OFF))
        assertEquals("S1-mini · Ready", ready.status); assertEquals(CardTone.GOOD, ready.tone); assertEquals(PhoneCardAction.MANAGE_MODEL, ready.action)
        val broken = phoneCard(ModelUiState("Repair needed", ModelHealth.BROKEN, action = ModelUiAction.REPAIR), settings(PolishMode.OFF))
        assertEquals("S1-mini · Repair needed", broken.status); assertEquals(CardTone.PROBLEM, broken.tone)
        val failed = phoneCard(ModelUiState("Failed", ModelHealth.BROKEN, action = ModelUiAction.RETRY), settings(PolishMode.OFF))
        assertEquals(PhoneCardAction.DOWNLOAD_MODEL, failed.action)
    }

    @Test fun anUnconfiguredProviderCardOpensThePickerAndCannotBeSelected() {
        val card = providerCard(settings(PolishMode.OFFLINE_S1))
        assertEquals("Your provider", card.title); assertEquals("OpenAI, Gemini, or Claude", card.status)
        assertFalse(card.selectable); assertTrue(card.tapOpensPicker); assertEquals(ProviderCardAction.CHOOSE_PROVIDER, card.action)
    }

    @Test fun aConfiguredKeyProviderIsConfiguredOnlyWithItsKey() {
        val withKey = providerCard(settings(PolishMode.OFFLINE_S1, configured = true, provider = Provider.GEMINI, key = true, model = "gemini-3.6-flash"))
        assertEquals("Gemini", withKey.title); assertEquals("gemini-3.6-flash · Configured", withKey.status)
        assertTrue(withKey.selectable); assertEquals(CardTone.GOOD, withKey.tone); assertEquals("Text is sent using your key", withKey.privacyLine)
        assertEquals(ProviderCardAction.EDIT_PROVIDER, withKey.action); assertTrue(withKey.canSwitchProvider)
        val noKey = providerCard(settings(PolishMode.OFFLINE_S1, configured = true, provider = Provider.GEMINI, key = false, model = "gemini-3.6-flash"))
        assertEquals("gemini-3.6-flash · Key missing", noKey.status); assertFalse(noKey.selectable); assertEquals(CardTone.PROBLEM, noKey.tone)
        assertTrue("already active stays selectable", providerCard(settings(PolishMode.PROVIDER, configured = true, provider = Provider.GEMINI, key = false)).selectable)
    }

    @Test fun selfHostedShowsItsHostAndOffersRemove() {
        val card = providerCard(settings(PolishMode.PROVIDER, configured = true, provider = Provider.SELF_HOSTED_POLISH, model = "m", endpoint = "https://box.local:8080/v1"))
        assertEquals("Self-hosted", card.title); assertEquals("box.local · Configured", card.status)
        assertEquals("Text is sent to your server", card.privacyLine); assertEquals(ProviderCardAction.REMOVE_SELF_HOSTED, card.action); assertTrue(card.selectable)
    }

    @Test fun theSnackbarShowsOncePerCompletedWriteAndResetsAfterRecreation() {
        assertEquals(PolishSnackbarPolicy.Decision(0, false), PolishSnackbarPolicy.decide(0, 0, "x"))
        assertEquals(PolishSnackbarPolicy.Decision(3, true), PolishSnackbarPolicy.decide(2, 3, "Gemini saved"))
        assertEquals(PolishSnackbarPolicy.Decision(3, false), PolishSnackbarPolicy.decide(3, 3, "Gemini saved"))
        assertEquals(PolishSnackbarPolicy.Decision(4, false), PolishSnackbarPolicy.decide(3, 4, ""))
        assertEquals("a fresh view model after recreation", PolishSnackbarPolicy.Decision(1, false), PolishSnackbarPolicy.decide(9, 1, "stale"))
    }

    @Test fun theSetupPageWaitsForItsOwnWriteAndStaysOnFailure() {
        val o = ProviderSetupSavePolicy
        assertEquals(ProviderSetupSavePolicy.Outcome.WAITING, o.outcome(null, 5, null, ProviderWriteOrigin.SETUP_PAGE))
        assertEquals(ProviderSetupSavePolicy.Outcome.WAITING, o.outcome(5, 4, null, ProviderWriteOrigin.SETUP_PAGE))
        assertEquals("an older tab write completing is not this page's write", ProviderSetupSavePolicy.Outcome.WAITING, o.outcome(5, 5, null, ProviderWriteOrigin.TAB))
        assertEquals(ProviderSetupSavePolicy.Outcome.DONE, o.outcome(5, 5, null, ProviderWriteOrigin.SETUP_PAGE))
        assertEquals(ProviderSetupSavePolicy.Outcome.DONE, o.outcome(5, 6, null, ProviderWriteOrigin.SETUP_PAGE))
        assertEquals(ProviderSetupSavePolicy.Outcome.FAILED, o.outcome(5, 5, "Could not update AI Polish settings", ProviderWriteOrigin.SETUP_PAGE))
    }
}
