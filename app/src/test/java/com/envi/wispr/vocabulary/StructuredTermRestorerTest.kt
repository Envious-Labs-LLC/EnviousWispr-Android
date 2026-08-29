package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTermRestorerTest {
    @Test fun exactMultiWordAliasUsesPreferredSpelling() {
        val words = listOf(CustomTerm("Visual Studio Code", aliases = listOf("vs code")))
        assertEquals("I opened Visual Studio Code", restore("I opened vs code", words))
    }

    @Test fun exactSingleAliasAndCanonicalSelfEntryFixCasing() {
        val words = listOf(
            CustomTerm("ChatGPT", aliases = listOf("chatgpt")),
            CustomTerm("iPhone"),
        )
        assertEquals("ChatGPT works on iPhone", restore("chatgpt works on iphone", words))
    }

    @Test fun fuzzyAliasAndCanonicalFallbackAreIndependent() {
        val aliasInput = "coobernettie"
        assertTrue(StructuredTermRestorer.score(aliasInput, "coobernetties") >= StructuredTermRestorer.DEFAULT_THRESHOLD)
        assertTrue(StructuredTermRestorer.score(aliasInput, "kubernetes") < StructuredTermRestorer.DEFAULT_THRESHOLD)
        assertEquals(
            "Kubernetes",
            restore(aliasInput, listOf(CustomTerm("Kubernetes", aliases = listOf("coobernetties")))),
        )
        assertEquals(
            "Kubernetes",
            restore("kuberntes", listOf(CustomTerm("Kubernetes"))),
        )
    }

    @Test fun shortAndDistantWordsAreNotGuessed() {
        assertEquals("use apt to install", restore("use apt to install", listOf(CustomTerm("API"))))
        assertEquals("I like bananas", restore("I like bananas", listOf(CustomTerm("Kubernetes"))))
        assertEquals("gi", restore("gi", listOf(CustomTerm("Go"))))
    }

    @Test fun nearTiedCandidatesAreRejected() {
        val words = listOf(CustomTerm("Kubernetes"), CustomTerm("Kubernotes"))
        assertEquals("kuberntes works", restore("kuberntes works", words))
    }

    @Test fun looseAcceptsWhatDefaultAndStrictReject() {
        val input = "kaberntes"
        assertEquals("Kubernetes", restore(input, listOf(CustomTerm("Kubernetes", minSimilarityOverride = 0.72))))
        assertEquals(input, restore(input, listOf(CustomTerm("Kubernetes"))))
        assertEquals(input, restore(input, listOf(CustomTerm("Kubernetes", minSimilarityOverride = 0.92))))
        assertEquals("Kubernetes", restore("kubernetes", listOf(CustomTerm("Kubernetes", minSimilarityOverride = 0.92))))
    }

    @Test fun compoundPassHandlesOneToThreeTokensAndStopsAtPunctuation() {
        val words = listOf(CustomTerm("ChatGPT"), CustomTerm("OpenAI"))
        assertEquals("I used ChatGPT with OpenAI", restore("I used chat gpt with open a i", words))
        assertEquals("Open. A I", restore("Open. A I", words))
        assertEquals("one two three four", restore("one two three four", listOf(CustomTerm("OneTwoThreeFour"))))
    }

    @Test fun punctuationAndWhitespaceOutsideMatchesArePreserved() {
        val words = listOf(CustomTerm("ChatGPT", aliases = listOf("chatgpt")))
        assertEquals("(ChatGPT),  works!", restore("(chatgpt),  works!", words))
    }

    @Test fun gluedDomainSuffixSurvivesExactAndFuzzyCorrection() {
        val words = listOf(CustomTerm("EnviousWispr", aliases = listOf("Enviousvisper")))
        assertEquals("EnviousWispr.com", restore("Enviousvisper.com", words))
        assertEquals("EnviousWispr.co.uk", restore("Enviousvispr.co.uk", words))
        assertEquals("unrelated.com", restore("unrelated.com", words))
    }

    @Test fun domainCanonicalDoesNotDuplicateOrAppendSuffix() {
        val words = listOf(CustomTerm("GitHub.com", aliases = listOf("githab")))
        assertEquals("GitHub.com", restore("githab.org", words))
    }

    @Test fun numericAndVersionSuffixesAreNotPeeled() {
        val words = listOf(CustomTerm("VersionOne", aliases = listOf("v1")))
        assertEquals(
            "v1.2 v1.2beta v1.2.3rc1 192.168.1.2",
            restore("v1.2 v1.2beta v1.2.3rc1 192.168.1.2", words),
        )
    }

    @Test fun exactReplacementsCannotCascadeIntoAnotherTerm() {
        val words = listOf(
            CustomTerm("Alpha", aliases = listOf("x")),
            CustomTerm("Beta", aliases = listOf("Alpha")),
        )
        assertEquals("Alpha Beta", restore("x Alpha", words))
    }

    @Test fun longestExactMultiWordMatchWinsAndIsProtected() {
        val words = listOf(
            CustomTerm("Alpha Beta Gamma", aliases = listOf("alpha beta gamma")),
            CustomTerm("Wrong", aliases = listOf("beta gamma", "gamma")),
        )
        assertEquals("Alpha Beta Gamma", restore("alpha beta gamma", words))
        assertEquals("Alpha Beta Gamma.com", restore("Alpha Beta Gamma.com", words))
    }

    @Test fun correctCompoundOwnerDeclinesSoOrdinaryAliasCanWinLikeMac() {
        val words = listOf(
            CustomTerm("Annie"),
            CustomTerm("Anika", aliases = listOf("Annie")),
        )
        assertEquals("Anika", restore("Annie", words))
    }

    @Test fun duplicateCanonicalsKeepAllAliasesAndLaterAliasAuthority() {
        val words = listOf(
            CustomTerm("Brand", aliases = listOf("first sound")),
            CustomTerm("Brand", aliases = listOf("second sound")),
        )
        assertEquals("Brand Brand", restore("first sound second sound", words))
    }

    @Test fun fuzzyPeeledAlreadyCorrectSpanIsProtectedFromShorterAliases() {
        val words = listOf(
            CustomTerm("Alpha Beta Gamma", aliases = listOf("Alfa Beta Gamma")),
            CustomTerm("Wrong", aliases = listOf("gamma")),
        )
        assertEquals("Alpha Beta Gamma.com", restore("Alpha Beta Gamma.com", words))
    }

    @Test fun multiWordFuzzyDoesNotUseSingleWordLengthRatioGate() {
        val words = listOf(CustomTerm("Result", aliases = listOf("a beta"), minSimilarityOverride = 0.0))
        assertEquals("Result", restore("alphabet soup", words))
    }

    @Test fun expandedMacDomainSuffixSetAvoidsDuplicateReattachment() {
        val words = listOf(CustomTerm("GitHub.travel", aliases = listOf("githab")))
        assertEquals("GitHub.travel", restore("githab.org", words))
    }

    @Test fun dotBearingExactCompoundCanFixCanonicalCasing() {
        val words = listOf(CustomTerm("D3.js"))
        assertEquals("D3.js", restore("d3.js", words))
    }

    @Test fun exactAliasesRetainArbitraryInternalPunctuation() {
        val terms = listOf(
            CustomTerm("support@example.com", aliases = listOf("help@example.com")),
            CustomTerm("snake_case", aliases = listOf("snake_casee")),
        )
        assertEquals(
            "support@example.com snake_case",
            restore("help@example.com snake_casee", terms),
        )
    }

    @Test fun internalApostrophesAndHyphensRemainPartOfOneToken() {
        val words = listOf(
            CustomTerm("O'Reilly", aliases = listOf("o'reily")),
            CustomTerm("S1-mini", aliases = listOf("s1-miny")),
        )
        assertEquals("O'Reilly and S1-mini", restore("o'reily and s1-miny", words))
    }

    @Test fun noSpacePassKeepsItsFirstOwnerAndPriorityDoesNotMatter() {
        val alpha = CustomTerm("Alpha", aliases = listOf("shared"), priority = 100)
        val beta = CustomTerm("Beta", aliases = listOf("shared"), priority = -100)
        assertEquals("Alpha", restore("shared", listOf(alpha, beta)))
        assertEquals("Alpha", restore("shared", listOf(alpha.copy(priority = -100), beta.copy(priority = 100))))
    }

    @Test fun fourTokenAliasUsesOrdinaryMultiWordAuthority() {
        val term = CustomTerm("Editor", aliases = listOf("the visual studio code"))
        assertEquals("Editor", restore("the visual studio code", listOf(term)))
    }

    @Test fun shortAliasUsesOrdinaryLastWriterAuthority() {
        val words = listOf(
            CustomTerm("Alpha", aliases = listOf("ny")),
            CustomTerm("Beta", aliases = listOf("ny")),
        )
        assertEquals("Beta", restore("ny", words))
    }

    @Test fun ordinaryCanonicalSelfEntryYieldsToAlias() {
        val words = listOf(
            CustomTerm("Alpha", aliases = listOf("ny")),
            CustomTerm("NY"),
        )
        assertEquals("Alpha", restore("ny", words))
    }

    @Test fun noSpaceCanonicalOverwritesAliasOwner() {
        val words = listOf(
            CustomTerm("Alpha", aliases = listOf("react native")),
            CustomTerm("React Native"),
        )
        assertEquals("React Native", restore("react native", words))
    }

    @Test fun fuzzyMultiWordAliasCorrects() {
        val term = CustomTerm("Release", aliases = listOf("teams have shipped"))
        assertEquals("Release", restore("teams have ship", listOf(term)))
    }

    @Test fun stopwordPenaltyRejectsMarginalMultiWordMatch() {
        val guarded = CustomTerm("Release", aliases = listOf("and we shipped"))
        val control = CustomTerm("Release", aliases = listOf("teams have shipped"))
        assertEquals("and we ship", restore("and we ship", listOf(guarded)))
        assertEquals("Release", restore("teams have ship", listOf(control)))
    }

    @Test fun forceAndCaseFlagsCharacterizeCurrentMacRuntimeAsMetadataOnly() {
        val term = CustomTerm(
            "APIKey",
            aliases = listOf("API key"),
            forceReplace = true,
            caseSensitive = true,
        )
        assertEquals("APIKey APIKey", restore("api key API key", listOf(term)))
        assertEquals("apt", restore("apt", listOf(CustomTerm("API", forceReplace = true))))
    }

    @Test fun reservedEmojiTriggerWordsAreNeverConsumed() {
        val words = listOf(
            CustomTerm("Replacement", aliases = listOf("emoji")),
            CustomTerm("Wrong Phrase", aliases = listOf("thumbs up emoji")),
        )
        assertEquals("thumbs up emoji", restore("thumbs up emoji", words))
        assertEquals("emoji.com", restore("emoji.com", words))
    }

    @Test fun matcherIsIdempotent() {
        val matcher = StructuredTermRestorer.compile(
            listOf(CustomTerm("EnviousWispr", aliases = listOf("envious whisper"))),
        )
        val once = matcher.restore("envious whisper works")
        assertEquals(once, matcher.restore(once))
    }

    @Test(timeout = 5_000L)
    fun maximumSupportedFuzzyPoolsCompileAndRestoreWithinBudget() {
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

    @Test fun scoringMatchesMacWeightingShape() {
        assertEquals(1.0, StructuredTermRestorer.score("hello", "hello"), 0.001)
        assertEquals(
            0.8894117647058823,
            StructuredTermRestorer.score("kuberntes", "kubernetes"),
            1e-12,
        )
        assertTrue(StructuredTermRestorer.score("kuberntes", "kubernetes") >= StructuredTermRestorer.DEFAULT_THRESHOLD)
        assertTrue(StructuredTermRestorer.score("banana", "kubernetes") < StructuredTermRestorer.DEFAULT_THRESHOLD)
    }

    private fun restore(text: String, terms: List<CustomTerm>): String =
        StructuredTermRestorer.restore(text, terms)
}
