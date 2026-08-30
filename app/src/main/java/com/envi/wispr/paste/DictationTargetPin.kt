package com.envi.wispr.paste

/**
 * What happened when a dictation tried to pin the editor it was started from.
 *
 * Four values, not the Boolean this replaced, because `false` folded the one case that is a fault
 * into the four entry points for which having no target is the design. The tile, the Home button,
 * onboarding practice and the side button pressed outside an editor all fail to pin against a
 * perfectly live service, and for all of them the clipboard is the destination the product intends.
 * A side button pressed INSIDE an editor while the service is dead fails to pin for a completely
 * different reason, and that one is issue #16.
 */
enum class DictationTargetPin {
    /** An editor was pinned. Insertion has somewhere to go. */
    PINNED,

    /** The service answered and there was no editor to pin. The designed clipboard case. */
    NO_TARGET,

    /** No service instance was bound when the dictation started. */
    SERVICE_NOT_RUNNING,

    /** The service was bound but its main thread did not answer within the handoff deadline. */
    SERVICE_DID_NOT_ANSWER,
}

/** Reconciles what the START of a dictation saw with what INSERTION found. */
object InsertionJudgement {
    /**
     * The handoff this dictation must be judged by, which is not always the one insertion returned.
     *
     * [InsertionHandoff.NO_PINNED_TARGET] answers two different questions with one word, and only
     * the start of the dictation can tell them apart. Android rebinds an accessibility service on
     * its own schedule, so the service can be dead when the pin is attempted and alive again by the
     * time the transcript is ready. Insertion then finds a live service and a missing pin, reports
     * the designed case, and the announcement is suppressed on the exact failure issue #16 exists
     * to end. The same reclassification arrives from the other direction when the service is
     * replaced mid-dictation: the pin succeeded, teardown cleared it, and the replacement knows
     * nothing about it.
     *
     * Whatever the start observed outranks the state insertion happened to find, so both windows
     * close. The one case left alone is the one where the service was alive at the start and said
     * there was nothing to pin.
     */
    fun handoffToJudge(
        startPin: DictationTargetPin,
        insertionHandoff: InsertionHandoff,
    ): InsertionHandoff {
        if (insertionHandoff != InsertionHandoff.NO_PINNED_TARGET) return insertionHandoff
        return when (startPin) {
            // The service answered and there was genuinely nothing to pin. Four of the five entry
            // points land here on every ordinary dictation.
            DictationTargetPin.NO_TARGET -> InsertionHandoff.NO_PINNED_TARGET
            // A target existed at the start and is gone now. `pinnedTarget` is cleared only by the
            // service's own teardown between those two moments, so the service was replaced while
            // the user was speaking. That is issue #16's acceptance case.
            DictationTargetPin.PINNED -> InsertionHandoff.SERVICE_NOT_RUNNING
            DictationTargetPin.SERVICE_NOT_RUNNING -> InsertionHandoff.SERVICE_NOT_RUNNING
            DictationTargetPin.SERVICE_DID_NOT_ANSWER -> InsertionHandoff.SERVICE_DID_NOT_ANSWER
        }
    }
}
