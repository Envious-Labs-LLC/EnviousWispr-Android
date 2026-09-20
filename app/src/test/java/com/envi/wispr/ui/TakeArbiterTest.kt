package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observability contract for the take referee (issue #176): exactly one ending per take, whatever
 * order the claims arrive in. Expected values are literal `(reason)` outcomes, never derived from the
 * arbiter. When this fails, a take reports two endings, or a cancelled take announces a failure, or a
 * destroyed take is counted as completed.
 */
class TakeArbiterTest {

    private fun arbiter(sink: MutableList<TerminalReason> = mutableListOf()) = TakeArbiter { sink += it } to sink

    @Test
    fun aFailureObservedDirectlyCommitsOnceAndLaterClaimsLose() {
        val (a, sink) = arbiter()
        assertTrue(a.commitNow(TerminalReason.AUDIO_FILE_MISSING))
        assertFalse("a second failure loses", a.commitNow(TerminalReason.ASR_CALLBACK_EXCEPTION))
        assertNull("nothing can reserve after a commit", a.reserve(Claimant.PUBLICATION))
        assertFalse("destruction cannot overwrite a committed ending", a.interrupt(TerminalReason.INTERRUPTED_PROCESSING))
        assertEquals(listOf(TerminalReason.AUDIO_FILE_MISSING), sink)
        assertEquals(TerminalReason.AUDIO_FILE_MISSING, a.committed)
        assertFalse(a.isOpen)
    }

    @Test
    fun publicationReservesThenCommitsCompletedOnlyWithItsOwnToken() {
        val (a, sink) = arbiter()
        val publication = a.reserve(Claimant.PUBLICATION)!!
        assertTrue("reserved still reads as open", a.isOpen)
        assertNull("a cancel cannot reserve over a publication", a.reserve(Claimant.CANCEL))
        assertFalse("a failure cannot commit over a reservation", a.commitNow(TerminalReason.ASR_FAILED))
        assertTrue(a.holds(publication))
        assertTrue(a.commit(publication, TerminalReason.COMPLETED))
        assertFalse("the token is spent", a.holds(publication))
        assertFalse(a.commit(publication, TerminalReason.COMPLETED))
        assertEquals(listOf(TerminalReason.COMPLETED), sink)
    }

    @Test
    fun aCancelThatOwnsTheTakeMakesALateAsrFailureSilent() {
        // The defect the arbiter exists for: the old ERROR check let an ASR error announce over a cancel.
        val (a, sink) = arbiter()
        val cancel = a.reserve(Claimant.CANCEL)!!
        assertFalse(a.commitNow(TerminalReason.ASR_FAILED))
        assertTrue(a.commit(cancel, TerminalReason.CANCELLED_PROCESSING))
        assertFalse(a.commitNow(TerminalReason.ASR_FAILED))
        assertEquals(listOf(TerminalReason.CANCELLED_PROCESSING), sink)
    }

    @Test
    fun aCancelCommitsTheCloseFailureInsteadWhenTheCloseFails() {
        val (a, sink) = arbiter()
        val cancel = a.reserve(Claimant.CANCEL)!!
        assertTrue(a.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL))
        assertEquals(listOf(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL), sink)
    }

    @Test
    fun destructionRevokesAnOutstandingPublicationAndTheWorkerCannotCommitAfterwards() {
        val (a, sink) = arbiter()
        val publication = a.reserve(Claimant.PUBLICATION)!!
        assertTrue(a.interrupt(TerminalReason.INTERRUPTED_PROCESSING))
        assertFalse("the displaced worker's token is dead", a.holds(publication))
        assertFalse(a.commit(publication, TerminalReason.COMPLETED))
        assertEquals(listOf(TerminalReason.INTERRUPTED_PROCESSING), sink)
    }

    @Test
    fun destructionOfAnOpenTakeCommitsInterruptedOnce() {
        val (a, sink) = arbiter()
        assertTrue(a.interrupt(TerminalReason.INTERRUPTED_RECORDING))
        assertFalse(a.interrupt(TerminalReason.INTERRUPTED_RECORDING))
        assertEquals(listOf(TerminalReason.INTERRUPTED_RECORDING), sink)
    }

    @Test
    fun theClosedArbiterRefusesEverythingAndReportsNothing() {
        val a = TakeArbiter.closed()
        assertFalse(a.isOpen)
        assertNull(a.reserve(Claimant.PUBLICATION))
        assertFalse(a.commitNow(TerminalReason.START_EXCEPTION))
        assertFalse(a.interrupt(TerminalReason.INTERRUPTED_STARTING))
        assertNull(a.committed)
    }

    @Test
    fun theSinkRunsOutsideTheLockSoASinkThatClaimsAgainCannotDeadlockOrDoubleCommit() {
        var reentered = false
        lateinit var a: TakeArbiter
        a = TakeArbiter { reentered = !a.commitNow(TerminalReason.COMPLETED) }
        assertTrue(a.commitNow(TerminalReason.CANCELLED_RECORDING))
        assertTrue("the re-entrant claim lost", reentered)
        assertEquals(TerminalReason.CANCELLED_RECORDING, a.committed)
    }

    @Test
    fun manyRacingClaimsYieldExactlyOneCommit() {
        val commits = AtomicInteger(0)
        val a = TakeArbiter { commits.incrementAndGet() }
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val results = (0 until threads).map { i ->
            pool.submit<Boolean> {
                ready.countDown()
                go.await()
                when (i % 4) {
                    0 -> a.commitNow(TerminalReason.ASR_FAILED)
                    1 -> a.interrupt(TerminalReason.INTERRUPTED_PROCESSING)
                    2 -> a.reserve(Claimant.PUBLICATION)?.let { a.commit(it, TerminalReason.COMPLETED) } ?: false
                    else -> a.reserve(Claimant.CANCEL)?.let { a.commit(it, TerminalReason.CANCELLED_PROCESSING) } ?: false
                }
            }
        }
        ready.await()
        go.countDown()
        val winners = results.count { it.get() }
        pool.shutdown()
        assertEquals("exactly one claim wins", 1, winners)
        assertEquals("the sink saw exactly one ending", 1, commits.get())
    }

    private object Claimant {
        const val PUBLICATION = "publication"
        const val CANCEL = "cancel"
    }
}
