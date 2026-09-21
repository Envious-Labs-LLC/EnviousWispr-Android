package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

import java.net.URI

/**
 * A self-hosted polish server, OpenAI-compatible or Ollama (#189). It takes no key check and lists no
 * models: the user names the endpoint and the model, and only the polish request reaches it. Both
 * protocols take the same chat body; only the path and the answer's shape differ.
 */
internal object SelfHostedAdapter : ProviderAdapter {
    override val provider = Provider.SELF_HOSTED_POLISH
    override val rateLimitFailure = PolishFailure.RATE_LIMITED

    override fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan? {
        val endpoint = request.endpoint ?: return null
        val path = when (request.selfHostedProtocol) {
            SelfHostedProtocol.OPENAI_COMPATIBLE -> "/v1/chat/completions"
            SelfHostedProtocol.OLLAMA -> "/api/chat"
        }
        val url = resolveSelfHostedEndpoint(endpoint, path) ?: return null
        return RequestPlan(
            url = url,
            headers = authHeaders(request.apiKey.orEmpty()),
            body = "{\"model\":${jsonString(request.model)},\"messages\":[{\"role\":\"system\",\"content\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))}},{\"role\":\"user\",\"content\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}],\"stream\":false}",
            responseFormat = when (request.selfHostedProtocol) {
                SelfHostedProtocol.OPENAI_COMPATIBLE -> ProviderReplyFormat.OPENAI_CHAT
                SelfHostedProtocol.OLLAMA -> ProviderReplyFormat.OLLAMA
            },
        )
    }

    /**
     * The endpoint is accepted only as explicit user configuration and only when the validator accepts it;
     * a query, a fragment or a missing authority is refused here as well, and any URI failure is a null
     * plan rather than an exception (the same catch as before the split).
     */
    private fun resolveSelfHostedEndpoint(endpoint: String, suffix: String): URI? {
        if (ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, endpoint), null,
            ) !is ValidationResult.Valid
        ) return null
        return try {
            val base = URI(endpoint)
            if (base.rawQuery != null || base.rawFragment != null || base.rawAuthority == null) return null
            val baseText = endpoint.trimEnd('/')
            URI("$baseText$suffix")
        } catch (_: Exception) {
            null
        }
    }

    override fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan? = null

    override fun listUrl(listOverride: String?): URI? = null

    override fun firstListPage(listOverride: String?): URI? = null

    override fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance = ListAdvance.Done

    override fun parseModelRows(body: String): ModelPage? = null

    override fun hasModelList(body: String): Boolean = false

    /** No body markers: a self-hosted server's error text is whatever the operator installed. */
    override fun errorSignal(status: Int, body: String): ProviderErrorSignal? = null

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

    /** Nothing is listed, so nothing is kept. */
    override fun filterModelRows(rows: List<ListedModel>): List<ListedModel> = ModelListRules.filter(rows) { false }

    override fun isRetryable(failure: ProviderPolishResult.Failure): Boolean = ProviderRetryPolicy.isRetryable(failure, retryRateLimit = true)
}
