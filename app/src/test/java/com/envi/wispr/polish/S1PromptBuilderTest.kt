package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

}
