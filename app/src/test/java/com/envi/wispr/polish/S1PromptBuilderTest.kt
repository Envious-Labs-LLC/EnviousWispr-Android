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
    fun `a spoken count to twenty gets more room than the 84 tokens that cut it off (385)`() {
        val count = (1..20).joinToString(" ") { "number$it" }.take(141)

        assertEquals(141, count.length)
        assertTrue(S1PromptBuilder.maxOutputTokens(count) > 84)
    }

    @Test
    fun `the cap never binds below two chars per token for any take length`() {
        for (length in listOf(1, 50, 141, 400, 1000, 1400)) {
            val budget = S1PromptBuilder.maxOutputTokens("x".repeat(length))
            assertTrue("length=$length budget=$budget", budget >= length / 2)
        }
    }

    @Test
    fun `an empty take still gets the floor and a huge one is bounded`() {
        assertEquals(128, S1PromptBuilder.maxOutputTokens(""))
        assertEquals(2048, S1PromptBuilder.maxOutputTokens("x".repeat(100_000)))
    }

}
