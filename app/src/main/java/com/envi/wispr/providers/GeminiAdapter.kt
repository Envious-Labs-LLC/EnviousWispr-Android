package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

import java.net.URI

/** Google Gemini over `generateContent`: what it is sent, how its answers read, and what its statuses mean (#189). */
internal object GeminiAdapter : ProviderAdapter {
    override val provider = Provider.GEMINI

    /** Gemini's 429 cannot be told from an exhausted quota (the macOS reference maps it the same way). */
    override val rateLimitFailure = PolishFailure.RATE_OR_QUOTA

    const val GEMINI_URL_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** The key check's endpoint (#61): a free model list, no user content; the macOS reference uses the same. */
    const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    override fun authHeaders(apiKey: String): Map<String, String> = mapOf("x-goog-api-key" to apiKey)

    /**
     * Gemini names the model in the PATH, so a test override keeps that shape (the fake server tells probes
     * apart by it); the real URL is unchanged.
     */
    private fun modelUrl(model: String, endpointOverride: String?): URI =
        if (endpointOverride != null) URI(endpointOverride.trimEnd('/') + "/" + encodePath(model) + ":generateContent")
        else URI(GEMINI_URL_PREFIX + encodePath(model) + ":generateContent")

    override fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan = RequestPlan(
        url = modelUrl(request.model, endpointOverride),
        headers = authHeaders(request.apiKey.orEmpty()),
        body = "{\"systemInstruction\":{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))}}]},\"contents\":[{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}]}",
        responseFormat = ProviderReplyFormat.GEMINI,
    )

    override fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan {
        val text = jsonString(ProviderAdapters.PROBE_TEXT)
        val body = when (style) {
            ProbeStyle.DEFAULT ->
                "{\"contents\":[{\"parts\":[{\"text\":$text}]}],\"generationConfig\":{\"maxOutputTokens\":${ProviderAdapters.PROBE_OUTPUT_TOKENS}}}"
            // `thinkingBudget: 0` is REJECTED with 400 by a model that cannot stop thinking
            // ("Budget 0 is invalid. This model only works in thinking mode", measured
            // 2026-09-02 on gemini-2.5-pro), and frees the cap on one that can.
            ProbeStyle.NO_REASONING ->
                "{\"contents\":[{\"parts\":[{\"text\":$text}]}],\"generationConfig\":{\"maxOutputTokens\":${ProviderAdapters.PROBE_RETRY_OUTPUT_TOKENS}," +
                    "\"thinkingConfig\":{\"thinkingBudget\":0}}}"
        }
        return RequestPlan(
            url = modelUrl(model, endpointOverride),
            headers = authHeaders(apiKey),
            body = body,
            responseFormat = ProviderReplyFormat.GEMINI,
        )
    }

    override fun listUrl(listOverride: String?): URI = cloudOrOverride(listOverride, GEMINI_MODELS_URL)

    override fun firstListPage(listOverride: String?): URI = listUrl(listOverride)

    override fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance = ListAdvance.Done

    override fun parseModelRows(body: String): ModelPage? {
        val map = ProviderJson.parseOrNull(body)?.root as? Map<*, *> ?: return null
        val list = map["models"] as? List<*> ?: return null
        return ModelPage(
            list.mapNotNull { entry ->
                val row = entry as? Map<*, *> ?: return@mapNotNull null
                val name = row["name"] as? String ?: return@mapNotNull null
                val methods = row["supportedGenerationMethods"] as? List<*> ?: return@mapNotNull null
                if (methods.none { it == "generateContent" }) return@mapNotNull null
                ListedModel(name.removePrefix("models/"), row["displayName"] as? String)
            },
            false,
            null,
        )
    }

    override fun hasModelList(body: String): Boolean =
        (ProviderJson.parseOrNull(body)?.root as? Map<*, *>)?.get("models") is List<*>

    override fun errorSignal(status: Int, body: String): ProviderErrorSignal? = when (status) {
        400 -> when {
            body.contains("API_KEY_INVALID") -> ProviderErrorSignal.KEY_REJECTED
            body.contains("exceeds the maximum number of tokens") -> ProviderErrorSignal.INPUT_TOO_LONG
            body.contains("PROHIBITED_CONTENT") || body.contains("blockReason") -> ProviderErrorSignal.CONTENT_BLOCKED
            else -> null
        }
        else -> null
    }

    /** Gemini answers a wrong key on the list endpoint with 403 (the macOS reference maps it the same way). */
    override fun keyCheckVerdict(status: Int, body: String): ProviderKeyCheck = KeyCheckVerdicts.classify(
        status = status,
        hasModelList = { hasModelList(body) },
        errorSignal = { errorSignal(status, body) },
        forbiddenVerdict = ProviderKeyCheck::Rejected,
        rateLimitFailure = rateLimitFailure,
    )

    override fun probeOutcome(status: Int?, body: String?, reply: ModelListRules.ProbeReply): ProbeOutcome = ProbeVerdicts.classify(
        status = status,
        body = body,
        reply = reply,
        keyRejectedByBody = { errorSignal(400, it) == ProviderErrorSignal.KEY_REJECTED },
        // A 429 whose body names a zero quota is a model this key cannot use; any other 429 is a live model.
        rateLimitAccess = { if (it?.contains("limit: 0") == true) ModelAccess.UNAVAILABLE else ModelAccess.AVAILABLE },
        serverErrorAccess = ModelAccess.UNVERIFIED,
    )

    override fun filterModelRows(rows: List<ListedModel>): List<ListedModel> = ModelListRules.filter(rows) { true }

    override fun isRetryable(failure: ProviderPolishResult.Failure): Boolean = ProviderRetryPolicy.isRetryable(failure, retryRateLimit = false)
}
