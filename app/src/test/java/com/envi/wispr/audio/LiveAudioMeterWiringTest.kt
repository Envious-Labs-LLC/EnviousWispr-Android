package com.envi.wispr.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard, not product coverage.
 *
 * The defect this exists for is the one issue #44 recorded: the amplitude was computed correctly for
 * months and no route carried it to the screen, so every part looked right on its own. Each link of that
 * route is asserted here because the broken state compiles, passes every other test, and shows nothing.
 *
 * Source-level because the two ends are an Android `Service` and a `View` attached to a `WindowManager`,
 * neither of which a JVM test can stand up.
 */
class LiveAudioMeterWiringTest {

    private val session = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()
    private val overlayState = File("src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt").readText()
    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val meterView = File("src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt").readText()

    @Test
    fun theSessionOwnerReadsTheAmplitudeAndPublishesAScaledLevel() {
        assertTrue(
            "the polling loop must read the capture service's amplitude",
            session.contains("service.currentAmplitude"),
        )
        assertTrue(
            "the amplitude must be scaled before it is published, never sent raw",
            session.contains("AudioLevelScale.display(amplitude)"),
        )
        assertTrue(
            "the published level must be smoothed against the value already drawn",
            session.contains("AudioLevelScale.smooth(") && session.contains("lastMeterLevel"),
        )
        assertTrue(
            "the scaled level must reach the recorder",
            session.contains("RecordingOverlayState.updateLevel("),
        )
    }

    @Test
    fun aFailingMeterReadingCannotStallTheTake() {
        // Two things keep the meter off the critical path, and the ORDER is the load-bearing one. A
        // reading taken before the terminal-reason check can delay transcription by being slow, which
        // no exception handler catches. Taken after it, a slow, throwing or dead reading costs the
        // meter alone.
        assertTrue(
            "the amplitude read must be caught where it happens",
            session.contains("runCatching { service.currentAmplitude }.getOrDefault(0f)"),
        )
        val loop = session.substringAfter("private fun startPolling()").substringBefore("\n    /**")
        val terminalCheck = loop.indexOf("service.terminalReason")
        val meterRead = loop.indexOf("service.currentAmplitude")
        assertTrue("the polling loop must contain both", terminalCheck >= 0 && meterRead >= 0)
        assertTrue(
            "the meter reading must come AFTER the check that ends a take, not before it",
            meterRead > terminalCheck,
        )
    }

    @Test
    fun theMeterIsOnlyEverReadInOnePlace() {
        // architecture-rules.md RULE: no-idle-cost — surfaces are pushed a level, they never poll for
        // one. One reader is what keeps that true as surfaces are added.
        val readers = Regex("currentAmplitude").findAll(session).count()
        assertTrue("the session owner must read the amplitude exactly once, found $readers", readers == 1)
        assertTrue(
            "the recorder must not reach for the capture service itself",
            !overlay.contains("currentAmplitude") && !meterView.contains("currentAmplitude"),
        )
    }

    @Test
    fun theSnapshotCarriesALevelAndPublishingItIsCheap() {
        assertTrue("the snapshot must carry a level", overlayState.contains("val level: Float"))
        assertTrue(
            "a repeated level must not wake the recorder again",
            overlayState.contains("it.level == quantised"),
        )
        assertTrue(
            "a level published while the recorder is hidden must be dropped",
            overlayState.contains("if (!it.visible || it.level == quantised) it"),
        )
        assertTrue(
            "a level tick must carry the notice forward, not replace the snapshot",
            overlayState.contains("it.copy(level = quantised)"),
        )
    }

    @Test
    fun aLevelPublishedAcrossAStopCannotBringTheRecorderBack() {
        // The level moves about ten times a second and Stop can land on any of them. Reading the
        // current state and committing the next one under one lock is what stops a level prepared
        // before a stop from being committed after it, which would leave the recorder on screen with
        // no take running and nothing left to hide it.
        listOf("fun show()", "fun showNotice(", "fun updateLevel(", "fun updateElapsed(", "fun hide()")
            .forEach { mutator ->
                val body = overlayState.substringAfter(mutator).substringBefore("\n    fun ")
                assertTrue(
                    "$mutator must go through change {}, which reads and commits under one lock",
                    body.contains("change {") || body.contains("change {\n"),
                )
            }
        // Two lines mention `snapshot =`: the field's own declaration, and the single commit inside
        // change {}. A third is a mutator writing the state on its own again.
        val writes = Regex("(?<!var )snapshot = ").findAll(overlayState).count()
        assertTrue(
            "the snapshot must be written in exactly one place, found $writes",
            writes == 1,
        )
        assertTrue(
            "delivery must read the state it finds, never replay a captured snapshot",
            overlayState.contains("val current = synchronized(lock) {"),
        )
    }

    @Test
    fun theRecorderShowsTheMeterAndDrawsTheLevelItWasGiven() {
        assertTrue(
            "the pill must contain the meter",
            Regex("addView\\(\\s*meter,").containsMatchIn(overlay),
        )
        assertTrue("the meter must be driven by the snapshot", overlay.contains("meter.setLevel(snapshot.level)"))
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
}
