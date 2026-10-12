package com.envi.wispr.processing

import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: when broken, the saved order changes an active take or selects an unsupported path. */
class ProcessingPreferenceTest {
    @Test fun cpuFirstIsHonoredWithoutRewritingMissingChoices() {
        val saved = ProcessingPreference(false, BackendOrder.of(listOf(ProcessingBackend.NPU, ProcessingBackend.CPU, ProcessingBackend.GPU)), 0)
        assertEquals(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU), saved.candidates(setOf(ProcessingBackend.CPU, ProcessingBackend.GPU), setOf(ProcessingBackend.CPU, ProcessingBackend.GPU)))
        assertEquals(listOf(ProcessingBackend.NPU, ProcessingBackend.CPU, ProcessingBackend.GPU), saved.customOrder!!.backends)
    }
    @Test fun snapshotOwnsTheOrderInsteadOfAliasingUiList() {
        val ui = mutableListOf(ProcessingBackend.CPU, ProcessingBackend.GPU, ProcessingBackend.NPU)
        val snapshot = BackendOrder.of(ui)
        ui.reverse()
        assertEquals(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU, ProcessingBackend.NPU), snapshot.backends)
    }
    @Test fun firstCustomOnCpuOnlyDoesNotPromoteAnUnseenGpuLater() {
        val selected = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU))
        assertEquals(ProcessingBackend.CPU, selected.candidates(setOf(ProcessingBackend.CPU, ProcessingBackend.GPU), setOf(ProcessingBackend.CPU, ProcessingBackend.GPU)).first())
    }
    @Test fun autoAndCustomRoundTripKeepTheEarlierExplicitOrder() {
        val chosen = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU))
        val automatic = ProcessingPreference.decode(chosen.copy(automatic = true).encode())
        assertEquals(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU, ProcessingBackend.NPU), automatic.custom(emptyList()).customOrder!!.backends)
    }
    @Test fun invalidStoredPreferencesFailRatherThanClaimingAutomatic() {
        listOf("", "2;auto;unset;0", "1;other;unset;0", "1;custom;unset;0", "1;custom;cpu,cpu,npu;0", "1;custom;cpu,tpu,npu;0", "1;auto;unset;-1").forEach { text ->
            assertTrue("Invalid preference accepted", runCatching { ProcessingPreference.decode(text) }.isFailure)
        }
    }
    @Test fun movingCpuAcrossAvailableRowsRetainsMissingNpuSlot() {
        val saved = ProcessingPreference(false, BackendOrder.of(listOf(ProcessingBackend.NPU, ProcessingBackend.GPU, ProcessingBackend.CPU)), 0)
        assertEquals(listOf(ProcessingBackend.NPU, ProcessingBackend.CPU, ProcessingBackend.GPU), saved.move(ProcessingBackend.CPU, -1, setOf(ProcessingBackend.GPU, ProcessingBackend.CPU)).customOrder!!.backends)
    }
    @Test fun manualCannotUseAnUnqualifiedProvider() {
        val saved = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU))
        assertEquals(emptyList<ProcessingBackend>(), saved.candidates(setOf(ProcessingBackend.GPU, ProcessingBackend.CPU), emptySet()))
    }
    @Test fun retryIsExplicitAndKeepsOrder() {
        val saved = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU))
        assertEquals(saved.customOrder, saved.retry().customOrder)
        assertEquals(1L, saved.retry().retryRevision)
    }
}
