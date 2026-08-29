package com.envi.wispr.models

enum class ModelUiAction { DOWNLOAD, PAUSE, RESUME, RETRY, REPAIR, REMOVE, UPDATE, CANCEL, NONE }

data class ModelUiState(
    val label: String,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val reason: String? = null,
    val action: ModelUiAction = ModelUiAction.NONE,
)

/** Maps persisted WorkManager and model-control values to honest lifecycle controls. */
fun modelUiState(
    verifiedReady: Boolean,
    workState: String?,
    progressState: String? = null,
    bytes: Long = 0L,
    total: Long = 0L,
    reason: String? = null,
    controlState: String? = null,
    staleInstalled: Boolean = false,
): ModelUiState {
    val normalizedWork = workState?.uppercase()
    val normalizedProgress = progressState?.uppercase()
    val safeBytes = bytes.coerceAtLeast(0L)
    val safeTotal = total.coerceAtLeast(0L)
    val normalizedControl = controlState?.uppercase()
    val paused = normalizedControl == ModelDeliveryControlState.PAUSED.name
    val cancelled = normalizedControl == ModelDeliveryControlState.CANCELLED.name
    return when (normalizedWork) {
        "ENQUEUED", "RUNNING" -> if (paused) {
            ModelUiState("Paused", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.RESUME)
        } else ModelUiState(
            label = if (normalizedProgress == DownloadState.VERIFYING.name) "Verifying" else if (normalizedWork == "ENQUEUED") "Queued" else "Downloading",
            bytes = safeBytes,
            total = safeTotal,
            reason = reason?.takeIf(String::isNotBlank),
            action = ModelUiAction.PAUSE,
        )
        "FAILED", "CANCELLED" -> if (paused) {
            ModelUiState("Paused", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.RESUME)
        } else if (verifiedReady) {
            ModelUiState("Ready", safeBytes, safeTotal, action = ModelUiAction.REMOVE)
        } else if (cancelled) {
            ModelUiState("Cancelled", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.RETRY)
        } else if (staleInstalled) {
            ModelUiState("Update failed", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.UPDATE)
        } else {
            ModelUiState(
                label = if (normalizedWork == "CANCELLED") "Cancelled" else "Failed",
                bytes = safeBytes,
                total = safeTotal,
                reason = reason?.takeIf(String::isNotBlank),
                action = ModelUiAction.RETRY,
            )
        }
        "SUCCEEDED" -> if (verifiedReady) {
            ModelUiState("Ready", safeBytes, safeTotal, action = ModelUiAction.REMOVE)
        } else if (staleInstalled) {
            ModelUiState("Update available", safeBytes, safeTotal, action = ModelUiAction.UPDATE)
        } else {
            ModelUiState("Checking", safeBytes, safeTotal, reason, ModelUiAction.NONE)
        }
        else -> if (paused) {
            ModelUiState("Paused", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.RESUME)
        } else if (cancelled) {
            ModelUiState("Cancelled", safeBytes, safeTotal, reason?.takeIf(String::isNotBlank), ModelUiAction.RETRY)
        } else if (verifiedReady) {
            ModelUiState("Ready", safeBytes, safeTotal, action = ModelUiAction.REMOVE)
        } else if (normalizedProgress == DownloadState.REPAIR_NEEDED.name) {
            ModelUiState("Repair needed", safeBytes, safeTotal, reason, ModelUiAction.REPAIR)
        } else if (staleInstalled) {
            ModelUiState("Update available", safeBytes, safeTotal, reason, ModelUiAction.UPDATE)
        } else {
            ModelUiState("Missing", safeBytes, safeTotal, reason, ModelUiAction.DOWNLOAD)
        }
    }
}
