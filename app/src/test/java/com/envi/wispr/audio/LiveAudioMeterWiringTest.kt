package com.envi.wispr.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard, not product coverage.
 *
 * The defect this exists for is the one issue #44 recorded: the level was computed correctly for months
 * and no route carried it to the screen, so every part looked right on its own. Each link of the route
 * from the audio process to the rail is asserted here because the broken state compiles, passes every
 * other test, and shows nothing. #151 replaced the scalar level with a per-band picture and the polling
 * tick's meter step with a thread of its own; #187 replaced that thread's thirty polls a second with one
 * picture pushed per analyser wake over a oneway listener; the links changed, the reason for guarding
 * them did not.
 *
 * Source-level because the two ends are an Android `Service` and a `View` attached to a `WindowManager`,
 * neither of which a JVM test can stand up.
 */
class LiveAudioMeterWiringTest {

    private val capture = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    /** The picture's owner since #188: ring, analyser thread, published bands and the push. */
    private val picture = File("src/main/java/com/envi/wispr/audio/PicturePublisher.kt").readText()
    /** The owner since #186: the meter moved from the Service to the coordinator with its seams (`surface` over the overlay state). */
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
    private val recorder = File("src/main/java/com/envi/wispr/ui/RecorderSurface.kt").readText()
    private val overlayState = File("src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt").readText()
    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val meterView = File("src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt").readText()
    private val aidl = File("src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl").readText()
    private val listenerAidl = File("src/main/aidl/com/envi/wispr/audio/IAudioSpectrumListener.aidl").readText()
    private val bindings = File("src/main/java/com/envi/wispr/ui/PipelineBindings.kt").readText()

    /** The text of one named function, and a loud failure if the name is gone. */
    private fun body(source: String, declaration: String): String {
        assertTrue("$declaration must exist; a renamed function would otherwise be inspected as unrelated text", source.contains(declaration))
        return source.substringAfter(declaration).substringBefore("\n    /**")
    }

    @Test
    fun theOwnerSubscribesToThePictureAndNeverPollsIt() {
        val listen = body(session, "private fun listenForPicture()")
        assertTrue("the serial is captured once, when the take starts", listen.contains("val takeSerial = surface.currentTakeSerial()"))
        assertTrue(
            "the owner registers a listener that stamps that serial on every picture",
            listen.contains("pipeline.capture?.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }"),
        )
        assertTrue("and registering cannot end the take", listen.contains("runCatching {\n            pipeline.capture?.listenForSpectrum"))
        // The seam is only as good as its production delegate (Codex review C1, 2026-09-20).
        assertTrue(recorder.contains("override fun updateBands(takeSerial: Long, bands: FloatArray) = RecordingOverlayState.updateBands(takeSerial, bands)"))
        assertTrue(recorder.contains("override fun currentTakeSerial(): Long = RecordingOverlayState.snapshots.value.takeSerial"))
        val polling = body(session, "private fun startPolling()")
        assertTrue("the polling thread registers the listener once the take is live", polling.contains("listenForPicture()"))
        assertTrue("and no meter thread remains", !session.contains("DictationMeterThread") && !session.contains("METER_INTERVAL_MS"))
    }

    @Test
    fun theOwnerUnsubscribesWhereEverySessionEnds() {
        val finish = body(session, "private fun finishSession()")
        val unsubscribe = finish.indexOf("runCatching { pipeline.capture?.stopListeningForSpectrum() }")
        val unbind = finish.indexOf("pipeline.unbind()")
        assertTrue("finishSession unregisters the listener", unsubscribe >= 0)
        assertTrue("before it unbinds", unbind > unsubscribe)
    }

    @Test
    fun nothingInProductionReadsThePicture() {
        // architecture-rules.md RULE: no-idle-cost — surfaces are pushed a picture, they never poll for
        // one. Since #187 nothing polls: the only mention of the legacy getter in production is the Stub
        // override that keeps the transaction alive. Repository-wide, so a poller reintroduced anywhere
        // (the proxy, a screen, a service) turns this red, not only one in the owner.
        val production = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the production tree must be readable", production.size > 100)
        val readers = production.filter { file ->
            val text = file.readText()
            text.contains("spectrumBands") || text.contains("getSpectrumBands")
        }.map { it.path }
        assertTrue(
            "only the audio service may mention the picture getter, found $readers",
            readers == listOf("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt"),
        )
        val mentions = Regex("getSpectrumBands").findAll(capture).count()
        assertTrue("and there only its Stub override, found $mentions", mentions == 1)
        assertTrue(
            "the recorder must not reach for the capture service itself",
            !overlay.contains("currentAmplitude") && !meterView.contains("currentAmplitude"),
        )
    }

