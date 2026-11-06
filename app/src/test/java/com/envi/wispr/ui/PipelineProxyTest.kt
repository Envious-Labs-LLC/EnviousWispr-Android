package com.envi.wispr.ui

import com.envi.wispr.polish.PolishOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/**
 * Harness Contract. The binder stubs are transport only (#253): a speech or polish callback's values are posted to main, and the
 * owner's listener runs only when main runs the post, never on the binder thread that received them. Driven
 * through the same `PostingSpeechListener` and `PostingPolishListener` the production proxies wrap each Stub
 * around, with a recording post in place of the main `Handler`.
 */
class PipelineProxyTest {
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val heard = CopyOnWriteArrayList<String>()

    private fun onForeignThread(block: () -> Unit) = Thread(block, "binder-1").apply { start(); join(10_000) }

    /** MUTATION: call the speech listener directly in `PostingSpeechListener`. */
    @Test fun speechCallbacksReachTheListenerOnlyThroughThePost() {
        val listener = object : SpeechListener {
            override fun onResult(text: String?) { heard += "result:$text on ${Thread.currentThread().name}" }
            override fun onError(message: String?) { heard += "error on ${Thread.currentThread().name}" }
            override fun onFailure(reason: Int, detail: String?) { heard += "failure:$reason on ${Thread.currentThread().name}" }
        }
        val posting = PostingSpeechListener({ posted += it }, listener)
        onForeignThread { posting.onResult("hello"); posting.onError("x"); posting.onFailure(3, "d") }
        assertTrue("nothing reached the listener on the binder thread: $heard", heard.isEmpty())
        assertEquals(3, posted.size)
        posted.forEach { it.run() }
        assertEquals(listOf("result:hello on Test worker", "error on Test worker", "failure:3 on Test worker").map { it.substringBefore(" on ") }, heard.map { it.substringBefore(" on ") })
        assertTrue("the listener ran where the post ran", heard.none { it.endsWith("binder-1") })
    }

    /** MUTATION: call the polish listener directly in `PostingPolishListener`. */
    @Test fun polishCallbacksReachTheListenerOnlyThroughThePost() {
        val listener = object : PolishListener {
            override fun onOutcome(outcome: PolishOutcome?) { heard += "outcome on ${Thread.currentThread().name}" }
        }
        val posting = PostingPolishListener({ posted += it }, listener)
        onForeignThread { posting.onOutcome(null) }
        assertTrue("nothing reached the listener on the binder thread: $heard", heard.isEmpty())
        assertEquals(1, posted.size)
        posted.forEach { it.run() }
        assertEquals(listOf("outcome"), heard.map { it.substringBefore(" on ") })
        assertTrue("the listener ran where the post ran", heard.none { it.endsWith("binder-1") })
    }

