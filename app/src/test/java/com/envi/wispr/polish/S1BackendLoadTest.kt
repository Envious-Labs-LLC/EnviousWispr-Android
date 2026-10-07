package com.envi.wispr.polish

import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: a failed preferred construction must move to the next candidate and report its winner. */
class S1BackendLoadTest {
    @Test fun aRealCandidateFailureAttemptsCpuAndCapturesTheGpuFailure() {
        val calls = mutableListOf<String>()
        val native = Any()
        val loaded = loadFirstS1Backend(listOf("gpu", "cpu")) { unit -> calls += unit; if (unit == "gpu") error("GPU construction failed"); native }
        assertEquals(listOf("gpu", "cpu"), calls); assertSame(native, loaded.runtime)
        assertEquals("cpu", loaded.backend); assertEquals("gpu", loaded.failedCodes)
    }
    @Test fun allFailuresRetainTheirIndividualCandidatesWithoutClaimingAWinner() {
        val error = runCatching { loadFirstS1Backend(listOf("cpu", "gpu")) { error("Cannot construct") } }.exceptionOrNull() as S1BackendLoadException
        assertEquals("cpu,gpu", error.failedCodes)
    }
}
