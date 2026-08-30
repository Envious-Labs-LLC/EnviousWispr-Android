package com.envi.wispr.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.envi.wispr.polish.S1Config
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDeliveryControlStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.models.modelUiState
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionOutcomeMessages
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfiguration
import com.envi.wispr.providers.ProviderConfigurationValidator
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.ValidationReason
import com.envi.wispr.providers.ValidationResult
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.disclosure
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermAuthoring
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.MatchStrictness
import com.envi.wispr.vocabulary.VocabularyTransfer
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination(val label: String) {
    Home("Home"),
    History("History"),
    Words("Your Words"),
    Models("Models"),
    Settings("Settings"),
}

@Composable
fun EnviousWisprApp(
    uiState: EnviousWisprUiState,
    onStartDictation: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOnboardingStep: (Int) -> Unit,
    onDismissOnboarding: () -> Unit,
    onResumeOnboarding: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onCustomTermSearchChange: (String) -> Unit,
    onAddCustomTerm: (CustomTerm) -> Unit,
    onEditCustomTerm: (CustomTermRecord, CustomTerm) -> Unit,
    onDeleteCustomTerm: (CustomTermRecord) -> Unit,
    onBulkDeleteCustomTerms: (Set<Long>) -> Unit,
    onImportCustomTerms: (String) -> Unit,
    onVocabularyEnabledChanged: (Boolean) -> Unit,
    onFillerRemovalChanged: (Boolean) -> Unit,
    onEmojiFormatterChanged: (Boolean) -> Unit,
    onSpokenPunctuationChanged: (Boolean) -> Unit,
    onAutoCopyChanged: (Boolean) -> Unit,
    onRestoreClipboardChanged: (Boolean) -> Unit,
    onSmartInsertionChanged: (Boolean) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onSetPolishMode: (PolishMode) -> Unit,
    onSaveProviderSettings: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProviderSettings: () -> Unit,
    onHistorySearchChange: (String) -> Unit,
    onKeepHistory: (TranscriptEntity) -> Unit,
    onDeleteHistory: (TranscriptEntity) -> Unit,
    onDeleteAllHistory: () -> Unit,
    onRefreshReadiness: () -> Unit,
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

    ModelWorkReadinessObserver(onRefreshReadiness)

    if (uiState.shouldShowOnboarding) {
        OnboardingScreen(
            step = uiState.preferences.onboardingStep,
            readiness = uiState.readiness,
            autoPaste = uiState.autoPaste,
            onStepChange = onOnboardingStep,
            onDismiss = onDismissOnboarding,
            onRequestMicrophone = onRequestMicrophone,
            onRequestNotifications = onRequestNotifications,
            onOpenAccessibility = onOpenAccessibility,
            onPractice = onStartDictation,
            onComplete = onCompleteOnboarding,
        )
        return
    }

    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
        ?: AppDestination.Home
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = if (LocalConfiguration.current.screenWidthDp >= 600) {
        NavigationSuiteType.NavigationRail
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }

    NavigationSuiteScaffold(
        modifier = Modifier.fillMaxSize(),
        layoutType = layoutType,
        navigationSuiteItems = {
            AppDestination.entries.forEach { item ->
                item(
                    selected = item == destination,
                    onClick = { destinationName = item.name },
                    icon = {
                        DestinationIcon(
                            destination = item,
                            selected = item == destination,
                        )
                    },
                    label = { Text(item.label) },
                )
            }
        },
    ) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "destination",
        ) { current ->
            when (current) {
                AppDestination.Home -> HomeScreen(
                    readiness = uiState.readiness,
                    autoPaste = uiState.autoPaste,
                    onStartDictation = onStartDictation,
                    onContinueSetup = onResumeOnboarding,
                    onOpenAccessibility = onOpenAccessibility,
                )
                AppDestination.History -> HistoryScreen(
                    transcripts = uiState.history,
                    totalCount = uiState.historyTotalCount,
                    search = uiState.historySearch,
                    error = uiState.historyError,
                    onSearchChange = onHistorySearchChange,
                    onKeep = onKeepHistory,
                    onDelete = onDeleteHistory,
                    onDeleteAll = onDeleteAllHistory,
                )
                AppDestination.Words -> WordsScreen(
                    terms = uiState.customTerms,
                    allTerms = uiState.allCustomTerms,
                    search = uiState.customTermSearch,
                    message = uiState.customTermMessage,
                    error = uiState.customTermError,
                    enabled = uiState.preferences.vocabularyEnabled,
                    onSearchChange = onCustomTermSearchChange,
                    onAdd = onAddCustomTerm,
                    onEdit = onEditCustomTerm,
                    onDelete = onDeleteCustomTerm,
                    onBulkDelete = onBulkDeleteCustomTerms,
                    onImport = onImportCustomTerms,
                    onEnabledChange = onVocabularyEnabledChanged,
                )
                AppDestination.Models -> ModelsScreen(readiness = uiState.readiness, onRefreshReadiness = onRefreshReadiness)
                AppDestination.Settings -> SettingsScreen(
                    uiState = uiState,
                    onContinueSetup = onResumeOnboarding,
                    onRequestNotifications = onRequestNotifications,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenLicenses = onOpenLicenses,
                    onDynamicColorChanged = onDynamicColorChanged,
                    onFillerRemovalChanged = onFillerRemovalChanged,
                    onEmojiFormatterChanged = onEmojiFormatterChanged,
                    onSpokenPunctuationChanged = onSpokenPunctuationChanged,
                    onAutoCopyChanged = onAutoCopyChanged,
                    onRestoreClipboardChanged = onRestoreClipboardChanged,
                    onSmartInsertionChanged = onSmartInsertionChanged,
                    onSetPolishMode = onSetPolishMode,
                    onSaveProviderSettings = onSaveProviderSettings,
                    onClearProviderSettings = onClearProviderSettings,
                )
            }
        }
    }
}

