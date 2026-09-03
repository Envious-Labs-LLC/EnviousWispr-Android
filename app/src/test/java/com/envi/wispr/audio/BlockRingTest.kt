package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockRingTest {

    private fun block(fill: Byte, size: Int = 16) = ByteArray(size) { fill }

    @Test
    fun blocksComeOutInTheOrderTheyWentIn() {
        val ring = BlockRing(capacity = 4, blockBytes = 16)
        (1..4).forEach { assertTrue(ring.offer(block(it.toByte()), 16)) }

        val out = ByteArray(16)
        (1..4).forEach { expected ->
            assertEquals(16, ring.poll(out))
            assertEquals("order must be preserved", expected.toByte(), out[0])
        }
        assertEquals(-1, ring.poll(out))
    }

    @Test
    fun aFullRingRefusesRatherThanBlocksOrOverwrites() {
        // The capture thread cannot wait, and it cannot be allowed to clobber a block the detector has
        // not read. Refusing is what lets the caller give up on auto-stop instead of losing audio.
        val ring = BlockRing(capacity = 2, blockBytes = 16)
        assertTrue(ring.offer(block(1), 16))
        assertTrue(ring.offer(block(2), 16))
        assertFalse("the third offer must be refused", ring.offer(block(3), 16))

        val out = ByteArray(16)
        ring.poll(out)
        assertEquals("the refused block must not have overwritten the queued one", 1.toByte(), out[0])
        ring.poll(out)
        assertEquals(2.toByte(), out[0])
    }

    @Test
    fun makingRoomLetsTheProducerContinue() {
        val ring = BlockRing(capacity = 2, blockBytes = 16)
        ring.offer(block(1), 16)
        ring.offer(block(2), 16)
        assertFalse(ring.offer(block(3), 16))

        val out = ByteArray(16)
        ring.poll(out)
        assertTrue("a freed slot is reusable", ring.offer(block(3), 16))
    }

    @Test
    fun clearingDropsTheQueueWithoutLosingLaterBlocks() {
        val ring = BlockRing(capacity = 4, blockBytes = 16)
        ring.offer(block(1), 16)
        ring.offer(block(2), 16)
        ring.clear()
        assertEquals(0, ring.size)

        val out = ByteArray(16)
        assertEquals(-1, ring.poll(out))
        assertTrue(ring.offer(block(9), 16))
        assertEquals(16, ring.poll(out))
        assertEquals(9.toByte(), out[0])
    }

    @Test
    fun aProducerAndConsumerRunningAtOnceNeverTearABlock() {
        // A block half written by the producer and half read by the consumer would be audio that never
        // existed, and the detector would score it. Raced, because a single-threaded test cannot see it.
        val blockBytes = 64
        val ring = BlockRing(capacity = 8, blockBytes = blockBytes)
        val total = 20_000
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        var torn = 0
        var received = 0

        val producer = Thread {
            start.await()
            var value = 1
            var sent = 0
            while (sent < total) {
                // Every byte of a block carries the same value, so any mixture is a tear.
                if (ring.offer(ByteArray(blockBytes) { value.toByte() }, blockBytes)) {
                    sent++
                    value = if (value == 127) 1 else value + 1
                }
            }
            finished.countDown()
        }

        val consumer = Thread {
            start.await()
            val out = ByteArray(blockBytes)
            while (received < total) {
                if (ring.poll(out) > 0) {
                    val first = out[0]
                    if (out.any { it != first }) torn++
                    received++
                }
            }
            finished.countDown()
        }

        producer.start()
        consumer.start()
        start.countDown()
        assertTrue("both threads finished", finished.await(30, TimeUnit.SECONDS))
        assertEquals("no block may be read half written", 0, torn)
        assertEquals(total, received)
    }
}
