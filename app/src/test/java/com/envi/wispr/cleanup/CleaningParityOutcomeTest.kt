package com.envi.wispr.cleanup

import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: final spelling, intended emoji or measurement units are wrong when these fail. */
class CleaningParityOutcomeTest {
    private val english = CleanupLanguage.Known("en")
    private val british = CleanupOptions(englishSpelling = EnglishSpelling.BRITISH)

    @Test fun privateUseInputNeverCollidesWithProtectionAndLaterFailureKeepsCompletedText() {
        val literal = "\uE0000\uE001 the eleventh hour"
        assertEquals(literal, DeterministicCleanup.apply(literal, CleanupOptions(false, false, false)).text)
        val result = DeterministicCleanup.apply("uh keep these words", trace = { family, _ -> if (family == "fillers") error("observer failed") })
        assertTrue(result.recovered)
        assertEquals("keep these words", result.text)
    }

    @Test fun aLongExplicitEmojiCommandIsAnIntendedContractionNotTextLoss() {
        val input = List(12) { "thumbs up emoji" }.joinToString(" ")
        assertEquals(List(12) { "👍" }.joinToString(" "), DeterministicCleanup.apply(input, language = english).text)
    }

    @Test fun measurementUnitsAreNotHesitationSounds() {
        assertEquals("the gap is 5 mm and the battery is 5 Ah", DeterministicCleanup.apply("the gap is 5 mm and the battery is 5 Ah", language = english).text)
        assertEquals("give me 3 copies", DeterministicCleanup.apply("give me 3 um copies", language = english).text)
        assertEquals("ship today", DeterministicCleanup.apply("Umm, uhh, er, mmm, mm, ship today", language = english).text)
        assertEquals("the pressure is 120 mm Hg", DeterministicCleanup.apply("the pressure is 120 mm Hg", language = english).text)
        assertEquals("Er owns this project", DeterministicCleanup.apply("Er owns this project", CleanupOptions(spellingProtectedWords = setOf("er")), english).text)
        assertEquals("the ER team uses an HMM", DeterministicCleanup.apply("the ER team uses an HMM", language = english).text)
        assertEquals("To err is human", DeterministicCleanup.apply("To err is human", language = english).text)
    }

