package com.envi.wispr.debug

import android.content.Context
import android.os.FileObserver
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This process's on-device log (#378): the file sink behind the hidden Detailed log switch. `DebugLogger`
 * hands every rendered line here as well as to logcat; the take's words reach a sink ONLY through [words],
 * and only into this app-private file, never logcat and never telemetry (`kotlin-patterns.md` RULE:
 * no-content-in-diagnostics, as narrowed by #378).
 *
 * Off means off. Callers read one process-local hint and, when it says off, enqueue nothing; when it is
 * stale they enqueue a lazy entry the [LogWriter] discards unevaluated under its lock. The hint is moved by
 * events, never a timer: one [FileObserver] per process on `flags/` and `logs/` wakes the writer when a
 * switch flag or the Share fence changes, and a take admission queues one refresh so a lost event recovers
 * at the next take.
 *
 * A limb: nothing here may delay or fail a dictation. Before [start] every call is a no-op.
 */
internal object LocalLog {
    @Volatile private var writer: LogWriter? = null
    @Volatile private var files: LogFiles? = null

    /** Held for the process's life: an unreferenced observer is collected and stops reporting. */
    @Volatile private var observer: FileObserver? = null
    private val started = AtomicBoolean(false)

    /** Open between a take admission and the writer's refresh, so that take's first lines are not lost to a stale hint. */
    @Volatile private var admissionWindow = false
    private var observerEvents = 0L

    /** Whether a call may enqueue. Reading it touches no disk. */
    private val accepting: Boolean
        get() = writer?.let { it.enabledHint || admissionWindow } ?: false

    /**
     * Once per process, from `ModelBootstrapApplication.onCreate`. Creates the directories before the watch
     * (a watch on a missing directory reports nothing), starts watching, then rescans flags and the fence so a
     * change made before the watch existed is still seen.
     */
    fun start(context: Context, process: String) {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            val paths = LogFiles(context.filesDir, context.cacheDir)
            paths.flagsDir.mkdirs()
            paths.logsDir.mkdirs()
            val lock = ProcessLogLock.of(paths.lock(process))
            val logWriter = LogWriter(
                files = paths,
                process = process,
                lock = lock,
                wallClock = System::currentTimeMillis,
                link = { from, to -> Os.link(from.absolutePath, to.absolutePath) },
                onWriteFailure = { DebugLogger.logcatOnly(TAG, it) },
                onRefreshed = { admissionWindow = false },
            )
            files = paths
            writer = logWriter
            logWriter.start()
            observer = watch(paths, logWriter).also { it.startWatching() }
            logWriter.signal(LogWriter.Signal.Refresh)
            logWriter.signal(LogWriter.Signal.Fence)
        }.onFailure { DebugLogger.logcatOnly(TAG, "Local log did not start: ${it.javaClass.simpleName}") }
    }

    private fun watch(paths: LogFiles, logWriter: LogWriter): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        return object : FileObserver(listOf(paths.flagsDir, paths.logsDir), mask) {
            override fun onEvent(event: Int, path: String?) {
                observerEvents++
                if (event and (DELETE_SELF or MOVE_SELF) != 0) {
                    // A watched directory went away: recreate it and rescan; the watch itself is re-armed at
                    // the next process start, and the take-admission refresh covers the gap until then.
                    paths.flagsDir.mkdirs()
                    paths.logsDir.mkdirs()
                    logWriter.signal(LogWriter.Signal.Refresh)
                    logWriter.signal(LogWriter.Signal.Fence)
                    return
                }
                when (path) {
                    LogFiles.DETAILED_LOG -> logWriter.signal(LogWriter.Signal.Refresh)
                    LogFiles.FENCE -> logWriter.signal(LogWriter.Signal.Fence)
                }
            }
        }
    }

    /** Called by `DebugLogger` with a line it already rendered for logcat. */
    fun line(level: Char, tag: String, takeId: String?, rendered: String) {
        if (!accepting) return
        writer?.offer(LogWriter.Entry(System.currentTimeMillis(), SystemClock.elapsedRealtime(), level, tag, takeId) { rendered })
    }

    /**
     * The ONE path by which a take's words reach any sink: this process's app-private log file, never logcat.
     * [text] is invoked only by the writer, under its lock, with Detailed log on.
     */
    fun words(tag: String, takeId: String?, stage: String, text: () -> String) {
        if (!accepting) return
        writer?.offer(
            LogWriter.Entry(System.currentTimeMillis(), SystemClock.elapsedRealtime(), 'I', tag, takeId) {
                val words = text()
                "words stage=$stage chars=${words.length} text=\"$words\""
            },
        )
    }

    /** At a take's admission: let this take's lines queue until the writer has re-read the flag. */
    fun takeAdmitted() {
        val logWriter = writer ?: return
        // The window closes when the writer has re-read the flag (`onRefreshed`); an entry queued meanwhile
        // is discarded by the writer's own under-lock check if the flag is absent.
        admissionWindow = true
        logWriter.signal(LogWriter.Signal.Refresh)
    }

    /**
     * After `Telemetry.bootstrap` has installed Sentry's handler: wraps whatever handler is current. On an
     * uncaught exception it asks the writer to flush and waits at most [CRASH_FLUSH_MS]; the flush is an
     * ordinary batch with the same under-lock flag check, so it cannot write content after Off. It never
     * touches a file on the crashing thread, and it calls the captured handler exactly once.
     */
    fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writer?.flush(CRASH_FLUSH_MS)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** The idle oracle's counters (#378 §11): observer callbacks and writer wakes in this process. */
    fun wakeCounts(): Pair<Long, Long> = observerEvents to (writer?.wakeCount ?: 0L)

    /** Visible for tests and the Share worker: this process's writer, or null before [start]. */
    internal fun writerForTest(): LogWriter? = writer

    private const val TAG = "LocalLog"
    private const val CRASH_FLUSH_MS = 200L
}
