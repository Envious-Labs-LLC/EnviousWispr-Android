package com.envi.wispr.paste

/**
 * How long a take has been running, in the two forms the recorder needs.
 *
 * A separate object rather than two private methods on the overlay, because the overlay is a `View`
 * attached to a `WindowManager` and nothing about these strings needs one. Here they can be checked.
 */
internal object ElapsedLabels {

    /**
     * `0:12`, the way a recording length is written everywhere else.
     *
     * `12s` is fine for the first minute and wrong after it: at four minutes it reads `247s`, a number
     * the reader has to convert. The cap is ten minutes, so the width is stable at four characters and
     * the pill does not resize as the take runs.
     */
    fun clock(elapsedSeconds: Int): String {
        val safe = elapsedSeconds.coerceAtLeast(0)
        return "%d:%02d".format(safe / 60, safe % 60)
    }

    /**
     * The same length in words, for a screen reader.
     *
     * `0:12` is announced as "zero colon twelve", which is not what it says. This is the one place the
     * spoken form and the drawn form should differ.
     */
    fun spoken(elapsedSeconds: Int): String {
        val safe = elapsedSeconds.coerceAtLeast(0)
        val minutes = safe / 60
        val seconds = safe % 60
        val minutePart = when (minutes) {
            0 -> ""
            1 -> "1 minute "
            else -> "$minutes minutes "
        }
        val secondPart = if (seconds == 1) "1 second" else "$seconds seconds"
        return "$minutePart$secondPart elapsed"
    }
}
