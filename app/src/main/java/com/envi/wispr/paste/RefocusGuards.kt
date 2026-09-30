package com.envi.wispr.paste

/**
 * The guards that must ALL pass before the pinned editor is sent `ACTION_FOCUS` (#201), in the order they
 * run. They are lazy on purpose (each is one accessibility IPC round trip and the insertion loop asks once
 * per tick), so the first refusal stops the rest, and the window check is FIRST: a focus action is never
 * sent, nor any other node read, while the pinned window does not hold input focus (another app on top).
 * The pure shape lets a JVM test prove the order and that every guard can refuse.
 */
internal object RefocusGuards {
    enum class Guard {
        /** The pinned window has input focus right now (not another app or window on top). */
        WINDOW_FOCUSED,

        /** The pinned window's root was found. */
        WINDOW_FOUND,

        /** The pinned node copy refreshed from the live tree. */
        PIN_REFRESHED,

        /** Visible, editable, focusable, matches the pin, and NOT already focused. */
        PIN_USABLE,

        /** The pinned node advertises `ACTION_FOCUS`. */
        ADVERTISES_FOCUS,

        /** A different editable node in that window holds input focus (the sibling case, not "nothing focused"). */
        SIBLING_HOLDS_FOCUS,
    }

    /** The first guard that refuses, or null when every guard passed. Later guards are not evaluated. */
    fun firstRefusal(
        windowFocused: () -> Boolean,
        windowFound: () -> Boolean,
        pinRefreshed: () -> Boolean,
        pinUsable: () -> Boolean,
        advertisesFocus: () -> Boolean,
        siblingHoldsFocus: () -> Boolean,
    ): Guard? = when {
        !windowFocused() -> Guard.WINDOW_FOCUSED
        !windowFound() -> Guard.WINDOW_FOUND
        !pinRefreshed() -> Guard.PIN_REFRESHED
        !pinUsable() -> Guard.PIN_USABLE
        !advertisesFocus() -> Guard.ADVERTISES_FOCUS
        !siblingHoldsFocus() -> Guard.SIBLING_HOLDS_FOCUS
        else -> null
    }
}
