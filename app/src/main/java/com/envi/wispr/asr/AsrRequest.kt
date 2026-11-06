package com.envi.wispr.asr

import java.util.concurrent.Executor

/** One server request's admission, cancellation, delivery authorization and actual retirement. */
internal class AsrRequest(
    private val watchdog: AsrWatchdog,
    private val control: Executor,
    private val ownerOpen: () -> Boolean,
    private val cleanup: () -> Unit,
    private val retired: (Retirement) -> Unit,
    private val controlFailed: (Throwable) -> Unit,
) {
    enum class Retirement { NEVER_STARTED, BODY_COMPLETED, INCOMPLETE }
    private sealed interface Work {
        data object Preparing : Work
        data object CancelledPreparing : Work
        data object Queued : Work
        data object CleaningQueued : Work
        data class Active(val task: AsrWatchdog.Scope) : Work
        data class Cancelling(val task: AsrWatchdog.Scope, val grace: AsrWatchdog.Scope?) : Work
        data object Retired : Work
    }
    private enum class Delivery { AWAITING, AUTHORIZED, SUPPRESSED }
    private val lock = Any()
    private var work: Work = Work.Preparing
    private var delivery = Delivery.AWAITING
    val cancellation = AsrCancellation()

    /** Binder death-link registration really returned; cancellation cannot lose its pending unlink. */
    fun registrationComplete() {
        val clean = synchronized(lock) {
            when (work) {
                Work.Preparing -> { work = Work.Queued; false }
                Work.CancelledPreparing -> { work = Work.CleaningQueued; true }
                Work.Queued, Work.CleaningQueued, is Work.Active, is Work.Cancelling, Work.Retired -> false
            }
        }
        if (clean) finishQueued()
    }

    /** A cancelled queued wrapper is inert BEFORE starting any watchdog/model work. */
    fun claim(boundMs: Long): Boolean {
        var abandon = false
        val claimed = synchronized(lock) {
            when (work) {
                Work.Queued -> {
                    if (!ownerOpen()) { abandon = true; false } else {
                        val task = watchdog.begin(boundMs, "a transcription")
                        if (task == null) { abandon = true; false } else { work = Work.Active(task); true }
                    }
                }
                Work.Preparing -> error("Speech request registration has not returned")
                Work.CancelledPreparing, Work.CleaningQueued, is Work.Active, is Work.Cancelling, Work.Retired -> false
            }
        }
        if (abandon) cancel()
        return claimed
    }

    fun check() {
        val active = synchronized(lock) { (work as? Work.Active)?.task?.active == true && ownerOpen() }
        if (!active) throw AsrCancelled()
        cancellation.check()
    }

    fun tighten(totalBudgetMs: Long): Boolean = synchronized(lock) {
        val active = work as? Work.Active ?: return@synchronized false
        ownerOpen() && active.task.tighten(totalBudgetMs)
    }

    /** An already-authorized callback cannot be unsent; current main-side attempt guards still reject it. */
    fun deliver(action: () -> Unit): Boolean {
        val authorized = synchronized(lock) {
            val active = work as? Work.Active
            if (delivery == Delivery.AWAITING && active?.task?.active == true && ownerOpen()) {
                delivery = Delivery.AUTHORIZED
                true
            } else false
        }
        if (authorized) action()
        return authorized
    }

    fun cancel() {
        var terminate = false
        val clean = synchronized(lock) {
            if (delivery == Delivery.AWAITING) delivery = Delivery.SUPPRESSED
            when (val current = work) {
                Work.Preparing -> { cancellation.requestCancellation(); work = Work.CancelledPreparing; false }
                Work.Queued -> { cancellation.requestCancellation(); work = Work.CleaningQueued; true }
                is Work.Active -> {
                    cancellation.requestCancellation()
                    work = Work.Cancelling(current.task, watchdog.begin(AsrBounds.RETIREMENT_GRACE_MS, "cancelled transcription retirement"))
                    terminate = true
                    false
                }
                Work.CancelledPreparing, Work.CleaningQueued, is Work.Cancelling, Work.Retired -> false
            }
        }
        if (clean) finishQueued()
        if (terminate) {
            // This executor is independent of native execution AND deadline scheduling. UI never waits for JNI.
            try { control.execute { try { cancellation.terminateNative() } catch (failure: Throwable) { controlFailed(failure) } } }
            catch (failure: Throwable) { controlFailed(failure) }
        }
    }

    private fun clean(): Boolean = try { cleanup(); true } catch (failure: Throwable) {
        watchdog.poison("Speech request retirement")
        false
    }

    private fun finishQueued() {
        val complete = clean()
        synchronized(lock) { check(work == Work.CleaningQueued); work = Work.Retired }
        retired(if (complete && !watchdog.wedged) Retirement.NEVER_STARTED else Retirement.INCOMPLETE)
    }

    /** Called in the worker's finally AFTER all input/native/delivery/resource work actually exits. */
    fun finish() {
        val cleaned = clean() // Unlink/registry cleanup is still inside the whole-task and cancellation scopes.
        val complete = synchronized(lock) {
            val won = when (val current = work) {
                is Work.Active -> current.task.complete()
                is Work.Cancelling -> {
                    val taskWon = current.task.complete()
                    val graceWon = current.grace?.complete() == true
                    taskWon && graceWon
                }
                Work.Preparing, Work.CancelledPreparing, Work.Queued, Work.CleaningQueued, Work.Retired ->
                    error("A speech task retired without owning its body")
            }
            work = Work.Retired
            cleaned && won
        }
        retired(if (complete) Retirement.BODY_COMPLETED else Retirement.INCOMPLETE)
    }
}
