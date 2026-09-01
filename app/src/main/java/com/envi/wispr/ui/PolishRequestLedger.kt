package com.envi.wispr.ui

import com.envi.wispr.polish.PolishRequestIdSource
import java.util.concurrent.atomic.AtomicLong

/**
 * Which polish request, if any, the session owner is still waiting for. An outcome is accepted
 * only when it names the open request, and only once; closing the ledger returns the id that was
 * open so the caller can cancel exactly that request on the engine. Both operations are single
 * compare-and-swaps, so cancel versus outcome is first-wins with no window between reading the id
 * and closing it. Pure Kotlin: `PolishRequestLedgerTest` drives it with an injected id source.
 *
 * An `AtomicLong` with a sentinel, not an `AtomicReference<Long?>`: the reference form compares
 * boxed identity, so any id above the small-value cache failed its own compare-and-swap. The test
 * caught it on the first run. Zero is never minted (`PolishRequestIdSource` starts at one).
 */
class PolishRequestLedger(private val ids: PolishRequestIdSource = PolishRequestIdSource.shared) {
    private val open = AtomicLong(NONE)

    /** Mints a fresh id and makes it the open request, replacing any previous one. */
    fun open(): Long {
        val id = ids.next()
        open.set(id)
        return id
    }

    /** True exactly once, for the open request's id; anything else is stale and is refused. */
    fun accepts(requestId: Long): Boolean = requestId != NONE && open.compareAndSet(requestId, NONE)

    /** Closes the ledger and returns the id that was open, or null when nothing was. */
    fun close(): Long? = open.getAndSet(NONE).takeIf { it != NONE }

    val openId: Long? get() = open.get().takeIf { it != NONE }

    private companion object {
        const val NONE = 0L
    }
}
