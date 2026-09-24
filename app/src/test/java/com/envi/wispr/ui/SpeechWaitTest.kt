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

    private fun stopWithTheRequestOpen(audioBytes: Int? = null): DictationSessionCoordinator {
        val coordinator = rig.coordinator()
        coordinator.onCreated()
        rig.command(coordinator, DictationSessionService.ACTION_START)
        rig.surface.awaitShown()
        audioBytes?.let { rig.capture.audioFile!!.writeBytes(ByteArray(it)) }
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

    /** Row 2: an answer after the bound publishes nothing and changes nothing. MUTATION m2: the late-answer guard removed. */
    @Test fun anAnswerAfterTheBoundIsIgnored() {
        stopWithTheRequestOpen()
        rig.host.fireDelayed(20_500L)
        assertEquals(TerminalReason.ASR_PROCESS_UNRESPONSIVE, rig.endings.awaitOne())
        rig.host.awaitStopped()

        rig.speech.listener!!.onResult("hello world")
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

    /** Row 4: the bound grows with the recording: two minutes of audio get 80 s, not the base. MUTATION m4: the base alone. */
    @Test fun theBoundGrowsWithTheRecording() {
        stopWithTheRequestOpen(audioBytes = 32_000 * 120)
        assertEquals(1, rig.host.postsWithDelay(80_000L))
        assertEquals(0, rig.host.postsWithDelay(20_000L))
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
