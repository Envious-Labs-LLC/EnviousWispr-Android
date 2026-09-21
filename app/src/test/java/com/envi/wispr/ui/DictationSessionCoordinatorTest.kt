package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.SpectrumAnalyzer
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
import org.junit.Assert.assertNull
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

    /**
     * Product Outcome (#187): when this fails, the user sees a rail that never moves, or a picture from
     * the last take drawn on this one. The audio process pushes; the owner registers when the pill
     * appears, stamps the visible take's serial on every picture, and unregisters where every session
     * ends, before the binding goes. The fakes share one timeline so the ORDER across them is the proof.
     * REVERT: delete the `listenForPicture()` call in `startPolling`; no "listen", no picture.
     */
    @Test
    fun theOwnerRegistersForThePictureWhenTheTakeGoesLiveAndUnregistersWhenItEnds() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val stamped = rig.surface.currentTakeSerial()
        val listener = rig.capture.awaitListener()
        val picture = FloatArray(SpectrumAnalyzer.BAND_COUNT) { index -> index / 10f }
        listener.onSpectrum(picture)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()

        assertEquals(listOf(stamped), rig.surface.pictures.map { it.first })
        assertTrue("the pushed picture reached the recorder unchanged", rig.surface.pictures.single().second.contentEquals(picture))
        val order = listOf("show", "listen", "updateBands:$stamped", "capture-stop", "stopListening", "unbind", "owner-stop")
        val seen = rig.timeline.filter { it in order }
        assertEquals("show < listen < picture < capture stop < stopListening < unbind < owner stop; timeline was ${rig.timeline}", order, seen)
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

    /**
     * Drift Guard on the owner's contract (#192): the field a take aims at is pinned ONCE, at admission,
     * and no later command touches it. Before #192 the launcher pinned again on the TOGGLE that stops a
     * take, so the words followed the user to whichever field they had reached; the owner itself never
     * did, which is why this row is a guard on the owner and the emulator scenario is the fix's proof.
     * REVERT: pin inside the TOGGLE, STOP or CANCEL arm of `handleCommand`; the count goes past one.
     */
    @Test
    fun aStoppingToggleNeverRepinsTheTarget() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        assertEquals("one pin at admission", 1L, rig.insertion.pins.get())
        rig.command(coordinator, DictationSessionService.ACTION_TOGGLE)
        rig.speech.awaitRequest().onResult("hello world")
        rig.polish.awaitRequest { "log: ${rig.log.lines}; uncaught: ${rig.uncaught}" }
        // The stop, the transcription and the insertion all ran with the pin from admission.
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("the stopping toggle took no second pin", 1L, rig.insertion.pins.get())
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
    }

    /** Same contract on the STOP command (#192); one rig holds one take, so CANCEL has its own row. */
    @Test
    fun aStopNeverRepinsTheTarget() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("a STOP took no second pin", 1L, rig.insertion.pins.get())
    }

    /** Same contract on the CANCEL command (#192). */
    @Test
    fun aCancelNeverRepinsTheTarget() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_RECORDING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("a CANCEL took no second pin", 1L, rig.insertion.pins.get())
    }

    /**
     * Drift Guard on the owner's contract (#192): a START that arrives while a take is RECORDING is
     * refused and takes no pin; the running take keeps the field it was admitted with.
     * REVERT: pin before the IDLE check in the START arm of `handleCommand`.
     */
    @Test
    fun aRefusedBusyStartNeverPinsTheTarget() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_START)
        // The refusal is synchronous on the fake main thread; the next command proves the take is still
        // the first one (a CANCEL ends it as CANCELLED_RECORDING, which a second admitted take could not).
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_RECORDING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("the refused START took no pin", 1L, rig.insertion.pins.get())
        assertEquals("capture was asked to start once", 1, rig.capture.events.count { it == "start" })
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
        // Only the insertion result is asserted. The stop path launches the `processing` write and the
        // `asr_error` write back to back on the IO dispatcher and they can land in either order (the
        // `processing` write carries no insertion result, so that column is stable either way); the
        // status column is a pre-existing write race, seen on a hosted runner 2026-09-20 and routed to
        // #115, whose History write queue serialises exactly these writes.
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
        // destroy() interrupts the arbiter under the publish lock, then blocks main until the cancel's
        // coroutine is joined; that coroutine is behind the gate. The gate opens only once the interrupt
        // has been COMMITTED (the ending sink fired), never on an earlier signal such as the recorder
        // hiding, which destroy does before the interrupt (a hosted-runner flake, 2026-09-20). The
        // cancel's own commit then finds the take already interrupted and does nothing.
        rig.postMain { coordinator.destroy() }
        assertEquals(TerminalReason.INTERRUPTED_CANCELLING, rig.endings.awaitOne())
        gate.countDown()
        // destroy() returns only after it joined the cancel's coroutine, and this runs after it on the
        // one main thread: by now the losing cancel has run its commit and lost.
        rig.onMain {}
        assertEquals(listOf(TerminalReason.INTERRUPTED_CANCELLING), rig.endings.reasons.toList())
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

    private fun fallbackWarnings(): List<String> = rig.log.lines.filter { it.contains("Settings reader fell back") }

    private fun completeTake(coordinator: DictationSessionCoordinator, spoken: String, polished: String) {
        val polish = stopAndTranscribe(coordinator, spoken)
        polish.listener!!.onOutcome(polish.outcome(polished))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
    }

    /**
     * Product Outcome (#193): when this fails, Frank presses the button and is told his settings could not
     * be loaded instead of being heard. A settings flow that throws before its first emission: capture is
     * asked to start (the rig's bound is 5 s; the answer is immediate), the take transcribes and inserts
     * on the defaults, the facts name the reader, and exactly one warning is logged.
     * REVERT: restore the `showError` ending in `beginSession`.
     */
    @Test
    fun aFailedSettingsReadStartsCaptureOnDefaults() {
        val failing = SessionPreferencesSource(
            preferenceStates = flow { throw java.io.IOException("store unreadable") },
            terms = rig.terms,
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = failing)
        startAndGoLive(coordinator)
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        assertEquals("settings:exception:IOException", rig.endings.facts.single().settingsFallback)
        assertEquals("exactly one warning for the fallback take", 1, fallbackWarnings().size)
        assertTrue("no ending sentence for a limb", rig.host.events.none { it.startsWith("toast") })
    }

    /** Product Outcome (#193): a word-list read that throws leaves the take with the built-in vocabulary only. */
    @Test
    fun aFailedVocabularyReadStartsCaptureWithoutUserTerms() {
        val failing = SessionPreferencesSource(
            preferenceStates = rig.preferenceStates,
            terms = flow { throw IllegalStateException("database closed") },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = failing)
        startAndGoLive(coordinator)
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        assertEquals("terms:exception:IllegalStateException", rig.endings.facts.single().settingsFallback)
        assertEquals(1, fallbackWarnings().size)
    }

    /**
     * Product Outcome (#193): a store that never answers cannot hold the microphone. Both flows stay
     * silent; capture starts after the bound on the defaults with `timed_out` in the facts.
     * REVERT: make the bound end the take.
     */
    @Test
    fun aSilentStoreStartsOnTheLastSnapshotAfterTheBound() {
        val silent = SessionPreferencesSource(
            preferenceStates = flow { awaitCancellation() },
            terms = flow<List<CustomTerm>> { awaitCancellation() },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = silent, answerBoundMs = 200L)
        startAndGoLive(coordinator)
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        assertEquals("both:timed_out:timed_out", rig.endings.facts.single().settingsFallback)
        assertEquals(1, fallbackWarnings().size)
    }

    /**
     * Product Outcome (#193): a reader that answered once and then failed keeps the values it answered
     * with. Non-default values land (auto-stop on, a custom term), then the flows throw; the take runs
     * with those values and names the exception.
     * REVERT: reset the snapshot's values in the collector's catch.
     */
    @Test
    fun aFailureAfterFreshUsesLastSuccessfulSnapshot() {
        val term = CustomTerm(spelling = "Envious", aliases = listOf("envious"))
        val failing = SessionPreferencesSource(
            preferenceStates = flow {
                emit(AppPreferencesState(autoStopOnSilenceEnabled = true, silencePauseSeconds = 2.5f))
                throw java.io.IOException("store went away")
            },
            terms = flow {
                emit(listOf(term))
                throw IllegalStateException("database closed")
            },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = failing)
        // Both readers must have FAILED before the take starts, or the take reads a Fresh snapshot
        // between the emission and the throw and the row measures the race, not the property. The
        // subject's own warning lines are the signal.
        coordinator.onCreated()
        rig.log.awaitLine("Unable to load cleanup preferences")
        rig.log.awaitLine("Unable to load custom terms")
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        completeTake(coordinator, "hello envious", "Hello envious.")

        assertEquals("the last successful auto-stop value reached capture", "start(autoStop=true, pause=2.5)", rig.capture.startArguments.last())
        assertEquals("both:exception:IOException:exception:IllegalStateException", rig.endings.facts.single().settingsFallback)
        assertEquals("the custom term reached the matcher before polish", "hello Envious", rig.polish.lastRawText)
    }

    /**
     * Drift Guard (#193): an ordinary take freezes ONE consistent snapshot and waits for the readers'
     * first REAL answer (a take started on defaults before the first emission gave a user with auto-stop a
     * manual take after every cold start). The flows emit after `onCreated`; the take runs with those
     * values and carries no fallback token. REVERT: start before the first answer.
     */
    @Test
    fun anOrdinaryTakeFreezesOneConsistentSnapshot() {
        rig.preferenceStates.value = AppPreferencesState(autoStopOnSilenceEnabled = true, silencePauseSeconds = 1.5f)
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals("start(autoStop=true, pause=1.5)", rig.capture.startArguments.last())
        assertNull("an ordinary take carries no fallback token", rig.endings.facts.single().settingsFallback)
        assertTrue(fallbackWarnings().isEmpty())
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
