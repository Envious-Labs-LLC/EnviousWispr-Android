package com.envi.wispr.paste

/**
 * Why a dictation did or did not reach the accessibility insertion path.
 *
 * Every non-scheduled case ends on the clipboard, which is why they were indistinguishable while
 * this was a Boolean. They need different diagnoses and, for the dead-service case, different copy.
 */
enum class InsertionHandoff {
    /** The service accepted the text and is waiting for the pinned editor to return. */
    SCHEDULED,

    /** No service instance is bound. The crash case: the Android setting still names us. */
    SERVICE_NOT_RUNNING,

    /** The service is alive but nothing was pinned when the dictation started. */
    NO_PINNED_TARGET,

    /** A previous insertion is still retrying, so this one cannot replace it. */
    INSERTION_ALREADY_PENDING,

    /** There was nothing to insert. */
    EMPTY_TEXT,

    /** The service is bound but its main thread did not answer within the handoff deadline. */
    SERVICE_DID_NOT_ANSWER,

    /** Insertion was never requested because the transcript has no durable history row. */
    HISTORY_NOT_DURABLE,
}
