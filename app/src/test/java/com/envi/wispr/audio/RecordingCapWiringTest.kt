package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard, not product coverage.
 *
 * The defect this exists for is the one the cap already had: it lived in two processes as two separate
 * literals, and a user-facing sentence hardcoded a third copy of the number. Nothing linked them, so
 * raising one was a silent truncation and the sentence quoted a limit that was no longer real.
 *
 * Source-level because the two ends are Android `Service` classes in different processes, and because
 * most of what matters is the ABSENCE of a second copy.
 */
class RecordingCapWiringTest {

    private val limits = File("src/main/java/com/envi/wispr/audio/RecordingLimits.kt").readText()
    private val capture = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val asr = File("src/main/java/com/envi/wispr/asr/AsrService.kt").readText()
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    @Test
    fun theCapHasExactlyOneHome() {
        assertTrue(
            "RecordingLimits must own the duration",
            limits.contains("const val MAX_DURATION_MS"),
        )
        assertFalse(
            "the capture process must not keep its own copy of the cap",
            capture.contains("MAX_RECORDING_DURATION_MS"),
        )
        assertFalse(
            "the speech process must not keep its own copy of the ceiling",
            asr.contains("private const val MAX_AUDIO_BYTES"),
        )
        listOf(capture, asr).forEach { user ->
            assertTrue("both processes must read the one owner", user.contains("RecordingLimits."))
        }
    }

    @Test
    fun theCapStopsTheTakeAndAlsoBoundsWhatTheSpeechEngineAccepts() {
        assertTrue(
            "capture must stop the take at the owner's duration",
            capture.contains("if (elapsed >= RecordingLimits.MAX_DURATION_MS) {"),
        )
        assertTrue(
            "the speech engine must refuse against the owner's ceiling",
            asr.contains("if (file.length() > RecordingLimits.MAX_AUDIO_BYTES) {"),
        )
    }

    @Test
    fun noSentenceTheUserReadsWritesTheNumberOutByHand() {
        // The old refusal said "exceeds the 120 second limit" while the cap lived somewhere else
        // entirely. A sentence that spells the number cannot track the constant.
        listOf("120 second" to asr, "two minute" to session, "2 minute" to session).forEach { (stale, source) ->
            assertFalse("a stale limit is still written out: $stale", source.contains(stale))
        }
        listOf(asr, session).forEach { source ->
            assertTrue(
                "every sentence about the limit must be built from RecordingLimits.MAX_DURATION_MINUTES",
                source.contains("\${RecordingLimits.MAX_DURATION_MINUTES}"),
            )
        }
    }

    @Test
    fun theSessionReadsTheCapFromTheOwnerAndNotOverTheBinder() {
        // Both processes compile the same constant, so a binder read buys no authority. What it does
        // buy is a place this thread can hang before its loop has run once, which is issue #115.
        assertTrue(
            "the warning moment must come from the owner",
            session.contains("RecordingLimits.WARNING_AT_MS"),
        )
        assertFalse(
            "the session must not ask the capture service for the cap",
            session.contains("maxDurationMs"),
        )
    }

    @Test
    fun theWarningMomentIsComputedOnceByTheOwner() {
        // Subtracting the lead from the cap at a call site can produce zero or a negative number, and
        // the warning then fires on the first tick of every take.
        assertTrue(
            "the owner must compute the moment",
            limits.contains("const val WARNING_AT_MS = MAX_DURATION_MS - WARNING_LEAD_MS"),
        )
        assertFalse(
            "no caller may redo the subtraction",
            session.contains("- RecordingLimits.WARNING_LEAD_MS"),
        )
        // The relationships are enforced by RecordingLimitsTest. A runtime `init` check in
        // RecordingLimits cannot do it: every value there is a `const val`, which the compiler inlines,
        // so the constant reads this app makes do not trigger the block.
        assertFalse(
            "an init block in RecordingLimits is a guard nothing arms",
            limits.contains("init {"),
        )
        val limitsTest = File("src/test/java/com/envi/wispr/audio/RecordingLimitsTest.kt").readText()
        assertTrue(
            "the only enforcement of the warning moment must still exist",
            limitsTest.contains("RecordingLimits.WARNING_AT_MS > 0L") &&
                limitsTest.contains("RecordingLimits.MAX_DURATION_MINUTES >= 1"),
        )
    }

