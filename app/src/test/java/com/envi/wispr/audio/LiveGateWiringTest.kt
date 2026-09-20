package com.envi.wispr.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the gate and the hold are wired into the two services, read from the source like the other
 * wiring guards (the capture loop is a thread inside an Android Service and has no unit seam).
 * Each assertion names the property that a plausible edit would silently remove.
 */
class LiveGateWiringTest {
    private val capture = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    private fun body(source: String, head: String): String =
        source.substringAfter(head).substringBefore("\n    private fun ").substringBefore("\n    override fun ")

    @Test
    fun bytesBeforeLiveAreNotWrittenAndFeedNothingButTheGate() {
        val loop = body(capture, "private fun captureLoop(")
        val gateCheck = loop.indexOf("if (active.gate.state == LiveGate.State.WAITING)")
        val write = loop.indexOf("active.output.write(buffer, 0, bytesRead)")
        val detector = loop.indexOf("offerToDetector(active, buffer, bytesRead, position)")
        val spectrum = loop.indexOf("active.spectrumRing.offer(")
        val amplitude = loop.indexOf("currentAmplitude =")
        assertTrue("the gate is consulted before the write", gateCheck in 0 until write)
        assertTrue("a waiting read skips the rest of the loop", loop.substring(gateCheck, write).contains("else continue"))
        assertTrue("the detector, the picture and the level all sit after the write", detector > write && spectrum > write && amplitude > write)
        assertTrue("the first written block makes the verdict visible", loop.indexOf("active.liveVisible = true") > write)
    }

    @Test
    fun theBinderReportsReadyOnlyAfterTheFirstSavedByte() {
        val getter = body(capture, "override fun getLiveState(): Int {")
        assertTrue(getter.contains("if (!active.liveVisible) return LIVE_WAITING"))
        val elapsed = body(capture, "override fun getElapsedMs(): Long {")
        assertTrue("the timer counts from live", elapsed.contains("active?.liveAtMs") && !elapsed.contains("startedAtMs"))
    }

    @Test
    fun theDeadlineIsAClockOnTheRouteThreadAndResetsOnlyWhileTheSinkIsOffered() {
        val arm = body(capture, "private fun armDeadline(")
        assertTrue(arm.contains("routeHandler.postDelayed(runnable, LiveGate.DEADLINE_MS)"))
        assertTrue("the second deadline is re-armed after the reset", arm.contains("routeHandler.postDelayed(this, LiveGate.DEADLINE_MS)"))
        assertTrue("forcing asks whether the observed route may be forced", arm.contains("if (routeAdmissible(active))"))
        assertTrue("an earbud target routed to the phone fails instead", arm.contains("lastStartFailure = START_FAILURE_EARBUDS"))
        val reset = body(capture, "private fun resetCommunicationDevice(")
        assertTrue(reset.contains("availableCommunicationDevices.any"))
        assertTrue(reset.contains("clearCommunicationDevice()") && reset.contains("setCommunicationDevice(sink)"))
    }

    @Test
    fun aRefusedLinkNoLongerResolvesOntoThePhone() {
        val resolve = body(capture, "private fun resolveRoute(")
        assertFalse(resolve.contains("allowBluetooth = false"))
        assertTrue(resolve.contains("reason = InputRouteReason.LINK_REFUSED"))
    }

    @Test
    fun theHoldStartsFromTheOneReleasePathAndOnlyForFinishedTakes() {
        val release = body(capture, "private fun releaseSession(")
        assertTrue(release.contains("holding = holdEligible(active) && startWarmHold(active)"))
        assertTrue(release.contains("closeResources(active, keepRoute = holding)"))
        assertTrue("a hold keeps the service alive", release.contains("if (!holding) stopSelf()"))
        val eligible = body(capture, "private fun holdEligible(")
        assertTrue(eligible.contains("CaptureEnding.Manual, CaptureEnding.Silence, CaptureEnding.MaxDuration -> true"))
        assertTrue(eligible.contains("CaptureEnding.StillRunning, CaptureEnding.Failure -> false"))
        assertTrue("the setting is read from the take, frozen at start", eligible.contains("active.keepEarbudsReady"))
        assertTrue("only a take that ended on Bluetooth holds", eligible.contains("active.effective.currentKind != InputRouteKind.BLUETOOTH) return false"))
    }

    @Test
    fun finishTakeGivesTheServiceAStartedLifetimeAndTheStartIsNotSticky() {
        val finish = body(capture, "private fun finishTake(): Boolean {")
        assertTrue(finish.contains("startService(Intent(this, AudioCaptureService::class.java))"))
        val start = body(capture, "override fun onStartCommand(")
        assertTrue(start.contains("return START_NOT_STICKY"))
        assertTrue(capture.contains("synchronized(sessionLock) { warmHold?.end(WarmHold.END_DESTROYED) }"))
        val destroy = capture.substringAfter("override fun onDestroy()")
        assertTrue("the flag precedes the stop that could end a take", destroy.indexOf("destroyed = true") < destroy.indexOf("stopRecording()"))
        assertTrue("no hold may start after teardown began", body(capture, "private fun holdEligible(").contains("if (destroyed) return false"))
        assertTrue("a hold started before the join is ended before the route thread quits",
            destroy.lastIndexOf("warmHold?.end(WarmHold.END_DESTROYED)") < destroy.indexOf("routeThread.quitSafely()"))
    }

