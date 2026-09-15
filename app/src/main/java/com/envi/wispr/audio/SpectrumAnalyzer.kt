package com.envi.wispr.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the newest 64 ms of microphone audio into the eleven pitch levels the floating recorder draws.
 *
 * The recorder shows a PICTURE of the voice right now, not a history of how loud it was: the lowest
 * band sits in the middle of the rail and the highest at its edges, and every bar moves with the sound
 * of this instant (founder 2026-09-14, #151: "I can see my actual voice in their bars"). One number per
 * tick could only ever show loudness, so the analyser keeps the shape of the sound instead.
 *
 * Pure and preallocated. Everything it needs, the window, the FFT tables and the band edges, is built
 * once in the constructor, and [analyze] allocates nothing. It runs on the analyser thread in `:audio`,
 * never on the capture thread, which only copies bytes into a ring and moves on.
 *
 * **Continuity is owned here.** Every chunk arrives with its byte position in the take. A chunk whose
 * position is not the one expected next means the ring dropped something in between, and the window
 * is reset to silence BEFORE that chunk is analysed, so two samples that were never adjacent in the
 * take are never in one window. A picture of a join that never happened is worse than a picture of a
 * short silence.
 *
 * The scale is a DISPLAY choice and nothing downstream reads it: transcription, the silence detector
 * and the stored audio never see these numbers. The two dB constants were first set on 2026-09-14
 * against the emulator and are tuned on the founder's phone pass. Every band is scaled the same way,
 * per bin: a high-band lift was tried (3, then 1.5 dB per octave) and both made the microphone's own
 * hiss spike the edge bars, on the founder's phone (build 127) and in the emulator's flat-hiss run
 * (2026-09-15). An "s" is loud enough where it lives to show without one.
 */
class SpectrumAnalyzer {

    /** The last [FFT_SIZE] samples, oldest first, as -1..1. Silence until the first chunk. */
    private val history = FloatArray(FFT_SIZE)

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { 0.5f - 0.5f * cos(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val cosTable = FloatArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val sinTable = FloatArray(FFT_SIZE / 2) { sin(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val bitReversed = IntArray(FFT_SIZE).also { table ->
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in table.indices) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Amplitude normalisation: a full-scale sine reads 1.0 in its bin, before the per-bin mean of its band. */
    private val amplitudeScale = 2f / window.sum()

    private val bandLowBin = IntArray(BAND_COUNT)
    private val bandHighBin = IntArray(BAND_COUNT)

    /** The byte position the next chunk should carry, or [NO_POSITION] before the first chunk. */
    private var expectedPosition = NO_POSITION

    init {
        for (band in 0 until BAND_COUNT) {
            val low = bandEdgeHz(band)
            val high = bandEdgeHz(band + 1)
            val lowBin = Math.round(low / BIN_HZ)
            val highBin = maxOf(lowBin, Math.round(high / BIN_HZ) - 1)
            bandLowBin[band] = lowBin.coerceIn(1, FFT_SIZE / 2 - 1)
            bandHighBin[band] = highBin.coerceIn(bandLowBin[band], FFT_SIZE / 2 - 1)
        }
    }

    /** Forget every sample. The next chunk is analysed against silence. */
    fun reset() {
        history.fill(0f)
        expectedPosition = NO_POSITION
    }

    /**
     * Analyse one chunk of 16 kHz mono 16-bit little-endian PCM and write [BAND_COUNT] levels, 0..1 and
     * low band first, into [out].
     *
     * [length] bytes of [chunk] are used, at most [MAX_CHUNK_BYTES]; a shorter chunk slides the window
     * by its own samples only. [positionBytes] is the take's byte offset of the chunk's first byte; a
     * position other than the one expected resets the window first (see the class note). [out] is
     * always fully written, so a caller can never read a stale band beside a fresh one.
     */
    fun analyze(chunk: ByteArray, length: Int, positionBytes: Long, out: FloatArray) {
        require(out.size >= BAND_COUNT) { "out holds every band" }
        val bytes = length.coerceIn(0, minOf(MAX_CHUNK_BYTES, chunk.size)) and 1.inv()
        if (expectedPosition != NO_POSITION && positionBytes != expectedPosition) {
            history.fill(0f)
        }
        expectedPosition = positionBytes + bytes

        val samples = bytes / PcmAudio.BYTES_PER_SAMPLE
        if (samples > 0) {
            System.arraycopy(history, samples, history, 0, FFT_SIZE - samples)
            var s = FFT_SIZE - samples
            var i = 0
            while (i < bytes) {
                val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                history[s++] = sample / 32768f
                i += 2
            }
        }

        for (n in 0 until FFT_SIZE) {
            re[n] = history[n] * window[n]
            im[n] = 0f
        }
        fft()

        for (band in 0 until BAND_COUNT) {
            var energy = 0f
            for (bin in bandLowBin[band]..bandHighBin[band]) {
                energy += re[bin] * re[bin] + im[bin] * im[bin]
            }
            // Mean power per bin, not the band's total. The top band is 128 bins wide and the first is
            // five, so summing made the edges read 14 dB louder than the centre on the same flat hiss:
            // the founder's first phone look at build 127 (2026-09-14) was "the two side bars are
            // spiking a good amount". Per bin, a flat noise reads the same in every band, and an "s",
            // which is broadband where it lives, still lifts the edges.
            val bins = bandHighBin[band] - bandLowBin[band] + 1
            val amplitude = sqrt(energy / bins) * amplitudeScale
            out[band] = display(amplitude)
        }
    }

    /** In-place radix-2 FFT of [re] and [im]. */
    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    companion object {
        /** How many pitch bands the picture has. The hold pill shows all of them mirrored, the tap pill six. */
        const val BAND_COUNT = 11

        /** 1024 samples, 64 ms at 16 kHz: two chunks of context, so a 32 ms hop still resolves low voices. */
        const val FFT_SIZE = 1024

        /** The most one chunk may carry: 512 samples, one `AudioRecord.read` of the capture loop. */
        const val MAX_CHUNK_BYTES = FFT_SIZE

        private const val BIN_HZ = PcmAudio.SAMPLE_RATE.toFloat() / FFT_SIZE

        /** The lowest band starts here: below it is rumble, not voice. */
        const val LOW_EDGE_HZ = 85f

        /** The highest band ends here: the sibilants live below it and the microphone's own hiss above. */
        const val HIGH_EDGE_HZ = 6_400f

        /** At or below this a band is dark. A quiet room measures under it (first set 2026-09-14). */
        const val QUIET_DBFS = -62f

        /** At or above this a band is full. A raised voice reaches it in its strongest band (first set 2026-09-14). */
        const val LOUD_DBFS = -18f

        private const val NO_POSITION = Long.MIN_VALUE

        /**
         * Where the first band ends. One octave wide on purpose: it holds the fundamental of nearly every
         * speaking voice, low or high, so the CENTRE of the rail is what swells when anyone talks. With
         * eleven equal log bands the first one stopped at 146 Hz, and a higher voice left the middle bar
         * dark while its neighbours lit (measured on the emulator's spoken take, 2026-09-14).
         */
        const val VOICE_BAND_TOP_HZ = 170f

        /**
         * The lower edge of [band]: [LOW_EDGE_HZ] to [VOICE_BAND_TOP_HZ] for band 0, then log-spaced up to
         * [HIGH_EDGE_HZ]; band [BAND_COUNT] is the top edge.
         */
        fun bandEdgeHz(band: Int): Float = when {
            band <= 0 -> LOW_EDGE_HZ
            else -> VOICE_BAND_TOP_HZ * (HIGH_EDGE_HZ / VOICE_BAND_TOP_HZ).toDouble().pow((band - 1).toDouble() / (BAND_COUNT - 1)).toFloat()
        }

        /**
         * Map one band amplitude (1.0 is a full-scale sine) to the fraction of the bar that should be
         * lit. Pure, so it can be asserted without audio. Anything not finite reads as silence: a meter
         * that jumps to full when the arithmetic misbehaves is worse than one that stops.
         */
        fun display(amplitude: Float): Float {
            if (!amplitude.isFinite() || amplitude <= 0f) return 0f
            val dbfs = 20f * log10(amplitude)
            val level = (dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)
            return if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        }
    }
}
