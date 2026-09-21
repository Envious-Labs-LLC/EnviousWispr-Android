package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.BuildConfig

/**
 * The four tabs in the bottom bar, and nothing else.
 *
 * Names and order follow the macOS reference implementation's `SettingsSection`, where `speechEngine`
 * is labelled "Transcription" and `wordCorrection` is labelled "Dictionary". Anything that is not one
 * of these four is a [SettingsPage] behind the drawer.
 */
internal enum class AppDestination(val label: String) {
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
    Privacy(
        SettingsPageGroup.SYSTEM,
        "Privacy",
        "What stays on this phone, and the little that leaves it.",
    ),
    Storage(
        SettingsPageGroup.SYSTEM,
        "Storage",
        "Space used by files in the models folder.",
    ),
    Licenses(
        SettingsPageGroup.SYSTEM,
        "Open Source Licenses",
        "EnviousWispr is built on open source. The third-party notices.",
    ),
}


/**
 * What is on screen right now: one of the four tabs, or one settings page on top of them.
 *
 * A sealed type rather than a nullable pair, so the `when` that renders it is exhaustive in the
 * compiler's eyes and a new tab or a new page cannot silently fall into an `else`.
 */
internal sealed interface Screen {
    data class Tab(val destination: AppDestination) : Screen

    data class Page(val page: SettingsPage) : Screen
}

/** What the scaffold needs to know about a page on top of the tabs: its title. Null means a tab is showing. */
internal data class PageChrome(val title: String)

/**
 * The chrome around whatever is on screen: the top bar with its one navigation control, and the
 * bottom bar, which is present only while a tab is showing.
 *
 * [content] receives the modifier carrying the scaffold's insets, so no screen adds its own status-bar
 * padding and no screen can forget it. [topBarBadge], when non-null, renders before the mic button on
 * whichever destination supplies it; every other destination omits it and sees no change.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppScaffold(
    destination: AppDestination,
    page: PageChrome?,
    snackbarHostState: SnackbarHostState,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    onSelectDestination: (AppDestination) -> Unit,
    onStartDictation: () -> Unit,
    topBarBadge: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: destination.label) },
                navigationIcon = {
                    if (page == null) {
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
                    if (page == null) {
                        topBarBadge?.invoke()
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
            if (page == null) {
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
internal fun SettingsDrawerSheet(
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

/** The hamburger. Three lines, drawn rather than imported, like every other glyph in the navigation chrome. */
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
 * The two saved route strings, resolved (#190). A saved name from a build whose enum had other members resolves
 * to nothing, so the tab falls back to the landing tab rather than crashing and a vanished page shows the tab
 * beneath it. These are the only readers of those saved strings.
 */
internal object AppRoutes {
    fun destination(savedName: String): AppDestination =
        AppDestination.entries.firstOrNull { it.name == savedName } ?: AppDestination.History

    fun settingsPage(savedName: String?): SettingsPage? =
        savedName?.let { saved -> SettingsPage.entries.firstOrNull { it.name == saved } }
}
