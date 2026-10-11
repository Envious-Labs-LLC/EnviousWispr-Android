package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: actual polish and discovery bodies must not reject a chosen model or waste thinking.
 * Literal expectations come from Mac #3425 and the official Responses-only Pro model pages.
 * These tests establish request behavior, not live provider acceptance or measured phone speed.
 */
class ProviderModelCapabilitiesTest {
    private fun body(plan: RequestPlan): Map<*, *> = ProviderJson.parseOrNull(plan.body!!)!!.root as Map<*, *>

    private fun bodies(adapter: ProviderAdapter, model: String): List<Map<*, *>> =
        listOfNotNull(
            adapter.polishPlan(ProviderPolishRequest(adapter.provider, model, "please send the deck today", "test-key"), null),
            adapter.probePlan(model, "test-key", ProbeStyle.DEFAULT, null),
            adapter.probePlan(model, "test-key", ProbeStyle.MORE_OUTPUT, null),
        ).map(::body)

    @Test fun openAiKnownSettingsReachPolishAndBothProbeStyles() {
        val efforts = mapOf(
            "gpt-6-luna" to "none", "gpt-6-sol" to "none",
            "gpt-5.6-luna" to "none", "gpt-5.6-terra" to "none", "gpt-5.6-sol" to "none",
            "gpt-5.5" to "none", "gpt-5.4" to "none", "gpt-5.4-mini" to "none", "gpt-5.2" to "none",
            "gpt-6-astra" to "low", "gpt-6.1-sol" to "low", "gpt-5-mini" to "low",
            "o1" to "low", "o3" to "low", "o4-mini" to "low",
            "gpt-5-pro" to "high", "gpt-5.2-pro" to "medium", "gpt-5.4-pro" to "medium", "gpt-5.5-pro" to "medium",
        )
        efforts.forEach { (model, effort) ->
            listOf(model, "$model-2026-10-03").forEach { id ->
                val plans = bodies(OpenAiAdapter, id)
                assertEquals(3, plans.size)
                plans.forEach {
                    assertEquals(id, it["model"])
                    assertEquals(mapOf("effort" to effort), it["reasoning"])
                    assertEquals(false, it["store"])
                    assertFalse(it.containsKey("temperature"))
                }
            }
        }
        assertEquals(mapOf("effort" to "none"), bodies(OpenAiAdapter, "GPT-6-SOL").first()["reasoning"])
        assertEquals("GPT-6-SOL", bodies(OpenAiAdapter, "GPT-6-SOL").first()["model"])
    }

    @Test fun openAiUnknownAndNonReasoningModelsDoNotGetUnsupportedControls() {
        listOf("gpt-4.1-mini", "gpt-5-chat-latest", "gpt-6-chat-latest", "gpt-60-future", "gpt-x",
            "gpt-6-pro", "gpt-6-pro-2026-10-03", "gpt-5.1-codex", "gpt-5.1-codex-2026-10-03", "o3-pro",
        ).forEach { model -> bodies(OpenAiAdapter, model).forEach { assertFalse(model, it.containsKey("reasoning")) } }
        listOf("gpt-6", "gpt-6-future", "gpt-6.2-future").forEach { model ->
            bodies(OpenAiAdapter, model).forEach { assertEquals(model, mapOf("effort" to "low"), it["reasoning"]) }
        }
    }

    @Test fun onlyValidIsoSnapshotsInheritOpenAiEffort() {
        listOf("gpt-5.5-2026-02-31", "gpt-5.5-2025-02-29", "gpt-5.5-20261003",
            "gpt-5.5-2026-1003", "gpt-5.5-202610-03", "gpt-5.5-1999-10-03", "gpt-5.5-２０２６-10-03",
        ).forEach { id -> assertEquals(id, mapOf("effort" to "low"), bodies(OpenAiAdapter, id).first()["reasoning"]) }
        listOf("gpt-5.5-2024-02-29", "gpt-5.5-2100-10-03").forEach { id ->
            assertEquals(id, mapOf("effort" to "none"), bodies(OpenAiAdapter, id).first()["reasoning"])
        }
        assertNull(ModelId.withoutSnapshot("gpt-5.5-2100-10-03"))
    }

