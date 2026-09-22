package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * The one `AudioRecord` this process may hold, as a lease that outlives any Service instance (#115), and
 * that knows WHICH capture holds it and whether that capture is still recording (#213).
 *
 * `AudioCaptureService.onDestroy` does not join the capture thread, and that thread is the sole owner of the
 * recorder: a thread that never returns from its read keeps the recorder, and a recorder whose release
 * threw may still be held natively. Either way the lease stays held, and before #213 every later start in
 * the process was refused until Android ended it. Now a production start that finds the lease held by a
 * capture that was already told to end, or whose release failed, [condemnAbandoned]s it and ends the
 * process; a capture still RECORDING is never condemned.
 *
 * Every transition is one compare-and-set on the exact [Holder] instance read (reference equality, so the
 * data class's value equality plays no part): a release and a condemnation of the same holder cannot both
 * win, and a newer capture's holder can never be changed by an older capture's token.
 */
internal class RecorderLease {
    /** Null is FREE; a holder in [State.CONDEMNED] is terminal for the process. */
    private val holder = AtomicReference<Holder?>(null)

    data class Holder(val token: Long, val state: State)

    enum class State { RECORDING, ENDING, RELEASE_FAILED, CONDEMNED }

    /** True when [token] now holds the lease; false while any capture, or a condemned one, holds it. */
    fun acquire(token: Long): Boolean = holder.compareAndSet(null, Holder(token, State.RECORDING))

    /** The capture [token] was told to end; from here a later production start may condemn it. */
    fun markEnding(token: Long) {
        while (true) {
            val current = holder.get() ?: return
            if (current.token != token || current.state != State.RECORDING) return
            if (holder.compareAndSet(current, Holder(token, State.ENDING))) return
        }
    }

    /** The recorder's release threw: the lease stays held, and a later production start may condemn it. */
    fun releaseFailed(token: Long) {
        while (true) {
            val current = holder.get() ?: return
            if (current.token != token || current.state == State.CONDEMNED) return
            if (holder.compareAndSet(current, Holder(token, State.RELEASE_FAILED))) return
        }
    }

    /** Released by the capture that holds it; false when [token] does not hold it or it was condemned first. */
    fun release(token: Long): Boolean {
        while (true) {
            val current = holder.get() ?: return false
            if (current.token != token || current.state == State.CONDEMNED) return false
            if (holder.compareAndSet(current, null)) return true
        }
    }

    /**
     * A production start's decision: true exactly when the holder was NOT recording (told to end, or its
     * release failed) and this call condemned it, so nobody can ever acquire again in this process.
     */
    fun condemnAbandoned(): Boolean {
        while (true) {
            val current = holder.get() ?: return false
            if (current.state == State.RECORDING || current.state == State.CONDEMNED) return false
            if (holder.compareAndSet(current, Holder(current.token, State.CONDEMNED))) return true
        }
    }

    val isHeld: Boolean get() = holder.get() != null

    /** The current holder, for tests and diagnostics; never used to decide anything. */
    val snapshot: Holder? get() = holder.get()

    companion object {
        /** The process's lease. */
        val PROCESS = RecorderLease()
    }
}
