package com.envi.wispr.ui

import android.system.Os
import android.system.OsConstants
import com.envi.wispr.history.TranscriptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** The measured state of one take's rescue write (#288). */
internal enum class RescueOutcome { KEPT, FAILED, PENDING }

/**
 * The words' last resort (#288): a write-ahead copy of each take's final text in an app-private file, kept until the
 * take's History save answers SAVED, and written into History at the next recovery when it never did. Founder
 * decision: the customer always gets their words.
 *
 * One per process, owned by the application beside the History write queue (#304), on a scope nothing cancels, so
 * settlement outlives the Service that published the words. Never the History queue, which may be the thing failing.
 * Every file operation holds [lock], and a user's delete runs its History delete and its file delete inside that lock
 * and marks the take (or bumps the clear generation), so a write still queued for a deleted take writes nothing and
 * deleted words cannot reappear. Files live under the app's private files directory; backup is disabled.
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

    /** Takes whose row the user deleted, and the Delete all count; both read and written under [lock]. */
    private val deleted = HashSet<String>()
    @Volatile private var generation = 0L

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
        record(takeId, RescueOutcome.PENDING)
        val startedIn = generation
        val write = scope.async {
            val outcome = lock.withLock {
                beforeWrite()
                // Deleted while this write was queued: the user's delete wins, and nothing is written.
                if (takeId in deleted || generation != startedIn) RescueOutcome.FAILED else write(takeId, text)
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
        }
        return write
    }

    /** Whether recovery must still leave [takeId]'s file alone: its save may yet land. */
    fun tracking(takeId: String): Boolean = takeId in tracked

    /** The rescue write's state for [takeId] now; a take never rescued in this process reads FAILED. */
    fun outcome(takeId: String): RescueOutcome = synchronized(outcomes) { outcomes[takeId] } ?: RescueOutcome.FAILED

    /** The take's words are in History: its file is no longer needed. The outcome stays, since they are not lost. */
    private suspend fun settle(takeId: String) {
        lock.withLock { File(dir, "$takeId$SUFFIX").delete() }
    }

    /** The user deletes one History row: [delete] runs inside the lock, then the take's rescue goes with it. */
    suspend fun deleting(takeId: String?, delete: suspend () -> Unit) {
        lock.withLock {
            delete()
            if (takeId != null && TAKE_ID.matches(takeId)) {
                deleted += takeId
                File(dir, "$takeId$SUFFIX").delete()
                synchronized(outcomes) { outcomes -= takeId }
            }
        }
    }

    /** The user deletes all History: [delete] runs inside the lock, then every rescue goes with it. */
    suspend fun clearing(delete: suspend () -> Unit) {
        lock.withLock {
            delete()
            generation++
            deleted.clear()
            dir.listFiles()?.forEach { it.delete() }
            synchronized(outcomes) { outcomes.clear() }
        }
    }

    /**
     * Writes every untracked rescue file into History through [repository] and deletes it only after the write
     * returned; a write that fails keeps the file for the next start. A temporary file a crash left behind holds no
     * complete record and is removed. Returns how many takes were written.
     */
    suspend fun recover(repository: TranscriptRepository): Int = lock.withLock {
        var recovered = 0
        for (file in dir.listFiles().orEmpty()) {
            if (file.name.endsWith(TEMP)) {
                file.delete()
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
     * Temp file, flushed to the disk, an atomic rename, then the directory flushed too, so the name survives a power
     * loss: KEPT is reported only after all four. A crash leaves either nothing or the whole record.
     */
    private fun write(takeId: String, text: String): RescueOutcome = try {
        dir.mkdirs()
        val temp = File(dir, "$takeId$TEMP")
        FileOutputStream(temp).use { out ->
            out.write("${wallClock()}\n$text".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (temp.renameTo(File(dir, "$takeId$SUFFIX"))) {
            syncDirectory()
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

    private fun syncDirectory() {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
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
        private const val TEMP = ".tmp"
        private val TAKE_ID = Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z")
    }
}
