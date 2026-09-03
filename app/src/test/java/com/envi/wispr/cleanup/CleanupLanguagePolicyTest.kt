package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome. When this fails, the app acts on a language it has not established, or refuses to act
 * on one it has, and the user's foreign words get rewritten by English rules.
 */
class CleanupLanguagePolicyTest {

    @Test fun noAnswerAbstains() {
        assertEquals(CleanupLanguage.Unknown, CleanupLanguagePolicy.resolve(null))
    }

    @Test fun belowTheFloorAbstains() {
        val justUnder = CleanupLanguagePolicy.MIN_CONFIDENCE - 0.01f
        assertEquals(CleanupLanguage.Unknown, CleanupLanguagePolicy.resolve(DetectedLanguage("de", justUnder)))
    }

    @Test fun atTheFloorIsAccepted() {
        assertEquals(
            CleanupLanguage.Known("de"),
            CleanupLanguagePolicy.resolve(DetectedLanguage("de", CleanupLanguagePolicy.MIN_CONFIDENCE)),
        )
    }

    @Test fun aNonFiniteConfidenceAbstains() {
        // Infinity satisfies `>= floor` while saying nothing about certainty, and NaN fails every
        // comparison. Both must land on abstention rather than on a confident wrong answer.
        assertEquals(CleanupLanguage.Unknown, CleanupLanguagePolicy.resolve(DetectedLanguage("de", Float.NaN)))
        assertEquals(
            CleanupLanguage.Unknown,
            CleanupLanguagePolicy.resolve(DetectedLanguage("de", Float.POSITIVE_INFINITY)),
        )
    }

    @Test fun theUndeterminedSentinelNeverBecomesALanguage() {
        assertEquals(CleanupLanguage.Unknown, CleanupLanguagePolicy.resolve(DetectedLanguage("und", 1.0f)))
        assertEquals(CleanupLanguage.Unknown, CleanupLanguagePolicy.resolve(DetectedLanguage("", 1.0f)))
    }

    @Test fun regionTagsReduceToTheirBase() {
        assertEquals(CleanupLanguage.Known("pt"), CleanupLanguagePolicy.resolve(DetectedLanguage("pt-BR", 1.0f)))
        assertEquals(CleanupLanguage.Known("en"), CleanupLanguagePolicy.resolve(DetectedLanguage("EN_GB", 1.0f)))
        assertEquals(CleanupLanguage.Known("zh"), CleanupLanguagePolicy.resolve(DetectedLanguage("zh-Latn", 1.0f)))
    }

    @Test fun onlyEnglishGainsTheExtraFiller() {
        assertEquals(setOf("um"), CleanupLanguagePolicy.extraFillers(CleanupLanguage.Known("en")))
        assertEquals(emptySet<String>(), CleanupLanguagePolicy.extraFillers(CleanupLanguage.Known("de")))
        assertEquals(emptySet<String>(), CleanupLanguagePolicy.extraFillers(CleanupLanguage.Unknown))
    }

    @Test fun onlyAnEstablishedNonEnglishLanguageSkipsTheEnglishFamilies() {
        assertTrue(CleanupLanguagePolicy.skipsEnglishRewrites(CleanupLanguage.Known("nl")))
        assertFalse(CleanupLanguagePolicy.skipsEnglishRewrites(CleanupLanguage.Known("en")))
        assertFalse(CleanupLanguagePolicy.skipsEnglishRewrites(CleanupLanguage.Unknown))
    }

    /**
     * Harness Contract, and it is a SMOKE TEST rather than a proof. It checks only that representative
     * language states stay lookup-safe. It does NOT protect the population: replacing the derivation
     * with a correct hand-written list of the same two members leaves this green, as review round 2
     * pointed out. What closes the population window is the derivation in `CleanupLanguagePolicy`
     * itself, not this row.
     */
    @Test fun sampledLanguageStatesAllResolveToADeclaredExtraFillerSet() {
        val states = listOf(
            CleanupLanguage.Unknown,
            CleanupLanguage.Known("en"),
            CleanupLanguage.Known("de"),
            CleanupLanguage.Known("nl"),
            CleanupLanguage.Known("pt"),
        )
        states.forEach { state ->
            val extras = CleanupLanguagePolicy.extraFillers(state)
            assertTrue(
                "$state produced extras $extras, which allExtraFillerSets does not declare",
                extras in CleanupLanguagePolicy.allExtraFillerSets,
            )
            DeterministicCleanup.fillerMatcher(state)
        }
    }
}
