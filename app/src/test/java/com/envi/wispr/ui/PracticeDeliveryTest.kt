package com.envi.wispr.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Product Outcome: another take cannot control practice, and competing endings deliver once. */
class PracticeDeliveryTest {
    @Test fun commandsBelongOnlyToTheActiveRequest() {
        val destination = PracticeDelivery("practice-1") { _, _ -> }
        assertTrue(destination.acceptsCommand("practice-1"))
        assertFalse(destination.acceptsCommand("another-take"))
        destination.update(PracticeDelivery.ENDED, terminalEvent = true)
        assertFalse(destination.acceptsCommand("practice-1"))
    }

    @Test fun aLateCancellationCannotReplaceDeliveredWords() {
        val received = mutableListOf<Pair<Int, String>>()
        val destination = PracticeDelivery("practice-1") { code, text -> received += code to text }
        destination.update(PracticeDelivery.FINISHED, "Call Grandma.", terminalEvent = true)
        destination.update(PracticeDelivery.ENDED, terminalEvent = true)
        destination.update(PracticeDelivery.FINISHED, "Old result", terminalEvent = true)
        assertEquals(listOf(PracticeDelivery.FINISHED to "Call Grandma."), received)
    }

    @Test fun competingTerminalCallbacksHaveOneWinner() {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val destination = PracticeDelivery("practice-1") { code, _ -> received += code }
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        listOf(PracticeDelivery.FINISHED, PracticeDelivery.ENDED).forEach { code ->
            Thread {
                try { start.await(); destination.update(code, "Words", terminalEvent = true) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue("Both terminal callbacks must return", done.await(5, TimeUnit.SECONDS))
        assertEquals(1, received.size)
    }
}
