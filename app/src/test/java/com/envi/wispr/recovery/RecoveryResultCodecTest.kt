package com.envi.wispr.recovery

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** Product Outcome: wrong identity, partial flags or text must never become a misleading recovered History row. */
class RecoveryResultCodecTest {
    private val ref = RecoveryRecordRef(UUID.fromString("22222222-2222-4222-8222-222222222222"), 0,
        RecoveryRecordIdentity(UUID.fromString("11111111-1111-4111-8111-111111111111"), 7L,
            RecoveryContextDigest.of(ByteArray(32) { it.toByte() })))
    private val replay = RecoveryReplayRef(ref, UUID.fromString("33333333-3333-4333-8333-333333333333"))
    private val model = RecoveryModelIdentity("onnxruntime", "parakeet-ultra",
        "3b80a92332b5d34c364a35a880ef049c7fede9f2", "parakeet-tdt-android-v1", "Parakeet Ultra")
    private val value = RecoveryResult(replay, 17L, 32_000L, true, model, "Hello.\uD800\u0000नमस्ते")
    // Independent fixed wire fixture, authored with integer/UTF-16 primitives outside the production codec.
    private val literal = "EWR-RESULT-1:RVdSUkVTMDEAAQAAAAAiIiIiIiJCIoIiIiIiIiIiAAAAAAAAAAczMzMzMzNDM4MzMzMzMzMzAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8AAAAAAAAAEQAAAAAAAH0AAQAAAAsAbwBuAG4AeAByAHUAbgB0AGkAbQBlAAAADgBwAGEAcgBhAGsAZQBlAHQALQB1AGwAdAByAGEAAAAoADMAYgA4ADAAYQA5ADIAMwAzADIAYgA1AGQAMwA0AGMAMwA2ADQAYQAzADUAYQA4ADgAMABlAGYAMAA0ADkAYwA3AGYAZQBkAGUAOQBmADIAAAAXAHAAYQByAGEAawBlAGUAdAAtAHQAZAB0AC0AYQBuAGQAcgBvAGkAZAAtAHYAMQAAAA4AUABhAHIAYQBrAGUAZQB0ACAAVQBsAHQAcgBhAAAADgBIAGUAbABsAG8ALtgAAAAJKAkuCTgJTQkkCUc="

    @Test fun theLiteralEnvelopePreservesWordsAndEveryVerifiedFact() {
        assertEquals(literal, RecoveryResultCodec.encode(value))
        val decoded = RecoveryResultCodec.decode(literal, replay)
        assertEquals("Hello.\uD800\u0000नमस्ते", decoded.rawText)
        assertEquals(17L, decoded.firstSample)
        assertEquals(32_000L, decoded.sampleCount)
        assertEquals(2_000L, decoded.durationMs)
        assertTrue(decoded.completedCapture)
        assertTrue("a late attachment is partial even with a complete footer", decoded.partial)
        assertEquals("onnxruntime", decoded.model.engineId)
        assertEquals("parakeet-ultra", decoded.model.modelId)
        assertEquals("3b80a92332b5d34c364a35a880ef049c7fede9f2", decoded.model.pinnedRevision)
        assertEquals("parakeet-tdt-android-v1", decoded.model.recipeId)
        assertEquals("Parakeet Ultra", decoded.model.displayName)
    }

    @Test fun everyRecordBindingOperandRejectsADifferentAttempt() {
        val other = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val wrong = listOf(
            replay.copy(record = ref.copy(slot = 1)),
            replay.copy(record = ref.copy(storeId = other)),
            replay.copy(record = ref.copy(identity = ref.identity.copy(generation = 8L))),
            replay.copy(attempt = other),
            replay.copy(record = ref.copy(identity = ref.identity.copy(context = RecoveryContextDigest.of(ByteArray(32))))),
        )
        wrong.forEach { expected -> assertThrows(RecoveryResultException::class.java) { RecoveryResultCodec.decode(literal, expected) } }
    }

    @Test fun completeAndPartialFactsComeFromTheActualOffsetAndFooter() {
        val complete = RecoveryResultCodec.decode(RecoveryResultCodec.encode(value.copy(firstSample = 0L)), replay)
        assertFalse(complete.partial)
        val torn = RecoveryResultCodec.decode(RecoveryResultCodec.encode(value.copy(firstSample = 0L, completedCapture = false)), replay)
        assertTrue(torn.partial)
    }

    @Test fun malformedRangesLengthsAndFooterCannotProduceAHistoryRow() {
        fun changed(mutate: (ByteBuffer) -> Unit): String {
            val bytes = Base64.getDecoder().decode(literal.substringAfter(':'))
            mutate(ByteBuffer.wrap(bytes))
            return "EWR-RESULT-1:" + Base64.getEncoder().encodeToString(bytes)
        }
        val wrong = listOf(
            "ordinary raw speech",
            changed { it.putLong(86, -1L) },
            changed { it.putLong(94, Long.MAX_VALUE) },
            changed { it.put(102, 2.toByte()) },
            changed { it.putInt(103, Int.MAX_VALUE) },
            literal + "A",
        )
        wrong.forEach { text -> assertThrows(RecoveryResultException::class.java) { RecoveryResultCodec.decode(text, replay) } }
    }

    @Test fun unsupportedModelFactsAndOversizeWordsAreRefusedWithoutTruncation() {
        val unsupported = RecoveryResultCodec.encode(value.copy(model = model.copy(modelId = "another-model")))
        assertThrows(RecoveryResultException::class.java) { RecoveryResultCodec.decode(unsupported, replay) }
        assertThrows(RecoveryResultException::class.java) { RecoveryResultCodec.encode(value.copy(rawText = "x".repeat(65_537))) }
        assertThrows(RecoveryResultException::class.java) { RecoveryResultCodec.decode("EWR-RESULT-1:" + "A".repeat(220_001), replay) }
    }
}
