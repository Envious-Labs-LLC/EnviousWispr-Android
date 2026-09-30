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
    fun `the measured 73 character count to twenty fits the cap with room to spare (385)`() {
        // Phone receipt, build 236, 2026-09-30: 73 characters in, 73 tokens generated, stop=eos.
        val measuredTokens = 73
        val oldCap = (((73 / 3.5).let { Math.round(it) }.coerceAtLeast(1) * 1.3 + 32).let { Math.round(it) }).toInt().coerceIn(64, 512)

        assertTrue("the old cap $oldCap would have cut a $measuredTokens token answer", oldCap < measuredTokens)
        assertTrue(S1PromptBuilder.maxOutputTokens("x".repeat(73)) >= measuredTokens * 2)
    }

    @Test
    fun `up to 800 chars the cap stays at or above one token per character`() {
        for (length in listOf(1, 50, 73, 141, 400, 800)) {
            val budget = S1PromptBuilder.maxOutputTokens("x".repeat(length))
            assertTrue("length=$length budget=$budget", budget >= length)
        }
    }

    @Test
    fun `an empty take still gets the floor and a mid-size one reaches the ceiling`() {
        assertEquals(128, S1PromptBuilder.maxOutputTokens(""))
        assertEquals(S1PromptBuilder.MAX_OUTPUT_TOKENS, S1PromptBuilder.maxOutputTokens("x".repeat(800)))
    }

    @Test
    fun `the prompt plus the cap never exceeds the context while the prompt itself fits`() {
        // Estimated prompt = 160 tokens of overhead plus one token per character (the measured worst case).
        for (length in listOf(0, 73, 500, 800, 900, 1200, 1500, 1800)) {
            val prompt = 160 + length
            val cap = S1PromptBuilder.maxOutputTokens("x".repeat(length))
            assertTrue("length=$length prompt=$prompt cap=$cap", prompt + cap <= S1Config.CONTEXT_SIZE)
        }
    }

    @Test
    fun `a take whose prompt alone overflows the context gets the small cramped cap`() {
        assertEquals(64, S1PromptBuilder.maxOutputTokens("x".repeat(100_000)))
    }

    @Test
    fun `the ceiling leaves room in the model context for the prompt`() {
        assertTrue(S1PromptBuilder.MAX_OUTPUT_TOKENS <= S1Config.CONTEXT_SIZE / 2)
    }

}
