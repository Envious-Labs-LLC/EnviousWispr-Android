package com.envi.wispr.debug

import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a log ZIP (#378 D6), in main, on the one log worker only (the caller's job: the Developer page and
 * the adb door both submit to that worker, so no two exports ever interleave).
 *
 * It fences all five writers: it writes a new fence id, every writer drains what it holds, pins an
 * immutable snapshot of its own files (hard links plus the recorded length) under its own lock and
 * acknowledges with a status. The ZIP reads only those pins, never the live files, and reports each
 * process's status and drop count. It never calls itself complete: rotation can evict a line between the
 * tap and a process's pin, and a process that did not confirm is named, never assumed dead.
 *
 * A process that did not confirm is usually not running (an idle phone runs only main), and its files are still
 * on disk (#382). The export then takes that process's own log lock, bounded, and copies its files as they are
 * under it: no writer can append or rotate while the lock is held, so the copy is whole. A lock that stays busy
 * past the bound leaves the process reported as not confirmed, with no files, exactly as before.
 */
internal class LogExport(
    private val files: LogFiles,
    private val deviceFacts: () -> String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val ackWaitMs: Long = ACK_WAIT_MS,
    private val lockFor: (process: String) -> ProcessLogLock = { ProcessLogLock.of(files.lock(it)) },
    private val lockTimeoutMs: Long = LogWriter.LOCK_TIMEOUT_MS,
) {
    /** The result: the finished ZIP and what [LogExport] could say about each process. */
    class Result(val zip: File, val statuses: Map<String, String>)

    /** Builds one ZIP into [outDir] and returns it; the caller owns the file afterwards. */
    fun build(outDir: File): Result {
        val fenceId = newId()
        writeAtomically(files.fence, fenceId)
        removeOtherSnapshots(keep = fenceId)
        try {
            val statuses = awaitAcks(fenceId).mapValues { (process, status) ->
                if (status == NOT_CONFIRMED) copyUnconfirmed(fenceId, process) else status
            }
            outDir.mkdirs()
            val partial = File(outDir, "${newId()}.zip.partial")
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("device.txt"))
                zip.write(deviceText(statuses).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((process, status) in statuses) {
                    when (status) {
                        NOT_CONFIRMED -> continue
                        COPIED_UNDER_LOCK -> addPins(zip, unconfirmedCopy(fenceId, process))
                        else -> addPins(zip, files.snapshot(fenceId, process))
                    }
                }
            }
            val done = File(outDir, partial.name.removeSuffix(".partial"))
            if (!partial.renameTo(done)) error("The ZIP could not be published")
            return Result(done, statuses)
        } finally {
            File(files.snapshots, fenceId).deleteRecursively()
        }
    }

    /**
     * Every snapshot directory except [keep]. Pins are never written after creation, so removing them
     * corrupts nothing; a pin a writer makes just after this scan is an orphan the next export or start
     * removes (at most one per writer, 50 MB in all, #378 D6).
     */
    fun removeOtherSnapshots(keep: String?) {
        files.snapshots.listFiles()?.forEach { dir -> if (dir.name != keep) dir.deleteRecursively() }
    }

    /** At main startup: cancel any inherited fence first, so no writer pins for it, then remove every pin. */
    fun startupCleanup() {
        writeAtomically(files.fence, LogFiles.CANCELLED_FENCE)
        removeOtherSnapshots(keep = null)
    }

    /** Exports older than [maxAgeMs] in [dir]; run on the log worker before a new export and at start. */
    fun sweep(dir: File, maxAgeMs: Long) {
        val cutoff = now() - maxAgeMs
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun awaitAcks(fenceId: String): Map<String, String> {
        val deadline = System.nanoTime() + ackWaitMs * 1_000_000
        val statuses = LinkedHashMap<String, String>()
        while (true) {
            for (process in LogFiles.PROCESSES) {
                if (statuses.containsKey(process)) continue
                val ack = runCatching { files.ack(process).readText().trim().split(' ') }.getOrNull() ?: continue
                if (ack.firstOrNull() == fenceId) statuses[process] = ack.drop(1).joinToString(" ")
            }
            if (statuses.size == LogFiles.PROCESSES.size || System.nanoTime() >= deadline) break
            Thread.sleep(ACK_POLL_MS)
        }
        return LogFiles.PROCESSES.associateWith { statuses[it] ?: NOT_CONFIRMED }
    }

    /**
     * [process] did not acknowledge: copy its files under its own lock into a directory no writer pins into, so
     * a late acknowledgment cannot rewrite them while the ZIP reads them. A writer that acknowledged while this
     * waited for the lock has a whole pin, and that pin wins. Returns the status the ZIP reports.
     */
    private fun copyUnconfirmed(fenceId: String, process: String): String {
        val held = runCatching {
            lockFor(process).withLock(lockTimeoutMs) {
                val ack = runCatching { files.ack(process).readText().trim().split(' ') }.getOrNull()
                if (ack?.firstOrNull() == fenceId) return@withLock ack.drop(1).joinToString(" ")
                val dir = unconfirmedCopy(fenceId, process).apply { mkdirs() }
                val lengths = StringBuilder()
                for (file in files.setOf(process)) {
                    if (!file.exists()) continue
                    val length = file.length()
                    LogWriter.copyPrefix(file, File(dir, file.name), length)
                    lengths.append(file.name).append(' ').append(length).append('\n')
                }
                File(dir, LogWriter.LENGTHS).writeText(lengths.toString())
                COPIED_UNDER_LOCK
            }
        }.getOrNull()
        return held?.value ?: NOT_CONFIRMED
    }

    private fun unconfirmedCopy(fenceId: String, process: String) = File(File(files.snapshots, fenceId), "$process$UNCONFIRMED_SUFFIX")

    private fun addPins(zip: ZipOutputStream, dir: File) {
        val lengths = runCatching { File(dir, LogWriter.LENGTHS).readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }
        for ((name, length) in lengths) {
            val pinned = File(dir, name)
            if (!pinned.exists()) continue
            zip.putNextEntry(ZipEntry("logs/$name"))
            FileInputStream(pinned).use { input ->
                var left = length
                val buffer = ByteArray(64 * 1024)
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                    left -= read
                }
            }
            zip.closeEntry()
        }
    }

    private fun deviceText(statuses: Map<String, String>): String = buildString {
        append(deviceFacts())
        append("\nThis ZIP is never complete by guarantee: rotation can remove the oldest lines between the request and a process's snapshot.\n")
        append("Per-process fence status (status dropped-count):\n")
        for ((process, status) in statuses) append("  ").append(process).append(": ").append(status).append('\n')
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        temp.renameTo(target)
    }

    companion object {
        const val NOT_CONFIRMED = "did not confirm within 2 s: not running, or busy; any lines it still held may be missing"
        const val COPIED_UNDER_LOCK = "did not confirm within 2 s (usually not running); its files as they stood were copied under its log lock"
        private const val UNCONFIRMED_SUFFIX = ".unconfirmed"
        private const val ACK_WAIT_MS = 2_000L

        /** Only while an export is waiting for acknowledgments; never at idle. */
        private const val ACK_POLL_MS = 25L
        const val EXPORT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
