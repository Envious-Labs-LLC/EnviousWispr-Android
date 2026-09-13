package com.envi.wispr.paste

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the bubble was last docked, on this phone, in the accessibility service's own private
 * preferences. Service-local state in the same pattern as the service's stop marker
 * (`PasteAccessibilityService.lifecyclePreferences`), never `AppPreferences`: nothing else reads it and
 * it must not ride a DataStore read on a touch.
 *
 * Reads are validated through [BubblePosition.parse], so an absent, unreadable, malformed or
 * out-of-range value yields null and the caller falls back to [BubblePosition.DEFAULT]. A failed write
 * is swallowed: the in-memory position holds for this process and the next drag writes again.
 */
internal class BubblePositionStore(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Disk. Call off the main thread. */
    fun load(): BubblePosition? = runCatching {
        val side = preferences.getString(KEY_SIDE, null)
        val fraction = if (preferences.contains(KEY_FRACTION)) preferences.getFloat(KEY_FRACTION, Float.NaN) else null
        BubblePosition.parse(side, fraction)
    }.getOrNull()

    fun save(position: BubblePosition) {
        runCatching {
            preferences.edit()
                .putString(KEY_SIDE, position.side.name)
                .putFloat(KEY_FRACTION, position.fraction)
                .apply()
        }
    }

    fun clear() {
        runCatching { preferences.edit().clear().apply() }
    }

    private companion object {
        const val PREFERENCES = "lips_bubble_position"
        const val KEY_SIDE = "side"
        const val KEY_FRACTION = "fraction"
    }
}
