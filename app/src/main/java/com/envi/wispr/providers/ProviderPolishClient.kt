package com.envi.wispr.providers

import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.polish.PolishFailure

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * The cloud polish request (#189): validation, the one deadline from entry, the #4 retry loop and the
 * reading of the reply. Every provider decision comes from the [ProviderAdapter]; the round trip from the
 * [ProviderTransport]; the key check and the model list live in [ProviderModelDiscoveryClient]. It
 * deliberately does not log request bodies, response bodies, endpoint credentials, or API keys. The caller
 * can keep the raw transcript when every provider fails, without this layer ever persisting or exposing it.
 */
internal class ProviderPolishClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val overallTimeoutMs: Int = DEFAULT_OVERALL_TIMEOUT_MS,
    /** Test-only endpoint overrides allow local HttpServer coverage without changing cloud origins. */
    private val endpointOverrides: Map<Provider, String> = emptyMap(),
    /** Where the content-free diagnostics go; a JVM test passes no-ops so android.util.Log is never touched. */
    private val logInfo: (String) -> Unit = { DebugLogger.log(PROVIDER_LOG_TAG, it) },
    private val logWarn: (String) -> Unit = { DebugLogger.warn(PROVIDER_LOG_TAG, it) },
    /** The retry policy's bounds (#4), the Mac's two retries at 1 s then 3 s; a test shortens or disables them. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val maxRetries: Int = MAX_RETRIES,
    /** The HTTP round trip; the production default, a test may pass a fake. */
    private val transport: ProviderTransport = HttpProviderTransport(logWarn),
) {
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

    private fun parseResponse(format: ProviderReplyFormat, body: String): ProviderPolishResult {
        val parsed = ProviderJson.parseOrNull(body) ?: return ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        val text = format.replyText(parsed.root)
        return if (text == null || text.isEmpty() || !ProviderPolishPrompt.isTranscriptOnly(text)) {
            ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE)
        } else {
            ProviderPolishResult.Success(text)
        }
    }

    internal companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val DEFAULT_OVERALL_TIMEOUT_MS = 30_000
        const val MAX_CONNECT_TIMEOUT_MS = 10_000
        const val MAX_READ_TIMEOUT_MS = 60_000
        const val MAX_OVERALL_TIMEOUT_MS = 60_000
        const val MAX_MODEL_CHARS = 256
        const val MAX_PROMPT_CHARS = 100_000
        /** The Mac's retry policy (#4): two retries, 1 s then 3 s, all inside the one polish deadline. */
        const val MAX_RETRIES = 2
        val RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 3_000L)
    }
}
