package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class S1PromptBuilderTest {
    @Test
    fun `prompt keeps the exact trained control line when custom terms exist`() {
        val prompt = S1PromptBuilder.buildUserPrompt(
            "envious whisper is useful",
        )

        assertEquals(
            "[Styling: semi-formal] [Structure: lists] [Context: general]\n" +
                "envious whisper is useful",
            prompt
        )
        assertFalse(prompt.contains("EnviousWispr"))
        assertFalse(prompt.contains("Saurabh"))
    }

}
