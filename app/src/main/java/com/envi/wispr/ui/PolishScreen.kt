package com.envi.wispr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.S1Config
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.disclosure

/** Which write the tab is waiting on, so its failure lands under the rung that started it. */
private enum class WriteKind { MODE, KEY, MODEL, REMOVE }

/**
 * The AI Polish tab as the founder's Ladder (#81): four numbered rungs on one page, each unlocking the
 * next. The tab renders persisted `settings` and the live `discovery`; its only local state is
 * navigation (an open Cloud setup, the tile being looked at, an open Replace), the key draft (plain
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
    onSave: (Provider, String, String?, Int?) -> Int,
    onClearProvider: () -> Int,
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
    // Counts completed KEY writes, so rung 3 can drop its draft and close Replace exactly once per save.
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
                RungOne.THIS_PHONE -> S1Card(s1State, onRefreshReadiness)
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

/** Rungs 2 to 4. Split out so every piece of rung-3 state is keyed on the displayed tile. */
@Composable
private fun CloudRungs(
    settings: ProviderSettingsUiState,
    discovery: ProviderDiscoveryUiState,
    displayed: Provider?,
    saving: Boolean,
    savingKind: WriteKind?,
    keyWriteCompleted: Int,
    writeError: String?,
    errorKind: WriteKind?,
    onPickTile: (Provider) -> Unit,
    onClearKeyError: () -> Unit,
    onStart: (WriteKind, () -> Int) -> Unit,
    onSave: (Provider, String, String?, Int?) -> Int,
    onClearProvider: () -> Int,
    onCheckKey: (Provider, String?) -> Int,
    onKeyDraftChanged: (Provider) -> Unit,
    onLoadCachedModels: (Provider) -> Unit,
) {
    RungHeader("2 · PROVIDER")
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CloudProviders.forEach { option ->
            ProviderTileButton(option, selected = option == displayed, enabled = !saving, onClick = { onPickTile(option) }, modifier = Modifier.weight(1f))
        }
    }
    if (settings.configured && settings.provider == Provider.SELF_HOSTED_POLISH) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Self-hosted · ${hostOf(settings.endpoint)}", style = MaterialTheme.typography.titleSmall)
                Text("Text is sent to your server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onStart(WriteKind.REMOVE, onClearProvider) }, enabled = !saving) { Text("Remove") }
                if (writeError != null && errorKind == WriteKind.REMOVE) ErrorLine(writeError)
            }
        }
    }
    if (displayed == null) return
    val name = displayed.capabilities().displayName

    // Everything below is keyed on the displayed tile, so switching tiles starts a fresh rung 3: the
    // previous tile's draft, check and Replace cannot leak into another provider's field.
    // The draft and everything derived from it (the Check it ran, the save that Check produced) share ONE
    // survival policy: none of it outlives a recreation, so nothing can describe a key that is gone.
    var apiKey by remember(displayed) { mutableStateOf("") }
    var checkSequence by remember(displayed) { mutableStateOf<Int?>(null) }
    var savedForSequence by remember(displayed) { mutableStateOf<Int?>(null) }
    var replacing by rememberSaveable(displayed) { mutableStateOf(false) }
    LaunchedEffect(displayed) { onLoadCachedModels(displayed) }

    val forTile = discovery.takeIf { it.provider == displayed }
    val draftListing = forTile?.takeIf { checkSequence != null && it.sequence == checkSequence }
    val checking = draftListing?.phase == ProviderDiscoveryUiState.Phase.CHECKING || (saving && savingKind == WriteKind.KEY)
    val failed = draftListing?.phase == ProviderDiscoveryUiState.Phase.FAILED || (writeError != null && errorKind == WriteKind.KEY)

    // An accepted Check saves at once with the suggested model, so "Key connected" is always a stored fact.
    LaunchedEffect(forTile?.phase, forTile?.sequence, checkSequence, apiKey.isBlank(), savedForSequence, saving) {
        if (forTile != null && PolishLadder.saveAtAccept(forTile, displayed, checkSequence, apiKey.isBlank(), savedForSequence, writePending = saving)) {
            val model = PolishLadder.defaultModel(forTile.models) ?: return@LaunchedEffect
            val sequence = checkSequence
            savedForSequence = sequence
            onStart(WriteKind.KEY) { onSave(displayed, model, apiKey, sequence) }
        }
    }
    // A completed KEY write means the draft has done its work: drop it, and close Replace.
    LaunchedEffect(keyWriteCompleted) {
        if (keyWriteCompleted > 0) { apiKey = ""; replacing = false }
    }

    when (PolishLadder.keyRung(displayed, settings, replacing)) {
        KeyRung.FIELD -> {
            RungHeader("3 · YOUR ${name.uppercase()} KEY", error = failed)
            val pill = PolishLadder.keyPill(draftBlank = apiKey.isBlank(), checking = checking, failed = failed)
            val emptyListing = draftListing?.phase == ProviderDiscoveryUiState.Phase.LISTED && draftListing.models.isEmpty()
            val hint = when {
                checking -> "Asking $name which models this key can reach."
                failed -> (if (errorKind == WriteKind.KEY) writeError else null) ?: draftListing?.line ?: "$name did not accept this key."
                emptyListing -> draftListing?.line ?: "No models this key can use for polish."
                replacing -> "Paste the new key. Your saved key stays until the new one is accepted."
                else -> "Encrypted in the Android Keystore. Never written to logs."
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; checkSequence = null; onClearKeyError(); onKeyDraftChanged(displayed) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(keyPlaceholder(displayed)) },
                isError = failed,
                supportingText = { Text(hint, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    TextButton(
                        onClick = { checkSequence = onCheckKey(displayed, apiKey) },
                        enabled = pill.enabled && !saving,
                    ) { Text(pill.label) }
                },
                singleLine = true,
                enabled = !saving,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (replacing) {
                TextButton(onClick = { replacing = false; apiKey = ""; checkSequence = null; onClearKeyError() }, enabled = !saving) { Text("Keep current key") }
            }
            // The key listed nothing this app can use: nothing is stored until a model id is typed, and the
            // key and the id are then saved together, so a key never exists in storage without a model.
            if (draftListing != null && PolishLadder.needsTypedModel(draftListing, displayed, checkSequence, apiKey.isBlank())) {
                var typedModel by remember(displayed, checkSequence) { mutableStateOf("") }
                OutlinedTextField(
                    value = typedModel,
                    onValueChange = { typedModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model id") },
                    supportingText = { Text("$name listed no chat model for this key. Type the model id you want to use.") },
                    singleLine = true,
                    enabled = !saving,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val sequence = checkSequence
                                savedForSequence = sequence
                                onStart(WriteKind.KEY) { onSave(displayed, typedModel.trim(), apiKey, sequence) }
                            },
                            enabled = !saving && PolishLadder.typedModelValid(typedModel),
                        ) { Text("Use this model") }
                    },
                )
            }
        }
        KeyRung.CONNECTED -> {
            val listed = forTile?.takeIf { it.usedStoredKey && it.phase == ProviderDiscoveryUiState.Phase.LISTED && it.models.isNotEmpty() }?.models?.size
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CheckGlyph()
                    Column(Modifier.weight(1f)) {
                        Text("3 · Key connected" + (if (listed != null) " · $listed models" else ""), style = MaterialTheme.typography.titleSmall)
                        Text("Encrypted in the Android Keystore", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Replace", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(enabled = !saving) { replacing = true })
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(enabled = !saving) { onStart(WriteKind.REMOVE, onClearProvider) })
                }
            }
            if (writeError != null && errorKind == WriteKind.REMOVE) ErrorLine(writeError)

            ModelRung(
                provider = displayed,
                settings = settings,
                discovery = forTile,
                saving = saving,
                writeError = writeError?.takeIf { errorKind == WriteKind.MODEL },
                onPick = { id -> onStart(WriteKind.MODEL) { onSave(displayed, id, null, null) } },
                onRefresh = { onCheckKey(displayed, null) },
            )
        }
    }
}

