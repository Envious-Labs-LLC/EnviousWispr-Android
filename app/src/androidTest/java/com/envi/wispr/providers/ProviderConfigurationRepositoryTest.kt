package com.envi.wispr.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.polish.PolishPolicy
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun everyNonOffWriteRecordsTheLastOnModeBesideTheMode() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString("last_on_mode", "sentinel").commit()
        repository.setMode(PolishMode.OFFLINE_S1)
        assertEquals("OFFLINE_S1", stored("mode")); assertEquals("OFFLINE_S1", stored("last_on_mode"))
        repository.setMode(PolishMode.OFF)
        assertEquals("OFF", stored("mode")); assertEquals("OFFLINE_S1", stored("last_on_mode"))
        repository.saveProvider(Provider.OPENAI, "gpt-test", null, "k", SelfHostedProtocol.OPENAI_COMPATIBLE)
        assertEquals("PROVIDER", stored("mode")); assertEquals("PROVIDER", stored("last_on_mode"))
        repository.setMode(PolishMode.OFF)
        assertEquals("PROVIDER", stored("last_on_mode"))
        repository.clearSelection()
        assertEquals("OFFLINE_S1", stored("mode")); assertEquals("OFFLINE_S1", stored("last_on_mode"))
    }

    @Test fun turnOnLandsOnTheSixCellsAgainstTheRealFile() {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // last_on_mode absent, no provider
        prefs.edit().clear().putString("mode", "OFF").commit()
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn()); assertEquals("OFFLINE_S1", stored("mode"))
        // PROVIDER remembered but the configuration is gone
        prefs.edit().clear().putString("mode", "OFF").putString("last_on_mode", "PROVIDER").commit()
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn())
        // PROVIDER remembered with a configuration
        repository.saveProvider(Provider.GEMINI, "gemini-test", null, "k", SelfHostedProtocol.OPENAI_COMPATIBLE)
        repository.setMode(PolishMode.OFF)
        assertEquals(PolishMode.PROVIDER, repository.turnOn()); assertEquals("PROVIDER", stored("mode"))
        // PROVIDER remembered, metadata saved, but the key is gone from the store
        repository.setMode(PolishMode.OFF)
        secrets.remove(Provider.GEMINI)
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn()); assertEquals("OFFLINE_S1", stored("mode"))
        // last_on_mode absent while a configuration exists
        repository.saveProvider(Provider.GEMINI, "gemini-test", null, "k", SelfHostedProtocol.OPENAI_COMPATIBLE)
        prefs.edit().putString("mode", "OFF").remove("last_on_mode").commit()
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn())
        // OFFLINE_S1 remembered, with and without a configuration
        repository.setMode(PolishMode.OFFLINE_S1); repository.setMode(PolishMode.OFF)
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn())
        repository.clearSelection(); repository.setMode(PolishMode.OFF)
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn())
        // garbage remembered
        prefs.edit().putString("last_on_mode", "garbage").putString("mode", "OFF").commit()
        assertEquals(PolishMode.OFFLINE_S1, repository.turnOn())
    }

    /** A FAKE, named as one: real preferences cannot be made to fail a commit. Both keys stay untouched. */
    @Test fun aFailedCommitLeavesModeAndLastOnModeUntouched_failingFake() {
        val real = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        real.edit().clear().putString("mode", "OFF").putString("last_on_mode", "PROVIDER").commit()
        val failing = FailingCommitPreferences(real)
        val fragile = ProviderConfigurationRepository(failing, secrets, checker)
        val threw = runCatching { fragile.setMode(PolishMode.OFFLINE_S1) }.isFailure
        assertEquals(true, threw)
        assertEquals("OFF", stored("mode")); assertEquals("PROVIDER", stored("last_on_mode"))
        // Both keys were offered to ONE commit; a production that wrote mode alone before failing shows here.
        assertEquals(listOf(setOf("mode", "last_on_mode")), failing.attemptedCommits)
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

    private class MemorySecrets : SecretStore {
        private val values = ConcurrentHashMap<Provider, String>()
        override fun put(provider: Provider, secret: String) { values[provider] = secret }
        override fun get(provider: Provider): String? = values[provider]
        override fun remove(provider: Provider) { values.remove(provider) }
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
