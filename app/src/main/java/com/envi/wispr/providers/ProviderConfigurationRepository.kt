package com.envi.wispr.providers

import android.content.Context
import android.content.SharedPreferences
import com.envi.wispr.polish.PolishPolicy

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

    fun loadMode(): PolishMode = decodeMode(preferences.all)

    fun load(): SelectedProviderConfiguration? {
        val selection = decodeSelection(preferences.all) ?: return null
        val apiKey = runCatching { secrets.get(selection.provider) }.getOrNull()
        // The stored key is validated too: a key carrying a control character is not a usable selection,
        // which is the acceptance this method has always had.
        val validation = ProviderConfigurationValidator.validate(
            ProviderConfiguration(selection.provider, selection.endpoint),
            apiKey,
        )
        if (validation is ValidationResult.Invalid && validation.reason != ValidationReason.API_KEY_REQUIRED) {
            return null
        }
        return SelectedProviderConfiguration(
            provider = selection.provider,
            model = selection.model,
            endpoint = selection.endpoint,
            apiKey = apiKey,
            selfHostedProtocol = selection.protocol,
        )
    }

    /**
     * The policy snapshot a dictation session carries to the engine (`PolishPolicy`). ONE read of the
     * preference map, so a commit landing between two reads cannot assemble a policy from two states,
     * and the credential is never read here. A store that cannot be read yields [PolishPolicy.Off].
     */
    fun loadPolicy(): PolishPolicy = readPolicy { preferences.all }

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
        /** The failure branch of [loadPolicy], separated so the JVM can stage a store that cannot be read. */
        internal fun readPolicy(readSnapshot: () -> Map<String, *>): PolishPolicy =
            runCatching { decodePolicy(readSnapshot()) }.getOrDefault(PolishPolicy.Off)

        /**
         * Pure decoding of one preference snapshot into the policy. Mirrors [decodeMode] and
         * [decodeSelection] exactly, so the screen and the engine can never disagree about what a
         * stored value means. Unit-tested on the JVM; the repository wraps it with the real store.
         */
        fun decodePolicy(values: Map<String, *>): PolishPolicy = when (decodeMode(values)) {
            PolishMode.OFF -> PolishPolicy.Off
            PolishMode.OFFLINE_S1 -> PolishPolicy.LocalS1
            PolishMode.PROVIDER -> decodeSelection(values)?.let { selection ->
                PolishPolicy.Cloud(selection.provider, selection.model, selection.endpoint, selection.protocol)
            } ?: PolishPolicy.CloudUnconfigured
        }

        /** An absent or unparseable mode reads as the offline default, as it always has. */
        fun decodeMode(values: Map<String, *>): PolishMode = (values[KEY_MODE] as? String)?.let { name ->
            runCatching { PolishMode.valueOf(name) }.getOrDefault(PolishMode.OFFLINE_S1)
        } ?: PolishMode.OFFLINE_S1

        /**
         * The stored selection when it is usable, or null. Validated with no key, tolerating only the
         * key-required refusal, which is the same acceptance [load] applies with the key present.
         */
        fun decodeSelection(values: Map<String, *>): StoredSelection? {
            val provider = (values[KEY_PROVIDER] as? String)?.let { name ->
                runCatching { Provider.valueOf(name) }.getOrNull()
            } ?: return null
            val model = (values[KEY_MODEL] as? String).orEmpty()
            val endpoint = values[KEY_ENDPOINT] as? String
            val protocol = (values[KEY_PROTOCOL] as? String)?.let { name ->
                runCatching { SelfHostedProtocol.valueOf(name) }.getOrDefault(SelfHostedProtocol.OPENAI_COMPATIBLE)
            } ?: SelfHostedProtocol.OPENAI_COMPATIBLE
            val validation = ProviderConfigurationValidator.validate(ProviderConfiguration(provider, endpoint), null)
            if ((validation is ValidationResult.Invalid && validation.reason != ValidationReason.API_KEY_REQUIRED) ||
                model.isBlank() || model.length > MAX_MODEL_CHARS || model.any(Char::isISOControl)) {
                return null
            }
            return StoredSelection(provider, model, endpoint, protocol)
        }

        private const val PREFERENCES = "envious_wispr_provider_configuration"
        private const val KEY_MODE = "mode"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_PROTOCOL = "protocol"
        private const val MAX_MODEL_CHARS = 256
    }
}

/** The credential-free part of a stored selection: what [ProviderConfigurationRepository.decodeSelection] can read. */
data class StoredSelection(
    val provider: Provider,
    val model: String,
    val endpoint: String?,
    val protocol: SelfHostedProtocol,
)

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
