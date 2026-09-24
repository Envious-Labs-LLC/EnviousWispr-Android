package com.envi.wispr.providers

import com.envi.wispr.ui.CloudProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The per-provider disclosure the setup screen shows (#308): for every provider, the text a user dictates leaves the
 * phone, and the sentence saying where is pinned as a literal, so rewording a privacy promise is a deliberate test
 * change. These bind the Play Data Safety answer about cloud text; the offline promise is `PrivacyPageSentencesTest`.
 */
class ProviderDisclosureTest {

    /** MUTATIONS m2 and m6: every provider needs the network AND sends text off the phone. */
    @Test fun everyProviderSendsTextOffThePhone() {
        Provider.entries.forEach { provider ->
            assertTrue("$provider sends text off the phone", provider.capabilities().sendsTextOffDevice)
            assertTrue("$provider needs the network", provider.disclosure().networkRequired)
            assertEquals("$provider stores a key encrypted exactly when it needs one", provider.capabilities().requiresApiKey, provider.disclosure().apiKeyStoredEncrypted)
        }
    }

    /** MUTATION m1: each destination sentence the cloud rung shows, literally. */
    @Test fun eachDestinationSentenceIsTodays() {
        val expected = mapOf(
            Provider.OPENAI to "Text is sent to OpenAI when this provider is used.",
            Provider.GEMINI to "Text is sent to Google Gemini when this provider is used.",
            Provider.CLAUDE to "Text is sent to Anthropic Claude when this provider is used.",
            Provider.SELF_HOSTED_POLISH to "Text is sent to the configured self-hosted endpoint when used.",
        )
        assertEquals(Provider.entries.toSet(), expected.keys)
        expected.forEach { (provider, sentence) -> assertEquals(provider.name, sentence, provider.disclosure().summary) }
    }

    /** #310, MUTATIONS m3 and m4: a fresh setup offers exactly these tiles, chosen by capability, in this order. */
    @Test fun theSetupTilesAreTheProvidersThatOfferOne() {
        assertEquals(listOf(Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE), CloudProviders)
        assertFalse(Provider.SELF_HOSTED_POLISH.capabilities().offeredAsSetupTile)
    }

    /** MUTATION m5: both per-provider tables stay exhaustive, so a new provider breaks the build instead of inheriting a row. */
    @Test fun bothProviderTablesHaveNoElse() {
        val source = File("src/main/java/com/envi/wispr/providers/Provider.kt").readText()
        assertFalse("no else branch in Provider.kt's per-provider tables", Regex("""(?m)^\s*else\s*->""").containsMatchIn(source))
    }
}
