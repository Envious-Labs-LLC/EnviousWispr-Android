package com.envi.wispr.ui

/**
 * The cues the session owner fires, and whether each one is the user's to switch off.
 *
 * The gate belongs to the CUE, not to the vibrate call, because the two kinds answer to different
 * settings. `Settings.System.HAPTIC_FEEDBACK_ENABLED` governs touch and long-press feedback,
 * so honouring it for a RESULT cue is parity with `AccessibilityInsertionRunner.performResultHaptic`.
 * A session cue is not feedback on a touch: on the side-button path there is no window, the
 * user's eyes are on another app's text field, and the buzz is the only signal that recording
 * started or stopped. Gating those on the touch-feedback switch silences the whole product for
 * a user who turned off keyboard clicks.
 *
 * Top-level since #186 so the coordinator names the cue and the Service fires it.
 */
internal enum class HapticCue(
    val durationMs: Long,
    val amplitude: Int,
    val honoursSystemHapticSetting: Boolean,
) {
    /** Recording started, or stopped for transcription. The only cue on a windowless path. */
    SESSION_TRANSITION(28L, 120, honoursSystemHapticSetting = false),

    /** The user cancelled. Also a windowless acknowledgement, with the heavier waveform. */
    SESSION_CANCELED(45L, 180, honoursSystemHapticSetting = false),

    /** A result cue: the dictation did not land. Parity with performResultHaptic. */
    FAILURE(45L, 180, honoursSystemHapticSetting = true),
}
