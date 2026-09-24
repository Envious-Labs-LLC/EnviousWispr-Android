package com.envi.wispr.polish

import com.envi.wispr.debug.DebugLogger
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Closes a resource whose `close()` is a synchronous vendor call off the caller's thread (#306), so a Service's
 * teardown on main never waits for it. With [PROCESS] the close is queued and the caller returns without waiting;
 * an injected executor may run it inline in tests.
 *
 * A close that throws an `Exception` is logged by class name only (a VM error still propagates, as the detector's
 * own policy). If the executor rejects the task, one short-lived daemon thread runs that close instead: never the
 * calling thread, which may be main. A close taken that way has no order against the queue, and it is not dropped
 * if the thread starts.
 */
internal class BackgroundCloser(private val executor: Executor) {
    companion object {
        private const val TAG = "BackgroundCloser"

        /**
         * One per process, shared by every Service instance, so no Service's destroy can cancel it. No permanent
         * idle thread: the worker expires after five idle seconds (`architecture-rules.md` RULE: no-idle-cost).
         * Accepted closes run one at a time in submission order.
         */
        val PROCESS = BackgroundCloser(
            ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
                Thread(runnable, "DetectorClose").apply { isDaemon = true }
            },
        )
    }

    fun close(resource: Closeable) {
        val task = Runnable { closeQuietly(resource) }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            Thread(task, "DetectorCloseFallback").apply { isDaemon = true }.start()
        }
    }

    private fun closeQuietly(resource: Closeable) {
        try {
            resource.close()
            // Content-free: which resource, on which thread. The receipt that the close left the caller's thread.
            runCatching { DebugLogger.log(TAG, "Closed ${resource.javaClass.simpleName} on ${Thread.currentThread().name}") }
        } catch (error: Exception) {
            // Shape only, never a message (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
            runCatching { DebugLogger.warn(TAG, "Close failed: ${error.javaClass.simpleName}") }
        }
    }
}
