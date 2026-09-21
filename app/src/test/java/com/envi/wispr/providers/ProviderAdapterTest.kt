package com.envi.wispr.providers

import com.envi.wispr.polish.PolishFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.net.URI

/**
 * Product Outcome, without a socket (#189): each adapter's request shape, reply reading, list envelope,
 * error markers, key and probe verdicts, paging, model filter, display name and retry decision, against
 * literal fixtures. When a row here fails, a provider is sent a body it rejects (the user's polish silently
 * returns raw words), a working model is hidden or a broken one offered, or a key is refused or accepted
 * wrongly. Every expected value is a literal; none is computed with the code under test.
 *
 * The rows marked "moved" came whole from `ProviderPolishClientTest` and `ModelListRulesTest` when the
 * decisions they assert moved into the adapters; their assertions are unchanged.
 *
 * `everyProviderResolvesToItsNamedAdapter` is a Drift Guard and is counted as such.
 */
class ProviderAdapterTest {
    private val transcript = "please send the deck to finance today"
    private val system = ProviderPolishPrompt.systemInstruction(transcript)
    private val user = ProviderPolishPrompt.userMessage(transcript)

    private fun request(provider: Provider, endpoint: String? = null, protocol: SelfHostedProtocol = SelfHostedProtocol.OPENAI_COMPATIBLE) =
        ProviderPolishRequest(provider, "model-x", transcript, "key-123", endpoint, protocol)

    // ---- The polish request per provider.

    @Test fun openAiPolishPlanCarriesInstructionsInputAndStoreFalse() {
        val plan = OpenAiAdapter.polishPlan(request(Provider.OPENAI), endpointOverride = null)
        assertEquals(URI("https://api.openai.com/v1/responses"), plan.url)
        assertEquals(mapOf("Authorization" to "Bearer key-123"), plan.headers)
        assertEquals("POST", plan.method)
        assertEquals(ProviderReplyFormat.OPENAI_RESPONSES, plan.responseFormat)
        assertEquals(
            "{\"model\":\"model-x\",\"instructions\":${jsonQuoted(system)},\"input\":${jsonQuoted(user)},\"store\":false}",
            plan.body,
        )
        assertEquals(URI("http://127.0.0.1:9/polish"), OpenAiAdapter.polishPlan(request(Provider.OPENAI), "http://127.0.0.1:9/polish").url)
    }

    @Test fun geminiPolishPlanNamesTheModelInThePathAndUsesTheGoogleHeader() {
        val plan = GeminiAdapter.polishPlan(request(Provider.GEMINI), endpointOverride = null)
        assertEquals(URI("https://generativelanguage.googleapis.com/v1beta/models/model-x:generateContent"), plan.url)
        assertEquals(mapOf("x-goog-api-key" to "key-123"), plan.headers)
        assertEquals(ProviderReplyFormat.GEMINI, plan.responseFormat)
        assertEquals(
            "{\"systemInstruction\":{\"parts\":[{\"text\":${jsonQuoted(system)}}]},\"contents\":[{\"parts\":[{\"text\":${jsonQuoted(user)}}]}]}",
            plan.body,
        )
        // The override keeps the model in the path, so a scripted server can tell probes apart by it.
        assertEquals(URI("http://127.0.0.1:9/probe/model-x:generateContent"), GeminiAdapter.polishPlan(request(Provider.GEMINI), "http://127.0.0.1:9/probe/").url)
        // A model id with a space is percent-encoded, never a raw URI failure.
        assertEquals(
            URI("https://generativelanguage.googleapis.com/v1beta/models/gemini%20x:generateContent"),
            GeminiAdapter.polishPlan(ProviderPolishRequest(Provider.GEMINI, "gemini x", transcript, "k"), null).url,
        )
    }

