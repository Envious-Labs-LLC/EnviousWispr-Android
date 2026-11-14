package com.envi.wispr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelDownloadPolicy
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.ui.theme.brandButtonColors
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.LocalPolishModel
import com.envi.wispr.polish.S1Context
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.polish.S1Structure
import com.envi.wispr.polish.S1Styling

@Composable
internal fun LocalPolishModelSelector(
    viewed: LocalPolishModel,
    active: LocalPolishModel?,
    s1State: ModelUiState,
    eg1State: ModelUiState,
    enabled: Boolean,
    onSelect: (LocalPolishModel) -> Unit,
) {
    RungHeader("2 · MODEL")
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        LocalPolishModel.entries.forEach { model ->
            val state = if (model == LocalPolishModel.S1_MINI) s1State else eg1State
            val descriptor = model.descriptor()
            val selected = model == viewed
            val running = model == active
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.small,
                color = if (selected) colors.surfaceContainerLow else colors.surfaceVariant,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
            ) {
                Column(
                    Modifier.selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(model) })
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(formatModelBytes(descriptor.files.sumOf { it.expectedBytes }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(descriptor.creator, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val status = when {
                        running -> "Active"
                        state.health == ModelHealth.READY -> "Downloaded"
                        state.action == ModelUiAction.DOWNLOAD -> "Not downloaded"
                        else -> state.label
                    }
                    Text(
                        status,
                        style = if (running) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                        color = when {
                            running -> colors.primary
                            state.health == ModelHealth.BROKEN -> colors.error
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalPolishDeliveryActions(
    model: LocalPolishModel,
    state: ModelUiState,
    enabled: Boolean,
    fallback: LocalPolishModel?,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val descriptor = model.descriptor()
    var confirm by rememberSaveable(model.name) { mutableStateOf(false) }
    var requestError by remember(model) { mutableStateOf<String?>(null) }
    fun request(error: String, operation: suspend () -> Unit) {
        requestError = null
        ModelDeliveryWorker.launch(
            onFailure = { withContext(Dispatchers.Main) { requestError = error } },
            block = operation,
        )
    }
    val action = state.action
    val inProgress = action == ModelUiAction.PAUSE || action == ModelUiAction.RESUME
    if (inProgress && state.total > 0) {
        LinearProgressIndicator(
            progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${formatModelBytes(state.bytes)} of ${formatModelBytes(state.total)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    (requestError ?: state.reason)?.let { ErrorLine(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (action) {
            ModelUiAction.DOWNLOAD, ModelUiAction.RETRY, ModelUiAction.REPAIR, ModelUiAction.UPDATE -> {
                Button(
                    enabled = enabled,
                    colors = brandButtonColors(),
                    onClick = {
                        request("Could not start the download. Try again.") {
                            when (action) {
                                ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, descriptor, activateForPolish = true)
                                ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, descriptor, activateForPolish = true)
                                ModelUiAction.DOWNLOAD, ModelUiAction.RETRY -> ModelDeliveryWorker.enqueue(context, descriptor, activateForPolish = true)
                                ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.REMOVE, ModelUiAction.CANCEL, ModelUiAction.NONE -> Unit
                            }
                        }
                    },
                ) {
                    Text(when (action) {
                        ModelUiAction.REPAIR -> "Repair"
                        ModelUiAction.UPDATE -> "Update"
                        ModelUiAction.RETRY -> "Retry download"
                        ModelUiAction.DOWNLOAD, ModelUiAction.PAUSE, ModelUiAction.RESUME,
                        ModelUiAction.REMOVE, ModelUiAction.CANCEL, ModelUiAction.NONE -> "Download"
                    })
                }
            }
            ModelUiAction.REMOVE -> OutlinedButton(
                enabled = enabled,
                onClick = { confirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Uninstall") }
            ModelUiAction.PAUSE, ModelUiAction.CANCEL -> TextButton(enabled = enabled, onClick = {
                request("Could not pause the download.") { ModelDeliveryWorker.pause(context, descriptor) }
            }) { Text("Pause") }
            ModelUiAction.RESUME -> TextButton(enabled = enabled, onClick = {
                request("Could not resume the download.") { ModelDeliveryWorker.resume(context, descriptor, activateForPolish = true) }
            }) { Text("Resume") }
            ModelUiAction.NONE -> Unit
        }
        if (inProgress) {
            TextButton(enabled = enabled, onClick = {
                request("Could not cancel the download.") { ModelDeliveryWorker.cancel(context, descriptor) }
            }) { Text("Cancel download") }
        }
        if (state.offersMobileData) {
            TextButton(enabled = enabled, onClick = {
                request("Could not start the download on mobile data.") { ModelDownloadPolicy.allowMobileData(context, descriptor) }
            }) { Text("Download now on mobile data") }
        }
    }
    if (confirm && action == ModelUiAction.REMOVE) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Uninstall ${model.displayName}?") },
            text = {
                val after = fallback?.let { "${it.displayName} will be used for polish." }
                    ?: "Basic cleanup will continue until you install a model."
                Text("Frees ${formatModelBytes(descriptor.files.sumOf { it.expectedBytes })} on this phone. $after")
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep model") } },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onUninstall()
                    request("Could not request uninstall. Check the model status and try again.") { ModelDeliveryWorker.enqueueRemove(context, descriptor) }
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
internal fun Eg1Strengths() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Turn spoken thoughts into finished text", style = MaterialTheme.typography.titleSmall)
            Text(
                "Drops filler, fixes grammar and punctuation, and follows mid-sentence corrections. It formats emails and spoken lists while keeping your meaning.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Android head-to-head", style = MaterialTheme.typography.titleSmall)
            Text("Results pending. Both models will polish the same benchmark dictations on Android phones.", style = MaterialTheme.typography.bodySmall)
            listOf("Measure" to "S1-mini", "English quality" to "TBD", "Other languages" to "TBD", "Polish time" to "TBD")
                .forEachIndexed { index, (measure, s1) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val style = if (index == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
                        Text(measure, Modifier.weight(1.35f), style = style)
                        Text(s1, Modifier.weight(1f), style = style)
                        Text(if (index == 0) "EG-1 Micro" else "TBD", Modifier.weight(1f), style = style)
                    }
                }
        }
    }
}

/**
 * The Writing style card (#152): S1-mini's three trained control-line axes as chips, rendered from the
 * PERSISTED picks only. A tap writes the whole triple through the tab's one-write-at-a-time tracking, so
 * the chips are disabled while a write is in flight and a failure lands under this card. `FlowRow` so
 * the four Tone chips wrap at phone width rather than clipping.
 */
@Composable
internal fun S1ControlCard(
    control: S1ControlSettings,
    enabled: Boolean,
    error: String?,
    onPick: (S1ControlSettings) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Writing style", style = MaterialTheme.typography.titleSmall)
        ControlAxis(S1ControlCopy.STYLING_LABEL) {
            S1Styling.entries.forEach { option ->
                FilterChip(
                    selected = control.styling == option,
                    enabled = enabled,
                    onClick = { if (control.styling != option) onPick(control.copy(styling = option)) },
                    label = { Text(S1ControlCopy.label(option)) },
                )
            }
        }
        ControlAxis(S1ControlCopy.STRUCTURE_LABEL) {
            S1Structure.entries.forEach { option ->
                FilterChip(
                    selected = control.structure == option,
                    enabled = enabled,
                    onClick = { if (control.structure != option) onPick(control.copy(structure = option)) },
                    label = { Text(S1ControlCopy.label(option)) },
                )
            }
        }
        ControlAxis(S1ControlCopy.CONTEXT_LABEL) {
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

/** One axis: its label, a wrapping row of chips, and the choices underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlAxis(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
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
