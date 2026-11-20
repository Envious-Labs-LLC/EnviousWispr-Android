package com.envi.wispr.ui

import com.envi.wispr.models.ModelManifest
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.polish.EgOneMnnRuntime
import com.envi.wispr.polish.LocalPolishModel
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.capabilities
import com.envi.wispr.providers.ui.ProviderSettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#35). Protects the user-readable readiness and troubleshooting summaries for
 * speech models, polish / cloud providers, and phone storage on the Permissions / Readiness screen.
 */
class ReadinessDiagnosticsTest {

    private fun allModelsReady() = AppReadiness(
        microphoneGranted = true,
        speechModelReady = true,
        polishModelReady = true,
        onboardingPolishModelReady = true,
        eg1PolishModelReady = true,
        modelsChecked = true,
    )

    @Test
    fun speechModelRowCoversCheckingReadyAndNotReady() {
        val checking = ReadinessDiagnostics.speechModelRow(AppReadiness(modelsChecked = false))
        assertNull(checking.ready)
        assertTrue(checking.subtitle.contains("Checking"))

        val ready = ReadinessDiagnostics.speechModelRow(
            AppReadiness(modelsChecked = true, speechModelReady = true),
        )
        assertEquals(true, ready.ready)
        assertTrue(ready.subtitle.contains("ready for offline dictation"))

        val missing = ReadinessDiagnostics.speechModelRow(
            AppReadiness(modelsChecked = true, speechModelReady = false),
        )
        assertEquals(false, missing.ready)
        assertTrue(missing.subtitle.contains("isn't ready yet"))
        assertTrue(missing.subtitle.contains("Transcription"))
    }

    @Test
    fun polishAndProviderRowUsesSelectedLocalModelFlagAndCredentialStore() {
        val offRow = ReadinessDiagnostics.polishAndProviderRow(
            AppReadiness(modelsChecked = true, speechModelReady = true),
            ProviderSettingsUiState(loading = false, mode = PolishMode.OFF),
        )
        assertEquals(true, offRow.ready)
        assertTrue(offRow.subtitle.contains("basic cleanup without AI polish"))

        // Switching between S1-mini and EG-1 Micro reads each model's own readiness flag, not a stale aggregate.
        val s1OnlyReady = AppReadiness(
            modelsChecked = true,
            polishModelReady = true,
            onboardingPolishModelReady = true,
            eg1PolishModelReady = false,
        )
        val eg1Selected = ReadinessDiagnostics.polishAndProviderRow(
            s1OnlyReady,
            ProviderSettingsUiState(
                loading = false,
                mode = PolishMode.OFFLINE_S1,
                localPolishModel = LocalPolishModel.EG1_MICRO,
            ),
        )
        assertEquals(false, eg1Selected.ready)
        assertTrue(eg1Selected.subtitle.contains(LocalPolishModel.EG1_MICRO.displayName))
        assertTrue(eg1Selected.subtitle.contains("isn't ready yet"))

        val s1Selected = ReadinessDiagnostics.polishAndProviderRow(
            s1OnlyReady,
            ProviderSettingsUiState(
                loading = false,
                mode = PolishMode.OFFLINE_S1,
                localPolishModel = LocalPolishModel.S1_MINI,
            ),
        )
        assertEquals(true, s1Selected.ready)
        assertTrue(s1Selected.subtitle.contains(LocalPolishModel.S1_MINI.displayName))

        // Unconfigured cloud polish does not blame the default OpenAI provider.
        val unknownCloud = ReadinessDiagnostics.polishAndProviderRow(
            allModelsReady(),
            ProviderSettingsUiState(
                loading = false,
                mode = PolishMode.PROVIDER,
                provider = Provider.OPENAI,
                configured = false,
            ),
        )
        assertEquals(false, unknownCloud.ready)
        assertTrue(unknownCloud.subtitle.contains("Finish cloud setup in AI Polish."))
        assertFalse(unknownCloud.subtitle.contains("OpenAI"))

        // Configured cloud provider WITHOUT a stored key must NOT show Ready.
        Provider.entries.forEach { provider ->
            val capabilities = provider.capabilities()
            val readyState = if (capabilities.requiresEndpoint) {
                ProviderSettingsUiState(
                    loading = false,
                    mode = PolishMode.PROVIDER,
                    provider = provider,
                    configured = true,
                    endpoint = "https://polish.example.com/v1",
                )
            } else {
                ProviderSettingsUiState(
                    loading = false,
                    mode = PolishMode.PROVIDER,
                    provider = provider,
                    configured = true,
                    storedProviders = setOf(provider),
                )
            }
            val readyRow = ReadinessDiagnostics.polishAndProviderRow(allModelsReady(), readyState)
            assertEquals("ready $provider", true, readyRow.ready)
            assertTrue(readyRow.subtitle.contains(capabilities.displayName))

            val missingCredentialState = if (capabilities.requiresEndpoint) {
                readyState.copy(endpoint = "   ")
            } else {
                readyState.copy(storedProviders = emptySet())
            }
            val missingRow = ReadinessDiagnostics.polishAndProviderRow(allModelsReady(), missingCredentialState)
            assertEquals("missing credential $provider", false, missingRow.ready)
            val expectedMissing = if (capabilities.requiresEndpoint) "endpoint" else "API key"
            assertTrue(
                "expected '$expectedMissing' in '${missingRow.subtitle}'",
                missingRow.subtitle.contains(expectedMissing),
            )
        }
    }