    @Test fun claudePolishPlanSendsVersionSystemAndMaxTokens() {
        val plan = ClaudeAdapter.polishPlan(request(Provider.CLAUDE), endpointOverride = null)
        assertEquals(URI("https://api.anthropic.com/v1/messages"), plan.url)
        assertEquals(mapOf("x-api-key" to "key-123", "anthropic-version" to "2023-06-01"), plan.headers)
        assertEquals(ProviderReplyFormat.CLAUDE, plan.responseFormat)
        assertEquals(
            "{\"model\":\"model-x\",\"max_tokens\":8192,\"system\":${jsonQuoted(system)},\"messages\":[{\"role\":\"user\",\"content\":${jsonQuoted(user)}}]}",
            plan.body,
        )
    }

    @Test fun selfHostedPlansDifferOnlyInPathAndFormat() {
        val chat = SelfHostedAdapter.polishPlan(request(Provider.SELF_HOSTED_POLISH, "https://polish.example.net:8080/", SelfHostedProtocol.OPENAI_COMPATIBLE), null)!!
        val ollama = SelfHostedAdapter.polishPlan(request(Provider.SELF_HOSTED_POLISH, "https://polish.example.net:8080", SelfHostedProtocol.OLLAMA), null)!!
        assertEquals(URI("https://polish.example.net:8080/v1/chat/completions"), chat.url)
        assertEquals(URI("https://polish.example.net:8080/api/chat"), ollama.url)
        assertEquals(ProviderReplyFormat.OPENAI_CHAT, chat.responseFormat)
        assertEquals(ProviderReplyFormat.OLLAMA, ollama.responseFormat)
        val body = "{\"model\":\"model-x\",\"messages\":[{\"role\":\"system\",\"content\":${jsonQuoted(system)}},{\"role\":\"user\",\"content\":${jsonQuoted(user)}}],\"stream\":false}"
        assertEquals(body, chat.body)
        assertEquals(body, ollama.body)
        assertEquals(mapOf("Authorization" to "Bearer key-123"), chat.headers)
        // No key, no header; the query and the missing endpoint are refused as no plan, never an exception.
        assertEquals(emptyMap<String, String>(), SelfHostedAdapter.polishPlan(ProviderPolishRequest(Provider.SELF_HOSTED_POLISH, "m", transcript, null, "https://polish.example.net:8080"), null)!!.headers)
        assertNull(SelfHostedAdapter.polishPlan(request(Provider.SELF_HOSTED_POLISH, "https://polish.example.net:8080/?x=1"), null))
        assertNull(SelfHostedAdapter.polishPlan(request(Provider.SELF_HOSTED_POLISH, endpoint = null), null))
    }

    // ---- The discovery probe per provider and style (#84, #103).

    @Test fun probePlansPerStyle() {
        val openAiDefault = OpenAiAdapter.probePlan("gpt-x", "k", ProbeStyle.DEFAULT, null)
        assertEquals("{\"model\":\"gpt-x\",\"input\":\"Hi\",\"max_output_tokens\":16,\"store\":false}", openAiDefault.body)
        assertEquals(URI("https://api.openai.com/v1/responses"), openAiDefault.url)
        assertEquals(
            "{\"model\":\"gpt-x\",\"input\":\"Hi\",\"max_output_tokens\":64,\"reasoning\":{\"effort\":\"minimal\"},\"store\":false}",
            OpenAiAdapter.probePlan("gpt-x", "k", ProbeStyle.NO_REASONING, null).body,
        )
        assertEquals(
            "{\"contents\":[{\"parts\":[{\"text\":\"Hi\"}]}],\"generationConfig\":{\"maxOutputTokens\":5}}",
            GeminiAdapter.probePlan("g-x", "k", ProbeStyle.DEFAULT, null).body,
        )
        assertEquals(
            "{\"contents\":[{\"parts\":[{\"text\":\"Hi\"}]}],\"generationConfig\":{\"maxOutputTokens\":64,\"thinkingConfig\":{\"thinkingBudget\":0}}}",
            GeminiAdapter.probePlan("g-x", "k", ProbeStyle.NO_REASONING, null).body,
        )
        assertEquals(URI("http://127.0.0.1:9/probe/g-x:generateContent"), GeminiAdapter.probePlan("g-x", "k", ProbeStyle.DEFAULT, "http://127.0.0.1:9/probe").url)
        assertEquals(
            "{\"model\":\"c-x\",\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}",
            ClaudeAdapter.probePlan("c-x", "k", ProbeStyle.DEFAULT, null)!!.body,
        )
        // Claude never opted into thinking, so there is nothing to ask it not to do.
        assertNull(ClaudeAdapter.probePlan("c-x", "k", ProbeStyle.NO_REASONING, null))
        assertNull(SelfHostedAdapter.probePlan("m", "k", ProbeStyle.DEFAULT, null))
        assertNull(SelfHostedAdapter.probePlan("m", "k", ProbeStyle.NO_REASONING, null))
    }

