package com.envi.wispr.providers

import com.envi.wispr.providers.ProviderPolishResult.Failure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails a momentary outage drops the polish, or a rejected key is retried
 * three times against the user's account.
 */
class ProviderRetryPolicyTest {
    @Test fun transientStatusesRetryExceptGeminisAmbiguousLimit() {
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429), Provider.OPENAI))
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429), Provider.CLAUDE))
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429), Provider.GEMINI))
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 503), Provider.GEMINI))
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 529), Provider.CLAUDE))
    }

    @Test fun keyCreditContentAndConfigurationFailuresNeverRetry() {
        listOf(400, 401, 402, 403, 404, 413).forEach {
            assertFalse("$it", ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, it), Provider.OPENAI))
        }
        ProviderErrorSignal.entries.forEach { signal ->
            assertFalse("$signal on 429", ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429, signal), Provider.OPENAI))
            assertFalse("$signal on 503", ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 503, signal), Provider.OPENAI))
        }
        listOf(
            ProviderFailureKind.NO_API_KEY, ProviderFailureKind.INVALID_CONFIGURATION, ProviderFailureKind.CANCELLED,
            ProviderFailureKind.MALFORMED_RESPONSE, ProviderFailureKind.RESPONSE_TOO_LARGE, ProviderFailureKind.REDIRECT_REJECTED,
        ).forEach { assertFalse("$it", ProviderRetryPolicy.isRetryable(Failure(it), Provider.OPENAI)) }
    }

    @Test fun transportFailuresRetryUnlessAnObservedStatusSaysOtherwise() {
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.NETWORK), Provider.OPENAI))
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.TIMEOUT), Provider.OPENAI))
        // A read that stalled AFTER a 401 arrived: the status decides, and it says stop.
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.TIMEOUT, 401), Provider.OPENAI))
        assertTrue(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.TIMEOUT, 503), Provider.OPENAI))
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.CANCELLED, 503), Provider.OPENAI))
        // An oversized or unreadable body under a 5xx is still that body: a second download cannot change it.
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.RESPONSE_TOO_LARGE, 503), Provider.OPENAI))
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.MALFORMED_RESPONSE, 503), Provider.OPENAI))
        assertFalse(ProviderRetryPolicy.isRetryable(Failure(ProviderFailureKind.REDIRECT_REJECTED, 503), Provider.OPENAI))
    }
}
