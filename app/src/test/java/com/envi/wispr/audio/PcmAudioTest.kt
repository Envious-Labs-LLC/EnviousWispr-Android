package com.envi.wispr.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Harness Contract: PCM wire format and bounded conversion checkpoints; real audio/model proof is separate. */
class PcmAudioTest {
    @Test
    fun decodesSignedLittleEndianSamplesAndIgnoresTrailingByte() {
        val pcm = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0x01)
        assertArrayEquals(floatArrayOf(-1f, 0.9999695f), PcmAudio.toFloatSamples(pcm) { }, 0.00001f)
    }

    @Test
    fun computesDurationFromBytes() {
        assertEquals(2f, PcmAudio.durationSeconds(64_000L), 0.0001f)
    }

    @Test fun recoveryConvertsOnlyValidAudioRatherThanUnusedBufferCapacity() {
        val pcm = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        assertArrayEquals(floatArrayOf(0.9999695f), PcmAudio.toFloatSamples(pcm, 2) {}, 0.00001f)
    }

    @Test fun cancellationInsideConversionNeverReturnsAPartialSampleArray() {
        val checkpoints = mutableListOf<Int>()
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        val thrown = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            PcmAudio.toFloatSamples(ByteArray(4096)) { index ->
                checkpoints += index
                if (index == 512) throw cancelled
            }
        }
        org.junit.Assert.assertSame(cancelled, thrown)
        assertEquals(listOf(0, 0, 512), checkpoints)
    }
}
