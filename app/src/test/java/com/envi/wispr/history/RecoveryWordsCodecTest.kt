package com.envi.wispr.history

import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.polish.PolishEngineLabels
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Product Outcome: a text-rescue restart preserves words/provenance and refuses corrupt or misattributed text. */
class RecoveryWordsCodecTest {
    private val take = "ed1a8a60-21f8-4594-acaf-797c1813e7f4"
    private val record = RecoveredWordsRecord(TranscriptEntity(
        originalText = "raw\u0000words\uD800", finalText = "Finished \uD83D\uDE42 words\uDC00.",
        createdAtMs = 123L, durationMs = 128L, speechEngine = "Parakeet Ultra",
        polishEngine = PolishEngineLabels.OFF, polishLatencyMs = 0L,
        insertionResult = InsertionResults.HISTORY_ONLY, recovered = true, takeId = take, asrMs = 42L,
        recoveryStartSample = 320L, recoveryPartial = true, recoveryModelIdentity = "synthetic-model/α/v1",
    ), 2_048L)

    @Test fun restartPreservesExactWordsAndAllRecordedRecoveryFacts() {
        val bytes = RecoveryWordsCodec.encode(record)
        assertArrayEquals(byteArrayOf(0x45, 0x57, 0x52, 0x57, 0x52, 0x44, 0x30, 0x31, 0, 1), bytes.copyOf(10))
        assertEquals(record, RecoveryWordsCodec.decode(bytes, take))
    }

    @Test fun aChangedTextByteCannotBecomeDifferentRecoveredWords() {
        val bytes = RecoveryWordsCodec.encode(record).also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertThrows(RecoveryWordsException::class.java) { RecoveryWordsCodec.decode(bytes, take) }
    }

    @Test fun aRenamedFileCannotAttributeWordsToAnotherTake() {
        assertThrows(RecoveryWordsException::class.java) {
            RecoveryWordsCodec.decode(RecoveryWordsCodec.encode(record), "f13f7db6-dcbe-4783-ab57-70876a88c5d2")
        }
    }

    /** Drift Guard: version and processing/destination tags have no legacy or permissive fallback. */
    @Test fun unknownVersionAiOrInsertionTagsAreRefusedEvenWithAValidChecksum() {
        for (offset in listOf(9, 59, 60)) {
            val bytes = RecoveryWordsCodec.encode(record)
            bytes[offset] = 2
            val size = bytes.size - 4
            ByteBuffer.wrap(bytes, size, 4).putInt(CRC32().apply { update(bytes, 0, size) }.value.toInt())
            assertThrows(RecoveryWordsException::class.java) { RecoveryWordsCodec.decode(bytes, take) }
        }
    }
}
