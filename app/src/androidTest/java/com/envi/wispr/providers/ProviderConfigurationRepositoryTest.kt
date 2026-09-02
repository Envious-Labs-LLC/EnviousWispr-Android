package com.envi.wispr.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.polish.PolishPolicy
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderConfigurationRepositoryTest {
    private lateinit var context: Context
    private lateinit var secrets: MemorySecrets
    private lateinit var checker: RecordingChecker
    private lateinit var repository: ProviderConfigurationRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        secrets = MemorySecrets()
        checker = RecordingChecker()
        repository = ProviderConfigurationRepository(context, secrets, checker)
    }

    @Test fun defaultsToOfflineS1AndPersistsEachExplicitMode() {
        assertEquals(PolishMode.OFFLINE_S1, repository.loadMode())

        repository.setMode(PolishMode.OFF)
        assertEquals(PolishMode.OFF, repository.loadMode())
        repository.setMode(PolishMode.PROVIDER)
        assertEquals(PolishMode.PROVIDER, repository.loadMode())
        repository.setMode(PolishMode.OFFLINE_S1)
        assertEquals(PolishMode.OFFLINE_S1, repository.loadMode())
    }

    // #67: the engine used last, written in the same batch as the mode, on the REAL preference file.
    private fun stored(key: String): String? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null)

    /** A FAKE, named as one: real preferences cannot be made to fail a commit. The mode stays untouched. */
    @Test fun aFailedCommitLeavesTheModeUntouched_failingFake() {
        val real = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        real.edit().clear().putString("mode", "OFF").commit()
        val failing = FailingCommitPreferences(real)
        val fragile = ProviderConfigurationRepository(failing, secrets, checker)
        val threw = runCatching { fragile.setMode(PolishMode.OFFLINE_S1) }.isFailure
        assertEquals(true, threw)
        assertEquals("OFF", stored("mode"))
        assertEquals(listOf(setOf("mode")), failing.attemptedCommits)
    }

    // #81: the Keystore put happens before the preferences commit, so a failed commit must give the old key back.

    @Test fun aFailedCommitOnReplaceRestoresThePreviousKey_failingFake() {
        repository.saveProvider(Provider.OPENAI, "gpt-test", null, "old-key", SelfHostedProtocol.OPENAI_COMPATIBLE)
        val fragile = ProviderConfigurationRepository(FailingCommitPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)), secrets, checker)
        val failure = runCatching { fragile.saveProvider(Provider.OPENAI, "gpt-test", null, "new-key", SelfHostedProtocol.OPENAI_COMPATIBLE) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException && failure !is InconsistentProviderStorageException)
        assertEquals("old-key", secrets.get(Provider.OPENAI))
        assertEquals("gpt-test", stored("model"))
    }

    @Test fun aFailedCommitOnAFirstSaveRemovesTheNewKey_failingFake() {
        val fragile = ProviderConfigurationRepository(FailingCommitPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)), secrets, checker)
        val threw = runCatching { fragile.saveProvider(Provider.GEMINI, "gemini-test", null, "new-key", SelfHostedProtocol.OPENAI_COMPATIBLE) }.isFailure
        assertEquals(true, threw)
        assertNull(secrets.get(Provider.GEMINI))
        assertNull(stored("provider"))
    }

    @Test fun anUnreadableKeySnapshotAbortsBeforeAnyWrite_failingFake() {
        val broken = FailingSecrets(secrets, failGet = true)
        val fragile = ProviderConfigurationRepository(context, broken, checker)
        val threw = runCatching { fragile.saveProvider(Provider.OPENAI, "gpt-test", null, "new-key", SelfHostedProtocol.OPENAI_COMPATIBLE) }.isFailure
        assertEquals(true, threw)
        assertEquals("the checker was never asked", 0, checker.calls.size)
        assertNull(stored("provider"))
        assertEquals(0, broken.puts)
    }

    @Test fun aFailedCommitOnRemoveRestoresTheKey_failingFake() {
        repository.saveProvider(Provider.OPENAI, "gpt-test", null, "old-key", SelfHostedProtocol.OPENAI_COMPATIBLE)
        val fragile = ProviderConfigurationRepository(FailingCommitPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)), secrets, checker)
        val threw = runCatching { fragile.clearSelection() }.isFailure
        assertEquals(true, threw)
        assertEquals("old-key", secrets.get(Provider.OPENAI))
        assertEquals("OPENAI", stored("provider"))
    }

    @Test fun aFailedRestoreReportsInconsistentStorage_failingFake() {
        repository.saveProvider(Provider.OPENAI, "gpt-test", null, "old-key", SelfHostedProtocol.OPENAI_COMPATIBLE)
        val broken = FailingSecrets(secrets, failPutAfter = 1)
        val fragile = ProviderConfigurationRepository(FailingCommitPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)), broken, checker)
        val failure = runCatching { fragile.saveProvider(Provider.OPENAI, "gpt-test", null, "new-key", SelfHostedProtocol.OPENAI_COMPATIBLE) }.exceptionOrNull()
        assertTrue("$failure", failure is InconsistentProviderStorageException)
    }

    // #61: no cloud key is written unless the checker accepted it; the checker sees the key that would be stored.

    @Test fun anAcceptedCheckSavesOnceWithTheKeyTrimmed() {
        repository.save(Provider.OPENAI, "gpt-test", apiKey = "  sk-spaced  ")
        assertEquals(listOf(Provider.OPENAI to "sk-spaced"), checker.calls)
        assertEquals("sk-spaced", repository.load()?.apiKey)
        assertEquals("gpt-test", repository.load()?.model)
    }

    @Test fun aRefusedCheckWritesNothing() {
        val before = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.toMap()
        listOf(
            ProviderKeyCheck.Rejected(401),
            ProviderKeyCheck.Denied(403),
            ProviderKeyCheck.Unverified(com.envi.wispr.polish.PolishFailure.UNREACHABLE),
            // A "no key to check" answer for a provider that needs one is a checker fault, never a pass.
            ProviderKeyCheck.NotApplicable,
        ).forEach { verdict ->
            checker.verdict = verdict
            val thrown = runCatching { repository.save(Provider.GEMINI, "gemini-test", apiKey = "AIza") }.exceptionOrNull()
            assertEquals("$verdict", verdict, (thrown as? ProviderKeyRefusedException)?.verdict)
            assertEquals("$verdict", before, context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.toMap())
            assertNull("$verdict", secrets.get(Provider.GEMINI))
        }
        assertEquals(4, checker.calls.size)
    }

    @Test fun aBlankDraftChecksTheStoredKey() {
        repository.save(Provider.CLAUDE, "claude-old", apiKey = "sk-ant-stored")
        checker.calls.clear()
        repository.save(Provider.CLAUDE, "claude-new", apiKey = null)
        assertEquals(listOf(Provider.CLAUDE to "sk-ant-stored"), checker.calls)
        assertEquals("claude-new", repository.load()?.model)
        assertEquals("sk-ant-stored", repository.load()?.apiKey)
    }

    @Test fun aControlCharacterKeyIsRefusedBeforeAnyCheck() {
        val thrown = runCatching { repository.save(Provider.OPENAI, "gpt-test", apiKey = "sk\u0007bad") }.exceptionOrNull()
        assertEquals(true, thrown is IllegalArgumentException)
        assertEquals(emptyList<Pair<Provider, String>>(), checker.calls)
        assertNull(repository.load())
    }

    @Test fun selfHostedNeverAsksTheChecker() {
        repository.save(Provider.SELF_HOSTED_POLISH, "llama3.2", endpoint = "http://localhost:8080/x", apiKey = "local-secret")
        assertEquals(emptyList<Pair<Provider, String>>(), checker.calls)
        assertEquals("local-secret", repository.load()?.apiKey)
    }

    @Test fun providerModeWithoutSelectionRemainsNotReadyForServiceFallback() {
        repository.setMode(PolishMode.PROVIDER)

        assertEquals(PolishMode.PROVIDER, repository.loadMode())
        assertNull(repository.load())
    }

    @Test fun persistsSelectionMetadataAndKeepsKeyOutOfPreferences() {
        repository.save(Provider.OPENAI, "gpt-test", apiKey = "openai-secret")

        val loaded = repository.load()
        assertEquals(Provider.OPENAI, loaded?.provider)
        assertEquals("gpt-test", loaded?.model)
        assertEquals("openai-secret", loaded?.apiKey)
        assertFalse(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.values
            .any { it == "openai-secret" })
    }

    @Test fun blankKeyKeepsExistingEncryptedKeyWhenEditingProviderSettings() {
        repository.save(Provider.OPENAI, "gpt-old", apiKey = "openai-secret")
        repository.save(Provider.OPENAI, "gpt-new", apiKey = null)

        assertEquals("gpt-new", repository.load()?.model)
        assertEquals("openai-secret", repository.load()?.apiKey)
    }

    @Test fun selfHostedSelectionPersistsValidatedEndpointAndOptionalKey() {
        repository.save(
            provider = Provider.SELF_HOSTED_POLISH,
            model = "llama3.2",
            endpoint = "http://localhost:8080/configured",
            apiKey = "local-secret",
            selfHostedProtocol = SelfHostedProtocol.OLLAMA,
        )

        val loaded = repository.load()
        assertEquals("http://localhost:8080/configured", loaded?.endpoint)
        assertEquals("local-secret", loaded?.apiKey)
        assertEquals(
            ProviderPolishRequest(
                Provider.SELF_HOSTED_POLISH,
                "llama3.2",
                "hello",
                "local-secret",
                "http://localhost:8080/configured",
                SelfHostedProtocol.OLLAMA,
            ),
            loaded?.request("hello"),
        )
    }

    @Test fun savingProviderSelectsProviderModeAndClearingRemovesItsSecret() {
        repository.save(Provider.OPENAI, "gpt-test", apiKey = "openai-secret")
        assertEquals(PolishMode.PROVIDER, repository.loadMode())
        repository.clearSelection()

        assertNull(repository.load())
        assertEquals(PolishMode.OFFLINE_S1, repository.loadMode())
        assertNull(secrets.get(Provider.OPENAI))
    }

    /**
     * Product Outcome (#103, founder 2026-09-02: "all 3 keys are showing blank in the app now").
     *
     * A Remove deletes ONE provider's key. When this fails, removing one key takes the others with it, or
     * leaves the app pointing at a provider whose key has just gone.
     */
    @Test fun removingOneProvidersKeyLeavesTheOtherProvidersKeysAlone() {
        repository.save(Provider.OPENAI, "gpt-test", apiKey = "openai-secret")
        repository.save(Provider.CLAUDE, "claude-test", apiKey = "claude-secret")
        repository.save(Provider.GEMINI, "gemini-test", apiKey = "gemini-secret")
        assertEquals(setOf(Provider.OPENAI, Provider.CLAUDE, Provider.GEMINI), repository.storedProviders())

        // Removing a key that is NOT the selected one touches only that key. Gemini stays selected and
        // cloud polish keeps running.
        repository.removeKey(Provider.OPENAI)
        assertEquals(setOf(Provider.CLAUDE, Provider.GEMINI), repository.storedProviders())
        assertNull(secrets.get(Provider.OPENAI))
        assertEquals(PolishMode.PROVIDER, repository.loadMode())
        assertEquals(Provider.GEMINI, repository.load()?.provider)

        // Removing the SELECTED provider's key clears the selection, because a provider mode with no key
        // cannot polish. The other key survives, which is the whole incident.
        repository.removeKey(Provider.GEMINI)
        assertEquals(setOf(Provider.CLAUDE), repository.storedProviders())
        assertNull(repository.load())
        assertEquals(PolishMode.OFFLINE_S1, repository.loadMode())
        assertEquals("claude-secret", secrets.get(Provider.CLAUDE))

        // And the survivor can still be removed, which it could not while the UI only knew about the
        // selected provider.
        repository.removeKey(Provider.CLAUDE)
        assertEquals(emptySet<Provider>(), repository.storedProviders())
    }

    /**
     * Product Outcome (#103). A connected row is drawn for every stored key, and its Refresh and model list
     * must reach THAT provider's credential. When this fails, refreshing any tile but the active one
     * refuses with "no key" while the key is sitting in the Keystore.
     *
     * The assertion is on the LISTING, never on the key. An earlier version of this test asserted the
     * returned key value, which is the escape the operation-specific signature exists to close.
     */
    @Test fun aStoredKeyListsModelsForItsOwnProviderNotOnlyForTheSelectedOne() {
        repository.save(Provider.OPENAI, "gpt-test", apiKey = "openai-secret")
        repository.save(Provider.GEMINI, "gemini-test", apiKey = "gemini-secret")
        assertEquals(Provider.GEMINI, repository.load()?.provider)

        // The discoverer proves WHICH key it was handed by naming it back as a model id, so the test can
        // assert the right credential was used without the repository ever returning one.
        val echo = ProviderModelDiscoverer { provider, apiKey ->
            ProviderDiscovery.Listed(listOf(DiscoveredModel(apiKey, provider.name, ModelAccess.AVAILABLE, false)), 0L)
        }
        fun idFor(provider: Provider) =
            (repository.discoverModelsWithStoredKey(provider, echo) as? ProviderDiscovery.Listed)?.models?.single()?.id

        // OpenAI is NOT the selected provider, and its own key is what reaches the discoverer.
        assertEquals("openai-secret", idFor(Provider.OPENAI))
        assertEquals("gemini-secret", idFor(Provider.GEMINI))
        // No key means no listing, rather than a listing built with somebody else's key.
        assertNull(repository.discoverModelsWithStoredKey(Provider.CLAUDE, echo))

        repository.removeKey(Provider.OPENAI)
        assertNull(repository.discoverModelsWithStoredKey(Provider.OPENAI, echo))
    }

    /** A store that cannot be read reports no keys rather than throwing at the screen. */
    @Test fun anUnreadableStoreReportsNoStoredProviders_failingFake() {
        val failing = ProviderConfigurationRepository(context, FailingSecrets(secrets, failGet = true), checker)
        assertEquals(emptySet<Provider>(), failing.storedProviders())
        assertNull(failing.discoverModelsWithStoredKey(Provider.OPENAI) { _, _ -> throw AssertionError("must not be reached") })
    }

    /**
     * Product Outcome: when this fails, the mode the user picked is not the mode the engine runs,
     * because the session owner's policy snapshot disagrees with what the screen saved. The
     * unreadable-store branch is `readPolicy`, staged on the JVM in `PolishPolicyTest`.
     */
    @Test fun loadPolicyReadsTheStoredSnapshotWithoutTheKey() {
        assertEquals(PolishPolicy.LocalS1, repository.loadPolicy())

        repository.setMode(PolishMode.OFF)
        assertEquals(PolishPolicy.Off, repository.loadPolicy())

        repository.setMode(PolishMode.PROVIDER)
        assertEquals(PolishPolicy.CloudUnconfigured, repository.loadPolicy())

        repository.save(Provider.OPENAI, "gpt-test", apiKey = "openai-secret")
        assertEquals(
            PolishPolicy.Cloud(Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE),
            repository.loadPolicy(),
        )

        repository.clearSelection()
        assertEquals(PolishPolicy.LocalS1, repository.loadPolicy())
    }

    /** Records every ask and answers with [verdict]; Accepted by default so the older cases still save. */
    private class RecordingChecker : ProviderKeyChecker {
        var verdict: ProviderKeyCheck = ProviderKeyCheck.Accepted
        val calls = mutableListOf<Pair<Provider, String>>()
        override fun check(provider: Provider, apiKey: String): ProviderKeyCheck {
            calls += provider to apiKey
            return verdict
        }
    }

    /** Delegates to a real store; refuses reads, or refuses puts after the first, to stage the #81 compensation paths. */
    private class FailingSecrets(private val real: SecretStore, private val failGet: Boolean = false, private val failPutAfter: Int = Int.MAX_VALUE) : SecretStore {
        var puts = 0
        override fun put(provider: Provider, secret: String) {
            puts++
            if (puts > failPutAfter) throw IllegalStateException("keystore unavailable")
            real.put(provider, secret)
        }
        override fun get(provider: Provider): String? = if (failGet) throw IllegalStateException("keystore unavailable") else real.get(provider)
        override fun remove(provider: Provider) = real.remove(provider)
        override fun storedProviders(): Set<Provider> =
            if (failGet) throw IllegalStateException("keystore unavailable") else real.storedProviders()
    }

    private class MemorySecrets : SecretStore {
        private val values = ConcurrentHashMap<Provider, String>()
        override fun put(provider: Provider, secret: String) { values[provider] = secret }
        override fun get(provider: Provider): String? = values[provider]
        override fun remove(provider: Provider) { values.remove(provider) }
        override fun storedProviders(): Set<Provider> = values.keys.toSet()
    }

    companion object {
        private const val PREFERENCES = "envious_wispr_provider_configuration"
    }
}

/**
 * Delegates every read to the real file, refuses every commit, and records which keys each refused commit
 * carried, so a write can be proven atomic AND single-batch.
 */
private class FailingCommitPreferences(private val real: android.content.SharedPreferences) : android.content.SharedPreferences by real {
    val attemptedCommits = mutableListOf<Set<String>>()

    override fun edit(): android.content.SharedPreferences.Editor {
        val keys = mutableSetOf<String>()
        return object : android.content.SharedPreferences.Editor by real.edit() {
            override fun commit(): Boolean { attemptedCommits += keys.toSet(); return false }
            override fun apply() = Unit
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { key?.let(keys::add); return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { key?.let(keys::add); return this }
            override fun clear(): android.content.SharedPreferences.Editor = this
        }
    }
}
