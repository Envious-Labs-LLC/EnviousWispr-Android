package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.ui.TerminalReason

/**
 * Where a condition goes: a Sentry ERROR only when our code owns the cause; everything else a
 * breadcrumb plus its analytics row (founder 2026-07-09: "Sentry for bugs, PostHog for behaviour";
 * issue #176 plan §3.6 is the literal table `TelemetryChannelsTest` compares against).
 *
 * Every `when` here is exhaustive with no `else`, so a new member of any vocabulary fails to compile
 * until someone decides its channel. No channel is inferred from a sentence or a member's name.
 */
enum class Channel { DEFECT, BREADCRUMB }

/**
 * The closed reading of `TranscriptEntity.insertionResult`, whose stored values are the string
 * constants in [InsertionResults]. `UNKNOWN` is what a historical or foreign string parses to and is
 * never a defect.
 */
enum class InsertionResultKind(val stored: String) {
    CLIPBOARD(InsertionResults.CLIPBOARD),
    PASTED(InsertionResults.PASTED),
    COMMITTED(InsertionResults.COMMITTED),
    COPY_ONLY(InsertionResults.COPY_ONLY),
    COPY_ONLY_INTERRUPTED(InsertionResults.COPY_ONLY_INTERRUPTED),
    COPY_ONLY_SERVICE_DESTROYED(InsertionResults.COPY_ONLY_SERVICE_DESTROYED),
    COPY_ONLY_SENSITIVE(InsertionResults.COPY_ONLY_SENSITIVE),
    COPY_ONLY_UNVERIFIED(InsertionResults.COPY_ONLY_UNVERIFIED),
    UNVERIFIED_NOT_COPIED(InsertionResults.UNVERIFIED_NOT_COPIED),
    HISTORY_ONLY(InsertionResults.HISTORY_ONLY),
    INSERTION_FAILED(InsertionResults.INSERTION_FAILED),
    INSERTION_INTERRUPTED(InsertionResults.INSERTION_INTERRUPTED),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromStored(value: String?): InsertionResultKind =
            entries.firstOrNull { it != UNKNOWN && it.stored == value } ?: UNKNOWN
    }
}

object TelemetryChannels {

    fun of(reason: PolishReason): Channel = when (reason) {
        PolishReason.POLISHED, PolishReason.OFF, PolishReason.NO_SPEECH, PolishReason.EMPTY_AFTER_CLEANUP,
        PolishReason.TOO_SHORT, PolishReason.LOCAL_NOT_READY, PolishReason.OUTPUT_REJECTED,
        PolishReason.CLOUD_NOT_CONFIGURED, PolishReason.NO_API_KEY, PolishReason.NETWORK, PolishReason.TIMEOUT,
        PolishReason.CANCELLED, PolishReason.HTTP_ERROR, PolishReason.HTTP_KEY_REJECTED,
        PolishReason.HTTP_OUT_OF_CREDITS, PolishReason.HTTP_INPUT_TOO_LONG, PolishReason.HTTP_CONTENT_BLOCKED,
        PolishReason.INVALID_CONFIGURATION, PolishReason.MALFORMED_RESPONSE, PolishReason.RESPONSE_TOO_LARGE,
        PolishReason.REDIRECT_REJECTED, PolishReason.SERVICE_UNAVAILABLE, PolishReason.SERVICE_DIED,
        PolishReason.CALL_FAILED,
        -> Channel.BREADCRUMB

        PolishReason.CLEANUP_RECOVERED, PolishReason.LOCAL_FAILED, PolishReason.LOCAL_TIMEOUT,
        PolishReason.UNEXPECTED, PolishReason.WATCHDOG_TIMEOUT,
        -> Channel.DEFECT
    }

