package com.envi.wispr.ui

import com.envi.wispr.providers.ui.ProviderSettingsUiState
import com.envi.wispr.providers.ui.ProviderDiscoveryUiState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider

/** Which write the tab is waiting on, so its failure lands under the rung that started it. */
internal enum class WriteKind { MODE, KEY, MODEL, REMOVE, S1_CONTROL }

/**
 * The AI Polish tab as the founder's Ladder (#81): four numbered rungs on one page, each unlocking the
 * next. The tab renders persisted `settings` and the live `discovery`; its only local state is
 * navigation (an open Cloud setup, the tile being looked at), the key draft (plain
 * `remember`, never saveable, never hoisted, never logged) and the write it is waiting on. Every
 * "connected", "selected" and "running" on screen is read back from storage after the write that made it
 * so; an accepted key is saved at once with the model `PolishLadder.defaultModel` picks, so the connected
 * row never describes a draft. The loading gate from #66 stays: nothing below is built until the saved
 * configuration is real.
 */
@Composable
internal fun PolishScreen(
    settings: ProviderSettingsUiState,
    s1State: ModelUiState,
    discovery: ProviderDiscoveryUiState,
    onSetMode: (PolishMode) -> Int,
    onSetS1Control: (S1ControlSettings) -> Int,
    onSave: (Provider, String, String?, Int?) -> Int,
    onClearProvider: (Provider) -> Int,
    onCheckKey: (Provider, String?) -> Int,
    onKeyDraftChanged: (Provider) -> Unit,
    onLoadCachedModels: (Provider) -> Unit,
    onRefreshReadiness: () -> Unit,
) {
    // The ONE write this tab started and is waiting on, with the rung that started it. Saveable, so a
    // rotation mid-write keeps waiting on the right sequence; declared ABOVE the loading gate so the gate
    // can clear a target restored after process death, which names a write the dead process never
    // finished and a sequence the new view model can never reach (the setup page's rule from #67).
    var target by rememberSaveable { mutableStateOf<Int?>(null) }
    var targetKindName by rememberSaveable { mutableStateOf<String?>(null) }
    if (settings.loading) {
        LaunchedEffect(target) { if (target != null) { target = null; targetKindName = null } }
        ScreenContainer(subtitle = "Clean up and rewrite your dictation with AI.") {
            Text("Checking polish settings", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val targetKind = targetKindName?.let { name -> WriteKind.entries.firstOrNull { it.name == name } }
    // Navigation state, saveable: none of it can assert a fact the key draft alone supported.
    var cloudSetup by rememberSaveable { mutableStateOf(false) }
    var browsedName by rememberSaveable { mutableStateOf<String?>(null) }
    val browsed = browsedName?.let { name -> CloudProviders.firstOrNull { it.name == name } }
    val displayed = PolishLadder.displayedProvider(browsed, settings)
    var writeError by remember { mutableStateOf<String?>(null) }
    var errorKind by remember { mutableStateOf<WriteKind?>(null) }
    // Counts completed KEY writes, so rung 3 can drop its draft exactly once per save.
    var keyWriteCompleted by remember { mutableStateOf(0) }

    LaunchedEffect(settings.writeSequence, settings.error, target) {
        when (PolishWritePolicy.outcome(target, settings.writeSequence, settings.error)) {
            PolishWritePolicy.Outcome.WAITING -> Unit
            PolishWritePolicy.Outcome.DONE -> {
                if (targetKind == WriteKind.KEY) keyWriteCompleted++
                target = null; targetKindName = null; writeError = null; errorKind = null
            }
            PolishWritePolicy.Outcome.FAILED -> { writeError = settings.error; errorKind = targetKind; target = null; targetKindName = null }
        }
    }
    val saving = target != null
    // One write at a time: every mutating control below is disabled while `saving`, so a later write can
    // never replace the completion an earlier target is waiting on.
    fun start(kind: WriteKind, write: () -> Int) {
        if (target != null) return
        // A remove keeps the user where they were standing; the rule and the reason are on
        // PolishLadder.browsedAfterRemove. Pinned at START rather than on completion, because by the time
        // the write lands the settings no longer name the provider that was removed. Safe on a FAILED
        // write too: nothing was cleared, so the mode is still PROVIDER and both values agree with it.
        if (kind == WriteKind.REMOVE) {
            val nav = PolishLadder.navigationAfterRemove(displayed)
            cloudSetup = nav.cloudSetup
            browsedName = nav.browsedName
        }
        writeError = null; errorKind = null
        targetKindName = kind.name
        target = write()
    }

    val rungOne = PolishLadder.rungOne(settings.mode, cloudSetup)

    ScreenContainer(subtitle = "Clean up and rewrite your dictation with AI.") {
        RungHeader("1 · WHERE POLISH RUNS")
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            RungOneButton(
                label = "Off", selected = rungOne == RungOne.OFF, enabled = !saving,
                onClick = { cloudSetup = false; start(WriteKind.MODE) { onSetMode(PolishMode.OFF) } },
                modifier = Modifier.weight(1f),
            ) { OffGlyph(it) }
            RungOneButton(
                label = "This phone", selected = rungOne == RungOne.THIS_PHONE, enabled = !saving,
                onClick = { cloudSetup = false; start(WriteKind.MODE) { onSetMode(PolishMode.OFFLINE_S1) } },
                modifier = Modifier.weight(1f),
            ) { PhoneGlyph(it) }
            RungOneButton(
                label = "Cloud", selected = rungOne == RungOne.CLOUD, enabled = !saving,
                onClick = {
                    when (PolishLadder.cloudTap(settings)) {
                        CloudTap.ACTIVATE -> start(WriteKind.MODE) { onSetMode(PolishMode.PROVIDER) }
                        CloudTap.SETUP -> cloudSetup = true
                    }
                },
                modifier = Modifier.weight(1f),
            ) { CloudGlyph(it) }
        }
        if (writeError != null && errorKind == WriteKind.MODE) ErrorLine(writeError!!)

        Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (rungOne) {
                RungOne.OFF -> QuietCard("No language model runs. Deterministic cleanup still removes obvious filler and spacing issues.")
                RungOne.THIS_PHONE -> {
                    S1Card(s1State, onRefreshReadiness)
                    S1ControlCard(
                        control = settings.s1Control,
                        enabled = !saving,
                        error = writeError?.takeIf { errorKind == WriteKind.S1_CONTROL },
                        onPick = { next -> start(WriteKind.S1_CONTROL) { onSetS1Control(next) } },
                    )
                    DevelopmentModelCard()
                }
                RungOne.CLOUD -> CloudRungs(
                    settings = settings,
                    discovery = discovery,
                    displayed = displayed,
                    saving = saving,
                    savingKind = targetKind,
                    keyWriteCompleted = keyWriteCompleted,
                    writeError = writeError,
                    errorKind = errorKind,
                    onPickTile = { browsedName = it.name },
                    // An edited or abandoned draft takes its failure with it, so a reopened field never
                    // shows the previous key's rejection with a disabled Retry.
                    onClearKeyError = { if (errorKind == WriteKind.KEY) { writeError = null; errorKind = null } },
                    onStart = ::start,
                    onSave = onSave,
                    onClearProvider = onClearProvider,
                    onCheckKey = onCheckKey,
                    onKeyDraftChanged = onKeyDraftChanged,
                    onLoadCachedModels = onLoadCachedModels,
                )
            }
        }
    }
}

@Composable
internal fun RungHeader(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun QuietCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One of the three equal rung-1 buttons: a fixed height so a two-line label cannot make one taller. */
@Composable
private fun RungOneButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable (Color) -> Unit,
) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.height(72.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            glyph(fg)
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OffGlyph(colour: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        drawCircle(colour, radius = w * 0.38f, center = androidx.compose.ui.geometry.Offset(w / 2, h / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))
        drawLine(colour, androidx.compose.ui.geometry.Offset(w / 2, h * 0.22f), androidx.compose.ui.geometry.Offset(w / 2, h * 0.5f), strokeWidth = w * 0.1f)
    }
}

@Composable
private fun PhoneGlyph(colour: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(colour, topLeft = androidx.compose.ui.geometry.Offset(w * 0.25f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.5f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f))
        drawLine(colour, androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.82f), androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.82f), strokeWidth = w * 0.1f)
    }
}

@Composable
private fun CloudGlyph(colour: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        drawCircle(colour, radius = w * 0.22f, center = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.58f))
        drawCircle(colour, radius = w * 0.28f, center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.48f))
        drawRoundRect(colour, topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.55f), size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.32f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.16f))
    }
}
