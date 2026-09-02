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

    @Test fun theQuestionDetectorIsConservative() {
        listOf("should we ship", "can you send it", "is there a room", "how do we start", "what is the plan?", "um well do you know if it works", "was the meeting moved", "had they already left", "\"should we ship\"", "'should we ship'", "i'm wondering if it works").forEach {
            assertTrue(it, TextSafety.looksLikeQuestion(it))
        }
        listOf("we should ship", "the plan is simple", "how we handle it is up to us", "wondering about the weather", "").forEach {
            assertFalse(it, TextSafety.looksLikeQuestion(it))
        }
    }
}
