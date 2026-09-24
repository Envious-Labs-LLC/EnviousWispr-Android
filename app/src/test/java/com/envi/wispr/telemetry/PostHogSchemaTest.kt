package com.envi.wispr.telemetry

import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.audio.CaptureEnding
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.polish.S1ControlSettings
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderDiscovery
import com.envi.wispr.providers.ProviderKeyCheck
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.ui.ApiKeyTelemetry
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.ui.PreferenceRead
import com.envi.wispr.ui.PreferenceStart
import com.envi.wispr.ui.SettingsSnapshot
import com.envi.wispr.ui.TermsSnapshot
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-outcome test (#307): when this fails, either a stray word can ride under a PostHog field, or a value the
 * app really sends is dropped from its row. Each value is made by its REAL producer, then sanitized.
 */
class PostHogSchemaTest {

    private fun kept(key: String, value: String): Boolean = PayloadSanitizer.sanitizeValue(key, value) == value

    private fun assertKept(key: String, value: String) = assertTrue("$key=$value must leave", kept(key, value))

    /** The keys that only ever carry a number or a boolean: no string rule, so no string at all. */
    private val numberOrBooleanKeys = setOf(
        "app_build", "telemetry_policy_version",
        "live_after_ms", "recording_s", "asr_ms", "asr_chars", "peak_amplitude", "polish_ms", "polish_status",
        "settings_answer_ms", "matcher_ready_ms", "policy_loaded_ms", "admission_observed_ms", "bind_requested_ms",
        "live_received_ms", "latency_ms", "recovered",
        "is_fresh_install", "models_ready", "accessibility_granted", "mic_granted", "onboarding_complete",
        "custom_words_count", "elapsed_s", "duration_s", "first_run",
        "\$is_emulator", "\$process_person_profile", "\$sample_threshold",
    )

    @Test
    fun everyAllowedKeyIsEitherNumberOnlyOrHasAStringRule() {
        val ruled = PostHogSchema.values.keys
        assertEquals("the two lists are disjoint", emptySet<String>(), numberOrBooleanKeys intersect ruled)
        assertEquals("every rule is for an allowed key", emptySet<String>(), ruled - PayloadSanitizer.allowedKeys)
        assertEquals("every allowed key is one or the other", PayloadSanitizer.allowedKeys, numberOrBooleanKeys + ruled)
        assertEquals(89, PayloadSanitizer.allowedKeys.size)
        assertEquals(29, numberOrBooleanKeys.size)
    }

    @Test
    fun aStrayWordUnderAnyRuledKeyIsDropped() {
        // A device or build label is printable ASCII by shape, so a word fits it; every other key refuses one.
        val labels = PostHogSchema.values.filterValues { it is PostHogSchema.ValueRule.Shaped }.keys -
            setOf("settings_fallback", "from", "to", "take_id", "distinct_id", "process_run_id", "\$session_id", "target_app")
        assertEquals(14, labels.size)
        for (key in PostHogSchema.values.keys - labels) {
            assertNull("hello under $key", PayloadSanitizer.sanitizeValue(key, "hello"))
        }
        for (key in numberOrBooleanKeys) {
            assertNull("a string under the number key $key", PayloadSanitizer.sanitizeValue(key, "ready"))
        }
    }

    @Test
    fun aDroppedStringIsReportedByItsKeyAndNeverByItsValue() {
        val heard = mutableListOf<String>()
        val out = PayloadSanitizer.sanitizeProperties(mapOf("reason" to "hello", "result" to "completed"), heard::add)
        assertEquals(mapOf("result" to "completed"), out)
        assertEquals(listOf("reason"), heard)
        assertTrue(heard.none { it.contains("hello") })
    }

    @Test
    fun theDebugLogNamesOnlyAClosedEventNameAndSortedKeys() {
        val config = TelemetryConfig(
            versionName = "0.1.0", appBuild = 150, environment = "production", processName = "com.envi.wispr",
            isMainProcess = true, processRunId = "2f7a4b3c-1d2e-4f5a-8b6c-9d0e1f2a3b4c",
        )
        val lines = mutableListOf<String>()
        PostHogBootstrap.processProperties("dictation.refused", mapOf("trigger_source" to "tile", "reason" to "hello"), config, lines::add)
        PostHogBootstrap.processProperties("Meet me at six", mapOf("trigger_source" to "tile"), config, lines::add)
        assertEquals(
            listOf(
                "PostHog value dropped: reason",
                "PostHog row dictation.refused kept: app,app_build,app_version,environment,process_run_id,telemetry_policy_version,trigger_source",
                "PostHog row unknown kept: app,app_build,app_version,environment,process_run_id,telemetry_policy_version,trigger_source",
            ),
            lines,
        )
    }

    // ---- every producer's output leaves

    @Test
    fun theTakeFactsTokensLeaveForEveryBranch() {
        val statuses = listOf(
            AudioCaptureService.SILENCE_STATUS_DISABLED, AudioCaptureService.SILENCE_STATUS_PREPARING,
            AudioCaptureService.SILENCE_STATUS_READY, AudioCaptureService.SILENCE_STATUS_UNAVAILABLE,
            AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY, 99,
        )
        assertEquals(6, statuses.map(TakeFacts::silenceStatusToken).toSet().size)
        statuses.forEach { assertKept("silence_stop_status", TakeFacts.silenceStatusToken(it)) }
        val endings = listOf(CaptureEnding.NONE, CaptureEnding.MAX_DURATION, CaptureEnding.MANUAL, CaptureEnding.ERROR, CaptureEnding.SILENCE, 99)
        assertEquals(5, endings.map(TakeFacts::captureEndingToken).toSet().size)
        endings.forEach { assertKept("capture_terminal", TakeFacts.captureEndingToken(it)) }
        assertKept("capture_terminal", TakeFacts.MANUAL_ENDING)
        for (pick in listOf(InputDevicePick.AUTO, "7|Saurabh's AirPods Pro")) assertKept("input_device", TakeFacts.inputDeviceToken(pick))
        listOf(TakeFacts.LIVE_READY, TakeFacts.LIVE_FORCED).forEach { assertKept("live_state", it) }
        listOf(TakeFacts.HISTORY_OK, TakeFacts.HISTORY_FAILED, TakeFacts.HISTORY_PENDING).forEach { assertKept("history_save", it) }
    }

    @Test
    fun everyPolishPolicyTokenLeaves() {
        val control = S1ControlSettings.DEFAULT
        val policies = listOf(PolishPolicy.Off, PolishPolicy.LocalS1(control), PolishPolicy.CloudUnconfigured) +
            Provider.entries.flatMap { provider ->
                SelfHostedProtocol.entries.map { protocol -> PolishPolicy.Cloud(provider, "m", null, protocol) }
            }
        val tokens = policies.map { PolishContext.from(it).encode() }.toSet()
        assertTrue(tokens.contains("cloud:SELF_HOSTED_POLISH:ollama"))
        for (token in tokens) {
            assertKept("polish_provider", token)
            assertKept("polish_policy", token)
        }
        assertKept("polish_policy", AppLaunchFacts.UNKNOWN)
    }

    @Test
    fun theLaunchProjectionLeavesForOnOffAndAnUnreadableStore() {
        val on = AppPreferencesState(
            dynamicColorEnabled = true, fillerRemovalEnabled = true, emojiFormatterEnabled = true, spokenPunctuationEnabled = true,
            autoCopyToClipboard = true, restoreClipboardAfterPaste = true, smartInsertionEnabled = true, autoStopOnSilenceEnabled = true,
            inputDevicePick = "7|Pixel Buds", showBluetoothTips = true, keepEarbudsReady = true,
        )
        val off = AppPreferencesState(
            dynamicColorEnabled = false, fillerRemovalEnabled = false, emojiFormatterEnabled = false, spokenPunctuationEnabled = false,
            autoCopyToClipboard = false, restoreClipboardAfterPaste = false, smartInsertionEnabled = false, autoStopOnSilenceEnabled = false,
            showBluetoothTips = false, keepEarbudsReady = false,
        )
        val projections = listOf(on, off, null).map { AppLaunchFacts.settingsProjection(it, "local") }
        for (projection in projections) {
            val out = PayloadSanitizer.sanitizeProperties(projection)
            assertEquals("every projected setting leaves: $projection", projection, out)
        }
        assertEquals(
            "every launch setting is a `settings.changed` name",
            AppLaunchFacts.SETTING_NAMES.toSet(), projections.last().keys,
        )
    }

    @Test
    fun aSettingsChangeLeavesWithEveryValueASettingCanHold() {
        for (name in AppLaunchFacts.SETTING_NAMES) assertKept("setting", name)
        val values = AppLaunchFacts.settingsProjection(AppPreferencesState(), "local").values.filterIsInstance<String>() +
            AppLaunchFacts.settingsProjection(null, AppLaunchFacts.UNKNOWN).values.filterIsInstance<String>() +
            listOf("on", "off", "picked") + PolishContext.TOKENS +
            listOf(0.5f, 1.25f, 3.0f).map { it.toString() }
        for (value in values) {
            assertKept("from", value)
            assertKept("to", value)
        }
        assertNull(PayloadSanitizer.sanitizeValue("to", "hello"))
        assertNull(PayloadSanitizer.sanitizeValue("from", "1.25 seconds"))
    }

    @Test
    fun everyApiKeyTokenLeaves() {
        val verdicts = listOf(ProviderKeyCheck.Accepted, ProviderKeyCheck.NotApplicable, ProviderKeyCheck.Rejected(401), ProviderKeyCheck.Denied(403)) +
            PolishFailure.entries.map { ProviderKeyCheck.Unverified(it) }
        val outcomes = verdicts.map { ProviderDiscovery.Refused(it) } + ProviderDiscovery.Listed(emptyList(), 0L)
        for (outcome in outcomes) assertKept("result", ApiKeyTelemetry.keyCheckToken(outcome))
        for (result in ApiKeyTelemetry.CHANGE_RESULTS) assertKept("result", result)
        for (action in ApiKeyTelemetry.ACTIONS) assertKept("action", action)
        for (provider in Provider.entries) assertKept("provider", provider.name.lowercase())
    }

    @Test
    fun theRefusalReasonsAndByteBucketsLeave() {
        AnalyticsEvent.DictationRefused.REASONS.forEach { assertKept("reason", it) }
        listOf(0L, 1L, 20L shl 20, 200L shl 20, 700L shl 20, 2L shl 30).forEach { assertKept("bytes_bucket", ModelDeliveryWorker.bytesBucket(it)) }
    }

    @Test
    fun theEmitSitesUseTheNamedValuesNotLiterals() {
        fun source(path: String) = File("src/main/java/com/envi/wispr/$path").readText()
        val owner = source("ui/DictationSessionCoordinator.kt")
        assertTrue(Regex("""DictationRefused\("""").find(owner) == null)
        assertTrue(owner.contains("takeFacts.liveState = if (forced) TakeFacts.LIVE_FORCED else TakeFacts.LIVE_READY"))
        assertTrue(owner.contains("is SaveOutcome.Saved -> TakeFacts.HISTORY_OK"))
        assertTrue(owner.contains("is SaveOutcome.Failed -> TakeFacts.HISTORY_FAILED"))
        assertTrue(owner.contains("null -> TakeFacts.HISTORY_PENDING"))
        assertTrue(Regex("""changeSetting\("""").find(source("ui/AppViewModel.kt")) == null)
        val polish = source("providers/ui/PolishSettingsViewModel.kt")
        assertTrue(Regex("""SettingsChanged\("|ApiKeyChanged\([^)]*"|ApiKeyValidationCompleted\([^)]*"""").find(polish) == null)
    }

    // ---- the shapes

    @Test
    fun theSettingsFallbackShapeAdmitsOnlyTheProducersTokens() {
        val exception = PreferenceRead.Failed.exception(IOException("disk"))
        val timedOut = PreferenceRead.Failed(PreferenceRead.Failed.TIMED_OUT)
        val empty = PreferenceRead.Failed(PreferenceRead.Failed.COMPLETED_WITHOUT_VALUE)
        val starts = listOf(
            PreferenceStart(SettingsSnapshot(read = timedOut), TermsSnapshot(read = PreferenceRead.Fresh)),
            PreferenceStart(SettingsSnapshot(read = PreferenceRead.Fresh), TermsSnapshot(read = empty)),
            PreferenceStart(SettingsSnapshot(read = timedOut), TermsSnapshot(read = exception)),
        )
        val tokens = starts.map { it.fallbackToken()!! }
        assertEquals(listOf("settings:timed_out", "terms:completed_without_value", "both:timed_out:exception:IOException"), tokens)
        tokens.forEach { assertKept("settings_fallback", it) }
        // A caught class whose name lacks the Exception or Error suffix, and an anonymous one, still report.
        class StoreFailure : Exception()
        for (odd in listOf(StoreFailure(), object : RuntimeException() {})) {
            val token = PreferenceStart(SettingsSnapshot(read = PreferenceRead.Failed.exception(odd)), TermsSnapshot(read = PreferenceRead.Fresh)).fallbackToken()!!
            assertEquals("settings:exception:Exception", token)
            assertKept("settings_fallback", token)
        }
        for (bad in listOf("both:timed_out:exception:java.io.IOException", "settings:hello world", "settings:timed_out:timed_out", "both:timed_out", "settings:hello")) {
            assertNull(bad, PayloadSanitizer.sanitizeValue("settings_fallback", bad))
        }
    }

    @Test
    fun thePauseIsANumberAndOnlyAFailedReadIsAString() {
        assertEquals(1.5f, PayloadSanitizer.sanitizeValue("silence_pause_seconds", 1.5f))
        assertKept("silence_pause_seconds", AppLaunchFacts.UNKNOWN)
        assertNull(PayloadSanitizer.sanitizeValue("silence_pause_seconds", "1.5"))
    }
}
