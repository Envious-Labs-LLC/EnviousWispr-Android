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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.BuildConfig
import com.envi.wispr.about.ReleaseNotes
import com.envi.wispr.models.ModelFolderFootprint
import com.envi.wispr.models.ModelFootprint
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.settings.AppPreferencesState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the models folder is taking up on this phone, and where it goes.
 *
 * Storage is the single biggest cost of having this app installed, and until #20 the app said nothing
 * about it anywhere. The model cards answer it per model; this answers it for the folder, which is the
 * question a user trying to free space actually has.
 *
 * **The rows and the total come from ONE traversal**, in `ModelFootprint.measureFolder`. Walking each
 * model and then walking the folder is N+1 measurements at N+1 instants, and a model that grows between
 * two of them invents space "no model claims" that nobody is using. Review found exactly that in the
 * first version of this page, which held both numbers in one object and called that one measurement.
 *
 * Three states, not two. A failed walk used to collapse into the same null as a running one, so the page
 * said "Measuring" forever with nothing left to measure.
 */
@Composable
internal fun StoragePage() {
    val context = LocalContext.current
    // `key(Unit)` is not what makes this re-measure; leaving the page disposes the state and returning
    // starts a fresh producer. It is here so that a future key can be added without moving the holder.
    val reading by key(Unit) {
        produceState<StorageReading>(initialValue = StorageReading.Measuring) {
            value = withContext(Dispatchers.IO) {
                try {
                    StorageReading.Measured(
                        ModelFootprint.measureFolder(ModelStorage.root(context), ModelManifest.all),
                    )
                } catch (cancellation: CancellationException) {
                    // Cancellation is the page going away, not a measurement failure. Rethrowing keeps
                    // structured concurrency honest instead of reporting an error nobody will see.
                    throw cancellation
                } catch (_: Throwable) {
                    // `measureFolder` throws rather than returning a smaller number, so there is no
                    // partial figure to show and the honest answer is that we could not measure.
                    StorageReading.Failed
                }
            }
        }
    }

    ScreenContainer(subtitle = SettingsPage.Storage.subtitle) {
        when (val state = reading) {
            StorageReading.Measuring -> Text(
                "Measuring what is on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StorageReading.Failed -> Text(
                "Couldn't measure model storage. Leave this page and open it again to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is StorageReading.Measured -> {
                val footprint = state.footprint
                SettingsGroup("Models") {
                    ModelManifest.all.forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        val bytes = footprint.perModel[model] ?: 0L
                        StorageRow(
                            title = model.displayName,
                            value = if (bytes > 0L) formatModelBytes(bytes) else "Not on this phone",
                        )
                    }
                    if (footprint.unclaimed > 0L) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        // Its own row rather than folded into a model, because no model owns it. This is
                        // what a half-finished download, or a file a version bump left behind, looks like.
                        StorageRow(
                            title = "Files no model claims",
                            value = formatModelBytes(footprint.unclaimed),
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    StorageRow(title = "Total", value = formatModelBytes(footprint.total), emphasise = true)
                }
                Text(
                    "This total covers the models folder, including files outside known model folders. " +
                        "It excludes the app itself and data stored elsewhere. Removing a model frees " +
                        "its space straight away, and you can download it again later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the Storage page knows so far. A sealed type because the third state is the one that went wrong:
 * a failure and a measurement still running are not the same thing and must not render the same way.
 */
internal sealed interface StorageReading {
    data object Measuring : StorageReading
    data object Failed : StorageReading
    data class Measured(val footprint: ModelFolderFootprint) : StorageReading
}

@Composable
private fun StorageRow(title: String, value: String, emphasise: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        )
        Text(
            value,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasise) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

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

