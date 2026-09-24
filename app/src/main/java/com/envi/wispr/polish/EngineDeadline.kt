package com.envi.wispr.polish

/**
 * The shipped local budgets and the debug override's bounded meaning. A valid override in 1..60 000 sets the
 * cooperative budget to that value and the hard budget to the value plus a grace; anything else is the
 * shipped pair. `EngineDeadlineTest` pins the bounds.
 */
internal data class LocalPolishBudget(val cooperativeMs: Long, val hardMs: Long) {
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
 * a local request still in flight a wedged worker would outlive its only hard deadline. A model load queued or
 * running on that worker is the same case (#344): the close would wait behind a load that may never return.
 */
internal fun mustKillEngineOnDestroy(poisoned: Boolean, activeLocalRequests: Int, modelLoading: Boolean): Boolean =
    poisoned || activeLocalRequests > 0 || modelLoading
