package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, a cancel stops the wrong request, or none, or a request's token
 * is silently shared or dropped, so a cloud call the user abandoned keeps running or a live one is
 * cut short.
 */
class PolishRequestRegistryTest {

    @Test fun cancelStopsExactlyTheNamedRequest() {
        val registry = PolishRequestRegistry()
        val queued = registry.register(1L)!!
        val running = registry.register(2L)!!
        registry.cancel(1L)
        assertTrue(queued.cancellation.isCancelled)
        assertFalse("the other request must be untouched", running.cancellation.isCancelled)
        assertEquals(1, registry.size)
    }

    @Test fun repeatedAndUnknownCancelsAreNoOps() {
        val registry = PolishRequestRegistry()
        val entry = registry.register(7L)!!
        registry.cancel(7L)
        registry.cancel(7L)
        registry.cancel(99L)
        assertTrue(entry.cancellation.isCancelled)
        assertEquals(0, registry.size)
    }

    @Test fun aDeliveredRequestIsReleasedAndALaterCancelDoesNothing() {
        val registry = PolishRequestRegistry()
        val entry = registry.register(3L)!!
        registry.release(entry)
        registry.cancel(3L)
        assertFalse(entry.cancellation.isCancelled)
        assertEquals(0, registry.size)
    }

    @Test fun aCollidingIdIsRefusedRatherThanSharingAToken() {
        val registry = PolishRequestRegistry()
        assertNotNull(registry.register(4L))
        assertNull(registry.register(4L))
        assertEquals(1, registry.size)
    }

    @Test fun releaseRemovesOnlyTheEntryItWasGiven() {
        // An older request's finally block must not remove a newer registration under the same id.
        val registry = PolishRequestRegistry()
        val older = registry.register(5L)!!
        registry.cancel(5L)
        val newer = registry.register(5L)!!
        registry.release(older)
        assertEquals("the newer entry survives", 1, registry.size)
        registry.release(newer)
        assertEquals(0, registry.size)
    }

    @Test fun cancelAllCancelsEveryOutstandingToken() {
        val registry = PolishRequestRegistry()
        val entries = (10L..14L).map { registry.register(it)!! }
        registry.cancelAll()
        assertTrue(entries.all { it.cancellation.isCancelled })
        assertEquals(0, registry.size)
    }
}
