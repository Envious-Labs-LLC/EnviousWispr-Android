package com.envi.wispr.paste

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME. When these fail, a user's words land in their editor while a toast, a shade
 * notification and the History row all say the dictation did not arrive, or an insertion the
 * session already gave up on runs afterwards into whatever is focused by then.
 *
 * The claim is RACED rather than described: a single-threaded test passes identically against an
 * atomic claim and a check-then-act (`validation-discipline.md`
 * RULE: a-single-threaded-test-cannot-distinguish-atomic-from-check-then-act). Both interleavings
 * are staged deterministically, with latches the subject itself counts down and no sleeps.
 */
class MainThreadHandoffTest {

    private val looper = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        looper.shutdownNow()
    }

    /**
     * The interleaving the fix is for: the body claims the work, then outlives the start deadline.
     *
     * A second deadline here is what returned "the service did not answer" while the queued body
     * went on to schedule a real insertion. The caller must report what the body returned.
     *
     * Staged with no sleeps and no budget standing in for a signal. `post` returns only once the
     * body holds the claim, so the start deadline expires against a body that is already running,
     * and the caller is then released only after it is observably parked with no deadline left.
     * A caller that applied a second deadline instead TERMINATES, which is the other state this
     * waits for, so the revert fails in one read rather than by running out of budget.
     */
    @Test
    fun aCallerNeverReportsAFailureTheBodyDidNotHave() {
        val bodyStarted = CountDownLatch(1)
        val bodyMayFinish = CountDownLatch(1)
        val postReturned = CountDownLatch(1)
        val handoff = MainThreadHandoff(
            onLooperThread = { false },
            post = { runnable ->
                looper.execute(runnable)
                assertTrue(
                    "The staged body never started, so this row raced nothing",
                    bodyStarted.await(10, TimeUnit.SECONDS),
                )
                postReturned.countDown()
                true
            },
            // Zero, so the start deadline expires against the blocked body deterministically
            // rather than depending on how fast this machine is.
            startTimeoutMs = 0L,
        )

        val answer = AtomicReference<String>()
        val caller = Thread {
            answer.set(
                handoff.call("SERVICE_DID_NOT_ANSWER") {
                    bodyStarted.countDown()
                    assertTrue(
                        "The body was never released",
                        bodyMayFinish.await(10, TimeUnit.SECONDS),
                    )
                    "SCHEDULED"
                },
            )
        }
        caller.start()
        assertTrue("The handoff was never posted", postReturned.await(10, TimeUnit.SECONDS))
        assertEquals(
            "The caller stopped waiting while the body still held the claim and was scheduling " +
                "the insertion. The session would then tell the user their words did not arrive, " +
                "and rewrite the History row, while they arrived.",
            Thread.State.WAITING,
            awaitParkedOrFinished(caller),
        )
        bodyMayFinish.countDown()
        caller.join(10_000)

        assertFalse("The caller never returned", caller.isAlive)
        assertEquals("SCHEDULED", answer.get())
    }

    /**
     * The two states that end this race: parked with no deadline left, or finished. Bounded only
     * so a wedged machine fails rather than hangs; the bound is not the wait.
     */
    private fun awaitParkedOrFinished(caller: Thread): Thread.State {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val state = caller.state
            if (state == Thread.State.WAITING || state == Thread.State.TERMINATED) return state
            Thread.yield()
        }
        return caller.state
    }

    /**
     * The other interleaving: the looper never picks the work up before the deadline. The caller
     * reports its fallback AND the abandoned body must never act, or an insertion the session has
     * already finalized as interrupted runs afterwards into whatever is focused by then.
     */
    @Test
    fun anAbandonedHandoffNeverActsBehindTheAnswerAlreadyGiven() {
        val queued = AtomicReference<Runnable>()
        val handoff = MainThreadHandoff(
            onLooperThread = { false },
            post = { runnable -> queued.set(runnable); true },
            startTimeoutMs = 0L,
        )

        val bodyRan = AtomicBoolean(false)
        val answer = handoff.call("SERVICE_DID_NOT_ANSWER") {
            bodyRan.set(true)
            "SCHEDULED"
        }

        assertEquals("SERVICE_DID_NOT_ANSWER", answer)
        // The looper drains its queue a moment later, as a busy main thread does.
        queued.get().run()
        assertFalse(
            "The abandoned body ran after the caller had already answered, so an insertion the " +
                "session finalized as interrupted would complete afterwards",
            bodyRan.get(),
        )
    }

    /** On the looper thread there is no handoff at all, and no deadline can apply. */
    @Test
    fun workAlreadyOnTheLooperThreadRunsInline() {
        val handoff = MainThreadHandoff(
            onLooperThread = { true },
            post = { throw AssertionError("Work on the looper thread must not be posted") },
            startTimeoutMs = 0L,
        )
        assertEquals("SCHEDULED", handoff.call("SERVICE_DID_NOT_ANSWER") { "SCHEDULED" })
    }

    /** A looper that will never run the work answers the fallback rather than blocking forever. */
    @Test
    fun aLooperThatRefusesTheWorkAnswersTheFallback() {
        val handoff = MainThreadHandoff(
            onLooperThread = { false },
            post = { false },
            startTimeoutMs = 0L,
        )
        val bodyRan = AtomicBoolean(false)
        val answer = handoff.call("SERVICE_DID_NOT_ANSWER") {
            bodyRan.set(true)
            "SCHEDULED"
        }
        assertEquals("SERVICE_DID_NOT_ANSWER", answer)
        assertFalse(bodyRan.get())
    }
}
