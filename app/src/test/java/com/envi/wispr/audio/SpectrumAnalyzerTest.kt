package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Product outcome: when these fail, the user sees a bar light for the wrong pitch, a quiet room
 * jittering, or a picture of two sounds that were never next to each other.
 */
class SpectrumAnalyzerTest {

    private val bands = FloatArray(SpectrumAnalyzer.BAND_COUNT)

    /** [samples] samples of a sine at [hz], amplitude [amplitude] of full scale, starting at [offset] samples. */
    private fun tone(hz: Float, amplitude: Float, samples: Int = 512, offset: Int = 0): ByteArray {
        val out = ByteArray(samples * 2)
        for (n in 0 until samples) {
            val value = (amplitude * 32767f * sin(2.0 * PI * hz * (n + offset) / PcmAudio.SAMPLE_RATE)).toInt()
            out[2 * n] = (value and 0xFF).toByte()
            out[2 * n + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun bandOf(hz: Float): Int =
        (0 until SpectrumAnalyzer.BAND_COUNT).first { hz >= SpectrumAnalyzer.bandEdgeHz(it) && hz < SpectrumAnalyzer.bandEdgeHz(it + 1) }

    private val random = java.util.Random(7)

    /** Flat hiss at [amplitude] of full scale (RMS). */
    private fun hiss(amplitude: Double, samples: Int = 512): ByteArray = ByteArray(samples * 2).also {
        for (i in it.indices step 2) {
            val v = (random.nextGaussian() * amplitude * 32767).toInt().coerceIn(-32768, 32767)
            it[i] = (v and 0xFF).toByte(); it[i + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    /**
     * An analyser that has heard one chunk of a quiet room, as every real take begins: the microphone
     * opens on the press and the first word comes later. That first chunk is what each band's floor
     * starts from. Position 0 is spent on it, so callers start at 1024.
     */
    private fun primed(): SpectrumAnalyzer = SpectrumAnalyzer().also { it.analyze(hiss(0.0005), 1024, 0L, bands) }

    /** Two consecutive chunks fill the 64 ms window; the picture after the second is the steady one. */
    private fun steady(analyzer: SpectrumAnalyzer, hz: Float, amplitude: Float, position: Long = 1024L): FloatArray {
        analyzer.analyze(tone(hz, amplitude), 1024, position, bands)
        analyzer.analyze(tone(hz, amplitude, offset = 512), 1024, position + 1024, bands)
        return bands.copyOf()
    }

    /** The geometric centre of [band]. */
    private fun centreOf(band: Int): Float =
        kotlin.math.sqrt(SpectrumAnalyzer.bandEdgeHz(band) * SpectrumAnalyzer.bandEdgeHz(band + 1))

    @Test
    fun aToneLightsItsOwnBandBrightestAndNothingBeyondItsNeighbours() {
        // A tone spreads into the bins either side of its own (the window's main lobe is about two bins
        // wide), and the lowest bands are only three bins wide, so a neighbour may glow. It must never
        // outshine the tone's own band, and a band further away must be dark.
        for (own in listOf(0, 1, 3, 6, 8, 10)) {
            val analyzer = primed()
            val hz = centreOf(own)
            // Quiet on purpose: a loud tone clamps its own band AND its neighbour at 1.0, and the
            // comparison below would then be between two ceilings.
            val picture = steady(analyzer, hz, amplitude = 0.02f)
            // The top band is 128 bins wide, so a pure tone's per-bin mean reads lower there than in the
            // narrow low bands; a third of the rail is still plainly lit.
            assertTrue("$hz Hz must light band $own, got ${picture[own]}", picture[own] > 0.3f)
            for (band in picture.indices) {
                if (band == own) continue
                if (kotlin.math.abs(band - own) == 1) {
                    assertTrue("$hz Hz: neighbour band $band (${picture[band]}) must be dimmer than its own (${picture[own]})", picture[band] < picture[own])
                } else {
                    assertEquals("$hz Hz must not light band $band", 0f, picture[band], 0.0001f)
                }
            }
        }
    }

    @Test
    fun theBandsRunFromLowToHighSoTheRailCanPutLowInTheMiddle() {
        // The first band holds every speaking voice's fundamental, so the middle bar swells for anyone.
        assertTrue("a low male voice", bandOf(95f) == 0)
        assertTrue("a high female voice", bandOf(165f) == 0)
        assertTrue(bandOf(120f) == 0)
        assertTrue(bandOf(6_000f) == SpectrumAnalyzer.BAND_COUNT - 1)
        for (band in 0 until SpectrumAnalyzer.BAND_COUNT) {
            assertTrue("edges rise with the band", SpectrumAnalyzer.bandEdgeHz(band) < SpectrumAnalyzer.bandEdgeHz(band + 1))
        }
        assertEquals(SpectrumAnalyzer.LOW_EDGE_HZ, SpectrumAnalyzer.bandEdgeHz(0), 0.01f)
        assertEquals(SpectrumAnalyzer.HIGH_EDGE_HZ, SpectrumAnalyzer.bandEdgeHz(SpectrumAnalyzer.BAND_COUNT), 0.5f)
    }

    @Test
    fun louderIsHigherAndAQuietRoomIsDark() {
        val quiet = steady(primed(), 1_000f, amplitude = 0.0003f)[bandOf(1_000f)]
        val soft = steady(primed(), 1_000f, amplitude = 0.01f)[bandOf(1_000f)]
        val loud = steady(primed(), 1_000f, amplitude = 0.3f)[bandOf(1_000f)]
        assertEquals("room tone, about -70 dBFS, must read as nothing", 0f, quiet, 0.0001f)
        assertTrue("a soft tone must show", soft > 0.1f)
        assertTrue("and a loud one more", loud > soft)
    }

    @Test
    fun aSteadyHissIsLearnedAsTheFloorAndAVoiceStillShowsAboveIt() {
        // Build 130 on the founder's phone: the outer bars never lit on speech, and the three middle
        // bars lit in silence. The fix is the standard pair: pre-emphasis lifts the high bands so
        // consonants reach them, and a per-band floor learns the room so hiss and rumble go dark.
        // A phone's hiss, about -55 dBFS broadband, is there from the first chunk: under the prior floor
        // in every band, so it is dark at once and stays dark.
        val analyzer = SpectrumAnalyzer()
        var position = 0L
        val early = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        repeat(25) {
            analyzer.analyze(hiss(0.0018), 1024, position, bands); position += 1024
            if (it >= 5) for (b in bands.indices) early[b] += bands[b] / 20
        }
        for (b in early.indices) assertTrue("band $b dark on the room's own hiss, got ${early[b]}", early[b] < 0.1f)
        // A tone well above the hiss still lights its band, and the hiss did not lift its neighbours.
        val picture = steady(analyzer, 1_000f, amplitude = 0.2f, position = position)
        assertTrue("the voice shows above the learned floor", picture[bandOf(1_000f)] > 0.5f)
        assertTrue("and the far bands stay dark", picture[0] < 0.1f && picture[SpectrumAnalyzer.BAND_COUNT - 1] < 0.1f)
    }

    @Test
    fun aFanThatStartsLaterIsLearnedWithinTheWindow() {
        // A steady sound that begins mid-take (a fan, a fridge, a phone's own gain creeping up in
        // silence) lights the bars and then goes dark once it has been there for the whole window.
        val analyzer = primed()
        var position = 1024L
        val late = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        val chunks = SpectrumAnalyzer.FLOOR_WINDOW_CHUNKS + 20
        repeat(chunks) {
            analyzer.analyze(hiss(0.02), 1024, position, bands); position += 1024
            if (it >= SpectrumAnalyzer.FLOOR_WINDOW_CHUNKS + 5) for (b in bands.indices) late[b] += bands[b] / 15
        }
        for (b in late.indices) assertTrue("band $b dark once the fan is learned, got ${late[b]}", late[b] < 0.1f)
    }

    @Test
    fun aWordOnTheVeryFirstChunkShowsAtOnce() {
        // Capture does not promise a quiet chunk before the first word (Codex review, 2026-09-15). The
        // floor starts from a typical quiet room, not from whatever the first chunk holds.
        val analyzer = SpectrumAnalyzer()
        val picture = steady(analyzer, 300f, amplitude = 0.05f, position = 0L)
        assertTrue("the first word lights its band (${picture[bandOf(300f)]})", picture[bandOf(300f)] > 0.3f)
    }

    @Test
    fun digitalSilenceCannotDragTheFloorDown() {
        // A muted or switching microphone hands over exact zeros. If the floor followed them, the next
        // ordinary hiss would read as a full-height voice in every band.
        val analyzer = primed()
        var position = 1024L
        repeat(20) { analyzer.analyze(ByteArray(1024), 1024, position, bands); position += 1024 }
        analyzer.analyze(hiss(0.0005), 1024, position, bands)
        for (b in bands.indices) assertEquals("quiet room after digital silence stays dark in band $b", 0f, bands[b], 0f)
    }

    @Test
    fun aHeldNoteStaysLitForTheWindowAndAGapRestoresIt() {
        // A held note stays lit for as long as the floor's window remembers the room before it; a gap
        // between words drops the floor back to the room at once, so the next word is full again.
        val analyzer = primed()
        var position = 1024L
        var first = 0f
        var afterOneSecond = 0f
        repeat(31) {
            analyzer.analyze(tone(300f, 0.05f, offset = it * 512), 1024, position, bands); position += 1024
            if (it == 1) first = bands[bandOf(300f)]
            if (it == 30) afterOneSecond = bands[bandOf(300f)]
        }
        assertTrue("the note lit at first ($first)", first > 0.6f)
        assertEquals("and is still lit a second in", first, afterOneSecond, 0.05f)
        analyzer.analyze(hiss(0.0005), 1024, position, bands); position += 1024
        analyzer.analyze(hiss(0.0005), 1024, position, bands); position += 1024
        val again = steady(analyzer, 300f, amplitude = 0.05f, position = position)[bandOf(300f)]
        assertTrue("after a gap the same note is full again ($again vs $first)", again >= first - 0.05f)
    }

    @Test
    fun preEmphasisLetsAConsonantReachTheEdges() {
        // Speech falls off about 6 dB per octave; a real "s" sits 25 dB under a vowel per bin. Without the
        // lift, that never crossed the floor and the outer bars never lit (build 130).
        val vowel = steady(primed(), 300f, amplitude = 0.1f)[bandOf(300f)]
        val consonant = steady(primed(), 5_000f, amplitude = 0.1f / 18f)[bandOf(5_000f)]
        assertTrue("a consonant 25 dB down still lights the edge band well ($consonant vs vowel $vowel)", consonant > 0.5f)
    }

    @Test
    fun silenceAndFullScaleAreBothFiniteAndInRange() {
        val analyzer = primed()
        analyzer.analyze(ByteArray(1024), 1024, 1024L, bands)
        analyzer.analyze(ByteArray(1024), 1024, 2048L, bands)
        for (band in bands.indices) assertEquals("silence is dark in band $band", 0f, bands[band], 0f)
        val full = ByteArray(1024).also { for (i in it.indices step 2) { it[i] = 0xFF.toByte(); it[i + 1] = 0x7F } }
        analyzer.analyze(full, 1024, 3072L, bands)
        analyzer.analyze(full, 1024, 4096L, bands)
        for (band in bands.indices) {
            assertTrue("full scale stays finite in band $band", bands[band].isFinite())
            assertTrue("and within the rail", bands[band] in 0f..1f)
        }
    }

    @Test
    fun theOutputIsAlwaysEveryBand() {
        val analyzer = primed()
        val out = FloatArray(SpectrumAnalyzer.BAND_COUNT) { Float.NaN }
        analyzer.analyze(tone(1_000f, 0.5f), 1024, 1024L, out)
        assertTrue("a normal chunk writes every band", out.none { it.isNaN() })
        out.fill(Float.NaN)
        analyzer.analyze(tone(1_000f, 0.5f, samples = 100), 200, 2048L, out)
        assertTrue("a short chunk writes every band", out.none { it.isNaN() })
        out.fill(Float.NaN)
        analyzer.analyze(ByteArray(0), 0, 2248L, out)
        assertTrue("an empty chunk writes every band", out.none { it.isNaN() })
    }

    @Test
    fun aShortChunkSlidesTheWindowByItsOwnSamplesOnly() {
        // A tone that stops half way through a chunk still reads, and the stale half of the window is
        // the earlier audio, never padding.
        val analyzer = primed()
        analyzer.analyze(tone(1_000f, 0.5f), 1024, 1024L, bands)
        analyzer.analyze(tone(1_000f, 0.5f, samples = 256, offset = 512), 512, 2048L, bands)
        assertTrue("the tone is still in the picture after a short chunk", bands[bandOf(1_000f)] > 0.5f)
    }

    @Test
    fun aDroppedChunkResetsTheWindowSoTwoSoundsThatWereNeverAdjacentAreNeverJoined() {
        // Chunks A (0) and B (1024) carry a 300 Hz tone; C (2048) is never offered; D (3072) is silence.
        // If B and D were joined, D's picture would still show the 300 Hz tone from B.
        val analyzer = primed()
        analyzer.analyze(tone(300f, 0.5f), 1024, 1024L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 512), 1024, 2048L, bands)
        assertTrue("B lights its band", bands[bandOf(300f)] > 0.5f)
        analyzer.analyze(ByteArray(1024), 1024, 4096L, bands)
        val afterGap = bands.copyOf()
        val fresh = primed()
        fresh.analyze(ByteArray(1024), 1024, 4096L, bands)
        for (band in bands.indices) {
            assertEquals("D after a gap reads as D on a fresh window, band $band", bands[band], afterGap[band], 0.0001f)
        }
        assertEquals("the tone that was only in B is gone", 0f, afterGap[bandOf(300f)], 0.0001f)
    }

    @Test
    fun aContinuousChunkKeepsTheWindow() {
        // The same sequence with C present: D still sees C's tail in its older half.
        val analyzer = primed()
        analyzer.analyze(tone(300f, 0.5f), 1024, 1024L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 512), 1024, 2048L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 1024), 1024, 3072L, bands)
        analyzer.analyze(ByteArray(1024), 1024, 4096L, bands)
        assertTrue("C's tone is still in D's window", bands[bandOf(300f)] > 0f)
    }

    @Test
    fun theDisplayScaleIsMonotonicAndClamped() {
        val floor = -50f
        assertEquals("at the floor", 0f, SpectrumAnalyzer.display(floor, floor), 0f)
        assertEquals("just inside the margin", 0f, SpectrumAnalyzer.display(floor + SpectrumAnalyzer.FLOOR_MARGIN_DB, floor), 0f)
        assertEquals("full at the top of the range", 1f, SpectrumAnalyzer.display(floor + SpectrumAnalyzer.FLOOR_MARGIN_DB + SpectrumAnalyzer.RANGE_DB, floor), 0.0001f)
        assertEquals("clamped above", 1f, SpectrumAnalyzer.display(floor + 100f, floor), 0f)
        assertEquals(0f, SpectrumAnalyzer.display(Float.NaN, floor), 0f)
        assertEquals(0f, SpectrumAnalyzer.display(-30f, Float.NaN), 0f)
        assertTrue(SpectrumAnalyzer.display(floor + 15f, floor) < SpectrumAnalyzer.display(floor + 25f, floor))
        assertEquals("digital silence is far under any floor", 0f, SpectrumAnalyzer.display(SpectrumAnalyzer.decibels(0f), floor), 0f)
    }
}