    @Test
    fun theWarningIsShownOncePerTakeAndResetsForTheNextOne() {
        assertTrue(
            "the warning must be latched",
            session.contains("if (durationWarningShown || elapsedMs < RecordingLimits.WARNING_AT_MS) return"),
        )
        assertTrue("the latch must be set when it fires", session.contains("durationWarningShown = true"))
        assertTrue(
            "the latch must be cleared with the other per-take state",
            session.contains("durationWarningShown = false"),
        )
        // Two lines read `durationWarningShown = false`: the field's own declaration and the per-take
        // reset. A third means a second place decides when a take starts, and the two will disagree.
        assertEquals(
            "the latch must be cleared in exactly one place, beside its sibling",
            1,
            Regex("(?<!var )durationWarningShown = false").findAll(session).count(),
        )
    }

    @Test
    fun theCapPathHasNoCallbackNothingAssigns() {
        // `onMaxDurationReached` was declared and invoked and never assigned, on the exact path this
        // change edits. A hook nobody arms reads as a route the user's message might travel by.
        assertFalse("a dead max-duration callback is back", capture.contains("onMaxDurationReached"))
        assertTrue(
            "the terminal reason is the only signal that a take hit the cap",
            capture.contains("claimEnding(active, TERMINAL_REASON_MAX_DURATION)"),
        )
    }

    @Test
    fun aTakeStoppedByTheCapSaysSoAndStillKeepsTheWords() {
        val ending = session.substringAfter("CaptureEnding.MaxDuration ->").substringBefore("CaptureEnding.Manual,")
        assertTrue("the cap ending must say what happened", ending.contains("DURATION_REACHED_NOTICE"))
        assertTrue("the cap ending must still transcribe", ending.contains("stopAndTranscribe()"))
        // Order matters and it is not cosmetic. The words are the thing that must survive, and the
        // sentence explaining the ending is a limb; ahead of the transition, a failure in it costs
        // the take it was describing.
        assertTrue(
            "the take must be secured before anything is announced about it",
            ending.indexOf("stopAndTranscribe()") < ending.indexOf("sayAfterRecording("),
        )
    }

    @Test
    fun neitherAnnouncementCanCarryTheLoopPastTheCheckThatEndsATake() {
        val loop = session.substringAfter("private fun startPolling()").substringBefore("\n    /**")
        val terminalCheck = loop.indexOf("service.terminalReason")
        assertTrue("the loop must contain the terminal check", terminalCheck >= 0)
        val warning = loop.indexOf("publishDurationWarningIfNeeded(")
        assertTrue("the loop must publish the warning", warning >= 0)
        assertTrue(
            "the duration warning must come AFTER the check that ends a take",
            warning > terminalCheck,
        )
    }

    @Test
    fun theFileItselfIsBoundedAndNotJustTheClock() {
        // record.startRecording() runs before startedAtMs is taken, so the hardware is already
        // buffering when the clock starts and the elapsed check cannot bound the byte count. AsrService
        // REFUSES an oversized file rather than truncating it, so a few bytes over the ceiling throws
        // away the whole long take this limit exists to preserve.
        assertTrue(
            "the read must be bounded by what is left under the ceiling",
            capture.contains("val remaining = RecordingLimits.MAX_AUDIO_BYTES - active.bytesWritten"),
        )
        assertTrue(
            "and must ask for no more than that",
            capture.contains("val requested = minOf(buffer.size.toLong(), remaining).toInt()") &&
                capture.contains("active.record.read(buffer, 0, requested, AudioRecord.READ_BLOCKING)"),
        )
        assertTrue(
            "reaching the ceiling must end the take the same way the clock does",
            capture.contains("if (remaining <= 0L) {"),
        )
    }
}