    // ---- The model list: URLs, paging, rows and envelope (#61, #84).

    @Test fun listUrlsAndPaging() {
        assertEquals(URI("https://api.openai.com/v1/models"), OpenAiAdapter.listUrl(null))
        assertEquals(URI("https://generativelanguage.googleapis.com/v1beta/models"), GeminiAdapter.listUrl(null))
        assertEquals(URI("https://api.anthropic.com/v1/models"), ClaudeAdapter.listUrl(null))
        assertNull(SelfHostedAdapter.listUrl(null))
        assertEquals(URI("http://127.0.0.1:9/models"), OpenAiAdapter.listUrl("http://127.0.0.1:9/models"))
        // The key check's GET is the plain list; discovery's first page asks Claude for its largest page.
        assertEquals(URI("https://api.openai.com/v1/models"), OpenAiAdapter.firstListPage(null))
        assertEquals(URI("https://api.anthropic.com/v1/models?limit=1000"), ClaudeAdapter.firstListPage(null))
        assertEquals(URI("http://127.0.0.1:9/models?x=1&limit=1000"), ClaudeAdapter.firstListPage("http://127.0.0.1:9/models?x=1"))
        assertNull(SelfHostedAdapter.firstListPage(null))

        val seen = HashSet<String>()
        val page = { hasMore: Boolean, lastId: String? -> ModelPage(emptyList(), hasMore, lastId) }
        assertEquals(ListAdvance.Done, ClaudeAdapter.nextListPage(page(false, "x"), seen, null))
        assertEquals(ListAdvance.Next(URI("https://api.anthropic.com/v1/models?limit=1000&after_id=x")), ClaudeAdapter.nextListPage(page(true, "x"), seen, null))
        assertEquals(setOf("x"), seen)
        assertEquals(ListAdvance.Next(URI("https://api.anthropic.com/v1/models?limit=1000&after_id=y")), ClaudeAdapter.nextListPage(page(true, "y"), seen, null))
        assertEquals(ListAdvance.Malformed, ClaudeAdapter.nextListPage(page(true, null), HashSet(), null))
        assertEquals(ListAdvance.Malformed, ClaudeAdapter.nextListPage(page(true, ""), HashSet(), null))
        assertEquals(ListAdvance.Malformed, ClaudeAdapter.nextListPage(page(true, "x"), hashSetOf("x"), null))
        // A cursor with a space is percent-encoded into the query.
        assertEquals(ListAdvance.Next(URI("https://api.anthropic.com/v1/models?limit=1000&after_id=a%20b")), ClaudeAdapter.nextListPage(page(true, "a b"), HashSet(), null))
        assertEquals(ListAdvance.Done, OpenAiAdapter.nextListPage(page(true, "x"), HashSet(), null))
        assertEquals(ListAdvance.Done, GeminiAdapter.nextListPage(page(true, "x"), HashSet(), null))
        assertEquals(ListAdvance.Done, SelfHostedAdapter.nextListPage(page(true, "x"), HashSet(), null))
    }

    /** Moved from `ModelListRulesTest.claudePaginationStopsContinuesOrCallsACursorMalformed`; the six decisions, unchanged. */
    @Test fun claudePaginationStopsContinuesOrCallsACursorMalformed() {
        assertEquals(ClaudeAdapter.Pagination.Stop, ClaudeAdapter.pagination(false, "x", emptySet()))
        assertEquals(ClaudeAdapter.Pagination.Continue("x"), ClaudeAdapter.pagination(true, "x", emptySet()))
        assertEquals(ClaudeAdapter.Pagination.Continue("y"), ClaudeAdapter.pagination(true, "y", setOf("x")))
        assertEquals(ClaudeAdapter.Pagination.Malformed, ClaudeAdapter.pagination(true, null, emptySet()))
        assertEquals(ClaudeAdapter.Pagination.Malformed, ClaudeAdapter.pagination(true, "", emptySet()))
        assertEquals(ClaudeAdapter.Pagination.Malformed, ClaudeAdapter.pagination(true, "x", setOf("x")))
    }

