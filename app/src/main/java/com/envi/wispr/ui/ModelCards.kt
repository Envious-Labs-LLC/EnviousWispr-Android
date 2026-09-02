package com.envi.wispr.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryControlStore
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.models.modelUiState

/**
 * Cost, speed and accuracy on the 1-3 scale the cloud model rows use, so a card and a row can be compared.
 * More dots is more of the named thing, which means a low cost score is the cheap one.
 */
internal data class ModelScores(val cost: Int, val speed: Int, val accuracy: Int)

@Composable
internal fun ModelCard(
    eyebrow: String,
    title: String,
    description: String?,
    state: ModelUiState,
    facts: List<String>,
    scores: ModelScores? = null,
    onAction: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    ElevatedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                }
                StatusPill(state.label, state.health == ModelHealth.READY)
            }
            // A card whose state speaks for itself passes null, so the facts row is not restated as prose.
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                // Scrollable because the labels are words, not codes, so three of them can be wider
                // than a phone.
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                facts.forEach { fact -> FactPill(fact) }
            }
            if (scores != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    ScoreColumn("Cost", scores.cost)
                    ScoreColumn("Speed", scores.speed)
                    ScoreColumn("Accuracy", scores.accuracy)
                }
            }
            if (state.total > 0L && (state.action == ModelUiAction.PAUSE || state.action == ModelUiAction.RESUME)) {
                LinearProgressIndicator(
                    progress = { (state.bytes.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatModelBytes(state.bytes)} of ${formatModelBytes(state.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state.action) {
                ModelUiAction.DOWNLOAD -> TextButton(onClick = onAction) { Text("Download") }
                ModelUiAction.RETRY -> TextButton(onClick = onAction) { Text("Retry") }
                ModelUiAction.PAUSE -> TextButton(onClick = onPause) { Text("Pause") }
                ModelUiAction.RESUME -> TextButton(onClick = onResume) { Text("Resume") }
                ModelUiAction.REPAIR -> TextButton(onClick = onAction) { Text("Repair") }
                ModelUiAction.REMOVE -> OutlinedButton(onClick = onAction) { Text("Remove") }
                ModelUiAction.UPDATE -> TextButton(onClick = onAction) { Text("Update") }
                ModelUiAction.CANCEL -> TextButton(onClick = onPause) { Text("Pause") }
                ModelUiAction.NONE -> Unit
            }
            state.reason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

internal fun workUiState(info: WorkInfo?, ready: Boolean, model: com.envi.wispr.models.ModelDescriptor, context: android.content.Context): ModelUiState {
    val progressState = info?.progress?.getString(ModelDeliveryWorker.KEY_STATE)
    val bytes = info?.progress?.getLong(ModelDeliveryWorker.KEY_BYTES, 0L) ?: 0L
    val total = info?.progress?.getLong(ModelDeliveryWorker.KEY_TOTAL, 0L) ?: 0L
    val reason = info?.outputData?.getString(ModelDeliveryWorker.KEY_REASON).orEmpty()
        .ifBlank { info?.progress?.getString(ModelDeliveryWorker.KEY_REASON).orEmpty() }
        .ifBlank { null }
    val controls = ModelDeliveryControlStore(com.envi.wispr.models.ModelStorage.root(context)).read(model)
    val stale = ModelDeliveryWorker.hasStaleInstallation(context, model)
    return modelUiState(ready, info?.state?.name, progressState, bytes, total, reason, controls.name, stale)
}

internal fun preferredModelWork(download: List<WorkInfo>, adoption: List<WorkInfo>): WorkInfo? {
    val active = (download + adoption).firstOrNull { !it.state.isFinished }
    return active ?: download.firstOrNull() ?: adoption.firstOrNull()
}

@Composable
internal fun ModelWorkReadinessObserver(onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val parakeetDownload by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Download by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val parakeetAdoption by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet)).collectAsStateWithLifecycle(emptyList())
    val s1Adoption by workManager.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(
        parakeetDownload.firstOrNull()?.state,
        s1Download.firstOrNull()?.state,
        parakeetAdoption.firstOrNull()?.state,
        s1Adoption.firstOrNull()?.state,
    ) {
        if (listOf(parakeetDownload, s1Download, parakeetAdoption, s1Adoption)
                .flatten()
                .any { it.state.isFinished }) {
            onRefreshReadiness()
        }
    }
}

internal fun formatModelBytes(bytes: Long): String = when {
    bytes >= 1_024L * 1_024L * 1_024L -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
    bytes >= 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    else -> "${bytes / 1_024L} KB"
}

/** One labelled meter, the same dots the cloud model rows use, so the legend is the word under it. */
@Composable
private fun ScoreColumn(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScoreDots(value)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A display-only label on a model card. Not a chip, because a chip invites a tap that does nothing. */
@Composable
private fun FactPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

