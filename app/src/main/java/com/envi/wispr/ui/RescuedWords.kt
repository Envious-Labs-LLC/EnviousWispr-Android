package com.envi.wispr.ui

import android.system.Os
import android.system.OsConstants
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.history.RecoveredWordsRecord
import com.envi.wispr.history.RecoverySaveOutcome
import com.envi.wispr.history.RecoveryWordsCodec
import com.envi.wispr.recovery.RecoveryTextLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/** The measured state of one take's rescue write (#288). */
internal enum class RescueOutcome { KEPT, FAILED, PENDING }

/**
 * The words' last resort (#288): a write-ahead copy of each take's final text in an app-private file, kept until the
 * take's History save answers SAVED, and written into History at the next recovery when it never did. Founder
 * decision: the customer always gets their words.
 *
 * One per process, owned by the application beside the History write queue (#304), on a scope nothing cancels, so
 * settlement outlives the Service that published the words. Never the History queue, which may be the thing failing.
 * Every file operation holds [lock]. The database decides what the user deleted: a History delete marks the take in
 * the same transaction (`DeletedTake`), and the take's own late save and every recovery insert only in a transaction
 * that reads that mark, so deleted words never come back and a delete that fails, or is cancelled, leaves the words
 * where they were. The History delete runs outside [lock], so a stalled database never holds a rescue write; once it
 * has committed, the take's files go under the lock, and a write still queued for it writes nothing. A file a crash
 * left behind for a deleted take is removed by the next recovery. Files live under the app's private files directory;
 * backup is disabled.
 *
 * A take is TRACKED from its write until its save answers or [trackingBoundMs] passes, whichever is first: recovery
 * never takes a tracked take's file, since its save may still land. A SAVED answer that comes later still settles the
 * file, through the slot's own completion, with no waiting coroutine left behind.
 */
