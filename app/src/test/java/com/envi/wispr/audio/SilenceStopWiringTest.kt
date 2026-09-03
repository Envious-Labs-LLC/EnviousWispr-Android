package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guards on the wiring between capture and the detector.
 *
 * Source-level because the subject is an Android `Service` bound across a process boundary, and because
 * most of what matters here is the ABSENCE of something: no binder call on the capture thread, no
 * unbounded wait, no unbind that Android could reconnect through.
 */
class SilenceStopWiringTest {

    private val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val aidl = File("src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun bodyOf(signature: String): String {
        val start = service.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = service.indexOf("\n    private fun ", start + 1)
        return service.substring(start, if (end > start) end else service.length)
    }

    @Test
    fun startCaptureIsStillTheFirstTransactionAndNothingWasReordered() {
        // A separately installed client binds by transaction number. Reordering breaks it at runtime,
        // with no compile error anywhere.
        val order = Regex("^\\s*(?:boolean|void|int|float|long|String|byte\\[])\\s+(\\w+)\\(", RegexOption.MULTILINE)
            .findAll(aidl).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "startCapture", "stopCapture", "isCapturing", "getTerminalReason", "getCurrentAmplitude",
                "getAudioFilePath", "getElapsedMs", "getMaxDurationMs", "waitForFileReady", "getAudioData",
                "startCaptureWithSilenceStop", "getSilenceStopStatus",
            ),
            order,
        )
    }

    @Test
    fun theOldStartMeansExactlyWhatItMeantBefore() {
        assertTrue(
            "startCapture must be the no-auto-stop case of the new one, not a second implementation",
            service.contains("startRecording(autoStopOnSilence = false, pauseSeconds = 0f)"),
        )
    }

    @Test
    fun theCaptureThreadNeverCallsTheDetector() {
        // The capture thread may not block, allocate, log on the hot path, or make a binder call. It
        // copies into a slot it already owns and moves on.
        val loop = bodyOf("private fun captureLoop(")
        val offer = bodyOf("private fun offerToDetector(")
        listOf(loop, offer).forEach { body ->
            assertFalse("no binder call on the capture thread", body.contains("vadService"))
            assertFalse("no bind on the capture thread", body.contains("bindService"))
            assertFalse("no waiting on the capture thread", body.contains("Thread.sleep"))
            assertFalse("no allocation on the capture thread", body.contains("ByteArray("))
        }
        assertTrue("it hands blocks to the ring", offer.contains("ring.offer("))
    }

    @Test
    fun aFullRingGivesUpOnAutoStopRatherThanResumingAcrossAGap() {
        // Resuming after dropped audio breaks the model's recurrent continuity, which can turn resumed
        // speech into an early stop. Giving up costs the user a button press; resuming costs them words.
        val offer = bodyOf("private fun offerToDetector(")
        assertTrue(offer.contains("if (!ring.offer(pending, READ_BLOCK_BYTES)) {"))
        assertTrue(offer.contains("markSilenceUnavailable("))
    }

    @Test
    fun everyBindingFailureIsHandledAndTheDeadOnesUnbind() {
        // Android reconnects a disconnected binding on its own. Unbinding explicitly is what stops it
        // reconnecting into a take that already gave up on the detector.
        listOf("onServiceConnected", "onServiceDisconnected", "onNullBinding", "onBindingDied")
            .forEach { assertTrue("$it must be handled", service.contains("override fun $it(")) }

        val died = service.substringAfter("override fun onBindingDied(").substringBefore("}")
        val nulled = service.substringAfter("override fun onNullBinding(").substringBefore("}")
        assertTrue("a dead binding unbinds", died.contains("unbindVad()"))
        assertTrue("a null binding unbinds", nulled.contains("unbindVad()"))
    }

    @Test
    fun theFeederIsJoinedOnlyBriefly() {
        // A stalled detector may cost this take its auto-stop. It may not refuse the next recording.
        val release = bodyOf("private fun releaseSilenceDetection(")
        assertTrue(release.contains("thread.join(FEEDER_JOIN_MS)"))
        assertFalse("never an unbounded join", release.contains("thread.join()"))
        assertTrue("and it says so when it gives up", release.contains("abandoning it"))
    }

    @Test
    fun aDetectorResultIsCheckedAgainstTheLiveTakeBeforeItCanEndAnything() {
        val feeder = bodyOf("private fun feederLoop(")
        assertTrue("the session identity is checked", feeder.contains("if (session === active) endTake(TERMINAL_REASON_SILENCE)"))
        assertTrue("and the token travels with every call", feeder.contains("remote.processBlock(active.token, block)"))
        assertTrue(feeder.contains("remote.start(active.token, pauseSeconds)"))
    }

    @Test
    fun theDetectorRunsInItsOwnProcess() {
        assertTrue(
            manifest.contains("android:name=\".vad.SilenceVadService\"") &&
                manifest.contains("android:process=\":vad\""),
        )
        assertTrue("and is not reachable from outside the app", manifest.contains("android:exported=\"false\""))
    }

    @Test
    fun withAutoStopOffNoRingNoFeederAndNoDetectorProcess() {
        // The switch off must be exactly today's behaviour, not today's behaviour plus idle machinery.
        val start = bodyOf("private fun startRecording(")
        assertTrue(start.contains("if (autoStopOnSilence) startSilenceDetection(newSession, pauseSeconds)"))
        assertTrue(start.contains("ring = if (autoStopOnSilence) BlockRing(RING_BLOCKS, READ_BLOCK_BYTES) else null"))
    }
}
