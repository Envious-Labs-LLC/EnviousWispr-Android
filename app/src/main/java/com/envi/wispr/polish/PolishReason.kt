package com.envi.wispr.polish

import com.envi.wispr.cleanup.PipelineOutcome
import com.envi.wispr.providers.ProviderErrorSignal
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

    /** The engine's own deadline on a local generation fired; the engine process is ending. */
    LOCAL_TIMEOUT,
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

    /** Session-owner side only: the engine never answered within the policy's budget. */
    WATCHDOG_TIMEOUT,

    // Body-signalled HTTP failures (#77): the provider's error body named a cause the status alone does
    // not. Produced by `ProviderErrorSignal` inside the cloud client; the body itself never leaves it.
    HTTP_KEY_REJECTED,
    HTTP_OUT_OF_CREDITS,
    HTTP_INPUT_TOO_LONG,
    HTTP_CONTENT_BLOCKED,

    /** The cloud client refused the request before sending it: model, endpoint, or size (#77). */
    INVALID_CONFIGURATION,
    ;

    companion object {
        /**
         * Exhaustive by construction: a new failure kind or a new body signal fails to compile here. A
         * signal only refines `HTTP_ERROR`; on any other kind it is ignored, because the client only sets
         * one on an HTTP error.
         */
        fun from(kind: ProviderFailureKind, signal: ProviderErrorSignal? = null): PolishReason = when (kind) {
            ProviderFailureKind.NO_API_KEY -> NO_API_KEY
            ProviderFailureKind.INVALID_CONFIGURATION -> INVALID_CONFIGURATION
            ProviderFailureKind.NETWORK -> NETWORK
            ProviderFailureKind.TIMEOUT -> TIMEOUT
            ProviderFailureKind.CANCELLED -> CANCELLED
            ProviderFailureKind.HTTP_ERROR -> when (signal) {
                null -> HTTP_ERROR
                ProviderErrorSignal.KEY_REJECTED -> HTTP_KEY_REJECTED
                ProviderErrorSignal.OUT_OF_CREDITS -> HTTP_OUT_OF_CREDITS
                ProviderErrorSignal.INPUT_TOO_LONG -> HTTP_INPUT_TOO_LONG
                ProviderErrorSignal.CONTENT_BLOCKED -> HTTP_CONTENT_BLOCKED
            }
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