    @Test
    fun theProxyRegistersAndUnregistersTheSameStub() {
        val proxy = bindings.substringAfter("private class CaptureProxy(")
        assertTrue("the proxy keeps the Stub it registered", proxy.contains("@Volatile private var spectrumStub: IAudioSpectrumListener.Stub? = null"))
        val listen = proxy.substringAfter("override fun listenForSpectrum(listener: SpectrumListener) {").substringBefore("override fun stopListeningForSpectrum()")
        assertTrue("it forwards every picture to the Kotlin listener", listen.contains("listener.onSpectrum(bands ?: FloatArray(0))"))
        assertTrue("stores the Stub before registering it", listen.indexOf("spectrumStub = stub") in 0 until listen.indexOf("service.registerSpectrumListener(stub)"))
        val stop = proxy.substringAfter("override fun stopListeningForSpectrum() {").substringBefore("override fun effectiveInputDevice()")
        assertTrue("and unregisters exactly the stored Stub", stop.contains("val stub = spectrumStub ?: return") && stop.contains("service.unregisterSpectrumListener(stub)"))
    }

    @Test
    fun theAnalyserPushesOutsideTheLockAndNeverFromCapture() {
        // A sequential test cannot open the window between a half-written picture and its reader
        // (validation-discipline.md RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act),
        // so the lock is asserted at the source: the copy in and the copy out both sit inside it, and
        // the push (a binder transaction) sits after the lock closes.
        val loop = body(picture, "private fun analyserLoop(stillLive: () -> Boolean)")
        val copyIn = "synchronized(bandsLock) {\n                        System.arraycopy(bands, 0, publishedBands, 0, SpectrumAnalyzer.BAND_COUNT)\n                    }"
        assertTrue("the analyser copies its picture in under the lock", loop.contains(copyIn))
        assertTrue(
            "and pushes it after the lock closes, never inside it",
            loop.substringAfter(copyIn).trimStart().startsWith("// Outside the lock") &&
                loop.substringAfter(copyIn).substringBefore("LockSupport.parkNanos").contains("pushSpectrum(bands)"),
        )
        assertTrue(
            "a failure zeros the local picture and publishes it both ways before leaving",
            loop.contains("bands.fill(0f)\n            synchronized(bandsLock) { publishedBands.fill(0f) }\n            pushSpectrum(bands)"),
        )
        val push = body(picture, "private fun pushSpectrum(bands: FloatArray)")
        assertTrue("the push reads the slot once", push.contains("val target = listener.get() ?: return"))
        assertTrue("a dead client clears only the listener it was pushing to", push.contains("listener.compareAndSet(target, null)"))
        val captureLoop = body(capture, "private fun captureLoop(active: CaptureSession)")
        assertTrue("the capture thread never touches the listener", !captureLoop.contains("spectrumListener") && !captureLoop.contains("listener"))
        val offer = body(picture, "fun offer(buffer: ByteArray, bytesRead: Int, position: Long)")
        assertTrue("and its offer into the picture takes no lock", !offer.contains("synchronized") && !offer.contains("bandsLock"))
        assertTrue("the getter copies out under the same lock", body(picture, "fun snapshot(): FloatArray").contains("return synchronized(bandsLock) { publishedBands.copyOf() }"))
        assertTrue(
            "and answers a full-length empty picture when no take is open, never an empty array",
            capture.contains("?: return FloatArray(SpectrumAnalyzer.BAND_COUNT)") && capture.contains("return active.picture.snapshot()"),
        )
        assertTrue("the capture thread never takes that lock", !captureLoop.contains("bandsLock"))
    }

