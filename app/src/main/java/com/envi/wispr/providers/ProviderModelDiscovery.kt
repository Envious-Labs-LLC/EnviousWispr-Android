package com.envi.wispr.providers

/**
 * The live model list (#84), ported from the macOS `LLMModelDiscovery`: the provider's own list for this
 * key, filtered to models that can polish text, each probed with a five-token request so the ones the key
 * cannot reach show as locked, the cheap fast ones tagged Recommended.
 */
enum class ModelAccess {
    /** The probe answered 200, or a transient limit the macOS rules read as available. */
    AVAILABLE,
    /** The provider refused this model for this key (403, 404). */
    UNAVAILABLE,
    /** No verdict: a transport failure, a timeout, an unclassified reply, or not probed (over the cap). */
    UNVERIFIED,
}

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val access: ModelAccess,
    val recommended: Boolean,
    /**
     * When the provider says this model was released, epoch millis, or null when nobody knows (#101).
     *
     * Null is not an error and is the ordinary case for Gemini, whose `/v1beta/models` returns no date at
     * all: measured 2026-09-02, its rows carry `version` (the model's own revision, "001") and a prose
     * `description`, and only 2 of 30 descriptions even mention a release month. `ui/ModelNotes` supplies
     * the date for those from Google's published changelog.
     */
    val releasedAt: Long? = null,
)

sealed interface ProviderDiscovery {
    data class Listed(val models: List<DiscoveredModel>, val fetchedAt: Long) : ProviderDiscovery

    /** The list call or a probe answered about the KEY, not a model; carries the #61 verdict for the copy. */
    data class Refused(val verdict: ProviderKeyCheck) : ProviderDiscovery
}

/** A separate operation from [ProviderKeyChecker.check], so Save never pays for the probes. */
fun interface ProviderModelDiscoverer {
    fun discoverModels(provider: Provider, apiKey: String): ProviderDiscovery
}

/** One raw row from a provider's list, before filtering. */
data class ListedModel(val id: String, val displayName: String?, val releasedAt: Long? = null)

/** What one probe reply means; [KeyRejected] aborts the whole discovery. */
sealed interface ProbeOutcome {
    data class Access(val access: ModelAccess) : ProbeOutcome
    /** The provider answered about the KEY; [status] is what it said (401, or Gemini's 400). */
    data class KeyRejected(val status: Int) : ProbeOutcome
}

/**
 * The provider-independent rules: the shared filter checks, the Recommended classifier, the sort and the
 * access merge. Every provider-specific decision (candidate ids, display name, probe verdict, paging) is the
 * adapter's (#189).
 */
object ModelListRules {
    /** Ids containing any of these cannot polish text (macOS `excludePatterns`). */
    private val excludePatterns = listOf(
        "tts", "image", "robotics", "computer-use", "deep-research", "gemma", "exp-", "embedding", "aqa",
        "vision", "nano-banana", "lyria",
    )
    private val versionedSuffixes = listOf("-001", "-002", "-003")
    private val aliasPatterns = listOf("latest")
    /**
     * THE TIER WORDS, AND THIS IS NOW A FALLBACK RATHER THAN THE RULE.
     *
     * Ported from macOS `AIPolishModelClassifier`, whose own comment records a live validation against the
     * OpenAI and Gemini APIs on **2026-05-04**. Re-validated here against all three of the founder's live
     * keys on **2026-09-02** with `scripts/model-id-shapes.py`, and the four months had broken it.
     *
     * **What broke it is not a missing word, it is that OpenAI stopped using tier words.** Its small tier
     * was `mini` and `nano` through the 4.x and 5.0 to 5.5 generations, and at 5.6 it became CODENAMES:
     * `gpt-5.6-luna` is the cheap and fast one, `terra` the middle, `sol` the large. No token can classify
     * those, and adding `luna` would only work until the next generation renames again.
     *
     * So the badge no longer rests on this set. `ModelListPresentation.recommendedPick` takes the tier
     * words OR anything named in `ModelNotes.preferred`, and the curated catalogue is what carries a model
     * we have actually vetted — including whether it is being retired, which no provider's list ever says.
     * This set is what classifies an id we have never seen, on a key we have never seen.
     *
     * Re-validate on contact rather than on a schedule, and when a generation renames, the answer is a
     * catalogue row, never a longer list of names here.
     */
    private val recommendedTokens = setOf("mini", "nano", "flash", "haiku", "lite")

