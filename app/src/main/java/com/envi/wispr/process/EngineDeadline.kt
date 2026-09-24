package com.envi.wispr.process

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A hard deadline on native work that cannot be interrupted (issue #75): expiry does not try, it runs [onExpiry] on
 * the scheduler thread, and the caller's expiry action ends its process. Shared by the polish engine (a local
 * generation, #75; the model load, #344) and the speech engine (the model load and each decode, #357).
 *
 * One state machine per [Handle]: `ARMED` moves to `CANCELLED` when the worker finishes first, or to
 * `EXPIRED` when the timer fires first, atomically, so a worker finishing late can never cancel an
 * expiry-owned exit and a timer firing late never runs its action. Pure Kotlin over an injected scheduler;
 * `EngineDeadlineTest` drives both orders.
 */
internal class EngineDeadline(private val scheduler: ScheduledExecutorService) {

    enum class State { ARMED, CANCELLED, EXPIRED }

    class Handle internal constructor() {
        private val state = AtomicReference(State.ARMED)
        internal var future: ScheduledFuture<*>? = null

        /** @return true when the worker won: the timer will never run its action. */
        fun cancel(): Boolean {
            if (!state.compareAndSet(State.ARMED, State.CANCELLED)) return false
            future?.cancel(false)
            return true
        }

        internal fun expire(): Boolean = state.compareAndSet(State.ARMED, State.EXPIRED)

        val current: State get() = state.get()
    }

    /** Arms the deadline; [onExpiry] runs on the scheduler thread only if the timer wins the race. */
    fun arm(budgetMs: Long, onExpiry: () -> Unit): Handle {
        val handle = Handle()
        handle.future = scheduler.schedule({ if (handle.expire()) onExpiry() }, budgetMs, TimeUnit.MILLISECONDS)
        return handle
    }

    /** Schedules a follow-up (the process exit) after [delayMs]; nothing can cancel it. */
    fun after(delayMs: Long, action: () -> Unit) {
        scheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    }
}
