package com.envi.wispr.ui

import com.envi.wispr.asr.AsrBounds
/**
 * The take's one speech request, bounded (#356). A `:asr` process that dies is seen by its connection; one that
 * stays alive and never answers (a native decode that never returns) was seen by nothing, and the take stayed in
 * PROCESSING until the user cancelled. Armed BEFORE the request is sent, so an answer can never arrive ahead of it;
 * on the owner's one main-thread timer (#115; RULE: no-idle-cost), posted only while the request is open.
 *
 * One per owner, which admits one take. It moves once from NEW to OPEN ([arm]) and once to DONE, by exactly one of
 * [answer] (the first speech callback), [close] (every other ending) or the expiry, which runs the armed action on
 * main. A [close] before [arm] records the ending, so an arm racing a cancel is refused (#356 review round 1). Every
 * move, the post and its removal included, is under one lock, so no post outlives a close.
 */
internal class SpeechWait(private val host: SessionHost) {
    private enum class State { NEW, OPEN, DONE }

    private val lock = Any()
    private var state = State.NEW
    private var onExpired: (() -> Unit)? = null
    private val expiry = Runnable {
        val action = synchronized(lock) {
            if (state != State.OPEN) return@Runnable
            state = State.DONE
            onExpired
        }
        action?.invoke()
    }

    /** Opens the wait for a recording of [audioMs]; false when the take already ended, and nothing is posted. */
    fun arm(audioMs: Long, onExpired: () -> Unit): Boolean = synchronized(lock) {
        if (state != State.NEW) return false
        state = State.OPEN
        this.onExpired = onExpired
        host.postToMainDelayed(AsrBounds.requestBoundMs(audioMs), expiry)
        true
    }

    /** The first speech callback: true exactly once while open, and the bound is gone. False means late. */
    fun answer(): Boolean = finish()

    /** Any other ending (disconnect, cancel, destroy, a thrown request): true if the request was still open. */
    fun close(): Boolean = finish()

    private fun finish(): Boolean = synchronized(lock) {
        val wasOpen = state == State.OPEN
        state = State.DONE
        if (wasOpen) host.cancelMainDelayed(expiry)
        wasOpen
    }
}
