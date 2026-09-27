package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity

/**
 * The Developer-only timings line on an open History card (#378 D8): each measured step in order, and nothing for a
 * step that was not measured, so an older row or a skipped stage never reads as zero. Polish time is on the polish
 * line already and is not repeated here.
 */
internal object HistoryTimings {
    /** Empty when the row carries no timing at all. */
    fun line(transcript: TranscriptEntity): String {
        val parts = listOfNotNull(
            transcript.liveAfterMs?.let { "live ${duration(it)}" },
            transcript.asrMs?.let { "speech ${duration(it)}" },
            transcript.insertionMs?.let { "insert ${duration(it)}" },
            transcript.endToEndMs?.let { "total ${duration(it)}" },
        )
        return if (parts.isEmpty()) "" else "Timings: " + parts.joinToString(" · ")
    }

    /** Milliseconds under a second, then seconds to one decimal: `702 ms`, `4.4 s`. */
    fun duration(ms: Long): String = if (ms < 1_000L) "$ms ms" else "%.1f s".format(java.util.Locale.US, ms / 1_000.0)
}