    @Test
    fun theAnalyserDrainsTheRingWithPositionsAndPublishesOncePerWake() {
        val loop = body(picture, "private fun analyserLoop(stillLive: () -> Boolean)")
        assertTrue("every queued chunk is analysed before publishing", loop.contains("while (true) {\n                    val length = spectrumRing.poll(chunk)\n                    if (length < 0) break"))
        assertTrue("each chunk carries its position from the ring's tag", loop.contains("analyzer.analyze(chunk, length, spectrumRing.lastPolledTag, bands)"))
        assertTrue("and the picture is published after the last one", loop.indexOf("if (analysed) {") > loop.indexOf("analyzer.analyze("))
        val captureLoop = body(capture, "private fun captureLoop(active: CaptureSession)")
        assertTrue("the capture thread offers every read with its take position", captureLoop.contains("active.picture.offer(buffer, bytesRead, position)"))
        val offer = body(picture, "fun offer(buffer: ByteArray, bytesRead: Int, position: Long)")
        assertTrue("the offer queues the read", offer.contains("spectrumRing.offer(buffer, bytesRead, position)"))
        assertTrue("and wakes the analyser without a lock", offer.contains("LockSupport.unpark(analyserThread)"))
    }

    @Test
    fun theAnalyserIsStartedAfterCaptureAndStoppedWithTheTake() {
        val start = body(picture, "fun start(stillLive: () -> Boolean)")
        assertTrue("its start failure is its own", start.contains("runCatching {") && start.contains("thread.start()"))
        assertTrue(
            "it starts only after the capture thread started",
            capture.indexOf("newSession.picture.start(") > capture.indexOf("Failed to start capture thread"),
        )
        val close = body(picture, "fun close()")
        assertTrue("the owner's close interrupts the analyser once", close.contains("closed.compareAndSet(false, true)") && close.contains("interrupt(it)"))
        val release = body(capture, "private fun releaseSession(active: CaptureSession)")
        assertTrue("release closes it", release.contains("active.picture.close()"))
        val destroy = capture.substringAfter("override fun onDestroy()")
        assertTrue("and so does destroy", destroy.contains("active.picture.close()"))
    }

    @Test
    fun theListenerIsOnewayAndTheRegistrationIsAppendedLast() {
        val appendLine = aidl.indexOf("// APPENDED.")
        assertTrue("the append marker exists", appendLine >= 0)
        assertTrue("the picture getter sits below the append marker", aidl.indexOf("float[] getSpectrumBands();") > appendLine)
        // Everything below it was appended later (#26); the transaction order is pinned in
        // SilenceStopWiringTest.startCaptureIsStillTheFirstTransactionAndNothingWasReordered.
        assertTrue(
            "and every later method sits below it",
            aidl.indexOf("startCaptureWithInputDevice") > aidl.indexOf("float[] getSpectrumBands();"),
        )
        val register = aidl.indexOf("void registerSpectrumListener(IAudioSpectrumListener listener);")
        val unregister = aidl.indexOf("void unregisterSpectrumListener(IAudioSpectrumListener listener);")
        assertTrue("the two registration transactions sit after the last pre-#187 one, in order",
            register > aidl.indexOf("float getTakePeakAmplitude();") && unregister > register)
        assertTrue("the listener's one call is oneway, so the analyser never waits on the app process",
            listenerAidl.contains("oneway void onSpectrum(in float[] bands);"))
        assertTrue("and the listener carries its own append marker", listenerAidl.contains("// APPENDED."))
    }

    @Test
    fun theServiceClearsOnlyTheObservedListener() {
        assertTrue("the slot is an AtomicReference", capture.contains("private val spectrumListener = AtomicReference<IAudioSpectrumListener?>(null)"))
        val register = body(capture, "override fun registerSpectrumListener(listener: IAudioSpectrumListener?)")
        assertTrue("registration replaces", register.contains("spectrumListener.set(listener)"))
        val unregister = body(capture, "override fun unregisterSpectrumListener(listener: IAudioSpectrumListener?)")
        assertTrue("unregister compares binder identity and clears with compareAndSet",
            unregister.contains("current.asBinder() == listener.asBinder()") && unregister.contains("spectrumListener.compareAndSet(current, null)"))
        val unbind = body(capture, "override fun onUnbind(intent: Intent?): Boolean")
        assertTrue("unbind clears outright", unbind.contains("spectrumListener.set(null)"))
    }

