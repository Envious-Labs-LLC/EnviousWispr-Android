package com.envi.wispr.vad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's refusal of work from a take that is over.
 *
 * Two feeders from different takes can be alive at once after an immediate restart, and AIDL orders calls
 * only within one client thread, so their calls can arrive in either order. Guarding each site an old
 * caller might reach is a description of a population; refusing anything older is the population.
 */
class CaptureTokenOrderTest {

    @Test
    fun aLateStartFromAnOlderTakeCannotReplaceTheCurrentOne() {
        val order = CaptureTokenOrder()
        assertTrue(order.accept(200L))
        assertFalse("an older take is refused", order.accept(100L))
        assertFalse("and so is the same one twice", order.accept(200L))
        assertTrue("a genuinely newer take is accepted", order.accept(300L))
    }

    @Test
    fun theVeryFirstTakeIsAccepted() {
        assertTrue(CaptureTokenOrder().accept(1L))
    }

    @Test
    fun onceRefusedAnOldTakeStaysRefusedHoweverOftenItAsks() {
        val order = CaptureTokenOrder()
        order.accept(500L)
        repeat(20) { assertFalse(order.accept(499L - it)) }
        assertTrue(order.accept(501L))
    }

    @Test
    fun acceptingDoesNotMoveBackwardsEvenWhenTheRefusedTokenIsLarge() {
        // A refused token must not become the new high-water mark, or one stale caller with a big token
        // would lock out every real take after it.
        val order = CaptureTokenOrder()
        assertTrue(order.accept(100L))
        assertTrue(order.accept(101L))
        assertFalse(order.accept(100L))
        assertTrue("the order still advances normally afterwards", order.accept(102L))
    }
}