    /** The defect a DEFECT-channel polish reason raises; null for the breadcrumb members. */
    fun defectOf(reason: PolishReason): AppDefect? = when (reason) {
        PolishReason.CLEANUP_RECOVERED -> AppDefect.CleanupRecovered
        PolishReason.LOCAL_FAILED -> AppDefect.LocalPolishFailed
        PolishReason.LOCAL_TIMEOUT -> AppDefect.LocalPolishDeadline
        PolishReason.UNEXPECTED -> AppDefect.PolishUnexpected
        PolishReason.WATCHDOG_TIMEOUT -> AppDefect.PolishWatchdogTimeout
        PolishReason.POLISHED, PolishReason.OFF, PolishReason.NO_SPEECH, PolishReason.EMPTY_AFTER_CLEANUP,
        PolishReason.TOO_SHORT, PolishReason.LOCAL_NOT_READY, PolishReason.OUTPUT_REJECTED,
        PolishReason.CLOUD_NOT_CONFIGURED, PolishReason.NO_API_KEY, PolishReason.NETWORK, PolishReason.TIMEOUT,
        PolishReason.CANCELLED, PolishReason.HTTP_ERROR, PolishReason.HTTP_KEY_REJECTED,
        PolishReason.HTTP_OUT_OF_CREDITS, PolishReason.HTTP_INPUT_TOO_LONG, PolishReason.HTTP_CONTENT_BLOCKED,
        PolishReason.INVALID_CONFIGURATION, PolishReason.MALFORMED_RESPONSE, PolishReason.RESPONSE_TOO_LARGE,
        PolishReason.REDIRECT_REJECTED, PolishReason.SERVICE_UNAVAILABLE, PolishReason.SERVICE_DIED,
        PolishReason.CALL_FAILED,
        -> null
    }

    fun of(kind: InsertionResultKind): Channel = when (kind) {
        InsertionResultKind.CLIPBOARD, InsertionResultKind.PASTED, InsertionResultKind.COMMITTED,
        InsertionResultKind.COPY_ONLY, InsertionResultKind.COPY_ONLY_INTERRUPTED,
        InsertionResultKind.COPY_ONLY_SERVICE_DESTROYED, InsertionResultKind.COPY_ONLY_SENSITIVE,
        InsertionResultKind.COPY_ONLY_UNVERIFIED, InsertionResultKind.UNVERIFIED_NOT_COPIED,
        InsertionResultKind.HISTORY_ONLY, InsertionResultKind.INSERTION_FAILED,
        InsertionResultKind.INSERTION_INTERRUPTED, InsertionResultKind.UNKNOWN,
        -> Channel.BREADCRUMB
    }

    fun of(handoff: InsertionHandoff): Channel = when (handoff) {
        InsertionHandoff.SCHEDULED, InsertionHandoff.SERVICE_NOT_RUNNING, InsertionHandoff.NO_PINNED_TARGET,
        InsertionHandoff.INSERTION_ALREADY_PENDING, InsertionHandoff.EMPTY_TEXT,
        InsertionHandoff.SERVICE_DID_NOT_ANSWER, InsertionHandoff.HISTORY_NOT_DURABLE,
        -> Channel.BREADCRUMB
    }

    fun of(reason: AsrFailureReason): Channel = when (reason) {
        AsrFailureReason.UNKNOWN, AsrFailureReason.MODEL_NOT_LOADED, AsrFailureReason.AUDIO_MISSING,
        AsrFailureReason.AUDIO_UNREADABLE,
        -> Channel.BREADCRUMB

        AsrFailureReason.OVER_LIMIT, AsrFailureReason.DECODE_FAILED -> Channel.DEFECT
    }

    fun defectOf(reason: AsrFailureReason): AppDefect? = when (reason) {
        AsrFailureReason.OVER_LIMIT -> AppDefect.AsrOverLimit
        AsrFailureReason.DECODE_FAILED -> AppDefect.AsrDecodeFailed(null)
        AsrFailureReason.UNKNOWN, AsrFailureReason.MODEL_NOT_LOADED, AsrFailureReason.AUDIO_MISSING,
        AsrFailureReason.AUDIO_UNREADABLE,
        -> null
    }