/** Rung 4: the live list (#84) for the connected provider; a row tap saves that model. */
@Composable
private fun ModelRung(
    provider: Provider,
    settings: ProviderSettingsUiState,
    discovery: ProviderDiscoveryUiState?,
    saving: Boolean,
    writeError: String?,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember(provider) { mutableStateOf("") }
    var sort by rememberSaveable(provider) { mutableStateOf(ModelSort.SUGGESTED) }
    val savedModel = savedModelFor(provider, settings)
    // Only a listing that describes the STORED key shows under a connected row; a draft's list never does.
    val models = discovery?.takeIf { it.usedStoredKey }?.models ?: emptyList()
    val refreshing = discovery?.phase == ProviderDiscoveryUiState.Phase.CHECKING
    val rows = ModelListPresentation.present(provider, models, query, sort, savedModel)
    val allRows = ModelListPresentation.present(provider, models, "", sort, savedModel)
    val countLine = if (models.isEmpty()) null else ModelListPresentation.countLine(allRows, rows.count { !it.typed }, query) +
        (if (discovery?.fromCache == true && discovery.fetchedAt != null) " · from ${relativeAge(discovery.fetchedAt)}" else "")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        RungHeader("4 · MODEL")
        Text(
            countLine ?: if (refreshing) "Loading the models this key can use" else "Tap Refresh to load the models this key can use.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search models") },
        singleLine = true,
        trailingIcon = if (query.isNotEmpty()) {
            { Text("Clear", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).clickable { query = "" }) }
        } else null,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(sort.groupLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 4.dp)) {
            listOf("C", "S", "A").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    // About four and a half rows, then the region scrolls on its own; the bounded height is what makes a
    // vertical scroll legal inside the tab's own list.
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            val selected = row.id == settings.model && row.id == savedModel
            val locked = row.access == ModelAccess.UNAVAILABLE
            Card(
                onClick = { onPick(row.id) },
                enabled = !saving && row.selectable && !selected,
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.id,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (row.tag != null && sort == ModelSort.SUGGESTED) {
                                Text(row.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (row.note != null) {
                            Text(row.note, style = MaterialTheme.typography.bodySmall, color = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (row.cost != null && row.speed != null && row.accuracy != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ScoreDots("Cost", row.cost); ScoreDots("Speed", row.speed); ScoreDots("Accuracy", row.accuracy) }
                    }
                }
            }
        }
    }
    if (writeError != null) ErrorLine(writeError)
    // A stored-key Refresh that failed keeps the previous listing (the view model keeps it) and says why here.
    if (discovery?.usedStoredKey == true && discovery.phase == ProviderDiscoveryUiState.Phase.FAILED && discovery.line != null) ErrorLine(discovery.line)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "C cost · S speed · A accuracy. ${provider.disclosure().summary}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh, enabled = !saving && !refreshing) { Text(if (refreshing) "Refreshing" else "Refresh") }
    }
}

/** The S1-mini card inside This phone, exactly what the old local-model page did (#67), now inline. */
@Composable
private fun S1Card(s1State: ModelUiState, onRefreshReadiness: () -> Unit) {
    val context = LocalContext.current
    ModelCard(
        eyebrow = "ON THIS PHONE",
        title = S1Config.MODEL_NAME,
        description = PolishLadder.s1Line(s1State),
        state = s1State,
        facts = PolishLadder.s1Facts(),
        scores = PolishLadder.S1_SCORES,
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

private fun keyPlaceholder(provider: Provider): String = when (provider) {
    Provider.OPENAI -> "sk-proj-…"
    Provider.GEMINI -> "AIza…"
    Provider.CLAUDE -> "sk-ant-…"
    Provider.SELF_HOSTED_POLISH -> ""
}

@Composable
private fun RungHeader(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ErrorLine(text: String) {
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
private fun ProviderTileButton(provider: Provider, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.height(76.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderTile(provider, size = 28.dp)
            Text(provider.capabilities().displayName, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
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

@Composable
private fun CheckGlyph() {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.15f, h * 0.55f); lineTo(w * 0.42f, h * 0.8f); lineTo(w * 0.88f, h * 0.22f) }
        drawPath(path, colour, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.14f))
    }
}
