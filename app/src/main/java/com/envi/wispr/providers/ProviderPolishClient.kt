package com.envi.wispr.providers

import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.polish.PolishFailure

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class SelfHostedProtocol {
    OPENAI_COMPATIBLE,
    OLLAMA,
}

/**
 * A self-hosted endpoint is accepted only as explicit user configuration. Callers must not fill
 * it from transcript text, provider responses, redirects, or other untrusted input; validation
 * constrains the URI, while the caller-owned settings layer must establish that provenance.
 */
data class ProviderPolishRequest(
    val provider: Provider,
    val model: String,
    val prompt: String,
    val apiKey: String? = null,
    val endpoint: String? = null,
    val selfHostedProtocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE,
) {
    override fun toString(): String =
        "ProviderPolishRequest(provider=$provider, model=<redacted>, prompt=<redacted>, apiKey=<redacted>)"
}

enum class ProviderFailureKind {
    NO_API_KEY,
    INVALID_CONFIGURATION,
    NETWORK,
    TIMEOUT,
    CANCELLED,
    HTTP_ERROR,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    REDIRECT_REJECTED,
}

sealed interface ProviderPolishResult {
    data class Success(val text: String) : ProviderPolishResult {
        override fun toString(): String = "Success(text=<redacted>)"
    }
    data class Failure(
        val kind: ProviderFailureKind,
        val statusCode: Int? = null,
        /** Set only on `HTTP_ERROR`, from the provider's error body, which itself goes no further. */
        val signal: ProviderErrorSignal? = null,
    ) : ProviderPolishResult
}

/**
 * What a provider's error BODY said beyond its status (#77), as a closed signal so the body, which can
 * echo the prompt, never leaves this client. The markers are the ones the macOS connectors match.
 */
enum class ProviderErrorSignal {
    KEY_REJECTED,
    OUT_OF_CREDITS,
    INPUT_TOO_LONG,
    CONTENT_BLOCKED,
    ;

    companion object {
        /** Exhaustive over [Provider]; a provider with no body markers answers null for every body. */
        fun classify(provider: Provider, status: Int, body: String): ProviderErrorSignal? = when (provider) {
            Provider.OPENAI -> when (status) {
                429 -> if (body.contains("insufficient_quota")) OUT_OF_CREDITS else null
                400 -> when {
                    body.contains("context_length_exceeded") -> INPUT_TOO_LONG
                    body.contains("content_filter") || body.contains("content_policy") -> CONTENT_BLOCKED
                    else -> null
                }
                else -> null
            }
            Provider.GEMINI -> when (status) {
                400 -> when {
                    body.contains("API_KEY_INVALID") -> KEY_REJECTED
                    body.contains("exceeds the maximum number of tokens") -> INPUT_TOO_LONG
                    body.contains("PROHIBITED_CONTENT") || body.contains("blockReason") -> CONTENT_BLOCKED
                    else -> null
                }
                else -> null
            }
            Provider.CLAUDE -> when (status) {
                400 -> when {
                    body.contains("credit balance") -> OUT_OF_CREDITS
                    body.contains("prompt is too long") -> INPUT_TOO_LONG
                    else -> null
                }
                else -> null
            }
            Provider.SELF_HOSTED_POLISH -> null
        }
    }
}

/** Cancellation is thread-safe and can interrupt a request that is blocked in HttpURLConnection. */
class ProviderCancellation {
    private val lock = Any()
    @Volatile private var cancelled = false
    private val callbacks = mutableListOf<() -> Unit>()

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        val snapshot = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        snapshot.forEach { callback -> callback() }
    }

    internal fun onCancel(callback: () -> Unit): AutoCloseable {
        val invokeImmediately = synchronized(lock) {
            if (cancelled) true else {
                callbacks += callback
                false
            }
        }
        if (invokeImmediately) callback()
        return AutoCloseable { synchronized(lock) { callbacks.remove(callback) } }
    }
}

/**
 * Small platform-only provider client. It deliberately does not log request bodies, response
 * bodies, endpoint credentials, or API keys. The caller can keep the raw transcript when every
 * provider fails, without this layer ever persisting or exposing it.
 */
class ProviderPolishClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val overallTimeoutMs: Int = DEFAULT_OVERALL_TIMEOUT_MS,
    /** Test-only endpoint overrides allow local HttpServer coverage without changing cloud origins. */
    private val endpointOverrides: Map<Provider, String> = emptyMap(),
    /** Test-only overrides for the key-check (model-list) endpoints, the same shape as [endpointOverrides]. */
    private val keyCheckOverrides: Map<Provider, String> = emptyMap(),
    /** Where the content-free diagnostics go; a JVM test passes no-ops so android.util.Log is never touched. */
    private val logInfo: (String) -> Unit = { DebugLogger.log(TAG, it) },
    private val logWarn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
    /** The live model list's bounds (#84); a test shortens them. */
    private val discoveryTimeoutMs: Int = DISCOVERY_TIMEOUT_MS,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
    /** The retry policy's bounds (#4), the Mac's two retries at 1 s then 3 s; a test shortens or disables them. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val maxRetries: Int = MAX_RETRIES,
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

        val plan = try {
            requestPlan(request, probe = null)
        } catch (_: RuntimeException) {
            null
        } ?: return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        // The assembled body is judged BEFORE the first attempt: the fixed prompt lowers the largest
        // transcript that fits, and a body over the cap must never cost a network round trip (#4).
        if ((plan.body?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) > MAX_REQUEST_BYTES) {
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
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) return ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
            val result = attemptOnce(request.provider, plan, cancellation, remaining, effectiveConnectTimeoutMs, effectiveReadTimeoutMs)
            if (cancellation.isCancelled) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            if (result !is ProviderPolishResult.Failure) return result
            if (attempt >= maxRetries || retryDelaysMs.isEmpty() || !ProviderRetryPolicy.isRetryable(result, request.provider)) return result
            val delay = retryDelaysMs[minOf(attempt, retryDelaysMs.size - 1)].coerceAtLeast(0L)
            if (remainingMillis(deadline) - delay <= 0) return result
            logWarn("Cloud retry ${attempt + 1}/$maxRetries after ${delay}ms (kind=${result.kind} status=${result.statusCode})")
            if (!delayUnlessCancelled(cancellation, delay)) return ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            attempt++
        }
    }

    /** One physical attempt: the transport result classified as the polish request's outcome. */
    private fun attemptOnce(
        provider: Provider,
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        overallTimeoutMs: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ProviderPolishResult = when (val transport = run(plan, cancellation, overallTimeoutMs, connectTimeoutMs, readTimeoutMs)) {
        is Transport.Failed -> ProviderPolishResult.Failure(transport.kind, transport.status)
        is Transport.Response -> if (transport.status >= 400) {
            // The error body is classified HERE into a closed signal and goes no further (#77).
            ProviderPolishResult.Failure(
                ProviderFailureKind.HTTP_ERROR,
                transport.status,
                ProviderErrorSignal.classify(provider, transport.status, transport.body),
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
        val url = when (provider) {
            Provider.OPENAI -> cloudOrOverride(keyCheckOverrides[provider], OPENAI_MODELS_URL)
            Provider.GEMINI -> cloudOrOverride(keyCheckOverrides[provider], GEMINI_MODELS_URL)
            Provider.CLAUDE -> cloudOrOverride(keyCheckOverrides[provider], CLAUDE_MODELS_URL)
            Provider.SELF_HOSTED_POLISH -> return ProviderKeyCheck.NotApplicable
        }
        if (apiKey.isBlank() || apiKey.any(Char::isISOControl)) {
            return ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST)
        }
        val plan = RequestPlan(url, authHeaders(provider, apiKey), body = null, responseFormat = ResponseFormat.NONE, method = "GET")
        // The constructor's timeouts still apply (a test shortens them); the check never waits past macOS's 15 s.
        val verdict = when (
            val transport = run(
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
            is Transport.Response -> classifyKeyCheck(provider, transport.status, transport.body)
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
        val listUrl = when (provider) {
            Provider.OPENAI -> cloudOrOverride(keyCheckOverrides[provider], OPENAI_MODELS_URL)
            Provider.GEMINI -> cloudOrOverride(keyCheckOverrides[provider], GEMINI_MODELS_URL)
            Provider.CLAUDE -> cloudOrOverride(keyCheckOverrides[provider], CLAUDE_MODELS_URL)
            Provider.SELF_HOSTED_POLISH -> return ProviderDiscovery.Refused(ProviderKeyCheck.NotApplicable)
        }
        if (apiKey.isBlank() || apiKey.any(Char::isISOControl)) {
            return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST))
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(discoveryTimeoutMs.coerceAtLeast(1).toLong())
        fun remaining(): Int = remainingMillis(deadline)

        // The list, page by page for Claude.
        val rows = mutableListOf<ListedModel>()
        var afterId: String? = null
        val seenCursors = HashSet<String>()
        var pages = 0
        while (pages < MAX_LIST_PAGES) {
            pages++
            val url = if (provider == Provider.CLAUDE) {
                URI(listUrl.toString() + (if (listUrl.rawQuery == null) "?" else "&") + "limit=1000" + (afterId?.let { "&after_id=${encodePath(it)}" } ?: ""))
            } else listUrl
            val plan = RequestPlan(url, authHeaders(provider, apiKey), body = null, responseFormat = ResponseFormat.NONE, method = "GET")
            val left = remaining()
            if (left <= 0) return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.TIMED_OUT))
            when (val transport = run(plan, ProviderCancellation(), left.coerceAtMost(KEY_CHECK_TIMEOUT_MS), connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS), readTimeoutMs.coerceIn(1, KEY_CHECK_TIMEOUT_MS))) {
                is Transport.Failed -> {
                    if (rows.isNotEmpty()) break // a later page failed: the rows so far are the list
                    return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(unverifiedFailure(transport.kind), transport.status))
                }
                is Transport.Response -> {
                    if (transport.status != 200) {
                        if (rows.isNotEmpty()) break
                        return when (val verdict = classifyKeyCheck(provider, transport.status, transport.body)) {
                            ProviderKeyCheck.Accepted -> ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, transport.status))
                            else -> ProviderDiscovery.Refused(verdict)
                        }
                    }
                    val page = parseModelRows(provider, transport.body)
                    if (page == null) {
                        // A malformed LATER page keeps the rows already fetched; a malformed first page is a refusal.
                        if (rows.isEmpty()) return ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 200))
                        logWarn("Discovery: $provider page $pages malformed")
                        break
                    }
                    rows += page.rows
                    if (provider != Provider.CLAUDE) break
                    when (val next = ModelListRules.claudePagination(page.hasMore, page.lastId, seenCursors)) {
                        ModelListRules.Pagination.Stop -> break
                        ModelListRules.Pagination.Malformed -> { logWarn("Discovery: CLAUDE pagination malformed after $pages page(s)"); break }
                        is ModelListRules.Pagination.Continue -> { seenCursors += next.afterId; afterId = next.afterId }
                    }
                }
            }
        }
        val listed = rows.size
        val kept = ModelListRules.filter(provider, rows)
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
            PROBE_EXECUTOR.submit<ProbeOutcome> { probe(provider, row.id, apiKey, remaining()) }
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
                    displayName = ModelListRules.displayName(provider, row.id, row.displayName),
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

    /** How a probe asks. The retry exists only because a reasoning model can spend the whole cap thinking. */
    private enum class ProbeStyle {
        /** The cheap first ask: the provider's own defaults and a tiny output cap. */
        DEFAULT,

        /** The one retry: ask the model not to reason, and leave room for the answer itself. */
        NO_REASONING,
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
    private fun probe(provider: Provider, model: String, apiKey: String, remainingMs: Int): ProbeOutcome {
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
        val first = probeOnce(provider, model, apiKey, deadline, ProbeStyle.DEFAULT)
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
        val retry = probeOnce(provider, model, apiKey, deadline, ProbeStyle.NO_REASONING) ?: return first.outcome
        // A key that stopped working between the two asks is NEWS, and the one thing the retry may report
        // that is not an improvement. It cannot be raised by the suppression being unsupported: neither
        // "Unsupported parameter: 'reasoning.effort'" nor "This model only works in thinking mode" is a
        // key marker to `ProviderErrorSignal.classify`, whose OpenAI 400 branch never answers KEY_REJECTED
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
    private fun probeOnce(provider: Provider, model: String, apiKey: String, deadline: Long, style: ProbeStyle): ProbeAttempt? {
        val plan = requestPlan(ProviderPolishRequest(provider, model, PROBE_TEXT, apiKey), probe = style) ?: return null
        val budget = remainingMillis(deadline)
        if (budget <= 0) return null
        return when (val transport = run(plan, ProviderCancellation(), budget, connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS), readTimeoutMs.coerceIn(1, budget))) {
            // A transport failure has no body at all; the null status makes this UNVERIFIED before the
            // reply is ever read, and the null reading stops it earning a retry it cannot use.
            is Transport.Failed ->
                ProbeAttempt(ModelListRules.probeOutcome(provider, null, null, ModelListRules.ProbeReply.NO_TEXT), null, null)
            is Transport.Response -> {
                val reply = probeReply(plan.responseFormat, transport.body)
                ProbeAttempt(
                    ModelListRules.probeOutcome(provider, transport.status, transport.body, reply),
                    transport.status,
                    reply,
                )
            }
        }
    }

    private class ModelPage(val rows: List<ListedModel>, val hasMore: Boolean, val lastId: String?)

    /** The provider's own list shape; null when the body is not that shape. */
    /**
     * Anthropic's `created_at`, e.g. `2026-08-28T00:00:00Z`, as epoch millis. Null on anything unparseable
     * rather than a guessed date: an undated model sorts after the dated ones, which is visible and
     * honest, while a wrong date silently reorders the list and nobody can see why.
     *
     * `Instant.parse` is API 26 and minSdk is 30, so there is no desugaring question here.
     */
    private fun parseIso8601(value: String?): Long? =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parseModelRows(provider: Provider, body: String): ModelPage? {
        val root = try {
            JsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        val map = root as? Map<*, *> ?: return null
        return when (provider) {
            Provider.OPENAI -> {
                val data = map["data"] as? List<*> ?: return null
                // `created` is unix SECONDS here; the app's clock is millis everywhere else.
                ModelPage(
                    data.mapNotNull { entry ->
                        val row = entry as? Map<*, *> ?: return@mapNotNull null
                        val id = row["id"] as? String ?: return@mapNotNull null
                        ListedModel(id, null, (row["created"] as? Number)?.toLong()?.times(1000L))
                    },
                    false,
                    null,
                )
            }
            Provider.GEMINI -> {
                val list = map["models"] as? List<*> ?: return null
                ModelPage(
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
            Provider.CLAUDE -> {
                val data = map["data"] as? List<*> ?: return null
                ModelPage(
                    data.mapNotNull { entry ->
                        val row = entry as? Map<*, *> ?: return@mapNotNull null
                        val id = row["id"] as? String ?: return@mapNotNull null
                        ListedModel(id, row["display_name"] as? String, parseIso8601(row["created_at"] as? String))
                    },
                    map["has_more"] as? Boolean ?: false,
                    map["last_id"] as? String,
                )
            }
            Provider.SELF_HOSTED_POLISH -> null
        }
    }

    private fun keyCheckStatus(verdict: ProviderKeyCheck): Int? = when (verdict) {
        ProviderKeyCheck.Accepted -> 200
        ProviderKeyCheck.NotApplicable -> null
        is ProviderKeyCheck.Rejected -> verdict.status
        is ProviderKeyCheck.Denied -> verdict.status
        is ProviderKeyCheck.Unverified -> verdict.status
    }

    private fun classifyKeyCheck(provider: Provider, status: Int, body: String): ProviderKeyCheck = when {
        status == 200 -> if (hasModelList(provider, body)) ProviderKeyCheck.Accepted else ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        status in 201..299 -> ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        status == 401 -> ProviderKeyCheck.Rejected(status)
        // Gemini answers a wrong key on this endpoint with 403 (the macOS reference maps it the same way).
        status == 403 -> if (provider == Provider.GEMINI) ProviderKeyCheck.Rejected(status) else ProviderKeyCheck.Denied(status)
        status == 400 && ProviderErrorSignal.classify(provider, status, body) == ProviderErrorSignal.KEY_REJECTED -> ProviderKeyCheck.Rejected(status)
        status == 429 -> ProviderKeyCheck.Unverified(if (provider == Provider.GEMINI) PolishFailure.RATE_OR_QUOTA else PolishFailure.RATE_LIMITED, status)
        status in 500..599 -> ProviderKeyCheck.Unverified(PolishFailure.PROVIDER_ERROR, status)
        status in 400..499 -> ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, status)
        else -> ProviderKeyCheck.Unverified(PolishFailure.UNEXPECTED, status)
    }

    /** A 200 counts only when the body is the provider's own list envelope, so a captive portal cannot accept a key. */
    private fun hasModelList(provider: Provider, body: String): Boolean {
        val root = try {
            JsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: StackOverflowError) {
            return false
        }
        val field = when (provider) {
            Provider.OPENAI, Provider.CLAUDE -> "data"
            Provider.GEMINI -> "models"
            Provider.SELF_HOSTED_POLISH -> return false
        }
        return (root as? Map<*, *>)?.get(field) is List<*>
    }

    /** The executor, the overall deadline and the cancel hook, shared by the polish request and the key check. */
    private fun run(
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        overallTimeoutMs: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Transport {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(overallTimeoutMs.toLong())
        val activeConnection = ActiveConnection()
        val future: Future<Transport> = REQUEST_EXECUTOR.submit<Transport> {
            executeRequest(plan, cancellation, deadline, activeConnection, connectTimeoutMs, readTimeoutMs)
        }
        val cancelRegistration = cancellation.onCancel {
            activeConnection.disconnect()
            future.cancel(true)
        }
        return try {
            future.get(overallTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            activeConnection.disconnect()
            future.cancel(true)
            Transport.Failed(ProviderFailureKind.TIMEOUT)
        } catch (_: CancellationException) {
            if (cancellation.isCancelled) Transport.Failed(ProviderFailureKind.CANCELLED)
            else Transport.Failed(ProviderFailureKind.TIMEOUT)
        } catch (_: InterruptedException) {
            activeConnection.disconnect()
            future.cancel(true)
            Thread.currentThread().interrupt()
            Transport.Failed(ProviderFailureKind.CANCELLED)
        } catch (failure: ExecutionException) {
            logWarn("Cloud request failed: ${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}")
            if (cancellation.isCancelled) Transport.Failed(ProviderFailureKind.CANCELLED)
            else Transport.Failed(ProviderFailureKind.NETWORK)
        } finally {
            cancelRegistration.close()
            activeConnection.disconnect()
        }
    }

    /**
     * One connection: the status the provider sent with its body, or the failure that stopped the read.
     * A read failure on an error status keeps that status beside its kind, so a caller can never mistake
     * an unread 401 for a verdict (#61).
     */
    private fun executeRequest(
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        deadline: Long,
        activeConnection: ActiveConnection,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Transport {
        var connection: HttpURLConnection? = null
        // The status the provider already sent, kept beside any failure that follows it (#61).
        var observedStatus: Int? = null
        return try {
            ensureActive(cancellation, deadline)
            val remainingMs = remainingMillis(deadline)
            if (remainingMs <= 0) {
                Transport.Failed(ProviderFailureKind.TIMEOUT)
            } else {
                connection = (plan.url.toURL().openConnection() as? HttpURLConnection)
                    ?: return Transport.Failed(ProviderFailureKind.NETWORK)
                activeConnection.set(connection)
                val body = plan.body?.toByteArray(StandardCharsets.UTF_8)
                connection.apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = plan.method
                    doOutput = body != null
                    connectTimeout = minTimeout(connectTimeoutMs, remainingMs)
                    readTimeout = minTimeout(readTimeoutMs, remainingMs)
                    if (body != null) setRequestProperty("Content-Type", "application/json")
                    plan.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                if (body != null && body.size > MAX_REQUEST_BYTES) {
                    Transport.Failed(ProviderFailureKind.INVALID_CONFIGURATION)
                } else {
                    if (body != null) connection.outputStream.use { it.write(body) }
                    ensureActive(cancellation, deadline)
                    val status = connection.responseCode.also { observedStatus = it }
                    if (status in 300..399) {
                        Transport.Failed(ProviderFailureKind.REDIRECT_REJECTED, status)
                    } else {
                        when (val response = readResponse(connection, status, cancellation, deadline)) {
                            is ResponseRead.Failure -> Transport.Failed(response.kind, status)
                            is ResponseRead.Success -> Transport.Response(status, response.body)
                        }
                    }
                }
            }
        } catch (_: ProviderCancelledException) {
            Transport.Failed(ProviderFailureKind.CANCELLED, observedStatus)
        } catch (_: ProviderTimedOutException) {
            Transport.Failed(ProviderFailureKind.TIMEOUT, observedStatus)
        } catch (_: SocketTimeoutException) {
            Transport.Failed(if (cancellation.isCancelled) ProviderFailureKind.CANCELLED else ProviderFailureKind.TIMEOUT, observedStatus)
        } catch (failure: IOException) {
            // Shape only, never content: the exception class names the layer that refused (#77).
            logWarn("Cloud request failed: ${failure.javaClass.simpleName}")
            Transport.Failed(failureKindAfter(cancellation, deadline), observedStatus)
        } catch (failure: RuntimeException) {
            logWarn("Cloud request failed: ${failure.javaClass.simpleName}")
            Transport.Failed(failureKindAfter(cancellation, deadline), observedStatus)
        } finally {
            activeConnection.clear(connection)
            connection?.disconnect()
        }
    }

    /**
     * [probe] builds the discovery probe (#84): the same URL and headers, the fixed word "Hi" as the
     * only input, no system instruction, a tiny output cap, and `store:false` kept for OpenAI.
     */
    private fun requestPlan(request: ProviderPolishRequest, probe: ProbeStyle?): RequestPlan? {
        val override = endpointOverrides[request.provider]
        return when (request.provider) {
            Provider.OPENAI -> RequestPlan(
                url = cloudOrOverride(override, OPENAI_URL),
                headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                body = if (probe != null) {
                    // The Responses API's smallest accepted cap is 16.
                    val model = jsonString(request.model)
                    when (probe) {
                        ProbeStyle.DEFAULT ->
                            "{\"model\":$model,\"input\":${jsonString(PROBE_TEXT)},\"max_output_tokens\":$OPENAI_PROBE_OUTPUT_TOKENS,\"store\":false}"
                        // `reasoning.effort` is REJECTED with 400 by every model that does not reason
                        // (measured 2026-09-02 on gpt-4.1-mini, gpt-4o-mini and gpt-3.5-turbo), which is
                        // exactly why it is only ever sent on a retry that cannot make a verdict worse.
                        ProbeStyle.NO_REASONING ->
                            "{\"model\":$model,\"input\":${jsonString(PROBE_TEXT)},\"max_output_tokens\":$PROBE_RETRY_OUTPUT_TOKENS," +
                                "\"reasoning\":{\"effort\":\"minimal\"},\"store\":false}"
                    }
                } else {
                    "{\"model\":${jsonString(request.model)},\"instructions\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))},\"input\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))},\"store\":false}"
                },
                responseFormat = ResponseFormat.OPENAI_RESPONSES,
            )
            Provider.GEMINI -> {
                // Gemini names the model in the PATH, so a test override keeps that shape (the fake server
                // tells probes apart by it); the real URL is unchanged.
                val base = if (override != null) URI(override.trimEnd('/') + "/" + encodePath(request.model) + ":generateContent")
                else URI(GEMINI_URL_PREFIX + encodePath(request.model) + ":generateContent")
                RequestPlan(
                    url = base,
                    headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                    body = if (probe != null) {
                        val text = jsonString(PROBE_TEXT)
                        when (probe) {
                            ProbeStyle.DEFAULT ->
                                "{\"contents\":[{\"parts\":[{\"text\":$text}]}],\"generationConfig\":{\"maxOutputTokens\":$PROBE_OUTPUT_TOKENS}}"
                            // `thinkingBudget: 0` is REJECTED with 400 by a model that cannot stop thinking
                            // ("Budget 0 is invalid. This model only works in thinking mode", measured
                            // 2026-09-02 on gemini-2.5-pro), and frees the cap on one that can.
                            ProbeStyle.NO_REASONING ->
                                "{\"contents\":[{\"parts\":[{\"text\":$text}]}],\"generationConfig\":{\"maxOutputTokens\":$PROBE_RETRY_OUTPUT_TOKENS," +
                                    "\"thinkingConfig\":{\"thinkingBudget\":0}}}"
                        }
                    } else {
                        "{\"systemInstruction\":{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))}}]},\"contents\":[{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}]}"
                    },
                    responseFormat = ResponseFormat.GEMINI,
                )
            }
            Provider.CLAUDE -> RequestPlan(
                url = cloudOrOverride(override, CLAUDE_URL),
                headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                body = if (probe != null) {
                    // Anthropic's extended thinking is OPT IN and this request does not opt in, so there is
                    // nothing to suppress and no retry to make: measured 2026-09-02, both models the
                    // founder's key reaches answer within a 5-token cap.
                    if (probe == ProbeStyle.NO_REASONING) return null
                    "{\"model\":${jsonString(request.model)},\"max_tokens\":$PROBE_OUTPUT_TOKENS,\"messages\":[{\"role\":\"user\",\"content\":${jsonString(PROBE_TEXT)}}]}"
                } else {
                    "{\"model\":${jsonString(request.model)},\"max_tokens\":$CLAUDE_MAX_OUTPUT_TOKENS,\"system\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))},\"messages\":[{\"role\":\"user\",\"content\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}"
                },
                responseFormat = ResponseFormat.CLAUDE,
            )
            Provider.SELF_HOSTED_POLISH -> {
                if (probe != null) return null
                val endpoint = request.endpoint ?: return null
                val path = when (request.selfHostedProtocol) {
                    SelfHostedProtocol.OPENAI_COMPATIBLE -> "/v1/chat/completions"
                    SelfHostedProtocol.OLLAMA -> "/api/chat"
                }
                val url = resolveSelfHostedEndpoint(endpoint, path) ?: return null
                RequestPlan(
                    url = url,
                    headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                    // Both protocols take the same chat body; only the path and the answer's shape differ.
                    body = "{\"model\":${jsonString(request.model)},\"messages\":[{\"role\":\"system\",\"content\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))}},{\"role\":\"user\",\"content\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}],\"stream\":false}",
                    responseFormat = when (request.selfHostedProtocol) {
                        SelfHostedProtocol.OPENAI_COMPATIBLE -> ResponseFormat.OPENAI_CHAT
                        SelfHostedProtocol.OLLAMA -> ResponseFormat.OLLAMA
                    },
                )
            }
        }
    }

    /** The one place a cloud provider's auth header is spelled, for the polish request and the key check alike. */
    private fun authHeaders(provider: Provider, apiKey: String): Map<String, String> = when (provider) {
        Provider.OPENAI -> mapOf("Authorization" to "Bearer $apiKey")
        Provider.GEMINI -> mapOf("x-goog-api-key" to apiKey)
        Provider.CLAUDE -> mapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION)
        Provider.SELF_HOSTED_POLISH -> if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
    }

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

    private fun cloudOrOverride(override: String?, defaultUrl: String): URI {
        return URI(override ?: defaultUrl)
    }

    private fun failureKindAfter(cancellation: ProviderCancellation, deadline: Long): ProviderFailureKind = when {
        cancellation.isCancelled -> ProviderFailureKind.CANCELLED
        System.nanoTime() >= deadline -> ProviderFailureKind.TIMEOUT
        else -> ProviderFailureKind.NETWORK
    }

    private fun readResponse(
        connection: HttpURLConnection,
        status: Int,
        cancellation: ProviderCancellation,
        deadline: Long,
    ): ResponseRead {
        val declared = connection.getHeaderFieldLong("Content-Length", -1L)
        if (declared > MAX_RESPONSE_BYTES) return ResponseRead.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE)
        // An error response with no body has a null error stream on Android; that is an EMPTY body, not a
        // network failure (#79): the status still names what happened.
        val stream = if (status >= 400) {
            connection.errorStream ?: return ResponseRead.Success("")
        } else {
            connection.inputStream ?: return ResponseRead.Failure(ProviderFailureKind.NETWORK)
        }
        stream.use { input ->
            val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 16 * 1024))
            val buffer = ByteArray(4096)
            while (true) {
                ensureActive(cancellation, deadline)
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_RESPONSE_BYTES) {
                    return ResponseRead.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE)
                }
            }
            return ResponseRead.Success(output.toString(StandardCharsets.UTF_8.name()))
        }
    }

    private fun parseResponse(format: ResponseFormat, body: String): ProviderPolishResult {
        val root = try {
            JsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        } catch (_: StackOverflowError) {
            return ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        }
        val text = replyText(format, root)
        return if (text == null || text.isEmpty() || !ProviderPolishPrompt.isTranscriptOnly(text)) {
            ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        } else {
            ProviderPolishResult.Success(text)
        }
    }

    /**
     * The reply text a parsed body carries, trimmed, or null when it carries none.
     *
     * ONE owner for "where does this provider put its answer", used by [parseResponse] and by the model
     * probe (#104 review round 1). The probe used to look for a `"text"` label in the raw body, which is a
     * second reading of the same envelope and disagreed with this one in both directions: a multipart reply
     * whose FIRST part is empty reads as no answer, and a whitespace-only answer reads as an answer even
     * though polish rejects it. A model must not be offered or hidden on a judgement the polish path does
     * not share.
     *
     * The judgement that stays HERE and out of the probe is [ProviderPolishPrompt.isTranscriptOnly]: it
     * asks whether a polish reply is the transcript rather than commentary about it, and the probe sends
     * the word "Hi" rather than a transcript, so applying it would refuse working models.
     */
    private fun replyText(format: ResponseFormat, root: Any?): String? = when (format) {
        ResponseFormat.OPENAI_RESPONSES -> root.firstMessageTextAt("output")
        ResponseFormat.OPENAI_CHAT -> root.stringAt("choices", 0, "message", "content")
        ResponseFormat.GEMINI -> root.firstTextAt("candidates", 0, "content", "parts")
        ResponseFormat.CLAUDE -> root.firstTextAt("content")
        ResponseFormat.OLLAMA -> root.stringAt("message", "content") ?: root.stringAt("response")
        ResponseFormat.NONE -> null
    }?.substringAfterLast("</think>")?.trim()

    /**
     * What this probe body carried, judged the way polish judges a real reply.
     *
     * A body that will not parse is NO_TEXT rather than inconclusive: every provider here answers a 200
     * with JSON, so one that does not is not a model this app can use.
     */
    private fun probeReply(format: ResponseFormat, body: String): ModelListRules.ProbeReply {
        val root = try {
            JsonParser(body).parse()
        } catch (_: IllegalArgumentException) {
            return ModelListRules.ProbeReply.NO_TEXT
        } catch (_: StackOverflowError) {
            return ModelListRules.ProbeReply.NO_TEXT
        }
        if (!replyText(format, root).isNullOrEmpty()) return ModelListRules.ProbeReply.TEXT
        return if (endedOfItsOwnAccord(format, root)) ModelListRules.ProbeReply.NO_TEXT else ModelListRules.ProbeReply.INCONCLUSIVE
    }

    /**
     * Did the model finish because it had finished, rather than because something stopped it?
     *
     * **Asked in the positive on purpose.** Listing the ways a reply can be cut short — an output cap, a
     * safety block, a recitation block, a language refusal, a tool-call fault — is a list that needs
     * extending whenever a provider adds a reason, and every missing entry silently condemns a working
     * model. Normal completion is ONE value per provider and providers do not add new ways to succeed.
     *
     * An absent, misspelt or unexpected marker therefore reads as "not proved", which is the safe answer
     * in both directions: the model stays on screen and is never chosen for the user.
     *
     * Exhaustive with no `else`, so a new response format must declare its own value.
     * `ResponseFormat.NONE` is the key check and never asks for text at all.
     */
    private fun endedOfItsOwnAccord(format: ResponseFormat, root: Any?): Boolean = when (format) {
        // Measured 2026-09-02: gpt-4.1-mini answers `completed` with text at the probe's 16-token cap,
        // while gpt-5-mini and gpt-5-nano answer `incomplete` / `max_output_tokens` with none.
        ResponseFormat.OPENAI_RESPONSES -> root.stringAt("status") == "completed"
        ResponseFormat.OPENAI_CHAT -> root.stringAt("choices", 0, "finish_reason") == "stop"
        ResponseFormat.GEMINI -> root.stringAt("candidates", 0, "finishReason") == "STOP"
        // Anthropic ends a normal turn with `end_turn`, or with `stop_sequence` when one was matched. We
        // send no stop sequences, so only the first is reachable today; both are the model finishing.
        // Documented rather than measured: reaching it needs a Claude model that writes nothing at all,
        // and both models the founder's key reaches answered within the cap on 2026-09-02.
        ResponseFormat.CLAUDE -> root.stringAt("stop_reason") in setOf("end_turn", "stop_sequence")
        ResponseFormat.OLLAMA -> root.stringAt("done_reason") == "stop"
        ResponseFormat.NONE -> false
    }

    private fun ensureActive(cancellation: ProviderCancellation, deadline: Long) {
        if (cancellation.isCancelled) throw ProviderCancelledException()
        if (System.nanoTime() >= deadline) throw ProviderTimedOutException()
    }

    private fun remainingMillis(deadline: Long): Int {
        val nanos = deadline - System.nanoTime()
        return if (nanos <= 0) 0 else minOf(Int.MAX_VALUE.toLong(), TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1)).toInt()
    }

    private fun minTimeout(configured: Int, remaining: Int): Int = minOf(configured, remaining).coerceAtLeast(1)

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private class ActiveConnection {
        @Volatile private var connection: HttpURLConnection? = null

        fun set(value: HttpURLConnection) {
            connection = value
        }

        fun clear(value: HttpURLConnection?) {
            if (connection === value) connection = null
        }

        fun disconnect() {
            connection?.disconnect()
        }
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private data class RequestPlan(
        val url: URI,
        val headers: Map<String, String>,
        /** Null sends no body and no Content-Type: the key check's GET. */
        val body: String?,
        val responseFormat: ResponseFormat,
        val method: String = "POST",
    ) {
        override fun toString(): String =
            "RequestPlan(url=<redacted>, headers=<redacted>, body=<redacted>, responseFormat=$responseFormat)"
    }

    private enum class ResponseFormat {
        OPENAI_RESPONSES,
        OPENAI_CHAT,
        GEMINI,
        CLAUDE,
        OLLAMA,
        /** The key check: the body is judged by [hasModelList], never parsed for text. */
        NONE,
    }

    /** What one connection produced, before any parsing: the provider's status with its body, or the failure that stopped the read. */
    private sealed interface Transport {
        data class Response(val status: Int, val body: String) : Transport
        data class Failed(val kind: ProviderFailureKind, val status: Int? = null) : Transport
    }

    private sealed interface ResponseRead {
        data class Success(val body: String) : ResponseRead
        data class Failure(val kind: ProviderFailureKind) : ResponseRead
    }

    private class ProviderCancelledException : IOException()
    private class ProviderTimedOutException : IOException()

    internal companion object {
        val REQUEST_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            4,
            ThreadFactory { runnable ->
                Thread(runnable, "provider-polish").apply { isDaemon = true }
            },
        )

        /** Three probes in flight, each waiting on one request worker, so one of the four stays free for polish (#84). */
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
        const val MAX_REQUEST_BYTES = 256 * 1024
        private const val TAG = "ProviderPolishClient"
        const val MAX_RESPONSE_BYTES = 512 * 1024

        /** The Anthropic API requires `max_tokens`; the same value macOS sends, so a long dictation's polish is not cut off (#79). */
        const val CLAUDE_MAX_OUTPUT_TOKENS = 8192
        const val MAX_MODEL_CHARS = 256
        const val MAX_PROMPT_CHARS = 100_000
        const val OPENAI_URL = "https://api.openai.com/v1/responses"
        const val GEMINI_URL_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** The key check's endpoints (#61): free model lists, no user content; the macOS reference uses the same three. */
        const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
        const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CLAUDE_MODELS_URL = "https://api.anthropic.com/v1/models"
        /** macOS's discovery timeout; one phone reading is never a calibration, so the reference value is kept. */
        const val KEY_CHECK_TIMEOUT_MS = 15_000
        /** The live model list (#84): one whole-operation deadline (the Mac has none; a phone needs one), a per-probe cap, page and probe counts. */
        const val DISCOVERY_TIMEOUT_MS = 60_000
        const val PROBE_TIMEOUT_MS = 10_000
        const val MAX_LIST_PAGES = 10
        const val MAX_PROBES = 40
        const val PROBE_TEXT = "Hi"
        const val PROBE_OUTPUT_TOKENS = 5
        const val OPENAI_PROBE_OUTPUT_TOKENS = 16

        /**
         * The retry's cap. Measured 2026-09-02: asked not to reason, `gpt-5-mini` answers "Hi" in 27 output
         * tokens and `gpt-5-nano` in 28, so 16 is not enough room for the ANSWER even once the thinking is
         * gone. Only models whose first probe told us nothing ever spend this.
         */
        const val PROBE_RETRY_OUTPUT_TOKENS = 64
        /** The Mac's retry policy (#4): two retries, 1 s then 3 s, all inside the one polish deadline. */
        const val MAX_RETRIES = 2
        val RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L)
    }
}

