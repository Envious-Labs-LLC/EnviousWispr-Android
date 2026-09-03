package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureEndingTest {

    @Test
    fun anUnknownEndingIsAFailureNotATranscription() {
        // The value this build does not recognise is the one a future version, or a stale separately
        // installed client, can send. Guessing that it was fine transcribes audio of unknown provenance.
        listOf(5, 6, 99, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { unknown ->
            assertEquals("$unknown must not be treated as ordinary", CaptureEnding.Failure, CaptureEnding.fromAidl(unknown))
            assertFalse(CaptureEnding.fromAidl(unknown).transcribes)
        }
    }

    @Test
    fun silenceIsANormalEndingNotAFailure() {
        assertEquals(CaptureEnding.Silence, CaptureEnding.fromAidl(CaptureEnding.SILENCE))
        assertTrue("a silence stop must go on to be transcribed", CaptureEnding.Silence.transcribes)
        assertTrue(CaptureEnding.Manual.transcribes)
        assertTrue(CaptureEnding.MaxDuration.transcribes)
        assertFalse(CaptureEnding.Failure.transcribes)
        assertFalse("a running take is not something to transcribe yet", CaptureEnding.StillRunning.transcribes)
    }

    @Test
    fun theServiceConstantsAndTheEndingTypeAgreeOnEveryValue() {
        // Two homes for one number is how the audio process and the app process end up disagreeing about
        // what happened. This pins the re-export.
        assertEquals(CaptureEnding.NONE, AudioCaptureService.TERMINAL_REASON_NONE)
        assertEquals(CaptureEnding.MAX_DURATION, AudioCaptureService.TERMINAL_REASON_MAX_DURATION)
        assertEquals(CaptureEnding.MANUAL, AudioCaptureService.TERMINAL_REASON_MANUAL)
        assertEquals(CaptureEnding.ERROR, AudioCaptureService.TERMINAL_REASON_ERROR)
        assertEquals(CaptureEnding.SILENCE, AudioCaptureService.TERMINAL_REASON_SILENCE)
    }

    @Test
    fun aClaimWithNoReasonIsRefused() {
        val claim = CaptureEndingClaim()
        assertFalse("ended, with no reason, is not a state anything can act on", claim.claim(CaptureEnding.NONE))
        assertFalse(claim.ended)
        assertEquals(CaptureEnding.StillRunning, claim.ending)
    }

    @Test
    fun theFirstClaimWinsAndLaterOnesAreNoOps() {
        val claim = CaptureEndingClaim()
        assertTrue(claim.claim(CaptureEnding.SILENCE))
        assertFalse(claim.claim(CaptureEnding.MANUAL))
        assertFalse(claim.claim(CaptureEnding.ERROR))
        assertEquals(CaptureEnding.SILENCE, claim.reason)
        assertEquals(CaptureEnding.Silence, claim.ending)
    }

    @Test
    fun manualAndSilenceRaceHasExactlyOneWinner() {
        // A single-threaded test cannot tell an atomic claim from a check-then-act, so this races it.
        // Repeated, because one pass through a window that is usually narrow proves little.
        repeat(400) { attempt ->
            val claim = CaptureEndingClaim()
            val start = CountDownLatch(1)
            val done = CountDownLatch(4)
            val winners = AtomicInteger(0)

            listOf(
                CaptureEnding.MANUAL,
                CaptureEnding.SILENCE,
                CaptureEnding.MAX_DURATION,
                CaptureEnding.ERROR,
            ).forEach { reason ->
                Thread {
                    start.await()
                    if (claim.claim(reason)) winners.incrementAndGet()
                    done.countDown()
                }.start()
            }

            start.countDown()
            assertTrue("threads finished", done.await(10, TimeUnit.SECONDS))
            assertEquals("attempt $attempt: exactly one ending may win", 1, winners.get())
            assertTrue("attempt $attempt: the take must be ended", claim.ended)
            assertTrue(
                "attempt $attempt: the surviving reason must be one that was actually claimed",
                claim.reason in setOf(
                    CaptureEnding.MANUAL,
                    CaptureEnding.SILENCE,
                    CaptureEnding.MAX_DURATION,
                    CaptureEnding.ERROR,
                ),
            )
        }
    }

    @Test
    fun aLateClaimCannotOverwriteTheReasonAfterTheTakeEnded() {
        // The stale-detector-result case: a stop arriving after the user already pressed the button.
        val claim = CaptureEndingClaim()
        claim.claim(CaptureEnding.MANUAL)
        repeat(50) { claim.claim(CaptureEnding.SILENCE) }
        assertEquals(CaptureEnding.MANUAL, claim.reason)
    }
}
