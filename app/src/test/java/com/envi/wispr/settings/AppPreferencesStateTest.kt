package com.envi.wispr.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product Outcome: independent persisted choices are mapped into the user's runtime policies. */
class AppPreferencesStateTest {
    @Test fun recordingDefaultsAreWhisperTickWithSoundsOffAndVibrationOn() {
        val defaults = AppPreferencesState()
        assertFalse(defaults.recordingSoundsEnabled)
        assertTrue(defaults.recordingVibrationEnabled)
        org.junit.Assert.assertEquals(com.envi.wispr.audio.RecordingSoundPairing.WHISPER_TICK, defaults.recordingSoundPairing)
        org.junit.Assert.assertEquals(com.envi.wispr.audio.RecordingSoundPairing.WHISPER_TICK, com.envi.wispr.audio.RecordingSoundPairing.fromStorage("foreign"))
    }

    @Test
    fun cleanupDefaultsMatchMacRelease() {
        val options = AppPreferencesState().cleanupOptions()

        assertTrue(options.removeFillers)
        assertTrue(options.spokenEmoji)
        assertFalse(options.spokenPunctuation)
    }

    @Test
    fun cleanupMappingPreservesEveryUserChoice() {
        val options = AppPreferencesState(
            fillerRemovalEnabled = false,
            emojiFormatterEnabled = false,
            spokenPunctuationEnabled = true,
        ).cleanupOptions()

        assertFalse(options.removeFillers)
        assertFalse(options.spokenEmoji)
        assertTrue(options.spokenPunctuation)
    }

    @Test
    fun clipboardDefaultsMatchMacRelease() {
        val policy = AppPreferencesState().clipboardInsertionPolicy()

        assertTrue(policy.autoCopyToClipboard)
        assertTrue(policy.restoreClipboardAfterPaste)
        assertTrue(policy.smartInsertion)
    }

    @Test
    fun clipboardMappingPreservesEveryUserChoice() {
        val policy = AppPreferencesState(
            autoCopyToClipboard = false,
            restoreClipboardAfterPaste = false,
            smartInsertionEnabled = false,
        ).clipboardInsertionPolicy()

        assertFalse(policy.autoCopyToClipboard)
        assertFalse(policy.restoreClipboardAfterPaste)
        assertFalse(policy.smartInsertion)
    }
}