    @Test fun geminiUsesEachModelsVerifiedDialectOnPolishAndBothProbes() {
        val settings = mapOf(
            "gemini-3.6-flash" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3.5-flash" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3.5-flash-lite" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3.1-flash-lite" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3.1-flash-lite-preview" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3-flash-preview" to mapOf("thinkingLevel" to "minimal"),
            "gemini-3.7-flash" to mapOf("thinkingLevel" to "low"),
            "gemini-3.8-flash" to mapOf("thinkingLevel" to "low"),
            "gemini-3.1-pro-preview" to mapOf("thinkingLevel" to "low"),
            "gemini-3.1-pro-preview-customtools" to mapOf("thinkingLevel" to "low"),
            "gemini-2.5-flash" to mapOf("thinkingBudget" to 0.0),
            "gemini-2.5-flash-lite" to mapOf("thinkingBudget" to 0.0),
            "gemini-2.5-pro" to mapOf("thinkingBudget" to 128.0),
        )
        settings.forEach { (model, setting) ->
            val plans = bodies(GeminiAdapter, model)
            assertEquals(3, plans.size)
            plans.forEach { assertEquals(model, setting, (it["generationConfig"] as Map<*, *>)["thinkingConfig"]) }
            assertFalse((plans.first()["generationConfig"] as Map<*, *>).containsKey("maxOutputTokens"))
        }
        listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-3.9-flash", "gemini-3.8-flash-preview").forEach { model ->
            bodies(GeminiAdapter, model).forEach {
                assertNull(model, (it["generationConfig"] as? Map<*, *>)?.get("thinkingConfig"))
            }
        }
    }

    @Test fun claudeUsesLegalThinkingSettingsWithoutTemperatureOrModelSubstitution() {
        val settings = mapOf(
            "claude-sonnet-5-5" to ("thinking" to mapOf("type" to "between_tools")),
            "claude-opus-5-5" to ("output_config" to mapOf("effort" to "low")),
            "claude-fable-5" to ("output_config" to mapOf("effort" to "low")),
            "claude-fable-5-1" to ("output_config" to mapOf("effort" to "low")),
            "claude-haiku-5-5" to ("thinking" to mapOf("type" to "disabled")),
            "claude-haiku-4-5" to ("thinking" to mapOf("type" to "disabled")),
            "claude-sonnet-5" to ("thinking" to mapOf("type" to "disabled")),
            "claude-opus-5" to ("thinking" to mapOf("type" to "disabled")),
            "claude-future" to ("thinking" to mapOf("type" to "disabled")),
        )
        settings.forEach { (model, expected) ->
            listOf(model, "$model-20261003", "$model-2026-10-03").forEach { id ->
                val plans = bodies(ClaudeAdapter, id)
                assertEquals(2, plans.size)
                plans.forEach {
                    assertEquals(id, it["model"])
                    assertEquals(id, expected.second, it[expected.first])
                    assertFalse(it.containsKey(if (expected.first == "thinking") "output_config" else "thinking"))
                    assertFalse(it.containsKey("temperature"))
                    assertFalse(it.containsKey("fallbacks"))
                }
                assertEquals(8192.0, plans.first()["max_tokens"])
            }
        }
    }

    @Test fun liveListFiltersKeepNewTextModelsWhileDroppingOtherModalities() {
        val openAi = listOf("gpt-6-luna", "gpt-6-sol", "gpt-6-astra", "gpt-6.1-sol", "gpt-5.5-pro")
        assertEquals(openAi, OpenAiAdapter.filterModelRows((openAi + "gpt-6-audio").map { ListedModel(it, null) }).map { it.id })
        val claude = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1", "claude-haiku-5-5")
        assertEquals(claude, ClaudeAdapter.filterModelRows(claude.map { ListedModel(it, null) }).map { it.id })
        val rows = GeminiAdapter.parseModelRows("{\"models\":[{\"name\":\"models/gemini-3.8-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/gemini-3.8-flash-tts\",\"supportedGenerationMethods\":[\"generateContent\"]}]}")!!.rows
        assertEquals(listOf("gemini-3.8-flash"), GeminiAdapter.filterModelRows(rows).map { it.id })
        assertTrue(ModelId.withoutSnapshot("claude-fable-5-1") == null)
    }
}
