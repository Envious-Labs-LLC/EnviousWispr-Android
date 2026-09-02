package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails a one-word dictation is sent to a model that can turn "yeah" into an
 * essay, or a refused output loses the name of the rule that refused it.
 */
class PolishPipelineTest {
    @Test fun threeWordsOrFewerNeverReachTheModel() {
        var called = 0
        val result = PolishPipeline.run("yeah okay sure") { called++; "An essay about agreement." }
        assertEquals(0, called)
        assertEquals(PipelineOutcome.TOO_SHORT, result.outcome)
        assertFalse(result.usedModel)
        assertTrue(result.text, result.text.lowercase().startsWith("yeah okay sure"))
    }

    @Test fun fourWordsReachTheModel() {
        var called = 0
        val result = PolishPipeline.run("yeah okay sure thing") { called++; "Yeah, okay, sure thing." }
        assertEquals(1, called)
        assertEquals(PipelineOutcome.MODEL_ACCEPTED, result.outcome)
    }

    @Test fun aScriptWithoutSpacesIsJudgedByCharacters() {
        assertTrue(PolishPipeline.tooShortForPolish("今日は暑い"))
        assertFalse(PolishPipeline.tooShortForPolish("今日はとても暑いので早く帰ります"))
        assertTrue(PolishPipeline.tooShortForPolish("สวัสดีครับ"))
        // Supplementary-plane Han (surrogate pairs) counts by code point, so a long sentence is not one token.
        assertFalse(PolishPipeline.tooShortForPolish("\uD840\uDC00".repeat(12)))
        assertTrue(PolishPipeline.tooShortForPolish("\uD840\uDC00".repeat(4)))
        assertFalse(PolishPipeline.tooShortForPolish("one two three four"))
        assertTrue(PolishPipeline.tooShortForPolish("one two three"))
    }

    @Test fun aRefusedOutputNamesItsRule() {
        val input = "should we ship on friday or wait another week for the fix"
        val result = PolishPipeline.run(input) { "We will ship on Friday." }
        assertEquals(PipelineOutcome.MODEL_REJECTED, result.outcome)
        assertEquals("question turned into an answer", result.refusal)
        assertFalse(result.usedModel)
        val accepted = PolishPipeline.run(input) { "Should we ship on Friday or wait another week for the fix?" }
        assertNull(accepted.refusal)
        assertTrue(accepted.usedModel)
    }
}