    /**
     * Specialised variants that would polish badly. `omni` was added from the 2026-09-02 sweep:
     * `gemini-omni-1.1-flash` and `gemini-omni-flash-preview` carry `flash` and were classified as good
     * cleanup models. They answer a real polish request with HTTP 400, so the probe already refused them,
     * but a classifier that is wrong and rescued downstream is still wrong.
     */
    private val disqualifierTokens = setOf(
        "realtime", "audio", "native", "live", "tts", "image", "search", "transcribe", "banana", "codex",
        "omni",
    )

    /**
     * Keeps the rows that can polish text, deduplicated by id, each id valid for a polish request. The checks
     * here are provider-independent; [candidate] is the adapter's own decision, given the lowercased id.
     */
    fun filter(rows: List<ListedModel>, candidate: (String) -> Boolean): List<ListedModel> {
        val seen = HashSet<String>()
        return rows.filter { row ->
            val id = row.id
            if (id.isBlank() || id.length > ProviderPolishClient.MAX_MODEL_CHARS || id.any(Char::isISOControl)) return@filter false
            if (!seen.add(id)) return@filter false
            val lowered = id.lowercase()
            if (excludePatterns.any { lowered.contains(it) }) return@filter false
            if (versionedSuffixes.any { lowered.endsWith(it) }) return@filter false
            if (aliasPatterns.any { lowered.contains(it) }) return@filter false
            candidate(lowered)
        }
    }

    /** macOS `AIPolishModelClassifier.isRecommendedForCleanup`: a positive token and no disqualifier. */
    fun isRecommended(id: String): Boolean {
        val tokens = id.lowercase().split('-', '.', '_', '/').filter { it.isNotEmpty() }.toSet()
        return tokens.any { it in recommendedTokens } && tokens.none { it in disqualifierTokens }
    }

    /** The order the page shows: available, then unverified, then unavailable (locked last). */
    fun accessRank(access: ModelAccess): Int = when (access) {
        ModelAccess.AVAILABLE -> 0
        ModelAccess.UNVERIFIED -> 1
        ModelAccess.UNAVAILABLE -> 2
    }

    /** Available first, then unverified, then unavailable; Recommended first within a group; then by display name. */
    fun sort(models: List<DiscoveredModel>): List<DiscoveredModel> = models.sortedWith(
        compareBy<DiscoveredModel>(
            { accessRank(it.access) },
            { if (it.recommended) 0 else 1 },
            { it.displayName.lowercase() },
        ),
    )

    /**
     * The fresh rows own every field; only a fresh UNVERIFIED access borrows a cached AVAILABLE or
     * UNAVAILABLE for the same id, so a flaky probe never erases a verdict the cache already held.
     */
    fun mergeAccess(fresh: List<DiscoveredModel>, cached: List<DiscoveredModel>): List<DiscoveredModel> {
        val known = cached.associate { it.id to it.access }
        return fresh.map { row ->
            val previous = known[row.id]
            if (row.access == ModelAccess.UNVERIFIED && previous != null && previous != ModelAccess.UNVERIFIED) row.copy(access = previous) else row
        }
    }

    /**
     * What a 200 probe body carried, read by the same parser polish uses.
     *
     * THREE values, and the third is the whole point: an empty reply is only evidence against a model when
     * the model itself chose to end it.
     *
     * **A model is refused only when it declared a NORMAL stop and still wrote nothing.** That is one
     * question with a closed answer per provider, rather than a list of the ways a reply can go wrong; a
     * list would need extending every time a provider adds a reason, which is how a check starts letting
     * things through. Gemini alone publishes more than a dozen `finishReason` values, covering safety
     * blocks, recitation, language refusals and tool-call faults; not one of them says the model cannot
     * polish, and none of them has to be named here.
     *
     * Measured 2026-09-02 against a live Gemini key. The probe asks for 5 output tokens (16 on OpenAI) and
     * a reasoning model can spend all of them thinking: `gemini-2.5-pro` and `gemini-3-flash-preview`
     * answer 200 with `MAX_TOKENS` and no text. Raising the cap does not help, because the thinking grows
     * with it: `gemini-2.5-pro` spent 2 thought tokens at a cap of 5, 29 at 32 and 125 at 128, emitting
     * nothing at any of them. Both polish fine at the real request's budget.
     *
     * The same run confirms the check still does its job: `gemini-3.5-transcribe`, the model this whole
     * thing exists for, answers `STOP` with no text and is refused.
     */
    enum class ProbeReply {
        /** The polish parser found words. */
        TEXT,

        /** The model finished of its own accord and wrote nothing. This is the transcribe case. */
        NO_TEXT,

        /** No words, and something other than the model's own choice ended the reply. Nothing was proved. */
        INCONCLUSIVE,
    }

}
