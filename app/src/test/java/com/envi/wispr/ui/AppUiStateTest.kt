package com.envi.wispr.ui

import com.envi.wispr.history.ui.HistoryUiState
import com.envi.wispr.providers.ui.ProviderDiscoveryUiState
import com.envi.wispr.providers.ui.ProviderSettingsUiState
import com.envi.wispr.vocabulary.ui.DictionaryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#218): the app leaves "Preparing EnviousWispr" exactly when preferences, the word list,
 * History and readiness have each answered once. An empty History or Dictionary is an answer; AI Polish
 * still loading, or a model list nobody asked for, never keeps the app waiting.
 *
 * REVERT: make `AppUiState.loading` read `polish.loading`, or read list emptiness instead of `loaded`.
 */
class AppUiStateTest {
    private val ready = AppUiState(
        shell = EnviousWisprUiState(loaded = true),
        readiness = ReadinessUiState(loaded = true),
        history = HistoryUiState(loaded = true),
        dictionary = DictionaryUiState(loaded = true),
    )

    @Test fun theAppWaitsUntilEveryOwnerHasAnswered() {
        assertTrue(AppUiState().loading)
        listOf(
            "preferences" to ready.copy(shell = EnviousWisprUiState()),
            "readiness" to ready.copy(readiness = ReadinessUiState()),
            "History" to ready.copy(history = HistoryUiState()),
            "Dictionary" to ready.copy(dictionary = DictionaryUiState()),
        ).forEach { (missing, state) ->
            assertTrue("the app opened before $missing answered", state.loading)
            assertFalse(state.shouldShowOnboarding)
        }
    }

    @Test fun anEmptyButRealHistoryAndDictionaryOpenTheApp() {
        assertTrue(ready.history.transcripts.isEmpty() && ready.dictionary.allTerms.isEmpty())
        assertFalse("an empty History or Dictionary kept the app on Preparing", ready.loading)
    }

    @Test fun aPolishTabStillLoadingOrAnIdleModelListNeverHoldsTheApp() {
        val state = ready.copy(
            polish = ProviderSettingsUiState(loading = true),
            discovery = ProviderDiscoveryUiState(phase = ProviderDiscoveryUiState.Phase.IDLE),
        )
        assertFalse(state.loading)
    }

    @Test fun onboardingShowsOnlyAfterTheAppHasLoaded() {
        assertFalse(AppUiState().shouldShowOnboarding)
        assertTrue(ready.shouldShowOnboarding)
        assertEquals(ready.shell.shouldShowOnboarding, ready.shouldShowOnboarding)
    }
}
