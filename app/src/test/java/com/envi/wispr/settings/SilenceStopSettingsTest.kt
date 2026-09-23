package com.envi.wispr.settings

import com.envi.wispr.vad.SilenceStopDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SilenceStopSettingsTest {

    private fun read(path: String) = File("src/main/java/com/envi/wispr/$path").readText()

    @Test
    fun autoStopIsOffOutOfTheBoxAndTheWaitIsTheSharedDefault() {
        // One canonical default across all three platforms, so a fresh install, a reset and a runtime
        // fallback cannot disagree. Auto-stop is wrong for someone who pauses to think, so it is opted
        // into and never out of.
        val fresh = AppPreferencesState()
        assertFalse(fresh.autoStopOnSilenceEnabled)
        assertEquals(1.5f, fresh.silencePauseSeconds, 0.0001f)
        assertEquals(SilenceStopDetector.DEFAULT_PAUSE_SECONDS, fresh.silencePauseSeconds, 0.0001f)
    }

    @Test
    fun theNewSettingsAreWrittenBeforeCaptureIsAllowedToReadThem() {
        // beginSession awaits this readiness signal before binding anything, so both values must be
        // assigned in the same collector block ABOVE the completion. Written anywhere else and a user
        // who enabled auto-stop silently gets a manual take after every cold start.
        // Since #186 the collector lives in SessionPreferencesSource, fed the authoritative flow by the
        // Service; beginSession awaits its readiness signal.
        assertTrue(read("ui/DictationSessionService.kt").contains("preferenceStates = AppPreferences(applicationContext).authoritativeState,"))
        // Since #193 the collector replaces ONE atomic snapshot holding every value, and only then
        // completes the first-answer signal; the take reads the frozen snapshot, never the live source.
        val source = read("ui/SessionPreferencesSource.kt")
        val block = source.substringAfter("preferenceStates.collect")
            .substringBefore("settingsAnswered.complete(Unit)")
        assertTrue("the switch is inside the snapshot replaced before the answer", block.contains("autoStopOnSilence = preferences.autoStopOnSilenceEnabled"))
        assertTrue("and so is the wait", block.contains("silencePauseSeconds = preferences.silencePauseSeconds"))
        assertTrue("one whole replace, not field writes", block.contains("settingsSnapshot.set(") && !block.contains("@Volatile"))
    }

    @Test
    fun theTakeFreezesTheSettingsRatherThanReadingThemAsItGoes() {
        // Since #193 the start call reads the take's FROZEN snapshot (`sessionPreferences`), never the
        // live source, so a settings emission after the take's answer belongs to the next take.
        val source = read("ui/DictationSessionCoordinator.kt")
        val body = source.substringAfter("private fun tryStartRecording()").substringBefore("\n    private fun ")
        assertTrue("the frozen snapshot is read once, on main", body.contains("val preferences = sessionPreferences"))
        assertTrue("and handed to the start", body.indexOf("val preferences = sessionPreferences") in 0 until body.indexOf("capture.start(preferences)"))
        // Since #216 the start's lane body is the owner's `CaptureSessionController`; it passes THAT snapshot on.
        val controller = read("ui/CaptureSessionController.kt")
        assertTrue(controller.contains("fun start(preferences: SessionPreferences): Boolean {"))
        val lane = controller.substringAfter("fun start(preferences: SessionPreferences): Boolean {").substringBefore("\n    /**")
        val start = lane.substringAfter("capture.startCaptureForTake(").substringBefore(")")
        listOf("preferences.autoStopOnSilence", "preferences.silencePauseSeconds", "preferences.inputDevicePick", "preferences.keepEarbudsReady", "id,").forEach {
            assertTrue("the start call carries $it", start.contains(it))
        }
    }

    @Test
    fun theNoticeFiresOnlyOnceAndOnlyWhenAutoStopNeverBecameAvailable() {
        val body = read("ui/DictationSessionCoordinator.kt")
            .substringAfter("private fun publishSilenceNoticeIfNeeded(")
            .substringBefore("private fun stopAndTranscribe(")
        assertTrue("nothing to say when the user has it off", body.contains("if (!sessionPreferences.autoStopOnSilence || silenceNoticeShown) return"))
        assertTrue("and only for the unavailable state", body.contains("!= AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return"))
        assertTrue("shown once per take", body.contains("silenceNoticeShown = true"))
    }

    @Test
    fun theNoticeHasASurfaceEvenWithoutTheAccessibilityService() {
        // The floating recorder only exists while PasteAccessibilityService runs. Clipboard-only mode is
        // supported and would otherwise show nothing at all.
        //
        // That decision belongs to `SessionNoticePresenter.say` (#256), which every recorder notice goes
        // through. Asserting it there is what stops the NEXT message picking a surface that is not on
        // screen, which asserting it inside this one caller could never do.
        // Since #186 the chooser reads the paste service's liveness and the overlay through the owner's seams.
        val source = read("ui/DictationSessionCoordinator.kt")
        val chooser = read("ui/SessionNotice.kt").substringAfter("fun say(notice: SessionNotice) {")
        assertTrue(chooser.contains("if (notice.timing == NoticeTiming.WHILE_RECORDING && insertion.isBound())"))
        assertTrue(chooser.contains("surface.showNotice(notice.line)"))
        // Both seams reach their real owners (Codex review C1, 2026-09-20).
        assertTrue(read("ui/InsertionGateway.kt").contains("override fun isBound(): Boolean = PasteAccessibilityService.isBound.value"))
        assertTrue(read("ui/RecorderSurface.kt").contains("RecordingOverlayState.showNotice("))
        assertTrue("the other surface is the application-context toast", chooser.contains("host.toastFromApplication(notice.line)"))
        assertTrue(
            read("ui/DictationSessionService.kt").substringAfter("override fun toastFromApplication(").contains("Toast.makeText(applicationContext, line"),
        )

        val notice = source
            .substringAfter("private fun publishSilenceNoticeIfNeeded(")
            .substringBefore("private fun publishDurationWarningIfNeeded(")
        assertTrue(
            "the silence notice must go through the shared chooser, not pick a surface itself",
            notice.contains("notices.say(SessionNotice.SILENCE_UNAVAILABLE)"),
        )
    }

    @Test
    fun theNoticeUsesMacOSsOwnSentence() {
        // Android inventing its own words for a state macOS has already worded is how they drift.
        assertTrue(
            read("ui/SessionNotice.kt")
                .contains("SILENCE_UNAVAILABLE(\"Auto-stop on silence is unavailable right now\""),
        )
    }

    @Test
    fun bothControlsLiveOnTheTranscriptionTabAboveTextCleanup() {
        val screen = read("ui/TranscriptionScreen.kt")
        val recording = screen.indexOf("SettingsGroup(\"Recording\")")
        val cleanup = screen.indexOf("SettingsGroup(\"Text cleanup\")")
        assertTrue("the recording group must exist", recording >= 0)
        assertTrue("when a recording ENDS comes before what is done to the text", recording < cleanup)
        assertTrue(screen.contains("title = \"Stop recording on silence\""))
    }

    @Test
    fun theSliderCoversTheSameRangeAsMacOSInTheSameSteps() {
        val screen = read("ui/TranscriptionScreen.kt")
        assertTrue(screen.contains("valueRange = 0.5f..3.0f"))
        // Eleven positions in quarter seconds means nine sit between the two ends.
        assertTrue(screen.contains("steps = 9"))
        assertEquals(0.5f, SilenceStopDetector.MIN_PAUSE_SECONDS, 0.0001f)
        assertEquals(3.0f, SilenceStopDetector.MAX_PAUSE_SECONDS, 0.0001f)
    }

    @Test
    fun theSliderNeverPromisesAnExactStopwatchTime() {
        // The state machine spends a block noticing the silence before it starts counting, so the real
        // wait is longer than the number. Saying "1.5s" would be a promise the detector does not keep.
        val screen = read("ui/TranscriptionScreen.kt")
        assertTrue(screen.contains("valueLabel = \"about \$"))
        assertTrue(screen.contains("Recording can take a moment longer to stop while your voice fades."))
    }

    @Test
    fun aNoticeSurvivesTheElapsedTick() {
        // The timer republishes a snapshot every second. A notice dropped there would vanish one second
        // after it appeared, which reads as the app flickering rather than telling the user something.
        val state = read("shortcuts/RecordingOverlayState.kt")
        assertTrue(
            "updateElapsed must copy the current snapshot rather than build a new one",
            state.contains("it.copy(elapsedSeconds = safe)"),
        )
        assertFalse(
            state.substringAfter("fun updateElapsed(").substringBefore("fun hide()")
                .contains("Snapshot("),
        )
    }
}
