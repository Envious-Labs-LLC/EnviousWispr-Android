package com.envi.wispr.history

import com.envi.wispr.insertion.WordsKept

/**
 * A take's History row as insertion sees it (#277): a handle, never an id read up front. Insertion is the
 * heart and History a limb (`architecture-rules.md` FACT: heart-and-limbs), so the words are handed to
 * insertion before the save answers, and every write of an insertion outcome resolves the row on the History
 * queue, where it runs behind the save of the same row.
 */
internal interface HistoryRow {
    /**
     * The saved row's id, or 0 when the save failed or there is no row. Call only inside a write queued on the
     * History queue: there the take's save has already run, so the answer is final.
     */
    fun resolveOnQueue(): Long

    /** Whether the save has answered with a saved row by now; false while it is pending or after it failed. */
    val savedNow: Boolean

    /** Where the words are held when not saved (#288), measured now: read only when [savedNow] is false. */
    fun wordsKept(): WordsKept

    /** No row: a debug probe's insertion. Nothing was rescued for it. */
    object None : HistoryRow {
        override fun resolveOnQueue(): Long = 0L
        override val savedNow: Boolean = false
        override fun wordsKept(): WordsKept = WordsKept.LOST
    }
}
