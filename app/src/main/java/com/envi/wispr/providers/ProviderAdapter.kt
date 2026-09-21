package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Everything one provider decides about the wire and about what its answers mean (#189): its URLs, its
 * auth header, its polish and probe bodies, its model list envelope, its error-body markers, its key and
 * probe verdicts, its paging, which listed models can polish text, how a model is named, and whether a
 * failure is worth a retry. The clients hold no provider branch of their own: they take one adapter from
 * [ProviderAdapters.of] and ask it.
 *
 * Adapters are stateless objects. Every key arrives as a call argument and is held by nothing
 * (`keystore-security.md` RULE: plaintext-never-leaves-the-store). They expose DECISIONS, never a flag a
 * client could branch on; the one value member, [rateLimitFailure], is the final answer its two readers
 * return as-is.
 */
internal interface ProviderAdapter {
    val provider: Provider

    /**
     * What this provider's 429 means to the user: Gemini's cannot be told from an exhausted quota, so it
     * reads `RATE_OR_QUOTA`; every other provider's reads `RATE_LIMITED`. Read by the key check's 429 row
     * and by `PolishFailure.fromStatus`, the two places that used to spell the same fact separately.
     */
    val rateLimitFailure: PolishFailure

    /** The one place this provider's auth header is spelled, for the polish request, the key check and the probes alike. */
    fun authHeaders(apiKey: String): Map<String, String>

    /** The polish request, or null when this provider cannot plan it (a self-hosted endpoint that will not resolve). */
    fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan?

    /**
     * The discovery probe in [style] (#84): the same URL and headers, the fixed word "Hi" as the only input,
     * no system instruction, a tiny output cap. Null when this provider has no probe of that style.
     */
    fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan?

    /** The key check's list GET (#61), or null when the provider takes no key (self-hosted). */
    fun listUrl(listOverride: String?): URI?

    /** Discovery's first page of the model list (#84), or null when there is no list to page. */
    fun firstListPage(listOverride: String?): URI?

    /** Where the list goes after [page]: nowhere, a next URL, or a malformed cursor. Only Claude ever pages. */
    fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance

    /** The provider's own list shape as rows; null when the body is not that shape. */
    fun parseModelRows(body: String): ModelPage?

    /** A 200 counts only when the body is the provider's own list envelope, so a captive portal cannot accept a key. */
    fun hasModelList(body: String): Boolean

    /**
     * What this provider's error BODY said beyond its status (#77), as a closed signal so the body, which can
     * echo the prompt, never leaves the client. The markers are the substrings the macOS connectors match,
     * on the raw body: two of them are not values a structural read could find (`blockReason` is a key,
     * `credit balance` sits inside a free-text message) and a non-JSON error body must still classify.
     */
    fun errorSignal(status: Int, body: String): ProviderErrorSignal?

    /** The key check's verdict for a status the provider actually sent (#61). */
    fun keyCheckVerdict(status: Int, body: String): ProviderKeyCheck

    /**
     * The probe verdict (macOS `probeOpenAI` / `probeGemini` / `probeClaude`). [reply] is the caller's reading
     * of the body, produced by the SAME parser polish uses; it is a required argument with no default because
     * a default here would be a silent answer at every call site that forgot it.
     */
    fun probeOutcome(status: Int?, body: String?, reply: ModelListRules.ProbeReply): ProbeOutcome

    /** The listed rows that can polish text, deduplicated by id, each id valid for a polish request. */
    fun filterModelRows(rows: List<ListedModel>): List<ListedModel>

    /** The display name the page shows: the provider's own when it gave one, else the id. */
    fun displayName(id: String, given: String?): String = given?.takeIf { it.isNotBlank() } ?: id

    /** Whether a polish failure is worth a second and third attempt (#4). */
    fun isRetryable(failure: ProviderPolishResult.Failure): Boolean
}

/** How a probe asks. The retry exists only because a reasoning model can spend the whole cap thinking. */
internal enum class ProbeStyle {
    /** The cheap first ask: the provider's own defaults and a tiny output cap. */
    DEFAULT,

    /** The one retry: ask the model not to reason, and leave room for the answer itself. */
    NO_REASONING,
}

/** One page of a provider's model list. */
internal class ModelPage(val rows: List<ListedModel>, val hasMore: Boolean, val lastId: String?)

/** Where the list goes after a page. */
internal sealed interface ListAdvance {
    data object Done : ListAdvance
    data object Malformed : ListAdvance
    data class Next(val url: URI) : ListAdvance
}

/** The exhaustive routing from a [Provider] to its adapter: the ONLY provider branch inside the clients. */
internal object ProviderAdapters {
    fun of(provider: Provider): ProviderAdapter = when (provider) {
        Provider.OPENAI -> OpenAiAdapter
        Provider.GEMINI -> GeminiAdapter
        Provider.CLAUDE -> ClaudeAdapter
        Provider.SELF_HOSTED_POLISH -> SelfHostedAdapter
    }

    const val PROBE_TEXT = "Hi"
    const val PROBE_OUTPUT_TOKENS = 5

    /**
     * The retry's cap. Measured 2026-09-02: asked not to reason, `gpt-5-mini` answers "Hi" in 27 output
     * tokens and `gpt-5-nano` in 28, so 16 is not enough room for the ANSWER even once the thinking is
     * gone. Only models whose first probe told us nothing ever spend this.
     */
    const val PROBE_RETRY_OUTPUT_TOKENS = 64
}

/**
 * The key check's status table (#61), written once. Every adapter calls it with its two decisions: what a
 * 403 means (Gemini answers a wrong key on this endpoint with 403, the macOS reference maps it the same
 * way; OpenAI and Claude mean "recognised but not allowed") and which failure a 429 reports.
 */
internal object KeyCheckVerdicts {
    fun classify(
        status: Int,
        hasModelList: () -> Boolean,
        errorSignal: () -> ProviderErrorSignal?,
        forbiddenVerdict: (Int) -> ProviderKeyCheck,
        rateLimitFailure: PolishFailure,
    ): ProviderKeyCheck = when {
        status == 200 -> if (hasModelList()) ProviderKeyCheck.Accepted else ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        status in 201..299 -> ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        status == 401 -> ProviderKeyCheck.Rejected(status)
        status == 403 -> forbiddenVerdict(status)
        status == 400 && errorSignal() == ProviderErrorSignal.KEY_REJECTED -> ProviderKeyCheck.Rejected(status)
        status == 429 -> ProviderKeyCheck.Unverified(rateLimitFailure, status)
        status in 500..599 -> ProviderKeyCheck.Unverified(PolishFailure.PROVIDER_ERROR, status)
        status in 400..499 -> ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        else -> ProviderKeyCheck.Unverified(PolishFailure.UNEXPECTED, status)
    }
}

/**
 * The probe verdict's status table (macOS `probeOpenAI` / `probeGemini` / `probeClaude`), written once.
 * Every adapter calls it with its two decisions: the access a 429 body means and the access a 5xx means.
 */
internal object ProbeVerdicts {
    fun classify(
        status: Int?,
        body: String?,
        reply: ModelListRules.ProbeReply,
        keyRejectedByBody: (String) -> Boolean,
        rateLimitAccess: (String?) -> ModelAccess,
        serverErrorAccess: ModelAccess,
    ): ProbeOutcome {
        if (status == null) return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        if (status == 401) return ProbeOutcome.KeyRejected(status)
        if (status == 400 && body != null && keyRejectedByBody(body)) return ProbeOutcome.KeyRejected(status)
        val access = when {
            // 200 IS NOT ENOUGH. The probe asks the model to answer the word "Hi" with a tiny output cap,
            // and a model that answers 200 with no text cannot polish anything: measured 2026-09-02,
            // gemini-3.5-transcribe returns an empty string to a real cleanup request, so it was shipping
            // as AVAILABLE while silently returning the user's raw words on every dictation. Green must
            // mean the outcome happened (validation-discipline RULE: verify-the-feature-not-the-crash).
            status == 200 -> when (reply) {
                ModelListRules.ProbeReply.TEXT -> ModelAccess.AVAILABLE
                ModelListRules.ProbeReply.NO_TEXT -> ModelAccess.UNAVAILABLE
                // Not "broken", and not "fine": nothing was proved. UNVERIFIED rows stay on screen and
                // `recommendedPick` never chooses one, so the model is still offered to a user who asks
                // for it by name and is never selected on their behalf. That is the only classification
                // that is safe in BOTH directions, which is why the unreadable cases land here.
                ModelListRules.ProbeReply.INCONCLUSIVE -> ModelAccess.UNVERIFIED
            }
            status == 429 -> rateLimitAccess(body)
            status == 403 || status == 404 -> ModelAccess.UNAVAILABLE
            status in 500..599 -> serverErrorAccess
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

/** A cloud provider's fixed URL, or the local server a test points it at. */
internal fun cloudOrOverride(override: String?, defaultUrl: String): URI = URI(override ?: defaultUrl)

internal fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/** A JSON string literal, every control character escaped, so a transcript can never break out of the body. */
internal fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
