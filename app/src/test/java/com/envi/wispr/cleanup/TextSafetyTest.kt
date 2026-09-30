package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails an essay replaces a "yeah", half a dictation vanishes, or a question
 * comes back as an answer; every refusal keeps the deterministic text. The rules are the Mac's
 * `validatePolishOutput` plus this app's own four.
 */
class TextSafetyTest {
    private val paragraph = "so um we should probably move the launch to next week because the build is not stable yet and marketing needs more time"

    @Test fun cleanOutputPassesAndNamesNoRule() {
        assertNull(TextSafety.refusal(paragraph, "We should probably move the launch to next week because the build is not stable yet and marketing needs more time."))
        assertTrue(TextSafety.isSafe("yeah okay", "Yeah, okay."))
    }

    @Test fun theFourOriginalRulesStillRefuse() {
        assertEquals("blank output", TextSafety.refusal("hello there", "   "))
        assertEquals("control characters", TextSafety.refusal("hello there", "hello\u0001there"))
        assertTrue(TextSafety.refusal("hi", "x".repeat(201))!!.startsWith("expansion"))
        assertTrue(TextSafety.refusal("a".repeat(40), "abcd")!!.startsWith("contraction"))
    }

    @Test fun expansionIsTheMacsMaxOfThreeTimesOrTwoHundred() {
        assertNull(TextSafety.refusal("hi", "x".repeat(200)))
        assertTrue(TextSafety.refusal("hi", "x".repeat(201))!!.startsWith("expansion"))
        val hundred = "w".repeat(100)
        assertNull(TextSafety.refusal(hundred, "x".repeat(300)))
        assertTrue(TextSafety.refusal(hundred, "x".repeat(301))!!.startsWith("expansion"))
    }

    @Test fun aWordCountDropBelowFortyPercentRefusesOnlyFromTenWords() {
        val ten = "one two three four five six seven eight nine ten"
        assertTrue(TextSafety.refusal(ten, "one two three")!!.startsWith("content drop"))
        assertNull(TextSafety.refusal(ten, "one two three four"))
        val nine = "one two three four five six seven eight nine"
        assertNull(TextSafety.refusal(nine, "one two three four five"))
    }

    @Test fun aQuestionTurnedIntoAnAnswerIsRefused() {
        assertEquals("question turned into an answer", TextSafety.refusal("should we ship friday", "We will ship Friday."))
        assertNull(TextSafety.refusal("should we ship friday", "Should we ship Friday?"))
        assertEquals("question turned into an answer", TextSafety.refusal("um so how many people are coming", "Many people are coming."))
        assertNull(TextSafety.refusal("how we handle this is up to the team", "How we handle this is up to the team."))
        assertEquals("question turned into an answer", TextSafety.refusal("i was wondering if you could send it", "You could send it."))
    }

    private val countToTwenty = "1, 2, 3, 4, 5, 6, 7, 8, 9, 10, eleven, twelve, thirteen, fourteen, fifteen, sixteen, seventeen, eighteen, nineteen, twenty"

    @Test fun aCountCutOffAfterSeventeenIsRefusedByName_385() {
        val cutOff = "1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17."
        assertEquals("number drop 3 numbers", TextSafety.refusal(countToTwenty, cutOff))
        assertFalse(TextSafety.isSafe(countToTwenty, cutOff))
    }

    @Test fun aFullCountPassesWhetherTheModelWritesFiguresOrWords_385() {
        val figures = "1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20."
        assertNull(TextSafety.refusal(countToTwenty, figures))
        assertNull(TextSafety.refusal(countToTwenty, countToTwenty.replace("1, ", "one, ")))
    }

    @Test fun aCountOfThreeOrMoreLosingAnItemInTheMiddleIsRefused_385() {
        val input = "the codes are 12, 14, 16, 18 and 20 for the lockers"
        assertNull(TextSafety.refusal(input, "The codes are 12, 14, 16, 18 and 20 for the lockers."))
        assertEquals("number drop 1 numbers", TextSafety.refusal(input, "The codes are 12, 14, 16 and 20 for the lockers."))
    }

    @Test fun aLoneNumberIsNeverHeldBecauseAModelMayWordItAnotherWay_385() {
        // Codex rounds 3 and 4: each is a correct answer; only a run of three or more is enforced.
        assertNull(TextSafety.refusal("repeat this 1 time after lunch", "Repeat this once after lunch."))
        assertNull(TextSafety.refusal("we should keep both of the 2 options", "We should keep both options."))
        assertNull(TextSafety.refusal("this is attempt number 1 today", "This is the first attempt today."))
        assertNull(TextSafety.refusal("this is my 2nd attempt at it", "This is my second attempt at it."))
        assertNull(TextSafety.refusal("1. buy milk 2. buy eggs", "Buy milk and eggs."))
        assertNull(TextSafety.refusal("the invoice total is 1,200 dollars for 3 seats and we meet at 10 tomorrow morning", "The invoice is 1200 dollars for seats and we meet tomorrow morning."))
    }

