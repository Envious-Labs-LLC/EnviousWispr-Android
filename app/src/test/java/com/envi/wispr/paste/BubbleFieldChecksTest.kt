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
        val failed = mutableListOf<BubbleFieldCheck>()
        var dispatchRefused = false
        var onFinish: (BubbleFieldCheck) -> Unit = {}
        var onFailed: (BubbleFieldCheck) -> Unit = {}
        var beginThrows = false
        var blockingThrows = false

        val checks = BubbleFieldChecks(
            background = {
                if (dispatchRefused) throw java.util.concurrent.RejectedExecutionException("refused")
                background.addLast(it)
            },
            main = { main.addLast(it) },
            begin = { check ->
                begun += check
                if (beginThrows) error("snapshot failed")
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
            failed = { check ->
                failed += check
                onFailed(check)
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
        assertEquals("the caller hears the check answered nothing", listOf(transition), h.failed)
        h.blockingThrows = false
        h.checks.submit(click)
        assertEquals("the next check starts", listOf(transition, click), h.begun)
        h.runBackground()
        h.runMain()
        assertEquals(listOf(click), h.finished)
    }

    @Test
    fun aRefusedDispatchIsReportedAndDoesNotWedgeTheQueue() {
        val h = Harness()
        h.dispatchRefused = true
        h.checks.submit(retry)
        assertEquals(listOf(retry), h.failed)
        h.dispatchRefused = false
        h.checks.submit(click)
        assertEquals("the next check starts", listOf(retry, click), h.begun)
    }

    @Test
    fun aBeginThatThrowsOnARetryReportsFailureAndDrainsWaitingWork() {
        // #561 R2: a throw in begin() during a retry must report failure so the retry sequence advances
        // instead of stalling, and any waiting check submitted while handling it must drain.
        val h = Harness()
        h.beginThrows = true
        var begunInsideFailed: List<BubbleFieldCheck>? = null
        h.onFailed = {
            h.beginThrows = false
            h.checks.submit(click)
            begunInsideFailed = h.begun.toList()
        }
        h.checks.submit(retry)
        assertEquals(listOf(retry), h.failed)
        assertEquals("running stays true during failed so follow-up waits and drains cleanly", listOf(retry), begunInsideFailed)
        assertEquals(emptyList<BubbleFieldCheck>(), h.finished)
        assertEquals("the follow-up check drained cleanly", listOf(retry, click), h.begun)
        assertEquals(1, h.background.size)
        h.runBackground()
        h.runMain()
        assertEquals(listOf(click), h.finished)
        assertTrue(h.background.isEmpty() && h.main.isEmpty())
    }

    @Test
    fun aCancelBeforeAQueuedCompletionRunsAbandonsItAndLetsTheNextCheckStart() {
        // #561 R2 reconnect ordering: a pre-reconnect completion already queued on main must be abandoned
        // by cancel() so it never calls finish/failed, and frees the slot for the post-reconnect check.
        val h = Harness()
        h.checks.submit(transition)
        h.runBackground() // completed runnable is now queued on main
        h.checks.cancel()
        h.runMain() // queued pre-reconnect completion runs before reconnect's posted check
        assertEquals(emptyList<BubbleFieldCheck>(), h.finished + h.failed)
        h.checks.submit(retry)
        assertEquals("the slot is free, so reconnect's check starts at once", listOf(transition, retry), h.begun)
        assertEquals(1, h.background.size)
        h.runBackground()
        h.runMain()
        assertEquals(listOf(retry), h.finished)
    }

    @Test
    fun aCancelAbandonsTheRunningAnswerAndTheWaitingCheckAndDrainsAfterTheWorkerFrees() {
        // onInterrupt / onUnbind / reconnect: nothing read before the cancel is drawn or re-asked,
        // and the post-cancel check waits behind the blocked worker before running.
        val h = Harness()
        h.checks.submit(transition)
        h.checks.submit(click)
        h.checks.cancel()
        h.checks.submit(retry)
        assertEquals("the worker is still blocked, so the post-cancel check waits", listOf(transition), h.begun)
        assertEquals(1, h.background.size)
        h.runBackground()
        h.runMain()
        assertEquals("the abandoned answer is neither applied nor reported", emptyList<BubbleFieldCheck>(), h.finished + h.failed)
        assertEquals("once the worker frees, the post-cancel check starts", listOf(transition, retry), h.begun)
        assertEquals(1, h.background.size)
        h.runBackground()
        h.runMain()
        assertEquals(listOf(retry), h.finished)
        assertTrue("the dropped waiting check never ran", h.background.isEmpty() && h.begun.size == 2)
    }

    @Test
    fun repeatedCancelsAndSubmitsWhileWorkerIsBlockedNeverPileObsoleteProbes() {
        // #561 R2: repeated cancel + submit cycles while one probe is blocked must not pile obsolete
        // probes onto the executor; only the 1 in-flight obsolete probe runs, then only the latest submit.
        val latest = BubbleFieldCheck(transition = true, transitionUptime = 250L)
        val h = Harness()
        h.checks.submit(transition)
        h.checks.cancel()
        h.checks.submit(click)
        h.checks.cancel()
        h.checks.submit(retry)
        h.checks.cancel()
        h.checks.submit(latest)

        assertEquals("still only 1 probe queued on the worker", 1, h.background.size)
        assertEquals("no obsolete probe started beside the blocked one", listOf(transition), h.begun)

        h.runBackground()
        h.runMain()
        assertEquals("only the 1 obsolete probe ran on the worker", listOf(transition), h.blockingRan)
        assertEquals("abandoned completion neither finishes nor fails", emptyList<BubbleFieldCheck>(), h.finished + h.failed)
        assertEquals("only the latest submitted check runs next", listOf(transition, latest), h.begun)
        assertEquals(1, h.background.size)

        h.runBackground()
        h.runMain()
        assertEquals(listOf(transition, latest), h.blockingRan)
        assertEquals(listOf(latest), h.finished)
        assertEquals(emptyList<BubbleFieldCheck>(), h.failed)
        assertTrue(h.background.isEmpty() && h.main.isEmpty())
    }

    @Test
    fun anAnswerAskedAgainIsOlderThanTheCheckThatSupersededIt() {
        // The host re-asks an obsolete answer behind the newer check; the newer retry attempt keeps precedence.
        val h = Harness()
        h.onFinish = { answer -> if (answer == transition) h.checks.submitOlder(BubbleFieldCheck(transition = true, transitionUptime = 100L, retryOf = 2)) }
        h.checks.submit(transition)
        h.checks.submit(retry)
        h.runBackground()
        h.runMain()
        assertEquals(
            BubbleFieldCheck(transition = true, transitionUptime = 100L, retryOf = 3),
            h.begun.last(),
        )
        assertEquals(1, h.background.size)
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
                failed = {},
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
