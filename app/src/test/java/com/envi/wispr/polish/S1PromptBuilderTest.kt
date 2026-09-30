package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S1PromptBuilderTest {
    @Test
    fun `the default prompt is byte-identical to the line the app shipped before the pickers`() {
        val prompt = S1PromptBuilder.buildUserPrompt(
            "envious whisper is useful",
            S1ControlSettings.DEFAULT,
        )

        assertEquals(
            "[Styling: semi-formal] [Structure: lists] [Context: general]\n" +
                "envious whisper is useful",
            prompt
        )
        assertFalse(prompt.contains("EnviousWispr"))
        assertFalse(prompt.contains("Saurabh"))
    }

    @Test
    fun `the user's picks are the first line and the transcript is bare below it`() {
        val prompt = S1PromptBuilder.buildUserPrompt(
            "  hi marcus thanks for the draft  ",
            S1ControlSettings(S1Styling.CASUAL, S1Structure.PROSE, S1Context.EMAIL),
        )

        assertEquals(
            "[Styling: casual] [Structure: prose] [Context: email]\nhi marcus thanks for the draft",
            prompt,
        )
    }

    @Test
    fun `a 141-char take gets more than the 84 tokens the old estimate gave it (385)`() {
        val count = (1..20).joinToString(" ") { "number$it" }.take(141)

        assertEquals(141, count.length)
        assertTrue(S1PromptBuilder.maxOutputTokens(count) > 84)
    }

    @Test
    fun `up to 2048 chars the cap stays at or above two chars per token`() {
        for (length in listOf(1, 50, 141, 400, 1000, 1400, 2000, 2048)) {
            val budget = S1PromptBuilder.maxOutputTokens("x".repeat(length))
            assertTrue("length=$length budget=$budget", budget >= length / 2)
        }
    }

    @Test
    fun `an empty take still gets the floor and a huge one hits the ceiling`() {
        assertEquals(128, S1PromptBuilder.maxOutputTokens(""))
        assertEquals(
            S1PromptBuilder.MAX_OUTPUT_TOKENS,
            S1PromptBuilder.maxOutputTokens("x".repeat(100_000)),
        )
    }

    @Test
    fun `the ceiling leaves room in the model context for the prompt`() {
        assertTrue(S1PromptBuilder.MAX_OUTPUT_TOKENS <= S1Config.CONTEXT_SIZE / 2)
    }

}
