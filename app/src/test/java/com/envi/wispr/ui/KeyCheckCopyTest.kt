package com.envi.wispr.ui

import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.providers.ProviderKeyCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome: when this fails the user reads "rejected" for a network problem and pastes a good key
 * three more times, or a refusal shows no line at all and the page looks stuck.
 */
class KeyCheckCopyTest {
    @Test fun acceptedAndNotApplicableHaveNoLine() {
        assertNull(keyCheckLine(ProviderKeyCheck.Accepted, "OpenAI"))
        assertNull(keyCheckLine(ProviderKeyCheck.NotApplicable, "Self-hosted polish"))
    }

    @Test fun rejectedAndDeniedNameTheProviderAndSayNothingWasSaved() {
        assertEquals("Gemini rejected this key. Nothing was saved.", keyCheckLine(ProviderKeyCheck.Rejected(403), "Gemini"))
        assertEquals(
            "OpenAI denied access for this key. Check your billing or API access. Nothing was saved.",
            keyCheckLine(ProviderKeyCheck.Denied(403), "OpenAI"),
        )
    }

    @Test fun unverifiedSaysCouldNotCheckWithTheReason() {
        fun line(f: PolishFailure) = keyCheckLine(ProviderKeyCheck.Unverified(f, 0), "Claude")
        assertEquals("Couldn't check the key with Claude: too many requests right now. Nothing was saved.", line(PolishFailure.RATE_LIMITED))
        assertEquals("Couldn't check the key with Claude: too many requests right now. Nothing was saved.", line(PolishFailure.RATE_OR_QUOTA))
        assertEquals("Couldn't check the key with Claude: Claude is having problems. Nothing was saved.", line(PolishFailure.PROVIDER_ERROR))
        assertEquals("Couldn't check the key with Claude: no connection. Nothing was saved.", line(PolishFailure.UNREACHABLE))
        assertEquals("Couldn't check the key with Claude: it took too long. Nothing was saved.", line(PolishFailure.TIMED_OUT))
        assertEquals("Couldn't check the key with Claude: an unexpected reply. Nothing was saved.", line(PolishFailure.BAD_REQUEST))
        assertEquals("Couldn't check the key with Claude: an unexpected reply. Nothing was saved.", line(PolishFailure.UNEXPECTED))
    }

    @Test fun noLineCarriesADash() {
        val all = listOf(ProviderKeyCheck.Rejected(401), ProviderKeyCheck.Denied(403)) +
            PolishFailure.entries.map { ProviderKeyCheck.Unverified(it, 0) }
        all.forEach { verdict ->
            val line = keyCheckLine(verdict, "OpenAI")!!
            org.junit.Assert.assertFalse(line, line.contains('—') || line.contains('–'))
        }
    }
}