    @Test
    fun setupBannerAgreesWithModeAwarePolishReadiness() {
        val noLocalPolish = AppReadiness(
            microphoneGranted = true,
            speechModelReady = true,
            polishModelReady = false,
            onboardingPolishModelReady = false,
            eg1PolishModelReady = false,
            modelsChecked = true,
        )
        val polishOff = ProviderSettingsUiState(loading = false, mode = PolishMode.OFF)
        assertFalse(setupCardShows(noLocalPolish, AutoPasteAvailability.LIVE, polishOff))
        assertEquals(
            "Auto-paste was switched off",
            calmAutoPasteNotice(noLocalPolish, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY, polishOff)?.title,
        )

        val cloudReady = ProviderSettingsUiState(
            loading = false,
            mode = PolishMode.PROVIDER,
            provider = Provider.CLAUDE,
            configured = true,
            storedProviders = setOf(Provider.CLAUDE),
        )
        assertFalse(setupCardShows(noLocalPolish, AutoPasteAvailability.LIVE, cloudReady))
        assertEquals(
            "Auto-paste was switched off",
            calmAutoPasteNotice(noLocalPolish, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY, cloudReady)?.title,
        )

        // Unfinished cloud polish stays in the Polish row/chip and does NOT raise the core setup banner.
        val cloudMissingKey = cloudReady.copy(storedProviders = emptySet())
        assertFalse(setupCardShows(noLocalPolish, AutoPasteAvailability.LIVE, cloudMissingKey))
        assertEquals(
            "Auto-paste was switched off",
            calmAutoPasteNotice(noLocalPolish, AutoPasteAvailability.SWITCHED_OFF_UNEXPECTEDLY, cloudMissingKey)?.title,
        )

        val localMissing = ProviderSettingsUiState(
            loading = false,
            mode = PolishMode.OFFLINE_S1,
            localPolishModel = LocalPolishModel.S1_MINI,
        )
        assertTrue(setupCardShows(noLocalPolish, AutoPasteAvailability.LIVE, localMissing))
        assertEquals(SetupContinue.POLISH_MODEL, setupContinue(true, noLocalPolish, localMissing))
    }

