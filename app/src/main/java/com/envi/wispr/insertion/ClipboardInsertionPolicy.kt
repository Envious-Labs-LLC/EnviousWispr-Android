package com.envi.wispr.insertion

/** Clipboard and editor behavior frozen for one dictation. */
data class ClipboardInsertionPolicy(
    val autoCopyToClipboard: Boolean = true,
    val restoreClipboardAfterPaste: Boolean = true,
    val smartInsertion: Boolean = true,
)
