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
import com.envi.wispr.providers.ProviderKeyRefusedException
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
    val credentialStored: Boolean = false,
    val message: String = "",
    val error: String? = null,
    /** The request sequence of the LAST COMPLETED write, success or failure; 0 before any write (#67). */
    val writeSequence: Int = 0,
    /** Who started the write that [writeSequence] names, so each surface renders only its own failures. */
    val writeOrigin: ProviderWriteOrigin = ProviderWriteOrigin.TAB,
)

/** Which surface asked for a provider-settings write (#67). */
enum class ProviderWriteOrigin { TAB, SETUP_PAGE }

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
) : ViewModel() {
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
    fun setPolishMode(mode: PolishMode): Int = updateProviderSettings(ProviderWriteOrigin.TAB) {
        if (mode == PolishMode.PROVIDER && providerRepository.load() == null) {
            error("Save provider settings before selecting provider mode")
        }
        providerRepository.setMode(mode)
        ""
    }

    /** The master switch turned on: the last engine used, or This phone if that engine is gone (#67). */
    fun turnPolishOn(): Int = updateProviderSettings(ProviderWriteOrigin.TAB) {
        providerRepository.turnOn()
        ""
    }

    /** The setup page's Save. Returns the request sequence the page waits for. */
    fun saveProviderSettings(
        provider: Provider,
        model: String,
        endpoint: String?,
        apiKey: String?,
        selfHostedProtocol: SelfHostedProtocol,
    ): Int = updateProviderSettings(ProviderWriteOrigin.SETUP_PAGE) {
        providerRepository.saveProvider(
            provider = provider,
            model = model.trim(),
            endpoint = endpoint?.trim()?.takeIf(String::isNotEmpty),
            apiKey = apiKey?.takeIf(String::isNotBlank),
            selfHostedProtocol = selfHostedProtocol,
        )
        "${provider.capabilities().displayName} saved"
    }

    /** Remove, from the setup page or the self-hosted card. Returns the request sequence. */
    fun clearProviderSettings(origin: ProviderWriteOrigin = ProviderWriteOrigin.SETUP_PAGE): Int = updateProviderSettings(origin) {
        providerRepository.clearSelection()
        "Provider removed"
    }

    private fun refreshProviderSettings(
        message: String = "",
        error: String? = null,
        sequence: Int = providerSettings.value.writeSequence,
        origin: ProviderWriteOrigin = providerSettings.value.writeOrigin,
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
            credentialStored = !selected?.apiKey.isNullOrBlank(),
            message = message,
            error = error,
            writeSequence = sequence,
            writeOrigin = origin,
        )
    }

    /** Allocated before a write is enqueued, so a caller can wait for ITS write and not an older one (#67). */
    private var nextWriteSequence = 0

    private fun updateProviderSettings(origin: ProviderWriteOrigin, operation: () -> String): Int {
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
                withContext(Dispatchers.IO) {
                    runCatching(operation).fold(
                        onSuccess = { message -> refreshProviderSettings(message = message, sequence = sequence, origin = origin) },
                        // A refused key check (#61) names its verdict from the verdict and the provider,
                        // never from exception text; every other failure gets one calm sentence, because
                        // the exception text is internal wording ("could not persist provider
                        // configuration"), never copy for the user.
                        onFailure = { failure ->
                            refreshProviderSettings(
                                error = (failure as? ProviderKeyRefusedException)?.let { refused ->
                                    keyCheckLine(refused.verdict, refused.provider.capabilities().displayName)
                                } ?: "Could not update AI Polish settings",
                                sequence = sequence,
                                origin = origin,
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