    @Test
    fun removedEarbudsAdmitThePhoneAndADeadAudioProcessEndsAStartingTake() {
        val admissible = body(capture, "private fun routeAdmissible(")
        assertTrue("disconnected earbuds are the one case the phone may record", admissible.contains("active.sinkGone"))
        val start = body(capture, "private fun startRecording(")
        assertTrue(start.contains("watchSink(newSession)"))
        val watch = body(capture, "private fun watchSink(")
        assertTrue("the device list is reconciled after registering, so a removal in between is not missed",
            watch.indexOf("registerAudioDeviceCallback") < watch.indexOf("availableCommunicationDevices.any"))
        assertTrue(body(capture, "private fun releaseSession(").contains("unregisterAudioDeviceCallback(w)"))
        val disconnect = session.substringAfter("private val audioConnection").substringBefore("private val asrConnection")
        assertTrue(disconnect.contains("seen == SessionState.RECORDING || seen == SessionState.STARTING"))
    }

    @Test
    fun everyTransitionOutOfStartingOrRecordingTakesThePublishLock() {
        // The producers of the race class Codex review 3 named: enumerated from the CAS sites, not
        // from the findings. A new CAS out of STARTING or RECORDING outside the lock fails here.
        val casLines = Regex("compareAndSet\\(SessionState\\.(STARTING|RECORDING), SessionState\\.\\w+\\)").findAll(session).count()
        val lockedCas = Regex("synchronized\\(publishLock\\) \\{\\s*\\n\\s*if \\(!state\\.compareAndSet\\(SessionState\\.(STARTING|RECORDING), SessionState\\.\\w+\\)\\)").findAll(session).count()
        assertTrue("every CAS out of STARTING/RECORDING ($casLines) sits under publishLock ($lockedCas)", casLines == lockedCas)
        val wait = body(session, "private fun waitForLive()")
        assertTrue("publication is posted to the main thread, where commands are dispatched", wait.contains("mainHandler.post { publishLive(forced) }"))
        assertTrue(body(session, "private fun publishLive(").contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue("the waiter claims failure before any cleanup", wait.indexOf("failWhileStarting(") < wait.indexOf("waitForFileReady"))
        assertFalse("the waiter never overwrites another owner with showError", wait.contains("showError("))
    }

    @Test
    fun theHeldIdentityIsReadBeforeTheHandoverClearsIt() {
        val start = body(capture, "private fun startRecording(")
        val read = start.indexOf("val type = heldSinkType")
        val hand = start.indexOf("hold.handOver()")
        assertTrue(read in 0 until hand)
        val resolve = body(capture, "private fun resolveRoute(")
        assertTrue(resolve.contains("handedOver.sinkType == sink.type && handedOver.sinkName == sink.name"))
    }

    @Test
    fun theSessionCarriesTheSavedSettingAndWaitsForLiveUnderTheLock() {
        assertTrue(session.contains("keepEarbudsReady = preferences.keepEarbudsReady"))
        assertTrue(session.contains("startCaptureForTake(autoStopOnSilence, silencePauseSeconds, inputDevicePick, keepEarbudsReady, takeId)"))
        val publish = body(session, "private fun publishLive(")
        assertTrue(publish.contains("synchronized(publishLock)"))
        assertTrue(publish.indexOf("compareAndSet(SessionState.STARTING, SessionState.RECORDING)") < publish.indexOf("RecordingOverlayState.show()"))
        val destroy = session.substringAfter("override fun onDestroy()")
        assertTrue("teardown invalidates under the same lock, before cleanup", destroy.indexOf("synchronized(publishLock)") < destroy.indexOf("serviceJob.cancel()"))
        val wait = body(session, "private fun waitForLive()")
        listOf("state.get() != SessionState.STARTING", "audioService ?: return", "!capturing", "LIVE_WAIT_BOUND_MS").forEach {
            assertTrue("the waiter has its exit: $it", wait.contains(it))
        }
    }

    @Test
    fun theHistoryDurationIsTheFileLengthReadBeforeTranscriptionCanDeleteIt() {
        val stop = body(session, "private fun stopAndTranscribe()")
        val duration = stop.indexOf("PcmAudio.durationSeconds(File(it).length())")
        assertTrue(duration > 0)
        assertTrue("read after the file is ready", duration > stop.indexOf("waitForFileReady(2_000L)"))
        assertTrue("and before the take is handed to the service", duration < stop.indexOf("finishTakeOrStop()"))
        assertFalse("no wall-clock fallback", stop.contains("System.currentTimeMillis() - recordingStartedAtMs"))
    }

    @Test
    fun aCancelledTakeStillHandsOverAndFailurePathsStillStop() {
        val cancel = body(session, "private fun cancelCaptureAndFinish(")
        assertTrue(cancel.contains("finishTakeOrStop()"))
        val error = body(session, "private fun announceError(")
        assertTrue(error.contains("stopAudioCaptureService()"))
        val finish = body(session, "private fun finishTakeOrStop()")
        assertTrue(finish.contains("if (!held) stopAudioCaptureService()"))
    }
}
