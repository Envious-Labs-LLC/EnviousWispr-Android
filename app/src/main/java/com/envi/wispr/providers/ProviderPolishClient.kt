package com.envi.wispr.providers

import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.polish.PolishFailure

import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Small platform-only provider client. It deliberately does not log request bodies, response
 * bodies, endpoint credentials, or API keys. The caller can keep the raw transcript when every
 * provider fails, without this layer ever persisting or exposing it.
 */
internal class ProviderPolishClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val overallTimeoutMs: Int = DEFAULT_OVERALL_TIMEOUT_MS,
    /** Test-only endpoint overrides allow local HttpServer coverage without changing cloud origins. */
    private val endpointOverrides: Map<Provider, String> = emptyMap(),
    /** Test-only overrides for the key-check (model-list) endpoints, the same shape as [endpointOverrides]. */
    private val keyCheckOverrides: Map<Provider, String> = emptyMap(),
    /** Where the content-free diagnostics go; a JVM test passes no-ops so android.util.Log is never touched. */
    private val logInfo: (String) -> Unit = { DebugLogger.log(PROVIDER_LOG_TAG, it) },
    private val logWarn: (String) -> Unit = { DebugLogger.warn(PROVIDER_LOG_TAG, it) },
    /** The live model list's bounds (#84); a test shortens them. */
    private val discoveryTimeoutMs: Int = DISCOVERY_TIMEOUT_MS,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
    /** The retry policy's bounds (#4), the Mac's two retries at 1 s then 3 s; a test shortens or disables them. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val maxRetries: Int = MAX_RETRIES,
    /** The HTTP round trip; the production default, an adapter test may pass a fake. */
    private val transport: ProviderTransport = HttpProviderTransport(logWarn),
) : ProviderKeyChecker, ProviderModelDiscoverer {
    fun polish(
        request: ProviderPolishRequest,
        cancellation: ProviderCancellation = ProviderCancellation(),
    ): ProviderPolishResult {
        // ONE deadline from entry: validation, prompt assembly, sizing, every delay and every attempt
        // consume it. Capped at the client's default, which the session watchdog documents as its margin.
        val budgetMs = overallTimeoutMs.coerceAtMost(DEFAULT_OVERALL_TIMEOUT_MS)
        if (budgetMs <= 0) return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.toLong())
        if (request.model.isBlank() || request.model.length > MAX_MODEL_CHARS || request.model.any(Char::isISOControl)) {
            return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        }
        if (request.prompt.length > MAX_PROMPT_CHARS || request.prompt.any { it.isISOControl() && it !in "\n\r\t\b\u000C" }) {
            return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        }
        val configuration = ProviderConfiguration(request.provider, request.endpoint)
        val validation = ProviderConfigurationValidator.validate(configuration, request.apiKey)
        if (validation is ValidationResult.Invalid) {
            return ProviderPolishResult.Failure(
                if (validation.reason == ValidationReason.API_KEY_REQUIRED) {
                    ProviderFailureKind.NO_API_KEY
                } else {
                    ProviderFailureKind.INVALID_CONFIGURATION
                },
            )
        }
        if (cancellation.isCancelled) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)

        val adapter = ProviderAdapters.of(request.provider)
        val plan = try {
            adapter.polishPlan(request, endpointOverrides[request.provider])
        } catch (_: RuntimeException) {
            null
        } ?: return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        // The assembled body is judged BEFORE the first attempt: the fixed prompt lowers the largest
        // transcript that fits, and a body over the cap must never cost a network round trip (#4).
        if ((plan.body?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) > HttpProviderTransport.MAX_REQUEST_BYTES) {
            return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        }
        val effectiveConnectTimeoutMs = connectTimeoutMs.coerceAtMost(MAX_CONNECT_TIMEOUT_MS)
        val effectiveReadTimeoutMs = readTimeoutMs.coerceAtMost(MAX_READ_TIMEOUT_MS)
        if (effectiveConnectTimeoutMs <= 0 || effectiveReadTimeoutMs <= 0) {
            return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        }
        var attempt = 0
        while (true) {
            // Cancellation always wins, before and after an attempt, ahead of the deadline and the verdict.
            if (cancellation.isCancelled) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            val remaining = ProviderDeadlines.remainingMillis(deadline)
            if (remaining <= 0) return ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
            val result = attemptOnce(adapter, plan, cancellation, remaining, effectiveConnectTimeoutMs, effectiveReadTimeoutMs)
            if (cancellation.isCancelled) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            if (result !is ProviderPolishResult.Failure) return result
            if (attempt >= maxRetries || retryDelaysMs.isEmpty() || !adapter.isRetryable(result)) return result
            val delay = retryDelaysMs[minOf(attempt, retryDelaysMs.size - 1)].coerceAtLeast(0L)
            if (ProviderDeadlines.remainingMillis(deadline) - delay <= 0) return result
            logWarn("Cloud retry ${attempt + 1}/$maxRetries after ${delay}ms (kind=${result.kind} status=${result.statusCode})")
            if (!delayUnlessCancelled(cancellation, delay)) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            attempt++
        }
    }

    /** One physical attempt: the transport result classified as the polish request's outcome. */
    private fun attemptOnce(
        adapter: ProviderAdapter,
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        overallTimeoutMs: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ProviderPolishResult = when (val transport = transport.run(plan, cancellation, overallTimeoutMs, connectTimeoutMs, readTimeoutMs)) {
        is Transport.Failed -> ProviderPolishResult.Failure(transport.kind, transport.status)
        is Transport.Response -> if (transport.status >= 400) {
            // The error body is classified HERE into a closed signal and goes no further (#77).
            ProviderPolishResult.Failure(
                ProviderFailureKind.HTTP_ERROR,
                transport.status,
                adapter.errorSignal(transport.status, transport.body),
            )
        } else {
            parseResponse(plan.responseFormat, transport.body)
        }
    }

    /**
     * The retry delay: a latch the cancel hook releases, never a bare sleep, and its registration is
     * closed like the request's own. @return false when the wait ended by cancellation.
     */
    private fun delayUnlessCancelled(cancellation: ProviderCancellation, delayMs: Long): Boolean {
        if (cancellation.isCancelled) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val registration = cancellation.onCancel { latch.countDown() }
        try {
            latch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            registration.close()
        }
        return !cancellation.isCancelled
    }

    /**
     * Asks the provider's model-list endpoint whether [apiKey] works (#61): a GET with the same auth
     * headers the polish request uses, no body, no user content. Every transport failure is a verdict
     * of "unverified", never "rejected": only a status the provider actually sent can reject a key.
     */
    override fun check(provider: Provider, apiKey: String): ProviderKeyCheck {
        val adapter = ProviderAdapters.of(provider)
        val url = adapter.listUrl(keyCheckOverrides[provider]) ?: return ProviderKeyCheck.NotApplicable
        if (apiKey.isBlank() || apiKey.any(Char::isISOControl)) {
            return ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST)
        }
        val plan = RequestPlan(url, adapter.authHeaders(apiKey), body = null, responseFormat = ProviderReplyFormat.NONE, method = "GET")
        // The constructor's timeouts still apply (a test shortens them); the check never waits past macOS's 15 s.
        val verdict = when (
            val transport = transport.run(
                plan,
                ProviderCancellation(),
                overallTimeoutMs.coerceIn(1, KEY_CHECK_TIMEOUT_MS),
                connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS),
                readTimeoutMs.coerceIn(1, KEY_CHECK_TIMEOUT_MS),
            )
        ) {
            is Transport.Failed -> ProviderKeyCheck.Unverified(
                when (transport.kind) {
                    ProviderFailureKind.NETWORK -> PolishFailure.UNREACHABLE
                    ProviderFailureKind.TIMEOUT -> PolishFailure.TIMED_OUT
                    ProviderFailureKind.NO_API_KEY,
                    ProviderFailureKind.INVALID_CONFIGURATION,
                    ProviderFailureKind.CANCELLED,
                    ProviderFailureKind.HTTP_ERROR,
                    ProviderFailureKind.MALFORMED_RESPONSE,
                    ProviderFailureKind.RESPONSE_TOO_LARGE,
                    ProviderFailureKind.REDIRECT_REJECTED,
                    -> PolishFailure.BAD_REQUEST
                },
                transport.status,
            )
            is Transport.Response -> adapter.keyCheckVerdict(transport.status, transport.body)
        }
        // Provider, status and verdict only: never the key, never the body.
        logInfo("Key check: $provider status=${keyCheckStatus(verdict)} verdict=${verdict::class.simpleName}")
        return verdict
    }

    /**
     * The live model list (#84), the macOS `discoverModels` shape: the list GET (Claude paginated), the
     * pure filter, then a five-token probe per model on [PROBE_EXECUTOR] so one request worker always
     * stays free for a polish request, all under one whole-operation deadline. A probe that answers
     * about the KEY (401, or a KEY_REJECTED body) refuses the whole discovery; a transport failure never
     * locks a row. Nothing here carries user content: the list has no body and the probe says "Hi".
     */
    override fun discoverModels(provider: Provider, apiKey: String): ProviderDiscovery {
        val adapter = ProviderAdapters.of(provider)
        val listOverride = keyCheckOverrides[provider]
        var url = adapter.firstListPage(listOverride) ?: return ProviderDiscovery.Refused(ProviderKeyCheck.NotApplicable)
        if (apiKey.isBlank() || apiKey.any(Char::isISOControl)) {
            return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST))
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(discoveryTimeoutMs.coerceAtLeast(1).toLong())
        fun remaining(): Int = ProviderDeadlines.remainingMillis(deadline)

        // The list, page by page for the one provider that pages.
        val rows = mutableListOf<ListedModel>()
        val seenCursors = HashSet<String>()
        var pages = 0
        while (pages < MAX_LIST_PAGES) {
            pages++
            val plan = RequestPlan(url, adapter.authHeaders(apiKey), body = null, responseFormat = ProviderReplyFormat.NONE, method = "GET")
            val left = remaining()
            if (left <= 0) return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.TIMED_OUT))
            when (val transport = transport.run(plan, ProviderCancellation(), left.coerceAtMost(KEY_CHECK_TIMEOUT_MS), connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS), readTimeoutMs.coerceIn(1, KEY_CHECK_TIMEOUT_MS))) {
                is Transport.Failed -> {
                    if (rows.isNotEmpty()) break // a later page failed: the rows so far are the list
                    return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(unverifiedFailure(transport.kind), transport.status))
                }
                is Transport.Response -> {
                    if (transport.status != 200) {
                        if (rows.isNotEmpty()) break
                        return when (val verdict = adapter.keyCheckVerdict(transport.status, transport.body)) {
                            ProviderKeyCheck.Accepted -> ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, transport.status))
                            else -> ProviderDiscovery.Refused(verdict)
                        }
                    }
                    val page = adapter.parseModelRows(transport.body)
                    if (page == null) {
                        // A malformed LATER page keeps the rows already fetched; a malformed first page is a refusal.
                        if (rows.isEmpty()) return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 200))
                        logWarn("Discovery: $provider page $pages malformed")
                        break
                    }
                    rows += page.rows
                    when (val next = adapter.nextListPage(page, seenCursors, listOverride)) {
                        ListAdvance.Done -> break
                        ListAdvance.Malformed -> { logWarn("Discovery: $provider pagination malformed after $pages page(s)"); break }
                        is ListAdvance.Next -> url = next.url
                    }
                }
            }
        }
        val listed = rows.size
        val kept = adapter.filterModelRows(rows)
        if (kept.isEmpty()) {
            logInfo("Discovery: $provider listed=$listed kept=0")
            return ProviderDiscovery.Listed(emptyList(), System.currentTimeMillis())
        }

        // The probes, at most MAX_PROBES of them, three in flight, every one bounded by the remaining time.
        //
        // NEWEST FIRST, so the budget is spent on the rows the list will show at the top and on the models
        // `recommendedPick` may choose from (#104 review round 1). Provider order is not the user's order:
        // his key lists 69 OpenAI models and only 40 can be probed, so under list order the untested tail
        // was arbitrary and could have contained the newest thing he owns. Under this it is the OLDEST 29.
        //
        // Gemini publishes no dates, measured 2026-09-02, so for it this is a stable no-op and its tail is
        // still list order. That is the reason its dates are researched into `ModelNotes` instead.
        val toProbe = kept
            .sortedWith(compareBy({ it.releasedAt == null }, { -(it.releasedAt ?: 0L) }))
            .take(MAX_PROBES)
        val futures = toProbe.map { row ->
            PROBE_EXECUTOR.submit<ProbeOutcome> { probe(adapter, row.id, apiKey, remaining()) }
        }
        val access = HashMap<String, ModelAccess>()
        var cutOff = 0
        try {
            futures.forEachIndexed { index, future ->
                // A probe the deadline cuts off is UNVERIFIED, never locked; the list still comes back.
                val left = remaining()
                val outcome = if (left <= 0) null else try {
                    future.get(left.toLong(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                }
                when (outcome) {
                    null -> cutOff++
                    is ProbeOutcome.KeyRejected -> {
                        logInfo("Discovery: $provider probe rejected the key status=${outcome.status}")
                        return ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(outcome.status))
                    }
                    is ProbeOutcome.Access -> access[toProbe[index].id] = outcome.access
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.UNEXPECTED))
        } catch (_: ExecutionException) {
            return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.UNEXPECTED))
        } finally {
            futures.forEach { it.cancel(true) }
        }
        val models = ModelListRules.sort(
            kept.map { row ->
                DiscoveredModel(
                    id = row.id,
                    displayName = adapter.displayName(row.id, row.displayName),
                    access = access[row.id] ?: ModelAccess.UNVERIFIED,
                    recommended = ModelListRules.isRecommended(row.id),
                    // The date the provider sent, carried through rather than re-derived. Dropping it here
                    // left OpenAI and Claude in discovery order under a header reading "Newest first",
                    // and the presentation tests could not see it because they injected dates straight
                    // into DiscoveredModel and never ran this mapping (#101 review round 1).
                    releasedAt = row.releasedAt,
                )
            },
        )
        logInfo(
            "Discovery: $provider listed=$listed kept=${kept.size} probed=${toProbe.size} cutOff=$cutOff " +
                "available=${models.count { it.access == ModelAccess.AVAILABLE }} unverified=${models.count { it.access == ModelAccess.UNVERIFIED }}",
        )
        return ProviderDiscovery.Listed(models, System.currentTimeMillis())
    }

    private fun unverifiedFailure(kind: ProviderFailureKind): PolishFailure = when (kind) {
        ProviderFailureKind.NETWORK -> PolishFailure.UNREACHABLE
        ProviderFailureKind.TIMEOUT -> PolishFailure.TIMED_OUT
        ProviderFailureKind.NO_API_KEY,
        ProviderFailureKind.INVALID_CONFIGURATION,
        ProviderFailureKind.CANCELLED,
        ProviderFailureKind.HTTP_ERROR,
        ProviderFailureKind.MALFORMED_RESPONSE,
        ProviderFailureKind.RESPONSE_TOO_LARGE,
        ProviderFailureKind.REDIRECT_REJECTED,
        -> PolishFailure.BAD_REQUEST
    }

    /**
     * One attempt's verdict, with the status and the reading that produced it, so the caller can tell WHY
     * it is unverified: the provider answered and said nothing useful, or it never answered at all.
     */
    private class ProbeAttempt(val outcome: ProbeOutcome, val status: Int?, val reply: ModelListRules.ProbeReply?)

    /**
     * One probe: the polish request's own plan with the fixed word "Hi" and a tiny output cap, and ONE
     * retry when that cap told us nothing.
     *
     * **The retry can only IMPROVE a verdict, never worsen one**, which is what makes it safe to send a
     * request some models refuse outright. Both suppressions are rejected with HTTP 400 by models that
     * cannot honour them — `reasoning.effort` on every OpenAI model that does not reason, `thinkingBudget:
     * 0` on `gemini-2.5-pro` — and a 400 on the retry simply leaves the first answer standing.
     *
     * Why it is needed at all, measured 2026-09-02 against live keys: `gpt-5-nano` spends the ENTIRE output
     * cap on reasoning at 64, 128 and 256 tokens, so no cap alone reaches it, and `gpt-5-mini` and
     * `gpt-5-nano` are the two newest models the founder's key can reach (#103). Asked not to reason, both
     * answer in about 30 tokens.
     */
    private fun probe(adapter: ProviderAdapter, model: String, apiKey: String, remainingMs: Int): ProbeOutcome {
        // ONE DEADLINE FOR THE WHOLE LOGICAL PROBE, clamped to a single `probeTimeoutMs` and fixed before
        // the first ask. Both asks then share it by construction, which is why there is no test for the
        // arithmetic: the two attempts cannot each take a full timeout because there is only one budget to
        // spend (review round 4).
        //
        // What it prevents: handing the retry a fresh budget let one model run for nearly twice the probe
        // timeout, and with three workers in the pool several slow reasoning models could hold it past the
        // discovery deadline, leaving later models unverified — the very state this retry exists to clear.
        val budget = remainingMs.coerceAtMost(probeTimeoutMs.coerceAtLeast(1))
        if (budget <= 0) return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.toLong())
        val first = probeOnce(adapter, model, apiKey, deadline, ProbeStyle.DEFAULT)
            ?: return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        // ONLY a model that ANSWERED and said nothing useful is worth asking again, and 200 is the whole
        // condition: every other status has already decided the model, and at 200 an inconclusive reading
        // is the only way to reach UNVERIFIED.
        //
        // Two ways this went wrong, both found in review. Gating on the reading alone spent a second
        // request on a 403, whose empty body reads as inconclusive while the model is plainly refused.
        // Gating on the VERDICT alone still fired on 429 and 5xx, whose error envelopes also read as
        // inconclusive, so the retry doubled traffic during a rate limit or an outage and spent the
        // discovery deadline without testing another model.
        if (first.status != 200 || first.reply != ModelListRules.ProbeReply.INCONCLUSIVE) return first.outcome
        val retry = probeOnce(adapter, model, apiKey, deadline, ProbeStyle.NO_REASONING) ?: return first.outcome
        // A key that stopped working between the two asks is NEWS, and the one thing the retry may report
        // that is not an improvement. It cannot be raised by the suppression being unsupported: neither
        // "Unsupported parameter: 'reasoning.effort'" nor "This model only works in thinking mode" is a
        // key marker to the adapters' `errorSignal`, whose OpenAI 400 branch never answers KEY_REJECTED
        // at all and whose Gemini one requires `API_KEY_INVALID`.
        if (retry.outcome is ProbeOutcome.KeyRejected) return retry.outcome
        return if (retry.reply == ModelListRules.ProbeReply.TEXT) retry.outcome else first.outcome
    }

    /**
     * One request to one model, spending whatever is left of the LOGICAL PROBE's deadline. Null when this
     * provider has no plan for this style, or when the deadline has already gone, so there is nothing to ask.
     *
     * It takes a deadline rather than a duration on purpose: a duration was clamped to `probeTimeoutMs`
     * here, once per call, so two calls could spend two full timeouts.
     */
    private fun probeOnce(adapter: ProviderAdapter, model: String, apiKey: String, deadline: Long, style: ProbeStyle): ProbeAttempt? {
        val plan = adapter.probePlan(model, apiKey, style, endpointOverrides[adapter.provider]) ?: return null
        val budget = ProviderDeadlines.remainingMillis(deadline)
        if (budget <= 0) return null
        return when (val transport = transport.run(plan, ProviderCancellation(), budget, connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS), readTimeoutMs.coerceIn(1, budget))) {
            // A transport failure has no body at all; the null status makes this UNVERIFIED before the
            // reply is ever read, and the null reading stops it earning a retry it cannot use.
            is Transport.Failed ->
                ProbeAttempt(adapter.probeOutcome(null, null, ModelListRules.ProbeReply.NO_TEXT), null, null)
            is Transport.Response -> {
                val reply = probeReply(plan.responseFormat, transport.body)
                ProbeAttempt(
                    adapter.probeOutcome(transport.status, transport.body, reply),
                    transport.status,
                    reply,
                )
            }
        }
    }

    private fun keyCheckStatus(verdict: ProviderKeyCheck): Int? = when (verdict) {
        ProviderKeyCheck.Accepted -> 200
        ProviderKeyCheck.NotApplicable -> null
        is ProviderKeyCheck.Rejected -> verdict.status
        is ProviderKeyCheck.Denied -> verdict.status
        is ProviderKeyCheck.Unverified -> verdict.status
    }

    private fun parseResponse(format: ProviderReplyFormat, body: String): ProviderPolishResult {
        val parsed = ProviderJson.parseOrNull(body) ?: return ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        val text = format.replyText(parsed.root)
        return if (text == null || text.isEmpty() || !ProviderPolishPrompt.isTranscriptOnly(text)) {
            ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        } else {
            ProviderPolishResult.Success(text)
        }
    }

    /**
     * What this probe body carried, judged the way polish judges a real reply.
     *
     * A body that will not parse is NO_TEXT rather than inconclusive: every provider here answers a 200
     * with JSON, so one that does not is not a model this app can use.
     */
    private fun probeReply(format: ProviderReplyFormat, body: String): ModelListRules.ProbeReply {
        val parsed = ProviderJson.parseOrNull(body) ?: return ModelListRules.ProbeReply.NO_TEXT
        if (!format.replyText(parsed.root).isNullOrEmpty()) return ModelListRules.ProbeReply.TEXT
        return if (format.endedOfItsOwnAccord(parsed.root)) ModelListRules.ProbeReply.NO_TEXT else ModelListRules.ProbeReply.INCONCLUSIVE
    }

    internal companion object {
        /** Three probes in flight, leaving one request worker free for non-probe provider work in this process (#84). */
        val PROBE_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            3,
            ThreadFactory { runnable ->
                Thread(runnable, "provider-probe").apply { isDaemon = true }
            },
        )
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val DEFAULT_OVERALL_TIMEOUT_MS = 30_000
        const val MAX_CONNECT_TIMEOUT_MS = 10_000
        const val MAX_READ_TIMEOUT_MS = 60_000
        const val MAX_OVERALL_TIMEOUT_MS = 60_000
        const val MAX_MODEL_CHARS = 256
        const val MAX_PROMPT_CHARS = 100_000
        /** macOS's discovery timeout; one phone reading is never a calibration, so the reference value is kept. */
        const val KEY_CHECK_TIMEOUT_MS = 15_000
        /** The live model list (#84): one whole-operation deadline (the Mac has none; a phone needs one), a per-probe cap, page and probe counts. */
        const val DISCOVERY_TIMEOUT_MS = 60_000
        const val PROBE_TIMEOUT_MS = 10_000
        const val MAX_LIST_PAGES = 10
        const val MAX_PROBES = 40
        /** The Mac's retry policy (#4): two retries, 1 s then 3 s, all inside the one polish deadline. */
        const val MAX_RETRIES = 2
        val RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L)
    }
}
