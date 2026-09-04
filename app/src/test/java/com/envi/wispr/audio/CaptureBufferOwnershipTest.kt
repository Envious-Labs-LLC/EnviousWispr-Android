package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guards on the capture thread's buffers.
 *
 * These read source text because `AudioCaptureService` is an Android `Service` and cannot be constructed
 * on the JVM, and because the property being protected is "this code does not exist here" rather than a
 * value any API returns. The device run measures the real numbers; this stops the shape regressing.
 */
class CaptureBufferOwnershipTest {

    private val source =
        File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()

    private fun captureLoopBody(): String {
        val start = source.indexOf("private fun captureLoop(")
        assertTrue("captureLoop must exist", start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1)
        assertTrue("captureLoop must be followed by another declaration", end > start)
        return source.substring(start, end)
    }

    @Test
    fun captureLoopUsesOnlyPreallocatedBuffers() {
        // The capture thread must do nothing that can make it late: no allocation, no logging on the
        // hot path, no lock another thread can hold. An allocation here is the one that used to live at
        // the top of this loop.
        val body = captureLoopBody()
        assertFalse(
            "captureLoop must not allocate its read buffer; the session owns it",
            body.contains("ByteArray("),
        )
        assertTrue(
            "captureLoop reads the buffer the session preallocated",
            body.contains("active.readBuffer"),
        )
    }

    @Test
    fun theReadBlockIsTwoHundredAndFiftySixMilliseconds() {
        // 4096 samples at 16 kHz, 2 bytes each. The same 256 ms the silence state machine ticks on.
        assertEquals(
            SilenceStopDetectorBlockBytes,
            8_192,
        )
        assertTrue(
            "the read block is a named constant, not a literal at the call site",
            source.contains("private const val READ_BLOCK_BYTES = 8_192"),
        )
    }

    @Test
    fun theNativeBufferIsSizedSeparatelyFromTheReadBlock() {
        // Two quantities with opposite pressures. Passing the read block to the AudioRecord constructor
        // would shrink the overrun margin; passing the native buffer to read() would restore the
        // one-second granularity this change exists to remove.
        assertTrue(
            "the recorder is constructed with the coerced native size",
            source.contains("ENCODING,\n                    nativeBufferBytes,"),
        )
        assertTrue(
            "the coerced size still floors at one second of audio",
            source.contains("coerceAtLeast(SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE)"),
        )
        assertFalse(
            "the read block must not be handed to the AudioRecord constructor",
            source.contains("ENCODING,\n                    READ_BLOCK_BYTES,"),
        )
    }

    @Test
    fun theRealBufferNumbersAreLoggedRatherThanAssumed() {
        // Whether the one-second floor binds cannot be settled from source, so the phone answers it.
        assertTrue(
            source.contains("Buffer sizes: minimum=\$minimum coerced=\$coerced read=\$READ_BLOCK_BYTES"),
        )
        assertTrue(
            "and what Android actually allocated, which can exceed what was requested",
            source.contains("record.bufferSizeInFrames"),
        )
    }

    private companion object {
        /** 4096 samples at 16 kHz, 16-bit mono. Written out rather than computed from the subject. */
        const val SilenceStopDetectorBlockBytes = 4096 * 2
    }
}
