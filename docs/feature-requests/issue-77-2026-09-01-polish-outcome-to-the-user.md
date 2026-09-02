# Polish outcome to the user: the reason, on the completion surface and in History

GitHub issues: `#77` (completion surface, vocabulary) and `#18` (History rows). Tier: MEDIUM (new runtime behaviour on the publication path; a Room migration). Status: SHIPPED 2026-09-01.
Phase 4 of `plan-2026-09-01-ai-polish-refinement-roadmap.md`. Depends on phase 1 (#69, shipped) for the reason and status that travel with every outcome.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/**`: tests, codex-review, hardware-uat) and `Docs/dev-tooling` (`docs/**`: cited-symbols, conditional).

**PAR rows closed:** none named; the outcome contract's failure notices are covered by catalog `user_copy` rows (macOS, `cloud-polish` and `ai-polish`, surface `completion warning`), which this plan ports.

**Hardware UAT:** Y. Marcus configures Gemini with a key he then breaks, dictates into Gmail: the toast reads "Polish failed. Using raw text." and "AI polish failed: Gemini rejected your API key. Check or replace it in Settings."; the History card's polish line says the same in one sentence; his words are in the field, rules-cleaned. A staged on-phone timeout (`device-testing.md` FACT: the-staged-polish-timeout) reads "AI cleanup skipped: the dictation took too long. Your original text was pasted unchanged." on both surfaces. A healthy on-phone dictation and one with polish Off show nothing new.

## Preface — User Rubric

1. **Who is this user in this moment?** Marcus Weber, replying in Gmail on his phone with Gemini polish on. His key was rotated at work this morning. He stops speaking; the rules-cleaned text lands, unpolished, and nothing says why.
2. **Why would they want this?** "Tell me it broke and what to fix." He would rather see one sentence than notice, three days later, that his replies have not been polished all week.
3. **How would they invoke it?** He does not; the notice fires for him, once, on the dictation that failed. The History card carries it afterwards.
4. **What app are they in?** Gmail, Slack, Docs, Messages: the text must still arrive there, rules-cleaned, exactly as today.
5. **What is their natural input?** "hey team quick update on the pricing page, the redesign slipped a week, I will send the new dates tomorrow morning" and four more like it: ordinary sentences whose polish he only notices by its absence.
6. **What does success feel like?** He reads "Gemini rejected your API key. Check or replace it in Settings." and fixes it in one trip. Nothing else about the dictation changed.
7. **What does wrong-not-broken look like?** A rate-limit blip announced with the same weight as a revoked key, so he opens Settings for nothing and starts ignoring the notice.
8. **What would a power user hack around this to get?** Priya would open History and compare the original to the final text to guess whether polish ran; the card's polish line now answers that directly.
9. **What level of control would they want?** None: a failed polish always says so once. Turning polish Off is the way to silence it, and Off is silent by design.

### Cross-persona check

Frank Chen and Meera Patel gain the most: a problem they could not name becomes an instruction. Dr. Elena Vasquez wants the notice not to interrupt the next dictation; a toast is not modal. Aaron Wu wants no notice for transient blips, which the lead-in distinction (failed versus skipped) and the calmer sentences for rate limits carry. No persona wants the failure hidden.

---

## 0. TL;DR

The engine and the session owner already know why a polish ended the way it did (`PolishReason` plus an HTTP status since #69, the session-side reasons since #75). This phase turns that into ONE user-facing vocabulary (`PolishFailure`), derived in one place from the reason, the status, and for two body-signalled cases the provider's error body, with the sentence per member ported from the macOS set. The completion surface shows the locked sentence "Polish failed. Using raw text." followed by "AI polish failed: <sentence>" (or "AI cleanup skipped: <sentence>" for the skipped class). History stores the reason, the status and a stable policy token (`polishContext`) in three new columns (Room 5 to 6) and renders the same sentence on the card through the same derivation, `PolishFailure.from(reason, status, context)`.

## 1. Problem

Roadmap finding 4: `PolishService` keeps the reason and status on the outcome, the session owner logs them, and the History row stores only the engine label, which on every failure is `Deterministic fallback`, rendered as "Cleaned up on this phone". A revoked key and a healthy offline run look identical, on the phone and in History. #18: the History card explains nothing in a person's words.

## 2. Goals & non-goals

### 2.1 Goals
- Every polish failure the user can act on is said once, in a sentence from the shared product set, on the surface the user is looking at when the words land.
- The History card says the same for as long as the row lives.
- One derivation site; a new `PolishReason` member fails to compile until it has a user-facing member.

### 2.2 Non-goals
- A live key check or model list (#61, phase 7). Retries (#4, phase 8). Any change to what text is published: the fallback text is unchanged. The AI Polish screen (#67, phase 6). Any new sentence the macOS set does not have, except where Android has a case macOS does not (named in §3).

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end
- Producer: `PolishService.run` builds `PolishOutcome(requestId, text, engine, reason, statusCode, latencyMs)`; the cloud client's `ProviderPolishResult.Failure(kind, statusCode)` feeds `PolishReason.from(kind)`. On HTTP ≥ 400 the `status >= 400` branch of `ProviderPolishClient.kt` calls `readResponse` and DISCARDS the whole `ResponseRead`: a successfully read error body (so the body-signalled distinctions macOS makes, OpenAI 429 `insufficient_quota` (external), Gemini 400 `API_KEY_INVALID` (external), Claude 400 `credit balance` (external) / `prompt is too long` (external), the content-filter markers, are lost today) AND a read failure (`RESPONSE_TOO_LARGE`, `NETWORK`), which today is reported as a plain `HTTP_ERROR` with the status. The design (§3) keeps both: a read failure reports its own kind with the status; a read body yields only a closed signal.
- Owner: `DictationSessionService` receives the outcome in `onOutcome`, calls `publishResult(text, engine, latencyMs)`; the session-side fallbacks call `publishFallback(rawText, prefs, reason)`, which calls the same `publishResult`. The reason and the status stop there.
- Consumers: `TranscriptRepository.finalize` / `insertReadyTranscript` write `polishEngine` and `polishLatencyMs`; `HistoryScreen.HistoryCard` renders `PolishEngineLabels.historySummary(polishEngine, polishLatencyMs)`; the completion surface is the toast `announceInsertionFallback` posts from the publication path (the only post-completion surface that exists on Android; the overlay is hidden by then).
- The provider's display name on a failed cloud polish is NOT on the outcome (the engine label is `DETERMINISTIC`); it is on the latched policy the session owner holds (`sessionPreferences.policy` is `PolishPolicy.Cloud(provider, ...)`).

### 2. Find the existing authority before proposing one
- Sentences: catalog `user_copy`, `platform_key='macos'`, features `cloud-polish` and `ai-polish`, surface `completion warning` (14 rows) and the locked sentence (decision `b7-decision-polish-warning-copy`, 2026-07-15). Source: `~/Developer/EnviousLabs/EnviousWispr/Sources/EnviousWisprLLM/PolishFailureReason.swift` `message(provider:)`, and its `leadIn` split (`failed` versus `skipped`: apiKeyMissing, apiKeyUnreadable, inputTooLong, timedOut, noModelSelected are `skipped`).
- Status classification: the three macOS connectors' `classify(statusCode:bodyString:)` (OpenAI, Gemini, Claude), ported per provider in §3.
- Reason vocabulary: `PolishReason` (closed, exhaustive `from` and `resolve`). Engine labels and the History polish line: `PolishEngineLabels` (one renderer, values stored in Room).
- Announcement shape: `insertion/FallbackAnnouncement` (private constructor, one line, no haptic, nothing durable) is the precedent for a post-completion notice.

### 3. Read prior attempts and live direction
- #16 settled that History says nothing about DELIVERY; this plan adds a line about POLISH, which is a fact about the words themselves, not about where they went.
- #69 removed `RegexPolisher` so the fallback text is the engine's deterministic text; this plan does not touch text.
- Roadmap §1 phase 4 names the vocabulary and the two surfaces; phase 7 will reuse the status-aware members for the key check.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss
- The error body is read in the `:polish` process and must not cross to the main process or a log: only a closed signal enum does (privacy: the body could echo the prompt on some providers).
- `PolishReason` crosses the binder inside `PolishOutcome` by NAME (`writeString(reason.name)`, `valueOf` on read), so declaration position has no wire effect; renaming or removing a member breaks decoding, and an older client reading a new name throws. Appending is a source-history convention here, not a Parcelable requirement (§4).
- Rows written by older builds have no reason: the three columns default to `''`, `0`, `''`, and the renderer treats an empty reason as "nothing to say beyond the engine line", which is today's rendering.
- The toast is posted on the main handler before `finishSession` stops the service, the same as the fallback toast; a toast outlives its poster.

### 5. Prove the high-risk premises
- `PolishOutcome`'s reason encoding: by name (`writeString(reason.name)` / `valueOf`), read 2026-09-01; consequence stated in §4.
- Gemini with a broken key answers 400 `API_KEY_INVALID`: macOS measured it (GeminiConnector classify comment); Android re-measures it in the hardware step with a deliberately broken key.

## 3. Design

**`polish/PolishFailure.kt`.** A closed enum, one member per sentence: `KEY_MISSING`, `KEY_REJECTED`, `ACCESS_DENIED`, `OUT_OF_CREDITS`, `RATE_LIMITED`, `RATE_OR_QUOTA`, `MODEL_UNAVAILABLE`, `INPUT_TOO_LONG`, `CONTENT_BLOCKED`, `UNREACHABLE`, `PROVIDER_ERROR`, `BAD_REQUEST`, `TIMED_OUT`, `OUTPUT_REJECTED`, `LOCAL_NOT_READY`, `UNEXPECTED`. `PolishFailure.from(reason: PolishReason, statusCode: Int, context: PolishContext?): PolishFailure?` is the ONE derivation. It returns `null` first when the context is `Off` or null (an unknown or empty stored token decodes to null), so a session with polish Off never produces a notice whatever the session-side reason (a `SERVICE_DIED` under Off is not a polish failure the user asked for); `cloud-unconfigured` stays distinct and maps `CLOUD_NOT_CONFIGURED` to `KEY_MISSING`. Then an exhaustive `when` over `PolishReason` with no `else`; `null` for `POLISHED, OFF, NO_SPEECH, EMPTY_AFTER_CLEANUP, CLEANUP_RECOVERED, CANCELLED`. `HTTP_ERROR` splits on the status: 401 `KEY_REJECTED`; 402 `OUT_OF_CREDITS`; 403 `ACCESS_DENIED`; 404 `MODEL_UNAVAILABLE`; 413 `INPUT_TOO_LONG`; 429 `RATE_OR_QUOTA` for Gemini else `RATE_LIMITED`; 500 to 599 `PROVIDER_ERROR`; other 4xx `BAD_REQUEST`; anything else `UNEXPECTED`. The mapping table for every member is in §7. `message(providerName: String?)` returns the macOS sentence verbatim; `leadIn` is `FAILED` ("AI polish failed:") or `SKIPPED` ("AI cleanup skipped:") per the macOS split, with the Android-only `LOCAL_NOT_READY` and `OUTPUT_REJECTED` classified `SKIPPED` and `FAILED` respectively.

**Android-only cases, mapped to the nearest honest sentence and named here:** `LOCAL_NOT_READY` (no macOS twin) says "the on-phone model isn't ready yet. Try again in a moment."; `OUTPUT_REJECTED` (the hallucination and length guards, `PipelineOutcome.MODEL_REJECTED`) says "the model's answer didn't match what you said, so your original was kept."; `LOCAL_FAILED`, `SERVICE_UNAVAILABLE`, `SERVICE_DIED`, `CALL_FAILED` map to `UNEXPECTED`; `LOCAL_TIMEOUT`, `WATCHDOG_TIMEOUT`, `TIMEOUT` map to `TIMED_OUT`; `NETWORK` to `UNREACHABLE`; `MALFORMED_RESPONSE`, `RESPONSE_TOO_LARGE`, `REDIRECT_REJECTED` to `BAD_REQUEST`; `NO_API_KEY` and `CLOUD_NOT_CONFIGURED` to `KEY_MISSING`. For a local engine the provider name in a sentence is "the on-phone model" and the Settings hint is unchanged.

**Body-signalled cases.** `ProviderPolishClient` gains `ProviderErrorSignal`: `OUT_OF_CREDITS, KEY_REJECTED, INPUT_TOO_LONG, CONTENT_BLOCKED`, or none, computed inside the client from the already-read error body by a per-provider exhaustive `when` (ported from the three macOS classifiers: OpenAI 429 `insufficient_quota`, 400 `context_length_exceeded` (external) / `content_filter` (external) / `content_policy` (external); Gemini 400 `API_KEY_INVALID` / `exceeds the maximum number of tokens` (external) / `PROHIBITED_CONTENT` (external) / `blockReason` (external); Claude 400 `credit balance` / `prompt is too long`). The body itself goes no further. The HTTP branch becomes: `when (val response = readResponse(...)) { is ResponseRead.Failure -> Failure(response.kind, status); is ResponseRead.Success -> Failure(HTTP_ERROR, status, classifyErrorSignal(provider, status, response.body)) }` (drop-in from the coverage round), so a read failure keeps its own kind. `PolishReason` gains four members APPENDED at the end: `HTTP_OUT_OF_CREDITS, HTTP_KEY_REJECTED, HTTP_INPUT_TOO_LONG, HTTP_CONTENT_BLOCKED`; `PolishReason.from(kind, signal)` produces them for `HTTP_ERROR` with a signal, so the status still travels beside them.

**The completion surface.** `polish/PolishFailureNotice`, private constructor, one factory: `notice(failure, context)` returns two parts. Measured on the phone 2026-09-01: a toast shows two lines and truncates the rest ("AI polish failed: Gemini rejected your ..."), so a two-sentence toast cannot carry the reason. The toast carries only the locked sentence, for `FAILED` and never for a skip; the lead-in and the full reason go to a silent notification on its own channel (`polish_problems`, low importance, not ongoing, cleared on tap, tapping opens the app), posted by `DictationNotificationController.showPolishNotice`. This is the one durable thing outside History, and it is durable on purpose: a revoked key is something the user must act on, and macOS's completion warning likewise stays until read. `DictationSessionService.publishResult(text, engine, latencyMs, reason, statusCode, polishContext)` holds the SOLE call to `PolishPublicationFacts.from(reason, statusCode, context)`, which returns the three stored facts and the notice text or null; it posts the notice on the main handler BEFORE starting persistence or insertion (so it precedes the session-owned delivery toast), and passes the facts to the History write. `publishFallback(rawText, takePreferences, reason)` calls `publishResult` with the deterministic text, `DETERMINISTIC`, latency 0, the reason, status 0 and `PolishContext.from(takePreferences.policy)`; `onOutcome` passes the outcome's reason and status and the same latched context. There are exactly two publication routes, `onOutcome → publishResult` and `publishFallback → publishResult`, and that is the guarded shape (§11); the eight current fallback producers are behavioural rows, not the architecture.

**History.** Room version 6: `polishReason TEXT NOT NULL DEFAULT ''` (the `PolishReason` name), `polishStatus INTEGER NOT NULL DEFAULT 0`, `polishContext TEXT NOT NULL DEFAULT ''`. The context is a stable persisted token for the latched policy, `PolishContext`: `off`, `local`, `cloud-unconfigured`, or `cloud:<Provider name>`; it is what `PolishFailure.from(reason, status, context)` needs, so live publication and History call the SAME derivation, and the provider's display name is resolved only while rendering (Cloud: from the stored provider key through `capabilities().displayName`; CloudUnconfigured has no provider and uses the generic key-missing sentence; LocalS1 renders "the on-phone model"; Off never produces a notice). `PolishReason.name` and the context tokens are database schema values: never rename or remove one without a migration or a legacy alias; decoding is tolerant, `decodeStoredReason(value)` returns null for an empty or unknown value and the card then renders today's line rather than throwing. The entity declares both the Kotlin and the SQL defaults (`@ColumnInfo(defaultValue = "''")` / `"0"`); `MIGRATION_5_6` adds the three `NOT NULL` columns with the same defaults, is `internal` so the migration test can name it, and is appended to `addMigrations(...)`; `TranscriptDao.finalize` names all three columns in its UPDATE and `TranscriptRepository.finalize` forwards them unchanged; the ready insert passes them into `TranscriptEntity`. `updateStatus`, `finalizeInsertionOutcome` and the stale-row recovery leave the polish facts untouched. `PolishEngineLabels.historySummary` gains the three facts: when the stored reason names a failure, the line is "AI polish failed: <sentence>" (or the skipped lead-in) followed by the existing engine line ("Cleaned up on this phone in N ms"); an empty or unknown reason renders exactly as today.

## 3b. Ownership justification
`PolishFailure` lives in `polish/` beside `PolishReason` because it is the reason's user-facing projection; both surfaces read it and neither owns it. The notice type lives beside it, not in `insertion/`, because it is about the words, not their delivery. The session owner gains three parameters (reason, status code, context) and one derivation call, no new state (`keep-central-types-thin`).

## 4. Contract deltas
- `PolishReason`: four members appended. Wire: `PolishOutcome` writes `reason.name` and reads it back with `PolishReason.valueOf` (verified 2026-09-01), so an older client reading a NEW name throws. The only separately installed client is the instrumentation APK, which is built from the same tree and reinstalled with the app on every hardware run (`android-tooling.md`); no shipped client can be older than the engine. Members are appended as a source-history convention; Parcelable compatibility depends on the persisted names, not on declaration position.
- `ProviderPolishResult.Failure`: `signal: ProviderErrorSignal?` added with a default of null.
- Room 5 to 6, additive columns with defaults; `app/schemas/.../6.json` committed.
- `PolishEngineLabels.historySummary` signature grows; the stored engine label values are unchanged.
- Persisted vocabularies: `PolishReason.name` and the `PolishContext` tokens become schema values with the compatibility contract in §3 (no rename without a migration or alias; tolerant decoding).
- No AIDL change.

## 5. End-to-end state and lifecycle audit
| Event | Outcome |
|---|---|
| Cloud 401 on a real dictation | Toast: locked sentence + "AI polish failed: Gemini rejected your API key. Check or replace it in Settings."; History stores `HTTP_ERROR`/401/`cloud:GEMINI`; card shows the same sentence over the engine line |
| Local timeout (#75 hard or cooperative) or watchdog | Toast: "AI cleanup skipped: the dictation took too long. Your original text was pasted unchanged." (no locked sentence: skipped class); History `LOCAL_TIMEOUT` or `WATCHDOG_TIMEOUT` |
| Polish Off, no speech, cleanup recovered, deterministic empty | No notice, History line unchanged from today |
| User cancel while processing | No publication, no notice, no row (unchanged) |
| Outcome arriving after the ledger closed | Ignored (unchanged); nothing announced |
| Session-side fallback: engine unbound / died / call threw, polish on | `UNEXPECTED` notice with the locked sentence; History stores the session-side reason and the context |
| Session-side fallback under polish Off | No notice (the Off gate in `from`); History stores the reason and `off` |
| Healthy `POLISHED` outcome | No notice; History stores `POLISHED`, the status and the context; the engine line renders as today |
| `NO_API_KEY` or `CLOUD_NOT_CONFIGURED` | Skipped notice (`KEY_MISSING`); History stores the producing reason |
| `LOCAL_NOT_READY` | Skipped notice; the deterministic text still publishes |
| `OUTPUT_REJECTED` | Failed notice; the guarded fallback text still publishes |
| Provider `NETWORK` / `TIMEOUT` | `UNREACHABLE` failed notice / `TIMED_OUT` skipped notice |
| Body-signalled HTTP reason | The signal overrides the generic status mapping; only the closed reason crosses processes |
| Malformed, oversized or redirected response | `BAD_REQUEST` failed notice; the fallback text still publishes |
| Blank transcript (no speech) | The draft is deleted before `publishResult`; no publication, no notice, no History row (unchanged) |
| Draft missing, or finalize updates zero rows | The ready-row insert stores the same reason, status and context as a normal finalization |
| Row written by an older build (empty reason) | Card renders today's engine line only |
| The toast's poster is finishing | Posted on the main handler before `finishSession`, as the delivery toast already is |
| Both a polish notice and a delivery line fire | The polish toast (locked sentence) precedes the delivery toast, because it is posted before persistence and insertion begin; the reason itself is in the shade, unaffected by toast order |
| The app has no notification permission | The notification post is best effort; the toast and the History card still carry the outcome |

## 6. Downstream consumer matrix
| Consumer | Change |
|---|---|
| `HistoryScreen.HistoryCard` | passes the three new entity fields to `historySummary` |
| `TranscriptRepository` / `TranscriptDao` | three columns on finalize and insert |
| `EnviousWisprDatabaseMigrationTest` | new 5 to 6 case |
| `PolishEngineLabelsTest` | failure rows |
| Logs | the reason was already logged by name; the sentence is not logged |
| Catalog | `ai-polish`/`cloud-polish` Android rows gain the notice; `user_copy` Android rows added for the Android-only sentences |

## 7. Failure-mode × caller table
| `PolishReason` (+status) | `PolishFailure` | Lead-in |
|---|---|---|
| POLISHED, OFF, NO_SPEECH, EMPTY_AFTER_CLEANUP, CLEANUP_RECOVERED, CANCELLED | none | |
| NO_API_KEY, CLOUD_NOT_CONFIGURED | KEY_MISSING | skipped |
| HTTP_ERROR 401, HTTP_KEY_REJECTED | KEY_REJECTED | failed |
| HTTP_ERROR 403 | ACCESS_DENIED | failed |
| HTTP_ERROR 402, HTTP_OUT_OF_CREDITS | OUT_OF_CREDITS | failed |
| HTTP_ERROR 429 (Gemini) | RATE_OR_QUOTA | failed |
| HTTP_ERROR 429 (others) | RATE_LIMITED | failed |
| HTTP_ERROR 404 | MODEL_UNAVAILABLE | failed |
| HTTP_ERROR 413, HTTP_INPUT_TOO_LONG | INPUT_TOO_LONG | skipped |
| HTTP_CONTENT_BLOCKED | CONTENT_BLOCKED | failed |
| HTTP_ERROR 5xx | PROVIDER_ERROR | failed |
| HTTP_ERROR other 4xx, INVALID_CONFIGURATION, MALFORMED_RESPONSE, RESPONSE_TOO_LARGE, REDIRECT_REJECTED | BAD_REQUEST | failed |
| NETWORK | UNREACHABLE | failed |
| TIMEOUT, LOCAL_TIMEOUT, WATCHDOG_TIMEOUT | TIMED_OUT | skipped |
| OUTPUT_REJECTED | OUTPUT_REJECTED | failed |
| LOCAL_NOT_READY | LOCAL_NOT_READY | skipped |
| LOCAL_FAILED, SERVICE_UNAVAILABLE, SERVICE_DIED, CALL_FAILED, UNEXPECTED, HTTP_ERROR other | UNEXPECTED | failed |

## 8. Caller-visible signals audit
The toast text and the History polish line are the only new signals; the log gains nothing (the reason was already logged). No haptic, no notification, nothing durable outside History.

## 9. Fallback source-of-truth audit
Text and fallback selection are untouched: `PolishFallback.deterministic` remains the single fallback owner (#69). `PolishFailure` is a deliberate second layer that PROJECTS the internal reason, status and context into a smaller user-message vocabulary; it never chooses the published text.

## 10. File-by-file changes
- `polish/PolishFailure.kt` (new): enum, `from`, `message`, `leadIn`.
- `polish/PolishFailureNotice.kt` (new): the toast text.
- `polish/PolishReason.kt`: four appended members; `from(kind, signal)`.
- `providers/ProviderPolishClient.kt`: `ProviderErrorSignal`, per-provider body classification, `Failure.signal`.
- `polish/PolishService.kt`: passes the signal into `PolishReason.from`.
- `polish/PolishEngineLabels.kt`: `historySummary(engine, latency, reason, status, context)`.
- `polish/PolishContext.kt` (new): the persisted policy token and its decoder.
- `polish/PolishPublicationFacts.kt` (new): the one derivation the session owner calls.
- `history/TranscriptEntity.kt`, `TranscriptDao.kt`, `TranscriptRepository.kt`, `EnviousWisprDatabase.kt` (+ `app/schemas/.../6.json`).
- `ui/DictationSessionService.kt`: `publishResult(text, engine, latencyMs, reason, statusCode, polishContext)`, the notice toast, the three facts to History.
- `ui/HistoryScreen.kt`: the call site.
- Tests: `PolishFailureTest` (new), `PolishEngineLabelsTest`, `PolishReasonTest`, `ProviderPolishClientTest` (body signals), `EnviousWisprDatabaseMigrationTest` (androidTest).

## 11. Testing
- `PolishFailureTest` (Product Outcome): every §7 ROW, not merely every member: each `PolishReason` at status 0, and `HTTP_ERROR` at 400, 401, 402, 403, 404, 413, 429 for Gemini, 429 for another provider, 499, 500, 599 and 0; every member's sentence is non-empty, has no dash, and names the provider where the macOS sentence does; the lead-in split matches macOS for the ported members. Revert to run: swap 401 and 403 in `from`, expect red.
- `PolishFailureNoticeTest` (Product Outcome): every exact sentence, the failed and skipped lead-ins, the locked first line and the newline for failed, its absence for skipped, provider substitution.
- `PolishPublicationFactsTest` (Product Outcome): the one pure function the session owner calls from `onOutcome` and from every `publishFallback` site (two `SERVICE_DIED`, one `SERVICE_UNAVAILABLE`, one `WATCHDOG_TIMEOUT`, four `CALL_FAILED`, enumerated from the producing code) turns (reason, status, context) into the stored facts and the notice; all eight fallback producers are tested behaviourally (a fallback carries status 0 and the latched context); a session with polish Off yields no notice for every reason. The session owner itself is not unit-testable; the source Drift Guard below pins the routes.
- `PolishEngineLabelsTest` (Product Outcome): a failure row renders the sentence over the engine line; an empty reason renders today's line byte for byte.
- `ProviderPolishClientTest` (Product Outcome), a matrix over the three providers: OpenAI quota, context length, content filter, unmatched body; Gemini invalid key, maximum tokens, prohibited content, block reason, unmatched body; Claude credit balance, prompt too long, unmatched body; a read failure keeps its own kind with the status; only the closed signal survives on the result and the body is never on it.
- A parcel round-trip test was planned and NOT written: `Parcel` exists only on the device, and the name
  encoding is read directly off `PolishOutcome` (`writeString(reason.name)` / `valueOf`) and pinned by
  `PolishReasonTest`'s member-name assertion; the instrumentation APK is rebuilt with the app on every run.
- `EnviousWisprDatabaseMigrationTest` (Drift Guard, androidTest): 5 to 6 preserves rows and defaults the three columns.
- `TranscriptOutcomePersistenceTest` (Drift Guard, androidTest beside `TranscriptRepositoryTest` because the DAO has no JVM harness): a draft starts with the empty defaults; finalization replaces all three; the ready-row insert stores identical facts.
- `PolishReasonTest`: the count and the names of the new members.
- Drift Guard on the publication routes: `publishResult` alone calls `PolishPublicationFacts.from`, and the outcome route and all eight fallback producers reach it through `publishResult`: exactly two call sites of `publishResult` and exactly one call of `PolishPublicationFacts.from`, read from the source file; a new direct publisher or a second derivation is red.

### 11.1 Hardware UAT spec
- **Subsystem:** the publication path (heart) and the polish limb.
- **Recipe:** (1) Cloud, Gemini, a deliberately broken key saved on the screen; dictate the 11 s fixture into Gmail: the toast shows the locked sentence and the key-rejected sentence naming Gemini; the History card's polish line says the same; the words are in the field. (2) This phone with `polish-stall-ms` = 30000: the toast reads the skipped lead-in with the timed-out sentence; History says the same. (3) Broken key again from the launcher (no pinned field): exactly one toast, the polish notice, and no delivery toast, because `NO_PINNED_TARGET` with a successful clipboard copy is deliberately silent (`FallbackAnnouncement`). The two-toast ordering is NOT staged on hardware: the only honest staging would be a debug override on the accessibility handoff, which is a bypass seam on the heart path (`validation-discipline.md` RULE: a-test-seam-on-a-GUARD-is-a-bypass); the order is carried by construction (the notice is posted before persistence and insertion begin) and pinned by the source Drift Guard. (4) This phone, healthy: nothing new. (5) Off: nothing new. Remove the broken key and the stall file.
- **Phone state to restore:** provider key removed, mode Off, stall file removed, engine process ended.

### 11.2 Other obligations
`tests`, `codex-review`, `hardware-uat` (Code lane); `cited-symbols` (Docs lane, conditional).

## 12. Blast radius & rollback
The publication path gains a derivation and a toast; the text is unchanged. A Room migration is additive. Rollback is a revert; rows written with the new columns lose nothing on a downgrade because Room refuses to open a newer schema (a downgrade is not a supported path in stage 1).

## 13. Ship criteria specific to THIS change
- Every `PolishReason` member has a row in §7 and a test row.
- Both surfaces render the same sentence from the same derivation.
- The error body never leaves the `:polish` process (grep for the signal enum's producers).

## 13.1 Found on the phone

- **The app never declared `android.permission.INTERNET`.** Every cloud request failed in 30 ms as
  unreachable (reason `NETWORK`), on this build and on every build before it: cloud polish has never
  worked on the phone. Added in this change, with the reason in the manifest. The catalog's `cloud-polish`
  "shipped" row is a discrepancy to record at wind-down.
- **A toast truncates after two lines**, which moved the reason to a notification (§3).
- **The client's silent catches** now log the exception class name (shape, never content), which is how
  the missing permission was found.

## 14. Open questions
- Whether the notice toast should be suppressed for `RATE_LIMITED` (Aaron's transient blip). The plan's reading: no; macOS announces it with a calm sentence, and parity wins until a measurement says otherwise.

## 15. Related
#69 (reason and status on the outcome), #75 (session-side reasons), #61 (phase 7 reuses the status-aware members), #67 (phase 6 screen), catalog decision `b7-decision-polish-warning-copy`.

## Review log

- **Coverage round, 2026-09-01, Codex session `01a05faf-a2a1-7a20-961a-ca48a45359ec`:** PROCEED-WITH-REVISIONS,
  all adopted: nine §5 rows added and the no-speech row corrected (the draft is deleted before publication);
  §11 now tests every §7 row and the HTTP status matrix, the notice contract, the parcel round-trip, the
  propagation from the one outcome path and the eight fallback sites through one pure function, the three
  row-writing paths, and the full three-provider body matrix; the HTTP branch keeps a read failure's own kind
  (drop-in code in §3); the wire premise corrected (name encoding, position irrelevant); §9 reworded (a
  projection, not a re-classification). Unchecked by the round: repository, DAO, AIDL, `FallbackAnnouncement`.
- **Code round 1, 2026-09-01, same session:** FINDINGS, all four adopted. (1) The client's
  `INVALID_CONFIGURATION` (bad model, endpoint, oversized request) reached the user as "no API key set
  yet": it is now its own `PolishReason` member, appended, mapped to the configuration sentence. (2) The
  tolerant decoder accepted an impossible token (`cloud:GEMINI:ollama`) and would have given Ollama
  guidance for a cloud provider: rejected now, with tests. (3) The routes guard counted occurrences only:
  it now pins the two named routes by section, the notice before the persistence coroutine, the eight
  fallback producers by reason, and the ready insert's three facts. (4) Every Gemini sentence is pinned
  verbatim. Client, session owner, History: no finding.
- **Code round 2, 2026-09-01, same session:** CLEAN. Axes: derivation (Off and unknown suppression, the
  Gemini split, invalid configuration, Ollama guidance, token rejection), client containment, the two
  routes and one derivation, History defaults and migration, and the tests (exact copy, the eight producers,
  routes and ordering, the ready insert).
- **Code round 3 (delta), 2026-09-01, same session:** one finding, adopted: a skip with no toast would have
  been silent when the notification permission is denied; a skip now toasts its reason line (truncated by
  the toast if long, complete in the notification and History). Permission, log privacy, channel, id,
  flags, intent, the hard-failure split and notification 1001's isolation: found correct.
- **Code round 4, 2026-09-01, same session:** CLEAN (hard failures toast the locked sentence only; skips
  toast their reason line; the notification carries the full title and detail; toast before notification;
  denial leaves the toast and History; the publication gates unchanged).
- **Hardware round 1, 2026-09-01:** the broken Gemini key produced `NETWORK` in 34 ms; the missing
  Internet permission (§13.1) was found and added; the second run classified the real Gemini 400 body as
  `HTTP_KEY_REJECTED` (status 400), showed the notice and stored `HTTP_KEY_REJECTED`/400/`cloud:GEMINI`
  (row 93), and the History card read "AI polish failed: Gemini rejected your API key. Check or replace
  it in Settings." The toast truncated the reason, so the surface split into toast plus notification (§3);
  Codex delta round to follow on both changes.
- **Grounded round 3, 2026-09-01, same session:** no design finding; every axis found closed (stored
  context, the Off and unknown gate, the unconfigured distinction, compatibility, the two-route guard,
  migration and DAO defaults, the one-toast case and the bypass rationale). Four wording residues of the
  old contract remained (§3b, §5, §9, §11) and are corrected here. Per the founder's diminishing-returns
  guidance (2026-09-01) a fourth round on wording is not run: the design is taken as PROCEED-AS-PLANNED.
- **Grounded round 2, 2026-09-01, same session:** PROCEED-WITH-REVISIONS: four wording residues of the
  superseded contract corrected (context everywhere, the routes guard, the append rationale); the Off gate
  added to the derivation with its §5 row and test; the combined two-toast hardware case REJECTED as
  proposed, because the drop-in required a debug override on the accessibility handoff, a bypass seam on
  the heart path; replaced by the one-toast launcher case and the source guard, stated in §11.1.
- **Grounded round 1, 2026-09-01, same session:** PROCEED-WITH-REVISIONS, all adopted: the stored provider
  display name could not reconstruct the policy (Off, local, unconfigured, Gemini's 429), so History stores a
  stable `PolishContext` token and both surfaces call one derivation; persisted names get a compatibility
  contract and tolerant decoding; the publication signature carries the context explicitly; the Drift Guard
  pins the two publication routes and the single derivation call rather than a site count; provider naming
  per policy made explicit; entity SQL defaults, the finalize columns, migration visibility and registration
  written down; the toast ordering gets a combined hardware case. Direction, composition and the skipped
  class confirmed sound.

## Checklist for the plan author
- [x] Gate 0 prior context posted on #18
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it
