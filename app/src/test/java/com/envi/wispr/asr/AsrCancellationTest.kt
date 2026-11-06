package com.envi.wispr.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Harness Contract: request cancellation and native-control lifetime; real ORT qualification is separate. */
class AsrCancellationTest {
    @Test fun cancellationBeforeNativeAttachmentPreventsWork() {
        val token = AsrCancellation()
        token.cancel()
        assertThrows(AsrCancelled::class.java) { token.check() }
        assertThrows(AsrCancelled::class.java) { token.attach { error("No native scope exists") } }
    }

    @Test fun liveControlIsTerminatedOnceAndTheNextRequestStartsClean() {
        val token = AsrCancellation()
        val terminations = AtomicInteger()
        val lease = token.attach { terminations.incrementAndGet() }
        token.cancel()
        token.cancel()
        assertTrue(token.isCancelled)
        assertEquals(1, terminations.get())
        lease.close()
        lease.close()
        token.cancel()
        assertEquals(1, terminations.get())
        assertFalse(AsrCancellation().isCancelled)
    }

    @Test fun detachedNativeOptionsAreNeverTerminated() {
        val token = AsrCancellation()
        token.attach { error("Options have already been detached for worker close") }.close()
        token.cancel()
        assertTrue(token.isCancelled)
    }

    @Test fun aStaleLeaseCannotDetachANewerScopeEvenWithTheSameCallback() {
        val token = AsrCancellation()
        val calls = AtomicInteger()
        val terminate = { calls.incrementAndGet(); Unit }
        val old = token.attach(terminate)
        old.close()
        val current = token.attach(terminate)
        old.close()
        token.cancel()
        assertEquals(1, calls.get())
        old.close()
        current.close()
    }

    @Test fun terminationFailureStillPermanentlyCancelsThisRequest() {
        val token = AsrCancellation()
        val lease = token.attach { error("control failed") }
        assertThrows(IllegalStateException::class.java) { token.cancel() }
        assertTrue(token.isCancelled)
        token.cancel()
        lease.close()
        assertThrows(AsrCancelled::class.java) { token.check() }
    }

    @Test fun theWorkerCannotFinishDetachingWhileNativeTerminationStillUsesTheOptions() {
        val token = AsrCancellation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val lease = token.attach {
            entered.countDown()
            assertTrue("The held native control was not released", release.await(5, TimeUnit.SECONDS))
        }
        try {
            val cancelling = workers.submit { token.cancel() }
            assertTrue("Native control did not enter", entered.await(5, TimeUnit.SECONDS))
            token.requestCancellation() // Must not wait for the deliberately held native-control monitor.
            assertTrue(token.isCancelled)
            val detached = workers.submit { closing.countDown(); lease.close() }
            assertTrue("Worker did not attempt detachment", closing.await(5, TimeUnit.SECONDS))
            // Bounded observation of deliberately HELD control, not a guessed completion or native-latency claim.
            assertThrows(TimeoutException::class.java) { detached.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            cancelling.get(5, TimeUnit.SECONDS)
            detached.get(5, TimeUnit.SECONDS)
            assertTrue(token.isCancelled)
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue("Fixture workers did not retire", workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun logicalCancellationPrecedesQueuedNativeControlAndRemainsRequestScoped() {
        val token = AsrCancellation()
        var controls = 0
        val lease = token.attach { controls++ }
        token.requestCancellation()
        assertThrows(AsrCancelled::class.java) { token.check() }
        assertEquals(0, controls)
        token.terminateNative()
        token.terminateNative()
        assertEquals(1, controls)
        lease.close()
        assertFalse(AsrCancellation().isCancelled)
    }

    @Test fun nativeCleanupAttemptsEveryCloserAndKeepsATypedFailure() {
        val closed = mutableListOf<Int>()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val failure = assertThrows(AsrCleanupFailure::class.java) {
            closeAsrNative(listOf(
                AutoCloseable { closed += 1; throw first },
                AutoCloseable { closed += 2; throw second },
                AutoCloseable { closed += 3 },
            ))
        }
        assertEquals(listOf(1, 2, 3), closed)
        org.junit.Assert.assertSame(first, failure.cause)
        assertTrue(failure.suppressed.contains(second))
    }

    @Test fun aCleanupFailureCannotBeMistakenForTheDecodeErrorItFollowed() {
        val decode = IllegalArgumentException("decode")
        val close = IllegalStateException("close")
        val failure = assertThrows(AsrCleanupFailure::class.java) {
            AutoCloseable { throw close }.useAsrNative { throw decode }
        }
        org.junit.Assert.assertSame(close, failure.cause)
        assertTrue(failure.suppressed.contains(decode))
        val ordinary = assertThrows(IllegalArgumentException::class.java) {
            AutoCloseable { }.useAsrNative { throw decode }
        }
        org.junit.Assert.assertSame(decode, ordinary)
    }
}
