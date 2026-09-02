package com.envi.wispr.polish

/**
 * Everything the session owner records and says about one polish outcome (#77), derived ONCE per
 * publication from the reason, the HTTP status and the latched context. [reasonToken] and [contextToken]
 * are what History stores; [notice] is what the completion surface shows, or null when there is nothing
 * to say. `DictationSessionService.publishResult` is the only caller.
 */
data class PolishPublicationFacts(
    val reasonToken: String,
    val statusCode: Int,
    val contextToken: String,
    val failure: PolishFailure?,
) {
    val notice: PolishFailureNotice? get() = failure?.let { PolishFailureNotice.notice(it, PolishContext.decode(contextToken)) }

    companion object {
        fun from(reason: PolishReason, statusCode: Int, context: PolishContext): PolishPublicationFacts = PolishPublicationFacts(
            reasonToken = reason.name,
            statusCode = statusCode,
            contextToken = context.encode(),
            failure = PolishFailure.from(reason, statusCode, context),
        )
    }
}
