package com.envi.wispr.polish

/**
 * What the completion surface says for a polish that did not do its job (#77). Private constructor, one
 * factory, so there is one surface to keep honest. Two parts, because a toast on this phone shows two
 * lines and truncates the rest (measured 2026-09-01 on the S26 Ultra: "AI polish failed: Gemini rejected
 * your ..."): [toastLine] is the locked product sentence (catalog decision `b7-decision-polish-warning-copy`,
 * 2026-07-15), shown for a hard failure and never for a skip; [title] and [detail] are the lead-in and the
 * full reason, carried by a silent notification the user can read at leisure and tap to open the app.
 */
class PolishFailureNotice private constructor(
    val toastLine: String,
    val title: String,
    val detail: String,
) {
    /** The whole notice as one string, for logs and tests. */
    val text: String get() = if (toastLine == LOCKED_SENTENCE) "$toastLine\n$title $detail" else "$title $detail"

    companion object {
        const val LOCKED_SENTENCE = "Polish failed. Using raw text."

        /**
         * A skip has no locked sentence, so its toast is the reason line itself: an immediate cue even
         * when the notification cannot be posted (permission denied), truncated by the toast if long,
         * complete in the notification and in History.
         */
        fun notice(failure: PolishFailure, context: PolishContext?): PolishFailureNotice {
            val detail = failure.message(context)
            return PolishFailureNotice(
                toastLine = when (failure.leadIn) {
                    PolishFailure.LeadIn.FAILED -> LOCKED_SENTENCE
                    PolishFailure.LeadIn.SKIPPED -> "${failure.leadIn.text} $detail"
                },
                title = failure.leadIn.text,
                detail = detail,
            )
        }
    }
}
