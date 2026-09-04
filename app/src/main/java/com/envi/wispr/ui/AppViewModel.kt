package com.envi.wispr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.AutoPasteReadiness
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.InconsistentProviderStorageException
import com.envi.wispr.providers.ProviderKeyRefusedException
import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelListCache
import com.envi.wispr.providers.ModelListRules
import com.envi.wispr.providers.ProviderDiscovery
import com.envi.wispr.providers.ProviderKeyCheck
import com.envi.wispr.providers.ProviderModelDiscoverer
import com.envi.wispr.providers.ProviderPolishClient
import com.envi.wispr.polish.PolishFailure
import kotlinx.coroutines.flow.asStateFlow
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.capabilities
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.VocabularyTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProviderSettingsUiState(
    val loading: Boolean = true,
    val mode: PolishMode = PolishMode.OFFLINE_S1,
    val provider: Provider = Provider.OPENAI,
    val model: String = "",
    val endpoint: String = "",
    val selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
    val configured: Boolean = false,
    /**
     * Every provider holding a usable key, whatever is selected (#103). The AI Polish tab asks this per
     * TILE, so a key that is stored stays visible, switchable and removable even when another provider is
     * the active one.
     */
    val storedProviders: Set<Provider> = emptySet(),
    val message: String = "",
    val error: String? = null,
    /** The request sequence of the LAST COMPLETED write, success or failure; 0 before any write (#67). */
    val writeSequence: Int = 0,
) {
    /**
     * The SELECTED provider's key is in the Keystore. DERIVED from [storedProviders] rather than stored
     * beside it, so the tab's per-tile answer and the status chip's one-provider answer cannot disagree.
     * Self-hosted stores no key and so is never credentialed, which is what it has always reported.
     */
    val credentialStored: Boolean get() = configured && provider in storedProviders
}

/** The setup page's live model list (#84): one provider at a time, one sequence, one phase. */
data class ProviderDiscoveryUiState(
    val provider: Provider? = null,
    val sequence: Int = 0,
    val phase: Phase = Phase.IDLE,
    val models: List<DiscoveredModel> = emptyList(),
    val fetchedAt: Long? = null,
    val fromCache: Boolean = false,
    /** True when the list describes the STORED credential (a cache read or a stored-key Check). */
    val usedStoredKey: Boolean = false,
    val line: String? = null,
) {
    enum class Phase { IDLE, CHECKING, LISTED, FAILED }
}

data class AppReadiness(
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val accessibilityPermitted: Boolean = false,
    val speechModelReady: Boolean = false,
    val polishModelReady: Boolean = false,
) {
    val requiredModelsReady: Boolean
        get() = speechModelReady && polishModelReady

    val coreReady: Boolean
        get() = microphoneGranted && requiredModelsReady
}

data class EnviousWisprUiState(
    val loading: Boolean = true,
    val preferences: AppPreferencesState = AppPreferencesState(),
    val readiness: AppReadiness = AppReadiness(),
    val allCustomTerms: List<CustomTermRecord> = emptyList(),
    val customTerms: List<CustomTermRecord> = emptyList(),
    val customTermTotalCount: Int = 0,
    val customTermSearch: String = "",
    val customTermMessage: String = "",
    val customTermError: String? = null,
    val autoPaste: AutoPasteAvailability = AutoPasteReadiness.initial,
    val history: List<TranscriptEntity> = emptyList(),
    val historyTotalCount: Int = 0,
    val historySearch: String = "",
    val historyError: String? = null,
    val providerSettings: ProviderSettingsUiState = ProviderSettingsUiState(),
) {
    val shouldShowOnboarding: Boolean
        get() = !loading &&
            !preferences.onboardingComplete &&
            !preferences.onboardingDismissed
}

