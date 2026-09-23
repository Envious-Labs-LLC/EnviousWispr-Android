package com.envi.wispr.audio

import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.Telemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The warm hold's silence writer on its own thread (#257): `stop()` marks the [SilenceWriter] stopped, and
 * [exited] says whether the thread has left the loop. A writer never started counts as exited.
 */
internal class SilenceWriterThread(write: () -> Int, onFailed: () -> Unit) {
    private val writer = SilenceWriter(write, onFailed)
    private val exit = CountDownLatch(1)
    @Volatile private var thread: Thread? = null

    fun start() {
        thread = Thread({ runToExit() }, "WarmHoldSilence").apply { start() }
    }

    private fun runToExit() {
        try {
            writer.run()
        } finally {
            exit.countDown()
        }
    }

    fun stop() = writer.stop()

    fun exited(): Boolean = thread == null || exit.count == 0L

    /** Waits, bounded, for the loop to leave; a JVM row's signal, never used on a production thread. */
    fun awaitExit(timeoutMs: Long): Boolean = thread == null || exit.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
}

/**
 * Every stopped warm-hold silence writer of this process, until its thread is seen to exit (#257).
 *
 * A new hold is admitted only when every stopped writer has exited ([admit], a liveness read, no wait). A writer
 * still running [EXIT_BOUND_MS] after its stop is reported once, as a content-free defect, and latches the
 * process against any further hold: the stuck thread holds a stopped, released track and no lock, and ending
 * the process could cut off a take that holds the handed-over route (deliberate audit deviation, the plan §3).
 *
 * The check and the report run on the watch's own worker, never main, a binder thread or under a caller's lock:
 * [admit] only queues the report. Process-scoped ([PROCESS]) so a replacement service instance sees it and no
 * service teardown can drop a pending sweep.
 */
internal class SilenceWriterWatch(
    private val clock: () -> Long,
    /** Runs a task on the watch's worker after a delay; never inline. */
    private val schedule: (Runnable, Long) -> Unit,
    private val report: () -> Unit,
) {
    private class Stopped(val track: WarmHold.SilentTrack, val atMs: Long)

    private val pending = ArrayList<Stopped>()
    private var wedged = false
    private var reported = false

    /** A hold's track has been stopped: watch its writer, and check it once the bound has passed. */
    fun stopped(track: WarmHold.SilentTrack) {
        val entry = Stopped(track, clock())
        synchronized(this) { pending += entry }
        schedule(Runnable { sweep(entry) }, EXIT_BOUND_MS)
    }

    /** True only when every stopped writer has exited and no writer ever overran the bound in this process. */
    fun admit(): Boolean {
        var claimed = false
        val admitted = synchronized(this) {
            claimed = check()
            !wedged && pending.isEmpty()
        }
        if (claimed) schedule(Runnable { send() }, 0L)
        return admitted
    }

    /**
     * On the worker, bound to the one stop that scheduled it: report an overdue writer once, or, when it fired
     * early, come back for the same entry. A sweep whose entry has exited schedules nothing; each other entry has
     * its own sweep.
     */
    private fun sweep(entry: Stopped) {
        var claimed = false
        var wait: Long? = null
        synchronized(this) {
            claimed = check()
            if (!wedged && entry in pending) wait = (EXIT_BOUND_MS - (clock() - entry.atMs)).takeIf { it > 0L }
        }
        if (claimed) send()
        wait?.let { schedule(Runnable { sweep(entry) }, it) }
    }

    /** Under the monitor. Drops exited writers; latches on an overdue one; true when this call claimed the report. */
    private fun check(): Boolean {
        pending.removeAll { it.track.writerExited() }
        val now = clock()
        if (!wedged && pending.any { now - it.atMs >= EXIT_BOUND_MS }) wedged = true
        if (wedged && !reported) {
            reported = true
            return true
        }
        return false
    }

    private fun send() {
        runCatching { report() }
            .onFailure { DebugLogger.warn(TAG, "silence writer defect not reported: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "SilenceWriterWatch"

        /** Grace before declaring a write stuck; a grace period, not a measured guarantee. */
        const val EXIT_BOUND_MS = 2_000L

        /** One lazily started daemon worker for the process; it parks with no timer between stops. */
        private val worker by lazy {
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "SilenceWriterWatch").apply { isDaemon = true } }
        }

        val PROCESS = SilenceWriterWatch(
            clock = { SystemClock.elapsedRealtime() },
            schedule = { task, delayMs -> worker.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
            report = { Telemetry.defect(AppDefect.SilenceWriterExitWedged) },
        )
    }
}
