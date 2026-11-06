package com.envi.wispr.asr

import com.envi.wispr.process.EngineDeadline

/**
 * `:asr`'s hard bound on its own native work (#357). A native load or decode ignores `Thread.interrupt`, and the
 * release queues behind it (`RecognizerOwner`), so a wedged one would hold the process's only worker for good and
 * every later take would queue behind it. Expiry therefore ends the process ([endProcess]); the owner sees the
 * disconnect, and the next take binds a fresh `:asr`.
 */
internal class AsrWatchdog(
    private val deadline: EngineDeadline,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val endProcess: (String) -> Unit,
) {
    private val lock = Any()
    private val scopes = mutableSetOf<Scope>()
    private sealed interface Ending {
        data object Healthy : Ending
        data class Pending(val reason: String) : Ending
        data object Dispatched : Ending
    }
    private var ending: Ending = Ending.Healthy

    /** True once any bound expired: the process is ending and must not report ready. */
    val wedged: Boolean get() = synchronized(lock) { endingLocked() }

    /** Observe the winning handle itself, including the interval before its expiry action runs. */
    private fun endingLocked(): Boolean {
        if (ending == Ending.Healthy) {
            scopes.firstOrNull { it.expired() }?.let { ending = Ending.Pending(it.reason) }
        }
        return ending != Ending.Healthy
    }

    private fun end(reason: String) {
        prepareEnding(reason).invoke()
    }

    /** Publish failure under a caller's short control lock, then invoke the returned action OUTSIDE it. */
    fun prepareEnding(reason: String): () -> Unit {
        synchronized(lock) {
            if (ending == Ending.Healthy) ending = Ending.Pending(reason)
        }
        return ::dispatchEnding
    }

    private fun dispatchEnding() {
        val dispatch = synchronized(lock) {
            when (val state = ending) {
                is Ending.Pending -> { ending = Ending.Dispatched; state.reason }
                Ending.Healthy, Ending.Dispatched -> null
            }
        }
        // A delayed or blocked kill action never holds the admission lock.
        if (dispatch != null) endProcess(dispatch)
    }

    /** Native cleanup did not complete; the same authority permanently refuses further admission. */
    fun poison(what: String) = end("$what did not complete native cleanup")

    /** A failed bound must publish poison BEFORE releasing admission, with external termination afterward. */
    private fun <T> control(block: () -> T): T {
        val reason = "Speech deadline control could not establish a bound"
        try {
            return synchronized(lock) {
                try { block() } catch (failure: Throwable) {
                    if (ending == Ending.Healthy) ending = Ending.Pending(reason)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            end(reason)
            throw failure
        }
    }

    /** A whole-task or retirement scope. Only the worker completes/tightens it; control threads may arm grace. */
    inner class Scope internal constructor(
        private var handle: EngineDeadline.Handle,
        private val startedMs: Long,
        private var budgetMs: Long,
        private val what: String,
    ) {
        internal var reason = "$what outlived its $budgetMs ms bound"
            private set

        internal fun expired(): Boolean = handle.current == EngineDeadline.State.EXPIRED

        val active: Boolean get() = synchronized(lock) {
            !endingLocked() && handle.current == EngineDeadline.State.ARMED
        }

        /** False after expiry, including when the expiry action has not yet published its kill. */
        fun complete(): Boolean = synchronized(lock) {
            endingLocked()
            val won = handle.cancel()
            if (expired() && ending == Ending.Healthy) ending = Ending.Pending(reason)
            scopes.remove(this)
            won && !endingLocked()
        }

        /** Tighten from the ORIGINAL task start; metadata never restarts or extends a task's budget. */
        fun tighten(totalBudgetMs: Long): Boolean {
            var elapsed = false
            val accepted = control {
                require(totalBudgetMs in 0..budgetMs) { "A speech deadline may only tighten" }
                if (endingLocked() || handle.current != EngineDeadline.State.ARMED) return@control false
                if (!handle.cancel()) {
                    endingLocked()
                    return@control false
                }
                budgetMs = totalBudgetMs
                reason = "$what outlived its $totalBudgetMs ms bound"
                val remaining = totalBudgetMs - (nowMs() - startedMs)
                if (remaining <= 0) {
                    ending = Ending.Pending(reason)
                    elapsed = true
                    false
                } else {
                    val expiryReason = reason
                    handle = deadline.arm(remaining) { end(expiryReason) }
                    true
                }
            }
            if (elapsed) end(reason)
            return accepted
        }
    }

    /** Publication and admission share the control lock, so no expired unpublished handle is skipped. */
    fun begin(boundMs: Long, what: String): Scope? {
        return control {
            if (endingLocked()) return@control null
            val started = nowMs()
            val reason = "$what outlived its $boundMs ms bound"
            Scope(deadline.arm(boundMs) { end(reason) }, started, boundMs, what).also { scopes += it }
        }
    }

    /**
     * Runs [work] under [boundMs]. Returns its value (or its throw) when it finished first; null when the bound expired
     * first, in which case the process is already ending and the late value or throw must deliver nothing.
     */
    fun <T> guard(boundMs: Long, what: String, work: () -> T): T? {
        val scope = begin(boundMs, what) ?: return null
        if (!scope.active) { scope.complete(); return null }
        val value = try {
            work()
        } catch (error: Throwable) {
            // A throw after the bound is late too (review round 1): the process is ending, so it reports nothing.
            if (!scope.complete()) return null
            throw error
        }
        return if (scope.complete()) value else null
    }
}
