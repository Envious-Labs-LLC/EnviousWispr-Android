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
    /**
     * Release date as `YYYY-MM-DD`, for providers whose API does not tell us (#101).
     *
     * ONLY needed for Gemini. OpenAI sends `created` and Anthropic sends `created_at`, and a date from the
     * provider is always fresher than one typed here, so the API wins wherever it speaks.
     *
     * These are read off Google's own changelog, https://ai.google.dev/gemini-api/docs/changelog, on
     * 2026-09-02, not from anybody's memory. **This table goes stale the day Google ships a model**, and
     * the model with no date sorts after the dated ones rather than being guessed into a position; that is
     * the visible prompt to add the row.
     */
    val released: String? = null,
)

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

    /**
     * The ONE model each provider recommends, most preferred first (#99, founder 2026-09-02).
     *
     * A LIST rather than a single id, because the first entry is not guaranteed to come back: a key on a
     * different tier, a region without a preview model, or a provider retiring one all return a catalogue
     * that does not contain it, and a user who sees no recommendation at all is worse off than today. The
     * caller falls through to ranking whatever they DID get.
     *
     * Gemini leads with `gemini-3.8-flash` on the founder's instruction. Measured 2026-09-02 against
     * Google's pricing page, it costs the same as 3.6 and 3.7 and HALF what 3.5 Flash costs, so newest and
     * cheap-per-quality are the same answer right now; they will diverge, which is why the fallback ranks
     * on the table below rather than on the version number.
     *
     * OpenAI and Claude are not the founder's call; each is the row this same table already describes as
     * the cheapest and fastest of its family, so the pick agrees with the dots beside it.
     */
    fun preferred(provider: Provider): List<String> = when (provider) {
        Provider.OPENAI -> listOf("gpt-5.6-luna", "gpt-4.1-mini")
        Provider.GEMINI -> listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
        Provider.CLAUDE -> listOf("claude-haiku-4-5")
        Provider.SELF_HOSTED_POLISH -> emptyList()
    }

    private val gemini = listOf(
        CatalogModel("gemini-3.8-flash", "Newest flash, best value", tag = null, cost = 2, speed = 3, accuracy = 3, released = "2026-09-02"),
        CatalogModel("gemini-3.6-flash", "Thinks by default", cost = 2, speed = 2, accuracy = 3, released = "2026-07-21"),
        CatalogModel("gemini-3.7-flash", "Quick and capable", cost = 2, speed = 2, accuracy = 3, released = "2026-08-13"),
        // Cost 3, not 2: measured against Google's pricing page 2026-09-02 it is $1.50/$9.00 per 1M, double
        // every newer Flash, which makes it the worst value on the list rather than their peer.
        CatalogModel("gemini-3.5-flash", "Older flash, costs double the newer ones", cost = 3, speed = 3, accuracy = 2, released = "2026-05-19"),
        CatalogModel("gemini-3.5-flash-lite", "Cheapest flash", cost = 1, speed = 3, accuracy = 2, released = "2026-07-21"),
        CatalogModel("gemini-3.1-flash-lite", "Cheap, previous lite", cost = 1, speed = 3, accuracy = 1, released = "2026-05-07"),
        CatalogModel("gemini-3.1-pro-preview", "Pro tier, slower", cost = 3, speed = 1, accuracy = 3, released = "2026-02-19"),
        CatalogModel("gemini-3-flash-preview", "First Gemini 3 flash", cost = 2, speed = 2, accuracy = 2, released = "2025-12-17"),
        CatalogModel("gemini-2.5-flash", "Token thinking budget", cost = 1, speed = 3, accuracy = 2, released = "2025-06-17"),
        CatalogModel("gemini-2.5-flash-lite", "Smallest 2.5", cost = 1, speed = 3, accuracy = 1, released = "2025-07-22"),
        CatalogModel("gemini-2.5-pro", "Previous pro tier", cost = 3, speed = 1, accuracy = 3, released = "2025-06-17"),
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

    fun forId(provider: Provider, id: String): CatalogModel? {
        val rows = forProvider(provider)
        return rows.firstOrNull { it.name == id } ?: withoutSnapshot(id)?.let { base -> rows.firstOrNull { it.name == base } }
    }

    /**
     * The id with a trailing DATED SNAPSHOT removed, or null when there is none (#103).
     *
     * Anthropic ships `claude-haiku-4-5-20251001` where its documentation says `claude-haiku-4-5`, and
     * OpenAI ships `gpt-4o-2024-08-06`. Every one of those was a silent miss: no note, no dots, and not
     * matched by [preferred], so a hand-written catalogue that is CORRECT still describes nothing the key
     * actually returns. Measured on the founder's key 2026-09-02: all 11 Claude ids carry a suffix.
     *
     * A DATE is checked, not merely digits, because a model id may legitimately end in a number:
     * `claude-fable-5-1` and `gemini-2.5-flash` must survive untouched. Both vendor spellings are handled,
     * and nothing else is guessed at — an unrecognised id keeps missing, which is visible, rather than
     * being trimmed until it accidentally matches.
     *
     * Measured across all three of the founder's live keys, 2026-09-02, by listing every id each returns:
     * OpenAI 43 of 130 dated, Claude 3 of 11, Gemini 0 of 54. Regenerate that with
     * `scripts/model-id-shapes.py`, never from memory.
     *
     * **KNOWN LIMIT, stated because the same measurement found it: OpenAI also ships a FOUR-digit `MMDD`
     * snapshot** — `gpt-3.5-turbo-0125`, `gpt-4-0613` — and this deliberately does not strip it. Four
     * digits cannot be told from a version (`-001` and `-002` are Google version suffixes on the same
     * list), and every base id behind those belongs to a model generation the catalogue does not carry, so
     * stripping them would buy nothing and risk matching the wrong row. It reopens if a `MMDD` model ever
     * needs a note.
     */
    internal fun withoutSnapshot(id: String): String? {
        val match = SNAPSHOT.find(id) ?: return null
        val (year, month, day) = match.destructured
        if (year.toInt() !in 2000..2099 || month.toInt() !in 1..12 || day.toInt() !in 1..31) return null
        return id.substring(0, match.range.first)
    }

    /** `-YYYYMMDD` or `-YYYY-MM-DD` at the END of an id, and only there. */
    private val SNAPSHOT = Regex("-(\\d{4})-?(\\d{2})-?(\\d{2})$")
}
