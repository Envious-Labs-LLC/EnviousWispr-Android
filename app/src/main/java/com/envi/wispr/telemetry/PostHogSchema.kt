package com.envi.wispr.telemetry

import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.audio.InputRouteKind
import com.envi.wispr.audio.InputRouteReason
import com.envi.wispr.insertion.ClipboardOutcome
import com.envi.wispr.models.DeliveryFailureReason
import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelSourceHost
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishReason
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ui.ApiKeyTelemetry
import com.envi.wispr.ui.OnboardingStage
import com.envi.wispr.ui.PracticeLesson
import com.envi.wispr.ui.PracticeOutcome
import com.envi.wispr.ui.PreferenceRead
import com.envi.wispr.ui.TerminalReason
import com.envi.wispr.ui.TerminalResult
import com.envi.wispr.ui.TriggerSource

/**
 * Every string a PostHog row may carry, per key (#307): the one table `PayloadSanitizer` reads. A key's rule is
 * either the CLOSED set of values its producers can make, built from the producing enum or the producer's named
 * constants so the two cannot differ, or a SHAPE for the few values that are not finite (ids, package names,
 * device labels, a settings reader's exception type, a pause length). A key with no rule admits no string at all.
 *
 * The sets keep the producers' spelling, uppercase included (`TerminalReason.name`, `cloud:<PROVIDER>`).
 */
internal object PostHogSchema {
    sealed class ValueRule {
        abstract fun admits(value: String): Boolean

        data class Closed(val values: Set<String>) : ValueRule() {
            override fun admits(value: String): Boolean = value in values
        }

        class Shaped(val shape: Regex) : ValueRule() {
            override fun admits(value: String): Boolean = shape.matches(value)
        }
    }

    private val UUID_SHAPE = Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z")
    private val PACKAGE_SHAPE = Regex("\\A[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\\z")
    /** Printable ASCII, no spaces: a model number, a version, a locale, a timezone id. */
    private val LABEL_SHAPE = Regex("\\A[\\x21-\\x7E]{1,64}\\z")
    /** Printable ASCII with single spaces: a manufacturer's model name ("Galaxy S26 Ultra"). */
    private val NAME_SHAPE = Regex("\\A[\\x21-\\x7E]+( [\\x21-\\x7E]+){0,4}\\z")

    /** `ReadAnswers.fallbackToken`: one reader and its reason, or both readers and exactly two reasons. */
    private val FALLBACK_REASON =
        "(${PreferenceRead.Failed.COMPLETED_WITHOUT_VALUE}|${PreferenceRead.Failed.TIMED_OUT}|exception:${SentrySchema.THROWABLE_NAME})"
    private val SETTINGS_FALLBACK_SHAPE = Regex("\\A((settings|terms):$FALLBACK_REASON|both:$FALLBACK_REASON:$FALLBACK_REASON)\\z")

    /** A pause length, `Float.toString()` of a sanitised value. */
    private const val DECIMAL = "-?[0-9]+(\\.[0-9]+)?"

    private fun lower(values: Iterable<Enum<*>>): Set<String> = values.map { it.name.lowercase() }.toSet()

    private val onOff = ValueRule.Closed(setOf(AppLaunchFacts.ON, AppLaunchFacts.OFF, AppLaunchFacts.UNKNOWN))

    /** Each setting's value set, the `app.launched` projection's. */
    private val settingValues: Map<String, ValueRule.Closed> =
        AppLaunchFacts.ON_OFF_SETTINGS.associateWith { onOff } + mapOf(
            // The number is sent as a number; only a failed read is a string.
            AppLaunchFacts.SILENCE_PAUSE_SECONDS to ValueRule.Closed(setOf(AppLaunchFacts.UNKNOWN)),
            AppLaunchFacts.INPUT_DEVICE to ValueRule.Closed(setOf(TakeFacts.INPUT_AUTO, TakeFacts.INPUT_PICKED, AppLaunchFacts.UNKNOWN)),
            AppLaunchFacts.BUBBLE_LOOK to ValueRule.Closed(lower(BubbleLook.entries) + AppLaunchFacts.UNKNOWN),
            AppLaunchFacts.POLISH_POLICY to ValueRule.Closed(PolishContext.TOKENS + AppLaunchFacts.UNKNOWN),
        )

    /** `settings.changed` `from`/`to`: any setting's value, or the pause length as `Float.toString()`. */
    private val settingChangeValue = ValueRule.Shaped(
        Regex("\\A(${settingValues.values.flatMap { it.values }.toSet().joinToString("|") { Regex.escape(it) }}|$DECIMAL)\\z"),
    )

