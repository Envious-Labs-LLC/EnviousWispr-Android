package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredTermRestorerPerformanceDeviceTest {
    @Test(timeout = 5_000L)
    fun maximumSupportedFuzzyPoolsStayWithinPhoneBudget() {
        val terms = (0 until 2_000).map { index ->
            CustomTerm(
                spelling = if (index == 1_999) "CanonicalWinner" else "Term%04dZZZ".format(index),
                aliases = listOf(
                    if (index == 1_999) "Supercalifragilistic" else "NoiseAlias%04dABC".format(index),
                ),
            )
        }
        val matcher = StructuredTermRestorer.compile(terms)

        assertEquals("CanonicalWinner", matcher.restore("Supercalifragilistix"))
        assertEquals("CanonicalWinner", matcher.restore("CanonicalWinnet"))
    }
}
