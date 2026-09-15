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

    /** Two consecutive chunks fill the 64 ms window; the picture after the second is the steady one. */
    private fun steady(analyzer: SpectrumAnalyzer, hz: Float, amplitude: Float): FloatArray {
        analyzer.analyze(tone(hz, amplitude), 1024, 0L, bands)
        analyzer.analyze(tone(hz, amplitude, offset = 512), 1024, 1024L, bands)
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
        val analyzer = SpectrumAnalyzer()
        for (own in listOf(0, 1, 3, 6, 8, 10)) {
            val hz = centreOf(own)
            // Mid-scale on purpose: a loud tone clamps its own band AND its neighbour at 1.0, and the
            // comparison below would then be between two ceilings.
            val picture = steady(analyzer, hz, amplitude = 0.05f)
            assertTrue("$hz Hz must light band $own, got ${picture[own]}", picture[own] > 0.4f)
            for (band in picture.indices) {
                if (band == own) continue
                if (kotlin.math.abs(band - own) == 1) {
                    assertTrue("$hz Hz: neighbour band $band (${picture[band]}) must be dimmer than its own (${picture[own]})", picture[band] < picture[own])
                } else {
                    assertEquals("$hz Hz must not light band $band", 0f, picture[band], 0.0001f)
                }
            }
            analyzer.reset()
        }
    }

    @Test
    fun theBandsRunFromLowToHighSoTheRailCanPutLowInTheMiddle() {
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
        val analyzer = SpectrumAnalyzer()
        val quiet = steady(analyzer, 1_000f, amplitude = 0.0003f)[bandOf(1_000f)]
        analyzer.reset()
        val soft = steady(analyzer, 1_000f, amplitude = 0.01f)[bandOf(1_000f)]
        analyzer.reset()
        val loud = steady(analyzer, 1_000f, amplitude = 0.3f)[bandOf(1_000f)]
        assertEquals("room tone, about -70 dBFS, must read as nothing", 0f, quiet, 0.0001f)
        assertTrue("a soft tone must show", soft > 0.1f)
        assertTrue("and a loud one more", loud > soft)
    }

    @Test
    fun silenceAndFullScaleAreBothFiniteAndInRange() {
        val analyzer = SpectrumAnalyzer()
        analyzer.analyze(ByteArray(1024), 1024, 0L, bands)
        analyzer.analyze(ByteArray(1024), 1024, 1024L, bands)
        for (band in bands.indices) assertEquals("silence is dark in band $band", 0f, bands[band], 0f)
        val full = ByteArray(1024).also { for (i in it.indices step 2) { it[i] = 0xFF.toByte(); it[i + 1] = 0x7F } }
        analyzer.analyze(full, 1024, 2048L, bands)
        analyzer.analyze(full, 1024, 3072L, bands)
        for (band in bands.indices) {
            assertTrue("full scale stays finite in band $band", bands[band].isFinite())
            assertTrue("and within the rail", bands[band] in 0f..1f)
        }
    }

    @Test
    fun theOutputIsAlwaysEveryBand() {
        val analyzer = SpectrumAnalyzer()
        val out = FloatArray(SpectrumAnalyzer.BAND_COUNT) { Float.NaN }
        analyzer.analyze(tone(1_000f, 0.5f), 1024, 0L, out)
        assertTrue("a normal chunk writes every band", out.none { it.isNaN() })
        out.fill(Float.NaN)
        analyzer.analyze(tone(1_000f, 0.5f, samples = 100), 200, 1024L, out)
        assertTrue("a short chunk writes every band", out.none { it.isNaN() })
        out.fill(Float.NaN)
        analyzer.analyze(ByteArray(0), 0, 1224L, out)
        assertTrue("an empty chunk writes every band", out.none { it.isNaN() })
    }

    @Test
    fun aShortChunkSlidesTheWindowByItsOwnSamplesOnly() {
        // A tone that stops half way through a chunk still reads, and the stale half of the window is
        // the earlier audio, never padding.
        val analyzer = SpectrumAnalyzer()
        analyzer.analyze(tone(1_000f, 0.5f), 1024, 0L, bands)
        analyzer.analyze(tone(1_000f, 0.5f, samples = 256, offset = 512), 512, 1024L, bands)
        assertTrue("the tone is still in the picture after a short chunk", bands[bandOf(1_000f)] > 0.5f)
    }

    @Test
    fun aDroppedChunkResetsTheWindowSoTwoSoundsThatWereNeverAdjacentAreNeverJoined() {
        // Chunks A (0) and B (1024) carry a 300 Hz tone; C (2048) is never offered; D (3072) is silence.
        // If B and D were joined, D's picture would still show the 300 Hz tone from B.
        val analyzer = SpectrumAnalyzer()
        analyzer.analyze(tone(300f, 0.5f), 1024, 0L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 512), 1024, 1024L, bands)
        assertTrue("B lights its band", bands[bandOf(300f)] > 0.5f)
        analyzer.analyze(ByteArray(1024), 1024, 3072L, bands)
        val afterGap = bands.copyOf()
        val fresh = SpectrumAnalyzer()
        fresh.analyze(ByteArray(1024), 1024, 3072L, bands)
        for (band in bands.indices) {
            assertEquals("D after a gap reads as D on a fresh window, band $band", bands[band], afterGap[band], 0.0001f)
        }
        assertEquals("the tone that was only in B is gone", 0f, afterGap[bandOf(300f)], 0.0001f)
    }

    @Test
    fun aContinuousChunkKeepsTheWindow() {
        // The same sequence with C present: D still sees C's tail in its older half.
        val analyzer = SpectrumAnalyzer()
        analyzer.analyze(tone(300f, 0.5f), 1024, 0L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 512), 1024, 1024L, bands)
        analyzer.analyze(tone(300f, 0.5f, offset = 1024), 1024, 2048L, bands)
        analyzer.analyze(ByteArray(1024), 1024, 3072L, bands)
        assertTrue("C's tone is still in D's window", bands[bandOf(300f)] > 0f)
    }

    @Test
    fun theDisplayScaleIsMonotonicAndClamped() {
        assertEquals(0f, SpectrumAnalyzer.display(0f, 0f), 0f)
        assertEquals(0f, SpectrumAnalyzer.display(Float.NaN, 0f), 0f)
        assertEquals(0f, SpectrumAnalyzer.display(-1f, 0f), 0f)
        assertEquals(1f, SpectrumAnalyzer.display(1f, 0f), 0f)
        assertTrue(SpectrumAnalyzer.display(0.01f, 0f) < SpectrumAnalyzer.display(0.1f, 0f))
        assertTrue("the tilt lifts a band", SpectrumAnalyzer.display(0.01f, 6f) > SpectrumAnalyzer.display(0.01f, 0f))
    }
}
