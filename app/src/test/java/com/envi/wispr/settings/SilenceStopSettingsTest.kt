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
        val source = read("ui/DictationSessionService.kt")
        val block = source.substringAfter("authoritativeState.collect")
            .substringBefore("cleanupPreferencesReady.complete(Unit)")
        assertTrue("the switch is read before the gate", block.contains("autoStopOnSilence = preferences.autoStopOnSilenceEnabled"))
        assertTrue("and so is the wait", block.contains("silencePauseSeconds = preferences.silencePauseSeconds"))
    }

    @Test
    fun theTakeFreezesTheSettingsRatherThanReadingThemAsItGoes() {
        val source = read("ui/DictationSessionService.kt")
        assertTrue(
            source.contains("audioService?.startCaptureWithSilenceStop(autoStopOnSilence, silencePauseSeconds)"),
        )
    }

    @Test
    fun theNoticeFiresOnlyOnceAndOnlyWhenAutoStopNeverBecameAvailable() {
        val body = read("ui/DictationSessionService.kt")
            .substringAfter("private fun publishSilenceNoticeIfNeeded(")
            .substringBefore("private fun stopAndTranscribe(")
        assertTrue("nothing to say when the user has it off", body.contains("if (!autoStopOnSilence || silenceNoticeShown) return"))
        assertTrue("and only for the unavailable state", body.contains("!= AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return"))
        assertTrue("shown once per take", body.contains("silenceNoticeShown = true"))
    }

    @Test
    fun theNoticeHasASurfaceEvenWithoutTheAccessibilityService() {
        // The floating recorder only exists while PasteAccessibilityService runs. Clipboard-only mode is
        // supported and would otherwise show nothing at all.
        val body = read("ui/DictationSessionService.kt")
            .substringAfter("private fun publishSilenceNoticeIfNeeded(")
            .substringBefore("private fun stopAndTranscribe(")
        assertTrue(body.contains("if (PasteAccessibilityService.isBound.value)"))
        assertTrue(body.contains("RecordingOverlayState.showNotice(SILENCE_UNAVAILABLE_NOTICE)"))
        assertTrue(body.contains("Toast.makeText(applicationContext, SILENCE_UNAVAILABLE_NOTICE"))
    }

    @Test
    fun theNoticeUsesMacOSsOwnSentence() {
        // Android inventing its own words for a state macOS has already worded is how they drift.
        assertTrue(
            read("ui/DictationSessionService.kt")
                .contains("\"Auto-stop on silence is unavailable right now\""),
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
            state.contains("snapshot.copy(elapsedSeconds = seconds.coerceAtLeast(0))"),
        )
        assertFalse(
            state.substringAfter("fun updateElapsed(").substringBefore("fun hide()")
                .contains("Snapshot("),
        )
    }
}
