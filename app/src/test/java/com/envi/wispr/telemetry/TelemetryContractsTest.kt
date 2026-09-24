package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observability contracts and drift guards for the telemetry package (issue #176, plan §11.2). Every
 * expected value is a literal written down independently of the code under test.
 */
class TelemetryContractsTest {

    // ---- TelemetryChannelsTest: the literal member→channel table of plan §3.6

    @Test
    fun everyPolishReasonRoutesAsTheTableSays() {
        val defects = setOf(
            PolishReason.CLEANUP_RECOVERED, PolishReason.LOCAL_FAILED, PolishReason.LOCAL_TIMEOUT,
            PolishReason.UNEXPECTED, PolishReason.WATCHDOG_TIMEOUT,
        )
        for (reason in PolishReason.entries) {
            val expected = if (reason in defects) Channel.DEFECT else Channel.BREADCRUMB
            assertEquals("$reason", expected, TelemetryChannels.of(reason))
            assertEquals("$reason has a defect iff DEFECT", expected == Channel.DEFECT, TelemetryChannels.defectOf(reason) != null)
        }
    }

    @Test
    fun everyInsertionValueAndHandoffIsABreadcrumb() {
        for (kind in InsertionResultKind.entries) assertEquals("$kind", Channel.BREADCRUMB, TelemetryChannels.of(kind))
        for (handoff in InsertionHandoff.entries) assertEquals("$handoff", Channel.BREADCRUMB, TelemetryChannels.of(handoff))
    }

    @Test
    fun asrFailuresRouteAsTheTableSays() {
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(AsrFailureReason.UNKNOWN))
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(AsrFailureReason.MODEL_NOT_LOADED))
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(AsrFailureReason.AUDIO_MISSING))
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(AsrFailureReason.AUDIO_UNREADABLE))
        assertEquals(Channel.DEFECT, TelemetryChannels.of(AsrFailureReason.OVER_LIMIT))
        assertEquals(Channel.DEFECT, TelemetryChannels.of(AsrFailureReason.DECODE_FAILED))
    }

    @Test
    fun terminalReasonsRouteAsTheTableSaysAndAsrFailedDefersToItsCause() {
        for (reason in TerminalReason.entries) {
            val expected = when (reason) {
                TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP -> Channel.DEFECT
                else -> Channel.BREADCRUMB
            }
            if (reason != TerminalReason.ASR_FAILED) assertEquals("$reason", expected, TelemetryChannels.of(reason, null))
        }
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(TerminalReason.ASR_FAILED, null))
        assertEquals(Channel.BREADCRUMB, TelemetryChannels.of(TerminalReason.ASR_FAILED, AsrFailureReason.MODEL_NOT_LOADED))
        assertEquals(Channel.DEFECT, TelemetryChannels.of(TerminalReason.ASR_FAILED, AsrFailureReason.DECODE_FAILED))
        assertEquals(AppDefect.CaptureStillRunningAfterStop, TelemetryChannels.defectOf(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP, null))
        assertNull(TelemetryChannels.defectOf(TerminalReason.COMPLETED, null))
    }

    @Test
    fun insertionResultsParseFromTheStoredStringsAndAnythingElseIsUnknown() {
        assertEquals(InsertionResultKind.COMMITTED, InsertionResultKind.fromStored(InsertionResults.COMMITTED))
        assertEquals(InsertionResultKind.INSERTION_INTERRUPTED, InsertionResultKind.fromStored("insertion_interrupted"))
        assertEquals(InsertionResultKind.UNKNOWN, InsertionResultKind.fromStored("pending"))
        assertEquals(InsertionResultKind.UNKNOWN, InsertionResultKind.fromStored(null))
        assertEquals(InsertionResultKind.UNKNOWN, InsertionResultKind.fromStored("unknown"))
    }

    // ---- AppDefectTest: the committed literal snapshot of every fingerprint

    @Test
    fun everyDefectFingerprintEqualsTheSnapshot() {
        val snapshot = mapOf(
            "vad_call_wedged" to "vad.call_wedged",
            "local_polish_deadline" to "polish.local_deadline",
            "polish_protocol_violation" to "polish.protocol_violation",
            "capture_still_running_after_stop" to "capture.still_running_after_stop",
            "capture_release_wedged" to "capture.release_wedged",
            "asr_decode_failed" to "asr.decode_failed",
            "asr_over_limit" to "asr.over_limit",
            "cleanup_recovered" to "polish.cleanup_recovered",
            "local_polish_failed" to "polish.local_failed",
            "polish_unexpected" to "polish.unexpected",
            "polish_watchdog_timeout" to "polish.watchdog_timeout",
            // #234: polish failures that fall back are raised once per take, when observed.
            "polish_service_unavailable" to "polish.service_unavailable",
            "polish_service_died" to "polish.service_died",
            "polish_call_failed" to "polish.call_failed",
            // #278: the stored polish policy could not be read at take start.
            "polish_policy_unreadable" to "polish.policy_unreadable",
            // #290: the take's start preparation threw or missed its bound.
            "take_preparation_failed" to "take.preparation_failed",
            // #292: the History write queue refused writes, once per overload episode.
            "history_queue_overloaded" to "history.queue_overloaded",
            // #235: the History save did not answer within the owner's bound.
            "history_save_timed_out" to "history.save_timed_out",
            "history_contract_violation" to "history.contract_violation",
            "polish_preparation_failed" to "polish.preparation_failed",
            // #257: a warm hold's silence writer outlived its stop by the bound.
            "silence_writer_exit_wedged" to "audio.silence_writer_exit_wedged",
            // #333: a warm hold's stopped silent track whose release did not return by the bound.
            "silent_track_release_unconfirmed" to "audio.silent_track_release_unconfirmed",
            "debug_probe" to "debug.probe",
        )
        val all = AppDefect.all()
        assertEquals("every member is in the snapshot", snapshot.size, all.size)
        assertEquals("fingerprints are unique", all.size, all.map { it.fingerprint }.toSet().size)
        for (defect in all) {
            assertTrue("${defect.fingerprint} is not in the snapshot", snapshot.containsKey(defect.fingerprint))
            assertEquals(defect.fingerprint, snapshot.getValue(defect.fingerprint), defect.semanticId)
        }
    }

    // ---- TriggerSourceTest

    @Test
    fun aMissingOrGarbageTriggerExtraIsUnknownNeverASurface() {
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra(null))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra(""))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra("side_button"))
        assertEquals(TriggerSource.UNKNOWN, TriggerSource.fromExtra("unknown"))
        assertEquals(TriggerSource.TILE, TriggerSource.fromExtra("tile"))
        assertEquals(listOf("bubble_tap", "bubble_hold", "assist", "tile", "app", "unknown"), TriggerSource.entries.map { it.wire })
    }

    // ---- TelemetryVolumePolicyTest

    @Test
    fun everyDeclaredEventIsKeptAndStampedAndSdkNoiseIsDropped() {
        val kept = TelemetryVolumePolicy.decide("dictation.terminal", mapOf("result" to "completed", "\$user_agent" to "x", "\$os_version" to "16"))
        assertEquals(
            TelemetryVolumePolicy.Decision.Keep(mapOf("result" to "completed", "\$os_version" to "16", "telemetry_policy_version" to 1)),
            kept,
        )
        assertEquals(TelemetryVolumePolicy.Decision.Drop, TelemetryVolumePolicy.decide("Application Opened", emptyMap()))
        assertEquals(TelemetryVolumePolicy.Decision.Drop, TelemetryVolumePolicy.decide("\$screen", emptyMap()))
    }

    // ---- AnalyticsEventTest: the complete final payload against an independent literal

    private val config = TelemetryConfig(
        versionName = "0.1.0", appBuild = 150, environment = "production", processName = "com.envi.wispr",
        isMainProcess = true, processRunId = "2f7a4b3c-1d2e-4f5a-8b6c-9d0e1f2a3b4c",
    )

    @Test
    fun aCompletedTerminalRowLeavesExactlyAsTheSchemaSays() {
        val event = AnalyticsEvent.DictationTerminal(
            takeId = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d",
            reason = TerminalReason.COMPLETED, asrFailure = null, trigger = TriggerSource.BUBBLE_TAP,
            routeKind = InputRouteKind.BLUETOOTH, routeReason = InputRouteReason.AUTO, liveAfterMs = 120L,
            liveState = "ready", silenceStopStatus = "ready", captureTerminal = "manual", recordingSeconds = 4.2, inputDevice = "auto",
            asrMs = 830L, asrChars = 57, peakAmplitude = 0.31f, polishProvider = "local",
            polishReason = PolishReason.POLISHED, polishMs = 410L, polishStatus = 0, historySave = "ok",
            settingsFallback = "settings:exception:IOException",
            settingsAnswerMs = 12L, matcherReadyMs = 30L, policyLoadedMs = 41L, admissionObservedMs = 9L, bindRequestedMs = 44L,
            liveReceivedMs = 180L,
        )
        val payload = PostHogBootstrap.processProperties(event.name, event.properties() + mapOf("\$user_agent" to "x", "\$os_name" to "Android"), config)
        val expected = mapOf(
            "take_id" to "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d",
            "result" to "completed",
            "trigger_source" to "bubble_tap",
            "route_kind" to "bluetooth",
            "route_reason" to "auto",
            "live_after_ms" to 120L,
            "live_state" to "ready",
            "silence_stop_status" to "ready",
            "capture_terminal" to "manual",
            "recording_s" to 4.2,
            "input_device" to "auto",
            "asr_ms" to 830L,
            "asr_chars" to 57,
            "peak_amplitude" to 0.31f,
            "polish_provider" to "local",
            "polish_reason" to "POLISHED",
            "polish_ms" to 410L,
            "polish_status" to 0,
            "history_save" to "ok",
            // #193: which settings readers fell back and why, a content-free token.
            "settings_fallback" to "settings:exception:IOException",
            // #258: the pre-capture chain, ms since the owner accepted the start command; numbers, so they pass.
            "settings_answer_ms" to 12L,
            "matcher_ready_ms" to 30L,
            "policy_loaded_ms" to 41L,
            "admission_observed_ms" to 9L,
            "bind_requested_ms" to 44L,
            "live_received_ms" to 180L,
            "\$os_name" to "Android",
            "app" to "enviouswispr-android",
            "environment" to "production",
            "app_version" to "0.1.0",
            "app_build" to 150,
            "process_run_id" to "2f7a4b3c-1d2e-4f5a-8b6c-9d0e1f2a3b4c",
            "telemetry_policy_version" to 1,
        )
        assertEquals(expected, payload)
        assertFalse("a completed take carries no reason", payload!!.containsKey("reason"))
    }

    @Test
    fun aFailedTerminalRowCarriesItsReasonAndTheAsrCause() {
        val event = AnalyticsEvent.DictationTerminal(
            takeId = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", reason = TerminalReason.ASR_FAILED,
            asrFailure = AsrFailureReason.DECODE_FAILED, trigger = TriggerSource.ASSIST, routeKind = null,
            routeReason = null, liveAfterMs = null, liveState = null, silenceStopStatus = null, captureTerminal = null, recordingSeconds = null,
            inputDevice = null, asrMs = null, asrChars = null, peakAmplitude = null, polishProvider = null,
            polishReason = null, polishMs = null, polishStatus = null, historySave = null, settingsFallback = null,
            settingsAnswerMs = null, matcherReadyMs = null, policyLoadedMs = null, admissionObservedMs = null, bindRequestedMs = null, liveReceivedMs = null,
        )
        val payload = PostHogBootstrap.processProperties(event.name, event.properties(), config)!!
        assertEquals("failed", payload["result"])
        assertEquals("ASR_FAILED", payload["reason"])
        assertFalse("an ordinary take carries no settings_fallback key (#193)", payload.containsKey("settings_fallback"))
        assertEquals("DECODE_FAILED", payload["asr_failure_reason"])
        assertTrue("absent facts stay absent, never zero", !payload.containsKey("peak_amplitude") && !payload.containsKey("recording_s"))
    }

    @Test
    fun everyEventNameIsOnTheClosedList() {
        val names = listOf(
            "app.launched", "dictation.terminal", "insertion.terminal", "dictation.interrupted", "dictation.refused",
            "onboarding.stage_reached", "onboarding.completed", "onboarding.practice", "model_delivery.terminal",
            "settings.changed", "api_key.changed", "api_key.validation_completed",
        )
        val built = listOf(
            AnalyticsEvent.AppLaunched("m", "16", false, true, true, true, true, 0, emptyMap()).name,
            AnalyticsEvent.DictationTerminal("t", TerminalReason.COMPLETED, null, TriggerSource.APP, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null).name,
            AnalyticsEvent.InsertionTerminal("t", null, InsertionResultKind.PASTED, InsertionRouteKind.PASTE, null, null, null, false).name,
            AnalyticsEvent.DictationInterrupted("t", TakeStage.RECORDING, "tile").name,
            AnalyticsEvent.DictationRefused("busy", TriggerSource.TILE).name,
            AnalyticsEvent.OnboardingStageReached("welcome", 1.0).name,
            AnalyticsEvent.OnboardingCompleted(9.0).name,
            AnalyticsEvent.OnboardingPractice("tap", "landed").name,
            AnalyticsEvent.ModelDeliveryTerminal("parakeet", "ready", "mirror", null, null, null, true).name,
            AnalyticsEvent.SettingsChanged("filler_removal", "on", "off").name,
            AnalyticsEvent.ApiKeyChanged("openai", "save", "success").name,
            AnalyticsEvent.ApiKeyValidationCompleted("openai", "valid").name,
        )
        assertEquals(names, built)
        // #307: the debug log prints only these names; every subclass literal is in the set, and nothing else is.
        val source = java.io.File("src/main/java/com/envi/wispr/telemetry/AnalyticsEvent.kt").readText()
        val literals = Regex("""AnalyticsEvent\("([^"]+)"\)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(names.toSet(), literals)
        assertEquals(names.toSet(), AnalyticsEvent.NAMES)
    }

    /**
     * #309: the arbiter's production sink, moved out of the session owner, makes its five calls in order. The rig
     * replaces the sink and no JVM seam reaches `Telemetry.journal`, so the order is read from the source. REVERT: drop
     * the journal commit, or end the error scope before it.
     */
    @Test
    fun theTakeEndingReportLogsBreadcrumbsReportsCommitsThenLeavesTheScope() {
        val body = java.io.File("src/main/java/com/envi/wispr/telemetry/TakeEndingReport.kt").readText()
            .substringAfter("internal fun recordTakeEnding(takeFacts: TakeFacts, reason: TerminalReason) {")
        val calls = listOf(
            "DebugSessionLog.log(\"Take terminal: ",
            "Telemetry.breadcrumb(\"take\", \"terminal\",",
            "Telemetry.defect(defect,",
            "Telemetry.journal?.terminal(takeFacts.terminal(reason))",
            "Telemetry.takeEnded(takeFacts.takeId)",
        )
        val at = calls.map { body.indexOf(it) }
        assertTrue("every call is present: $at", at.all { it >= 0 })
        assertEquals("in order", at.sorted(), at)
        val owner = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
        assertTrue(owner.contains("private val endingSink: (TakeFacts, TerminalReason) -> Unit = ::recordTakeEnding,"))
    }

    // ---- TelemetryFacadeTest: a limb before bootstrap

    /**
     * Drift Guard (#193): the Sentry breadcrumb a fallback take leaves has one shape, category `take`,
     * message `settings_fallback`, data `take_id` and `settings_fallback`, sent only on a fallback take
     * (inside the `fallbackToken()?.let` block). The facade has no test seam, so the shape is read from
     * the preparer's source (since #281). REVERT: rename the message or drop a data key in `TakeStartPreparer.prepare`.
     */
    @Test
    fun theSettingsFallbackBreadcrumbHasOneShapeAndIsSentOnlyOnAFallbackTake() {
        val preparer = java.io.File("src/main/java/com/envi/wispr/ui/TakeStartPreparer.kt").readText()
        val block = preparer.substringAfter("start.fallbackToken()?.let { token ->").substringBefore("\n        }\n")
        assertTrue(block.contains("""Telemetry.breadcrumb("take", "settings_fallback", mapOf("take_id" to takeId, "settings_fallback" to token))"""))
        assertEquals("one breadcrumb, inside the fallback block only", 1, preparer.split("\"settings_fallback\", mapOf(").size - 1)
        val owner = java.io.File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
        assertEquals("and none in the owner", 1, owner.split("\"settings_fallback\", mapOf(").size)
    }

    @Test
    fun everyFacadeEntryIsANoOpBeforeBootstrap() {
        val status = Telemetry.status()
        assertFalse(status.sentry)
        assertFalse(status.postHog)
        assertNull(status.installId)
        // None of these may throw or start anything.
        Telemetry.capture(AnalyticsEvent.OnboardingCompleted(1.0))
        Telemetry.breadcrumb("take", "admitted", mapOf("take_id" to "x"))
        Telemetry.defect(AppDefect.DebugProbe)
        Telemetry.takeStarted("t")
        Telemetry.takeEnded("t")
        assertFalse(Telemetry.analyticsEnabled)
    }

    @Test
    fun theInstallIdIsAcceptedOnlyInItsCanonicalLowercaseFormVerbatim() {
        assertEquals("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", InstallIdentity.canonical("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"))
        assertNull("uppercase is corrupt, never normalised", InstallIdentity.canonical("0A1B2C3D-4E5F-4A6B-8C7D-9E8F7A6B5C4D"))
        assertNull(InstallIdentity.canonical("0a1b2c3d4e5f4a6b8c7d9e8f7a6b5c4d"))
        assertNull(InstallIdentity.canonical(""))
        assertNull(InstallIdentity.canonical("not a uuid"))
    }
}
