# Issue #189 — Split the cloud provider client into transport, adapters, discovery and parser — 2026-09-20

GitHub issue: `#189`. Tier: MEDIUM. Status: APPROVED (coverage 8 gaps folded; grounded G1 to G5 folded; G6 PROCEED-AS-PLANNED, `docs/audits/2026-09-20-189-grounded-r6.md`).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/providers/**`, `app/src/test/java/com/envi/wispr/providers/**`,
three one-line repoints under `app/src/main/java/com/envi/wispr/ui/` and `polish/`).

**PAR rows closed:** `none`. This is an internal split; every parity row it touches (`PAR-064`, `PAR-065`,
`PAR-067`) is already closed by the behaviour it preserves.

**Hardware UAT:** Y. Cloud polish is a limb, not the heart, but the ticket's done-when is "no provider
identity behaviour lost" and only a live provider can prove that. Success: the founder opens Settings,
saves his OpenAI key (the key check accepts it, the model list appears with the newest models on top and
locked ones marked), dictates one sentence into Gmail with a cloud model selected, and the polished text
lands in the field; then the same take with Polish switched to the local engine still lands.

## Preface — User Rubric

User Rubric: N/A — a code split with no new behaviour. Every request body, header, URL, deadline, retry
and verdict is asserted unchanged by the existing 56 wire-level rows, which move but do not change. The
one behavioural edge, a nesting cap in the JSON parser, replaces a caught `StackOverflowError` with the
same `MALFORMED_RESPONSE` verdict.

## 1. Problem

`app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt` is 1,323 lines (`wc -l`, 2026-09-20)
and one class owns six responsibilities: the polish request with its #4 retry loop, the #61 key check,
the #84 model discovery with its probe pool, the `HttpURLConnection` transport with cancellation and
deadlines, four providers' request encoding and five response formats' decoding, and a hand-written JSON
parser. The audit (`docs/audits/2026-09-20-senior-audit.md` REF-06) grades this MEDIUM/High: a change
to one provider's body shape is reviewed in the same unit as the retry loop and the transport, and no
test can exercise one provider's encoding without a socket.

Evidence that the coupling costs review effort: #65 (an OpenAI parse fix) and #103/#104 (probe retries)
each landed as edits to this one file with 30-line explanatory comments, because the reader has to be
told which of the six concerns a line belongs to.

## 2. Goals & non-goals

### 2.1 Goals
1. Key checking, discovery and polishing live in two classes with no shared implementation: the polish
   client and a discovery client. Verifiable: `ProviderPolishClient` no longer implements
   `ProviderKeyChecker` or `ProviderModelDiscoverer`.
2. One adapter per provider owns that provider's URLs, auth header, polish body, probe bodies, model-list
   parsing and list-envelope check. Verifiable: `ProviderPolishClient.kt` and the discovery client contain
   no `Provider.OPENAI`, `Provider.GEMINI`, `Provider.CLAUDE` or `Provider.SELF_HOSTED_POLISH` token.
3. HTTP execution, cancellation hooks, the response size cap and the redirect refusal live in one
   transport type behind an `internal` interface. Verifiable: `HttpURLConnection` is imported by one
   production file.
4. JSON parsing lives in one type with an explicit nesting cap. Verifiable: `StackOverflowError` is caught
   nowhere in `providers/`.
5. Every provider adapter has request and response fixtures that run without a socket.
6. All 56 existing rows pass unchanged in assertion; they move between files only.

### 2.2 Non-goals
- Swapping the hand-written parser for `kotlinx-serialization-json`. Rejected in §3: it changes what
  counts as malformed and earns its own matrix.
- Changing the error-body MARKERS or their substring matching. The markers are the ones the macOS
  connectors match (`ProviderPolishClient.kt:70-71`); two of them are not values a structural parse
  could find (`blockReason` is a KEY in Gemini's `promptFeedback` (external), `credit balance` sits inside a
  free-text `message`), and a non-JSON error body must still classify. The classification MOVES into
  the adapters (G1 D1) with its `contains` semantics intact; making it structural is a behaviour change
  with its own matrix and is out of scope.
- Any change to timeouts, retry counts, probe caps, executor sizes, URLs, headers or body shapes.
- `ProviderConfigurationRepository`, `PolishService` and `AppViewModel` beyond the two default-argument
  repoints and `ProviderConfigurationRepository`'s one-line reuse of `capabilities().requiresEndpoint`.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

**Polish.** `polish/PolishService.kt:44` constructs `ProviderPolishClient()`; `:378-386` builds a
`ProviderPolishRequest` (key from `secrets.get`, endpoint and protocol from the policy) and calls
`polish(request, entry.cancellation)`, in the `:polish` process, on the service's worker. The client
validates, plans the request (`requestPlan`), sizes the body, then loops `attemptOnce` → `run` →
`REQUEST_EXECUTOR.submit { executeRequest }` → `readResponse` → `parseResponse` → `replyText` under one
deadline. `ProviderCancellation.cancel()` (called from `PolishService` on `cancel(requestId)`) fires
`onCancel` hooks that disconnect the live connection and release the retry latch.

**Key check.** `providers/ProviderConfigurationRepository.kt:33` defaults `keyCheck: ProviderKeyChecker =
ProviderPolishClient()`; `saveProvider` calls `check(provider, apiKey)` before writing a key. `check`
builds a GET `RequestPlan` with `authHeaders`, runs it through the same `run`, and classifies status and
envelope with `classifyKeyCheck` / `hasModelList`.

**Discovery.** `ui/AppViewModel.kt:153` defaults `discoverer: ProviderModelDiscoverer =
ProviderPolishClient()`; `ProviderConfigurationRepository.discoverModelsWithStoredKey(provider, discoverer)`
(`:97`) decrypts the key inside the lock and calls `discoverModels`. The client pages the list (Claude),
parses rows with `parseModelRows`, filters with `ModelListRules.filter`, probes on `PROBE_EXECUTOR` through
`probe` → `probeOnce` → `requestPlan(probe = style)` → `run` → `probeReply`, and merges with
`ModelListRules.probeOutcome` / `ModelListRules.sort`.

Command: `/usr/bin/grep -rn "ProviderPolishClient\b" app/src/main` (pasted in the Gate 0 comment on #189).

### 2. Find the existing authority before proposing one

- **Transport**: `/usr/bin/grep -rln "HttpURLConnection\|OkHttp\|HttpClient" app/src/main` returns only
  `providers/ProviderPolishClient.kt` and `models/ModelDeliveryWorker.kt` (the model download, WorkManager
  owned, a different concern: streaming a file to disk with a checksum). No shared HTTP owner exists;
  `new authority proposed` for the provider transport, scoped to `providers/`.
- **JSON**: `kotlinx-serialization-json` 1.8.1 is a dependency used by `vocabulary/VocabularyCore.kt` and
  `providers/ModelListCache.kt` for app-authored data. The provider client's parser is the only reader of
  provider-authored bodies. Kept (§3).
- **Per-provider tables that exist today**: `Provider.capabilities()` and `Provider.disclosure()`
  (`Provider.kt`, stay), `ProviderConfigurationValidator.validate` (stays), and inside the client,
  `ModelListRules` and `ProviderRetryPolicy`: `ProviderErrorSignal.classify`, `classifyKeyCheck`,
  `probeOutcome`, `claudePagination`, `filter`, `isRetryable`'s `provider != GEMINI` on 429,
  `displayName`, `requestPlan`, `authHeaders`, `parseModelRows`, `hasModelList`. After the change the
  adapters own every provider-specific wire shape, error marker, key verdict, probe verdict, pagination
  decision, model filter, display-name rule, retry decision and rate-limit reading; the full population
  of remaining identity sites and their dispositions is §2.5.6.
- **Cancellation**: `ProviderCancellation` is the only token; callers `PolishService` (`:62`, `:81`,
  `:114`, `:150` per the #69 plan) and the client. It moves to its own file, unchanged.

### 3. Read prior attempts and live direction

Gate 0 comment on #189. Binding decisions: one deadline from entry (#4); the key check reuses the polish
headers and never rejects on a transport failure (#61); one discovery deadline, three probes in flight,
one request worker always free (#84); the error body becomes a closed signal and goes no further (#77);
`replyText` is the one reading shared by polish and probe (#104 R1); newest-first probe order (#104 R1);
the retry can only improve a probe verdict (#103). The catalog `decision` table has no row about the
parser, the transport or adapters (queried 2026-09-20).

### 4. Boundaries a naive design would miss

- **Process**: `polish` runs in `:polish`; `check` and `discoverModels` run in the default process (the
  ViewModel and the repository). The split keeps both executors as process-wide singletons in their new
  owners; each process gets its own pool as today.
- **Thread**: `run` blocks the caller on `future.get`; the discovery client blocks on up to `MAX_PROBES`
  futures. Unchanged. `ProviderCancellation.onCancel` may fire on any thread; the transport's
  `ActiveConnection` stays `@Volatile`.
- **Cancellation versus timeout**: `Transport.Failed(CANCELLED)` versus `TIMEOUT` is decided in five
  places (`run`'s four catches and `failureKindAfter`). They move as one block into the transport.
- **Key exposure**: the key enters through `ProviderPolishRequest.apiKey` and `check`/`discoverModels`
  arguments, is spelled into headers by `authHeaders`, and never appears in a log or a `toString`. The
  adapters take the key as a `String` argument per call and hold no state, so nothing new retains it.
- **Endpoint overrides**: two maps keyed by `Provider` (`endpointOverrides` for polish and probes,
  `keyCheckOverrides` for the list) exist only so tests can point at a local server. They stay
  constructor arguments of the two clients and are passed to adapters per call.

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| No consumer outside `:app` | `app/build.gradle.kts` is the only module depending on `providers/`; `/usr/bin/grep -rln "com.envi.wispr.providers" llama-android accelerator-benchmark` is empty |
| No androidTest constructs the client | `/usr/bin/grep -rln "ProviderPolishClient\|ProviderKeyChecker\|ProviderModelDiscoverer" app/src/androidTest` returns only `ProviderConfigurationRepositoryTest.kt`, which passes a fake `ProviderKeyChecker` lambda |
| The constants consumers read | `/usr/bin/grep -rhno "ProviderPolishClient\.[A-Za-z_]*" app/src`: `MAX_MODEL_CHARS` (3 production sites, 1 test), `MAX_PROMPT_CHARS` (test), `MAX_PROBES` and `PROBE_RETRY_OUTPUT_TOKENS` (discovery tests), `DEFAULT_OVERALL_TIMEOUT_MS` (a comment in `PolishWatchdogBudgetTest`) |
| The 56 rows and their split | `/usr/bin/grep -c "@Test" ProviderPolishClientTest.kt` = 56; by fixture (coverage round): `client(...)` 17, no named fixture 4 (`:173,196,233,282`), `checker(...)` 5 plus one direct construction (`:418`), `discoverer(...)` 19 (one, `:818`, mixes direct construction with the fixture), `retrying(...)` 10: 31 polish-side, 25 discovery-side. `cancellationRegistrationRaceAlwaysInvokesCallback` (`:282`) reaches only `ProviderCancellation` and stays in the polish suite as a deliberate cross-class row |
| `StackOverflowError` is the only depth bound today | four `catch (_: StackOverflowError)` sites in the client, none elsewhere in `providers/` |
| Every response format has one decode owner | `replyText` and `endedOfItsOwnAccord` are the only `when (format)` sites; both exhaustive with no `else` |

No problem-only Codex consult: the who-calls-whom is three call sites, all read.


### 2.5.6 The identity-site population, enumerated from the producer

Three review rounds each found one more provider-identity site (G1 `classify`/`probeOutcome`, G2
`filter`/`displayName`, G3 `isRetryable`), so the population is enumerated here from the producing grep,
never from the findings (`workflow-process.md` RULE: enumerate-from-the-producer-not-from-the-findings).
Command: `/usr/bin/grep -rn "Provider\.\(OPENAI\|GEMINI\|CLAUDE\|SELF_HOSTED_POLISH\)\|when (provider)\|when (request\.provider)" app/src/main/java/com/envi/wispr`
(2026-09-20, 12 files; the `PolishProvider` privacy table is listed separately because this grep does not
match that enum). Disposition of every site:

| Site | What it decides | Disposition |
|---|---|---|
| `ProviderPolishClient.kt:82,274,324,343,370,576,633,635,650,776,861` | wire shape, markers, key verdict, list URL, paging, envelope, headers | move into the adapters (§3 item 5) |
| `ProviderModelDiscovery.kt:117-120,141-143,249-255` | model filter, display name, probe verdict | move into the adapters (§3 item 5) |
| `ProviderRetryPolicy.kt:27` | Gemini's 429 is not retried | adapter `isRetryable` (§3 item 5) |
| `polish/PolishFailure.kt:113` | Gemini's 429 reads as `RATE_OR_QUOTA` | the same fact as the key check's 429 row, a third copy. The adapter gets `rateLimitFailure` (proposed), a `PolishFailure` value, the ANSWER rather than a boolean to branch on, consumed by `KeyCheckVerdicts` and by `fromStatus`, whose 429 arm becomes `if (context is PolishContext.Cloud) ProviderAdapters.of(context.provider).rateLimitFailure else RATE_LIMITED` (a non-cloud 429 stays `RATE_LIMITED`, as today). `PolishFailureTest:72` keeps its assertion. One line in `polish/`, in scope because it is the third home of one fact |
| `ProviderConfigurationRepository.kt:173` | only self-hosted stores an endpoint | `provider.capabilities().requiresEndpoint`, the capability that already exists; one line in a file this change already touches |
| `polish/PolishContext.kt:51,67` | the Ollama protocol exists only for self-hosted | stays: it encodes the context for a reason label and the protocol is a self-hosted concept by construction |
| `ui/PolishLadder.kt:46,82-87,103,333-340` | cloud provider list, key portal URL, self-hosted tap, provider icon | stay: presentation identity, not wire shape |
| `ui/ModelNotes.kt:60-64,92-96` | curated model notes per provider | stay: the curated catalogue is a product table |
| `ui/PolishStatusChip.kt:78-95` | chip kind and copy per provider | stay: presentation |
| `ui/PolishScreen.kt:269,277,760-764` | key placeholder, self-hosted clear | stay: presentation |
| `ui/AppViewModel.kt:67,719` | default provider `OPENAI` | stay: a default value, not a branch |
| `privacy/PrivacyDisclosure.kt:29-32` | data-safety disclosure | stays by rule (`architecture-rules.md` RULE: gate-on-capability-not-identity-literal) |
| `Provider.kt:20-24,37-41` | capabilities and disclosure summary | stays: the enum's own tables |

After the change, inside `providers/` the only identity branches are `ProviderAdapters.of` and
`Provider.kt`'s two tables; outside it, the presentation tables above, `PolishContext`, and the privacy
disclosure. The shape test (§11.2) pins the two client files and the transport, not the whole app.

## 3. Design

Eleven new production files split the existing client, leaving twelve provider files in this change, all
in `com.envi.wispr.providers`, all `internal` except the types that already cross into `polish/` and `ui/`:

1. **`ProviderPolishRequest.kt`** (proposed): the public contract, moved verbatim. `SelfHostedProtocol`,
   `ProviderPolishRequest`, `ProviderFailureKind`, `ProviderPolishResult`, `ProviderErrorSignal`.
2. **`ProviderCancellation.kt`** (proposed): `ProviderCancellation`, moved verbatim.
3. **`HttpProviderTransport.kt`** (proposed): `ProviderTransport` (proposed), an internal interface with one
   method `run(plan, cancellation, overallTimeoutMs, connectTimeoutMs, readTimeoutMs): Transport`;
   `HttpProviderTransport` (proposed), an internal class taking `logWarn`, owning `executeRequest`, `readResponse`,
   `ActiveConnection`, `failureKindAfter`, `ensureActive`, the two exceptions, `REQUEST_EXECUTOR` and
   the size caps `MAX_REQUEST_BYTES`, `MAX_RESPONSE_BYTES`. `RequestPlan` (proposed, internal data class
   with the redacting `toString`), `Transport` (proposed, internal sealed) and `ResponseRead` move here.
   `remainingMillis` and `minTimeout` move only into `ProviderDeadlines` (proposed), an internal object in
   this file, because both clients need them. `cloudOrOverride` and `encodePath` become shared adapter
   support in `ProviderAdapter.kt`; `ClaudeAdapter` owns `parseIso8601`; `SelfHostedAdapter` owns
   `resolveSelfHostedEndpoint`; the discovery client owns `ProbeAttempt`. Both clients and the transport
   log under the one shared `PROVIDER_LOG_TAG` (item 7).
   The moved KDoc for `requestPlan`, `replyText` and `ResponseFormat.NONE` is rewritten to describe the
   cross-type call path rather than link private members in another file.
4. **`ProviderReplyFormat.kt`** (proposed): `ProviderReplyFormat` (proposed), an internal enum replacing
   `ResponseFormat`, with `replyText(root)` and `endedOfItsOwnAccord(root)` as members. The `NONE` member
   stays for the list GET. The `Any?.valueAt/stringAt/firstTextAt/firstMessageTextAt` helpers move here,
   private.
5. **`ProviderAdapter.kt`** (proposed): `ProviderAdapter` (proposed), an internal interface:
   Adapters expose DECISIONS, never flags: the clients never ask whether an adapter is Gemini,
   rate-limit-special or paged (G1 D1, D4).
   - `val provider: Provider`
   - `fun authHeaders(apiKey: String): Map<String, String>`
   - `polishPlan` (proposed): `fun polishPlan(request: ProviderPolishRequest, endpointOverride: String?): RequestPlan?`
   - `probePlan` (proposed): `fun probePlan(model: String, apiKey: String, style: ProbeStyle, endpointOverride: String?): RequestPlan?`
   - `firstListUrl` (proposed): `fun firstListUrl(listOverride: String?): URI?` (null: no list, self-hosted)
   - `nextListPage` (proposed): `fun nextListPage(page: ModelPage, seenCursors: MutableSet<String>, listOverride: String?): ListAdvance`
     where `ListAdvance` (proposed) is `Done | Malformed | Next(url)`; only `ClaudeAdapter` ever answers
     `Next`, using the moved `claudePagination` rule (`ModelListRules.claudePagination` and its
     `Pagination` type move into `ClaudeAdapter`; `ModelListRulesTest.claudePaginationStopsContinuesOrCallsACursorMalformed`
     repoints)
   - `fun parseModelRows(body: String): ModelPage?`
   - `fun hasModelList(body: String): Boolean`
   - `errorSignal` (proposed): `fun errorSignal(status: Int, body: String): ProviderErrorSignal?`: the moved per-provider marker
     table from `ProviderErrorSignal.classify`, `contains` semantics unchanged (§2.2).
     `ProviderErrorSignal.classify` is removed; `ProviderErrorSignal` stays public for `PolishReason`.
   - `keyCheckVerdict` (proposed): `fun keyCheckVerdict(status: Int, body: String): ProviderKeyCheck`: the moved `classifyKeyCheck`
     table. The provider-independent skeleton (200 with envelope, 201-299, 401, 429, 5xx, other 4xx,
     else) lives once in `KeyCheckVerdicts` (proposed), an internal object in `ProviderAdapter.kt`, and
     each adapter supplies its two decisions as arguments: what a 403 means (`Rejected` for Gemini,
     `Denied` otherwise) and which failure a 429 reports (`RATE_OR_QUOTA` for Gemini, `RATE_LIMITED`
     otherwise). The table is written ONCE, not four times.
   - `fun probeOutcome(status: Int?, body: String?, reply: ModelListRules.ProbeReply): ProbeOutcome`: the
     moved `ModelListRules.probeOutcome`. The same shape: the provider-independent skeleton (null
     status, 401, 400 key rejected via `errorSignal`, 200 by reply, 403/404, other 400, else) lives once
     in `ProbeVerdicts` (proposed) beside `KeyCheckVerdicts`, and each adapter supplies its two decisions:
     the access a 429 body means and the access a 5xx means.
     `ModelListRulesTest.probeOutcomeFollowsTheMacRulesPerProvider` repoints to the adapters.
   - `filterModelRows` (proposed): `fun filterModelRows(rows: List<ListedModel>): List<ListedModel>`: `ModelListRules.filter` loses its
     `provider` parameter and gains a candidate predicate, `filter(rows, candidate: (String) -> Boolean)`,
     keeping the shared blank, length, control-character, duplicate, excluded-pattern, versioned-suffix
     and alias checks once; each adapter supplies its predicate (`OpenAiAdapter` owns
     `isOpenAiCandidate`, `openAiModalitySkips`, `openAiChatCompletionsOnly`; Gemini and Claude accept
     every surviving row; self-hosted accepts none).
   - `fun displayName(id: String, given: String?): String`: interface default `given` when non-blank else
     `id`; `OpenAiAdapter` overrides the missing-name case with the existing title-casing.
     `ModelListRules.displayName` is removed. Five `ModelListRulesTest` rows (`filterDropsWhatCannotPolishText`,
     `filterDropsVersionedDuplicatesAndLatestAliases`, `openAiKeepsChatFamiliesMinusOtherModalitiesAndChatCompletionsOnlyIds`,
     `filterDropsInvalidIdsAndSelfHosted`, `displayNameUsesTheProvidersOrTitleCasesAnOpenAiId`) repoint to
     the adapters with every assertion kept.
   plus `ProbeStyle` (moved), `ModelPage` (moved), the shared probe constants `PROBE_TEXT`,
   `PROBE_OUTPUT_TOKENS`, `PROBE_RETRY_OUTPUT_TOKENS`, and `ProviderAdapters.of(provider)` (proposed), an
   exhaustive `when` with no `else`. Four internal objects in their own files: `OpenAiAdapter` (proposed),
   `GeminiAdapter` (proposed), `ClaudeAdapter` (proposed), `SelfHostedAdapter` (proposed), each holding its URLs and
   per-provider constants (`ANTHROPIC_VERSION`, `CLAUDE_MAX_OUTPUT_TOKENS`, `OPENAI_PROBE_OUTPUT_TOKENS`).
   The shared helpers `jsonString`, `cloudOrOverride` and `encodePath` are `internal` top-level functions in
   `ProviderAdapter.kt` (four files use them, so private is impossible; G1 D8). `ModelListRules` keeps
   only the provider-independent `filter(rows, candidate)`, `isRecommended`, `accessRank`, `sort`,
   `mergeAccess` and `ProbeReply`.
   - `fun isRetryable(failure: ProviderPolishResult.Failure): Boolean` (G3 F1): `ProviderRetryPolicy`
     keeps the provider-independent retry skeleton and loses its `Provider` parameter for a
     `retryRateLimit` (proposed) boolean decision; each adapter calls it with its 429 answer (Gemini `false`, the
     other three `true`). The polish client calls `adapter.isRetryable(result)`;
     `ProviderRetryPolicyTest` repoints its calls to the adapters with every assertion kept.
   - `val rateLimitFailure: PolishFailure`: what this provider's 429 means to the user (`RATE_OR_QUOTA`
     for Gemini, `RATE_LIMITED` otherwise), the one owner of a fact that has three copies today
     (`classifyKeyCheck:635`, `PolishFailure.kt:113`, and by implication `ProviderRetryPolicy.kt:27`);
     read by `KeyCheckVerdicts` and by `PolishFailure.fromStatus`. It is the answer, not a flag to
     branch on. §2.5.6 enumerates every remaining identity site in the app.
   Adapters are stateless objects: every key arrives as a call argument and is held by nothing
   (`keystore-security.md` RULE: plaintext-never-leaves-the-store).
6. **`ProviderJson.kt`** (proposed): `ProviderJson` (proposed), an internal object with `parseOrNull` (proposed) `(body):
   Any?` returning null on any malformed input, wrapping the moved `JsonParser` with `MAX_DEPTH = 64`
   (proposed): the parser counts open containers (root is depth 1) and throws `IllegalArgumentException`
   when a 65th would open, so the four `StackOverflowError` catches go. 64 levels covers every provider
   envelope: the deepest read is Gemini's six containers (`candidates[0].content.parts[0].text`), then
   OpenAI Responses five, OpenAI Chat four, Claude three, Ollama two. `valueAt` is iterative and the text
   helpers add no recursion, so the parser is the only recursion. The check arms only on hostile or
   malformed input; it replaces recovery from a JVM `Error` with a deterministic refusal, which is the
   reason for the change (G1 D3).
7. **`ProviderModelDiscoveryClient.kt`** (proposed): `ProviderModelDiscoveryClient` (proposed)
   `: ProviderKeyChecker, ProviderModelDiscoverer`, constructor `(connectTimeoutMs, readTimeoutMs,
   overallTimeoutMs, endpointOverrides, keyCheckOverrides, logInfo, logWarn, discoveryTimeoutMs,
   probeTimeoutMs, transport: ProviderTransport = HttpProviderTransport(logWarn))`. Owns `check`,
   `discoverModels`, `probe`, `probeOnce`, `probeReply`, `unverifiedFailure`,
   `keyCheckStatus`, `PROBE_EXECUTOR`, and the discovery constants (`KEY_CHECK_TIMEOUT_MS`,
   `DISCOVERY_TIMEOUT_MS`, `PROBE_TIMEOUT_MS`, `MAX_LIST_PAGES`, `MAX_PROBES`). `internal`: a public
   constructor naming the internal `ProviderTransport` is a compile error (`EXPOSED_PARAMETER_TYPE` (external)),
   and every caller is inside `:app`. `ui/AppViewModel` and the repository may name an internal class in
   a default argument whose declared type is the public seam. Logs under the one shared tag
   `PROVIDER_LOG_TAG` (proposed), value `ProviderPolishClient`, declared once in `HttpProviderTransport.kt`,
   so every `Key check:`, `Discovery:` and `Cloud request failed:` line is byte-identical to today.
8. **`ProviderPolishClient.kt`** (kept, becomes `internal` for the same reason): constructor `(connectTimeoutMs, readTimeoutMs,
   overallTimeoutMs, endpointOverrides, logInfo, logWarn, retryDelaysMs, maxRetries, transport =
   HttpProviderTransport(logWarn))`; `polish`, `attemptOnce`, `delayUnlessCancelled`, `parseResponse`
   (now `format.replyText(ProviderJson.parseOrNull(body))` plus the `isTranscriptOnly` judgement); the
   companion keeps `MAX_MODEL_CHARS`, `MAX_PROMPT_CHARS`, the default and max timeouts, `MAX_RETRIES`,
   `RETRY_DELAYS_MS`. The class no longer implements the two discovery seams and drops
   `keyCheckOverrides`, `discoveryTimeoutMs`, `probeTimeoutMs`. Target size under 200 lines.

**Exception and clamp boundaries are preserved exactly (G1 D2).** `polish` wraps adapter lookup and
`polishPlan` in its existing `catch (RuntimeException)` and maps null or failure to
`INVALID_CONFIGURATION`; `SelfHostedAdapter` alone keeps the existing `catch (Exception)` around endpoint
URI resolution; `probePlan`, `firstListUrl` and `nextListPage` add no catch, so a URI failure there
propagates exactly as today. Body sizing and every timeout clamp stay in the clients at their current
call-order positions (polish `:172,204-220`, key check `:289-291`, discovery `:347-349`, probe
`:502-538`), never inside an adapter or a transport default. The source comment at `:1101` is rewritten
to `Three probes in flight, leaving one request worker free for non-probe provider work in this process.`
because polish runs in `:polish` and discovery in the default process (G1 D5).

**Repoints**: `ui/AppViewModel.kt:153` and `ProviderConfigurationRepository.kt:33` default to
`ProviderModelDiscoveryClient()`; `ProviderKeyCheck.kt:31`'s KDoc names the new implementation;
`Provider.kt:3`'s comment names both clients.

**Alternatives rejected.**
- *Keep one class that delegates `check`/`discoverModels` to the discovery client.* A facade keeps the
  three concerns in one review unit by name and fails goal 1 as the issue words it. Rejected.
- *Adapters as a sealed class hierarchy with the `Provider` enum member.* An `object` per provider with a
  `ProviderAdapters.of(provider)` exhaustive `when` gives the same compile-time completeness without a
  second enum-shaped type.
- *Swap to `kotlinx-serialization-json`.* `Json.parseToJsonElement` differs from the current parser on
  at least: leading `+`, `NaN`/`Infinity` literals (lenient mode), unescaped control characters, and
  duplicate keys; each would need a row in the malformed matrix and a decision on which side is right.
  None of that is this ticket. The parser is 130 lines with its own rows; isolating it behind
  `ProviderJson` satisfies the issue's "or isolate it behind one parser type".
- *Client-side capability flags (`keyRejectedOn403` (removed), `listIsPaged` (removed), and a client-read
  `rateLimitFailure`).* Facts exposed so a client can branch are identity under another name; the flags are
  removed (G1 D4). The retained adapter `rateLimitFailure` is different: it is the final `PolishFailure`
  answer consumed directly by the shared verdict skeleton and the user-copy mapping, with no caller branch.
- *A transport fake for the discovery tests.* The 25 wire-level rows already stage every transport
  outcome through the scripted server; a fake would be a second answer to the same question. The
  `ProviderTransport` interface exists for the adapter and discovery tests that need to assert a plan
  without opening a socket, and the production default is the HTTP implementation.

### 3.5 Build chunks

Each chunk ends with `./gradlew :app:testDebugUnitTest --tests 'com.envi.wispr.providers.*' --tests
'com.envi.wispr.polish.PolishFailureTest'` and `:app:assembleDebug` green; every intermediate state still
serves polish, the key check and discovery.

1. **Contracts, parser, formats.** Move the public types and `ProviderCancellation` to their files; add
   `ProviderJson` and `ProviderReplyFormat`; rewire the still-monolithic client to them and delete the
   private `JsonParser`, `ResponseFormat`, `replyText`, `endedOfItsOwnAccord` and the four
   `StackOverflowError` catches. Constructors and consumers unchanged; all 56 rows runnable.
2. **Transport and adapters.** First make the client `internal` and add all internal shared adapter
   helpers, the verdict skeletons (`KeyCheckVerdicts`, `ProbeVerdicts`) and the retry skeleton. Add
   `RequestPlan`, `ProviderTransport`, `HttpProviderTransport`, `ProviderAdapter`, all four adapters and
   `ProviderDeadlines`. Bind one adapter at the start of polish, key check and discovery. Before deleting
   any old member, rewire `requestPlan`, `authHeaders`, list URL and pagination, `parseModelRows`,
   `hasModelList`, `classifyKeyCheck`, `ProviderErrorSignal.classify`, `ModelListRules.probeOutcome`,
   `ModelListRules.filter` (`:380`), `ModelListRules.displayName` (`:433`) and
   `ProviderRetryPolicy.isRetryable` (`:218`) to their adapter methods, preserving the exception and
   clamp boundaries in §3. Repoint the body-marker row, all seven affected `ModelListRulesTest` rows and
   `ProviderRetryPolicyTest` in the same chunk. Then delete or change the old symbols (`run`,
   `executeRequest`, `readResponse`, `ActiveConnection`, `requestPlan`, `authHeaders`, `parseModelRows`,
   `hasModelList`, `resolveSelfHostedEndpoint`, `cloudOrOverride`, `encodePath`, `jsonString`,
   `classifyKeyCheck` from the client; `probeOutcome`, `claudePagination`, `Pagination`, `displayName`
   and the `provider` parameter of `filter` from `ModelListRules`; the `Provider` parameter of
   `ProviderRetryPolicy.isRetryable`). In the same chunk, replace `PolishFailure.fromStatus`'s Gemini
   identity check with the guarded adapter `rateLimitFailure` expression from §2.5.6, and replace
   `ProviderConfigurationRepository:173` with `provider.capabilities().requiresEndpoint`. The completed
   chunk has no reference to a removed signature and no
   mixed old/new provider path, and still serves polish, key check and discovery.
3. **Discovery client.** Add `ProviderModelDiscoveryClient`; move `check`, `discoverModels`, `probe`,
   `probeOnce`, `probeReply`, `unverifiedFailure`, `keyCheckStatus`, `ProbeAttempt`,
   `PROBE_EXECUTOR` and the discovery constants; repoint `AppViewModel` and
   `ProviderConfigurationRepository`; remove the two seams and the three constructor arguments from
   `ProviderPolishClient` in the same chunk. Move the 25 discovery rows and the server helpers so the
   suite compiles.
4. **Fixtures and guards.** Add `ProviderAdapterTest`, `ProviderJsonTest`, `ProviderClientShapeTest`,
   run the revert receipts, update the two knowledge owners and `docs/play-data-safety-answers.md`.
   No production behaviour changes in this chunk.

## 3b. Ownership justification

The adapters live in `providers/` because the wire shape is the provider's identity and nothing else in
the app spells a URL or a header; the alternative was a `net/` package, but the only HTTP client outside
this is the model download, whose shape (streaming to disk, checksum) shares nothing with a JSON round
trip. The discovery client lives beside the polish client because both are consumers of the same
adapters and transport; the alternative, keeping it in the polish client, is the defect.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `ProviderPolishClient` | drops `: ProviderKeyChecker, ProviderModelDiscoverer`; drops `keyCheckOverrides`, `discoveryTimeoutMs`, `probeTimeoutMs`; gains `transport` (default HTTP) | `PolishService` unchanged; a caller that used it as a checker or discoverer must construct `ProviderModelDiscoveryClient` (two default arguments, repointed here) |
| `ProviderModelDiscoveryClient` (proposed) | new; implements both discovery seams | the production checker and discoverer |
| `ProviderPolishRequest`, `ProviderPolishResult`, `ProviderFailureKind`, `SelfHostedProtocol`, `ProviderCancellation` | file moves, same package, same members | none; imports are by name |
| `ProviderErrorSignal` | file move; companion classifier removed; enum members unchanged | `PolishReason` unchanged; `ProviderRetryPolicy` reads only `signal != null`, unchanged |
| `ProviderJson` (proposed) | new; a body nested deeper than 64 levels is malformed | polish: `MALFORMED_RESPONSE` as before; key check: `Unverified(BAD_REQUEST)` as before; discovery: malformed page as before. Before, the same input reached the same verdict through a caught `StackOverflowError` |
| `ProviderAdapter`, `ProviderTransport`, `RequestPlan`, `Transport`, `ProviderReplyFormat` | new, `internal` | reachable from `app/src/test` only |
| `ProviderPolishClient`, `ProviderModelDiscoveryClient` | `internal` | every caller is in `:app`; `PolishService`'s private field and the two default arguments compile unchanged |
| `ProviderErrorSignal.classify` | removed; `ProviderAdapters.of(provider).errorSignal(status, body)` | `ModelListRules.probeOutcome` (moves) and one test row (repoints) |
| `ModelListRules.probeOutcome`, `ModelListRules.claudePagination`, `ModelListRules.Pagination`, `ModelListRules.displayName`, `filter`'s `provider` parameter | removed; adapter `probeOutcome`, `ClaudeAdapter` pagination, adapter `displayName`, adapter `filterModelRows` | the discovery client and seven `ModelListRulesTest` rows (repoint) |
| `ProviderRetryPolicy.isRetryable(failure, provider)` | becomes `isRetryable(failure, retryRateLimit)`, called by the adapters | the polish client calls `adapter.isRetryable(result)`; `ProviderRetryPolicyTest` repoints |

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every `when (provider)` in the client (identity sites the adapters absorb) | eleven sites (`:82,274,324,343,370,576,633,635,650,776,861`): `check` list URL, `discoverModels` list URL, `discoverModels` Claude paging (`provider == CLAUDE` twice), `classifyKeyCheck` (`GEMINI` twice), `requestPlan`, `authHeaders`, `parseModelRows`, `hasModelList` are the ten that move into adapters as decisions; `ProviderErrorSignal.classify` (`:82`) is the eleventh and moves too, as `errorSignal` per adapter, with `ModelListRules.probeOutcome` (`ProviderModelDiscovery.kt:228,249-255`) and `claudePagination` (`:170`) following it. After the change the only `when (provider)` inside `providers/` outside `Provider.kt` is `ProviderAdapters.of` (the rest of the app: §2.5.6), and every provider-specific model filter and display-name rule is adapter behaviour (`ModelListRules.filter` `:107-131` and `displayName` `:139-146` branch on identity today and lose that branch). `unverifiedFailure` and `keyCheckStatus` are over `ProviderFailureKind`/`ProviderKeyCheck`, not identity; they stay in the discovery client |
| Every `when (format)` | `replyText`, `endedOfItsOwnAccord`: both become members of `ProviderReplyFormat`, exhaustive, no `else` |
| Every catch site deciding CANCELLED versus TIMEOUT | `run` (4), `executeRequest` (5: `ProviderCancelledException`, `ProviderTimedOutException`, `SocketTimeoutException`, `IOException`, `RuntimeException`), `delayUnlessCancelled` (1): the first nine move into the transport as one block together with `executeRequest`'s expired-budget branch, `ensureActive` and `failureKindAfter`; the tenth stays in the polish client, which also keeps its own five cancellation checks (entry, pre-attempt, post-attempt, pre-delay, delay result) |
| Every executor | `REQUEST_EXECUTOR` (4 threads) → transport companion; `PROBE_EXECUTOR` (3) → discovery client companion. Both remain JVM singletons per process; the "one worker free for polish" invariant needs both pools in the same process to matter and discovery runs in the default process, polish in `:polish`, so it was and is a same-process guarantee only when both run in one process (tests). Unchanged |
| Every `onCancel` registration | `run` (disconnect + cancel future), `delayUnlessCancelled` (release the latch): both close their registration in `finally`. Unchanged |
| Every `toString` that redacts | `ProviderPolishRequest`, `ProviderPolishResult.Success`, `RequestPlan`: all move verbatim; `requestAndResultToStringsDoNotContainSecretsOrTranscript` still covers the first two; a new adapter row covers `RequestPlan` |
| Every `StackOverflowError` catch | `parseModelRows`, `hasModelList`, `parseResponse`, `probeReply`: all four replaced by `ProviderJson.parseOrNull` |
| Every deadline computation | `polish` (one deadline; remaining per attempt and before each delay), `check` (clamped to `KEY_CHECK_TIMEOUT_MS`), `discoverModels` (one operation deadline; remainder for the list call, remainder handed to each probe, remainder for each `future.get`), `probe` (one logical-probe budget clamped to `probeTimeoutMs`, its own deadline), `probeOnce` (request budget and read timeout both derived from the probe's remaining deadline), `run` (per attempt): each keeps its owner; `remainingMillis` becomes shared in `ProviderDeadlines` |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| polish client no longer a checker | `ProviderConfigurationRepository:33` default | constructs the polish client | constructs `ProviderModelDiscoveryClient()` | one line | `ProviderModelDiscoveryClientTest` key-check rows; compile |
| polish client no longer a discoverer | `AppViewModel:153` default | same | same | one line | discovery rows; compile |
| `polish` unchanged | `PolishService:44,386` | | | none | 30 polish rows plus the body-marker row repointed whole to `ProviderAdapterTest` |
| `MAX_MODEL_CHARS` | `PolishLadder:201`, `ModelListPresentation:129,202`, `ProviderModelDiscovery:111`, `ModelListRulesTest:45` | read from the polish client companion | same | none | compile |
| `MAX_PROBES`, `PROBE_RETRY_OUTPUT_TOKENS` | discovery test rows | read from the polish client | read from `ProviderModelDiscoveryClient` / `ProviderAdapter` | test repoint | compile |
| `DEFAULT_OVERALL_TIMEOUT_MS` | `PolishWatchdogBudgetTest:29` comment | names the client | still true | none | |
| `MAX_PROMPT_CHARS` | `ProviderPolishClientTest:1253` | read from the polish client | same | none | compile |
| `MAX_PROBES` in prose | `ModelListPresentation:159,163` comment | names the polish client as the owner of probe order | names `ProviderModelDiscoveryClient` | comment repoint | `check-cited-symbols` |
| `ProviderKeyChecker` fake | `ProviderConfigurationRepositoryTest` (androidTest) | passes a lambda | same | none | |

## 7. Failure-mode × caller table

No new failure mode. The one changed path:

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| a provider body nested past 64 levels | `ProviderJson.parseOrNull` → null | `parseResponse` | the deterministic fallback with the "couldn't read the reply" reason (`PolishReason.from(MALFORMED_RESPONSE)`), as today | History row as today | not retryable (`ProviderRetryPolicy`), as today |
| same | same | the adapter's `keyCheckVerdict` (200 with a bad envelope) | "couldn't verify" copy, as today | no key written | user retries |
| same | same | `parseModelRows` | first page: refused as `BAD_REQUEST`; later page: rows so far, as today | cache as today | |

## 8. Caller-visible signals audit

| Signal | Carried by | Meaning | Change |
|---|---|---|---|
| `Failure.statusCode == null` | `ProviderPolishResult.Failure` | the provider never answered (transport) | none |
| `Failure.signal != null` | same | the body was classified; retry stops | none |
| `ProviderKeyCheck.Unverified.status == null` | key check | transport failure, not a verdict | none |
| `ModelAccess.UNVERIFIED` on a row | discovery | never probed, cut off, or inconclusive | none |
| `ProviderDiscovery.Listed(fetchedAt)` | discovery | cache freshness | none |
| `RequestPlan.body == null` | transport | GET with no Content-Type | now an `internal` type; same meaning |

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| malformed reply | the text handed to `polish` (the cleaned transcript) | `PolishPipeline.run` | the last successful text, `architecture-rules.md` FACT: heart-and-limbs | `Failure(MALFORMED_RESPONSE)` returned, `PolishService` writes `null` and the pipeline keeps `cleaned` | n/a, already the floor | `PolishService:386` |

## 10. File-by-file changes

Production, `app/src/main/java/com/envi/wispr/providers/`:
- `ProviderPolishClient.kt`: shrinks to the polish client (§3 item 8).
- `ProviderPolishRequest.kt`, `ProviderCancellation.kt`: verbatim moves.
- `HttpProviderTransport.kt`, `ProviderReplyFormat.kt`, `ProviderJson.kt`, `ProviderAdapter.kt`,
  `OpenAiAdapter.kt`, `GeminiAdapter.kt`, `ClaudeAdapter.kt`, `SelfHostedAdapter.kt`,
  `ProviderModelDiscoveryClient.kt`: new, contents named in §3.
- `ProviderModelDiscovery.kt`: `probeOutcome`, `claudePagination` and `Pagination` leave `ModelListRules`.
- `ProviderKeyCheck.kt`, `Provider.kt`: KDoc repoint only.
- `ProviderRetryPolicy.kt`: loses the `Provider` parameter for `retryRateLimit`.
- `polish/PolishFailure.kt:113`: reads the adapter's `rateLimitFailure`.
- `ProviderConfigurationRepository.kt:173`: reads `capabilities().requiresEndpoint`.
- `ui/AppViewModel.kt`, `ProviderConfigurationRepository.kt`: default-argument repoint.

Tests, `app/src/test/java/com/envi/wispr/providers/`:
- `ProviderTestServer.kt` (proposed): `withServer`, `ScriptedServer`, `TestRequest`, `okBody`,
  `jsonQuoted`, `openAiList`, `probedModel`, moved out of the suite as `internal` test helpers.
- `ProviderPolishClientTest.kt`: keeps 30 rows (the 31 polish-side rows minus the body-marker row, which
  moves to the adapter suite).
- `ProviderModelDiscoveryClientTest` (proposed), `ProviderModelDiscoveryClientTest.kt`: the 25 key-check and discovery rows, moved.
- `ProviderAdapterTest` (proposed), `ProviderAdapterTest.kt`: socket-free fixtures per adapter (§11).
- `ProviderAdapterTest.kt` also receives `theBodyMarkersAreTheMacOsOnesPerProvider` (as `errorSignalPerAdapter` (proposed),
  every assertion literal, the call repointed to `ProviderAdapters.of(provider).errorSignal`), and from
  `ModelListRulesTest` the probe-verdict row (every assertion at `:125-159` kept: the full `ProbeReply.entries`
  sweep, null status, 401, 403, 404, ordinary 400, 429 and 5xx per provider), the Claude pagination row (all six
  cases, `Pagination.Continue` becoming `ListAdvance.Next(url)`), and the five filter and display-name rows.
- `ProviderJsonTest` (proposed), `ProviderJsonTest.kt`: the parser's malformed classes and the depth cap.
- `ProviderClientShapeTest` (proposed), `ProviderClientShapeTest.kt`: the drift guard for goals 2, 3 and 4.

Knowledge: `.claude/knowledge/architecture.md` source map row for `providers/`;
`.claude/knowledge/polish-engines.md` FACT: cloud-polish-providers names the adapters as the owner of
the wire shape; `docs/play-data-safety-answers.md:17` names the adapters beside the client as the files
that send text off device; `docs/audits/2026-09-20-senior-audit.md` is not edited (it is a dated report).

## 11. Testing

1. **Class of every new test.** `ProviderAdapterTest`: Product Outcome for the encode rows (when a body
   shape is wrong the user's polish silently returns raw words) and for the decode rows (a wrong reading
   hides a working model or offers a broken one); `ProviderJsonTest`: Product Outcome (a malformed body
   that is accepted inserts garbage; one that crashes the worker loses the polish);
   `ProviderClientShapeTest`: Drift Guard, counted as such.
2. **Revert that turns each red.** Named per row in §11.2. Each revert is performed once during the
   build, observed red, and restored; the receipts go in `docs/audits/2026-09-20-189-revert-receipts.txt`
   (proposed).
3. **Deliberately not tested.** The `HttpURLConnection` transport against a real TLS endpoint (the local
   server is plain HTTP; TLS is the platform's); a `ProviderTransport` fake for the discovery rows
   (§3, rejected); thread-pool sizing (a constant, pinned by `discoveryRunsAtMostThreeProbesAtOnce`).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (cloud polish, key check, model list).
- **Recipe:** `device-testing.md` FACT: the-cloud-polish-pass, if present; otherwise: Settings → Polish →
  OpenAI → paste key → Save (expect the model list, newest first, locked rows marked) → pick a model →
  dictate "this is the cloud polish check after the split" into Gmail compose → expect the polished
  sentence in the field, the History card naming OpenAI and the model → switch Polish to the local
  engine → dictate again → expect text as before.
- **Expected observation:** the editor's own text for both takes; the History card's engine label; no
  logcat line containing the key or the sentence (`adb logcat -d | grep -c "<key prefix>"` = 0).
- **Phone state to restore afterwards:** the provider and model the founder had selected before.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `openAiPolishPlanCarriesInstructionsInputAndStoreFalse` (proposed) in `ProviderAdapterTest` | Product Outcome | URL, `Authorization: Bearer`, body keys `model`, `instructions`, `input`, `store:false`, format `OPENAI_RESPONSES` | drop `"store":false` from `OpenAiAdapter.polishPlan` |
| `…geminiPolishPlanNamesTheModelInThePathAndUsesTheGoogleHeader` | Product Outcome | `/models/<model>:generateContent`, `x-goog-api-key`, `systemInstruction` + `contents` | encode the model in the body instead of the path |
| `…claudePolishPlanSendsVersionSystemAndMaxTokens` | Product Outcome | `x-api-key`, `anthropic-version`, `max_tokens` 8192, `system` | change `CLAUDE_MAX_OUTPUT_TOKENS` |
| `…selfHostedPlansDifferOnlyInPathAndFormat` | Product Outcome | `/v1/chat/completions` vs `/api/chat`, formats `OPENAI_CHAT` vs `OLLAMA`, same body, bearer only with a key, null on an invalid endpoint | swap the two paths |
| `…probePlansPerStyle` | Product Outcome | OpenAI DEFAULT 16 tokens, NO_REASONING adds `reasoning.effort` and 64; Gemini adds `thinkingBudget:0`; Claude NO_REASONING is null; self-hosted always null | return a plan for Claude NO_REASONING |
| `…listUrlsAndPaging` | Product Outcome | three first-page URLs; Claude's `nextListPage` answers `Next` with `limit=1000` and `after_id`, `Done` on `has_more:false`, `Malformed` on a missing, empty or repeated cursor; the other three answer `Done`; self-hosted first URL null | drop the `after_id` |
| `…errorSignalPerAdapter` | Product Outcome | the moved marker matrix (OpenAI quota, context length, content filter; Gemini key, tokens, prohibited content; Claude credit balance, too long; self-hosted null; wrong status null), every body a literal | remove one marker |
| `…keyCheckVerdictPerAdapter` | Product Outcome | the status table per adapter, 403 and 429 differing for Gemini, every verdict a literal | swap Gemini's 403 to `Denied` |
| `…probeOutcomePerAdapter` | Product Outcome | `probeOutcomeFollowsTheMacRulesPerProvider` moved whole: the `ProbeReply.entries` sweep at 200, null status, 401, 400 key rejected via the adapter's own marker, ordinary 400, 403, 404, 429 with and without `limit: 0`, 5xx per provider | make Claude's 5xx `UNVERIFIED` |
| `…filterAndDisplayNamePerAdapter` | Product Outcome | the five moved `ModelListRulesTest` rows, assertions unchanged | drop `openAiChatCompletionsOnly` |
| `ProviderRetryPolicyTest` (repointed) | Product Outcome | 429 retries for OpenAI and Claude, never for Gemini; 5xx for all; signals and configuration kinds never | make Gemini's 429 decision `true` |
| `…everyProviderResolvesToItsNamedAdapter` | Drift Guard | `Provider.entries.associateWith(ProviderAdapters::of)` equals the literal map of four | point two providers at one adapter |
| `…modelRowsPerProvider` | Product Outcome | OpenAI `created` seconds → millis (expected value a literal, never `created * 1000`); Gemini keeps only `generateContent` rows and strips `models/`; Claude reads `display_name`, `created_at` (literal epoch millis), `has_more`, `last_id` | stop multiplying `created` by 1000 |
| `…listEnvelopePerProvider` | Product Outcome | `data` for OpenAI and Claude, `models` for Gemini, false for self-hosted and for `{}` | accept any object |
| `…replyTextPerFormat` | Product Outcome | the five decode paths on fixture bodies, `NONE` is null, plus the `</think>` strip and the empty-first-part case; the OpenAI fixture puts a typed `reasoning` item at `output[0]` and the literal text in a later `message` item | read `output[0]` again (red) |
| `…endedOfItsOwnAccordPerFormat` | Product Outcome | `completed`, `stop`, `STOP`, `end_turn`/`stop_sequence`, `done_reason`; absent marker false; `NONE` false | accept an absent marker as true |
| `…requestPlanToStringRedacts` | Observability Contract | no URL, header or body in `toString` | remove the override |
| `acceptsEveryProviderEnvelopeShape` (proposed) in `ProviderJsonTest` | Product Outcome | objects, arrays, strings with escapes, numbers, literals, each expected value a literal | replace the parser's array branch with a rejection |
| `…refusesTrailingContentControlCharactersAndBadEscapes` | Product Outcome | null on each | accept trailing content |
| `…refusesNestingPastTheCapAndAcceptsAtIt` | Product Outcome | 64 containers parse, 65 return null; an ordinary JVM stack parses 65 without the cap, so the row needs the cap to go red and never a tiny-stack thread | remove the depth check (65 parses) |
| `ProviderClientShapeTest` | Drift Guard | the two client files match no concrete enum constant (`Provider\.(OPENAI\|GEMINI\|CLAUDE\|SELF_HOSTED_POLISH)`); only `HttpProviderTransport.kt` under `providers/` imports `HttpURLConnection`; no `StackOverflowError` under `providers/`; paired with the registry row above so routing, not spelling, is what is pinned | run it against the pre-split file: red |
| the 56 moved rows | as declared in their KDoc | unchanged | unchanged |

## 12. Blast radius & rollback

- **Touched:** `app/src/main/java/com/envi/wispr/providers/` (one file becomes twelve), two default
  arguments in `ui/AppViewModel.kt` and `providers/ProviderConfigurationRepository.kt`, the test package.
- **Not touched:** `polish/PolishService.kt`, `ProviderPolishPrompt.kt`, `ProviderValidation.kt`, the
  Keystore store, AIDL, manifests, Gradle, `ModelDeliveryWorker`.
- **Rollback:** revert the squash commit; no schema, no stored format, no manifest.
- **Debt:** no temporary shortcut, compatibility shim or deferred extraction remains.

## 13. Ship criteria specific to THIS change

- [ ] The founder's key saves, the model list shows with the newest on top, one cloud take lands
      polished in Gmail, one local take still lands.
- [ ] `wc -l app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt` under 200.
- [ ] Every existing assertion remains represented: the 55 client and discovery rows move unchanged apart
      from fixture or method-owner repoints; the body-marker row and the seven `ModelListRulesTest` rows
      move to the adapter suite with their assertions kept; the Claude pagination assertions change only
      their expected type from `Pagination` to the equivalent `ListAdvance`.
- [ ] Codex confirms no provider identity behaviour lost, with a confirming re-run.

## 14. Open questions

None that block. Routed: a `ProviderTransport` fake for a future discovery test that needs to stage a
transport outcome the scripted server cannot (none known today).

## 15. Related

#189 (this), #4 (retries), #61 (key check), #65 (OpenAI reasoning items), #77 (error signals), #84
(discovery), #103/#104 (probe retry), #185 (the audit), #186/#195 (the session owner split, independent).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas, or N/A with a specific reason
- [x] §2.5 grounded in real code before §3 was written, never the reverse
- [x] §4-9 answered, briefly or in full, none struck through
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
