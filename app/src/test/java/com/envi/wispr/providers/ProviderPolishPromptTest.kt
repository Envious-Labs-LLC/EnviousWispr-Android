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

    @Test fun emptyOrNullCustomVocabularyAppendsNothing() {
        val baseline = ProviderPolishPrompt.systemInstruction(eleven)
        assertEquals(baseline, ProviderPolishPrompt.systemInstruction(eleven, null))
        assertEquals(baseline, ProviderPolishPrompt.systemInstruction(eleven, ""))
        assertEquals(baseline, ProviderPolishPrompt.systemInstruction(eleven, "   "))
        assertEquals(null, CustomVocabularyFormatter.render(emptyList()))
    }

    @Test fun customVocabularyAppendsAfterShortGuardWithMacFramingAndLeavesFixedTextUntouched() {
        val vocab = CustomVocabularyFormatter.render(
            listOf(
                com.envi.wispr.vocabulary.CustomTerm("Saurabh", aliases = listOf("Sore off", "Sorab"), priority = 1),
                com.envi.wispr.vocabulary.CustomTerm("EnviousWispr", priority = 0),
            ),
        )!!
        val system = ProviderPolishPrompt.systemInstruction(seven, vocab)
        assertTrue(system.contains(ProviderPolishPrompt.CLOUD_FIXED_PROMPT_V7))
        assertTrue(
            system.contains(
                "\n\nIMPORTANT: Very short input. Return as-is with only minimal punctuation fixes." +
                    "\n\nThe following are preferred spellings for words the speaker used. " +
                    "Apply them as spelling corrections. This is the one exception to leaving the wording unchanged.\n" +
                    CustomVocabularyFormatter.FULL_HEADER + "\n" +
                    "- Saurabh (may be misheard as: Sore off, Sorab)\n" +
                    "- EnviousWispr",
            ),
        )

        // Manual terms (priority 0) must never be crowded out of the 50-word cloud cap by
        // lower-priority imported contacts (priority -10).
        val importedContacts = (1..55).map { i ->
            com.envi.wispr.vocabulary.CustomTerm("Contact%02d".format(i), priority = -10, imported = true)
        }
        val rendered = CustomVocabularyFormatter.render(
            importedContacts + com.envi.wispr.vocabulary.CustomTerm("ManualImportantTerm", priority = 0),
        )!!
        assertTrue(rendered.contains("- ManualImportantTerm"))
    }

    @Test fun customVocabularyFormatterEnforcesBoundsAndSanitizesPromptInjectionChars() {
        val sanitized = CustomVocabularyFormatter.render(
            listOf(
                com.envi.wispr.vocabulary.CustomTerm("  <Foo>\n`Bar`\u2028Baz  ", aliases = listOf("baz\r\n<qux>\u2029quux")),
                com.envi.wispr.vocabulary.CustomTerm("   "),
            ),
        )!!
        assertEquals(
            CustomVocabularyFormatter.FULL_HEADER + "\n- Foo 'Bar' Baz (may be misheard as: baz qux quux)",
            sanitized,
        )

        val sixty = (1..60).map { i -> com.envi.wispr.vocabulary.CustomTerm("Term%02d".format(i)) }
        val cappedByWords = CustomVocabularyFormatter.render(sixty)!!
        val renderedLines = cappedByWords.lines().drop(1)
        assertEquals(CustomVocabularyFormatter.MAX_WORDS, renderedLines.size)
        assertTrue(cappedByWords.length <= CustomVocabularyFormatter.MAX_CHARS)

        val huge = (1..50).map { i -> com.envi.wispr.vocabulary.CustomTerm("Word${i}_" + "a".repeat(120)) }
        val cappedByChars = CustomVocabularyFormatter.render(huge)!!
        assertTrue(cappedByChars.length <= CustomVocabularyFormatter.MAX_CHARS)
        assertTrue(cappedByChars.lines().size in 2..50)

        val singleOversize = listOf(com.envi.wispr.vocabulary.CustomTerm("x".repeat(CustomVocabularyFormatter.MAX_CHARS + 50)))
        assertEquals(null, CustomVocabularyFormatter.render(singleOversize))
    }

    @Test fun cloudAdaptersIncludeCustomVocabularyAndPolicyRedactsToString() {
        val vocab = CustomVocabularyFormatter.render(listOf(com.envi.wispr.vocabulary.CustomTerm("Qualtrics")))!!
        val openAi = OpenAiAdapter.polishPlan(
            ProviderPolishRequest(Provider.OPENAI, "gpt-test", eleven, "sk-test", customVocabulary = vocab),
            null,
        )
        val gemini = GeminiAdapter.polishPlan(
            ProviderPolishRequest(Provider.GEMINI, "gemini-test", eleven, "g-test", customVocabulary = vocab),
            null,
        )
        val claude = ClaudeAdapter.polishPlan(
            ProviderPolishRequest(Provider.CLAUDE, "claude-test", eleven, "c-test", customVocabulary = vocab),
            null,
        )
        val selfHosted = SelfHostedAdapter.polishPlan(
            ProviderPolishRequest(
                Provider.SELF_HOSTED_POLISH,
                "llama3.2",
                eleven,
                endpoint = "http://localhost:11434",
                selfHostedProtocol = SelfHostedProtocol.OLLAMA,
                customVocabulary = vocab,
            ),
            null,
        )!!
        assertTrue(openAi.body!!.contains("Qualtrics"))
        assertTrue(gemini.body!!.contains("Qualtrics"))
        assertTrue(claude.body!!.contains("Qualtrics"))
        assertTrue(selfHosted.body!!.contains("Qualtrics"))

        val policy = com.envi.wispr.polish.PolishPolicy.Cloud(
            Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE, customVocabulary = vocab,
        )
        assertFalse(policy.toString().contains("Qualtrics"))
        assertTrue(policy.toString().contains("<redacted>"))
    }

    @Test fun sessionPreferencesFreezeAttachesCustomVocabularyToCloudPolicyOnly() {
        val source = com.envi.wispr.ui.SessionPreferencesSource(
            preferenceStates = kotlinx.coroutines.flow.emptyFlow(),
            terms = kotlinx.coroutines.flow.emptyFlow(),
            migrateLegacyTerms = {},
            log = object : com.envi.wispr.ui.SessionLog {
                override fun log(message: String) = Unit
                override fun warn(message: String) = Unit
                override fun error(message: String, throwable: Throwable?) = Unit
                override fun mark(event: String) = Unit
                override fun pipelineSummary(): String = ""
            },
        )
        val userTerms = listOf(com.envi.wispr.vocabulary.CustomTerm("Saurabh", aliases = listOf("Sorab")))
        val start = com.envi.wispr.ui.PreferenceStart(
            settings = com.envi.wispr.ui.SettingsSnapshot(read = com.envi.wispr.ui.PreferenceRead.Fresh),
            terms = com.envi.wispr.ui.TermsSnapshot(
                read = com.envi.wispr.ui.PreferenceRead.Fresh,
                structuredTerms = userTerms,
                userSpellings = userTerms.map { it.spelling },
                userTerms = userTerms,
            ),
        )
        val matcher = com.envi.wispr.vocabulary.StructuredTermRestorer.compile(userTerms)
        val cloud = com.envi.wispr.polish.PolishPolicy.Cloud(
            Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE,
        )
        val frozenCloud = source.freeze(start, matcher, cloud).policy as com.envi.wispr.polish.PolishPolicy.Cloud
        assertTrue(frozenCloud.customVocabulary!!.contains("- Saurabh (may be misheard as: Sorab)"))

        val frozenOff = source.freeze(start, matcher, com.envi.wispr.polish.PolishPolicy.Off).policy
        assertEquals(com.envi.wispr.polish.PolishPolicy.Off, frozenOff)
    }
}
