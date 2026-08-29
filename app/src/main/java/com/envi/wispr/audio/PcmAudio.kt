package com.envi.wispr.audio

/** Allocation-only PCM helpers shared by capture and offline ASR. */
object PcmAudio {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    fun durationSeconds(byteCount: Long): Float =
        byteCount.toFloat() / (SAMPLE_RATE * BYTES_PER_SAMPLE)

    /** Decode complete little-endian PCM16 samples. A trailing byte is ignored. */
    fun toFloatSamples(pcmData: ByteArray): FloatArray {
        val samples = FloatArray(pcmData.size / BYTES_PER_SAMPLE)
        for (i in samples.indices) {
            val offset = i * BYTES_PER_SAMPLE
            val value = (pcmData[offset].toInt() and 0xFF) or
                (pcmData[offset + 1].toInt() shl 8)
            samples[i] = value / 32768.0f
        }
        return samples
    }
}
