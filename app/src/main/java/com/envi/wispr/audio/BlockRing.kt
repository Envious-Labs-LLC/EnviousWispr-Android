package com.envi.wispr.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * A fixed-capacity, single-producer single-consumer ring of preallocated audio blocks.
 *
 * The producer is the capture thread, and it may not do anything that can make it late: no allocation,
 * no logging, no binder call, and no lock another thread could be holding. So every slot is allocated
 * before recording starts, [offer] only copies into a slot it already owns and advances one index, and a
 * full ring is REFUSED rather than waited on.
 *
 * Refusing is the correct behaviour, not a compromise. If the consumer has fallen more than the ring
 * behind, the detector cannot catch up without skipping audio, and skipping audio breaks the model's
 * recurrent continuity, which could turn resumed speech into an early stop. The caller's answer to a
 * refusal is to give up on auto-stop for the rest of the take, never to resume across the gap.
 */
internal class BlockRing(
    private val capacity: Int,
    private val blockBytes: Int,
) {
    init {
        require(capacity > 0) { "a ring holds at least one block" }
        require(blockBytes > 0) { "a block has a size" }
    }

    private val slots = Array(capacity) { ByteArray(blockBytes) }
    private val lengths = IntArray(capacity)

    private val writeIndex = AtomicLong(0)
    private val readIndex = AtomicLong(0)

    val size: Int get() = (writeIndex.get() - readIndex.get()).toInt()

    /**
     * Producer side. Copies one block in.
     *
     * @return false when the ring is full. The producer must carry on regardless.
     */
    fun offer(source: ByteArray, length: Int): Boolean {
        require(length in 0..blockBytes) { "a block does not exceed its slot" }
        val write = writeIndex.get()
        if (write - readIndex.get() >= capacity) return false
        val slot = (write % capacity).toInt()
        System.arraycopy(source, 0, slots[slot], 0, length)
        lengths[slot] = length
        // Published last: the consumer only sees the index move after the bytes are in place.
        writeIndex.set(write + 1)
        return true
    }

    /**
     * Consumer side. Copies the oldest block out.
     *
     * @return how many bytes were written into [destination], or -1 when the ring is empty.
     */
    fun poll(destination: ByteArray): Int {
        val read = readIndex.get()
        if (read >= writeIndex.get()) return -1
        val slot = (read % capacity).toInt()
        val length = lengths[slot]
        System.arraycopy(slots[slot], 0, destination, 0, length)
        // Released last: the producer only reuses this slot after the bytes are out.
        readIndex.set(read + 1)
        return length
    }

    /** Consumer side. Drops everything queued, without touching what the producer is writing. */
    fun clear() {
        readIndex.set(writeIndex.get())
    }
}
