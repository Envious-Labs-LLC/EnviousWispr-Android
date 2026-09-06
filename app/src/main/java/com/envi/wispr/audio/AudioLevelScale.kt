package com.envi.wispr.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * Turns the capture service's raw amplitude into the 0..1 level the floating recorder draws.
 *
 * `AudioCaptureService` publishes the mean absolute sample divided by [Short.MAX_VALUE]. That number is
 * LINEAR, and linear is unusable as a meter: ordinary speech sits near 0.05, so a bar drawn straight from
 * it never leaves the floor and the user concludes the microphone is dead. Hearing is logarithmic, so the
 * scale here is too.
 *
 * The window is stated in decibels relative to full scale, and both ends are chosen against real material
 * rather than against the arithmetic range. [QUIET_DBFS] sits below a quiet room, so room tone reads as
 * nothing. [LOUD_DBFS] sits at the loud end of held speech, so the meter fills on a raised voice rather
 * than only on a clipped one.
 *
 * These two numbers are a DISPLAY choice and nothing downstream reads them. Transcription, the silence
 * detector and the stored audio are all untouched by anything in this file.
 */
object AudioLevelScale {

    /** At or below this the meter is empty. A quiet room measures under it. */
    const val QUIET_DBFS = -55f

    /** At or above this the meter is full. Held speech close to the microphone reaches it. */
    const val LOUD_DBFS = -10f

    /** How far the meter travels toward a HIGHER level on one tick. */
    const val ATTACK = 0.65f

    /** How far the meter travels toward a LOWER level on one tick. */
    const val RELEASE = 0.22f

    /**
     * [QUIET_DBFS] expressed as a linear amplitude.
     *
     * Readings at or below it never reach [log10], which answers negative infinity at zero.
     */
    private val QUIET_AMPLITUDE = 10f.pow(QUIET_DBFS / 20f)

    /**
     * Map one amplitude reading to the fraction of the meter that should be lit.
     *
     * A reading that is not finite, or is negative, is a broken one rather than a quiet one, and both
     * floor to zero. A meter that jumps to full when capture misbehaves is worse than one that stops.
     */
    fun display(amplitude: Float): Float {
        if (!amplitude.isFinite() || amplitude <= QUIET_AMPLITUDE) return 0f
        val dbfs = 20f * log10(amplitude.coerceAtMost(1f))
        return ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0f, 1f)
    }

    /**
     * Move the drawn level one tick toward [target].
     *
     * Rising is faster than falling on purpose. A meter that tracks the signal exactly flickers in every
     * gap between words and reads as a fault; one that falls slowly reads as a voice.
     */
    fun smooth(previous: Float, target: Float): Float {
        val from = if (previous.isFinite()) previous.coerceIn(0f, 1f) else 0f
        val to = if (target.isFinite()) target.coerceIn(0f, 1f) else 0f
        val rate = if (to > from) ATTACK else RELEASE
        return (from + (to - from) * rate).coerceIn(0f, 1f)
    }
}