private fun Any?.valueAt(vararg path: Any): Any? {
    var value: Any? = this
    for (part in path) {
        value = when (part) {
            is String -> (value as? Map<*, *>)?.get(part)
            is Int -> (value as? List<*>)?.getOrNull(part)
            else -> null
        }
        if (value == null) return null
    }
    return value
}

private fun Any?.stringAt(vararg path: Any): String? = valueAt(*path) as? String

private fun Any?.firstTextAt(vararg path: Any): String? =
    (valueAt(*path) as? List<*>)
        ?.asSequence()
        ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
        ?.firstOrNull { it.isNotEmpty() }

/**
 * OpenAI's Responses API `output` array holds typed items — `message`, `reasoning`, tool and
 * function calls among them — and a reasoning model can place a `reasoning` item before the
 * assistant's own `message` item. Reading a fixed index (`output[0]`) breaks the moment that
 * happens, silently, as `MALFORMED_RESPONSE`. This instead finds the first item whose `type` is
 * either absent (accepted for backward compatibility with a minimal payload shape) or exactly
 * `"message"`, skipping any other typed item ahead of it — matching OpenAI's own migration
 * guidance to iterate items by type rather than assume position (found and fixed in code review
 * on issue #62, 2026-09-01; full parser gap tracked separately as #65 for the remaining formats
 * this file does not touch here).
 */
