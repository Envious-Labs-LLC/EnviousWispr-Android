package com.envi.wispr.polish

/**
 * What a person is told when a polish did not do its job (#77): one member per sentence, derived in ONE
 * place ([from]) from the engine's reason, the HTTP status and the latched policy. The sentences are the
 * macOS set (catalog `user_copy`, surface `completion warning`); the two Android-only members say so.
 * A projection of the reason, never a re-classification: the published text is chosen elsewhere.
 */
enum class PolishFailure(val leadIn: LeadIn) {
    KEY_MISSING(LeadIn.SKIPPED),
    KEY_REJECTED(LeadIn.FAILED),
    ACCESS_DENIED(LeadIn.FAILED),
    OUT_OF_CREDITS(LeadIn.FAILED),
    RATE_LIMITED(LeadIn.FAILED),
    RATE_OR_QUOTA(LeadIn.FAILED),
    MODEL_UNAVAILABLE(LeadIn.FAILED),
    INPUT_TOO_LONG(LeadIn.SKIPPED),
    CONTENT_BLOCKED(LeadIn.FAILED),
    UNREACHABLE(LeadIn.FAILED),
    PROVIDER_ERROR(LeadIn.FAILED),
    BAD_REQUEST(LeadIn.FAILED),
    TIMED_OUT(LeadIn.SKIPPED),

    /** Android only: the output guards refused the model's answer (`PipelineOutcome.MODEL_REJECTED`). */
    OUTPUT_REJECTED(LeadIn.FAILED),

    /** Android only: the on-phone model was still loading when the words arrived. */
    LOCAL_NOT_READY(LeadIn.SKIPPED),
    UNEXPECTED(LeadIn.FAILED),
    ;

    /** macOS's split: a skip is not a hard failure and never carries the locked sentence. */
    enum class LeadIn(val text: String) {
        FAILED("AI polish failed:"),
        SKIPPED("AI cleanup skipped:"),
    }

    /**
     * The sentence after the lead-in, verbatim from macOS where a twin exists. [context] supplies the
     * provider's name; a local engine is "the on-phone model" and an unconfigured cloud has no name.
     */
    fun message(context: PolishContext?): String {
        val name = context?.providerName
        val ollama = context?.isOllama == true
        return when (this) {
            KEY_MISSING -> if (name == null) "no API key set yet. Add one in Settings." else "no $name API key set yet. Add one in Settings."
            KEY_REJECTED -> if (ollama) "Ollama isn't signed in. Run ollama signin in Terminal, then try again." else "${name ?: "The provider"} rejected your API key. Check or replace it in Settings."
            ACCESS_DENIED -> if (ollama) "that Ollama model requires a subscription. Pick another model or check your Ollama plan." else "${name ?: "The provider"} denied access. Check your provider billing, API access, region, or selected model."
            OUT_OF_CREDITS -> "your ${name ?: "provider"} account is out of credits. Check your provider billing."
            RATE_LIMITED -> "too many requests to ${name ?: "the provider"} right now. It should work again in a moment."
            RATE_OR_QUOTA -> "${name ?: "The provider"} hit a rate or quota limit. Wait a moment, or check your ${name ?: "provider"} billing if it keeps happening."
            MODEL_UNAVAILABLE -> if (ollama) "that Ollama model isn't downloaded yet. Pull it in Ollama or pick another in Settings." else "the selected ${name ?: "provider"} model isn't available. Pick another in Settings."
            INPUT_TOO_LONG -> "this dictation is too long for the selected model. Try a shorter one or a larger model in Settings."
            CONTENT_BLOCKED -> "${name ?: "The provider"} blocked this text. Your original was pasted unchanged."
            UNREACHABLE -> if (ollama) "Ollama isn't reachable. Start Ollama and try again." else "couldn't reach ${name ?: "the provider"}. Check your internet connection, VPN, or proxy."
            PROVIDER_ERROR -> "${name ?: "The provider"} is having problems right now. Try again shortly."
            BAD_REQUEST -> "a configuration problem stopped it. Your original text was pasted unchanged."
            TIMED_OUT -> "the dictation took too long. Your original text was pasted unchanged."
            OUTPUT_REJECTED -> "the model's answer didn't match what you said, so your original was kept."
            LOCAL_NOT_READY -> "the on-phone model isn't ready yet. Try again in a moment."
            UNEXPECTED -> "an unexpected error stopped it. Your original text was pasted unchanged."
        }
    }

    companion object {
        /**
         * The ONE derivation. Null first when polish was Off or the context is unknown: a session-side
         * fallback under Off is not a polish failure the user asked about, and an unreadable stored token
         * must say nothing rather than guess. Then exhaustive over [PolishReason]; `HTTP_ERROR` splits on
         * the status the way the macOS connectors do.
         */
        fun from(reason: PolishReason, statusCode: Int, context: PolishContext?): PolishFailure? {
            if (context == null || context == PolishContext.Off) return null
            return when (reason) {
                PolishReason.POLISHED,
                PolishReason.OFF,
                PolishReason.NO_SPEECH,
                PolishReason.EMPTY_AFTER_CLEANUP,
                PolishReason.CLEANUP_RECOVERED,
                PolishReason.CANCELLED,
                -> null
                PolishReason.NO_API_KEY, PolishReason.CLOUD_NOT_CONFIGURED -> KEY_MISSING
                PolishReason.HTTP_KEY_REJECTED -> KEY_REJECTED
                PolishReason.HTTP_OUT_OF_CREDITS -> OUT_OF_CREDITS
                PolishReason.HTTP_INPUT_TOO_LONG -> INPUT_TOO_LONG
                PolishReason.HTTP_CONTENT_BLOCKED -> CONTENT_BLOCKED
                PolishReason.HTTP_ERROR -> fromStatus(statusCode, context)
                PolishReason.INVALID_CONFIGURATION,
                PolishReason.MALFORMED_RESPONSE,
                PolishReason.RESPONSE_TOO_LARGE,
                PolishReason.REDIRECT_REJECTED,
                -> BAD_REQUEST
                PolishReason.NETWORK -> UNREACHABLE
                PolishReason.TIMEOUT, PolishReason.LOCAL_TIMEOUT, PolishReason.WATCHDOG_TIMEOUT -> TIMED_OUT
                PolishReason.OUTPUT_REJECTED -> OUTPUT_REJECTED
                PolishReason.LOCAL_NOT_READY -> LOCAL_NOT_READY
                PolishReason.LOCAL_FAILED,
                PolishReason.SERVICE_UNAVAILABLE,
                PolishReason.SERVICE_DIED,
                PolishReason.CALL_FAILED,
                PolishReason.UNEXPECTED,
                -> UNEXPECTED
            }
        }

        private fun fromStatus(statusCode: Int, context: PolishContext): PolishFailure = when (statusCode) {
            401 -> KEY_REJECTED
            402 -> OUT_OF_CREDITS
            403 -> ACCESS_DENIED
            404 -> MODEL_UNAVAILABLE
            413 -> INPUT_TOO_LONG
            429 -> if (context is PolishContext.Cloud && context.provider == com.envi.wispr.providers.Provider.GEMINI) RATE_OR_QUOTA else RATE_LIMITED
            in 500..599 -> PROVIDER_ERROR
            in 400..499 -> BAD_REQUEST
            else -> UNEXPECTED
        }
    }
}
