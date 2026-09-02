package com.envi.wispr.ui

import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails the Ladder highlights an engine that is not running, claims a key is
 * connected that is not stored, starts cloud polish with no key, saves the same key twice, starts on a
 * model the key cannot reach, or tells a user their phone model is fine when it is broken.
 */
class PolishLadderTest {
    private val cloudWithKey = ProviderSettingsUiState(loading = false, mode = PolishMode.PROVIDER, provider = Provider.OPENAI, model = "gpt-4.1-mini", configured = true, credentialStored = true)
    private val cloudNoKey = cloudWithKey.copy(credentialStored = false)
    private val selfHosted = ProviderSettingsUiState(loading = false, mode = PolishMode.PROVIDER, provider = Provider.SELF_HOSTED_POLISH, model = "local", endpoint = "https://box.local", configured = true)
    private val nothing = ProviderSettingsUiState(loading = false, mode = PolishMode.OFFLINE_S1)

    @Test fun rungOneFollowsThePersistedModeUnlessSetupIsOpen() {
        assertEquals(RungOne.OFF, PolishLadder.rungOne(PolishMode.OFF, cloudSetup = false))
        assertEquals(RungOne.THIS_PHONE, PolishLadder.rungOne(PolishMode.OFFLINE_S1, cloudSetup = false))
        assertEquals(RungOne.CLOUD, PolishLadder.rungOne(PolishMode.PROVIDER, cloudSetup = false))
        assertEquals(RungOne.CLOUD, PolishLadder.rungOne(PolishMode.OFF, cloudSetup = true))
        assertEquals(RungOne.CLOUD, PolishLadder.rungOne(PolishMode.OFFLINE_S1, cloudSetup = true))
        assertEquals(RungOne.CLOUD, PolishLadder.rungOne(PolishMode.PROVIDER, cloudSetup = true))
    }

    @Test fun cloudActivatesOnlyAConfiguredProviderWithAKey() {
        assertEquals(CloudTap.ACTIVATE, PolishLadder.cloudTap(cloudWithKey))
        assertEquals(CloudTap.ACTIVATE, PolishLadder.cloudTap(selfHosted))
        assertEquals(CloudTap.SETUP, PolishLadder.cloudTap(cloudNoKey))
        assertEquals(CloudTap.SETUP, PolishLadder.cloudTap(nothing))
    }

    @Test fun theLowerRungsShowTheTappedTileElseTheSavedCloudProviderElseNothing() {
        assertEquals(Provider.GEMINI, PolishLadder.displayedProvider(Provider.GEMINI, cloudWithKey))
        assertEquals(Provider.OPENAI, PolishLadder.displayedProvider(null, cloudWithKey))
        assertNull(PolishLadder.displayedProvider(null, selfHosted))
        assertNull(PolishLadder.displayedProvider(null, nothing))
    }

    @Test fun aBrowsedTileNeverInheritsAnotherProvidersKey() {
        assertEquals(KeyRung.CONNECTED, PolishLadder.keyRung(Provider.OPENAI, cloudWithKey, replacing = false))
        assertEquals(KeyRung.FIELD, PolishLadder.keyRung(Provider.GEMINI, cloudWithKey, replacing = false))
    }

    @Test fun connectedNeedsTheSavedProviderWithItsKeyAndNoReplace() {
        assertEquals(KeyRung.FIELD, PolishLadder.keyRung(Provider.OPENAI, cloudNoKey, replacing = false))
        assertEquals(KeyRung.FIELD, PolishLadder.keyRung(Provider.OPENAI, nothing, replacing = false))
        assertEquals(KeyRung.FIELD, PolishLadder.keyRung(Provider.OPENAI, cloudWithKey, replacing = true))
    }