private fun Any?.firstMessageTextAt(vararg path: Any): String? =
    (valueAt(*path) as? List<*>)
        ?.asSequence()
        ?.filterIsInstance<Map<*, *>>()
        ?.firstOrNull { item -> (item["type"] as? String)?.let { it == "message" } ?: true }
        ?.get("content")
        .firstTextAt()

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(index == input.length) { "trailing JSON" }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        require(index < input.length) { "missing value" }
        return when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("invalid JSON")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (consume(']')) return result
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            val char = input[index++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < input.length) { "unfinished escape" }
                    when (val escaped = input[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= input.length) { "short unicode escape" }
                            result.append(input.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("invalid escape")
                    }
                }
                else -> {
                    require(char.code >= 0x20) { "control in string" }
                    result.append(char)
                }
            }
        }
        throw IllegalArgumentException("unfinished string")
    }

    private fun parseNumber(): Number {
        val start = index
        consume('-')
        if (consume('0')) Unit else {
            require(index < input.length && input[index] in '1'..'9') { "invalid number" }
            while (index < input.length && input[index].isDigit()) index++
        }
        if (consume('.')) {
            require(index < input.length && input[index].isDigit()) { "invalid fraction" }
            while (index < input.length && input[index].isDigit()) index++
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            require(index < input.length && input[index].isDigit()) { "invalid exponent" }
            while (index < input.length && input[index].isDigit()) index++
        }
        return input.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException("invalid number")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        require(input.startsWith(literal, index)) { "invalid literal" }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun expect(char: Char) {
        require(index < input.length && input[index++] == char) { "expected $char" }
    }

    private fun consume(char: Char): Boolean {
        if (index < input.length && input[index] == char) {
            index++
            return true
        }
        return false
    }
}
