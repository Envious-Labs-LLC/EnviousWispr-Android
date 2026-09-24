package com.envi.wispr.ui

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#288): the words' last resort, driven alone. A take's final text is on the phone before delivery,
 * leaves only when History answers SAVED, and is written into History by the next recovery otherwise, once. Each row
 * waits on a condition the store makes true, never on a sleep it assumes, and names the mutation that turns it red.
 */
class RescuedWordsTest {
    private val dir: File = Files.createTempDirectory("rescued-words").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val store = RescuedWords(dir, scope, wallClock = { 1_000L }, warn = { warnings += it }, trackingBoundMs = 5_000L)
    private val dao = DictationSessionRig.FakeTranscriptDao()
    private val repository = TranscriptRepository(dao, clock = { 2_000L })
    private val take = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun file() = File(dir, "$take.words")

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    /** Row 1: the words are written ahead, whole, with their time. MUTATION m1: no write-ahead. */
    @Test fun theWordsAreOnThePhoneBeforeAnyAnswer() = runBlocking {
        val outcome = store.keep(take, "Keep these words.\nAnd this line.", SaveSlot()).await()
        assertEquals(RescueOutcome.KEPT, outcome)
        assertEquals(RescueOutcome.KEPT, store.outcome(take))
        assertEquals("1000\nKeep these words.\nAnd this line.", file().readText())
        assertFalse("no temporary file is left", File(dir, "$take.tmp").exists())
    }

    /** Row 2: a SAVED answer deletes the words, after the write. MUTATION m3: settle before the SAVED answer. */
    @Test fun aSavedAnswerDeletesTheWordsAndNothingElseDoes() = runBlocking {
        val quick = RescuedWords(dir, scope, wallClock = { 1_000L }, warn = { warnings += it }, trackingBoundMs = 50L)
        val save = SaveSlot()
        quick.keep(take, "Keep these words.", save).await()
        // Past the tracking bound with no answer: the watcher has passed the write and is waiting only on the save.
        awaitUntil("the take is no longer tracked") { !quick.tracking(take) }
        assertTrue("nothing but a SAVED answer removes them", file().exists())
        save.answer(SaveOutcome.Saved(7L)) { 0L }
        awaitUntil("the settled file is gone") { !file().exists() }
    }

    /** Row 3: a FAILED answer keeps the words, and the next recovery writes them into History once. MUTATION m4. */
    @Test fun aFailedSaveIsRecoveredIntoHistoryOnceAndThenDeleted() = runBlocking {
        val save = SaveSlot()
        store.keep(take, "Keep these words.", save).await()
        assertEquals("a tracked take is never recovered", 0, store.recover(repository))
        assertTrue(file().exists())
        assertTrue(store.tracking(take))
        save.answer(SaveOutcome.Failed(IllegalStateException("disk full"))) { 0L }
        awaitUntil("the take is recovered once its save failed") { runBlocking { store.recover(repository) } == 1 }
        val row = dao.rows.values.single()
        assertEquals("Keep these words.", row.finalText)
        assertEquals(take, row.takeId)
        assertEquals(1_000L, row.createdAtMs)
        assertEquals(TranscriptEntity.STATUS_INSERTION_INTERRUPTED, row.status)
        assertFalse("deleted once History holds them", file().exists())
        assertEquals("a second recovery writes nothing", 0, store.recover(repository))
        assertEquals(1, dao.rows.size)
    }

    /** Row 4: a recovery whose History write fails keeps the file for the next start. MUTATION m4: delete before the write. */
    @Test fun aFailedRecoveryWriteKeepsTheWords() = runBlocking {
        val save = SaveSlot()
        store.keep(take, "Keep these words.", save).await()
        save.answer(SaveOutcome.Failed(IllegalStateException("disk full"))) { 0L }
        dao.failInserts = true
        awaitUntil("the take is untracked") { runBlocking { store.recover(repository) }; warnings.any { it.startsWith("Rescued words not written to History") } }
        assertTrue(file().exists())
        assertTrue("never the words in a log", warnings.none { it.contains("Keep these words") })
        dao.failInserts = false
        assertEquals(1, store.recover(repository))
        assertFalse(file().exists())
    }

    /** Row 5: a row the take already wrote is completed when blank, and left when it holds words; never a second row. */
    @Test fun aRowTheTakeWroteIsCompletedOrLeftNeverDuplicated() = runBlocking {
        dao.rows[1L] = TranscriptEntity(id = 1L, originalText = "", finalText = "", createdAtMs = 5L, durationMs = 0L, speechEngine = "Parakeet", polishEngine = "", polishLatencyMs = 0L, insertionResult = "pending", status = TranscriptEntity.STATUS_DRAFT, takeId = take)
        File(dir.apply { mkdirs() }, "$take.words").writeText("1000\nKeep these words.")
        assertEquals(1, store.recover(repository))
        assertEquals("Keep these words.", dao.rows.getValue(1L).finalText)
        assertEquals(1, dao.rows.size)
        File(dir, "$take.words").writeText("1000\nOther words.")
        assertEquals(1, store.recover(repository))
        assertEquals("the saved words are left", "Keep these words.", dao.rows.getValue(1L).finalText)
        assertEquals(1, dao.rows.size)
    }

