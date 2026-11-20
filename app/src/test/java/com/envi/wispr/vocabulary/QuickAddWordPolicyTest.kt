package com.envi.wispr.vocabulary

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * PRODUCT OUTCOME (#31). Protects text-selection normalization, match ranking via
 * `QuickAddRanking`, and `ACTION_PROCESS_TEXT` manifest registration for Quick Add.
 */
class QuickAddWordPolicyTest {

    @Test
    fun normalizeCollapsesUnicodeWhitespaceAndCapsOversizedInput() {
        assertEquals(QuickAddWordPolicy.Normalization.Blank, QuickAddWordPolicy.normalize(null))
        assertEquals(QuickAddWordPolicy.Normalization.Blank, QuickAddWordPolicy.normalize("   \n\t\u00A0  "))
        assertEquals(
            QuickAddWordPolicy.Normalization.Valid("Envious Wispr"),
            QuickAddWordPolicy.normalize("  Envious\u00A0\n\tWispr\u2028 "),
        )
        val exactMax = "a".repeat(QuickAddWordPolicy.MAX_SPELLING_LENGTH)
        assertEquals(exactMax, QuickAddWordPolicy.normalizeCandidate(exactMax))
        assertEquals(
            QuickAddWordPolicy.Normalization.TooLong,
            QuickAddWordPolicy.normalize("a".repeat(QuickAddWordPolicy.MAX_SPELLING_LENGTH + 1)),
        )
        assertEquals(
            QuickAddWordPolicy.Normalization.TooLong,
            QuickAddWordPolicy.normalize(" ".repeat(QuickAddWordPolicy.MAX_RAW_INPUT_CHARS + 10)),
        )
        assertNull(QuickAddWordPolicy.normalizeCandidate("a".repeat(QuickAddWordPolicy.MAX_RAW_INPUT_CHARS + 50)))
    }

    @Test
    fun matchingTermsAndExactMatchUseSameWhitespaceNormalizationAsSave() {
        val terms = listOf(
            CustomTerm(spelling = "SuperWispr", usageCount = 20),
            CustomTerm(spelling = "Wispr", usageCount = 1),
            CustomTerm(spelling = "WisprKit", usageCount = 5),
            CustomTerm(spelling = "WisprPro", usageCount = 10),
            CustomTerm(spelling = "Envious Wispr", usageCount = 4),
            CustomTerm(spelling = "Envious", aliases = listOf("wispr-alias"), usageCount = 2),
            CustomTerm(spelling = "Unrelated", usageCount = 99),
        )
        val ranked = QuickAddWordPolicy.matchingTerms("wispr", terms, limit = 4)
        assertEquals(
            listOf("Wispr", "WisprPro", "WisprKit", "SuperWispr"),
            ranked.map { it.spelling },
        )

        val multiSpaceMatch = QuickAddWordPolicy.matchingTerms("envious  \u00A0 wispr", terms)
        assertEquals(listOf("Envious Wispr"), multiSpaceMatch.map { it.spelling })
        assertTrue(QuickAddWordPolicy.hasExactMatch("  envious  \u00A0 wispr ", terms))

        // Stored spelling containing a nonbreaking space still matches AND ranks as a prefix ahead of weaker matches.
        val nbspCandidates = listOf(
            CustomTerm(spelling = "My Envious Wispr One", usageCount = 50),
            CustomTerm(spelling = "My Envious Wispr Two", usageCount = 40),
            CustomTerm(spelling = "My Envious Wispr Three", usageCount = 30),
            CustomTerm(spelling = "Envious\u00A0Wispr Pro", usageCount = 1),
        )
        assertTrue(QuickAddWordPolicy.hasExactMatch("Envious Wispr Pro", nbspCandidates))
        assertEquals(
            "Envious\u00A0Wispr Pro",
            QuickAddWordPolicy.matchingTerms("Envious Wispr", nbspCandidates, limit = 3).first().spelling,
        )

        val aliasOnly = QuickAddWordPolicy.matchingTerms("wispr-alias", terms)
        assertEquals(listOf("Envious"), aliasOnly.map { it.spelling })
        assertTrue(QuickAddWordPolicy.matchingTerms("   ", terms).isEmpty())
    }

    @Test
    fun manifestRegistersQuickAddWordActivityWithExportedProcessTextFilter() {
        val manifestFile = File("src/main/AndroidManifest.xml").takeIf { it.exists() }
            ?: File("app/src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifestFile)
        val androidNs = "http://schemas.android.com/apk/res/android"

        val activities = doc.getElementsByTagName("activity")
        var quickAdd: Element? = null
        for (i in 0 until activities.length) {
            val element = activities.item(i) as Element
            if (element.getAttributeNS(androidNs, "name") == ".ui.QuickAddWordActivity") {
                quickAdd = element
                break
            }
        }
        assertTrue("QuickAddWordActivity must be declared in AndroidManifest.xml", quickAdd != null)
        assertEquals("true", quickAdd!!.getAttributeNS(androidNs, "exported"))

        val filters = quickAdd.getElementsByTagName("intent-filter")
        var hasCompleteProcessTextFilter = false
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val actions = (0 until filter.getElementsByTagName("action").length)
                .map { (filter.getElementsByTagName("action").item(it) as Element).getAttributeNS(androidNs, "name") }
            val categories = (0 until filter.getElementsByTagName("category").length)
                .map { (filter.getElementsByTagName("category").item(it) as Element).getAttributeNS(androidNs, "name") }
            val mimeTypes = (0 until filter.getElementsByTagName("data").length)
                .map { (filter.getElementsByTagName("data").item(it) as Element).getAttributeNS(androidNs, "mimeType") }
            if ("android.intent.action.PROCESS_TEXT" in actions &&
                "android.intent.category.DEFAULT" in categories &&
                "text/plain" in mimeTypes
            ) {
                hasCompleteProcessTextFilter = true
            }
        }
        assertTrue(
            "QuickAddWordActivity must have one intent-filter with PROCESS_TEXT, DEFAULT, and text/plain",
            hasCompleteProcessTextFilter,
        )
    }
}
