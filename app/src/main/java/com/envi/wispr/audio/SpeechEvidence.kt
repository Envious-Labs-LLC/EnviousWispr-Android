package com.envi.wispr.audio

import com.envi.wispr.ui.TerminalReason

/**
 * Turns the take's peak loudness into the ending an EMPTY transcript gets (issue #176).
 *
 * The three answers are deliberately three: below the floor is a quiet room, above it the engine was
 * given audio and found no words, and NO reading at all stays unmeasured. A missing reading is never
 * converted to silence (macOS #1809: an exact zero is the signature of a dead channel, so a default of
 * zero would make missing data impersonate the finding).
 *
 * [PEAK_FLOOR] is PROVISIONAL: 1% of full scale, about -40 dBFS, chosen before any phone measurement.
 * Chunk C of #176 records the peak on every `dictation.terminal` row, and the floor is re-set from that
 * distribution; until then the split is a hypothesis and the knowledge file says so.
 */
internal object SpeechEvidence {
    const val PEAK_FLOOR = 0.01f

    /** [peak] is the capture process's reading, or null when it could not be read. */
    fun emptyTranscriptReason(peak: Float?): TerminalReason = when {
        peak == null -> TerminalReason.ASR_EMPTY_UNMEASURED
        peak < PEAK_FLOOR -> TerminalReason.NO_SPEECH
        else -> TerminalReason.ASR_EMPTY_DESPITE_AUDIO
    }
}