    @Test
    fun storageRowAccountsForMissingModelDownloadsAndUncheckedState() {
        val ready = allModelsReady()
        val localMode = ProviderSettingsUiState(loading = false, mode = PolishMode.OFFLINE_S1)

        assertNull(ReadinessDiagnostics.storageRow(StorageReadiness.Checking, ready, localMode).ready)
        assertNull(ReadinessDiagnostics.storageRow(StorageReadiness.Unavailable, ready, localMode).ready)

        // While models are unchecked, healthy free space does not flash a green Ready badge yet.
        val uncheckedEnough = ReadinessDiagnostics.storageRow(
            StorageReadiness.Measured(2L * 1024L * 1024L * 1024L),
            AppReadiness(modelsChecked = false),
            localMode,
        )
        assertNull(uncheckedEnough.ready)

        // With speech model missing, 128 MiB free is above the low-storage floor but below a fresh
        // Parakeet download (~802 MB), so it shows the fresh-download estimate without a false failure badge.
        val speechMissing = ready.copy(speechModelReady = false)
        val speechDownloadBytes = ModelManifest.parakeet.files.sumOf { it.expectedBytes }
        val expectedNeeded = ReadinessDiagnostics.LOW_STORAGE_THRESHOLD_BYTES + speechDownloadBytes
        assertEquals(expectedNeeded, ReadinessDiagnostics.requiredStorageBytes(speechMissing, localMode))

        val belowFreshDownload = ReadinessDiagnostics.storageRow(
            StorageReadiness.Measured(ReadinessDiagnostics.LOW_STORAGE_THRESHOLD_BYTES),
            speechMissing,
            localMode,
        )
        assertNull(belowFreshDownload.ready)
        assertTrue(belowFreshDownload.subtitle.contains("fresh download needs about"))

        val belowLowFloor = ReadinessDiagnostics.storageRow(
            StorageReadiness.Measured(ReadinessDiagnostics.LOW_STORAGE_THRESHOLD_BYTES - 1L),
            speechMissing,
            localMode,
        )
        assertEquals(false, belowLowFloor.ready)
        assertTrue(belowLowFloor.subtitle.contains("Low storage"))

        // With EG-1 Micro installed/verified, an unbuilt engine cache (~840 MB) is still counted.
        val eg1Mode = ProviderSettingsUiState(
            loading = false,
            mode = PolishMode.OFFLINE_S1,
            localPolishModel = LocalPolishModel.EG1_MICRO,
        )
        val eg1CacheOnlyNeeded = ReadinessDiagnostics.LOW_STORAGE_THRESHOLD_BYTES + ModelManifest.eg1Micro.engineCacheBytes
        assertEquals(eg1CacheOnlyNeeded, ReadinessDiagnostics.requiredStorageBytes(ready, eg1Mode))
        assertEquals(
            false,
            ReadinessDiagnostics.storageRow(
                StorageReadiness.Measured(500L * 1024L * 1024L),
                ready,
                eg1Mode,
            ).ready,
        )
        // Even while speech is missing, an installed EG-1 Micro without its cache still flags low working space at 500 MiB.
        assertEquals(
            false,
            ReadinessDiagnostics.storageRow(
                StorageReadiness.Measured(500L * 1024L * 1024L),
                speechMissing,
                eg1Mode,
            ).ready,
        )
        // Once EG-1 Micro's weight cache is built on disk (`*sync.static` present), startup still needs
        // 175,745,798 bytes (128 MiB margin + 24,750,854 prefix + 16,777,216 KV scratch).
        val builtCacheDir = java.nio.file.Files.createTempDirectory("eg1-built-cache").toFile()
        try {
            java.io.File(builtCacheDir, "weights.sync.static").writeText("ok")
            val requiredCacheBytes = EgOneMnnRuntime.requiredEngineCacheBytes(builtCacheDir)
            assertEquals(
                false,
                ReadinessDiagnostics.storageRow(
                    StorageReadiness.Measured(
                        availableBytes = 175_745_797L,
                        remainingEngineCacheBytesByModelId = mapOf(ModelManifest.eg1Micro.id to requiredCacheBytes),
                    ),
                    ready,
                    eg1Mode,
                ).ready,
            )
            assertEquals(
                true,
                ReadinessDiagnostics.storageRow(
                    StorageReadiness.Measured(
                        availableBytes = 175_745_798L,
                        remainingEngineCacheBytesByModelId = mapOf(ModelManifest.eg1Micro.id to requiredCacheBytes),
                    ),
                    ready,
                    eg1Mode,
                ).ready,
            )
        } finally {
            builtCacheDir.deleteRecursively()
        }

        // With all required models already ready (S1-mini has no separate engine cache), 128 MiB working headroom is ready.
        val atThreshold = ReadinessDiagnostics.storageRow(
            StorageReadiness.Measured(ReadinessDiagnostics.LOW_STORAGE_THRESHOLD_BYTES),
            ready,
            localMode,
        )
        assertEquals(true, atThreshold.ready)
        assertTrue(atThreshold.subtitle.contains("free on this phone"))
    }
}