    @Test fun everyBundledPhraseMatchesThePinnedReferenceFormatter() {
        checkNotNull(CleanupResources.emoji) { "the production emoji asset did not load" }
        var count = 0
        checkNotNull(javaClass.getResourceAsStream("/cleanup/current-mac-emoji.jsonl")).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val row = kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
                val input = (row.getValue("input") as kotlinx.serialization.json.JsonPrimitive).content
                val expected = (row.getValue("expected") as kotlinx.serialization.json.JsonPrimitive).content
                assertEquals(input, expected, SpokenEmojiFormatter.format(input)); count++
            }
        }
        assertTrue("no reference formatter rows loaded", count > 0)
    }

    @Test fun literalDiscussionAndBareSentimentStayWords() {
        assertEquals("the heart emoji category is here", SpokenEmojiFormatter.format("the heart emoji category is here"))
        assertEquals("fire heart thumbs up", SpokenEmojiFormatter.format("fire heart thumbs up"))
        assertEquals("(👍)", SpokenEmojiFormatter.format("(thumbs up emoji)"))
        assertEquals("👍", SpokenEmojiFormatter.format("thumbz up emoji"))
    }

    @Test fun theRealPostModelDictionaryRetainsBritishProseAndTheUsersSavedAmericanWord() {
        val user = listOf(com.envi.wispr.vocabulary.CustomTerm("center"))
        val matcher = com.envi.wispr.vocabulary.StructuredTermRestorer.compile(com.envi.wispr.vocabulary.BuiltinVocabulary.withUserTerms(user))
        val options = british.copy(spellingProtectedWords = BritishSpelling.protectedWords(user.map { it.spelling }))
        val raw = matcher.restore("The organization needs to prioritize the color review at the center")
        val result = PolishPipeline.run(raw, options, english) { "The organization needs to prioritize the color review at the center." }
        assertTrue(result.usedModel)
        assertEquals("The organisation needs to prioritise the colour review at the center.", matcher.restore(result.text))
    }

    @Test fun britishSpellingSurvivesAnAmericanModelAnswerAndModelFailure() {
        val raw = "we should organize the color review"
        val accepted = PolishPipeline.run(raw, british, english) { cleaned ->
            assertEquals("we should organise the colour review", cleaned)
            "We should organize the color review."
        }
        assertTrue(accepted.usedModel)
        assertEquals("We should organise the colour review.", accepted.text)
        val declined = PolishPipeline.run(raw, british, english) { null }
        assertFalse(declined.usedModel)
        assertEquals("we should organise the colour review", declined.text)
    }

    @Test fun spellingProtectsUserNamesCodeAndLanguageAbstention() {
        val protected = BritishSpelling.protectedWords(listOf("Kennedy Center"))
        val raw = "the color guide at Kennedy Center uses color.js and center.io"
        assertEquals("the colour guide at Kennedy Center uses color.js and center.io", BritishSpelling.convert(raw, EnglishSpelling.BRITISH, english, protected))
        assertEquals(raw, BritishSpelling.convert(raw, EnglishSpelling.BRITISH, CleanupLanguage.Unknown, protected))
        assertEquals(raw, BritishSpelling.convert(raw, EnglishSpelling.BRITISH, CleanupLanguage.Known("es"), protected))
        assertEquals(EnglishSpelling.AMERICAN, EnglishSpelling.fromStored("foreign corrupt preference"))
    }

    @Test fun onlyTheExplicitLocalCapabilityRepairsAnAcceptedModelsLostEmoji() {
        val raw = "we shipped the feature today thumbs up emoji and the team is happy"
        val model: (String) -> String? = { "We shipped the feature today and the team is happy." }
        assertEquals("We shipped the feature today 👍 and the team is happy.", PolishPipeline.run(raw, language = english, restoreLocalEmoji = true, model = model).text)
        assertEquals("We shipped the feature today and the team is happy.", PolishPipeline.run(raw, language = english, model = model).text)
        val blank = PolishPipeline.run(raw, language = english, restoreLocalEmoji = true) { "" }
        assertFalse(blank.usedModel)
        assertEquals("we shipped the feature today 👍 and the team is happy", blank.text)
    }

    @Test fun repeatedAnchorsAndPartlyRetainedRunsKeepTheirOrderAndGraphemes() {
        val rows = listOf(
            Triple("Shipped it 🚀.", "Shipped it.", "Shipped it 🚀."),
            Triple("👀 wait that is wrong.", "Wait, that is wrong.", "👀 Wait, that is wrong."),
            Triple("Miami ☀️ 🌴 trip.", "Miami trip.", "Miami ☀️ 🌴 trip."),
            Triple("Happy birthday bro 🎉 🎂.", "Happy birthday, bro 🎉.", "Happy birthday, bro 🎉 🎂."),
            Triple("Happy birthday bro 🎉 🎂.", "Happy birthday, bro 🎂.", "Happy birthday, bro 🎉 🎂."),
            Triple("Party A 🎉 🎂 🚀 B end.", "Party A 🎉 🚀 B end.", "Party A 🎉 🎂 🚀 B end."),
            Triple("Done. 🚀", "Done.", "Done. 🚀"),
            Triple("I really think I can't 🔥.", "I really think I can’t.", "I really think I can’t 🔥."),
            Triple("Great 👍🏽 team.", "Great team.", "Great 👍🏽 team."),
            Triple("Thanks 👨‍👩‍👧‍👦 everyone.", "Thanks everyone.", "Thanks 👨‍👩‍👧‍👦 everyone."),
        )
        for ((before, after, expected) in rows) assertEquals(before, expected, EmojiRestorer.restore(before, after))
        assertEquals("Great work 🎉 team.", EmojiRestorer.restore("🎉 Great work team.", "Great work 🎉 team."))
        assertEquals("Great 👍 team.", EmojiRestorer.restore("Great 👍🏽 team.", "Great 👍 team."))
        assertEquals("", EmojiRestorer.restore("hello 🔥", ""))
    }

    @Test fun actualAlignmentTokensBoundLongPunctuationSeparatedInput() {
        val before = (1..1_001).joinToString(",") { "word$it" } + " 🔥"
        val after = before.removeSuffix(" 🔥")
        assertEquals(1_001, EmojiRestorer.alignmentTokenCount(before))
        assertEquals(after, EmojiRestorer.restore(before, after))
    }
}
