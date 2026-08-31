package com.envi.wispr.insertion

/**
 * Why the accessibility service, which was alive and had accepted the text, did not place it in
 * the user's field.
 *
 * Separate from `InsertionHandoff`, which answers whether the text was ever accepted at all. These
 * six are the terminal outcomes of an accepted insertion, and every one of them owes the user a
 * sentence saying where the words went instead. The last two are the teardown pair: they used to
 * copy the words to the clipboard and say nothing at all, which is the silence issue #16 reported,
 * reached from the one direction where the service dies with the words still in its hands.
 */
enum class ServiceFallbackReason {
    /** The target field refused text by our own rule. Removed with issue #11. */
    SENSITIVE_FIELD,

    /** The editor action RAN and the change could not be read back, so the field may hold it. */
    UNVERIFIED,

    /** The pinned editor never came back before the retry deadline. */
    TARGET_NEVER_RETURNED,

    /** The node offered no safe insertion action. */
    NO_INSERTION_ACTION,

    /** Android interrupted the service with an insertion still pending. */
    SERVICE_INTERRUPTED,

    /** The service is being destroyed with an insertion still pending. */
    SERVICE_DESTROYED,
}
