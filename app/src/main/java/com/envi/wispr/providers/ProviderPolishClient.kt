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
            requestPlan(request)
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
        val toProbe = kept.take(MAX_PROBES)
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

    /** One probe: the polish request's own plan with the fixed word "Hi" and a tiny output cap. */
    private fun probe(provider: Provider, model: String, apiKey: String, remainingMs: Int): ProbeOutcome {
        val plan = requestPlan(ProviderPolishRequest(provider, model, PROBE_TEXT, apiKey), probe = true)
            ?: return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        if (remainingMs <= 0) return ProbeOutcome.Access(ModelAccess.UNVERIFIED)
        val budget = remainingMs.coerceAtMost(probeTimeoutMs.coerceAtLeast(1))
        return when (val transport = run(plan, ProviderCancellation(), budget, connectTimeoutMs.coerceIn(1, MAX_CONNECT_TIMEOUT_MS), readTimeoutMs.coerceIn(1, budget))) {
            is Transport.Failed -> ModelListRules.probeOutcome(provider, null, null)
            is Transport.Response -> ModelListRules.probeOutcome(provider, transport.status, transport.body)
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
    private fun requestPlan(request: ProviderPolishRequest, probe: Boolean = false): RequestPlan? {
        val override = endpointOverrides[request.provider]
        return when (request.provider) {
            Provider.OPENAI -> RequestPlan(
                url = cloudOrOverride(override, OPENAI_URL),
                headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                body = if (probe) {
                    // The Responses API's smallest accepted cap is 16.
                    "{\"model\":${jsonString(request.model)},\"input\":${jsonString(PROBE_TEXT)},\"max_output_tokens\":$OPENAI_PROBE_OUTPUT_TOKENS,\"store\":false}"
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
                    body = if (probe) {
                        "{\"contents\":[{\"parts\":[{\"text\":${jsonString(PROBE_TEXT)}}]}],\"generationConfig\":{\"maxOutputTokens\":$PROBE_OUTPUT_TOKENS}}"
                    } else {
                        "{\"systemInstruction\":{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))}}]},\"contents\":[{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}]}"
                    },
                    responseFormat = ResponseFormat.GEMINI,
                )
            }
            Provider.CLAUDE -> RequestPlan(
                url = cloudOrOverride(override, CLAUDE_URL),
                headers = authHeaders(request.provider, request.apiKey.orEmpty()),
                body = if (probe) {
                    "{\"model\":${jsonString(request.model)},\"max_tokens\":$PROBE_OUTPUT_TOKENS,\"messages\":[{\"role\":\"user\",\"content\":${jsonString(PROBE_TEXT)}}]}"
                } else {
                    "{\"model\":${jsonString(request.model)},\"max_tokens\":$CLAUDE_MAX_OUTPUT_TOKENS,\"system\":${jsonString(ProviderPolishPrompt.systemInstruction(request.prompt))},\"messages\":[{\"role\":\"user\",\"content\":${jsonString(ProviderPolishPrompt.userMessage(request.prompt))}}]}"
                },
                responseFormat = ResponseFormat.CLAUDE,
            )
            Provider.SELF_HOSTED_POLISH -> {
                if (probe) return null
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
        val text = when (format) {
            ResponseFormat.OPENAI_RESPONSES -> root.firstMessageTextAt("output")
            ResponseFormat.OPENAI_CHAT -> root.stringAt("choices", 0, "message", "content")
            ResponseFormat.GEMINI -> root.firstTextAt("candidates", 0, "content", "parts")
            ResponseFormat.CLAUDE -> root.firstTextAt("content")
            ResponseFormat.OLLAMA -> root.stringAt("message", "content") ?: root.stringAt("response")
            ResponseFormat.NONE -> null
        }?.substringAfterLast("</think>")?.trim()
        return if (text == null || text.isEmpty() || !ProviderPolishPrompt.isTranscriptOnly(text)) {
            ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        } else {
            ProviderPolishResult.Success(text)
        }
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
