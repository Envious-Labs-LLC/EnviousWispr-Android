package com.envi.wispr.audio

/** Allocation-only PCM helpers shared by capture and offline ASR. */
object PcmAudio {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    /**
     * How much audio one `AudioRecord.read` asks for: 512 samples, 32 ms at 16 kHz.
     *
     * **This is a different quantity from the buffer the AudioRecord is constructed with**, and the
     * two want opposite things. The native buffer is the margin that stops an overrun when the
     * capture thread is descheduled, so it wants to be large. This is the loop's decision
     * granularity, so it wants to be small: the duration ceiling can only fire on a read boundary,
     * and so can a silence stop. Reading the whole native buffer made both coarse to about a second.
     *
     * Android's own guidance is to read in short frequent chunks rather than waiting for the buffer
     * to fill. 32 ms is what the recorder's live picture needs: a syllable is about 100 ms, and the
     * 256 ms read this replaced handed the meter one averaged number per quarter second, which
     * cannot show a voice (#151). The capture service reads with it and the picture sizes its ring
     * from it (#188).
     */
    const val READ_CHUNK_BYTES = 1_024

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
