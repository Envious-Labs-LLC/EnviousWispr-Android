package com.envi.wispr.insertion

/**
 * What happened to the clipboard copy of one dictation.
 *
 * Three values, not a Boolean, because the two ways of not being on the clipboard mean opposite
 * things to the user. A copy that was ATTEMPTED and did not happen is a fault: the destination the
 * code aimed at is empty and the user will press and hold and get somebody else's clip. A copy that
 * was never attempted is the user's own setting (`ClipboardInsertionPolicy.autoCopyToClipboard`
 * off), which makes History the destination and the dictation a success.
 *
 * Collapsed into one Boolean, the first case was silent for a user who had also declined
 * auto-paste, and the second buzzed and posted a notification on every working dictation.
 */
enum class ClipboardOutcome {
    /** The words are on the system clipboard. */
    COPIED,

    /** A copy was made for this dictation and the clipboard did not take it. */
    WRITE_FAILED,

    /** No copy was made, because the user turned auto-copy off. History is the destination. */
    NOT_ATTEMPTED,
}