    @Test fun theCheckPillReadsCheckCheckingRetry() {
        assertEquals(KeyPill("Check", false), PolishLadder.keyPill(draftBlank = true, checking = false, failed = false))
        assertEquals(KeyPill("Check", true), PolishLadder.keyPill(draftBlank = false, checking = false, failed = false))
        assertEquals(KeyPill("Checking", false), PolishLadder.keyPill(draftBlank = false, checking = true, failed = false))
        assertEquals(KeyPill("Checking", false), PolishLadder.keyPill(draftBlank = false, checking = true, failed = true))
        assertEquals(KeyPill("Retry", true), PolishLadder.keyPill(draftBlank = false, checking = false, failed = true))
        assertEquals(KeyPill("Retry", false), PolishLadder.keyPill(draftBlank = true, checking = false, failed = true))
    }

    private fun model(id: String, access: ModelAccess, recommended: Boolean = false) = DiscoveredModel(id, id, access, recommended)

    @Test fun theDefaultModelPrefersRecommendedThenAvailableThenAnySelectable() {
        // Recommended counts only when the probe reached it; a recommended model with no verdict loses to a reached one.
        assertEquals("a", PolishLadder.defaultModel(listOf(model("a", ModelAccess.AVAILABLE), model("b", ModelAccess.UNVERIFIED, recommended = true))))
        assertEquals("b", PolishLadder.defaultModel(listOf(model("a", ModelAccess.AVAILABLE), model("b", ModelAccess.AVAILABLE, recommended = true))))
        assertEquals("b", PolishLadder.defaultModel(listOf(model("a", ModelAccess.UNVERIFIED), model("b", ModelAccess.AVAILABLE))))
        assertEquals("a", PolishLadder.defaultModel(listOf(model("a", ModelAccess.UNVERIFIED), model("b", ModelAccess.UNVERIFIED))))
        // A model the key cannot reach is never the start, even when recommended.
        assertEquals("b", PolishLadder.defaultModel(listOf(model("a", ModelAccess.UNAVAILABLE, recommended = true), model("b", ModelAccess.AVAILABLE))))
        assertNull(PolishLadder.defaultModel(listOf(model("a", ModelAccess.UNAVAILABLE))))
        assertNull(PolishLadder.defaultModel(emptyList()))
    }

    @Test fun aKeyWithNoUsableModelWaitsForATypedId() {
        val empty = ProviderDiscoveryUiState(provider = Provider.OPENAI, sequence = 3, phase = ProviderDiscoveryUiState.Phase.LISTED, models = listOf(model("x", ModelAccess.UNAVAILABLE)))
        assertTrue(PolishLadder.needsTypedModel(empty, Provider.OPENAI, checkSequence = 3, draftBlank = false))
        assertFalse("nothing saves without the draft", PolishLadder.needsTypedModel(empty, Provider.OPENAI, 3, draftBlank = true))
        assertFalse("another tile", PolishLadder.needsTypedModel(empty, Provider.GEMINI, 3, false))
        assertFalse("stale sequence", PolishLadder.needsTypedModel(empty, Provider.OPENAI, 2, false))
        assertFalse("a usable model exists", PolishLadder.needsTypedModel(empty.copy(models = listOf(model("y", ModelAccess.AVAILABLE))), Provider.OPENAI, 3, false))
        assertTrue("and then save-at-accept fires instead", PolishLadder.saveAtAccept(empty.copy(models = listOf(model("y", ModelAccess.AVAILABLE))), Provider.OPENAI, 3, false, null, false))
        assertFalse("save never fires with nothing usable", PolishLadder.saveAtAccept(empty, Provider.OPENAI, 3, false, null, false))
        assertTrue(PolishLadder.typedModelValid("  my-model "))
        assertFalse(PolishLadder.typedModelValid("   "))
        assertFalse(PolishLadder.typedModelValid("bad\u0001id"))
        assertFalse(PolishLadder.typedModelValid("m".repeat(300)))
    }

