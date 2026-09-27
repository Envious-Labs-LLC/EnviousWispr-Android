package com.envi.wispr.debug

import android.content.Context
import android.os.Build
import com.envi.wispr.BuildConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Main's log worker (#378 D6, D10): the ONE thread that builds log ZIPs, sweeps old exports and removes
 * orphan pins, for both the Developer page's Share and the adb door. One thread, so no two of these ever
 * interleave. It touches only `filesDir/logs/` (pins), `cacheDir/share/` and `cacheDir/devlog-pulls/log/`;
 * recordings have their own worker and folders.
 */
internal class DeveloperLogs private constructor(private val app: Context) {
    val files = LogFiles(app.filesDir, app.cacheDir)
    private val export = LogExport(files, ::deviceFacts)
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "developer-log-export").apply { isDaemon = true } }

    /** At main start: cancel an inherited fence, remove every pin, expire old exports, drop abandoned door files. */
    fun startup() {
        worker.execute {
            runCatching {
                export.startupCleanup()
                export.sweep(files.shareDir, LogExport.EXPORT_MAX_AGE_MS)
                files.doorLogDir.listFiles()?.forEach { it.delete() }
            }.onFailure { DebugLogger.logcatOnly(TAG, "Log export cleanup failed: ${it.javaClass.simpleName}") }
        }
    }

    /** The Developer page's Share: a ZIP under `cacheDir/share/`, kept 24 hours for the receiving app. */
    fun shareZip(): Future<LogExport.Result> = worker.submit<LogExport.Result> {
        export.sweep(files.shareDir, LogExport.EXPORT_MAX_AGE_MS)
        export.build(files.shareDir)
    }

    /** Delete shared log ZIPs: after any Share queued before it, so a ZIP is handed off before it can go. */
    fun deleteSharedZips(): Future<Int> = worker.submit<Int> {
        files.shareDir.listFiles()?.count { it.name.endsWith(".zip") && it.delete() } ?: 0
    }

    /** The adb door's pull: a ZIP under `cacheDir/devlog-pulls/log/`, unlinked by the door once opened. */
    fun doorZip(): Future<LogExport.Result> = worker.submit<LogExport.Result> {
        // Only ABANDONED door files go: a ZIP another pull built moments ago may not be open yet (code review round 1).
        removeAbandonedDoorFiles()
        export.build(files.doorLogDir)
    }

    /** After a door timeout: queued behind the build that timed out, so it never deletes a file being written. */
    fun cleanupDoorFiles() {
        worker.execute { removeAbandonedDoorFiles() }
    }

    /** Door files older than [DOOR_FILE_ABANDONED_MS]: a pull opens its ZIP within its own 30 s bound or not at all. */
    private fun removeAbandonedDoorFiles() {
        val cutoff = System.currentTimeMillis() - DOOR_FILE_ABANDONED_MS
        files.doorLogDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Total size of the live log files, for the Developer page. */
    fun liveLogBytes(): Long = files.logsDir.listFiles()?.filter { it.name.endsWith(".log") }?.sumOf { it.length() } ?: 0L

    /** Whether a log file or a shared export exists: the Privacy page's retained-file sentence reads it. */
    fun retainedFilesExist(): Boolean =
        liveLogBytes() > 0 || (files.shareDir.listFiles()?.any { it.name.endsWith(".zip") } ?: false)

    private fun deviceFacts(): String {
        val switches = DeveloperSwitches.of(app).state.value
        return buildString {
            append("EnviousWispr ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ")
                .append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Free storage: ").append(app.filesDir.usableSpace / (1024 * 1024)).append(" MB\n")
            append("Detailed log: ").append(switches.detailedLog).append(", Keep recordings: ").append(switches.keepRecordings).append('\n')
        }
    }

    companion object {
        private const val TAG = "DeveloperLogs"
        private const val DOOR_FILE_ABANDONED_MS = 5 * 60 * 1000L
        @Volatile private var instance: DeveloperLogs? = null

        fun of(context: Context): DeveloperLogs = instance ?: synchronized(this) {
            instance ?: DeveloperLogs(context.applicationContext).also { instance = it }
        }
    }
}
