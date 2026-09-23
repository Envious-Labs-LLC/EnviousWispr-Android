package com.envi.wispr.audio

import com.envi.wispr.ui.SessionSources
import org.junit.Assert.assertEquals
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
    /** Since #188 the route (gate, deadline, sink watch) and the warm hold are owners of their own. */
    private val route = File("src/main/java/com/envi/wispr/audio/TakeRoute.kt").readText()
    private val hold = File("src/main/java/com/envi/wispr/audio/WarmHoldOwner.kt").readText()
    /**
     * Since #186 the owner is the coordinator; the three connections live in `PipelineBindings` and the
     * preference writes in `SessionPreferencesSource`. Each pin below reads the file its statement moved to.
     */
    private val session = SessionSources.coordinator
    /** Since #216 the lane, the listener and both timers are the owner's `CaptureSessionController`. */
    private val captureSide = SessionSources.capture
    private val preferences = File("src/main/java/com/envi/wispr/ui/SessionPreferencesSource.kt").readText()
    private val bindings = File("src/main/java/com/envi/wispr/ui/PipelineBindings.kt").readText()

    private fun body(source: String, head: String): String =
        source.substringAfter(head).substringBefore("\n    private fun ").substringBefore("\n    override fun ")

    /** One function of an owner class, where every member carries its own KDoc and most are public. */
    private fun owned(source: String, head: String): String {
        assertTrue("$head must exist", source.contains(head))
        return source.substringAfter(head).substringBefore("\n    /**").substringBefore("\n    private fun ").substringBefore("\n    fun ")
    }

    @Test
    fun bytesBeforeLiveAreNotWrittenAndFeedNothingButTheGate() {
        val loop = body(capture, "private fun captureLoop(")
        val gateCheck = loop.indexOf("if (active.route.gate.state == LiveGate.State.WAITING)")
        val write = loop.indexOf("active.output.write(buffer, 0, bytesRead)")
        val detector = loop.indexOf("active.detector.offer(buffer, bytesRead, position)")
        val spectrum = loop.indexOf("active.picture.offer(")
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
        assertTrue("the timer counts from live", elapsed.contains("active?.route?.liveAtMs") && !elapsed.contains("startedAtMs"))
        val start = body(capture, "private fun startRecording(")
        assertTrue(
            "the recorder starts before its route clock is captured, as before #188",
            start.indexOf("record.startRecording()") in 0 until start.indexOf("takeRoute.markRecorderStarted(SystemClock.elapsedRealtime())"),
        )
    }

    @Test
    fun theDeadlineIsAClockOnTheRouteThreadAndResetsOnlyWhileTheSinkIsOffered() {
        val arm = owned(route, "fun armDeadline(")
        assertTrue(arm.contains("scheduler.postDelayed(runnable, LiveGate.DEADLINE_MS)"))
        assertTrue("the second deadline is re-armed after the reset", arm.contains("scheduler.postDelayed(this, LiveGate.DEADLINE_MS)"))
        assertTrue("the scheduler is the service's route thread", capture.contains("override fun postDelayed(runnable: Runnable, delayMs: Long) { routeHandler.postDelayed(runnable, delayMs) }"))
        assertTrue("forcing asks whether the observed route may be forced", arm.contains("if (admissible())"))
        assertTrue("an earbud target routed to the phone refuses instead", arm.contains("onRefused()"))
        val start = body(capture, "private fun startRecording(")
        assertTrue("and the service turns the refusal into a failed start", start.substringAfter("onRefused = {").substringBefore("}").contains("lastStartFailure = START_FAILURE_EARBUDS"))
        val reset = owned(route, "private fun reset(audioManager: AudioManager): Boolean")
        assertTrue(reset.contains("availableCommunicationDevices.any"))
        assertTrue(reset.contains("clearCommunicationDevice()") && reset.contains("setCommunicationDevice(sink)"))
    }

    @Test
    fun aRefusedLinkNoLongerResolvesOntoThePhone() {
        val resolve = route.substringAfter("fun resolve(").substringBefore("\n    }\n")
        assertFalse(resolve.contains("allowBluetooth = false"))
        assertTrue(resolve.contains("reason = InputRouteReason.LINK_REFUSED"))
    }

    @Test
    fun theHoldStartsFromTheOneReleasePathAndOnlyForFinishedTakes() {
        val release = body(capture, "private fun releaseSession(")
        assertTrue(release.contains("holding = warmHoldOwner.eligible(active.route, active.endingClaim.ending, active.keepEarbudsReady, destroyed) &&\n                warmHoldOwner.start(active.route)"))
        assertTrue(release.contains("closeResources(active, keepRoute = holding)"))
        assertTrue("a hold keeps the service alive", release.contains("if (!holding) stopSelf()"))
        val eligible = owned(hold, "fun eligible(")
        assertTrue(eligible.contains("CaptureEnding.Manual, CaptureEnding.Silence, CaptureEnding.MaxDuration -> true"))
        assertTrue(eligible.contains("CaptureEnding.StillRunning, CaptureEnding.Failure -> false"))
        assertTrue("the setting is read from the take, frozen at start", eligible.contains("if (!keepEarbudsReady || !route.targetBluetooth || route.sink == null) return false"))
        assertTrue("only a take that ended on Bluetooth holds", eligible.contains("route.effective.currentKind != InputRouteKind.BLUETOOTH) return false"))
    }

    @Test
    fun finishTakeGivesTheServiceAStartedLifetimeAndTheStartIsNotSticky() {
        val finish = owned(hold, "fun finishTake(): Boolean {")
        assertTrue("the hold keeps the service through the injected edge", finish.contains("keepAlive()"))
        assertTrue("which is a started lifetime", capture.contains("keepAlive = { startService(Intent(this, AudioCaptureService::class.java)) }"))
        // #220: both interfaces call one helper, which takes the session lock.
        assertTrue("the helper calls it under the session lock", capture.contains("private fun finishTakeHold(): Boolean = synchronized(sessionLock) { warmHoldOwner.finishTake() }"))
        assertEquals("and both binders call that helper", 2, Regex("override fun finishTake\\(\\): Boolean = this@AudioCaptureService\\.finishTakeHold\\(\\)").findAll(capture).count())
        val start = body(capture, "override fun onStartCommand(")
        assertTrue(start.contains("return START_NOT_STICKY"))
        assertTrue(start.contains("if (!warmHoldOwner.isActive && session == null) stopSelf()"))
        assertTrue(capture.contains("synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }"))
        assertTrue("the owner's close is the hold's end", owned(hold, "fun close(reason: String)").contains("warmHold?.end(reason)"))
        val destroy = capture.substringAfter("override fun onDestroy()")
        assertTrue("the flag precedes the stop that could end a take", destroy.indexOf("destroyed = true") < destroy.indexOf("stopRecording()"))
        assertTrue("no hold may start after teardown began", owned(hold, "fun eligible(").contains("if (destroyed) return false"))
        assertTrue("a hold started before the join is ended before the route thread quits",
            destroy.lastIndexOf("warmHoldOwner.close(WarmHold.END_DESTROYED)") < destroy.indexOf("routeThread.quitSafely()"))
    }

    @Test
    fun removedEarbudsAdmitThePhoneAndADeadAudioProcessEndsAStartingTake() {
        val admissible = owned(route, "fun admissible(): Boolean =")
        assertTrue("disconnected earbuds are the one case the phone may record", admissible.contains("sinkGone"))
        val start = body(capture, "private fun startRecording(")
        assertTrue(start.contains("takeRoute.watchSink(audioManager, routeHandler)"))
        val watch = owned(route, "fun watchSink(")
        assertTrue("the device list is reconciled after registering, so a removal in between is not missed",
            watch.indexOf("registerAudioDeviceCallback") < watch.indexOf("availableCommunicationDevices.any"))
        assertTrue("release stops the watch first", body(capture, "private fun releaseSession(").contains("active.route.stopWatching()"))
        assertTrue("and stopping it unregisters the callback", owned(route, "fun stopWatching()").contains("unregisterDeviceCallback(it)"))
        val disconnect = body(session, "override fun onCaptureDisconnected()")
        assertTrue(disconnect.contains("seen == SessionState.RECORDING || seen == SessionState.STARTING"))
        // The link is cleared BEFORE the owner hears of the death, as the proxy field was (Codex review C1).
        val clear = bindings.indexOf("capture = null")
        val notify = bindings.indexOf("listener?.onCaptureDisconnected()")
        assertTrue("the capture link must be cleared before the disconnect listener runs", clear >= 0 && notify > clear)
    }

    @Test
    fun everyTransitionOutOfStartingOrRecordingTakesThePublishLock() {
        // The producers of the race class Codex review 3 named: enumerated from the CAS sites, not
        // from the findings. A new CAS out of STARTING or RECORDING outside the lock fails here.
        val casLines = Regex("compareAndSet\\(SessionState\\.(STARTING|RECORDING), SessionState\\.\\w+\\)").findAll(session).count()
        val lockedCas = Regex("synchronized\\(publishLock\\) \\{\\s*\\n\\s*if \\(!state\\.compareAndSet\\(SessionState\\.(STARTING|RECORDING), SessionState\\.\\w+\\)\\)").findAll(session).count()
        assertTrue("every CAS out of STARTING/RECORDING ($casLines) sits under publishLock ($lockedCas)", casLines == lockedCas)
        // Since #115 the live event arrives on a binder thread and is posted to main, where commands
        // are dispatched, before it publishes.
        assertTrue(captureSide.contains("private val takeListener = object : TakeListener {"))
        val listener = captureSide.substringAfter("private val takeListener = object : TakeListener {").substringBefore("\n    }\n")
        assertTrue("live is posted to the main thread, for this take's events only", listener.contains("host.postToMain { if (ours(takeId)) { rearmSilenceBound(); events(CaptureEvent.Live(forced, routeKind, routeReason, liveAfterMs)) } }"))
        // Since #216 the controller reports and the owner publishes: the event must reach publishLive.
        assertTrue("the owner publishes the reported live", body(session, "private fun onCaptureEvent(").contains("is CaptureEvent.Live -> publishLive(event.forced, event.routeKind, event.routeReason, event.liveAfterMs)"))
        assertTrue(body(session, "private fun publishLive(").contains("check(host.onMainThread())"))
        val deadline = body(session, "private fun onLiveDeadline()")
        assertTrue("the deadline claims failure before any cleanup", deadline.indexOf("failWhileStarting(") in 0 until deadline.indexOf("capture.stop("))
        assertTrue("the controller's deadline only reports", captureSide.contains("private val liveDeadline = Runnable { events(CaptureEvent.LiveDeadlinePassed) }"))
        assertTrue("and the owner handles the report", body(session, "private fun onCaptureEvent(").contains("CaptureEvent.LiveDeadlinePassed -> onLiveDeadline()"))
        assertFalse("the deadline never overwrites another owner with showError", deadline.contains("showError("))
    }

    @Test
    fun theHeldIdentityIsReadBeforeTheHandoverClearsIt() {
        val handOver = owned(hold, "fun handOver(): HandedRoute?")
        val read = handOver.indexOf("val type = heldSinkType")
        val hand = handOver.indexOf("hold.handOver()")
        assertTrue(read in 0 until hand)
        assertTrue("the service takes the handed route from the owner", body(capture, "private fun startRecording(").contains("val handedOver = warmHoldOwner.handOver()"))
        val resolve = route.substringAfter("fun resolve(").substringBefore("\n    }\n")
        assertTrue(resolve.contains("handedOver.sinkType == sink.type && handedOver.sinkName == sink.name"))
    }

    @Test
    fun theSessionCarriesTheSavedSettingAndWaitsForLiveUnderTheLock() {
        assertTrue(preferences.contains("keepEarbudsReady = preferences.keepEarbudsReady"))
        // Since #193 the start call reads the take's FROZEN snapshot, never the live source; since #115
        // the snapshot is taken on main and the call runs on the capture command lane.
        val start = body(session, "private fun tryStartRecording()")
        assertTrue("the snapshot is read on main and handed to the start", start.indexOf("val preferences = sessionPreferences") in 0 until start.indexOf("capture.start(preferences)"))
        // Since #216 the lane body is the controller's: it must pass THAT snapshot's fields on.
        val lane = owned(captureSide, "fun start(preferences: SessionPreferences): Boolean {")
        assertTrue("the controller's start issues the command", lane.contains("command(\"start\")"))
        listOf("preferences.autoStopOnSilence", "preferences.silencePauseSeconds", "preferences.inputDevicePick", "preferences.keepEarbudsReady", "id,").forEach {
            assertTrue("the start call carries $it", lane.substringAfter("startCaptureForTake(").substringBefore(")").contains(it))
        }
        val publish = body(session, "private fun publishLive(")
        assertTrue(publish.contains("synchronized(publishLock)"))
        assertTrue(publish.indexOf("compareAndSet(SessionState.STARTING, SessionState.RECORDING)") < publish.indexOf("surface.show()"))
        val destroy = session.substringAfter("fun destroy()")
        assertTrue("teardown invalidates under the same lock, before cleanup", destroy.indexOf("synchronized(publishLock)") < destroy.indexOf("serviceJob.cancel()"))
        // Since #115 there is no waiter thread: the STARTING bound is a main-thread deadline armed before
        // the start command and cancelled at live; the live event itself carries the transition.
        assertTrue("the live deadline is armed before the start command", lane.indexOf("host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)") in 0 until lane.indexOf("startCaptureForTake("))
        assertTrue("the deadline fails the take only while STARTING", body(session, "private fun onLiveDeadline()").contains("if (state.get() != SessionState.STARTING) return"))
        assertTrue("live cancels the deadline", publish.contains("capture.cancelLiveDeadline()"))
        assertTrue("which cancels exactly that runnable", owned(captureSide, "fun cancelLiveDeadline() {").contains("host.cancelMainDelayed(liveDeadline)"))
        assertFalse("no waiter thread remains", SessionSources.all.contains("waitForLive") || SessionSources.all.contains("LiveWaiter"))
    }

    @Test
    fun theHistoryDurationIsTheFileLengthReadBeforeTranscriptionCanDeleteIt() {
        // Since #115 the file arrives CLOSED on the ending event; the duration is read from it there.
        val stop = body(session, "private fun continueAfterEnding(")
        val duration = stop.indexOf("PcmAudio.durationSeconds(File(it).length())")
        assertTrue(duration > 0)
        assertTrue("read from the ending's path", stop.indexOf("val audioFilePath = ending.audioFilePath") in 0 until duration)
        assertTrue("and before the take is handed to the service", duration < stop.indexOf("finishTakeOrStop()"))
        assertFalse("no wall-clock fallback", stop.contains("System.currentTimeMillis() - recordingStartedAtMs"))
    }

    @Test
    fun aCancelledTakeStillHandsOverAndFailurePathsStillStop() {
        val cancel = body(session, "private fun cancelCaptureAndFinish(")
        assertTrue(cancel.contains("finishTakeOrStop()"))
        val error = body(session, "private fun announceError(")
        assertTrue(error.contains("pipeline.stopAudioService()"))
        val finish = owned(captureSide, "fun finishTakeOrStop() {")
        assertTrue(finish.contains("if (!held) pipeline.stopAudioService()"))
    }
}
