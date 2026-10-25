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

    /**
     * S26, build 246: Remove on Parakeet left the card on "Checking" with no button and no way to download it
     * again. A finished removal (its work SUCCEEDED on the download chain, the control already cleared, as the
     * worker leaves it) offers Download; a finished download still verifying keeps Checking.
     */
    @Test fun aFinishedRemovalOffersDownloadAgain() {
        val removed = modelUiState(false, "SUCCEEDED", controlState = ModelDeliveryControlState.ACTIVE.name, removed = true)
        assertEquals("Missing", removed.label)
        assertEquals(ModelUiAction.DOWNLOAD, removed.action)
        assertEquals(ModelHealth.NOT_READY, removed.health)
        assertEquals(ModelUiAction.NONE, modelUiState(false, "SUCCEEDED", controlState = ModelDeliveryControlState.ACTIVE.name).action)
    }

    @Test fun repairNeededProgressOffersRepair() {
        val state = modelUiState(false, null, "REPAIR_NEEDED", reason = "integrity check failed")
        assertEquals("Repair needed", state.label)
        assertEquals(ModelUiAction.REPAIR, state.action)
    }

    /**
     * #108: an update whose download failed its check, with the previous revision still installed, must not offer
     * Repair, which deletes the model still dictating. REVERT: drop the `staleInstalled` branch before Repair.
     */
    @Test fun aFailedUpdateNeverOffersRepairOverTheInstalledModel() {
        val state = modelUiState(false, null, "REPAIR_NEEDED", reason = "integrity check failed", staleInstalled = true)
        assertEquals("Update failed", state.label)
        assertEquals(ModelUiAction.UPDATE, state.action)
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
    /**
     * #108: a queued download held to Wi-Fi while the phone is off it says so and offers mobile data beside Pause; once
     * it runs, or while it waits for something else, it never claims to wait for Wi-Fi. REVERT: drop the branch.
     */
    @Test fun aDownloadWaitingForWifiSaysSoAndOffersMobileData() {
        val waiting = modelUiState(false, "ENQUEUED", waitingForWifi = true)
        assertEquals("Waiting for Wi-Fi", waiting.label)
        assertEquals(ModelUiAction.PAUSE, waiting.action)
        assertEquals(ModelHealth.NOT_READY, waiting.health)
        assertEquals(true, waiting.offersMobileData)
        assertEquals("Queued", modelUiState(false, "ENQUEUED").label)
        assertEquals(false, modelUiState(false, "ENQUEUED").offersMobileData)
        assertEquals("Downloading", modelUiState(false, "RUNNING", waitingForWifi = true).label)
        assertEquals("Paused", modelUiState(false, "ENQUEUED", controlState = "PAUSED", waitingForWifi = true).label)
    }

    @Test fun pausedBootstrapRemainsResumableAfterTheWorkerReturns() {
        val paused = modelUiState(false, "SUCCEEDED", controlState = "PAUSED")
        assertEquals("Paused", paused.label)
        assertEquals(ModelUiAction.RESUME, paused.action)
        assertEquals(ModelHealth.NOT_READY, paused.health)
    }

}
