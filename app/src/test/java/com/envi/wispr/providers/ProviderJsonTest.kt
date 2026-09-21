package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Outcome (#189). When this fails a provider body that should be refused is accepted (and its
 * "text" inserted into the user's editor), a well-formed reply is refused (and the user gets raw words), or
 * a hostile body takes the worker down instead of being refused. Every expected value is a literal.
 */
class ProviderJsonTest {
    private fun parse(body: String): Any? = ProviderJson.parseOrNull(body)!!.root

    @Test fun acceptsEveryProviderEnvelopeShape() {
        assertEquals(mapOf("a" to listOf(1.0, "two", true, null)), parse("{\"a\":[1,\"two\",true,null]}"))
        assertEquals(listOf(emptyMap<String, Any?>(), emptyList<Any?>()), parse(" [ {} , [] ] "))
        assertEquals("quote \" slash \\ tab \t nl \n uni é", parse("\"quote \\\" slash \\\\ tab \\t nl \\n uni \\u00e9\""))
        assertEquals(-12.5, parse("-12.5"))
        assertEquals(1.0E3, parse("1e3"))
        assertEquals(mapOf("k" to mapOf("deep" to "yes")), parse("{\"k\":{\"deep\":\"yes\"}}"))
        // A well-formed JSON `null` is a parsed body whose root is null, not a malformed body.
        val literalNull = ProviderJson.parseOrNull("null")
        assertNotNull(literalNull)
        assertNull(literalNull!!.root)
    }

    @Test fun refusesTrailingContentControlCharactersAndBadEscapes() {
        assertNull(ProviderJson.parseOrNull(""))
        assertNull(ProviderJson.parseOrNull("{\"a\":1} trailing"))
        assertNull(ProviderJson.parseOrNull("{\"a\":1,}"))
        assertNull(ProviderJson.parseOrNull("[1,]"))
        assertNull(ProviderJson.parseOrNull("{\"a\" 1}"))
        assertNull(ProviderJson.parseOrNull("\"raw\u0001control\""))
        assertNull(ProviderJson.parseOrNull("\"bad \\x escape\""))
        assertNull(ProviderJson.parseOrNull("\"short \\u12\""))
        assertNull(ProviderJson.parseOrNull("\"unterminated"))
        assertNull(ProviderJson.parseOrNull("01"))
        assertNull(ProviderJson.parseOrNull("1."))
        assertNull(ProviderJson.parseOrNull("1e"))
        assertNull(ProviderJson.parseOrNull("tru"))
        assertNull(ProviderJson.parseOrNull("<html>not json</html>"))
    }

    /**
     * Depth counts open containers, the root being depth 1: 64 parse and a 65th is refused. An ordinary
     * JVM stack parses 65 levels without the cap, so this row goes red on the cap alone, never on a
     * `StackOverflowError`; the whole point of the cap is that the error class is gone.
     */
    @Test fun refusesNestingPastTheCapAndAcceptsAtIt() {
        assertEquals(64, ProviderJson.MAX_DEPTH)
        val sixtyFour = "[".repeat(64) + "]".repeat(64)
        val sixtyFive = "[".repeat(65) + "]".repeat(65)
        assertNotNull(ProviderJson.parseOrNull(sixtyFour))
        assertNull(ProviderJson.parseOrNull(sixtyFive))
        // Objects count the same as arrays, and mixing them counts every container.
        val mixedSixtyFour = "{\"k\":".repeat(32) + "[".repeat(32) + "]".repeat(32) + "}".repeat(32)
        val mixedSixtyFive = "{\"k\":".repeat(33) + "[".repeat(32) + "]".repeat(32) + "}".repeat(33)
        assertNotNull(ProviderJson.parseOrNull(mixedSixtyFour))
        assertNull(ProviderJson.parseOrNull(mixedSixtyFive))
        // A hostile body far past the cap is refused, not thrown.
        assertNull(ProviderJson.parseOrNull("[".repeat(100_000)))
    }
}
