package com.envi.wispr.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.polish.PolishContext
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.vocabulary.CustomTermRepository
import kotlinx.coroutines.flow.first

/**
 * The `app.launched` row's facts (issue #176, plan §3.1): the phone, the permissions, the models and
 * the PERSISTED settings, read once per main-process run by the bootstrap. Settings are projections of
 * what is stored, never the UI's defaults: a booleans is `on`/`off`, the pause is a number, the bubble
 * look is its enum name, the microphone pick is `auto`/`picked` (the product name never leaves), and
 * the polish policy is `PolishContext`'s token. A read that fails is the `unknown` token for every
 * setting, so a stranger's phone that cannot read its preferences is visible as such.
 */
internal object AppLaunchFacts {
    const val UNKNOWN = "unknown"

    suspend fun read(context: Context, freshInstall: Boolean): AnalyticsEvent.AppLaunched {
        val preferences = runCatching { AppPreferences(context).authoritativeState.first() }.getOrNull()
        val policy = runCatching { PolishContext.from(ProviderConfigurationRepository(context).loadPolicy()).encode() }.getOrDefault(UNKNOWN)
        val customWords = runCatching { CustomTermRepository(context).list().size }.getOrNull()
        return AnalyticsEvent.AppLaunched(
            deviceModel = Build.MODEL.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            isFreshInstall = freshInstall,
            modelsReady = runCatching { ModelStorage.isReady(context, ModelManifest.parakeet) && ModelStorage.isReady(context, ModelManifest.s1) }.getOrDefault(false),
            accessibilityGranted = runCatching { AccessibilityPermission.isGranted(context) }.getOrDefault(false),
            micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            onboardingComplete = preferences?.onboardingComplete ?: false,
            customWordsCount = customWords,
            settings = settingsProjection(preferences, policy),
        )
    }

    /** Pure, so the projection is testable against a literal without a phone. */
    fun settingsProjection(state: AppPreferencesState?, polishPolicy: String): Map<String, Any> {
        if (state == null) {
            return SETTING_KEYS.associateWith { UNKNOWN } + ("polish_policy" to polishPolicy)
        }
        return mapOf(
            "filler_removal" to onOff(state.fillerRemovalEnabled),
            "emoji_formatter" to onOff(state.emojiFormatterEnabled),
            "spoken_punctuation" to onOff(state.spokenPunctuationEnabled),
            "auto_copy_to_clipboard" to onOff(state.autoCopyToClipboard),
            "restore_clipboard_after_paste" to onOff(state.restoreClipboardAfterPaste),
            "smart_insertion" to onOff(state.smartInsertionEnabled),
            "auto_stop_on_silence" to onOff(state.autoStopOnSilenceEnabled),
            "silence_pause_seconds" to state.silencePauseSeconds,
            "input_device" to TakeFacts.inputDeviceToken(state.inputDevicePick),
            "show_bluetooth_tips" to onOff(state.showBluetoothTips),
            "keep_earbuds_ready" to onOff(state.keepEarbudsReady),
            "dynamic_color" to onOff(state.dynamicColorEnabled),
            "bubble_look" to state.bubbleLook.name.lowercase(),
            "polish_policy" to polishPolicy,
        )
    }

    fun onOff(value: Boolean): String = if (value) "on" else "off"

    private val SETTING_KEYS = listOf(
        "filler_removal", "emoji_formatter", "spoken_punctuation", "auto_copy_to_clipboard",
        "restore_clipboard_after_paste", "smart_insertion", "auto_stop_on_silence", "silence_pause_seconds",
        "input_device", "show_bluetooth_tips", "keep_earbuds_ready", "dynamic_color", "bubble_look",
    )
}
