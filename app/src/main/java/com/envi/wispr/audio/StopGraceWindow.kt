package com.envi.wispr.audio

/**
 * The short stretch after a stop request during which the capture loop keeps reading, so the last audio still
 * travelling to the app reaches the file (#419).
 *
 * One instance per take, written by the thread that wins the ending claim and read by the capture thread. The
 * deadline is armed BEFORE the take stops being "recording": a reader that sees the stop must already see the
 * window, or it leaves the loop and the tail is lost anyway. [cancel] closes the window early (a destroyed
 * service) and cannot be undone by a later [arm].
 */
internal class StopGraceWindow {
    @Volatile private var untilMs: Long = 0L
    @Volatile private var cancelled: Boolean = false

    /** True once a window was armed, whether it is still open or not: the take ended by a stop request. */
    val wasArmed: Boolean get() = untilMs != 0L

    /** Opens the window for [graceMs] from [nowMs]. A second call keeps the first deadline. */
    fun arm(nowMs: Long, graceMs: Long) {
        if (untilMs == 0L && graceMs > 0L) untilMs = nowMs + graceMs
    }

    fun isOpen(nowMs: Long): Boolean = !cancelled && untilMs != 0L && nowMs < untilMs

    fun cancel() {
        cancelled = true
    }
}
