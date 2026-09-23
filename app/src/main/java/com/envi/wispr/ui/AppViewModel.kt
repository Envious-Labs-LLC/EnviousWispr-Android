package com.envi.wispr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.AppLaunchFacts
import com.envi.wispr.telemetry.TakeFacts
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.vad.SilenceStopDetector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app shell's own state (#218): the preferences and nothing a feature owns. History, Dictionary, AI
 * Polish and readiness each have their own view model; [AppUiState] joins them for the screens.
 */
internal data class EnviousWisprUiState(
    /** False only in the initial value; every value built from a real preferences emission sets it. */
    val loaded: Boolean = false,
    val preferences: AppPreferencesState = AppPreferencesState(),
) {
    val shouldShowOnboarding: Boolean
        get() = loaded &&
            !preferences.onboardingComplete &&
            !preferences.onboardingDismissed
}

/** Owns the app's preferences (#218): the settings writes with their telemetry, and onboarding. */
internal class EnviousWisprViewModel(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<EnviousWisprUiState> = appPreferences.state.map { preferences ->
        EnviousWisprUiState(loaded = true, preferences = preferences)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EnviousWisprUiState(),
    )

    fun setOnboardingStep(step: Int) {
        viewModelScope.launch {
            appPreferences.setOnboardingStep(step)
            OnboardingStage.entries.getOrNull(step)?.let { stage ->
                Telemetry.capture(AnalyticsEvent.OnboardingStageReached(stage.name.lowercase(), Telemetry.secondsSinceProcessStart()))
            }
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            appPreferences.dismissOnboarding()
        }
    }

    fun resumeOnboarding() {
        viewModelScope.launch {
            appPreferences.resumeOnboarding()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            appPreferences.completeOnboarding()
            Telemetry.capture(AnalyticsEvent.OnboardingCompleted(Telemetry.secondsSinceProcessStart()))
        }
    }

    /**
     * One settings write plus its `settings.changed` row (issue #176): `from` is the PERSISTED value
     * read before the write, never the UI's, and nothing leaves when the value did not change. The
     * key names are the `app.launched` projection's, so one query reconstructs a phone's settings.
     */
    private fun changeSetting(
        setting: String,
        to: String,
        from: (AppPreferencesState) -> String,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            val before = runCatching { from(appPreferences.authoritativeState.first()) }.getOrDefault(AppLaunchFacts.UNKNOWN)
            write()
            if (before != to) Telemetry.capture(AnalyticsEvent.SettingsChanged(setting, before, to))
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) =
        changeSetting("dynamic_color", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.dynamicColorEnabled) }) { appPreferences.setDynamicColorEnabled(enabled) }

    fun setBubbleLook(look: BubbleLook) =
        changeSetting("bubble_look", look.name.lowercase(), { it.bubbleLook.name.lowercase() }) { appPreferences.setBubbleLook(look) }

    fun setFillerRemovalEnabled(enabled: Boolean) =
        changeSetting("filler_removal", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.fillerRemovalEnabled) }) { appPreferences.setFillerRemovalEnabled(enabled) }

    fun setEmojiFormatterEnabled(enabled: Boolean) =
        changeSetting("emoji_formatter", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.emojiFormatterEnabled) }) { appPreferences.setEmojiFormatterEnabled(enabled) }

    fun setAutoStopOnSilenceEnabled(enabled: Boolean) =
        changeSetting("auto_stop_on_silence", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.autoStopOnSilenceEnabled) }) { appPreferences.setAutoStopOnSilenceEnabled(enabled) }

    /** The value is clamped in the store as well, so a bad one never reaches a take. */
    fun setSilencePauseSeconds(seconds: Float) =
        changeSetting("silence_pause_seconds", SilenceStopDetector.sanitisePauseSeconds(seconds).toString(), { it.silencePauseSeconds.toString() }) { appPreferences.setSilencePauseSeconds(seconds) }

    fun setInputDevicePick(pick: InputDevicePick) =
        changeSetting("input_device", TakeFacts.inputDeviceToken(pick.serialize()), { TakeFacts.inputDeviceToken(it.inputDevicePick) }) { appPreferences.setInputDevicePick(pick) }

    fun setKeepEarbudsReady(enabled: Boolean) =
        changeSetting("keep_earbuds_ready", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.keepEarbudsReady) }) { appPreferences.setKeepEarbudsReady(enabled) }

    fun setShowBluetoothTips(enabled: Boolean) =
        changeSetting("show_bluetooth_tips", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.showBluetoothTips) }) { appPreferences.setShowBluetoothTips(enabled) }

    fun setSpokenPunctuationEnabled(enabled: Boolean) =
        changeSetting("spoken_punctuation", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.spokenPunctuationEnabled) }) { appPreferences.setSpokenPunctuationEnabled(enabled) }

    fun setAutoCopyToClipboard(enabled: Boolean) =
        changeSetting("auto_copy_to_clipboard", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.autoCopyToClipboard) }) { appPreferences.setAutoCopyToClipboard(enabled) }

    fun setRestoreClipboardAfterPaste(enabled: Boolean) =
        changeSetting("restore_clipboard_after_paste", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.restoreClipboardAfterPaste) }) { appPreferences.setRestoreClipboardAfterPaste(enabled) }

    fun setSmartInsertionEnabled(enabled: Boolean) =
        changeSetting("smart_insertion", AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.smartInsertionEnabled) }) { appPreferences.setSmartInsertionEnabled(enabled) }

    class Factory(
        private val appPreferences: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(EnviousWisprViewModel::class.java))
            return EnviousWisprViewModel(appPreferences) as T
        }
    }
}
