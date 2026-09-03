package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCleanupTest {
    /**
     * Product Outcome. When this fails the user watches a word they actually said disappear from the text
     * that gets inserted. Added with #36, which made 24 more languages reachable and therefore made the
     * collision reachable; `err` was already broken in English before that. Owner of the wider sweep: #107.
     */
    @Test fun realWordsThatLookLikeFillersSurvive() {
        fun clean(input: String) = DeterministicCleanup.apply(input, CleanupOptions()).text

        // English, and this one was live before any language work: `err` is a verb.
        assertEquals("To err is human", clean("To err is human"))
        // German preposition, Portuguese article, Croatian noun. All inside Parakeet v3's 25 languages.
        assertEquals("Wir treffen uns um drei", clean("Wir treffen uns um drei"))
        assertEquals("Eu quero um cafe", clean("Eu quero um cafe"))
        assertEquals("Um je bistar", clean("Um je bistar"))

        // The six that stayed are not words in those languages, so filler removal still does its job.
        assertEquals("hold on", clean("Uh, hold on"))
        assertEquals("let me see", clean("Hmm, let me see"))
    }

    @Test fun removesFillersAndExplicitCommands() {
        val result = DeterministicCleanup.apply(
            "uh hello comma thumbs up emoji",
            CleanupOptions(spokenPunctuation = true),
        )
        assertEquals("hello, 👍", result.text)
        assertTrue(result.changed)
        assertTrue(!result.recovered)
    }

    @Test fun disabledOptionsLeaveCommandsAlone() {
        val result = DeterministicCleanup.apply("uh hello comma", CleanupOptions(false, false, false))
        assertEquals("uh hello comma", result.text)
    }

    @Test fun polishPipelineHonorsEachCleanupToggleIndependently() {
        val raw = "uh send thumbs up emoji comma literally"

        assertEquals(
            "uh send 👍 comma literally",
            PolishPipeline.run(
                raw,
                CleanupOptions(removeFillers = false, spokenEmoji = true, spokenPunctuation = false),
            ).text,
        )
        assertEquals(
            "send thumbs up emoji comma literally",
            PolishPipeline.run(
                raw,
                CleanupOptions(removeFillers = true, spokenEmoji = false, spokenPunctuation = false),
            ).text,
        )
        assertEquals(
            "uh send thumbs up emoji, literally",
            PolishPipeline.run(
                raw,
                CleanupOptions(removeFillers = false, spokenEmoji = false, spokenPunctuation = true),
            ).text,
        )
        assertEquals(
            raw,
            PolishPipeline.run(
                raw,
                CleanupOptions(removeFillers = false, spokenEmoji = false, spokenPunctuation = false),
            ).text,
        )
    }

    @Test fun rejectsMeaningDroppingOutput() {
        val result = DeterministicCleanup.apply("uh ".repeat(30))
        assertTrue(result.recovered)
        assertEquals("uh ".repeat(30).trim(), result.text)
    }

    @Test fun modelReceivesCleanedText() {
        var seen = ""
        // Four words or more, so the too-short bypass (#2) lets the model see the cleaned text.
        val result = PolishPipeline.run(
            "uh envious whisper comma works well today",
            CleanupOptions(spokenPunctuation = true),
        ) { cleaned ->
            seen = cleaned
            "enviouswispr works well today"
        }
        assertEquals("enviouswispr works well today", result.text)
        assertEquals("envious whisper, works well today", seen)
        assertTrue(result.usedModel)
    }

    @Test fun unsafeAndBlankModelOutputRecoverToCleanedText() {
        val unsafe = PolishPipeline.run("hello world from the model") { "x".repeat(300) }
        assertEquals("hello world from the model", unsafe.text)
        assertTrue(unsafe.recovered)
        val blank = PolishPipeline.run("uh hello there my friend") { " " }
        assertEquals("hello there my friend", blank.text)
        assertTrue(blank.recovered)
    }

    @Test fun recoveredCleanupSkipsModel() {
        var invoked = false
        val result = PolishPipeline.run("uh ".repeat(30) + "envious whisper") {
            invoked = true
            "model output"
        }
        assertTrue(!invoked)
        assertEquals("uh ".repeat(30).trim() + " envious whisper", result.text)
        assertTrue(result.recovered)
        assertTrue(!result.usedModel)
    }

    @Test fun formatsPhoneCodesAndYears() {
        assertEquals("my number is 055-512-3456", clean("my number is o five five five one two three four five six"))
        assertEquals("the code is 203", clean("the code is two zero three"))
        assertEquals("i was born in 1987", clean("i was born in nineteen eighty seven"))
        assertEquals("the release is 2026", clean("the release is two thousand twenty six"))
    }

    @Test fun formatsCurrencyPercentDecimalsAndCardinals() {
        assertEquals("we raised $80 million last year", clean("we raised eighty million dollars last year"))
        assertEquals("he paid $15.25", clean("he paid fifteen dollars and twenty five cents"))
        assertEquals("20% off", clean("twenty percent off"))
        assertEquals("the measurement is 3.5", clean("the measurement is three point five"))
        assertEquals("945 people came", clean("nine hundred forty five people came"))
        assertEquals("i have two cats", clean("i have two cats"))
    }

    @Test fun formatsDatesTimesOrdinalsAndRanges() {
        assertEquals("meet June 2, 2026 at 3:05 PM", clean("meet june second twenty twenty six at three oh five p m"))
        assertEquals("the 20th century", clean("the twentieth century"))
        assertEquals("between 3-7", clean("between three and seven"))
        assertEquals("use 2 by 4 by 6 boards", clean("use two by four by six boards"))
        assertEquals("the date is 4/6/2021", clean("the date is four slash six slash two thousand twenty one"))
    }

    @Test fun formatsEmailUrlsAndTheirPaths() {
        assertEquals("email alice@example.com", clean("email alice at example dot com"))
        assertEquals("open example.com/docs/start", clean("open example dot com slash docs slash start"))
        assertEquals("open docs.xyz/page", clean("open docs.xyz slash page"))
    }

    @Test fun preservesAmbiguousNaturalSpeech() {
        assertEquals("there were one twenty people there", clean("there were one twenty people there"))
        assertEquals("One thing that i want", clean("One thing that i want"))
        assertEquals("we ate at Five Guys", clean("we ate at Five Guys"))
        assertEquals("do it one by one", clean("do it one by one"))
        assertEquals("wait a quarter to five", clean("wait a quarter to five"))
    }

    @Test fun spokenPunctuationIsOptIn() {
        assertEquals("the grace period expires", DeterministicCleanup.apply("the grace period expires").text)
        assertEquals(
            "hello,",
            DeterministicCleanup.apply(
                "hello comma",
                CleanupOptions(removeFillers = false, spokenEmoji = false, spokenPunctuation = true),
            ).text,
        )
    }

    @Test fun emojiRequiresAnExplicitNonDiscussionTrigger() {
        assertEquals("fire weather", DeterministicCleanup.apply("fire weather").text)
        assertEquals("send a 🔥", DeterministicCleanup.apply("send a fire emoji").text)
        assertEquals("the fire emoji feature", DeterministicCleanup.apply("the fire emoji feature").text)
        assertEquals(
            "the fire emoji keyboard is missing",
            DeterministicCleanup.apply("the fire emoji keyboard is missing").text,
        )
        assertEquals(
            "the heart emoji character",
            DeterministicCleanup.apply("the heart emoji character").text,
        )
    }

    @Test fun preservesIntentionalRepetition() {
        assertEquals("this is very very important", DeterministicCleanup.apply("this is very very important").text)
    }

    @Test fun protectsDurationNounsOwnedByMac() {
        assertEquals("30 second introduction", clean("thirty second introduction"))
        assertEquals("30 second lead", clean("thirty second lead"))
    }

    @Test fun cleanupIsStableAndFailsBackToOriginalOnMeaningLoss() {
        val once = clean("email alice at example dot com comma call two zero three nine five four eight eight seven nine")
        assertEquals(once, clean(once))
        val lossy = DeterministicCleanup.apply("uh ".repeat(30))
        assertTrue(lossy.recovered)
        assertEquals("uh ".repeat(30).trim(), lossy.text)
    }

    private fun clean(input: String): String = DeterministicCleanup.apply(
        input,
        CleanupOptions(spokenPunctuation = true),
    ).text
}
