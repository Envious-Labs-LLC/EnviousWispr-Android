package com.envi.wispr.paste

/**
 * The three looks the user picks for the floating button, in Settings > Appearance. A look is a
 * THEME: it styles the idle bubble, the tap pill and the hold pill together, so the three surfaces
 * read as one family (founder 2026-09-14, from Codex's `docs/mockups/android-bubble-v2/themes.html`).
 *
 * The values are Codex's, ported one to one from that mock's README: one ground colour and shadow
 * shared by the bubble and both pills, no outline on any of them, and the clock and the level rail
 * carrying a thin dark ink edge so they read on a white page as well as a dark one.
 */
internal enum class BubbleLook(
    /** What DataStore holds. Never renamed: a stored key that no longer parses falls to [DEFAULT]. */
    val storageKey: String,
    /** What the settings list shows. */
    val title: String,
    /** One line under the title, plain English. */
    val description: String,
    /** The ground shared by the bubble and both pills, ARGB; fully transparent for Bare. */
    val surfaceFill: Int,
    /** The ground's shadow depth in dp; 0 draws no shadow at all. */
    val surfaceElevationDp: Int,
    /** The lips' side inside the 56 dp bubble, in dp. */
    val lipsDp: Int,
    /** A dark edge around each lip bar and each rail bar, in dp; 0 draws none. */
    val inkEdgeDp: Float,
    /** The cancel control's ground, ARGB. See-through in every look (founder 2026-09-14). */
    val cancelFill: Int = 0x992A2733.toInt(),
    /** The accept control's ground, ARGB: the accent, see-through, the one coloured control. */
    val acceptFill: Int = 0xB37C3AED.toInt(),
) {
    BARE(
        storageKey = "bare",
        title = "Bare",
        description = "Just the colour, with less around it.",
        surfaceFill = 0x00000000,
        surfaceElevationDp = 0,
        lipsDp = 42,
        inkEdgeDp = 0.65f,
    ),
    CLEAR(
        storageKey = "clear",
        title = "Clear",
        description = "A light touch that lets the page show through.",
        surfaceFill = 0x47FFFFFF,
        surfaceElevationDp = 3,
        lipsDp = 38,
        inkEdgeDp = 0.65f,
    ),
    SMOKE(
        storageKey = "smoke",
        title = "Smoke",
        description = "A soft dark background behind the colour.",
        surfaceFill = 0xA3131019.toInt(),
        surfaceElevationDp = 3,
        lipsDp = 38,
        inkEdgeDp = 0.65f,
    );

    companion object {
        /**
         * Smoke out of the box: the one look that reads on every ground, so the first thing a new
         * user sees is never a bubble lost against their page.
         */
        val DEFAULT = SMOKE

        /** The look a stored key names, or [DEFAULT] for null, blank, or a key no look carries. */
        fun fromStorage(key: String?): BubbleLook =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
