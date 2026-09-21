package com.envi.wispr.telemetry

import android.content.Context
import android.util.AtomicFile
import com.envi.wispr.debug.DebugLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * The one anonymous id for this install, shared by both vendors and every process (issue #176,
 * plan §3.4). PostHog receives it through `getAnonymousId`; Sentry carries it as the
 * `analytics.distinct_id` tag; the two can be joined because they hold the same string.
 *
 * Why not the SDK's own id: PostHog runs only in the main process, and a helper process must be able
 * to tag its crash with the same id before main ever ran. Why a file and not preferences: every
 * process reads it, and a plain file under one lock is the smallest thing five processes can share.
 *
 * Election (G1 D5a): read-check-create runs under a process-local mutex AND an OS file lock on a
 * separate permanent lock file, because an atomic rename alone lets two first readers both "win" with
 * different ids. Minting happens only when the id file is genuinely absent. An unreadable, corrupt or
 * uncommittable id DISABLES telemetry for this bootstrap and never mints a replacement: a second id
 * would split one person into two, and PostHog's own persisted id would no longer match the file.
 *
 * Identity is not the privacy boundary (founder 2026-09-15); content is. This value never identifies
 * a person, only an install, and it is never sent anywhere but the two vendors.
 */
internal object InstallIdentity {

    sealed class Resolution {
        /** [minted] is true only in the process that created the file: the install's first run. */
        data class Available(val id: String, val minted: Boolean = false) : Resolution()

        /** Storage refused, or the file is corrupt: telemetry stays off for this bootstrap. */
        data class Unavailable(val why: String) : Resolution()
    }

    private const val TAG = "InstallIdentity"
    private const val DIR = "telemetry"
    private const val ID_FILE = "install-id"
    private const val LOCK_FILE = "install-id.lock"
    private val processLock = Any()

    @Volatile private var cached: Resolution? = null

    /** Resolves once per process; the result is stable for the process's lifetime. */
    fun resolve(context: Context): Resolution {
        cached?.let { return it }
        synchronized(processLock) {
            cached?.let { return it }
            val resolved = runCatching { readOrMint(context.applicationContext) }
                .getOrElse { Resolution.Unavailable(it.javaClass.simpleName) }
            cached = resolved
            if (resolved is Resolution.Unavailable) DebugLogger.warn(TAG, "Install id unavailable: ${resolved.why}")
            return resolved
        }
    }

    private fun readOrMint(context: Context): Resolution {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return Resolution.Unavailable("no telemetry directory")
        val idFile = File(dir, ID_FILE)
        val atomic = AtomicFile(idFile)
        RandomAccessFile(File(dir, LOCK_FILE), "rw").use { lockFile ->
            // The OS lock serialises the five processes; the mutex above serialises threads within one.
            lockFile.channel.lock().use {
                if (idFile.exists()) {
                    val text = runCatching { String(atomic.readFully(), Charsets.UTF_8).trim() }.getOrNull()
                        ?: return Resolution.Unavailable("unreadable id file")
                    val canonical = canonical(text) ?: return Resolution.Unavailable("corrupt id file")
                    return Resolution.Available(canonical)
                }
                val minted = UUID.randomUUID().toString().lowercase()
                val stream = runCatching { atomic.startWrite() }.getOrNull()
                    ?: return Resolution.Unavailable("cannot open id file for writing")
                val committed = runCatching {
                    stream.write(minted.toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                    true
                }.getOrElse {
                    runCatching { atomic.failWrite(stream) }
                    false
                }
                return if (committed) Resolution.Available(minted, minted = true) else Resolution.Unavailable("id file write failed")
            }
        }
    }

    /**
     * Accepts exactly a canonical hyphenated lowercase UUID and returns it VERBATIM: the value must equal
     * what PostHog stores. Anything else is corrupt, never normalised (the Mac's `canonicalAnonymousPostHogID`).
     */
    fun canonical(raw: String): String? {
        val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return if (parsed.toString() == raw) raw else null
    }
}
