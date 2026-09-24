package com.envi.wispr.providers

/** Providers supported by the configuration layer. The wire shape per provider is its `ProviderAdapter`; `ProviderPolishClient` and `ProviderModelDiscoveryClient` make the calls. */
internal enum class Provider {
    OPENAI,
    GEMINI,
    CLAUDE,
    SELF_HOSTED_POLISH,
}

internal data class ProviderCapabilities(
    val provider: Provider,
    val displayName: String,
    val requiresApiKey: Boolean,
    val requiresEndpoint: Boolean,
    val sendsTextOffDevice: Boolean,
    val offlineAvailable: Boolean,
    /**
     * Whether a fresh setup offers this provider as a tile (#310). Self-hosted is not offered: the catalog decision
     * of 2026-09-01 keeps it only for a configuration an older build saved.
     */
    val offeredAsSetupTile: Boolean,
)

internal fun Provider.capabilities(): ProviderCapabilities = when (this) {
    Provider.OPENAI -> ProviderCapabilities(this, "OpenAI", true, false, true, false, offeredAsSetupTile = true)
    Provider.GEMINI -> ProviderCapabilities(this, "Gemini", true, false, true, false, offeredAsSetupTile = true)
    Provider.CLAUDE -> ProviderCapabilities(this, "Claude", true, false, true, false, offeredAsSetupTile = true)
    Provider.SELF_HOSTED_POLISH -> ProviderCapabilities(this, "Self-hosted polish", false, true, true, false, offeredAsSetupTile = false)
}

/**
 * The one per-provider disclosure (#308): the sentence the setup screen shows before a user's text leaves the phone.
 * Its `when (this)` stays exhaustive with no `else`, so a new provider breaks the build instead of inheriting a wrong
 * disclosure; `ProviderDisclosureTest` pins every sentence. The Privacy page's own sentences are
 * `privacy/PrivacyDisclosure.kt`.
 */
internal data class ProviderDisclosure(
    val provider: Provider,
    val summary: String,
    val networkRequired: Boolean,
    val apiKeyStoredEncrypted: Boolean,
)

internal fun Provider.disclosure(): ProviderDisclosure = ProviderDisclosure(
    provider = this,
    summary = when (this) {
        Provider.OPENAI -> "Text is sent to OpenAI when this provider is used."
        Provider.GEMINI -> "Text is sent to Google Gemini when this provider is used."
        Provider.CLAUDE -> "Text is sent to Anthropic Claude when this provider is used."
        Provider.SELF_HOSTED_POLISH -> "Text is sent to the configured self-hosted endpoint when used."
    },
    networkRequired = true,
    apiKeyStoredEncrypted = capabilities().requiresApiKey,
)
