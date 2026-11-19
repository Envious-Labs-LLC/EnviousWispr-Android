package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * PRODUCT OUTCOME (#52): shared card/row mockup style owns violet card borders (brighter when
 * selected), leading icon tiles on featured cards/rows, accessible green switches from one theme
 * owner, dot-plus-word ready status (no filled pill box), and icon-leading fact pills.
 */
class MockupStyleTest {

    private fun uiFile(name: String): String =
        listOf(File("src/main/java/com/envi/wispr/ui/$name"), File("app/src/main/java/com/envi/wispr/ui/$name"))
            .first { it.isFile }
            .readText()

    private fun scriptFile(name: String): String =
        listOf(File("../scripts/$name"), File("scripts/$name"))
            .first { it.isFile }
            .readText()

    private fun luminance(rgb: Int): Double {
        fun channel(raw: Int): Double {
            val c = raw / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun parseColorRole(block: String, role: String): Int {
        val m = Regex("""\b$role\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""").find(block)
        assertNotNull("missing color role $role", m)
        return m!!.groupValues[1].toInt(16)
    }

    private fun parseBrandGreen(line: String): Triple<Int, Int, Int> {
        val m = Regex("""BrandGreen\(Color\(0xFF([0-9A-Fa-f]{6})\),\s*Color\(0xFF([0-9A-Fa-f]{6})\),\s*Color\(0xFF([0-9A-Fa-f]{6})\)\)""")
            .find(line)
        assertNotNull("missing BrandGreen literal in: $line", m)
        return Triple(
            m!!.groupValues[1].toInt(16),
            m.groupValues[2].toInt(16),
            m.groupValues[3].toInt(16),
        )
    }

    @Test
    fun greenSwitchAndStatusColorsFromThemeClearContrastBarsInLightAndDark() {
        val theme = uiFile("theme/EnviousWisprTheme.kt")
        val lightScheme = theme.substringAfter("private val EnviousLightColors").substringBefore("private val EnviousDarkColors")
        val darkScheme = theme.substringAfter("private val EnviousDarkColors").substringBefore("private val EnviousShapes")
        val lightGreenLine = theme.substringAfter("val brandGreen =").lines().first { it.contains("else -> BrandGreen") }
        val darkGreenLine = theme.substringAfter("val brandGreen =").lines().first { it.contains("darkTheme -> BrandGreen") }

        val (lightTrack, lightThumb, lightStatusText) = parseBrandGreen(lightGreenLine)
        val (darkTrack, darkThumb, darkStatusText) = parseBrandGreen(darkGreenLine)

        val surfaceRoles = listOf("surface", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHighest")
        surfaceRoles.forEach { role ->
            val lightBg = parseColorRole(lightScheme, role)
            assertTrue("light switch track >= 3.0 on $role", contrastRatio(lightTrack, lightBg) >= 3.0)
            assertTrue("light ready text >= 4.5 on $role", contrastRatio(lightStatusText, lightBg) >= 4.5)

            val darkBg = parseColorRole(darkScheme, role)
            assertTrue("dark switch track >= 3.0 on $role", contrastRatio(darkTrack, darkBg) >= 3.0)
            assertTrue("dark ready text >= 4.5 on $role", contrastRatio(darkStatusText, darkBg) >= 4.5)
        }
        assertTrue("light switch thumb >= 4.5 on track", contrastRatio(lightThumb, lightTrack) >= 4.5)
        assertTrue("dark switch thumb >= 4.5 on track", contrastRatio(darkThumb, darkTrack) >= 4.5)

        // ReadinessChip dots on filled containers must also clear 3.0 (non-text UI boundary).
        assertTrue(
            "light readiness chip dot >= 3.0",
            contrastRatio(parseColorRole(lightScheme, "onPrimaryContainer"), parseColorRole(lightScheme, "primaryContainer")) >= 3.0,
        )
        assertTrue(
            "dark readiness chip dot >= 3.0",
            contrastRatio(parseColorRole(darkScheme, "onPrimaryContainer"), parseColorRole(darkScheme, "primaryContainer")) >= 3.0,
        )

        val paletteScript = scriptFile("generate-brand-palette.py")
        val darkBlock = paletteScript.substringAfter("MACOS_GREEN = {").substringBefore("\"light\":")
        val lightBlock = paletteScript.substringAfter("MACOS_GREEN = {").substringAfter("\"light\":").substringBefore("}")
        fun hex(value: Int) = "#${value.toString(16).padStart(6, '0').uppercase()}"
        assertTrue(lightBlock.contains("\"track\": \"${hex(lightTrack)}\""))
        assertTrue(lightBlock.contains("\"thumb\": \"${hex(lightThumb)}\""))
        assertTrue(lightBlock.contains("\"statusText\": \"${hex(lightStatusText)}\""))
        assertTrue(darkBlock.contains("\"track\": \"${hex(darkTrack)}\""))
        assertTrue(darkBlock.contains("\"thumb\": \"${hex(darkThumb)}\""))
        assertTrue(darkBlock.contains("\"statusText\": \"${hex(darkStatusText)}\""))
    }

    @Test
    fun themeOwnsGreenSwitchBorderAndStatusTokensAtOnePlace() {
        val theme = uiFile("theme/EnviousWisprTheme.kt")
        assertTrue(theme.contains("internal fun brandSwitchColors("))
        assertTrue(theme.contains("internal fun brandCardBorder("))
        assertTrue(theme.contains("internal fun brandReadyColor("))
        assertTrue(theme.contains("internal fun brandReadyTextColor("))

        val components = uiFile("SettingsComponents.kt")
        assertTrue(components.contains("colors = brandSwitchColors()"))
        assertEquals(1, Regex("""\bSwitch\(""").findAll(components).count())
        assertTrue(components.contains("internal fun BrandIconTile("))
        assertTrue(components.contains("border = brandCardBorder()"))
    }

    @Test
    fun statusPillIsDotPlusWordAndModelFactsHaveLeadingIcons() {
        val components = uiFile("SettingsComponents.kt")
        val statusPillBody = components.substringAfter("internal fun StatusPill(").substringBefore("internal fun StatusDot(")
        assertFalse("StatusPill no longer renders a filled pill Surface", statusPillBody.contains("Surface("))
        assertTrue(statusPillBody.contains("StatusDot(ready = ready, decorative = true)"))
        assertTrue(statusPillBody.contains("brandReadyTextColor()"))

        val modelCards = uiFile("ModelCards.kt")
        assertTrue(modelCards.contains("border = brandCardBorder(selected = selected)"))
        assertTrue(modelCards.contains("FactChipGlyph(text)"))
        val transcription = uiFile("TranscriptionScreen.kt")
        assertTrue(transcription.contains("WaveformGlyph()"))
        assertTrue(transcription.contains("FilterGlyph()"))
        assertTrue(transcription.contains("EmojiGlyph()"))
        assertTrue(transcription.contains("PunctuationGlyph()"))
    }
}
