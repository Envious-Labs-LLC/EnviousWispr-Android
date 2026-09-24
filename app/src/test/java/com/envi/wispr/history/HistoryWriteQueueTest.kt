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
        assertTrue(queue.enqueue("after the held one") { repository -> repository.discard(2L) })
        val enqueueNanos = System.nanoTime() - started
        release.countDown()
        check(drained().await(10, TimeUnit.SECONDS))
        assertTrue("enqueue took ${enqueueNanos / 1_000_000} ms", enqueueNanos < 100_000_000L)
        assertEquals(listOf("discard", "discard"), dao.landed.toList())
    }
}