    val values: Map<String, ValueRule> = mapOf(
        // Bounded dynamic strings: an id, a package, a device or build label.
        "take_id" to ValueRule.Shaped(UUID_SHAPE), "distinct_id" to ValueRule.Shaped(UUID_SHAPE),
        "process_run_id" to ValueRule.Shaped(UUID_SHAPE), "\$session_id" to ValueRule.Shaped(UUID_SHAPE),
        "target_app" to ValueRule.Shaped(PACKAGE_SHAPE),
        "device_model" to ValueRule.Shaped(NAME_SHAPE), "\$device_model" to ValueRule.Shaped(NAME_SHAPE),
        "\$device_manufacturer" to ValueRule.Shaped(NAME_SHAPE),
        "os_version" to ValueRule.Shaped(LABEL_SHAPE), "\$os_version" to ValueRule.Shaped(LABEL_SHAPE),
        "\$os_name" to ValueRule.Shaped(LABEL_SHAPE), "\$app_version" to ValueRule.Shaped(LABEL_SHAPE),
        "\$app_build" to ValueRule.Shaped(LABEL_SHAPE), "\$locale" to ValueRule.Shaped(LABEL_SHAPE),
        "\$lib" to ValueRule.Shaped(LABEL_SHAPE), "\$lib_version" to ValueRule.Shaped(LABEL_SHAPE),
        "app_version" to ValueRule.Shaped(LABEL_SHAPE), "app" to ValueRule.Shaped(LABEL_SHAPE),
        "environment" to ValueRule.Shaped(LABEL_SHAPE),

        // dictation.terminal, dictation.refused, insertion.terminal, the api_key rows
        "result" to ValueRule.Closed(
            TerminalResult.entries.map { it.wire }.toSet() +
                InsertionResultKind.entries.map { it.stored } +
                ApiKeyTelemetry.CHANGE_RESULTS + ApiKeyTelemetry.CHECK_RESULTS,
        ),
        // dictation.terminal (a FAILED ending's name), dictation.refused, model_delivery.terminal
        "reason" to ValueRule.Closed(
            TerminalReason.entries.filter { it.result == TerminalResult.FAILED }.map { it.name }.toSet() +
                AnalyticsEvent.DictationRefused.REASONS +
                DeliveryFailureReason.entries.map { it.wire },
        ),
        "asr_failure_reason" to ValueRule.Closed(AsrFailureReason.entries.map { it.name }.toSet()),
        "trigger_source" to ValueRule.Closed(TriggerSource.entries.map { it.wire }.toSet()),
        "route_kind" to ValueRule.Closed(lower(InputRouteKind.entries)),
        "route_reason" to ValueRule.Closed(lower(InputRouteReason.entries)),
        "live_state" to ValueRule.Closed(setOf(TakeFacts.LIVE_READY, TakeFacts.LIVE_FORCED)),
        "silence_stop_status" to ValueRule.Closed(TakeFacts.SILENCE_STATUS_TOKENS),
        "capture_terminal" to ValueRule.Closed(TakeFacts.CAPTURE_ENDING_TOKENS),
        "polish_provider" to ValueRule.Closed(PolishContext.TOKENS),
        "polish_reason" to ValueRule.Closed(PolishReason.entries.map { it.name }.toSet()),
        "history_save" to ValueRule.Closed(setOf(TakeFacts.HISTORY_OK, TakeFacts.HISTORY_FAILED, TakeFacts.HISTORY_PENDING)),
        "settings_fallback" to ValueRule.Shaped(SETTINGS_FALLBACK_SHAPE),
        // dictation.interrupted, onboarding.stage_reached
        "stage" to ValueRule.Closed(lower(TakeStage.entries) + lower(OnboardingStage.entries)),
        "handoff" to ValueRule.Closed(lower(InsertionHandoff.entries)),
        "route" to ValueRule.Closed(InsertionRouteKind.entries.map { it.wire }.toSet()),
        "clipboard_outcome" to ValueRule.Closed(lower(ClipboardOutcome.entries)),

        // settings.changed
        "setting" to ValueRule.Closed(AppLaunchFacts.SETTING_NAMES.toSet()),
        "from" to settingChangeValue,
        "to" to settingChangeValue,

        // onboarding.practice, model_delivery.terminal
        "lesson" to ValueRule.Closed(lower(PracticeLesson.entries)),
        // A practice verdict is reported only once the take ended, never while it is still WORKING.
        "outcome" to ValueRule.Closed(lower(PracticeOutcome.entries.filter { it != PracticeOutcome.WORKING }) + lower(DownloadState.entries)),
        "model" to ValueRule.Closed(ModelManifest.all.map { it.id }.toSet()),
        "source_host" to ValueRule.Closed(ModelSourceHost.entries.map { it.wire }.toSet()),
        "bytes_bucket" to ValueRule.Closed(ModelDeliveryWorker.BYTES_BUCKETS),

        // api_key rows
        "provider" to ValueRule.Closed(lower(Provider.entries)),
        "action" to ValueRule.Closed(ApiKeyTelemetry.ACTIONS),
    ) + settingValues
}
