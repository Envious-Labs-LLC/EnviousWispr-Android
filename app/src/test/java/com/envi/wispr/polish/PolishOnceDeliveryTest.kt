package com.envi.wispr.polish

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, the same dictation is published twice, or the engine's second
 * attempt after a throwing binder callback escapes the worker and nothing is published at all.
 */
class PolishOnceDeliveryTest {

    @Test fun twoAttemptsInvokeTheCallbackOnce() {
        val entry = PolishRequestRegistry().register(1L)!!
        val calls = AtomicInteger()
        assertTrue(entry.deliverOnce { calls.incrementAndGet() })
        assertFalse(entry.deliverOnce { calls.incrementAndGet() })
        assertEquals(1, calls.get())
    }

    @Test fun aThrowingFirstDeliveryStillCountsAndDoesNotEscape() {
        val entry = PolishRequestRegistry().register(2L)!!
        val calls = AtomicInteger()
        val delivered = entry.deliverOnce {
            calls.incrementAndGet()
            throw IllegalStateException("client process gone")
        }
        assertTrue("the attempt happened", delivered)
        assertFalse("no retry after a throw", entry.deliverOnce { calls.incrementAndGet() })
        assertEquals(1, calls.get())
    }

    @Test fun racingDeliveriesFromTwoThreadsInvokeTheCallbackOnce() {
        val entry = PolishRequestRegistry().register(3L)!!
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                entry.deliverOnce { calls.incrementAndGet() }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, calls.get())
    }
}
