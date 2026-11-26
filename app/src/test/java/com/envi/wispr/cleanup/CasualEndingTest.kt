package com.envi.wispr.cleanup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.envi.wispr.polish.PolishFallback
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.cleanupOptions
import com.envi.wispr.vocabulary.CustomTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Product Outcome (#3737): Casual mode strips the trailing full stop at the very end of a dictation
 * across cleanup, AI polish, emoji restoration, custom-word restoration, and fallback paths, while
 * preserving other endings, dotted abbreviations, ellipses, non-English ordinals after numbers, and
 * explicit spoken periods.
 */
class CasualEndingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun strip(
        text: String,
        language: String? = "en",
        spoken: Boolean = false,
    ): String = CasualEnding.removingFinalFullStop(text, language, spoken)

    @Test
    fun removesFinalFullStopAcrossSupportedScriptsAndTrailingWhitespace() {
        val cases = listOf(
            "Sounds good, see you at three." to "Sounds good, see you at three",
            "Hi. See you soon." to "Hi. See you soon",
            "Done.\n" to "Done\n",
            "on my way. " to "on my way ",
            "The price is 3.5." to "The price is 3.5",
            "See you at 5." to "See you at 5",
            "**done**." to "**done**",
            "今天很好。" to "今天很好",
            "यह ठीक है।" to "यह ठीक है",
        )
        cases.forEach { (input, expected) ->
            assertEquals("for input: $input", expected, strip(input))
        }
    }

    @Test
    fun keepsNonSentenceFinalDotsAbbreviationsEllipsesAndPunctuation() {
        val preserved = listOf(
            "Are you free?",
            "That is great!",
            "Wait...",
            "Wait…",
            "Wait.。",
            "Wait.।",
            "Meet me at 9 a.m.",
            "I live in the U.S.",
            "Bring snacks, e.g.",
            "She has a Ph.D.",
            "(see above.)",
            "\"hi.\"",
            "Thanks. 👍",
            ".",
            "  .  ",
            "",
            "no ending",
        )
        preserved.forEach { input ->
            assertEquals("for input: $input", input, strip(input))
        }
    }

    @Test
    fun spokenFinalFullStopIsAlwaysPreserved() {
        assertEquals("See you.", strip("See you.", spoken = true))
        assertEquals("See you", strip("See you.", spoken = false))
    }

    @Test
    fun ordinalDotAfterUnicodeNumberStaysOutsideEnglish() {
        assertEquals("Wir treffen uns am 3.", strip("Wir treffen uns am 3.", "de"))
        assertEquals("Wir treffen uns am 3.", strip("Wir treffen uns am 3.", "de-DE"))
        assertEquals("Die Fläche ist 5 m².", strip("Die Fläche ist 5 m².", "de"))
        assertEquals("Die Fläche ist 5 m².", strip("Die Fläche ist 5 m².", null))
        assertEquals("The area is 5 m²", strip("The area is 5 m².", "en"))
        assertEquals("See you at 3", strip("See you at 3.", "en-GB"))
        assertEquals("Bis morgen", strip("Bis morgen.", "de"))
    }

    @Test
    fun polishPipelineAppliesCasualEndingAfterModelSpellingAndEmojiOnAllExits() {
        val en = CleanupLanguage.Known("en")
        val off = PolishPipeline.run(
            "Sounds good, see you tomorrow.",
            options = CleanupOptions(casualMode = true),
            language = en,
        )
        assertEquals("Sounds good, see you tomorrow", off.text)

        val tooShort = PolishPipeline.run(
            "Wait.",
            options = CleanupOptions(casualMode = true),
            language = en,
        ) { "Expanded unnecessarily." }
        assertEquals(PipelineOutcome.TOO_SHORT, tooShort.outcome)
        assertEquals("Wait", tooShort.text)

        val polished = PolishPipeline.run(
            "we organized the program today and we will ship friday.",
            options = CleanupOptions(
                casualMode = true,
                englishSpelling = EnglishSpelling.BRITISH,
            ),
            language = en,
        ) { "We organized the program today and we will ship Friday." }
        assertEquals(PipelineOutcome.MODEL_ACCEPTED, polished.outcome)
        assertEquals("We organised the program today and we will ship Friday", polished.text)

        val declined = PolishPipeline.run(
            "This is already ready to send now.",
            options = CleanupOptions(casualMode = true),
            language = en,
        ) { null }
        assertEquals(PipelineOutcome.MODEL_DECLINED, declined.outcome)
        assertEquals("This is already ready to send now", declined.text)
    }

    @Test
    fun polishPipelinePreservesExplicitSpokenPeriodAcrossReconciledEndings() {
        val en = CleanupLanguage.Known("en")
        listOf(
            "See you at the office tomorrow period" to "See you at the office tomorrow.",
            "See you at the office tomorrow period." to "See you at the office tomorrow.",
            "See you at the office tomorrow period?" to "See you at the office tomorrow.",
            "See you at the office tomorrow period!" to "See you at the office tomorrow.",
            "See you at the office tomorrow period new line." to "See you at the office tomorrow.",
            "See you at the office tomorrow full stop new line." to "See you at the office tomorrow.",
            "See you tomorrow? Period." to "See you tomorrow.",
            "See you tomorrow! Full stop." to "See you tomorrow.",
            "See you tomorrow period. Period." to "See you tomorrow.",
            "See you tomorrow full stop. Full stop." to "See you tomorrow.",
            "He said \"see you.\" period." to "He said \"see you\".",
            "(See you tomorrow.) Period." to "(See you tomorrow).",
            "He said \"see you\"? Period." to "He said \"see you\".",
            "He said \"see you...\" period." to "He said \"see you...\".",
        ).forEach { (input, expected) ->
            val result = PolishPipeline.run(
                input,
                options = CleanupOptions(spokenPunctuation = true, casualMode = true),
                language = en,
            ) { cleaned -> cleaned }
            assertEquals("for input: $input", expected, result.text)
        }

        // A final recognizer dot in a second clause is not a spoken period, so Casual mode drops it.
        val secondClauseDot = PolishPipeline.run(
            "See you period ok.",
            options = CleanupOptions(spokenPunctuation = true, casualMode = true),
            language = en,
        ) { cleaned -> cleaned }
        assertEquals("See you. Ok", secondClauseDot.text)

        val twoSentencesAfterLine = PolishPipeline.run(
            "See you period new line okay.",
            options = CleanupOptions(spokenPunctuation = true, casualMode = true),
            language = en,
        ) { cleaned -> cleaned }
        assertEquals("See you.\nOkay", twoSentencesAfterLine.text)

        val twoSentencesAfterParagraph = PolishPipeline.run(
            "See you period new paragraph okay.",
            options = CleanupOptions(spokenPunctuation = true, casualMode = true),
            language = en,
        ) { cleaned -> cleaned }
        assertEquals("See you.\n\nOkay", twoSentencesAfterParagraph.text)

        val recognizerDotNotSpoken = PolishPipeline.run(
            "See you at the office tomorrow.",
            options = CleanupOptions(spokenPunctuation = true, casualMode = true),
            language = en,
        ) { cleaned -> cleaned }
        assertEquals("See you at the office tomorrow", recognizerDotNotSpoken.text)
    }

    @Test
    fun polishPipelinePreservesEmojiAfterCasualModeStripsFinalDotOnAcceptedTake() {
        val en = CleanupLanguage.Known("en")
        val emojiRes = PolishPipeline.run(
            "We shipped the release today thumbs up emoji and happy",
            options = CleanupOptions(spokenEmoji = true, casualMode = true),
            language = en,
            restoreLocalEmoji = true,
        ) { "We shipped the release today and happy." }
        assertEquals(PipelineOutcome.MODEL_ACCEPTED, emojiRes.outcome)
        assertEquals("We shipped the release today 👍 and happy", emojiRes.text)
    }

    @Test
    fun casualModeRunsOnceAfterCustomWordsAndOnFailOpenExits() {
        val en = LanguageDetector { DetectedLanguage("en", 0.99f) }
        val options = CleanupOptions(casualMode = true)
        val phdAlias = listOf(CustomTerm(spelling = "Ph.D", aliases = listOf("21st")))
        assertEquals(
            "Ph.D.",
            recoverDeterministicText("twenty first.", options, phdAlias, en),
        )
        assertEquals(
            "We settled it on the Ph.D.",
            recoverDeterministicText("We settled it on the twenty first.", options, phdAlias, en),
        )

        val deadlineAlias = listOf(CustomTerm(spelling = "deadline", aliases = listOf("period")))
        assertEquals(
            "I reviewed the grace deadline",
            recoverDeterministicText(
                "I reviewed the grace period.",
                CleanupOptions(spokenPunctuation = true, casualMode = true),
                deadlineAlias,
                en,
            ),
        )

        val corpAlias = listOf(CustomTerm(spelling = "Acme Inc.", aliases = listOf("21st")))
        assertEquals(
            "Acme Inc",
            recoverDeterministicText("twenty first", options, corpAlias, en),
        )

        val failOpenRestore = deterministicText(
            raw = "Sounds good.",
            restoreRaw = { null },
            clean = { error("must not run") },
            restoreCleaned = { error("must not run") },
            finalizeCasual = { final, cleanupInput -> CasualEnding.applyFailOpen(final, cleanupInput, options, en) },
        )
        assertEquals("Sounds good", failOpenRestore)

        val failOpenClean = deterministicText(
            raw = "Sounds good.",
            restoreRaw = { it },
            clean = { null },
            restoreCleaned = { error("must not run") },
            finalizeCasual = { final, cleanupInput -> CasualEnding.applyFailOpen(final, cleanupInput, options, en) },
        )
        assertEquals("Sounds good", failOpenClean)

        val fallback = PolishFallback.deterministicOrWords(
            rawText = "See you soon.",
            options = options,
            detector = en,
            warn = {},
            clean = { _, _, _ -> throw IllegalStateException("detector failed") },
        )
        assertEquals("See you soon", fallback)
    }

    private suspend fun <T> withClosedStore(file: File, block: suspend (AppPreferences) -> T): T {
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(AppPreferences(store))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun casualModePreferenceDefaultsOffAndPersistsAcrossClosedStores() = runTest {
        val file = tempFolder.newFile("casual_mode.preferences_pb")
        withClosedStore(file) { prefs ->
            assertFalse(prefs.state.first().casualModeEnabled)
            prefs.setCasualModeEnabled(true)
        }
        val reloaded = withClosedStore(file) { it.state.first() }
        assertTrue(reloaded.casualModeEnabled)
        assertTrue(reloaded.cleanupOptions().casualMode)
    }
}