    /** Same controlled executor shape as PolishFallbackLaneTest; timers are not completion oracles. */
    private class QueueWorker : AbstractExecutorService() {
        val queued = java.util.ArrayDeque<Runnable>()
        private var down = false
        override fun execute(command: Runnable) { if (down) throw RejectedExecutionException("closed"); queued.addLast(command) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        override fun shutdown() { down = true }
        override fun shutdownNow(): MutableList<Runnable> = queued.toMutableList().also { down = true; queued.clear() }
        override fun isShutdown() = down
        override fun isTerminated() = down && queued.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class Protocol {
        val failures = CopyOnWriteArrayList<Throwable>()
        val workers = CopyOnWriteArrayList<QueueWorker>()
        val heard = CopyOnWriteArrayList<String>()
        val cores = CopyOnWriteArrayList<PreparedSpeechSubmission>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cancels = java.util.concurrent.atomic.AtomicInteger()
        var reject = false
        val port = SpeechCancelPort(failed = { failures += it }, makeWorker = {
            QueueWorker().also { if (reject) it.shutdown(); workers += it }
        })
        fun prepare(issue: (SpeechListener) -> Unit = {}, cancel: () -> Unit = {}): PreparedSpeechSubmission {
            val listener = object : SpeechListener {
                override fun onResult(text: String?) { heard += "result" }
                override fun onError(message: String?) { heard += "error" }
                override fun onFailure(reason: Int, detail: String?) { heard += "failure" }
            }
            return PreparedSpeechSubmission(listener, issue = { calls.incrementAndGet(); issue(it) },
                cancelRemote = { cancels.incrementAndGet(); cancel() }, port = port,
                registrationLease = port.lease(), tailLease = port.lease(), settled = {}).also { cores += it }
        }
        fun runCommands() { workers.forEach { it.runAll() } }
        fun close() { cores.forEach { it.cancel() }; port.close(); runCommands() }
    }
    private val protocols = mutableListOf<Protocol>()
    private fun protocol() = Protocol().also { protocols += it }
    @After fun closeProtocolFixtures() { protocols.forEach { it.close() } }

    @Test fun cancellingBeforeSubmitPreventsIssueAndAnUnissuedCallbackCannotPostWords() {
        val p = protocol()
        val submission = p.prepare()
        submission.onResult("unissued")
        assertTrue(p.heard.isEmpty())
        submission.cancel()
        submission.submit()
        p.port.close()
        assertEquals(0, p.calls.get())
        assertEquals(0, p.cancels.get())
        assertTrue(p.workers.isEmpty())
        assertTrue(p.port.closedAndDrained)
    }

    @Test fun aModernAnsweredTailCanCancelAfterTheProxyPortCloses() {
        val p = protocol()
        val submission = p.prepare(issue = { it.onResult("words") })
        submission.submit()
        assertEquals(listOf("result"), p.heard.toList())
        assertTrue(p.workers.isEmpty())
        p.port.close()
        assertFalse(p.port.closedAndDrained)
        submission.cancel()
        assertFalse("An enqueued action has not actually returned", p.port.closedAndDrained)
        p.runCommands()
        assertEquals(1, p.cancels.get())
        assertTrue(p.port.closedAndDrained)
        submission.cancel()
        assertEquals(1, p.cancels.get())
        assertTrue(p.failures.isEmpty())
    }

    @Test fun callbackThenCloseDuringHeldSubmitPreservesTheExactPostReturnCancel() {
        val p = protocol()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        val submission = p.prepare(issue = {
            it.onResult("words")
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Held submit was not released" }
        })
        val returned = caller.submit { submission.submit() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            submission.cancel()
            p.port.close()
            assertFalse(p.port.closedAndDrained)
            assertTrue("Return debt prevents premature cancel queueing", p.workers.isEmpty())
            release.countDown()
            returned.get(5, TimeUnit.SECONDS)
            assertFalse(p.port.closedAndDrained)
            p.runCommands()
            assertEquals(1, p.cancels.get())
            assertEquals(listOf("result"), p.heard.toList())
            assertTrue(p.port.closedAndDrained)
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun aThrowAfterCallbackPropagatesTheSameExceptionWithoutLosingCancellation() {
        val p = protocol()
        val original = IllegalStateException("issue failed")
        val submission = p.prepare(issue = { it.onResult("words"); throw original })
        val caught = org.junit.Assert.assertThrows(IllegalStateException::class.java) { submission.submit() }
        org.junit.Assert.assertSame(original, caught)
        assertEquals(listOf("result"), p.heard.toList())
        submission.cancel()
        p.port.close()
        p.runCommands()
        assertEquals(1, p.cancels.get())
        assertTrue(p.port.closedAndDrained)
    }

    @Test fun aReturnedUnansweredLegacyRequestStillRetainsCancellationThroughClose() {
        val p = protocol()
        val submission = p.prepare()
        submission.submit()
        submission.releaseUnexposedHandle()
        p.port.close()
        assertFalse(p.port.closedAndDrained)
        submission.cancel()
        p.runCommands()
        assertEquals(1, p.cancels.get())
        assertTrue(p.port.closedAndDrained)
    }

    @Test fun aRejectedCancellationQueueKeepsLocalRevocationAndReportsControlFailure() {
        val p = protocol().also { it.reject = true }
        val submission = p.prepare()
        submission.submit()
        submission.cancel()
        submission.onResult("late")
        p.port.close()
        assertTrue(p.heard.isEmpty())
        assertEquals(0, p.cancels.get())
        assertEquals(1, p.failures.size)
        assertTrue(p.failures.single() is RejectedExecutionException)
        assertTrue("Only local obligations drained, not a native-retirement acknowledgment", p.port.closedAndDrained)
    }

    @Test fun aHeldCancellationRpcCannotBeReportedDrainedByClose() {
        val p = protocol()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = Executors.newSingleThreadExecutor()
        val submission = p.prepare(cancel = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Held cancel was not released" }
        })
        submission.submit()
        submission.cancel()
        p.port.close()
        val returned = controller.submit { p.runCommands() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(p.port.closedAndDrained)
            submission.cancel()
            p.port.close()
            assertFalse(p.port.closedAndDrained)
            release.countDown()
            returned.get(5, TimeUnit.SECONDS)
            assertEquals(1, p.cancels.get())
            assertTrue(p.port.closedAndDrained)
        } finally {
            release.countDown()
            controller.shutdownNow()
            assertTrue(controller.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun duplicateCallbacksCannotPostAnotherAnswerOrReopenTheReturnState() {
        val p = protocol()
        val submission = p.prepare(issue = { it.onResult("words"); it.onFailure(1, "late"); it.onError("late") })
        submission.submit()
        assertEquals(listOf("result"), p.heard.toList())
        submission.cancel()
        p.port.close()
        p.runCommands()
        assertEquals(1, p.cancels.get())
        assertTrue(p.port.closedAndDrained)
    }

    @Test fun aSavedCloseSnapshotOfACompletedLegacyRequestHasNoReleasedLeaseToReserve() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val workers = CopyOnWriteArrayList<QueueWorker>()
        val port = SpeechCancelPort(failed = { failures += it }, makeWorker = { QueueWorker().also { workers += it } })
        val settling = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackThread = Executors.newSingleThreadExecutor()
        val listener = object : SpeechListener {
            override fun onResult(text: String?) { }
            override fun onError(message: String?) { }
            override fun onFailure(reason: Int, detail: String?) { }
        }
        val submission = PreparedSpeechSubmission(listener, issue = {}, cancelRemote = { error("Completed unexposed v1 request has no tail owner") },
            port = port, registrationLease = port.lease(), tailLease = port.lease(), settled = {
                settling.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Settlement gate was not released" }
            })
        submission.submit()
        submission.releaseUnexposedHandle()
        val callback = callbackThread.submit { submission.onResult("synthetic") }
        try {
            assertTrue(settling.await(5, TimeUnit.SECONDS))
            submission.cancel() // Proxy.close may already hold this exact snapshot after local settlement.
            port.close()
            assertTrue(port.closedAndDrained)
            assertTrue(workers.isEmpty())
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            try { callback.get(5, TimeUnit.SECONDS) } finally {
                callbackThread.shutdownNow()
                assertTrue(callbackThread.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
