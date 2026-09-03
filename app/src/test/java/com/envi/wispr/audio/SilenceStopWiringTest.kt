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
        // Resuming after dropped audio breaks the model's recurrent continuity, so speech that resumed
        // inside the gap could read as silence. The flag is what the feeder actually reads: setting a
        // status the feeder never checks would look like giving up while still processing stale blocks.
        val offer = bodyOf("private fun offerToDetector(")
        assertTrue(offer.contains("if (!ring.offer(pending, READ_BLOCK_BYTES)) {"))
        assertTrue("the flag the feeder reads, not a status it ignores", offer.contains("abandonDetector(active)"))
        assertTrue("and it stops feeding immediately", offer.contains("if (active.detectorAbandoned.get()) return"))

        val feeder = bodyOf("private fun feederLoop(")
        assertTrue("the feeder checks the flag", feeder.contains("active.detectorAbandoned.get()"))
        assertTrue("after every remote call, because a gap can open while one is in flight",
            feeder.contains("if (shouldStop()) return"))
    }

    @Test
    fun everyBindingFailureIsHandledAndTheDeadOnesUnbind() {
        // Android reconnects a disconnected binding on its own. Unbinding explicitly is what stops it
        // reconnecting into a take that already gave up on the detector.
        listOf("onServiceConnected", "onServiceDisconnected", "onNullBinding", "onBindingDied")
            .forEach { assertTrue("$it must be handled", service.contains("override fun $it(")) }

        val died = service.substringAfter("override fun onBindingDied(").substringBefore("}")
        val nulled = service.substringAfter("override fun onNullBinding(").substringBefore("}")
        assertTrue("a dead binding unbinds", died.contains("unbindVad(active)"))
        assertTrue("a null binding unbinds", nulled.contains("unbindVad(active)"))
    }

    @Test
    fun aCallbackFromAFinishedTakeCannotTouchTheOneRunningNow() {
        // The connection is built per take and closes over it, so a late callback has something to
        // compare against. A service-wide connection has nothing, and would clear the live take's
        // status or unbind its detector.
        assertTrue(service.contains("private fun vadConnectionFor(active: CaptureSession)"))
        val connected = service.substringAfter("override fun onServiceConnected(").substringBefore("override fun onServiceDisconnected(")
        assertTrue("a late connection refuses and unbinds", connected.contains("if (session !== active)"))
        assertTrue(connected.contains("unbindVad(active)"))
    }

    @Test
    fun captureTeardownNeverWaitsForTheDetector() {
        // The user is waiting for words that are already recorded. Interrupting a stalled binder call
        // does not unblock it, so any join here would charge the user for the detector's problem.
        val release = bodyOf("private fun releaseSession(")
        assertFalse("no join of any kind on the teardown path", release.contains(".join("))
        assertTrue("the file closes first", release.indexOf("closeResources(active)") < release.indexOf("detectorAbandoned"))
        assertTrue("then the feeder is told to stop and abandoned", release.contains("active.feederThread?.interrupt()"))
    }

    @Test
    fun aDetectorResultCanOnlyEndTheTakeItBelongsTo() {
        // Checking the session and THEN ending "the current one" leaves a window in which the old take
        // finishes and a new one starts, and the stale result stops the new recording. The name of the
        // take travels with the request instead, and is re-checked under the same lock that ends it.
        val feeder = bodyOf("private fun feederLoop(")
        assertTrue("the ending names its take", feeder.contains("endTake(active, TERMINAL_REASON_SILENCE)"))
        assertFalse("never end whatever happens to be current", feeder.contains("if (session === active) endTake("))
        assertTrue("and the token travels with every call", feeder.contains("remote.processBlock(active.token, block)"))
        assertTrue(feeder.contains("remote.start(active.token, pauseSeconds)"))

        val endTake = bodyOf("private fun endTake(expected: CaptureSession")
        assertTrue("the identity check and the claim share one hold of the lock",
            endTake.contains("synchronized(sessionLock)") && endTake.contains("if (session !== expected"))
    }

    @Test
    fun nothingAfterTheTakeEndsCanTellHowItEnded() {
        // The strongest statement available about insertion without a real editor in front of a person:
        // a silence-stopped take and a hand-stopped one are INDISTINGUISHABLE to everything downstream,
        // so transcription, polish and insertion cannot behave differently after one.
        //
        // Enumerated from the producer rather than from a guess: every reader of the ending in the whole
        // app, then the absence of any reader inside the path that runs afterwards.
        val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

        val readers = Regex("CaptureEnding\\.fromAidl\\(").findAll(session).count()
        assertEquals("the ending is classified in exactly one place", 1, readers)

        val afterTheEnding = session.substringAfter("private fun stopAndTranscribe(")
        val downstream = afterTheEnding.substringBefore("\n    private fun ")
        listOf("terminalReason", "CaptureEnding", "TERMINAL_REASON").forEach { name ->
            assertFalse(
                "$name must not be visible to the path that runs after a take ends",
                downstream.contains(name),
            )
        }
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
    fun anOutOfRangePauseRefusesAutoStopRatherThanSubstitutingANumber() {
        // A value outside the slider's range arriving over the binder means a caller we do not control.
        // Quietly using 1.5 would give that caller a detector configured with a number nobody chose.
        val start = bodyOf("private fun startRecording(")
        assertTrue(start.contains("it >= SilenceStopDetector.MIN_PAUSE_SECONDS"))
        assertTrue(start.contains("it <= SilenceStopDetector.MAX_PAUSE_SECONDS"))
        assertTrue("requested is not the same as enabled", start.contains("val detectorEnabled = autoStopOnSilence && validPause != null"))
        assertTrue("and the user is told, because they asked for it and cannot have it",
            start.contains("newSession.silenceStatus.set(SILENCE_STATUS_UNAVAILABLE)"))
        assertTrue("no detector is built for a refused request", start.contains("if (detectorEnabled) startSilenceDetection("))
    }

    @Test
    fun losingTheDetectorAfterItWorkedIsSilentButLosingItBeforeIsNot() {
        // Both are "unavailable" to the code. Only one is worth interrupting someone for: a take that
        // never got a detector, versus a take whose recording is still perfectly correct.
        val abandon = bodyOf("private fun abandonDetector(")
        assertTrue(abandon.contains("SILENCE_STATUS_READY -> SILENCE_STATUS_LOST_AFTER_READY"))
        assertTrue("and the first landing wins, so a second failure cannot rewrite it",
            abandon.contains("compareAndSet(previous, next)"))
        assertTrue("an already-terminal status is left alone",
            abandon.contains("SILENCE_STATUS_UNAVAILABLE,\n                SILENCE_STATUS_LOST_AFTER_READY -> return"))

        val notice = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
        assertTrue("only the never-became-available status speaks",
            notice.contains("!= AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return"))
    }

    @Test
    fun aStoppedTakeWithNoEndingIsNotTranscribed() {
        // Capture that stopped without publishing a reason has no success to report, and the type says
        // so. Grouping it with the successes sends partial audio on as though it were finished.
        assertFalse(CaptureEnding.StillRunning.transcribes)
        val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
        val terminal = session.substringAfter("when (CaptureEnding.fromAidl").substringBefore("break")
        val failureArm = terminal.substringBefore("CaptureEnding.Manual")
        assertTrue("StillRunning belongs in the failure arm", failureArm.contains("CaptureEnding.StillRunning"))
    }

    @Test
    fun theCaptureThreadDoesNotLogWhenTheDetectorFallsBehind() {
        // Logging is work, and the capture thread must do none that can make it late. The feeder does
        // the reporting, off this thread.
        val offer = bodyOf("private fun offerToDetector(")
        assertFalse(offer.contains("DebugLogger"))
    }

    @Test
    fun theReadIsExplicitlyBlockingRatherThanRelyingOnADefault() {
        val loop = bodyOf("private fun captureLoop(")
        assertTrue(loop.contains("AudioRecord.READ_BLOCKING"))
    }

    @Test
    fun withAutoStopOffNoRingNoFeederAndNoDetectorProcess() {
        // The switch off must be exactly today's behaviour, not today's behaviour plus idle machinery.
        val start = bodyOf("private fun startRecording(")
        // detectorEnabled requires autoStopOnSilence, so the switch being off is sufficient on its own.
        assertTrue(start.contains("val detectorEnabled = autoStopOnSilence &&"))
        assertTrue(start.contains("if (detectorEnabled) startSilenceDetection("))
        assertTrue(start.contains("ring = if (detectorEnabled) BlockRing(RING_BLOCKS, READ_BLOCK_BYTES) else null"))
        assertTrue(start.contains("pendingBlock = if (detectorEnabled) ByteArray(READ_BLOCK_BYTES) else null"))
    }
}
