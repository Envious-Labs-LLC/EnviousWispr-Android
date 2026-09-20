package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTerm
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * Harness Contract, not product coverage (`testing-philosophy.md` RULE:
 * the-heart-crosses-a-real-boundary-at-least-once): the three helper processes, the accessibility
 * service, the overlay and Android itself are fakes from [DictationSessionRig], so a row here proves the
 * owner's DECISIONS against those fakes, never that audio, speech, polish or insertion crossed a real
 * boundary. The device runs own that. What the fakes can stage and the phone cannot on demand is exactly
 * the reason the suite exists: a cancel that inserts, a failure with no sentence, a row left open, a
 * late answer accepted after the take was claimed.
 *
 * Every expected reason, sentence, status and route is a literal (`validation-discipline.md` RULE:
 * an-expectation-built-with-the-mechanism-under-test-cannot-fail). Every wait is on a signal the subject
 * fires: the ending sink, the Service stop, the pill, the request to a fake process.
 *
 * Reverts that turn each row red are named in the #186 plan §11.2 and were each seen red once.
 */
class DictationSessionCoordinatorTest {
    private val rig = DictationSessionRig()

    @After
    fun tearDown() = rig.close()

    private fun startAndGoLive(coordinator: DictationSessionCoordinator) {
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
    }

    private fun startAndStayStarting(coordinator: DictationSessionCoordinator) {
        rig.capture.liveStateAfterStart = AudioCaptureService.LIVE_WAITING
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        // Capture was asked to start; the live waiter is polling and the take is still STARTING.
        rig.capture.awaitStarted()
    }

    private fun stopAndTranscribe(coordinator: DictationSessionCoordinator, text: String): DictationSessionRig.FakePolish {
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult(text)
        rig.polish.awaitRequest { "log: ${rig.log.lines}; uncaught: ${rig.uncaught}" }
        return rig.polish
    }

    private fun theOnlyRow(): TranscriptEntity {
        assertEquals("one History row", 1, rig.dao.rows.size)
        return rig.dao.rows.values.single()
    }

