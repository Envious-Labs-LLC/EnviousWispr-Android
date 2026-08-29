package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomTermEntityTest {
    @Test fun entityRoundTripPreservesStructuredTerm() {
        val term = CustomTerm(
            spelling = "  Café  ",
            aliases = listOf("comma, alias", "line\nvalue", "unit\u001Fseparator", "slash\\value"),
            category = " product ",
            priority = 120,
            forceReplace = true,
            caseSensitive = true,
            minSimilarityOverride = 0.72,
            usageCount = -4,
            imported = true,
        )
        val entity = CustomTermEntity.fromCustomTerm(term, nowMs = 42L)
        assertEquals("Café", entity.spelling)
        assertEquals(listOf("comma, alias", "line\nvalue", "unit\u001Fseparator", "slash\\value"), entity.toCustomTerm().aliases)
        assertEquals("product", entity.category)
        assertEquals(100, entity.priority)
        assertEquals(0L, entity.usageCount)
        assertEquals(true, entity.toCustomTerm().imported)
        assertEquals(0.72, entity.toCustomTerm().minSimilarityOverride!!, 0.0)
    }
}
