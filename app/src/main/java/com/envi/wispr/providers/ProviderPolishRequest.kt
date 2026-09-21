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
 * echo the prompt, never leaves this client. The markers are the ones the macOS connectors match.
 */
enum class ProviderErrorSignal {
    KEY_REJECTED,
    OUT_OF_CREDITS,
    INPUT_TOO_LONG,
    CONTENT_BLOCKED,
    ;

    companion object {
        /** Exhaustive over [Provider]; a provider with no body markers answers null for every body. */
        fun classify(provider: Provider, status: Int, body: String): ProviderErrorSignal? = when (provider) {
            Provider.OPENAI -> when (status) {
                429 -> if (body.contains("insufficient_quota")) OUT_OF_CREDITS else null
                400 -> when {
                    body.contains("context_length_exceeded") -> INPUT_TOO_LONG
                    body.contains("content_filter") || body.contains("content_policy") -> CONTENT_BLOCKED
                    else -> null
                }
                else -> null
            }
            Provider.GEMINI -> when (status) {
                400 -> when {
                    body.contains("API_KEY_INVALID") -> KEY_REJECTED
                    body.contains("exceeds the maximum number of tokens") -> INPUT_TOO_LONG
                    body.contains("PROHIBITED_CONTENT") || body.contains("blockReason") -> CONTENT_BLOCKED
                    else -> null
                }
                else -> null
            }
            Provider.CLAUDE -> when (status) {
                400 -> when {
                    body.contains("credit balance") -> OUT_OF_CREDITS
                    body.contains("prompt is too long") -> INPUT_TOO_LONG
                    else -> null
                }
                else -> null
            }
            Provider.SELF_HOSTED_POLISH -> null
        }
    }
}
