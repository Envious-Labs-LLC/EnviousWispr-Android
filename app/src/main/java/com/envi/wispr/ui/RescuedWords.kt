package com.envi.wispr.ui

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
 * Every file operation (write, settle, forget, clear, recover) holds [lock], so a delete cannot race a pending write
 * and deleted words cannot reappear. Files live under the app's private files directory; backup is disabled.
 *
 * A take is TRACKED from its write until its save answers or [trackingBoundMs] passes, whichever is first: recovery
 * never takes a tracked take's file, since its save may still land. After that a still-present file is an unsettled
 * rescue, and the next recovery in this process or the next takes it.
 */
internal class RescuedWords(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Wall time, for the History row's creation time. */
    private val wallClock: () -> Long,
    private val warn: (String) -> Unit,
    private val trackingBoundMs: Long = HistorySaveObserver.HISTORY_SAVE_BOUND_MS,
    /** Runs before each write; production passes nothing. The rig slows a write with it to show the owner waits. */
    private val beforeWrite: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val outcomes = ConcurrentHashMap<String, RescueOutcome>()
    private val tracked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Writes [text] ahead of delivery for [takeId], then watches [save]: a SAVED answer deletes the file once the write
     * has completed, so a late write cannot recreate a settled record. Returns the write's outcome; never blocks.
     */
    fun keep(takeId: String, text: String, save: SaveSlot): Deferred<RescueOutcome> {
        if (!TAKE_ID.matches(takeId)) {
            warn("Rescue refused an unexpected take id shape")
            return scope.async { RescueOutcome.FAILED }
        }
        tracked += takeId
        outcomes[takeId] = RescueOutcome.PENDING
        val write = scope.async {
            beforeWrite()
            val outcome = lock.withLock { write(takeId, text) }
            outcomes[takeId] = outcome
            outcome
        }
        scope.launch {
            val early = withTimeoutOrNull(trackingBoundMs) { save.await() }
            write.await()
            tracked -= takeId
            val answer = early ?: save.await()
            if (answer.outcome is SaveOutcome.Saved) forget(takeId)
        }
        return write
    }

    /** Whether recovery must still leave [takeId]'s file alone: its save may yet land. */
    fun tracking(takeId: String): Boolean = takeId in tracked

    /** The rescue write's state for [takeId] now; a take never rescued in this process reads FAILED. */
    fun outcome(takeId: String): RescueOutcome = outcomes[takeId] ?: RescueOutcome.FAILED

    /** Deletes [takeId]'s file: its words are in History, or the user deleted its row. */
    suspend fun forget(takeId: String) {
        if (!TAKE_ID.matches(takeId)) return
        lock.withLock {
            File(dir, "$takeId$SUFFIX").delete()
            outcomes -= takeId
        }
    }

    /** Deletes every rescue file: the user deleted all History. */
    suspend fun clear() {
        lock.withLock {
            dir.listFiles()?.forEach { it.delete() }
            outcomes.clear()
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
                outcomes -= takeId
                recovered++
            }
        }
        recovered
    }

    /** Temp file, flushed to the disk, then an atomic rename: a crash leaves either nothing or the whole record. */
    private fun write(takeId: String, text: String): RescueOutcome = try {
        dir.mkdirs()
        val temp = File(dir, "$takeId$TEMP")
        FileOutputStream(temp).use { out ->
            out.write("${wallClock()}\n$text".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (temp.renameTo(File(dir, "$takeId$SUFFIX"))) RescueOutcome.KEPT else RescueOutcome.FAILED.also { temp.delete() }
    } catch (error: Exception) {
        // The type only: the words never enter a log (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
        warn("Rescue write failed: ${error.javaClass.simpleName}")
        RescueOutcome.FAILED
    }

    companion object {
        /** How long the owner waits for the rescue write before the handoff (#288): the words are the heart. */
        const val RESCUE_WRITE_BOUND_MS = 250L
        private const val SUFFIX = ".words"
        private const val TEMP = ".tmp"
        private val TAKE_ID = Regex("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z")
    }
}
