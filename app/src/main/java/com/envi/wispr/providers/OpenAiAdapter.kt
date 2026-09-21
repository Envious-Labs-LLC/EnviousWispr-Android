package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

import java.net.URI

/** OpenAI over the Responses API: what it is sent, how its answers read, and what its statuses mean (#189). */
internal object OpenAiAdapter : ProviderAdapter {
    override val provider = Provider.OPENAI
    override val rateLimitFailure = PolishFailure.RATE_LIMITED

    const val OPENAI_URL = "https://api.openai.com/v1/responses"

    /** The key check's endpoint (#61): a free model list, no user content; the macOS reference uses the same. */
    const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"

    /** The Responses API's smallest accepted cap is 16. */
    const val OPENAI_PROBE_OUTPUT_TOKENS = 16

    /** Realtime, audio, search and transcription variants cannot polish text. */
    private val openAiModalitySkips = listOf("realtime", "audio", "search", "transcribe")

    /**
     * Android sends every OpenAI request to the Responses API; these ids exist only on chat completions
     * (OpenAI's model page and deprecations list, checked 2026-09-01 for `PolishModelCatalog`), so
     * offering them would save a model that fails every dictation. The Mac excludes the opposite set.
     */
    private val openAiChatCompletionsOnly = setOf("o1-mini", "o1-preview")

    override fun authHeaders(apiKey: String): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

    override fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan = RequestPlan(
        url = cloudOrOverride(endpointOverride, OPENAI_URL),
        headers = authHeaders(request.apiKey.orEmpty()),
        body = "{\"model\":${jsonString(request.model)},\"instructions\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))},\"input\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))},\"store\":false}",
        responseFormat = ProviderReplyFormat.OPENAI_RESPONSES,
    )

    override fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan {
        val modelJson = jsonString(model)
        val body = when (style) {
            ProbeStyle.DEFAULT ->
                "{\"model\":$modelJson,\"input\":${jsonString(ProviderAdapters.PROBE_TEXT)},\"max_output_tokens\":$OPENAI_PROBE_OUTPUT_TOKENS,\"store\":false}"
            // `reasoning.effort` is REJECTED with 400 by every model that does not reason
            // (measured 2026-09-02 on gpt-4.1-mini, gpt-4o-mini and gpt-3.5-turbo), which is
            // exactly why it is only ever sent on a retry that cannot make a verdict worse.
            ProbeStyle.NO_REASONING ->
                "{\"model\":$modelJson,\"input\":${jsonString(ProviderAdapters.PROBE_TEXT)},\"max_output_tokens\":${ProviderAdapters.PROBE_RETRY_OUTPUT_TOKENS}," +
                    "\"reasoning\":{\"effort\":\"minimal\"},\"store\":false}"
        }
        return RequestPlan(
            url = cloudOrOverride(endpointOverride, OPENAI_URL),
            headers = authHeaders(apiKey),
            body = body,
            responseFormat = ProviderReplyFormat.OPENAI_RESPONSES,
        )
    }

    override fun listUrl(listOverride: String?): URI = cloudOrOverride(listOverride, OPENAI_MODELS_URL)

    override fun firstListPage(listOverride: String?): URI = listUrl(listOverride)

    override fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance = ListAdvance.Done

    override fun parseModelRows(body: String): ModelPage? {
        val map = ProviderJson.parseOrNull(body)?.root as? Map<*, *> ?: return null
        val data = map["data"] as? List<*> ?: return null
        // `created` is unix SECONDS here; the app's clock is millis everywhere else.
        return ModelPage(
            data.mapNotNull { entry ->
                val row = entry as? Map<*, *> ?: return@mapNotNull null
                val id = row["id"] as? String ?: return@mapNotNull null
                ListedModel(id, null, (row["created"] as? Number)?.toLong()?.times(1000L))
            },
            false,
            null,
        )
    }

    override fun hasModelList(body: String): Boolean =
        (ProviderJson.parseOrNull(body)?.root as? Map<*, *>)?.get("data") is List<*>

    override fun errorSignal(status: Int, body: String): ProviderErrorSignal? = when (status) {
        429 -> if (body.contains("insufficient_quota")) ProviderErrorSignal.OUT_OF_CREDITS else null
        400 -> when {
            body.contains("context_length_exceeded") -> ProviderErrorSignal.INPUT_TOO_LONG
            body.contains("content_filter") || body.contains("content_policy") -> ProviderErrorSignal.CONTENT_BLOCKED
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

    override fun probeOutcome(status: Int?, body: String?, reply: ModelListRules.ProbeReply): ProbeOutcome = ProbeVerdicts.classify(
        status = status,
        body = body,
        reply = reply,
        keyRejectedByBody = { errorSignal(400, it) == ProviderErrorSignal.KEY_REJECTED },
        rateLimitAccess = { ModelAccess.UNVERIFIED },
        serverErrorAccess = ModelAccess.UNVERIFIED,
    )

    override fun filterModelRows(rows: List<ListedModel>): List<ListedModel> = ModelListRules.filter(rows, ::isOpenAiCandidate)

    private fun isOpenAiCandidate(id: String): Boolean {
        val chatCapable = id.startsWith("gpt-") || id.startsWith("o-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
        if (!chatCapable) return false
        if (openAiModalitySkips.any { id.contains(it) }) return false
        return id !in openAiChatCompletionsOnly
    }

    /** OpenAI's list carries no display name, so the id is shown in title case. */
    override fun displayName(id: String, given: String?): String {
        if (!given.isNullOrBlank()) return given
        return id.split('-').filter { it.isNotEmpty() }.joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    }

    override fun isRetryable(failure: ProviderPolishResult.Failure): Boolean = ProviderRetryPolicy.isRetryable(failure, retryRateLimit = true)
}
