package com.envi.wispr.polish

import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome: when this fails, the History row and the completion surface disagree about one
 * dictation, or a session-side fallback (engine unbound, died, call threw, watchdog) announces nothing.
 */
class PolishPublicationFactsTest {
    private val cloud = PolishContext.from(PolishPolicy.Cloud(Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE))

    @Test fun theOutcomeRouteStoresItsFactsAndSaysTheSentenceHistoryWillSay() {
        val facts = PolishPublicationFacts.from(PolishReason.HTTP_ERROR, 401, cloud)
        assertEquals("HTTP_ERROR", facts.reasonToken)
        assertEquals(401, facts.statusCode)
        assertEquals("cloud:OPENAI", facts.contextToken)
        assertEquals(PolishFailure.KEY_REJECTED, facts.failure)
        assertEquals(
            PolishFailure.KEY_REJECTED,
            PolishFailure.from(PolishReason.valueOf(facts.reasonToken), facts.statusCode, PolishContext.decode(facts.contextToken)),
        )
        assertEquals(PolishFailureNotice.notice(PolishFailure.KEY_REJECTED, cloud).text, facts.notice?.text)
    }

    @Test fun everySessionSideFallbackProducerAnnouncesWhenPolishWasOn() {
        // The eight producers in DictationSessionService, by reason: two SERVICE_DIED, one SERVICE_UNAVAILABLE,
        // one WATCHDOG_TIMEOUT, four CALL_FAILED. All carry status 0 and the latched context.
        listOf(PolishReason.SERVICE_DIED, PolishReason.SERVICE_UNAVAILABLE, PolishReason.WATCHDOG_TIMEOUT, PolishReason.CALL_FAILED).forEach { reason ->
            val facts = PolishPublicationFacts.from(reason, 0, PolishContext.Local)
            assertNotNull("$reason under local polish", facts.notice)
            assertEquals("local", facts.contextToken)
            assertEquals(0, facts.statusCode)
        }
    }

    @Test fun polishOffNeverAnnouncesWhateverTheReason() {
        PolishReason.entries.forEach { reason ->
            val facts = PolishPublicationFacts.from(reason, 500, PolishContext.Off)
            assertNull("$reason under Off", facts.notice)
            assertEquals("off", facts.contextToken)
        }
    }

    @Test fun aHealthyOutcomeStoresItsFactsAndSaysNothing() {
        val facts = PolishPublicationFacts.from(PolishReason.POLISHED, 0, cloud)
        assertEquals("POLISHED", facts.reasonToken)
        assertNull(facts.notice)
    }
}