    @Test fun modelRowsPerProvider() {
        // OpenAI's `created` is unix SECONDS; 1_787_875_200 s is 2026-08-28T00:00:00Z, so the row carries 1_787_875_200_000 ms.
        val openAi = OpenAiAdapter.parseModelRows("{\"data\":[{\"id\":\"gpt-a\",\"created\":1787875200},{\"id\":\"gpt-b\"},{\"object\":\"model\"}]}")!!
        assertEquals(listOf(ListedModel("gpt-a", null, 1_787_875_200_000L), ListedModel("gpt-b", null, null)), openAi.rows)
        assertFalse(openAi.hasMore)
        assertNull(openAi.lastId)

        val gemini = GeminiAdapter.parseModelRows(
            "{\"models\":[{\"name\":\"models/gemini-a\",\"displayName\":\"Gemini A\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
                "{\"name\":\"models/embed-1\",\"supportedGenerationMethods\":[\"embedContent\"]},{\"name\":\"models/no-methods\"}]}",
        )!!
        assertEquals(listOf(ListedModel("gemini-a", "Gemini A", null)), gemini.rows)

        val claude = ClaudeAdapter.parseModelRows(
            "{\"data\":[{\"id\":\"claude-a\",\"display_name\":\"Claude A\",\"created_at\":\"2026-08-28T00:00:00Z\"},{\"id\":\"claude-b\",\"created_at\":\"not a date\"}],\"has_more\":true,\"last_id\":\"claude-b\"}",
        )!!
        assertEquals(listOf(ListedModel("claude-a", "Claude A", 1_787_875_200_000L), ListedModel("claude-b", null, null)), claude.rows)
        assertTrue(claude.hasMore)
        assertEquals("claude-b", claude.lastId)

        // Not the provider's shape: null, never an empty page.
        assertNull(OpenAiAdapter.parseModelRows("{\"models\":[]}"))
        assertNull(GeminiAdapter.parseModelRows("{\"data\":[]}"))
        assertNull(ClaudeAdapter.parseModelRows("[]"))
        assertNull(ClaudeAdapter.parseModelRows("not json"))
        assertNull(SelfHostedAdapter.parseModelRows("{\"data\":[{\"id\":\"m\"}]}"))
    }

    @Test fun listEnvelopePerProvider() {
        assertTrue(OpenAiAdapter.hasModelList("{\"data\":[]}"))
        assertTrue(ClaudeAdapter.hasModelList("{\"data\":[]}"))
        assertTrue(GeminiAdapter.hasModelList("{\"models\":[]}"))
        assertFalse(OpenAiAdapter.hasModelList("{\"models\":[]}"))
        assertFalse(GeminiAdapter.hasModelList("{\"data\":[]}"))
        assertFalse(OpenAiAdapter.hasModelList("{}"))
        assertFalse(OpenAiAdapter.hasModelList("{\"data\":{}}"))
        assertFalse(OpenAiAdapter.hasModelList("<html>captive portal</html>"))
        assertFalse(SelfHostedAdapter.hasModelList("{\"data\":[]}"))
    }

    // ---- The reply readings, one per format.

