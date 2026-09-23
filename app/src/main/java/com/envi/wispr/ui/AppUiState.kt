package com.envi.wispr.ui

import com.envi.wispr.history.ui.HistoryUiState
import com.envi.wispr.providers.ui.ProviderDiscoveryUiState
import com.envi.wispr.providers.ui.ProviderSettingsUiState
import com.envi.wispr.vocabulary.ui.DictionaryUiState

/**
 * Everything the screens read, one field per owning view model (#218). The activity builds it from seven
 * collected states; nothing here is stored, so no feature has a second home.
 */
internal data class AppUiState(
    val shell: EnviousWisprUiState = EnviousWisprUiState(),
    val readiness: ReadinessUiState = ReadinessUiState(),
    val history: HistoryUiState = HistoryUiState(),
    val dictionary: DictionaryUiState = DictionaryUiState(),
    val polish: ProviderSettingsUiState = ProviderSettingsUiState(),
    val discovery: ProviderDiscoveryUiState = ProviderDiscoveryUiState(),
    /** The two model cards, from `ModelWorkViewModel` (#255). */
    val models: ModelWorkUiState = ModelWorkUiState(),
) {
    /**
     * True until preferences, terms, transcripts and readiness have each emitted once. Read from each
     * state's `loaded`, never from list emptiness, so an empty but real History or Dictionary ends it.
     * AI Polish keeps its own gate (`ProviderSettingsUiState.loading`) and never holds this one.
     */
    val loading: Boolean
        get() = !(shell.loaded && history.loaded && dictionary.loaded && readiness.loaded)

    val shouldShowOnboarding: Boolean
        get() = !loading && shell.shouldShowOnboarding
}
