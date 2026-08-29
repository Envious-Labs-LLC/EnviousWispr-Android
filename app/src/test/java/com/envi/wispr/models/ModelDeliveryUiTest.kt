package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDeliveryUiTest {
    @Test fun runningWorkShowsProgressAndPause() {
        val state = modelUiState(false, "RUNNING", "DOWNLOADING", 50L, 100L)
        assertEquals(ModelUiAction.PAUSE, state.action)
        assertEquals("Downloading", state.label)
        assertEquals(50L, state.bytes)
        assertEquals(100L, state.total)
    }

    @Test fun failedOrCancelledWorkOffersRetryAndReason() {
        val failed = modelUiState(false, "FAILED", reason = "checksum mismatch")
        val cancelled = modelUiState(false, "CANCELLED")
        assertEquals(ModelUiAction.RETRY, failed.action)
        assertEquals("checksum mismatch", failed.reason)
        assertEquals(ModelUiAction.RETRY, cancelled.action)
    }

    @Test fun removeIsAvailableOnlyForVerifiedReadyModel() {
        assertEquals(ModelUiAction.REMOVE, modelUiState(true, null).action)
        assertEquals(ModelUiAction.DOWNLOAD, modelUiState(false, null).action)
        assertEquals(ModelUiAction.NONE, modelUiState(false, "SUCCEEDED").action)
    }

    @Test fun repairNeededProgressOffersRepair() {
        val state = modelUiState(false, null, "REPAIR_NEEDED", reason = "integrity check failed")
        assertEquals("Repair needed", state.label)
        assertEquals(ModelUiAction.REPAIR, state.action)
    }

    @Test fun persistedPauseOffersResume() {
        val state = modelUiState(false, "CANCELLED", controlState = ModelDeliveryControlState.PAUSED.name)
        assertEquals("Paused", state.label)
        assertEquals(ModelUiAction.RESUME, state.action)
    }

    @Test fun staleAdmittedModelOffersUpdate() {
        val state = modelUiState(false, "SUCCEEDED", staleInstalled = true)
        assertEquals("Update available", state.label)
        assertEquals(ModelUiAction.UPDATE, state.action)
    }
}
