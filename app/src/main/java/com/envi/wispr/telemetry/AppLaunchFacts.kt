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
        val policy = runCatching { ProviderConfigurationRepository(context).loadPolicy().freshPolicy?.let { PolishContext.from(it).encode() } }.getOrNull() ?: UNKNOWN
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
            return SETTING_KEYS.associateWith { UNKNOWN } + (POLISH_POLICY to polishPolicy)
        }
        return mapOf(
            FILLER_REMOVAL to onOff(state.fillerRemovalEnabled),
            EMOJI_FORMATTER to onOff(state.emojiFormatterEnabled),
            SPOKEN_PUNCTUATION to onOff(state.spokenPunctuationEnabled),
            AUTO_COPY_TO_CLIPBOARD to onOff(state.autoCopyToClipboard),
            RESTORE_CLIPBOARD_AFTER_PASTE to onOff(state.restoreClipboardAfterPaste),
            SMART_INSERTION to onOff(state.smartInsertionEnabled),
            AUTO_STOP_ON_SILENCE to onOff(state.autoStopOnSilenceEnabled),
            SILENCE_PAUSE_SECONDS to state.silencePauseSeconds,
            INPUT_DEVICE to TakeFacts.inputDeviceToken(state.inputDevicePick),
            SHOW_BLUETOOTH_TIPS to onOff(state.showBluetoothTips),
            KEEP_EARBUDS_READY to onOff(state.keepEarbudsReady),
            DYNAMIC_COLOR to onOff(state.dynamicColorEnabled),
            BUBBLE_LOOK to state.bubbleLook.name.lowercase(),
            POLISH_POLICY to polishPolicy,
        )
    }

    const val ON = "on"
    const val OFF = "off"

    fun onOff(value: Boolean): String = if (value) ON else OFF

    // The setting names: the `app.launched` keys AND the `settings.changed` row's `setting` values (#307).
    const val FILLER_REMOVAL = "filler_removal"
    const val EMOJI_FORMATTER = "emoji_formatter"
    const val SPOKEN_PUNCTUATION = "spoken_punctuation"
    const val AUTO_COPY_TO_CLIPBOARD = "auto_copy_to_clipboard"
    const val RESTORE_CLIPBOARD_AFTER_PASTE = "restore_clipboard_after_paste"
    const val SMART_INSERTION = "smart_insertion"
    const val AUTO_STOP_ON_SILENCE = "auto_stop_on_silence"
    const val SILENCE_PAUSE_SECONDS = "silence_pause_seconds"
    const val INPUT_DEVICE = "input_device"
    const val SHOW_BLUETOOTH_TIPS = "show_bluetooth_tips"
    const val KEEP_EARBUDS_READY = "keep_earbuds_ready"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val BUBBLE_LOOK = "bubble_look"
    const val POLISH_POLICY = "polish_policy"

    /** The ten settings projected as [ON] or [OFF]. */
    val ON_OFF_SETTINGS: List<String> = listOf(
        FILLER_REMOVAL, EMOJI_FORMATTER, SPOKEN_PUNCTUATION, AUTO_COPY_TO_CLIPBOARD, RESTORE_CLIPBOARD_AFTER_PASTE,
        SMART_INSERTION, AUTO_STOP_ON_SILENCE, SHOW_BLUETOOTH_TIPS, KEEP_EARBUDS_READY, DYNAMIC_COLOR,
    )

    /** Every persisted setting the projection reports; [POLISH_POLICY] is the repository's, read separately. */
    private val SETTING_KEYS: List<String> = ON_OFF_SETTINGS + listOf(SILENCE_PAUSE_SECONDS, INPUT_DEVICE, BUBBLE_LOOK)

    /** Every name a `settings.changed` row can carry. */
    val SETTING_NAMES: List<String> = SETTING_KEYS + POLISH_POLICY
}
