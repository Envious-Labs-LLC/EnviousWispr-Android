package com.envi.wispr.providers

/**
 * Which cloud polish failures are worth a second and third attempt (#4), the macOS `LLMRetryPolicy` and
 * `PolishFailureReason.isRetryable` rules. Precedence, in this order: cancellation stops; a body signal
 * stops (a rejected key, no credits, too long, blocked: retrying spends the user's account for nothing);
 * an OBSERVED status decides (429 unless Gemini, whose 429 cannot be told from an exhausted quota; 5xx);
 * otherwise the transport kind decides (no network and a timeout are transient). Status decides
 * eligibility only; it never reclassifies the failure the service receives.
 */
object ProviderRetryPolicy {
    fun isRetryable(failure: ProviderPolishResult.Failure, provider: Provider): Boolean {
        if (failure.signal != null) return false
        // Kinds that a second attempt cannot change, whatever status came with them: a refused
        // configuration, a body that did not parse or was too large to read, a redirect, a cancel.
        when (failure.kind) {
            ProviderFailureKind.CANCELLED,
            ProviderFailureKind.NO_API_KEY,
            ProviderFailureKind.INVALID_CONFIGURATION,
            ProviderFailureKind.MALFORMED_RESPONSE,
            ProviderFailureKind.RESPONSE_TOO_LARGE,
            ProviderFailureKind.REDIRECT_REJECTED,
            -> return false
            ProviderFailureKind.NETWORK, ProviderFailureKind.TIMEOUT, ProviderFailureKind.HTTP_ERROR -> Unit
        }
        val status = failure.statusCode
        if (status != null) return status in 500..599 || (status == 429 && provider != Provider.GEMINI)
        return failure.kind == ProviderFailureKind.NETWORK || failure.kind == ProviderFailureKind.TIMEOUT
    }
}
