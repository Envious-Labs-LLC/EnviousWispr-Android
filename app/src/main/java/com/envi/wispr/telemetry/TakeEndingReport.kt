package com.envi.wispr.telemetry

import com.envi.wispr.ui.DebugSessionLog
import com.envi.wispr.ui.TerminalReason

/**
 * The arbiter's sink: the one place a committed ending becomes telemetry (issue #176). A breadcrumb
 * always; a Sentry defect only when the channel table says the cause is ours; the journal commit,
 * which captures the `dictation.terminal` row after its own Room transaction; then the take leaves
 * the error scope. Every call is a limb that returns at once.
 */
internal fun recordTakeEnding(takeFacts: TakeFacts, reason: TerminalReason) {
    // The pre-capture chain (#258), in ms since the accepted start command, so a UAT reads it without PostHog.
    val start = with(takeFacts) { "settings=$settingsAnswerMs matcher=$matcherReadyMs policy=$policyLoadedMs admission=$admissionObservedMs bind=$bindRequestedMs live=$liveReceivedMs" }
    DebugSessionLog.log("Take terminal: ${reason.name} (${reason.result.wire}) start: $start")
    Telemetry.breadcrumb("take", "terminal", mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "result" to reason.result.wire))
    TelemetryChannels.defectOf(reason, takeFacts.asrFailure)?.let { defect ->
        Telemetry.defect(defect, mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "asr_failure_reason" to takeFacts.asrFailure?.name))
    }
    Telemetry.journal?.terminal(takeFacts.terminal(reason))
    Telemetry.takeEnded(takeFacts.takeId)
}