    @Test fun replyTextPerFormat() {
        // A reasoning model puts a `reasoning` item first; the answer is the `message` item after it (#65).
        assertEquals(
            "hello there",
            ProviderReplyFormat.OPENAI_RESPONSES.replyText(root("{\"output\":[{\"type\":\"reasoning\",\"summary\":[]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello there\"}]}]}")),
        )
        assertEquals("plain", ProviderReplyFormat.OPENAI_RESPONSES.replyText(root("{\"output\":[{\"content\":[{\"text\":\"plain\"}]}]}")))
        assertEquals("chat", ProviderReplyFormat.OPENAI_CHAT.replyText(root("{\"choices\":[{\"message\":{\"content\":\"chat\"}}]}")))
        // A multipart reply whose FIRST part is empty still carries its answer (#104 R1).
        assertEquals("second", ProviderReplyFormat.GEMINI.replyText(root("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"},{\"text\":\"second\"}]}}]}")))
        assertEquals("claude", ProviderReplyFormat.CLAUDE.replyText(root("{\"content\":[{\"type\":\"text\",\"text\":\"claude\"}]}")))
        assertEquals("ollama", ProviderReplyFormat.OLLAMA.replyText(root("{\"message\":{\"content\":\"ollama\"}}")))
        assertEquals("generate", ProviderReplyFormat.OLLAMA.replyText(root("{\"response\":\"generate\"}")))
        // A reasoning block is stripped; the answer is trimmed.
        assertEquals("after", ProviderReplyFormat.OLLAMA.replyText(root("{\"message\":{\"content\":\"<think>x</think>  after  \"}}")))
        // Whitespace only is an empty answer, and NONE never reads text.
        assertEquals("", ProviderReplyFormat.CLAUDE.replyText(root("{\"content\":[{\"text\":\"   \"}]}")))
        assertNull(ProviderReplyFormat.CLAUDE.replyText(root("{\"content\":[]}")))
        assertNull(ProviderReplyFormat.NONE.replyText(root("{\"output\":[{\"content\":[{\"text\":\"x\"}]}]}")))
    }

    @Test fun endedOfItsOwnAccordPerFormat() {
        assertTrue(ProviderReplyFormat.OPENAI_RESPONSES.endedOfItsOwnAccord(root("{\"status\":\"completed\"}")))
        assertFalse(ProviderReplyFormat.OPENAI_RESPONSES.endedOfItsOwnAccord(root("{\"status\":\"incomplete\"}")))
        assertTrue(ProviderReplyFormat.OPENAI_CHAT.endedOfItsOwnAccord(root("{\"choices\":[{\"finish_reason\":\"stop\"}]}")))
        assertFalse(ProviderReplyFormat.OPENAI_CHAT.endedOfItsOwnAccord(root("{\"choices\":[{\"finish_reason\":\"length\"}]}")))
        assertTrue(ProviderReplyFormat.GEMINI.endedOfItsOwnAccord(root("{\"candidates\":[{\"finishReason\":\"STOP\"}]}")))
        assertFalse(ProviderReplyFormat.GEMINI.endedOfItsOwnAccord(root("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}")))
        assertTrue(ProviderReplyFormat.CLAUDE.endedOfItsOwnAccord(root("{\"stop_reason\":\"end_turn\"}")))
        assertTrue(ProviderReplyFormat.CLAUDE.endedOfItsOwnAccord(root("{\"stop_reason\":\"stop_sequence\"}")))
        assertFalse(ProviderReplyFormat.CLAUDE.endedOfItsOwnAccord(root("{\"stop_reason\":\"max_tokens\"}")))
        assertTrue(ProviderReplyFormat.OLLAMA.endedOfItsOwnAccord(root("{\"done_reason\":\"stop\"}")))
        assertFalse(ProviderReplyFormat.OLLAMA.endedOfItsOwnAccord(root("{\"done_reason\":\"length\"}")))
        // An absent marker is "not proved", never "finished".
        assertFalse(ProviderReplyFormat.OPENAI_RESPONSES.endedOfItsOwnAccord(root("{}")))
        assertFalse(ProviderReplyFormat.GEMINI.endedOfItsOwnAccord(root("{\"candidates\":[]}")))
        assertFalse(ProviderReplyFormat.NONE.endedOfItsOwnAccord(root("{\"status\":\"completed\"}")))
    }

    private fun root(body: String): Any? = ProviderJson.parseOrNull(body)!!.root

