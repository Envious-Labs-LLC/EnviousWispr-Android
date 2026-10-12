package com.envi.wispr.processing

import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: late metadata and cached failures cannot overwrite newer real model checks. */
class ProcessingEvidenceOrderTest {
    private val gpu = ProcessingBackend.GPU
    private val context = "a".repeat(64)
    private fun stamp(n: Long) = ProcessingEvidenceStamp(1, n)
    private fun ready() = ProcessingQualification.of(context, setOf(gpu))
    @Test fun olderSuccessCannotReopenANewerFailedCheck() {
        val failed = ready().observed(emptySet(), setOf(gpu), false, 2, stamp(200))
        val late = failed.observed(setOf(gpu), emptySet(), false, 0, stamp(100))
        assertEquals(emptySet<ProcessingBackend>(), late.backends)
        assertEquals(2L, late.failedAtRevision[gpu])
    }
    @Test fun olderFailureCannotRevokeANewerSuccessfulCheck() {
        val checked = ready().observed(setOf(gpu), emptySet(), false, 2, stamp(200))
        assertEquals(setOf(gpu), checked.observed(emptySet(), setOf(gpu), false, 0, stamp(100)).backends)
    }
    @Test fun failureRevisionCannotMoveBackwardEvenAfterSuccess() {
        val failed = ready().observed(emptySet(), setOf(gpu), false, 5, stamp(100))
        val restored = failed.observed(setOf(gpu), emptySet(), false, 0, stamp(200))
        val laterFailure = restored.observed(emptySet(), setOf(gpu), false, 2, stamp(300))
        assertEquals(5L, laterFailure.failedAtRevision[gpu])
        assertFalse(gpu in laterFailure.eligible(5))
        assertTrue(gpu in laterFailure.eligible(6))
    }
    @Test fun commonFailureOrdersAgainstAPreviouslyUnpublishedSuccess() {
        val empty = ProcessingQualification.of(context, emptySet())
        val failed = empty.observed(emptySet(), emptySet(), true, 0, stamp(200))
        assertFalse(gpu in failed.observed(setOf(gpu), emptySet(), false, 0, stamp(100)).backends)
    }
}
