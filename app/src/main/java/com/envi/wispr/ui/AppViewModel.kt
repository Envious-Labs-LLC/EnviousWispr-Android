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
        changeSetting(AppLaunchFacts.DYNAMIC_COLOR, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.dynamicColorEnabled) }) { appPreferences.setDynamicColorEnabled(enabled) }

    fun setBubbleLook(look: BubbleLook) =
        changeSetting(AppLaunchFacts.BUBBLE_LOOK, look.name.lowercase(), { it.bubbleLook.name.lowercase() }) { appPreferences.setBubbleLook(look) }

    fun setFillerRemovalEnabled(enabled: Boolean) =
        changeSetting(AppLaunchFacts.FILLER_REMOVAL, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.fillerRemovalEnabled) }) { appPreferences.setFillerRemovalEnabled(enabled) }

    fun setEmojiFormatterEnabled(enabled: Boolean) =
        changeSetting(AppLaunchFacts.EMOJI_FORMATTER, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.emojiFormatterEnabled) }) { appPreferences.setEmojiFormatterEnabled(enabled) }

    fun setAutoStopOnSilenceEnabled(enabled: Boolean) =
        changeSetting(AppLaunchFacts.AUTO_STOP_ON_SILENCE, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.autoStopOnSilenceEnabled) }) { appPreferences.setAutoStopOnSilenceEnabled(enabled) }

    /** The value is clamped in the store as well, so a bad one never reaches a take. */
    fun setSilencePauseSeconds(seconds: Float) =
        changeSetting(AppLaunchFacts.SILENCE_PAUSE_SECONDS, SilenceStopDetector.sanitisePauseSeconds(seconds).toString(), { it.silencePauseSeconds.toString() }) { appPreferences.setSilencePauseSeconds(seconds) }

    fun setInputDevicePick(pick: InputDevicePick) =
        changeSetting(AppLaunchFacts.INPUT_DEVICE, TakeFacts.inputDeviceToken(pick.serialize()), { TakeFacts.inputDeviceToken(it.inputDevicePick) }) { appPreferences.setInputDevicePick(pick) }

    fun setKeepEarbudsReady(enabled: Boolean) =
        changeSetting(AppLaunchFacts.KEEP_EARBUDS_READY, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.keepEarbudsReady) }) { appPreferences.setKeepEarbudsReady(enabled) }

    fun setShowBluetoothTips(enabled: Boolean) =
        changeSetting(AppLaunchFacts.SHOW_BLUETOOTH_TIPS, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.showBluetoothTips) }) { appPreferences.setShowBluetoothTips(enabled) }

    fun setSpokenPunctuationEnabled(enabled: Boolean) =
        changeSetting(AppLaunchFacts.SPOKEN_PUNCTUATION, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.spokenPunctuationEnabled) }) { appPreferences.setSpokenPunctuationEnabled(enabled) }

    fun setAutoCopyToClipboard(enabled: Boolean) =
        changeSetting(AppLaunchFacts.AUTO_COPY_TO_CLIPBOARD, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.autoCopyToClipboard) }) { appPreferences.setAutoCopyToClipboard(enabled) }

    fun setRestoreClipboardAfterPaste(enabled: Boolean) =
        changeSetting(AppLaunchFacts.RESTORE_CLIPBOARD_AFTER_PASTE, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.restoreClipboardAfterPaste) }) { appPreferences.setRestoreClipboardAfterPaste(enabled) }

    fun setSmartInsertionEnabled(enabled: Boolean) =
        changeSetting(AppLaunchFacts.SMART_INSERTION, AppLaunchFacts.onOff(enabled), { AppLaunchFacts.onOff(it.smartInsertionEnabled) }) { appPreferences.setSmartInsertionEnabled(enabled) }

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
