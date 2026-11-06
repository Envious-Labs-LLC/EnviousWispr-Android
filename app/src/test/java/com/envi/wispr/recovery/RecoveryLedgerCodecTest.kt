package com.envi.wispr.recovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.UUID

/** Product Outcome: unknown or damaged allocation metadata must not authorize replacement keys. */
class RecoveryLedgerCodecTest {
    private val store = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val take = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val digest = RecoveryContextDigest.of(ByteArray(32) { it.toByte() })
    private val reserved = RecoveryRecord(RecoveryRecordIdentity(take, 1L, digest), Long.MIN_VALUE, RecoveryKeyStage.NOT_ISSUED, RecoveryRecordPhase.RESERVED, RecoveryDisposition.NONE, null)

    @Test fun matchesAnIndependentEmptyLedgerByteAndChecksumFixture() {
        val literal = hex(
            "4557524c45443031" + "0001" + "11111111111111111111111111111111" + "0000000000000001" + "0000" +
                "bd733961",
        )
        assertEquals(40, literal.size)
        assertArrayEquals(literal, RecoveryLedgerCodec.encode(RecoveryLedger.of(store, 1L, listOf(null, null))))
        val decoded = RecoveryLedgerCodec.decode(literal)
        assertEquals(store, decoded.storeId)
        assertEquals(1L, decoded.nextGeneration)
        assertEquals(listOf(null, null), decoded.slots)
    }

    @Test fun preservesBothFullSlotsAndTheMaximumGenerationWithoutLosingSpentAttempts() {
        val first = reserved.copy(
            identity = reserved.identity.copy(generation = Long.MAX_VALUE - 2),
            keyStage = RecoveryKeyStage.SETTLED, phase = RecoveryRecordPhase.TERMINAL,
            disposition = RecoveryDisposition.DISCARDED, attempt = UUID(3L, 4L),
        )
        val second = first.copy(identity = first.identity.copy(takeId = UUID(5L, 6L), generation = Long.MAX_VALUE - 1), createdAtMs = Long.MAX_VALUE, keyStage = RecoveryKeyStage.DELETE_ACKNOWLEDGED)
        val ledger = RecoveryLedger.of(store, Long.MAX_VALUE, listOf(first, second))
        val encoded = RecoveryLedgerCodec.encode(ledger)
        assertEquals(208, encoded.size)
        val decoded = RecoveryLedgerCodec.decode(encoded)
        assertEquals(store, decoded.storeId)
        assertEquals(Long.MAX_VALUE, decoded.nextGeneration)
        assertEquals(listOf(first, second), decoded.slots)
        assertArrayEquals(encoded, RecoveryLedgerCodec.encode(decoded))
    }

    @Test fun ownsItsSlotListRatherThanFollowingCallerMutations() {
        val slots = mutableListOf<RecoveryRecord?>(reserved, null)
        val ledger = RecoveryLedger.of(store, 2L, slots)
        slots[0] = null
        assertEquals(listOf(reserved, null), ledger.slots)
        assertThrows(UnsupportedOperationException::class.java) { (ledger.slots as MutableList).clear() }
    }

    @Test fun refusesDuplicateIdentitiesInvalidGenerationsAndWrongSlotCounts() {
        listOf(
            listOf(reserved), listOf(reserved, null, null),
            listOf(reserved, reserved.copy(identity = reserved.identity.copy(generation = 2L))),
            listOf(reserved, reserved.copy(identity = reserved.identity.copy(takeId = UUID(8L, 9L)))),
            listOf(reserved.copy(identity = reserved.identity.copy(generation = 0L)), null),
            listOf(reserved.copy(identity = reserved.identity.copy(generation = 3L)), null),
        ).forEach { slots -> malformed { RecoveryLedger.of(store, 3L, slots) } }
        malformed { RecoveryLedger.of(store, 0L, listOf(null, null)) }
        malformed { RecoveryLedger.of(store, Long.MIN_VALUE, listOf(null, null)) }
    }

