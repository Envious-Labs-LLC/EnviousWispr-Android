package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File

/**
 * Drift Guard (#189), counted as such: it pins the SHAPE the split produced, not a user outcome. Paired
 * with `ProviderAdapterTest.everyProviderResolvesToItsNamedAdapter`, which pins the routing, so what is
 * guarded is that a provider decision cannot creep back into a client, that the network stays behind one
 * transport, and that the JSON parser stays bounded rather than caught.
 *
 * Run against the pre-split `ProviderPolishClient.kt` (1,323 lines, commit `01f441e`) every assertion here
 * was red: eleven provider branches, the `HttpURLConnection` import and four `StackOverflowError` catches.
 */
class ProviderClientShapeTest {
    private val providers = File("src/main/java/com/envi/wispr/providers")
    private val clients = listOf("ProviderPolishClient.kt", "ProviderModelDiscoveryClient.kt", "HttpProviderTransport.kt")

    /** A concrete enum constant, not the type name: `Provider.OPENAI` is a branch, `Provider` in a signature is not. */
    private val concreteProvider = Regex("""Provider\.(OPENAI|GEMINI|CLAUDE|SELF_HOSTED_POLISH)""")

    @Test fun theClientsAndTheTransportHoldNoProviderBranch() {
        clients.forEach { name ->
            val hits = concreteProvider.findAll(File(providers, name).readText()).map { it.value }.toList()
            assertEquals("$name names a provider constant; that decision belongs in its adapter", emptyList<String>(), hits)
        }
    }

    @Test fun onlyTheTransportOpensAConnection() {
        // The IMPORT, not the word: a KDoc may name the class, only code that imports it can open one.
        val importers = providers.listFiles { f -> f.name.endsWith(".kt") }!!
            .filter { it.readText().contains("import java.net.HttpURLConnection") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("HttpProviderTransport.kt"), importers)
    }

    @Test fun theParserIsBoundedNotCaught() {
        // The CATCH, not the word: the parser's KDoc says why the error class is gone.
        val catcher = Regex("""catch\s*\([^)]*StackOverflowError""")
        val catchers = providers.listFiles { f -> f.name.endsWith(".kt") }!!
            .filter { catcher.containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals(emptyList<String>(), catchers)
        assertTrue(File(providers, "ProviderJson.kt").readText().contains("MAX_DEPTH"))
    }

    @Test fun theAdaptersAreTheOnlyRoutingFromAProvider() {
        // `when (provider)` over the enum appears once under providers/ outside Provider.kt's own tables.
        val whens = providers.listFiles { f -> f.name.endsWith(".kt") && f.name != "Provider.kt" }!!
            .flatMap { f -> Regex("""when \((request\.)?provider\)""").findAll(f.readText()).map { f.name } }
        assertEquals(listOf("ProviderAdapter.kt"), whens)
    }
}