    /** Row 6: a crash between the write and the rename leaves no record, and recovery removes the half file. */
    @Test fun aHalfWrittenFileIsNeverRead() = runBlocking {
        File(dir.apply { mkdirs() }, "$take.tmp").writeText("1000\nKeep th")
        assertEquals(0, store.recover(repository))
        assertEquals(0, dao.rows.size)
        assertFalse(File(dir, "$take.tmp").exists())
    }

    /** Row 7: deleting a row or all History deletes the words too. MUTATION m6: Delete all leaves the files. */
    @Test fun deletedWordsStayDeleted() = runBlocking {
        val deletes = mutableListOf<String>()
        store.keep(take, "Keep these words.", SaveSlot()).await()
        store.deleting(take) { deletes += "row" }
        assertFalse(file().exists())
        val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        store.keep(other, "Other words.", SaveSlot()).await()
        store.clearing { deletes += "all" }
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
        assertEquals("each History delete ran inside the store's delete", listOf("row", "all"), deletes)
    }

    /**
     * Row 7b (review round 1): a write still queued when the user deletes writes nothing, whether the row or all
     * History was deleted. One thread runs the store, so the lock queues its waiters in the order they are started.
     * MUTATION m9: the write ignores the delete.
     */
    @Test fun aWriteQueuedBehindADeleteWritesNothing() = runBlocking {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val one = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = RescuedWords(dir, one, wallClock = { 1_000L }, warn = { warnings += it }, trackingBoundMs = 5_000L, beforeWrite = { gate.await() })
        val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        // The first write holds the lock at its gate; everything after queues behind it, in this order.
        val holder = slow.keep("2a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d", "Held.", SaveSlot())
        val deleting = one.async { slow.deleting(take) {} }
        val queued = slow.keep(take, "Keep these words.", SaveSlot())
        val clearing = one.async { slow.clearing {} }
        val otherWrite = slow.keep(other, "Other words.", SaveSlot())
        gate.complete(Unit)
        holder.await(); deleting.await(); clearing.await()
        assertEquals("the row was deleted before its write ran", RescueOutcome.FAILED, queued.await())
        assertEquals("all History was deleted after this take began", RescueOutcome.FAILED, otherWrite.await())
        assertFalse(file().exists())
        assertFalse(File(dir, "$other.words").exists())
        one.cancel()
        executor.shutdown()
    }

    /**
     * Row 7c (review round 2): a History delete that stalls holds no other take's rescue write back, and the take being
     * deleted is not recovered meanwhile. MUTATION m13: the History delete runs inside the store's lock.
     */
    @Test fun aStalledDeleteNeverHoldsAnotherTakesWrite() = runBlocking {
        val other = "1a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"
        File(dir.apply { mkdirs() }, "$other.words").writeText("1000\nOther words.")
        val stalled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val deleting = scope.async { store.deleting(other) { stalled.await() } }
        awaitUntil("the delete is pending") { store.deletedByUser(other) }
        val written = kotlinx.coroutines.withTimeoutOrNull(5_000L) { store.keep(take, "Keep these words.", SaveSlot()).await() }
        assertEquals("another take's write lands while the delete is stalled", RescueOutcome.KEPT, written)
        // The written take is still tracked and the other is being deleted: a recovery now writes neither.
        assertEquals("the take being deleted is not recovered", 0, store.recover(repository))
        assertEquals(0, dao.rows.size)
        stalled.complete(Unit)
        deleting.await()
        assertFalse(File(dir, "$other.words").exists())
    }

    /** Row 8: a take id that is not a UUID never names a file. */
    @Test fun anUnexpectedTakeIdNamesNoFile() = runBlocking {
        assertEquals(RescueOutcome.FAILED, store.keep("../escape", "Keep these words.", SaveSlot()).await())
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    /** Row 9: a write that cannot land is FAILED, measured, and logged by type only. */
    @Test fun aWriteThatCannotLandIsFailed() = runBlocking {
        dir.deleteRecursively()
        dir.writeText("not a directory")
        assertEquals(RescueOutcome.FAILED, store.keep(take, "Keep these words.", SaveSlot()).await())
        assertTrue(warnings.any { it.startsWith("Rescue write failed:") })
        assertTrue(warnings.none { it.contains("Keep these words") })
        dir.delete()
        Unit
    }
}
