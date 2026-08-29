package com.envi.wispr.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {
    private lateinit var context: Context
    private lateinit var store: AndroidKeystoreSecretStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageFiles().forEach(File::delete)
        store = AndroidKeystoreSecretStore(context)
    }

    @After fun tearDown() {
        storageFiles().forEach(File::delete)
    }

    @Test fun roundTripUsesKeystoreAndDoesNotPersistPlaintext() {
        val secret = "test-secret-never-for-production"
        store.put(Provider.OPENAI, secret)
        assertEquals(secret, store.get(Provider.OPENAI))
        val bytes = File(context.noBackupFilesDir, "provider-secrets.v1").readBytes()
        assertFalse(bytes.decodeToString().contains(secret))
        assertTrue(bytes.decodeToString().startsWith("OPENAI=v1:"))
    }

    @Test fun blankSecretsAreRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.put(Provider.OPENAI, " \t\n")
        }
    }

    @Test fun rewritingASecretUsesAFreshGcmNonce() {
        store.put(Provider.OPENAI, "same-secret")
        val first = File(context.noBackupFilesDir, "provider-secrets.v1").readText()
        store.put(Provider.OPENAI, "same-secret")
        val second = File(context.noBackupFilesDir, "provider-secrets.v1").readText()
        assertNotEquals(first, second)
    }

    @Test fun ciphertextIsBoundToItsProviderByAad() {
        val file = File(context.noBackupFilesDir, "provider-secrets.v1")
        store.put(Provider.OPENAI, "openai-test")
        file.writeText(file.readText().replace("OPENAI=", "CLAUDE="))

        org.junit.Assert.assertThrows(GeneralSecurityException::class.java) {
            store.get(Provider.CLAUDE)
        }
    }

    @Test fun valuesCanBeRemovedAndAreIsolatedByProvider() {
        store.put(Provider.OPENAI, "openai-test")
        store.put(Provider.CLAUDE, "claude-test")
        store.remove(Provider.OPENAI)
        assertNull(store.get(Provider.OPENAI))
        assertEquals("claude-test", store.get(Provider.CLAUDE))
    }

    @Test fun removingTheLastValueRemovesTheBackingFile() {
        val file = File(context.noBackupFilesDir, "provider-secrets.v1")
        store.put(Provider.OPENAI, "openai-test")
        store.remove(Provider.OPENAI)
        assertFalse(file.exists())

        // Clearing the last value also clears the alias. A later write must provision a fresh key.
        store.put(Provider.OPENAI, "openai-test-again")
        assertEquals("openai-test-again", store.get(Provider.OPENAI))
    }

    @Test fun missingPrimaryRestoresBackupAndCleansRecoveryArtifacts() {
        val file = File(context.noBackupFilesDir, "provider-secrets.v1")
        val backup = File(context.noBackupFilesDir, "provider-secrets.v1.bak")
        val temporary = File(context.noBackupFilesDir, "provider-secrets.v1.tmp")
        store.put(Provider.OPENAI, "openai-recoverable")
        backup.writeBytes(file.readBytes())
        file.delete()
        temporary.writeText("partial encrypted write")

        assertEquals("openai-recoverable", store.get(Provider.OPENAI))
        assertTrue(file.exists())
        assertFalse(backup.exists())
        assertFalse(temporary.exists())
    }

    private fun storageFiles() = listOf(
        File(context.noBackupFilesDir, "provider-secrets.v1"),
        File(context.noBackupFilesDir, "provider-secrets.v1.tmp"),
        File(context.noBackupFilesDir, "provider-secrets.v1.bak"),
    )
}
