package com.envi.wispr.insertion

/**
 * Where a take's words are when they are neither on the clipboard nor saved in History (#288), as measured when the
 * fallback line is spoken. Only [LOST] may tell the user to dictate again.
 */
internal enum class WordsKept {
    /** The rescue file for the take is durably written: the words reach History at the next start at the latest. */
    KEPT,

    /** The rescue write or the History save has not answered yet; neither has failed for certain. */
    UNCONFIRMED,

    /** The History save answered FAILED and the rescue write failed too: the words are nowhere the app holds. */
    LOST,
}
