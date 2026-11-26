package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Product Outcome at the logic layer (#561): when this fails, the user sees the phone freeze in an ANR
 * (a field check blocking on main), or the bubble drawn from an old answer. It drives [BubbleFieldChecks],
 * the queue the bubble host hands every field check to. The ordering rows step two hand-run queues so each
 * interleaving is chosen, not hoped for; one row crosses real threads to prove the blocking half never runs
 * on the thread that submitted it. The accessibility calls themselves are not crossed on the JVM.
 */
class BubbleFieldChecksTest {

    /** A hand-stepped pair of queues standing in for the probe thread and the main looper. */
    private class Harness {
        val background = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val begun = mutableListOf<BubbleFieldCheck>()
        val blockingRan = mutableListOf<BubbleFieldCheck>()
        val finished = mutableListOf<BubbleFieldCheck>()
        var onFinish: (BubbleFieldCheck) -> Unit = {}
        var blockingThrows = false

        val checks = BubbleFieldChecks(
            background = { background.addLast(it) },
            main = { main.addLast(it) },
            begin = { check ->
                begun += check
                val blocking: () -> BubbleFieldCheck = {
                    blockingRan += check
                    if (blockingThrows) error("the probe failed")
                    check
                }
                blocking
            },
            finish = { answer ->
                finished += answer
                onFinish(answer)
            },
        )

        fun runBackground() = background.removeFirst().run()
        fun runMain() = main.removeFirst().run()
    }

    private val transition = BubbleFieldCheck(transition = true, transitionUptime = 100L)
    private val click = BubbleFieldCheck(revalidate = true)
    private val retry = BubbleFieldCheck(retryOf = 3)

    @Test
    fun theBlockingHalfNeverRunsInsideSubmitAndItsAnswerIsAppliedOnlyThroughMain() {
        val h = Harness()
        h.checks.submit(transition)
        assertEquals("the snapshot is taken on the submitting thread", listOf(transition), h.begun)
        assertEquals("nothing blocks inside submit", emptyList<BubbleFieldCheck>(), h.blockingRan)
        assertEquals(1, h.background.size)

        h.runBackground()
        assertEquals(listOf(transition), h.blockingRan)
        assertEquals("the answer is not applied off main", emptyList<BubbleFieldCheck>(), h.finished)

        h.runMain()
        assertEquals(listOf(transition), h.finished)
    }

    @Test
    fun aBurstWhileOneCheckRunsBecomesOneMergedCheckAfterIt() {
        val h = Harness()
        h.checks.submit(transition)
        h.checks.submit(click)
        h.checks.submit(retry)
        assertEquals("one check at a time", 1, h.begun.size)
        assertEquals(1, h.background.size)

        h.runBackground()
        h.runMain()
        assertEquals(listOf(transition), h.finished)
        assertEquals(
            "the waiting checks start as one",
            listOf(transition, BubbleFieldCheck(revalidate = true, retryOf = 3)),
            h.begun,
        )
        assertEquals(1, h.background.size)
        h.runBackground()
        h.runMain()
        assertEquals(2, h.finished.size)
        assertTrue("and nothing further", h.background.isEmpty() && h.main.isEmpty())
    }

    @Test
    fun aCheckSubmittedWhileAnAnswerIsAppliedWaitsRatherThanRunningBesideTheNext() {
        // The host re-asks from inside finish when an answer turned out stale; that must not start a second
        // check alongside the waiting one.
        val h = Harness()
        h.onFinish = { answer -> if (answer == transition) h.checks.submit(retry) }
        h.checks.submit(transition)
        h.checks.submit(click)
        h.runBackground()
        h.runMain()
        assertEquals("exactly one check started after the answer", 1, h.background.size)
        assertEquals(BubbleFieldCheck(revalidate = true, retryOf = 3), h.begun.last())
    }

    @Test
    fun afterCloseNoAnswerIsAppliedAndNothingStarts() {
        val h = Harness()
        h.checks.submit(transition)
        h.checks.submit(click)
        h.checks.close()
        h.runBackground()
        h.runMain()
        assertEquals("the in-flight answer is dropped", emptyList<BubbleFieldCheck>(), h.finished)
        assertEquals("the waiting check never starts", listOf(transition), h.begun)
        h.checks.submit(click)
        assertEquals("a submit after close is ignored", listOf(transition), h.begun)
        assertTrue(h.background.isEmpty())
    }

    @Test
    fun aCancelledSequenceStripsTheWaitingCheckButNotTheRunningOne() {
        val h = Harness()
        h.checks.submit(click)
        h.checks.submit(transition)
        h.checks.forgetSequence()
        h.runBackground()
        h.runMain()
        assertEquals(listOf(click, BubbleFieldCheck(revalidate = true)), h.begun)
    }

    @Test
    fun aProbeThatThrowsAnswersNothingAndDoesNotWedgeTheQueue() {
        val h = Harness()
        h.blockingThrows = true
        h.checks.submit(transition)
        h.runBackground()
        h.runMain()
        assertEquals(emptyList<BubbleFieldCheck>(), h.finished)
        h.blockingThrows = false
        h.checks.submit(click)
        assertEquals("the next check starts", listOf(transition, click), h.begun)
        h.runBackground()
        h.runMain()
        assertEquals(listOf(click), h.finished)
    }

    @Test
    fun anEmptyCheckStartsNothing() {
        val h = Harness()
        h.checks.submit(BubbleFieldCheck())
        assertTrue(h.begun.isEmpty() && h.background.isEmpty())
    }

    @Test
    fun onRealThreadsTheBlockingHalfRunsOffTheMainThreadAndTheAnswerLandsBackOnIt() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }
        val probe = Executors.newSingleThreadExecutor { Thread(it, "test-probe") }
        try {
            val mainThread = AtomicReference<Thread>()
            val beginThread = AtomicReference<Thread>()
            val blockingThread = AtomicReference<Thread>()
            val finishThread = AtomicReference<Thread>()
            val finished = CountDownLatch(1)
            val checks = BubbleFieldChecks(
                background = { probe.execute(it) },
                main = { main.execute(it) },
                begin = { _ ->
                    beginThread.set(Thread.currentThread())
                    val blocking: () -> Unit = { blockingThread.set(Thread.currentThread()) }
                    blocking
                },
                finish = {
                    finishThread.set(Thread.currentThread())
                    finished.countDown()
                },
            )
            main.execute {
                mainThread.set(Thread.currentThread())
                checks.submit(transition)
            }
            assertTrue("finish was never called back on main", finished.await(5, TimeUnit.SECONDS))
            assertEquals("the snapshot is taken on main", mainThread.get(), beginThread.get())
            assertNotEquals("the blocking half never runs on main", mainThread.get(), blockingThread.get())
            assertEquals("test-probe", blockingThread.get().name)
            assertEquals("the answer is applied on main", mainThread.get(), finishThread.get())
        } finally {
            main.shutdownNow()
            probe.shutdownNow()
        }
    }
}