internal class RescuedWords(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Wall time, for the History row's creation time. */
    private val wallClock: () -> Long,
    private val warn: (String) -> Unit,
    private val trackingBoundMs: Long = HistorySaveObserver.HISTORY_SAVE_BOUND_MS,
    /** Runs before each write, inside the lock; production passes nothing. The rig slows a write to show the owner waits. */
    private val beforeWrite: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val tracked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The most recent takes' outcomes, for the fallback line; bounded, since a line is spoken within a take's life. */
    private val outcomes = object : LinkedHashMap<String, RescueOutcome>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RescueOutcome>?): Boolean = size > OUTCOMES_KEPT
    }

    /** Takes whose History delete has COMMITTED in this process: a write still queued for one writes nothing. */
    private val deleted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Takes whose words are on their way: from [keep] until both the save has answered and the write has finished,
     * however long that takes. Delete all marks these with the rows, since a take here may have no row yet.
     */
    private val live: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun record(takeId: String, outcome: RescueOutcome) = synchronized(outcomes) { outcomes[takeId] = outcome }

    /**
     * Writes [text] ahead of delivery for [takeId] and settles it on [save]'s SAVED answer, after the write, so a late
     * write cannot recreate a settled record. Returns the write's outcome; never blocks.
     */
    fun keep(takeId: String, text: String, save: SaveSlot): Deferred<RescueOutcome> {
        if (!TAKE_ID.matches(takeId)) {
            warn("Rescue refused an unexpected take id shape")
            return scope.async { RescueOutcome.FAILED }
        }
        tracked += takeId
        live += takeId
        record(takeId, RescueOutcome.PENDING)
        val write = scope.async {
            val outcome = lock.withLock {
                beforeWrite()
                // Deleted while this write was queued: the user's delete wins, and nothing is written.
                if (takeId in deleted) RescueOutcome.FAILED else write(takeId, text)
            }
            record(takeId, outcome)
            outcome
        }
        save.onAnswered { answer ->
            if (answer.outcome is SaveOutcome.Saved) scope.launch { write.await(); settle(takeId) }
        }
        scope.launch {
            withTimeoutOrNull(trackingBoundMs) { save.await() }
            write.await()
            tracked -= takeId
            save.await()
            live -= takeId
        }
        return write
    }

    /** Produced recovery text keeps its provenance through the existing write-ahead and settlement owner. */
    fun keepRecovered(record: RecoveredWordsRecord, save: Deferred<RecoverySaveOutcome>): Deferred<RescueOutcome> {
        val takeId = record.transcript.takeId
        if (takeId == null || !TAKE_ID.matches(takeId)) return scope.async { RescueOutcome.FAILED }
        tracked += takeId
        live += takeId
        record(takeId, RescueOutcome.PENDING)
        val write = scope.async {
            val outcome = lock.withLock {
                beforeWrite()
                if (takeId in deleted) RescueOutcome.FAILED else writeRecovered(takeId, record)
            }
            record(takeId, outcome)
            outcome
        }
        scope.launch {
            val answer = runCatching { save.await() }.getOrNull()
            write.await()
            when (answer) {
                is RecoverySaveOutcome.Saved, is RecoverySaveOutcome.PreservedExisting -> settle(takeId)
                RecoverySaveOutcome.Deleted -> forget(listOf(takeId))
                is RecoverySaveOutcome.Failed, null -> Unit
            }
        }
        scope.launch {
            runCatching { withTimeoutOrNull(trackingBoundMs) { save.await() } }
            write.await()
            tracked -= takeId
            runCatching { save.await() }
            live -= takeId
        }
        return write
    }

    /** Absence is accepted only from an actual ENOENT answer. Pending/unknown text must defer audio replay. */
    suspend fun hasPendingOrStoredWords(take: UUID): Boolean = lock.withLock {
        val takeId = take.toString()
        if (takeId in live) return@withLock true
        listOf(SUFFIX, RECOVERED_SUFFIX).any { suffix ->
            try {
                Files.readAttributes(File(dir, "$takeId$suffix").toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                true
            } catch (_: NoSuchFileException) { false }
        }
    }

    /** Whether recovery must still leave [takeId]'s file alone: its save may yet land. */
    fun tracking(takeId: String): Boolean = takeId in tracked

    /** The rescue write's state for [takeId] now; a take never rescued in this process reads FAILED. */
    fun outcome(takeId: String): RescueOutcome = synchronized(outcomes) { outcomes[takeId] } ?: RescueOutcome.FAILED

    /** The take's words are in History: its file is no longer needed. The outcome stays, since they are not lost. */
    private suspend fun settle(takeId: String) {
        lock.withLock { deleteWords(takeId) }
    }

    /**
     * The user deletes one History row: [delete] runs outside the lock and marks the take in its own transaction; once
     * it has committed, the take's rescue goes too. A failed or cancelled delete leaves everything as it was.
     */
    suspend fun deleting(takeId: String?, delete: suspend () -> Unit) {
        delete()
        val take = takeId?.takeIf { TAKE_ID.matches(it) } ?: return
        forget(listOf(take))
    }

    /**
     * The user deletes all History: every take whose words are here or on their way when the delete begins is handed
     * to [delete], which marks them with every row's take in its own transaction; once it has committed, their
     * rescues go too. A take that begins after this point keeps its words. A failed delete leaves everything as it was.
     */
    suspend fun clearing(delete: suspend (liveTakes: Set<String>) -> Unit) = clearing(emptySet(), delete)

    /** The caller's verified protected/pending identities join this same committed deletion snapshot. */
    suspend fun clearing(protectedTakes: Set<UUID>, delete: suspend (liveTakes: Set<String>) -> Unit) {
        val takes = lock.withLock { live + files().mapNotNull(::fileTakeId) + protectedTakes.map(UUID::toString) }
        delete(takes)
        forget(takes)
    }

    /** After a committed delete: the takes' files go, and a write still queued for one writes nothing. */
    private suspend fun forget(takes: Collection<String>) = lock.withLock {
        deleted += takes
        for (take in takes) deleteWords(take)
        synchronized(outcomes) { outcomes -= takes.toSet() }
    }

    private fun files() = directoryFiles().filter { fileTakeId(it) != null }

    private fun directoryFiles(): List<File> = try {
        Files.newDirectoryStream(dir.toPath()).use { entries -> entries.map { it.toFile() } }
    } catch (_: NoSuchFileException) { emptyList() }

    private fun fileTakeId(file: File): String? {
        val take = when {
            file.name.endsWith(RECOVERED_SUFFIX) -> file.name.removeSuffix(RECOVERED_SUFFIX)
            file.name.endsWith(SUFFIX) -> file.name.removeSuffix(SUFFIX)
            else -> return null
        }
        return take.takeIf { TAKE_ID.matches(it) }
    }

    private fun deleteWords(takeId: String) {
        File(dir, "$takeId$SUFFIX").delete()
        File(dir, "$takeId$RECOVERED_SUFFIX").delete()
    }

    /**
     * Writes every untracked rescue file into History through [repository] and deletes it only after the write
     * returned; a write that fails keeps the file for the next start. A temporary file a crash left behind holds no
     * complete record and is removed. Returns how many takes were written.
     */
    suspend fun recover(repository: TranscriptRepository): Int = lock.withLock {
        var recovered = 0
        for (file in directoryFiles().sortedBy { if (it.name.endsWith(SUFFIX)) 0 else 1 }) {
            if (file.name.endsWith(TEMP)) {
                file.delete()
                continue
            }
            if (file.name.endsWith(RECOVERED_SUFFIX)) {
                val takeId = fileTakeId(file) ?: continue
                if (takeId in tracked) continue
                val attempt = runCatching {
                    val bytes = FileInputStream(file).use { input ->
                        val size = input.channel.size()
                        if (size !in 1L..RecoveryTextLimits.WORDS_BYTES.toLong()) throw IOException("Recovery text unavailable")
                        ByteArray(size.toInt()).also { bytes ->
                            DataInputStream(input).readFully(bytes)
                            if (input.read() != -1) throw IOException("Recovery text unavailable")
                        }
                    }
                    repository.saveRecovered(RecoveryWordsCodec.decode(bytes, takeId).transcript)
                }
                val failed = attempt.exceptionOrNull()
                if (failed is kotlinx.coroutines.CancellationException) throw failed
                val result = attempt.getOrNull()
                when (result) {
                    is RecoverySaveOutcome.Saved, is RecoverySaveOutcome.PreservedExisting -> {
                        file.delete(); record(takeId, RescueOutcome.KEPT); recovered++
                    }
                    RecoverySaveOutcome.Deleted -> { file.delete(); deleted += takeId }
                    is RecoverySaveOutcome.Failed, null -> Unit
                }
                continue
            }
            val takeId = file.name.removeSuffix(SUFFIX)
            if (!file.name.endsWith(SUFFIX) || !TAKE_ID.matches(takeId) || takeId in tracked) continue
            val record = runCatching { file.readText() }.getOrNull() ?: continue
            val createdAtMs = record.substringBefore('\n').toLongOrNull() ?: continue
            val text = record.substringAfter('\n')
            val written = try {
                repository.keepRescuedWords(takeId, text, createdAtMs)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                warn("Rescued words not written to History: ${error.javaClass.simpleName}")
                false
            }
            // Written, or the user deleted the take (a crash came between the delete and its file's removal).
            if (written) {
                file.delete()
                // In History now: a line still to be spoken for this take must not call the words lost.
                record(takeId, RescueOutcome.KEPT)
                recovered++
            }
        }
        recovered
    }

    /**
     * Temp file, flushed to the disk, an atomic rename, then the directory flushed too (and its parent when this write
     * created it), so the name survives a power loss: KEPT is reported only after all of them. A crash leaves either
     * nothing or the whole record.
     */
    private fun write(takeId: String, text: String): RescueOutcome = try {
        // A directory this write creates is itself a name in its parent: flushed too, or a power loss can drop it.
        if (!dir.isDirectory && dir.mkdirs()) syncDirectory(dir.parentFile ?: dir)
        val temp = File(dir, "$takeId$TEMP")
        FileOutputStream(temp).use { out ->
            out.write("${wallClock()}\n$text".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (temp.renameTo(File(dir, "$takeId$SUFFIX"))) {
            syncDirectory(dir)
            RescueOutcome.KEPT
        } else {
            temp.delete()
            RescueOutcome.FAILED
        }
    } catch (error: Exception) {
        // The type only: the words never enter a log (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
        warn("Rescue write failed: ${error.javaClass.simpleName}")
        RescueOutcome.FAILED
    }

    private fun writeRecovered(takeId: String, record: RecoveredWordsRecord): RescueOutcome = try {
        val bytes = RecoveryWordsCodec.encode(record)
        if (!dir.isDirectory && dir.mkdirs()) syncDirectory(dir.parentFile ?: dir)
        val temp = File(dir, "$takeId$RECOVERED_SUFFIX$TEMP")
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        if (temp.renameTo(File(dir, "$takeId$RECOVERED_SUFFIX"))) {
            syncDirectory(dir)
            RescueOutcome.KEPT
        } else { temp.delete(); RescueOutcome.FAILED }
    } catch (_: Throwable) {
        warn("Recovery text rescue unavailable")
        RescueOutcome.FAILED
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    companion object {
        /** How long the owner waits for the rescue write before the handoff (#288): the words are the heart. */
        const val RESCUE_WRITE_BOUND_MS = 250L
        private const val OUTCOMES_KEPT = 64
        private const val SUFFIX = ".words"
        private const val RECOVERED_SUFFIX = ".recovery-words-v1"
        private const val TEMP = ".tmp"
        private val TAKE_ID = Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z")
    }
}
