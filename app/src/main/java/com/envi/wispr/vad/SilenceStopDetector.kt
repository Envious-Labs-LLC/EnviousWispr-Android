package com.envi.wispr.vad

import kotlin.math.ceil

/**
 * Decides when a take has ended, from a stream of speech probabilities.
 *
 * Pure logic: no Android, no audio, no model, no thread. That is what makes it testable on the JVM, and
 * it is why the decision is separable from the detector's runtime.
 *
 * The algorithm is macOS's `SilenceDetector.advanceStateMachine`, ported unchanged, advancing once per
 * 256 ms block so macOS's constants carry over with no frame-rate arithmetic. An earlier design ran this
 * per 32 ms frame and derived Android equivalents for alpha, the confirmation count and the hangover
 * floor; three of those conversions were wrong, so the conversion layer was deleted rather than fixed.
 *
 * **Auto-stop reads RAW PROBABILITY, never the detector's own speech-segment boundaries.** macOS states
 * that as a contract: the two signals have different timing and different thresholds, and conflating them
 * is a deliberate decision rather than a refactor.
 */
internal class SilenceStopDetector(pauseSeconds: Float) {

    internal enum class Phase { IDLE, SPEECH, HANGOVER }

    private val hangoverBlocks = hangoverBlocks(pauseSeconds)

    private var phase = Phase.IDLE
    private var smoothed = 0f
    private var consecutiveAboveOnset = 0
    private var hangoverRemaining = 0

    /** True once speech has been confirmed at least once in this take. */
    var speechDetected: Boolean = false
        private set

    /** Exposed for tests and diagnostics. Never read to make a decision. */
    internal val currentPhase: Phase get() = phase

    /**
     * Feed one 256 ms block's probability. Returns true exactly once, on the block that ends the take.
     *
     * The first below-offset block ENTERS hangover without decrementing it, so a countdown of N is
     * consumed by the N blocks that follow. That off-by-one is macOS's real behaviour and is reproduced
     * rather than corrected: at the default 1.5 s setting the nominal wait is 1.792 s, which is why the
     * slider presents its value as approximate.
     */
    fun onBlock(rawProbability: Float): Boolean {
        val raw = if (rawProbability.isFinite()) rawProbability.coerceIn(0f, 1f) else 0f
        smoothed = EMA_ALPHA * raw + (1f - EMA_ALPHA) * smoothed

        when (phase) {
            Phase.IDLE -> {
                if (smoothed >= ONSET) {
                    consecutiveAboveOnset++
                    if (consecutiveAboveOnset >= ONSET_CONFIRMATION_BLOCKS) {
                        phase = Phase.SPEECH
                        speechDetected = true
                    }
                } else {
                    consecutiveAboveOnset = 0
                }
            }

            Phase.SPEECH -> {
                if (smoothed < OFFSET) {
                    phase = Phase.HANGOVER
                    hangoverRemaining = hangoverBlocks
                }
            }

            Phase.HANGOVER -> {
                if (smoothed >= ONSET) {
                    phase = Phase.SPEECH
                } else {
                    hangoverRemaining--
                    if (hangoverRemaining <= 0) {
                        phase = Phase.IDLE
                        consecutiveAboveOnset = 0
                        return true
                    }
                }
            }
        }
        return false
    }

    companion object {
        /** 4096 samples at 16 kHz. macOS's chunk, and this state machine's tick. */
        const val SAMPLES_PER_BLOCK = 4096
        const val BLOCK_SECONDS = 0.256f

        /**
         * Silero's window. The k2-fsa v4 export has no overlap, so its window size equals its shift and
         * eight straight windows fill one block with no rolling buffer to reproduce.
         */
        const val WINDOW_SAMPLES = 512
        const val WINDOWS_PER_BLOCK = SAMPLES_PER_BLOCK / WINDOW_SAMPLES

        /**
         * macOS `fromSensitivity(0.5)`, RESOLVED. These are NOT the `SmoothedVADConfig` struct defaults
         * of 0.3 / 0.5 / 0.35, which macOS never ships because its own path builds the config through
         * `fromSensitivity`. Sensitivity is fixed because macOS exposes no control for it either.
         */
        const val SENSITIVITY = 0.5f
        const val EMA_ALPHA = 0.4f
        const val ONSET = 0.4125f
        const val OFFSET = 0.2625f
        const val ONSET_CONFIRMATION_BLOCKS = 1
        const val MIN_HANGOVER_BLOCKS = 3

        const val MIN_PAUSE_SECONDS = 0.5f
        const val MAX_PAUSE_SECONDS = 3.0f
        const val DEFAULT_PAUSE_SECONDS = 1.5f

        /** A value outside the slider's range means a corrupt or foreign write, never a user choice. */
        fun sanitisePauseSeconds(value: Float): Float =
            if (value.isFinite() && value >= MIN_PAUSE_SECONDS && value <= MAX_PAUSE_SECONDS) value
            else DEFAULT_PAUSE_SECONDS

        fun hangoverBlocks(pauseSeconds: Float): Int =
            maxOf(
                MIN_HANGOVER_BLOCKS,
                ceil(sanitisePauseSeconds(pauseSeconds) / BLOCK_SECONDS).toInt(),
            )

        /**
         * The block's probability is its LAST window's.
         *
         * Silero is recurrent: by the eighth window its state already carries the preceding seven, so the
         * last value is the detector's state at the block boundary rather than an isolated sample of it.
         * A maximum was considered and rejected: it discards temporal order and lets one false-positive
         * 32 ms window arm speech for a whole block while the user is idle, which is the shape that ends
         * a take before anyone has spoken.
         */
        fun blockProbability(windowProbabilities: FloatArray): Float {
            require(windowProbabilities.isNotEmpty()) { "a block has at least one window" }
            return windowProbabilities.last()
        }
    }
}
