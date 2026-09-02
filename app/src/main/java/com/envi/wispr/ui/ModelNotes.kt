package com.envi.wispr.ui

import com.envi.wispr.providers.Provider

/**
 * Hand-written DECORATION for model ids the live list (#84) returns: a one-line note and the cost,
 * speed and accuracy dots the founder's design carries. This is no longer a list of what exists; the
 * provider answers that. An id absent here shows without a note or dots.
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

object ModelNotes {
    private val openAi = listOf(
        // Ratings verified against OpenAI's July 2026 pricing (Sol $5/$30, Terra $2/$12, Luna $0.20/$1.20 per 1M).
        CatalogModel("gpt-5.6-terra", "Reasoning, best value for most dictation", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("gpt-5.6-sol", "Reasoning, best on long dictation", cost = 3, speed = 1, accuracy = 3),
        CatalogModel("gpt-5.6-luna", "Reasoning, cheapest and fastest", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gpt-4.1", "Classic chat, long context", cost = 2, speed = 2, accuracy = 2),
        CatalogModel("gpt-4.1-mini", "Classic chat, cheaper", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("gpt-4o", "Classic chat, widely available", cost = 2, speed = 2, accuracy = 2),
        CatalogModel("gpt-4o-mini", "Older and cheapest", cost = 1, speed = 3, accuracy = 1),
        CatalogModel("o3", "Reasoning, deliberate", cost = 3, speed = 1, accuracy = 3),
    )

    private val gemini = listOf(
        CatalogModel("gemini-3.6-flash", "Thinks by default", cost = 2, speed = 2, accuracy = 3),
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
        CatalogModel("claude-sonnet-5", "Steadiest tone", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("claude-haiku-4-5", "Fastest and cheapest", cost = 1, speed = 3, accuracy = 2),
        CatalogModel("claude-fable-5", "Warmer rewriting", cost = 2, speed = 2, accuracy = 3),
        CatalogModel("claude-opus-4-8", "Slowest, most accurate", cost = 3, speed = 1, accuracy = 3),
        CatalogModel("claude-opus-4-7", "Previous opus", cost = 3, speed = 1, accuracy = 3),
    )

    /** Exhaustive with no `else`, so a new provider must say whether it has decoration rows. */
    private fun forProvider(provider: Provider): List<CatalogModel> = when (provider) {
        Provider.OPENAI -> openAi
        Provider.GEMINI -> gemini
        Provider.CLAUDE -> claude
        Provider.SELF_HOSTED_POLISH -> emptyList()
    }

    /**
     * Every decoration row there is, built through [forProvider] so it cannot fall behind a new provider.
     * The dot sweep in `PolishLadderTest` reads this rather than a list of its own, because a test that
     * enumerates a set by hand stops covering the rows added after it was written.
     */
    val all: List<CatalogModel> get() = Provider.entries.flatMap(::forProvider)

    fun forId(provider: Provider, id: String): CatalogModel? =
        forProvider(provider).firstOrNull { it.name == id }
}
