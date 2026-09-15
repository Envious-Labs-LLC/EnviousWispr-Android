package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val cancel = overlay.substringAfter("actionButton(ActionGlyph.CROSS").substringBefore("},")
        val accept = overlay.substringAfter("actionButton(ActionGlyph.CHECK").substringBefore("},")
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
            overlay.contains("actionButton(ActionGlyph.CHECK, \"Stop and use these words\", BrandPalette.ACCENT)"),
        )
        assertTrue(
            "the X must be the quiet one",
            overlay.contains("actionButton(ActionGlyph.CROSS, \"Cancel\", BrandPalette.NEUTRAL_CONTROL)"),
        )
        // Drawn, never typed: a text glyph sits where its font's line box puts it, and the founder saw
        // the "×" low in its circle on the phone (2026-09-12).
        assertTrue("the controls must draw their symbols", overlay.contains(") = ActionGlyphView(service, glyph).apply {"))
        assertTrue("no text glyph may remain", !overlay.contains("\"×\"") && !overlay.contains("\"✓\""))
    }

    @Test
    fun theMarkIsTheBrandLipsOnTheSharedGeometry() {
        // The launcher icon, the onboarding lips, the approved bubble mock and the macOS icon all draw
        // the same 256-unit lips: nine bars per lip, 14 wide on a 24 step from 24, radius 5. The
        // founder's first look at the Play build caught a different mark here (2026-09-12).
        assertEquals(9, BrandMarkView.BAR_COUNT)
        assertEquals(14f, BrandMarkView.BAR_WIDTH)
        assertEquals(24f, BrandMarkView.BAR_LEFT)
        assertEquals(24f, BrandMarkView.BAR_STEP)
        assertEquals(5f, BrandMarkView.BAR_RADIUS)
        // The same numbers as res/drawable/ic_launcher_monochrome.xml, whose path is stroked 14 wide
        // with round caps, so each bar there runs from (y - 7) to (y + 7) around its segment.
        val icon = File("src/main/res/drawable/ic_launcher_monochrome.xml").readText()
        val segments = Regex("M(\\d+),([\\d.]+)V([\\d.]+)").findAll(icon).map { m ->
            Triple(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
        }.toList()
        assertEquals(18, segments.size)
        segments.forEachIndexed { i, (x, y0, y1) ->
            val row = if (i < 9) BrandMarkView.UPPER_TOP to BrandMarkView.UPPER_HEIGHT else BrandMarkView.LOWER_TOP to BrandMarkView.LOWER_HEIGHT
            val index = i % 9
            assertEquals("bar $i left", BrandMarkView.BAR_LEFT + index * BrandMarkView.BAR_STEP, x - 7f, 0.01f)
            assertEquals("bar $i top", row.first[index], y0 - 7f, 0.01f)
            assertEquals("bar $i height", row.second[index], (y1 + 7f) - (y0 - 7f), 0.01f)
        }
        // The lower lip runs the rainbow backwards and both lips end in violet.
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1, 0, 8), BrandMarkView.LOWER_COLOUR.toList())
    }

    @Test
    fun thePillCarriesTheFounderSpecifiedOrder() {
        // From docs/mockups/android-v2/06-floating-recorder.png: time, rail, cancel, accept. The mockup's
        // mark and LISTENING word were dropped by the founder on 2026-09-13 (build 114 phone pass): the
        // lips are on the bubble already, and the bar is smaller without them.
        val body = overlay.substringAfter("private fun buildPill()").substringBefore("private fun pillBackground()")
        assertFalse("the pill carries no lips mark", body.contains("BrandMarkView("))
        assertFalse("the pill carries no state label", body.contains("stateLabel"))
        // The recording mark is the hold pill's thumb-end slot (founder 2026-09-15), last in the row and
        // hidden on the tap pill.
        val order = listOf("timer,", "meter,", "cancelButton,", "acceptButton,", "recordMark,")
            .map { it to body.indexOf(it) }
        order.forEach { (piece, at) -> assertTrue("$piece is not in the pill", at >= 0) }
        assertEquals(
            "the pill's order must match the mockup",
            order.sortedBy { it.second }.map { it.first },
            order.map { it.first },
        )
    }

    @Test
    fun noSurfaceCarriesAnOutlineAndTheLookPaintsAllThree() {
        // Founder 2026-09-14: the violet outline and violet glow are retired; none of the three looks
        // has a border, and one ground colour is shared by the bubble and both pills.
        assertFalse("no bubble or pill may draw the violet stroke", overlay.contains("setStroke(dp(1).coerceAtLeast(1), BrandPalette.VIOLET)"))
        val painters = overlay.substringAfter("private fun applyLook()").substringBefore("private fun buildHideTarget()")
        assertFalse("no bubble or pill may draw any stroke", painters.contains("setStroke("))
        assertFalse("no violet glow remains", overlay.contains("ShadowColor = BrandPalette.VIOLET"))
        val apply = overlay.substringAfter("private fun applyLook()").substringBefore("\n    }\n")
        listOf("bubble.background", "bubble.elevation", "pill.background", "pill.elevation", "bubbleMark.inkEdgePx", "meter.inkEdgePx", "cancelButton.background", "acceptButton.background")
            .forEach { assertTrue("applyLook must set $it", apply.contains(it)) }
        assertTrue("the look is applied once the pill exists", overlay.contains("pill = container\n        applyLook()"))
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
        assertTrue("the rail must take the picture", meter.contains("fun setBands(bands: FloatArray)"))
        assertTrue(
            "the mark must not",
            !mark.contains("fun setBands") && !mark.contains("fun pushSample") && !mark.contains("fun setLevel"),
        )
    }
}
