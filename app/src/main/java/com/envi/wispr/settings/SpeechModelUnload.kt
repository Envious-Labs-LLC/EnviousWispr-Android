package com.envi.wispr.settings

/**
 * "Unload model after" for the speech model (#37), the macOS setting `macos.audio.model-unload` ported one to one:
 * the same choices and labels, applied from the moment a dictation's transcript arrives. The default is NEVER on
 * Android as on macOS (founder 2026-10-21): the model stays loaded until Android ends the app or the user picks a
 * shorter time, so the next dictation skips the load (4 to 7 s on Galaxy A15 and A35, 3.4 s on the S26).
 */
internal enum class SpeechModelUnload(
    /** What DataStore holds. Never renamed: a stored key that no longer parses falls to [DEFAULT]. */
    val storageKey: String,
    /** What the settings list shows (the macOS labels). */
    val title: String,
    /** How long the model stays loaded after a transcript; null for [NEVER] (no limit) and [IMMEDIATELY] (none). */
    val holdMillis: Long?,
) {
    NEVER("never", "Never", null),
    IMMEDIATELY("immediately", "Immediately", null),
    TWO_MINUTES("two_minutes", "After 2 minutes", 2 * 60_000L),
    FIVE_MINUTES("five_minutes", "After 5 minutes", 5 * 60_000L),
    TEN_MINUTES("ten_minutes", "After 10 minutes", 10 * 60_000L),
    FIFTEEN_MINUTES("fifteen_minutes", "After 15 minutes", 15 * 60_000L),
    SIXTY_MINUTES("sixty_minutes", "After 1 hour", 60 * 60_000L);

    companion object {
        val DEFAULT = NEVER

        /** The choice a stored key names, or [DEFAULT] for null, blank, or a key no choice carries. */
        fun fromStorage(key: String?): SpeechModelUnload = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
