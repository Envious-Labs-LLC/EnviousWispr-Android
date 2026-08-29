package com.envi.wispr.providers

import android.content.Context
import android.content.SharedPreferences

/** Explicit polish policy persisted independently from the selected provider credentials. */
enum class PolishMode {
    OFF,
    OFFLINE_S1,
    PROVIDER,
}

/**
 * Persists provider selection metadata separately from credentials. API keys are always delegated
 * to [SecretStore]; this class never writes a key to SharedPreferences. The endpoint is accepted
 * here only as explicit user configuration and is revalidated every time it is loaded.
 */
class ProviderConfigurationRepository(
    context: Context,
    private val secrets: SecretStore = AndroidKeystoreSecretStore(context.applicationContext),
) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun loadMode(): PolishMode = preferences.getString(KEY_MODE, null)?.let { name ->
        runCatching { PolishMode.valueOf(name) }.getOrDefault(PolishMode.OFFLINE_S1)
    } ?: PolishMode.OFFLINE_S1

    fun load(): SelectedProviderConfiguration? {
        val provider = preferences.getString(KEY_PROVIDER, null)?.let { name ->
            runCatching { Provider.valueOf(name) }.getOrNull()
        } ?: return null
        val model = preferences.getString(KEY_MODEL, null).orEmpty()
        val endpoint = preferences.getString(KEY_ENDPOINT, null)
        val protocol = preferences.getString(KEY_PROTOCOL, null)?.let { name ->
            runCatching { SelfHostedProtocol.valueOf(name) }.getOrDefault(SelfHostedProtocol.OPENAI_COMPATIBLE)
        } ?: SelfHostedProtocol.OPENAI_COMPATIBLE
        val apiKey = runCatching { secrets.get(provider) }.getOrNull()
        val validation = ProviderConfigurationValidator.validate(ProviderConfiguration(provider, endpoint), apiKey)
        if ((validation is ValidationResult.Invalid && validation.reason != ValidationReason.API_KEY_REQUIRED) ||
            model.isBlank() || model.length > MAX_MODEL_CHARS || model.any(Char::isISOControl)) {
            return null
        }
        return SelectedProviderConfiguration(
            provider = provider,
            model = model,
            endpoint = endpoint,
            apiKey = apiKey,
            selfHostedProtocol = protocol,
        )
    }

    /** Saves metadata and the optional key without ever putting the key in preferences. */
    fun saveProvider(
        provider: Provider,
        model: String,
        endpoint: String? = null,
        apiKey: String? = null,
        selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
    ) {
        require(model.isNotBlank() && model.length <= MAX_MODEL_CHARS && model.none(Char::isISOControl)) {
            "invalid provider model"
        }
        // A blank key means "keep the existing encrypted key" when editing model/endpoint.
        // Read it only for validation and runtime selection; it never enters preferences or UI.
        val effectiveApiKey = apiKey?.takeIf(String::isNotBlank)
            ?: runCatching { secrets.get(provider) }.getOrNull()
        require(
            ProviderConfigurationValidator.validate(ProviderConfiguration(provider, endpoint), effectiveApiKey)
                is ValidationResult.Valid,
        ) { "invalid provider configuration" }
        if (!apiKey.isNullOrBlank()) secrets.put(provider, apiKey)
        val values = preferences.edit()
            .putString(KEY_MODE, PolishMode.PROVIDER.name)
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_MODEL, model)
            .putString(KEY_PROTOCOL, selfHostedProtocol.name)
        if (provider == Provider.SELF_HOSTED_POLISH) values.putString(KEY_ENDPOINT, endpoint)
        else values.remove(KEY_ENDPOINT)
        check(values.commit()) { "could not persist provider configuration" }
    }

    /** Compatibility alias for callers that already use the shorter provider-save name. */
    fun save(
        provider: Provider,
        model: String,
        endpoint: String? = null,
        apiKey: String? = null,
        selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
    ) = saveProvider(provider, model, endpoint, apiKey, selfHostedProtocol)

    fun setMode(mode: PolishMode) {
        check(preferences.edit().putString(KEY_MODE, mode.name).commit()) {
            "could not persist polish mode"
        }
    }

    fun clearSelection() {
        val selectedProvider = preferences.getString(KEY_PROVIDER, null)?.let { name ->
            runCatching { Provider.valueOf(name) }.getOrNull()
        }
        selectedProvider?.let { secrets.remove(it) }
        check(
            preferences.edit()
                .putString(KEY_MODE, PolishMode.OFFLINE_S1.name)
                .remove(KEY_PROVIDER)
                .remove(KEY_MODEL)
                .remove(KEY_ENDPOINT)
                .remove(KEY_PROTOCOL)
                .commit(),
        ) {
            "could not clear provider configuration"
        }
    }

    companion object {
        private const val PREFERENCES = "envious_wispr_provider_configuration"
        private const val KEY_MODE = "mode"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_PROTOCOL = "protocol"
        private const val MAX_MODEL_CHARS = 256
    }
}

data class SelectedProviderConfiguration(
    val provider: Provider,
    val model: String,
    val endpoint: String?,
    val apiKey: String?,
    val selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
) {
    fun request(prompt: String): ProviderPolishRequest = ProviderPolishRequest(
        provider = provider,
        model = model,
        prompt = prompt,
        apiKey = apiKey,
        endpoint = endpoint,
        selfHostedProtocol = selfHostedProtocol,
    )
}
