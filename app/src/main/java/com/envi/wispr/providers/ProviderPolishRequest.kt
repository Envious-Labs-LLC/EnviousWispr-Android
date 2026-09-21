package com.envi.wispr.providers

/*
 * The cloud polish contract: what a caller hands the client and what it gets back. Moved verbatim out of
 * `ProviderPolishClient.kt` (#189); the wire shape behind it is the adapters' business.
 */

enum class SelfHostedProtocol {
    OPENAI_COMPATIBLE,
    OLLAMA,
}

/**
 * A self-hosted endpoint is accepted only as explicit user configuration. Callers must not fill
 * it from transcript text, provider responses, redirects, or other untrusted input; validation
 * constrains the URI, while the caller-owned settings layer must establish that provenance.
 */
data class ProviderPolishRequest(
    val provider: Provider,
    val model: String,
    val prompt: String,
    val apiKey: String? = null,
    val endpoint: String? = null,
    val selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
) {
    override fun toString(): String =
        "ProviderPolishRequest(provider=$provider, model=<redacted>, prompt=<redacted>, apiKey=<redacted>)"
}

enum class ProviderFailureKind {
    NO_API_KEY,
    INVALID_CONFIGURATION,
    NETWORK,
    TIMEOUT,
    CANCELLED,
    HTTP_ERROR,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    REDIRECT_REJECTED,
}

sealed interface ProviderPolishResult {
    data class Success(val text: String) : ProviderPolishResult {
        override fun toString(): String = "Success(text=<redacted>)"
    }
    data class Failure(
        val kind: ProviderFailureKind,
        val statusCode: Int? = null,
        /** Set only on `HTTP_ERROR`, from the provider's error body, which itself goes no further. */
        val signal: ProviderErrorSignal? = null,
    ) : ProviderPolishResult
}

/**
 * What a provider's error BODY said beyond its status (#77), as a closed signal so the body, which can
 * echo the prompt, never leaves the client. Each adapter's `errorSignal` owns its provider's markers, the
 * ones the macOS connectors match.
 */
enum class ProviderErrorSignal {
    KEY_REJECTED,
    OUT_OF_CREDITS,
    INPUT_TOO_LONG,
    CONTENT_BLOCKED,
}
