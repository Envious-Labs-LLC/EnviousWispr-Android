package com.envi.wispr.polish

import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drift Guard: the tokens are database schema values. When this fails, a History row written by an older
 * build decodes to the wrong policy, or a rename has silently changed what stored rows mean.
 */
class PolishContextTest {
    @Test fun everyPolicyRoundTripsThroughItsToken() {
        val policies = listOf(
            PolishPolicy.Off to "off",
            PolishPolicy.LocalS1 to "local",
            PolishPolicy.CloudUnconfigured to "cloud-unconfigured",
            PolishPolicy.Cloud(Provider.GEMINI, "m", null, SelfHostedProtocol.OPENAI_COMPATIBLE) to "cloud:GEMINI",
            PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "m", "http://h:1", SelfHostedProtocol.OLLAMA) to "cloud:SELF_HOSTED_POLISH:ollama",
            PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "m", "http://h:1", SelfHostedProtocol.OPENAI_COMPATIBLE) to "cloud:SELF_HOSTED_POLISH",
        )
        policies.forEach { (policy, token) ->
            val context = PolishContext.from(policy)
            assertEquals(token, context.encode())
            assertEquals(context, PolishContext.decode(token))
        }
    }

    @Test fun anEmptyOrUnknownTokenDecodesToNullRatherThanThrowing() {
        listOf("", "cloud:", "cloud:NOPE", "legacy", "cloud:GEMINI:x", "cloud:GEMINI:ollama", "cloud:OPENAI:ollama").forEach { assertNull(it, PolishContext.decode(it)) }
    }

    @Test fun theProviderNameIsResolvedOnlyWhileRendering() {
        assertEquals("Gemini", PolishContext.Cloud(Provider.GEMINI, false).providerName)
        assertEquals(PolishContext.LOCAL_NAME, PolishContext.Local.providerName)
        assertNull(PolishContext.CloudUnconfigured.providerName)
        assertNull(PolishContext.Off.providerName)
    }
}
