package com.envi.wispr.ui

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.SpectrumAnalyzer
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTerm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        rig.awaitHistoryIdle()
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
        val order = listOf("show", "listen", "updateBands:$stamped", "capture-stop", "unbind", "owner-stop")
        val seen = rig.timeline.filter { it in order }
        assertEquals("show < listen < picture < capture stop < unbind < owner stop; timeline was ${rig.timeline}", order, seen)
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

    /**
     * Product Outcome (#115): when this fails, Aaron says his whole PR description, presses stop, and the
     * recorder sits there forever with his words inside it. The capture process publishes live and then
     * nothing (a frozen or wedged process); the silence bound fires and the take ends with its sentence,
     * the audio service is stopped by intent and the binding released, and NOTHING is called on the
     * capture process itself. The bound is test time: the fake host fires the delayed post.
     * REVERT: disarm the bound, or call the capture process on the wedge path.
     */
    @Test
    fun aSilentAudioProcessEndsTheTakeWithinTheBound() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        // show() fires inside the live transition and the picture registration is issued from it on the
        // capture command lane, so the snapshot waits for that registration to land.
        rig.capture.awaitListener()
        rig.capture.silent = true
        val callsBefore = rig.capture.events.toList()
        rig.host.fireDelayed(CaptureSessionController.TAKE_SILENT_BOUND_MS)

        assertEquals(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:The microphone stopped answering. Try again."))
        assertTrue("the audio service is stopped by intent", rig.pipeline.events.contains("stopAudioService"))
        assertTrue("the binding is released", rig.pipeline.events.contains("unbind"))
        assertEquals("nothing was asked of the wedged process", callsBefore, rig.capture.events.toList())
        assertTrue("the draft is discarded", rig.dao.rows.isEmpty())
    }

    /**
     * Product Outcome (#115): a stop went out and the capture process never published the ending, so
     * the file never closed. The bound ends the take as the close that never came, the sentence it had.
     * REVERT: let the bound only cover RECORDING.
     */
    @Test
    fun aSilentCloseAfterAStopEndsAsTheCloseThatNeverCame() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.silent = true
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.capture.awaitStopRequested()
        rig.host.fireDelayed(CaptureSessionController.TAKE_SILENT_BOUND_MS)

        assertEquals(TerminalReason.CAPTURE_CLOSE_UNSAFE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.dao.rows.isEmpty())
    }

    /**
     * Drift Guard (#115): an ending that arrives after the bound already ended the take changes nothing;
     * the take has one ending. REVERT: drop the state check in the ending handler.
     */
    @Test
    fun aLateEndingAfterTheBoundIsDropped() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.silent = true
        rig.host.fireDelayed(CaptureSessionController.TAKE_SILENT_BOUND_MS)
        assertEquals(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()

        rig.capture.silent = false
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_MANUAL)
        rig.capture.settle()
        assertEquals(listOf(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE), rig.endings.reasons.toList())
        assertTrue("no transcription was asked for", rig.speech.listener == null)
    }

    /**
     * Product Outcome (#115): the take after a wedge is an ordinary take. A fresh owner on a fresh
     * capture process goes live and completes. REVERT: leave the bound armed or the binding held.
     */
    @Test
    fun theNextTakeStartsAfterAWedge() {
        val first = rig.coordinator()
        startAndGoLive(first)
        rig.capture.silent = true
        rig.host.fireDelayed(CaptureSessionController.TAKE_SILENT_BOUND_MS)
        assertEquals(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        rig.capture.settle()
        assertTrue("no bound is left armed once the binding is released: ${rig.host.delayed.map { it.first }} events=${rig.host.events}", rig.host.delayed.isEmpty())

        val next = DictationSessionRig()
        try {
            val coordinator = next.coordinator()
            coordinator.onCreated()
            next.command(coordinator, DictationSessionService.ACTION_START)
            next.surface.awaitShown()
            next.command(coordinator, DictationSessionService.ACTION_STOP)
            next.speech.awaitRequest().onResult("hello again")
            val polish = next.polish
            polish.awaitRequest { "log: ${next.log.lines}" }
            polish.listener!!.onOutcome(polish.outcome("Hello again."))
            assertEquals(TerminalReason.COMPLETED, next.endings.awaitOne())
            // The ending is committed BEFORE the delivery that records the paste (#210): wait for the take's
            // last step, as every other completed-take row here does.
            next.host.awaitStopped()
            assertEquals(listOf(1L to "Hello again."), next.insertion.pastes.toList())
        } finally {
            next.close()
        }
    }

    /**
     * Drift Guard (#115): every event from the capture process pushes the bound out, so a healthy take
     * (a long silence with auto-stop off, a slow gate before live) never trips it: the bound entry is
     * re-posted on each heartbeat and there is always exactly one. REVERT: stop re-arming on a tick.
     */
    @Test
    fun aHeartbeatRearmsTheBound() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.settle()
        val armed = rig.host.delayed.filter { it.first == CaptureSessionController.TAKE_SILENT_BOUND_MS }
        assertEquals("one bound armed", 1, armed.size)
        val before = armed.single().second
        val postsBefore = rig.host.postsWithDelay(CaptureSessionController.TAKE_SILENT_BOUND_MS)
        rig.capture.tick(5_000L)
        rig.capture.settle()
        val after = rig.host.delayed.filter { it.first == CaptureSessionController.TAKE_SILENT_BOUND_MS }
        assertEquals("still exactly one bound", 1, after.size)
        // Counted by the bound's own delay, so the live deadline's post cannot stand in for a re-arm.
        assertEquals("the heartbeat cancelled the bound and posted it again", postsBefore + 1, rig.host.postsWithDelay(CaptureSessionController.TAKE_SILENT_BOUND_MS))
        assertTrue(before === after.single().second)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_RECORDING, rig.endings.awaitOne())
    }

    /**
     * Drift Guard (#115): the owner ASKS the capture process nothing. Over a whole completed take the
     * fake saw exactly the three commands and the two registrations, in this order.
     * REVERT: add a read to `CaptureLink` and call it (the fake cannot be asked what it does not have).
     */
    @Test
    fun theOwnerAsksTheCaptureProcessNothing() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf("listenForTake", "start", "listen", "stop", "finishTake"), rig.capture.events.toList())
        // The bound was pending through the whole healthy take; it goes with the binding.
        // REVERT: drop the cancel from `disarmSilenceBound`.
        assertTrue("no bound left armed after an ordinary take", rig.host.delayed.isEmpty())
    }

    /**
     * Product Outcome (#115 review round 1, F2): back-to-back takes. The audio process's publisher is
     * service-scoped and the previous take's ending can still be queued when the next owner registers;
     * without take identity that ending was consumed as the NEW take's, which then failed as "ended before
     * live" with the old file. Here another take's ending arrives while this take is STARTING: it changes
     * nothing, this take goes live and completes, and the transcript is this take's file.
     * REVERT: drop the `ours(...)` check in the take listener.
     */
    @Test
    fun anotherTakesEndingIsDiscarded() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_MANUAL, takeId = "the-previous-take")
        rig.capture.settle()
        assertTrue("no ending was committed for another take's event", rig.endings.reasons.isEmpty())
        rig.capture.tick(0L)
        rig.capture.settle()
        // Now this take goes live and completes as an ordinary take.
        val own = rig.capture.currentTakeId
        rig.capture.pushLive(own)
        rig.surface.awaitShown()
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
    }

    /**
     * Drift Guard (#115 review round 1, F1): the owner's main thread never calls into the capture
     * process. Every command the fake saw was issued from the one command lane, never from the fake main
     * thread. REVERT: call `startCaptureForTake` or `listenForSpectrum` directly instead of through the lane.
     */
    @Test
    fun noCaptureCommandRunsOnTheMainThread() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.awaitListener()
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("every command came down the lane; threads were ${rig.capture.commandThreads}", setOf("CaptureCommands"), rig.capture.commandThreads.toSet())
    }

    /**
     * Product Outcome (#115 review round 2, F1): the capture process wedges INSIDE the registration and
     * returns after the silence bound already ended the take and released the binding. The late start
     * must not begin a recording nobody owns: the lane rechecks the take before starting and the fake
     * never sees "start". REVERT: drop the STARTING-and-same-take check before the start command.
     */
    @Test
    fun aRegistrationThatReturnsAfterTheBoundNeverStartsAnOrphanTake() {
        val gate = CountDownLatch(1)
        rig.capture.registrationGate = gate
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.capture.awaitRegistering()
        rig.host.fireDelayed(CaptureSessionController.TAKE_SILENT_BOUND_MS)
        assertEquals(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        gate.countDown()
        // The lane runs the rest of the start command after the gate opens; wait for its own decision, the
        // one line it writes when it declines to start (#210), not for a drain that may finish first.
        rig.capture.awaitRegistered()
        rig.log.awaitLine("Capture start skipped: the take is no longer starting")
        assertEquals("the late registration landed but no start followed it", listOf("listenForTake"), rig.capture.events.toList())
    }

    /**
     * Product Outcome (#115, found by the hosted runner): the route goes live and the owner publishes
     * RECORDING on main BEFORE the start call has returned on the lane. That is an ordinary take, not one
     * that ended while the start was in flight, and the lane must not stop it. Here the fake's start
     * returns only after the pill is up; the take then stops and completes as usual, with exactly one
     * stop. REVERT: treat any state other than STARTING after the start as an ended take.
     */
    @Test
    fun aTakeThatGoesLiveBeforeTheStartReturnsIsNotStopped() {
        val returnGate = CountDownLatch(1)
        rig.capture.startReturnGate = returnGate
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        returnGate.countDown()
        rig.capture.awaitListener()
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("one stop, the user's", 1, rig.capture.events.count { it == "stop" })
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
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

    /**
     * #253: a blank take ends and the Service is destroyed before the audio delete has run; the delete, queued
     * on the process-owned worker, still runs after the teardown. MUTATION: delete on the session scope.
     */
    @Test
    fun aTeardownRightAfterABlankTakeStillDeletesItsAudio() {
        rig.capture.peak = 0.001f
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val coordinator = rig.coordinator(audioCleanup = { queued += it })
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        val listener = rig.speech.awaitRequest()
        val audio = checkNotNull(rig.capture.audioFile)
        rig.onMain { listener.onResult("") }
        assertEquals(TerminalReason.NO_SPEECH, rig.endings.awaitOne())
        rig.onMain { coordinator.destroy() }
        assertTrue("the audio is still on disk until its delete runs", audio.exists())
        val delete = checkNotNull(queued.poll(10, TimeUnit.SECONDS)) { "the delete was never queued on the process worker" }
        delete.run()
        assertFalse("the take's audio was deleted after the teardown", audio.exists())
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
    fun refusedEarbudsEndsStarting() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.capture.startFailure = AudioCaptureService.START_FAILURE_EARBUDS
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_ERROR)

        assertEquals(TerminalReason.CAPTURE_START_EARBUDS_REFUSED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Earbuds could not be used."))
    }

    // #213 row 17: a capture process that ends itself during the start (to recover a recorder that was never
    // released) is ONE ending, AUDIO_PROCESS_DIED, whichever of the throw and the death notice arrives first.
    // REVERT: map DeadObjectException to START_EXCEPTION, and the first row turns red.
    @Test
    fun aCaptureProcessDyingDuringTheStartIsOneProcessDeathWhenTheThrowComesFirst() {
        rig.capture.startThrows = android.os.DeadObjectException()
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)

        assertEquals(TerminalReason.AUDIO_PROCESS_DIED, rig.endings.awaitOne())
        rig.pipeline.disconnect("capture")
        rig.host.awaitStopped()
        rig.capture.settle()
        assertEquals("one ending, whatever arrived second", listOf(TerminalReason.AUDIO_PROCESS_DIED), rig.endings.reasons.toList())
        assertTrue(rig.host.events.contains("toast:Microphone service stopped unexpectedly"))
    }

    @Test
    fun aCaptureProcessDyingDuringTheStartIsOneProcessDeathWhenTheNoticeComesFirst() {
        val gate = CountDownLatch(1)
        rig.capture.startThrows = android.os.DeadObjectException()
        rig.capture.startThrowGate = gate
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.capture.awaitStarted()
        rig.pipeline.disconnect("capture")

        assertEquals(TerminalReason.AUDIO_PROCESS_DIED, rig.endings.awaitOne())
        gate.countDown()
        // The throw's own ending arrives second and is refused by the arbiter: that refusal is the signal.
        rig.log.awaitLine("Ignoring AUDIO_PROCESS_DIED")
        rig.host.awaitStopped()
        assertEquals("one ending, whatever arrived second", listOf(TerminalReason.AUDIO_PROCESS_DIED), rig.endings.reasons.toList())
    }

    @Test
    fun anyOtherThrowDuringTheStartStaysAStartException() {
        rig.capture.startThrows = android.os.RemoteException()
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)

        assertEquals(TerminalReason.START_EXCEPTION, rig.endings.awaitOne())
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
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_ERROR)

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
        // The P4 race, staged: the stop path writes `processing` then `asr_error` back to back, and the
        // fake holds the FIRST of them. On 87e07ca the two were launched on the IO dispatcher and the held
        // one landed last, leaving the row `processing`; the History queue (#115) applies them in enqueue
        // order whatever the disk does. REVERT: launch the status writes on the owner's scope again.
        rig.dao.delayFirstStatusMs = 200L
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

    /**
     * Product Outcome (#115, chunk B): when this fails, force-stopping the app during a take freezes its
     * screen for as long as the disk is stalled, because teardown waited on the History write. Here the
     * disk is HELD; destroy returns on the main thread anyway (the rig's main-thread call has a bound),
     * and the `interrupted` row lands once the disk answers. REVERT: restore a `runBlocking` on the
     * `interrupted` write in `destroy`.
     */
    @Test
    fun destroyReturnsWhileARoomWriteIsStalled() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.awaitHistoryIdle()
        val disk = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.dao.holdStatusWrites = disk
        rig.onMain { coordinator.destroy() }
        assertEquals(TerminalReason.INTERRUPTED_RECORDING, rig.endings.awaitOne())
        assertEquals("the row is still the draft while the disk is held", "draft", rig.dao.rows.values.single().status)
        disk.complete(Unit)
        rig.dao.holdStatusWrites = null
        val row = theOnlyRow()
        assertEquals("interrupted", row.status)
        assertTrue(row.interrupted)
    }

    /**
     * Product Outcome (#115, chunk B): when this fails, a dictation that finished and reached the editor
     * is later shown as "interrupted" in History because the Service's ordinary stop wrote over it.
     * `interrupted` is queued ONLY when the interrupt won the take; after a completed take it loses.
     * REVERT: enqueue `interrupted` in `destroy` whether or not `arbiter.interrupt` won.
     */
    @Test
    fun destroyAfterACompletedTakeLeavesTheRowAlone() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        rig.onMain { coordinator.destroy() }
        val row = theOnlyRow()
        assertEquals("ready_for_insertion", row.status)
        assertFalse(row.interrupted)
    }

    /**
     * Product Outcome (#115 review of chunks B and C, F1): the start-up recovery is a write of its own; a
     * disk that stalls it must not hold a new take's draft, status and finalization behind it. Here the
     * recovery is HELD for the whole take, which still completes and lands its row.
     * REVERT: enqueue the recovery on the per-take queue.
     */
    @Test
    fun aStalledRecoveryDoesNotHoldTheTakesWrites() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.dao.holdRecovery = held
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello world")
        polish.listener!!.onOutcome(polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals("ready_for_insertion", theOnlyRow().status)
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        held.complete(Unit)
    }

    @Test
    fun destroyWhileStartingMarksInterrupted() {
        val coordinator = rig.coordinator()
        startAndStayStarting(coordinator)
        rig.onMain { coordinator.destroy() }

        assertEquals(TerminalReason.INTERRUPTED_STARTING, rig.endings.awaitOne())
        // REVERT (#115 review round 1, F4): drop `disarmSilenceBound()` from `destroy`.
        assertTrue("destroy disarms both delayed callbacks", rig.host.delayed.isEmpty())
        assertTrue("the surface is hidden by teardown", rig.surface.events.contains("hide"))
        assertTrue(rig.dao.rows.isEmpty())
    }

    @Test
    fun destroyWhileRecordingMarksInterrupted() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.onMain { coordinator.destroy() }

        assertEquals(TerminalReason.INTERRUPTED_RECORDING, rig.endings.awaitOne())
        // REVERT (#115 review round 1, F4): drop `disarmSilenceBound()` from `destroy`.
        assertTrue("destroy disarms both delayed callbacks", rig.host.delayed.isEmpty())
        val row = theOnlyRow()
        assertEquals("interrupted", row.status)
        assertTrue(row.interrupted)
        assertEquals("not_attempted", row.insertionResult)
    }

    @Test
    fun destroyWhileCancellingMarksInterrupted() {
        val gate = CountDownLatch(1)
        rig.capture.endingGate = gate
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        // The stop went out and the ending is held by the gate: the take is CANCELLING, waiting (#115).
        rig.capture.awaitStopRequested()
        // destroy() interrupts the arbiter under the publish lock. The gate opens only once the interrupt
        // has been COMMITTED (the ending sink fired), never on an earlier signal such as the recorder
        // hiding, which destroy does before the interrupt (a hosted-runner flake, 2026-09-20). The
        // cancel's own commit, when the ending lands, then finds the take already interrupted and does
        // nothing.
        rig.postMain { coordinator.destroy() }
        assertEquals(TerminalReason.INTERRUPTED_CANCELLING, rig.endings.awaitOne())
        gate.countDown()
        // The ending leaves the fake's binder thread once the gate opens and is posted to main, where its
        // commit loses: settle() waits for both hops (#210), where two main drains could finish first.
        rig.capture.settle()
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
        // REVERT (#115 review round 1, F4): drop `disarmSilenceBound()` from `destroy`.
        assertTrue("destroy disarms both delayed callbacks", rig.host.delayed.isEmpty())
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
     * Product Outcome (#193, coverage B1): a flow that completes without ever emitting is a failed read
     * answered at once, not a silence that waits out the bound. Both flows complete empty; the take starts
     * on the defaults with `completed_without_value` in the facts. A flow that completes AFTER a value has
     * answered `Fresh` (the ordinary-take row below stages exactly that).
     * REVERT: mark a completed flow `Failed` unconditionally, or not at all.
     */
    @Test
    fun aReaderThatCompletesWithoutAValueFailsAtOnce() {
        val empty = SessionPreferencesSource(
            preferenceStates = flow { },
            terms = flow { },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = empty)
        startAndGoLive(coordinator)
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals("both:completed_without_value:completed_without_value", rig.endings.facts.single().settingsFallback)
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
        // The readers answer only when the test says so, AFTER the start command: a take that did not
        // wait would start capture on the defaults (auto-stop off) and this row would read it.
        val gate = CompletableDeferred<Unit>()
        val late = SessionPreferencesSource(
            preferenceStates = flow { gate.await(); emit(AppPreferencesState(autoStopOnSilenceEnabled = true, silencePauseSeconds = 1.5f)) },
            terms = flow { gate.await(); emit(emptyList()) },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = late)
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        assertTrue("capture is not asked to start before the readers answer", rig.capture.startArguments.isEmpty())
        gate.complete(Unit)
        rig.surface.awaitShown()
        completeTake(coordinator, "hello world", "Hello world.")

        assertEquals("start(autoStop=true, pause=1.5)", rig.capture.startArguments.single())
        assertNull("an ordinary take carries no fallback token", rig.endings.facts.single().settingsFallback)
        assertTrue(fallbackWarnings().isEmpty())
    }

    /**
     * #214 row 1 (Harness Contract): a blank answer over real words is a broken engine; the owner publishes its
     * own floor (custom words restored), never the raw transcript. REVERT: remove the blank-outcome branch.
     */
    @Test
    fun aBlankPolishedAnswerPublishesTheExactCleanedVocabularyText() {
        val term = CustomTerm(spelling = "Envious", aliases = listOf("envious"))
        val withTerm = SessionPreferencesSource(
            preferenceStates = flow { emit(AppPreferencesState()) },
            terms = flow { emit(listOf(term)) },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        val coordinator = rig.coordinator(preferences = withTerm)
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "hello envious")
        polish.listener!!.onOutcome(polish.outcome(""))

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "hello Envious"), rig.insertion.pastes.toList())
        val row = theOnlyRow()
        assertEquals("hello Envious", row.finalText)
        assertEquals("Deterministic fallback", row.polishEngine)
        assertEquals("CALL_FAILED", row.polishReason)
        assertEquals(
            "exactly one blank protocol violation",
            listOf("polish_protocol_violation" to "blank"),
            rig.defects.filter { it.first == "polish_protocol_violation" }.map { it.first to it.second["shape"] },
        )
    }

    /**
     * #214 row 2 (Harness Contract): a nonblank answer for a filler-only take (cleanup recovered what it would
     * otherwise have erased) is inserted as today. REVERT: make the branch reject nonblank outcomes.
     */
    @Test
    fun aNonblankFillerRecoveryUnderOffStillInsertsWhatWasSaid() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        val polish = stopAndTranscribe(coordinator, "um uh")
        polish.listener!!.onOutcome(polish.outcome("um uh", com.envi.wispr.polish.PolishReason.OFF))

        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "um uh"), rig.insertion.pastes.toList())
        assertEquals("OFF", theOnlyRow().polishReason)
        assertTrue("no protocol violation", rig.defects.none { it.first == "polish_protocol_violation" })
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

    // ---- #258: the pre-capture chain, timed from the accepted start command ------------------------------------

    /** Every clock read answers 1000, 1010, 1020, ...: one read per step, so each step's offset is literal. */
    private fun scriptTheClock() {
        val reads = java.util.concurrent.atomic.AtomicLong(0L)
        rig.host.clock = { 1_000L + 10L * reads.getAndIncrement() }
    }

    /**
     * Row 1: with the admission already landed, every read in the start chain happens in one order: the origin,
     * the admission's observed completion, then settings, matcher, policy, bind and live. MUTATIONS: record a
     * step's absolute time instead of its offset; record admission when the wait returns.
     */
    @Test
    fun theStartChainIsTimedFromTheAcceptedCommandInStepOrder() {
        scriptTheClock()
        val coordinator = rig.coordinator(admit = { _, _ -> CompletableDeferred(true) })
        startAndGoLive(coordinator)
        rig.capture.settle()
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        rig.endings.awaitOne()
        val facts = rig.endings.facts.single()
        assertEquals(
            listOf(10L, 20L, 30L, 40L, 50L, 60L),
            listOf(facts.admissionObservedMs, facts.settingsAnswerMs, facts.matcherReadyMs, facts.policyLoadedMs, facts.bindRequestedMs, facts.liveReceivedMs),
        )
    }

    /**
     * Row 2: a take ended while its settings are still unanswered keeps the admission it observed; every later
     * milestone stays absent, never zero. The rig's capture is bound from the start, so a cancel here would wait
     * for a capture ending that never comes; the Service's destroy ends the take at once, as INTERRUPTED_STARTING.
     * MUTATION: default the durations to 0.
     */
    @Test
    fun aTakeEndedBeforeItsSettingsAnswerKeepsOnlyWhatItReached() {
        scriptTheClock()
        val silent = SessionPreferencesSource(
            preferenceStates = flow { awaitCancellation() },
            terms = flow<List<CustomTerm>> { awaitCancellation() },
            migrateLegacyTerms = {},
            log = rig.log,
        )
        // A long bound, and the ending right after the starting signal, well before it expires.
        val coordinator = rig.coordinator(preferences = silent, answerBoundMs = 60_000L, admit = { _, _ -> CompletableDeferred(true) })
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        assertTrue("the take is starting", rig.surface.events.contains("starting"))
        rig.onMain { coordinator.destroy() }
        assertEquals(TerminalReason.INTERRUPTED_STARTING, rig.endings.awaitOne())
        val facts = rig.endings.facts.single()
        assertEquals(10L, facts.admissionObservedMs)
        assertEquals(
            listOf<Long?>(null, null, null, null, null),
            listOf(facts.settingsAnswerMs, facts.matcherReadyMs, facts.policyLoadedMs, facts.bindRequestedMs, facts.liveReceivedMs),
        )
    }

    // ---- #256: each recorder notice, driven through the owner, on the surface it belongs on ----------------

    private fun pills() = rig.surface.events.filter { it.startsWith("notice:") }

    /** The silence detector never became available for a take that had auto-stop on. MUTATION: the wrong notice at a call site. */
    @Test
    fun theSilenceNoticeIsThePillWhenAutoStopNeverBecameAvailable() {
        rig.preferenceStates.value = AppPreferencesState(autoStopOnSilenceEnabled = true)
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.silenceStatus(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
        rig.capture.settle()
        assertEquals(listOf("notice:Auto-stop on silence is unavailable right now"), pills())
    }

    /** A take that goes live on Bluetooth with tips on hears the tip once. */
    @Test
    fun theBluetoothTipIsThePillForATakeLiveOnBluetooth() {
        rig.capture.liveRouteKind = com.envi.wispr.audio.InputRouteKind.BLUETOOTH.code
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.settle()
        assertEquals(listOf("notice:Recording through your earbuds"), pills())
    }

    /** The last minute of a take is announced on the pill. */
    @Test
    fun theDurationWarningIsThePillInTheLastMinute() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.tick(com.envi.wispr.audio.RecordingLimits.WARNING_AT_MS)
        rig.capture.settle()
        assertEquals(listOf("notice:Recording stops in under a minute (10 minute limit)"), pills())
    }

    /** The cap ends a take, which keeps its words, and the reason arrives as a toast after the recorder has gone. */
    @Test
    fun theCapIsSaidAsAToastAfterTheTakeContinues() {
        val coordinator = rig.coordinator()
        startAndGoLive(coordinator)
        rig.capture.endOnItsOwn(AudioCaptureService.TERMINAL_REASON_MAX_DURATION)
        rig.speech.awaitRequest()
        rig.host.awaitApplicationToast()
        assertTrue(
            "the cap's sentence: ${rig.host.events}",
            rig.host.events.any { it.startsWith("toast-app:Reached the 10 minute limit. Working on what you said.@") },
        )
        assertTrue("no pill for the cap: ${pills()}", pills().isEmpty())
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
        // Nothing was handed to the accessibility service, so the field the take aimed at is let go here,
        // once (#216: the delivery moved to `SessionFinalizer`, and no row pinned this release before).
        assertEquals("the pinned field is released once", 1, rig.insertion.releases.get())
    }
}
