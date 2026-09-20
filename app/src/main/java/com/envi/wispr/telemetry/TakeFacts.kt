package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.CaptureEnding
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource

/**
 * What the session owner learned about ONE take, written as each fact becomes known and read once, at
 * the arbiter's commit, into the `dictation.terminal` row (issue #176, plan §3.1). Every field starts
 * null and stays null when its stage never ran: an absent property means "not measured", never zero.
 *
 * Every value is already a closed token, a number or a boolean. No sentence, label or transcript can
 * be stored here: the capture device's display name and the polish engine's label have no field.
 */
class TakeFacts(val takeId: String, val trigger: TriggerSource) {
    @Volatile var routeKind: InputRouteKind? = null
    @Volatile var routeReason: InputRouteReason? = null
    @Volatile var liveAfterMs: Long? = null
    /** `ready` or `forced` (the take proceeded on earbuds that sent nothing). */
    @Volatile var liveState: String? = null
    /** The silence detector's last status for the take, as a token; see [silenceStatusToken]. */
    @Volatile var silenceStopStatus: String? = null
    /** How the capture process ended the take, as a token; see [captureEndingToken]. */
    @Volatile var captureTerminal: String? = null
    @Volatile var recordingSeconds: Double? = null
    /** `auto` or `picked`: whether the user chose a microphone. The product name never leaves. */
    @Volatile var inputDevice: String? = null
    @Volatile var asrMs: Long? = null
    @Volatile var asrChars: Int? = null
    @Volatile var peakAmplitude: Float? = null
    /** `PolishContext.encode()`: `off`, `local`, `cloud-unconfigured`, `cloud:<PROVIDER>[:ollama]`. */
    @Volatile var polishProvider: String? = null
    @Volatile var polishReason: PolishReason? = null
    @Volatile var polishMs: Long? = null
    @Volatile var polishStatus: Int? = null
    /** `ok` or `failed`: whether the History save returned, known only on the completed route. */
    @Volatile var historySave: String? = null
    /** The speech process's typed failure; reported only on an `ASR_FAILED` ending. */
    @Volatile var asrFailure: AsrFailureReason? = null

    /** The row, built from whatever was measured by the time [reason] was committed. */
    fun terminal(reason: TerminalReason): AnalyticsEvent.DictationTerminal = AnalyticsEvent.DictationTerminal(
        takeId = takeId,
        reason = reason,
        asrFailure = asrFailure.takeIf { reason == TerminalReason.ASR_FAILED },
        trigger = trigger,
        routeKind = routeKind,
        routeReason = routeReason,
        liveAfterMs = liveAfterMs,
        liveState = liveState,
        silenceStopStatus = silenceStopStatus,
        captureTerminal = captureTerminal,
        recordingSeconds = recordingSeconds,
        inputDevice = inputDevice,
        asrMs = asrMs,
        asrChars = asrChars,
        peakAmplitude = peakAmplitude,
        polishProvider = polishProvider,
        polishReason = polishReason,
        polishMs = polishMs,
        polishStatus = polishStatus,
        historySave = historySave,
    )

    companion object {
        /** The owner's own stop request, which the capture process reports as its manual ending. */
        const val MANUAL_ENDING = "manual"

        /** The capture process's silence status integer as a closed token; an unknown code is `unknown`. */
        fun silenceStatusToken(status: Int): String = when (status) {
            AudioCaptureService.SILENCE_STATUS_DISABLED -> "off"
            AudioCaptureService.SILENCE_STATUS_PREPARING -> "preparing"
            AudioCaptureService.SILENCE_STATUS_READY -> "ready"
            AudioCaptureService.SILENCE_STATUS_UNAVAILABLE -> "unavailable_before_ready"
            AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY -> "lost_after_ready"
            else -> "unknown"
        }

        /** The capture ending as a token; exhaustive over [CaptureEnding], no `else`. */
        fun captureEndingToken(reason: Int): String = when (CaptureEnding.fromAidl(reason)) {
            CaptureEnding.StillRunning -> "still_running"
            CaptureEnding.MaxDuration -> "max_duration"
            CaptureEnding.Manual -> MANUAL_ENDING
            CaptureEnding.Silence -> "silence"
            CaptureEnding.Failure -> "failure"
        }

        /** `auto` or `picked`, from the stored pick string; the name after the bar never leaves. */
        fun inputDeviceToken(pick: String): String = if (pick == com.envi.wispr.audio.InputDevicePick.AUTO) "auto" else "picked"
    }
}