    @Test fun saveAtAcceptFiresOncePerCheckAndOnlyForTheDraftsOwnListing() {
        val listed = ProviderDiscoveryUiState(
            provider = Provider.OPENAI, sequence = 7, phase = ProviderDiscoveryUiState.Phase.LISTED,
            models = listOf(model("gpt-4.1-mini", ModelAccess.AVAILABLE, recommended = true)),
        )
        assertTrue(PolishLadder.saveAtAccept(listed, Provider.OPENAI, checkSequence = 7, draftBlank = false, savedForSequence = null, false))
        assertFalse("already saved", PolishLadder.saveAtAccept(listed, Provider.OPENAI, 7, false, savedForSequence = 7, false))
        assertFalse("another tile", PolishLadder.saveAtAccept(listed, Provider.GEMINI, 7, false, null, false))
        assertFalse("stale sequence", PolishLadder.saveAtAccept(listed, Provider.OPENAI, 6, false, null, false))
        assertFalse("no check run", PolishLadder.saveAtAccept(listed, Provider.OPENAI, null, false, null, false))
        assertFalse("draft gone after rotation", PolishLadder.saveAtAccept(listed, Provider.OPENAI, 7, true, null, false))
        assertFalse("still checking", PolishLadder.saveAtAccept(listed.copy(phase = ProviderDiscoveryUiState.Phase.CHECKING), Provider.OPENAI, 7, false, null, false))
        assertFalse("nothing to start with", PolishLadder.saveAtAccept(listed.copy(models = emptyList()), Provider.OPENAI, 7, false, null, false))
        assertFalse("nothing reachable", PolishLadder.saveAtAccept(listed.copy(models = listOf(model("z", ModelAccess.UNAVAILABLE))), Provider.OPENAI, 7, false, null, false))
    }

    @Test fun saveAtAcceptWaitsForAPendingWrite() {
        val listed = ProviderDiscoveryUiState(provider = Provider.OPENAI, sequence = 2, phase = ProviderDiscoveryUiState.Phase.LISTED, models = listOf(model("m", ModelAccess.AVAILABLE)))
        assertFalse("a mode, model or remove write is in flight", PolishLadder.saveAtAccept(listed, Provider.OPENAI, 2, false, null, writePending = true))
        assertTrue("and it fires once that write completes", PolishLadder.saveAtAccept(listed, Provider.OPENAI, 2, false, null, writePending = false))
    }

    @Test fun theS1LineIsExhaustiveOverHealth() {
        fun state(health: ModelHealth, action: ModelUiAction) = ModelUiState("x", health, 0L, 0L, null, action)
        assertNull("a ready model is described by its facts row, not by a sentence repeating it", PolishLadder.s1Line(state(ModelHealth.READY, ModelUiAction.REMOVE)))
        assertTrue(PolishLadder.s1Line(state(ModelHealth.BROKEN, ModelUiAction.REPAIR))!!.startsWith("S1-mini is not working"))
        assertEquals("Download S1-mini to polish on this phone.", PolishLadder.s1Line(state(ModelHealth.NOT_READY, ModelUiAction.DOWNLOAD)))
        assertEquals("Download S1-mini to polish on this phone.", PolishLadder.s1Line(state(ModelHealth.NOT_READY, ModelUiAction.RETRY)))
        assertEquals("Getting S1-mini ready.", PolishLadder.s1Line(state(ModelHealth.NOT_READY, ModelUiAction.PAUSE)))
        assertEquals("Getting S1-mini ready.", PolishLadder.s1Line(state(ModelHealth.UNKNOWN, ModelUiAction.NONE)))
        // READY is the ONLY health allowed to say nothing; every other one names what the user does next,
        // and a blank string would render as an empty gap rather than as no row at all.
        ModelHealth.entries.forEach { health ->
            val line = PolishLadder.s1Line(state(health, ModelUiAction.NONE))
            if (health == ModelHealth.READY) assertNull(line) else assertTrue(health.name, line?.isNotBlank() == true)
        }
    }

    @Test fun theS1FactsNameThePublisherTheSizeAndTheOfflinePromise() {
        // The independent oracle is the manifest's own byte total, written here as a literal, because the
        // formatted string cannot be one: formatModelBytes formats in the DEFAULT locale on purpose, so a
        // German phone reads 461,8 MB and an English-literal assertion goes red under
        // JAVA_TOOL_OPTIONS='-Duser.language=de -Duser.country=DE' while the app is behaving correctly.
        val bytes = ModelManifest.s1.files.sumOf { it.expectedBytes }
        assertEquals(484_219_808L, bytes)
        val facts = PolishLadder.s1Facts()
        assertEquals(listOf("Superwhisper", formatModelBytes(bytes), "Offline"), facts)
        assertTrue(facts[1], facts[1].matches(Regex("""484[.,]2 MB""")))
    }

