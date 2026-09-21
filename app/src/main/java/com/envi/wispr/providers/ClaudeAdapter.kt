package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

import java.net.URI

/** Anthropic Claude over the Messages API: what it is sent, how its answers read, and what its statuses mean (#189). */
internal object ClaudeAdapter : ProviderAdapter {
    override val provider = Provider.CLAUDE
    override val rateLimitFailure = PolishFailure.RATE_LIMITED

    const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** The key check's endpoint (#61): a free model list, no user content; the macOS reference uses the same. */
    const val CLAUDE_MODELS_URL = "https://api.anthropic.com/v1/models"

    /** The Anthropic API requires `max_tokens`; the same value macOS sends, so a long dictation's polish is not cut off (#79). */
    const val CLAUDE_MAX_OUTPUT_TOKENS = 8192

    override fun authHeaders(apiKey: String): Map<String, String> = mapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION)

    override fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan = RequestPlan(
        url = cloudOrOverride(endpointOverride, CLAUDE_URL),
        headers = authHeaders(request.apiKey.orEmpty()),
        body = "{\"model\":${jsonString(request.model)},\"max_tokens\":$CLAUDE_MAX_OUTPUT_TOKENS,\"system\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))},\"messages\":[{\"role\":\"user\",\"content\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}",
        responseFormat = ProviderReplyFormat.CLAUDE,
    )

    /**
     * Anthropic's extended thinking is OPT IN and this request does not opt in, so there is nothing to
     * suppress and no retry to make: measured 2026-09-02, both models the founder's key reaches answer
     * within a 5-token cap.
     */
    override fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan? = when (style) {
        ProbeStyle.NO_REASONING -> null
        ProbeStyle.DEFAULT -> RequestPlan(
            url = cloudOrOverride(endpointOverride, CLAUDE_URL),
            headers = authHeaders(apiKey),
            body = "{\"model\":${jsonString(model)},\"max_tokens\":${ProviderAdapters.PROBE_OUTPUT_TOKENS},\"messages\":[{\"role\":\"user\",\"content\":${jsonString(ProviderAdapters.PROBE_TEXT)}}]}",
            responseFormat = ProviderReplyFormat.CLAUDE,
        )
    }

    override fun listUrl(listOverride: String?): URI = cloudOrOverride(listOverride, CLAUDE_MODELS_URL)

    /** The list, page by page: the largest page Anthropic allows, then `after_id` from the last row. */
    override fun firstListPage(listOverride: String?): URI = pageUrl(listUrl(listOverride), afterId = null)

    private fun pageUrl(listUrl: URI, afterId: String?): URI =
        URI(listUrl.toString() + (if (listUrl.rawQuery == null) "?" else "&") + "limit=1000" + (afterId?.let { "&after_id=${encodePath(it)}" } ?: ""))

    override fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance =
        when (val next = pagination(page.hasMore, page.lastId, seenCursors)) {
            Pagination.Stop -> ListAdvance.Done
            Pagination.Malformed -> ListAdvance.Malformed
            is Pagination.Continue -> {
                seenCursors += next.afterId
                ListAdvance.Next(pageUrl(listUrl(listOverride), next.afterId))
            }
        }

    internal sealed interface Pagination {
        data object Stop : Pagination
        data object Malformed : Pagination
        data class Continue(val afterId: String) : Pagination
    }

    /** macOS `claudePaginationDecision`: stop on no more; a missing, empty or repeated cursor is malformed. */
    internal fun pagination(hasMore: Boolean, lastId: String?, seen: Set<String>): Pagination = when {
        !hasMore -> Pagination.Stop
        lastId.isNullOrEmpty() -> Pagination.Malformed
        lastId in seen -> Pagination.Malformed
        else -> Pagination.Continue(lastId)
    }

    override fun parseModelRows(body: String): ModelPage? {
        val map = ProviderJson.parseOrNull(body)?.root as? Map<*, *> ?: return null
        val data = map["data"] as? List<*> ?: return null
        return ModelPage(
            data.mapNotNull { entry ->
                val row = entry as? Map<*, *> ?: return@mapNotNull null
                val id = row["id"] as? String ?: return@mapNotNull null
                ListedModel(id, row["display_name"] as? String, parseIso8601(row["created_at"] as? String))
            },
            map["has_more"] as? Boolean ?: false,
            map["last_id"] as? String,
        )
    }

    /**
     * Anthropic's `created_at`, e.g. `2026-08-28T00:00:00Z`, as epoch millis. Null on anything unparseable
     * rather than a guessed date: an undated model sorts after the dated ones, which is visible and
     * honest, while a wrong date silently reorders the list and nobody can see why.
     *
     * `Instant.parse` is API 26 and minSdk is 30, so there is no desugaring question here.
     */
    private fun parseIso8601(value: String?): Long? =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    override fun hasModelList(body: String): Boolean =
        (ProviderJson.parseOrNull(body)?.root as? Map<*, *>)?.get("data") is List<*>

    override fun errorSignal(status: Int, body: String): ProviderErrorSignal? = when (status) {
        400 -> when {
            body.contains("credit balance") -> ProviderErrorSignal.OUT_OF_CREDITS
            body.contains("prompt is too long") -> ProviderErrorSignal.INPUT_TOO_LONG
            else -> null
        }
        else -> null
    }

    override fun keyCheckVerdict(status: Int, body: String): ProviderKeyCheck = KeyCheckVerdicts.classify(
        status = status,
        hasModelList = { hasModelList(body) },
        errorSignal = { errorSignal(status, body) },
        forbiddenVerdict = ProviderKeyCheck::Denied,
        rateLimitFailure = rateLimitFailure,
    )

    /** A rate limit or an outage on Anthropic's side says nothing against the model: it stays available (macOS `probeClaude`). */
    override fun probeOutcome(status: Int?, body: String?, reply: ModelListRules.ProbeReply): ProbeOutcome = ProbeVerdicts.classify(
        status = status,
        body = body,
        reply = reply,
        keyRejectedByBody = { errorSignal(400, it) == ProviderErrorSignal.KEY_REJECTED },
        rateLimitAccess = { ModelAccess.AVAILABLE },
        serverErrorAccess = ModelAccess.AVAILABLE,
    )

    override fun filterModelRows(rows: List<ListedModel>): List<ListedModel> = ModelListRules.filter(rows) { true }

    override fun isRetryable(failure: ProviderPolishResult.Failure): Boolean = ProviderRetryPolicy.isRetryable(failure, retryRateLimit = true)
}