@Composable
private fun DestinationIcon(destination: AppDestination, selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = destination.label },
    ) {
        val stroke = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
        when (destination) {
            AppDestination.Home -> {
                val path = Path().apply {
                    moveTo(size.width * 0.14f, size.height * 0.49f)
                    lineTo(size.width * 0.50f, size.height * 0.18f)
                    lineTo(size.width * 0.86f, size.height * 0.49f)
                    moveTo(size.width * 0.25f, size.height * 0.43f)
                    lineTo(size.width * 0.25f, size.height * 0.82f)
                    lineTo(size.width * 0.75f, size.height * 0.82f)
                    lineTo(size.width * 0.75f, size.height * 0.43f)
                }
                drawPath(path, color, style = stroke)
            }
            AppDestination.History -> {
                drawCircle(color, radius = size.minDimension * 0.34f, style = stroke)
                drawLine(
                    color,
                    Offset(size.width * 0.50f, size.height * 0.50f),
                    Offset(size.width * 0.50f, size.height * 0.29f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.50f, size.height * 0.50f),
                    Offset(size.width * 0.66f, size.height * 0.60f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            AppDestination.Words -> {
                drawLine(color, Offset(size.width * 0.24f, size.height * 0.78f), Offset(size.width * 0.47f, size.height * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.47f, size.height * 0.22f), Offset(size.width * 0.69f, size.height * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.31f, size.height * 0.59f), Offset(size.width * 0.62f, size.height * 0.59f), stroke.width, StrokeCap.Round)
                drawCircle(color, size.minDimension * 0.055f, Offset(size.width * 0.78f, size.height * 0.29f))
            }
            AppDestination.Models -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.10f),
                    style = stroke,
                )
                repeat(3) { index ->
                    val fraction = 0.34f + index * 0.16f
                    drawLine(color, Offset(size.width * fraction, size.height * 0.10f), Offset(size.width * fraction, size.height * 0.22f), stroke.width, StrokeCap.Round)
                    drawLine(color, Offset(size.width * fraction, size.height * 0.78f), Offset(size.width * fraction, size.height * 0.90f), stroke.width, StrokeCap.Round)
                }
            }
            AppDestination.Settings -> {
                listOf(0.29f, 0.50f, 0.71f).forEachIndexed { index, y ->
                    drawLine(color, Offset(size.width * 0.18f, size.height * y), Offset(size.width * 0.82f, size.height * y), stroke.width, StrokeCap.Round)
                    val x = listOf(0.38f, 0.66f, 0.47f)[index]
                    drawCircle(surfaceColor, size.minDimension * 0.10f, Offset(size.width * x, size.height * y))
                    drawCircle(color, size.minDimension * 0.10f, Offset(size.width * x, size.height * y), style = stroke)
                }
            }
        }
    }
}

