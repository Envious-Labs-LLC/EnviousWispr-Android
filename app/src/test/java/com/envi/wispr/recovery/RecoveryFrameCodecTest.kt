package com.envi.wispr.recovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID
import javax.crypto.KeyGenerator

/**
 * Product Outcome: damaged or different-take recovery bytes must not become recovered words.
 * Uses the host's real AES-GCM, not a fake encryption result. AndroidKeyStore persistence, cross-process
 * ownership and capture/replay wiring need separate device qualification; these tests do not claim it.
 */
class RecoveryFrameCodecTest {
    private val identity = RecoveryRecordIdentity(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        7L,
        RecoveryContextDigest.of(ByteArray(32) { it.toByte() }),
    )
    private val audio = RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, 2L, 1_024L, 2)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pcm = byteArrayOf(1, 0, 2, 0)

    @Test fun authenticatesExactHeaderAndPcmWithoutGivingOutItsInternalPayload() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val opened = RecoveryFrameCodec.open(frame, identity, key)

        assertEquals(audio, opened.header)
        assertEquals(4, opened.payloadBytes)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), opened.copyPayload())
        opened.copyPayload().fill(99)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), opened.copyPayload())
    }

    @Test fun writesTheIndependentlySpecifiedBigEndianHeader() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val literalHeader = hex(
            "4557524155443031" + // EWRAUD01
                "00000001" + "02" +
                "123e4567e89b12d3a456426614174000" + "0000000000000007" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "0000000000000002" + "0000000000000400" + "00000002" + "00000004",
        )
        assertEquals(93, literalHeader.size)
        assertArrayEquals(literalHeader, frame.copyOfRange(0, 93))
        assertEquals(125, frame.size)
    }

    @Test fun identicalInputsUseFreshProviderIvsAndRemainReadable() {
        val first = RecoveryFrameCodec.seal(audio, pcm, key)
        val second = RecoveryFrameCodec.seal(audio, pcm, key)
        assertFalse(first.copyOfRange(93, 105).contentEquals(second.copyOfRange(93, 105)))
        assertArrayEquals(pcm, RecoveryFrameCodec.open(first, identity, key).copyPayload())
        assertArrayEquals(pcm, RecoveryFrameCodec.open(second, identity, key).copyPayload())
    }

    @Test fun refusesAnotherTakeGenerationOrFrozenContextEvenWhenTheKeyIsTheSame() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val otherTake = identity.copy(takeId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000"))
        val otherContext = identity.copy(context = RecoveryContextDigest.of(ByteArray(32) { 99 }))
        listOf(otherTake, identity.copy(generation = 8L), otherContext).forEach { expected ->
            assertEquals(RecoveryFrameFailure.WRONG_RECORD, rejected { RecoveryFrameCodec.open(frame, expected, key) }.reason)
        }
    }

    @Test fun bindsRewrittenValidMetadataToTheAuthenticationTag() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        // Each replacement remains a valid audio header, so rejection must cross the real GCM boundary.
        val changes = listOf(76 to 3, 84 to 2)
        changes.forEach { (offset, replacement) ->
            val changed = frame.copyOf().also { it[offset] = replacement.toByte() }
            assertEquals(RecoveryFrameFailure.AUTHENTICATION_FAILED, rejected { RecoveryFrameCodec.open(changed, identity, key) }.reason)
        }
        val changedGeneration = frame.copyOf().also { it[36] = 8 }
        assertEquals(
            RecoveryFrameFailure.AUTHENTICATION_FAILED,
            rejected { RecoveryFrameCodec.open(changedGeneration, identity.copy(generation = 8L), key) }.reason,
        )
    }

    @Test fun rejectsChangedIvCiphertextAndTagWithoutPublishingPlaintextOrVendorCauses() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        listOf(93, 105, frame.lastIndex).forEach { offset ->
            val changed = frame.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val failure = rejected { RecoveryFrameCodec.open(changed, identity, key) }
            assertEquals(RecoveryFrameFailure.AUTHENTICATION_FAILED, failure.reason)
            assertEquals("Protected recording unavailable", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun uuidAndContextRemainAuthenticatedWhenExpectedIdentityMatchesTheRewrittenHeader() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val otherTake = identity.copy(takeId = UUID.fromString("133e4567-e89b-12d3-a456-426614174000"))
        val rewrittenTake = frame.copyOf().also { it[13] = 0x13 }
        assertEquals(
            RecoveryFrameFailure.AUTHENTICATION_FAILED,
            rejected { RecoveryFrameCodec.open(rewrittenTake, otherTake, key) }.reason,
        )
        val otherDigest = RecoveryContextDigest.of(ByteArray(32) { if (it == 0) 42 else it.toByte() })
        val rewrittenContext = frame.copyOf().also { it[37] = 42 }
        assertEquals(
            RecoveryFrameFailure.AUTHENTICATION_FAILED,
            rejected { RecoveryFrameCodec.open(rewrittenContext, identity.copy(context = otherDigest), key) }.reason,
        )
    }

    @Test fun wrongKeyDoesNotYieldARecoveryFrame() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(
            RecoveryFrameFailure.AUTHENTICATION_FAILED,
            rejected { RecoveryFrameCodec.open(frame, identity, wrongKey) }.reason,
        )
    }

    @Test fun rejectsTornFramesAndTrailingDataBeforeAcceptingARecord() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        listOf(0, 7, 12, 29, 69, 92, 93, 104, 124).forEach { length ->
            assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.open(frame.copyOf(length), identity, key) }.reason)
        }
        assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.open(frame + byteArrayOf(0), identity, key) }.reason)
    }

    @Test fun refusesUnknownMagicVersionAndKindInsteadOfReadingThemAsCurrentAudio() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        listOf(0 to 0, 11 to 2, 12 to 99).forEach { (offset, value) ->
            val changed = frame.copyOf().also { it[offset] = value.toByte() }
            assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.open(changed, identity, key) }.reason)
        }
    }

    @Test fun rejectsNegativeAndOversizedDeclaredPayloadBeforeAllocatingIt() {
        val frame = RecoveryFrameCodec.seal(audio, pcm, key)
        val negative = frame.copyOf().also { for (offset in 89..92) it[offset] = -1 }
        val oversized = frame.copyOf().also { it[89] = 0x7f; it[90] = -1; it[91] = -1; it[92] = -1 }
        listOf(negative, oversized).forEach { changed ->
            assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.open(changed, identity, key) }.reason)
        }
    }

    @Test fun rejectsOddPcmMismatchedCountsAndArithmeticOutsideTheCaptureCap() {
        assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.seal(audio, byteArrayOf(1, 0, 2), key) }.reason)
        listOf(
            audio.copy(sampleCount = 3),
            audio.copy(sampleCount = Int.MAX_VALUE),
            audio.copy(startSample = Long.MAX_VALUE),
            audio.copy(startSample = 9_599_999L),
            audio.copy(sequence = 586L),
            audio.copy(identity = identity.copy(generation = 0L)),
        ).forEach { invalid ->
            assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.seal(invalid, pcm, key) }.reason)
        }
    }

    @Test fun admitsAFullFrameAndTheRealLastPartialFrameAtTheTenMinuteByteCap() {
        val full = ByteArray(32_768) { 0x5a }
        val first = RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, 0L, 0L, 16_384)
        assertArrayEquals(full, RecoveryFrameCodec.open(RecoveryFrameCodec.seal(first, full, key), identity, key).copyPayload())
        val last = RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, 585L, 9_584_640L, 15_360)
        val tail = ByteArray(30_720) { 0x33 }
        assertArrayEquals(tail, RecoveryFrameCodec.open(RecoveryFrameCodec.seal(last, tail, key), identity, key).copyPayload())
    }

    @Test fun contextAndFooterHaveTheirOwnPayloadAndPositionContracts() {
        val context = RecoveryFrameHeader(identity, RecoveryFrameKind.CONTEXT, 0L, 0L, 0)
        val contextBytes = byteArrayOf(0, 1, 0, 2, 0, 3)
        assertArrayEquals(contextBytes, RecoveryFrameCodec.open(RecoveryFrameCodec.seal(context, contextBytes, key), identity, key).copyPayload())
        assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.seal(context.copy(startSample = 1L), contextBytes, key) }.reason)
        val footer = RecoveryFrameHeader(identity, RecoveryFrameKind.COMPLETED_CAPTURE, 3L, 1_026L, 2)
        val opened = RecoveryFrameCodec.open(RecoveryFrameCodec.seal(footer, byteArrayOf(), key), identity, key)
        assertEquals(0, opened.payloadBytes)
        assertEquals(footer, opened.header)
        assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.seal(footer, pcm, key) }.reason)
        assertEquals(RecoveryFrameFailure.MALFORMED, rejected { RecoveryFrameCodec.seal(footer.copy(sampleCount = 1_027), byteArrayOf(), key) }.reason)
    }

    @Test fun contextLimitAcceptsItsFullBudgetAndRefusesAnExtraByte() {
        val context = RecoveryFrameHeader(identity, RecoveryFrameKind.CONTEXT, 0L, 0L, 0)
        val payload = ByteArray(16_777_216) { 0x5a }
        val frame = RecoveryFrameCodec.seal(context, payload, key)
        val opened = RecoveryFrameCodec.open(frame, identity, key)
        assertEquals(16_777_337, frame.size)
        assertEquals(16_777_216, opened.payloadBytes)
        assertArrayEquals(payload, opened.copyPayload())
        assertEquals(
            RecoveryFrameFailure.MALFORMED,
            rejected { RecoveryFrameCodec.seal(context, ByteArray(16_777_217), key) }.reason,
        )
        val small = RecoveryFrameCodec.seal(context, byteArrayOf(1), key)
        val oversizedDeclared = small.copyOf().also { it[89] = 1; it[90] = 0; it[91] = 0; it[92] = 1 }
        assertEquals(
            RecoveryFrameFailure.MALFORMED,
            rejected { RecoveryFrameCodec.open(oversizedDeclared, identity, key) }.reason,
        )
    }

    @Test fun digestIdentityCannotBeChangedThroughCallerOwnedArrays() {
        val bytes = ByteArray(32) { 7 }
        val digest = RecoveryContextDigest.of(bytes)
        bytes.fill(9)
        digest.copyBytes().fill(11)
        assertArrayEquals(ByteArray(32) { 7 }, digest.copyBytes())
    }

    private fun rejected(block: () -> Unit): RecoveryFrameException =
        assertThrows(RecoveryFrameException::class.java, block)

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
