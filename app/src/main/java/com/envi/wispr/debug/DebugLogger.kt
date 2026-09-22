package com.envi.wispr.debug

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one door onto logcat for EnviousWispr (#194): every diagnostic line in the app goes through here,
 * and what goes through is structurally content-free.
 *
 * - A [Throwable] is rendered by [render] as its class name and ONE code location, never its message
 *   and never its full stack trace: an exception message carries whatever the failing call was holding
 *   (a transcript, a prompt, a URL with a key on it), and Android's own three-argument `Log.e`
 *   prints the message and the whole trace. The cause chain contributes class names only.
 * - There is no file sink. The shared-storage log this object once wrote (`/sdcard/EnviousWispr/debug.log`)
 *   had no caller and would have been readable by any app with storage access.
 * - Pipeline profiling: mark events, get a timing summary. Thread-safe (AtomicInteger, ConcurrentLinkedQueue).
 *
 * `DiagnosticsShapeTest` refuses any other `android.util.Log` caller in the app and any diagnostic line
 * that carries exception text; `DebugLoggerRenderTest` proves the rendering.
 */
internal object DebugLogger {

    private const val MAX_MARKERS = 500

    /** Frames whose class starts with this are OUR code: the location where our code met the failure. */
    private const val APP_PACKAGE_PREFIX = "com.envi.wispr."

    /** Cause class names rendered before the chain is cut with `…`; a repeat cuts it earlier. */
    internal const val MAX_CAUSE_CLASSES = 4

    @Volatile
    private var pipelineStartTime = 0L

    private val markers = ConcurrentLinkedQueue<Pair<String, Long>>()
    private val markerCount = AtomicInteger(0)

    /**
     * Start a new pipeline timing session. Clears all previous markers.
     */
    fun startPipeline() {
        pipelineStartTime = SystemClock.elapsedRealtime()
        markers.clear()
        markerCount.set(0)
        log("Pipeline", "START")
    }

    /**
     * Record a named timing marker relative to pipeline start.
     * Capped at [MAX_MARKERS] to prevent unbounded growth.
     */
    fun mark(tag: String, event: String) {
        val elapsed = SystemClock.elapsedRealtime() - pipelineStartTime
        if (markerCount.getAndIncrement() < MAX_MARKERS) {
            markers.add(event to elapsed)
        }
        log(tag, "$event [+${elapsed}ms]")
    }

    /**
     * Get a one-line summary of all pipeline markers and total time.
     */
    fun pipelineSummary(): String {
        val total = SystemClock.elapsedRealtime() - pipelineStartTime
        val sb = StringBuilder("Pipeline: ${total}ms total")
        for ((event, time) in markers) {
            sb.append(" | $event:${time}ms")
        }
        return sb.toString()
    }

    fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun log(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
    }

    /**
     * The throwable is rendered by [render]; it is never handed to `Log.e` itself, which would print its
     * message and full trace.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, render(message, throwable))
    }

    /**
     * `message` alone when there is no throwable; otherwise `"$message (Class at Frame.method:line)"`,
     * followed by ` caused by X, Y` for the cause chain's class names.
     *
     * The frame is the first whose class is ours ([APP_PACKAGE_PREFIX]): for a failure thrown by our code
     * that is the throw site; for one thrown by a library and caught by us it is the call our code made.
     * With no such frame the top frame is used; with no frames at all (a throwable built with
     * `writableStackTrace = false`, or a vendor override of `fillInStackTrace`) the location is `no frame`.
     *
     * The cause walk is iterative with identity tracking and stops at [MAX_CAUSE_CLASSES] names or at the
     * first cause already seen, appending `…` when it was cut, so a cycle or a deep chain is bounded.
     * The frames are read once, here, on an error path only.
     */
    internal fun render(message: String, throwable: Throwable?): String {
        if (throwable == null) return message
        val sb = StringBuilder(message)
        sb.append(" (").append(className(throwable)).append(" at ").append(location(throwable)).append(')')
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        seen.add(throwable)
        var cause = throwable.cause
        var rendered = 0
        var first = true
        while (cause != null) {
            if (!seen.add(cause) || rendered == MAX_CAUSE_CLASSES) {
                sb.append(if (first) " caused by …" else ", …")
                break
            }
            sb.append(if (first) " caused by " else ", ").append(className(cause))
            first = false
            rendered++
            cause = cause.cause
        }
        return sb.toString()
    }

    private fun className(throwable: Throwable): String =
        throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name.substringAfterLast('.') }

    private fun location(throwable: Throwable): String {
        val frames = throwable.stackTrace
        if (frames.isEmpty()) return "no frame"
        val frame = frames.firstOrNull { it.className.startsWith(APP_PACKAGE_PREFIX) } ?: frames[0]
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }
}
