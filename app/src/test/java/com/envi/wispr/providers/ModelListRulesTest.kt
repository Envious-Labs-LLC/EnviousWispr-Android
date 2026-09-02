package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails an image or audio model is offered for polish, a retired alias is
 * offered, the cheap fast row loses its Recommended tag, a timed-out probe locks a row, or a Claude
 * page is dropped. The cases are the macOS discovery and classifier cases, plus the Android endpoint rule.
 */
class ModelListRulesTest {
    private fun ids(vararg names: String) = names.map { ListedModel(it, null) }

    @Test fun filterDropsWhatCannotPolishText() {
        val kept = ModelListRules.filter(
            Provider.GEMINI,
            ids(
                "gemini-3.6-flash", "gemini-2.5-flash-image", "gemini-embedding-001", "gemma-3-27b", "gemini-3-tts",
                "gemini-2.0-flash-exp-0827", "aqa", "gemini-3-flash-latest", "veo-lyria", "gemini-3.6-flash",
            ),
        ).map { it.id }
        assertEquals(listOf("gemini-3.6-flash"), kept)
    }

    @Test fun filterDropsVersionedDuplicatesAndLatestAliases() {
        val kept = ModelListRules.filter(Provider.GEMINI, ids("gemini-2.5-pro", "gemini-2.5-pro-001", "gemini-2.5-pro-002", "gemini-pro-latest")).map { it.id }
        assertEquals(listOf("gemini-2.5-pro"), kept)
    }

    @Test fun openAiKeepsChatFamiliesMinusOtherModalitiesAndChatCompletionsOnlyIds() {
        val kept = ModelListRules.filter(
            Provider.OPENAI,
            ids(
                "gpt-5.6-terra", "gpt-4o-realtime-preview", "gpt-4o-audio-preview", "gpt-4o-search-preview", "gpt-4o-transcribe",
                "o3", "o4-mini", "o1-mini", "o1-preview", "dall-e-3", "whisper-1", "text-embedding-3-small", "gpt-5.6-pro",
                "codex-mini-latest", "gpt-5-codex",
            ),
        ).map { it.id }
        assertEquals(listOf("gpt-5.6-terra", "o3", "o4-mini", "gpt-5.6-pro", "gpt-5-codex"), kept)
    }

    @Test fun filterDropsInvalidIdsAndSelfHosted() {
        val long = "g".repeat(ProviderPolishClient.MAX_MODEL_CHARS + 1)
        assertEquals(emptyList<ListedModel>(), ModelListRules.filter(Provider.CLAUDE, ids("", " ", long, "bad\u0007id")))
        assertEquals(emptyList<ListedModel>(), ModelListRules.filter(Provider.SELF_HOSTED_POLISH, ids("llama3.2")))
    }

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

    @Test fun displayNameUsesTheProvidersOrTitleCasesAnOpenAiId() {
        assertEquals("Gemini 3.6 Flash", ModelListRules.displayName(Provider.GEMINI, "gemini-3.6-flash", "Gemini 3.6 Flash"))
        assertEquals("gemini-3.6-flash", ModelListRules.displayName(Provider.GEMINI, "gemini-3.6-flash", null))
        assertEquals("Gpt 4.1 Mini", ModelListRules.displayName(Provider.OPENAI, "gpt-4.1-mini", null))
        assertEquals("claude-sonnet-5", ModelListRules.displayName(Provider.CLAUDE, "claude-sonnet-5", ""))
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

    @Test fun claudePaginationStopsContinuesOrCallsACursorMalformed() {
        assertEquals(ModelListRules.Pagination.Stop, ModelListRules.claudePagination(false, "x", emptySet()))
        assertEquals(ModelListRules.Pagination.Continue("x"), ModelListRules.claudePagination(true, "x", emptySet()))
        assertEquals(ModelListRules.Pagination.Continue("y"), ModelListRules.claudePagination(true, "y", setOf("x")))
        assertEquals(ModelListRules.Pagination.Malformed, ModelListRules.claudePagination(true, null, emptySet()))
        assertEquals(ModelListRules.Pagination.Malformed, ModelListRules.claudePagination(true, "", emptySet()))
        assertEquals(ModelListRules.Pagination.Malformed, ModelListRules.claudePagination(true, "x", setOf("x")))
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
     * Product Outcome. When this fails the user is offered a model that cannot polish their words, or is
     * denied one that can.
     *
     * A 200 alone no longer means available (#104): measured 2026-09-02, gemini-3.5-transcribe answers 200
     * with an empty string and was being listed as a good choice while silently returning the user's raw
     * text on every dictation. Whether the body carried text is decided by the client, so the 200 rows
     * here sweep that answer rather than the envelope it came from.
     */
    @Test fun probeOutcomeFollowsTheMacRulesPerProvider() {
        // What the body carried is the CLIENT's answer, produced by the same parser polish uses; the
        // envelope shapes are asserted against a real server in `ProviderPolishClientTest`. Passing it in
        // keeps this table about STATUS, which is the only thing this function decides.
        fun a(p: Provider, s: Int?, b: String? = "{}", reply: ModelListRules.ProbeReply = ModelListRules.ProbeReply.TEXT) =
            ModelListRules.probeOutcome(p, s, b, reply)
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.OPENAI, 200))
        // A 200 that carries no text is the transcribe case, and it is UNAVAILABLE, not available.
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.GEMINI, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.SELF_HOSTED_POLISH, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.SELF_HOSTED_POLISH, 200))
        // A reply that ended for any reason other than the model finishing proved NOTHING. Measured
        // 2026-09-02: gemini-2.5-pro spends every token of the probe's cap on thinking, at every cap. It
        // is a working model, so it must not be refused, and it earned no verdict, so it is not available.
        // The sweep is over the whole enum, so a fourth reading has to declare its own access.
        ModelListRules.ProbeReply.entries.forEach { reply ->
            val expected = when (reply) {
                ModelListRules.ProbeReply.TEXT -> ModelAccess.AVAILABLE
                ModelListRules.ProbeReply.NO_TEXT -> ModelAccess.UNAVAILABLE
                ModelListRules.ProbeReply.INCONCLUSIVE -> ModelAccess.UNVERIFIED
            }
            assertEquals("$reply", ProbeOutcome.Access(expected), a(Provider.GEMINI, 200, reply = reply))
        }
        // Text in the body cannot rescue a status that already refused, or the check would read a
        // successful envelope out of an error page. Every row below carries `text = true` for that reason.
        assertEquals(ProbeOutcome.KeyRejected(401), a(Provider.OPENAI, 401))
        assertEquals(ProbeOutcome.KeyRejected(400), a(Provider.GEMINI, 400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 403))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 404))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, 429))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.GEMINI, 429, "{\"error\":{\"message\":\"Resource exhausted\"}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.GEMINI, 429, "{\"error\":{\"message\":\"quota limit: 0\"}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.CLAUDE, 429))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.CLAUDE, 529))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, 503))
        // A non-key 400 is the provider answering "this model cannot serve this request", so it is a
        // refusal rather than "we could not tell": the omni models and antigravity all answer this way.
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 400))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, null, null))
    }
}
