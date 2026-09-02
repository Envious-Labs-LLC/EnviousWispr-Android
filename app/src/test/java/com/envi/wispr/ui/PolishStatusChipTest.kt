package com.envi.wispr.ui

import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

/** Product Outcome: when this fails, the user sees the wrong colour dot, or the wrong name, in the app bar. */
class PolishStatusChipTest {

    private val readyS1 = ModelUiState(label = "Ready", health = ModelHealth.READY, action = ModelUiAction.REMOVE)
    private val failedS1 = ModelUiState(label = "Failed", health = ModelHealth.BROKEN, action = ModelUiAction.RETRY)
    private val downloadingS1 = ModelUiState(label = "Downloading", health = ModelHealth.NOT_READY, action = ModelUiAction.PAUSE)
    private val repairNeededS1 = ModelUiState(label = "Repair needed", health = ModelHealth.BROKEN, action = ModelUiAction.REPAIR)
    private val updateFailedS1 = ModelUiState(label = "Update failed", health = ModelHealth.BROKEN, action = ModelUiAction.UPDATE)
    private val cancelledS1 = ModelUiState(label = "Cancelled", health = ModelHealth.NOT_READY, action = ModelUiAction.RETRY)
    private val updateAvailableS1 = ModelUiState(label = "Update available", health = ModelHealth.NOT_READY, action = ModelUiAction.UPDATE)

    @Test fun offModeShowsGreyDotRegardlessOfS1State() {
        val settings = ProviderSettingsUiState(mode = PolishMode.OFF)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, failedS1).dot)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, readyS1).dot)
    }

    @Test fun thisPhoneFailedShowsRedDot() {
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        val chip = polishStatusChip(settings, failedS1)
        assertEquals(PolishStatusDot.RED, chip.dot)
        assertEquals(PolishStatusKind.LOCAL, chip.kind)
    }

    @Test fun thisPhoneReadyShowsGreenDot() {
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.GREEN, polishStatusChip(settings, readyS1).dot)
    }

    @Test fun thisPhoneDownloadingShowsNeutralNotRed() {
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, downloadingS1).dot)
    }

    @Test fun thisPhoneRepairNeededShowsRedDot() {
        // Regression for a real bug caught in code review: matching only the literal "Failed" label
        // sent this equally-broken state to the neutral dot.
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.RED, polishStatusChip(settings, repairNeededS1).dot)
    }

    @Test fun thisPhoneUpdateFailedShowsRedDot() {
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.RED, polishStatusChip(settings, updateFailedS1).dot)
    }

    @Test fun thisPhoneCancelledShowsNeutralNotRed() {
        // Same RETRY action as Failed, but the user's own choice, not a broken state — proves the
        // classification is by label, not by action, since action alone cannot tell these apart.
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, cancelledS1).dot)
    }

    @Test fun thisPhoneUpdateAvailableShowsNeutralNotRed() {
        // Same UPDATE action as Update failed, but optional, not broken.
        val settings = ProviderSettingsUiState(mode = PolishMode.OFFLINE_S1)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, updateAvailableS1).dot)
    }

    @Test fun cloudModeNotYetConfiguredShowsANeutralNotGreenNotRedState() {
        val settings = ProviderSettingsUiState(mode = PolishMode.PROVIDER, configured = false)
        assertEquals(PolishStatusDot.NEUTRAL, polishStatusChip(settings, readyS1).dot)
    }

    @Test fun cloudConfiguredShowsGreenWithProviderAndModel() {
        val settings = ProviderSettingsUiState(
            mode = PolishMode.PROVIDER,
            provider = Provider.OPENAI,
            model = "gpt-5.4-mini",
            configured = true,
            credentialStored = true,
        )
        val chip = polishStatusChip(settings, readyS1)
        assertEquals(PolishStatusDot.GREEN, chip.dot)
        assertEquals(PolishStatusKind.OPENAI, chip.kind)
        assertEquals("gpt-5.4-mini", chip.label)
    }

    @Test fun cloudConfiguredWithNoStoredKeyShowsRedNotGreen() {
        // Same class as `PolishScreenProviderTilesTest.configuredButNoStoredKeyStartsAtKeyEntryNotConnected`:
        // `configured` alone does not mean a key exists, and this is a known-broken state, not a
        // neutral one — found while enumerating that class in code review, 2026-09-01.
        val settings = ProviderSettingsUiState(
            mode = PolishMode.PROVIDER,
            provider = Provider.OPENAI,
            model = "gpt-5.4-mini",
            configured = true,
            credentialStored = false,
        )
        val chip = polishStatusChip(settings, readyS1)
        assertEquals(PolishStatusDot.RED, chip.dot)
        assertEquals(PolishStatusKind.OPENAI, chip.kind)
    }

    @Test fun selfHostedConfiguredStillShowsAGreenBadgeEvenThoughItIsHiddenFromTheScreen() {
        val settings = ProviderSettingsUiState(
            mode = PolishMode.PROVIDER,
            provider = Provider.SELF_HOSTED_POLISH,
            model = "llama",
            configured = true,
        )
        val chip = polishStatusChip(settings, readyS1)
        assertEquals(PolishStatusDot.GREEN, chip.dot)
        assertEquals(PolishStatusKind.SELF_HOSTED, chip.kind)
    }

    @Test fun badgeIgnoresS1StateEntirelyWhenModeIsCloud() {
        val settings = ProviderSettingsUiState(
            mode = PolishMode.PROVIDER,
            provider = Provider.CLAUDE,
            model = "claude-sonnet-5",
            configured = true,
        )
        assertEquals(polishStatusChip(settings, readyS1), polishStatusChip(settings, failedS1))
    }
}