class EnviousWisprViewModel(
    private val appPreferences: AppPreferences,
    private val repository: TranscriptRepository,
    private val customTermRepository: CustomTermRepository,
    private val providerRepository: ProviderConfigurationRepository,
    private val appContext: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The live model list (#84): the discoverer and the per-provider cache, both replaceable by a test. */
    private val discoverer: ProviderModelDiscoverer = ProviderPolishClient(),
    private val modelCache: ModelListCache = ModelListCache(appContext),
) : ViewModel() {
    private val providerDiscoveryState = MutableStateFlow(ProviderDiscoveryUiState())
    /** The setup page's live model list; separate from [uiState] because it is per page, not per app. */
    val providerDiscovery: StateFlow<ProviderDiscoveryUiState> = providerDiscoveryState.asStateFlow()

    // Discovery's own counters, allocated and compared on Main (never the write sequence, never the
    // settings mutex): only the latest discovery per provider may touch the UI or the cache (#84).
    private var nextDiscoverySequence = 0
    private val latestDiscoveryByProvider = HashMap<Provider, Int>()
    /** A draft-key result waits here per provider, under its sequence, until a Save carrying that sequence succeeds. */
    private val draftResults = HashMap<Provider, Pair<Int, ProviderDiscovery.Listed>>()

    private val readiness = MutableStateFlow(AppReadiness())
    private val customTermSearch = MutableStateFlow("")
    private val customTermMessage = MutableStateFlow("")
    private val customTermError = MutableStateFlow<String?>(null)
    private val historySearch = MutableStateFlow("")
    private val historyError = MutableStateFlow<String?>(null)
    private val providerSettings = MutableStateFlow(ProviderSettingsUiState())
    // AI Polish now commits mode and provider changes immediately on tap rather than behind a single
    // Apply button (`ui/PolishScreen.kt`), so two writes launched in quick succession (Off, then This
    // phone) can otherwise race on `Dispatchers.IO` and persist whichever finishes last rather than
    // whichever was tapped last. This serializes every provider-settings write to launch order.
    private val providerSettingsMutex = Mutex()

    private val history = repository.transcripts
    private val customTerms = customTermRepository.observe()

    private val baseUiState = combine(
        appPreferences.state,
        readiness,
        customTerms,
        customTermSearch,
        customTermMessage,
    ) { preferences, currentReadiness, terms, termSearch, termMessage ->
        EnviousWisprUiState(
            loading = false,
            preferences = preferences,
            readiness = currentReadiness,
            allCustomTerms = terms,
            customTerms = terms.filter { record ->
                termSearch.isBlank() ||
                    record.term.spelling.contains(termSearch, ignoreCase = true) ||
                    record.term.aliases.any { it.contains(termSearch, ignoreCase = true) } ||
                    record.term.category?.contains(termSearch, ignoreCase = true) == true
            },
            customTermTotalCount = terms.size,
            customTermSearch = termSearch,
            customTermMessage = termMessage,
        )
    }

    // Derived, never stored. AppReadiness is a snapshot written wholesale from the Settings screen,
    // so a liveness field inside it would go stale between pushes; combining here gives the answer
    // exactly one producer and no second home (`architecture-rules.md` RULE: own-state-locally).
    // Assigned directly, with no operator after it: AutoPasteReadiness.observe owns the join, and
    // projecting its answer back down here would put the permission fact in charge again.
    private val autoPaste = AutoPasteReadiness.observe(
        permittedInSettings = readiness.map { it.accessibilityPermitted },
        serviceBound = PasteAccessibilityService.isBound,
    )

    private val uiStateWithHistory = combine(
        baseUiState,
        history,
        autoPaste,
    ) { base, transcripts, autoPasteStatus ->
        base.copy(
            history = transcripts,
            historyTotalCount = transcripts.size,
            autoPaste = autoPasteStatus,
        )
    }

    val uiState: StateFlow<EnviousWisprUiState> = combine(
        uiStateWithHistory,
        historySearch,
        historyError,
        providerSettings,
        customTermError,
    ) { base, search, error, currentProviderSettings, currentCustomTermError ->
        base.copy(
            history = base.history.filter {
                search.isBlank() || it.finalText.contains(search, ignoreCase = true) ||
                    it.originalText.contains(search, ignoreCase = true)
            },
            historySearch = search,
            historyError = error,
            providerSettings = currentProviderSettings,
            customTermError = currentCustomTermError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EnviousWisprUiState(),
    )

    init {
        viewModelScope.launch {
            runCatching {
                repository.recoverStaleOpenRows(clock())
                // Rows an older build saved for a dictation with no words in them. Swept here rather
                // than left for the user to delete, because they are the reason History could not be
                // scanned. Nothing writes them any more, so on a phone that has run this once it
                // deletes nothing.
                repository.pruneWordlessRows()
            }
                .onFailure { error -> historyError.value = error.message ?: error::class.simpleName }
        }
        viewModelScope.launch {
            runCatching { customTermRepository.migrateLegacySharedPreferences(appContext) }
                .onSuccess { imported ->
                    if (imported > 0) customTermMessage.value = "Imported $imported existing custom terms"
                }
                .onFailure { failure ->
                    customTermError.value = failure.message ?: "Could not migrate existing custom terms"
                }
        }
        // Routed through the same mutex as every write, not called bare: without this, the initial
        // load and a fast user-triggered write race with no ordering between them at all, and if the
        // initial load's own read happened before the write but its publish lands after, it can
        // revert `providerSettings.value` to the pre-write snapshot — a real bug caught in code
        // review, 2026-09-01, distinct from (and found right after) the ordering fix inside
        // `updateProviderSettings` itself.
        viewModelScope.launch {
            providerSettingsMutex.withLock {
                withContext(Dispatchers.IO) { refreshProviderSettings() }
            }
        }
    }

    fun updateHistorySearch(value: String) { historySearch.value = value }

    fun setHistoryKept(transcript: TranscriptEntity) {
        updateHistory { repository.setKept(transcript.id, !transcript.kept) }
    }

    fun deleteHistory(transcript: TranscriptEntity) {
        updateHistory { repository.delete(transcript) }
    }

    fun deleteAllHistory() {
        updateHistory { repository.deleteAll() }
    }

    private fun updateHistory(operation: suspend () -> Unit) {
        historyError.value = null
        viewModelScope.launch {
            runCatching { operation() }.onFailure { error ->
                if (error is CancellationException) throw error
                historyError.value = error.message ?: error::class.simpleName
            }
        }
    }

    fun updateReadiness(snapshot: AppReadiness) {
        readiness.value = snapshot
    }

    fun updateCustomTermSearch(value: String) {
        customTermSearch.value = value
    }

    fun addCustomTerm(term: CustomTerm) {
        updateCustomTerms {
            customTermRepository.add(term)
            "Added ${term.spelling.trim()}"
        }
    }

    fun editCustomTerm(record: CustomTermRecord, term: CustomTerm) {
        updateCustomTerms {
            checkNotNull(customTermRepository.edit(record.id, term)) { "Custom term no longer exists" }
            "Updated ${term.spelling.trim()}"
        }
    }

    fun deleteCustomTerm(record: CustomTermRecord) {
        updateCustomTerms {
            check(customTermRepository.delete(record.id)) { "Custom term no longer exists" }
            "Deleted ${record.term.spelling}"
        }
    }

    fun bulkDeleteCustomTerms(ids: Set<Long>) {
        updateCustomTerms {
            val deleted = customTermRepository.bulkDelete(ids)
            "Deleted $deleted custom ${if (deleted == 1) "term" else "terms"}"
        }
    }

    fun importCustomTerms(input: String) {
        updateCustomTerms {
            val existing = customTermRepository.list().map(CustomTermRecord::term)
            val preview = VocabularyTransfer.preview(input, existing)
            val result = customTermRepository.applyImport(preview)
            buildString {
                append("Imported ${result.added}")
                if (result.updated > 0) append("; updated ${result.updated}")
                if (result.skipped > 0) append("; ${result.skipped} duplicates skipped")
                if (result.rejected > 0) append("; ${result.rejected} invalid skipped")
            }
        }
    }

    private fun updateCustomTerms(operation: suspend () -> String) {
        customTermMessage.value = ""
        customTermError.value = null
        viewModelScope.launch {
            runCatching { operation() }.fold(
                onSuccess = { customTermMessage.value = it },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    customTermError.value = failure.message ?: "Could not update custom terms"
                },
            )
        }
    }

    fun setOnboardingStep(step: Int) {
        viewModelScope.launch {
            appPreferences.setOnboardingStep(step)
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            appPreferences.dismissOnboarding()
        }
    }

    fun resumeOnboarding() {
        viewModelScope.launch {
            appPreferences.resumeOnboarding()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            appPreferences.completeOnboarding()
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setDynamicColorEnabled(enabled)
        }
    }

    fun setFillerRemovalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setFillerRemovalEnabled(enabled)
        }
    }

    fun setEmojiFormatterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setEmojiFormatterEnabled(enabled)
        }
    }

    fun setAutoStopOnSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoStopOnSilenceEnabled(enabled) }
    }

    /** The value is clamped in the store as well, so a bad one never reaches a take. */
    fun setSilencePauseSeconds(seconds: Float) {
        viewModelScope.launch { appPreferences.setSilencePauseSeconds(seconds) }
    }

    fun setSpokenPunctuationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setSpokenPunctuationEnabled(enabled)
        }
    }

    fun setAutoCopyToClipboard(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoCopyToClipboard(enabled) }
    }

    fun setRestoreClipboardAfterPaste(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setRestoreClipboardAfterPaste(enabled) }
    }

    fun setSmartInsertionEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setSmartInsertionEnabled(enabled) }
    }

    /** A mode tap on the tab. Returns the request sequence of the write it queued. */
    fun setPolishMode(mode: PolishMode): Int = updateProviderSettings {
        if (mode == PolishMode.PROVIDER && providerRepository.load() == null) {
            error("Save provider settings before selecting provider mode")
        }
        providerRepository.setMode(mode)
        ""
    }

    /** The Ladder's save (#81): an accepted key with its starting model, or a model change. Returns the request sequence the tab waits for. */
    fun saveProviderSettings(
        provider: Provider,
        model: String,
        endpoint: String?,
        apiKey: String?,
        selfHostedProtocol: SelfHostedProtocol,
        /** The Check the page ran on this same key draft, so its list can become the cache on success (#84). */
        discoverySequence: Int? = null,
    ): Int {
        val suppliedKey = !apiKey.isNullOrBlank()
        return updateProviderSettings(
            // A supplied key clears the provider's persisted cache BEFORE the write (#81): the commit and the
            // promotion below are two steps, and a process death between them would otherwise restart with
            // the old key's cache labelled as the stored key's. Losing a valid old cache on a failed save is
            // safe; showing an old key's models under a newly stored key is not.
            beforeWrite = {
                if (ProviderDiscoveryApplyPolicy.clearsCacheBeforeSave(suppliedKey)) {
                    withContext(Dispatchers.IO) { modelCache.clear(provider) }
                }
            },
            // Awaited BEFORE the completed write is published, so the tab reads the promoted or cleared
            // cache, never the stale one.
            afterWrite = { succeeded ->
                when (ProviderDiscoveryApplyPolicy.afterSave(succeeded, suppliedKey, discoverySequence, draftResults[provider]?.first)) {
                    ProviderDiscoveryApplyPolicy.CacheAction.PROMOTE -> {
                        val listed = draftResults.remove(provider)?.second
                        if (listed != null) {
                            withContext(Dispatchers.IO) { modelCache.write(provider, ModelListCache.Entry(listed.fetchedAt, listed.models)) }
                            if (providerDiscoveryState.value.provider == provider) {
                                providerDiscoveryState.value = providerDiscoveryState.value.copy(usedStoredKey = true)
                            }
                        }
                    }
                    ProviderDiscoveryApplyPolicy.CacheAction.CLEAR -> {
                        draftResults.remove(provider)
                        withContext(Dispatchers.IO) { modelCache.clear(provider) }
                        if (providerDiscoveryState.value.provider == provider) providerDiscoveryState.value = ProviderDiscoveryUiState(provider = provider)
                    }
                    ProviderDiscoveryApplyPolicy.CacheAction.NONE -> Unit
                }
            },
        ) {
            providerRepository.saveProvider(
                provider = provider,
                model = model.trim(),
                endpoint = endpoint?.trim()?.takeIf(String::isNotEmpty),
                apiKey = apiKey?.takeIf(String::isNotBlank),
                selfHostedProtocol = selfHostedProtocol,
            )
            "${provider.capabilities().displayName} saved"
        }
    }

    /**
     * Remove, from the Ladder's connected row or the self-hosted card. Takes the provider the ROW is about
     * (#103), not the selected one: with a connected row per stored key, a Remove on an inactive tile must
     * delete that tile's key and leave the active provider running.
     *
     * Returns the request sequence.
     */
    fun removeProviderKey(provider: Provider): Int = updateProviderSettings(
        afterWrite = { succeeded ->
            if (succeeded) {
                draftResults.remove(provider)
                withContext(Dispatchers.IO) { modelCache.clear(provider) }
                if (providerDiscoveryState.value.provider == provider) providerDiscoveryState.value = ProviderDiscoveryUiState(provider = provider)
            }
        },
    ) {
        providerRepository.removeKey(provider)
        "${provider.capabilities().displayName} removed"
    }

    /** The page's cached list on open (#84); never replaces a live result already showing for that provider. */
    fun loadCachedModels(provider: Provider) {
        // The page that opened is the active one from this moment; another provider's late completion
        // can no longer touch the state (ProviderDiscoveryApplyPolicy.appliesToActivePage).
        val current0 = providerDiscoveryState.value
        if (current0.provider != provider) providerDiscoveryState.value = ProviderDiscoveryUiState(provider = provider)
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { modelCache.read(provider) }
            val current = providerDiscoveryState.value
            if (current.provider != provider || current.phase != ProviderDiscoveryUiState.Phase.IDLE) return@launch
            providerDiscoveryState.value = if (entry == null) ProviderDiscoveryUiState(provider = provider) else ProviderDiscoveryUiState(
                provider = provider,
                phase = ProviderDiscoveryUiState.Phase.LISTED,
                models = entry.models,
                fetchedAt = entry.fetchedAt,
                fromCache = true,
                usedStoredKey = true,
            )
        }
    }

    /** The user edited the key: a draft result no longer describes the credential on the page (#84). */
    fun keyDraftChanged(provider: Provider) {
        draftResults.remove(provider)
        val current = providerDiscoveryState.value
        if (current.provider == provider && !current.usedStoredKey) providerDiscoveryState.value = ProviderDiscoveryUiState(provider = provider)
    }

    /**
     * Check on the setup page (#84): the live list for [provider] with the draft key, else the stored one.
     * Runs on IO outside the settings mutex; the completion applies only if this sequence is still the
     * latest for the provider. @return the sequence, which the page hands back to Save.
     */
    fun discoverModels(provider: Provider, apiKeyDraft: String?): Int {
        val sequence = ++nextDiscoverySequence
        latestDiscoveryByProvider[provider] = sequence
        // The draft is judged RAW first (a control character refuses, never trims away), then trimmed once,
        // the same rule the repository applies on Save.
        val draftInvalid = apiKeyDraft?.any(Char::isISOControl) == true
        val draft = apiKeyDraft?.trim()?.takeIf(String::isNotEmpty)
        val usingDraft = draft != null || draftInvalid
        val previous = providerDiscoveryState.value.takeIf { it.provider == provider && !usingDraft }
        providerDiscoveryState.value = ProviderDiscoveryUiState(
            provider = provider,
            sequence = sequence,
            phase = ProviderDiscoveryUiState.Phase.CHECKING,
            // A draft-key Check shows nothing of the saved credential while it runs.
            models = previous?.models.orEmpty(),
            fetchedAt = previous?.fetchedAt,
            fromCache = previous?.fromCache == true,
            usedStoredKey = previous?.usedStoredKey == true,
        )
        viewModelScope.launch {
            val usedStoredKey = !usingDraft
            val outcome = if (draftInvalid) {
                ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST))
            } else withContext(Dispatchers.IO) {
                // A stored-key Check reaches THIS provider's credential, not the selected one's (#103).
                // Reading it through `load()` meant Refresh only ever worked on the active provider, so on
                // any other connected tile it refused with no key while the key sat in the Keystore.
                //
                // The stored key is never named here: the repository runs the discovery with it and hands
                // back only the listing, so no shape of this call can reach the plaintext
                // (keystore-security.md RULE: plaintext-never-leaves-the-store).
                if (draft != null) discoverer.discoverModels(provider, draft)
                else providerRepository.discoverModelsWithStoredKey(provider, discoverer)
                    ?: ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST))
            }
            val name = provider.capabilities().displayName
            // The class of defect this closes: a completion judged on state read BEFORE a suspension. There
            // are three suspensions in this coroutine (the discovery itself, the cache read for the merge,
            // the cache write), and after EACH the completion re-asks both questions on the state as it is
            // now: is this still the latest Check for the provider, and is its page still the active one.
            fun appliesNow() = ProviderDiscoveryApplyPolicy.isLatest(sequence, latestDiscoveryByProvider[provider]) &&
                ProviderDiscoveryApplyPolicy.appliesToActivePage(provider, providerDiscoveryState.value.provider)
            when (outcome) {
                is ProviderDiscovery.Listed -> {
                    if (outcome.models.isEmpty()) {
                        // A discovery hiccup, never a reason to drop what the cache holds.
                        if (appliesNow()) providerDiscoveryState.value = providerDiscoveryState.value.copy(phase = ProviderDiscoveryUiState.Phase.LISTED, line = "No models this key can use for polish.")
                        return@launch
                    }
                    // Only a stored-key discovery borrows the saved credential's cached access.
                    val merged = if (ProviderDiscoveryApplyPolicy.mergesWithCache(usedStoredKey)) {
                        val cached = withContext(Dispatchers.IO) { modelCache.read(provider)?.models.orEmpty() }
                        ModelListRules.sort(ModelListRules.mergeAccess(outcome.models, cached))
                    } else {
                        ModelListRules.sort(outcome.models)
                    }
                    val listed = ProviderDiscovery.Listed(merged, outcome.fetchedAt)
                    // A stored-key list is cached even if the user has moved on, but never over a newer
                    // Check's result for the same provider.
                    if (ProviderDiscoveryApplyPolicy.isLatest(sequence, latestDiscoveryByProvider[provider]) &&
                        ProviderDiscoveryApplyPolicy.writesCacheNow(usedStoredKey, true)
                    ) {
                        withContext(Dispatchers.IO) { modelCache.write(provider, ModelListCache.Entry(listed.fetchedAt, listed.models)) }
                    }
                    if (!appliesNow()) return@launch
                    if (!usedStoredKey) draftResults[provider] = sequence to listed
                    providerDiscoveryState.value = ProviderDiscoveryUiState(
                        provider = provider,
                        sequence = sequence,
                        phase = ProviderDiscoveryUiState.Phase.LISTED,
                        models = merged,
                        fetchedAt = listed.fetchedAt,
                        fromCache = false,
                        usedStoredKey = usedStoredKey,
                    )
                }
                is ProviderDiscovery.Refused -> if (appliesNow()) {
                    providerDiscoveryState.value = providerDiscoveryState.value.copy(
                        sequence = sequence,
                        phase = ProviderDiscoveryUiState.Phase.FAILED,
                        line = discoveryLine(outcome.verdict, name) ?: "Couldn't check the key with $name.",
                    )
                }
            }
        }
        return sequence
    }

    private fun refreshProviderSettings(
        message: String = "",
        error: String? = null,
        sequence: Int = providerSettings.value.writeSequence,
    ) {
        val mode = providerRepository.loadMode()
        val selected = providerRepository.load()
        providerSettings.value = ProviderSettingsUiState(
            loading = false,
            mode = mode,
            provider = selected?.provider ?: Provider.OPENAI,
            model = selected?.model.orEmpty(),
            endpoint = selected?.endpoint.orEmpty(),
            selfHostedProtocol = selected?.selfHostedProtocol
                ?: SelfHostedProtocol.OPENAI_COMPATIBLE,
            configured = selected != null,
            storedProviders = providerRepository.storedProviders(),
            message = message,
            error = error,
            writeSequence = sequence,
        )
    }

    /** Allocated before a write is enqueued, so a caller can wait for ITS write and not an older one (#67). */
    private var nextWriteSequence = 0

    private fun updateProviderSettings(
        /**
         * Run inside the write mutex and inside the captured outcome, before [operation] (#81): a throw here
         * means the repository write does not run and this same sequence publishes a failure.
         */
        beforeWrite: suspend () -> Unit = {},
        /** Awaited BEFORE the completed write is published, with whether it succeeded (#84 cache decisions). */
        afterWrite: suspend (Boolean) -> Unit = {},
        operation: () -> String,
    ): Int {
        val sequence = ++nextWriteSequence
        providerSettings.value = providerSettings.value.copy(message = "", error = null)
        // Launching straight onto `Dispatchers.IO` would let two calls reach `withLock` in whichever
        // order the multithreaded IO pool happens to schedule them, not the order they were tapped —
        // a real bug caught in code review, 2026-09-01. `viewModelScope`'s default dispatcher is
        // `Main.immediate`, and every caller here is itself a Main-thread UI callback, so acquiring
        // the lock on the DEFAULT dispatcher first preserves tap order; only the blocking work inside
        // the lock moves to IO.
        viewModelScope.launch {
            providerSettingsMutex.withLock {
                val outcome = runCatching {
                    beforeWrite()
                    withContext(Dispatchers.IO) { operation() }
                }
                if (outcome.exceptionOrNull() is CancellationException) throw outcome.exceptionOrNull()!!
                // Cache maintenance is a limb: a failure here must never stop the write's completion from
                // publishing, or the tab waits on this sequence for ever (code review, 2026-09-02).
                runCatching { afterWrite(outcome.isSuccess) }.exceptionOrNull()?.let { failure ->
                    if (failure is CancellationException) throw failure
                }
                withContext(Dispatchers.IO) {
                    outcome.fold(
                        onSuccess = { message -> refreshProviderSettings(message = message, sequence = sequence) },
                        // A refused key check (#61) names its verdict from the verdict and the provider,
                        // never from exception text; a failed key restore (#81) names its own sentence;
                        // every other failure gets one calm sentence, because the exception text is
                        // internal wording ("could not persist provider configuration"), never copy.
                        onFailure = { failure ->
                            refreshProviderSettings(
                                error = when (failure) {
                                    is ProviderKeyRefusedException -> keyCheckLine(failure.verdict, failure.provider.capabilities().displayName)
                                    is InconsistentProviderStorageException -> "Could not restore your saved key. Remove the provider and set it up again."
                                    else -> "Could not update AI Polish settings"
                                },
                                sequence = sequence,
                            )
                        },
                    )
                }
            }
        }
        return sequence
    }

    class Factory(
        private val appPreferences: AppPreferences,
        private val repository: TranscriptRepository,
        private val customTermRepository: CustomTermRepository,
        private val providerRepository: ProviderConfigurationRepository,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(EnviousWisprViewModel::class.java))
            return EnviousWisprViewModel(
                appPreferences,
                repository,
                customTermRepository,
                providerRepository,
                appContext,
            ) as T
        }
    }
}

/**
 * Reads the facts Android will only answer when asked. Auto-paste LIVENESS is not one of them: the
 * setting string still names a service that has crashed, and so do `AccessibilityManager.isEnabled`
 * and `getEnabledAccessibilityServiceList`, which project the same setting. Liveness is pushed by
 * `PasteAccessibilityService.isBound`, and the two are combined in the view model.
 */
fun readAppReadiness(context: Context): AppReadiness {
    val accessibilityPermitted = AccessibilityPermission.isGranted(context)

    val speechModelReady = ModelStorage.isReady(context, ModelManifest.parakeet)

    return AppReadiness(
        microphoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        accessibilityPermitted = accessibilityPermitted,
        speechModelReady = speechModelReady,
        polishModelReady = ModelStorage.isReady(context, ModelManifest.s1),
    )
}
