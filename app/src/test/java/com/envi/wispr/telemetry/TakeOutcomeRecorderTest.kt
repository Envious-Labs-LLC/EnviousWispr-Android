package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.ui.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #329: the take's outcome record, driven alone. Each call stamps its facts, advances the journal where the owner did,
 * and writes the breadcrumb the owner wrote, with fakes for the three sinks. Names the mutation each row turns red on.
 */
class TakeOutcomeRecorderTest {
    private val facts = TakeFacts("t1", TriggerSource.ASSIST)
    private val stages = mutableListOf<String>()
    private val crumbs = mutableListOf<String>()
    private val defects = mutableListOf<String>()
    private val started = mutableListOf<String>()
    private val recorder = TakeOutcomeRecorder(
        facts,
        defect = { defect, _ -> defects += defect.semanticId },
        advance = { takeId, stage -> stages += "$takeId:${stage.name}" },
        breadcrumb = { category, message, _ -> crumbs += "$category/$message" },
        started = { started += it },
    )

    /** MUTATION m1: `live` no longer advances the journal to RECORDING. */
    @Test fun liveStampsTheRouteAndAdvancesTheJournal() {
        recorder.live(InputRouteKind.PHONE.code, 0, 120L, { 340L }, forced = true)
        assertEquals(InputRouteKind.PHONE, facts.routeKind)
        assertEquals(120L, facts.liveAfterMs)
        assertEquals(340L, facts.liveReceivedMs)
        assertEquals(TakeFacts.LIVE_FORCED, facts.liveState)
        assertEquals(listOf("t1:RECORDING"), stages)
        assertEquals(listOf("take/live"), crumbs)
    }

    /** A stop the owner requested reads manual; one the ending handler classified keeps its token. */
    @Test fun stoppedDefaultsToManualAndAdvancesTheJournal() {
        recorder.stopped(2_500L)
        assertEquals(2.5, facts.recordingSeconds!!, 0.0)
        assertEquals(TakeFacts.MANUAL_ENDING, facts.captureTerminal)
        assertEquals(listOf("t1:PROCESSING"), stages)
        val other = TakeFacts("t2", TriggerSource.ASSIST)
        val second = TakeOutcomeRecorder(other, defect = { _, _ -> }, advance = { _, _ -> }, breadcrumb = { _, _, _ -> }, started = {})
        second.captureTerminal(AudioCaptureService.TERMINAL_REASON_SILENCE)
        second.stopped(1_000L)
        assertEquals(TakeFacts.captureEndingToken(AudioCaptureService.TERMINAL_REASON_SILENCE), other.captureTerminal)
    }

    /** MUTATION m2: `polishDone` no longer raises the defect its reason names. */
    @Test fun aPolishFailureRaisesExactlyOneDefectAndAnOrdinaryPolishNone() {
        recorder.polishDone(PolishReason.POLISHED, 400L, 0, "local")
        assertEquals(emptyList<String>(), defects)
        recorder.polishDone(PolishReason.WATCHDOG_TIMEOUT, 5_000L, 0, "local")
        assertEquals(listOf(TelemetryChannels.defectOf(PolishReason.WATCHDOG_TIMEOUT)!!.semanticId), defects)
        assertEquals(listOf("take/polish_done", "take/polish_done"), crumbs)
        assertEquals(PolishReason.WATCHDOG_TIMEOUT, facts.polishReason)
    }

    @Test fun admissionAsrAndHistoryStampWhatTheOwnerStamped() {
        recorder.admitted()
        assertEquals(listOf("t1"), started)
        recorder.admissionObserved(15L)
        recorder.bindRequested(40L)
        recorder.captureEnded(0.25f, AudioCaptureService.SILENCE_STATUS_READY)
        recorder.asrResult(900L, 34)
        recorder.asrDone()
        assertEquals(15L, facts.admissionObservedMs)
        assertEquals(40L, facts.bindRequestedMs)
        assertEquals(0.25f, facts.peakAmplitude!!, 0f)
        assertEquals(TakeFacts.silenceStatusToken(AudioCaptureService.SILENCE_STATUS_READY), facts.silenceStopStatus)
        assertEquals(34, facts.asrChars)
        recorder.asrFailed(AsrFailureReason.UNKNOWN) { 950L }
        assertEquals(AsrFailureReason.UNKNOWN, facts.asrFailure)
        assertEquals(950L, facts.asrMs)
        assertEquals(listOf("take/admitted", "take/asr_done"), crumbs)
        assertNull(facts.historySave)
        recorder.historySaved(null)
        assertEquals(TakeFacts.HISTORY_PENDING, facts.historySave)
        recorder.historySaved(true)
        assertEquals(TakeFacts.HISTORY_OK, facts.historySave)
        recorder.historySaved(false)
        assertEquals(TakeFacts.HISTORY_FAILED, facts.historySave)
    }
}
