package com.envi.wispr.ui

import androidx.compose.material3.RadioButton
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.BuildConfig
import com.envi.wispr.about.ReleaseNotes
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.settings.AppPreferencesState

@Composable
internal fun WhatsNewPage() {
    ScreenContainer(subtitle = SettingsPage.WhatsNew.subtitle) {
        Text(
            "You are running version ${BuildConfig.VERSION_NAME}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReleaseNotes.entries.forEach { note ->
            ElevatedCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Version ${note.version}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            note.date,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    note.lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearancePage(
    preferences: AppPreferencesState,
    onDynamicColorChanged: (Boolean) -> Unit,
    onBubbleLookChanged: (BubbleLook) -> Unit,
) {
    ScreenContainer(subtitle = SettingsPage.Appearance.subtitle) {
        SettingsGroup("Floating button") {
            BubbleLookPicker(selected = preferences.bubbleLook, onSelect = onBubbleLookChanged)
        }
        Text(
            "The floating button and the recorder that replaces it while you talk share one look. " +
                "Pick the one that reads best over the apps you type in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup("Colours") {
            SettingsToggleRow(
                title = "Use Galaxy colours",
                // Wallpaper colours are Android 12; every supported phone has them at minSdk 33 (#260).
                subtitle = "Take the colours from this phone's wallpaper instead of EnviousWispr's own.",
                checked = preferences.dynamicColorEnabled,
                onCheckedChange = onDynamicColorChanged,
            )
        }
        Text(
            "EnviousWispr uses its own purple. Turn this on and the app takes your wallpaper's " +
                "colours instead. Either way it follows the light or dark setting you chose for " +
                "the phone; choosing light or dark just for this app is not available yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Three equal tiles, one per [BubbleLook], each carrying a preview: the look's ground with the brand
 * lips on it, drawn over a half-white, half-dark strip so the user sees how it reads on both kinds
 * of page before choosing. A radio group to a screen reader.
 */
@Composable
private fun BubbleLookPicker(selected: BubbleLook, onSelect: (BubbleLook) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BubbleLook.entries.forEach { look ->
            BubbleLookTile(
                look = look,
                selected = look == selected,
                onClick = { onSelect(look) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BubbleLookTile(look: BubbleLook, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val muted = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${look.title}. ${look.description}" },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BubbleLookPreview(look)
            Text(look.title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(look.description, style = MaterialTheme.typography.bodySmall, color = muted, minLines = 4, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The look at 44 dp, over a strip that is white on the left and near-black on the right. */
@Composable
private fun BubbleLookPreview(look: BubbleLook) {
    val ground = Color(look.surfaceFill)
    val lipsFraction = look.lipsDp / 56f
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp))) {
        drawRect(Color.White, size = Size(size.width / 2f, size.height))
        drawRect(Color(0xFF14121A), topLeft = Offset(size.width / 2f, 0f), size = Size(size.width / 2f, size.height))
        val bubble = 44.dp.toPx()
        val visible = bubble * (48f / 56f)
        val radius = CornerRadius(bubble * (14f / 56f))
        listOf(size.width * 0.25f, size.width * 0.75f).forEach { centreX ->
            val left = centreX - visible / 2f
            val top = (size.height - visible) / 2f
            if (ground.alpha > 0f) drawRoundRect(ground, Offset(left, top), Size(visible, visible), radius)
            drawLips(centreX, size.height / 2f, bubble * lipsFraction, edge = if (look.inkEdgeDp > 0f) look.inkEdgeDp.dp.toPx() else 0f)
        }
    }
}

/** The brand lips on the 256-unit grid, the same drawing as `OnboardingLips`, static, with an optional dark edge behind each bar. */
private fun DrawScope.drawLips(centreX: Float, centreY: Float, side: Float, edge: Float) {
    val upperY = floatArrayOf(84.2f, 65.75f, 43.36f, 63.04f, 81.43f, 63.04f, 43.36f, 65.75f, 84.2f)
    val upperH = floatArrayOf(20f, 32f, 48f, 36f, 24f, 36f, 48f, 32f, 20f)
    val lowerY = floatArrayOf(125.8f, 119.35f, 112.96f, 120.64f, 127.03f, 120.64f, 112.96f, 119.35f, 125.8f)
    val lowerH = floatArrayOf(20f, 36f, 48f, 60f, 68f, 60f, 48f, 36f, 20f)
    val colors = listOf(0xFFFF2A40, 0xFFFF8C00, 0xFFFFD700, 0xFFADFF2F, 0xFF00FA9A, 0xFF00FFFF, 0xFF1E90FF, 0xFF4169E1, 0xFF8A2BE2).map { Color(it) }
    val lowerColors = listOf(7, 6, 5, 4, 3, 2, 1, 0, 8)
    val unit = side / 256f
    val originX = centreX - side / 2f
    val originY = centreY - side / 2f
    val ink = Color(0xD9131019)
    for (row in 0..1) for (i in 0..8) {
        val y = (if (row == 0) upperY[i] else lowerY[i]) + 13f
        val h = if (row == 0) upperH[i] else lowerH[i]
        val left = originX + (24 + i * 24) * unit
        val top = originY + y * unit
        if (edge > 0f) {
            drawRoundRect(ink, Offset(left - edge, top - edge), Size(14 * unit + 2 * edge, h * unit + 2 * edge), CornerRadius(5 * unit + edge))
        }
        drawRoundRect(colors[if (row == 0) i else lowerColors[i]], Offset(left, top), Size(14 * unit, h * unit), CornerRadius(5 * unit))
    }
}

@Composable
internal fun SoundsPage() {
    ScreenContainer(subtitle = SettingsPage.Sounds.subtitle) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A short vibration", style = MaterialTheme.typography.titleMedium)
                Text(
                    "EnviousWispr vibrates when recording starts, when it stops, and when you " +
                        "cancel. You feel it without looking at the screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Start and stop sounds are not available yet, so there is nothing here to switch on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ClipboardPage(
    preferences: AppPreferencesState,
    onAutoCopyChanged: (Boolean) -> Unit,
    onRestoreClipboardChanged: (Boolean) -> Unit,
    onSmartInsertionChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    fun updateWithHaptic(value: Boolean, update: (Boolean) -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        update(value)
    }
    ScreenContainer(subtitle = SettingsPage.Clipboard.subtitle) {
        SettingsGroup("Clipboard and insertion") {
            SettingsToggleRow(
                title = "Auto-copy to clipboard",
                subtitle = "Keep completed text ready to paste when direct insertion is unavailable.",
                checked = preferences.autoCopyToClipboard,
                onCheckedChange = { updateWithHaptic(it, onAutoCopyChanged) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Restore clipboard after paste",
                subtitle = "Put back everything that was on your clipboard before automatic paste.",
                checked = preferences.restoreClipboardAfterPaste,
                onCheckedChange = { updateWithHaptic(it, onRestoreClipboardChanged) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Smart insertion",
                subtitle = "Match spacing and capitalization to the text around your cursor.",
                checked = preferences.smartInsertionEnabled,
                onCheckedChange = { updateWithHaptic(it, onSmartInsertionChanged) },
            )
        }
        Text(
            "These choices are locked when recording starts, so changing one never alters a dictation already in progress.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Everything the app needs from the phone, in one place.
 *
 * This page carries the readiness surfaces that used to sit on the deleted Home screen. They belong
 * beside the permissions they describe, and `AutoPasteWiringTest` pins every one of them.
 */
@Composable
internal fun PermissionsPage(
    readiness: AppReadiness,
    autoPaste: AutoPasteAvailability,
    onContinueSetup: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    ScreenContainer(subtitle = SettingsPage.Permissions.subtitle) {
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
                            // No destination named here. `autoCopyToClipboard` decides whether
                            // that is the clipboard or History, and this card cannot see it; the
                            // line after a dictation names the destination that was measured.
                            "Your words will not go into the field until it reconnects. If it " +
                                "stays disconnected, turn EnviousWispr off and then on in " +
                                "Accessibility settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FilledTonalButton(onClick = onOpenAccessibility) {
                        Text("Accessibility settings")
                    }
                }
            }
        }

        Text(
            "Readiness",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
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

        SettingsGroup("What EnviousWispr needs") {
            SettingsActionRow(
                title = "Microphone",
                subtitle = if (readiness.microphoneGranted) {
                    "Granted"
                } else {
                    "Needed before any dictation can start"
                },
                ready = readiness.microphoneGranted,
                enabled = !readiness.microphoneGranted,
                onClick = onRequestMicrophone,
            )
            HorizontalDivider()
            SettingsActionRow(
                title = "Recording controls",
                subtitle = if (readiness.notificationsGranted) {
                    "Stop and Cancel available in notifications"
                } else {
                    "Allow notifications for Stop and Cancel"
                },
                ready = readiness.notificationsGranted,
                enabled = !readiness.notificationsGranted,
                onClick = onRequestNotifications,
            )
            HorizontalDivider()
            SettingsActionRow(
                title = "Auto-paste access",
                subtitle = when (autoPaste) {
                    AutoPasteAvailability.LIVE -> "Ready for side-button dictation"
                    AutoPasteAvailability.PERMITTED_NOT_RUNNING ->
                        "Turned on but not connected. Words will not go into the field until it reconnects."
                    AutoPasteAvailability.NOT_PERMITTED -> "Needs accessibility permission"
                },
                ready = autoPaste == AutoPasteAvailability.LIVE,
                statusDescription = autoPaste.statusDescription(),
                onClick = onOpenAccessibility,
            )
            HorizontalDivider()
            SettingsActionRow(
                title = "Continue guided setup",
                subtitle = "Resume from your saved step",
                ready = null,
                onClick = onContinueSetup,
            )
        }
    }
}
