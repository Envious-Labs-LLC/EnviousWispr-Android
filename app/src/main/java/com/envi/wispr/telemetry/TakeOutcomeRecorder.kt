package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.polish.PolishReason

/**
 * One take's outcome record (#329): every take-fact stamp, journal stage and take breadcrumb the session owner
 * writes, in one place. It only RECORDS: it never reserves, commits, chooses an ending or delivers text, so the
 * owner stays the take's one owner (`architecture-rules.md` RULE: keep-central-types-thin). Its facts are the
 * take's own [facts], read at the arbiter's commit by `recordTakeEnding`.
 *
 * Order is the owner's: each call sits where its stamp sat, so a fact an ending must carry is still written
 * before the reservation or commit that follows it (ASR failures before `commitNow`, the polish facts before the
 * payload and the reservation, the History answer before the completed commit). The facts are volatile, as
 * before, so no call needs a lock. The start's own stamps belong to `TakeStartPreparer`.
 */
internal class TakeOutcomeRecorder(
    val facts: TakeFacts,
    /** The owner's guarded defect seam, so a sink that throws never stops the publication after it. */
    private val defect: (AppDefect, Map<String, Any?>) -> Unit,
    /** The journal's stage advance; the process journal, when one exists. */
    private val advance: (String, TakeStage) -> Unit = { takeId, stage -> Telemetry.journal?.advance(takeId, stage) },
    private val breadcrumb: (String, String, Map<String, Any?>) -> Unit = Telemetry::breadcrumb,
    private val started: (String) -> Unit = Telemetry::takeStarted,
) {
    private val takeId: String get() = facts.takeId

    /** Admitted, before the journal admission is queued. */
    fun admitted() {
        started(takeId)
        breadcrumb("take", "admitted", mapOf("take_id" to takeId, "trigger_source" to facts.trigger.wire))
    }

    fun admissionObserved(sinceAcceptedMs: Long) {
        facts.admissionObservedMs = sinceAcceptedMs
    }

    fun bindRequested(sinceAcceptedMs: Long) {
        facts.bindRequestedMs = sinceAcceptedMs
    }

    /** The capture process's ending, as it arrives on main, before the owner classifies it. */
    fun captureEnded(peakAmplitude: Float, silenceStatus: Int) {
        facts.peakAmplitude = peakAmplitude
        facts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(silenceStatus) }.getOrNull()
    }

    /** How the capture process ended a RECORDING take, stamped at the one place it is classified. */
    fun captureTerminal(terminalReason: Int) {
        facts.captureTerminal = TakeFacts.captureEndingToken(terminalReason)
    }

    /**
     * The STARTING to RECORDING transition won, under the owner's publish lock. [receivedSinceAcceptedMs] is read at
     * its own assignment, after the route facts, where the owner read it (#329 review round 2).
     */
    fun live(routeKind: Int, routeReason: Int, liveAfterMs: Long, receivedSinceAcceptedMs: () -> Long, forced: Boolean) {
        facts.routeKind = runCatching { InputRouteKind.fromCode(routeKind) }.getOrNull()
        facts.routeReason = runCatching { InputRouteReason.fromCode(routeReason) }.getOrNull()
        facts.liveAfterMs = liveAfterMs
        facts.liveReceivedMs = receivedSinceAcceptedMs()
        facts.liveState = if (forced) TakeFacts.LIVE_FORCED else TakeFacts.LIVE_READY
        advance(takeId, TakeStage.RECORDING)
        breadcrumb(
            "take", "live",
            mapOf("take_id" to takeId, "route_kind" to facts.routeKind?.name?.lowercase(), "live_after_ms" to facts.liveAfterMs, "live_state" to facts.liveState),
        )
    }

    /**
     * The take stopped and its file is read. Stamped `manual` when the ending handler did not classify it: this
     * stop is then the owner's own request, which the capture process reports as a manual ending.
     */
    fun stopped(recordingDurationMs: Long) {
        facts.recordingSeconds = recordingDurationMs / 1000.0
        if (facts.captureTerminal == null) facts.captureTerminal = TakeFacts.MANUAL_ENDING
        advance(takeId, TakeStage.PROCESSING)
        breadcrumb(
            "take", "stopped",
            mapOf("take_id" to takeId, "capture_terminal" to facts.captureTerminal, "recording_s" to facts.recordingSeconds, "silence_stop_status" to facts.silenceStopStatus),
        )
    }

    /** The speech result's two facts, stamped first, as before. */
    fun asrResult(asrMs: Long, chars: Int) {
        facts.asrMs = asrMs
        facts.asrChars = chars
    }

    /** The `asr_done` breadcrumb, after the owner's own result log lines, as before (#329 review round 1). */
    fun asrDone() {
        breadcrumb("take", "asr_done", mapOf("take_id" to takeId, "asr_ms" to facts.asrMs, "asr_chars" to facts.asrChars))
    }

    /**
     * Before the owner's `commitNow`, so the ending's row carries it; no breadcrumb, as before. [elapsedMs] is read
     * after the failure is stamped, where the owner read it (#329 review round 2).
     */
    fun asrFailed(failure: AsrFailureReason, elapsedMs: () -> Long) {
        facts.asrFailure = failure
        facts.asrMs = elapsedMs()
    }

    /** Before the payload and the reservation (#176): any later ending carries the polish facts. */
    fun polishDone(reason: PolishReason, latencyMs: Long, statusCode: Int, contextToken: String) {
        val record = facts.recordPolish(reason, latencyMs, statusCode, contextToken)
        breadcrumb("take", "polish_done", record.breadcrumb)
        record.defect?.let { defect(it, record.defectData) }
    }

    /** The History save's answer as the publication read it (#277): true saved, false failed, null not yet. */
    fun historySaved(saved: Boolean?) {
        facts.historySave = when (saved) {
            true -> TakeFacts.HISTORY_OK
            false -> TakeFacts.HISTORY_FAILED
            null -> TakeFacts.HISTORY_PENDING
        }
    }
}
