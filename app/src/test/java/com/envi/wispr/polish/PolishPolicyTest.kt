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

    @Test fun wrongStoredProcessingTypeFailsTheReadAndRetainsTheLastPolicy() {
        val reader = PolicyReader()
        val first = reader.read { emptyMap<String, Any>() }
        assertTrue(first is PolicyRead.Fresh)
        assertEquals(PolicyRead.Failed((first as PolicyRead.Fresh).policy), reader.read { mapOf("s1_processing" to 42) })
    }
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
            assertTrue("loadPolicy reads through the process reader", source.contains("fun loadPolicy(): PolicyRead = loadPolicyWith { synchronized(PROCESSING_LOCK) { preferences.all } }"))
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

    @Test fun egOneMicroForcesAutomaticCpuProcessingWhilePreservingCustomS1PreferenceAndSeparateQualification() {
        val customS1 = com.envi.wispr.processing.ProcessingPreference.DEFAULT
            .custom(listOf(com.envi.wispr.processing.ProcessingBackend.GPU))
            .encode()
        val s1Qual = com.envi.wispr.processing.ProcessingQualification
            .of("1".repeat(64), setOf(com.envi.wispr.processing.ProcessingBackend.GPU))
        val eg1Qual = com.envi.wispr.processing.ProcessingQualification
            .of("2".repeat(64), setOf(com.envi.wispr.processing.ProcessingBackend.CPU))

        val eg1Policy = decode(
            "mode" to PolishMode.OFFLINE_S1.name,
            "local_polish_model" to "eg1-micro",
            "s1_processing" to customS1,
            "s1_processing_qualification" to s1Qual.encode(),
            "eg1_processing_qualification" to eg1Qual.encode(),
        ) as PolishPolicy.LocalS1
        assertEquals(LocalPolishModel.EG1_MICRO, eg1Policy.model)
        assertEquals(com.envi.wispr.processing.ProcessingPreference.DEFAULT, eg1Policy.processing)
        assertEquals(eg1Qual, eg1Policy.qualification)

        val s1Policy = decode(
            "mode" to PolishMode.OFFLINE_S1.name,
            "local_polish_model" to "s1-mini",
            "s1_processing" to customS1,
            "s1_processing_qualification" to s1Qual.encode(),
            "eg1_processing_qualification" to eg1Qual.encode(),
        ) as PolishPolicy.LocalS1
        assertEquals(LocalPolishModel.S1_MINI, s1Policy.model)
        assertEquals(com.envi.wispr.processing.ProcessingPreference.decode(customS1), s1Policy.processing)
        assertEquals(s1Qual, s1Policy.qualification)
    }

    @Test fun egOneMicroSelectsPortableCpuWithoutEvaluatingHardwareProbeOrGpuBackends() {
        assertEquals(
            S1RuntimeKind.PORTABLE_CPU,
            S1RuntimeKind.runtimeKindFor(LocalPolishModel.EG1_MICRO) { error("hardware probe must not run for EG-1 Micro") },
        )
        assertEquals(
            S1RuntimeKind.GENIEX,
            S1RuntimeKind.runtimeKindFor(LocalPolishModel.S1_MINI) { S1RuntimeKind.GENIEX },
        )
        assertEquals(
            setOf(com.envi.wispr.processing.ProcessingBackend.CPU),
            com.envi.wispr.processing.ProcessingEnvironment.implementedBackends(S1RuntimeKind.GENIEX, LocalPolishModel.EG1_MICRO),
        )
    }
}
