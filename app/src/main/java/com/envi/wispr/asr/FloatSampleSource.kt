package com.envi.wispr.asr

import com.envi.wispr.audio.PcmAudio
import java.io.Closeable
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Random-access source of 16 kHz mono float samples for [TdtRecipe] (#117).
 *
 * Keeps whole-take allocations off the Java heap: [PcmFileSampleSource] streams only the requested
 * window straight from a PCM16 file on disk so hour-long takes stay under a few megabytes of heap
 * instead of holding both the raw ByteArray and full FloatArray in memory at once.
 */
internal interface FloatSampleSource {
    val size: Int

    /** Returns samples in `[fromIndex, toIndex)`. */
    fun copyRange(fromIndex: Int, toIndex: Int): FloatArray
}

internal class ArraySampleSource(private val samples: FloatArray) : FloatSampleSource {
    override val size: Int = samples.size

    override fun copyRange(fromIndex: Int, toIndex: Int): FloatArray {
        require(fromIndex in 0..size && toIndex in fromIndex..size) {
            "range [$fromIndex, $toIndex) out of bounds for size $size"
        }
        return samples.copyOfRange(fromIndex, toIndex)
    }
}

/**
 * Streams 16-bit little-endian PCM directly from [file] on demand (#117). Not thread-safe; used
 * sequentially on `:asr`'s single decode worker.
 */
internal class PcmFileSampleSource(
    private val file: RandomAccessFile,
    override val size: Int,
    private val checkpoint: () -> Unit = {},
) : FloatSampleSource, Closeable {
    private val scratch = ByteArray(SCRATCH_BYTES)

    init {
        require(size >= 0) { "sample count must be non-negative: $size" }
    }

    override fun copyRange(fromIndex: Int, toIndex: Int): FloatArray {
        require(fromIndex in 0..size && toIndex in fromIndex..size) {
            "range [$fromIndex, $toIndex) out of bounds for size $size"
        }
        val count = toIndex - fromIndex
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        file.seek(fromIndex.toLong() * PcmAudio.BYTES_PER_SAMPLE)
        var written = 0
        while (written < count) {
            checkpoint()
            val chunkSamples = min(count - written, SCRATCH_SAMPLES)
            val chunkBytes = chunkSamples * PcmAudio.BYTES_PER_SAMPLE
            file.readFully(scratch, 0, chunkBytes)
            for (i in 0 until chunkSamples) {
                val offset = i * PcmAudio.BYTES_PER_SAMPLE
                val sample = (scratch[offset].toInt() and 0xFF) or
                    (scratch[offset + 1].toInt() shl 8)
                out[written + i] = sample / 32768.0f
            }
            written += chunkSamples
        }
        checkpoint()
        return out
    }

    override fun close() {
        file.close()
    }

    companion object {
        private const val SCRATCH_SAMPLES = 32_768
        private const val SCRATCH_BYTES = SCRATCH_SAMPLES * PcmAudio.BYTES_PER_SAMPLE
    }
}
