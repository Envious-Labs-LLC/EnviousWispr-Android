package com.envi.wispr.providers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.ui.ModelListPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key check (#61) and the live model list (#84) over the wire, against the local servers in
 * `ProviderTestServer.kt`. The rows moved whole from `ProviderPolishClientTest` when the discovery client
 * left the polish client (#189); each row's KDoc declares its own class.
 */
class ProviderModelDiscoveryClientTest {

    private fun checker(base: String, provider: Provider, readTimeoutMs: Int = 2_000) = ProviderModelDiscoveryClient(
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
        val client = ProviderModelDiscoveryClient(keyCheckOverrides = mapOf(Provider.OPENAI to "http://127.0.0.1:1/never"), logInfo = {}, logWarn = {})
        assertEquals(ProviderKeyCheck.NotApplicable, client.check(Provider.SELF_HOSTED_POLISH, "anything"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, null), client.check(Provider.OPENAI, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, null), client.check(Provider.OPENAI, "k\u0007"))
    }

    private fun discoverer(server: ScriptedServer, provider: Provider, discoveryTimeoutMs: Int = 10_000, probeTimeoutMs: Int = 2_000, readTimeoutMs: Int = 2_000) = ProviderModelDiscoveryClient(
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

    /**
     * Product Outcome (#110). When the final assertion fails, a key the provider rejects part way through still
     * sends queued probes afterwards: up to forty paid requests on a key the user has just been told is bad.
     *
     * The deadline rows separately prove cancellation and budget checks at expiry. This row additionally
     * proves queued cancellation after an early key rejection, while budget remains.
     * Responses are staged with latches: the first model's probe answers 401 once three distinct
     * probes are in flight, every other probe is held until after discovery has returned. Completion is a
     * signal from the probe pool itself, never a quiet-count poll.
     *
     * The subject is each queued probe, not a count: while gpt-m0 is held, the six probes still waiting in the
     * pool's queue are captured, and every one of them must be cancelled once discovery has returned. The
     * arrival count is a second, weaker check (at most four: three in flight plus the one the freed worker may
     * take, which is held too). CONTROLS, run: removing `futures.forEach { it.cancel(true) }` alone, and
     * cancelling all but the first five futures, each turn this red.
     */
    @Test fun aKeyRejectedPartWayCancelsTheProbesStillQueued() {
        val models = 9
        val threeInFlight = CountDownLatch(3)
        val stagedInTime = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = CountDownLatch(1)
        val pool = ProviderModelDiscoveryClient.PROBE_EXECUTOR as java.util.concurrent.ThreadPoolExecutor
        val queued = java.util.concurrent.atomic.AtomicReference<List<java.util.concurrent.Future<*>>>(emptyList())
        ScriptedServer({ request ->
            when {
                request.path.startsWith("/models") -> 200 to openAiList(*Array(models) { "gpt-m$it" })
                probedModel(request) == "gpt-m0" -> {
                    threeInFlight.countDown()
                    val inFlight = threeInFlight.await(10, TimeUnit.SECONDS)
                    // All nine are submitted before the first is waited on, so six must be queued behind the three
                    // held workers. Bounded: a fixture that cannot see them fails below as a fixture failure.
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (pool.queue.size < models - 3 && System.nanoTime() < until) Thread.sleep(5)
                    queued.set(pool.queue.map { it as java.util.concurrent.Future<*> })
                    stagedInTime.set(inFlight && queued.get().size == models - 3)
                    401 to "{\"error\":{\"code\":\"invalid_api_key\"}}"
                }
                else -> {
                    threeInFlight.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    200 to okBody(Provider.OPENAI)
                }
            }
        }).use { server ->
            fun probed(): List<String> = synchronized(server.requests) {
                server.requests.filter { it.path.startsWith("/probe") }.map { probedModel(it) }
            }
            try {
                val started = System.nanoTime()
                val result = discoverer(server, Provider.OPENAI, discoveryTimeoutMs = 30_000, probeTimeoutMs = 20_000, readTimeoutMs = 20_000)
                    .discoverModels(Provider.OPENAI, "k")
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue("fixture: three probes were in flight and six queued before the rejection", stagedInTime.get())
                assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(401)), result)
                // Fixture precondition, which also proves gpt-m0 is the first future waited on: the return was
                // EARLY, so the per-probe budget check had most of 30 s left and could not stop the queue.
                assertTrue("fixture: discovery returned on the rejection, not near its deadline: $elapsedMs ms", elapsedMs < 10_000)
            } finally {
                release.countDown()
            }

            // Every task queued before these markers has FINISHED once all three markers run at once: the pool is
            // three workers draining one FIFO queue, so three markers holding all three workers means nothing
            // earlier is still running or waiting. A finished probe has already been logged by the server.
            // A marker whose wait runs out FAILS, so a timed-out marker cannot free its worker for the next marker
            // and fake a pool that was never held whole (code review round 2).
            val allHeld = CountDownLatch(3)
            val markers = (1..3).map {
                pool.submit {
                    allHeld.countDown()
                    check(allHeld.await(20, TimeUnit.SECONDS)) { "fixture: the three markers never held the pool together" }
                }
            }
            try {
                markers.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                markers.forEach { it.cancel(true) }
            }

            val notCancelled = queued.get().filterNot { it.isCancelled }
            assertTrue("${notCancelled.size} of the ${models - 3} queued probes were not cancelled", notCancelled.isEmpty())

            val arrived = probed()
            assertTrue("the rejected model's probe was recorded: $arrived", "gpt-m0" in arrived)
            assertTrue("queued probes ran after the key was rejected: $arrived", arrived.size <= 4)
        }
    }

    /**
     * Harness Contract: clock and executor gates distinguish client admission from server log time.
     * Product Outcome: discovery returns unverified rows without starting requests after its budget.
     * The caller is held before cancellation, so cancellation cannot mask a missing budget check.
     */
    @Test fun expiredQueuedProbesNeverReachTheTransport() {
        DeadlineRig(models = 9, expireAfterList = true).use { rig ->
            rig.awaitSubmitted()
            rig.releaseWorkersAndDrain()
            assertEquals("expired queued probes started a transport request", 0, rig.probes.get())
            rig.releaseCaller()
            val result = rig.result() as ProviderDiscovery.Listed
            assertEquals(9, result.models.size)
            assertTrue(result.models.all { it.access == ModelAccess.UNVERIFIED })
        }
    }

    /** Product Outcome: every queued probe is cancelled at expiry, independently of its budget guard. */
    @Test fun theDeadlineCancelsEveryQueuedProbeBeforeTheWorkersAreReleased() {
        DeadlineRig(models = 9, expireAfterList = true).use { rig ->
            rig.awaitSubmitted()
            val queued = rig.pool.queue.toList().map { it as java.util.concurrent.Future<*> }
            assertEquals("fixture: all nine probes were queued", 9, queued.size)
            rig.releaseCaller()
            assertTrue(rig.result() is ProviderDiscovery.Listed)
            assertTrue("some queued probes were not cancelled", queued.all { it.isCancelled })
            rig.releaseWorkersAndDrain()
            assertEquals(0, rig.probes.get())
        }
    }

    /** Product Outcome: a retry cannot spend a fresh budget after the logical probe deadline. */
    @Test fun anInconclusiveReplyAtTheProbeDeadlineDoesNotStartARetry() {
        DeadlineRig(models = 1, expireAfterList = false, expireAfterFirstProbe = true).use { rig ->
            rig.awaitSubmitted()
            rig.releaseWorkersAndDrain()
            assertEquals("a retry started after the logical probe deadline", 1, rig.probes.get())
            rig.releaseCaller()
            val result = rig.result() as ProviderDiscovery.Listed
            assertEquals(ModelAccess.UNVERIFIED, result.models.single().access)
        }
    }

    /** Product Outcome: the frozen-clock fixture also permits a real pre-deadline successful probe. */
    @Test fun aLiveFrozenClockStillAllowsAnAvailableModel() {
        DeadlineRig(models = 1, expireAfterList = false).use { rig ->
            rig.awaitSubmitted()
            rig.releaseWorkersAndDrain()
            assertEquals(1, rig.probes.get())
            rig.releaseCaller()
            val result = rig.result() as ProviderDiscovery.Listed
            assertEquals(ModelAccess.AVAILABLE, result.models.single().access)
        }
    }

    /** All gates are signals with fail-fast bounds, and every test owns its caller executor only. */
    private class DeadlineRig(
        models: Int,
        expireAfterList: Boolean,
        expireAfterFirstProbe: Boolean = false,
    ) : AutoCloseable {
        val pool = ProviderModelDiscoveryClient.PROBE_EXECUTOR as java.util.concurrent.ThreadPoolExecutor
        val probes = java.util.concurrent.atomic.AtomicInteger()
        private val now = java.util.concurrent.atomic.AtomicLong()
        private val workersHeld = CountDownLatch(3)
        private val workersReleased = CountDownLatch(1)
        private val submitted = CountDownLatch(1)
        private val callerReleased = CountDownLatch(1)
        private val listReturned = java.util.concurrent.atomic.AtomicBoolean()
        private val callerParked = java.util.concurrent.atomic.AtomicBoolean()
        private val callerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        private val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val holders = (1..3).map {
            pool.submit {
                workersHeld.countDown()
                check(workersReleased.await(20, TimeUnit.SECONDS)) { "fixture: workers were never released" }
            }
        }
        private val answer: java.util.concurrent.Future<ProviderDiscovery>

        init {
            try {
                check(workersHeld.await(10, TimeUnit.SECONDS)) { "fixture: could not hold all probe workers" }
                val transport = object : ProviderTransport {
                    override fun run(plan: RequestPlan, cancellation: ProviderCancellation, overallTimeoutMs: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Transport {
                        if (plan.method == "GET") {
                            if (expireAfterList) now.set(TimeUnit.SECONDS.toNanos(30))
                            listReturned.set(true)
                            return Transport.Response(200, openAiList(*Array(models) { "gpt-m$it" }))
                        }
                        val count = probes.incrementAndGet()
                        if (expireAfterFirstProbe && count == 1) {
                            now.set(TimeUnit.SECONDS.toNanos(1))
                            return Transport.Response(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}")
                        }
                        return Transport.Response(200, okBody(Provider.OPENAI))
                    }
                }
                val client = ProviderModelDiscoveryClient(
                    transport = transport, discoveryTimeoutMs = 30_000, probeTimeoutMs = 1_000,
                    logInfo = {}, logWarn = {},
                    nanoTime = {
                        // Only the caller's first read after the list: all probe submissions precede it.
                        if (Thread.currentThread() === callerThread.get() && listReturned.get() && callerParked.compareAndSet(false, true)) {
                            submitted.countDown()
                            check(callerReleased.await(20, TimeUnit.SECONDS)) { "fixture: caller was never released" }
                        }
                        now.get()
                    },
                )
                answer = caller.submit<ProviderDiscovery> {
                    callerThread.set(Thread.currentThread())
                    client.discoverModels(Provider.OPENAI, "fixture-key")
                }
            } catch (error: Throwable) {
                callerReleased.countDown()
                workersReleased.countDown()
                holders.forEach { it.cancel(true) }
                caller.shutdownNow()
                check(caller.awaitTermination(10, TimeUnit.SECONDS)) { "fixture: caller leaked during setup" }
                throw error
            }
        }

        fun awaitSubmitted() = check(submitted.await(10, TimeUnit.SECONDS)) { "fixture: discovery never submitted its probes" }
        fun releaseCaller() = callerReleased.countDown()
        fun result(): ProviderDiscovery = answer.get(10, TimeUnit.SECONDS)
        fun releaseWorkersAndDrain() {
            workersReleased.countDown()
            holders.forEach { it.get(10, TimeUnit.SECONDS) }
            // Three simultaneous markers occupy the whole FIFO pool; earlier tasks have finished.
            val allHeld = CountDownLatch(3)
            val markers = (1..3).map {
                pool.submit {
                    allHeld.countDown()
                    check(allHeld.await(10, TimeUnit.SECONDS)) { "fixture: probe pool never drained" }
                }
            }
            try { markers.forEach { it.get(15, TimeUnit.SECONDS) } }
            finally { markers.forEach { it.cancel(true) } }
        }
        override fun close() {
            callerReleased.countDown()
            workersReleased.countDown()
            try {
                answer.get(10, TimeUnit.SECONDS)
                releaseWorkersAndDrain()
            } finally {
                answer.cancel(true)
                holders.forEach { it.cancel(true) }
                caller.shutdownNow()
                check(caller.awaitTermination(10, TimeUnit.SECONDS)) { "fixture: caller leaked" }
            }
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
        val many = Array(ProviderModelDiscoveryClient.MAX_PROBES + 3) { "gpt-x$it" }
        ScriptedServer({ request -> if (request.path.startsWith("/models")) 200 to openAiList(*many) else 200 to okBody(Provider.OPENAI) }, connections = 128).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            assertEquals(many.size, result.models.size)
            assertEquals(ProviderModelDiscoveryClient.MAX_PROBES, result.models.count { it.access == ModelAccess.AVAILABLE })
            assertEquals(3, result.models.count { it.access == ModelAccess.UNVERIFIED })
            assertEquals(ProviderModelDiscoveryClient.MAX_PROBES, server.requests.count { it.path.startsWith("/probe") })
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
        val client = ProviderModelDiscoveryClient(keyCheckOverrides = mapOf(Provider.OPENAI to "http://127.0.0.1:1/never"), logInfo = {}, logWarn = {})
        assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.NotApplicable), client.discoverModels(Provider.SELF_HOSTED_POLISH, "x"))
        ScriptedServer({ _ -> 200 to openAiList("dall-e-3", "whisper-1") }).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed
            assertEquals(emptyList<DiscoveredModel>(), result.models)
            assertEquals(0, server.requests.count { it.path.startsWith("/probe") })
        }
    }

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
     * Product Outcome, measured 2026-09-02 against live OpenAI and Gemini keys.
     *
     * A model that spent the whole probe cap reasoning gets ONE retry that asks it not to. `gpt-5-nano`
     * burns the entire cap at 64, 128 and 256 tokens, so no cap alone reaches it, and it and `gpt-5-mini`
     * are the two newest models the founder's key can reach (#103). Asked not to reason, both answer.
     *
     * **The retry may only improve a verdict.** The suppression is refused outright by models that cannot
     * honour it — `reasoning.effort` by every OpenAI model that does not reason, `thinkingBudget: 0` by
     * `gemini-2.5-pro` — so a retry that fails must leave the first answer standing. When this fails, a
     * working model is either hidden or, worse, condemned by a request it never asked for.
     */
    @Test fun aModelThatSpentTheProbeCapThinkingIsAskedOnceMoreNotTo() {
        val thinking = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}"
        val answered = "{\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"Hello there\"}]}]}"
        val silent = "{\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"\"}]}]}"
        val refusedParameter = "{\"error\":{\"message\":\"Unsupported parameter: 'reasoning.effort'\"}}"
        // The retry is told apart from the first ask by the suppression it carries, exactly as the real
        // provider sees it.
        fun isRetry(body: String) = body.contains("\"reasoning\"")
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-reasoner", "gpt-stubborn", "gpt-plain", "gpt-mute", "gpt-denied")
            else when (probedModel(request)) {
                // Refused by its STATUS, and its empty body reads as inconclusive. The verdict is already
                // made, so there is nothing a retry could add and none is spent.
                "gpt-denied" -> 403 to "{}"

                // Thinks on the first ask, answers when told not to.
                "gpt-reasoner" -> if (isRetry(request.body)) 200 to answered else 200 to thinking
                // Thinks on the first ask, and REFUSES the suppression. This is the shape that must not be
                // turned into a refusal by the retry itself.
                "gpt-stubborn" -> if (isRetry(request.body)) 400 to refusedParameter else 200 to thinking
                // Finished and wrote nothing: refused on the first ask, and never retried.
                "gpt-mute" -> 200 to silent
                else -> 200 to answered
            }
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            assertEquals(ModelAccess.AVAILABLE, models["gpt-reasoner"])
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-stubborn"])
            assertEquals(ModelAccess.AVAILABLE, models["gpt-plain"])
            assertEquals(ModelAccess.UNAVAILABLE, models["gpt-mute"])
            assertEquals(ModelAccess.UNAVAILABLE, models["gpt-denied"])

            // Only a model that told us nothing costs a second request; the other two are asked once.
            val asks = server.requests.filter { it.path.startsWith("/probe") }.groupBy { probedModel(it) }
            assertEquals(2, asks["gpt-reasoner"]?.size)
            assertEquals(2, asks["gpt-stubborn"]?.size)
            assertEquals(1, asks["gpt-plain"]?.size)
            assertEquals(1, asks["gpt-mute"]?.size)
            assertEquals(1, asks["gpt-denied"]?.size)
            // The retry really does carry the suppression and more room, or it is the same ask twice.
            val retry = asks["gpt-reasoner"]!!.single { isRetry(it.body) }.body
            assertTrue(retry, retry.contains("\"effort\":\"minimal\""))
            assertTrue(retry, retry.contains("\"max_output_tokens\":${ProviderAdapters.PROBE_RETRY_OUTPUT_TOKENS}"))
        }
    }

    /**
     * Product Outcome. A provider that is rate limiting us or falling over must not be asked twice as fast.
     * Its error envelope parses and carries no text, so it READS inconclusive while the status has already
     * decided the model; only a 200 earns the retry (review round 1 on #106).
     *
     * When this fails, discovery doubles its traffic during exactly the outage that caused it, and burns
     * the deadline that other models needed.
     */
    @Test fun aProviderThatIsRateLimitingOrFailingIsNotAskedAgainImmediately() {
        val rateLimited = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}"
        val serverError = "{\"error\":{\"message\":\"The server had an error\"}}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-busy", "gpt-broken")
            else when (probedModel(request)) {
                "gpt-busy" -> 429 to rateLimited
                else -> 503 to serverError
            }
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-busy"])
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-broken"])
            // One ask each. Both bodies parse with no text, so a reading-only rule would have retried both.
            assertEquals(2, server.requests.count { it.path.startsWith("/probe") })
        }
    }

    /**
     * Product Outcome. A key revoked BETWEEN the two asks is news, and the retry is the only thing that
     * saw it. Keeping the first answer would leave the whole connection looking merely untested while the
     * key is dead (review round 1 on #106).
     */
    @Test fun aKeyThatDiesBetweenTheTwoAsksIsReportedRatherThanSwallowed() {
        val thinking = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to openAiList("gpt-reasoner")
            else if (request.body.contains("\"reasoning\"")) 401 to "{\"error\":{\"message\":\"Incorrect API key\"}}"
            else 200 to thinking
        }).use { server ->
            val result = discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k")
            assertEquals(ProviderDiscovery.Refused(ProviderKeyCheck.Rejected(401)), result)
        }
    }

    /**
     * Product Outcome, END TO END from the provider's list to the badge on screen, which is the check the
     * two halves of #103 kept passing individually while the outcome stayed broken.
     *
     * This is the founder's own OpenAI key, 2026-09-02: `gpt-4.1-mini` from April 2025 answers the probe
     * outright, while `gpt-5-mini` and `gpt-5-nano` from that August spend the cap thinking and answer only
     * when asked not to.
     *
     * The retry must make all three USABLE, so none is hidden and he can pick any of them. The badge must
     * still land on `gpt-4.1-mini`, because the two 5s are on OpenAI's deprecations list for 2026-10-23 and
     * the badge auto-saves. Usable and recommended are different questions, and this is the test that says
     * so end to end.
     */
    @Test fun everyModelAKeyCanReachIsOfferedButOnlyAVettedOneIsRecommended() {
        val day = 24 * 60 * 60L
        val thinking = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}"
        val answered = "{\"status\":\"completed\",\"output\":[{\"content\":[{\"text\":\"Hello there\"}]}]}"
        val list = "{\"data\":[" + listOf(
            "gpt-4.1-mini" to 20_183L,
            "gpt-5-mini" to 20_305L,
            "gpt-5-nano" to 20_305L,
        ).joinToString(",") { (id, epochDay) -> "{\"id\":\"$id\",\"created\":${epochDay * day}}" } + "]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to list
            else if (probedModel(request)!!.startsWith("gpt-5") && !request.body.contains("\"reasoning\"")) 200 to thinking
            else 200 to answered
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed).models
            // The retry did its job first: all three are usable, so all three can be recommended.
            assertEquals(
                listOf(ModelAccess.AVAILABLE, ModelAccess.AVAILABLE, ModelAccess.AVAILABLE),
                models.sortedBy { it.id }.map { it.access },
            )
            assertEquals("gpt-4.1-mini", ModelListPresentation.recommendedPick(Provider.OPENAI, models))
            // And the page agrees with the pick, with exactly one badge on it. All three rows are shown:
            // the retry is what keeps the newest two off the hidden list, whatever wears the badge.
            val rows = ModelListPresentation.present(Provider.OPENAI, models, "", "")
            assertEquals("gpt-4.1-mini", rows.first { it.tag == "Recommended" }.id)
            assertEquals(1, rows.count { it.tag == "Recommended" })
            assertEquals(setOf("gpt-4.1-mini", "gpt-5-mini", "gpt-5-nano"), rows.map { it.id }.toSet())
        }
    }

    /** Claude opts in to thinking and this request does not, so there is nothing to suppress and no retry. */
    @Test fun claudeIsNeverAskedTwiceBecauseItWasNeverAskedToThink() {
        val thinking = "{\"stop_reason\":\"max_tokens\",\"content\":[]}"
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to "{\"data\":[{\"id\":\"claude-quiet\"}],\"has_more\":false}"
            else 200 to thinking
        }).use { server ->
            val models = (discoverer(server, Provider.CLAUDE).discoverModels(Provider.CLAUDE, "sk-ant") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            assertEquals(ModelAccess.UNVERIFIED, models["claude-quiet"])
            assertEquals(1, server.requests.count { it.path.startsWith("/probe") })
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
        val rows = (1..ProviderModelDiscoveryClient.MAX_PROBES + 1).joinToString(",") { n ->
            "{\"id\":\"gpt-m$n\",\"created\":${1_700_000_000L + n * day}}"
        }
        ScriptedServer({ request ->
            if (request.path.startsWith("/models")) 200 to "{\"data\":[$rows]}" else 200 to okBody(Provider.OPENAI)
        }).use { server ->
            val models = (discoverer(server, Provider.OPENAI).discoverModels(Provider.OPENAI, "k") as ProviderDiscovery.Listed)
                .models.associate { it.id to it.access }
            val newest = "gpt-m${ProviderModelDiscoveryClient.MAX_PROBES + 1}"
            assertEquals(ModelAccess.AVAILABLE, models[newest])
            // The one left unprobed is the OLDEST, which is the model a user is least likely to pick.
            assertEquals(ModelAccess.UNVERIFIED, models["gpt-m1"])
            assertEquals(ProviderModelDiscoveryClient.MAX_PROBES, server.requests.count { it.path.startsWith("/probe") })
        }
    }
}
