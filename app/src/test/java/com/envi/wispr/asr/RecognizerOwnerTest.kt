package com.envi.wispr.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drift Guard (#212): the recognizer's ownership and ordering. Load, every decode and the release run on
 * one worker, the release after every admitted task, never on the caller's thread; an answer finished
 * after close is discarded; a request after close is refused once. Not product coverage: the product
 * outcome is the emulator take in the plan's hardware pass.
 *
 * Every wait is a latch; its deadline exists only so a regression fails instead of hanging.
 */
class RecognizerOwnerTest {

    private class Recognizer(val name: String)

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val freeThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
    private val freed = AtomicInteger(0)
    private val discards = AtomicInteger(0)

    private fun worker(): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "TestAsrWorker") }

    private fun owner(worker: ExecutorService) = RecognizerOwner<Recognizer>(
        worker,
        free = {
            freeThreads += Thread.currentThread()
            freed.incrementAndGet()
            events += "free"
        },
        discarded = {
            discards.incrementAndGet()
            events += "discarded"
        },
    )

    private fun CountDownLatch.awaitOrFail(what: String) =
        assertTrue("$what never happened", await(5, TimeUnit.SECONDS))

    private fun ExecutorService.awaitTerminatedOrFail() =
        assertTrue("the worker never stopped", awaitTermination(5, TimeUnit.SECONDS))

    /** Parks the worker on a task submitted straight to it; returns the latch that releases it. */
    private fun ExecutorService.park(): CountDownLatch {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        execute {
            parked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        parked.awaitOrFail("the gate")
        return release
    }

    private fun loaded(worker: ExecutorService): RecognizerOwner<Recognizer> {
        val owner = owner(worker)
        val done = CountDownLatch(1)
        owner.load { Recognizer("parakeet").also { done.countDown() } }
        done.awaitOrFail("the load")
        return owner
    }

    // REVERT R1 (free inline in close) and R8 (deliver regardless of closed) turn this red.
    @Test
    fun aCloseDuringADecodeFreesOnlyAfterItAndDiscardsItsAnswer() {
        val worker = worker()
        val owner = loaded(worker)
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        owner.use(refused = { events += "refused" }) { _ ->
            inside.countDown()
            finish.await(5, TimeUnit.SECONDS)
            events += "work-end"
            return@use { events += "delivered" }
        }
        inside.awaitOrFail("the decode")
        owner.close()
        assertEquals("nothing is freed while the decode is inside the recognizer", 0, freed.get())
        finish.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(listOf("work-end", "discarded", "free"), events.toList())
    }

    // REVERT R1 and R5 (isReady returns ready alone) turn this red.
    @Test
    fun aCloseDuringTheLoadFreesOnlyAfterTheLoad() {
        val worker = worker()
        val owner = owner(worker)
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val opened = Recognizer("parakeet")
        owner.load {
            inside.countDown()
            finish.await(5, TimeUnit.SECONDS)
            opened
        }
        inside.awaitOrFail("the load")
        // Queued straight onto the worker between the load and the release: it reads readiness in the
        // one moment a finished load has written it and the release has not yet cleared it.
        var readyBetween: Boolean? = null
        worker.execute { readyBetween = owner.isReady }
        owner.close()
        assertEquals("nothing is freed while the load is inside the recognizer", 0, freed.get())
        assertFalse(owner.isReady)
        finish.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(1, freed.get())
        assertEquals("a load that finished after close never reads as ready", false, readyBetween)
        assertFalse(owner.isReady)
    }

    // REVERT R1 and R6 (free on a new thread) turn this red.
    @Test
    fun anIdleCloseFreesOnTheWorkerOnce() {
        val worker = worker()
        val owner = loaded(worker)
        owner.close()
        worker.awaitTerminatedOrFail()
        assertEquals(1, freed.get())
        assertEquals("TestAsrWorker", freeThreads.single().name)
        assertNotEquals(Thread.currentThread(), freeThreads.single())
    }

    // REVERT R2 (drop the in-worker closed check) turns this red.
    @Test
    fun aQueuedRequestThatReachesTheWorkerAfterCloseRefusesThenFrees() {
        val worker = worker()
        val owner = loaded(worker)
        val gate = worker.park()
        owner.use(refused = { events += "refused" }) { _ ->
            events += "work"
            return@use { events += "delivered" }
        }
        owner.close()
        gate.countDown()
        worker.awaitTerminatedOrFail()
        assertEquals(listOf("refused", "free"), events.toList())
    }

    // REVERT R3 (drop the RejectedExecutionException catch) turns this red.
    @Test
    fun aRequestAfterTheWorkerStoppedIsRefusedOnceWithoutThrowing() {
        val worker = worker()
        val owner = loaded(worker)
        owner.close()
        worker.awaitTerminatedOrFail()
        val refusals = AtomicInteger(0)
        owner.use(refused = { refusals.incrementAndGet() }) { _ ->
            events += "work"
            return@use {}
        }
        assertEquals(1, refusals.get())
        assertFalse(events.contains("work"))
    }

    @Test
    fun aLoadAfterCloseNeverOpens() {
        val worker = worker()
        val owner = owner(worker)
        owner.close()
        val opens = AtomicInteger(0)
        owner.load {
            opens.incrementAndGet()
            Recognizer("parakeet")
        }
        worker.awaitTerminatedOrFail()
        assertEquals(0, opens.get())
        assertEquals(0, freed.get())
    }

    /** Counts what the owner submits, so a second release task is visible even when it finds nothing to free. */
    private class CountingExecutor(private val inner: ExecutorService) : AbstractExecutorService() {
        val submissions = AtomicInteger(0)
        override fun execute(command: Runnable) {
            submissions.incrementAndGet()
            inner.execute(command)
        }
        override fun shutdown() = inner.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = inner.shutdownNow()
        override fun isShutdown() = inner.isShutdown
        override fun isTerminated() = inner.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = inner.awaitTermination(timeout, unit)
    }

    // REVERT R4 (an unconditional closed.set(true) in place of the compare-and-set) turns this red.
    @Test
    fun closeTwiceSubmitsOneReleaseAndFreesOnce() {
        val worker = CountingExecutor(worker())
        val owner = loaded(worker)
        val before = worker.submissions.get()
        owner.close()
        owner.close()
        worker.awaitTerminatedOrFail()
        assertEquals("one release task, however many closes", before + 1, worker.submissions.get())
        assertEquals(1, freed.get())
    }

    @Test
    fun isReadyStaysFalseAfterClose() {
        val worker = worker()
        val owner = loaded(worker)
        assertTrue(owner.isReady)
        owner.close()
        assertFalse(owner.isReady)
        worker.awaitTerminatedOrFail()
        assertFalse(owner.isReady)
    }

    // REVERT R6 and R9 (always discard) turn this red.
    @Test
    fun loadUseAndFreeRunOnOneWorkerInOrder() {
        val worker = worker()
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val owner = owner(worker)
        val delivered = CountDownLatch(1)
        owner.load {
            threads += Thread.currentThread()
            events += "open"
            Recognizer("parakeet")
        }
        owner.use(refused = { events += "refused" }) { recognizer ->
            threads += Thread.currentThread()
            events += "use:${recognizer?.name}"
            return@use {
                threads += Thread.currentThread()
                events += "delivery"
                delivered.countDown()
            }
        }
        // Closed only once the answer is out: a close before the use ran would refuse it, by design.
        delivered.awaitOrFail("the delivery")
        owner.close()
        worker.awaitTerminatedOrFail()
        threads += freeThreads
        assertEquals(listOf("open", "use:parakeet", "delivery", "free"), events.toList())
        assertEquals("one worker thread for every touch", 1, threads.toSet().size)
    }

    // REVERT R9 (always discard) turns this red.
    @Test
    fun anAnswerFinishedWhileOpenIsDelivered() {
        val worker = worker()
        val owner = loaded(worker)
        val delivered = CountDownLatch(1)
        owner.use(refused = { events += "refused" }) { _ ->
            return@use { delivered.countDown() }
        }
        delivered.awaitOrFail("the delivery")
        assertEquals(0, discards.get())
        owner.close()
        worker.awaitTerminatedOrFail()
    }
}
