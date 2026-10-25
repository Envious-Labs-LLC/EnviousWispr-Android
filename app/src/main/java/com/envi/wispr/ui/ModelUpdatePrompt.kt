package com.envi.wispr.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelDownloadPolicy
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelUpdates
import com.envi.wispr.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What the update prompt says for one model revision (#108). */
internal data class ModelUpdatePromptContent(val model: ModelDescriptor, val title: String, val body: String)

internal object ModelUpdatePromptCopy {
    /**
     * The "keeps working" sentence is a promise, so it is said only when the installed previous model fully verifies
     * ([keepsWorking], plan §18.6).
     */
    fun of(model: ModelDescriptor, keepsWorking: Boolean): ModelUpdatePromptContent {
        val speech = model.id == ModelManifest.parakeet.id
        val size = formatModelBytes(model.files.sumOf { it.expectedBytes })
        val body = buildString {
            append("The update is $size. Download it now on mobile data, or wait for Wi-Fi.")
            if (keepsWorking) append(if (speech) " Dictation keeps working with your current model until it finishes." else " Polish keeps working with your current model until it finishes.")
        }
        return ModelUpdatePromptContent(model, if (speech) "New speech model available" else "New polish model available", body)
    }
}

/**
 * Once per model revision, when the app opens off Wi-Fi with a model update pending and downloads set to Wi-Fi only:
 * "Download now" lets this revision use mobile data without changing the setting; "Wait for Wi-Fi" keeps it Wi-Fi only.
 * Either answer is remembered, and the model's card keeps offering mobile data while it waits.
 */
@Composable
internal fun ModelUpdatePrompt() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var content by remember { mutableStateOf<ModelUpdatePromptContent?>(null) }
    LaunchedEffect(Unit) {
        content = withContext(Dispatchers.IO) {
            runCatching {
                val state = preferences.authoritativeState.first()
                val onWifi = ModelDownloadPolicy.onWifi(context)
                val root = ModelStorage.root(context)
                ModelManifest.all.firstOrNull { model ->
                    ModelDownloadPolicy.promptOwed(
                        updatePending = ModelUpdates.owed(root, model),
                        onWifi = onWifi,
                        anyNetwork = ModelDownloadPolicy.anyNetwork(state.modelDownloadsOnMobileData, state.mobileDataApprovedRevisions, model),
                        answered = state.answeredUpdatePrompts,
                        model = model,
                    )
                }?.let { ModelUpdatePromptCopy.of(it, keepsWorking = ModelStorage.usable(context, it) != null) }
            }.getOrNull()
        }
    }
    val shown = content ?: return
    fun answer(downloadNow: Boolean) {
        content = null
        ModelDeliveryWorker.launch {
            if (downloadNow) ModelDownloadPolicy.allowMobileData(context, shown.model)
            else preferences.answerUpdatePrompt(ModelDownloadPolicy.revisionKey(shown.model), downloadNow = false)
            // The start-up queue may not have run yet; KEEP leaves a queued update as it is.
            ModelDeliveryWorker.enqueueOwedUpdate(context, shown.model)
        }
    }
    AlertDialog(
        // Dismissed without an answer: the Wi-Fi-only download stays queued and the prompt returns next time.
        onDismissRequest = { content = null },
        title = { Text(shown.title) },
        text = { Text(shown.body) },
        confirmButton = { TextButton(onClick = { answer(downloadNow = true) }) { Text("Download now") } },
        dismissButton = { TextButton(onClick = { answer(downloadNow = false) }) { Text("Wait for Wi-Fi") } },
    )
}
