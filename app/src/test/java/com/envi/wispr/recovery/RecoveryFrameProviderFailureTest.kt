package com.envi.wispr.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator

/**
 * Product Outcome: a failing crypto provider yields an unavailable recovery, not vendor diagnostics.
 * The failure-only provider is a test fixture, never an alternate successful crypto implementation.
 * JUnit4 runs these methods serially in a Gradle worker; separate forks have independent registries.
 */
class RecoveryFrameProviderFailureTest {
    private val identity = RecoveryRecordIdentity(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), 1L,
        RecoveryContextDigest.of(ByteArray(32) { 3 }),
    )
    private val header = RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, 0L, 0L, 1)
    // Made before installing the failure-only Cipher service; this is the host's real key generator.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun sealingSanitizesAnUncheckedProviderFailure() = withFailingProvider {
        unavailable { RecoveryFrameCodec.seal(header, byteArrayOf(1, 0), key) }
    }

    @Test fun openingSanitizesAnUncheckedProviderFailure() {
        val frame = RecoveryFrameCodec.seal(header, byteArrayOf(1, 0), key)
        withFailingProvider { unavailable { RecoveryFrameCodec.open(frame, identity, key) } }
    }

    private fun unavailable(block: () -> Unit) {
        val before = finalCalls.get()
        val failure = assertThrows(RecoveryFrameException::class.java, block)
        assertEquals("the failure-only provider must reach its actual final operation", before + 1, finalCalls.get())
        assertEquals(RecoveryFrameFailure.CRYPTO_UNAVAILABLE, failure.reason)
        assertEquals("Protected recording unavailable", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun withFailingProvider(block: () -> Unit) = synchronized(providerLock) {
        val originalNames = Security.getProviders().map { it.name }
        assertNull("test provider name is already owned", Security.getProvider(PROVIDER_NAME))
        val provider = object : Provider(PROVIDER_NAME, 1.0, "failure-only recovery test fixture") {
            init { put("Cipher.AES/GCM/NoPadding", FailingCipher::class.java.name) }
        }
        val insertedAt = Security.insertProviderAt(provider, 1)
        try {
            assertEquals(1, insertedAt)
            assertSame(provider, Security.getProvider(PROVIDER_NAME))
            assertEquals(PROVIDER_NAME, Cipher.getInstance("AES/GCM/NoPadding").provider.name)
            block()
        } finally {
            // Never remove another test's replacement if registration ownership was unexpectedly lost.
            if (Security.getProvider(PROVIDER_NAME) === provider) Security.removeProvider(PROVIDER_NAME)
            assertEquals(originalNames, Security.getProviders().map { it.name })
        }
    }

    class FailingCipher : CipherSpi() {
        override fun engineSetMode(mode: String) = Unit
        override fun engineSetPadding(padding: String) = Unit
        override fun engineGetBlockSize(): Int = 16
        override fun engineGetOutputSize(inputLen: Int): Int = inputLen + 16
        override fun engineGetIV(): ByteArray = ByteArray(12)
        override fun engineGetParameters(): AlgorithmParameters? = null
        override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) = Unit
        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) = Unit
        override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) = Unit
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray = byteArrayOf()
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int = 0
        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray = fail()
        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int = fail()

        private fun fail(): Nothing {
            finalCalls.incrementAndGet()
            throw ProviderException("synthetic vendor failure canary", IllegalStateException("synthetic token cause"))
                .also { it.addSuppressed(IllegalStateException("synthetic vendor suppression")) }
        }
    }

    companion object {
        private const val PROVIDER_NAME = "WisprRecoveryFailureOnly"
        private val providerLock = Any()
        private val finalCalls = AtomicInteger()
    }
}
