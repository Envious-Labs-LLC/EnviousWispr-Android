package com.envi.wispr.polish

import com.envi.wispr.providers.ProviderCancellation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine's view of every request it has not yet delivered: one cancellation token and one
 * delivery gate per request id. Registration refuses a colliding id rather than sharing a token;
 * removal is conditional on the same entry so an older request's `finally` cannot remove a newer
 * one; a cancel is a no-op on an unknown or delivered id. Pure Kotlin so the JVM tests can race it.
 */
class PolishRequestRegistry {
    class Entry internal constructor(val requestId: Long) {
        val cancellation = ProviderCancellation()
        private val delivered = AtomicBoolean(false)

        /** Runs [block] the first time only; a throwing block still counts as delivered. */
        fun deliverOnce(block: () -> Unit): Boolean {
            if (!delivered.compareAndSet(false, true)) return false
            runCatching(block)
            return true
        }
    }

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** @return the new entry, or null when [requestId] is already registered. */
    fun register(requestId: Long): Entry? {
        val entry = Entry(requestId)
        return if (entries.putIfAbsent(requestId, entry) == null) entry else null
    }

    fun cancel(requestId: Long) {
        entries.remove(requestId)?.cancellation?.cancel()
    }

    fun cancelAll() {
        entries.keys.toList().forEach(::cancel)
    }

    /** Removes [entry] only if it is still the one registered under its id. */
    fun release(entry: Entry) {
        entries.remove(entry.requestId, entry)
    }

    val size: Int get() = entries.size
}
