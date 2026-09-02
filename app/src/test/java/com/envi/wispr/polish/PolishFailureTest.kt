package com.envi.wispr.polish

import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, a user whose polish did not run is told the wrong thing, or nothing,
 * about why, on the completion surface and on the History card that shares this derivation.
 */
class PolishFailureTest {

    private val gemini = PolishContext.Cloud(Provider.GEMINI, ollama = false)
    private val openAi = PolishContext.Cloud(Provider.OPENAI, ollama = false)
    private val ollama = PolishContext.Cloud(Provider.SELF_HOSTED_POLISH, ollama = true)

    @Test fun everyReasonHasARowAndTheRowsAreThePlansTable() {
        // §7 of the plan, one row per reason at status 0; HTTP_ERROR is the status matrix below.
        val expected = mapOf(
            PolishReason.POLISHED to null,
            PolishReason.OFF to null,
            PolishReason.NO_SPEECH to null,
            PolishReason.EMPTY_AFTER_CLEANUP to null,
            PolishReason.CLEANUP_RECOVERED to null,
            PolishReason.CANCELLED to null,
            PolishReason.NO_API_KEY to PolishFailure.KEY_MISSING,
            PolishReason.CLOUD_NOT_CONFIGURED to PolishFailure.KEY_MISSING,
            PolishReason.HTTP_KEY_REJECTED to PolishFailure.KEY_REJECTED,
            PolishReason.HTTP_OUT_OF_CREDITS to PolishFailure.OUT_OF_CREDITS,
            PolishReason.HTTP_INPUT_TOO_LONG to PolishFailure.INPUT_TOO_LONG,
            PolishReason.HTTP_CONTENT_BLOCKED to PolishFailure.CONTENT_BLOCKED,
            PolishReason.HTTP_ERROR to PolishFailure.UNEXPECTED,
            PolishReason.INVALID_CONFIGURATION to PolishFailure.BAD_REQUEST,
            PolishReason.TOO_SHORT to null,
            PolishReason.MALFORMED_RESPONSE to PolishFailure.BAD_REQUEST,
            PolishReason.RESPONSE_TOO_LARGE to PolishFailure.BAD_REQUEST,
            PolishReason.REDIRECT_REJECTED to PolishFailure.BAD_REQUEST,
            PolishReason.NETWORK to PolishFailure.UNREACHABLE,
            PolishReason.TIMEOUT to PolishFailure.TIMED_OUT,
            PolishReason.LOCAL_TIMEOUT to PolishFailure.TIMED_OUT,
            PolishReason.WATCHDOG_TIMEOUT to PolishFailure.TIMED_OUT,
            PolishReason.OUTPUT_REJECTED to PolishFailure.OUTPUT_REJECTED,
            PolishReason.LOCAL_NOT_READY to PolishFailure.LOCAL_NOT_READY,
            PolishReason.LOCAL_FAILED to PolishFailure.UNEXPECTED,
            PolishReason.SERVICE_UNAVAILABLE to PolishFailure.UNEXPECTED,
            PolishReason.SERVICE_DIED to PolishFailure.UNEXPECTED,
            PolishReason.CALL_FAILED to PolishFailure.UNEXPECTED,
            PolishReason.UNEXPECTED to PolishFailure.UNEXPECTED,
        )
        assertEquals("every reason is pinned", PolishReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, failure) -> assertEquals(reason.name, failure, PolishFailure.from(reason, 0, openAi)) }
    }

    @Test fun theHttpStatusMatrixIsTheMacOsOne() {
        val rows = listOf(
            400 to PolishFailure.BAD_REQUEST,
            401 to PolishFailure.KEY_REJECTED,
            402 to PolishFailure.OUT_OF_CREDITS,
            403 to PolishFailure.ACCESS_DENIED,
            404 to PolishFailure.MODEL_UNAVAILABLE,
            413 to PolishFailure.INPUT_TOO_LONG,
            429 to PolishFailure.RATE_LIMITED,
            499 to PolishFailure.BAD_REQUEST,
            500 to PolishFailure.PROVIDER_ERROR,
            599 to PolishFailure.PROVIDER_ERROR,
            0 to PolishFailure.UNEXPECTED,
        )
        rows.forEach { (status, failure) -> assertEquals("status $status", failure, PolishFailure.from(PolishReason.HTTP_ERROR, status, openAi)) }
        assertEquals("Gemini cannot tell rate from quota", PolishFailure.RATE_OR_QUOTA, PolishFailure.from(PolishReason.HTTP_ERROR, 429, gemini))
    }

    @Test fun polishOffOrAnUnknownContextNeverProducesANotice() {
        PolishReason.entries.forEach { reason ->
            assertNull("$reason under Off", PolishFailure.from(reason, 401, PolishContext.Off))
            assertNull("$reason under an unknown token", PolishFailure.from(reason, 401, null))
        }
    }

    @Test fun theUnconfiguredCloudStaysDistinctFromOff() {
        assertEquals(PolishFailure.KEY_MISSING, PolishFailure.from(PolishReason.CLOUD_NOT_CONFIGURED, 0, PolishContext.CloudUnconfigured))
        assertEquals("no API key set yet. Add one in Settings.", PolishFailure.KEY_MISSING.message(PolishContext.CloudUnconfigured))
    }

    @Test fun everySentenceIsPlainNonEmptyAndNamesTheProviderWhereMacOsDoes() {
        val named = setOf(
            PolishFailure.KEY_MISSING, PolishFailure.KEY_REJECTED, PolishFailure.ACCESS_DENIED, PolishFailure.OUT_OF_CREDITS,
            PolishFailure.RATE_LIMITED, PolishFailure.RATE_OR_QUOTA, PolishFailure.MODEL_UNAVAILABLE, PolishFailure.CONTENT_BLOCKED,
            PolishFailure.UNREACHABLE, PolishFailure.PROVIDER_ERROR,
        )
        PolishFailure.entries.forEach { failure ->
            listOf(gemini, PolishContext.Local, PolishContext.CloudUnconfigured, ollama, null).forEach { context ->
                val sentence = failure.message(context)
                assertTrue("$failure/$context is empty", sentence.isNotBlank())
                assertFalse("$failure/$context carries a dash", sentence.contains('—') || sentence.contains('–'))
            }
            if (failure in named) assertTrue("$failure names Gemini", failure.message(gemini).contains("Gemini"))
        }
    }

    @Test fun everyGeminiSentenceIsExact() {
        val expected = mapOf(
            PolishFailure.KEY_MISSING to "no Gemini API key set yet. Add one in Settings.",
            PolishFailure.KEY_REJECTED to "Gemini rejected your API key. Check or replace it in Settings.",
            PolishFailure.ACCESS_DENIED to "Gemini denied access. Check your provider billing, API access, region, or selected model.",
            PolishFailure.OUT_OF_CREDITS to "your Gemini account is out of credits. Check your provider billing.",
            PolishFailure.RATE_LIMITED to "too many requests to Gemini right now. It should work again in a moment.",
            PolishFailure.RATE_OR_QUOTA to "Gemini hit a rate or quota limit. Wait a moment, or check your Gemini billing if it keeps happening.",
            PolishFailure.MODEL_UNAVAILABLE to "the selected Gemini model isn't available. Pick another in Settings.",
            PolishFailure.INPUT_TOO_LONG to "this dictation is too long for the selected model. Try a shorter one or a larger model in Settings.",
            PolishFailure.CONTENT_BLOCKED to "Gemini blocked this text. Your original was pasted unchanged.",
            PolishFailure.UNREACHABLE to "couldn't reach Gemini. Check your internet connection, VPN, or proxy.",
            PolishFailure.PROVIDER_ERROR to "Gemini is having problems right now. Try again shortly.",
            PolishFailure.BAD_REQUEST to "a configuration problem stopped it. Your original text was pasted unchanged.",
            PolishFailure.TIMED_OUT to "the dictation took too long. Your original text was pasted unchanged.",
            PolishFailure.OUTPUT_REJECTED to "the model's answer didn't match what you said, so your original was kept.",
            PolishFailure.LOCAL_NOT_READY to "the on-phone model isn't ready yet. Try again in a moment.",
            PolishFailure.UNEXPECTED to "an unexpected error stopped it. Your original text was pasted unchanged.",
        )
        assertEquals(PolishFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, text) -> assertEquals(failure.name, text, failure.message(gemini)) }
    }

    @Test fun theSkippedClassIsTheMacOsOne() {
        val skipped = PolishFailure.entries.filter { it.leadIn == PolishFailure.LeadIn.SKIPPED }.toSet()
        assertEquals(
            setOf(PolishFailure.KEY_MISSING, PolishFailure.INPUT_TOO_LONG, PolishFailure.TIMED_OUT, PolishFailure.LOCAL_NOT_READY),
            skipped,
        )
    }

    @Test fun ollamaGetsItsOwnGuidance() {
        assertTrue(PolishFailure.KEY_REJECTED.message(ollama).startsWith("Ollama isn't signed in"))
        assertTrue(PolishFailure.MODEL_UNAVAILABLE.message(ollama).contains("Pull it in Ollama"))
        assertTrue(PolishFailure.UNREACHABLE.message(ollama).startsWith("Ollama isn't reachable"))
        assertTrue(PolishFailure.ACCESS_DENIED.message(ollama).contains("subscription"))
    }
}
