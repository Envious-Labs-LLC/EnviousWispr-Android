package com.envi.wispr.polish

import com.envi.wispr.cleanup.CleanupOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Harness Contract (#291): the `:polish` process's failure answers never run their cleanup on the caller's (binder)
 * thread, a cancelled answer is never delivered, and a closed lane still answers, raw, off-thread. Each row names the
 * compiling mutation that turns it red (recorded in the PR's receipts).
 */
class PolishFallbackLaneTest {
    /** A worker that queues tasks until the row runs them, and refuses once shut down. */
    private class QueueWorker : AbstractExecutorService() {
        val queued = CopyOnWriteArrayList<Runnable>()
        @Volatile private var down = false
        override fun execute(command: Runnable) {
            if (down) throw RejectedExecutionException("shut down")
            queued += command
        }
        fun runAll() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }
        override fun shutdown() { down = true }
        override fun shutdownNow(): MutableList<Runnable> = queued.toMutableList().also { down = true }
        override fun isShutdown() = down
        override fun isTerminated() = down && queued.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val options = CleanupOptions()
    private val prepared = CopyOnWriteArrayList<String>()
    private val prepare: (String, CleanupOptions) -> String = { raw, _ -> prepared += Thread.currentThread().name; "Cleaned: $raw" }

    /** Row 1. `answer` returns without preparing; the worker prepares and delivers once. MUTATION m1: prepare inline before submitting. */
    @Test fun anAnswerIsPreparedOnTheWorkerNeverOnTheCaller() {
        val worker = QueueWorker()
        val lane = PolishFallbackLane(worker, prepare)
        val delivered = CopyOnWriteArrayList<PolishOutcome>()
        lane.answer(7L, "raw words", options, PolishReason.UNEXPECTED) { delivered += it }
        assertTrue("nothing prepared on the caller", prepared.isEmpty())
        assertTrue(delivered.isEmpty())
        worker.runAll()
        assertEquals(listOf(PolishOutcome(7L, "Cleaned: raw words", PolishEngineLabels.DETERMINISTIC, PolishReason.UNEXPECTED, 0, 0)), delivered.toList())
    }

    /** Row 2. A refusing worker: the raw words arrive once, from another thread, unprepared. MUTATION m2: the refusal delivers inline. */
    @Test fun aRefusedAnswerDeliversTheRawWordsOffTheCaller() {
        val worker = QueueWorker().apply { shutdown() }
        val lane = PolishFallbackLane(worker, prepare)
        val arrived = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<Pair<PolishOutcome, String>>()
        lane.answer(8L, "raw words", options, PolishReason.LOCAL_FAILED) { delivered += it to Thread.currentThread().name; arrived.countDown() }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        val (outcome, thread) = delivered.single()
        assertEquals(PolishOutcome(8L, "raw words", PolishEngineLabels.DETERMINISTIC, PolishReason.LOCAL_FAILED, 0, 0), outcome)
        assertNotEquals("never the caller's thread", Thread.currentThread().name, thread)
        assertTrue("no cleanup ran for a refusal", prepared.isEmpty())
    }

    /**
     * Row 3. A cancel before the queued answer runs means it is never delivered; another id still is. A refused answer
     * cancelled before its deliverer runs is never delivered either. MUTATION m3: the queued task ignores the token;
     * MUTATION m6: the refusal ignores it.
     */
    @Test fun aCancelledAnswerIsNeverDelivered() {
        val worker = QueueWorker()
        val lane = PolishFallbackLane(worker, prepare)
        val delivered = CopyOnWriteArrayList<Long>()
        lane.answer(1L, "one", options, PolishReason.UNEXPECTED) { delivered += it.requestId }
        lane.answer(2L, "two", options, PolishReason.UNEXPECTED) { delivered += it.requestId }
        lane.cancel(1L)
        assertEquals("the cancelled answer is forgotten at once, before its task runs", 1, lane.pending())
        worker.runAll()
        assertEquals(listOf(2L), delivered.toList())
        assertEquals(0, lane.pending())

        val held = CopyOnWriteArrayList<Runnable>()
        val refusing = PolishFallbackLane(QueueWorker().apply { shutdown() }, prepare, startRefusal = { held += it })
        refusing.answer(3L, "three", options, PolishReason.UNEXPECTED) { delivered += it.requestId }
        refusing.cancel(3L)
        held.single().run()
        assertEquals("the cancelled refusal delivered nothing", listOf(2L), delivered.toList())
    }

    /**
     * Row 4. `close(then)`: `then` runs, and only after the admitted answer delivered; a later answer is refused (raw,
     * off-thread). MUTATION m4: `close` runs `then` immediately.
     */
    @Test fun closeRunsItsFinalStepBehindTheAdmittedAnswers() {
        val worker = QueueWorker()
        val order = CopyOnWriteArrayList<String>()
        val refused = CopyOnWriteArrayList<Runnable>()
        val lane = PolishFallbackLane(worker, prepare, startRefusal = { refused += it })
        lane.answer(4L, "four", options, PolishReason.UNEXPECTED) { order += "answer ${it.requestId}" }
        lane.close { order += "then" }
        assertTrue("nothing ran before the worker", order.isEmpty())
        worker.runAll()
        assertEquals(listOf("answer 4", "then"), order.toList())
        lane.answer(5L, "five", options, PolishReason.UNEXPECTED) { order += "raw ${it.text}" }
        assertEquals("a closed lane refuses, off the caller", 1, refused.size)
        refused.single().run()
        assertEquals(listOf("answer 4", "then", "raw five"), order.toList())
    }

    /** A worker that refuses the final step still runs it, off the caller (#291 review). MUTATION m7: the refusal swallowed. */
    @Test fun aRefusedFinalStepStillRuns() {
        val started = CopyOnWriteArrayList<Runnable>()
        val lane = PolishFallbackLane(QueueWorker().apply { shutdown() }, prepare, startRefusal = { started += it })
        var ran = false
        lane.close { ran = true }
        started.single().run()
        assertTrue(ran)
    }

    /** A cleanup that throws still answers, with the raw words. */
    @Test fun aCleanupThatThrowsAnswersTheRawWords() {
        val worker = QueueWorker()
        val lane = PolishFallbackLane(worker, { _, _ -> error("detector broke") })
        val delivered = CopyOnWriteArrayList<String>()
        lane.answer(6L, "six", options, PolishReason.UNEXPECTED) { delivered += it.text }
        worker.runAll()
        assertEquals(listOf("six"), delivered.toList())
    }
}
