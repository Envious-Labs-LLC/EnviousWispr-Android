package com.envi.wispr.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one speech request of a take, bounded (#356). A `:asr` process that dies is seen by its connection; one that
 * stays alive and never answers (a native decode that never returns) was seen by nothing, and the take stayed in
 * PROCESSING until the user cancelled. Armed BEFORE the request is sent, so an answer can never arrive ahead of it;
 * on the owner's one main-thread timer (#115; RULE: no-idle-cost), armed only while the request is open.
 *
 * Exactly one of these wins, once: [answer] (the first speech callback), [close] (every other ending of the wait),
 * or the expiry, which runs [onExpired] on main. A callback that loses is late and must touch nothing.
 */
internal class SpeechWait(
    private val host: SessionHost,
    private val onExpired: () -> Unit,
) {
    private val open = AtomicBoolean(false)
    private val expiry = Runnable { if (open.compareAndSet(true, false)) onExpired() }

    /** Arms the bound for a recording of [audioMs]. Called once, before the request. */
    fun arm(audioMs: Long) {
        if (open.compareAndSet(false, true)) host.postToMainDelayed(boundMs(audioMs), expiry)
    }

    /** The first speech callback: true exactly once while open, and the bound is gone. False means late. */
    fun answer(): Boolean = close()

    /** Any other ending of the wait (disconnect, cancel, destroy, a thrown request): true if it was still open. */
    fun close(): Boolean {
        if (!open.compareAndSet(true, false)) return false
        host.cancelMainDelayed(expiry)
        return true
    }

    internal companion object {
        /** Covers a cold model load queued ahead of the decode, and a short take on a slow phone. */
        const val BASE_MS = 20_000L

        /**
         * Per second of audio. The S26 decodes at a real-time factor of 0.05 to 0.12 (session log), so a healthy
         * decode uses at most a quarter of this; a ten-minute take is bounded at 320 s.
         */
        const val PER_AUDIO_SECOND_MS = 500L

        fun boundMs(audioMs: Long): Long = BASE_MS + audioMs.coerceAtLeast(0L) * PER_AUDIO_SECOND_MS / 1_000L
    }
}
