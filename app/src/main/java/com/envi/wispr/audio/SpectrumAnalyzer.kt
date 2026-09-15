package com.envi.wispr.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
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
 * and the stored audio never see these numbers. It is the standard shape of a speech visualiser, and
 * every piece of it answers a phone finding:
 *
 * 1. **Log-spaced bands, mean power per bin.** A band total made wide bands read louder on the same
 *    hiss (build 127: the edge bars spiked).
 * 2. **Pre-emphasis, +6 dB per octave above [PRE_EMPHASIS_FROM_HZ].** Speech falls off with pitch at
 *    about that rate, so without it the high bands never leave the floor: on build 130 only the middle
 *    five bars ever lit and the outer bars never did (founder 2026-09-15). This is the speech-processing
 *    pre-emphasis filter, applied per band.
 * 3. **An adaptive noise floor per band.** The lift in step 2 lifts the microphone's hiss too, which is
 *    why it was removed once. Instead of removing it, each band's floor is the QUIETEST reading of its
 *    last [FLOOR_WINDOW_CHUNKS] chunks (about 1.5 s), never below [QUIET_DBFS]: a steady hiss, or a
 *    phone's own gain creeping up in silence, becomes the floor within that window, a pause between
 *    words shows the room at once, and only sound ABOVE the floor lights the bar. In a quiet room the
 *    three middle bars that lit on build 130 go dark for the same reason. A rate-limited floor was
 *    tried first and lagged the emulator's own source ramping up after the microphone opened, which lit
 *    every bar for a second at the start of a take (2026-09-15); a windowed minimum follows a ramp as
 *    fast as the window.
 * 4. **A fixed range above the floor.** [FLOOR_MARGIN_DB] above the floor is dark, [RANGE_DB] above that
 *    is full, so a normal voice fills the rail whatever the phone's gain.
 *
 * The per-take state (the floor) lives on the instance, which the capture service creates per take.
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

    /** Pre-emphasis per band, in dB: +6 per octave above [PRE_EMPHASIS_FROM_HZ]. */
    private val preEmphasisDb = FloatArray(BAND_COUNT)

    /**
     * Each band's last [FLOOR_WINDOW_CHUNKS] readings in dB (after pre-emphasis), a ring per band.
     *
     * Seeded with [PRIOR_FLOOR_DB], a typical quiet room, so a take that opens on a word is not judged
     * against that word: the first chunk would otherwise be the whole window and set the floor at the
     * voice itself, and the bar would stay dark until the first gap between words (Codex review,
     * 2026-09-15). The seed expires as real readings replace it, within the window.
     */
    private val recent = Array(BAND_COUNT) { FloatArray(FLOOR_WINDOW_CHUNKS) { PRIOR_FLOOR_DB } }
    private var recentIndex = 0


    init {
        for (band in 0 until BAND_COUNT) {
            val low = bandEdgeHz(band)
            val high = bandEdgeHz(band + 1)
            val lowBin = Math.round(low / BIN_HZ)
            val highBin = maxOf(lowBin, Math.round(high / BIN_HZ) - 1)
            bandLowBin[band] = lowBin.coerceIn(1, FFT_SIZE / 2 - 1)
            bandHighBin[band] = highBin.coerceIn(bandLowBin[band], FFT_SIZE / 2 - 1)
            val centre = sqrt(low * high)
            preEmphasisDb[band] = if (centre > PRE_EMPHASIS_FROM_HZ) PRE_EMPHASIS_DB_PER_OCTAVE * log2(centre / PRE_EMPHASIS_FROM_HZ) else 0f
        }
    }

    /** Forget every sample and every floor. The next chunk is analysed against silence. */
    fun reset() {
        history.fill(0f)
        for (ring in recent) ring.fill(PRIOR_FLOOR_DB)
        recentIndex = 0
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
            val db = decibels(amplitude) + preEmphasisDb[band]
            // The floor is the quietest reading in the window, so a pause between words shows the room
            // at once and a steady sound is learned within the window. Never below QUIET_DBFS: digital
            // silence (a muted or switching microphone) must not set a floor so low that the next
            // ordinary hiss reads as a voice.
            recent[band][recentIndex] = db
            var floor = db
            for (value in recent[band]) floor = minOf(floor, value)
            out[band] = display(db, maxOf(floor, QUIET_DBFS))
        }
        recentIndex = (recentIndex + 1) % FLOOR_WINDOW_CHUNKS
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

        /**
         * The lowest a band's floor can be, per bin after pre-emphasis: readings below it are a quiet
         * room or digital silence, and a floor that followed them down would make the next ordinary hiss
         * read as a voice (first set 2026-09-14).
         */
        const val QUIET_DBFS = -62f

        /** Above the floor by less than this a band is dark: the hiss's own wobble never shows. */
        const val FLOOR_MARGIN_DB = 8f

        /**
         * A typical quiet room per bin after pre-emphasis, the floor a take starts from. Real readings
         * replace it within the window. A phone's own hiss sits under it and stays dark; a word that
         * begins on the first chunk sits well above it and shows at once.
         */
        const val PRIOR_FLOOR_DB = QUIET_DBFS + 12f

        /** From dark to full: the dynamic range a voice is drawn across. */
        const val RANGE_DB = 30f

        /**
         * How many chunks the floor looks back over: 48 of 32 ms is about 1.5 s, longer than any gap
         * between words in a sentence, shorter than a sound that has become part of the room.
         */
        const val FLOOR_WINDOW_CHUNKS = 48

        /** Speech's own fall-off with pitch, undone so the outer bars can light on consonants. */
        const val PRE_EMPHASIS_DB_PER_OCTAVE = 6f
        const val PRE_EMPHASIS_FROM_HZ = 250f

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

        /** One band amplitude (1.0 is a full-scale sine) in dB; silence and anything not finite read as very quiet. */
        fun decibels(amplitude: Float): Float =
            if (!amplitude.isFinite() || amplitude <= 0f) SILENT_DB else 20f * log10(amplitude)

        /**
         * Map one band's level in dB against its floor to the fraction of the bar that should be lit:
         * dark up to [FLOOR_MARGIN_DB] above the floor, full [RANGE_DB] above that. Pure, so it can be
         * asserted without audio. Anything not finite reads as silence: a meter that jumps to full when
         * the arithmetic misbehaves is worse than one that stops.
         */
        fun display(db: Float, floor: Float): Float {
            if (!db.isFinite() || !floor.isFinite()) return 0f
            val level = (db - floor - FLOOR_MARGIN_DB) / RANGE_DB
            return if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        }

        /** Well under any floor: what a band reads with no energy at all. */
        private const val SILENT_DB = -160f
    }
}
