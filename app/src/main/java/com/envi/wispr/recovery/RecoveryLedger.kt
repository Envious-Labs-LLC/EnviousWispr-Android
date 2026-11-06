package com.envi.wispr.recovery

import java.io.IOException
import java.util.Collections
import java.util.UUID

internal enum class RecoveryKeyStage(val wireTag: Int) {
    NOT_ISSUED(0), CREATE_MAY_HAVE_RUN(1), SETTLED(2), DELETE_ACKNOWLEDGED(3),
}

internal enum class RecoveryRecordPhase(val wireTag: Int) {
    RESERVED(0), ARMABLE(1), WRITING(2), SEALED(3), ATTEMPT_RESERVED(4), NATIVE_CONSUMED(5), TEXT_PENDING(6), TERMINAL(7),
}

internal enum class RecoveryDisposition(val wireTag: Int) {
    NONE(0), NO_REPLAY(1), DISCARDED(2), TEXT_SAVED(3),
}

/** Content-free allocation identity and durable facts; no words, paths or key material. */
internal data class RecoveryRecord(
    val identity: RecoveryRecordIdentity,
    val createdAtMs: Long,
    val keyStage: RecoveryKeyStage,
    val phase: RecoveryRecordPhase,
    val disposition: RecoveryDisposition,
    val attempt: UUID?,
)

/** An immutable complete allocator census. This value alone is not a persistence acknowledgement. */
internal class RecoveryLedger private constructor(
    val storeId: UUID,
    val nextGeneration: Long,
    val slots: List<RecoveryRecord?>,
) {
    companion object {
        const val SLOT_COUNT = 2

        fun of(storeId: UUID, nextGeneration: Long, slots: List<RecoveryRecord?>): RecoveryLedger {
            if (slots.size != SLOT_COUNT || nextGeneration <= 0L) malformedLedger()
            val generations = HashSet<Long>()
            val takes = HashSet<UUID>()
            slots.filterNotNull().forEach { record ->
                val generation = record.identity.generation
                if (generation <= 0L || generation >= nextGeneration ||
                    !generations.add(generation) || !takes.add(record.identity.takeId)) malformedLedger()
                when (record.phase) {
                    RecoveryRecordPhase.RESERVED -> {
                        if (record.attempt != null || record.keyStage == RecoveryKeyStage.DELETE_ACKNOWLEDGED) malformedLedger()
                    }
                    RecoveryRecordPhase.ARMABLE, RecoveryRecordPhase.WRITING, RecoveryRecordPhase.SEALED -> {
                        if (record.attempt != null || record.keyStage != RecoveryKeyStage.SETTLED) malformedLedger()
                    }
                    RecoveryRecordPhase.ATTEMPT_RESERVED, RecoveryRecordPhase.NATIVE_CONSUMED, RecoveryRecordPhase.TEXT_PENDING -> {
                        if (record.attempt == null || record.keyStage != RecoveryKeyStage.SETTLED) malformedLedger()
                    }
                    RecoveryRecordPhase.TERMINAL -> {
                        if (record.attempt != null && record.keyStage != RecoveryKeyStage.SETTLED &&
                            record.keyStage != RecoveryKeyStage.DELETE_ACKNOWLEDGED) malformedLedger()
                    }
                }
                if ((record.phase == RecoveryRecordPhase.TERMINAL) != (record.disposition != RecoveryDisposition.NONE)) malformedLedger()
            }
            return RecoveryLedger(storeId, nextGeneration, Collections.unmodifiableList(ArrayList(slots)))
        }
    }
}

internal enum class RecoveryStoreFailure { MALFORMED, UNSUPPORTED_FORMAT, IO_UNAVAILABLE, KEY_UNAVAILABLE, CRYPTO_UNAVAILABLE, OWNERSHIP_UNCERTAIN, CAPACITY, INELIGIBLE, STALE_IDENTITY }

/** Closed local outcome only. Never retain vendor exceptions, file names or content as a cause. */
internal class RecoveryStoreException(val reason: RecoveryStoreFailure) : IOException("Protected recording unavailable")

private fun malformedLedger(): Nothing = throw RecoveryStoreException(RecoveryStoreFailure.MALFORMED)
