package com.envi.wispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.polish.S1Config
import com.envi.wispr.ui.theme.brandButtonColors
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfiguration
import com.envi.wispr.providers.ProviderConfigurationValidator
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.ValidationReason
import com.envi.wispr.providers.ValidationResult
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.disclosure

internal enum class KeyRung { TYPING, REJECTED, CONNECTED }

/** The providers this screen offers a tile for. Self-hosted keeps working if already configured
 *  (§2.2 of the AI Polish Ladder plan); it is only hidden from fresh selection here. */
internal val CloudProviders: List<Provider> = Provider.entries - Provider.SELF_HOSTED_POLISH

/**
 * Which rung the Cloud path starts on when the screen opens or a provider tile is tapped.
 * Pure so it is testable without a Compose harness — see `PolishScreenProviderTilesTest`.
 *
 * `configured` alone is not enough: `ProviderConfigurationRepository.load()` can return a
 * configuration with no stored key (a blank/missing Keystore entry is not treated as invalid on its
 * own), and removing a provider's key resets `settings.provider` back to its default (`OPENAI`)
 * rather than to null — so a `remember` keyed only on `provider` would not even recompute after a
 * removal that happens to leave the provider unchanged. `credentialStored` closes both gaps (caught
 * in code review, 2026-09-01).
 */
internal fun initialKeyRung(configured: Boolean, credentialStored: Boolean, provider: Provider): KeyRung =
    if (configured && credentialStored && provider != Provider.SELF_HOSTED_POLISH) {
        KeyRung.CONNECTED
    } else {
        KeyRung.TYPING
    }

/**
 * `settings.model` names a model that belongs to `settings.provider`, not necessarily to whichever
 * provider tile is currently selected on screen. After switching to a DIFFERENT provider than the one
 * saved, carrying the old model name over would offer it as a pickable row under the wrong provider's
 * catalog and could save an invalid provider/model pair (caught in code review, 2026-09-01).
 */
internal fun savedModelFor(provider: Provider, settings: ProviderSettingsUiState): String =
    if (provider == settings.provider) settings.model else ""

/**
 * Whether tapping the Cloud mode button, while [displayedProvider]'s tile is the one currently showing,
 * should commit immediately rather than leave Cloud mode uncommitted for the user to finish setting up.
 * Both halves are required: the displayed provider must be the SAVED one (not a different tile the user
 * is merely browsing) AND that saved provider must already be fully connected. Checking connectedness
 * alone reactivated whichever provider happened to be saved while the screen kept showing a different
 * tile's setup, silently routing dictation to a provider the user was not even looking at (real bug
 * caught in code review, round 22).
 */
internal fun cloudReactivatesImmediately(displayedProvider: Provider, settings: ProviderSettingsUiState): Boolean =
    displayedProvider == settings.provider &&
        initialKeyRung(settings.configured, settings.credentialStored, settings.provider) == KeyRung.CONNECTED

/**
 * What happens after transcription, and the model that does it.
 *
 * `settings.loading` gates everything below: the ViewModel's provider settings load asynchronously in
 * `init`, so this composable can genuinely be entered before that finishes. Refusing to build any local
 * draft state until loading is real closes a whole class of bug by construction, rather than by
 * detecting and undoing it after the fact — six review rounds found a new timing gap in the reconcile-
 * after-the-fact version of this screen before it was replaced with this gate (code review, 2026-09-01;
 * see [PolishScreenBody] for what that version tried to do and why the flags it grew are gone).
 */
