package com.envi.wispr.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#115): when this fails, a History row reads `processing` after its take ended in an
 * error, or `ready` after a teardown marked it `interrupted`, because two writes to one row landed in the
 * wrong order. The queue applies every write in the order it was enqueued, on one worker, and a write
 * that throws costs itself and nothing after it. The DAO is a fake that records the order writes landed.
 */
class HistoryWriteQueueTest {
    private class RecordingDao : TranscriptDao {
        val landed = CopyOnWriteArrayList<String>()
        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(emptyList())
        override suspend fun insert(transcript: TranscriptEntity): Long { landed += "insert"; return 1L }
        override suspend fun findByTakeId(takeId: String): TranscriptEntity? = null
        override suspend fun insertDeletedTakes(takes: List<DeletedTake>) = Unit
        override suspend fun isTakeDeleted(takeId: String): Boolean = false
        override suspend fun rowTakeIds(): List<String> = emptyList()
        override suspend fun setKept(id: Long, kept: Boolean) = Unit
        override suspend fun delete(transcript: TranscriptEntity) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun deleteById(id: Long): Int { landed += "discard"; return 1 }
        override suspend fun deleteWordlessRows(): Int = 0
        override suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean, insertionResult: String?): Int { landed += "status:$status"; return 1 }
        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean): Int { landed += "finalize"; return 1 }
        override suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, stateChangedAtMs: Long, interrupted: Boolean): Int { landed += "outcome:$result"; return 1 }
        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun recoverStaleProcessingRows(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int = 0
        override suspend fun staleReadyRowIds(cutoffMs: Long): List<Long> = emptyList()
        override suspend fun promoteUnroutedToReady(id: Long, nowMs: Long): Int { landed += "promote"; return 1 }
        override suspend fun recoverStaleUnroutedRows(cutoffMs: Long, nowMs: Long): Int = 0
    }

    private val dao = RecordingDao()
    private val warnings = CopyOnWriteArrayList<String>()
    private val queue = HistoryWriteQueue(TranscriptRepository(dao, clock = { 1_000L }), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), warn = { warnings += it })

    private fun drained(): CountDownLatch = CountDownLatch(1).also { latch -> queue.enqueue("marker") { latch.countDown() } }

    @Test
    fun writesLandInEnqueueOrderWhateverTheDiskDoes() {
        // The first write is SLOW (a held disk); on two workers the second would land first. One worker,
        // enqueue order. REVERT: drain on Dispatchers.IO with `launch` per write instead of one consumer.
        queue.enqueue("processing") { repository -> delay(150L); repository.updateStatus(1L, "processing") }
        queue.enqueue("asr_error") { repository -> repository.updateStatus(1L, "asr_error", insertionResult = "asr_error") }
        queue.enqueue("interrupted") { repository -> repository.updateStatus(1L, "interrupted", interrupted = true) }
        check(drained().await(10, TimeUnit.SECONDS))
        assertEquals(listOf("status:processing", "status:asr_error", "status:interrupted"), dao.landed.toList())
    }

    @Test
    fun aWriteThatThrowsCostsItselfAndNothingAfterIt() {
        queue.enqueue("bad insert") { throw IllegalStateException("disk full") }
        queue.enqueue("discard") { repository -> repository.discard(1L) }
        check(drained().await(10, TimeUnit.SECONDS))
        assertEquals(listOf("discard"), dao.landed.toList())
        assertEquals(listOf("History write failed (bad insert): IllegalStateException"), warnings.toList())
    }

    @Test
    fun enqueueReturnsAtOnceWhileTheDiskIsHeld() {
        // The owner's main thread enqueues; it must never wait for storage. A held first write does not
        // delay the enqueue of the next.
        val release = CountDownLatch(1)
        queue.enqueue("held") { repository -> release.await(10, TimeUnit.SECONDS); repository.discard(1L) }
        val started = System.nanoTime()
        assertEquals(Enqueued.ACCEPTED, queue.enqueue("after the held one") { repository -> repository.discard(2L) })
        val enqueueNanos = System.nanoTime() - started
        release.countDown()
        check(drained().await(10, TimeUnit.SECONDS))
        assertTrue("enqueue took ${enqueueNanos / 1_000_000} ms", enqueueNanos < 100_000_000L)
        assertEquals(listOf("discard", "discard"), dao.landed.toList())
    }

    // ---- #292: a bounded queue with a typed refusal ------------------------------------------------------------

    /** A queue of [capacity] (ordinary limit [ordinaryLimit]) whose FIRST write holds until [release], recording overload episodes. */
    private class HeldQueue(capacity: Int, ordinaryLimit: Int) {
        val dao = RecordingDao()
        val episodes = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        val queue = HistoryWriteQueue(
            TranscriptRepository(dao, clock = { 1_000L }),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            warn = {},
            capacity = capacity,
            ordinaryLimit = ordinaryLimit,
            onOverload = { episodes.incrementAndGet() },
        )

        /** Holds the worker in its first write and waits until it has entered it. */
        fun hold(label: String) {
            val gate = release
            check(queue.enqueue(label) { entered.countDown(); gate.await(10, TimeUnit.SECONDS); dao.landed += label } == Enqueued.ACCEPTED)
            check(entered.await(10, TimeUnit.SECONDS)) { "the worker never entered the held write" }
        }

        fun write(label: String, kind: WriteKind) = queue.enqueue(label, kind) { dao.landed += label }

        /** Waits until the worker has finished every accepted write and brought the count to zero. */
        fun drain() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (queue.pendingForTest() > 0) {
                check(System.nanoTime() < deadline) { "the backlog never drained" }
                Thread.sleep(2)
            }
        }
    }

    /**
     * Row 1. Capacity 4, ordinary limit 3, the worker held in its first write: ordinary writes are refused past 3
     * outstanding while a terminal one is still taken; past 4 a terminal write is refused too; nothing blocks; one
     * episode for all those refusals; exactly the accepted writes land, in order. A new stall after the backlog
     * drained is a second episode. MUTATIONS m1 (never rejects), m2 (overload on every refusal), m5 (terminal held
     * to the ordinary limit).
     */
    @Test fun aFullQueueRefusesOrdinaryWritesFirstAndReportsOnceAnEpisode() {
        val held = HeldQueue(capacity = 4, ordinaryLimit = 3)
        held.hold("held")
        assertEquals(Enqueued.ACCEPTED, held.write("ordinary 1", WriteKind.ORDINARY))
        assertEquals(Enqueued.ACCEPTED, held.write("ordinary 2", WriteKind.ORDINARY))
        val started = System.nanoTime()
        assertEquals("past the ordinary limit", Enqueued.REJECTED, held.write("ordinary 3", WriteKind.ORDINARY))
        assertEquals("a terminal write keeps the reserve", Enqueued.ACCEPTED, held.write("terminal 1", WriteKind.TERMINAL))
        assertEquals("past the capacity", Enqueued.REJECTED, held.write("terminal 2", WriteKind.TERMINAL))
        assertEquals(Enqueued.REJECTED, held.write("ordinary 4", WriteKind.ORDINARY))
        assertTrue("refusals never block", System.nanoTime() - started < 100_000_000L)
        assertEquals("one episode for every refusal of one stall", 1, held.episodes.get())
        held.release.countDown()
        held.drain()
        assertEquals(listOf("held", "ordinary 1", "ordinary 2", "terminal 1"), held.dao.landed.toList())

        // A second stall, after the backlog drained, is a second episode.
        held.release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val gate = held.release
        check(held.queue.enqueue("held again") { secondEntered.countDown(); gate.await(10, TimeUnit.SECONDS) } == Enqueued.ACCEPTED)
        check(secondEntered.await(10, TimeUnit.SECONDS))
        repeat(2) { assertEquals(Enqueued.ACCEPTED, held.write("fill $it", WriteKind.ORDINARY)) }
        assertEquals(Enqueued.REJECTED, held.write("over again", WriteKind.ORDINARY))
        assertEquals(2, held.episodes.get())
        held.release.countDown()
    }

    /** A log line that throws never stops the count coming down, nor a refusal being answered (#292 review). MUTATION m6. */
    @Test fun aThrowingLogNeverWedgesTheQueue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val throwing = HistoryWriteQueue(
            TranscriptRepository(RecordingDao(), clock = { 1_000L }),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            warn = { error("the log broke") },
            capacity = 2,
            ordinaryLimit = 1,
        )
        assertEquals(Enqueued.ACCEPTED, throwing.enqueue("held") { entered.countDown(); release.await(10, TimeUnit.SECONDS); error("the write broke") })
        check(entered.await(10, TimeUnit.SECONDS))
        assertEquals("a refusal is still answered", Enqueued.REJECTED, throwing.enqueue("refused") { })
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (throwing.pendingForTest() > 0) {
            check(System.nanoTime() < deadline) { "the count never came down after a write and its log line threw" }
            Thread.sleep(2)
        }
        assertEquals(Enqueued.ACCEPTED, throwing.enqueue("after") { })
    }

    /** Row 2. A refused draft insert completes its deferred with the queue-full cause at once, and the row resolves to 0. MUTATION m3. */
    @Test fun aRefusedDraftNeverLeavesItsDeferredOpen() {
        val held = HeldQueue(capacity = 2, ordinaryLimit = 1)
        held.hold("held")
        val history = com.envi.wispr.ui.TakeHistory(held.queue)
        val draft = history.insertDraft("take-1", 1_000L)
        val cause = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(2_000L) { runCatching { draft.await() }.exceptionOrNull() }
        }
        assertTrue("the queue-full cause, not any failure: $cause", cause is HistoryQueueFullException)
        assertEquals(0L, kotlinx.coroutines.runBlocking { history.resolvedId() })
        held.release.countDown()
    }

    /**
     * Row 3. A refused save answers its slot `Failed(HistoryQueueFullException)` at once, and (#304) the application's
     * observer reports it once as a failed save: a warning and a `history_save_failed` breadcrumb. MUTATION m4.
     */
    @Test fun aRefusedSaveAnswersItsSlotFailed() {
        val held = HeldQueue(capacity = 2, ordinaryLimit = 1)
        held.hold("held")
        check(held.write("terminal fill", WriteKind.TERMINAL) == Enqueued.ACCEPTED)
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val breadcrumbs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finalizer = com.envi.wispr.ui.SessionFinalizer(
            host = com.envi.wispr.ui.DictationSessionRig().host,
            insertion = com.envi.wispr.ui.DictationSessionRig.FakeInsertion(),
            log = com.envi.wispr.ui.DictationSessionRig().log,
            historyWrites = held.queue,
            historySaves = com.envi.wispr.ui.HistorySaveObserver(
                scope = observerScope,
                clock = { System.nanoTime() / 1_000_000L },
                warn = { warnings += it },
                defectSink = { _, _ -> },
                breadcrumb = { _, message, _ -> breadcrumbs += message },
            ),
            rescuedWords = com.envi.wispr.ui.RescuedWords(java.nio.file.Files.createTempDirectory("rescued-words").toFile(), observerScope, wallClock = { 0L }, warn = {}),
        )
        val slot = com.envi.wispr.ui.SaveSlot()
        val publication = com.envi.wispr.ui.Publication(
            finalText = "Words.", engine = "Fake", originalText = "words", latencyMs = 0L, durationMs = 1L, captureDevice = "",
            polishFacts = com.envi.wispr.polish.PolishPublicationFacts.from(com.envi.wispr.polish.PolishReason.POLISHED, 0, com.envi.wispr.polish.PolishContext.Off),
        )
        finalizer.enqueueSave(com.envi.wispr.ui.TakeHistory(held.queue), publication, slot, "take-1")
        val answer = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(2_000L) { slot.await() } }
        val outcome = answer.outcome
        assertTrue("a failed save, not any outcome: $outcome", outcome is com.envi.wispr.ui.SaveOutcome.Failed)
        assertTrue((outcome as com.envi.wispr.ui.SaveOutcome.Failed).cause is HistoryQueueFullException)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (breadcrumbs.isEmpty()) {
            check(System.nanoTime() < deadline) { "the observer never reported the refused save" }
            Thread.sleep(2)
        }
        assertEquals(listOf("history_save_failed"), breadcrumbs.toList())
        assertEquals(listOf("Unable to save transcript history: HistoryQueueFullException"), warnings.toList())
        held.release.countDown()
        observerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * #304: a refusal is overload, not a broken contract. The queue raises one `HistoryQueueOverloaded` per episode
     * (#292), so the save's own classifier names no defect for it, while an ordinary illegal state still names one.
     * MUTATION m4 (the decisive check).
     */
    @Test fun aRefusedSaveNamesNoDefectOfItsOwn() {
        assertEquals(null, com.envi.wispr.telemetry.TelemetryChannels.historySaveDefect(HistoryQueueFullException()))
        assertTrue(com.envi.wispr.telemetry.TelemetryChannels.historySaveDefect(IllegalStateException()) is com.envi.wispr.telemetry.AppDefect.HistoryContractViolation)
    }
}
