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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            var showLicenses by remember { mutableStateOf(false) }

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
                    onOpenLicenses = { showLicenses = true },
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
                    onVocabularyEnabledChanged = viewModel::setVocabularyEnabled,
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

                if (showLicenses) {
                    AlertDialog(
                        onDismissRequest = { showLicenses = false },
                        title = { Text("Open-source licenses") },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.72f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    thirdPartyNotices,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLicenses = false }) {
                                Text("Done")
                            }
                        },
                    )
                }
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
