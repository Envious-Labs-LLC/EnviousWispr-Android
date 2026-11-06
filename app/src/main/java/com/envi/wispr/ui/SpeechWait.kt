package com.envi.wispr.ui

import com.envi.wispr.asr.AsrBounds

/** One original deadline, at most one reconnect retry, and one exact active/answered-tail submission. */
internal class SpeechWait(private val host: SessionHost) {
    enum class Loss { WAITING, RETRYING, EXHAUSTED, FINISHED }
    class Attempt internal constructor(internal val peer: SpeechLink)
    private class Slot(val attempt: Attempt, var submission: SpeechSubmission? = null)
    private sealed interface State {
        data object New : State
        data class Open(val slot: Slot?) : State
        data class Answered(val tail: SpeechSubmission?) : State
        data object Closed : State
    }
    private sealed interface Retry {
        data object First : Retry
        data class Used(val failedPeer: SpeechLink) : Retry
    }
    private val lock = Any()
    private var state: State = State.New
    private var retry: Retry = Retry.First
    private var onExpired: (() -> Unit)? = null
    private val expiry = Runnable {
        val expired = synchronized(lock) {
            val open = state as? State.Open ?: return@Runnable
            state = State.Closed
            val action = onExpired
            onExpired = null
            open.slot?.submission to action
        }
        expired.first?.cancel()
        expired.second?.invoke()
    }

    fun arm(audioMs: Long, onExpired: () -> Unit): Boolean = synchronized(lock) {
        if (state != State.New) return false
        state = State.Open(null)
        this.onExpired = onExpired
        host.postToMainDelayed(AsrBounds.requestBoundMs(audioMs), expiry)
        true
    }

    fun claimAttempt(readPeer: () -> SpeechLink?): Attempt? = synchronized(lock) {
        val open = state as? State.Open ?: return null
        val peer = readPeer() ?: return null
        if (open.slot != null || (retry as? Retry.Used)?.failedPeer === peer) return null
        Attempt(peer).also { state = State.Open(Slot(it)) }
    }

    /** Preparation issues no RPC. Attach before submit; a late/replacement handle is cancelled outside the lock. */
    fun attach(token: Attempt, submission: SpeechSubmission): Boolean {
        val accepted = synchronized(lock) {
            val slot = (state as? State.Open)?.slot
            if (slot?.attempt !== token) false else {
                val previous = slot.submission
                if (previous == null || previous === submission) { slot.submission = submission; true } else false
            }
        }
        if (!accepted) submission.cancel()
        return accepted
    }

    fun disconnected(token: Attempt? = null): Loss {
        var abandoned: SpeechSubmission? = null
        val loss = synchronized(lock) {
            when (val current = state) {
                State.New -> if (token == null) Loss.WAITING else Loss.FINISHED
                is State.Answered, State.Closed -> Loss.FINISHED
                is State.Open -> {
                    val slot = current.slot
                    if (token != null && slot?.attempt !== token) return@synchronized Loss.FINISHED
                    if (slot == null) return@synchronized Loss.WAITING
                    abandoned = slot.submission
                    when (retry) {
                        Retry.First -> {
                            retry = Retry.Used(slot.attempt.peer)
                            state = State.Open(null)
                            Loss.RETRYING
                        }
                        is Retry.Used -> {
                            state = State.Closed
                            onExpired = null
                            host.cancelMainDelayed(expiry)
                            Loss.EXHAUSTED
                        }
                    }
                }
            }
        }
        abandoned?.cancel()
        return loss
    }

    /** Every accepted callback/ordinary throw transfers the SAME handle into one answered-tail slot. */
    fun answer(token: Attempt): Boolean = synchronized(lock) {
        val slot = (state as? State.Open)?.slot ?: return false
        if (slot.attempt !== token) return false
        state = State.Answered(slot.submission)
        onExpired = null
        host.cancelMainDelayed(expiry)
        true
    }

    /** Closing an answered wait still cancels its tail, while false preserves existing PCM deletion ownership. */
    fun close(): Boolean {
        val closed = synchronized(lock) {
            val previous = state
            val submission = when (previous) {
                State.New, State.Closed -> null
                is State.Open -> previous.slot?.submission
                is State.Answered -> previous.tail
            }
            val wasOpen = previous is State.Open
            state = State.Closed
            onExpired = null
            if (wasOpen) host.cancelMainDelayed(expiry)
            wasOpen to submission
        }
        closed.second?.cancel()
        return closed.first
    }
}
