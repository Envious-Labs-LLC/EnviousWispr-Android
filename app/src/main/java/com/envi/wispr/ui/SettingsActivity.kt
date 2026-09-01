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
        refreshReadiness()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshReadiness()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val thirdPartyNotices = runCatching {
            assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "Third-party notices are unavailable in this build." }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            EnviousWisprTheme(dynamicColor = uiState.preferences.dynamicColorEnabled) {
                EnviousWisprApp(
                    uiState = uiState,
                    onStartDictation = {
                        startActivity(Intent(this, VoiceInputActivity::class.java))
                    },
                    onRequestMicrophone = {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestNotifications = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                    onAutoCopyChanged = viewModel::setAutoCopyToClipboard,
                    onRestoreClipboardChanged = viewModel::setRestoreClipboardAfterPaste,
                    onSmartInsertionChanged = viewModel::setSmartInsertionEnabled,
                    onDynamicColorChanged = viewModel::setDynamicColorEnabled,
                    onSetPolishMode = viewModel::setPolishMode,
                    onSaveProviderSettings = viewModel::saveProviderSettings,
                    onClearProviderSettings = viewModel::clearProviderSettings,
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

    private fun refreshReadiness() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readAppReadiness(this@SettingsActivity) }
            viewModel.updateReadiness(snapshot)
        }
    }
}
