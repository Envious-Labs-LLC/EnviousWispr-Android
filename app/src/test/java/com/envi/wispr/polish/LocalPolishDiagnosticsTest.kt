package com.envi.wispr.polish

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** Observability Contract: timings must identify the observed phase, including a wedged native call. */
class LocalPolishDiagnosticsTest {
    @Test fun completionDuringClockSamplingUsesTheFinishedSpanForReturnAndThrow() {
        for (throws in listOf(false, true)) {
            val clock = AtomicLong(100)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            val snapshotThread = Thread.currentThread()
            lateinit var completed: java.util.concurrent.Future<Boolean>
            val timing = LocalPolishTiming(0, S1RuntimeKind.PORTABLE_CPU, null) {
                if (Thread.currentThread() === snapshotThread) {
                    clock.set(125)
                    release.countDown()
                    assertEquals(throws, completed.get(3, TimeUnit.SECONDS))
                    900L
                } else clock.get()
            }
            try {
                completed = worker.submit<Boolean> {
                    runCatching {
                        timing.measureGeneration {
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS)) { "Fixture generation was never released" }
                            if (throws) error("fixture")
                        }
                    }.isFailure
                }
                assertTrue("Generation never reached its gate", entered.await(3, TimeUnit.SECONDS))
                assertEquals(25L, timing.snapshot().generationMs)
            } finally {
                release.countDown()
                worker.shutdownNow()
            }
        }
    }
    @Test fun unstartedGenerationIsAbsentAndHistoricalLoadIsAged() {
        val timing = LocalPolishTiming(7, S1RuntimeKind.PORTABLE_CPU, S1LoadTiming(120, 200, 3)) { 250 }
        assertEquals(
            LocalPolishDiagnostics(7, null, 120, 50, 3, S1RuntimeKind.PORTABLE_CPU),
            timing.snapshot(),
        )
        assertNull(LocalPolishTiming(0, null, null) { 10 }.snapshot().lastLoadMs)
    }

    @Test fun completedAndThrowingCallsExcludeLaterRequestWork() {
        for (throws in listOf(false, true)) {
            var clock = 100L
            val timing = LocalPolishTiming(4, S1RuntimeKind.PORTABLE_CPU, null) { clock }
            val result = runCatching {
                timing.measureGeneration {
                    clock = 125L
                    if (throws) error("fixture")
                    "answer"
                }
            }
            assertEquals(throws, result.isFailure)
            clock = 900L
            assertEquals(25L, timing.snapshot().generationMs)
        }
    }

    @Test fun expiryCanSnapshotAStillRunningCallWithoutWaitingAndNeverChangesItsFrozenValue() {
        val clock = AtomicLong(10)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val timing = LocalPolishTiming(8, S1RuntimeKind.PORTABLE_CPU, null, clock::get)
        try {
            val completed = worker.submit<String> {
                timing.measureGeneration {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS)) { "Fixture generation was never released" }
                    "answer"
                }
            }
            assertTrue("Generation never entered its gated call", entered.await(3, TimeUnit.SECONDS))
            clock.set(40)
            val expired = timing.snapshot()
            assertEquals(30L, expired.generationMs)
            clock.set(50)
            release.countDown()
            assertEquals("answer", completed.get(3, TimeUnit.SECONDS))
            clock.set(900)
            assertEquals(40L, timing.snapshot().generationMs)
            assertEquals("A published expiry observation is immutable", 30L, expired.generationMs)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
