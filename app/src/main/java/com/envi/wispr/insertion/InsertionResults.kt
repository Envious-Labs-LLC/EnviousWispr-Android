package com.envi.wispr.insertion

import com.envi.wispr.history.TranscriptEntity

/**
 * Every value the app writes to `TranscriptEntity.insertionResult` on a row whose status is
 * `STATUS_INSERTION_INTERRUPTED`.
 *
 * One home, because the History sentence is chosen by matching this population and the population
 * had three writers, not two: the session owner, the accessibility service, and the DAO's
 * stale-row recovery. `InsertionOutcomeMessagesTest` enumerates this object by reflection, so a
 * constant added here without a sentence fails the fast gate instead of reaching a user as
 * `Status: insertion interrupted`.
 */
object InsertionResults {

    /** The session owner wrote the words to the clipboard itself; the service was never reached. */
    const val CLIPBOARD = "clipboard"

    /** The service tried, gave up, and the clipboard took the words. */
    const val COPY_ONLY = "copy_only"

    /** The service was interrupted by the platform mid-insertion. */
    const val COPY_ONLY_INTERRUPTED = "copy_only_interrupted"

    /** The service was destroyed with an insertion still pending. */
    const val COPY_ONLY_SERVICE_DESTROYED = "copy_only_service_destroyed"

    /** Insertion was refused for a password or otherwise sensitive field. */
    const val COPY_ONLY_SENSITIVE = "copy_only_sensitive"

    /**
     * The editor action RAN and the change could not be read back. The words may well be in the
     * field. Any sentence for this value has to hedge, which is why it cannot share a bucket with
     * the outcomes above.
     */
    const val COPY_ONLY_UNVERIFIED = "copy_only_unverified"

    /**
     * The editor action RAN, could not be read back, AND the clipboard write failed. Same hedge as
     * `COPY_ONLY_UNVERIFIED`, without the clipboard. Folding this into `INSERTION_FAILED` made the
     * History row say "Not inserted" while the notification for the same event said the insertion
     * could not be confirmed.
     */
    const val UNVERIFIED_NOT_COPIED = "unverified_not_copied"

    /** Auto-copy is off, so the words reached History and nowhere else. */
    const val HISTORY_ONLY = "history_only"

    /** Nothing took the words except History, because the clipboard write itself failed. */
    const val INSERTION_FAILED = "insertion_failed"

    /**
     * Written by `TranscriptDao.recoverStaleReadyRows` when a process died between the transcript
     * being ready and any insertion outcome being recorded. Where the words went is unknown.
     */
    const val INSERTION_INTERRUPTED = TranscriptEntity.STATUS_INSERTION_INTERRUPTED

    /**
     * The durable record for a service-side fallback, from the SAME two facts the toast and the
     * notification are built from.
     *
     * Exhaustive over [ServiceFallbackReason], so a new outcome cannot reach History as a value
     * chosen from a partial view of the event. That is what happened to the unverified case: the
     * hedge was carried only on the branch where the clipboard also took the words, so a failed
     * copy silently downgraded "could not be confirmed" to "not inserted".
     *
     * The teardown writers (`onInterrupt`, `onDestroy`) come through here too. Their constants say
     * which teardown happened rather than which attempt failed, and routing them through this one
     * function is what put them on the same announcement path as every other accepted insertion
     * that missed the field.
     */
    fun forServiceFallback(reason: ServiceFallbackReason, clipboard: ClipboardOutcome): String {
        val copied = clipboard == ClipboardOutcome.COPIED
        return when (reason) {
            ServiceFallbackReason.SENSITIVE_FIELD ->
                if (copied) COPY_ONLY_SENSITIVE else INSERTION_FAILED
            ServiceFallbackReason.UNVERIFIED ->
                if (copied) COPY_ONLY_UNVERIFIED else UNVERIFIED_NOT_COPIED
            ServiceFallbackReason.SERVICE_INTERRUPTED ->
                if (copied) COPY_ONLY_INTERRUPTED else INSERTION_FAILED
            ServiceFallbackReason.SERVICE_DESTROYED ->
                if (copied) COPY_ONLY_SERVICE_DESTROYED else INSERTION_FAILED
            ServiceFallbackReason.TARGET_NEVER_RETURNED,
            ServiceFallbackReason.NO_INSERTION_ACTION,
            -> if (copied) COPY_ONLY else INSERTION_FAILED
        }
    }
}
