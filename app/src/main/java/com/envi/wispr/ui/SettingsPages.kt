package com.envi.wispr.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.BuildConfig
import com.envi.wispr.about.ReleaseNotes
import com.envi.wispr.paste.AutoPasteAvailability
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
) {
    // Wallpaper colours are an Android 12 feature and `minSdk` is 30, so on the oldest supported phone
    // the switch would store a value the theme cannot read. `EnviousWisprTheme` already falls back to
    // the brand palette there; the row has to say so rather than look like it worked.
    val wallpaperColoursSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ScreenContainer(subtitle = SettingsPage.Appearance.subtitle) {
        SettingsGroup("Colours") {
            SettingsToggleRow(
                title = "Use Galaxy colours",
                subtitle = if (wallpaperColoursSupported) {
                    "Take the colours from this phone's wallpaper instead of EnviousWispr's own."
                } else {
                    "Needs Android 12. This phone keeps EnviousWispr's own colours."
                },
                checked = wallpaperColoursSupported && preferences.dynamicColorEnabled,
                enabled = wallpaperColoursSupported,
                onCheckedChange = onDynamicColorChanged,
            )
        }
        Text(
            if (wallpaperColoursSupported) {
                "EnviousWispr uses its own purple. Turn this on and the app takes your wallpaper's " +
                    "colours instead. Either way it follows the light or dark setting you chose for " +
                    "the phone; choosing light or dark just for this app is not available yet."
            } else {
                "EnviousWispr uses its own purple. It follows the light or dark setting you chose " +
                    "for the phone; choosing light or dark just for this app is not available yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MicrophonePage(
    readiness: AppReadiness,
    onRequestMicrophone: () -> Unit,
) {
    ScreenContainer(subtitle = SettingsPage.Microphone.subtitle) {
        SettingsGroup("Access") {
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
        }
        Text(
            "EnviousWispr listens with whichever microphone the phone is using. Picking a " +
                "specific microphone, and keeping a headset connected through a dictation, are " +
                "not available yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
internal fun LicensesPage(notices: String) {
    ScreenContainer(subtitle = SettingsPage.Licenses.subtitle) {
        Text(
            notices,
            style = MaterialTheme.typography.bodySmall,
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
                    AutoPasteAvailability.LIVE -> "Ready for right-button dictation"
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

