package com.envi.wispr.polish

import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift Guard: the pure decoder behind `ProviderConfigurationRepository.loadPolicy` maps every mode,
 * with and without a usable selection, exactly as the screen's own `loadMode` reads the same values.
 * The repository wiring itself is a device case in `ProviderConfigurationRepositoryTest`.
 */
class PolishPolicyTest {

    private fun decode(vararg values: Pair<String, String>) = ProviderConfigurationRepository.decodePolicy(mapOf(*values))

    @Test fun everyModeWithNoSelectionMapsExactly() {
        assertEquals(PolishPolicy.Off, decode("mode" to PolishMode.OFF.name))
        assertEquals(PolishPolicy.LocalS1, decode("mode" to PolishMode.OFFLINE_S1.name))
        assertEquals(PolishPolicy.CloudUnconfigured, decode("mode" to PolishMode.PROVIDER.name))
    }

    @Test fun anAbsentOrUnreadableModeIsTheOfflineDefaultLikeLoadMode() {
        assertEquals(PolishPolicy.LocalS1, decode())
        assertEquals(PolishPolicy.LocalS1, decode("mode" to "garbage"))
        assertEquals(PolishMode.OFFLINE_S1, ProviderConfigurationRepository.decodeMode(mapOf("mode" to "garbage")))
    }

    @Test fun aProviderModeWithAValidSelectionIsCloud() {
        assertEquals(
            PolishPolicy.Cloud(Provider.CLAUDE, "claude-sonnet-5", null, SelfHostedProtocol.OPENAI_COMPATIBLE),
            decode("mode" to PolishMode.PROVIDER.name, "provider" to Provider.CLAUDE.name, "model" to "claude-sonnet-5"),
        )
    }

    @Test fun aProviderModeWithAnUnusableSelectionIsCloudUnconfiguredNotCloud() {
        assertEquals(
            PolishPolicy.CloudUnconfigured,
            decode("mode" to PolishMode.PROVIDER.name, "provider" to Provider.CLAUDE.name, "model" to ""),
        )
        assertEquals(
            PolishPolicy.CloudUnconfigured,
            decode("mode" to PolishMode.PROVIDER.name, "provider" to "NOT_A_PROVIDER", "model" to "x"),
        )
        assertEquals(
            "a self-hosted selection with no endpoint is unusable",
            PolishPolicy.CloudUnconfigured,
            decode("mode" to PolishMode.PROVIDER.name, "provider" to Provider.SELF_HOSTED_POLISH.name, "model" to "llama"),
        )
    }

    @Test fun aSelfHostedSelectionCarriesItsEndpointAndProtocol() {
        assertEquals(
            PolishPolicy.Cloud(Provider.SELF_HOSTED_POLISH, "llama3.2", "http://localhost:8080/v1", SelfHostedProtocol.OLLAMA),
            decode(
                "mode" to PolishMode.PROVIDER.name,
                "provider" to Provider.SELF_HOSTED_POLISH.name,
                "model" to "llama3.2",
                "endpoint" to "http://localhost:8080/v1",
                "protocol" to SelfHostedProtocol.OLLAMA.name,
            ),
        )
    }

    @Test fun anUnreadableStoreFailsClosedToOff() {
        assertEquals(PolishPolicy.Off, ProviderConfigurationRepository.readPolicy { error("preference store unavailable") })
    }

    @Test fun aSelectionUnderAnOffModeIsIgnored() {
        assertEquals(
            PolishPolicy.Off,
            decode("mode" to PolishMode.OFF.name, "provider" to Provider.OPENAI.name, "model" to "gpt-test"),
        )
    }
}
