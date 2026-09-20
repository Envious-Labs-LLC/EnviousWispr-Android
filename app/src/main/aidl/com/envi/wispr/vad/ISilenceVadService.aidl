package com.envi.wispr.vad;

/**
 * The silence detector, in its own process.
 *
 * SYNCHRONOUS ON PURPOSE. There is no callback interface, because Android's binding API produces at
 * least four states in which a message either never arrives or arrives after the take it belongs to has
 * ended, and guarding each of those is a description of a population rather than an enumeration of it.
 * The caller blocks on a thread that is allowed to block; the capture thread never calls this.
 *
 * Every method carries the capture token, so a result that arrives late because a call stalled can be
 * recognised as belonging to a finished take and discarded.
 *
 * This interface never returns audio and never receives a file path or handle. It gets copied blocks of
 * PCM and returns an integer. That is what makes a wrong answer unable to touch what was recorded.
 */
interface ISilenceVadService {

    /** Prepare for one take. Returns STATUS_READY or STATUS_UNAVAILABLE. */
    int start(long captureToken, float pauseSeconds);

    /**
     * Feed one 256 ms block of 16 kHz mono PCM16, 8192 bytes.
     * Returns RESULT_CONTINUE, RESULT_SILENCE, or RESULT_UNAVAILABLE.
     */
    int processBlock(long captureToken, in byte[] pcm16);

    /** Release state for the take, if the token still matches the active one. */
    void finish(long captureToken);

    // APPENDED (issue #176). Never reorder or rename anything above this line.

    /**
     * start plus the take's id: same return codes, same token ordering and stale-call rejection, same
     * pause configuration and deadline. The id is bound only after the token is accepted and is request
     * context for this take; processBlock and finish keep using the token. The legacy start keeps its
     * behaviour and carries no take context.
     */
    int startForTake(long captureToken, float pauseSeconds, String takeId);
}
