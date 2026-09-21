package com.envi.wispr.asr

/**
 * Why a transcription request failed, as a closed code that crosses the binder
 * (`IAsrCallback.onFailure`, issue #176). Before this, the speech process sent a sentence or an
 * exception message, and nothing could count the same failure twice.
 *
 * `code` is the wire integer and is PINNED: a member is never renumbered, and a new member takes the
 * next free code. `UNKNOWN` is what a client reads for a code its build does not know; it is kept, never
 * mapped to a real member (`validation-discipline.md`: an unrecognised input must not fall into a
 * plausible default).
 */
internal enum class AsrFailureReason(val code: Int) {
    /** A code this build does not recognise. Never emitted by the service. */
    UNKNOWN(0),

    /** The recogniser was not initialised when the request arrived: a readiness state, not a defect. */
    MODEL_NOT_LOADED(1),

    /** The audio file named by the request does not exist. */
    AUDIO_MISSING(2),

    /** The audio file exists and could not be read. */
    AUDIO_UNREADABLE(3),

    /** The file is over the recording ceiling the capture process already enforces: ours if it happens. */
    OVER_LIMIT(4),

    /** The decoder call itself threw. */
    DECODE_FAILED(5),
    ;

    companion object {
        fun fromCode(code: Int): AsrFailureReason = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}
