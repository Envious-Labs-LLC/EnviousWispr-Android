package com.envi.wispr.ui

import com.envi.wispr.asr.AsrBounds

/**
 * One take's speech lifetime: one deadline, at most two admitted requests after a helper loss.
 * An opaque attempt owns its callbacks; a disconnected attempt cannot answer or close its replacement.
 * Cancellation before arm is permanent. Reconnection never extends the original deadline.
 */
internal class SpeechWait(private val host: SessionHost) {
    private enum class State { NEW, OPEN, DONE }
    enum class Loss { WAITING, RETRYING, EXHAUSTED, FINISHED }
    class Attempt internal constructor(internal val peer: SpeechLink)

    private val lock = Any()
    private var state = State.NEW
    private var attempt: Attempt? = null
    private var failedPeer: SpeechLink? = null
    private var retried = false
    private var onExpired: (() -> Unit)? = null
    private val expiry = Runnable {
        val action = synchronized(lock) {
            if (state != State.OPEN) return@Runnable
            state = State.DONE
            attempt = null
            onExpired
        }
        action?.invoke()
    }

    fun arm(audioMs: Long, onExpired: () -> Unit): Boolean = synchronized(lock) {
        if (state != State.NEW) return false
        state = State.OPEN
        this.onExpired = onExpired
        host.postToMainDelayed(AsrBounds.requestBoundMs(audioMs), expiry)
        true
    }

    /** The worker admits a request against the current proxy, never the proxy that already failed. */
    fun claimAttempt(readPeer: () -> SpeechLink?): Attempt? = synchronized(lock) {
        val peer = readPeer() ?: return null
        if (state != State.OPEN || attempt != null || peer === failedPeer) return null
        Attempt(peer).also { attempt = it }
    }

    /** Main-thread connection loss, or a token-scoped dead-Binder throw posted there by its worker. */
    fun disconnected(token: Attempt? = null): Loss = synchronized(lock) {
        if (token != null && attempt !== token) return Loss.FINISHED
        when (state) {
            State.NEW -> Loss.WAITING
            State.DONE -> Loss.FINISHED
            State.OPEN -> {
                val lost = attempt ?: return Loss.WAITING
                failedPeer = lost.peer
                attempt = null
                if (retried) {
                    finish()
                    Loss.EXHAUSTED
                } else {
                    retried = true
                    Loss.RETRYING
                }
            }
        }
    }

    /** Only this admitted attempt may accept an answer or an ordinary request exception. */
    fun answer(token: Attempt): Boolean = synchronized(lock) {
        if (state != State.OPEN || attempt !== token) return false
        finish()
    }

    /** Any terminal ending. Closing NEW permanently refuses an arm racing cancellation. */
    fun close(): Boolean = synchronized(lock) { finish() }

    private fun finish(): Boolean {
        val wasOpen = state == State.OPEN
        state = State.DONE
        attempt = null
        if (wasOpen) host.cancelMainDelayed(expiry)
        return wasOpen
    }
}
