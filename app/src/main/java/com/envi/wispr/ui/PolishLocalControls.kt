package com.envi.wispr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import com.envi.wispr.polish.DevelopmentPolishModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.S1Config
import com.envi.wispr.polish.S1Context
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.polish.S1Structure
import com.envi.wispr.polish.S1Styling

/** The S1-mini card inside This phone, exactly what the old local-model page did (#67), now inline. */
@Composable
internal fun S1Card(s1State: ModelUiState, onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    ModelCard(
        eyebrow = "ON THIS PHONE",
        title = S1Config.MODEL_NAME,
        description = PolishLadder.s1Line(s1State),
        state = s1State,
        facts = PolishLadder.s1Facts(),
        scores = PolishLadder.S1_SCORES,
        model = ModelManifest.s1,
        onAction = {
            // Exhaustive with no `else`, for the reason given at the same `when` in `TranscriptionScreen`.
            when (s1State.action) {
                ModelUiAction.REMOVE -> ModelDeliveryWorker.enqueueRemove(context, ModelManifest.s1)
                ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, ModelManifest.s1)
                ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, ModelManifest.s1)
                ModelUiAction.DOWNLOAD, ModelUiAction.RETRY -> ModelDeliveryWorker.enqueue(context, ModelManifest.s1)
                ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.CANCEL, ModelUiAction.NONE -> Unit
            }
            onRefreshReadiness()
        },
        onPause = { ModelDeliveryWorker.pause(context, ModelManifest.s1) },
        onResume = { ModelDeliveryWorker.resume(context, ModelManifest.s1) },
    )
}

/**
 * The Writing style card (#152): S1-mini's three trained control-line axes as chips, rendered from the
 * PERSISTED picks only. A tap writes the whole triple through the tab's one-write-at-a-time tracking, so
 * the chips are disabled while a write is in flight and a failure lands under this card. `FlowRow` so
 * the four Tone chips wrap at phone width rather than clipping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun S1ControlCard(
    control: S1ControlSettings,
    enabled: Boolean,
    error: String?,
    onPick: (S1ControlSettings) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(S1ControlCopy.EYEBROW, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(S1ControlCopy.INTRO, style = MaterialTheme.typography.bodyMedium)
            ControlAxis(S1ControlCopy.STYLING_LABEL, S1ControlCopy.STYLING_HINT) {
                S1Styling.entries.forEach { option ->
                    FilterChip(
                        selected = control.styling == option,
                        enabled = enabled,
                        onClick = { if (control.styling != option) onPick(control.copy(styling = option)) },
                        label = { Text(S1ControlCopy.label(option)) },
                    )
                }
            }
            ControlAxis(S1ControlCopy.STRUCTURE_LABEL, S1ControlCopy.STRUCTURE_HINT) {
                S1Structure.entries.forEach { option ->
                    FilterChip(
                        selected = control.structure == option,
                        enabled = enabled,
                        onClick = { if (control.structure != option) onPick(control.copy(structure = option)) },
                        label = { Text(S1ControlCopy.label(option)) },
                    )
                }
            }
            ControlAxis(S1ControlCopy.CONTEXT_LABEL, S1ControlCopy.CONTEXT_HINT) {
                S1Context.entries.forEach { option ->
                    FilterChip(
                        selected = control.context == option,
                        enabled = enabled,
                        onClick = { if (control.context != option) onPick(control.copy(context = option)) },
                        label = { Text(S1ControlCopy.label(option)) },
                    )
                }
            }
            if (error != null) ErrorLine(error)
        }
    }
}

/** One axis: its label, a wrapping row of chips, and the one-sentence hint under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlAxis(label: String, hint: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The development-models folder: what it costs, and a way to be rid of it.
 *
 * **Debuggable builds only, and absent rather than disabled otherwise.** A release build never reads
 * this folder, so offering to manage it would name a capability that build does not have.
 * `DevelopmentPolishModel.isSupported` owns that test.
 *
 * **It describes a FOLDER, and says nothing about what is in it.** Three sentences were tried here and
 * all three claimed more than the code establishes: that polish was running from the file, that a
 * measured speed came from it, and that a named model was present and invalid. The last is the one that
 * settled it, because a folder holding some other file entirely produced a confident sentence about a
 * model that was not there. What is actually known is the SIZE and the SCOPE, so that is all it says
 * (issue #21, review 2026-09-06, against a consequence declared before the verdict was read).
 *
 * The thing the removed sentences were reaching for, which model produced a given latency number, is
 * real and belongs where a benchmark is reported rather than asserted by a card about a folder.
 */
@Composable
internal fun DevelopmentModelCard() {
    val context = LocalContext.current
    if (!DevelopmentPolishModel.isSupported(context)) return
    val scope = rememberCoroutineScope()

    // Bumped after a removal, so the card measures again rather than showing what was just deleted.
    var generation by rememberSaveable { mutableIntStateOf(0) }
    // Plain `remember`, NOT rememberSaveable. It describes the last attempt in this sitting. Surviving
    // a recreation would let it describe contents that were replaced while the screen was away.
    var lastRemovalFailed by remember { mutableStateOf(false) }

    // THREE states, not two. `null` is PENDING; a `Result` is an answer, successful or not. Collapsing
    // pending into failure made the card say "Could not measure what is in it" for the moment before
    // the first measurement returned, and again after every removal, which is a failure reported before
    // one has happened.
    val measured by key(generation) {
        produceState<Result<Long>?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                runCatching { DevelopmentPolishModel.bytesOnDisk(context) }
            }
        }
    }

    val measurement = measured
    val bytes = measurement?.getOrNull()
    // Nothing to show while still measuring, and nothing to show when the folder is measurably empty. A
    // FAILED measurement still shows the card, because a removal that failed must be able to say so even
    // when the next measurement of that same broken folder also fails.
    if (measurement == null && !lastRemovalFailed) return
    if (bytes == 0L && !lastRemovalFailed) return

    ElevatedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "DEVELOPMENT ONLY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Development models folder", style = MaterialTheme.typography.headlineMedium)
            Text(
                // Says only what is checkable. An earlier version said the files were put here BY
                // HAND, which is a claim about who created them that nothing here establishes: another
                // development tool could write into this folder just as easily.
                "Storage for development files. A released build never selects a model from here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Silent while the measurement is still in flight. A size line that appears only once
            // there is an answer cannot report a failure that has not happened.
            if (measurement != null) {
                Text(
                    if (bytes != null) {
                        "${formatModelBytes(bytes)} on this phone"
                    } else {
                        "Could not measure what is in it"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lastRemovalFailed) {
                Text(
                    "The last attempt to remove it did not finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = {
                    // Whether it worked is read from the world afterwards, never assumed, and a failure
                    // is SAID. It is recorded separately from the measurement so a folder that cannot
                    // be measured either can still explain itself.
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            runCatching { DevelopmentPolishModel.delete(context) }.getOrDefault(false)
                        }
                        lastRemovalFailed = !removed
                        generation += 1
                    }
                },
            ) { Text("Remove") }
        }
    }
}
