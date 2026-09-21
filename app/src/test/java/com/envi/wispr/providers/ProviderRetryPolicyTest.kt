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
        assertTrue(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429)))
        assertTrue(ProviderAdapters.of(Provider.CLAUDE).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429)))
        assertFalse(ProviderAdapters.of(Provider.GEMINI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429)))
        assertTrue(ProviderAdapters.of(Provider.GEMINI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 503)))
        assertTrue(ProviderAdapters.of(Provider.CLAUDE).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 529)))
    }

    @Test fun keyCreditContentAndConfigurationFailuresNeverRetry() {
        listOf(400, 401, 402, 403, 404, 413).forEach {
            assertFalse("$it", ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, it)))
        }
        ProviderErrorSignal.entries.forEach { signal ->
            assertFalse("$signal on 429", ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 429, signal)))
            assertFalse("$signal on 503", ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.HTTP_ERROR, 503, signal)))
        }
        listOf(
            ProviderFailureKind.NO_API_KEY, ProviderFailureKind.INVALID_CONFIGURATION, ProviderFailureKind.CANCELLED,
            ProviderFailureKind.MALFORMED_RESPONSE, ProviderFailureKind.RESPONSE_TOO_LARGE, ProviderFailureKind.REDIRECT_REJECTED,
        ).forEach { assertFalse("$it", ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(it))) }
    }

    @Test fun transportFailuresRetryUnlessAnObservedStatusSaysOtherwise() {
        assertTrue(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.NETWORK)))
        assertTrue(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.TIMEOUT)))
        // A read that stalled AFTER a 401 arrived: the status decides, and it says stop.
        assertFalse(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.TIMEOUT, 401)))
        assertTrue(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.TIMEOUT, 503)))
        assertFalse(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.CANCELLED, 503)))
        // An oversized or unreadable body under a 5xx is still that body: a second download cannot change it.
        assertFalse(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.RESPONSE_TOO_LARGE, 503)))
        assertFalse(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.MALFORMED_RESPONSE, 503)))
        assertFalse(ProviderAdapters.of(Provider.OPENAI).isRetryable(Failure(ProviderFailureKind.REDIRECT_REJECTED, 503)))
    }
}