    @Test
    fun theCountersAndTheTakeEndLineAreWired() {
        // Observability Contract: the take-end line is the oracle the hardware pass reads for "no polling".
        val getter = body(capture, "override fun getSpectrumBands(): FloatArray")
        assertTrue("the legacy getter reads the owner's snapshot", getter.contains("return active.picture.snapshot()"))
        assertTrue("and every snapshot is counted as a poll", body(picture, "fun snapshot(): FloatArray").contains("polls.incrementAndGet()"))
        val push = body(picture, "private fun pushSpectrum(bands: FloatArray)")
        assertTrue("every delivered push is counted", push.contains("pushes.incrementAndGet()"))
        val release = body(capture, "private fun releaseSession(active: CaptureSession)")
        assertTrue(
            "and the take-end line names both, in releaseSession, which every ending reaches",
            release.contains("\"Live picture: pushed=\${active.picture.pushes.get()} polled=\${active.picture.polls.get()}\""),
        )
        assertTrue("and nowhere else", Regex("Live picture: pushed=").findAll(capture).count() == 1)
    }

    @Test
    fun theSnapshotCarriesThePictureAndTheTakeSerial() {
        assertTrue("the snapshot must carry a picture", overlayState.contains("val bands: FloatArray = NO_BANDS"))
        assertTrue("and the take it belongs to", overlayState.contains("val takeSerial: Long = 0L"))
        assertTrue("show() stamps a fresh serial", body(overlayState, "fun show()").contains("lastTakeSerial += 1"))
        assertTrue(
            "a picture for a hidden recorder or another take is refused inside the locked change",
            body(overlayState, "fun updateBands(takeSerial: Long, bands: FloatArray)")
                .contains("change { if (!it.visible || it.takeSerial != takeSerial) it else it.copy(bands = safe) }"),
        )
        assertTrue("the recorder hands the rail every picture it is delivered", overlay.contains("meter.setBands(snapshot.bands)"))
        assertTrue("a new take starts at rest", overlay.contains("if (!previous.visible) meter.reset()"))
    }

    @Test
    fun aPicturePublishedAcrossAStopCannotBringTheRecorderBack() {
        // The picture moves about thirty times a second and Stop can land on any of them. Reading the
        // current state and committing the next one under one lock is what stops a picture prepared
        // before a stop from being committed after it, which would leave the recorder on screen with
        // no take running and nothing left to hide it.
        listOf("fun show()", "fun showNotice(", "fun updateBands(", "fun updateElapsed(", "fun hide()")
            .forEach { mutator ->
                val text = body(overlayState, mutator)
                assertTrue(
                    "$mutator must go through change {}, which reads and commits under one lock",
                    text.contains("change {") || text.contains("change {\n"),
                )
            }
        // Two lines mention `snapshot =`: the field's own declaration, and the single commit inside
        // change {}. A third is a mutator writing the state on its own again.
        val writes = Regex("(?<!var )snapshot = ").findAll(overlayState).count()
        assertTrue("the snapshot must be written in exactly one place, found $writes", writes == 1)
        assertTrue(
            "delivery must read the state it finds, never replay a captured snapshot",
            overlayState.contains("val current = synchronized(lock) {"),
        )
    }

    @Test
    fun theRecorderShowsTheMeterAndDrawsThePictureItWasGiven() {
        assertTrue("the pill must contain the meter", Regex("addView\\(\\s*meter,").containsMatchIn(overlay))
        assertTrue("the meter must be driven by the snapshot", overlay.contains("meter.setBands(snapshot.bands)"))
    }

    @Test
    fun theMeterIsNotAnnouncedToAScreenReader() {
        // It repeats what the timer already says, several times a second, and there is no way to mute it
        // from inside a running dictation.
        assertTrue(
            "the meter must be hidden from accessibility",
            meterView.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO"),
        )
    }

    @Test
    fun theFrameLoopStopsByItself() {
        // architecture-rules.md RULE: no-idle-cost: a rail at rest books no frame.
        val step = body(meterView, "private fun step()")
        assertTrue("the next frame is booked only while a bar is still moving", step.contains("if (moving && isAttachedToWindow) postOnAnimation(frame) else animating = false"))
        assertTrue("detaching stops it", meterView.contains("override fun onDetachedFromWindow() {\n        stopAnimating()"))
        assertTrue("and so does a reset", body(meterView, "fun reset()").contains("stopAnimating()"))
    }
}
