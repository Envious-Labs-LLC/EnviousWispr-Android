package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

/**
 * What a provider said when asked whether a key works (#61). Produced by [ProviderKeyChecker.check] from
 * the provider's model-list endpoint and consumed by [ProviderConfigurationRepository.saveProvider], which
 * writes a cloud key only on [Accepted]. No verdict carries the key or the response body.
 */
sealed interface ProviderKeyCheck {
    /** The provider answered 200 with its model list: the key works. */
    data object Accepted : ProviderKeyCheck

    /** The provider takes no key (self-hosted); nothing was asked. */
    data object NotApplicable : ProviderKeyCheck

    /** The provider said the key itself is wrong (401; Gemini 403 or 400 API_KEY_INVALID). */
    data class Rejected(val status: Int) : ProviderKeyCheck

    /** The key is recognised but not allowed (403 from OpenAI or Claude). */
    data class Denied(val status: Int) : ProviderKeyCheck

    /**
     * No verdict on the key: the provider could not be reached, answered with a limit or an error, or
     * answered with something that is not its model list. [failure] is one of the existing #77 members
     * RATE_LIMITED, RATE_OR_QUOTA, PROVIDER_ERROR, UNREACHABLE, TIMED_OUT, BAD_REQUEST or UNEXPECTED.
     */
    data class Unverified(val failure: PolishFailure, val status: Int? = null) : ProviderKeyCheck
}

/** The seam the repository is given; [ProviderPolishClient] is the production implementation. */
fun interface ProviderKeyChecker {
    fun check(provider: Provider, apiKey: String): ProviderKeyCheck
}

/** Thrown by [ProviderConfigurationRepository.saveProvider] before any write when the check did not accept. */
class ProviderKeyRefusedException(val provider: Provider, val verdict: ProviderKeyCheck) :
    RuntimeException("key check for $provider: ${verdict::class.simpleName}")
