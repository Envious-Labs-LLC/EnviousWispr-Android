package com.envi.wispr.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private lateinit var repository: ProviderConfigurationRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        secrets = MemorySecrets()
        repository = ProviderConfigurationRepository(context, secrets)
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
