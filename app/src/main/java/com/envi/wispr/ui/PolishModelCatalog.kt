package com.envi.wispr.ui

import com.envi.wispr.providers.Provider

/**
 * A curated, app-shipped model list per cloud provider. This does not validate that the user's
 * account can actually reach a given model — that check is issue #61. It only curates what the
 * picker offers as a starting point; [currentSelectionRow] keeps an already-saved model visible
 * even when it is not one of these.
 */
data class CatalogModel(
    val name: String,
    val note: String,
    val tag: String? = null,
    val cost: Int,
    val speed: Int,
    val accuracy: Int,
)

enum class ModelSort(val label: String, val groupLabel: String) {
    SUGGESTED("Suggested", "Ranked for dictation polish"),
    CHEAPEST("Cheapest", "Lowest cost first"),
    FASTEST("Fastest", "Quickest to come back"),
    ACCURATE("Most accurate", "Best output first"),
}

object PolishModelCatalog {

    private val openAi = listOf(
        // gpt-5.4-mini, gpt-5.5 and gpt-5.5-mini were removed here: OpenAI's own current model
        // documentation (developers.openai.com/api/docs/models, checked 2026-09-01) lists gpt-5.6-sol
        // /-terra/-luna as the current flagship family and no gpt-5.4 or gpt-5.5 family at all — one
        // of these (gpt-5.5-mini) was caught in code review as a guaranteed polish-call failure, and
        // sweeping the rest of this same speculative-sounding family against the live model list
        // before another round found the next one turned up the other two.
        //
        // gpt-5-chat-latest, gpt-5-mini, gpt-5-nano, gpt-4.1-nano, o4-mini and o3-mini were removed in
        // the same sweep, against OpenAI's own deprecations list (developers.openai.com/api/docs
        // /deprecations, checked 2026-09-01): gpt-5-chat-latest already shut down on 2026-07-23 — it
        // was still the "Suggested" default, so picking the FIRST recommended row would have saved a
        // model that already fails every dictation — and the other five are scheduled to shut down on
        // 2026-10-23 or 2026-12-11, weeks to months after this ships. None needed a replacement row of
        // their own: each one's niche (cheapest-and-fastest, classic-chat-cheapest, maths-leaning
        // reasoning, faster-but-lighter reasoning) is already covered by a row below that OpenAI's own
        // page names as that model's recommended replacement — gpt-5.6-luna for the two gpt-5-nano/mini
        // and gpt-4.1-nano rows, gpt-5.6-terra for o4-mini, gpt-5.6-sol for o3-mini — so adding a new
        // row for each would only have duplicated one already here.
        // "Suggested" moved to gpt-5.6-terra (OpenAI's own recommended replacement for the deprecated
        // gpt-5-chat-latest), a mid cost/speed/high-accuracy pick matching the Suggested row's profile
        // on the Gemini and Claude lists below (both cost 2 / speed 2 / accuracy 3). It is a reasoning
        // model, which is fine now: ProviderPolishClient's Responses parser (see #65) no longer assumes
        // the first `output` item is the message, so a leading `reasoning` item no longer breaks it.
        CatalogModel("gpt-5.6-terra", "Reasoning, best value for most dictation", tag = "Suggested", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("gpt-5.6-sol", "Reasoning, best on long dictation", cost = 3, speed = 1, accuracy = 3),
        // Terra and Luna's ratings verified against OpenAI's own July 2026 pricing update (per-1M-token:
        // Sol $5/$30, Terra $2/$12, Luna $0.20/$1.20) and vendor coverage describing Luna as the
        // cheapest, fastest, high-volume tier and Terra as the "similar quality, fraction of the cost"
        // drop-in — Luna was previously rated identically to Sol here, which was backwards.
        CatalogModel("gpt-5.6-luna", "Reasoning, cheapest and fastest", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gpt-4.1", "Classic chat, long context", cost = 2, speed = 2, accuracy = 2),
        CatalogModel("gpt-4.1-mini", "Classic chat, cheaper", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gpt-4o", "Classic chat, widely available", cost = 2, speed = 2, accuracy = 2),
        CatalogModel("gpt-4o-mini", "Older and cheapest", cost = 1, speed = 3, accuracy = 1),
        CatalogModel("o3", "Reasoning, deliberate", cost = 3, speed = 1, accuracy = 3),
        // o1-mini is deliberately absent: OpenAI does not support it on the Responses API, and
        // ProviderPolishClient sends every OpenAI request there (OPENAI_URL, ResponseFormat
        // .OPENAI_RESPONSES) — offering it would save successfully and then fail on every dictation.
        // Verified against OpenAI's own model list and community reports, 2026-09-01.
    )

    private val gemini = listOf(
        CatalogModel("gemini-3.6-flash", "Thinks by default", tag = "Suggested", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("gemini-3.7-flash", "Newest flash", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("gemini-3.5-flash", "Steady and quick", cost = 2, speed = 3, accuracy = 2),
        CatalogModel("gemini-3.5-flash-lite", "Cheapest flash", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gemini-3.1-flash-lite", "Cheap, previous lite", cost = 1, speed = 3, accuracy = 1),
        CatalogModel("gemini-3.1-pro-preview", "Pro tier, slower", cost = 3, speed = 1, accuracy = 3),
        CatalogModel("gemini-3-flash-preview", "First Gemini 3 flash", cost = 2, speed = 2, accuracy = 2),
        CatalogModel("gemini-2.5-flash", "Token thinking budget", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gemini-2.5-flash-lite", "Smallest 2.5", cost = 1, speed = 3, accuracy = 1),
        CatalogModel("gemini-2.5-pro", "Previous pro tier", cost = 3, speed = 1, accuracy = 3),
    )

    private val claude = listOf(
        CatalogModel("claude-sonnet-5", "Steadiest tone", tag = "Suggested", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("claude-haiku-4-5", "Fastest and cheapest", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("claude-fable-5", "Warmer rewriting", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("claude-opus-4-8", "Slowest, most accurate", cost = 3, speed = 1, accuracy = 3),
        CatalogModel("claude-opus-4-7", "Previous opus", cost = 3, speed = 1, accuracy = 3),
    )

    fun modelsFor(provider: Provider): List<CatalogModel> = when (provider) {
        Provider.OPENAI -> openAi
        Provider.GEMINI -> gemini
        Provider.CLAUDE -> claude
        Provider.SELF_HOSTED_POLISH -> emptyList()
    }

    /**
     * A saved model absent from the catalog (an older name, or a future addition) must not vanish
     * from the picker. This synthesizes a selected, untagged row for it so it stays visible.
     */
    fun currentSelectionRow(provider: Provider, savedModel: String): CatalogModel? {
        if (savedModel.isBlank()) return null
        if (modelsFor(provider).any { it.name == savedModel }) return null
        return CatalogModel(savedModel, "Currently selected", tag = null, cost = 0, speed = 0, accuracy = 0)
    }

    fun filterAndSort(
        provider: Provider,
        query: String,
        sort: ModelSort,
        savedModel: String,
    ): List<CatalogModel> {
        val normalizedQuery = query.trim().lowercase()
        val base = modelsFor(provider).filter {
            normalizedQuery.isEmpty() || it.name.lowercase().contains(normalizedQuery)
        }
        val sorted = when (sort) {
            ModelSort.SUGGESTED -> base
            ModelSort.CHEAPEST -> base.sortedWith(compareBy({ it.cost }, { -it.speed }))
            ModelSort.FASTEST -> base.sortedWith(compareBy({ -it.speed }, { it.cost }))
            ModelSort.ACCURATE -> base.sortedWith(compareBy({ -it.accuracy }, { it.cost }))
        }
        val current = currentSelectionRow(provider, savedModel)
        return if (current != null && (normalizedQuery.isEmpty() || current.name.lowercase().contains(normalizedQuery))) {
            listOf(current) + sorted
        } else {
            sorted
        }
    }
}