    @Test fun twoItemsAreNotACountAndAThreeItemRunIs_385() {
        assertNull(TextSafety.refusal("we need 4, 5 chairs for the event today", "We need chairs for the event today."))
        assertEquals("number drop 3 numbers", TextSafety.refusal("we need 4, 5, 6 chairs for the event today", "We need chairs for the event today."))
    }

    @Test fun compoundsTheModelRewritesAsOneFigureAreNotDrops_385() {
        assertNull(TextSafety.refusal("we shipped twenty one builds and two hundred and fifty tests last quarter", "We shipped 21 builds and 250 tests last quarter."))
        assertNull(TextSafety.refusal("call me at five five five one two one two after lunch today please", "Call me at 555-1212 after lunch today please."))
    }

    @Test fun aFullySpelledCountCutOffAtSeventeenIsRefusedAndAFullOneIsNot_385() {
        val spelled = "one, two, three, four, five, six, seven, eight, nine, ten, eleven, twelve, thirteen, fourteen, fifteen, sixteen, seventeen, eighteen, nineteen, twenty"
        assertEquals("number drop 3 numbers", TextSafety.refusal(spelled, "One, two, three, four, five, six, seven, eight, nine, ten, eleven, twelve, thirteen, fourteen, fifteen, sixteen, seventeen."))
        assertNull(TextSafety.refusal(spelled, "$spelled."))
        assertNull(TextSafety.refusal(spelled, "1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20."))
    }

    @Test fun reformattingANumberTheRuleDoesNotCheckIsNeverARefusal_385() {
        // Codex round 3: each of these is a correct polish answer and must not fall back to the raw text.
        assertNull(TextSafety.refusal("the dose is 3.50 milligrams", "The dose is 3.5 milligrams."))
        assertNull(TextSafety.refusal("we need 100 chairs for the event", "We need a hundred chairs for the event."))
        assertNull(TextSafety.refusal("call 2125551212 after lunch today", "Call (212) 555-1212 after lunch today."))
        assertNull(TextSafety.refusal("we meet at 3:30 in the main room", "We meet at half past three in the main room."))
        assertNull(TextSafety.refusal("the plan starts in 2024 and runs a while", "The plan starts in twenty twenty-four and runs a while."))
        assertNull(TextSafety.refusal("the price is \$5 and up to 50% off", "The price is 5 dollars and up to fifty percent off."))
    }

    @Test fun aPlainFigureWrittenOutAsWordsOrAsACompoundIsKept_385() {
        assertNull(TextSafety.refusal("we have 21 apples for everyone here", "We have twenty-one apples for everyone here."))
        assertNull(TextSafety.refusal("we have 21 apples for everyone here", "We have twenty one apples for everyone here."))
        assertNull(TextSafety.refusal("we have 3 apples for everyone here", "We have three apples for everyone here."))
        assertNull(TextSafety.refusal("one of them has 1 apple for me", "One of them has one apple for me."))
    }

    @Test fun theSpellingRestoreOfCustomWordsSkipsTheNumberRule_385() {
        val input = "we have 18, 19, 20 apples for everyone here today"
        assertFalse(TextSafety.isSafe(input, "We have apples for everyone here today."))
        assertTrue(TextSafety.isSafe(input, "We have apples for everyone here today.", checkNumbers = false))
    }

    @Test fun theWordOneIsNeverCountedBecauseItIsAlsoAPronoun_385() {
        assertNull(TextSafety.refusal("that one thing we talked about needs a lot more work before friday", "That thing we talked about needs a lot more work before Friday."))
    }

    @Test fun theQuestionDetectorIsConservative() {
        listOf("should we ship", "can you send it", "is there a room", "how do we start", "what is the plan?", "um well do you know if it works", "was the meeting moved", "had they already left", "\"should we ship\"", "'should we ship'", "i'm wondering if it works").forEach {
            assertTrue(it, TextSafety.looksLikeQuestion(it))
        }
        listOf("we should ship", "the plan is simple", "how we handle it is up to us", "wondering about the weather", "").forEach {
            assertFalse(it, TextSafety.looksLikeQuestion(it))
        }
    }
}
