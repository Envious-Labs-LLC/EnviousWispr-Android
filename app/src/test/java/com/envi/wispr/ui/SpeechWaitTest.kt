package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#356): a take whose speech request is never answered ends at a bound, with a sentence, instead
 * of staying in Processing. When this fails, the user sees a recorder stuck on Processing until they cancel it.
 * The rig's recording is 32,000 bytes, one second of audio, so its bound is 20.5 s; each row names its mutation.
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
}
