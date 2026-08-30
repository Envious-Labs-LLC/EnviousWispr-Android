package com.envi.wispr.paste

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one piece of work on another thread's looper and returns ITS answer, or the caller's
 * fallback when that looper never took the work up.
 *
 * The point of the type is that those two outcomes are the only two. The caller and the body claim
 * the right to act through a single [AtomicBoolean], so exactly one of them proceeds:
 *
 * - the caller wins the claim, the body returns without touching anything, and the fallback is the
 *   truth;
 * - the body wins the claim, and then it is ALREADY EXECUTING, so the caller waits for it with no
 *   second deadline and reports what it returned.
 *
 * The second bullet is the whole reason this is not a plain `get(timeout)`. A deadline there
 * returned "the service did not answer" while the queued body went on to schedule a real
 * insertion: the session owner rewrote the History row to interrupted, buzzed, toasted and posted
 * a notification saying the words had not arrived, and then the insertion completed in the editor
 * and rewrote the same row to completed. The words landed while three surfaces said they had not.
 *
 * That untimed wait is bounded by the body's own work rather than by a clock. It cannot DEADLOCK:
 * the body is already running, and it never waits on anything this caller holds. It is not instant
 * either, because `requestInsertion` makes its first insertion attempt inline and an accessibility
 * action is a binder call into another process, bounded by the framework's own timeouts rather than
 * by ours. Waiting through that is the right trade here: the caller is a background publication
 * step whose only other option is to tell the user their words did not arrive while they are
 * arriving. Anything that could block indefinitely, a file read, a database call, a network call,
 * or a lock this caller holds, must not be posted through here.
 *
 * Kept free of Android types so the claim can be RACED from the fast gate: a single-threaded test
 * cannot tell an atomic claim from a check-then-act (`validation-discipline.md`
 * RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act).
 */
internal class MainThreadHandoff(
    private val onLooperThread: () -> Boolean,
    private val post: (Runnable) -> Boolean,
    private val startTimeoutMs: Long,
) {

    /** Carries the answer so a legitimately null result is distinguishable from "no answer yet". */
    private class Answer<T>(val value: T)

    fun <T> call(fallback: T, action: () -> T): T {
        if (onLooperThread()) return action()
        val claimed = AtomicBoolean(false)
        val task = FutureTask<Answer<T>?> {
            if (claimed.compareAndSet(false, true)) Answer(action()) else null
        }
        if (!post(task)) {
            // The looper is gone. Claim it so a task that somehow runs later cannot act behind an
            // answer already given.
            claimed.set(true)
            return fallback
        }
        awaitStart(task)?.let { return it.value }
        if (claimed.compareAndSet(false, true)) {
            // We won the claim, so the posted body will return without touching anything.
            task.cancel(false)
            return fallback
        }
        // The body claimed it and is running now, so its answer is the only true one. No second
        // deadline: a deadline here is exactly what let the caller report a failure the service
        // did not have.
        //
        // The elvis is on the Answer, never on its value: `?.value ?: fallback` would substitute
        // the caller's fallback for a body that legitimately returned null, which is the one
        // distinction this wrapper exists to keep.
        val answer = runCatching { task.get() }.getOrNull()
        return if (answer == null) fallback else answer.value
    }

    private fun <T> awaitStart(task: FutureTask<Answer<T>?>): Answer<T>? =
        runCatching { task.get(startTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
}
