package com.envi.wispr.ui

/**
 * Which surface asked for a dictation (issue #176; the Mac's `TriggerSource`, RULE:
 * keep-trigger-source-separate). Carried on the START/TOGGLE intent as
 * `DictationSessionService.EXTRA_TRIGGER_SOURCE`, stamped on the take at admission, reported on the
 * terminal row. It is the entry surface, never the recording mode.
 *
 * The side button reaches the app as `ACTION_ASSIST`, so it is `ASSIST`, not "side button": the app
 * cannot tell the button from any other assistant mapping. The notification has no START. A practice
 * take is an ordinary bubble take aimed at our own field, visible as `target_app = com.envi.wispr`, and
 * is not a surface.
 *
 * A missing or unknown extra reads [UNKNOWN] and is kept as such: never defaulted to a real surface
 * (`validation-discipline.md`, plausible-value traps).
 */
enum class TriggerSource(val wire: String) {
    BUBBLE_TAP("bubble_tap"),
    BUBBLE_HOLD("bubble_hold"),
    ASSIST("assist"),
    TILE("tile"),
    APP("app"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromExtra(value: String?): TriggerSource = entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}
