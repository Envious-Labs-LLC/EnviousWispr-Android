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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.ui.theme.EnviousWisprTheme
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.vocabulary.CustomTermRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private var modelReadinessGeneration = 0L
    private val viewModel: EnviousWisprViewModel by viewModels {
        EnviousWisprViewModel.Factory(
            appPreferences = AppPreferences(applicationContext),
            repository = TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao()),
            customTermRepository = CustomTermRepository(applicationContext),
            providerRepository = ProviderConfigurationRepository(applicationContext),
            appContext = applicationContext,
        )
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val thirdPartyNotices = runCatching {
            assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Third-party notices are unavailable in this build." }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val providerDiscovery by viewModel.providerDiscovery.collectAsStateWithLifecycle()

            EnviousWisprTheme(dynamicColor = uiState.preferences.dynamicColorEnabled) {
                EnviousWisprApp(
                    uiState = uiState,
                    onStartDictation = {
                        startActivity(Intent(this, VoiceInputActivity::class.java))
                    },
                    onRequestMicrophone = {
                        requestPermissionWithRecovery(Manifest.permission.RECORD_AUDIO) { microphonePermission.launch(it) }
                    },
                    onRequestNotifications = {
                        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissionWithRecovery(Manifest.permission.POST_NOTIFICATIONS) { notificationPermission.launch(it) } else refreshReadiness()
                    },
                    onOpenAccessibility = {
                        startActivity(Intent(this, AccessibilityGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    licenseNotices = thirdPartyNotices,
                    onOnboardingStep = viewModel::setOnboardingStep,
                    onDismissOnboarding = viewModel::dismissOnboarding,
                    onResumeOnboarding = viewModel::resumeOnboarding,
                    onCompleteOnboarding = viewModel::completeOnboarding,
                    onCustomTermSearchChange = viewModel::updateCustomTermSearch,
                    onAddCustomTerm = viewModel::addCustomTerm,
                    onEditCustomTerm = viewModel::editCustomTerm,
                    onDeleteCustomTerm = viewModel::deleteCustomTerm,
                    onBulkDeleteCustomTerms = viewModel::bulkDeleteCustomTerms,
                    onImportCustomTerms = viewModel::importCustomTerms,
                    onFillerRemovalChanged = viewModel::setFillerRemovalEnabled,
                    onEmojiFormatterChanged = viewModel::setEmojiFormatterEnabled,
                    onSpokenPunctuationChanged = viewModel::setSpokenPunctuationEnabled,
                    onAutoStopOnSilenceChanged = viewModel::setAutoStopOnSilenceEnabled,
                    onSilencePauseSecondsChanged = viewModel::setSilencePauseSeconds,
                    onAutoCopyChanged = viewModel::setAutoCopyToClipboard,
                    onRestoreClipboardChanged = viewModel::setRestoreClipboardAfterPaste,
                    onSmartInsertionChanged = viewModel::setSmartInsertionEnabled,
                    onDynamicColorChanged = viewModel::setDynamicColorEnabled,
                    onSetPolishMode = viewModel::setPolishMode,
                    onSaveProviderSettings = viewModel::saveProviderSettings,
                    onClearProviderSettings = viewModel::removeProviderKey,
                    providerDiscovery = providerDiscovery,
                    onCheckKey = viewModel::discoverModels,
                    onKeyDraftChanged = viewModel::keyDraftChanged,
                    onLoadCachedModels = viewModel::loadCachedModels,
                    onHistorySearchChange = viewModel::updateHistorySearch,
                    onKeepHistory = viewModel::setHistoryKept,
                    onDeleteHistory = viewModel::deleteHistory,
                    onDeleteAllHistory = viewModel::deleteAllHistory,
                    onRefreshReadiness = ::refreshReadiness,
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
        viewModel.refreshPermissions()
        val generation = ++modelReadinessGeneration
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readAppReadiness(this@SettingsActivity) }
            if (generation == modelReadinessGeneration) viewModel.updateVerifiedModels(snapshot)
        }
    }
}
