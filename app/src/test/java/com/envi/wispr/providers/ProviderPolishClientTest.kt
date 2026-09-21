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
import com.envi.wispr.ui.ModelListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
}
