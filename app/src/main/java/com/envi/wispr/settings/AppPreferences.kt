package com.envi.wispr.settings

import android.content.Context
import com.envi.wispr.processing.ProcessingPreference
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.paste.BubbleLook
import com.envi.wispr.ui.OnboardingStage
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.vad.SilenceStopDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.enviousWisprDataStore by preferencesDataStore(name = "enviouswispr_settings")

internal data class AppPreferencesState(
    val onboardingStep: Int = 0,
    val onboardingMobileData: Boolean = false,
    val onboardingComplete: Boolean = false,
    val onboardingDismissed: Boolean = false,
    // OFF by default, so what a user sees out of the box is EnviousWispr rather than their wallpaper.
    // The two defaults have to agree: this one is what the UI renders before DataStore has delivered,
    // and `mapState` is what it settles on. They disagreed once and the app flashed the wrong theme.
    val dynamicColorEnabled: Boolean = false,
    val speechProcessing: ProcessingPreference = ProcessingPreference.DEFAULT,
    val speechProcessingReadError: Boolean = false,
    /** How the floating button and its recorder pills look. Same default here and in `mapState`. */
    val bubbleLook: BubbleLook = BubbleLook.DEFAULT,
    val fillerRemovalEnabled: Boolean = true,
    val emojiFormatterEnabled: Boolean = true,
    val spokenPunctuationEnabled: Boolean = false,
    val englishSpelling: com.envi.wispr.cleanup.EnglishSpelling = com.envi.wispr.cleanup.EnglishSpelling.AMERICAN,
    val autoCopyToClipboard: Boolean = true,
    val restoreClipboardAfterPaste: Boolean = true,
    val smartInsertionEnabled: Boolean = true,
    // OFF by default, matching the one canonical default across all three platforms. Auto-stop is
    // actively wrong for someone who pauses to think mid-sentence, so it is opted into, never out of.
    val autoStopOnSilenceEnabled: Boolean = false,
    val silencePauseSeconds: Float = SilenceStopDetector.DEFAULT_PAUSE_SECONDS,
    /** "auto", or "<type>|<name>": the microphone the user picked. Crosses the binder as-is. */
    val inputDevicePick: String = InputDevicePick.AUTO,
    /** The one-time Bluetooth line on the recorder, and the settings note. On, like macOS. */
    val showBluetoothTips: Boolean = true,
    /**
     * After a take on earbuds, keep the link open for 30 s with silent playback so the next take starts
     * at once (founder 2026-09-18). Frozen per take; crosses the binder on the start call. On by default.
     */
    val keepEarbudsReady: Boolean = true,
)

/** The Developer page's stored values (#378); a null switch was never set. */
internal data class DeveloperStored(val unlocked: Boolean, val detailedLog: Boolean?, val keepRecordings: Boolean?)

internal fun AppPreferencesState.cleanupOptions(): CleanupOptions = CleanupOptions(
    removeFillers = fillerRemovalEnabled,
    spokenEmoji = emojiFormatterEnabled,
    spokenPunctuation = spokenPunctuationEnabled,
    englishSpelling = englishSpelling,
)

internal fun AppPreferencesState.clipboardInsertionPolicy(): ClipboardInsertionPolicy = ClipboardInsertionPolicy(
    autoCopyToClipboard = autoCopyToClipboard,
    restoreClipboardAfterPaste = restoreClipboardAfterPaste,
    smartInsertion = smartInsertionEnabled,
)

