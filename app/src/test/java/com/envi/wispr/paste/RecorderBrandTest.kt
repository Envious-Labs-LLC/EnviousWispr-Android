package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome for the elapsed labels, Drift Guard for the rest, and the sections say which.
 *
 * The one that is not cosmetic is `theAcceptButtonStopsAndTheCancelButtonCancels`. The recorder's two
 * controls now look like a quiet X and a bright tick rather than a red circle and a green square, and a
 * swap would put "throw this dictation away" behind the button that reads as "keep it".
 */
class RecorderBrandTest {

    private val overlay = File("src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt").readText()
    private val palette = File("src/main/java/com/envi/wispr/paste/BrandPalette.kt").readText()
    private val meter = File("src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt").readText()
    private val mark = File("src/main/java/com/envi/wispr/paste/BrandMarkView.kt").readText()

    // ---- Product Outcome: what the user reads on the pill ----

    @Test
    fun theElapsedTimeReadsAsAClockAndNotAsASecondCount() {
        assertEquals("0:00", ElapsedLabels.clock(0))
        assertEquals("0:09", ElapsedLabels.clock(9))
        assertEquals("0:12", ElapsedLabels.clock(12))
        assertEquals("1:00", ElapsedLabels.clock(60))
        assertEquals("1:05", ElapsedLabels.clock(65))
        assertEquals("4:07", ElapsedLabels.clock(247))
        assertEquals("10:00", ElapsedLabels.clock(600))
    }

    @Test
    fun theClockNeverShowsANegativeOrARaggedWidth() {
        assertEquals("0:00", ElapsedLabels.clock(-1))
        assertEquals("0:00", ElapsedLabels.clock(Int.MIN_VALUE))
        // Every value inside the cap is four or five characters, so the pill does not resize mid-take.
        (0..600).forEach { seconds ->
            val text = ElapsedLabels.clock(seconds)
            assertTrue("at $seconds the clock read $text", text.length in 4..5)
        }
    }

    @Test
    fun aScreenReaderHearsWordsRatherThanAColon() {
        // "0:12" is announced as "zero colon twelve", which is not what it says.
        assertEquals("12 seconds elapsed", ElapsedLabels.spoken(12))
        assertEquals("1 second elapsed", ElapsedLabels.spoken(1))
        assertEquals("1 minute 5 seconds elapsed", ElapsedLabels.spoken(65))
        assertEquals("1 minute 1 second elapsed", ElapsedLabels.spoken(61))
        assertEquals("4 minutes 7 seconds elapsed", ElapsedLabels.spoken(247))
        assertEquals("0 seconds elapsed", ElapsedLabels.spoken(0))
    }

    // ---- Drift Guard: the brand device and the two controls ----

    @Test
    fun theRainbowIsTheNineBrandStopsInOrder() {
        // Source of truth: the website's :root block, --rainbow-full. A stop edited here and not there
        // makes the product's signature quietly wrong on the one surface that carries it most.
        listOf(
            "0xFFFF2A40", "0xFFFF8C00", "0xFFFFD700", "0xFFADFF2F", "0xFF00FA9A",
            "0xFF00FFFF", "0xFF1E90FF", "0xFF4169E1", "0xFF8A2BE2",
        ).forEachIndexed { index, stop ->
            assertTrue("stop $index ($stop) is missing from the rainbow", palette.contains(stop))
        }
        val order = Regex("0xFF[0-9A-F]{6}")
            .findAll(palette.substringAfter("val RAINBOW").substringBefore("/** `--v2-violet`"))
            .map { it.value }.toList()
        assertEquals(
            listOf(
                "0xFFFF2A40", "0xFFFF8C00", "0xFFFFD700", "0xFFADFF2F", "0xFF00FA9A",
                "0xFF00FFFF", "0xFF1E90FF", "0xFF4169E1", "0xFF8A2BE2",
            ),
            order,
        )
    }

    @Test
    fun theAcceptButtonStopsAndTheCancelButtonCancels() {
        val cancel = overlay.substringAfter("actionButton(\"×\"").substringBefore("},")
        val accept = overlay.substringAfter("actionButton(\"✓\"").substringBefore("},")
        assertTrue(
            "the X must cancel the dictation",
            cancel.contains("DictationSessionService.ACTION_CANCEL"),
        )
        assertTrue(
            "the tick must stop and keep the words",
            accept.contains("DictationSessionService.ACTION_STOP"),
        )
        assertTrue(
            "the tick is the one filled control, so it must carry the accent",
            overlay.contains("actionButton(\"✓\", \"Stop and use these words\", BrandPalette.ACCENT)"),
        )
        assertTrue(
            "the X must be the quiet one",
            overlay.contains("actionButton(\"×\", \"Cancel\", BrandPalette.NEUTRAL_CONTROL)"),
        )
    }

    @Test
    fun thePillCarriesTheFounderSpecifiedOrder() {
        // From docs/mockups/android-v2/06-floating-recorder.png: mark, time, rail, state, cancel, accept.
        val body = overlay.substringAfter("private fun buildPill()").substringBefore("private fun pillBackground()")
        val order = listOf("mark,", "timer,", "meter,", "stateLabel,", "\"×\"", "\"✓\"")
            .map { it to body.indexOf(it) }
        order.forEach { (piece, at) -> assertTrue("$piece is not in the pill", at >= 0) }
        assertEquals(
            "the pill's order must match the mockup",
            order.sortedBy { it.second }.map { it.first },
            order.map { it.first },
        )
    }

    @Test
    fun theRecorderCarriesTheVioletOutlineAndGlow() {
        assertTrue("the pill needs its violet outline", overlay.contains("setStroke(dp(1).coerceAtLeast(1), BrandPalette.VIOLET)"))
        assertTrue(
            "and its violet glow, which is the shadow tinted",
            overlay.contains("outlineSpotShadowColor = BrandPalette.VIOLET") &&
                overlay.contains("outlineAmbientShadowColor = BrandPalette.VIOLET"),
        )
    }

    @Test
    fun neitherPaintedViewIsAnnouncedToAScreenReader() {
        // Both repeat what the timer and the recorder's own label already say, and the rail changes
        // about ten times a second.
        listOf("the level rail" to meter, "the brand mark" to mark).forEach { (what, source) ->
            assertTrue(
                "$what must be hidden from accessibility",
                source.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO"),
            )
        }
    }

    @Test
    fun onlyTheRailMovesWithTheVoice() {
        // Two things moving with the voice read as two meters, and the user cannot then tell which one
        // is the signal.
        assertTrue("the rail must take a level", meter.contains("fun setLevel(value: Float)"))
        assertTrue("the mark must not", !mark.contains("fun setLevel"))
    }
}
