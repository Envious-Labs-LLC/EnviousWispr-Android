package com.envi.wispr.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.settings.AppPreferencesState

/**
 * The words this sentence NAMES must be words cleanup actually removes. `um` was named here while it was
 * being deleted from German, Portuguese and Croatian, and removing it from the filler set left this line
 * promising something untrue (#36, #107). `FillerCopyTest` binds the two together.
 */
internal const val FILLER_TOGGLE_SUBTITLE = "Remove pauses such as uh and hmm."

/**
 * The speech engine that turns your voice into text, and the rules that tidy the result.
 *
 * The cleanup switches sit here rather than on their own page because the macOS reference puts
 * `fillerRemovalEnabled`, `emojiFormatterEnabled` and `spokenPunctuationEnabled` on the same page as
 * the speech engine. They run before AI Polish, so this is where the user reads about them.
 */
@Composable
internal fun TranscriptionScreen(
    readiness: AppReadiness,
    preferences: AppPreferencesState,
    onRefreshReadiness: () -> Unit,
    onFillerRemovalChanged: (Boolean) -> Unit,
    onEmojiFormatterChanged: (Boolean) -> Unit,
    onSpokenPunctuationChanged: (Boolean) -> Unit,
    onAutoStopOnSilenceChanged: (Boolean) -> Unit,
    onSilencePauseSecondsChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val parakeetWork by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val parakeetAdoption by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val parakeetState = workUiState(preferredModelWork(parakeetWork, parakeetAdoption), readiness.speechModelReady, ModelManifest.parakeet, context)
    fun updateWithHaptic(value: Boolean, update: (Boolean) -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        update(value)
    }
    ScreenContainer(subtitle = "The speech engine that turns your voice into text.") {
        ModelCard(
            eyebrow = "SPEECH ENGINE",
            title = "Parakeet",
            description = "Your offline speech engine. It listens and transcribes on this phone, so your voice never leaves it.",
            state = parakeetState,
            facts = listOf("Offline", "25 languages", "Stays on this phone"),
            onAction = {
                // Exhaustive with no `else`, so a new ModelUiAction is a compile error here rather
                // than a silent download. The four inert members are the ones `ModelCard` routes to
                // onPause, onResume, or to no button at all, so this lambda never sees them.
                when (parakeetState.action) {
                    ModelUiAction.REMOVE -> ModelDeliveryWorker.enqueueRemove(context, ModelManifest.parakeet)
                    ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, ModelManifest.parakeet)
                    ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, ModelManifest.parakeet)
                    ModelUiAction.DOWNLOAD, ModelUiAction.RETRY ->
                        ModelDeliveryWorker.enqueue(context, ModelManifest.parakeet)
                    ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.CANCEL,
                    ModelUiAction.NONE -> Unit
                }
                onRefreshReadiness()
            },
            onPause = { ModelDeliveryWorker.pause(context, ModelManifest.parakeet) },
            onResume = { ModelDeliveryWorker.resume(context, ModelManifest.parakeet) },
        )
        SettingsGroup("Recording") {
            SettingsToggleRow(
                title = "Stop recording on silence",
                subtitle = "End the recording by itself when you stop speaking, instead of pressing stop.",
                checked = preferences.autoStopOnSilenceEnabled,
                onCheckedChange = { updateWithHaptic(it, onAutoStopOnSilenceChanged) },
            )
            if (preferences.autoStopOnSilenceEnabled) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingsSliderRow(
                    title = "How long to wait",
                    subtitle = "How long to wait after you stop speaking before ending the recording. " +
                        "Recording can take a moment longer to stop while your voice fades.",
                    value = preferences.silencePauseSeconds,
                    valueRange = 0.5f..3.0f,
                    // Eleven positions: 0.5 to 3.0 in quarter seconds, so nine sit between the ends.
                    steps = 9,
                    valueLabel = "about ${"%.2f".format(preferences.silencePauseSeconds).trimEnd('0').trimEnd('.')}s",
                    onValueChange = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSilencePauseSecondsChanged(it)
                    },
                )
            }
        }
        Text(
            "Auto-stop listens on this phone only. It never sends anything anywhere, and it never " +
                "changes what you said, only when the recording ends.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup("Text cleanup") {
            SettingsToggleRow(
                title = "Remove filler words",
                subtitle = FILLER_TOGGLE_SUBTITLE,
                checked = preferences.fillerRemovalEnabled,
                onCheckedChange = { updateWithHaptic(it, onFillerRemovalChanged) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Spoken emoji",
                subtitle = "Turn explicit commands such as thumbs up emoji into symbols.",
                checked = preferences.emojiFormatterEnabled,
                onCheckedChange = { updateWithHaptic(it, onEmojiFormatterChanged) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            SettingsToggleRow(
                title = "Spoken punctuation",
                subtitle = "Turn commands such as comma and new paragraph into punctuation.",
                checked = preferences.spokenPunctuationEnabled,
                onCheckedChange = { updateWithHaptic(it, onSpokenPunctuationChanged) },
            )
        }
        Text(
            "These rules run on this phone, before AI Polish. Turn off anything you prefer to dictate literally. A change here applies to your next dictation, never to one already in progress.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Languages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "EnviousWispr transcribes 25 European languages. There is nothing to choose: speak, and it writes what it hears, with no network. Tidying up spoken numbers, dates and money is still built for English.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

