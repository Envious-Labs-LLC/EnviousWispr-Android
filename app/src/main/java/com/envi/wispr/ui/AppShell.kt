package com.envi.wispr.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
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
import com.envi.wispr.BuildConfig
import com.envi.wispr.ui.theme.brandButtonColors
import com.envi.wispr.about.ReleaseNotes
import com.envi.wispr.polish.S1Config
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDeliveryControlStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.models.modelUiState
import com.envi.wispr.history.TranscriptEntity
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
import com.envi.wispr.vocabulary.CustomTermRecord
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * The four tabs in the bottom bar, and nothing else.
 *
 * Names and order follow the macOS reference implementation's `SettingsSection`, where `speechEngine`
 * is labelled "Transcription" and `wordCorrection` is labelled "Dictionary". Anything that is not one
 * of these four is a [SettingsPage] behind the drawer.
 */
private enum class AppDestination(val label: String) {
    History("History"),
    Dictionary("Dictionary"),
    Transcription("Transcription"),
    Polish("AI Polish"),
}

/** A heading in the drawer. Ported from the macOS `SettingsGroup`. */
internal enum class SettingsPageGroup(val heading: String) {
    APP("APP"),
    RECORD("RECORD"),
    OUTPUT("OUTPUT"),
    SYSTEM("SYSTEM"),
}

/**
 * A full-screen settings page, reachable only from the drawer, always with a back control.
 *
 * This enum is the whole menu: the drawer is rendered from [entries] grouped by [group], so a page
 * added here appears without a second edit, and a page that exists cannot be unreachable.
 */
internal enum class SettingsPage(
    val group: SettingsPageGroup,
    val title: String,
    val subtitle: String,
) {
    WhatsNew(SettingsPageGroup.APP, "What's New", "The latest improvements and fixes in this release."),
    Appearance(SettingsPageGroup.APP, "Appearance", "How EnviousWispr looks on this phone."),
    Microphone(SettingsPageGroup.RECORD, "Microphone", "The microphone EnviousWispr listens with."),
    Sounds(SettingsPageGroup.RECORD, "Sounds", "What you feel and hear when recording starts and stops."),
    Clipboard(
        SettingsPageGroup.OUTPUT,
        "Clipboard",
        "How your words reach the clipboard and the app you are typing in.",
    ),
    Permissions(
        SettingsPageGroup.SYSTEM,
        "Permissions",
        "The microphone and accessibility access EnviousWispr needs.",
    ),
    Licenses(
        SettingsPageGroup.SYSTEM,
        "Open Source Licenses",
        "EnviousWispr is built on open source. The third-party notices.",
    ),
}

@Composable
internal fun EnviousWisprApp(
    uiState: EnviousWisprUiState,
    onStartDictation: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    licenseNotices: String,
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

    var destinationName by rememberSaveable { mutableStateOf(AppDestination.History.name) }
    // A saved name from a build whose enum had other members resolves to nothing, so the fallback is
    // the landing tab rather than a crash. This is the only reader of that saved string.
    val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
        ?: AppDestination.History
    var settingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
    // The one History card that is open, held here rather than inside the row so that scrolling it
    // out of the list, or leaving History for another tab, does not close it. Null is "all closed",
    // and holding ONE id is what makes "only one open at a time" true by construction.
    var expandedTranscriptId by rememberSaveable { mutableStateOf<Long?>(null) }
    val settingsPage = settingsPageName?.let { saved ->
        SettingsPage.entries.firstOrNull { it.name == saved }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Only armed while a settings page is open, so the system back gesture on a tab still leaves the
    // app rather than being swallowed by a handler with nothing to close.
    BackHandler(enabled = settingsPage != null) { settingsPageName = null }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
            settingsPage = settingsPage,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onBack = { settingsPageName = null },
            onSelectDestination = { destinationName = it.name },
            onStartDictation = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onStartDictation()
            },
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
                            onSearchChange = onHistorySearchChange,
                            onKeep = onKeepHistory,
                            onDelete = onDeleteHistory,
                            onDeleteAll = onDeleteAllHistory,
                        )
                        AppDestination.Dictionary -> DictionaryScreen(
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
                        AppDestination.Transcription -> TranscriptionScreen(
                            readiness = uiState.readiness,
                            preferences = uiState.preferences,
                            onRefreshReadiness = onRefreshReadiness,
                            onFillerRemovalChanged = onFillerRemovalChanged,
                            onEmojiFormatterChanged = onEmojiFormatterChanged,
                            onSpokenPunctuationChanged = onSpokenPunctuationChanged,
                        )
                        AppDestination.Polish -> PolishScreen(
                            settings = uiState.providerSettings,
                            readiness = uiState.readiness,
                            onRefreshReadiness = onRefreshReadiness,
                            onSetMode = onSetPolishMode,
                            onSaveProvider = onSaveProviderSettings,
                            onClearProvider = onClearProviderSettings,
                        )
                    }
                    is Screen.Page -> when (current.page) {
                        SettingsPage.WhatsNew -> WhatsNewPage()
                        SettingsPage.Appearance -> AppearancePage(
                            preferences = uiState.preferences,
                            onDynamicColorChanged = onDynamicColorChanged,
                        )
                        SettingsPage.Microphone -> MicrophonePage(
                            readiness = uiState.readiness,
                            onRequestMicrophone = onRequestMicrophone,
                        )
                        SettingsPage.Sounds -> SoundsPage()
                        SettingsPage.Clipboard -> ClipboardPage(
                            preferences = uiState.preferences,
                            onAutoCopyChanged = onAutoCopyChanged,
                            onRestoreClipboardChanged = onRestoreClipboardChanged,
                            onSmartInsertionChanged = onSmartInsertionChanged,
                        )
                        SettingsPage.Permissions -> PermissionsPage(
                            readiness = uiState.readiness,
                            autoPaste = uiState.autoPaste,
                            onContinueSetup = onResumeOnboarding,
                            onRequestMicrophone = onRequestMicrophone,
                            onRequestNotifications = onRequestNotifications,
                            onOpenAccessibility = onOpenAccessibility,
                        )
                        SettingsPage.Licenses -> LicensesPage(notices = licenseNotices)
                    }
                }
            }
        }
    }
}

