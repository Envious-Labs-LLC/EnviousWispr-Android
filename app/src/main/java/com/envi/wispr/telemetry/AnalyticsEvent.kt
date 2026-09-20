package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TriggerSource

/**
 * The closed set of PostHog rows this app can send (issue #176, plan §3.1): one subclass per row, typed
 * properties, and the wire name. A string event name cannot be captured: `Telemetry.capture` takes only
 * this type. Property VALUES are numbers, booleans, closed tokens (an enum's wire name) or one of the
 * bounded strings `PayloadSanitizer` admits (`take_id`, `target_app`, the device model); a free string
 * cannot be added without also adding its key to the sanitizer's allowlist, and `AnalyticsEventTest`
 * compares every event's payload to a literal schema.
 *
 * Event names are the Mac's where the meaning is the same, so one query spans both platforms under an
 * `app` filter. A property that is absent means "not measured", never zero (macOS #1809).
 */
sealed class AnalyticsEvent(val name: String) {
    /** The properties as they enter the volume policy and the sanitizer; nulls are dropped there. */
    abstract fun properties(): Map<String, Any?>

    /** Take-keyed rows carry the take id; the rest do not. */
    open val takeId: String? get() = null

    /** One per main-process run, from the bootstrap, so a service-only start counts (G2 D8). */
    class AppLaunched(
        val deviceModel: String,
        val osVersion: String,
        val isFreshInstall: Boolean,
        val modelsReady: Boolean,
        val accessibilityGranted: Boolean,
        val micGranted: Boolean,
        val onboardingComplete: Boolean,
        val customWordsCount: Int,
        /** Persisted settings as projections; a failed read is the `unknown` token, never a UI default. */
        val settings: Map<String, Any>,
    ) : AnalyticsEvent("app.launched") {
        override fun properties(): Map<String, Any?> = mapOf(
            "device_model" to deviceModel,
            "os_version" to osVersion,
            "is_fresh_install" to isFreshInstall,
            "models_ready" to modelsReady,
            "accessibility_granted" to accessibilityGranted,
            "mic_granted" to micGranted,
            "onboarding_complete" to onboardingComplete,
            "custom_words_count" to customWordsCount,
        ) + settings
    }

    /** The one row per take, at the arbiter's commit. Facts absent when they were never measured. */
    class DictationTerminal(
        override val takeId: String,
        val reason: TerminalReason,
        val asrFailure: AsrFailureReason?,
        val trigger: TriggerSource,
        val routeKind: InputRouteKind?,
        val routeReason: InputRouteReason?,
        val liveAfterMs: Long?,
        val liveState: String?,
        val silenceStopStatus: String?,
        val recordingSeconds: Double?,
        val inputDevice: String?,
        val asrMs: Long?,
        val asrChars: Int?,
        val peakAmplitude: Float?,
        val polishProvider: String?,
        val polishReason: PolishReason?,
        val polishMs: Long?,
        val polishStatus: Int?,
        val historySave: String?,
    ) : AnalyticsEvent("dictation.terminal") {
        override fun properties(): Map<String, Any?> = mapOf(
            "take_id" to takeId,
            "result" to reason.result.wire,
            // Only a failed ending carries a reason; presence is the signal (plan §8).
            "reason" to reason.name.takeIf { reason.result == com.envi.wispr.ui.TerminalResult.FAILED },
            "asr_failure_reason" to asrFailure?.name,
            "trigger_source" to trigger.wire,
            "route_kind" to routeKind?.name?.lowercase(),
            "route_reason" to routeReason?.name?.lowercase(),
            "live_after_ms" to liveAfterMs,
            "live_state" to liveState,
            "silence_stop_status" to silenceStopStatus,
            "recording_s" to recordingSeconds,
            "input_device" to inputDevice,
            "asr_ms" to asrMs,
            "asr_chars" to asrChars,
            "peak_amplitude" to peakAmplitude,
            "polish_provider" to polishProvider,
            "polish_reason" to polishReason?.name,
            "polish_ms" to polishMs,
            "polish_status" to polishStatus,
            "history_save" to historySave,
        )
    }

    /** Where the words went, from whichever of the three writers recorded it. */
    class InsertionTerminal(
        override val takeId: String?,
        val handoff: InsertionHandoff?,
        val result: InsertionResultKind,
        val route: String?,
        /** The target's PACKAGE name, never a display label; `com.envi.wispr` is our own field. */
        val targetApp: String?,
        val latencyMs: Long?,
        val clipboard: String?,
        val recovered: Boolean,
    ) : AnalyticsEvent("insertion.terminal") {
        override fun properties(): Map<String, Any?> = mapOf(
            "take_id" to takeId,
            "handoff" to handoff?.name?.lowercase(),
            "result" to result.stored,
            "route" to route,
            "target_app" to targetApp,
            "latency_ms" to latencyMs,
            "clipboard_outcome" to clipboard,
            "recovered" to recovered,
        )
    }

    /** A take an earlier run admitted and never ended: "no durable terminal", never proof of a crash. */
    class DictationInterrupted(override val takeId: String, val stage: TakeStage, val trigger: String) :
        AnalyticsEvent("dictation.interrupted") {
        override fun properties(): Map<String, Any?> = mapOf(
            "take_id" to takeId,
            "stage" to stage.name.lowercase(),
            "trigger_source" to trigger,
        )
    }

    class DictationRefused(val reason: String, val trigger: TriggerSource) : AnalyticsEvent("dictation.refused") {
        override fun properties(): Map<String, Any?> = mapOf("reason" to reason, "trigger_source" to trigger.wire)
    }

    class OnboardingStageReached(val stage: String, val elapsedSeconds: Double) : AnalyticsEvent("onboarding.stage_reached") {
        override fun properties(): Map<String, Any?> = mapOf("stage" to stage, "elapsed_s" to elapsedSeconds)
    }

    class OnboardingCompleted(val elapsedSeconds: Double) : AnalyticsEvent("onboarding.completed") {
        override fun properties(): Map<String, Any?> = mapOf("elapsed_s" to elapsedSeconds)
    }

    class OnboardingPractice(val lesson: String, val outcome: String) : AnalyticsEvent("onboarding.practice") {
        override fun properties(): Map<String, Any?> = mapOf("lesson" to lesson, "outcome" to outcome)
    }

    class ModelDeliveryTerminal(
        val model: String,
        val outcome: String,
        val sourceHost: String,
        val reason: String?,
        val bytesBucket: String?,
        val durationSeconds: Double?,
        val firstRun: Boolean,
    ) : AnalyticsEvent("model_delivery.terminal") {
        override fun properties(): Map<String, Any?> = mapOf(
            "model" to model,
            "outcome" to outcome,
            "source_host" to sourceHost,
            "reason" to reason,
            "bytes_bucket" to bytesBucket,
            "duration_s" to durationSeconds,
            "first_run" to firstRun,
        )
    }

    class SettingsChanged(val setting: String, val from: String, val to: String) : AnalyticsEvent("settings.changed") {
        override fun properties(): Map<String, Any?> = mapOf("setting" to setting, "from" to from, "to" to to)
    }

    class ApiKeyChanged(val provider: String, val action: String, val result: String) : AnalyticsEvent("api_key.changed") {
        override fun properties(): Map<String, Any?> = mapOf("provider" to provider, "action" to action, "result" to result)
    }

    class ApiKeyValidationCompleted(val provider: String, val result: String) : AnalyticsEvent("api_key.validation_completed") {
        override fun properties(): Map<String, Any?> = mapOf("provider" to provider, "result" to result)
    }
}
