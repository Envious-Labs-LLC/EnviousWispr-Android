package com.envi.wispr.recovery

import com.envi.wispr.audio.BlockRing
import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.audio.RecordingLimits
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Optional per-take feed. The capture producer only copies into the existing preallocated ring. */
internal class RecoveryAudioWriter private constructor(private val lease: RecoveryAudioStore.AudioWriterLease) {
    private data class Ending(val bytePosition: Long, val complete: Boolean)

    internal data class Outcome(
        val firstSample: Long?,
        val sampleCount: Int,
        val completedCapture: Boolean,
        val released: RecoveryClaimRelease,
        val failed: Boolean,
    )

    private val ring = BlockRing(64, PcmAudio.READ_CHUNK_BYTES)
    private val block = ByteArray(PcmAudio.READ_CHUNK_BYTES)
    private val assembly = ByteArray(RecoveryFrameLimits.PAYLOAD_BYTES)
    private val ending = AtomicReference<Ending?>()
    private val abandoned = AtomicBoolean()
    val completion = CompletableFuture<Outcome>()
    private val worker = Thread({ runOnWorker() }, "ProtectedRecordingWriter").apply { isDaemon = true }

    /** Capture thread only: no disk, crypto, Binder, allocation or consumer-held lock. */
    fun offer(buffer: ByteArray, bytes: Int, bytePosition: Long) {
        if (abandoned.get() || ending.get() != null) return
        if (bytes <= 0 || bytes > PcmAudio.READ_CHUNK_BYTES || bytes > buffer.size || bytes % 2 != 0 ||
            bytePosition < 0L || bytePosition % 2 != 0L || bytePosition > RecordingLimits.MAX_AUDIO_BYTES - bytes ||
            !ring.offer(buffer, bytes, bytePosition)
        ) abandoned.set(true)
        LockSupport.unpark(worker)
    }

    /** Called after the capture producer has exited. It requests drain; completion is actual close. */
    fun finishOnCaptureClosed(bytePosition: Long, complete: Boolean) {
        ending.compareAndSet(null, Ending(bytePosition, complete))
        LockSupport.unpark(worker)
    }

    private fun runOnWorker() {
        var pending = 0
        var assemblyStart = 0L
        var nextPosition = -1L
        var completed = false
        var failed = false
        try {
            while (!abandoned.get()) {
                val length = ring.poll(block)
                if (length > 0) {
                    val position = ring.lastPolledTag
                    if (nextPosition != -1L && position != nextPosition) {
                        abandoned.set(true)
                        break
                    }
                    nextPosition = position + length
                    var consumed = 0
                    while (consumed < length) {
                        if (pending == 0) assemblyStart = (position + consumed) / PcmAudio.BYTES_PER_SAMPLE
                        val count = minOf(length - consumed, assembly.size - pending)
                        block.copyInto(assembly, pending, consumed, consumed + count)
                        pending += count
                        consumed += count
                        if (pending == assembly.size) {
                            lease.appendOnWorker(assemblyStart, assembly)
                            pending = 0
                        }
                    }
                    continue
                }
                val end = ending.get()
                if (end != null) {
                    // The producer may have published its final block after the empty poll above.
                    if (ring.size > 0) continue
                    if (pending > 0) {
                        val tail = assembly.copyOf(pending)
                        try { lease.appendOnWorker(assemblyStart, tail) } finally { tail.fill(0) }
                    }
                    if (end.complete && nextPosition == end.bytePosition) {
                        completed = lease.completeOnWorker(end.bytePosition / PcmAudio.BYTES_PER_SAMPLE)
                    }
                    break
                }
                LockSupport.park(this)
            }
        } catch (_: Throwable) {
            failed = true
        } finally {
            block.fill(0)
            assembly.fill(0)
            ring.clear()
            val released = runCatching { lease.closeOnWorker() }.getOrDefault(RecoveryClaimRelease.UNCERTAIN)
            completion.complete(Outcome(lease.firstSample, lease.sampleCount, completed, released, failed || abandoned.get()))
        }
    }

    companion object {
        /** Factory worker transfers the lease only to a thread whose actual exit owns its close. */
        fun startOnWorker(lease: RecoveryAudioStore.AudioWriterLease): RecoveryAudioWriter? {
            var writer: RecoveryAudioWriter? = null
            return try {
                writer = RecoveryAudioWriter(lease)
                writer.worker.start()
                writer
            } catch (_: Throwable) {
                if (writer == null || writer.worker.state == Thread.State.NEW) {
                    lease.closeOnWorker()
                } else {
                    writer.abandoned.set(true)
                    LockSupport.unpark(writer.worker)
                }
                null
            }
        }
    }
}
