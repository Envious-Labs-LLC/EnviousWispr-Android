package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails a Spanish dictation can come back in English, a five-word reply grows
 * a sentence, or an echoed label reaches the user's editor.
 */
class ProviderPolishPromptTest {
    private val seven = "please send the deck to finance today"
    private val eleven = "please send the deck to finance today and copy the team"

    @Test fun theSystemInstructionOpensWithTheLanguageRuleThenTheFixedText() {
        val system = ProviderPolishPrompt.systemInstruction(eleven)
        assertTrue(system.startsWith("Keep the cleaned text in the same language(s) and script(s) as the transcript. Never translate it, and preserve any code-switching between languages.\n\n"))
        assertTrue(system.contains(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7))
        assertTrue(system.endsWith("You are capturing their writing, not talking with them."))
    }

    @Test fun theShortInputGuardAppendsAtTenWordsAndNotAtEleven() {
        val guard = "\n\nIMPORTANT: Very short input. Return as-is with only minimal punctuation fixes."
        assertTrue(ProviderPolishPrompt.systemInstruction(seven).endsWith(guard))
        assertTrue(ProviderPolishPrompt.systemInstruction("one two three four five six seven eight nine ten").endsWith(guard))
        assertEquals(11, ProviderPolishPrompt.wordCount(eleven))
        assertFalse(ProviderPolishPrompt.systemInstruction(eleven).endsWith(guard))
        assertFalse(ProviderPolishPrompt.systemInstruction("one two three four five six seven eight nine ten eleven").endsWith(guard))
        assertEquals(10, ProviderPolishPrompt.wordCount("  one two three four five six seven eight nine ten  "))
    }

    @Test fun theUserMessageIsTheLabelledTranscriptWithNoTagWrapper() {
        assertEquals("Transcript to clean:\n\num so the meeting is at three", ProviderPolishPrompt.userMessage("um so the meeting is at three"))
    }

    @Test fun anEchoedLabelIsNotATranscript() {
        assertFalse(ProviderPolishPrompt.isTranscriptOnly("Transcript to clean:\n\nHello there."))
        assertFalse(ProviderPolishPrompt.isTranscriptOnly("transcript to clean: hello"))
        assertTrue(ProviderPolishPrompt.isTranscriptOnly("Hello there, the transcript to clean is ready."))
        assertTrue(ProviderPolishPrompt.isTranscriptOnly("Please upload it."))
    }

    @Test fun theFixedTextIsTheMacsVerbatim() {
        // The SHA-256 of the Mac's `CloudFixedPromptBuilder.cloudFixedSystemPrompt` (v7, 2026-08-16), computed from
        // the Swift source on 2026-09-02; any character of drift, deliberate or not, fails here.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7.toByteArray(Charsets.UTF_8))
        assertEquals("1382f15841b3e1118f10f0c4603dcb5269551da9ab8cbe7845266ea703860cef", digest.joinToString("") { "%02x".format(it) })
        assertTrue(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7.startsWith("You are the writing assistant inside a dictation app."))
        assertTrue(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7.contains("Spoken: \"Priya, can you send the deck to legal. Sorry, to finance.\"\nCleaned: \"Priya, can you send the deck to finance.\""))
        assertTrue(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7.endsWith("You are capturing their writing, not talking with them."))
        assertFalse(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7.contains("Return only the polished transcript"))
    }
}
