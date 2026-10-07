package com.envi.wispr.processing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Cancellation and publication compete on the same state; cancelled work retains its id until release. */
internal class ProcessingCheckRegistry {
    class Entry internal constructor(val operationId: Long) {
        private enum class State { OPEN, CANCELLED, DELIVERED }
        private val state = AtomicReference(State.OPEN)
        val isCancelled: Boolean get() = state.get() == State.CANCELLED
        internal fun cancel() { state.compareAndSet(State.OPEN, State.CANCELLED) }
        fun deliverOnce(block: () -> Unit): Boolean {
            if (!state.compareAndSet(State.OPEN, State.DELIVERED)) return false
            runCatching(block)
            return true
        }
    }
    private val entries = ConcurrentHashMap<Long, Entry>()
    fun register(id: Long): Entry? = Entry(id).let { if (entries.putIfAbsent(id, it) == null) it else null }
    fun cancel(id: Long) { entries[id]?.cancel() }
    fun cancelAll() { entries.values.forEach { it.cancel() } }
    fun release(entry: Entry) { entries.remove(entry.operationId, entry) }
}
