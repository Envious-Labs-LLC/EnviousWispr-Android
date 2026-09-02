package com.envi.wispr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.S1Config
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities

/** The providers a fresh setup can pick; self-hosted is excluded by the catalog decision of 2026-09-01. */
internal val CloudProviders: List<Provider> = Provider.entries - Provider.SELF_HOSTED_POLISH

/** The saved model for [provider], or blank when the saved provider is a different one. */
internal fun savedModelFor(provider: Provider, settings: ProviderSettingsUiState): String =
    if (provider == settings.provider) settings.model else ""

/**
 * The AI Polish tab (#67): a master switch, and under it two single-choice cards for where polish runs.
 * The tab renders persisted `settings` directly; its only local state is whether the provider picker is
 * open. Provider setup lives on [ProviderSetupPage]; model management on [LocalModelPage]. The loading
 * gate from #66 stays: nothing below is built until the saved configuration is real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PolishScreen(
    settings: ProviderSettingsUiState,
    s1State: ModelUiState,
    onTurnOn: () -> Unit,
    onSetMode: (PolishMode) -> Unit,
    onOpenProviderSetup: (Provider) -> Unit,
    onOpenLocalModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onClearProvider: () -> Unit,
) {
    if (settings.loading) {
        ScreenContainer(subtitle = "Clean up and rewrite your dictation with AI.") {
             Text("Checking polish settings", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val on = settings.mode != PolishMode.OFF
    val phone = phoneCard(s1State, settings)
    val provider = providerCard(settings)

    ScreenContainer(subtitle = "Clean up and rewrite your dictation with AI.") {
                    Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Tile { SparkleGlyph() }
                    Column(Modifier.weight(1f)) {
                        Text("AI Polish", style = MaterialTheme.typography.titleSmall)
                        Text("Turns rough speech into ready-to-send text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = { checked -> if (checked) onTurnOn() else onSetMode(PolishMode.OFF) })
                }
            }
                    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!on) {
                    Text(
                        "AI Polish is off. Basic cleanup still runs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("WHERE POLISH RUNS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EngineCard(
                        selected = phone.selected,
                        selectable = phone.selectable,
                        title = "On this phone",
                        status = phone.status,
                        tone = phone.tone,
                        privacyLine = "Text stays on this phone",
                        actionLabel = when (phone.action) {
                            PhoneCardAction.MANAGE_MODEL -> "Manage model"
                            PhoneCardAction.DOWNLOAD_MODEL -> "Download model"
                        },
                        onSelect = { onSetMode(PolishMode.OFFLINE_S1) },
                        onAction = when (phone.action) {
                            PhoneCardAction.MANAGE_MODEL -> onOpenLocalModel
                            PhoneCardAction.DOWNLOAD_MODEL -> onDownloadModel
                        },
                        secondaryLabel = null,
                        onSecondary = null,
                        onBodyTap = null,
                    ) { PhoneGlyph() }
                    EngineCard(
                        selected = provider.selected,
                        selectable = provider.selectable,
                        title = provider.title,
                        status = provider.status,
                        tone = provider.tone,
                        privacyLine = provider.privacyLine,
                        actionLabel = when (provider.action) {
                            ProviderCardAction.CHOOSE_PROVIDER -> "Choose a provider"
                            ProviderCardAction.EDIT_PROVIDER -> "Edit provider"
                            ProviderCardAction.REMOVE_SELF_HOSTED -> "Remove"
                        },
                        onSelect = { onSetMode(PolishMode.PROVIDER) },
                        onAction = when (provider.action) {
                            ProviderCardAction.CHOOSE_PROVIDER -> { { showPicker = true } }
                            ProviderCardAction.EDIT_PROVIDER -> { { onOpenProviderSetup(settings.provider) } }
                            ProviderCardAction.REMOVE_SELF_HOSTED -> onClearProvider
                        },
                        secondaryLabel = if (provider.canSwitchProvider) "Switch provider" else null,
                        onSecondary = if (provider.canSwitchProvider) { { showPicker = true } } else null,
                        onBodyTap = if (provider.tapOpensPicker) { { showPicker = true } } else null,
                    ) { CloudGlyph() }
                }
            }
                    Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("If polish cannot finish", style = MaterialTheme.typography.titleSmall)
                    Text("Your cleaned transcript is still inserted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        // A failed write started on this tab. The setup page renders its own failures (#67).
        if (settings.error != null && settings.writeOrigin == ProviderWriteOrigin.TAB) {
             Text(settings.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showPicker) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a provider", style = MaterialTheme.typography.titleMedium)
                Text("Your words are sent only when this mode is used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudProviders.forEach { option ->
                    val current = settings.configured && settings.provider == option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = current, role = Role.RadioButton) {
                                showPicker = false
                                onOpenProviderSetup(option)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProviderTile(option)
                        Column(Modifier.weight(1f)) {
                            Text(option.capabilities().displayName, style = MaterialTheme.typography.titleSmall)
                            Text("Use your API key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = current, onClick = null)
                    }
                }
                TextButton(onClick = { showPicker = false }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

/**
 * One engine card: the whole card is a radio (TalkBack reads it as one), the route glyph shows only on
 * the selected card, a privacy line says where text goes, and an action row does the card's one thing.
 */