/**
 * What is on screen right now: one of the four tabs, or one settings page on top of them.
 *
 * A sealed type rather than a nullable pair, so the `when` that renders it is exhaustive in the
 * compiler's eyes and a new tab or a new page cannot silently fall into an `else`.
 */
private sealed interface Screen {
    data class Tab(val destination: AppDestination) : Screen

    data class Page(val page: SettingsPage) : Screen
}

/**
 * The chrome around whatever is on screen: the top bar with its one navigation control, and the
 * bottom bar, which is present only while a tab is showing.
 *
 * [content] receives the modifier carrying the scaffold's insets, so no screen adds its own status-bar
 * padding and no screen can forget it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppScaffold(
    destination: AppDestination,
    settingsPage: SettingsPage?,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onSelectDestination: (AppDestination) -> Unit,
    onStartDictation: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(settingsPage?.title ?: destination.label) },
                navigationIcon = {
                    if (settingsPage == null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.semantics { contentDescription = "Open settings menu" },
                        ) { MenuGlyph() }
                    } else {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Back" },
                        ) { BackGlyph() }
                    }
                },
                actions = {
                    if (settingsPage == null) {
                        IconButton(
                            onClick = onStartDictation,
                            modifier = Modifier.semantics {
                                contentDescription = "Start dictation"
                                role = Role.Button
                            },
                        ) {
                            MicrophoneGlyph(
                                Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (settingsPage == null) {
                NavigationBar {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { onSelectDestination(item) },
                            icon = { DestinationIcon(item, item == destination) },
                            label = { Text(item.label) },
                            // The LABEL only. Material tints it with `secondary`, a DIFFERENT purple
                            // from the accent every other tinted thing uses, and on the one bar
                            // always on screen two near-identical purples read as a mistake rather
                            // than a hierarchy. Measured before this change: #C38BF5 on the label
                            // against #A78BFA on the microphone and the drawer headings.
                            //
                            // `selectedIconColor` is deliberately NOT set here, because it would do
                            // nothing: `DestinationIcon` is a `Canvas` that picks its own colour
                            // from `onSecondaryContainer`, so the glyph never reads this value.
                            // Measured after: the glyph is #E5C4FF on the #612B8F pill.
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/**
 * The drawer, rendered from [SettingsPage.entries] grouped by [SettingsPageGroup], so the menu cannot
 * drift from the set of pages that exist.
 */
