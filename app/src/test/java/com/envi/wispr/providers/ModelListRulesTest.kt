package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails the cheap fast row loses its Recommended tag, the list shows in the wrong
 * order, or a flaky probe erases a verdict the cache already held. The provider-specific rows (filter,
 * display name, paging, probe verdict) live in `ProviderAdapterTest` since #189.
 */
class ModelListRulesTest {

    @Test fun recommendedIsMiniNanoFlashOrHaikuWithoutADisqualifier() {
        listOf("gpt-4.1-mini", "gpt-5-nano", "gemini-3.6-flash", "claude-haiku-4-5", "gemini-2.5-flash-lite").forEach {
            assertTrue(it, ModelListRules.isRecommended(it))
        }
        listOf(
            "gpt-4o-mini-realtime-preview", "gpt-4o-mini-audio-preview", "gemini-2.5-flash-native-audio", "gemini-live-2.5-flash",
            "gemini-2.5-flash-image", "codex-mini", "gpt-4o-mini-search-preview", "gpt-4o-mini-transcribe", "gpt-5.6-terra", "o3",
            "claude-opus-4-8",
        ).forEach { assertFalse(it, ModelListRules.isRecommended(it)) }
    }

    @Test fun sortIsAvailableThenUnverifiedThenUnavailableRecommendedFirstThenByName() {
        val sorted = ModelListRules.sort(
            listOf(
                DiscoveredModel("z-locked", "Z", ModelAccess.UNAVAILABLE, false),
                DiscoveredModel("b-plain", "B", ModelAccess.AVAILABLE, false),
                DiscoveredModel("c-mini", "C", ModelAccess.AVAILABLE, true),
                DiscoveredModel("a-unverified", "A", ModelAccess.UNVERIFIED, false),
                DiscoveredModel("a-plain", "A", ModelAccess.AVAILABLE, false),
            ),
        ).map { it.id }
        assertEquals(listOf("c-mini", "a-plain", "b-plain", "a-unverified", "z-locked"), sorted)
    }

    @Test fun mergeAccessLetsAFreshUnverifiedBorrowACachedVerdictAndNothingElse() {
        val fresh = listOf(
            DiscoveredModel("a", "A", ModelAccess.UNVERIFIED, true),
            DiscoveredModel("b", "B fresh", ModelAccess.AVAILABLE, false),
            DiscoveredModel("c", "C", ModelAccess.UNVERIFIED, false),
            DiscoveredModel("d", "D", ModelAccess.UNVERIFIED, false),
        )
        val cached = listOf(
            DiscoveredModel("a", "A old", ModelAccess.UNAVAILABLE, false),
            DiscoveredModel("b", "B old", ModelAccess.UNAVAILABLE, false),
            DiscoveredModel("c", "C old", ModelAccess.UNVERIFIED, false),
            DiscoveredModel("gone", "Gone", ModelAccess.AVAILABLE, false),
        )
        val merged = ModelListRules.mergeAccess(fresh, cached)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
        assertEquals(listOf(ModelAccess.UNAVAILABLE, ModelAccess.AVAILABLE, ModelAccess.UNVERIFIED, ModelAccess.UNVERIFIED), merged.map { it.access })
        assertEquals(listOf("A", "B fresh", "C", "D"), merged.map { it.displayName })
        assertEquals(true, merged[0].recommended)
    }

    /**
     * Product Outcome. The tier words classify an id we have NEVER SEEN, on a key we have never seen, so
     * they are swept against what the three live keys actually return (measured 2026-09-02, regenerate with
     * `scripts/model-id-shapes.py`). When this fails, a specialised model is offered as a good cleanup
     * choice, or a genuinely cheap one is not.
     */
    @Test fun theTierWordsClassifyWhatTheLiveKeysActuallyReturn() {
        // The small tier of each vendor, as their lists spell it today.
        listOf("gpt-4.1-mini", "gpt-5.4-nano", "gemini-3.8-flash", "gemini-3.5-flash-lite",
               "gemini-flash-lite-latest", "claude-haiku-4-5-20251001")
            .forEach { assertTrue(it, ModelListRules.isRecommended(it)) }

        // OpenAI's 5.6 generation dropped tier words for CODENAMES, which is why the badge no longer rests
        // on this set: none of these can be classified here, and `luna` is the cheap one.
        listOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol")
            .forEach { assertFalse(it, ModelListRules.isRecommended(it)) }

        // The big tiers, which must never be badged.
        listOf("gpt-5.4-pro", "gpt-5.5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5",
               "gemini-2.5-pro", "gemini-3.1-pro-preview")
            .forEach { assertFalse(it, ModelListRules.isRecommended(it)) }

        // Specialised variants that carry a tier word and would polish badly. `omni` is the one the
        // 2026-09-02 sweep added: both omni models are flash-named and were being called good.
        listOf("gemini-omni-1.1-flash", "gemini-omni-flash-preview", "gemini-2.5-flash-image",
               "gemini-3.1-flash-tts-preview", "gpt-4o-mini-transcribe", "gpt-4o-mini-tts",
               "gpt-4o-mini-search-preview", "gpt-5.1-codex-mini", "nano-banana-pro-preview")
            .forEach { assertFalse(it, ModelListRules.isRecommended(it)) }
    }
}
