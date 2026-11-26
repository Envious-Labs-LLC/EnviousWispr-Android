package com.envi.wispr.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Product Outcome (PAR-097): Light, Dark, and System theme choices resolve accurately regardless
 * of phone setting, survive app storage restarts, and fall back safely on unknown stored keys.
 */
class ThemeModeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun <T> withClosedStore(file: File, block: suspend (AppPreferences) -> T): T {
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(AppPreferences(store))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun storageKeysAndTitlesMatchMacOsPreference() {
        assertEquals(
            listOf(
                "system" to "System",
                "light" to "Light",
                "dark" to "Dark",
            ),
            ThemeMode.entries.map { it.storageKey to it.title },
        )
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.SYSTEM, AppPreferencesState().themeMode)
    }

    @Test
    fun unknownStoredKeysFallBackToSystem() {
        listOf(null, "", "purple", "DARK", "night").forEach { raw ->
            assertEquals(raw.toString(), ThemeMode.SYSTEM, ThemeMode.fromStorage(raw))
        }
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("system"))
    }

    @Test
    fun resolveDarkThemeHonorsOverrideAgainstPhoneSetting() {
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = false))
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = true))

        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemInDarkTheme = false))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(systemInDarkTheme = true))

        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(systemInDarkTheme = true))
    }

    @Test
    fun themeModePersistsAcrossClosedStoresAndResetsToSystem() = runTest {
        val file = tempFolder.newFile("theme_mode.preferences_pb")

        withClosedStore(file) { prefs ->
            assertEquals(ThemeMode.SYSTEM, prefs.state.first().themeMode)
            prefs.setThemeMode(ThemeMode.DARK)
        }
        assertEquals(ThemeMode.DARK, withClosedStore(file) { it.state.first().themeMode })

        withClosedStore(file) { it.setThemeMode(ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, withClosedStore(file) { it.state.first().themeMode })

        withClosedStore(file) { it.setThemeMode(ThemeMode.SYSTEM) }
        assertEquals(ThemeMode.SYSTEM, withClosedStore(file) { it.state.first().themeMode })

        // Corrupt storage falls back to System when reopened by a fresh instance.
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            store.edit { it[stringPreferencesKey("theme_mode")] = "corrupt_value" }
        } finally {
            job.cancelAndJoin()
        }
        assertEquals(ThemeMode.SYSTEM, withClosedStore(file) { it.state.first().themeMode })
    }
}
