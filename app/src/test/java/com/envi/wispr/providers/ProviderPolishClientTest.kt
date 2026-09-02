package com.envi.wispr.providers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.envi.wispr.polish.PolishFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPolishClientTest {
    @Test fun openAiResponsesRequestUsesBearerAndParsesText() = withServer(
        response = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"clean result\"}]}]}",
        inspect = { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer openai-test-key", request.headers["authorization"])
            assertEquals("application/json", request.headers["content-type"])
            val body = request.body
            assertTrue(body.contains("\"store\":false"))
            assertTrue(body.contains("Return only their cleaned-up text, nothing else."))
            assertTrue(body.contains("You are capturing their writing, not talking with them."))
            assertTrue(body.contains("line 1\\nline 2"))
        },
    ) { endpoint ->
        val result = client(endpoint).polish(
            ProviderPolishRequest(Provider.OPENAI, "gpt-test", "line 1\nline 2", "openai-test-key"),
        )
        assertEquals(ProviderPolishResult.Success("clean result"), result)
    }

    @Test fun openAiResponsesSkipsALeadingReasoningItemAndParsesTheMessageAfterIt() = withServer(
        // Regression for a real bug caught in code review: OpenAI's Responses API can place a
        // `reasoning` item before the assistant's own `message` item for a reasoning-capable model.
        // Reading a fixed `output[0]` breaks the moment that happens; this fixture reproduces it.
        response = "{\"output\":[" +
            "{\"type\":\"reasoning\",\"summary\":[]}," +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"clean result\"}]}" +
            "]}",
    ) { endpoint ->
        val result = client(endpoint).polish(
            ProviderPolishRequest(Provider.OPENAI, "gpt-5-mini", "line 1\nline 2", "openai-test-key"),
        )
        assertEquals(ProviderPolishResult.Success("clean result"), result)
    }

    @Test fun geminiRequestUsesGoogleHeaderAndParsesCandidate() = withServer(
        response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"gemini result\"}]}}]}",
        inspect = { request ->
            assertEquals("gemini-test-key", request.headers["x-goog-api-key"])
            assertTrue(request.body.contains("systemInstruction"))
            assertTrue(request.body.contains("Return only their cleaned-up text, nothing else."))
            assertTrue(request.path.endsWith("/gemini-test:generateContent"))
        },
    ) { endpoint ->
        val result = client("$endpoint/v1beta/models/gemini-test:generateContent", Provider.GEMINI).polish(
            ProviderPolishRequest(Provider.GEMINI, "gemini-test", "hello", "gemini-test-key"),
        )
        assertEquals(ProviderPolishResult.Success("gemini result"), result)
    }

    @Test fun claudeRequestUsesRequiredHeadersAndParsesContentBlock() = withServer(
        response = "{\"content\":[{\"type\":\"text\",\"text\":\"claude result\"}]}",
        inspect = { request ->
            assertEquals("claude-test-key", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            assertTrue(request.body.contains("\"system\":"))
            assertTrue(request.body.contains("Return only their cleaned-up text, nothing else."))
        },
    ) { endpoint ->
        val result = client(endpoint, Provider.CLAUDE).polish(
            ProviderPolishRequest(Provider.CLAUDE, "claude-test", "hello", "claude-test-key"),
        )
        assertEquals(ProviderPolishResult.Success("claude result"), result)
    }

    @Test fun selfHostedOllamaStaysUnderValidatedBasePath() = withServer(
        response = "{\"message\":{\"role\":\"assistant\",\"content\":\"ollama result\"}}",
        inspect = { request ->
            assertEquals("/configured/api/chat", request.path)
            assertEquals("Bearer local-test-key", request.headers["authorization"])
            assertTrue(request.body.contains("\"role\":\"system\""))
            assertTrue(request.body.contains("Return only their cleaned-up text, nothing else."))
        },
        basePath = "/configured",
    ) { endpoint ->
        val result = client(endpoint).polish(
            ProviderPolishRequest(
                provider = Provider.SELF_HOSTED_POLISH,
                model = "llama3.2",
                prompt = "hello",
                apiKey = "local-test-key",
                endpoint = endpoint,
                selfHostedProtocol = SelfHostedProtocol.OLLAMA,
            ),
        )
        assertEquals(ProviderPolishResult.Success("ollama result"), result)
    }

    @Test fun noApiKeyFailsBeforeOpeningNetwork() {
        val requests = AtomicInteger()
        withServer(
            response = "{\"output\":[]}",
            inspect = { requests.incrementAndGet() },
        ) { endpoint ->
            val result = client(endpoint).polish(
                ProviderPolishRequest(Provider.OPENAI, "gpt-test", "hello"),
            )
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.NO_API_KEY), result)
        }
        assertEquals(0, requests.get())
    }

    @Test fun malformedAndOversizedResponsesAreTypedFailures() {
        withServer(response = "not-json") { endpoint ->
            val result = client(endpoint).polish(request(endpoint))
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE), result)
        }
        withServer(response = "x".repeat(600_000)) { endpoint ->
            val result = client(endpoint).polish(request(endpoint))
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE, 200), result)
        }
    }

    @Test fun redirectsAreNeverFollowed() {
        val redirectedRequests = AtomicInteger()
        withServer(
            status = 302,
            response = "",
            headers = mapOf("Location" to "http://127.0.0.1:1/secret"),
            inspect = { redirectedRequests.incrementAndGet() },
        ) { endpoint ->
            val result = client(endpoint).polish(request(endpoint))
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.REDIRECT_REJECTED, 302), result)
        }
        assertEquals(1, redirectedRequests.get())
    }

    @Test fun providerHttpErrorsDoNotExposeOrParseErrorBodies() = withServer(
        status = 401,
        response = "{\"error\":{\"message\":\"secret-bearing provider error\"}}",
    ) { endpoint ->
        val result = client(endpoint).polish(request(endpoint))
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.HTTP_ERROR, 401), result)
        assertFalse(result.toString().contains("secret-bearing"))
    }

    @Test fun anEmptyBodiedHttpErrorKeepsItsStatusRatherThanReadingAsUnreachable() = withServer(
        status = 401,
        response = "",
    ) { endpoint ->
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.HTTP_ERROR, 401), client(endpoint).polish(request(endpoint)))
    }

    @Test fun claudeAsksForTheSameOutputBudgetAsMacOs() = withServer(
        response = "{\"content\":[{\"type\":\"text\",\"text\":\"hello there\"}]}",
        inspect = { request -> assertTrue(request.body.contains("\"max_tokens\":8192,")) },
    ) { endpoint ->
        val result = client(endpoint, Provider.CLAUDE).polish(ProviderPolishRequest(Provider.CLAUDE, "claude-test", "hello", "test-key"))
        assertEquals(ProviderPolishResult.Success("hello there"), result)
    }

    @Test fun backticksInsideASentenceAreTheUsersWordsButALeadingFenceIsAWrapper() {
        assertTrue(ProviderPolishPrompt.isTranscriptOnly("use the ``` fence to quote code"))
        assertFalse(ProviderPolishPrompt.isTranscriptOnly("```\nhello\n```"))
        assertFalse(ProviderPolishPrompt.isTranscriptOnly("```text hello```"))
    }

    @Test fun anErrorBodyBecomesOnlyAClosedSignalOverTheWire() = withServer(
        status = 429,
        response = "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"secret-bearing quota text\"}}",
    ) { endpoint ->
        val result = client(endpoint).polish(request(endpoint))
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.HTTP_ERROR, 429, ProviderErrorSignal.OUT_OF_CREDITS), result)
        assertFalse(result.toString().contains("secret-bearing"))
    }

    @Test fun anOversizedErrorBodyKeepsItsOwnKindWithTheStatus() = withServer(
        status = 500,
        response = "x".repeat(600 * 1024),
    ) { endpoint ->
        val result = client(endpoint).polish(request(endpoint))
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE, 500), result)
    }

    @Test fun theBodyMarkersAreTheMacOsOnesPerProvider() {
        // (#77) OpenAI
        assertEquals(ProviderErrorSignal.OUT_OF_CREDITS, ProviderErrorSignal.classify(Provider.OPENAI, 429, "{\"type\":\"insufficient_quota\"}"))
        assertNull(ProviderErrorSignal.classify(Provider.OPENAI, 429, "{\"type\":\"rate_limit\"}"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderErrorSignal.classify(Provider.OPENAI, 400, "context_length_exceeded"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderErrorSignal.classify(Provider.OPENAI, 400, "content_filter"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderErrorSignal.classify(Provider.OPENAI, 400, "content_policy"))
        assertNull(ProviderErrorSignal.classify(Provider.OPENAI, 400, "something else"))
        assertNull(ProviderErrorSignal.classify(Provider.OPENAI, 401, "context_length_exceeded"))
        // Gemini
        assertEquals(ProviderErrorSignal.KEY_REJECTED, ProviderErrorSignal.classify(Provider.GEMINI, 400, "API_KEY_INVALID"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderErrorSignal.classify(Provider.GEMINI, 400, "exceeds the maximum number of tokens"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderErrorSignal.classify(Provider.GEMINI, 400, "PROHIBITED_CONTENT"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderErrorSignal.classify(Provider.GEMINI, 400, "\"blockReason\":\"SAFETY\""))
        assertNull(ProviderErrorSignal.classify(Provider.GEMINI, 400, "something else"))
        // Claude
        assertEquals(ProviderErrorSignal.OUT_OF_CREDITS, ProviderErrorSignal.classify(Provider.CLAUDE, 400, "Your credit balance is too low"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderErrorSignal.classify(Provider.CLAUDE, 400, "prompt is too long: 250024 tokens"))
        assertNull(ProviderErrorSignal.classify(Provider.CLAUDE, 400, "something else"))
        // Self-hosted has no markers
        assertNull(ProviderErrorSignal.classify(Provider.SELF_HOSTED_POLISH, 400, "API_KEY_INVALID insufficient_quota"))
    }

    @Test fun commentaryWrappersAreRejectedForDeterministicFallback() = withServer(
        response = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"Here is the polished transcript: hello\"}]}]}",
    ) { endpoint ->
        val result = client(endpoint).polish(request(endpoint))
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE), result)
    }

    @Test fun reasoningBlocksAreRemovedBeforeTypedSuccess() = withServer(
        response = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"<think>internal</think>hello\"}]}]}",
    ) { endpoint ->
        val result = client(endpoint).polish(request(endpoint))
        assertEquals(ProviderPolishResult.Success("hello"), result)
    }

    @Test fun requestAndResultToStringsDoNotContainSecretsOrTranscript() {
        val request = ProviderPolishRequest(Provider.OPENAI, "gpt-test", "private transcript", "private-key")
        val result = ProviderPolishResult.Success("private response")
        assertFalse(request.toString().contains("private transcript"))
        assertFalse(request.toString().contains("private-key"))
        assertFalse(result.toString().contains("private response"))
    }

    @Test fun timeoutAndCancellationRemainDistinctTypedFailures() {
        val started = CountDownLatch(1)
        withServer(
            response = "{\"output\":[{\"content\":[{\"text\":\"late\"}]}]}",
            inspect = { Thread.sleep(1_000) },
            beforeResponse = started,
        ) { endpoint ->
            val timed = client(endpoint, connect = 100, read = 100, overall = 250).polish(request(endpoint))
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT), timed)
        }

        val cancellationStarted = CountDownLatch(1)
        withServer(
            response = "{\"output\":[{\"content\":[{\"text\":\"late\"}]}]}",
            inspect = { Thread.sleep(1_000) },
            beforeResponse = cancellationStarted,
        ) { endpoint ->
            val cancellation = ProviderCancellation()
            val result = Executors.newSingleThreadExecutor().use { executor ->
                val future = executor.submit<ProviderPolishResult> { client(endpoint, read = 5_000).polish(request(endpoint), cancellation) }
                assertTrue(cancellationStarted.await(2, TimeUnit.SECONDS))
                cancellation.cancel()
                future.get(2, TimeUnit.SECONDS)
            }
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED), result)
        }
    }

    @Test fun overallTimeoutBoundsSlowMultiChunkResponse() = withServer(
        response = "{\"output\":[{\"content\":[{\"text\":\"late\"}]}]}",
        chunkDelayMs = 1_000,
    ) { endpoint ->
        val startedAt = System.nanoTime()
        val result = client(endpoint, connect = 2_000, read = 5_000, overall = 250).polish(request(endpoint))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        // The socket read timeout (bounded by the overall deadline) and the outer deadline race; whichever
        // wins, the kind is TIMEOUT. Since #61 the inner path keeps the status it had already seen (200).
        assertTrue("$result", result is ProviderPolishResult.Failure && result.kind == ProviderFailureKind.TIMEOUT && result.statusCode in setOf(null, 200))
        assertTrue("request exceeded strict overall timeout: ${elapsedMs}ms", elapsedMs < 800)
    }

    @Test fun cancellationRegistrationRaceAlwaysInvokesCallback() {
        repeat(100) {
            val cancellation = ProviderCancellation()
            val callbackCount = AtomicInteger()
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val registration = executor.submit {
                    ready.countDown()
                    release.await()
                    cancellation.onCancel { callbackCount.incrementAndGet() }
                }
                assertTrue(ready.await(1, TimeUnit.SECONDS))
                val cancel = executor.submit { cancellation.cancel() }
                release.countDown()
                registration.get(1, TimeUnit.SECONDS)
                cancel.get(1, TimeUnit.SECONDS)
                assertEquals(1, callbackCount.get())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun request(@Suppress("UNUSED_PARAMETER") endpoint: String) = ProviderPolishRequest(
        Provider.OPENAI,
        "gpt-test",
        "hello",
        "test-key",
    )

    private fun client(
        endpoint: String,
        provider: Provider = Provider.OPENAI,
        connect: Int = 2_000,
        read: Int = 2_000,
        overall: Int = 4_000,
    ) = ProviderPolishClient(
        // The older single-attempt cases: no retries, no Android logger on the JVM.
        maxRetries = 0,
        logInfo = {},
        logWarn = {},
        connectTimeoutMs = connect,
        readTimeoutMs = read,
        overallTimeoutMs = overall,
        endpointOverrides = mapOf(provider to endpoint),
    )

    // ---- Key check (#61): a GET to the model-list endpoint with the polish headers, judged by status and envelope.

    private fun checker(base: String, provider: Provider, readTimeoutMs: Int = 2_000) = ProviderPolishClient(
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        overallTimeoutMs = 5_000,
        keyCheckOverrides = mapOf(provider to base),
        logInfo = {},
        logWarn = {},
    )

    @Test fun keyCheckSendsAGetWithNoBodyAndTheOpenAiBearer() {
        var seen: TestRequest? = null
        withServer("{\"data\":[]}", basePath = "/v1/models", inspect = { seen = it }) { base ->
            assertEquals(ProviderKeyCheck.Accepted, checker(base, Provider.OPENAI).check(Provider.OPENAI, "sk-test"))
        }
        assertEquals("GET", seen!!.method)
        assertEquals("/v1/models", seen!!.path)
        assertEquals("Bearer sk-test", seen!!.headers["authorization"])
        assertNull(seen!!.headers["content-type"])
        assertEquals("", seen!!.body)
    }

    @Test fun keyCheckUsesTheGeminiAndClaudeHeaders() {
        var gemini: TestRequest? = null
        withServer("{\"models\":[{\"name\":\"models/x\"}]}", inspect = { gemini = it }) { base ->
            assertEquals(ProviderKeyCheck.Accepted, checker(base, Provider.GEMINI).check(Provider.GEMINI, "AIza"))
        }
        assertEquals("AIza", gemini!!.headers["x-goog-api-key"])
        var claude: TestRequest? = null
        withServer("{\"data\":[]}", inspect = { claude = it }) { base ->
            assertEquals(ProviderKeyCheck.Accepted, checker(base, Provider.CLAUDE).check(Provider.CLAUDE, "sk-ant"))
        }
        assertEquals("sk-ant", claude!!.headers["x-api-key"])
        assertEquals("2023-06-01", claude!!.headers["anthropic-version"])
    }

    @Test fun keyCheckAcceptsOnlyTheProvidersListEnvelope() {
        val badBodies = listOf("{}", "[]", "{\"data\":{}}", "{\"models\":[]}", "{", "\"data\"", "")
        badBodies.forEach { body ->
            withServer(body) { base ->
                assertEquals(body, ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 200), checker(base, Provider.OPENAI).check(Provider.OPENAI, "k"))
            }
        }
        withServer("", status = 204) { base ->
            assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 204), checker(base, Provider.OPENAI).check(Provider.OPENAI, "k"))
        }
        withServer("{\"data\":[]}") { base ->
            assertEquals(ProviderKeyCheck.Accepted, checker(base, Provider.OPENAI).check(Provider.OPENAI, "k"))
        }
        val oversized = "{\"data\":[\"" + "x".repeat(512 * 1024 + 1024) + "\"]}"
        withServer(oversized) { base ->
            val verdict = checker(base, Provider.OPENAI).check(Provider.OPENAI, "k")
            assertTrue("$verdict", verdict is ProviderKeyCheck.Unverified && verdict.failure == PolishFailure.BAD_REQUEST)
        }
    }

    @Test fun keyCheckMapsEveryStatusRow() {
        fun verdict(provider: Provider, status: Int, body: String = "{}"): ProviderKeyCheck {
            var result: ProviderKeyCheck? = null
            withServer(body, status = status) { base -> result = checker(base, provider).check(provider, "k") }
            return result!!
        }
        assertEquals(ProviderKeyCheck.Rejected(401), verdict(Provider.OPENAI, 401))
        assertEquals(ProviderKeyCheck.Denied(403), verdict(Provider.OPENAI, 403))
        assertEquals(ProviderKeyCheck.Denied(403), verdict(Provider.CLAUDE, 403))
        assertEquals(ProviderKeyCheck.Rejected(403), verdict(Provider.GEMINI, 403))
        assertEquals(ProviderKeyCheck.Rejected(400), verdict(Provider.GEMINI, 400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 400), verdict(Provider.OPENAI, 400))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.RATE_LIMITED, 429), verdict(Provider.OPENAI, 429))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.RATE_OR_QUOTA, 429), verdict(Provider.GEMINI, 429))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.PROVIDER_ERROR, 503), verdict(Provider.CLAUDE, 503))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 418), verdict(Provider.OPENAI, 418))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 302), verdict(Provider.OPENAI, 302))
    }

    @Test fun keyCheckNeverRejectsOnATransportFailure() {
        // A 401 whose body stalls past the read timeout is a timeout, not a verdict on the key.
        withServer("x".repeat(64), status = 401, chunkDelayMs = 3_000) { base ->
            assertEquals(ProviderKeyCheck.Unverified(PolishFailure.TIMED_OUT, 401), checker(base, Provider.OPENAI, readTimeoutMs = 300).check(Provider.OPENAI, "k"))
        }
        // A server that accepts and hangs up before any status line is unreachable.
        withServer("", closeBeforeStatus = true) { base ->
            assertEquals(ProviderKeyCheck.Unverified(PolishFailure.UNREACHABLE, null), checker(base, Provider.OPENAI).check(Provider.OPENAI, "k"))
        }
    }

    @Test fun keyCheckAsksNothingForSelfHostedOrAnUnusableKey() {
        val client = ProviderPolishClient(keyCheckOverrides = mapOf(Provider.OPENAI to "http://127.0.0.1:1/never"), logInfo = {}, logWarn = {})
        assertEquals(ProviderKeyCheck.NotApplicable, client.check(Provider.SELF_HOSTED_POLISH, "anything"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, null), client.check(Provider.OPENAI, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, null), client.check(Provider.OPENAI, "k\u0007"))
    }

    private fun withServer(
        response: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        basePath: String = "",
        beforeResponse: CountDownLatch? = null,
        chunkDelayMs: Long = 0,
        /** Read the request, then close without a status line: the shape of a dead upstream (#61). */
        closeBeforeStatus: Boolean = false,
        inspect: (TestRequest) -> Unit = {},
        block: (String) -> Unit,
    ) {
        val server = TestServer(status, response, headers, beforeResponse, chunkDelayMs, inspect, closeBeforeStatus)
        try {
            block("http://127.0.0.1:${server.port}$basePath")
        } finally {
            server.close()
        }
    }

    // ---- Discovery (#84): a scripted server serves the list call and every probe on one port, concurrently.

    private class ScriptedServer(
        /** Answers one request: (status, body). Runs on the connection's own thread. */
        private val respond: (TestRequest) -> Pair<Int, String>,
        private val holdMs: Long = 0,
        private val connections: Int = 64,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        private val acceptor = Executors.newSingleThreadExecutor()
        private val handlers = Executors.newCachedThreadPool()
        val requests = java.util.Collections.synchronizedList(mutableListOf<TestRequest>())
        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val port: Int get() = socket.localPort
        val base: String get() = "http://127.0.0.1:$port"

        init {
            acceptor.submit {
                repeat(connections) {
                    val connection = try { socket.accept() } catch (_: Exception) { return@submit }
                    handlers.submit {
                        try {
                            connection.use {
                                val now = inFlight.incrementAndGet()
                                maxInFlight.accumulateAndGet(now, ::maxOf)
                                try {
                                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.ISO_8859_1))
                                    val requestLine = reader.readLine() ?: return@use
                                    val parts = requestLine.split(' ', limit = 3)
                                    val headers = buildMap {
                                        while (true) {
                                            val line = reader.readLine() ?: break
                                            if (line.isEmpty()) break
                                            val colon = line.indexOf(':')
                                            if (colon > 0) put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                                        }
                                    }
                                    val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                                    val body = CharArray(bodyLength)
                                    var read = 0
                                    while (read < bodyLength) {
                                        val count = reader.read(body, read, bodyLength - read)
                                        if (count < 0) break
                                        read += count
                                    }
                                    val request = TestRequest(parts[0], parts.getOrElse(1) { "" }, headers, String(body, 0, read))
                                    requests += request
                                    if (holdMs > 0) Thread.sleep(holdMs)
                                    val (status, response) = respond(request)
                                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                                    val writer = PrintWriter(OutputStreamWriter(it.getOutputStream(), StandardCharsets.ISO_8859_1))
                                    writer.print("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                    writer.flush()
                                    it.getOutputStream().write(bytes)
                                    it.getOutputStream().flush()
                                } finally {
                                    inFlight.decrementAndGet()
                                }
                            }
                        } catch (_: Exception) {
                            // A cancelled probe closes its socket mid-write; that is the shape under test.
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            acceptor.shutdownNow()
            handlers.shutdownNow()
        }
    }

    private fun discoverer(server: ScriptedServer, provider: Provider, discoveryTimeoutMs: Int = 10_000, probeTimeoutMs: Int = 2_000, readTimeoutMs: Int = 2_000) = ProviderPolishClient(
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        overallTimeoutMs = 5_000,
        endpointOverrides = mapOf(provider to server.base + "/probe"),
        keyCheckOverrides = mapOf(provider to server.base + "/models"),
        logInfo = {},
        logWarn = {},
        discoveryTimeoutMs = discoveryTimeoutMs,
        probeTimeoutMs = probeTimeoutMs,
    )

    private fun openAiList(vararg ids: String) = "{\"data\":[" + ids.joinToString(",") { "{\"id\":\"$it\",\"object\":\"model\"}" } + "]}"
    private fun probedModel(request: TestRequest): String = Regex("\"model\":\"([^\"]+)\"").find(request.body)?.groupValues?.get(1) ?: request.path.substringAfterLast('/').substringBefore(':')

    @Test fun discoveryListsFiltersProbesRecommendsAndSorts() {
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-5.6-terra", "gpt-4.1-mini", "gpt-4o-realtime-preview", "o1-mini", "gpt-5.6-terra", "gpt-locked")
            else when (probedModel(request)) {
                "gpt-locked" -> 403 to "{}"
                else -> 200 to okBody(Provider.OPENAI)
            }
        }).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "sk-test")
            assertTrue("$result", result is ProviderDiscovery.Listed)
            val models = (result as ProviderDiscovery.Listed).models
            assertEquals(listOf("gpt-4.1-mini", "gpt-5.6-terra", "gpt-locked"), models.map { it.id })
            assertEquals(listOf(ModelAccess.AVAILABLE, ModelAccess.AVAILABLE, ModelAccess.UNAVAILABLE), models.map { it.access })
            assertEquals(listOf(true, false, false), models.map { it.recommended })
            assertEquals("Gpt 4.1 Mini", models[0].displayName)
            val list = server.requests.first { it.path.startsWith("/models") }
            assertEquals("GET", list.method); assertEquals("", list.body); assertEquals("Bearer sk-test", list.headers["authorization"])
            val probes = server.requests.filter { it.path.startsWith("/probe") }
            assertEquals(3, probes.size)
            probes.forEach { probe ->
                assertEquals("POST", probe.method)
                assertTrue(probe.body, probe.body.contains("\"input\":\"Hi\"") && probe.body.contains("\"max_output_tokens\":16") && probe.body.contains("\"store\":false"))
                assertFalse(probe.body, probe.body.contains("instructions"))
            }
        }
    }

    @Test fun discoveryRunsAtMostThreeProbesAtOnce() {
        ScriptedServer(
            { request -> if (request.path.startsWith("/models")) 200 to openAiList(*Array(9) { "gpt-m$it" }) else 200 to okBody(Provider.OPENAI) },
            holdMs = 150,
        ).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k")
            assertTrue("$result", result is ProviderDiscovery.Listed)
            assertEquals(9, (result as ProviderDiscovery.Listed).models.size)
            assertTrue("max in flight ${server.maxInFlight.get()}", server.maxInFlight.get() in 2..3)
        }
    }

    @Test fun geminiKeepsGenerateContentRowsAndReadsAQuotaZeroLimitAsLocked() {
        val list = "{\"models\":[" +
            "{\"name\":\"models/gemini-3.6-flash\",\"displayName\":\"Gemini 3.6 Flash\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
            "{\"name\":\"models/gemini-embedding-001\",\"displayName\":\"Embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
            "{\"name\":\"models/gemini-2.5-pro\",\"displayName\":\"Gemini 2.5 Pro\",\"supportedGenerationMethods\":[\"generateContent\"]}" +
            "]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to list
            else if (request.path.contains("gemini-2.5-pro")) 429 to "{\"error\":{\"message\":\"Quota exceeded, limit: 0\"}}"
            else 429 to "{\"error\":{\"message\":\"Resource exhausted\"}}"
        }).use { server ->
            val result = discoverer(server, Provider.GEMINI).discoverModels(Provider.GEMINI, "AIza") as ProviderDiscovery.Listed
            assertEquals(listOf("gemini-3.6-flash", "gemini-2.5-pro"), result.models.map { it.id })
            assertEquals(listOf(ModelAccess.AVAILABLE, ModelAccess.UNAVAILABLE), result.models.map { it.access })
            assertEquals("Gemini 3.6 Flash", result.models[0].displayName)
            val probe = server.requests.first { it.path.startsWith("/probe") }
            assertTrue(probe.body, probe.body.contains("\"maxOutputTokens\":5") && !probe.body.contains("systemInstruction"))
        }
    }

    /**
     * Product Outcome. When this fails, OpenAI and Claude sit in discovery order under a header that says
     * "Newest first" (#101).
     *
     * It runs the WHOLE path, request to DiscoveredModel, because the presentation tests could not catch
     * the defect that made this necessary: they handed dates straight to the row builder, so a mapping
     * that parsed the date and then dropped it was invisible to every one of them.
     */
    @Test fun theProvidersReleaseDateSurvivesAllTheWayIntoTheDiscoveredModel() {
        // OpenAI sends unix SECONDS; everything downstream is millis.
        val openAi = "{\"data\":[{\"id\":\"gpt-5.6-luna\",\"created\":1756771200},{\"id\":\"gpt-4.1-mini\"}]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAi else 200 to okBody(Provider.OPENAI)
        }).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            val luna = result.models.first { it.id == "gpt-5.6-luna" }
            assertEquals(1756771200L * 1000L, luna.releasedAt)
            // A row the provider dated nothing for stays null rather than being invented.
            assertNull(result.models.first { it.id == "gpt-4.1-mini" }.releasedAt)
        }

        // Anthropic sends ISO 8601.
        val claude = "{\"data\":[{\"id\":\"claude-haiku-4-5\",\"created_at\":\"2026-08-28T00:00:00Z\"}," +
            "{\"id\":\"claude-sonnet-5\",\"created_at\":\"not-a-date\"}],\"has_more\":false}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to claude else 200 to okBody(Provider.CLAUDE)
        }).use { server ->
            val result = discoverer(server, Provider.CLAUDE).discoverModels(Provider.CLAUDE, "sk-ant") as ProviderDiscovery.Listed
            assertEquals(
                java.time.Instant.parse("2026-08-28T00:00:00Z").toEpochMilli(),
                result.models.first { it.id == "claude-haiku-4-5" }.releasedAt,
            )
            // Unparseable is null, never a guess: a wrong date reorders the list and nobody can see why.
            assertNull(result.models.first { it.id == "claude-sonnet-5" }.releasedAt)
        }

        // Gemini publishes no date at all, measured 2026-09-02, so every row arrives undated and the order
        // comes from ui/ModelNotes instead. This is the control that makes the two assertions above mean
        // something rather than passing by accident.
        val gemini = "{\"models\":[{\"name\":\"models/gemini-3.8-flash\",\"displayName\":\"Gemini 3.8 Flash\"," +
            "\"supportedGenerationMethods\":[\"generateContent\"]}]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to gemini else 200 to okBody(Provider.GEMINI)
        }).use { server ->
            val result = discoverer(server, Provider.GEMINI).discoverModels(Provider.GEMINI, "AIza") as ProviderDiscovery.Listed
            assertNull(result.models.single().releasedAt)
        }
    }

    @Test fun claudeFollowsPaginationAndStopsOnARepeatedCursor() {
        val page1 = "{\"data\":[{\"id\":\"claude-sonnet-5\",\"display_name\":\"Claude Sonnet 5\"}],\"has_more\":true,\"last_id\":\"c1\"}"
        val page2 = "{\"data\":[{\"id\":\"claude-haiku-4-5\",\"display_name\":\"Claude Haiku 4.5\"}],\"has_more\":true,\"last_id\":\"c1\"}"
        ScriptedServer({ request ->
            when {
                request.path.startsWith("/models") && request.path.contains("after_id=c1") -> 200 to page2
                request.path.startsWith("/models") -> 200 to page1
                else -> 200 to okBody(Provider.CLAUDE)
            }
        }).use { server ->
            val result = discoverer(server, Provider.CLAUDE).discoverModels(Provider.CLAUDE, "sk-ant") as ProviderDiscovery.Listed
            assertEquals(listOf("claude-haiku-4-5", "claude-sonnet-5"), result.models.map { it.id })
            val lists = server.requests.filter { it.path.startsWith("/models") }
            assertEquals(2, lists.size)
            assertTrue(lists[0].path, lists[0].path.contains("limit=1000"))
            assertEquals("sk-ant", lists[0].headers["x-api-key"])
            val probe = server.requests.first { it.path.startsWith("/probe") }
            assertTrue(probe.body, probe.body.contains("\"max_tokens\":5") && !probe.body.contains("\"system\""))
        }
    }

    @Test fun aMalformedLaterClaudePageKeepsAndProbesTheEarlierRows() {
        val page1 = "{\"data\":[{\"id\":\"claude-sonnet-5\",\"display_name\":\"Claude Sonnet 5\"}],\"has_more\":true,\"last_id\":\"c1\"}"
        ScriptedServer({ request ->
            when {
                request.path.startsWith("/models") && request.path.contains("after_id=c1") -> 200 to "not json at all"
                request.path.startsWith("/models") -> 200 to page1
                else -> 200 to okBody(Provider.CLAUDE)
            }
        }).use { server ->
            val result = discoverer(server, Provider.CLAUDE).discoverModels(Provider.CLAUDE, "sk-ant") as ProviderDiscovery.Listed
            assertEquals(listOf("claude-sonnet-5"), result.models.map { it.id })
            assertEquals(ModelAccess.AVAILABLE, result.models.single().access)
        }
        ScriptedServer({ _ -> 200 to "not json at all" }).use { server ->
            assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 200)), discoverer(server, Provider.CLAUDE).discoverModels(Provider.CLAUDE, "sk-ant"))
        }
    }

    @Test fun theDeadlineCancelsQueuedAndActiveProbes() {
        // Every probe the fake server receives is HELD on this latch, never slept: an in-flight probe cannot
        // finish before the deadline, so at most three (the executor's width) can reach the server before
        // the client returns, whatever the machine load. A sleep here raced the clock and went red under
        // load (2026-09-02) when the list fetch ate the budget and the probes timed out instantly.
        val hold = java.util.concurrent.CountDownLatch(1)
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList(*Array(9) { "gpt-m$it" }) else { hold.await(10, TimeUnit.SECONDS); 200 to okBody(Provider.OPENAI) }
        }).use { server ->
            // The request log is a synchronized list; a count that iterates it must hold its lock, or a
            // probe landing mid-iteration throws and the test goes red on a correct client.
            fun probeCount(): Int = synchronized(server.requests) { server.requests.count { it.path.startsWith("/probe") } }
            val result = discoverer(server, Provider.OPENAI, discoveryTimeoutMs = 800, probeTimeoutMs = 5_000, readTimeoutMs = 5_000).discoverModels(Provider.OPENAI, "k")
            assertTrue("$result", result is ProviderDiscovery.Listed)
            val probesAtReturn = probeCount()
            hold.countDown()
            // The held probes finish now; a queued probe that was NOT cancelled would be dequeued the instant
            // a thread frees and arrive here. Gate on the count going quiet, never on a fixed sleep: unchanged
            // across five polls 200 ms apart, with a 5 s ceiling that only a defect can reach.
            var probesLater = probeCount()
            var quiet = 0
            var polls = 0
            while (quiet < 5 && polls < 25) {
                Thread.sleep(200)
                val now = probeCount()
                if (now == probesLater) quiet++ else { quiet = 0; probesLater = now }
                polls++
            }
            // Nine models, three in flight: without cancellation the queued six would all arrive after the
            // return. What the client promises is narrower than "none": a probe thread that freed up in the
            // same instant the deadline fired can have dequeued its next probe before the cancel reached the
            // queue, and an interrupt cannot pull back a request already connecting, so up to the executor's
            // width (three) may still land. Never more, and never the whole queue.
            assertTrue("at return $probesAtReturn, later $probesLater", probesLater <= probesAtReturn + 3 && probesLater < 9)
        }
    }

    @Test fun discoveryRefusesTheWholeListWhenAProbeRejectsTheKey() {
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-a", "gpt-b") else 401 to "{}"
        }).use { server ->
            assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(401)), discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k"))
        }
        // Gemini says a wrong key with a 400 body on the probe too; the status is carried as the provider sent it.
        val geminiList = "{\"models\":[{\"name\":\"models/gemini-3.6-flash\",\"displayName\":\"F\",\"supportedGenerationMethods\":[\"generateContent\"]}]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to geminiList else 400 to "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"
        }).use { server ->
            assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(400)), discoverer(server, Provider.GEMINI).discoverModels(Provider.GEMINI, "AIza"))
        }
    }

    @Test fun discoveryRefusesOnAListRejectionAndCapsTheProbes() {
        ScriptedServer({ _ -> 401 to "{}" }).use { server ->
            assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(401)), discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k"))
        }
        val many = Array(ProviderPolishClient.MAX_PROBES + 3) { "gpt-x$it" }
        ScriptedServer({ request -> if (request.path.startsWith("/models")) 200 to openAiList(*many) else 200 to okBody(Provider.OPENAI) }, connections = 128).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            assertEquals(many.size, result.models.size)
            assertEquals(ProviderPolishClient.MAX_PROBES, result.models.count { it.access == ModelAccess.AVAILABLE })
            assertEquals(3, result.models.count { it.access == ModelAccess.UNVERIFIED })
            assertEquals(ProviderPolishClient.MAX_PROBES, server.requests.count { it.path.startsWith("/probe") })
        }
    }

    @Test fun aProbeTimeoutIsUnverifiedAndTheWholeDeadlineRefusesAsTimedOut() {
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-slow", "gpt-fast") else { if (probedModel(request) == "gpt-slow") Thread.sleep(1_500); 200 to okBody(Provider.OPENAI) }
        }).use { server ->
            val result = discoverer(server, Provider.OPENAI, probeTimeoutMs = 400, readTimeoutMs = 400).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            assertEquals(ModelAccess.AVAILABLE, result.models.first { it.id == "gpt-fast" }.access)
            assertEquals(ModelAccess.UNVERIFIED, result.models.first { it.id == "gpt-slow" }.access)
        }
        // The whole-operation deadline bounds every probe: the ones it cuts off are UNVERIFIED (never locked)
        // and the list still comes back, inside the deadline plus one probe's grace.
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList(*Array(6) { "gpt-m$it" }) else { Thread.sleep(700); 200 to okBody(Provider.OPENAI) }
        }).use { server ->
            val started = System.nanoTime()
            val result = discoverer(server, Provider.OPENAI, discoveryTimeoutMs = 900, probeTimeoutMs = 5_000, readTimeoutMs = 5_000).discoverModels(Provider.OPENAI, "k")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("$result", result is ProviderDiscovery.Listed)
            val access = (result as ProviderDiscovery.Listed).models.map { it.access }
            assertTrue("$access", access.count { it == ModelAccess.UNVERIFIED } >= 1 && access.none { it == ModelAccess.UNAVAILABLE })
            assertTrue("took ${elapsedMs}ms", elapsedMs < 2_500)
        }
        // A deadline already spent before the list call refuses as timed out.
        ScriptedServer({ _ -> 200 to openAiList("gpt-a") }, holdMs = 400).use { server ->
            val result = discoverer(server, Provider.OPENAI, discoveryTimeoutMs = 60, probeTimeoutMs = 5_000, readTimeoutMs = 5_000).discoverModels(Provider.OPENAI, "k")
            val verdict = (result as? ProviderDiscovery.Refused)?.verdict
            assertTrue("$result", verdict is ProviderKeyCheck.Unverified && verdict.failure == PolishFailure.TIMED_OUT)
        }
    }

    @Test fun discoveryAsksNothingForSelfHostedAndAnEmptyFilteredListIsListedEmpty() {
        val client = ProviderPolishClient(keyCheckOverrides = mapOf(Provider.OPENAI to "http://127.0.0.1:1/never"), logInfo = {}, logWarn = {})
        assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.NotApplicable), client.discoverModels(Provider.SELF_HOSTED_POLISH, "x"))
        ScriptedServer({ _ -> 200 to openAiList("dall-e-3", "whisper-1") }).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            assertEquals(emptyList<DiscoveredModel>(), result.models)
            assertEquals(0, server.requests.count { it.path.startsWith("/probe") })
        }
    }

    // ---- The fixed prompt and the retry policy (#2, #3, #4).

    private fun retrying(base: String, provider: Provider, delaysMs: List<Long> = listOf(20L, 40L), overall: Int = 5_000, read: Int = 2_000) = ProviderPolishClient(
        connectTimeoutMs = 2_000,
        readTimeoutMs = read,
        overallTimeoutMs = overall,
        endpointOverrides = mapOf(provider to base),
        logInfo = {},
        logWarn = {},
        retryDelaysMs = delaysMs,
    )

    /**
     * Product Outcome, and the sweep of one class rather than one more instance of it. Two review rounds
     * both landed on how a 200 probe body is read, so this covers every way a reply can carry no words:
     * an empty string, whitespace, a later part, a body that is not JSON, an exhausted output budget, a
     * safety block, and no terminal marker at all.
     *
     * The rule under it is one question, not a list: a model is refused only when it declared a NORMAL
     * stop and still wrote nothing. When this fails a user is offered a model that silently returns their
     * raw dictation, or is denied a model that works.
     */
    @Test fun aModelIsRefusedOnlyWhenItFinishedOfItsOwnAccordAndWroteNothing() {
        // The measured transcribe case: 200, well-formed envelope, empty string.
        val empty = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"\"}]}}]}"
        // Whitespace is not an answer either: polish trims before judging, so this must match it.
        val blank = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"   \"}]}}]}"
        // A multipart reply whose FIRST part is empty still carries words. The old label scan stopped at
        // the first `"text"` and called this model unusable.
        val later = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"},{\"text\":\"Hi\"}]}}]}"
        // A body that is not JSON at all did not answer, whatever bytes it happens to contain.
        val notJson = "text: Hi"
        // Measured 2026-09-02 against a live Gemini key: gemini-2.5-pro and gemini-3-flash-preview answer
        // exactly this to the probe, having spent the whole output cap on thinking. Both polish fine at
        // the real request's budget, so this must NOT be refused. Raising the cap does not help: the
        // thinking grows with it, 2 thought tokens at a cap of 5 and 125 at a cap of 128.
        val outOfBudget = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}"
        // A safety block is the same answer for the same reason, and nothing in the code names it: the
        // check asks whether the model finished, so every other way a reply can end lands here for free.
        val blocked = "{\"candidates\":[{\"finishReason\":\"PROHIBITED_CONTENT\",\"content\":{\"parts\":[]}}]}"
        // No marker at all is also unproved, rather than a refusal by default.
        val noMarker = "{\"candidates\":[{\"content\":{\"parts\":[]}}]}"
        val list = "{\"models\":[" + listOf("empty", "blank", "later", "notjson", "outofbudget", "blocked", "nomarker", "good").joinToString(",") {
            "{\"name\":\"models/gemini-$it\",\"supportedGenerationMethods\":[\"generateContent\"]}"
        } + "]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to list else when (probedModel(request)) {
                "gemini-empty" -> 200 to empty
                "gemini-blank" -> 200 to blank
                "gemini-later" -> 200 to later
                "gemini-notjson" -> 200 to notJson
                "gemini-outofbudget" -> 200 to outOfBudget
                "gemini-blocked" -> 200 to blocked
                "gemini-nomarker" -> 200 to noMarker
                else -> 200 to okBody(Provider.GEMINI)
            }
        }).use { server ->
            val models = (discoverer(server, Provider.GEMINI).discoverModels(Provider.GEMINI, "AIza") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            assertEquals(ModelAccess.UNAVAILABLE, models["gemini-empty"])
            assertEquals(ModelAccess.UNAVAILABLE, models["gemini-blank"])
            assertEquals(ModelAccess.AVAILABLE, models["gemini-later"])
            assertEquals(ModelAccess.UNAVAILABLE, models["gemini-notjson"])
            // Neither refused nor confirmed: the probe could not tell, and an UNVERIFIED row stays on
            // screen, so the model is still offered.
            assertEquals(ModelAccess.UNVERIFIED, models["gemini-outofbudget"])
            assertEquals(ModelAccess.UNVERIFIED, models["gemini-blocked"])
            assertEquals(ModelAccess.UNVERIFIED, models["gemini-nomarker"])
            assertEquals(ModelAccess.AVAILABLE, models["gemini-good"])
        }
    }

    /**
     * Product Outcome, measured 2026-09-02 against a live OpenAI key and not hypothetical.
     *
     * `gpt-5-mini` and `gpt-5-nano` answer the probe with `status: "incomplete"`,
     * `incomplete_details.reason: "max_output_tokens"` and no text, because the reasoning consumed the
     * 16-token cap. Those are the two NEWEST models the founder's key can reach (#103), so refusing an
     * empty reply without asking why it was empty would have hidden exactly them. `gpt-4.1-mini` answers
     * `completed` with 34 characters at the same cap.
     *
     * When this fails, the newest models a user owns disappear from their list.
     */
    @Test fun anOpenAiModelThatSpentTheProbeBudgetThinkingIsNotRefused() {
        val thinking = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}"
        val answered = "{\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"Hello there\"}]}]}"
        val silent = "{\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"\"}]}]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-thinking", "gpt-answered", "gpt-silent")
            else when (probedModel(request)) {
                "gpt-thinking" -> 200 to thinking
                "gpt-silent" -> 200 to silent
                else -> 200 to answered
            }
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-thinking"])
            assertEquals(ModelAccess.AVAILABLE, models["gpt-answered"])
            // A model that COMPLETED and still wrote nothing is the refusal case, on OpenAI as on Gemini.
            assertEquals(ModelAccess.UNAVAILABLE, models["gpt-silent"])
        }
    }

    /**
     * Product Outcome. The probe budget stops at MAX_PROBES, so which models get checked decides which
     * ones the list can offer and which one wears the Recommended badge. Spending it in provider order
     * left the untested tail arbitrary: his key lists 69 OpenAI models and only 40 are probed.
     *
     * When this fails the newest model a user owns can be the one nobody checked.
     */
    @Test fun theProbeBudgetGoesToTheNewestModelsNotTheOnesTheProviderHappensToListFirst() {
        val day = 24 * 60 * 60L
        // Listed oldest first, with the newest model LAST, which is where provider order puts it.
        val rows = (1..ProviderPolishClient.MAX_PROBES + 1).joinToString(",") { n ->
            "{\"id\":\"gpt-m$n\",\"created\":${1_700_000_000L + n * day}}"
        }
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to "{\"data\":[$rows]}" else 200 to okBody(Provider.OPENAI)
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            val newest = "gpt-m${ProviderPolishClient.MAX_PROBES + 1}"
            assertEquals(ModelAccess.AVAILABLE, models[newest])
            // The one left unprobed is the OLDEST, which is the model a user is least likely to pick.
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-m1"])
            assertEquals(ProviderPolishClient.MAX_PROBES, server.requests.count { it.path.startsWith("/probe") })
        }
    }

    /**
     * A successful reply in each provider's own envelope. **It must match the provider under test**: since
     * #104 the probe judges a body with the polish parser, so serving an OpenAI envelope to a Claude probe
     * makes every model read UNAVAILABLE. That mismatch was live in four fixtures and only went red once
     * the crude label scan was replaced.
     */
    private fun okBody(provider: Provider) = when (provider) {
        Provider.OPENAI -> "{\"output\":[{\"content\":[{\"text\":\"clean result\"}]}]}"
        Provider.GEMINI -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"clean result\"}]}}]}"
        Provider.CLAUDE -> "{\"content\":[{\"type\":\"text\",\"text\":\"clean result\"}]}"
        Provider.SELF_HOSTED_POLISH -> "{\"choices\":[{\"message\":{\"content\":\"clean result\"}}]}"
    }

    private fun jsonQuoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test fun theFourBodiesCarryTheAssembledPromptInTheRightPlaces() {
        val transcript = "please send the deck to finance today"
        val system = ProviderPolishPrompt.systemInstruction(transcript)
        val user = ProviderPolishPrompt.userMessage(transcript)
        listOf(Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE).forEach { provider ->
            var body = ""
            withServer(okBody(provider), inspect = { body = it.body }) { base ->
                retrying(base, provider).polish(ProviderPolishRequest(provider, "m", transcript, apiKey = "k"))
            }
            when (provider) {
                Provider.OPENAI -> { assertTrue(body, body.contains("\"instructions\":" + jsonQuoted(system))); assertTrue(body, body.contains("\"input\":" + jsonQuoted(user))) }
                Provider.GEMINI -> { assertTrue(body, body.contains("\"systemInstruction\":{\"parts\":[{\"text\":" + jsonQuoted(system))); assertTrue(body, body.contains("\"contents\":[{\"parts\":[{\"text\":" + jsonQuoted(user))) }
                Provider.CLAUDE -> { assertTrue(body, body.contains("\"system\":" + jsonQuoted(system))); assertTrue(body, body.contains("\"messages\":[{\"role\":\"user\",\"content\":" + jsonQuoted(user))) }
                Provider.SELF_HOSTED_POLISH -> Unit
            }
            assertEquals("the transcript appears exactly once, inside the user message", 1, body.split(jsonQuoted(user)).size - 1)
        }
        assertTrue(system.endsWith("only minimal punctuation fixes."))
        var hosted = ""
        withServer(okBody(Provider.SELF_HOSTED_POLISH), basePath = "/configured", inspect = { hosted = it.body }) { base ->
            retrying(base, Provider.SELF_HOSTED_POLISH).polish(ProviderPolishRequest(Provider.SELF_HOSTED_POLISH, "llama3.2", transcript, apiKey = "k", endpoint = base))
        }
        assertTrue(hosted, hosted.contains("{\"role\":\"system\",\"content\":" + jsonQuoted(system) + "}"))
        assertTrue(hosted, hosted.contains("{\"role\":\"user\",\"content\":" + jsonQuoted(user) + "}"))
    }

    @Test fun anEchoedLabelIsMalformedAndFallsBack() = withServer("{\"output\":[{\"content\":[{\"text\":\"Transcript to clean:\\n\\nhello there\"}]}]}") { base ->
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.MALFORMED_RESPONSE), retrying(base, Provider.OPENAI).polish(ProviderPolishRequest(Provider.OPENAI, "m", "hello there", apiKey = "k")))
    }

    @Test fun aTransientFailureIsRetriedAndThenSucceeds() {
        val calls = AtomicInteger()
        ScriptedServer({ _ -> if (calls.incrementAndGet() == 1) 503 to "{}" else 200 to okBody(Provider.OPENAI) }).use { server ->
            val started = System.nanoTime()
            val result = retrying(server.base, Provider.OPENAI).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(ProviderPolishResult.Success("clean result"), result)
            assertEquals(2, server.requests.size)
            assertTrue("took ${elapsedMs}ms", elapsedMs >= 20)
        }
        val calls2 = AtomicInteger()
        ScriptedServer({ _ -> if (calls2.incrementAndGet() == 1) 429 to "{}" else 200 to okBody(Provider.OPENAI) }).use { server ->
            assertEquals(ProviderPolishResult.Success("clean result"), retrying(server.base, Provider.OPENAI).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k")))
            assertEquals(2, server.requests.size)
        }
    }

    @Test fun retriesStopAtTwoAndReportTheLastFailure() {
        ScriptedServer({ _ -> 503 to "{}" }).use { server ->
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.HTTP_ERROR, 503, null), retrying(server.base, Provider.OPENAI).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k")))
            assertEquals(3, server.requests.size)
        }
    }

    @Test fun nonRetryableAnswersMakeOneRequest() {
        val cases = listOf(
            Triple(Provider.GEMINI, 429, "{}"),
            Triple(Provider.OPENAI, 401, "{}"),
            Triple(Provider.OPENAI, 429, "{\"error\":{\"code\":\"insufficient_quota\"}}"),
            Triple(Provider.GEMINI, 400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"),
            Triple(Provider.CLAUDE, 400, "{\"error\":{\"message\":\"prompt is too long\"}}"),
            Triple(Provider.OPENAI, 400, "{\"error\":{\"code\":\"content_filter\"}}"),
            Triple(Provider.OPENAI, 404, "{}"),
        )
        cases.forEach { (provider, status, body) ->
            ScriptedServer({ _ -> status to body }).use { server ->
                val result = retrying(server.base, provider).polish(ProviderPolishRequest(provider, "m", "some words to polish here", apiKey = "k"))
                assertTrue("$provider $status: $result", result is ProviderPolishResult.Failure && result.statusCode == status)
                assertEquals("$provider $status", 1, server.requests.size)
            }
        }
    }

    @Test fun aStalledBodyAfterA401MakesOneRequestAndStaysATimeout() = withServer("x".repeat(64), status = 401, chunkDelayMs = 2_000) { base ->
        val result = retrying(base, Provider.OPENAI, read = 300).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k"))
        assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.TIMEOUT, 401, null), result)
    }

    @Test fun aCancelDuringTheDelayStopsWithOneRequest() {
        ScriptedServer({ _ -> 503 to "{}" }).use { server ->
            val cancellation = ProviderCancellation()
            val client = retrying(server.base, Provider.OPENAI, delaysMs = listOf(2_000L, 2_000L))
            val worker = Executors.newSingleThreadExecutor()
            val future = worker.submit<ProviderPolishResult> { client.polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k"), cancellation) }
            Thread.sleep(300)
            val started = System.nanoTime()
            cancellation.cancel()
            val result = future.get(3, TimeUnit.SECONDS)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.CANCELLED), result)
            assertEquals(1, server.requests.size)
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 1_000)
            worker.shutdownNow()
        }
    }

    @Test fun aDeadlineSpentByTheFirstAttemptMakesNoRetry() {
        ScriptedServer({ _ -> Thread.sleep(600); 503 to "{}" }).use { server ->
            val result = retrying(server.base, Provider.OPENAI, overall = 500, read = 2_000).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k"))
            assertTrue("$result", result is ProviderPolishResult.Failure && result.kind == ProviderFailureKind.TIMEOUT)
            Thread.sleep(200)
            assertEquals(1, server.requests.size)
        }
    }

    @Test fun aDelayThatWouldOutliveTheDeadlineIsNotTaken() {
        // The first attempt fails fast; the 500 ms delay would end past the 300 ms deadline, so the failure is
        // returned at once rather than slept through and reported as a timeout.
        ScriptedServer({ _ -> 503 to "{}" }).use { server ->
            val started = System.nanoTime()
            val result = retrying(server.base, Provider.OPENAI, delaysMs = listOf(500L, 500L), overall = 300).polish(ProviderPolishRequest(Provider.OPENAI, "m", "some words to polish here", apiKey = "k"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.HTTP_ERROR, 503, null), result)
            assertEquals(1, server.requests.size)
            assertTrue("took ${elapsedMs}ms", elapsedMs < 300)
        }
    }

    @Test fun anOversizedAssembledBodyIsRefusedBeforeAnyRequest() {
        // Fits MAX_PROMPT_CHARS as characters, but each is three UTF-8 bytes, so the assembled body passes the byte cap.
        val transcript = "あ".repeat(ProviderPolishClient.MAX_PROMPT_CHARS)
        listOf(Provider.OPENAI, Provider.GEMINI, Provider.CLAUDE).forEach { provider ->
            ScriptedServer({ _ -> 200 to okBody(provider) }).use { server ->
                assertEquals("$provider", ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION), retrying(server.base, provider).polish(ProviderPolishRequest(provider, "m", transcript, apiKey = "k")))
                assertEquals("$provider", 0, server.requests.size)
            }
        }
        ScriptedServer({ _ -> 200 to okBody(Provider.SELF_HOSTED_POLISH) }).use { server ->
            val request = ProviderPolishRequest(Provider.SELF_HOSTED_POLISH, "m", transcript, endpoint = server.base)
            assertEquals(ProviderPolishResult.Failure(ProviderFailureKind.INVALID_CONFIGURATION), retrying(server.base, Provider.SELF_HOSTED_POLISH).polish(request))
            assertEquals(0, server.requests.size)
        }
    }

    private data class TestRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class TestServer(
        private val status: Int,
        private val response: String,
        private val responseHeaders: Map<String, String>,
        private val beforeResponse: CountDownLatch?,
        private val chunkDelayMs: Long,
        private val inspect: (TestRequest) -> Unit,
        private val closeBeforeStatus: Boolean = false,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        val port: Int get() = socket.localPort

        init {
            executor.submit {
                try {
                    // The JDK client retries once on an EOF before the status line; hang up on the retry too.
                    repeat(if (closeBeforeStatus) 3 else 1) { socket.accept().use { connection ->
                        val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
                        val requestLine = reader.readLine() ?: return@use
                        val parts = requestLine.split(' ', limit = 3)
                        val headers = buildMap {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                            }
                        }
                        val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = CharArray(bodyLength)
                        var read = 0
                        while (read < bodyLength) {
                            val count = reader.read(body, read, bodyLength - read)
                            if (count < 0) break
                            read += count
                        }
                        inspect(TestRequest(parts[0], parts.getOrElse(1) { "" }, headers, String(body, 0, read)))
                        if (closeBeforeStatus) return@use
                        beforeResponse?.countDown()
                        val bytes = response.toByteArray(StandardCharsets.UTF_8)
                        val writer = PrintWriter(OutputStreamWriter(connection.getOutputStream(), StandardCharsets.ISO_8859_1))
                        writer.print("HTTP/1.1 $status Test\r\n")
                        writer.print("Content-Length: ${bytes.size}\r\n")
                        responseHeaders.forEach { (name, value) -> writer.print("$name: $value\r\n") }
                        writer.print("Connection: close\r\n\r\n")
                        writer.flush()
                        if (chunkDelayMs > 0 && bytes.size > 1) {
                            val midpoint = bytes.size / 2
                            connection.getOutputStream().write(bytes, 0, midpoint)
                            connection.getOutputStream().flush()
                            Thread.sleep(chunkDelayMs)
                            connection.getOutputStream().write(bytes, midpoint, bytes.size - midpoint)
                        } else {
                            connection.getOutputStream().write(bytes)
                        }
                        connection.getOutputStream().flush()
                    } }
                } catch (_: Exception) {
                    // The client may disconnect intentionally during cancellation tests.
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
}
