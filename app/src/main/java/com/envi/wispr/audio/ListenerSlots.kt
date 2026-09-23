package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicReference

/** Which binding set a listener slot (#220): the legacy interface, or one take binding's epoch. */
internal sealed interface SlotOrigin {
    data object Legacy : SlotOrigin
    data class Take(val epoch: Long) : SlotOrigin
}

/**
 * The capture service's listener slots and which binding set each one (#220).
 *
 * The owner binds the take interface with a fresh intent identifier per bind, so every take binding is its
 * own epoch; the device tests bind the legacy interface with no action. A slot is cleared only by the
 * binding that set it: the legacy unbind never drops the owner's listeners, and a take unbind never drops
 * the legacy client's. A registration from a take epoch that has already unbound is refused, because the
 * owner's command lane can issue a queued registration after its unbind, and the next take must not
 * inherit it.
 *
 * Registration, the origin that goes with it, the legacy unregister, every clear and every epoch change
 * run under ONE short lock and hold nothing slow: no listener callback, recording operation or hold
 * decision runs under it. The publishers read [Slot.listener] with a plain atomic `get()`, never the lock.
 */
internal class ListenerSlots {
    private val lock = Any()
    private val slots = ArrayList<Slot<*>>()
    private val openEpochs = HashSet<Long>()
    private val epochByIdentifier = HashMap<String, Long>()
    private var nextEpoch = 0L
    /** Set by [clearAll]: a destroyed service accepts no registration from any binding again. */
    private var closed = false

    /** A new slot; [key] is the listener's identity (its binder in production). */
    fun <T : Any> slot(key: (T) -> Any): Slot<T> = Slot(key).also { synchronized(lock) { slots.add(it) } }

    /** Main thread, from `onBind` for a take intent: a new epoch named by the intent's identifier. */
    fun openTakeEpoch(identifier: String?): Long = synchronized(lock) {
        val epoch = ++nextEpoch
        openEpochs.add(epoch)
        epochByIdentifier[identifier.orEmpty()] = epoch
        epoch
    }

    /** Main thread, from `onUnbind` for a take intent: its epoch refuses from now on, and what it set is cleared. */
    fun closeTakeEpoch(identifier: String?) = synchronized(lock) {
        val epoch = epochByIdentifier.remove(identifier.orEmpty()) ?: return@synchronized
        openEpochs.remove(epoch)
        val owner = SlotOrigin.Take(epoch)
        slots.forEach { it.clearOwnedByLocked(owner) }
    }

    /** Main thread, from `onUnbind` for the legacy intent: clears what the legacy binding set, only that. */
    fun unbindLegacy() = synchronized(lock) {
        slots.forEach { it.clearOwnedByLocked(SlotOrigin.Legacy) }
    }

    /**
     * `onDestroy`: every slot and its origin, together, and no registration after it. A binder call already
     * queued can still arrive after the destroy; it must not refill a slot the event publisher still drains.
     */
    fun clearAll() = synchronized(lock) {
        closed = true
        openEpochs.clear()
        epochByIdentifier.clear()
        slots.forEach { it.clearOwnedByLocked(null) }
    }

    inner class Slot<T : Any>(private val key: (T) -> Any) {
        /** What the publishers read, lock-free. Written only under the slots' lock. */
        val listener = AtomicReference<T?>(null)
        private var origin: SlotOrigin? = null

        /** A binder thread. Replaces the slot; false after destroy, or when [from] is a take epoch that has already unbound. */
        fun register(from: SlotOrigin, value: T?): Boolean = synchronized(lock) {
            if (closed) return@synchronized false
            if (from is SlotOrigin.Take && from.epoch !in openEpochs) return@synchronized false
            listener.set(value)
            origin = if (value == null) null else from
            true
        }

        /** The legacy unregister: only a legacy registration of the same listener identity is cleared. */
        fun unregisterLegacy(value: T?) = synchronized(lock) {
            val current = listener.get() ?: return@synchronized
            if (origin == SlotOrigin.Legacy && value != null && key(current) == key(value)) clearLocked()
        }

        /** A publisher whose push failed: clears the slot only if it still holds [observed], origin with it. */
        fun clearIfCurrent(observed: T): Boolean = synchronized(lock) {
            if (listener.get() !== observed) return@synchronized false
            clearLocked()
            true
        }

        internal fun clearOwnedByLocked(owner: SlotOrigin?) {
            if (owner == null || origin == owner) clearLocked()
        }

        private fun clearLocked() {
            listener.set(null)
            origin = null
        }
    }
}
