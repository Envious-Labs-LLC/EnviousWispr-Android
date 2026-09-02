package com.envi.wispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfiguration
import com.envi.wispr.providers.ProviderConfigurationValidator
import com.envi.wispr.providers.ValidationReason
import com.envi.wispr.providers.ValidationResult
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.disclosure
import com.envi.wispr.ui.theme.brandButtonColors

/**
 * The full-screen setup page for one cloud provider (#67): the API key, the model catalog, the privacy
 * disclosure and one pinned Save. This is the ONLY place a provider write is started from a form, and the
 * page WAITS for its own write ([ProviderSetupSavePolicy]) so a storage failure keeps the drafts on the
 * form rather than losing them on the tab. The key draft is plain `remember`: never saveable, never
 * hoisted, never logged.
 *
 * [onSave] and [onClear] return the request sequence of the write they queued; [onDone] pops the page.
 */
@Composable
internal fun ProviderSetupPage(
    provider: Provider,
    settings: ProviderSettingsUiState,
    onSave: (Provider, String, String?) -> Int,
    onClear: () -> Int,
    onDone: () -> Unit,
) {
    val editing = settings.configured && settings.provider == provider
    val keyStored = editing && settings.credentialStored
    var apiKey by remember { mutableStateOf("") }
    // The SAVED model stays pinned to the top of the list whatever the draft becomes, so an obsolete saved
    // model can be re-chosen without leaving the page.
    val savedModel = remember(provider, settings.provider, settings.model) { savedModelFor(provider, settings) }
    var modelDraft by rememberSaveable(provider) { mutableStateOf(savedModel) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ModelSort.SUGGESTED) }
    var localError by remember { mutableStateOf<String?>(null) }
    // The sequence of the write this page started and is waiting for; survives rotation with the page.
    var target by rememberSaveable { mutableStateOf<Int?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }

    if (settings.loading) {
        // Only process recreation reaches here with a page open. A restored target names a write the dead
        // process never finished, and the new view model's sequence starts at 0, so waiting would never
        // end: pop to the tab, which shows the truth.
        LaunchedEffect(target) { if (target != null) onDone() }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Checking polish settings", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LaunchedEffect(settings.writeSequence, settings.error, settings.writeOrigin, target) {
        when (ProviderSetupSavePolicy.outcome(target, settings.writeSequence, settings.error, settings.writeOrigin)) {
            ProviderSetupSavePolicy.Outcome.WAITING -> Unit
            ProviderSetupSavePolicy.Outcome.DONE -> {
                target = null
                onDone()
            }
            ProviderSetupSavePolicy.Outcome.FAILED -> {
                saveError = settings.error
                target = null
            }
        }
    }

    val saving = target != null
    val canSave = modelDraft.isNotBlank() && (apiKey.isNotBlank() || keyStored) && !saving
    val name = provider.capabilities().displayName

    fun save() {
        val normalizedModel = modelDraft.trim()
        if (normalizedModel.isEmpty() || normalizedModel.length > 256 || normalizedModel.any(Char::isISOControl)) {
            localError = "Enter a valid provider model ID."
            return
        }
        val effectiveKey = apiKey.takeIf(String::isNotBlank) ?: if (keyStored) "stored-credential" else null
        when (val validation = ProviderConfigurationValidator.validate(ProviderConfiguration(provider, null), effectiveKey)) {
            ValidationResult.Valid -> {
                localError = null
                saveError = null
                target = onSave(provider, normalizedModel, apiKey.takeIf(String::isNotBlank))
            }
            is ValidationResult.Invalid -> localError = validation.reason.userMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderTile(provider)
                    Column {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text("Your API key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Your saved provider stays in use until you save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; localError = null; saveError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                isError = localError != null,
                supportingText = {
                    Text(
                        localError ?: if (keyStored) "Leave blank to keep your saved key." else "Encrypted in the Android Keystore. Never written to logs.",
                    )
                },
                singleLine = true,
                enabled = !saving,
                visualTransformation = PasswordVisualTransformation(),
            )

            Text("Model", style = MaterialTheme.typography.titleSmall)
            val filtered = PolishModelCatalog.filterAndSort(provider, query, sort, savedModel)
            val catalogTotal = PolishModelCatalog.filterAndSort(provider, "", sort, savedModel).size
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelSort.entries.forEach { s ->
                    FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (query.isNotBlank()) "${filtered.size} of $catalogTotal models" else "$catalogTotal models",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("C", "S", "A").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filtered.forEach { row ->
                    val selected = row.name == modelDraft
                    Card(
                        onClick = { modelDraft = row.name; localError = null },
                        enabled = !saving,
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                                    if (row.tag != null && sort == ModelSort.SUGGESTED) {
                                        Text(row.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(row.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    provider.disclosure().summary,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (editing) {
                OutlinedButton(onClick = { saveError = null; target = onClear() }, enabled = !saving) {
                    Text("Remove saved provider and key")
                }
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Button(
                onClick = ::save,
                enabled = canSave,
                colors = brandButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saving) "Checking key" else "Save provider")
            }
        }
    }
}

/** The provider's initial in a rounded tile, the same measurements as the Dictionary's picker cards. */
@Composable
internal fun ProviderTile(provider: Provider) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.capabilities().displayName.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Three dots, filled up to [value] on a 1-3 scale, read against the C/S/A legend. */
@Composable
internal fun ScoreDots(value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (level in 3 downTo 1) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (value >= level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

internal fun ValidationReason.userMessage(): String = when (this) {
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