@Composable
private fun ScreenContainer(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    onStartDictation: () -> Unit,
    onContinueSetup: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val view = LocalView.current
    ScreenContainer(
        title = "EnviousWispr",
        subtitle = "Private dictation, polished on your phone.",
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        ),
                    )
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    if (readiness.coreReady) "Ready when you are" else "Finish setup to get the full experience",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (readiness.coreReady) {
                        "Tap once, speak naturally, and your words are cleaned up locally."
                    } else {
                        "Your local models are safe. A few phone permissions still need attention."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                LargeFloatingActionButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onStartDictation()
                    },
                    modifier = Modifier
                        .size(96.dp)
                        .semantics {
                            contentDescription = "Start dictation"
                            role = Role.Button
                        },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    MicrophoneGlyph(Modifier.size(38.dp))
                }
                Text(
                    "Start dictation",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (!readiness.coreReady || autoPaste == AutoPasteAvailability.NOT_PERMITTED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(ready = false)
                    Column(Modifier.weight(1f)) {
                        Text("Setup needs attention", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Complete the guided checks so dictation can start and insert text anywhere.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FilledTonalButton(onClick = onContinueSetup) {
                        Text("Continue")
                    }
                }
            }
        }

        // A separate, CALMER card, and calmer has to be visible or the split is only in the source.
        // The permission is granted, so routing the user back to grant it would be a wrong
        // instruction, and the service is legitimately unbound for a moment at every cold start:
        // firing the same red alarm through that window would train the user to ignore it.
        // Suppressed entirely while the setup card above is showing, so the screen never carries
        // two alarm cards for one unfinished setup.
        if (readiness.coreReady && autoPaste == AutoPasteAvailability.PERMITTED_NOT_RUNNING) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(ready = false, description = autoPaste.statusDescription())
                    Column(Modifier.weight(1f)) {
                        Text("Auto-paste is not connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Your words go to the clipboard until it reconnects. If it stays " +
                                "disconnected, turn EnviousWispr off and then on in Accessibility settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FilledTonalButton(onClick = onOpenAccessibility) {
                        Text("Accessibility settings")
                    }
                }
            }
        }

        Text("Readiness", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReadinessChip("Microphone", readiness.microphoneGranted)
            ReadinessChip("Speech", readiness.speechModelReady)
            ReadinessChip("Polish", readiness.polishModelReady)
            ReadinessChip(
                label = "Insert",
                ready = autoPaste == AutoPasteAvailability.LIVE,
                description = autoPaste.statusDescription(),
            )
        }

        Card {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Fast offline setup", style = MaterialTheme.typography.titleMedium)
                        Text("Parakeet speech engine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (readiness.speechModelReady) "Ready" else "Missing", readiness.speechModelReady)
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Local AI polish", style = MaterialTheme.typography.titleMedium)
                        Text("${S1Config.MODEL_NAME} by ${S1Config.MODEL_CREATOR}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (readiness.polishModelReady) "Ready" else "Missing", readiness.polishModelReady)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    transcripts: List<TranscriptEntity>,
    totalCount: Int,
    search: String,
    error: String?,
    onSearchChange: (String) -> Unit,
    onKeep: (TranscriptEntity) -> Unit,
    onDelete: (TranscriptEntity) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TranscriptEntity?>(null) }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("History", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text("Your recent dictations will stay private on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(value = search, onValueChange = onSearchChange, modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp), label = { Text("Search history") }, singleLine = true)
        }
        if (error != null) {
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp)) }
        }
        if (transcripts.isEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (search.isBlank()) "No dictations yet" else "No matching dictations", style = MaterialTheme.typography.titleLarge)
                        Text("Completed dictations stay on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().widthIn(max = 900.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                }
            }
            items(transcripts, key = { it.id }) { transcript ->
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (transcript.status != TranscriptEntity.STATUS_COMPLETED) {
                            Text(
                                InsertionOutcomeMessages.historyStatusLine(
                                    transcript.status,
                                    transcript.insertionResult,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text("Final", style = MaterialTheme.typography.labelMedium)
                        Text(
                            transcript.finalText.ifBlank { "No finalized text yet" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text("Original", style = MaterialTheme.typography.labelMedium)
                        Text(transcript.originalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(transcript.createdAtMs))} · ${transcript.speechEngine}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val copied = runCatching {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("EnviousWispr", transcript.finalText))
                                        ?: error("Clipboard unavailable")
                                }.isSuccess
                                // This row now owns the clipboard, so the standing "press and hold,
                                // then tap Paste" claim about an earlier dictation is false. The app
                                // made this replacement itself and knows it succeeded, which is the
                                // only reason it can be retracted at all.
                                if (copied) {
                                    DictationNotificationController.dismissWordsNotInserted(context)
                                }
                                Toast.makeText(context, if (copied) "Copied" else "Unable to copy", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy") }
                            TextButton(onClick = { onKeep(transcript) }) { Text(if (transcript.kept) "Unkeep" else "Keep") }
                            TextButton(onClick = { confirmDelete = transcript }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { transcript ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this dictation?") },
            text = { Text("This removes it from local history.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(transcript) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false }, title = { Text("Delete all history?") }, text = { Text("This permanently removes all $totalCount saved dictations from this phone.") }, confirmButton = { TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("Delete all") } }, dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } })
}

@Composable
private fun WordsScreen(
    terms: List<CustomTermRecord>,
    allTerms: List<CustomTermRecord>,
    search: String,
    message: String,
    error: String?,
    enabled: Boolean,
    onSearchChange: (String) -> Unit,
    onAdd: (CustomTerm) -> Unit,
    onEdit: (CustomTermRecord, CustomTerm) -> Unit,
    onDelete: (CustomTermRecord) -> Unit,
    onBulkDelete: (Set<Long>) -> Unit,
    onImport: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readNBytes(2_000_001)
                        require(bytes.size <= 2_000_000) { "That vocabulary file is too large." }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("That vocabulary file could not be opened.")
                }
            }
            imported.onSuccess(onImport).onFailure { failure ->
                Toast.makeText(context, failure.message ?: "Unable to read vocabulary", Toast.LENGTH_LONG).show()
            }
        }
    }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var showNewEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomTermRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomTermRecord?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    ScreenContainer(
        title = "Your Words",
        subtitle = "Teach EnviousWispr names, aliases, products, and exact spelling.",
    ) {
        Card {
            SettingsToggleRow(
                title = "Use custom vocabulary",
                subtitle = if (enabled) "Applied to new dictations" else "Saved terms are currently ignored",
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search spelling or alias") },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { showNewEditor = true }) { Text("Add term") }
            OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain")) }) {
                Text("Import file")
            }
            OutlinedButton(onClick = { showImport = true }) { Text("Paste import") }
            OutlinedButton(
                onClick = {
                    val exported = VocabularyTransfer.export(allTerms.map(CustomTermRecord::term))
                    val copied = runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("EnviousWispr vocabulary", exported))
                            ?: error("Clipboard unavailable")
                    }.isSuccess
                    // Vocabulary JSON is now on the clipboard, so any standing claim that a
                    // dictation is waiting there to be pasted is false.
                    if (copied) {
                        DictationNotificationController.dismissWordsNotInserted(context)
                    }
                    Toast.makeText(
                        context,
                        if (copied) "Vocabulary copied" else "Unable to export vocabulary",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) { Text("Export") }
            if (selectedIds.isNotEmpty()) {
                OutlinedButton(onClick = { confirmBulkDelete = true }) {
                    Text("Delete ${selectedIds.size}")
                }
            }
        }
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (terms.isEmpty()) {
            ElevatedCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (search.isBlank()) "No custom terms yet" else "No matching terms",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Add a preferred spelling and optional aliases.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            terms.forEach { record ->
                val term = record.term
                ElevatedCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = record.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + record.id
                                    else selectedIds - record.id
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(term.spelling, style = MaterialTheme.typography.titleMedium)
                                val details = buildList {
                                    if (term.aliases.isNotEmpty()) add("${term.aliases.size} aliases")
                                    if (term.usageCount > 0) add("used ${term.usageCount} times")
                                }.joinToString(" · ")
                                if (details.isNotBlank()) {
                                    Text(
                                        details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { editTarget = record }) { Text("Edit") }
                                TextButton(onClick = { deleteTarget = record }) { Text("Delete") }
                            }
                        }
                        if (term.aliases.isNotEmpty()) {
                            Text(
                                "Aliases: ${term.aliases.joinToString()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewEditor || editTarget != null) {
        CustomTermEditorDialog(
            record = editTarget,
            onDismiss = {
                showNewEditor = false
                editTarget = null
            },
            onSave = { term ->
                editTarget?.let { onEdit(it, term) } ?: onAdd(term)
                showNewEditor = false
                editTarget = null
            },
        )
    }
    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${record.term.spelling}?") },
            text = { Text("This removes the term and its aliases from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds -= record.id
                    onDelete(record)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${selectedIds.size} custom terms?") },
            text = { Text("This permanently removes the selected terms from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onBulkDelete(selectedIds)
                    selectedIds = emptySet()
                    confirmBulkDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } },
        )
    }
    if (showImport) {
        VocabularyImportDialog(
            onDismiss = { showImport = false },
            onImport = {
                onImport(it)
                showImport = false
            },
        )
    }
}

@Composable
private fun CustomTermEditorDialog(
    record: CustomTermRecord?,
    onDismiss: () -> Unit,
    onSave: (CustomTerm) -> Unit,
) {
    val existing = record?.term
    var spelling by remember(record?.id) { mutableStateOf(existing?.spelling.orEmpty()) }
    var aliases by remember(record?.id) { mutableStateOf(existing?.aliases.orEmpty()) }
    var newAlias by remember(record?.id) { mutableStateOf("") }
    var matchStrictness by remember(record?.id) {
        mutableStateOf(MatchStrictness.from(existing?.minSimilarityOverride))
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record == null) "Add custom term" else "Edit custom term") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = spelling,
                    onValueChange = { spelling = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preferred spelling") },
                    singleLine = true,
                )
                Text("What EnviousWispr might hear", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add each misspelling or alternate phrase that should become the preferred spelling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                aliases.forEachIndexed { index, alias ->
                    ElevatedCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(alias, modifier = Modifier.weight(1f))
                            TextButton(onClick = { aliases = aliases.toMutableList().also { it.removeAt(index) } }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newAlias,
                        onValueChange = { newAlias = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Misspelling or alias") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            val updated = CustomTermAuthoring.includePendingAlias(aliases, newAlias)
                            if (updated !== aliases) {
                                aliases = updated
                                newAlias = ""
                            }
                        },
                        enabled = newAlias.isNotBlank(),
                    ) { Text("Add") }
                }
                Text("Match strictness", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MatchStrictness.entries.forEach { strictness ->
                        FilterChip(
                            selected = matchStrictness == strictness,
                            onClick = { matchStrictness = strictness },
                            label = { Text(strictness.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(onClick = {
                val normalizedSpelling = spelling.trim()
                val savedAliases = CustomTermAuthoring.includePendingAlias(aliases, newAlias)
                when {
                    normalizedSpelling.isEmpty() -> error = "Enter a preferred spelling."
                    normalizedSpelling.length > 200 -> error = "Preferred spelling must be 200 characters or fewer."
                    else -> onSave(
                        CustomTerm(
                            spelling = normalizedSpelling,
                            aliases = savedAliases,
                            category = existing?.category,
                            priority = existing?.priority ?: 0,
                            forceReplace = existing?.forceReplace ?: false,
                            caseSensitive = existing?.caseSensitive ?: false,
                            minSimilarityOverride = matchStrictness.thresholdOverride,
                            usageCount = existing?.usageCount ?: 0,
                            imported = existing?.imported ?: false,
                        ),
                    )
                }
            }) { Text("Save") }
        },
    )
}

@Composable
private fun VocabularyImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import vocabulary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste an EnviousWispr vocabulary export or one spelling per line.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    label = { Text("Vocabulary data") },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(onClick = { if (input.isNotBlank()) onImport(input) }, enabled = input.isNotBlank()) {
                Text("Import")
            }
        },
    )
}

@Composable
private fun ModelsScreen(readiness: AppReadiness, onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    val parakeetWork by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val parakeetAdoption by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Adoption by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val parakeetState = workUiState(preferredModelWork(parakeetWork, parakeetAdoption), readiness.speechModelReady, ModelManifest.parakeet, context)
    val s1State = workUiState(preferredModelWork(s1Work, s1Adoption), readiness.polishModelReady, ModelManifest.s1, context)
    ScreenContainer(
        title = "Models and AI",
        subtitle = "Choose what runs locally and what may use your own provider.",
    ) {
        ModelCard(
            eyebrow = "FAST SPEECH",
            title = "Parakeet",
            description = "Your current offline English speech engine. Fast, private, and already proven on this S26 Ultra.",
            state = parakeetState,
            facts = listOf("Offline", "4 threads", "Local audio"),
            onAction = {
                when (parakeetState.action) {
                    ModelUiAction.REMOVE -> ModelDeliveryWorker.enqueueRemove(context, ModelManifest.parakeet)
                    ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, ModelManifest.parakeet)
                    ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, ModelManifest.parakeet)
                    else -> ModelDeliveryWorker.enqueue(context, ModelManifest.parakeet)
                }
                onRefreshReadiness()
            },
            onPause = { ModelDeliveryWorker.pause(context, ModelManifest.parakeet) },
            onResume = { ModelDeliveryWorker.resume(context, ModelManifest.parakeet) },
        )
        ModelCard(
            eyebrow = "LOCAL POLISH",
            title = S1Config.MODEL_NAME,
            description = "The first-party local cleanup model by ${S1Config.MODEL_CREATOR}. Custom spelling corrections stay on device.",
            state = s1State,
            facts = listOf("Offline", "Q4_K_M", "Private text"),
            onAction = {
                when (s1State.action) {
                    ModelUiAction.REMOVE -> ModelDeliveryWorker.enqueueRemove(context, ModelManifest.s1)
                    ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, ModelManifest.s1)
                    ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, ModelManifest.s1)
                    else -> ModelDeliveryWorker.enqueue(context, ModelManifest.s1)
                }
                onRefreshReadiness()
            },
            onPause = { ModelDeliveryWorker.pause(context, ModelManifest.s1) },
            onResume = { ModelDeliveryWorker.resume(context, ModelManifest.s1) },
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Broad-language speech and cloud providers", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Whisper-family multilingual speech, OpenAI, Gemini, Claude, and self-hosted providers are separate parity slices. Dictation will always retain a safe local fallback.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    eyebrow: String,
    title: String,
    description: String,
    state: ModelUiState,
    facts: List<String>,
    onAction: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    ElevatedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                }
                StatusPill(state.label, state.label == "Ready")
            }
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                facts.forEach { fact -> AssistChip(onClick = {}, label = { Text(fact) }) }
            }
            if (state.total > 0L && (state.action == ModelUiAction.PAUSE || state.action == ModelUiAction.RESUME)) {
                LinearProgressIndicator(
                    progress = { (state.bytes.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatModelBytes(state.bytes)} of ${formatModelBytes(state.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state.action) {
                ModelUiAction.DOWNLOAD -> TextButton(onClick = onAction) { Text("Download") }
                ModelUiAction.RETRY -> TextButton(onClick = onAction) { Text("Retry") }
                ModelUiAction.PAUSE -> TextButton(onClick = onPause) { Text("Pause") }
                ModelUiAction.RESUME -> TextButton(onClick = onResume) { Text("Resume") }
                ModelUiAction.REPAIR -> TextButton(onClick = onAction) { Text("Repair") }
                ModelUiAction.REMOVE -> OutlinedButton(onClick = onAction) { Text("Remove") }
                ModelUiAction.UPDATE -> TextButton(onClick = onAction) { Text("Update") }
                ModelUiAction.CANCEL -> TextButton(onClick = onPause) { Text("Pause") }
                ModelUiAction.NONE -> Unit
            }
            state.reason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun workUiState(info: WorkInfo?, ready: Boolean, model: com.envi.wispr.models.ModelDescriptor, context: android.content.Context): ModelUiState {
    val progressState = info?.progress?.getString(ModelDeliveryWorker.KEY_STATE)
    val bytes = info?.progress?.getLong(ModelDeliveryWorker.KEY_BYTES, 0L) ?: 0L
    val total = info?.progress?.getLong(ModelDeliveryWorker.KEY_TOTAL, 0L) ?: 0L
    val reason = info?.outputData?.getString(ModelDeliveryWorker.KEY_REASON).orEmpty()
        .ifBlank { info?.progress?.getString(ModelDeliveryWorker.KEY_REASON).orEmpty() }
        .ifBlank { null }
    val controls = ModelDeliveryControlStore(com.envi.wispr.models.ModelStorage.root(context)).read(model)
    val stale = ModelDeliveryWorker.hasStaleInstallation(context, model)
    return modelUiState(ready, info?.state?.name, progressState, bytes, total, reason, controls.name, stale)
}

private fun preferredModelWork(download: List<WorkInfo>, adoption: List<WorkInfo>): WorkInfo? {
    val active = (download + adoption).firstOrNull { !it.state.isFinished }
    return active ?: download.firstOrNull() ?: adoption.firstOrNull()
}

@Composable
private fun ModelWorkReadinessObserver(onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val parakeetDownload by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Download by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val parakeetAdoption by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Adoption by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(
        parakeetDownload.firstOrNull()?.state,
        s1Download.firstOrNull()?.state,
        parakeetAdoption.firstOrNull()?.state,
        s1Adoption.firstOrNull()?.state,
    ) {
        if (listOf(parakeetDownload, s1Download, parakeetAdoption, s1Adoption)
                .flatten()
                .any { it.state.isFinished }) {
            onRefreshReadiness()
        }
    }
}

private fun formatModelBytes(bytes: Long): String = when {
    bytes >= 1_024L * 1_024L * 1_024L -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
    bytes >= 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    else -> "${bytes / 1_024L} KB"
}

@Composable
private fun SettingsScreen(
    uiState: EnviousWisprUiState,
    onContinueSetup: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenLicenses: () -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onFillerRemovalChanged: (Boolean) -> Unit,
    onEmojiFormatterChanged: (Boolean) -> Unit,
    onSpokenPunctuationChanged: (Boolean) -> Unit,
    onAutoCopyChanged: (Boolean) -> Unit,
    onRestoreClipboardChanged: (Boolean) -> Unit,
    onSmartInsertionChanged: (Boolean) -> Unit,
    onSetPolishMode: (PolishMode) -> Unit,
    onSaveProviderSettings: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProviderSettings: () -> Unit,
) {
    var showPolishSettings by rememberSaveable { mutableStateOf(false) }
    var showCleanupSettings by rememberSaveable { mutableStateOf(false) }
    var showClipboardSettings by rememberSaveable { mutableStateOf(false) }
    val polishReady = when (uiState.providerSettings.mode) {
        PolishMode.OFF -> true
        PolishMode.OFFLINE_S1 -> uiState.readiness.polishModelReady
        PolishMode.PROVIDER -> uiState.providerSettings.configured &&
            (!uiState.providerSettings.provider.capabilities().requiresApiKey ||
                uiState.providerSettings.credentialStored)
    }
    val polishSubtitle = when {
        uiState.providerSettings.loading -> "Checking polish settings"
        uiState.providerSettings.error != null -> uiState.providerSettings.error
        uiState.providerSettings.message.isNotBlank() -> uiState.providerSettings.message
        uiState.providerSettings.mode == PolishMode.OFF -> "Off; deterministic cleanup only"
        uiState.providerSettings.mode == PolishMode.OFFLINE_S1 -> if (polishReady) {
            "Offline S1-mini ready"
        } else {
            "Offline S1-mini needs its model"
        }
        uiState.providerSettings.configured ->
            "${uiState.providerSettings.provider.capabilities().displayName} selected"
        else -> "Provider needs setup"
    }
    ScreenContainer(
        title = "Settings",
        subtitle = "Make dictation feel exactly right for you.",
    ) {
        SettingsGroup("Appearance") {
            SettingsToggleRow(
                title = "Use Galaxy colors",
                subtitle = "Match this phone's wallpaper and system theme.",
                checked = uiState.preferences.dynamicColorEnabled,
                onCheckedChange = onDynamicColorChanged,
            )
        }
        SettingsGroup("System-wide dictation") {
            SettingsActionRow(
                title = "Recording controls",
                subtitle = if (uiState.readiness.notificationsGranted) {
                    "Stop and Cancel available in notifications"
                } else {
                    "Allow notifications for Stop and Cancel"
                },
                ready = uiState.readiness.notificationsGranted,
                onClick = onRequestNotifications,
            )
            HorizontalDivider()
            SettingsActionRow(
                title = "Auto-paste access",
                subtitle = when (uiState.autoPaste) {
                    AutoPasteAvailability.LIVE -> "Ready for right-button dictation"
                    AutoPasteAvailability.PERMITTED_NOT_RUNNING ->
                        "Turned on but not connected. Your words go to the clipboard until it reconnects."
                    AutoPasteAvailability.NOT_PERMITTED -> "Needs accessibility permission"
                },
                ready = uiState.autoPaste == AutoPasteAvailability.LIVE,
                statusDescription = uiState.autoPaste.statusDescription(),
                onClick = onOpenAccessibility,
            )
        }
        SettingsGroup("Product") {
            SettingsActionRow("Continue guided setup", "Resume from your saved step", false, onClick = onContinueSetup)
            HorizontalDivider()
            SettingsActionRow(
                "Text cleanup",
                "Filler words, spoken emoji, and punctuation",
                null,
            ) { showCleanupSettings = true }
            HorizontalDivider()
            SettingsActionRow("AI Polish", polishSubtitle, polishReady) {
                showPolishSettings = true
            }
            HorizontalDivider()
            SettingsActionRow("Microphone and sounds", "Routing, warm start, cues", null, onClick = {})
            HorizontalDivider()
            SettingsActionRow(
                "Clipboard and insertion",
                "Auto-copy, clipboard restore, and smart insertion",
                null,
            ) { showClipboardSettings = true }
        }
        SettingsGroup("About") {
            SettingsActionRow("Open-source licenses", "S1-mini, llama.cpp, sherpa-onnx, and more", null, onClick = onOpenLicenses)
            HorizontalDivider()
            SettingsActionRow("Version 0.1.0", "Android parity foundation", null, onClick = {})
        }
    }

    if (showPolishSettings) {
        PolishSettingsDialog(
            settings = uiState.providerSettings,
            onDismiss = { showPolishSettings = false },
            onSetMode = { mode ->
                onSetPolishMode(mode)
                showPolishSettings = false
            },
            onSaveProvider = { provider, model, endpoint, apiKey, protocol ->
                onSaveProviderSettings(provider, model, endpoint, apiKey, protocol)
                showPolishSettings = false
            },
            onClearProvider = {
                onClearProviderSettings()
                showPolishSettings = false
            },
        )
    }

    if (showCleanupSettings) {
        TextCleanupSettingsSheet(
            preferences = uiState.preferences,
            onDismiss = { showCleanupSettings = false },
            onFillerRemovalChanged = onFillerRemovalChanged,
            onEmojiFormatterChanged = onEmojiFormatterChanged,
            onSpokenPunctuationChanged = onSpokenPunctuationChanged,
        )
    }

    if (showClipboardSettings) {
        ClipboardInsertionSettingsSheet(
            preferences = uiState.preferences,
            onDismiss = { showClipboardSettings = false },
            onAutoCopyChanged = onAutoCopyChanged,
            onRestoreClipboardChanged = onRestoreClipboardChanged,
            onSmartInsertionChanged = onSmartInsertionChanged,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ClipboardInsertionSettingsSheet(
    preferences: com.envi.wispr.settings.AppPreferencesState,
    onDismiss: () -> Unit,
    onAutoCopyChanged: (Boolean) -> Unit,
    onRestoreClipboardChanged: (Boolean) -> Unit,
    onSmartInsertionChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    var autoCopy by rememberSaveable { mutableStateOf(preferences.autoCopyToClipboard) }
    var restoreClipboard by rememberSaveable { mutableStateOf(preferences.restoreClipboardAfterPaste) }
    var smartInsertion by rememberSaveable { mutableStateOf(preferences.smartInsertionEnabled) }
    LaunchedEffect(preferences.autoCopyToClipboard) { autoCopy = preferences.autoCopyToClipboard }
    LaunchedEffect(preferences.restoreClipboardAfterPaste) {
        restoreClipboard = preferences.restoreClipboardAfterPaste
    }
    LaunchedEffect(preferences.smartInsertionEnabled) {
        smartInsertion = preferences.smartInsertionEnabled
    }
    fun updateWithHaptic(value: Boolean, update: (Boolean) -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        update(value)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Clipboard and insertion", style = MaterialTheme.typography.headlineSmall)
            Text(
                "These choices are locked when recording starts, so changing one never alters a dictation already in progress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(28.dp),
            ) {
                SettingsToggleRow(
                    title = "Auto-copy to clipboard",
                    subtitle = "Keep completed text ready to paste when direct insertion is unavailable.",
                    checked = autoCopy,
                    onCheckedChange = {
                        autoCopy = it
                        updateWithHaptic(it, onAutoCopyChanged)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsToggleRow(
                    title = "Restore clipboard after paste",
                    subtitle = "Put back everything that was on your clipboard before automatic paste.",
                    checked = restoreClipboard,
                    onCheckedChange = {
                        restoreClipboard = it
                        updateWithHaptic(it, onRestoreClipboardChanged)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsToggleRow(
                    title = "Smart insertion",
                    subtitle = "Match spacing and capitalization to the text around your cursor.",
                    checked = smartInsertion,
                    onCheckedChange = {
                        smartInsertion = it
                        updateWithHaptic(it, onSmartInsertionChanged)
                    },
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TextCleanupSettingsSheet(
    preferences: com.envi.wispr.settings.AppPreferencesState,
    onDismiss: () -> Unit,
    onFillerRemovalChanged: (Boolean) -> Unit,
    onEmojiFormatterChanged: (Boolean) -> Unit,
    onSpokenPunctuationChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    var fillerEnabled by rememberSaveable { mutableStateOf(preferences.fillerRemovalEnabled) }
    var emojiEnabled by rememberSaveable { mutableStateOf(preferences.emojiFormatterEnabled) }
    var punctuationEnabled by rememberSaveable { mutableStateOf(preferences.spokenPunctuationEnabled) }
    LaunchedEffect(preferences.fillerRemovalEnabled) {
        fillerEnabled = preferences.fillerRemovalEnabled
    }
    LaunchedEffect(preferences.emojiFormatterEnabled) {
        emojiEnabled = preferences.emojiFormatterEnabled
    }
    LaunchedEffect(preferences.spokenPunctuationEnabled) {
        punctuationEnabled = preferences.spokenPunctuationEnabled
    }
    fun updateWithHaptic(value: Boolean, update: (Boolean) -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        update(value)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Text cleanup", style = MaterialTheme.typography.headlineSmall)
            Text(
                "These private rules run on your phone before AI Polish. Turn off anything you prefer to dictate literally.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(28.dp),
            ) {
                SettingsToggleRow(
                    title = "Remove filler words",
                    subtitle = "Remove pauses such as um and uh.",
                    checked = fillerEnabled,
                    onCheckedChange = {
                        fillerEnabled = it
                        updateWithHaptic(it, onFillerRemovalChanged)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsToggleRow(
                    title = "Spoken emoji",
                    subtitle = "Turn explicit commands such as thumbs up emoji into symbols.",
                    checked = emojiEnabled,
                    onCheckedChange = {
                        emojiEnabled = it
                        updateWithHaptic(it, onEmojiFormatterChanged)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsToggleRow(
                    title = "Spoken punctuation",
                    subtitle = "Turn commands such as comma and new paragraph into punctuation.",
                    checked = punctuationEnabled,
                    onCheckedChange = {
                        punctuationEnabled = it
                        updateWithHaptic(it, onSpokenPunctuationChanged)
                    },
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun PolishSettingsDialog(
    settings: ProviderSettingsUiState,
    onDismiss: () -> Unit,
    onSetMode: (PolishMode) -> Unit,
    onSaveProvider: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProvider: () -> Unit,
) {
    var mode by remember(settings.mode) { mutableStateOf(settings.mode) }
    var provider by remember(settings.provider) { mutableStateOf(settings.provider) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var endpoint by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var protocol by remember(settings.selfHostedProtocol) {
        mutableStateOf(settings.selfHostedProtocol)
    }
    // A credential draft is deliberately not saveable and never enters the ViewModel state.
    var apiKey by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Polish") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Choose what happens after private, on-device transcription.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        PolishMode.OFF to "Off",
                        PolishMode.OFFLINE_S1 to "Offline S1",
                        PolishMode.PROVIDER to "Provider",
                    ).forEach { (option, label) ->
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                localError = null
                            },
                            label = { Text(label) },
                        )
                    }
                }

                when (mode) {
                    PolishMode.OFF -> Text(
                        "No language model runs. Deterministic cleanup still removes obvious filler and spacing issues.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PolishMode.OFFLINE_S1 -> Text(
                        "S1-mini polishes entirely on this phone. Dictated text does not leave the device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PolishMode.PROVIDER -> {
                        Text("Provider", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Provider.entries.forEach { option ->
                                FilterChip(
                                    selected = provider == option,
                                    onClick = {
                                        if (provider != option) {
                                            provider = option
                                            model = ""
                                            endpoint = ""
                                            apiKey = ""
                                        }
                                        localError = null
                                    },
                                    label = { Text(option.capabilities().displayName) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = {
                                model = it
                                localError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Model ID") },
                            supportingText = { Text("Use the exact model name from your provider.") },
                            singleLine = true,
                        )
                        if (provider.capabilities().requiresApiKey) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API key") },
                                placeholder = if (settings.credentialStored && provider == settings.provider) {
                                    { Text("Leave blank to keep saved key") }
                                } else null,
                                supportingText = {
                                    Text("Encrypted in Android Keystore. Never saved in screen state or logs.")
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        }
                        if (provider == Provider.SELF_HOSTED_POLISH) {
                            OutlinedTextField(
                                value = endpoint,
                                onValueChange = {
                                    endpoint = it
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Server URL") },
                                supportingText = { Text("HTTPS is required except for loopback development.") },
                                singleLine = true,
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SelfHostedProtocol.entries.forEach { option ->
                                    FilterChip(
                                        selected = protocol == option,
                                        onClick = { protocol = option },
                                        label = {
                                            Text(
                                                if (option == SelfHostedProtocol.OLLAMA) "Ollama"
                                                else "OpenAI compatible",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Text(
                                provider.disclosure().summary,
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                localError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (settings.configured) {
                    TextButton(onClick = onClearProvider) {
                        Text("Remove saved provider and key")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mode != PolishMode.PROVIDER) {
                        onSetMode(mode)
                        return@Button
                    }
                    val normalizedModel = model.trim()
                    if (normalizedModel.isEmpty() || normalizedModel.length > 256 ||
                        normalizedModel.any(Char::isISOControl)) {
                        localError = "Enter a valid provider model ID."
                        return@Button
                    }
                    val storedCredentialApplies = settings.credentialStored &&
                        provider == settings.provider
                    val effectiveKey = apiKey.takeIf(String::isNotBlank)
                        ?: if (storedCredentialApplies) "stored-credential" else null
                    val normalizedEndpoint = endpoint.trim().takeIf(String::isNotEmpty)
                    when (val validation = ProviderConfigurationValidator.validate(
                        ProviderConfiguration(provider, normalizedEndpoint),
                        effectiveKey,
                    )) {
                        ValidationResult.Valid -> onSaveProvider(
                            provider,
                            normalizedModel,
                            normalizedEndpoint,
                            apiKey.takeIf(String::isNotBlank),
                            protocol,
                        )
                        is ValidationResult.Invalid -> {
                            localError = validation.reason.userMessage()
                        }
                    }
                },
            ) {
                Text(if (mode == PolishMode.PROVIDER) "Save provider" else "Apply")
            }
        },
    )
}

private fun ValidationReason.userMessage(): String = when (this) {
    ValidationReason.API_KEY_REQUIRED -> "Enter an API key for this provider."
    ValidationReason.API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS -> "The API key contains invalid characters."
    ValidationReason.ENDPOINT_REQUIRED -> "Enter the self-hosted server URL."
    ValidationReason.ENDPOINT_MUST_BE_HTTPS -> "Use HTTPS for this server URL."
    ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS -> "Use an HTTPS URL, or loopback for local development."
    ValidationReason.ENDPOINT_MUST_HAVE_HOST -> "The server URL needs a valid host."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS -> "Remove credentials from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT -> "Remove the # fragment from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE -> "Remove spaces from the server URL."
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    ready: Boolean?,
    statusDescription: String? = null,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ready != null) StatusDot(ready, statusDescription)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingScreen(
    step: Int,
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    onStepChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onPractice: () -> Unit,
    onComplete: () -> Unit,
) {
    val steps = remember {
        listOf(
            SetupStep("WELCOME", "Your voice, ready everywhere", "Fast local dictation with an optional polish layer that never blocks your words."),
            SetupStep("PRIVACY", "Private by default", "Audio stays on this phone. Cloud polish receives text only when you choose a provider and add your own key."),
            SetupStep("MICROPHONE", "Let EnviousWispr listen", "Microphone access is used only while you are visibly recording or while an optional warm period is active."),
            SetupStep("CONTROLS", "Stay in control", "Allow a quiet ongoing notification so Stop and Cancel remain available while EnviousWispr is listening."),
            SetupStep("INSERTION", "Put words where you need them", "Keep Gboard. Double-press the right button, speak in the floating recorder, and EnviousWispr returns your words to the original field."),
            SetupStep("MODELS", "Keep the essentials offline", "The fast speech and local polish models are verified before EnviousWispr calls them ready."),
            SetupStep("PRACTICE", "Try the real path", "Start a short dictation now. This uses your microphone, speech model, local polish model, and current insertion route."),
            SetupStep("READY", "You are ready to whisper", "Your setup is saved. You can change every choice later without losing dictation."),
        )
    }
    val safeStep = step.coerceIn(0, steps.lastIndex)
    val current = steps[safeStep]
    val view = LocalView.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("EnviousWispr", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Set up later") }
            }
            LinearProgressIndicator(
                progress = { (safeStep + 1).toFloat() / steps.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .padding(top = 16.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = safeStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding-step",
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 680.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SetupHeroGlyph(step = safeStep)
                        Text(current.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(current.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                        Text(
                            current.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        SetupStepAction(
                            step = safeStep,
                            readiness = readiness,
                            autoPaste = autoPaste,
                            onRequestMicrophone = onRequestMicrophone,
                            onRequestNotifications = onRequestNotifications,
                            onOpenAccessibility = onOpenAccessibility,
                            onPractice = onPractice,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onStepChange((safeStep - 1).coerceAtLeast(0)) },
                    enabled = safeStep > 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) { Text("Back") }
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        if (safeStep == steps.lastIndex) onComplete() else onStepChange(safeStep + 1)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) { Text(if (safeStep == steps.lastIndex) "Open EnviousWispr" else "Continue") }
            }
        }
    }
}

private data class SetupStep(val eyebrow: String, val title: String, val description: String)

@Composable
private fun SetupStepAction(
    step: Int,
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onPractice: () -> Unit,
) {
    val context = LocalContext.current
    when (step) {
        2 -> SetupActionCard(
            title = if (readiness.microphoneGranted) "Microphone ready" else "Microphone permission needed",
            ready = readiness.microphoneGranted,
            action = if (readiness.microphoneGranted) null else "Allow microphone",
            onClick = onRequestMicrophone,
        )
        3 -> SetupActionCard(
            title = if (readiness.notificationsGranted) {
                "Recording controls ready"
            } else {
                "Allow recording controls"
            },
            ready = readiness.notificationsGranted,
            action = if (readiness.notificationsGranted) null else "Allow notifications",
            onClick = onRequestNotifications,
        )
        4 -> SetupActionCard(
            title = when (autoPaste) {
                AutoPasteAvailability.LIVE -> "Right-button auto-insert ready"
                AutoPasteAvailability.PERMITTED_NOT_RUNNING ->
                    "Auto-insert is turned on but not connected"
                AutoPasteAvailability.NOT_PERMITTED -> "Enable right-button auto-insert"
            },
            ready = autoPaste == AutoPasteAvailability.LIVE,
            action = if (autoPaste == AutoPasteAvailability.LIVE) null else "Accessibility settings",
            statusDescription = autoPaste.statusDescription(),
            onClick = onOpenAccessibility,
        )
        5 -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupActionCard("Fast speech model", readiness.speechModelReady, if (readiness.speechModelReady) null else "Repair", onClick = { ModelDeliveryWorker.enqueue(context, ModelManifest.parakeet) })
                SetupActionCard("${S1Config.MODEL_NAME} local polish", readiness.polishModelReady, if (readiness.polishModelReady) null else "Repair", onClick = { ModelDeliveryWorker.enqueue(context, ModelManifest.s1) })
            }
        }
        6 -> Button(
            onClick = onPractice,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            MicrophoneGlyph(Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Start practice dictation")
        }
    }
}

@Composable
private fun SetupActionCard(
    title: String,
    ready: Boolean,
    action: String?,
    statusDescription: String? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(ready, statusDescription)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (action != null) FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun SetupHeroGlyph(step: Int) {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    Box(
        modifier = Modifier
            .size(116.dp)
            .clip(RoundedCornerShape(38.dp))
            .background(colors[step % colors.size]),
        contentAlignment = Alignment.Center,
    ) {
        when (step) {
            2, 6 -> MicrophoneGlyph(Modifier.size(48.dp), MaterialTheme.colorScheme.primary)
            else -> Text(
                listOf("W", "◉", "✓", "◎", "↗", "◇", "●", "✓")[step],
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MicrophoneGlyph(modifier: Modifier, color: Color = Color.Unspecified) {
    val actualColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onPrimary else color
    Canvas(modifier = modifier.semantics { contentDescription = "Microphone" }) {
        val strokeWidth = 3.dp.toPx()
        drawRoundRect(
            color = actualColor,
            topLeft = Offset(size.width * 0.34f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.32f, size.height * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.16f),
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
        val arcPath = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.48f)
            cubicTo(
                size.width * 0.20f,
                size.height * 0.78f,
                size.width * 0.80f,
                size.height * 0.78f,
                size.width * 0.80f,
                size.height * 0.48f,
            )
        }
        drawPath(arcPath, actualColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawLine(actualColor, Offset(size.width * 0.50f, size.height * 0.75f), Offset(size.width * 0.50f, size.height * 0.91f), strokeWidth, StrokeCap.Round)
        drawLine(actualColor, Offset(size.width * 0.36f, size.height * 0.91f), Offset(size.width * 0.64f, size.height * 0.91f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun ReadinessChip(label: String, ready: Boolean, description: String? = null) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(ready, description)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPill(label: String, ready: Boolean) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StatusDot(ready: Boolean, description: String? = null) {
    // The screen-reader label is the fifth surface that said Ready while auto-paste was dead, and
    // the one that matters most on a feature built out of an accessibility service.
    val label = description ?: if (ready) "Ready" else "Needs attention"
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            .semantics { contentDescription = label },
    )
}

private fun AutoPasteAvailability.statusDescription(): String = when (this) {
    AutoPasteAvailability.LIVE -> "Ready"
    AutoPasteAvailability.PERMITTED_NOT_RUNNING -> "Not connected"
    AutoPasteAvailability.NOT_PERMITTED -> "Needs attention"
}
