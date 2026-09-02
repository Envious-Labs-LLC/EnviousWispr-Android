package com.envi.wispr.ui

import com.envi.wispr.polish.LocalPolishBudget
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift Guard: the session watchdog's budget per policy. A wrong value shows on the phone as a polish
 * thrown away while it was about to finish, or as a stuck session that ends only at the watchdog.
 */
class PolishWatchdogBudgetTest {

    private val cloud = PolishPolicy.Cloud(Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE)

    @Test fun localPoliciesGetTheLocalBudgetAndCloudTheCloudBudget() {
        assertEquals(15_000L, PolishWatchdogBudget.forPolicy(PolishPolicy.LocalS1))
        assertEquals(15_000L, PolishWatchdogBudget.forPolicy(PolishPolicy.Off))
        assertEquals(15_000L, PolishWatchdogBudget.forPolicy(PolishPolicy.CloudUnconfigured))
        assertEquals(35_000L, PolishWatchdogBudget.forPolicy(cloud))
    }

    @Test fun theWatchdogSitsAboveEveryEngineSideBudget() {
        assertTrue("the engine's hard deadline must fire before the session gives up", LocalPolishBudget.HARD_MS < PolishWatchdogBudget.LOCAL_MS)
        // ProviderPolishClient.DEFAULT_OVERALL_TIMEOUT_MS is 30 000 and its companion is private; the literal is the claim.
        assertTrue("the cloud client's own cap must fire before the session gives up", 30_000L < PolishWatchdogBudget.CLOUD_MS)
    }
}
