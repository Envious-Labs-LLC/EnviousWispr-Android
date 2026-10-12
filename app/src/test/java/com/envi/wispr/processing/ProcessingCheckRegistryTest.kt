package com.envi.wispr.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Product Outcome: cancelled checks must not enable a processing option afterward. */
class ProcessingCheckRegistryTest {
    @Test fun cancelBeforePublicationRefusesSuccess() {
        val r = ProcessingCheckRegistry(); val e = r.register(1)!!
        r.cancel(1)
        assertTrue(e.isCancelled)
        assertFalse(e.deliverOnce { fail("Cancelled success") })
        assertNull("A cancelled queued operation still owns its id", r.register(1))
        r.release(e)
        assertNotNull(r.register(1))
    }
    @Test fun completedCallbackCannotBeDeliveredTwice() {
        val e = ProcessingCheckRegistry().register(2)!!; var count = 0
        assertTrue(e.deliverOnce { count++ }); assertFalse(e.deliverOnce { count++ })
        assertEquals(1, count)
    }
    @Test fun allCancelledEntriesRemainUnpublishable() {
        val r = ProcessingCheckRegistry(); val a = r.register(1)!!; val b = r.register(2)!!
        r.cancelAll()
        assertFalse(a.deliverOnce { fail() }); assertFalse(b.deliverOnce { fail() })
    }
    @Test fun publicationAndCancelHaveOneWinner() {
        repeat(30) {
            val r = ProcessingCheckRegistry(); val e = r.register(1)!!
            val start = CountDownLatch(1); val done = CountDownLatch(2)
            var calls = 0
            Thread { assertTrue(start.await(5, TimeUnit.SECONDS)); e.deliverOnce { calls++ }; done.countDown() }.start()
            Thread { assertTrue(start.await(5, TimeUnit.SECONDS)); r.cancel(1); done.countDown() }.start()
            start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(if (e.isCancelled) 0 else 1, calls)
        }
    }
}
