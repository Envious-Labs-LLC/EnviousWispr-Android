package com.envi.wispr.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
internal fun EnviousWisprApp(
    uiState: EnviousWisprUiState,
    providerDiscovery: ProviderDiscoveryUiState,
    licenseNotices: String,
    actions: AppActions,
) {
    val context = LocalContext.current
    if (uiState.loading) {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("Preparing EnviousWispr", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    ModelWorkReadinessObserver(actions.shell.onRefreshReadiness)

    if (uiState.shouldShowOnboarding) {
        OnboardingScreen(
            step = uiState.preferences.onboardingStep,
            readiness = uiState.readiness,
            autoPaste = uiState.autoPaste,
            onStepChange = actions.onboarding.onStep,
            onDismiss = actions.onboarding.onDismiss,
            onRequestMicrophone = actions.permissions.onRequestMicrophone,
            onRequestNotifications = actions.permissions.onRequestNotifications,
            onOpenAccessibility = actions.permissions.onOpenAccessibility,
            onComplete = actions.onboarding.onComplete,
            look = uiState.preferences.bubbleLook,
        )
        return
    }

    var destinationName by rememberSaveable { mutableStateOf(AppDestination.History.name) }
    val destination = AppRoutes.destination(destinationName)
    var settingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
    // The AI Polish snackbar shows each completed write once. The memory lives HERE, above the animated
    // screen body, because the tab is removed while a settings page shows and a value inside it would be
    // reborn with the tab and replay the message (#67, `PolishSnackbarPolicy`).
    var lastShownWriteSequence by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    // The one History card that is open, held here rather than inside the row so that scrolling it
    // out of the list, or leaving History for another tab, does not close it. Null is "all closed",
    // and holding ONE id is what makes "only one open at a time" true by construction.
    var expandedTranscriptId by rememberSaveable { mutableStateOf<Long?>(null) }
    val settingsPage = AppRoutes.settingsPage(settingsPageName)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Only armed while a settings page is open, so the system back gesture on a tab still leaves the
    // app rather than being swallowed by a handler with nothing to close.
    val closePages = { settingsPageName = null }
    BackHandler(enabled = settingsPage != null, onBack = closePages)

    LaunchedEffect(uiState.providerSettings.writeSequence, uiState.providerSettings.message, destination) {
        val decision = PolishSnackbarPolicy.decide(lastShownWriteSequence, uiState.providerSettings.writeSequence, uiState.providerSettings.message)
        if (decision.show && destination == AppDestination.Polish) {
            lastShownWriteSequence = decision.remember
            snackbarHostState.showSnackbar(uiState.providerSettings.message)
        } else if (!decision.show) {
            lastShownWriteSequence = decision.remember
        }
    }

    // Computed only while the Polish tab is showing, so every other tab pays no WorkManager query.
    // Both the app-bar badge and PolishScreen's body read this one value, never a second computation
    // of the same fact — see architecture-rules.md RULE: own-state-locally.
    val polishS1State = if (destination == AppDestination.Polish) {
        val s1Work by WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.s1))
            .collectAsStateWithLifecycle(emptyList())
        val s1Adoption by WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1))
            .collectAsStateWithLifecycle(emptyList())
        workUiState(preferredModelWork(s1Work, s1Adoption), uiState.readiness.polishModelReady, ModelManifest.s1, context)
    } else {
        ModelUiState(label = "", health = ModelHealth.UNKNOWN)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = settingsPage == null,
        drawerContent = {
            SettingsDrawerSheet(
                current = settingsPage,
                onPick = { page ->
                    settingsPageName = page.name
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        AppScaffold(
            destination = destination,
            page = settingsPage?.let { PageChrome(it.title) },
            snackbarHostState = snackbarHostState,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onBack = closePages,
            onSelectDestination = { destinationName = it.name },
            onStartDictation = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                actions.shell.onStartDictation()
            },
            // `uiState.providerSettings` starts at its placeholder default (mode = OFFLINE_S1) and only
            // becomes real once the ViewModel's async initial load lands (`loading` flips to false) —
            // showing the badge before then would name a mode that may not be what is actually saved,
            // contradicting its own persisted-state contract (real bug caught in code review,
            // 2026-09-01, the badge-side twin of the same gate `PolishScreen` uses for its own body).
            topBarBadge = if (destination == AppDestination.Polish && !uiState.providerSettings.loading) {
                { PolishStatusBadge(polishStatusChip(uiState.providerSettings, polishS1State)) }
            } else null,
        ) { contentModifier ->
            AnimatedContent(
                targetState = settingsPage?.let(Screen::Page) ?: Screen.Tab(destination),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "destination",
                modifier = contentModifier,
            ) { current ->
                when (current) {
                    is Screen.Tab -> when (current.destination) {
                        AppDestination.History -> HistoryScreen(
                            transcripts = uiState.history,
                            totalCount = uiState.historyTotalCount,
                            search = uiState.historySearch,
                            error = uiState.historyError,
                            expandedId = expandedTranscriptId,
                            onExpandedChange = { expandedTranscriptId = it },
                            onSearchChange = actions.history.onSearchChange,
                            onKeep = actions.history.onKeep,
                            onDelete = actions.history.onDelete,
                            onDeleteAll = actions.history.onDeleteAll,
                        )
                        AppDestination.Dictionary -> DictionaryScreen(
                            terms = uiState.customTerms,
                            allTerms = uiState.allCustomTerms,
                            search = uiState.customTermSearch,
                            message = uiState.customTermMessage,
                            error = uiState.customTermError,
                            onSearchChange = actions.dictionary.onSearchChange,
                            onAdd = actions.dictionary.onAdd,
                            onEdit = actions.dictionary.onEdit,
                            onDelete = actions.dictionary.onDelete,
                            onBulkDelete = actions.dictionary.onBulkDelete,
                            onImport = actions.dictionary.onImport,
                        )
                        AppDestination.Transcription -> TranscriptionScreen(
                            readiness = uiState.readiness,
                            preferences = uiState.preferences,
                            onRefreshReadiness = actions.shell.onRefreshReadiness,
                            onFillerRemovalChanged = actions.transcription.onFillerRemovalChanged,
                            onEmojiFormatterChanged = actions.transcription.onEmojiFormatterChanged,
                            onSpokenPunctuationChanged = actions.transcription.onSpokenPunctuationChanged,
                            onAutoStopOnSilenceChanged = actions.transcription.onAutoStopOnSilenceChanged,
                            onSilencePauseSecondsChanged = actions.transcription.onSilencePauseSecondsChanged,
                        )
                        AppDestination.Polish -> PolishScreen(
                            settings = uiState.providerSettings,
                            s1State = polishS1State,
                            discovery = providerDiscovery,
                            onSetMode = actions.polish.onSetMode,
                            onSetS1Control = actions.polish.onSetS1Control,
                            onSave = { provider, model, apiKey, discoverySequence ->
                                actions.polish.onSaveProviderSettings(provider, model, null, apiKey, SelfHostedProtocol.OPENAI_COMPATIBLE, discoverySequence)
                            },
                            onClearProvider = actions.polish.onClearProvider,
                            onCheckKey = actions.polish.onCheckKey,
                            onKeyDraftChanged = actions.polish.onKeyDraftChanged,
                            onLoadCachedModels = actions.polish.onLoadCachedModels,
                            onRefreshReadiness = actions.shell.onRefreshReadiness,
                        )
                    }
                    is Screen.Page -> when (current.page) {
                        SettingsPage.WhatsNew -> WhatsNewPage()
                        SettingsPage.Appearance -> AppearancePage(
                            preferences = uiState.preferences,
                            onDynamicColorChanged = actions.appearance.onDynamicColorChanged,
                            onBubbleLookChanged = actions.appearance.onBubbleLookChanged,
                        )
                        SettingsPage.Microphone -> MicrophonePage(
                            readiness = uiState.readiness,
                            preferences = uiState.preferences,
                            onRequestMicrophone = actions.permissions.onRequestMicrophone,
                            onInputDevicePickChanged = actions.microphone.onInputDevicePickChanged,
                            onShowBluetoothTipsChanged = actions.microphone.onShowBluetoothTipsChanged,
                            onKeepEarbudsReadyChanged = actions.microphone.onKeepEarbudsReadyChanged,
                        )
                        SettingsPage.Sounds -> SoundsPage()
                        SettingsPage.Clipboard -> ClipboardPage(
                            preferences = uiState.preferences,
                            onAutoCopyChanged = actions.clipboard.onAutoCopyChanged,
                            onRestoreClipboardChanged = actions.clipboard.onRestoreClipboardChanged,
                            onSmartInsertionChanged = actions.clipboard.onSmartInsertionChanged,
                        )
                        SettingsPage.Permissions -> PermissionsPage(
                            readiness = uiState.readiness,
                            autoPaste = uiState.autoPaste,
                            onContinueSetup = actions.onboarding.onResume,
                            onRequestMicrophone = actions.permissions.onRequestMicrophone,
                            onRequestNotifications = actions.permissions.onRequestNotifications,
                            onOpenAccessibility = actions.permissions.onOpenAccessibility,
                        )
                        SettingsPage.Privacy -> PrivacyPage()
                        SettingsPage.Storage -> StoragePage()
                        SettingsPage.Licenses -> LicensesPage(notices = licenseNotices)
                    }
                }
            }
        }
    }
}
