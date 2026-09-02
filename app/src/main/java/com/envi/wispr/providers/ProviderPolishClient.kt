package com.envi.wispr.providers

import com.envi.wispr.debug.DebugLogger

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
) {
    fun polish(
        request: ProviderPolishRequest,
        cancellation: ProviderCancellation = ProviderCancellation(),
    ): ProviderPolishResult {
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
        val effectiveConnectTimeoutMs = connectTimeoutMs.coerceAtMost(MAX_CONNECT_TIMEOUT_MS)
        val effectiveReadTimeoutMs = readTimeoutMs.coerceAtMost(MAX_READ_TIMEOUT_MS)
        val effectiveOverallTimeoutMs = overallTimeoutMs.coerceAtMost(MAX_OVERALL_TIMEOUT_MS)
        if (effectiveConnectTimeoutMs <= 0 || effectiveReadTimeoutMs <= 0 || effectiveOverallTimeoutMs <= 0) {
            return ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveOverallTimeoutMs.toLong())
        val activeConnection = ActiveConnection()
        val future: Future<ProviderPolishResult> = REQUEST_EXECUTOR.submit<ProviderPolishResult> {
            executeRequest(request.provider, plan, cancellation, deadline, activeConnection, effectiveConnectTimeoutMs, effectiveReadTimeoutMs)
        }
        val cancelRegistration = cancellation.onCancel {
            activeConnection.disconnect()
            future.cancel(true)
        }
        return try {
            future.get(effectiveOverallTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            activeConnection.disconnect()
            future.cancel(true)
            ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
        } catch (_: CancellationException) {
            if (cancellation.isCancelled) ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            else ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
        } catch (_: InterruptedException) {
            activeConnection.disconnect()
            future.cancel(true)
            Thread.currentThread().interrupt()
            ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
        } catch (failure: ExecutionException) {
            DebugLogger.warn(TAG, "Cloud request failed: ${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}")
            if (cancellation.isCancelled) ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            else ProviderPolishResult.Failure(ProviderFailureKind.NETWORK)
        } finally {
            cancelRegistration.close()
            activeConnection.disconnect()
        }
    }

    private fun executeRequest(
        provider: Provider,
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        deadline: Long,
        activeConnection: ActiveConnection,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ProviderPolishResult {
        var connection: HttpURLConnection? = null
        return try {
            ensureActive(cancellation, deadline)
            val remainingMs = remainingMillis(deadline)
            if (remainingMs <= 0) {
                ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
            } else {
                connection = (plan.url.toURL().openConnection() as? HttpURLConnection)
                    ?: return ProviderPolishResult.Failure(ProviderFailureKind.NETWORK)
                activeConnection.set(connection)
                connection.apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = minTimeout(connectTimeoutMs, remainingMs)
                    readTimeout = minTimeout(readTimeoutMs, remainingMs)
                    setRequestProperty("Content-Type", "application/json")
                    plan.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                val body = plan.body.toByteArray(StandardCharsets.UTF_8)
                if (body.size > MAX_REQUEST_BYTES) {
                    ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION)
                } else {
                    connection.outputStream.use { it.write(body) }
                    ensureActive(cancellation, deadline)
                    val status = connection.responseCode
                    if (status in 300..399) {
                        ProviderPolishResult.Failure(ProviderFailureKind.REDIRECT_REJECTED, status)
                    } else if (status >= 400) {
                        // The error body is read to completion and classified HERE into a closed signal;
                        // a read failure keeps its own kind with the status (#77).
                        when (val response = readResponse(connection, status, cancellation, deadline)) {
                            is ResponseRead.Failure -> ProviderPolishResult.Failure(response.kind, status)
                            is ResponseRead.Success -> ProviderPolishResult.Failure(
                                ProviderFailureKind.HTTP_ERROR,
                                status,
                                ProviderErrorSignal.classify(provider, status, response.body),
                            )
                        }
                    } else {
                        val response = readResponse(connection, status, cancellation, deadline)
                        if (response is ResponseRead.Failure) {
                            ProviderPolishResult.Failure(response.kind, status)
                        } else {
                            parseResponse(plan.responseFormat, (response as ResponseRead.Success).body)
                        }
                    }
                }
            }
        } catch (_: ProviderCancelledException) {
            ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
        } catch (_: ProviderTimedOutException) {
            ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
        } catch (_: SocketTimeoutException) {
            if (cancellation.isCancelled) ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            else ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
        } catch (failure: IOException) {
            // Shape only, never content: the exception class names the layer that refused (#77).
            DebugLogger.warn(TAG, "Cloud request failed: ${failure.javaClass.simpleName}")
            if (cancellation.isCancelled) ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            else if (System.nanoTime() >= deadline) ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
            else ProviderPolishResult.Failure(ProviderFailureKind.NETWORK)
        } catch (failure: RuntimeException) {
            DebugLogger.warn(TAG, "Cloud request failed: ${failure.javaClass.simpleName}")
            if (cancellation.isCancelled) ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED)
            else if (System.nanoTime() >= deadline) ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT)
            else ProviderPolishResult.Failure(ProviderFailureKind.NETWORK)
        } finally {
            activeConnection.clear(connection)
            connection?.disconnect()
        }
    }

    private fun requestPlan(request: ProviderPolishRequest): RequestPlan? {
        val override = endpointOverrides[request.provider]
        return when (request.provider) {
            Provider.OPENAI -> RequestPlan(
                url = cloudOrOverride(override, OPENAI_URL),
                headers = mapOf("Authorization" to "Bearer ${request.apiKey}"),
                body = "{\"model\":${jsonString(request.model)},\"instructions\":${jsonString(ProviderPolishPrompt.SYSTEM_INSTRUCTION)},\"input\":${jsonString(request.prompt)},\"store\":false}",
                responseFormat = ResponseFormat.OPENAI_RESPONSES,
            )
            Provider.GEMINI -> {
                val base = cloudOrOverride(override, GEMINI_URL_PREFIX + encodePath(request.model) + ":generateContent")
                RequestPlan(
                    url = base,
                    headers = mapOf("x-goog-api-key" to request.apiKey.orEmpty()),
                    body = "{\"systemInstruction\":{\"parts\":[{\"text\":${jsonString(ProviderPolishPrompt.SYSTEM_INSTRUCTION)}}]},\"contents\":[{\"parts\":[{\"text\":${jsonString(request.prompt)}}]}]}",
                    responseFormat = ResponseFormat.GEMINI,
                )
            }
            Provider.CLAUDE -> RequestPlan(
                url = cloudOrOverride(override, CLAUDE_URL),
                headers = mapOf(
                    "x-api-key" to request.apiKey.orEmpty(),
                    "anthropic-version" to ANTHROPIC_VERSION,
                ),
                body = "{\"model\":${jsonString(request.model)},\"max_tokens\":$CLAUDE_MAX_OUTPUT_TOKENS,\"system\":${jsonString(ProviderPolishPrompt.SYSTEM_INSTRUCTION)},\"messages\":[{\"role\":\"user\",\"content\":${jsonString(request.prompt)}}]}",
                responseFormat = ResponseFormat.CLAUDE,
            )
            Provider.SELF_HOSTED_POLISH -> {
                val endpoint = request.endpoint ?: return null
                val path = when (request.selfHostedProtocol) {
                    SelfHostedProtocol.OPENAI_COMPATIBLE -> "/v1/chat/completions"
                    SelfHostedProtocol.OLLAMA -> "/api/chat"
                }
                val url = resolveSelfHostedEndpoint(endpoint, path) ?: return null
                RequestPlan(
                    url = url,
                    headers = if (request.apiKey.isNullOrEmpty()) emptyMap() else mapOf("Authorization" to "Bearer ${request.apiKey}"),
                    // Both protocols take the same chat body; only the path and the answer's shape differ.
                    body = "{\"model\":${jsonString(request.model)},\"messages\":[{\"role\":\"system\",\"content\":${jsonString(ProviderPolishPrompt.SYSTEM_INSTRUCTION)}},{\"role\":\"user\",\"content\":${jsonString(request.prompt)}}],\"stream\":false}",
                    responseFormat = when (request.selfHostedProtocol) {
                        SelfHostedProtocol.OPENAI_COMPATIBLE -> ResponseFormat.OPENAI_CHAT
                        SelfHostedProtocol.OLLAMA -> ResponseFormat.OLLAMA
                    },
                )
            }
        }
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
        val body: String,
        val responseFormat: ResponseFormat,
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
    }

    private sealed interface ResponseRead {
        data class Success(val body: String) : ResponseRead
        data class Failure(val kind: ProviderFailureKind) : ResponseRead
    }

    private class ProviderCancelledException : IOException()
    private class ProviderTimedOutException : IOException()

    private companion object {
        val REQUEST_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            4,
            ThreadFactory { runnable ->
                Thread(runnable, "provider-polish").apply { isDaemon = true }
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
