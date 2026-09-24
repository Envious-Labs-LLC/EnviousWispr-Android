package com.envi.wispr.polish

import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.PolicyRead
import com.envi.wispr.providers.PolicyReader
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(PolishPolicy.LocalS1(S1ControlSettings.DEFAULT), decode("mode" to PolishMode.OFFLINE_S1.name))
        assertEquals(PolishPolicy.CloudUnconfigured, decode("mode" to PolishMode.PROVIDER.name))
    }

    @Test fun anAbsentOrUnreadableModeIsTheOfflineDefaultLikeLoadMode() {
        assertEquals(PolishPolicy.LocalS1(S1ControlSettings.DEFAULT), decode())
        assertEquals(PolishPolicy.LocalS1(S1ControlSettings.DEFAULT), decode("mode" to "garbage"))
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

    /** #278: an unreadable store is `Failed`, never the user's Off. MUTATION m1: the failure branch reads `Fresh(PolishPolicy.Off)`. */
    @Test fun anUnreadableStoreIsFailedNeverOff() {
        assertEquals(PolicyRead.Failed(null), PolicyReader().read { error("preference store unavailable") })
        assertEquals(PolicyRead.Fresh(PolishPolicy.Off), PolicyReader().read { mapOf("mode" to PolishMode.OFF.name) })
    }

    /** #278: a failed read carries the last policy the same reader read. MUTATION m4: the `Fresh` branch does not write `lastRead`. */
    @Test fun aFailedReadCarriesTheLastRead() {
        val reader = PolicyReader()
        assertEquals(PolicyRead.Fresh(PolishPolicy.Off), reader.read { mapOf("mode" to PolishMode.OFF.name) })
        assertEquals(PolicyRead.Failed(PolishPolicy.Off), reader.read { error("preference store unavailable") })
    }

    /**
     * #278 wiring: every `loadPolicy()` reads through the ONE process reader, so a later repository's failed read
     * still carries an earlier repository's read. MUTATION m5: `loadPolicyWith` builds a new reader per call.
     */
    @Test fun theProcessReaderIsSharedAcrossLoads() {
        ProviderConfigurationRepository.resetProcessReaderForTest()
        try {
            val stored = mapOf("mode" to PolishMode.OFF.name)
            assertEquals(PolicyRead.Fresh(PolishPolicy.Off), ProviderConfigurationRepository.loadPolicyWith { stored })
            assertEquals(PolicyRead.Failed(PolishPolicy.Off), ProviderConfigurationRepository.loadPolicyWith { error("preference store unavailable") })
            val source = java.io.File("src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt").readText()
            assertTrue("loadPolicy reads through the process reader", source.contains("fun loadPolicy(): PolicyRead = loadPolicyWith { preferences.all }"))
        } finally {
            ProviderConfigurationRepository.resetProcessReaderForTest()
        }
    }

    @Test fun storedS1PicksRideOnTheLocalPolicy() {
        assertEquals(
            PolishPolicy.LocalS1(S1ControlSettings(S1Styling.CASUAL, S1Structure.PROSE, S1Context.EMAIL)),
            decode("mode" to PolishMode.OFFLINE_S1.name, "s1_styling" to "casual", "s1_structure" to "prose", "s1_context" to "email"),
        )
    }

    @Test fun anAbsentOrUnknownS1PickReadsAsTheShippedDefaultPerAxis() {
        assertEquals(
            "one axis set, the other two absent",
            PolishPolicy.LocalS1(S1ControlSettings(S1Styling.SEMI_FORMAL, S1Structure.PROSE, S1Context.GENERAL)),
            decode("mode" to PolishMode.OFFLINE_S1.name, "s1_structure" to "prose"),
        )
        assertEquals(
            "an unknown token is never sent to the model",
            PolishPolicy.LocalS1(S1ControlSettings.DEFAULT),
            decode("mode" to PolishMode.OFFLINE_S1.name, "s1_styling" to "shouty", "s1_structure" to "LISTS", "s1_context" to ""),
        )
    }

    @Test fun s1PicksUnderACloudOrOffModeDoNotChangeThePolicy() {
        assertEquals(PolishPolicy.Off, decode("mode" to PolishMode.OFF.name, "s1_styling" to "casual"))
        assertEquals(PolishPolicy.CloudUnconfigured, decode("mode" to PolishMode.PROVIDER.name, "s1_styling" to "casual"))
    }

    @Test fun aSelectionUnderAnOffModeIsIgnored() {
        assertEquals(
            PolishPolicy.Off,
            decode("mode" to PolishMode.OFF.name, "provider" to Provider.OPENAI.name, "model" to "gpt-test"),
        )
    }
}
