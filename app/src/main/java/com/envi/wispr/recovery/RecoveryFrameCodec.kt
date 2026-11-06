package com.envi.wispr.recovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One independently authenticated frame, not a continuous-prefix or completed-record verdict.
 * Prefix ordering, footer reconciliation, durable attempts and key ownership belong to the store/reader.
 * This primitive is not wired into capture or recovery yet. Call it only on an owning worker.
 */
internal object RecoveryFrameCodec {
    private val magic = byteArrayOf(0x45, 0x57, 0x52, 0x41, 0x55, 0x44, 0x30, 0x31) // EWRAUD01
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The provider chooses a fresh IV. There is no caller-IV or plaintext fallback path. */
    fun seal(header: RecoveryFrameHeader, plaintext: ByteArray, key: SecretKey): ByteArray {
        validate(header, plaintext.size)
        val aad = encodeHeader(header, plaintext.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: unavailable()
            if (iv.size != RecoveryFrameLimits.IV_BYTES) unavailable()
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            if (ciphertext.size != plaintext.size + RecoveryFrameLimits.TAG_BYTES) unavailable()
            return ByteArrayOutputStream(aad.size + iv.size + ciphertext.size).use { output ->
                output.write(aad)
                output.write(iv)
                output.write(ciphertext)
                output.toByteArray()
            }
        } catch (_: ProviderException) {
            unavailable()
        } catch (_: GeneralSecurityException) {
            unavailable()
        }
    }

    /**
     * Nothing exposes plaintext until doFinal has verified the tag. The parsed header is encoded back
     * into canonical AAD, so a caller mutating its input array cannot authenticate one header and return another.
     */
    fun open(frame: ByteArray, expected: RecoveryRecordIdentity, key: SecretKey): AuthenticatedFrame =
        AuthenticatedFrame.authenticate(frame, expected, key)

    internal class AuthenticatedFrame private constructor(
        val header: RecoveryFrameHeader,
        private val plaintext: ByteArray,
    ) {
        val payloadBytes: Int get() = plaintext.size

        fun copyPayload(): ByteArray = plaintext.copyOf()

        companion object {
            fun authenticate(frame: ByteArray, expected: RecoveryRecordIdentity, key: SecretKey): AuthenticatedFrame {
                if (frame.size < RecoveryFrameLimits.OVERHEAD_BYTES || frame.size > RecoveryFrameLimits.MAX_FRAME_BYTES) malformed()
                try {
                    DataInputStream(ByteArrayInputStream(frame)).use { input ->
                        val (header, payloadBytes) = readHeader(input)
                        validate(header, payloadBytes)
                        if (frame.size.toLong() != payloadBytes.toLong() + RecoveryFrameLimits.OVERHEAD_BYTES) malformed()
                        if (header.identity != expected) throw RecoveryFrameException(RecoveryFrameFailure.WRONG_RECORD)
                        val iv = ByteArray(RecoveryFrameLimits.IV_BYTES).also(input::readFully)
                        val ciphertext = ByteArray(payloadBytes + RecoveryFrameLimits.TAG_BYTES).also(input::readFully)
                        if (input.read() != -1) malformed()
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecoveryFrameLimits.TAG_BITS, iv))
                        cipher.updateAAD(encodeHeader(header, payloadBytes))
                        val plaintext = cipher.doFinal(ciphertext)
                        if (plaintext.size != payloadBytes) malformed()
                        return AuthenticatedFrame(header, plaintext)
                    }
                } catch (failure: RecoveryFrameException) {
                    throw failure
                } catch (_: AEADBadTagException) {
                    throw RecoveryFrameException(RecoveryFrameFailure.AUTHENTICATION_FAILED)
                } catch (_: ProviderException) {
                    unavailable()
                } catch (_: GeneralSecurityException) {
                    unavailable()
                } catch (_: IOException) {
                    malformed()
                }
            }
        }
    }

    private fun encodeHeader(header: RecoveryFrameHeader, payloadBytes: Int): ByteArray =
        ByteArrayOutputStream(RecoveryFrameLimits.AAD_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeInt(RecoveryFrameLimits.VERSION)
                output.writeByte(header.kind.wireTag)
                output.writeLong(header.identity.takeId.mostSignificantBits)
                output.writeLong(header.identity.takeId.leastSignificantBits)
                output.writeLong(header.identity.generation)
                output.write(header.identity.context.copyBytes())
                output.writeLong(header.sequence)
                output.writeLong(header.startSample)
                output.writeInt(header.sampleCount)
                output.writeInt(payloadBytes)
            }
            bytes.toByteArray().also { check(it.size == RecoveryFrameLimits.AAD_BYTES) }
        }

    private fun readHeader(input: DataInputStream): Pair<RecoveryFrameHeader, Int> {
        val actualMagic = ByteArray(magic.size).also(input::readFully)
        if (!actualMagic.contentEquals(magic) || input.readInt() != RecoveryFrameLimits.VERSION) malformed()
        val tag = input.readUnsignedByte()
        val kind = RecoveryFrameKind.entries.singleOrNull { it.wireTag == tag } ?: malformed()
        val takeId = UUID(input.readLong(), input.readLong())
        val generation = input.readLong()
        val digest = RecoveryContextDigest.of(ByteArray(RecoveryContextDigest.BYTES).also(input::readFully))
        val sequence = input.readLong()
        val startSample = input.readLong()
        val samples = input.readInt()
        val payloadBytes = input.readInt()
        return RecoveryFrameHeader(RecoveryRecordIdentity(takeId, generation, digest), kind, sequence, startSample, samples) to payloadBytes
    }

    private fun validate(header: RecoveryFrameHeader, payloadBytes: Int) {
        if (header.identity.generation <= 0L || header.sequence < 0L || header.startSample < 0L || header.sampleCount < 0) malformed()
        if (header.startSample > RecoveryFrameLimits.MAX_SAMPLES || header.sampleCount.toLong() > RecoveryFrameLimits.MAX_SAMPLES) malformed()
        when (header.kind) {
            RecoveryFrameKind.CONTEXT -> {
                if (header.sequence != 0L || header.startSample != 0L || header.sampleCount != 0 || payloadBytes !in 1..RecoveryFrameLimits.CONTEXT_BYTES) malformed()
            }
            RecoveryFrameKind.AUDIO -> {
                if (payloadBytes !in 1..RecoveryFrameLimits.PAYLOAD_BYTES || payloadBytes.toLong() != header.sampleCount.toLong() * 2L) malformed()
                if (header.sequence >= RecoveryFrameLimits.MAX_AUDIO_FRAMES || header.startSample + header.sampleCount > RecoveryFrameLimits.MAX_SAMPLES) malformed()
            }
            RecoveryFrameKind.COMPLETED_CAPTURE -> {
                if (payloadBytes != 0 || header.sequence > RecoveryFrameLimits.MAX_AUDIO_FRAMES || header.startSample < header.sampleCount) malformed()
            }
        }
    }

    private fun malformed(): Nothing = throw RecoveryFrameException(RecoveryFrameFailure.MALFORMED)
    private fun unavailable(): Nothing = throw RecoveryFrameException(RecoveryFrameFailure.CRYPTO_UNAVAILABLE)
}
