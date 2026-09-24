package com.envi.wispr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.audio.InputDeviceResolver
import com.envi.wispr.audio.InputDeviceCandidate
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.RadioButton
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.envi.wispr.settings.AppPreferencesState

@Composable
internal fun MicrophonePage(
    readiness: AppReadiness,
    preferences: AppPreferencesState,
    onRequestMicrophone: () -> Unit,
    onInputDevicePickChanged: (InputDevicePick) -> Unit,
    onShowBluetoothTipsChanged: (Boolean) -> Unit,
    onKeepEarbudsReadyChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    val inputs = rememberConnectedInputs()
    val pick = InputDevicePick.parse(preferences.inputDevicePick)
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
        // The macOS "Input Device" row (catalog `microphone-selection`), as a list: Auto, then every
        // microphone the phone can see right now, by its own name. The list is for DISPLAY and choice
        // only; which one records is decided in the capture process at the moment a take starts.
        // Auto names the microphone it would open; a pick that is not connected is remembered, not
        // shown, and reclaims its row when it reconnects (the Mac rule, `InputDeviceRows`, #173).
        SettingsGroup("Input Device") {
            Column(Modifier.selectableGroup()) {
                InputDeviceRows.build(pick, inputs).forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    InputDeviceRow(
                        title = row.title,
                        subtitle = row.subtitle,
                        selected = row.selected,
                        onSelect = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onInputDevicePickChanged(row.pick)
                        },
                    )
                }
            }
        }
        // PAR-028: the macOS Bluetooth guide, rewritten where Android differs. Since 2026-09-18 the
        // recorder waits for the earbuds itself (the lips spin until they deliver sound), and the
        // readiness window is the 30 s hold below, measured on the S26 (V13).
        SettingsGroup("When using Bluetooth") {
            Text(
                "Bluetooth earbuds switch into call mode when a recording starts. On a cold start that " +
                    "takes a couple of seconds: the lips spin until the earbuds are live, then the " +
                    "recorder opens and you speak. Music on the earbuds drops to call quality while " +
                    "you dictate and, with the option below, for 30 seconds after. Wired and USB " +
                    "microphones have no startup delay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Keep earbuds ready after dictating",
                subtitle = "For 30 seconds after a dictation the earbud link stays open, so the next one " +
                    "starts at once. Music on the earbuds stays in call quality for those 30 seconds.",
                checked = preferences.keepEarbudsReady,
                onCheckedChange = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onKeepEarbudsReadyChanged(it)
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Show Bluetooth tips",
                subtitle = "A one-time reminder on the recorder when earbuds are your microphone.",
                checked = preferences.showBluetoothTips,
                onCheckedChange = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onShowBluetoothTipsChanged(it)
                },
            )
        }
        Text(
            "Each dictation names the microphone that recorded it on its History card.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The microphones the phone can see, kept current while the page is open. Read in the app process for
 * display only; the capture process reads its own list when a take starts.
 */
@Composable
private fun rememberConnectedInputs(): List<InputDeviceCandidate> {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    // Microphones only (the phone also lists its telephony port and a playback capture as sources),
    // one row per identity. The resolver applies the same filter to a stored pick, so a value written
    // by an older build or by hand falls back to Auto rather than recording the wrong port.
    // A read that throws shows the empty list (Auto, "No microphone found") rather than killing the
    // settings screen; the capture service reads its own list when the take starts.
    fun read(): List<InputDeviceCandidate> = runCatching {
        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.map(InputDeviceCandidate::from)
            ?.let(InputDeviceResolver::pickable)
    }.getOrNull().orEmpty()
    var inputs by remember { mutableStateOf(read()) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { inputs = read() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { inputs = read() }
        }
        audioManager?.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }
    return inputs
}

@Composable
private fun InputDeviceRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
