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

    // #64: the meaning beside the label, set in the same branch, one assertion per branch.
    @Test fun everyBranchCarriesItsHealth() {
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "RUNNING").health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "ENQUEUED").health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "RUNNING", controlState = "PAUSED").health)
        assertEquals(ModelHealth.BROKEN, modelUiState(false, "FAILED", reason = "checksum mismatch").health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "CANCELLED").health)
        assertEquals(ModelHealth.BROKEN, modelUiState(false, "FAILED", staleInstalled = true).health)
        assertEquals(ModelHealth.READY, modelUiState(true, "FAILED").health)
        assertEquals(ModelHealth.READY, modelUiState(true, "SUCCEEDED").health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "SUCCEEDED", staleInstalled = true).health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, "SUCCEEDED").health)
        assertEquals(ModelHealth.READY, modelUiState(true, null).health)
        assertEquals(ModelHealth.BROKEN, modelUiState(false, null, progressState = DownloadState.REPAIR_NEEDED.name).health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, null, staleInstalled = true).health)
        assertEquals(ModelHealth.NOT_READY, modelUiState(false, null).health)
        assertEquals("Missing", modelUiState(false, null).label)
    }
}
