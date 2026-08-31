package com.envi.wispr.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
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

/**
 * What happens after transcription, and the model that does it.
 *
 * The API-key draft below is the reason this is a page rather than a saved-state destination: it lives
 * in a plain `remember`, so leaving AI Polish disposes it. It must never move to `rememberSaveable`,
 * never be hoisted into `EnviousWisprUiState`, and never be logged.
 */
@Composable
internal fun PolishScreen(
    settings: ProviderSettingsUiState,
    readiness: AppReadiness,
    onRefreshReadiness: () -> Unit,
    onSetMode: (PolishMode) -> Unit,
    onSaveProvider: (Provider, String, String?, String?, SelfHostedProtocol) -> Unit,
    onClearProvider: () -> Unit,
) {
    val context = LocalContext.current
    val s1Work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val s1Adoption by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1)).collectAsStateWithLifecycle(emptyList())
    val s1State = workUiState(preferredModelWork(s1Work, s1Adoption), readiness.polishModelReady, ModelManifest.s1, context)
    var mode by remember(settings.mode) { mutableStateOf(settings.mode) }
    var provider by remember(settings.provider) { mutableStateOf(settings.provider) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var endpoint by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var protocol by remember(settings.selfHostedProtocol) {
        mutableStateOf(settings.selfHostedProtocol)
    }
    // A credential draft is deliberately not saveable and never enters the ViewModel state.
    var apiKey by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    ScreenContainer(
        subtitle = "Clean up and rewrite your dictation with AI.",
        modifier = Modifier.imePadding(),
    ) {
        Text(
            "Choose what happens after private, on-device transcription.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PolishMode.OFF to "Off",
                PolishMode.OFFLINE_S1 to "Offline S1",
                PolishMode.PROVIDER to "Provider",
            ).forEach { (option, label) ->
                FilterChip(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        localError = null
                    },
                    label = { Text(label) },
                )
            }
        }

        when (mode) {
            PolishMode.OFF -> Text(
                "No language model runs. Deterministic cleanup still removes obvious filler and spacing issues.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PolishMode.OFFLINE_S1 -> Text(
                "S1-mini polishes entirely on this phone. Dictated text does not leave the device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PolishMode.PROVIDER -> {
                Text("Provider", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Provider.entries.forEach { option ->
                        FilterChip(
                            selected = provider == option,
                            onClick = {
                                if (provider != option) {
                                    provider = option
                                    model = ""
                                    endpoint = ""
                                    apiKey = ""
                                }
                                localError = null
                            },
                            label = { Text(option.capabilities().displayName) },
                        )
                    }
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model ID") },
                    supportingText = { Text("Use the exact model name from your provider.") },
                    singleLine = true,
                )
                if (provider.capabilities().requiresApiKey) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            localError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        placeholder = if (settings.credentialStored && provider == settings.provider) {
                            { Text("Leave blank to keep saved key") }
                        } else null,
                        supportingText = {
                            Text("Encrypted in Android Keystore. Never saved in screen state or logs.")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (provider == Provider.SELF_HOSTED_POLISH) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            localError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Server URL") },
                        supportingText = { Text("HTTPS is required except for loopback development.") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelfHostedProtocol.entries.forEach { option ->
                            FilterChip(
                                selected = protocol == option,
                                onClick = { protocol = option },
                                label = {
                                    Text(
                                        if (option == SelfHostedProtocol.OLLAMA) "Ollama"
                                        else "OpenAI compatible",
                                    )
                                },
                            )
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                colors = brandButtonColors(),
                onClick = {
                    if (mode != PolishMode.PROVIDER) {
                        onSetMode(mode)
                        return@Button
                    }
                    val normalizedModel = model.trim()
                    if (normalizedModel.isEmpty() || normalizedModel.length > 256 ||
                        normalizedModel.any(Char::isISOControl)) {
                        localError = "Enter a valid provider model ID."
                        return@Button
                    }
                    val storedCredentialApplies = settings.credentialStored &&
                        provider == settings.provider
                    val effectiveKey = apiKey.takeIf(String::isNotBlank)
                        ?: if (storedCredentialApplies) "stored-credential" else null
                    val normalizedEndpoint = endpoint.trim().takeIf(String::isNotEmpty)
                    when (val validation = ProviderConfigurationValidator.validate(
                        ProviderConfiguration(provider, normalizedEndpoint),
                        effectiveKey,
                    )) {
                        ValidationResult.Valid -> {
                            onSaveProvider(
                                provider,
                                normalizedModel,
                                normalizedEndpoint,
                                apiKey.takeIf(String::isNotBlank),
                                protocol,
                            )
                            // The key is now encrypted in the Keystore, so the draft has no
                            // reason to stay in memory.
                            apiKey = ""
                        }
                        is ValidationResult.Invalid -> {
                            localError = validation.reason.userMessage()
                        }
                    }
                },
            ) {
                Text(if (mode == PolishMode.PROVIDER) "Save provider" else "Apply")
            }
            if (settings.configured) {
                OutlinedButton(onClick = {
                    apiKey = ""
                    onClearProvider()
                }) {
                    Text("Remove saved provider and key")
                }
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

