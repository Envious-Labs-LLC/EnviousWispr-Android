package com.envi.wispr.recovery

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Semaphore

internal sealed interface RecoveryClaimResult {
    data class Acquired(val claim: RecoveryFileClaim) : RecoveryClaimResult
    data object Busy : RecoveryClaimResult
    data class Unavailable(val reason: RecoveryStoreFailure) : RecoveryClaimResult
}

internal enum class RecoveryClaimRelease { RELEASED, UNCERTAIN }

/** One exclusive local lease. One channel per inode; unknown closure is never a released claim. */
internal class RecoveryFileClaim private constructor(private val path: String, private val entry: Entry) {
    /** Retain the kernel claim when another owned IO closer could not be confirmed. */
    fun poisonOnWorker() = synchronized(monitor) {
        if (entries[path] === entry && entry.released == null) entry.poisoned = true
    }

    fun matches(files: RecoveryStoreFiles, name: RecoveryLockName): Boolean = synchronized(monitor) {
        path == files.lockFile(name).absolutePath && entries[path] === entry && !entry.poisoned &&
            entry.released == null && entry.lock?.isValid == true && files.lockIdentity(name) == entry.inode
    }

    /** Call only after every operation using this lease has actually exited, on its owning worker. */
    fun release(): RecoveryClaimRelease = synchronized(monitor) {
        entry.released?.let { return@synchronized it }
        if (entries[path] !== entry) return@synchronized RecoveryClaimRelease.UNCERTAIN
        if (entry.poisoned) {
            entry.released = RecoveryClaimRelease.UNCERTAIN
            if (entry.metadataPermit) metadata.release()
            return@synchronized RecoveryClaimRelease.UNCERTAIN
        }
        var failed = false
        try { entry.lock?.release() } catch (_: Throwable) { failed = true }
        try { entry.channel.close() } catch (_: Throwable) { failed = true }
        val outcome = if (failed) RecoveryClaimRelease.UNCERTAIN else RecoveryClaimRelease.RELEASED
        entry.released = outcome
        if (failed) entry.poisoned = true else entries.remove(path)
        if (entry.metadataPermit) metadata.release()
        outcome
    }

    private class Entry(
        val inode: RecoveryInode,
        val channel: FileChannel,
        val metadataPermit: Boolean,
        var lock: FileLock? = null,
        var poisoned: Boolean = false,
    ) {
        var released: RecoveryClaimRelease? = null
    }

    companion object {
        private val monitor = Any()
        private val entries = HashMap<String, Entry>()
        private val knownInodes = HashMap<String, RecoveryInode>()
        private val metadata = Semaphore(1)

        /** All owner acquisition is nonblocking. Ordinary metadata operations also defer on contention. */
        fun tryAcquire(files: RecoveryStoreFiles, name: RecoveryLockName): RecoveryClaimResult = acquire(files, name, false)

        /**
         * Only an already-owned key operation's completion receipt may wait for metadata on its worker.
         * No provider call happens while held, and kernel waiting does not hold the channel registry monitor.
         */
        fun acquireMetadataForSettlement(files: RecoveryStoreFiles): RecoveryClaimResult = acquire(files, RecoveryLockName.STORE, true)

        private fun acquire(files: RecoveryStoreFiles, name: RecoveryLockName, settle: Boolean): RecoveryClaimResult {
            val ownsMetadata = name == RecoveryLockName.STORE
            if (ownsMetadata) {
                if (settle) {
                    try { metadata.acquire() } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return unavailable(RecoveryStoreFailure.IO_UNAVAILABLE)
                    }
                } else if (!metadata.tryAcquire()) return RecoveryClaimResult.Busy
            }
            var transferred = false
            try {
                val path = files.lockFile(name).absolutePath
                val opened = synchronized(monitor) {
                    entries[path]?.let { return if (it.poisoned) uncertain() else RecoveryClaimResult.Busy }
                    val inode = try { files.lockIdentity(name) } catch (error: RecoveryStoreException) { return unavailable(error.reason) }
                    val previous = knownInodes[path]
                    if (previous != null && previous != inode || entries.values.any { it.inode == inode }) return uncertain()
                    knownInodes[path] = inode
                    val channel = try {
                        FileChannel.open(files.lockFile(name).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) { return unavailable(RecoveryStoreFailure.IO_UNAVAILABLE) }
                    val entry = Entry(inode, channel, ownsMetadata)
                    entries[path] = entry
                    try {
                        if (files.lockIdentity(name) != inode) { entry.poisoned = true; return uncertain() }
                    } catch (_: Exception) { entry.poisoned = true; return uncertain() }
                    entry
                }
                val lock = try {
                    if (settle) opened.channel.lock() else opened.channel.tryLock()
                } catch (_: Exception) {
                    // Closing an unexpectedly overlapping channel could release an untracked local
                    // lease. Keep this channel quarantined, rather than guessing it acquired nothing.
                    synchronized(monitor) { opened.poisoned = true }
                    return uncertain()
                }
                if (lock == null) {
                    val closed = synchronized(monitor) {
                        try { opened.channel.close(); entries.remove(path); true }
                        catch (_: Exception) { opened.poisoned = true; false }
                    }
                    return if (closed) RecoveryClaimResult.Busy else uncertain()
                }
                synchronized(monitor) { opened.lock = lock }
                transferred = true
                return RecoveryClaimResult.Acquired(RecoveryFileClaim(path, opened))
            } finally {
                if (ownsMetadata && !transferred) metadata.release()
            }
        }

        private fun unavailable(reason: RecoveryStoreFailure) = RecoveryClaimResult.Unavailable(reason)
        private fun uncertain() = unavailable(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
    }
}
