package com.envi.wispr.providers

/** Providers supported by the configuration layer. ProviderPolishClient owns network calls. */
enum class Provider {
    OPENAI,
    GEMINI,
    CLAUDE,
    SELF_HOSTED_POLISH,
}

data class ProviderCapabilities(
    val provider: Provider,
    val displayName: String,
    val requiresApiKey: Boolean,
    val requiresEndpoint: Boolean,
    val sendsTextOffDevice: Boolean,
    val offlineAvailable: Boolean,
)

fun Provider.capabilities(): ProviderCapabilities = when (this) {
    Provider.OPENAI -> ProviderCapabilities(this, "OpenAI", true, false, true, false)
    Provider.GEMINI -> ProviderCapabilities(this, "Gemini", true, false, true, false)
    Provider.CLAUDE -> ProviderCapabilities(this, "Claude", true, false, true, false)
    Provider.SELF_HOSTED_POLISH -> ProviderCapabilities(this, "Self-hosted polish", false, true, true, false)
}

/** User-facing disclosure kept here so callers cannot imply that configuration is an offline feature. */
data class ProviderDisclosure(
    val provider: Provider,
    val summary: String,
    val networkRequired: Boolean,
    val apiKeyStoredEncrypted: Boolean,
)

fun Provider.disclosure(): ProviderDisclosure = ProviderDisclosure(
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
