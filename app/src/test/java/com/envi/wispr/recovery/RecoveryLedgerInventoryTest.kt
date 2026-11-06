package com.envi.wispr.recovery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/** Drift Guard: metadata fields and explicit wire tags require a format decision when changed. */
class RecoveryLedgerInventoryTest {
    @Test fun newAllocationFactsAndTagsCannotBeSilentlyOmittedByTheVersionOneCodec() {
        assertEquals(setOf("storeId", "nextGeneration", "slots"), fields(RecoveryLedger::class.java))
        assertEquals(setOf("identity", "createdAtMs", "keyStage", "phase", "disposition", "attempt"), fields(RecoveryRecord::class.java))
        assertEquals(mapOf("NOT_ISSUED" to 0, "CREATE_MAY_HAVE_RUN" to 1, "SETTLED" to 2, "DELETE_ACKNOWLEDGED" to 3), RecoveryKeyStage.entries.associate { it.name to it.wireTag })
        assertEquals(mapOf("RESERVED" to 0, "ARMABLE" to 1, "WRITING" to 2, "SEALED" to 3, "ATTEMPT_RESERVED" to 4, "NATIVE_CONSUMED" to 5, "TEXT_PENDING" to 6, "TERMINAL" to 7), RecoveryRecordPhase.entries.associate { it.name to it.wireTag })
        assertEquals(mapOf("NONE" to 0, "NO_REPLAY" to 1, "DISCARDED" to 2, "TEXT_SAVED" to 3), RecoveryDisposition.entries.associate { it.name to it.wireTag })
    }

    private fun fields(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
}
