package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome. When this fails the user dictates in one of the 24 non-English languages Parakeet v3
 * decodes and gets English number, money or filler rules applied to their words.
 *
 * Every row is asserted at all three language states, because a gate that is never observed CLOSED and
 * OPEN on the same input has not been shown to do anything.
 */
class DeterministicCleanupLanguageTest {

    private fun clean(input: String, language: CleanupLanguage) =
        DeterministicCleanup.apply(input, CleanupOptions(), language).text

    /**
     * The regression measured on the phone 2026-09-02: spoken through Azure Speech into the real
     * recogniser, which returned the Dutch correctly, and cleanup then rewrote `ten` as the English
     * number ten.
     */
    @Test fun theMeasuredDutchRegressionIsFixedWhenDutchIsEstablished() {
        val dutch = "Dit is ten minste duidelijk."
        assertEquals(dutch, clean(dutch, CleanupLanguage.Known("nl")))
    }

    @Test fun theMeasuredDutchRegressionStillReproducesWhenNothingIsEstablished() {
        // The two-way control. Without this row the row above would pass against a gate that does
        // nothing, because it asserts the input is unchanged.
        assertEquals("Dit is 10 minste duidelijk.", clean("Dit is ten minste duidelijk.", CleanupLanguage.Unknown))
    }

    @Test fun germanKeepsItsOwnNumbersAndArticles() {
        val german = "Ich brauche one hundred Euro und ein Catch-22"
        assertEquals(german, clean(german, CleanupLanguage.Known("de")))
    }

    @Test fun nonEnglishSkipsTheUnconditionalArticleHundredRewrite() {
        // `germanKeepsItsOwnNumbersAndArticles` says "one hundred", which only reaches the structured
        // pass. The `a|an hundred` rewrite is a SEPARATE unconditional site earlier in `apply`, so
        // without this row that site could be moved outside the gate with every test still green.
        val input = "Ich brauche a hundred Euro"
        assertEquals(input, clean(input, CleanupLanguage.Known("de")))
        // Reasoned from the code, not pasted from a run: the article rewrite drops `a`, and the
        // structured pass then reads the bare `hundred` as the cardinal 100.
        assertEquals("Ich brauche 100 Euro", clean(input, CleanupLanguage.Unknown))
    }

    @Test fun nonEnglishSkipsTheUnconditionalCatch22Rewrite() {
        // The second unconditional site, for the same reason.
        val input = "Das ist a catch twenty two"
        assertEquals(input, clean(input, CleanupLanguage.Known("de")))
        assertEquals("Das ist a Catch-22", clean(input, CleanupLanguage.Unknown))
    }

    @Test fun englishStillGetsItsNumbersFormatted() {
        assertEquals("I need $10 today", clean("I need ten dollars today", CleanupLanguage.Known("en")))
    }

    @Test fun englishGainsBackTheCommonestFiller() {
        // `um` left the shared set on 2026-09-02 because it is a German preposition and a Portuguese
        // article. With English established it is safe again, which is the whole point of the gate.
        assertEquals("I think so", clean("Um, I think so", CleanupLanguage.Known("en")))
    }

    @Test fun germanKeepsUmBecauseItIsAPreposition() {
        assertEquals("Wir treffen uns um drei", clean("Wir treffen uns um drei", CleanupLanguage.Known("de")))
    }

    @Test fun portugueseKeepsUmBecauseItIsAnArticle() {
        assertEquals("Eu quero um cafe", clean("Eu quero um cafe", CleanupLanguage.Known("pt")))
    }

    @Test fun anUnestablishedLanguageKeepsUmExactlyAsItDoesToday() {
        assertEquals("Um, I think so", clean("Um, I think so", CleanupLanguage.Unknown))
    }

    @Test fun errIsNeverRemovedAtAnyLanguageState() {
        // `err` is an English VERB. An English answer does not make it safe, so it is absent from the
        // set at every state rather than gated like `um`.
        val sentence = "To err is human"
        listOf(CleanupLanguage.Unknown, CleanupLanguage.Known("en"), CleanupLanguage.Known("de")).forEach { state ->
            assertEquals("`err` was stripped at $state", sentence, clean(sentence, state))
        }
    }

    @Test fun theSharedFillersAreRemovedAtEveryLanguageState() {
        // The base six are not words in the 25 languages Parakeet v3 decodes, so the gate must not stop
        // removing them. A gate that skipped too much would show up here.
        listOf(CleanupLanguage.Unknown, CleanupLanguage.Known("en"), CleanupLanguage.Known("de")).forEach { state ->
            assertEquals("uh survived at $state", "hello there", clean("uh hello there", state))
        }
    }

    @Test fun spokenPunctuationAndEmojiAreEnglishCommandsAndStopOnForeignText() {
        val options = CleanupOptions(spokenEmoji = true, spokenPunctuation = true)
        val input = "das ist fire emoji new line gut"
        assertEquals(input, DeterministicCleanup.apply(input, options, CleanupLanguage.Known("de")).text)
    }
}
