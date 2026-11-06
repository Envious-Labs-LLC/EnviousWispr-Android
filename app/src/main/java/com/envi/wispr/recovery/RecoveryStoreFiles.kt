package com.envi.wispr.recovery

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption

internal enum class RecoveryLockName(val fileName: String) {
    STORE("store.lock"), MAIN_0("slot-0.main.lock"), AUDIO_0("slot-0.audio.lock"), ASR_0("slot-0.asr.lock"),
    MAIN_1("slot-1.main.lock"), AUDIO_1("slot-1.audio.lock"), ASR_1("slot-1.asr.lock"),
}

internal data class RecoveryInode(val device: Long, val number: Long)

/** Fixed recovery-only paths. The store calls these blocking operations only on its IO worker. */
internal class RecoveryStoreFiles(context: Context) {
    private val application = context.applicationContext
    private val parent by lazy { application.noBackupFilesDir }
    private val root by lazy { File(parent, "protected-recovery.v1") }
    private val ledger by lazy { File(root, "ledger.v1") }
    private val pendingLedger by lazy { File(root, "ledger.v1.pending") }
    private val quarantine by lazy { File(root, "allocation-blocked.v1") }

    /** Only true after this call atomically creates the directory. Existing incomplete roots stay unknown. */
    fun createRootIfAbsent(): Boolean = io {
        val created = try {
            Os.mkdir(root.path, 448) // 0700
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST || !OsConstants.S_ISDIR(Os.lstat(root.path).st_mode)) throw error
            false
        }
        if (created) syncDirectory(parent)
        created
    }

    /** Initializer only, before publishing the first ledger. Never called for an existing root. */
    fun createInitialLockFiles() = io {
        RecoveryLockName.entries.forEach { name ->
            RecoveryIoClose.using(FileChannel.open(lockFile(name).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { it.force(true) }
        }
        syncDirectory(root)
    }

    fun lockFile(name: RecoveryLockName): File = File(root, name.fileName)

    fun lockIdentity(name: RecoveryLockName): RecoveryInode = io {
        val stat = Os.lstat(lockFile(name).path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_size != 0L) unavailable()
        RecoveryInode(stat.st_dev, stat.st_ino)
    }

    /** Missing locks are never recreated; hard-link aliases and unexpected occupants quarantine the store. */
    fun verifyCoordinationFiles() = io {
        val identities = RecoveryLockName.entries.map(::lockIdentity)
        if (identities.toSet().size != RecoveryLockName.entries.size) unavailable()
        listOf(ledger, pendingLedger).forEach { file ->
            val stat = statOrMissing(file)
            if (stat != null && (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || RecoveryInode(stat.st_dev, stat.st_ino) in identities)) unavailable()
        }
    }

    fun hasLedger(): Boolean = io { statOrMissing(ledger) != null }

    /** Any occupant is conservative quarantine; no negative alias census can clear it. */
    fun isQuarantined(): Boolean = io { statOrMissing(quarantine) != null }

    fun quarantineAllocation() = io {
        try {
            RecoveryIoClose.using(FileChannel.open(quarantine.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { it.force(true) }
        } catch (_: FileAlreadyExistsException) {
            if (!isQuarantined()) unavailable()
        }
        syncDirectory(root)
    }

    fun hasRoot(): Boolean = io {
        val stat = statOrMissing(root)
        if (stat != null && !OsConstants.S_ISDIR(stat.st_mode)) unavailable()
        stat != null
    }

    fun writeContext(slot: Int, frame: ByteArray) = io {
        if (frame.size !in RecoveryFrameLimits.OVERHEAD_BYTES..RecoveryFrameLimits.MAX_FRAME_BYTES) unavailable()
        verifyDataFiles(slot)
        val pending = dataFile(slot, "context.pending")
        RecoveryIoClose.using(FileChannel.open(pending.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel ->
            val buffer = ByteBuffer.wrap(frame)
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) unavailable()
            channel.force(true)
        }
        Os.rename(pending.path, dataFile(slot, "context").path)
        syncDirectory(root)
    }

    /** The caller already holds this occupant's audio claim and has committed WRITING. */
    fun createAudio(slot: Int): FileChannel = io {
        verifyDataFiles(slot)
        val channel = FileChannel.open(dataFile(slot, "audio").toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        var transferred = false
        try {
            syncDirectory(root)
            transferred = true
            channel
        } finally {
            if (!transferred) RecoveryIoClose.using(channel) { }
        }
    }

    fun hasRecordFiles(slot: Int): Boolean = io {
        verifyDataFiles(slot)
        listOf("context", "context.pending", "audio").any { statOrMissing(dataFile(slot, it)) != null }
    }

    /** A file without an allocator occupant is unknown data, not an empty slot to overwrite. */
    fun verifyAllocatorLayout(value: RecoveryLedger) = io {
        val allowed = RecoveryLockName.entries.map { it.fileName }.toMutableSet()
        allowed.addAll(listOf("ledger.v1", "ledger.v1.pending", "allocation-blocked.v1"))
        repeat(RecoveryLedger.SLOT_COUNT) { slot ->
            allowed.addAll(listOf("slot-$slot.context", "slot-$slot.context.pending", "slot-$slot.audio"))
            verifyDataFiles(slot)
            if (value.slots[slot] == null && hasRecordFiles(slot)) unavailable()
        }
        val names = root.list() ?: unavailable()
        if (names.any { it !in allowed }) unavailable()
    }

    /** Caller holds all owner claims and has established the exact terminal occupant. */
    fun removeRecordFiles(slot: Int) = io {
        verifyDataFiles(slot)
        var failed = false
        listOf("context", "context.pending", "audio").forEach { suffix ->
            try {
                Files.delete(dataFile(slot, suffix).toPath())
            } catch (_: NoSuchFileException) {
                // A successful ENOENT result for this exact owned path is already removed.
            } catch (_: Exception) {
                failed = true
            }
        }
        try {
            syncDirectory(root)
        } catch (_: Exception) {
            failed = true
        }
        if (failed) unavailable()
    }

    private fun dataFile(slot: Int, suffix: String): File {
        if (slot !in 0 until RecoveryLedger.SLOT_COUNT) unavailable()
        return File(root, "slot-$slot.$suffix")
    }

    private fun verifyDataFiles(slot: Int) {
        verifyCoordinationFiles()
        val coordination = RecoveryLockName.entries.map(::lockIdentity).toSet()
        listOf("context", "context.pending", "audio").forEach { suffix ->
            val stat = statOrMissing(dataFile(slot, suffix))
            if (stat != null && (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || RecoveryInode(stat.st_dev, stat.st_ino) in coordination)) unavailable()
        }
    }

    /** Called under the actual stable metadata claim. Never substitutes an empty ledger for a failed read. */
    fun readLedger(): RecoveryLedger = io {
        verifyCoordinationFiles()
        RecoveryIoClose.using(FileChannel.open(ledger.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            val buffer = ByteBuffer.allocate(RecoveryLedgerCodec.MAX_BYTES + 1)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer)
                if (read == -1) break
                if (read == 0) unavailable()
            }
            RecoveryLedgerCodec.decode(buffer.array().copyOf(buffer.position()))
        }
    }

    /** Marker authorizations are returned only after both file and directory sync complete. */
    fun writeLedger(value: RecoveryLedger) = io {
        verifyCoordinationFiles()
        val bytes = RecoveryLedgerCodec.encode(value)
        RecoveryIoClose.using(FileChannel.open(pendingLedger.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) unavailable()
            channel.force(true)
        }
        Os.rename(pendingLedger.path, ledger.path)
        syncDirectory(root)
    }

    private fun statOrMissing(file: File) = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) unavailable()
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private inline fun <T> io(action: () -> T): T = try {
        action()
    } catch (failure: RecoveryStoreException) {
        throw RecoveryStoreException(failure.reason)
    } catch (_: Exception) {
        unavailable()
    }

    private fun unavailable(): Nothing = throw RecoveryStoreException(RecoveryStoreFailure.IO_UNAVAILABLE)
}
