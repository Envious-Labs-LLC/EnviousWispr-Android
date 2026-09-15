package com.envi.wispr.polish

/**
 * S1-mini's three control-line axes (#152, the Mac's #2649).
 *
 * S1-mini is one model in one file. Its published input format puts a single control line at the top
 * of every request, and that line is the model's whole API surface:
 * `[Styling: …] [Structure: …] [Context: …]`. Every combination of the values below was trained; a
 * value outside them is off-distribution and the model card warns it makes the model hallucinate or
 * garble its output. So each axis is a closed enum whose [token] IS the wire string, and nothing else
 * can reach the line.
 *
 * Founder decision 2026-09-04 (catalog `decision` table): all three are user settings, each defaulting
 * to the value the app shipped with, so anyone who never opens the picker sees no change. Automatic
 * per-destination selection is a later upgrade over the pickers, not a replacement for them.
 *
 * The picks travel on [PolishPolicy.LocalS1], latched once per session by the session owner, so a
 * change on the screen applies from the NEXT recording (issue #69 owns why the engine reads no
 * preference itself).
 */
enum class S1Styling(val token: String) {
    CASUAL("casual"),
    SEMI_CASUAL("semi-casual"),

    /**
     * The card's own default and the one its published examples use. `casual` and `semi-casual`
     * deliberately keep sentence starts lowercase and drop the final period, which reads as a formatting
     * bug in dictated text destined for other people's documents; that is why the app ships on this one
     * rather than on the loosest register.
     */
    SEMI_FORMAL("semi-formal"),
    FORMAL("formal");

    companion object {
        fun fromToken(token: String?): S1Styling = entries.firstOrNull { it.token == token } ?: S1ControlSettings.DEFAULT.styling
    }
}

/**
 * Told `lists` the model emits one for genuinely enumerable content (a clear run of at least three
 * items) and leaves everything else as prose; told `prose` it scores zero on list-demanding input.
 * This is a SETTING the model obeys, not a judgement it makes, so the choice is the user's and it is
 * never inferred per transcript. The Mac measured it: 104/114 list cases format under `lists`, and
 * 23/1,348 non-list cases over-trigger.
 */
enum class S1Structure(val token: String) {
    PROSE("prose"),
    LISTS("lists");

    companion object {
        fun fromToken(token: String?): S1Structure = entries.firstOrNull { it.token == token } ?: S1ControlSettings.DEFAULT.structure
    }
}

/**
 * Destination conventions. `email` is a PERMISSION, not a forcing instruction: measured on the shipped
 * weights, a note-to-self and a code comment were byte-identical under both values, and only text that
 * already carried a greeting or a sign-off gained email layout. `general` is the neutral value.
 */
enum class S1Context(val token: String) {
    GENERAL("general"),
    EMAIL("email");

    companion object {
        fun fromToken(token: String?): S1Context = entries.firstOrNull { it.token == token } ?: S1ControlSettings.DEFAULT.context
    }
}

/** The three axes together, and the one place the control line is composed. */
data class S1ControlSettings(
    val styling: S1Styling,
    val structure: S1Structure,
    val context: S1Context,
) {
    /** The first line of the user message, exactly as the card specifies it. */
    fun controlLine(): String =
        "[Styling: ${styling.token}] [Structure: ${structure.token}] [Context: ${context.token}]"

    companion object {
        /**
         * What every install runs until the user changes something. These are the exact values that
         * shipped as a constant before the pickers existed, which is what makes the pickers a no-op for
         * anyone who ignores them.
         */
        val DEFAULT = S1ControlSettings(S1Styling.SEMI_FORMAL, S1Structure.LISTS, S1Context.GENERAL)
    }
}
