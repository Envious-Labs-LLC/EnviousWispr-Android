package com.envi.wispr.paste

/**
 * The brand colours the floating recorder draws with.
 *
 * The recorder is not Compose and cannot reach `ui/theme/EnviousWisprTheme.kt`, so the values it needs
 * are named here rather than left as unexplained integers inside a view. This is a SECOND copy of a few
 * brand values and that is a real cost; it is accepted because the alternative is a hex literal buried
 * in a drawing routine, which is the same copy with nothing saying where it came from.
 *
 * Source of truth, and the file to change first:
 * `~/Developer/EnviousLabs/EnviousWispr/website/src/styles/global.css`, the `:root` block. Measured
 * against it 2026-09-06.
 */
internal object BrandPalette {

    /** `--rainbow-full`, the nine stops of the signature gradient, red through violet. */
    val RAINBOW = intArrayOf(
        0xFFFF2A40.toInt(), // red
        0xFFFF8C00.toInt(), // orange
        0xFFFFD700.toInt(), // gold
        0xFFADFF2F.toInt(), // green-yellow
        0xFF00FA9A.toInt(), // spring green
        0xFF00FFFF.toInt(), // cyan
        0xFF1E90FF.toInt(), // dodger blue
        0xFF4169E1.toInt(), // royal blue
        0xFF8A2BE2.toInt(), // violet
    )

    /** `--v2-violet`. The brand violet, used for the recorder's outline and its glow. */
    const val VIOLET = 0xFF8A2BE2.toInt()

    /** `--accent`. The Envious purple, used for the one filled action. */
    const val ACCENT = 0xFF7C3AED.toInt()

    /** The recorder's own ground. Near-black and violet-shifted, matching the dark theme's surface. */
    const val PILL_BACKGROUND = 0xFF131019.toInt()

    /** The cancel control's ground: present, and quieter than the accept control. */
    const val NEUTRAL_CONTROL = 0xFF2A2733.toInt()

    /** Primary text on the pill. */
    const val TEXT = 0xFFECE9F4.toInt()

    /** Secondary text on the pill, for the state label. */
    const val TEXT_MUTED = 0xFF9A93AD.toInt()

    /** A resting meter bar, dim enough to read as "nothing heard" without reading as broken. */
    const val METER_RESTING = 0xFF3A3547.toInt()
}
