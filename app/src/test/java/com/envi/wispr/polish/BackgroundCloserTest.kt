package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `BackgroundCloser` (#306): with PROCESS, close runs off the caller. Injected executors may run inline; rejection
 * uses a daemon thread if it starts. A resource close that throws an `Exception` is caught; VM errors propagate.
 * Every wait is a bounded latch or join.
 */
class BackgroundCloserTest {

    /** Records the thread its close ran on and how many times; can block inside close and can throw. */
    private class Resource(private val hold: CountDownLatch? = null, private val throwOnClose: Boolean = false) : Closeable {
        val closedOn = AtomicReference<Thread?>(null)
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        override fun close() {
            closedOn.set(Thread.currentThread())
            closes.incrementAndGet()
            entered.countDown()
            hold?.let { check(it.await(10, TimeUnit.SECONDS)) }
            if (throwOnClose) throw IllegalStateException("vendor close broke")
        }
    }

    /** Row 2: the caller returns while the resource is still closing, on a thread that is not the caller's. MUTATION m2. */
    @Test fun theCallerNeverWaitsForTheClose() {
        val hold = CountDownLatch(1)
        val resource = Resource(hold)
        val caller = thread(name = "the-caller") { BackgroundCloser.PROCESS.close(resource) }
        caller.join(5_000L)
        assertFalse("the caller returned while the close was still running", caller.isAlive)
        assertTrue("the close ran", resource.entered.await(10, TimeUnit.SECONDS))
        val closedOn = resource.closedOn.get()
        assertNotEquals("not on the caller", caller, closedOn)
        assertNotEquals("not on the test thread", Thread.currentThread(), closedOn)
        hold.countDown()
    }

    /** Row 3: a worker that refuses the task still closes the resource, once, and never on the caller. MUTATION m3. */
    @Test fun aRefusedCloseStillRunsOffTheCaller() {
        val refusing = Executor { throw RejectedExecutionException("worker gone") }
        val resource = Resource()
        BackgroundCloser(refusing).close(resource)
        assertTrue("the refused close still ran", resource.entered.await(10, TimeUnit.SECONDS))
        assertEquals(1, resource.closes.get())
        assertNotEquals("not on the caller", Thread.currentThread(), resource.closedOn.get())
    }

    /** Row 4: a close that throws an `Exception` does not escape, and the next close still runs. A direct executor, so no worker replacement can mask it. MUTATION m4. */
    @Test fun aThrowingCloseEscapesNothing() {
        val direct = BackgroundCloser(Executor { it.run() })
        val broken = Resource(throwOnClose = true)
        direct.close(broken)
        val next = Resource()
        direct.close(next)
        assertEquals(1, broken.closes.get())
        assertEquals(1, next.closes.get())
    }
}
