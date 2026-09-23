package com.envi.wispr.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.ui.HistoryViewModel
import com.envi.wispr.ui.theme.EnviousWisprTheme
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.providers.ModelListCache
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.ui.PolishSettingsViewModel
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.ui.DictionaryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private var modelReadinessGeneration = 0L
    // One view model per feature (#218), each built from its own dependencies only.
    private val shellViewModel: EnviousWisprViewModel by viewModels {
        EnviousWisprViewModel.Factory(appPreferences = AppPreferences(applicationContext))
    }
    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory(repository = TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao()))
    }
    private val dictionaryViewModel: DictionaryViewModel by viewModels {
        DictionaryViewModel.Factory(customTermRepository = CustomTermRepository(applicationContext), appContext = applicationContext)
    }
    private val polishViewModel: PolishSettingsViewModel by viewModels {
        PolishSettingsViewModel.Factory(
            providerRepository = ProviderConfigurationRepository(applicationContext),
            modelCache = ModelListCache(applicationContext),
        )
    }
    private val readinessViewModel: ReadinessViewModel by viewModels {
        ReadinessViewModel.Factory(appContext = applicationContext)
    }
    private val modelWorkViewModel: ModelWorkViewModel by viewModels {
        ModelWorkViewModel.Factory(appContext = applicationContext, readiness = readinessViewModel.state)
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        readinessViewModel.refreshPermissions()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        readinessViewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val thirdPartyNotices = runCatching {
            assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Third-party notices are unavailable in this build." }
        // A finished model work refreshes readiness while the activity is started (#255).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { collectModelRefresh(modelWorkViewModel.finished, ::refreshReadiness) }
        }

        setContent {
            val shell by shellViewModel.state.collectAsStateWithLifecycle()
            val readiness by readinessViewModel.state.collectAsStateWithLifecycle()
            val history by historyViewModel.state.collectAsStateWithLifecycle()
            val dictionary by dictionaryViewModel.state.collectAsStateWithLifecycle()
            val polish by polishViewModel.settings.collectAsStateWithLifecycle()
            val discovery by polishViewModel.providerDiscovery.collectAsStateWithLifecycle()
            val models by modelWorkViewModel.models.collectAsStateWithLifecycle()

            EnviousWisprTheme(dynamicColor = shell.preferences.dynamicColorEnabled) {
                val actions = remember(shellViewModel, historyViewModel, dictionaryViewModel, polishViewModel, readinessViewModel, modelWorkViewModel) {
                    AppActions(
                        shell = ShellActions(
                            onStartDictation = {
                                startActivity(Intent(this, VoiceInputActivity::class.java).putExtra(VoiceInputActivity.EXTRA_TRIGGER_SOURCE, TriggerSource.APP.wire))
                            },
                            onRefreshReadiness = ::refreshReadiness,
                            onShowModels = modelWorkViewModel::show,
                        ),
                        permissions = PermissionActions(
                            onRequestMicrophone = {
                                requestPermissionWithRecovery(Manifest.permission.RECORD_AUDIO) { microphonePermission.launch(it) }
                            },
                            onRequestNotifications = {
                                if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissionWithRecovery(Manifest.permission.POST_NOTIFICATIONS) { notificationPermission.launch(it) } else refreshReadiness()
                            },
                            onOpenAccessibility = {
                                startActivity(Intent(this, AccessibilityGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                        ),
                        onboarding = OnboardingActions(
                            onStep = shellViewModel::setOnboardingStep,
                            onDismiss = shellViewModel::dismissOnboarding,
                            onResume = shellViewModel::resumeOnboarding,
                            onComplete = shellViewModel::completeOnboarding,
                        ),
                        history = HistoryActions(
                            onSearchChange = historyViewModel::updateHistorySearch,
                            onKeep = historyViewModel::setHistoryKept,
                            onDelete = historyViewModel::deleteHistory,
                            onDeleteAll = historyViewModel::deleteAllHistory,
                        ),
                        dictionary = DictionaryActions(
                            onSearchChange = dictionaryViewModel::updateCustomTermSearch,
                            onAdd = dictionaryViewModel::addCustomTerm,
                            onEdit = dictionaryViewModel::editCustomTerm,
                            onDelete = dictionaryViewModel::deleteCustomTerm,
                            onBulkDelete = dictionaryViewModel::bulkDeleteCustomTerms,
                            onImport = dictionaryViewModel::importCustomTerms,
                        ),
                        transcription = TranscriptionActions(
                            onFillerRemovalChanged = shellViewModel::setFillerRemovalEnabled,
                            onEmojiFormatterChanged = shellViewModel::setEmojiFormatterEnabled,
                            onSpokenPunctuationChanged = shellViewModel::setSpokenPunctuationEnabled,
                            onAutoStopOnSilenceChanged = shellViewModel::setAutoStopOnSilenceEnabled,
                            onSilencePauseSecondsChanged = shellViewModel::setSilencePauseSeconds,
                        ),
                        polish = PolishActions(
                            onSetMode = polishViewModel::setPolishMode,
                            onSetS1Control = polishViewModel::setS1Control,
                            onSaveProviderSettings = polishViewModel::saveProviderSettings,
                            onClearProvider = polishViewModel::removeProviderKey,
                            onCheckKey = polishViewModel::discoverModels,
                            onKeyDraftChanged = polishViewModel::keyDraftChanged,
                            onLoadCachedModels = polishViewModel::loadCachedModels,
                        ),
                        microphone = MicrophoneActions(
                            onInputDevicePickChanged = shellViewModel::setInputDevicePick,
                            onShowBluetoothTipsChanged = shellViewModel::setShowBluetoothTips,
                            onKeepEarbudsReadyChanged = shellViewModel::setKeepEarbudsReady,
                        ),
                        clipboard = ClipboardActions(
                            onAutoCopyChanged = shellViewModel::setAutoCopyToClipboard,
                            onRestoreClipboardChanged = shellViewModel::setRestoreClipboardAfterPaste,
                            onSmartInsertionChanged = shellViewModel::setSmartInsertionEnabled,
                        ),
                        appearance = AppearanceActions(
                            onDynamicColorChanged = shellViewModel::setDynamicColorEnabled,
                            onBubbleLookChanged = shellViewModel::setBubbleLook,
                        ),
                    )
                }
                EnviousWisprApp(
                    state = AppUiState(
                        shell = shell,
                        readiness = readiness,
                        history = history,
                        dictionary = dictionary,
                        polish = polish,
                        discovery = discovery,
                        models = models,
                    ),
                    licenseNotices = thirdPartyNotices,
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
    }

    private fun requestPermissionWithRecovery(permission: String, request: (String) -> Unit) {
        val requested = getSharedPreferences("permission_requests", MODE_PRIVATE)
        val denied = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (denied && requested.getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        } else {
            requested.edit().putBoolean(permission, true).apply()
            request(permission)
        }
    }

    private fun refreshReadiness() {
        readinessViewModel.refreshPermissions()
        val generation = ++modelReadinessGeneration
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readAppReadiness(this@SettingsActivity) }
            if (generation == modelReadinessGeneration) readinessViewModel.updateVerifiedModels(snapshot)
        }
    }
}
