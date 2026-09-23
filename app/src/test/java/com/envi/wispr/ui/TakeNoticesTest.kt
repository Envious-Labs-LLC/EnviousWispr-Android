package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the sentences a take can end on (issue #176). When this fails, a user reads a
 * different sentence than they did before the closed vocabulary existed, or an ending that was silent
 * starts talking. Every expected value is a literal copied from the session owner as it stood on
 * 2026-09-19, never derived from the mapper under test.
 */
class TakeNoticesTest {

    /** Literal sentences per member; a member absent here fails the exhaustiveness test below. */
    private val frozen: Map<TerminalReason, String?> = mapOf(
        TerminalReason.COMPLETED to null,
        TerminalReason.CANCELLED_STARTING to null,
        TerminalReason.CANCELLED_RECORDING to null,
        TerminalReason.CANCELLED_PROCESSING to null,
        TerminalReason.NO_SPEECH to null,
        TerminalReason.ASR_EMPTY_DESPITE_AUDIO to null,
        TerminalReason.ASR_EMPTY_UNMEASURED to null,
        TerminalReason.FINAL_TEXT_EMPTY to null,
        TerminalReason.INTERRUPTED_STARTING to null,
        TerminalReason.INTERRUPTED_RECORDING to null,
        TerminalReason.INTERRUPTED_PROCESSING to null,
        TerminalReason.INTERRUPTED_CANCELLING to null,
        TerminalReason.AUDIO_PROCESS_DIED to "Microphone service stopped unexpectedly",
        TerminalReason.AUDIO_PROCESS_UNRESPONSIVE to "The microphone stopped answering. Try again.",
        TerminalReason.ASR_PROCESS_DIED to "Speech service stopped before transcription finished",
        TerminalReason.POLISH_PROCESS_DIED to "Polish service stopped before cleanup finished",
        TerminalReason.AUDIO_BIND_FAILED to "Microphone service could not be connected",
        TerminalReason.ASR_BIND_FAILED to "Speech service could not be connected",
        TerminalReason.POLISH_BIND_FAILED to "Polish service could not be connected",
        TerminalReason.CAPTURE_START_NO_MICROPHONE to "No microphone found. Please connect one.",
        TerminalReason.CAPTURE_START_EARBUDS_REFUSED to "Earbuds could not be used.",
        TerminalReason.CAPTURE_START_FAILED to "Microphone capture could not start safely",
        TerminalReason.START_EXCEPTION to "Failed to start recording",
        TerminalReason.CAPTURE_ENDED_BEFORE_LIVE to "Microphone capture stopped unexpectedly. Try again.",
        TerminalReason.LIVE_WAIT_DEADLINE to "Microphone capture could not start safely",
        TerminalReason.CAPTURE_FAILED_MID_TAKE to "Microphone capture stopped unexpectedly. Try again.",
        TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP to "Microphone capture stopped unexpectedly. Try again.",
        TerminalReason.CAPTURE_CLOSE_UNSAFE to "Audio capture did not finish safely. Try again.",
        TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL to "Audio capture did not finish safely. Try again.",
        TerminalReason.AUDIO_FILE_MISSING to "No audio captured",
        TerminalReason.ASR_NOT_READY to "Speech model is still loading. Try again in a moment.",
        TerminalReason.ASR_FAILED to "Speech recognition failed",
        TerminalReason.ASR_CALLBACK_EXCEPTION to "Transcription failed",
    )

    @Test
    fun everyEndingHasExactlyTheSentenceItHadBefore() {
        for (reason in TerminalReason.entries) {
            assertTrue("$reason is not in the frozen table", frozen.containsKey(reason))
            assertEquals("$reason", frozen[reason], TakeNotices.line(reason))
        }
    }

    @Test
    fun onlyFailedEndingsSpeakAndEveryFailedEndingSpeaks() {
        for (reason in TerminalReason.entries) {
            val speaks = TakeNotices.line(reason) != null
            // A failure the app survived tells the user; every other ending is acknowledged by the
            // haptic and the overlay, or is a quiet room, or is a death nobody could announce.
            val shouldSpeak = when (reason.result) {
                TerminalResult.FAILED, TerminalResult.AUDIO_INTERRUPTED, TerminalResult.ASR_INTERRUPTED -> true
                TerminalResult.COMPLETED, TerminalResult.CANCELLED, TerminalResult.DISCARDED, TerminalResult.NO_SPEECH,
                TerminalResult.ASR_EMPTY_DESPITE_AUDIO, TerminalResult.ASR_EMPTY, TerminalResult.INTERRUPTED -> false
            }
            assertEquals("$reason", shouldSpeak, speaks)
        }
    }

    @Test
    fun wireResultTokensAreTheMacEightPlusInterruptedAndUnmeasured() {
        val expected = listOf(
            "completed", "failed", "cancelled", "discarded", "no_speech", "audio_interrupted", "asr_interrupted",
            "asr_empty_despite_audio", "asr_empty", "interrupted",
        )
        assertEquals(expected, TerminalResult.entries.map { it.wire })
    }

    @Test
    fun theSessionOwnerNeverAuthorsAnEndingSentence() {
        // The identity is the member; a literal at a call site is the old shape coming back.
        // Every file of the session owner (#216): the controller's events reach these calls too.
        val source = SessionSources.all
        for (call in listOf("showError(\"", "failWhileStarting(\"", "handleServiceFailure(\"", "announceError(\"")) {
            assertTrue("found $call", !source.contains(call))
        }
        assertNull(TakeNotices.line(TerminalReason.COMPLETED))
    }
}
