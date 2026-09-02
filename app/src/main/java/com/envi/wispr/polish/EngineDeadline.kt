package com.envi.wispr.polish

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine's hard deadline on a local generation (issue #75). A wedged native generation cannot be
 * interrupted, so expiry does not try: it runs [onExpiry] on the scheduler thread, and the caller's expiry
 * action delivers the deterministic text, poisons the runtime and ends the process.
 *
 * One state machine per [Handle]: `ARMED` moves to `CANCELLED` when the worker finishes first, or to
 * `EXPIRED` when the timer fires first, atomically, so a worker finishing late can never cancel an
 * expiry-owned exit and a timer firing late never runs its action. Pure Kotlin over an injected scheduler;
 * `EngineDeadlineTest` drives both orders.
 */
class EngineDeadline(private val scheduler: ScheduledExecutorService) {

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

/**
 * The shipped local budgets and the debug override's bounded meaning. A valid override in 1..60 000 sets the
 * cooperative budget to that value and the hard budget to the value plus a grace; anything else is the
 * shipped pair. `EngineDeadlineTest` pins the bounds.
 */
data class LocalPolishBudget(val cooperativeMs: Long, val hardMs: Long) {
    companion object {
        const val COOPERATIVE_MS = 10_000L
        const val HARD_MS = 12_000L
        const val GRACE_MS = 2_000L
        const val MAX_OVERRIDE_MS = 60_000L
        val SHIPPED = LocalPolishBudget(COOPERATIVE_MS, HARD_MS)

        fun fromOverride(value: String?): LocalPolishBudget {
            val ms = value?.trim()?.toLongOrNull() ?: return SHIPPED
            if (ms < 1L || ms > MAX_OVERRIDE_MS) return SHIPPED
            return LocalPolishBudget(ms, ms + GRACE_MS)
        }
    }
}

/**
 * The winning expiry path of a local generation, in the order the engine depends on (#75): poison first, so
 * a request entering during delivery already sees it; deliver the deterministic text; then, only if this
 * caller won `deliverOnce`, schedule the exit. A throwing delivery still counts as delivered and still
 * exits, because the engine is poisoned by then. Pure so the JVM tests exercise the production order.
 */
internal fun expireOnce(
    entry: PolishRequestRegistry.Entry,
    poison: () -> Unit,
    deliver: () -> Unit,
    scheduleExit: () -> Unit,
): Boolean {
    val won = entry.deliverOnce {
        poison()
        deliver()
    }
    if (won) scheduleExit()
    return won
}

/**
 * Whether the engine must end its process on destruction instead of closing the runtime in order (#75).
 * Orderly destruction cancels the deadline timer and queues the runtime close behind the worker, so with
 * a local request still in flight a wedged worker would outlive its only hard deadline.
 */
internal fun mustKillEngineOnDestroy(poisoned: Boolean, activeLocalRequests: Int): Boolean =
    poisoned || activeLocalRequests > 0
