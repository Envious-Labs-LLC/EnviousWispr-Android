package com.envi.wispr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.envi.wispr.audio.RecordingSoundOutput
import com.envi.wispr.audio.RecordingSoundPairing
import com.envi.wispr.settings.AppPreferencesState

@Composable
internal fun SoundsPage(preferences: AppPreferencesState, actions: SoundsActions) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busy by RecordingSoundOutput.busy.collectAsStateWithLifecycle()
    val previewing by RecordingSoundOutput.previewing.collectAsStateWithLifecycle()
    val previewStarted by RecordingSoundOutput.previewStarted.collectAsStateWithLifecycle()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) RecordingSoundOutput.cancelPreview()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); RecordingSoundOutput.cancelPreview() }
    }
    ScreenContainer(subtitle = SettingsPage.Sounds.subtitle) {
        SettingsGroup("Recording cues") {
            SettingsToggleRow(
                title = "Play recording chimes",
                subtitle = "Plays a short chime when recording starts and stops.",
                checked = preferences.recordingSoundsEnabled,
                onCheckedChange = actions.onRecordingSoundsChanged,
            )
            SettingsToggleRow(
                title = "Recording vibration",
                subtitle = "Vibrates when recording starts, stops, or is cancelled.",
                checked = preferences.recordingVibrationEnabled,
                onCheckedChange = actions.onRecordingVibrationChanged,
            )
        }
        Text(
            if (busy) "Finish dictating to preview sounds." else "Preview a chime without changing your choice.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecordingSoundPairing.entries.forEach { pairing ->
            val selected = preferences.recordingSoundPairing == pairing
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f)
                            .selectable(selected = selected, role = Role.RadioButton) { actions.onRecordingSoundPairingChanged(pairing) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(pairing.title, style = MaterialTheme.typography.titleMedium)
                            Text(pairing.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val ink = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                                val step = size.width / pairing.waveform.size
                                pairing.waveform.forEachIndexed { index, level ->
                                    val x = (index + .5f) * step
                                    val half = level * size.height / 2
                                    drawLine(ink, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), strokeWidth = step * .55f, cap = StrokeCap.Round)
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { RecordingSoundOutput.preview(context, pairing) },
                        enabled = !busy,
                        modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Preview ${pairing.title}" },
                    ) { Text(if (previewStarted == pairing) "Playing" else if (previewing == pairing) "Loading" else "Preview") }
                }
            }
        }
    }
}