    /**
     * A take's ending. `ASR_FAILED` defers to the ASR table (its own `asr_failure_reason` decides);
     * `CAPTURE_STILL_RUNNING_AFTER_STOP` is our protocol. Everything else is the world, or a choice.
     */
    fun of(reason: TerminalReason, asrFailure: AsrFailureReason?): Channel = when (reason) {
        TerminalReason.COMPLETED, TerminalReason.CANCELLED_STARTING, TerminalReason.CANCELLED_RECORDING,
        TerminalReason.CANCELLED_PROCESSING, TerminalReason.NO_SPEECH, TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
        TerminalReason.ASR_EMPTY_UNMEASURED, TerminalReason.FINAL_TEXT_EMPTY, TerminalReason.INTERRUPTED_STARTING,
        TerminalReason.INTERRUPTED_RECORDING, TerminalReason.INTERRUPTED_PROCESSING,
        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.ASR_PROCESS_DIED,
        TerminalReason.POLISH_PROCESS_DIED, TerminalReason.SETTINGS_UNAVAILABLE, TerminalReason.AUDIO_BIND_FAILED,
        TerminalReason.ASR_BIND_FAILED, TerminalReason.POLISH_BIND_FAILED, TerminalReason.CAPTURE_START_NO_MICROPHONE,
        TerminalReason.CAPTURE_START_EARBUDS_REFUSED, TerminalReason.CAPTURE_START_FAILED,
        TerminalReason.START_EXCEPTION, TerminalReason.CAPTURE_ENDED_BEFORE_LIVE, TerminalReason.LIVE_WAIT_DEADLINE,
        TerminalReason.CAPTURE_FAILED_MID_TAKE, TerminalReason.CAPTURE_CLOSE_UNSAFE,
        TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL, TerminalReason.AUDIO_FILE_MISSING, TerminalReason.ASR_NOT_READY,
        TerminalReason.ASR_CALLBACK_EXCEPTION,
        -> Channel.BREADCRUMB

        TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP -> Channel.DEFECT
        TerminalReason.ASR_FAILED -> asrFailure?.let { of(it) } ?: Channel.BREADCRUMB
    }

    fun defectOf(reason: TerminalReason, asrFailure: AsrFailureReason?): AppDefect? = when (reason) {
        TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP -> AppDefect.CaptureStillRunningAfterStop
        TerminalReason.ASR_FAILED -> asrFailure?.let { defectOf(it) }
        TerminalReason.COMPLETED, TerminalReason.CANCELLED_STARTING, TerminalReason.CANCELLED_RECORDING,
        TerminalReason.CANCELLED_PROCESSING, TerminalReason.NO_SPEECH, TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
        TerminalReason.ASR_EMPTY_UNMEASURED, TerminalReason.FINAL_TEXT_EMPTY, TerminalReason.INTERRUPTED_STARTING,
        TerminalReason.INTERRUPTED_RECORDING, TerminalReason.INTERRUPTED_PROCESSING,
        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.ASR_PROCESS_DIED,
        TerminalReason.POLISH_PROCESS_DIED, TerminalReason.SETTINGS_UNAVAILABLE, TerminalReason.AUDIO_BIND_FAILED,
        TerminalReason.ASR_BIND_FAILED, TerminalReason.POLISH_BIND_FAILED, TerminalReason.CAPTURE_START_NO_MICROPHONE,
        TerminalReason.CAPTURE_START_EARBUDS_REFUSED, TerminalReason.CAPTURE_START_FAILED,
        TerminalReason.START_EXCEPTION, TerminalReason.CAPTURE_ENDED_BEFORE_LIVE, TerminalReason.LIVE_WAIT_DEADLINE,
        TerminalReason.CAPTURE_FAILED_MID_TAKE, TerminalReason.CAPTURE_CLOSE_UNSAFE,
        TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL, TerminalReason.AUDIO_FILE_MISSING, TerminalReason.ASR_NOT_READY,
        TerminalReason.ASR_CALLBACK_EXCEPTION,
        -> null
    }
}
