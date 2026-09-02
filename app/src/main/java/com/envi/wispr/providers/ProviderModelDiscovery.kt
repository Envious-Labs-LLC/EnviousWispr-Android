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

/** The pure rules: the macOS filter, classifier, sort, pagination decision and access merge. */
object ModelListRules {
    /** Ids containing any of these cannot polish text (macOS `excludePatterns`). */
    private val excludePatterns = listOf(
        "tts", "image", "robotics", "computer-use", "deep-research", "gemma", "exp-", "embedding", "aqa",
        "vision", "nano-banana", "lyria",
    )
    private val versionedSuffixes = listOf("-001", "-002", "-003")
    private val aliasPatterns = listOf("latest")
    private val openAiModalitySkips = listOf("realtime", "audio", "search", "transcribe")

    /**
     * Android sends every OpenAI request to the Responses API; these ids exist only on chat completions
     * (OpenAI's model page and deprecations list, checked 2026-09-01 for `PolishModelCatalog`), so
     * offering them would save a model that fails every dictation. The Mac excludes the opposite set.
     */
    private val openAiChatCompletionsOnly = setOf("o1-mini", "o1-preview")

    private val recommendedTokens = setOf("mini", "nano", "flash", "haiku")
    private val disqualifierTokens = setOf(
        "realtime", "audio", "native", "live", "tts", "image", "search", "transcribe", "banana", "codex",
    )

    /** Keeps the rows that can polish text, deduplicated by id, each id valid for a polish request. */
    fun filter(provider: Provider, rows: List<ListedModel>): List<ListedModel> {
        val seen = HashSet<String>()
        return rows.filter { row ->
            val id = row.id
            if (id.isBlank() || id.length > ProviderPolishClient.MAX_MODEL_CHARS || id.any(Char::isISOControl)) return@filter false
            if (!seen.add(id)) return@filter false
            val lowered = id.lowercase()
            if (excludePatterns.any { lowered.contains(it) }) return@filter false
            if (versionedSuffixes.any { lowered.endsWith(it) }) return@filter false
            if (aliasPatterns.any { lowered.contains(it) }) return@filter false
            when (provider) {
                Provider.OPENAI -> isOpenAiCandidate(lowered)
                Provider.GEMINI, Provider.CLAUDE -> true
                Provider.SELF_HOSTED_POLISH -> false
            }
        }
    }

    private fun isOpenAiCandidate(id: String): Boolean {
        val chatCapable = id.startsWith("gpt-") || id.startsWith("o-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
        if (!chatCapable) return false
        if (openAiModalitySkips.any { id.contains(it) }) return false
        return id !in openAiChatCompletionsOnly
    }

    /** macOS `AIPolishModelClassifier.isRecommendedForCleanup`: a positive token and no disqualifier. */
    fun isRecommended(id: String): Boolean {
        val tokens = id.lowercase().split('-', '.', '_', '/').filter { it.isNotEmpty() }.toSet()
        return tokens.any { it in recommendedTokens } && tokens.none { it in disqualifierTokens }
    }

    /** The display name the page shows: the provider's own when it gave one, else the id in title case. */
    fun displayName(provider: Provider, id: String, given: String?): String {
        if (!given.isNullOrBlank()) return given
        return when (provider) {
            Provider.OPENAI -> id.split('-').filter { it.isNotEmpty() }.joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            Provider.GEMINI, Provider.CLAUDE, Provider.SELF_HOSTED_POLISH -> id
        }
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

    sealed interface Pagination {
        data class Continue(val afterId: String) : Pagination
        data object Stop : Pagination
        data object Malformed : Pagination
    }

    /** macOS `claudePaginationDecision`: stop on no more; a missing, empty or repeated cursor is malformed. */
    fun claudePagination(hasMore: Boolean, lastId: String?, seen: Set<String>): Pagination = when {
        !hasMore -> Pagination.Stop
        lastId.isNullOrEmpty() || lastId in seen -> Pagination.Malformed
        else -> Pagination.Continue(lastId)
    }

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

    /**
     * The probe verdict per provider (macOS `probeOpenAI` / `probeGemini` / `probeClaude`).
     *
     * [reply] is the caller's reading of the body, produced by the SAME parser polish uses. It is a
     * required argument with no default, because a default here would be a silent answer at every call
     * site that forgot it.
     */
    fun probeOutcome(provider: Provider, status: Int?, body: String?, reply: ProbeReply): ProbeOutcome {
        if (status == null) return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        if (status == 401) return ProbeOutcome.KeyRejected(status)
        if (status == 400 && body != null && ProviderErrorSignal.classify(provider, status, body) == ProviderErrorSignal.KEY_REJECTED) {
            return ProbeOutcome.KeyRejected(status)
        }
        val access = when {
            // 200 IS NOT ENOUGH. The probe asks the model to answer the word "Hi" with a tiny output cap,
            // and a model that answers 200 with no text cannot polish anything: measured 2026-09-02,
            // gemini-3.5-transcribe returns an empty string to a real cleanup request, so it was shipping
            // as AVAILABLE while silently returning the user's raw words on every dictation. Green must
            // mean the outcome happened (validation-discipline RULE: verify-the-feature-not-the-crash).
            status == 200 -> when (reply) {
                ProbeReply.TEXT -> ModelAccess.AVAILABLE
                ProbeReply.NO_TEXT -> ModelAccess.UNAVAILABLE
                // Not "broken", and not "fine": nothing was proved. UNVERIFIED rows stay on screen and
                // `recommendedPick` never chooses one, so the model is still offered to a user who asks
                // for it by name and is never selected on their behalf. That is the only classification
                // that is safe in BOTH directions, which is why the unreadable cases land here.
                ProbeReply.INCONCLUSIVE -> ModelAccess.UNVERIFIED
            }
            status == 429 -> when (provider) {
                Provider.GEMINI -> if (body?.contains("limit: 0") == true) ModelAccess.UNAVAILABLE else ModelAccess.AVAILABLE
                Provider.CLAUDE -> ModelAccess.AVAILABLE
                Provider.OPENAI, Provider.SELF_HOSTED_POLISH -> ModelAccess.UNVERIFIED
            }
            status == 403 || status == 404 -> ModelAccess.UNAVAILABLE
            status in 500..599 -> if (provider == Provider.CLAUDE) ModelAccess.AVAILABLE else ModelAccess.UNVERIFIED
            // A 400 that is not about the KEY is the provider saying this model cannot serve this request.
            // It answered, so "we could not tell" is the wrong record: measured 2026-09-02, the two omni
            // models and antigravity-preview all answer 400 INVALID_ARGUMENT and were being listed as
            // merely untested. Key rejections were already taken above, so nothing about the key reaches
            // here.
            status == 400 -> ModelAccess.UNAVAILABLE
            else -> ModelAccess.UNVERIFIED
        }
        return ProbeOutcome.Access(access)
    }
}
