package com.envi.wispr.ui

import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.providers.ProviderKeyCheck

/**
 * The one line the setup page shows under Save when a key check did not accept (#61). Null means the
 * save may proceed. Setup-page wording: it never calls the dictation-time [PolishFailure.message].
 */
internal fun keyCheckLine(verdict: ProviderKeyCheck, providerName: String): String? = when (verdict) {
    ProviderKeyCheck.Accepted, ProviderKeyCheck.NotApplicable -> null
    is ProviderKeyCheck.Rejected -> "$providerName rejected this key. Nothing was saved."
    is ProviderKeyCheck.Denied -> "$providerName denied access for this key. Check your billing or API access. Nothing was saved."
    is ProviderKeyCheck.Unverified -> "Couldn't check the key with $providerName: ${unverifiedReason(verdict.failure, providerName)}. Nothing was saved."
}

private fun unverifiedReason(failure: PolishFailure, providerName: String): String = when (failure) {
    PolishFailure.RATE_LIMITED, PolishFailure.RATE_OR_QUOTA -> "too many requests right now"
    PolishFailure.PROVIDER_ERROR -> "$providerName is having problems"
    PolishFailure.UNREACHABLE -> "no connection"
    PolishFailure.TIMED_OUT -> "it took too long"
    PolishFailure.BAD_REQUEST,
    PolishFailure.UNEXPECTED,
    PolishFailure.KEY_MISSING,
    PolishFailure.KEY_REJECTED,
    PolishFailure.ACCESS_DENIED,
    PolishFailure.OUT_OF_CREDITS,
    PolishFailure.MODEL_UNAVAILABLE,
    PolishFailure.INPUT_TOO_LONG,
    PolishFailure.CONTENT_BLOCKED,
    PolishFailure.OUTPUT_REJECTED,
    PolishFailure.LOCAL_NOT_READY,
    -> "an unexpected reply"
}

/**
 * The line under the key field when Check (#84) did not list models: the same classification as Save's,
 * without "Nothing was saved", because Check never tried to save.
 */
internal fun discoveryLine(verdict: ProviderKeyCheck, providerName: String): String? = when (verdict) {
    ProviderKeyCheck.Accepted, ProviderKeyCheck.NotApplicable -> null
    is ProviderKeyCheck.Rejected -> "$providerName rejected this key."
    is ProviderKeyCheck.Denied -> "$providerName denied access for this key. Check your billing or API access."
    is ProviderKeyCheck.Unverified -> "Couldn't check the key with $providerName: ${unverifiedReason(verdict.failure, providerName)}."
}
