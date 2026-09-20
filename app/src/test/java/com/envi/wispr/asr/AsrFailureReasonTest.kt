package com.envi.wispr.asr

import com.envi.wispr.audio.SpeechEvidence
import com.envi.wispr.ui.TerminalReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift guard for the wire integers the speech process sends (issue #176). The expected table is a
 * literal: a renumbered member would make every phone on the old build report the wrong failure.
 */
class AsrFailureReasonTest {

    @Test
    fun wireCodesArePinned() {
        val pinned = mapOf(
            0 to AsrFailureReason.UNKNOWN,
            1 to AsrFailureReason.MODEL_NOT_LOADED,
            2 to AsrFailureReason.AUDIO_MISSING,
            3 to AsrFailureReason.AUDIO_UNREADABLE,
            4 to AsrFailureReason.OVER_LIMIT,
            5 to AsrFailureReason.DECODE_FAILED,
        )
        assertEquals("every member has a pinned code", pinned.size, AsrFailureReason.entries.size)
        for ((code, member) in pinned) assertEquals(member, AsrFailureReason.fromCode(code))
        for (member in AsrFailureReason.entries) assertEquals(member, pinned[member.code])
    }

    @Test
    fun aCodeThisBuildDoesNotKnowReadsAsUnknownNeverAsARealMember() {
        assertEquals(AsrFailureReason.UNKNOWN, AsrFailureReason.fromCode(99))
        assertEquals(AsrFailureReason.UNKNOWN, AsrFailureReason.fromCode(-1))
    }

    @Test
    fun anEmptyTranscriptSplitsThreeWaysOnThePeakAndNeverDefaultsToSilence() {
        assertEquals(TerminalReason.ASR_EMPTY_UNMEASURED, SpeechEvidence.emptyTranscriptReason(null))
        assertEquals(TerminalReason.NO_SPEECH, SpeechEvidence.emptyTranscriptReason(0f))
        assertEquals(TerminalReason.NO_SPEECH, SpeechEvidence.emptyTranscriptReason(0.009f))
        assertEquals(TerminalReason.ASR_EMPTY_DESPITE_AUDIO, SpeechEvidence.emptyTranscriptReason(0.01f))
        assertEquals(TerminalReason.ASR_EMPTY_DESPITE_AUDIO, SpeechEvidence.emptyTranscriptReason(0.8f))
    }
}
