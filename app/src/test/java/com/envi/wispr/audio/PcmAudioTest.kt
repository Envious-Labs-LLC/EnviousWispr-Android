package com.envi.wispr.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioTest {
    @Test
    fun decodesSignedLittleEndianSamplesAndIgnoresTrailingByte() {
        val pcm = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0x01)
        assertArrayEquals(floatArrayOf(-1f, 0.9999695f), PcmAudio.toFloatSamples(pcm), 0.00001f)
    }

    @Test
    fun computesDurationFromBytes() {
        assertEquals(2f, PcmAudio.durationSeconds(64_000L), 0.0001f)
    }
}