    @Test fun onlyAdmitsTheExplicitKeyPhaseDispositionAndAttemptRelationships() {
        // Literal truth table: these are dependent state axes, not independent input permutations.
        val unspent = setOf(
            RecoveryRecordPhase.RESERVED to RecoveryKeyStage.NOT_ISSUED,
            RecoveryRecordPhase.RESERVED to RecoveryKeyStage.CREATE_MAY_HAVE_RUN,
            RecoveryRecordPhase.RESERVED to RecoveryKeyStage.SETTLED,
            RecoveryRecordPhase.ARMABLE to RecoveryKeyStage.SETTLED,
            RecoveryRecordPhase.WRITING to RecoveryKeyStage.SETTLED,
            RecoveryRecordPhase.SEALED to RecoveryKeyStage.SETTLED,
        )
        val spent = setOf(RecoveryRecordPhase.ATTEMPT_RESERVED, RecoveryRecordPhase.NATIVE_CONSUMED, RecoveryRecordPhase.TEXT_PENDING)
        RecoveryKeyStage.entries.forEach { key -> RecoveryRecordPhase.entries.forEach { phase ->
            RecoveryDisposition.entries.forEach { disposition -> listOf(null, UUID(3L, 4L)).forEach { attempt ->
                val terminal = phase == RecoveryRecordPhase.TERMINAL && disposition in setOf(RecoveryDisposition.NO_REPLAY, RecoveryDisposition.DISCARDED, RecoveryDisposition.TEXT_SAVED)
                val terminalAttempt = attempt == null || key in setOf(RecoveryKeyStage.SETTLED, RecoveryKeyStage.DELETE_ACKNOWLEDGED)
                val ordinary = disposition == RecoveryDisposition.NONE && attempt == null && (phase to key) in unspent
                val attempted = disposition == RecoveryDisposition.NONE && attempt != null && phase in spent && key == RecoveryKeyStage.SETTLED
                val record = reserved.copy(keyStage = key, phase = phase, disposition = disposition, attempt = attempt)
                if (terminal && terminalAttempt || ordinary || attempted) {
                    val decoded = RecoveryLedgerCodec.decode(RecoveryLedgerCodec.encode(RecoveryLedger.of(store, 2L, listOf(record, null))))
                    assertEquals(record, decoded.slots[0])
                } else malformed { RecoveryLedger.of(store, 2L, listOf(record, null)) }
            } }
        } }
    }

    @Test fun refusesEveryTruncatedOrCorruptedRecordAndOversizedOrTrailingMetadata() {
        val encoded = RecoveryLedgerCodec.encode(RecoveryLedger.of(store, 2L, listOf(reserved, null)))
        for (length in encoded.indices) malformed { RecoveryLedgerCodec.decode(encoded.copyOf(length)) }
        for (offset in encoded.indices) malformed { RecoveryLedgerCodec.decode(encoded.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }) }
        malformed { RecoveryLedgerCodec.decode(ByteArray(209)) }
        malformed { RecoveryLedgerCodec.decode(rechecksum(encoded.copyOfRange(0, encoded.size - 4) + byteArrayOf(0))) }
    }

    @Test fun rejectsUnknownVersionTagsAndImpossibleStateEvenWithACorrectChecksum() {
        val encoded = RecoveryLedgerCodec.encode(RecoveryLedger.of(store, 2L, listOf(reserved, null)))
        val unknown = assertThrows(RecoveryStoreException::class.java) { RecoveryLedgerCodec.decode(changeBody(encoded) { it[9] = 2 }) }
        assertEquals(RecoveryStoreFailure.UNSUPPORTED_FORMAT, unknown.reason)
        listOf(34, 99, 100, 101, 102).forEach { offset ->
            malformed { RecoveryLedgerCodec.decode(changeBody(encoded) { it[offset] = 99 }) }
        }
        malformed { RecoveryLedgerCodec.decode(changeBody(encoded) { ByteBuffer.wrap(it).putLong(26, 1L) }) }
        malformed { RecoveryLedgerCodec.decode(changeBody(encoded) { it[100] = 4 }) }
        // A valid changed timestamp confirms that the rechecksummed fixture reaches semantic parsing.
        val changed = RecoveryLedgerCodec.decode(changeBody(encoded) { ByteBuffer.wrap(it).putLong(91, 42L) })
        assertEquals(42L, changed.slots[0]!!.createdAtMs)
        assertEquals(RecoveryKeyStage.NOT_ISSUED, changed.slots[0]!!.keyStage)
    }

    private fun changeBody(encoded: ByteArray, change: (ByteArray) -> Unit): ByteArray =
        rechecksum(encoded.copyOfRange(0, encoded.size - 4).also(change))

    private fun rechecksum(body: ByteArray): ByteArray = body + ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array()

    private fun malformed(block: () -> Unit) {
        val error = assertThrows(RecoveryStoreException::class.java, block)
        assertEquals(RecoveryStoreFailure.MALFORMED, error.reason)
        assertEquals("Protected recording unavailable", error.message)
        assertEquals(null, error.cause)
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
