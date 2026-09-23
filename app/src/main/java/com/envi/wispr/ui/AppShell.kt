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
import androidx.lifecycle.compose.LifecycleStartEffect
import com.envi.wispr.providers.SelfHostedProtocol
import kotlinx.coroutines.launch

@Composable
internal fun EnviousWisprApp(
    state: AppUiState,
    licenseNotices: String,
    actions: AppActions,
) {
    if (state.loading) {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("Preparing EnviousWispr", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    val preferences = state.shell.preferences
    val readiness = state.readiness.readiness
    val autoPaste = state.readiness.autoPaste
    val providerSettings = state.polish

    if (state.shouldShowOnboarding) {
        OnboardingScreen(
            step = preferences.onboardingStep,
            readiness = readiness,
            autoPaste = autoPaste,
            onStepChange = actions.onboarding.onStep,
            onDismiss = actions.onboarding.onDismiss,
            onRequestMicrophone = actions.permissions.onRequestMicrophone,
            onRequestNotifications = actions.permissions.onRequestNotifications,
            onOpenAccessibility = actions.permissions.onOpenAccessibility,
            onComplete = actions.onboarding.onComplete,
            look = preferences.bubbleLook,
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

    LaunchedEffect(providerSettings.writeSequence, providerSettings.message, destination) {
        val decision = PolishSnackbarPolicy.decide(lastShownWriteSequence, providerSettings.writeSequence, providerSettings.message)
        if (decision.show && destination == AppDestination.Polish) {
            lastShownWriteSequence = decision.remember
            snackbarHostState.showSnackbar(providerSettings.message)
        } else if (!decision.show) {
            lastShownWriteSequence = decision.remember
        }
    }

    // The model owner observes only the tab that shows while the activity is started (#255); a settings page,
    // onboarding and a stopped activity show none. Both the app-bar badge and PolishScreen's body read the one
    // published value, never a second computation of the same fact (architecture-rules.md RULE: own-state-locally).
    LifecycleStartEffect(destination, settingsPage) {
        actions.shell.onShowModels(if (settingsPage == null) destination else null)
        onStopOrDispose { actions.shell.onShowModels(null) }
    }
    val polishS1State = state.models.polish

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
            // `providerSettings` starts at its placeholder default (mode = OFFLINE_S1) and only
            // becomes real once the ViewModel's async initial load lands (`loading` flips to false) —
            // showing the badge before then would name a mode that may not be what is actually saved,
            // contradicting its own persisted-state contract (real bug caught in code review,
            // 2026-09-01, the badge-side twin of the same gate `PolishScreen` uses for its own body).
            topBarBadge = if (destination == AppDestination.Polish && !providerSettings.loading) {
                { PolishStatusBadge(polishStatusChip(providerSettings, polishS1State)) }
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
                            transcripts = state.history.transcripts,
                            totalCount = state.history.totalCount,
                            search = state.history.search,
                            error = state.history.error,
                            expandedId = expandedTranscriptId,
                            onExpandedChange = { expandedTranscriptId = it },
                            onSearchChange = actions.history.onSearchChange,
                            onKeep = actions.history.onKeep,
                            onDelete = actions.history.onDelete,
                            onDeleteAll = actions.history.onDeleteAll,
                        )
                        AppDestination.Dictionary -> DictionaryScreen(
                            terms = state.dictionary.terms,
                            allTerms = state.dictionary.allTerms,
                            search = state.dictionary.search,
                            message = state.dictionary.message,
                            error = state.dictionary.error,
                            onSearchChange = actions.dictionary.onSearchChange,
                            onAdd = actions.dictionary.onAdd,
                            onEdit = actions.dictionary.onEdit,
                            onDelete = actions.dictionary.onDelete,
                            onBulkDelete = actions.dictionary.onBulkDelete,
                            onImport = actions.dictionary.onImport,
                        )
                        AppDestination.Transcription -> TranscriptionScreen(
                            speechModel = state.models.speech,
                            preferences = preferences,
                            onRefreshReadiness = actions.shell.onRefreshReadiness,
                            onFillerRemovalChanged = actions.transcription.onFillerRemovalChanged,
                            onEmojiFormatterChanged = actions.transcription.onEmojiFormatterChanged,
                            onSpokenPunctuationChanged = actions.transcription.onSpokenPunctuationChanged,
                            onAutoStopOnSilenceChanged = actions.transcription.onAutoStopOnSilenceChanged,
                            onSilencePauseSecondsChanged = actions.transcription.onSilencePauseSecondsChanged,
                        )
                        AppDestination.Polish -> PolishScreen(
                            settings = providerSettings,
                            s1State = polishS1State,
                            discovery = state.discovery,
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
                            preferences = preferences,
                            onDynamicColorChanged = actions.appearance.onDynamicColorChanged,
                            onBubbleLookChanged = actions.appearance.onBubbleLookChanged,
                        )
                        SettingsPage.Microphone -> MicrophonePage(
                            readiness = readiness,
                            preferences = preferences,
                            onRequestMicrophone = actions.permissions.onRequestMicrophone,
                            onInputDevicePickChanged = actions.microphone.onInputDevicePickChanged,
                            onShowBluetoothTipsChanged = actions.microphone.onShowBluetoothTipsChanged,
                            onKeepEarbudsReadyChanged = actions.microphone.onKeepEarbudsReadyChanged,
                        )
                        SettingsPage.Sounds -> SoundsPage()
                        SettingsPage.Clipboard -> ClipboardPage(
                            preferences = preferences,
                            onAutoCopyChanged = actions.clipboard.onAutoCopyChanged,
                            onRestoreClipboardChanged = actions.clipboard.onRestoreClipboardChanged,
                            onSmartInsertionChanged = actions.clipboard.onSmartInsertionChanged,
                        )
                        SettingsPage.Permissions -> PermissionsPage(
                            readiness = readiness,
                            autoPaste = autoPaste,
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
