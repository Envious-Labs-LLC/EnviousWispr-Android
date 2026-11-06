package com.envi.wispr.process

import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A scheduler the test advances by hand, so both orders of the race can be played. A cancelled timer is
 * NOT removed: a real scheduler can already be running the command when `cancel(false)` arrives, so the
 * handle's own state, not the future's cancellation, must be what stops a late timer.
 */
internal class ManualDeadlineScheduler : ScheduledExecutorService by unsupported() {
    val queue = mutableListOf<Pair<Long, Runnable>>()
    var cancelled = 0

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val entry = delay to command
        queue += entry
        return object : ScheduledFuture<Any?> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled++; return true }
            override fun isCancelled() = false
            override fun isDone() = false
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
            override fun getDelay(unit: TimeUnit) = delay
            override fun compareTo(other: Delayed) = 0
        }
    }

    fun fireAll() { val due = queue.toList(); queue.clear(); due.forEach { it.second.run() } }
}

private inline fun <reified T> unsupported(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as T
