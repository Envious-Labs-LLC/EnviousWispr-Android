package com.envi.wispr.polish

import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.capabilities

/**
 * The latched polish policy as a STABLE PERSISTED TOKEN (#77). History stores it beside the reason and the
 * status so a card can rebuild the same sentence weeks later through the same derivation the completion
 * surface used. The tokens are database schema values: never rename or remove one without a migration or
 * a legacy alias, and decoding is tolerant, an empty or unknown token reads as null and the card then says
 * nothing about a failure rather than throwing.
 */
sealed class PolishContext {
    object Off : PolishContext()
    object Local : PolishContext()
    object CloudUnconfigured : PolishContext()
    data class Cloud(val provider: Provider, val ollama: Boolean) : PolishContext()

    /** The name a sentence uses for the engine, or null where no provider is involved. */
    val providerName: String?
        get() = when (this) {
            Off, CloudUnconfigured -> null
            Local -> LOCAL_NAME
            is Cloud -> provider.capabilities().displayName
        }

    val isOllama: Boolean get() = this is Cloud && ollama

    fun encode(): String = when (this) {
        Off -> TOKEN_OFF
        Local -> TOKEN_LOCAL
        CloudUnconfigured -> TOKEN_CLOUD_UNCONFIGURED
        is Cloud -> TOKEN_CLOUD_PREFIX + provider.name + if (ollama) OLLAMA_SUFFIX else ""
    }

    companion object {
        const val LOCAL_NAME = "the on-phone model"
        private const val TOKEN_OFF = "off"
        private const val TOKEN_LOCAL = "local"
        private const val TOKEN_CLOUD_UNCONFIGURED = "cloud-unconfigured"
        private const val TOKEN_CLOUD_PREFIX = "cloud:"
        private const val OLLAMA_SUFFIX = ":ollama"

        fun from(policy: PolishPolicy): PolishContext = when (policy) {
            PolishPolicy.Off -> Off
            PolishPolicy.LocalS1 -> Local
            PolishPolicy.CloudUnconfigured -> CloudUnconfigured
            is PolishPolicy.Cloud -> Cloud(
                policy.provider,
                ollama = policy.provider == Provider.SELF_HOSTED_POLISH && policy.protocol == SelfHostedProtocol.OLLAMA,
            )
        }

        /** Tolerant: null for an empty or unknown token, never a throw. */
        fun decode(token: String): PolishContext? = when {
            token == TOKEN_OFF -> Off
            token == TOKEN_LOCAL -> Local
            token == TOKEN_CLOUD_UNCONFIGURED -> CloudUnconfigured
            token.startsWith(TOKEN_CLOUD_PREFIX) -> {
                val rest = token.removePrefix(TOKEN_CLOUD_PREFIX)
                val ollama = rest.endsWith(OLLAMA_SUFFIX)
                val name = rest.removeSuffix(OLLAMA_SUFFIX)
                val provider = Provider.entries.firstOrNull { it.name == name } ?: return null
                // Only a self-hosted server can speak the Ollama protocol; any other pairing is not a token
                // this code ever wrote, and must not turn into Ollama guidance for a cloud provider.
                if (ollama && provider != Provider.SELF_HOSTED_POLISH) null else Cloud(provider, ollama)
            }
            else -> null
        }
    }
}
