package com.envi.wispr.providers.ui

import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.providers.ProviderDiscovery
import com.envi.wispr.providers.ProviderKeyCheck

/**
 * The closed values the Polish page's `api_key.changed` and `api_key.validation_completed` rows carry (#307):
 * what was done to a key, whether it landed, and a key check's verdict. Never the key. `PostHogSchema` admits
 * exactly these sets, so an emit site that invents a value loses the field instead of sending it.
 */
internal object ApiKeyTelemetry {
    const val ACTION_SAVE = "save"
    const val ACTION_MODEL_CHANGE = "model_change"
    const val ACTION_REMOVE = "remove"
    val ACTIONS: Set<String> = setOf(ACTION_SAVE, ACTION_MODEL_CHANGE, ACTION_REMOVE)

    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILED = "failed"
    val CHANGE_RESULTS: Set<String> = setOf(RESULT_SUCCESS, RESULT_FAILED)

    const val CHECK_VALID = "valid"
    const val CHECK_NOT_APPLICABLE = "not_applicable"
    const val CHECK_REJECTED = "rejected"
    const val CHECK_DENIED = "denied"
    private const val CHECK_UNVERIFIED_PREFIX = "unverified_"

    fun unverified(failure: PolishFailure): String = CHECK_UNVERIFIED_PREFIX + failure.name.lowercase()

    /** Every token [keyCheckToken] can answer. */
    val CHECK_RESULTS: Set<String> =
        setOf(CHECK_VALID, CHECK_NOT_APPLICABLE, CHECK_REJECTED, CHECK_DENIED) + PolishFailure.entries.map(::unverified)

    /** A key check's verdict as a closed token; exhaustive over the verdict type, no `else`. */
    fun keyCheckToken(outcome: ProviderDiscovery): String = when (outcome) {
        is ProviderDiscovery.Listed -> CHECK_VALID
        is ProviderDiscovery.Refused -> when (val verdict = outcome.verdict) {
            ProviderKeyCheck.Accepted -> CHECK_VALID
            ProviderKeyCheck.NotApplicable -> CHECK_NOT_APPLICABLE
            is ProviderKeyCheck.Rejected -> CHECK_REJECTED
            is ProviderKeyCheck.Denied -> CHECK_DENIED
            is ProviderKeyCheck.Unverified -> unverified(verdict.failure)
        }
    }
}
