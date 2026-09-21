package com.envi.wispr.telemetry

import android.content.Context
import com.envi.wispr.debug.DebugLogger
import java.io.File
import java.util.UUID

/**
 * A content-free note a DYING process leaves for the next main start (issue #176, plan §3.2). A
 * deliberate `Process.killProcess` produces no crash report and `Sentry.flush` acknowledges nothing
 * (G1 D6), so the silence detector writes one of these before it kills itself, and the next main
 * bootstrap converts it into a Sentry event with the record's ORIGINAL id, timestamp, process, build,
 * install id and take id, never the converter's own scope.
 *
 * One file per record, named by its event UUID, written to a temp name and published by rename, so a
 * half-written file is never eligible. Main is the sole converter, at most once per run; a record is
 * retained after conversion until it expires seven days after its original timestamp, because there is
 * no SDK acknowledgment and Sentry de-duplicates by event id within the day (G2 D6, G3 E1). Best effort,
 * and described as such.
 */
internal object PendingDefects {
    private const val TAG = "PendingDefects"
    private const val DIR = "telemetry/pending-defects"
    private const val TEMP_SUFFIX = ".tmp"
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    /** A temp younger than this may be a live writer's; the note path is bounded at 500 ms. */
    const val TEMP_GRACE_MS = 60_000L

    /** Everything a converter needs and nothing that is content. */
    data class Record(
        val eventId: String,
        val timestampMs: Long,
        val processName: String,
        val appBuild: Int,
        val installId: String?,
        val takeId: String?,
        val fingerprint: String,
        val semanticId: String,
        /** A closed token the writer chose (the wedged call's name), never free text. */
        val detail: String,
    )

    /** Writes on the caller's thread; never the watchdog executor. Returns false when storage refused. */
    fun write(context: Context, record: Record): Boolean {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val temp = File(dir, record.eventId + TEMP_SUFFIX)
        val published = File(dir, record.eventId)
        return runCatching {
            temp.writeText(encode(record))
            temp.renameTo(published)
        }.getOrDefault(false).also { if (!it) runCatching { temp.delete() } }
    }

    /** Committed records only; temp files are the writer's and are cleaned at its next start. */
    fun readAll(context: Context, nowMs: Long): List<Record> {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles() ?: return emptyList()
        val out = mutableListOf<Record>()
        for (file in files) {
            if (file.name.endsWith(TEMP_SUFFIX)) continue
            val record = runCatching { decode(file.readText()) }.getOrNull()
            if (record == null) {
                DebugLogger.warn(TAG, "Dropping an unreadable pending defect record")
                runCatching { file.delete() }
                continue
            }
            if (nowMs - record.timestampMs > RETENTION_MS) {
                runCatching { file.delete() }
                continue
            }
            out += record
        }
        return out
    }

    /** The writer's own housekeeping at its next start: a temp file is a write that never finished. */
    /** Temps older than [TEMP_GRACE_MS]: a younger one may be a dying helper's write in flight right now. */
    fun cleanTemps(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val dir = File(context.filesDir, DIR)
        dir.listFiles()
            ?.filter { it.name.endsWith(TEMP_SUFFIX) && nowMs - it.lastModified() > TEMP_GRACE_MS }
            ?.forEach { runCatching { it.delete() } }
    }

    fun newEventId(): String = UUID.randomUUID().toString().replace("-", "")

    // One line per field, key=value, no free text: every value is a token, a number or a UUID.
    private fun encode(r: Record): String = listOf(
        "eventId=${r.eventId}", "timestampMs=${r.timestampMs}", "processName=${r.processName}",
        "appBuild=${r.appBuild}", "installId=${r.installId.orEmpty()}", "takeId=${r.takeId.orEmpty()}",
        "fingerprint=${r.fingerprint}", "semanticId=${r.semanticId}", "detail=${r.detail}",
    ).joinToString("\n")

    private fun decode(text: String): Record {
        val map = text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        return Record(
            eventId = map.getValue("eventId"),
            timestampMs = map.getValue("timestampMs").toLong(),
            processName = map.getValue("processName"),
            appBuild = map.getValue("appBuild").toInt(),
            installId = map["installId"]?.takeIf { it.isNotEmpty() },
            takeId = map["takeId"]?.takeIf { it.isNotEmpty() },
            fingerprint = map.getValue("fingerprint"),
            semanticId = map.getValue("semanticId"),
            detail = map.getValue("detail"),
        )
    }
}
