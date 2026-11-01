package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness Contract (#356/#43): the real session owner with fake Android/Binder peers and a synthetic file.
 * Protects bounded processing, one reconnect attempt and rejection of superseded replies. This does not prove
 * microphone capture, real recognition or editor insertion. The rig's one-second file has a 20.5 s bound.
 */
class SpeechWaitTest {
    private val rig = DictationSessionRig()

    @After fun tearDown() = rig.close()

    private fun stopWithTheRequestOpen(audioBytes: Int? = null, tickMs: Long? = null): DictationSessionCoordinator {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        audioBytes?.let { rig.capture.audioFile!!.writeBytes(ByteArray(it)) }
        tickMs?.let { rig.capture.tick(it); rig.capture.settle() }
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.speech.awaitRequest()
        return coordinator
    }

    private fun theOnlyRow(): TranscriptEntity {
        rig.awaitHistoryIdle()
        assertEquals("one History row", 1, rig.dao.rows.size)
        return rig.dao.rows.values.single()
    }

    /** Row 1: the request never answers; the bound ends the take with its reason, sentence, row status and file delete. MUTATION m1: no bound armed. */
    @Test fun aSpeechRequestThatNeverAnswersEndsAtItsBound() {
        stopWithTheRequestOpen()
        val audio = rig.capture.audioFile!!
        assertEquals("one bound armed for one second of audio", 1, rig.host.postsWithDelay(20_500L))
        rig.host.fireDelayed(20_500L)

        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue(rig.host.events.contains("toast:Speech service stopped answering. Try again."))
        assertEquals(TranscriptEntity.STATUS_ASR_ERROR, theOnlyRow().status)
        assertFalse("the take's audio is deleted", audio.exists())
        assertTrue("the binding is released", rig.pipeline.events.contains("unbind"))
    }

    /**
     * Row 2: an answer after the bound publishes nothing and changes nothing, not even the capture file, which the
     * capture process reuses for the next take. MUTATION m2: the late-answer guard removed.
     */
    @Test fun anAnswerAfterTheBoundIsIgnored() {
        stopWithTheRequestOpen()
        val audio = rig.capture.audioFile!!
        rig.host.fireDelayed(20_500L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertFalse(audio.exists())
        // The next take records into the same path.
        audio.writeBytes(ByteArray(32_000))

        rig.speech.listener!!.onResult("hello world")
        assertTrue("the next take's recording is untouched", audio.exists())
        assertNull("no polish request for a late answer", rig.polish.listener)
        assertTrue("no insertion", rig.insertion.pastes.isEmpty())
        assertEquals(listOf(TerminalReason.ASR_PROCESS_UNRESPONSIVE), rig.endings.reasons.toList())
        assertEquals(TranscriptEntity.STATUS_ASR_ERROR, theOnlyRow().status)
        assertTrue(rig.log.lines.any { it.contains("Speech answer arrived after the request was closed; ignored") })
    }

    /** Row 3: an answer in time removes the bound; the take completes and a later firing finds nothing. MUTATION m3: no removal on the answer. */
    @Test fun anAnswerInTimeRemovesTheBound() {
        stopWithTheRequestOpen()
        rig.speech.listener!!.onResult("hello world")
        rig.polish.awaitRequest { "log: ${rig.log.lines}; uncaught: ${rig.uncaught}" }
        assertTrue("the bound is gone once the words are here", rig.host.delayed.none { it.first == 20_500L })
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
    }

    /**
     * Row 4: the bound grows with the recording: two minutes of audio get 80 s, not the base, and the take ends when
     * 80 s pass. MUTATION m4: the base alone.
     */
    @Test fun theBoundGrowsWithTheRecording() {
        stopWithTheRequestOpen(audioBytes = 32_000 * 120)
        rig.host.fireDelayed(20_000L)
        assertTrue("nothing ends at the base", rig.endings.reasons.isEmpty())
        rig.host.fireDelayed(80_000L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
    }

    /**
     * Row 6: when the capture clock ran longer than the file says (a short or unreadable file), the clock sets the
     * bound: one minute of ticks over one second of file gets 50 s. MUTATION m6: the file length alone.
     */
    @Test fun theCaptureClockSetsTheBoundWhenItRanLonger() {
        stopWithTheRequestOpen(tickMs = 60_000L)
        rig.host.fireDelayed(20_500L)
        assertTrue("nothing ends at the file's bound", rig.endings.reasons.isEmpty())
        rig.host.fireDelayed(50_000L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
    }

    /**
     * Row 8: a request that throws after the bound already ended the take cleans nothing up a second time: the row
     * keeps its one ending and the next take's recording at the same path is untouched. MUTATION m8: the catch
     * ignores who closed the wait.
     */
    @Test fun aRequestThatThrowsAfterTheBoundChangesNothing() {
        val release = java.util.concurrent.CountDownLatch(1)
        rig.speech.throwWhenReleased = release
        stopWithTheRequestOpen()
        val audio = rig.capture.audioFile!!
        rig.host.fireDelayed(20_500L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        audio.writeBytes(ByteArray(32_000))

        release.countDown()
        rig.log.awaitLine("Speech request threw after the take ended: RemoteException")
        rig.awaitHistoryIdle()
        assertTrue("the next take's recording is untouched", audio.exists())
        assertEquals(listOf(TerminalReason.ASR_PROCESS_UNRESPONSIVE), rig.endings.reasons.toList())
    }

    /** Row 7: a close before the arm refuses it, so a cancel racing the request posts no bound. MUTATION m7: arm ignores an earlier close. */
    @Test fun aCloseBeforeTheArmRefusesIt() {
        val host = rig.host
        val wait = SpeechWait(host)
        assertFalse("nothing was open", wait.close())
        assertFalse("the arm is refused", wait.arm(1_000L) { error("never") })
        assertEquals(0, host.postsWithDelay(20_500L))
    }

    /** Row 5: a cancel while the request is open removes the bound and deletes the file; a later answer is ignored. MUTATION m5: no close on the ending. */
    @Test fun aCancelRemovesTheBoundAndTheLateAnswer() {
        val coordinator = stopWithTheRequestOpen()
        val audio = rig.capture.audioFile!!
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_PROCESSING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertTrue("the bound is gone", rig.host.delayed.none { it.first == 20_500L })
        assertFalse("the take's audio is deleted", audio.exists())

        rig.speech.listener!!.onResult("hello world")
        assertNull("no polish request after the cancel", rig.polish.listener)
        assertEquals(listOf(TerminalReason.CANCELLED_PROCESSING), rig.endings.reasons.toList())
    }
    /** Harness Contract: same owner/recording survives one helper loss, without duplicate words. */
    @Test fun aDisconnectedRequestIsRetriedWithTheSameRecordingOnce() {
        stopWithTheRequestOpen()
        val original = rig.speech.listener!!
        val audio = rig.capture.audioFile!!
        rig.pipeline.disconnect("speech")
        assertTrue("audio stays available during reconnect", audio.exists())
        assertTrue("no premature ending", rig.endings.reasons.isEmpty())
        original.onResult("superseded words")
        original.onFailure(1, "synthetic failure")
        original.onError("synthetic legacy answer")
        assertTrue("stale answers do not delete the retry input", audio.exists())
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        val reply = replacement.awaitRequest()
        assertEquals("same file and take id", rig.speech.requests.single(), replacement.requests.single())
        assertEquals("reconnection did not reset the deadline", 1, rig.host.postsWithDelay(20_500L))
        rig.onMain { reply.onResult("hello world") }
        rig.polish.awaitRequest()
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
        assertEquals(listOf(TerminalReason.COMPLETED), rig.endings.reasons.toList())
        assertFalse("audio is removed after acceptance", audio.exists())
    }

    @Test fun aSecondSpeechLossEndsTheTakeInsteadOfRetryingForever() {
        stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        replacement.awaitRequest()
        rig.pipeline.disconnect("speech")
        assertEquals(TerminalReason.ASR_PROCESS_DIED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertFalse(rig.capture.audioFile!!.exists())
        assertEquals(listOf(TerminalReason.ASR_PROCESS_DIED), rig.endings.reasons.toList())
    }

    @Test fun reconnectDoesNotExtendExpiryAndLateReplacementCannotDeleteANewerFile() {
        stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        val late = replacement.awaitRequest()
        assertEquals(1, rig.host.postsWithDelay(20_500L))
        rig.host.fireDelayed(20_500L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val audio = rig.capture.audioFile!!
        audio.writeBytes(byteArrayOf(5, 6))
        late.onResult("too late")
        late.onFailure(1, "too late")
        assertTrue("the next take's file survives", audio.readBytes().contentEquals(byteArrayOf(5, 6)))
        assertTrue(rig.insertion.pastes.isEmpty())
    }

    @Test fun cancelWhileReconnectingCannotBeRevivedByAConnection() {
        val coordinator = stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        rig.command(coordinator, DictationSessionService.ACTION_CANCEL)
        assertEquals(TerminalReason.CANCELLED_PROCESSING, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        assertFalse(rig.capture.audioFile!!.exists())
        assertTrue(rig.insertion.pastes.isEmpty())
        assertTrue(replacement.requests.isEmpty())
    }

    @Test fun destructionWhileReconnectingCannotBeRevivedByAConnection() {
        val coordinator = stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        rig.onMain { coordinator.destroy() }
        rig.endings.awaitOne()
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        assertFalse(rig.capture.audioFile!!.exists())
        assertTrue(rig.insertion.pastes.isEmpty())
        assertTrue(replacement.requests.isEmpty())
    }

    @Test fun anInitiallyAbsentSpeechConnectionCanDeliverAfterConnecting() {
        rig.pipeline.speech = null
        rig.pipeline.connectSpeech = false
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        rig.command(coordinator, DictationSessionService.ACTION_STOP)
        rig.log.awaitLine("Waiting for speech service connection")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        rig.onMain { replacement.awaitRequest().onResult("hello world") }
        rig.polish.awaitRequest()
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
        assertEquals(1, rig.insertion.pastes.size)
    }

    @Test fun aLateThrowFromTheFirstAttemptDoesNotCloseItsReplacement() {
        val release = java.util.concurrent.CountDownLatch(1)
        rig.speech.throwWhenReleased = release
        stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        val reply = replacement.awaitRequest()
        release.countDown()
        rig.log.awaitLine("Speech request threw after the take ended: RemoteException")
        assertTrue(rig.capture.audioFile!!.exists())
        rig.onMain { reply.onResult("hello world") }
        rig.polish.awaitRequest()
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
    }

    @Test fun deadBinderThenPlatformDisconnectConsumesOnlyOneRetry() {
        rig.speech.requestFailure = android.os.DeadObjectException()
        stopWithTheRequestOpen()
        rig.log.awaitLine("Speech request interrupted; waiting for one reconnect attempt")
        rig.pipeline.disconnect("speech")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        val reply = replacement.awaitRequest()
        rig.onMain { reply.onResult("hello world") }
        rig.polish.awaitRequest()
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
    }

    @Test fun platformDisconnectThenLateDeadBinderDoesNotConsumeReplacement() {
        val release = java.util.concurrent.CountDownLatch(1)
        rig.speech.throwWhenReleased = release
        rig.speech.requestFailure = android.os.DeadObjectException()
        stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        val replacement = DictationSessionRig.FakeSpeech()
        rig.pipeline.reconnectSpeech(replacement)
        val reply = replacement.awaitRequest()
        release.countDown()
        // A main-queue marker after the actual loss callback proves it cannot end the replacement.
        rig.log.awaitLine("Late speech Binder loss ignored")
        assertTrue(rig.capture.audioFile!!.exists())
        rig.onMain { reply.onResult("hello world") }
        rig.polish.awaitRequest()
        rig.polish.listener!!.onOutcome(rig.polish.outcome("Hello world."))
        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
        rig.host.awaitStopped()
    }

    @Test fun attemptAdmissionRefusesClosedWaitAndFailedProxy() {
        val wait = SpeechWait(rig.host)
        assertTrue(wait.arm(1_000L) { error("not fired") })
        val original = checkNotNull(wait.claimAttempt { rig.speech })
        assertNull("duplicate connection cannot admit twice", wait.claimAttempt { rig.speech })
        assertEquals(SpeechWait.Loss.RETRYING, wait.disconnected(original))
        assertNull("a dead Binder cannot be reused before the platform callback", wait.claimAttempt { rig.speech })
        assertEquals(SpeechWait.Loss.WAITING, wait.disconnected())
        val next = DictationSessionRig.FakeSpeech()
        val replacement = checkNotNull(wait.claimAttempt { next })
        assertEquals(SpeechWait.Loss.FINISHED, wait.disconnected(original))
        assertFalse(wait.answer(original))
        assertTrue(wait.close())
        assertNull("queued worker cannot admit after cancellation", wait.claimAttempt { next })
        assertFalse(wait.answer(replacement))
    }

    @Test fun aReplacementThrowAfterExpiryCannotDeleteANewerFile() {
        stopWithTheRequestOpen()
        rig.pipeline.disconnect("speech")
        val release = java.util.concurrent.CountDownLatch(1)
        val replacement = DictationSessionRig.FakeSpeech().apply { throwWhenReleased = release }
        rig.pipeline.reconnectSpeech(replacement)
        replacement.awaitRequest()
        rig.host.fireDelayed(20_500L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()
        val audio = rig.capture.audioFile!!
        audio.writeBytes(byteArrayOf(7, 8))
        release.countDown()
        rig.log.awaitLine("Speech request threw after the take ended: RemoteException")
        assertTrue(audio.readBytes().contentEquals(byteArrayOf(7, 8)))
        assertEquals(listOf(TerminalReason.ASR_PROCESS_UNRESPONSIVE), rig.endings.reasons.toList())
    }

    @Test fun aProxyClearedBeforeWorkerAdmissionCannotBeRequested() {
        val wait = SpeechWait(rig.host)
        assertTrue(wait.arm(1_000L) { error("not fired") })
        var live: SpeechLink? = rig.speech
        // Platform clears the proxy before calling the owner's disconnect, while a worker was queued.
        live = null
        assertEquals(SpeechWait.Loss.WAITING, wait.disconnected())
        assertNull(wait.claimAttempt { live })
        live = DictationSessionRig.FakeSpeech()
        val accepted = checkNotNull(wait.claimAttempt { live })
        assertTrue(wait.answer(accepted))
    }

    @Test fun expiryBeforeWorkerAdmissionCannotBeRevived() {
        val wait = SpeechWait(rig.host)
        var expired = false
        assertTrue(wait.arm(1_000L) { expired = true })
        rig.host.fireDelayed(20_500L)
        assertTrue(expired)
        assertNull(wait.claimAttempt { rig.speech })
    }

}
