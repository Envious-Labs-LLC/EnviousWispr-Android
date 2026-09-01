package com.envi.wispr.polish

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Mints polish request ids that are unique for the life of the engine process. A raw clock reading
 * is monotonic but not an allocator: two calls can observe one value, and two session-owner
 * instances can share one `:polish` process whose request map treats equality as identity. So the
 * id is `max(previous + 1, clock)`, atomically; the clock only keeps ids increasing across
 * session-owner instances, and the `+ 1` keeps them distinct within one tick.
 */
class PolishRequestIdSource(private val clock: () -> Long) {
    private val last = AtomicLong(0L)

    fun next(): Long = last.updateAndGet { previous -> maxOf(previous + 1, clock()) }

    companion object {
        /** The process-wide source production code uses; tests inject their own clock. */
        val shared: PolishRequestIdSource by lazy { PolishRequestIdSource { SystemClock.elapsedRealtimeNanos() } }
    }
}
