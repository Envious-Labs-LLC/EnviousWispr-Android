package com.envi.wispr.polish

import com.envi.wispr.cleanup.PipelineOutcome
import com.envi.wispr.providers.ProviderFailureKind

/**
 * Why a polish request ended the way it did: one closed vocabulary, one member per producer path.
 * Carried in `PolishOutcome` over the binder and logged by name only. Phase 4 of the AI Polish
 * refinement roadmap renders it; phase 7 splits `HTTP_ERROR` by the status that travels beside it.
 */
enum class PolishReason {
    POLISHED,
    OFF,
    NO_SPEECH,
    EMPTY_AFTER_CLEANUP,
    CLEANUP_RECOVERED,
    LOCAL_NOT_READY,
    LOCAL_FAILED,
    OUTPUT_REJECTED,
    CLOUD_NOT_CONFIGURED,
    NO_API_KEY,
    NETWORK,
    TIMEOUT,
    CANCELLED,
    HTTP_ERROR,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    REDIRECT_REJECTED,
    UNEXPECTED,

    /** Session-owner side only: the engine was never bound when the transcript arrived. */
    SERVICE_UNAVAILABLE,

    /** Session-owner side only: the engine process went away mid-request. */
    SERVICE_DIED,

    /** Session-owner side only: the binder call itself threw. */
    CALL_FAILED,
    ;

    companion object {
        /** Exhaustive by construction: a new failure kind fails to compile here. */
        fun from(kind: ProviderFailureKind): PolishReason = when (kind) {
            ProviderFailureKind.NO_API_KEY -> NO_API_KEY
            ProviderFailureKind.INVALID_CONFIGURATION -> CLOUD_NOT_CONFIGURED
            ProviderFailureKind.NETWORK -> NETWORK
            ProviderFailureKind.TIMEOUT -> TIMEOUT
            ProviderFailureKind.CANCELLED -> CANCELLED
            ProviderFailureKind.HTTP_ERROR -> HTTP_ERROR
            ProviderFailureKind.MALFORMED_RESPONSE -> MALFORMED_RESPONSE
            ProviderFailureKind.RESPONSE_TOO_LARGE -> RESPONSE_TOO_LARGE
            ProviderFailureKind.REDIRECT_REJECTED -> REDIRECT_REJECTED
        }

        /**
         * The reason for a finished pipeline run. [attempt] is what the model adapter recorded about
         * its own failure, if any; it wins over anything inferred from the pipeline's shape, and a
         * declined or rejected model with nothing recorded is an output problem, never an inferred
         * cloud kind.
         */
        fun resolve(policy: PolishPolicy, outcome: PipelineOutcome, attempt: PolishReason?): PolishReason = when (policy) {
            PolishPolicy.Off -> OFF
            PolishPolicy.CloudUnconfigured -> CLOUD_NOT_CONFIGURED
            PolishPolicy.LocalS1, is PolishPolicy.Cloud -> when (outcome) {
                PipelineOutcome.MODEL_ACCEPTED -> POLISHED
                PipelineOutcome.CLEANUP_RECOVERED -> CLEANUP_RECOVERED
                PipelineOutcome.EMPTY_AFTER_CLEANUP -> EMPTY_AFTER_CLEANUP
                PipelineOutcome.NO_MODEL -> UNEXPECTED
                PipelineOutcome.MODEL_DECLINED, PipelineOutcome.MODEL_REJECTED -> attempt ?: OUTPUT_REJECTED
            }
        }
    }
}
