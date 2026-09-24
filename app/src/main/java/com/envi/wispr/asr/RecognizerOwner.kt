package com.envi.wispr.asr

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one owner of a native recognizer (#212). Every touch, the release included, is a task on [worker],
 * so a release is ordered after every task admitted before it and can never free the object a load or a
 * decode is inside: the native `release()` takes no lock of its own, and a JNI decode ignores
 * `Thread.interrupt`. [close] returns at once, never frees on the caller's thread and never ends the
 * process (Android can start a replacement service in the same process while this one is being destroyed).
 *
 * The `closed` read at the head of a worker task is that task's admission point. A task that reads false
 * may finish even if [close] flips right after; the release is queued behind it. A task that reads true
 * calls its refusal once and never reads [recognizer]; such a wrapper may run after the release.
 */
internal class RecognizerOwner<R : Any>(
    private val worker: ExecutorService,
    private val free: (R) -> Unit,
    private val discarded: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    /** Read and written only on [worker]. */
    private var recognizer: R? = null

    @Volatile
    private var ready = false

    /** A close permanently masks readiness, even if a load finishes afterwards. */
    val isReady: Boolean
        get() = !closed.get() && ready

    fun load(open: () -> R?) = submit(refused = {}) {
        val opened = open()
        recognizer = opened
        ready = opened != null
    }

    /**
     * Runs [work] on the worker with the recognizer (null if it never loaded). [work] does the decode and
     * RETURNS the delivery; the delivery runs only if the owner is still open when the work returns, else
     * [discarded] runs. The check is best-effort: a synchronous callback already in flight when [close]
     * flips still arrives. After close, [refused] runs instead, exactly once.
     */
    fun use(refused: () -> Unit, work: (R?) -> () -> Unit) = submit(refused) {
        val deliver = work(recognizer)
        if (closed.get()) discarded() else deliver()
    }

    /**
     * Idempotent. Queues the release behind every admitted task, then stops the worker. [after] runs once, after the
     * release (or at once if nothing could be queued), whether or not a recognizer was ever loaded.
     */
    fun close(after: () -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        try {
            worker.execute {
                try {
                    val current = recognizer
                    recognizer = null
                    ready = false
                    if (current != null) free(current)
                } finally {
                    after()
                }
            }
        } catch (_: RejectedExecutionException) {
            // Only reachable if the worker was stopped outside this owner; nothing can be ordered then.
            after()
        }
        worker.shutdown()
    }

    private fun submit(refused: () -> Unit, task: () -> Unit) {
        try {
            worker.execute { if (closed.get()) refused() else task() }
        } catch (_: RejectedExecutionException) {
            refused()
        }
    }
}
