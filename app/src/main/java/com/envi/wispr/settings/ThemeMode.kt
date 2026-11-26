package com.envi.wispr.settings

/**
 * Light, dark, or system theme override (`PAR-097`), matching macOS `AppearancePreference`:
 * `"System"` follows the device setting, while `"Light"` and `"Dark"` pin the app's UI theme.
 */
internal enum class ThemeMode(
    /** What DataStore holds. Never renamed: an unknown stored key falls back to [DEFAULT]. */
    val storageKey: String,
    /** What Settings displays. */
    val title: String,
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    fun resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromStorage(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