internal class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.enviousWisprDataStore

    val authoritativeState: Flow<AppPreferencesState> = dataStore.data
        .map { mapState(it) }

    val state: Flow<AppPreferencesState> = dataStore.data.map { mapState(it, strictProcessing = false) }
        .catch { exception ->
            if (exception is IOException) {
                emit(AppPreferencesState(speechProcessingReadError = true))
            } else {
                throw exception
            }
        }

    private fun mapState(preferences: Preferences, strictProcessing: Boolean = true): AppPreferencesState = AppPreferencesState(
        onboardingStep = if (preferences[Keys.ONBOARDING_VERSION] == 2) preferences[Keys.ONBOARDING_STEP] ?: 0 else 0,
        onboardingMobileData = preferences[Keys.ONBOARDING_MOBILE_DATA] ?: false,
        onboardingComplete = preferences[Keys.ONBOARDING_COMPLETE] ?: false,
        onboardingDismissed = preferences[Keys.ONBOARDING_DISMISSED] ?: false,
        dynamicColorEnabled = preferences[Keys.DYNAMIC_COLOR] ?: false,
        speechProcessing = if (strictProcessing) ProcessingPreference.decode(preferences[Keys.SPEECH_PROCESSING]) else
            runCatching { ProcessingPreference.decode(preferences[Keys.SPEECH_PROCESSING]) }.getOrDefault(ProcessingPreference.DEFAULT),
        speechProcessingReadError = runCatching { ProcessingPreference.decode(preferences[Keys.SPEECH_PROCESSING]) }.isFailure,
        bubbleLook = BubbleLook.fromStorage(preferences[Keys.BUBBLE_LOOK]),
        fillerRemovalEnabled = preferences[Keys.FILLER_REMOVAL] ?: true,
        emojiFormatterEnabled = preferences[Keys.EMOJI_FORMATTER] ?: true,
        spokenPunctuationEnabled = preferences[Keys.SPOKEN_PUNCTUATION] ?: false,
        englishSpelling = com.envi.wispr.cleanup.EnglishSpelling.fromStored(preferences[Keys.ENGLISH_SPELLING]),
        autoCopyToClipboard = preferences[Keys.AUTO_COPY_TO_CLIPBOARD] ?: true,
        restoreClipboardAfterPaste = preferences[Keys.RESTORE_CLIPBOARD_AFTER_PASTE] ?: true,
        smartInsertionEnabled = preferences[Keys.SMART_INSERTION] ?: true,
        autoStopOnSilenceEnabled = preferences[Keys.AUTO_STOP_ON_SILENCE] ?: false,
        // A stored value outside the slider's range is a corrupt or foreign write, never a choice
        // anyone made, so it reads as the default rather than being honoured.
        silencePauseSeconds = SilenceStopDetector.sanitisePauseSeconds(
            preferences[Keys.SILENCE_PAUSE_SECONDS] ?: SilenceStopDetector.DEFAULT_PAUSE_SECONDS,
        ),
        // Stored as the string the binder carries; garbage reads as Auto at the parse, never here.
        inputDevicePick = preferences[Keys.INPUT_DEVICE_PICK] ?: InputDevicePick.AUTO,
        showBluetoothTips = preferences[Keys.SHOW_BLUETOOTH_TIPS] ?: true,
        keepEarbudsReady = preferences[Keys.KEEP_EARBUDS_READY] ?: true,
    )

    suspend fun setOnboardingStep(step: Int) {
        dataStore.edit { preferences ->
            // The last stage, read off the enum: a literal here silently pinned setup to its old last screen
            // when the demo stage was added (emulator, 2026-09-14).
            preferences[Keys.ONBOARDING_STEP] = step.coerceIn(0, OnboardingStage.entries.lastIndex)
            preferences[Keys.ONBOARDING_VERSION] = 2
            preferences[Keys.ONBOARDING_DISMISSED] = false
        }
    }

    suspend fun setOnboardingMobileData(allowed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_MOBILE_DATA] = allowed }
    }

    suspend fun dismissOnboarding() {
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_DISMISSED] = true
        }
    }

    suspend fun resumeOnboarding() {
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_DISMISSED] = false
        }
    }

    suspend fun completeOnboarding() {
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_COMPLETE] = true
            preferences[Keys.ONBOARDING_DISMISSED] = false
        }
    }

    suspend fun setSpeechProcessing(value: ProcessingPreference) {
        dataStore.edit { it[Keys.SPEECH_PROCESSING] = value.encode() }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setBubbleLook(look: BubbleLook) {
        dataStore.edit { preferences -> preferences[Keys.BUBBLE_LOOK] = look.storageKey }
    }

    suspend fun setFillerRemovalEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.FILLER_REMOVAL] = enabled
        }
    }

    suspend fun setEmojiFormatterEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.EMOJI_FORMATTER] = enabled
        }
    }

    suspend fun setEnglishSpelling(spelling: com.envi.wispr.cleanup.EnglishSpelling) {
        dataStore.edit { it[Keys.ENGLISH_SPELLING] = spelling.name }
    }

    suspend fun setSpokenPunctuationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.SPOKEN_PUNCTUATION] = enabled
        }
    }

    suspend fun setAutoCopyToClipboard(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AUTO_COPY_TO_CLIPBOARD] = enabled }
    }

    suspend fun setRestoreClipboardAfterPaste(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.RESTORE_CLIPBOARD_AFTER_PASTE] = enabled }
    }

    suspend fun setSmartInsertionEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SMART_INSERTION] = enabled }
    }

    suspend fun setAutoStopOnSilenceEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AUTO_STOP_ON_SILENCE] = enabled }
    }

    suspend fun setInputDevicePick(pick: InputDevicePick) {
        dataStore.edit { preferences -> preferences[Keys.INPUT_DEVICE_PICK] = pick.serialize() }
    }

    suspend fun setShowBluetoothTips(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SHOW_BLUETOOTH_TIPS] = enabled }
    }

    suspend fun setKeepEarbudsReady(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.KEEP_EARBUDS_READY] = enabled }
    }

    /**
     * The hidden Developer page's stored values (#378), kept OUT of [AppPreferencesState] on purpose: that
     * state feeds `app.launched` and the settings telemetry, and neither the unlock nor the two switches may
     * ever reach PostHog or Sentry. A switch never set reads null; `DeveloperSwitches` owns its default (on in
     * a debuggable build, off otherwise) and is the only writer of these keys.
     */
    val developerStored: Flow<DeveloperStored> = dataStore.data
        .map { preferences ->
            DeveloperStored(
                unlocked = preferences[Keys.DEVELOPER_UNLOCKED] ?: false,
                detailedLog = preferences[Keys.DETAILED_LOG],
                keepRecordings = preferences[Keys.KEEP_RECORDINGS],
            )
        }

    suspend fun setDeveloperUnlocked() {
        dataStore.edit { it[Keys.DEVELOPER_UNLOCKED] = true }
    }

    suspend fun setDetailedLog(on: Boolean) {
        dataStore.edit { it[Keys.DETAILED_LOG] = on }
    }

    suspend fun setKeepRecordings(on: Boolean) {
        dataStore.edit { it[Keys.KEEP_RECORDINGS] = on }
    }

    /** Clamped on the way in as well as on the way out, so a bad value never reaches storage. */
    suspend fun setSilencePauseSeconds(seconds: Float) {
        val safe = SilenceStopDetector.sanitisePauseSeconds(seconds)
        dataStore.edit { preferences -> preferences[Keys.SILENCE_PAUSE_SECONDS] = safe }
    }

    private object Keys {
        val ONBOARDING_VERSION = intPreferencesKey("onboarding_version")
        val ONBOARDING_MOBILE_DATA = booleanPreferencesKey("onboarding_mobile_data")
        val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")
        val SPEECH_PROCESSING = stringPreferencesKey("speech_processing")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val BUBBLE_LOOK = stringPreferencesKey("bubble_look")
        val FILLER_REMOVAL = booleanPreferencesKey("filler_removal_enabled")
        val EMOJI_FORMATTER = booleanPreferencesKey("emoji_formatter_enabled")
        val ENGLISH_SPELLING = stringPreferencesKey("english_spelling")
        val SPOKEN_PUNCTUATION = booleanPreferencesKey("spoken_punctuation_enabled")
        val AUTO_COPY_TO_CLIPBOARD = booleanPreferencesKey("auto_copy_to_clipboard")
        val RESTORE_CLIPBOARD_AFTER_PASTE = booleanPreferencesKey("restore_clipboard_after_paste")
        val SMART_INSERTION = booleanPreferencesKey("smart_insertion_enabled")
        val AUTO_STOP_ON_SILENCE = booleanPreferencesKey("auto_stop_on_silence_enabled")
        val SILENCE_PAUSE_SECONDS = floatPreferencesKey("silence_pause_seconds")
        val INPUT_DEVICE_PICK = stringPreferencesKey("input_device_pick")
        val SHOW_BLUETOOTH_TIPS = booleanPreferencesKey("show_bluetooth_tips")
        val KEEP_EARBUDS_READY = booleanPreferencesKey("keep_earbuds_ready")
        val DEVELOPER_UNLOCKED = booleanPreferencesKey("developer_unlocked")
        val DETAILED_LOG = booleanPreferencesKey("developer_detailed_log")
        val KEEP_RECORDINGS = booleanPreferencesKey("developer_keep_recordings")
    }
}
