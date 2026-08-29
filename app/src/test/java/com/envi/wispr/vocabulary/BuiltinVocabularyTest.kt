package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinVocabularyTest {
    @Test
    fun macDefaultsArePresentAndUserSpellingOverridesThem() {
        assertEquals(11, BuiltinVocabulary.terms.size)
        assertEquals(11, BuiltinVocabulary.terms.map { it.spelling.lowercase() }.toSet().size)

        val userOverride = CustomTerm("EnviousWispr", aliases = listOf("my private alias"), imported = true)
        val merged = BuiltinVocabulary.withUserTerms(listOf(userOverride))

        assertEquals(11, merged.size)
        assertEquals(userOverride, merged.last())
        assertEquals(1, merged.count { it.spelling.equals("EnviousWispr", ignoreCase = true) })
        assertFalse(merged.last().aliases.contains("envious whisper"))
        assertTrue(merged.any { it.spelling == "Envious Labs" })
    }

    @Test
    fun saveIncludesPendingAliasWithoutRequiringSeparateAddTap() {
        assertEquals(
            listOf("first", "second"),
            CustomTermAuthoring.includePendingAlias(listOf("first"), " second "),
        )
        assertEquals(
            listOf("first"),
            CustomTermAuthoring.includePendingAlias(listOf("first"), " FIRST "),
        )
    }
}