@Composable
private fun EngineCard(
    selected: Boolean,
    selectable: Boolean,
    title: String,
    status: String,
    tone: CardTone,
    privacyLine: String,
    actionLabel: String,
    onSelect: () -> Unit,
    onAction: () -> Unit,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
    onBodyTap: (() -> Unit)?,
    glyph: @Composable () -> Unit,
) {
    val statusColour = when (tone) {
        CardTone.GOOD -> MaterialTheme.colorScheme.primary
        CardTone.PROBLEM -> MaterialTheme.colorScheme.error
        CardTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base ->
                        when {
                            selectable -> base.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                            onBodyTap != null -> base.clickable(onClick = onBodyTap)
                            else -> base
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tile(glyph)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColour)
                }
                RadioButton(selected = selected, onClick = null, enabled = selectable)
            }
            if (selected) {
                RouteGlyph(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShieldGlyph()
                Text(privacyLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (secondaryLabel != null && onSecondary != null) {
                    Text(
                        secondaryLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onSecondary).padding(end = 12.dp),
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The model-management page for S1-mini: everything the tab's phone card does not show (#67). */
@Composable
internal fun LocalModelPage(s1State: ModelUiState, onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    ScreenContainer {
                    ModelCard(
                eyebrow = "LOCAL POLISH",
                title = S1Config.MODEL_NAME,
                description = "The polish model that runs on this phone. Your words are cleaned up here and never sent anywhere.",
                state = s1State,
                facts = listOf("Offline", "Stays on this phone"),
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
}

@Composable
private fun Tile(glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/** Where the text goes: a row of coloured dots ending in a sparkle, only ever inside the selected card. */
@Composable
private fun RouteGlyph(modifier: Modifier = Modifier) {
    val colours = listOf(Color(0xFFEF6C6C), Color(0xFFF2A65A), Color(0xFFF7D154), Color(0xFF6CCB8A), Color(0xFF5DA9F0), Color(0xFFA78BFA))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colours.forEach { colour -> Box(Modifier.size(8.dp).background(colour, CircleShape)) }
        SparkleGlyph(size = 14.dp)
    }
}

@Composable
private fun SparkleGlyph(size: androidx.compose.ui.unit.Dp = 22.dp) {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, 0f); lineTo(w * 0.62f, h * 0.38f); lineTo(w, h / 2); lineTo(w * 0.62f, h * 0.62f)
            lineTo(w / 2, h); lineTo(w * 0.38f, h * 0.62f); lineTo(0f, h / 2); lineTo(w * 0.38f, h * 0.38f); close()
        }
        drawPath(path, colour)
    }
}

@Composable
private fun PhoneGlyph() {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(colour, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.56f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f))
        drawCircle(colour, radius = w * 0.05f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.88f))
    }
}

@Composable
private fun CloudGlyph() {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        drawCircle(colour, radius = w * 0.22f, center = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.55f))
        drawCircle(colour, radius = w * 0.28f, center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.45f))
        drawRoundRect(colour, topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f), size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.32f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.16f))
    }
}

@Composable
private fun ShieldGlyph() {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, 0f); lineTo(w, h * 0.2f); lineTo(w * 0.85f, h * 0.7f); lineTo(w / 2, h); lineTo(w * 0.15f, h * 0.7f); lineTo(0f, h * 0.2f); close()
        }
        drawPath(path, colour, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f))
    }
}
