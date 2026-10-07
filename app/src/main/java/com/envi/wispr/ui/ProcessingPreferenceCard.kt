package com.envi.wispr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.envi.wispr.processing.*

/** This surface renders qualified options; it never discovers hardware or runs a model itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProcessingPreferenceCard(
    model: String,
    preference: ProcessingPreference,
    implemented: Set<ProcessingBackend>,
    qualified: Set<ProcessingBackend>,
    checks: Map<ProcessingBackend, ProcessingCheckStatus?>,
    enabled: Boolean,
    error: String?,
    onSave: (ProcessingPreference) -> Unit,
    onCheck: ((ProcessingBackend) -> Unit)?,
    onRetry: ((ProcessingPreference) -> Unit)? = null,
    latestResult: String? = null,
    onCloseChecks: () -> Unit = {},
    modelReady: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onCloseChecks() } }
    val order = (preference.customOrder ?: BackendOrder.DEFAULT).backends
    val available = order.filter { modelReady && it in implemented && it in qualified }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Processing preference", style = MaterialTheme.typography.titleSmall)
                    Text(if (preference.automatic) "Automatic" else available.joinToString(" → ") { it.shortLabel }.ifEmpty { "Custom order. No checked option available." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(enabled = enabled, onClick = { open = true }) { Text("Change") }
            }
            Text("Changes apply to your next dictation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            latestResult?.takeIf { it.isNotBlank() }?.let { Text("Last result: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { ErrorLine(it) }
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false; onCloseChecks() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$model processing", style = MaterialTheme.typography.titleLarge)
            Text("Choose where this model tries to run first.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = preference.automatic, enabled = enabled,
                    onClick = { onSave(preference.copy(automatic = true)) }, label = { Text("Automatic") },
                    modifier = Modifier.semantics { contentDescription = "Use Automatic" })
                FilterChip(selected = !preference.automatic, enabled = enabled && (available.isNotEmpty() || preference.customOrder != null),
                    onClick = { onSave(preference.custom(available)) }, label = { Text("Custom order") },
                    modifier = Modifier.semantics { contentDescription = "Use Custom order" })
            }
            Text(if (preference.automatic) "Keeps the current defaults. Experimental model paths are not selected automatically." else "Try checked options in your order. Your saved order stays intact when an option is unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            available.forEachIndexed { index, backend ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                    Text("${backend.label} (${backend.shortLabel})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (!preference.automatic && available.size > 1) {
                        TextButton(enabled = enabled && index > 0, onClick = { onSave(preference.move(backend, -1, qualified)) }, modifier = Modifier.semantics { contentDescription = "Move ${backend.label} earlier" }) { Text("↑") }
                        TextButton(enabled = enabled && index < available.lastIndex, onClick = { onSave(preference.move(backend, 1, qualified)) }, modifier = Modifier.semantics { contentDescription = "Move ${backend.label} later" }) { Text("↓") }
                    }
                }
            }
            if (available.isEmpty()) Text("No checked option for this model yet. Automatic retains its existing behavior.", style = MaterialTheme.typography.bodyMedium)
            if (available.size < ProcessingBackend.entries.size) {
                HorizontalDivider()
                Text("Other choices", style = MaterialTheme.typography.titleSmall)
                order.filter { !modelReady || it !in qualified || it !in implemented }.forEach { backend ->
                    val status = checks[backend]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${backend.label} (${backend.shortLabel})", style = MaterialTheme.typography.bodyMedium)
                            val message = when {
                                backend !in implemented -> "Not supported for this model in this version."
                                !modelReady -> "Download and verify the model first."
                                checks.containsKey(backend) && status == null -> "Checking on this device"
                                status == ProcessingCheckStatus.RUNTIME_FAILED -> "Local model software could not start."
                                status == ProcessingCheckStatus.MODEL_MISSING -> "Download the model first."
                                status == ProcessingCheckStatus.EXPIRED -> "The check did not finish. Try again."
                                status != null -> "This option did not pass its model check."
                                else -> "Check this model on this device before choosing it."
                            }
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (backend in implemented && onCheck != null) {
                            TextButton(enabled = enabled && !(checks.containsKey(backend) && status == null), onClick = { onCheck(backend) }) { Text("Check ${backend.shortLabel}") }
                        }
                    }
                }
            }
            if (onRetry != null) {
                TextButton(enabled = enabled, onClick = { onRetry(preference.retry()) }) { Text("Retry preferred option next time") }
            }
            error?.let { ErrorLine(it) }
            Button(onClick = { open = false; onCloseChecks() }, modifier = Modifier.fillMaxWidth(), colors = com.envi.wispr.ui.theme.brandButtonColors()) { Text("Done") }
        }
    }
}