@Composable
internal fun PolishScreen(
    settings: ProviderSettingsUiState,
    s1State: ModelUiState,
    onRefreshReadiness: () -> Unit,
    onSetMode: (PolishMode) -> Unit,
    onSaveProvider: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProvider: () -> Unit,
) {
    if (settings.loading) {
        ScreenContainer(
            subtitle = "Clean up and rewrite your dictation with AI.",
            modifier = Modifier.imePadding(),
        ) {
            Text("Loading your AI Polish settings…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    PolishScreenBody(settings, s1State, onRefreshReadiness, onSetMode, onSaveProvider, onClearProvider)
}

/**
 * The real screen, entered only once `settings` already holds the real, saved configuration — never a
 * placeholder. `mode` and `provider` below are which section the user is currently VIEWING, not a
 * mirror of what is active; "what is active" is always read directly from `settings` (the app-bar badge
 * in `PolishStatusChip.kt` does the same, and always did — it was this screen's body that used to keep
 * a second, reconciled copy). Seeded once here and controlled only by direct user taps from then on,
 * with no re-sync back to `settings` ever needed: Off, This phone, and an already-configured Cloud
 * commit immediately, so `settings` catching up afterward is confirming what the user already sees, not
 * correcting it; a Cloud pick that still needs a model or a key simply has not committed yet, and
 * showing that in-progress state is the whole point of it being local.
 *
 * The API-key draft is the reason this needs its own composable rather than a saved-state destination:
 * it lives in a plain `remember`, so leaving AI Polish disposes it. It must never move to
 * `rememberSaveable`, never be hoisted into `EnviousWisprUiState`, and never be logged.
 */
@Composable
private fun PolishScreenBody(
    settings: ProviderSettingsUiState,
    s1State: ModelUiState,
    onRefreshReadiness: () -> Unit,
    onSetMode: (PolishMode) -> Unit,
    onSaveProvider: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProvider: () -> Unit,
) {
    val context = LocalContext.current
    // `mode` and `provider` are `rememberSaveable`, not `remember`: a configuration change (rotation,
    // dark-mode toggle, multi-window resize) tears down and recreates this composable, and a plain
    // `remember` would then re-seed them from `settings` at that instant. If the user had just tapped a
    // mode or a provider tile — and the async write it queued had not landed yet — that reseed would
    // read the OLD persisted value, and nothing re-syncs it once the write does land, so the screen
    // would disagree with the app-bar badge (which always reads `settings` directly) until the user
    // left and re-entered this screen (real bug caught in code review, 2026-09-01). Surviving the
    // recreation with the value the user actually chose means the eventual write always agrees with
    // what is already on screen. `rung` needs the opposite treatment — see its own comment below.
    var mode by rememberSaveable { mutableStateOf(settings.mode) }
    var provider by rememberSaveable { mutableStateOf(settings.provider) }
    // A credential draft is deliberately not saveable and never enters the ViewModel state.
    var apiKey by remember { mutableStateOf("") }
    // `rung` stays plain `remember`, NOT `rememberSaveable`, even though `mode`/`provider` above are:
    // a `CONNECTED` verdict reached by typing a new key and tapping Check is only true because that
    // key is sitting in `apiKey`, which never survives a configuration change by design. Saving `rung`
    // alone would restore "Key connected" after rotation with no key behind it — apiKey blank, no
    // stored credential yet since the save was still in flight — so every model tap would fail with a
    // contradictory "enter an API key" error under a screen that just said it was connected (a real bug
    // caught in code review, round 20). Reseeding `rung` from `settings` on recreation, same as before
    // this file's `mode`/`provider` fix, means it can only ever say `CONNECTED` when a real credential
    // is already on file, which is always true here — PROVIDED it checks the credential against the
    // right provider: `provider` (restored above) and `settings.provider` (persisted) can now disagree
    // after a rotation caught mid-browse, and joining `settings.credentialStored` against `provider`
    // rather than blindly against `settings.provider` matches every other rung/CONNECTED check on this
    // screen (the tile taps below, the self-hosted "Switch to..." link) — using the unjoined form here
    // showed a just-restored Gemini tile as CONNECTED off a saved OpenAI key (a real bug caught in code
    // review, round 21).
    var rung by remember {
        mutableStateOf(
            initialKeyRung(settings.configured && provider == settings.provider, settings.credentialStored, provider),
        )
    }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ModelSort.SUGGESTED) }
    var localError by remember { mutableStateOf<String?>(null) }

    // `ProviderConfigurationRepository.clearSelection()` always resets the persisted mode to
    // `OFFLINE_S1` and drops the provider back to its default. This mirrors it explicitly, at the
    // exact moment the user asks to remove, matching every other real transition on this screen.
    fun resetLocalStateAfterClear() {
        mode = PolishMode.OFFLINE_S1
        provider = Provider.OPENAI
        apiKey = ""
        rung = KeyRung.TYPING
        localError = null
    }

    fun pickModel(pickedName: String) {
        val normalizedModel = pickedName.trim()
        if (normalizedModel.isEmpty() || normalizedModel.length > 256 ||
            normalizedModel.any(Char::isISOControl)) {
            localError = "Enter a valid provider model ID."
            return
        }
        val storedCredentialApplies = settings.credentialStored && provider == settings.provider
        val effectiveKey = apiKey.takeIf(String::isNotBlank)
            ?: if (storedCredentialApplies) "stored-credential" else null
        when (val validation = ProviderConfigurationValidator.validate(
            ProviderConfiguration(provider, null),
            effectiveKey,
        )) {
            ValidationResult.Valid -> {
                onSaveProvider(
                    provider,
                    normalizedModel,
                    null,
                    apiKey.takeIf(String::isNotBlank),
                    SelfHostedProtocol.OPENAI_COMPATIBLE,
                )
                // Deliberately NOT cleared here: `onSaveProvider` only queues an async write, with no
                // completion signal back to this screen. Clearing immediately meant a second, fast
                // model tap for the same provider — before `settings.credentialStored` had caught up
                // — found neither the (already-cleared) draft nor the not-yet-persisted stored key,
                // reported "enter an API key", and left the FIRST model selected even though the
                // second tap was the user's actual last choice (real bug caught in code review,
                // 2026-09-01). The draft only needs to survive until the rung leaves CONNECTED (a
                // provider switch, Replace, Remove, or leaving the screen), all of which already
                // clear it explicitly.
                localError = null
            }
            is ValidationResult.Invalid -> {
                localError = validation.reason.userMessage()
            }
        }
    }

    // True exactly when the self-hosted fallback card (below) is the one rendering, so the
    // screen-wide "Remove saved provider and key" button can stay suppressed while that card's own
    // Remove action is the one to use.
    val showingSelfHostedFallback = mode == PolishMode.PROVIDER &&
        settings.provider == Provider.SELF_HOSTED_POLISH &&
        provider == Provider.SELF_HOSTED_POLISH

    ScreenContainer(
        subtitle = "Clean up and rewrite your dictation with AI.",
        modifier = Modifier.imePadding(),
    ) {
        Text(
            "Choose what happens after private, on-device transcription.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PolishMode.OFF to "Off",
                PolishMode.OFFLINE_S1 to "This phone",
                PolishMode.PROVIDER to "Cloud",
            ).forEach { (option, label) ->
                val selected = mode == option
                val onClick = {
                    mode = option
                    localError = null
                    // Off and This phone are complete facts on their own and commit immediately.
                    // Cloud normally commits nothing here, UNLESS the provider tile currently showing
                    // is BOTH the one already saved AND already fully configured (a real key on file,
                    // per `initialKeyRung`) — then there is nothing left to finish, and leaving it
                    // uncommitted would show a fully "connected" Cloud view while dictation kept using
                    // whatever mode was active before, the same gap the self-hosted "Turn back on"
                    // path exists to close, just for the three visible providers instead. Safe for the
                    // same reason "Turn back on" is: a valid configuration already exists, so
                    // `AppViewModel.setPolishMode`'s refusal to commit `PROVIDER` with nothing saved
                    // never fires here. `settings` is already real at this point (the loading gate in
                    // `PolishScreen` guarantees it), so this check can never fire against a
                    // placeholder the way it once could.
                    //
                    // See `cloudReactivatesImmediately`'s own doc comment for why checking
                    // connectedness alone is not enough (round 22).
                    if (option != PolishMode.PROVIDER) {
                        onSetMode(option)
                    } else if (cloudReactivatesImmediately(provider, settings)) {
                        onSetMode(option)
                    }
                }
                if (selected) {
                    Button(colors = brandButtonColors(), onClick = onClick, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                }
            }
        }

        when (mode) {
            PolishMode.OFF -> Text(
                "No language model runs. Deterministic cleanup still removes obvious filler and spacing issues.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PolishMode.OFFLINE_S1 -> Unit
            PolishMode.PROVIDER -> if (showingSelfHostedFallback) {
                // Whether it is currently running is read from the PERSISTED mode, not the local
                // `mode` above — that local value only says which section the user is looking at
                // right now, not what is actually active. This whole branch replaces the cloud
                // picker entirely rather than sitting above it — showing the provider tiles, key
                // entry and a SECOND "Remove saved provider and key" button underneath a message
                // that says "no longer configurable on this screen" was a real, confusing
                // contradiction caught in code review, 2026-09-01.
                val selfHostedActive = settings.mode == PolishMode.PROVIDER
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (selfHostedActive) {
                                "Self-hosted is running, but no longer configurable on this screen."
                            } else {
                                "Self-hosted is saved but not running. It is no longer configurable on this screen, but turning it back on does not require reconfiguring it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!selfHostedActive) {
                                // Safe even though `setPolishMode` refuses `PROVIDER` with no
                                // saved configuration: a real self-hosted configuration still
                                // exists in the repository here, so `providerRepository.load()`
                                // succeeds and this never reaches that refusal.
                                Button(
                                    colors = brandButtonColors(),
                                    onClick = { onSetMode(PolishMode.PROVIDER) },
                                ) {
                                    Text("Turn back on")
                                }
                            }
                            OutlinedButton(onClick = {
                                resetLocalStateAfterClear()
                                onClearProvider()
                            }) {
                                Text("Remove")
                            }
                        }
                        Text(
                            "Switch to OpenAI, Gemini or Claude",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                provider = Provider.OPENAI
                                apiKey = ""
                                query = ""
                                rung = initialKeyRung(
                                    settings.configured && Provider.OPENAI == settings.provider,
                                    settings.credentialStored,
                                    Provider.OPENAI,
                                )
                            },
                        )
                    }
                }
            } else {
                Text("Provider", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CloudProviders.forEach { option ->
                        FilterChip(
                            selected = provider == option,
                            onClick = {
                                if (provider != option) {
                                    provider = option
                                    apiKey = ""
                                    // The catalog and its search results are provider-scoped; a
                                    // search term from the old provider could leave the new one's
                                    // list looking empty for no visible reason.
                                    query = ""
                                    rung = initialKeyRung(
                                        settings.configured && option == settings.provider,
                                        settings.credentialStored,
                                        option,
                                    )
                                }
                                localError = null
                            },
                            label = { Text(option.capabilities().displayName) },
                        )
                    }
                }

                if (provider != Provider.SELF_HOSTED_POLISH) {
                    when (rung) {
                        KeyRung.TYPING, KeyRung.REJECTED -> {
                            val armed = apiKey.trim().isNotEmpty()
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    if (rung == KeyRung.REJECTED) rung = KeyRung.TYPING
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API key") },
                                isError = rung == KeyRung.REJECTED,
                                supportingText = {
                                    Text(
                                        if (rung == KeyRung.REJECTED) {
                                            "That key didn't look right. Nothing was saved."
                                        } else {
                                            "Encrypted in the Android Keystore. Never written to logs."
                                        },
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                trailingIcon = {
                                    Text(
                                        if (rung == KeyRung.REJECTED) "Retry" else "Check",
                                        color = if (armed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .then(
                                                if (armed) {
                                                    Modifier.clickable {
                                                        when (
                                                            val validation = ProviderConfigurationValidator.validate(
                                                                ProviderConfiguration(provider, null),
                                                                apiKey,
                                                            )
                                                        ) {
                                                            ValidationResult.Valid -> {
                                                                rung = KeyRung.CONNECTED
                                                                localError = null
                                                            }
                                                            is ValidationResult.Invalid -> {
                                                                rung = KeyRung.REJECTED
                                                                localError = validation.reason.userMessage()
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                },
                            )
                        }
                        KeyRung.CONNECTED -> {
                            val savedModelForThisProvider = savedModelFor(provider, settings)
                            // Includes the preserved legacy-model row (if any) by construction, since
                            // it is built from the exact same function as `filtered` below with an
                            // empty query.
                            val catalogTotal = PolishModelCatalog.filterAndSort(provider, "", sort, savedModelForThisProvider).size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Key connected · $catalogTotal models",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Replace",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        rung = KeyRung.TYPING
                                        apiKey = ""
                                        localError = null
                                    },
                                )
                            }

                            val filtered = PolishModelCatalog.filterAndSort(provider, query, sort, savedModelForThisProvider)
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search models") },
                                singleLine = true,
                                trailingIcon = if (query.isNotEmpty()) {
                                    { Text("Clear", modifier = Modifier.clickable { query = "" }) }
                                } else null,
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ModelSort.entries.forEach { s ->
                                    FilterChip(
                                        selected = sort == s,
                                        onClick = { sort = s },
                                        label = { Text(s.label) },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (query.isNotBlank()) {
                                        "${filtered.size} of $catalogTotal models"
                                    } else {
                                        "$catalogTotal models"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("C", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("S", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filtered.forEach { row ->
                                    val selected = row.name == savedModelForThisProvider
                                    Card(
                                        onClick = { pickModel(row.name) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selected) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(12.dp)
                                                .fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                                                    if (row.tag != null && sort == ModelSort.SUGGESTED) {
                                                        Text(
                                                            row.tag,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }
                                                Text(
                                                    row.note,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                ScoreDots(row.cost)
                                                ScoreDots(row.speed)
                                                ScoreDots(row.accuracy)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        provider.disclosure().summary,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        localError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (settings.configured && !showingSelfHostedFallback) {
            OutlinedButton(onClick = {
                resetLocalStateAfterClear()
                onClearProvider()
            }) {
                Text("Remove saved provider and key")
            }
        }

        ModelCard(
            eyebrow = "LOCAL POLISH",
            title = S1Config.MODEL_NAME,
            description = "The polish model that runs on this phone. Your words are cleaned up here and never sent anywhere.",
            state = s1State,
            facts = listOf("Offline", "Stays on this phone"),
            onAction = {
                // Exhaustive with no `else`, for the reason given at the same `when` in
                // `TranscriptionScreen`.
                when (s1State.action) {
                    ModelUiAction.REMOVE -> ModelDeliveryWorker.enqueueRemove(context, ModelManifest.s1)
                    ModelUiAction.REPAIR -> ModelDeliveryWorker.enqueueRepair(context, ModelManifest.s1)
                    ModelUiAction.UPDATE -> ModelDeliveryWorker.enqueueUpdate(context, ModelManifest.s1)
                    ModelUiAction.DOWNLOAD, ModelUiAction.RETRY ->
                        ModelDeliveryWorker.enqueue(context, ModelManifest.s1)
                    ModelUiAction.PAUSE, ModelUiAction.RESUME, ModelUiAction.CANCEL,
                    ModelUiAction.NONE -> Unit
                }
                onRefreshReadiness()
            },
            onPause = { ModelDeliveryWorker.pause(context, ModelManifest.s1) },
            onResume = { ModelDeliveryWorker.resume(context, ModelManifest.s1) },
        )
    }
}

/** Three dots, filled up to [value] on a 1-3 scale. Used for a [CatalogModel]'s cost, speed and
 *  accuracy columns, read top to bottom against the C/S/A legend above the list. */
@Composable
private fun ScoreDots(value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (level in 3 downTo 1) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (value >= level) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private fun ValidationReason.userMessage(): String = when (this) {
    ValidationReason.API_KEY_REQUIRED -> "Enter an API key for this provider."
    ValidationReason.API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS -> "The API key contains invalid characters."
    ValidationReason.ENDPOINT_REQUIRED -> "Enter the self-hosted server URL."
    ValidationReason.ENDPOINT_MUST_BE_HTTPS -> "Use HTTPS for this server URL."
    ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS -> "Use an HTTPS URL, or loopback for local development."
    ValidationReason.ENDPOINT_MUST_HAVE_HOST -> "The server URL needs a valid host."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS -> "Remove credentials from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT -> "Remove the # fragment from the server URL."
    ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE -> "Remove spaces from the server URL."
}
