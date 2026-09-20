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
 * tick's meter step with a thread of its own; the links changed, the reason for guarding them did not.
 *
 * Source-level because the two ends are an Android `Service` and a `View` attached to a `WindowManager`,
 * neither of which a JVM test can stand up.
 */
class LiveAudioMeterWiringTest {

    private val capture = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    /** The owner since #186: the meter moved from the Service to the coordinator with its seams (`surface` over the overlay state). */
    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
    private val overlayState = File("src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt").readText()
    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val meterView = File("src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt").readText()
    private val aidl = File("src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl").readText()

    /** The text of one named function, and a loud failure if the name is gone. */
    private fun body(source: String, declaration: String): String {
        assertTrue("$declaration must exist; a renamed function would otherwise be inspected as unrelated text", source.contains(declaration))
        return source.substringAfter(declaration).substringBefore("\n    /**")
    }

    @Test
    fun theSessionOwnerReadsThePictureOnItsOwnThreadAndPublishesIt() {
        val meter = body(session, "private fun startMeter()")
        assertTrue("the meter thread must read the capture service's picture", meter.contains("service.spectrumBands()"))
        assertTrue("the read must be caught where it happens", meter.contains("runCatching { service.spectrumBands() }"))
        assertTrue("a throwing read must publish the empty picture, so the rail rests", meter.contains("getOrElse { surface.emptyBands() }"))
        assertTrue("the picture must reach the recorder with the take's serial", meter.contains("surface.updateBands(takeSerial, bands)"))
        assertTrue("the meter runs on its own thread, never in the polling tick", meter.contains("\"DictationMeterThread\""))
        assertTrue("and starting it cannot end the take", meter.contains("runCatching {\n            Thread("))
        val polling = body(session, "private fun startPolling()")
        assertTrue("the polling tick must not read the picture", !polling.contains("spectrumBands"))
        assertTrue("the polling thread starts the meter thread", polling.contains("startMeter()"))
    }

    @Test
    fun theMeterExitsWhenItsTakeIsOver() {
        val meter = body(session, "private fun startMeter()")
        assertTrue("the serial is captured once, when the take starts", meter.contains("val takeSerial = surface.currentTakeSerial()"))
        assertTrue("and compared after every read, so a read that returned in a later take leaves", meter.contains("if (surface.currentTakeSerial() != takeSerial) break"))
        assertTrue("the loop lives only while recording", meter.contains("while (state.get() == SessionState.RECORDING)"))
    }

    @Test
    fun thePictureIsOnlyEverReadInOnePlace() {
        // architecture-rules.md RULE: no-idle-cost — surfaces are pushed a picture, they never poll for
        // one. One reader is what keeps that true as surfaces are added.
        val readers = Regex("spectrumBands").findAll(session).count()
        assertTrue("the session owner must read the picture exactly once, found $readers", readers == 1)
        assertTrue(
            "the recorder must not reach for the capture service itself",
            !overlay.contains("spectrumBands") && !meterView.contains("spectrumBands") &&
                !overlay.contains("currentAmplitude") && !meterView.contains("currentAmplitude"),
        )
    }

    @Test
    fun theAudioProcessPublishesUnderOneLockAndTheGetterReadsUnderIt() {
        // A sequential test cannot open the window between a half-written picture and its reader
        // (validation-discipline.md RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act),
        // so the lock is asserted at the source: the copy in and the copy out both sit inside it.
        val loop = body(capture, "private fun analyserLoop(active: CaptureSession)")
        assertTrue(
            "the analyser copies its picture in under the lock",
            loop.contains("synchronized(active.bandsLock) {\n                        System.arraycopy(bands, 0, active.publishedBands"),
        )
        assertTrue("a failure publishes the empty picture before leaving", loop.contains("synchronized(active.bandsLock) { active.publishedBands.fill(0f) }"))
        assertTrue("the getter copies out under the same lock", capture.contains("return synchronized(active.bandsLock) { active.publishedBands.copyOf() }"))
        assertTrue(
            "and answers a full-length empty picture when no take is open, never an empty array",
            capture.contains("?: return FloatArray(SpectrumAnalyzer.BAND_COUNT)"),
        )
        assertTrue("the capture thread never takes that lock", !body(capture, "private fun captureLoop(active: CaptureSession)").contains("bandsLock"))
    }

    @Test
    fun theAnalyserDrainsTheRingWithPositionsAndPublishesOncePerWake() {
        val loop = body(capture, "private fun analyserLoop(active: CaptureSession)")
        assertTrue("every queued chunk is analysed before publishing", loop.contains("while (true) {\n                    val length = active.spectrumRing.poll(chunk)\n                    if (length < 0) break"))
        assertTrue("each chunk carries its position from the ring's tag", loop.contains("analyzer.analyze(chunk, length, active.spectrumRing.lastPolledTag, bands)"))
        assertTrue("and the picture is published after the last one", loop.indexOf("if (analysed) {") > loop.indexOf("analyzer.analyze("))
        val captureLoop = body(capture, "private fun captureLoop(active: CaptureSession)")
        assertTrue("the capture thread offers every read with its take position", captureLoop.contains("active.spectrumRing.offer(buffer, bytesRead, position)"))
        assertTrue("and wakes the analyser without a lock", captureLoop.contains("LockSupport.unpark(active.analyserThread)"))
    }

    @Test
    fun theAnalyserIsStartedAfterCaptureAndStoppedWithTheTake() {
        val start = body(capture, "private fun startSpectrumAnalysis(active: CaptureSession)")
        assertTrue("its start failure is its own", start.contains("runCatching {") && start.contains("thread.start()"))
        assertTrue(
            "it starts only after the capture thread started",
            capture.indexOf("startSpectrumAnalysis(newSession)") > capture.indexOf("Failed to start capture thread"),
        )
        val release = body(capture, "private fun releaseSession(active: CaptureSession)")
        assertTrue("release interrupts it", release.contains("active.analyserThread?.interrupt()"))
        val destroy = capture.substringAfter("override fun onDestroy()")
        assertTrue("and so does destroy", destroy.contains("active.analyserThread?.interrupt()"))
    }

    @Test
    fun theAidlMethodIsAppendedLast() {
        val appendLine = aidl.indexOf("// APPENDED.")
        assertTrue("the append marker exists", appendLine >= 0)
        assertTrue("the picture getter sits below the append marker", aidl.indexOf("float[] getSpectrumBands();") > appendLine)
        // Everything below it was appended later (#26); the transaction order is pinned in
        // SilenceStopWiringTest.startCaptureIsStillTheFirstTransactionAndNothingWasReordered.
        assertTrue(
            "and every later method sits below it",
            aidl.indexOf("startCaptureWithInputDevice") > aidl.indexOf("float[] getSpectrumBands();"),
        )
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
