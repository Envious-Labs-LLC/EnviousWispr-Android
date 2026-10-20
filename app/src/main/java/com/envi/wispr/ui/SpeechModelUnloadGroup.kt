package com.envi.wispr.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.envi.wispr.settings.SpeechModelUnload

/** The line under the title: the macOS help text (`DictationSettingsCopy`), kept word for word. */
internal const val SPEECH_MODEL_UNLOAD_LINE = "Frees memory when you are not dictating."

/** Shown only for Immediately: the macOS warning, with "recording" made "dictation". */
internal const val SPEECH_MODEL_UNLOAD_IMMEDIATELY_LINE = "Expect a reload delay on each dictation."

/**
 * "Unload model after" (#37), under the speech model's card on the Transcription page (founder 2026-10-21), as on
 * macOS where the picker sits with its speech engine. One choice of seven; a change applies at once to a model
 * already held and from the next transcript otherwise (`asr/SpeechModelKeepAlive`).
 */
@Composable
internal fun SpeechModelUnloadGroup(selected: SpeechModelUnload, onSelected: (SpeechModelUnload) -> Unit) {
    val view = LocalView.current
    SettingsGroup("Unload model after") {
        Text(
            SPEECH_MODEL_UNLOAD_LINE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
            SpeechModelUnload.entries.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = choice == selected, role = Role.RadioButton) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelected(choice)
                        }
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = choice == selected, onClick = null)
                    Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (selected == SpeechModelUnload.IMMEDIATELY) {
            Text(
                SPEECH_MODEL_UNLOAD_IMMEDIATELY_LINE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
}
