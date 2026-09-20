package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService

/**
 * The ONE place a take's ending becomes the sentence the user reads. The twin of macOS
 * `DictationNarrator`: the session owner names the [TerminalReason], never the words, so the copy and
 * the telemetry identity can never disagree (issue #176).
 *
 * Every sentence here is today's sentence, byte for byte, moved from the call site that used to own it.
 * `TakeNoticesTest` freezes them as literals. A member that returns null ends the take silently on
 * purpose: a cancel is acknowledged by the haptic and the overlay closing, and a quiet room is not an
 * event worth reporting (founder, 2026-08-31).
 */
object TakeNotices {

    /**
     * Today's line for every other start failure, unchanged. Also the live-wait deadline's line:
     * the route never delivered sound, which to the user is the same "could not start".
     */
    private const val CAPTURE_STOPPED = "Microphone capture stopped unexpectedly. Try again."
    private const val CLOSE_UNSAFE = "Audio capture did not finish safely. Try again."

    /**
     * The speech process's own error text used to be shown as the toast. From chunk A3 the process
     * reports a code instead, and this is the one approved sentence for every code (G2 review: vendor
     * prose is never copy). It is the fallback the owner already used for a blank message.
     */
    const val SPEECH_RECOGNITION_FAILED = "Speech recognition failed"

    /** The sentence for an ending, or null when the ending says nothing. Exhaustive: no `else`. */
    fun line(reason: TerminalReason): String? = when (reason) {
        TerminalReason.COMPLETED,
        TerminalReason.CANCELLED_STARTING,
        TerminalReason.CANCELLED_RECORDING,
        TerminalReason.CANCELLED_PROCESSING,
        TerminalReason.NO_SPEECH,
        TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
        TerminalReason.ASR_EMPTY_UNMEASURED,
        TerminalReason.FINAL_TEXT_EMPTY,
        TerminalReason.INTERRUPTED_STARTING,
        TerminalReason.INTERRUPTED_RECORDING,
        TerminalReason.INTERRUPTED_PROCESSING,
        TerminalReason.INTERRUPTED_CANCELLING,
        -> null

        TerminalReason.AUDIO_PROCESS_DIED -> "Microphone service stopped unexpectedly"
        TerminalReason.ASR_PROCESS_DIED -> "Speech service stopped before transcription finished"
        TerminalReason.POLISH_PROCESS_DIED -> "Polish service stopped before cleanup finished"

        TerminalReason.SETTINGS_UNAVAILABLE -> "Settings could not be loaded. Try again."
        TerminalReason.AUDIO_BIND_FAILED -> "Microphone service could not be connected"
        TerminalReason.ASR_BIND_FAILED -> "Speech service could not be connected"
        TerminalReason.POLISH_BIND_FAILED -> "Polish service could not be connected"
        TerminalReason.CAPTURE_START_NO_MICROPHONE -> CaptureNotices.NO_MICROPHONE
        TerminalReason.CAPTURE_START_EARBUDS_REFUSED -> CaptureNotices.EARBUDS_UNUSABLE
        TerminalReason.CAPTURE_START_FAILED -> CaptureNotices.START_FAILED
        TerminalReason.START_EXCEPTION -> "Failed to start recording"
        TerminalReason.CAPTURE_ENDED_BEFORE_LIVE -> CAPTURE_STOPPED
        TerminalReason.LIVE_WAIT_DEADLINE -> CaptureNotices.START_FAILED

        TerminalReason.CAPTURE_FAILED_MID_TAKE,
        TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP,
        -> CAPTURE_STOPPED

        TerminalReason.CAPTURE_CLOSE_UNSAFE,
        TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL,
        -> CLOSE_UNSAFE

        TerminalReason.AUDIO_FILE_MISSING -> "No audio captured"
        TerminalReason.ASR_NOT_READY -> "Speech model is still loading. Try again in a moment."
        TerminalReason.ASR_FAILED -> SPEECH_RECOGNITION_FAILED
        TerminalReason.ASR_CALLBACK_EXCEPTION -> "Transcription failed"
    }

    /**
     * Which ending a refused capture start is, decided from the code the capture process reports over
     * the binder (`IAudioCaptureService.getLastStartFailure`), never from a display label. The same
     * table `CaptureNotices.startFailureLine` used to hold; the sentence now comes from [line].
     */
    fun startFailureReason(lastStartFailure: Int): TerminalReason = when (lastStartFailure) {
        AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE -> TerminalReason.CAPTURE_START_NO_MICROPHONE
        AudioCaptureService.START_FAILURE_EARBUDS -> TerminalReason.CAPTURE_START_EARBUDS_REFUSED
        else -> TerminalReason.CAPTURE_START_FAILED
    }
}