    /**
     * The unit is the claim. Android's own Formatter and the Play listing both divide by 1000, so a
     * binary divisor under a decimal label would print a number 5% smaller than every other size on the
     * phone. Each row sits just above a boundary, because a threshold is where a divisor swap shows.
     */
    @Test fun sizesAreInTheDecimalUnitsTheRestOfThePhoneUses() {
        assertTrue(formatModelBytes(484_219_808L), formatModelBytes(484_219_808L).matches(Regex("""484[.,]2 MB""")))
        assertTrue(formatModelBytes(1_000_000_000L), formatModelBytes(1_000_000_000L).matches(Regex("""1[.,]0 GB""")))
        assertTrue(formatModelBytes(1_000_000L), formatModelBytes(1_000_000L).matches(Regex("""1[.,]0 MB""")))
        assertEquals("999 KB", formatModelBytes(999_999L))
        // The binary divisor this replaced would have printed 461.8 MB, 0.9 GB and 0.9 MB for the first
        // three, so every row above changes if anyone puts 1024 back.
    }

    /**
     * Drift Guard, not product coverage. Every dot in this app is hand-written decoration, so no test can
     * say a value is RIGHT. What it can say is that every value renders: `ScoreDots` fills levels 3 down
     * to 1, so a 0 draws an empty meter and a 4 draws one that never fills, and both look like a working
     * meter reporting something false. The sweep runs over the producer — the S1 row and every
     * `ModelNotes` row together — rather than over the row this change happened to add.
     */
    @Test fun everyScoreInTheAppRendersAsAMeter() {
        val rows = ModelNotes.all
        assertTrue("an empty sweep is not a pass; the catalog had ${rows.size} rows", rows.size > 20)
        val scores = rows.map { Triple(it.name, listOf(it.cost, it.speed, it.accuracy), "catalog") } +
            listOf(Triple("S1-mini", with(PolishLadder.S1_SCORES) { listOf(cost, speed, accuracy) }, "on-phone"))
        scores.forEach { (name, values, source) ->
            values.forEach { assertTrue("$source row $name has $it, outside 1..3", it in 1..3) }
        }
    }

    @Test fun writeOutcomeWaitsCompletesOrFails() {
        assertEquals(PolishWritePolicy.Outcome.WAITING, PolishWritePolicy.outcome(null, 5, null))
        assertEquals(PolishWritePolicy.Outcome.WAITING, PolishWritePolicy.outcome(6, 5, null))
        assertEquals(PolishWritePolicy.Outcome.DONE, PolishWritePolicy.outcome(6, 6, null))
        assertEquals(PolishWritePolicy.Outcome.DONE, PolishWritePolicy.outcome(6, 7, null))
        assertEquals(PolishWritePolicy.Outcome.FAILED, PolishWritePolicy.outcome(6, 6, "could not persist"))
    }

    @Test fun snackbarShowsEachCompletedWriteOnce() {
        assertEquals(PolishSnackbarPolicy.Decision(1, true), PolishSnackbarPolicy.decide(0, 1, "OpenAI saved"))
        assertEquals(PolishSnackbarPolicy.Decision(1, false), PolishSnackbarPolicy.decide(1, 1, "OpenAI saved"))
        assertEquals(PolishSnackbarPolicy.Decision(2, false), PolishSnackbarPolicy.decide(1, 2, ""))
        // Process recreation: the remembered count exceeds the fresh view model's, so it resets silently.
        assertEquals(PolishSnackbarPolicy.Decision(0, false), PolishSnackbarPolicy.decide(4, 0, "OpenAI saved"))
    }

    @Test fun hostOfReadsTheHostOrKeepsTheWholeString() {
        assertEquals("box.local", hostOf("https://box.local:8443/v1"))
        assertEquals("not a url", hostOf("not a url"))
    }
}