    @Test
    fun completedTakeInsertsOnce() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        val row = theOnlyRow()
        assertEquals("ready_for_insertion", row.status)
        assertEquals("Hello world.", row.finalText)
        assertEquals("hello world", row.originalText)
        assertEquals("Phone microphone", row.captureDevice)
        assertEquals(listOf("vibrate:SESSION_TRANSITION", "vibrate:SESSION_TRANSITION"), rig.host.events.filter { it.startsWith("vibrate") })
        assertTrue("the helpers are unbound on the way out", rig.pipeline.events.contains("unbind"))
        assertEquals("the Service stops itself last", "stopSelf", rig.host.events.last())
        assertTrue(rig.host.events.indexOf("foreground-removed") < rig.host.events.indexOf("stopSelf"))
    }

    @Test
    fun cancelWhileStartingLeavesNoRow() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)

        assertEquals(TerminalReason.CANCELLED_STARTING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("capture is stopped", rig.capture.events.contains("stop"))
        assertTrue("no row for a take that never went live", rig.dao.rows.isEmpty())
        assertEquals(listOf("vibrate:SESSION_CANCELED"), rig.host.events.filter { it.startsWith("vibrate") })
    }

    @Test
    fun cancelWhileRecordingLeavesNoRow() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)

        assertEquals(TerminalReason.CANCELLED_RECORDING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("the draft row is discarded", rig.dao.rows.isEmpty())
        assertTrue("the take is handed back to capture", rig.capture.events.contains("finishTake"))
        assertTrue("no sentence for a cancel the user asked for", rig.host.events.none { it.startsWith("toast") })
    }

    @Test
    fun cancelWhileProcessingBeatsLatePolish() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "some words")
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)

        assertEquals(TerminalReason.CANCELLED_PROCESSING, rig.endings.awaitOne())
        // Once from the cancel, and once more from the submitter when it finds the ledger closed.
        assertTrue("the exact open request is cancelled on the engine: ${rig.polish.cancelled}", rig.polish.cancelled.isNotEmpty() && rig.polish.cancelled.all { it == polish.requestId })
        // The engine answers anyway, late.
        polish.listener!!.onOutcome(polish.outcome("Some words."))
        rig.host.awaitStopped()
        assertTrue("a late outcome after a cancel inserts nothing", rig.insertion.pastes.isEmpty())
        assertTrue("and the draft is gone", rig.dao.rows.isEmpty())
        assertEquals(1, rig.endings.reasons.size)
    }

    @Test
    fun audioDiedWhileRecordingEndsAsFailure() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.pipeline.disconnect("capture")

        assertEquals(TerminalReason.AUDIO_PROCESS_DIED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Microphone service stopped unexpectedly"))
        assertTrue("the draft is discarded", rig.dao.rows.isEmpty())
        assertEquals(listOf("vibrate:SESSION_TRANSITION", "vibrate:FAILURE"), rig.host.events.filter { it.startsWith("vibrate") })
    }

    @Test
    fun asrDiedWithRawTextFallsBack() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        stopAndTranscribe(coordinator, "raw words here")
        rig.pipeline.disconnect("speech")

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val row = theOnlyRow()
        assertEquals("Deterministic fallback", row.polishEngine)
        assertEquals("SERVICE_DIED", row.polishReason)
        assertEquals(1, rig.insertion.pastes.size)
    }

    @Test
    fun silenceLeavesNothing() {
        rig.capture.peak = 0.001f
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("")

        assertEquals(TerminalReason.NO_SPEECH, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("nothing left in History", rig.dao.rows.isEmpty())
        assertTrue("the pinned editor is released", rig.insertion.releases.get() >= 1)
        assertTrue("no sentence: hearing nothing is not reported twice", rig.host.events.none { it.startsWith("toast") })
    }

    @Test
    fun audibleNonSpeechLeavesNothing() {
        rig.capture.peak = 0.5f
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("")

        assertEquals(TerminalReason.ASR_EMPTY_DESPITE_AUDIO, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.dao.rows.isEmpty())
    }

    @Test
    fun unmeasuredPeakLeavesNothing() {
        rig.capture.throwOnPeak = true
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest().onResult("")

        assertEquals(TerminalReason.ASR_EMPTY_UNMEASURED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.dao.rows.isEmpty())
    }

    @Test
    fun refusedEarbudsEndsStarting() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.capture.startFailure = AudioCaptureService.START_FAILURE_EARBUDS
        rig.capture.capturing = false

        assertEquals(TerminalReason.CAPTURE_START_EARBUDS_REFUSED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Earbuds could not be used."))
    }

    @Test
    fun captureStartFailureEndsStarting() {
        rig.capture.startResult = false
        rig.capture.startFailure = AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)

        assertEquals(TerminalReason.CAPTURE_START_NO_MICROPHONE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:No microphone found. Please connect one."))
        assertTrue("the audio service is stopped after a refused start", rig.pipeline.events.contains("stopAudioService"))
    }

    @Test
    fun captureEndedBeforeLiveEndsStarting() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.capture.startFailure = AudioCaptureService.START_FAILURE_OTHER
        rig.capture.capturing = false

        assertEquals(TerminalReason.CAPTURE_ENDED_BEFORE_LIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Microphone capture stopped unexpectedly. Try again."))
    }

    @Test
    fun midTakeCaptureFailureEndsWithSentence() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_ERROR)

        assertEquals(TerminalReason.CAPTURE_FAILED_MID_TAKE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Microphone capture stopped unexpectedly. Try again."))
        assertTrue("the draft is discarded", rig.dao.rows.isEmpty())
    }

    @Test
    fun asrNotReadyEndsProcessing() {
        rig.pipeline.speech = null
        rig.pipeline.connectSpeech = false
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)

        assertEquals(TerminalReason.ASR_NOT_READY, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Speech model is still loading. Try again in a moment."))
        val row = theOnlyRow()
        assertEquals("asr_error", row.status)
        assertEquals("asr_error", row.insertionResult)
    }

    @Test
    fun destroyWhileStartingMarksInterrupted() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.onMain { coordinator.destroy() }

        assertEquals(TerminalReason.INTERRUPTED_STARTING, rig.endings.awaitOne())
        assertTrue("the surface is hidden by teardown", rig.surface.events.contains("hide"))
        assertTrue(rig.dao.rows.isEmpty())
    }

    @Test
    fun destroyWhileRecordingMarksInterrupted() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.onMain { coordinator.destroy() }

        assertEquals(TerminalReason.INTERRUPTED_RECORDING, rig.endings.awaitOne())
        val row = theOnlyRow()
        assertEquals("interrupted", row.status)
        assertTrue(row.interrupted)
        assertEquals("not_attempted", row.insertionResult)
    }

    @Test
    fun destroyWhileCancellingMarksInterrupted() {
        val gate = CountDownLatch(1)
        rig.capture.fileReadyGate = gate
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        // The cancel is inside waitForFileReady, held by the gate: the take is CANCELLING.
        rig.capture.awaitStopRequested()
        // destroy() invalidates under the publish lock (hiding the recorder), then blocks main until the
        // cancel's coroutine is joined; that coroutine is behind the gate, which opens only after the
        // invalidation has happened, so the cancel's own commit finds the take already interrupted.
        rig.postMain { coordinator.destroy() }
        rig.surface.awaitHidden()
        gate.countDown()

        assertEquals(TerminalReason.INTERRUPTED_CANCELLING, rig.endings.awaitOne())
        assertEquals(1, rig.endings.reasons.size)
    }

    @Test
    fun destroyWhileProcessingMarksInterrupted() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest()
        rig.onMain { coordinator.destroy() }

        assertEquals(TerminalReason.INTERRUPTED_PROCESSING, rig.endings.awaitOne())
        val row = theOnlyRow()
        assertEquals("interrupted", row.status)
        assertTrue(row.interrupted)
    }

    @Test
    fun settingsNeverReadyEndsStartingWithSentence() {
        val neverReady = SessionPreferencesSource(
            preferenceStates = flow { awaitCancellation() },
            terms = flow<List<CustomTerm>> { awaitCancellation() },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = neverReady, settingsWaitMs = 200L)
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)

        assertEquals(TerminalReason.SETTINGS_UNAVAILABLE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Settings could not be loaded. Try again."))
        assertTrue("capture is never asked to start", rig.capture.events.isEmpty())
    }

    @Test
    fun watchdogFallsBackAndCancelsOnEngine() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "slow words")
        rig.polishTimeout.fire()

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("the watchdog cancels the exact open request: ${rig.polish.cancelled}", rig.polish.cancelled.isNotEmpty() && rig.polish.cancelled.all { it == polish.requestId })
        val row = theOnlyRow()
        assertEquals("Deterministic fallback", row.polishEngine)
        assertEquals("WATCHDOG_TIMEOUT", row.polishReason)
        assertEquals(1, rig.insertion.pastes.size)
    }

    @Test
    fun historySaveFailureCopiesToClipboard() {
        rig.dao.failInserts = true
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "keep these words")
        polish.listener!!.onOutcome(polish.outcome("Keep these words."))

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("nothing was handed to the accessibility service", rig.insertion.pastes.isEmpty())
        assertTrue(rig.host.events.contains("clipboard:Keep these words."))
        assertTrue("the user is told where the words went", rig.host.events.any { it.startsWith("toast:") })
        assertTrue(rig.log.lines.contains("I History persistence unavailable; transcript kept on clipboard only (handoff=HISTORY_NOT_DURABLE)"))
    }

    @Test
    fun mainImmediateRunsInlineOnTheOwnerThread() {
        // An unbound insertion surface routes the forced notice through launch(mainDispatcher) from
        // INSIDE the posted publishLive; Main.immediate runs it inline there, and so must the fixture.
        rig.insertion.bound = false
        rig.capture.liveStateAfterStart = AudioCaptureService.LIVE_FORCED
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.host.awaitApplicationToast()

        assertTrue(
            "the notice ran inline inside the posted runnable, not as a dispatched task: ${rig.host.events}",
            rig.host.events.contains("toast-app:Earbuds are not sending sound.@post"),
        )
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_RECORDING, rig.endings.awaitOne())
    }

    @Test
    fun handoffNotScheduledCopiesAndAnnounces() {
        rig.insertion.handoff = InsertionHandoff.SERVICE_NOT_RUNNING
        rig.preferenceStates.value = AppPreferencesState(autoCopyToClipboard = true)
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "to the clipboard")
        polish.listener!!.onOutcome(polish.outcome("To the clipboard.", PolishReason.POLISHED))

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("clipboard:To the clipboard."))
        val row = theOnlyRow()
        assertEquals("insertion_interrupted", row.status)
        assertEquals("clipboard", row.insertionResult)
        assertFalse("polished text carries no failure notice", rig.host.events.contains("polish-notice"))
    }
}
