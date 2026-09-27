package com.envi.wispr.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * The adb door (#378 D10): how Claude pulls the local log and sets the Developer switches from the Mac, with
 * no tap on the phone. Exported, with the manifest's read and write permission `android.permission.DUMP`,
 * AND a check on every entry that the caller is the adb shell's user id: DUMP can be granted to another app
 * through adb, so the permission alone is not the boundary. A provider's `call` is not covered by its
 * read or write permission at all, so every `call` checks the caller itself.
 *
 * From the Mac:
 * `adb exec-out content read --uri content://com.envi.wispr.devlog/log.zip > log.zip`
 * `adb shell content call --uri content://com.envi.wispr.devlog --method setDetailedLog --arg true`
 *
 * It holds no state and writes nothing of its own: it reads the ZIP the log worker builds and requests the
 * switches through `DeveloperSwitches`, exactly as the Developer page does.
 */
internal class DeveloperLogProvider : ContentProvider() {

    /** No waiting here: the provider is published before `Application.onCreate` runs. */
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireShellAndDump()
        if (mode != "r") throw SecurityException("read only")
        val app = context ?: throw FileNotFoundException("no context")
        awaitReady(app)
        return when (uri.lastPathSegment) {
            LOG_ZIP -> {
                val logs = DeveloperLogs.of(app)
                val pending = logs.doorZip()
                val built = runCatching { pending.get(DOOR_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
                    logs.cleanupDoorFiles(pending)
                    throw FileNotFoundException("The log could not be built in time: ${it.javaClass.simpleName}")
                }
                val descriptor = ParcelFileDescriptor.open(built.zip, ParcelFileDescriptor.MODE_READ_ONLY)
                // The descriptor keeps the bytes; the path goes now, so no door file outlives its read.
                built.zip.delete()
                descriptor
            }
            else -> throw FileNotFoundException("unknown path")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireShellAndDump()
        val app = context ?: throw IllegalStateException("no context")
        awaitReady(app)
        val switches = DeveloperSwitches.of(app)
        val result = when (method) {
            STATUS -> null
            SET_DETAILED_LOG -> switches.requestDetailedLog(parseOn(arg))
            SET_KEEP_RECORDINGS -> switches.requestKeepRecordings(parseOn(arg))
            else -> throw IllegalArgumentException("unknown method")
        }
        // A binder thread, never the UI thread: waiting here holds only this caller (`kotlin-patterns.md`
        // RULE: never-block-a-binder-or-ui-thread allows the synchronous edge a vendor API forces; `content
        // call` is one).
        val settled = result?.let { runBlocking { withTimeoutOrNull(DOOR_TIMEOUT_MS) { it.await() } } }
        val state = switches.state.value
        return Bundle().apply {
            putString("detailedLog", state.detailedLog.toString())
            putString("keepRecordings", state.keepRecordings.toString())
            if (result != null) putString("result", settled?.toString() ?: "Pending")
        }
    }

    /**
     * Every entry, first: the caller holds DUMP (the manifest gate, checked here too because `call` and `getType`
     * are not covered by it) AND is the adb shell's user id (DUMP can be granted to another app through adb).
     */
    private fun requireShellAndDump() {
        val app = context ?: throw SecurityException("no context")
        if (app.checkCallingPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("DUMP required")
        }
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("adb shell only")
    }

    /** Cold start: wait, bounded, for the switch repair `Application.onCreate` queued. */
    private fun awaitReady(app: android.content.Context) {
        val ready = runBlocking { withTimeoutOrNull(DOOR_TIMEOUT_MS) { DeveloperSwitches.of(app).ready.await() } }
        if (ready == null) throw IllegalStateException("Pending: the switches are still starting")
    }

    private fun parseOn(arg: String?): Boolean = when (arg) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("arg must be true or false")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        requireShellAndDump()
        return null
    }

    override fun getType(uri: Uri): String? {
        requireShellAndDump()
        return if (uri.lastPathSegment == LOG_ZIP) "application/zip" else null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("read only")

    companion object {
        const val LOG_ZIP = "log.zip"
        const val STATUS = "status"
        const val SET_DETAILED_LOG = "setDetailedLog"
        const val SET_KEEP_RECORDINGS = "setKeepRecordings"
        private const val DOOR_TIMEOUT_MS = 30_000L
    }
}
