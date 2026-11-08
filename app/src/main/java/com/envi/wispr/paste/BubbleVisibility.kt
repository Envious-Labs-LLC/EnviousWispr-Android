package com.envi.wispr.paste

/**
 * Whether the IDLE floating bubble may be drawn on a focused field (#523). Display only: the tracker still
 * remembers the field, so the side button, the tile and insertion are unchanged, and a running take's
 * recorder is drawn by the overlay before any field state is read.
 *
 * Order: a locked phone first (nothing can be inserted there), then the user's hidden apps, then password
 * boxes. A wrong hide costs the primary trigger, so every unknown input is passed in as the value that SHOWS.
 */
internal object BubbleVisibility {

    sealed interface Decision {
        data object Show : Decision
        data object HiddenLocked : Decision
        data object HiddenApp : Decision
        data object HiddenPassword : Decision
    }

    fun decide(
        keyguardLocked: Boolean,
        passwordField: Boolean,
        packageName: String?,
        hiddenApps: Set<String>,
        showOnPasswords: Boolean,
    ): Decision = when {
        keyguardLocked -> Decision.HiddenLocked
        packageName != null && packageName in hiddenApps -> Decision.HiddenApp
        passwordField && !showOnPasswords -> Decision.HiddenPassword
        else -> Decision.Show
    }

    /** Content-free label for the debug log: the reason, never the package. */
    fun reason(decision: Decision): String = when (decision) {
        Decision.Show -> "SHOW"
        Decision.HiddenLocked -> "LOCKED"
        Decision.HiddenApp -> "APP"
        Decision.HiddenPassword -> "PASSWORD"
    }
}
