package com.envi.wispr.providers

/** Cancellation is thread-safe and can interrupt a request that is blocked in HttpURLConnection. */
class ProviderCancellation {
    private val lock = Any()
    @Volatile private var cancelled = false
    private val callbacks = mutableListOf<() -> Unit>()

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        val snapshot = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        snapshot.forEach { callback -> callback() }
    }

    internal fun onCancel(callback: () -> Unit): AutoCloseable {
        val invokeImmediately = synchronized(lock) {
            if (cancelled) true else {
                callbacks += callback
                false
            }
        }
        if (invokeImmediately) callback()
        return AutoCloseable { synchronized(lock) { callbacks.remove(callback) } }
    }
}