    /** Observability Contract: a logged or thrown plan can never carry a URL, a header or a body. */
    @Test fun requestPlanToStringRedacts() {
        val plan = OpenAiAdapter.polishPlan(request(Provider.OPENAI), "http://127.0.0.1:9/secret-path")
        val text = plan.toString()
        assertEquals("RequestPlan(url=<redacted>, headers=<redacted>, body=<redacted>, responseFormat=OPENAI_RESPONSES)", text)
        assertFalse(text.contains("key-123"))
        assertFalse(text.contains("secret-path"))
        assertFalse(text.contains(transcript))
    }

    // ---- What the statuses and bodies mean, per provider.

    /** Moved from `ProviderPolishClientTest.theBodyMarkersAreTheMacOsOnesPerProvider` (#77); every assertion unchanged. */
    @Test fun errorSignalPerAdapter() {
        // (#77) OpenAI
        assertEquals(ProviderErrorSignal.OUT_OF_CREDITS, ProviderAdapters.of(Provider.OPENAI).errorSignal(429, "{\"type\":\"insufficient_quota\"}"))
        assertNull(ProviderAdapters.of(Provider.OPENAI).errorSignal(429, "{\"type\":\"rate_limit\"}"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderAdapters.of(Provider.OPENAI).errorSignal(400, "context_length_exceeded"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderAdapters.of(Provider.OPENAI).errorSignal(400, "content_filter"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderAdapters.of(Provider.OPENAI).errorSignal(400, "content_policy"))
        assertNull(ProviderAdapters.of(Provider.OPENAI).errorSignal(400, "something else"))
        assertNull(ProviderAdapters.of(Provider.OPENAI).errorSignal(401, "context_length_exceeded"))
        // Gemini
        assertEquals(ProviderErrorSignal.KEY_REJECTED, ProviderAdapters.of(Provider.GEMINI).errorSignal(400, "API_KEY_INVALID"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderAdapters.of(Provider.GEMINI).errorSignal(400, "exceeds the maximum number of tokens"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderAdapters.of(Provider.GEMINI).errorSignal(400, "PROHIBITED_CONTENT"))
        assertEquals(ProviderErrorSignal.CONTENT_BLOCKED, ProviderAdapters.of(Provider.GEMINI).errorSignal(400, "\"blockReason\":\"SAFETY\""))
        assertNull(ProviderAdapters.of(Provider.GEMINI).errorSignal(400, "something else"))
        // Claude
        assertEquals(ProviderErrorSignal.OUT_OF_CREDITS, ProviderAdapters.of(Provider.CLAUDE).errorSignal(400, "Your credit balance is too low"))
        assertEquals(ProviderErrorSignal.INPUT_TOO_LONG, ProviderAdapters.of(Provider.CLAUDE).errorSignal(400, "prompt is too long: 250024 tokens"))
        assertNull(ProviderAdapters.of(Provider.CLAUDE).errorSignal(400, "something else"))
        // Self-hosted has no markers
        assertNull(ProviderAdapters.of(Provider.SELF_HOSTED_POLISH).errorSignal(400, "API_KEY_INVALID insufficient_quota"))
    }

    @Test fun keyCheckVerdictPerAdapter() {
        val list = "{\"data\":[]}"
        assertEquals(ProviderKeyCheck.Accepted, OpenAiAdapter.keyCheckVerdict(200, list))
        assertEquals(ProviderKeyCheck.Accepted, GeminiAdapter.keyCheckVerdict(200, "{\"models\":[]}"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 200), OpenAiAdapter.keyCheckVerdict(200, "<html>portal</html>"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 204), OpenAiAdapter.keyCheckVerdict(204, ""))
        assertEquals(ProviderKeyCheck.Rejected(401), OpenAiAdapter.keyCheckVerdict(401, ""))
        // 403 is a wrong key on Gemini and a recognised-but-refused key elsewhere (the macOS mapping).
        assertEquals(ProviderKeyCheck.Rejected(403), GeminiAdapter.keyCheckVerdict(403, ""))
        assertEquals(ProviderKeyCheck.Denied(403), OpenAiAdapter.keyCheckVerdict(403, ""))
        assertEquals(ProviderKeyCheck.Denied(403), ClaudeAdapter.keyCheckVerdict(403, ""))
        assertEquals(ProviderKeyCheck.Rejected(400), GeminiAdapter.keyCheckVerdict(400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 400), GeminiAdapter.keyCheckVerdict(400, "{\"error\":{\"message\":\"bad\"}}"))
        // Gemini's 429 cannot be told from an exhausted quota; the others' can.
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.RATE_OR_QUOTA, 429), GeminiAdapter.keyCheckVerdict(429, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.RATE_LIMITED, 429), OpenAiAdapter.keyCheckVerdict(429, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.RATE_LIMITED, 429), ClaudeAdapter.keyCheckVerdict(429, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.PROVIDER_ERROR, 503), ClaudeAdapter.keyCheckVerdict(503, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST, 418), OpenAiAdapter.keyCheckVerdict(418, ""))
        assertEquals(ProviderKeyCheck.Unverified(PolishFailure.UNEXPECTED, 302), OpenAiAdapter.keyCheckVerdict(302, ""))
        assertEquals(PolishFailure.RATE_OR_QUOTA, GeminiAdapter.rateLimitFailure)
        assertEquals(PolishFailure.RATE_LIMITED, OpenAiAdapter.rateLimitFailure)
        assertEquals(PolishFailure.RATE_LIMITED, ClaudeAdapter.rateLimitFailure)
        assertEquals(PolishFailure.RATE_LIMITED, SelfHostedAdapter.rateLimitFailure)
    }

    /**
     * Moved from `ModelListRulesTest.probeOutcomeFollowsTheMacRulesPerProvider`; every assertion unchanged.
     * What the body carried is the CLIENT's answer, produced by the same parser polish uses; the envelope
     * shapes are asserted against a real server in `ProviderModelDiscoveryClientTest`. Passing it in keeps
     * this table about STATUS, which is the only thing the verdict decides.
     */
    @Test fun probeOutcomeFollowsTheMacRulesPerProvider() {
        fun a(p: Provider, s: Int?, b: String? = "{}", reply: ModelListRules.ProbeReply = ModelListRules.ProbeReply.TEXT) =
            ProviderAdapters.of(p).probeOutcome(s, b, reply)
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.OPENAI, 200))
        // A 200 that carries no text is the transcribe case, and it is UNAVAILABLE, not available.
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.GEMINI, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.SELF_HOSTED_POLISH, 200, reply = ModelListRules.ProbeReply.NO_TEXT))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.SELF_HOSTED_POLISH, 200))
        // A reply that ended for any reason other than the model finishing proved NOTHING. Measured
        // 2026-09-02: gemini-2.5-pro spends every token of the probe's cap on thinking, at every cap. It
        // is a working model, so it must not be refused, and it earned no verdict, so it is not available.
        // The sweep is over the whole enum, so a fourth reading has to declare its own access.
        ModelListRules.ProbeReply.entries.forEach { reply ->
            val expected = when (reply) {
                ModelListRules.ProbeReply.TEXT -> ModelAccess.AVAILABLE
                ModelListRules.ProbeReply.NO_TEXT -> ModelAccess.UNAVAILABLE
                ModelListRules.ProbeReply.INCONCLUSIVE -> ModelAccess.UNVERIFIED
            }
            assertEquals("$reply", ProbeOutcome.Access(expected), a(Provider.GEMINI, 200, reply = reply))
        }
        // Text in the body cannot rescue a status that already refused, or the check would read a
        // successful envelope out of an error page. Every row below carries `text = true` for that reason.
        assertEquals(ProbeOutcome.KeyRejected(401), a(Provider.OPENAI, 401))
        assertEquals(ProbeOutcome.KeyRejected(400), a(Provider.GEMINI, 400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 403))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 404))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, 429))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.GEMINI, 429, "{\"error\":{\"message\":\"Resource exhausted\"}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.GEMINI, 429, "{\"error\":{\"message\":\"quota limit: 0\"}}"))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.CLAUDE, 429))
        assertEquals(ProbeOutcome.Access(ModelAccess.AVAILABLE), a(Provider.CLAUDE, 529))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, 503))
        // A non-key 400 is the provider answering "this model cannot serve this request", so it is a
        // refusal rather than "we could not tell": the omni models and antigravity all answer this way.
        assertEquals(ProbeOutcome.Access(ModelAccess.UNAVAILABLE), a(Provider.OPENAI, 400))
        assertEquals(ProbeOutcome.Access(ModelAccess.UNVERIFIED), a(Provider.OPENAI, null, null))
    }

    // ---- Which listed models can polish, and what they are called (moved from `ModelListRulesTest`).

    private fun ids(vararg names: String) = names.map { ListedModel(it, null) }

    @Test fun filterDropsWhatCannotPolishText() {
        val kept = ProviderAdapters.of(Provider.GEMINI).filterModelRows(
            ids(
                "gemini-3.6-flash", "gemini-2.5-flash-image", "gemini-embedding-001", "gemma-3-27b", "gemini-3-tts",
                "gemini-2.0-flash-exp-0827", "aqa", "gemini-3-flash-latest", "veo-lyria", "gemini-3.6-flash",
            ),
        ).map { it.id }
        assertEquals(listOf("gemini-3.6-flash"), kept)
    }

    @Test fun filterDropsVersionedDuplicatesAndLatestAliases() {
        val kept = ProviderAdapters.of(Provider.GEMINI).filterModelRows(ids("gemini-2.5-pro", "gemini-2.5-pro-001", "gemini-2.5-pro-002", "gemini-pro-latest")).map { it.id }
        assertEquals(listOf("gemini-2.5-pro"), kept)
    }

    @Test fun openAiKeepsChatFamiliesMinusOtherModalitiesAndChatCompletionsOnlyIds() {
        val kept = ProviderAdapters.of(Provider.OPENAI).filterModelRows(
            ids(
                "gpt-5.6-terra", "gpt-4o-realtime-preview", "gpt-4o-audio-preview", "gpt-4o-search-preview", "gpt-4o-transcribe",
                "o3", "o4-mini", "o1-mini", "o1-preview", "dall-e-3", "whisper-1", "text-embedding-3-small", "gpt-5.6-pro",
                "codex-mini-latest", "gpt-5-codex",
            ),
        ).map { it.id }
        assertEquals(listOf("gpt-5.6-terra", "o3", "o4-mini", "gpt-5.6-pro", "gpt-5-codex"), kept)
    }

    @Test fun filterDropsInvalidIdsAndSelfHosted() {
        val long = "g".repeat(ProviderPolishClient.MAX_MODEL_CHARS + 1)
        assertEquals(emptyList<ListedModel>(), ProviderAdapters.of(Provider.CLAUDE).filterModelRows(ids("", " ", long, "badid")))
        assertEquals(emptyList<ListedModel>(), ProviderAdapters.of(Provider.SELF_HOSTED_POLISH).filterModelRows(ids("llama3.2")))
    }

    @Test fun displayNameUsesTheProvidersOrTitleCasesAnOpenAiId() {
        assertEquals("Gemini 3.6 Flash", ProviderAdapters.of(Provider.GEMINI).displayName("gemini-3.6-flash", "Gemini 3.6 Flash"))
        assertEquals("gemini-3.6-flash", ProviderAdapters.of(Provider.GEMINI).displayName("gemini-3.6-flash", null))
        assertEquals("Gpt 4.1 Mini", ProviderAdapters.of(Provider.OPENAI).displayName("gpt-4.1-mini", null))
        assertEquals("claude-sonnet-5", ProviderAdapters.of(Provider.CLAUDE).displayName("claude-sonnet-5", ""))
    }

    // ---- Drift Guard: the routing, not the spelling.

    @Test fun everyProviderResolvesToItsNamedAdapter() {
        val expected = mapOf(
            Provider.OPENAI to OpenAiAdapter,
            Provider.GEMINI to GeminiAdapter,
            Provider.CLAUDE to ClaudeAdapter,
            Provider.SELF_HOSTED_POLISH to SelfHostedAdapter,
        )
        assertEquals(expected, Provider.entries.associateWith(ProviderAdapters::of))
        Provider.entries.forEach { assertEquals(it, ProviderAdapters.of(it).provider) }
    }
}
