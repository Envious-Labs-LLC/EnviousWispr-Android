package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Observability contract (issue #176): the per-take facts holder builds the terminal row from exactly
 * what was measured, the capture-process integers map to pinned tokens, and the launch row's settings
 * projection is a literal. Nothing here may produce a sentence or a product name.
 */
class TakeFactsTest {

    @Test
    fun anUnmeasuredTakeReportsOnlyItsIdentityResultAndTrigger() {
        val facts = TakeFacts("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", TriggerSource.ASSIST)
        val row = facts.terminal(TerminalReason.SETTINGS_UNAVAILABLE).properties().filterValues { it != null }
        assertEquals(
            mapOf("take_id" to "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", "result" to "failed", "reason" to "SETTINGS_UNAVAILABLE", "trigger_source" to "assist"),
            row,
        )
    }

    @Test
    fun theSpeechFailureRidesOnlyOnAnAsrFailedEnding() {
        val facts = TakeFacts("t", TriggerSource.TILE)
        facts.asrFailure = AsrFailureReason.DECODE_FAILED
        assertEquals("DECODE_FAILED", facts.terminal(TerminalReason.ASR_FAILED).properties()["asr_failure_reason"])
        // The fact was written before a cancel won the take: the cancelled row must not carry it.
        assertNull(facts.terminal(TerminalReason.CANCELLED_PROCESSING).properties()["asr_failure_reason"])
    }

    @Test
    fun everyMeasuredFactLandsUnderItsOwnKey() {
        val facts = TakeFacts("t", TriggerSource.BUBBLE_HOLD).apply {
            routeKind = InputRouteKind.BLUETOOTH; routeReason = InputRouteReason.PICKED; liveAfterMs = 340L; liveState = "forced"
            silenceStopStatus = "lost_after_ready"; captureTerminal = "silence"; recordingSeconds = 6.4; inputDevice = "picked"
            asrMs = 910L; asrChars = 43; peakAmplitude = 0.22f; polishProvider = "cloud:GEMINI"; polishReason = PolishReason.HTTP_KEY_REJECTED
            polishMs = 1200L; polishStatus = 400; historySave = "ok"
        }
        val row = facts.terminal(TerminalReason.COMPLETED).properties().filterValues { it != null }
        assertEquals(
            mapOf(
                "take_id" to "t", "result" to "completed", "trigger_source" to "bubble_hold",
                "route_kind" to "bluetooth", "route_reason" to "picked", "live_after_ms" to 340L, "live_state" to "forced",
                "silence_stop_status" to "lost_after_ready", "capture_terminal" to "silence", "recording_s" to 6.4, "input_device" to "picked",
                "asr_ms" to 910L, "asr_chars" to 43, "peak_amplitude" to 0.22f, "polish_provider" to "cloud:GEMINI",
                "polish_reason" to "HTTP_KEY_REJECTED", "polish_ms" to 1200L, "polish_status" to 400, "history_save" to "ok",
            ),
            row,
        )
        assertFalse("a completed take carries no reason", row.containsKey("reason"))
    }

    @Test
    fun theCaptureProcessIntegersMapToPinnedTokens() {
        assertEquals(listOf("off", "preparing", "ready", "unavailable_before_ready", "lost_after_ready", "unknown"), (0..5).map(TakeFacts::silenceStatusToken))
        assertEquals(listOf("still_running", "max_duration", "manual", "failure", "silence", "failure"), (0..5).map(TakeFacts::captureEndingToken))
        assertEquals("auto", TakeFacts.inputDeviceToken("auto"))
        assertEquals("picked", TakeFacts.inputDeviceToken("7|Saurabh's AirPods Pro"))
    }

    @Test
    fun theLaunchSettingsProjectionIsALiteralAndNeverThePickedProductName() {
        val state = AppPreferencesState(
            fillerRemovalEnabled = true, emojiFormatterEnabled = false, spokenPunctuationEnabled = true, autoCopyToClipboard = false,
            restoreClipboardAfterPaste = true, smartInsertionEnabled = false, autoStopOnSilenceEnabled = true, silencePauseSeconds = 2.5f,
            inputDevicePick = "7|Saurabh's AirPods Pro", showBluetoothTips = false, keepEarbudsReady = true, dynamicColorEnabled = true,
            bubbleLook = BubbleLook.DEFAULT,
        )
        assertEquals(
            mapOf(
                "filler_removal" to "on", "emoji_formatter" to "off", "spoken_punctuation" to "on", "auto_copy_to_clipboard" to "off",
                "restore_clipboard_after_paste" to "on", "smart_insertion" to "off", "auto_stop_on_silence" to "on", "silence_pause_seconds" to 2.5f,
                "input_device" to "picked", "show_bluetooth_tips" to "off", "keep_earbuds_ready" to "on", "dynamic_color" to "on",
                "bubble_look" to BubbleLook.DEFAULT.name.lowercase(), "polish_policy" to "local",
            ),
            AppLaunchFacts.settingsProjection(state, "local"),
        )
    }

    @Test
    fun anUnreadablePreferencesStoreProjectsEverySettingAsUnknown() {
        val projection = AppLaunchFacts.settingsProjection(null, "unknown")
        assertEquals(14, projection.size)
        assertEquals(setOf("unknown"), projection.values.toSet())
    }
}
