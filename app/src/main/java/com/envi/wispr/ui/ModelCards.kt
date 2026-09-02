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
 * A card's meters, on the 1-3 scale the cloud model rows use, so a card and a row can be compared. More
 * dots is more of the named thing.
 *
 * There is no cost here. A card describes ONE model, and every model that has a card is free, so a cost
 * meter would encode a constant; the cloud rows keep theirs because their prices differ from each other.
 * Adding one back means answering what it would vary with.
 */
internal data class ModelScores(val speed: Int, val accuracy: Int)

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
                Row(
                    // Scrollable for the same reason the facts row above it is: these labels are words,
                    // and at a large system font scale two of them are wider than a narrow phone.
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ScoreBar("Speed", scores.speed)
                    ScoreBar("Accuracy", scores.accuracy)
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

/**
 * A download size in the units the rest of the phone uses.
 *
 * DECIMAL, not binary, and the unit is the whole point of the function. Android's own
 * `Formatter.formatFileSize` and the Play Store listing both divide by 1000, so a model shown as
 * `484.2 MB` here is the same number the user reads on the store page and in Settings. Dividing by 1024
 * and still writing MB reported S1-mini as `461.8 MB`, which is its size in MiB and understates the space
 * it takes by 22 MB against every other number on the phone.
 *
 * The platform function is not used because it needs a `Context` and cannot be reached from a JVM unit
 * test; `PolishLadderTest` pins this one's convention instead. It formats in the DEFAULT locale, so a
 * German phone reads `484,2 MB`, which is correct and is why no test asserts an English literal.
 */
internal fun formatModelBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1_000L} KB"
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

