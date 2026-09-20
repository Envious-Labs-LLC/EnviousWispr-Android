package com.envi.wispr.ui

/**
 * The one lifecycle arbiter of a take: exactly one [TerminalReason] is committed per take, whatever
 * order the callbacks, cancels, watchdogs and the owner's own destruction arrive in (issue #176, plan
 * §3.2 refactor 1; grounded review G2 D2).
 *
 * Three states. OPEN: nothing has claimed the ending. RESERVED: an owner holds a [Token] and is working
 * towards an outcome it does not know yet (a publication awaiting its History save, a cancel awaiting
 * the safe close). COMMITTED: the ending is decided and every later claim loses. Reserving is not
 * committing: `completed` is unknown until the save returns, and `cancelled` is unknown until the
 * capture file closed safely, so those owners reserve first and commit with the outcome they observed.
 *
 * Destruction is the one claimant that may REVOKE a reservation: [interrupt] commits `interrupted` over
 * an OPEN or RESERVED take, and the displaced owner's token stops working, so it can no longer commit,
 * announce or start an insertion. A worker that suspended while holding a token asks [holds] before it
 * acts again.
 *
 * Pure and process-local: no database, binder, SDK or blocking work runs under its lock, and it never
 * throws. [onCommit] is called exactly once, outside the lock, with the committed reason; the sink is
 * a limb (a no-op in chunk A2, the telemetry facade from chunk C).
 */
class TakeArbiter private constructor(
    private val onCommit: (TerminalReason) -> Unit,
    /** True for [closed]: no take was ever admitted, so every claim loses and nothing is reported. */
    private val closedByConstruction: Boolean,
) {
    constructor(onCommit: (TerminalReason) -> Unit) : this(onCommit, closedByConstruction = false)

    companion object {
        /** The owner's arbiter before any take is admitted: refuses every claim, reports nothing. */
        fun closed(): TakeArbiter = TakeArbiter({}, closedByConstruction = true)
    }

    /** An opaque reservation. Only the holder of the live token can commit. */
    class Token internal constructor(internal val claimant: String)

    private val lock = Any()
    private var reservation: Token? = null
    private var committedReason: TerminalReason? = null

    /** Committed, or never open at all: the only two ways a claim can lose without a rival. */
    private fun decided(): Boolean = closedByConstruction || committedReason != null

    /** The committed ending, or null while the take is still open or reserved. */
    val committed: TerminalReason?
        get() = synchronized(lock) { committedReason }

    /** True until something commits: a claim can still succeed. Reserved counts as open here. */
    val isOpen: Boolean
        get() = synchronized(lock) { !decided() }

    /**
     * Reserve the ending for [claimant] without deciding it. Null when another owner already holds the
     * reservation or the take is committed: the caller then does no History, notification, insertion
     * or terminal work.
     */
    fun reserve(claimant: String): Token? = synchronized(lock) {
        if (decided() || reservation != null) return null
        Token(claimant).also { reservation = it }
    }

    /** Whether [token] is still the live reservation: false once committed, revoked or superseded. */
    fun holds(token: Token): Boolean = synchronized(lock) { !decided() && reservation === token }

    /**
     * Commit [reason] under [token]. False when the token is no longer live (destruction revoked it, or
     * something committed first); the caller must then stop.
     */
    fun commit(token: Token, reason: TerminalReason): Boolean {
        val won = synchronized(lock) {
            if (decided() || reservation !== token) return false
            committedReason = reason
            reservation = null
            true
        }
        if (won) onCommit(reason)
        return won
    }

    /**
     * Reserve and commit in one step, for an ending whose outcome is known at the call site (every
     * failure the owner observes directly). False when reserved or committed by someone else.
     */
    fun commitNow(reason: TerminalReason): Boolean {
        val won = synchronized(lock) {
            if (decided() || reservation != null) return false
            committedReason = reason
            true
        }
        if (won) onCommit(reason)
        return won
    }

    /**
     * Destruction: commit [reason] (an `INTERRUPTED_*` member) unless a terminal was already committed,
     * revoking any reservation. Returns true when this call decided the ending.
     */
    fun interrupt(reason: TerminalReason): Boolean {
        val won = synchronized(lock) {
            if (decided()) return false
            committedReason = reason
            reservation = null
            true
        }
        if (won) onCommit(reason)
        return won
    }
}
