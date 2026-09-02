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
class ProviderConfigurationRepository internal constructor(
    private val preferences: SharedPreferences,
    private val secrets: SecretStore,
    /** Asked before any cloud key is written (#61); the production checker is the cloud client itself. */
    private val keyCheck: ProviderKeyChecker,
) {
    /** Production: the app's own preference file, the Keystore-backed store and the live key check. */
    constructor(
        context: Context,
        secrets: SecretStore = AndroidKeystoreSecretStore(context.applicationContext),
        keyCheck: ProviderKeyChecker = ProviderPolishClient(),
    ) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        secrets,
        keyCheck,
    )

    /**
     * The engine the user was on before turning polish off (#67). Written in the SAME editor batch as
     * `mode` by every non-Off write, so the two cannot disagree; never `OFF`. Absent or unreadable reads as
     * This phone, which is the safe engine: it never routes text to a provider it cannot prove.
     */
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
        // A supplied key is judged on its RAW value first (control characters are a refusal, never
        // trimmed away), then trimmed ONCE; that trimmed value is what gets checked and stored. A blank
        // key means "keep the existing encrypted key" when editing model/endpoint, and a stored key is
        // never re-normalised. The key is read only for validation, the check and runtime selection; it
        // never enters preferences or UI.
        require(apiKey?.any(Char::isISOControl) != true) { "invalid provider configuration" }
        val suppliedKey = apiKey?.trim()?.takeIf(String::isNotEmpty)
        // The previous credential is read BEFORE anything is written (#81), because the Keystore put below
        // happens before the preferences commit and a failed commit must give the old key back. For a
        // supplied key the snapshot is mandatory: an unreadable store aborts here, before any write, rather
        // than reading as "no previous key" and deleting one during the compensation.
        val previousKey: String? = if (suppliedKey != null) secrets.get(provider) else runCatching { secrets.get(provider) }.getOrNull()
        val effectiveApiKey = suppliedKey ?: previousKey
        require(
            ProviderConfigurationValidator.validate(ProviderConfiguration(provider, endpoint), effectiveApiKey)
                is ValidationResult.Valid,
        ) { "invalid provider configuration" }
        // The live check (#61) runs before the first write, so nothing is stored unless the provider
        // accepted the key. Self-hosted takes no key and is never asked.
        if (provider.capabilities().requiresApiKey) {
            // Only an explicit Accepted writes; NotApplicable is a self-hosted answer and, for a provider
            // that needs a key, a checker fault, so it refuses like every other non-acceptance.
            when (val verdict = keyCheck.check(provider, effectiveApiKey.orEmpty())) {
                ProviderKeyCheck.Accepted -> Unit
                ProviderKeyCheck.NotApplicable,
                is ProviderKeyCheck.Rejected,
                is ProviderKeyCheck.Denied,
                is ProviderKeyCheck.Unverified,
                -> throw ProviderKeyRefusedException(provider, verdict)
            }
        }
        if (suppliedKey != null) secrets.put(provider, suppliedKey)
        val values = preferences.edit()
            .putString(KEY_MODE, PolishMode.PROVIDER.name)
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_MODEL, model)
            .putString(KEY_PROTOCOL, selfHostedProtocol.name)
        if (provider == Provider.SELF_HOSTED_POLISH) values.putString(KEY_ENDPOINT, endpoint)
        else values.remove(KEY_ENDPOINT)
        if (!values.commit()) {
            // Compensation: the metadata did not change, so the key must not have either. A compensation
            // that fails is reported as what it is; "nothing changed" would be a lie the UI repeats.
            if (suppliedKey != null) {
                val restored = runCatching { if (previousKey != null) secrets.put(provider, previousKey) else secrets.remove(provider) }
                if (restored.isFailure) throw InconsistentProviderStorageException(provider, restored.exceptionOrNull())
            }
            error("could not persist provider configuration")
        }
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
        // The key leaves the Keystore before the preferences commit, so a failed commit must put it back
        // (#81, the mirror of saveProvider): the snapshot is mandatory, an unreadable store aborts here.
        val previousKey = selectedProvider?.takeIf { it.capabilities().requiresApiKey }?.let(secrets::get)
        selectedProvider?.let { secrets.remove(it) }
        val committed = preferences.edit()
            .putString(KEY_MODE, PolishMode.OFFLINE_S1.name)
            .remove(KEY_PROVIDER)
            .remove(KEY_MODEL)
            .remove(KEY_ENDPOINT)
            .remove(KEY_PROTOCOL)
            .commit()
        if (!committed) {
            if (selectedProvider != null && previousKey != null) {
                val restored = runCatching { secrets.put(selectedProvider, previousKey) }
                if (restored.isFailure) throw InconsistentProviderStorageException(selectedProvider, restored.exceptionOrNull())
            }
            error("could not clear provider configuration")
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

/**
 * A failed save could not put the previous key back (#81): the preferences commit failed after the new key
 * reached the Keystore, and the compensation failed too. The stored key and the stored metadata may now
 * disagree, and the only honest repair is to remove the provider and set it up again.
 */
class InconsistentProviderStorageException(val provider: Provider, cause: Throwable?) :
    IllegalStateException("provider storage is inconsistent for ${provider.name}", cause)
