package com.envi.wispr.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.enviousWisprDataStore by preferencesDataStore(name = "enviouswispr_settings")

data class AppPreferencesState(
    val onboardingStep: Int = 0,
    val onboardingComplete: Boolean = false,
    val onboardingDismissed: Boolean = false,
    // OFF by default, so what a user sees out of the box is EnviousWispr rather than their wallpaper.
    // The two defaults have to agree: this one is what the UI renders before DataStore has delivered,
    // and `mapState` is what it settles on. They disagreed once and the app flashed the wrong theme.
    val dynamicColorEnabled: Boolean = false,
    val fillerRemovalEnabled: Boolean = true,
    val emojiFormatterEnabled: Boolean = true,
    val spokenPunctuationEnabled: Boolean = false,
    val autoCopyToClipboard: Boolean = true,
    val restoreClipboardAfterPaste: Boolean = true,
    val smartInsertionEnabled: Boolean = true,
)

fun AppPreferencesState.cleanupOptions(): CleanupOptions = CleanupOptions(
    removeFillers = fillerRemovalEnabled,
    spokenEmoji = emojiFormatterEnabled,
    spokenPunctuation = spokenPunctuationEnabled,
)

fun AppPreferencesState.clipboardInsertionPolicy(): ClipboardInsertionPolicy = ClipboardInsertionPolicy(
    autoCopyToClipboard = autoCopyToClipboard,
    restoreClipboardAfterPaste = restoreClipboardAfterPaste,
    smartInsertion = smartInsertionEnabled,
)

class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.enviousWisprDataStore

    val authoritativeState: Flow<AppPreferencesState> = dataStore.data
        .map(::mapState)

    val state: Flow<AppPreferencesState> = authoritativeState
        .catch { exception ->
            if (exception is IOException) {
                emit(AppPreferencesState())
            } else {
                throw exception
            }
        }

    private fun mapState(preferences: Preferences): AppPreferencesState = AppPreferencesState(
        onboardingStep = preferences[Keys.ONBOARDING_STEP] ?: 0,
        onboardingComplete = preferences[Keys.ONBOARDING_COMPLETE] ?: false,
        onboardingDismissed = preferences[Keys.ONBOARDING_DISMISSED] ?: false,
        dynamicColorEnabled = preferences[Keys.DYNAMIC_COLOR] ?: false,
        fillerRemovalEnabled = preferences[Keys.FILLER_REMOVAL] ?: true,
        emojiFormatterEnabled = preferences[Keys.EMOJI_FORMATTER] ?: true,
        spokenPunctuationEnabled = preferences[Keys.SPOKEN_PUNCTUATION] ?: false,
        autoCopyToClipboard = preferences[Keys.AUTO_COPY_TO_CLIPBOARD] ?: true,
        restoreClipboardAfterPaste = preferences[Keys.RESTORE_CLIPBOARD_AFTER_PASTE] ?: true,
        smartInsertionEnabled = preferences[Keys.SMART_INSERTION] ?: true,
    )

    suspend fun setOnboardingStep(step: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_STEP] = step.coerceAtLeast(0)
            preferences[Keys.ONBOARDING_DISMISSED] = false
        }
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

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.DYNAMIC_COLOR] = enabled
        }
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

    private object Keys {
        val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FILLER_REMOVAL = booleanPreferencesKey("filler_removal_enabled")
        val EMOJI_FORMATTER = booleanPreferencesKey("emoji_formatter_enabled")
        val SPOKEN_PUNCTUATION = booleanPreferencesKey("spoken_punctuation_enabled")
        val AUTO_COPY_TO_CLIPBOARD = booleanPreferencesKey("auto_copy_to_clipboard")
        val RESTORE_CLIPBOARD_AFTER_PASTE = booleanPreferencesKey("restore_clipboard_after_paste")
        val SMART_INSERTION = booleanPreferencesKey("smart_insertion_enabled")
    }
}
