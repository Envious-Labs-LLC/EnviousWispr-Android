package com.envi.wispr.asr

/**
 * The speech request's bounds, one owner for both processes. The session owner ends a take whose request outlives
 * [requestBoundMs] (#356, `ui/SpeechWait`); `:asr` ends ITSELF when one decode outlives that bound plus
 * [DECODE_GRACE_MS] (#357), so the next take gets a fresh process instead of queueing behind a native decode that
 * ignores interrupts.
 */
internal object AsrBounds {
    /** Covers a cold model load queued ahead of the decode (4.2 s on the emulator, 2026-09-24), and a short take on a slow phone. */
    const val BASE_MS = 20_000L

    /**
     * Per second of audio. The S26 decodes at a real-time factor of 0.05 to 0.12 (session log), so a healthy
     * decode uses at most a quarter of this; a ten-minute take is bounded at 320 s.
     */
    const val PER_AUDIO_SECOND_MS = 500L

    /**
     * `:asr`'s own margin past the owner's bound. The owner's bound counts from the request (the queue and the file
     * read too) and usually ends the take first with its own sentence; when the capture clock ran longer than the
     * file, or on the legacy entry points that have no owner bound, the watchdog can fire first, and the take ends
     * as a lost process instead. Both endings are bounded.
     */
    const val DECODE_GRACE_MS = 5_000L

    /** The model load, matching the polish engine's (#344): about fourteen times the emulator's cold load. */
    const val LOAD_BOUND_MS = 60_000L

    fun requestBoundMs(audioMs: Long): Long = BASE_MS + audioMs.coerceAtLeast(0L) * PER_AUDIO_SECOND_MS / 1_000L
}