@Composable
private fun SettingsDrawerSheet(
    current: SettingsPage?,
    onPick: (SettingsPage) -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "EnviousWispr",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsPageGroup.entries.forEach { group ->
                val pages = SettingsPage.entries.filter { it.group == group }
                if (pages.isEmpty()) return@forEach
                Text(
                    group.heading,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
                )
                pages.forEach { page ->
                    NavigationDrawerItem(
                        label = { Text(page.title) },
                        selected = page == current,
                        onClick = { onPick(page) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
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
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = destination.label },
    ) {
        val stroke = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
        when (destination) {
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
            AppDestination.Dictionary -> {
                drawLine(color, Offset(size.width * 0.24f, size.height * 0.78f), Offset(size.width * 0.47f, size.height * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.47f, size.height * 0.22f), Offset(size.width * 0.69f, size.height * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.31f, size.height * 0.59f), Offset(size.width * 0.62f, size.height * 0.59f), stroke.width, StrokeCap.Round)
                drawCircle(color, size.minDimension * 0.055f, Offset(size.width * 0.78f, size.height * 0.29f))
            }
            AppDestination.Transcription -> {
                // A waveform, matching the macOS Transcription page's `waveform` symbol.
                listOf(0.18f to 0.22f, 0.34f to 0.40f, 0.50f to 0.32f, 0.66f to 0.40f, 0.82f to 0.22f)
                    .forEach { (x, halfHeight) ->
                        drawLine(
                            color,
                            Offset(size.width * x, size.height * (0.5f - halfHeight)),
                            Offset(size.width * x, size.height * (0.5f + halfHeight)),
                            stroke.width,
                            StrokeCap.Round,
                        )
                    }
            }
            AppDestination.Polish -> {
                // Three sparkles, matching the macOS AI Polish page's `sparkles` symbol.
                listOf(
                    Triple(0.38f, 0.38f, 0.26f),
                    Triple(0.72f, 0.24f, 0.14f),
                    Triple(0.70f, 0.72f, 0.16f),
                ).forEach { (cx, cy, radius) ->
                    val path = Path().apply {
                        moveTo(size.width * cx, size.height * (cy - radius))
                        lineTo(size.width * (cx + radius * 0.32f), size.height * (cy - radius * 0.32f))
                        lineTo(size.width * (cx + radius), size.height * cy)
                        lineTo(size.width * (cx + radius * 0.32f), size.height * (cy + radius * 0.32f))
                        lineTo(size.width * cx, size.height * (cy + radius))
                        lineTo(size.width * (cx - radius * 0.32f), size.height * (cy + radius * 0.32f))
                        lineTo(size.width * (cx - radius), size.height * cy)
                        lineTo(size.width * (cx - radius * 0.32f), size.height * (cy - radius * 0.32f))
                        close()
                    }
                    drawPath(path, color, style = stroke)
                }
            }
        }
    }
}

/** The hamburger. Three lines, drawn rather than imported, like every other glyph in this shell. */
@Composable
private fun MenuGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.1.dp.toPx()
        listOf(0.28f, 0.50f, 0.72f).forEach { y ->
            drawLine(
                color,
                Offset(size.width * 0.17f, size.height * y),
                Offset(size.width * 0.83f, size.height * y),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

/** The back arrow on every settings page. */
@Composable
private fun BackGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.1.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.80f, size.height * 0.50f),
            Offset(size.width * 0.22f, size.height * 0.50f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.44f, size.height * 0.28f),
            Offset(size.width * 0.22f, size.height * 0.50f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.44f, size.height * 0.72f),
            Offset(size.width * 0.22f, size.height * 0.50f),
            stroke,
            StrokeCap.Round,
        )
    }
}

/**
 * The body of every screen and page.
 *
 * The title is not repeated here: the top app bar already carries it, and printing it twice was the
 * first thing to look wrong when the bar arrived. [subtitle] is the one line of orientation macOS puts
 * under each page's title.
 */
@Composable
internal fun ScreenContainer(
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
            )
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
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/**
 * A row carrying one switch.
 *
 * [enabled] exists for a setting the phone itself cannot honour. A switch that stores a value nothing
 * reads is worse than an absent one, because it looks like it worked. The row keeps its title and says
 * why in its subtitle instead.
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that leads somewhere.
 *
 * [enabled] exists because a permission row stops leading anywhere once the permission is granted:
 * re-launching a granted request returns instantly and draws nothing, so the row would promise a
 * result with a chevron and then deliver none. A disabled row keeps its status dot and its sentence,
 * loses the chevron, and does not accept a tap.
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    ready: Boolean?,
    statusDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
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
            if (enabled) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    colors = brandButtonColors(),
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
            colors = brandButtonColors(),
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
internal fun ReadinessChip(label: String, ready: Boolean, description: String? = null) {
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
internal fun StatusPill(label: String, ready: Boolean) {
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
internal fun StatusDot(ready: Boolean, description: String? = null) {
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

internal fun AutoPasteAvailability.statusDescription(): String = when (this) {
    AutoPasteAvailability.LIVE -> "Ready"
    AutoPasteAvailability.PERMITTED_NOT_RUNNING -> "Not connected"
    AutoPasteAvailability.NOT_PERMITTED -> "Needs attention"
}
