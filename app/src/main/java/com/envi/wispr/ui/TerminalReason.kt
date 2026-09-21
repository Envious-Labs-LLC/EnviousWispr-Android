package com.envi.wispr.ui

/**
 * The wire-level outcome of a take: the macOS eight (`dictation.terminal.result`), plus `interrupted`
 * for a take the owner was destroyed under, plus `asr_empty` for an empty transcript with no loudness
 * reading to split it. The name IS the wire token; never rename a member without a version floor
 * (macOS RULE: enum-backed-properties-carry-retired-vocabularies-split-by-version).
 */
internal enum class TerminalResult(val wire: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DISCARDED("discarded"),
    NO_SPEECH("no_speech"),
    AUDIO_INTERRUPTED("audio_interrupted"),
    ASR_INTERRUPTED("asr_interrupted"),
    ASR_EMPTY_DESPITE_AUDIO("asr_empty_despite_audio"),
    ASR_EMPTY("asr_empty"),
    INTERRUPTED("interrupted"),
}

/**
 * Every way a take ends, one member per route through `DictationSessionService` (issue #176, plan
 * §3.6; the Codex trace of 2026-09-19 counted 36 routes). One closed vocabulary, one home.
 *
 * Why this exists: before it, a failure was identified by the SENTENCE shown to the user, chosen at
 * the failing call site, so the same failure had as many identities as call sites and nothing could be
 * counted. macOS shipped exactly that and a query on the token form undercounted 13x
 * (`pipeline.failed.error_code`, #1714). Here the member is the identity; the sentence is derived from
 * it in ONE place (`TakeNotices`), so copy and telemetry can never disagree.
 *
 * `result` is what leaves the phone as `dictation.terminal.result`. Only [TerminalResult.FAILED]
 * members carry `reason` on the wire, and that reason is this member's name.
 */
internal enum class TerminalReason(val result: TerminalResult) {
    // Endings with words, or without a fault.
    COMPLETED(TerminalResult.COMPLETED),
    CANCELLED_STARTING(TerminalResult.CANCELLED),
    CANCELLED_RECORDING(TerminalResult.CANCELLED),
    CANCELLED_PROCESSING(TerminalResult.CANCELLED),

    /** Empty transcript and the take's peak loudness was below the floor: a quiet room. */
    NO_SPEECH(TerminalResult.NO_SPEECH),

    /** Empty transcript and the peak was above the floor: audio arrived, the engine found no words. */
    ASR_EMPTY_DESPITE_AUDIO(TerminalResult.ASR_EMPTY_DESPITE_AUDIO),

    /** Empty transcript and no peak reading: unknown, and never converted to "silence". */
    ASR_EMPTY_UNMEASURED(TerminalResult.ASR_EMPTY),

    /** Both the polished and the raw text were blank at publication. */
    FINAL_TEXT_EMPTY(TerminalResult.DISCARDED),

    // The owner was destroyed with a take open (`onDestroy`).
    INTERRUPTED_STARTING(TerminalResult.INTERRUPTED),
    INTERRUPTED_RECORDING(TerminalResult.INTERRUPTED),
    INTERRUPTED_PROCESSING(TerminalResult.INTERRUPTED),
    INTERRUPTED_CANCELLING(TerminalResult.INTERRUPTED),

    // A pipeline process went away mid-take. Three members, never one shared "service died".
    AUDIO_PROCESS_DIED(TerminalResult.AUDIO_INTERRUPTED),
    ASR_PROCESS_DIED(TerminalResult.ASR_INTERRUPTED),
    POLISH_PROCESS_DIED(TerminalResult.FAILED),

    // Failures before capture. A settings or vocabulary read that fails is not one (#193): the take
    // starts on the last values and the failure is a fact of the take.
    AUDIO_BIND_FAILED(TerminalResult.FAILED),
    ASR_BIND_FAILED(TerminalResult.FAILED),
    POLISH_BIND_FAILED(TerminalResult.FAILED),
    CAPTURE_START_NO_MICROPHONE(TerminalResult.FAILED),
    CAPTURE_START_EARBUDS_REFUSED(TerminalResult.FAILED),
    CAPTURE_START_FAILED(TerminalResult.FAILED),
    START_EXCEPTION(TerminalResult.FAILED),
    CAPTURE_ENDED_BEFORE_LIVE(TerminalResult.FAILED),
    LIVE_WAIT_DEADLINE(TerminalResult.FAILED),

    // Failures during capture.
    /** `CaptureEnding.Failure`: the capture process reported an error ending. */
    CAPTURE_FAILED_MID_TAKE(TerminalResult.AUDIO_INTERRUPTED),

    /** `CaptureEnding.StillRunning` after the process said it stopped: our own protocol violation. */
    CAPTURE_STILL_RUNNING_AFTER_STOP(TerminalResult.FAILED),

    // Failures at stop.
    CAPTURE_CLOSE_UNSAFE(TerminalResult.FAILED),
    CAPTURE_CLOSE_UNSAFE_ON_CANCEL(TerminalResult.FAILED),
    AUDIO_FILE_MISSING(TerminalResult.FAILED),

    // Failures in transcription.
    ASR_NOT_READY(TerminalResult.FAILED),

    /** The speech process reported a failure; carries `AsrFailureReason` from chunk A3 on. */
    ASR_FAILED(TerminalResult.FAILED),
    ASR_CALLBACK_EXCEPTION(TerminalResult.FAILED),
}
