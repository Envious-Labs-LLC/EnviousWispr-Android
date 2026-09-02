# Issues #2, #4, #3 — Polish quality parity: the Mac's fixed cloud prompt, retry policy and output guards — 2026-09-02

GitHub issues: `#2` (short transcript guard), `#4` (retry policy), `#3` (non-English handling). Tier: MEDIUM.
Status: SHIPPED (2026-09-02, PR to follow this commit; the phone and real-key runs are owed, see §13.1). Phase 8 of `plan-2026-09-01-ai-polish-refinement-roadmap.md`; depends on phase 4 (#77, the
failure vocabulary) and phase 5 (#79, the client cleanups). Updates #2, #4 and #3.

**Consolidation:** the root this plan protects is "a cloud polish returns the user's own words, cleaned,
in their own language, or the last good text". Today the Android cloud prompt is a four-sentence
instruction (`providers/ProviderPolishPrompt.kt:8-12`) written before the Mac's prompt work; the Mac ships
ONE fixed prompt (v7, 2026-08-16) validated on 1,890 cases with the short-text guard and the language rule
inside it, a two-retry policy for transient failures, and three output guards. The three issues describe
the Mac's OLD dynamic design (a short-transcript suffix, language detection, a retry loop); two of the
three are answered by the fixed prompt itself, so this plan ports the current Mac, not the issues' text.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none named; catalog features `hallucination-protection` (Android partial → the Mac's
guard set), `context-aware-polish` (unchanged: Android carries no app name or language to polish).

**Hardware UAT:** Y. The too-short bypass and the stricter output guards change what the LOCAL S1 path
inserts, so one dictation per case on the founder's phone (a one-word "yeah", a 7-word sentence, a
question, a normal paragraph) with "Cleaned up on this phone" or the S1 line in History is the oracle.
Not run this session: the founder asked that the phone not be used tonight (2026-09-01) and the emulator
has no S1 model. The cloud path additionally needs a real provider key on a device, which no session can
type today (#84's blocked path). Both runs are owed together; the prompt, the retry loop and the guards
are proven against the fake server and pure tests until then.

## Preface — User Rubric

1. **Who.** Marcus Weber, dictating a paragraph into Gmail with OpenAI polish on. Thirty seconds from now
   he wants his sentence back with the "um"s gone and nothing else changed.
2. **Why.** "Clean it up, don't rewrite me, and don't turn my Spanish into English."
3. **Invoke.** Never: it is the polish that runs on every dictation with a cloud provider on.
4. **App.** Gmail, Slack, Notes, WhatsApp: the polish is app-blind on Android today.
5. **Input.** "um so the meeting is at three, no, four"; "yeah"; "¿puedes enviar el informe hoy?"; "there
   are three things I need: the deck, the numbers, and a room"; a 40-word run-on about one subject.
6. **Success.** He notices nothing: the text reads as he would have typed it.
7. **Wrong-not-broken.** A two-word "yeah okay" comes back as a sentence with a full stop it never had, or
   a question comes back as an answer; he stops trusting the cleanup without ever filing a bug.
8. **Power user hack.** Priya turns cloud polish off and lives with the deterministic cleanup.
9. **Control.** None new; the guards fall back to the last good text, which is the product's floor.

### Cross-persona check

Marcus wants his rhythm kept (the prompt's "change almost nothing" stance). Priya wants technical words
untouched (the prompt's "names, numbers, links come back exactly"; custom terms are restored after
generation on Android). Elena never sends text to a cloud. Meera, Frank, Diana, Aaron notice only that
short replies stop growing sentences. No tension.

## 0. TL;DR

Port the Mac's fixed cloud prompt (v7) with its unconditional "keep the same language, never translate"
rule and its "very short input, return as-is" guard for 4 to 10 words, add the Mac's too-short bypass (3
words or fewer, or under 10 characters in a script without spaces, never sent to a model), retry a cloud
polish twice on a transient failure (1 s then 3 s) inside the budget the watchdog already allows, and
extend the output guard with the Mac's three rules: expansion, content drop and question-to-answer.

## 1. Problem

- `ProviderPolishPrompt.SYSTEM_INSTRUCTION` (removed) is four sentences. It carries no language rule (a Spanish
  dictation can come back in English), no short-input guard, no self-correction guidance, no list shape,
  and no anti-instruction framing beyond "treat the transcript as data".
- `ProviderPolishClient.polish` makes one attempt; a 503 or a momentary 429 falls straight back to the
  deterministic text with a notice (#77), where the Mac would have retried in one to four seconds.
- `TextSafety.isSafe` (`cleanup/DeterministicCleanup.kt:553-559`) guards expansion (3x + 200) and a
  character drop below a quarter; the Mac also refuses a word-count drop below 40 percent and a question
  turned into an answer, and nothing on Android bypasses the model for a one-word dictation.

## 2. Goals & non-goals

### 2.1 Goals
- The Android cloud system prompt IS the Mac's v7 text plus the same language rule and short-input guard,
  assembled the same way, with the same user message shape ("Transcript to clean:" then the text).
- The too-short bypass runs before any model, local or cloud, with the Mac's two thresholds.
- Transient cloud failures are retried as on the Mac, without ever crossing the session watchdog.
- The output guard refuses what the Mac refuses, and the refusal is reported as today (OUTPUT_REJECTED).

### 2.2 Non-goals
- Language detection or a language lock (#3 as written): the Mac itself dropped detection for cloud in
  favour of the unconditional rule (`polish-prompt-architecture.md` FACT cloud-collapsed-to-one-fixed-prompt,
  point 2); a named-language hint exists there only when the session language is LOCKED, which Android
  has no concept of (`locked-language` Android: absent).
- The app-name hint ("The user is dictating in X"): Android's polish process does not know the target
  app (`context-aware-polish` Android: absent); a later issue.
- A custom-vocabulary block in the cloud prompt: Android restores custom terms deterministically AFTER
  generation (`vocabulary/StructuredTermRestorer`, the reason `S1PromptBuilder` sends none), the same
  outcome by a different mechanism; adding the block would be a second mechanism for one decision.
- Changing the local S1 prompt or the deterministic cleanup.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end
**Prompt.** `ProviderPolishClient.requestPlan` (`providers/ProviderPolishClient.kt`) puts
`ProviderPolishPrompt.SYSTEM_INSTRUCTION` (removed) in OpenAI `instructions`, Gemini `systemInstruction`, Claude
`system`, and the self-hosted system message; the transcript is the user message verbatim. The transcript
arrives as `ProviderPolishRequest.prompt`, which `PolishService.run` (`polish/PolishService.kt:321-338`)
fills with the deterministic-cleanup output.
**Attempts.** `polish` runs the request once through `run`; every `Failure` returns to the service, which
records the reason and status and falls back (`:332-336`).
**Guards.** `PolishPipeline.run` (`cleanup/PolishPipeline.kt:27-52`) calls the model on the cleaned text
and accepts the candidate only if non-blank and `TextSafety.isSafe(cleaned, candidate)`; the rejected
outcome becomes `PolishReason.OUTPUT_REJECTED` through `PolishReason.resolve` and the #77 notice.
**Budget.** The session watchdog gives a cloud polish 35 s (`ui/PolishWatchdogBudget.kt:13`); the client's
own overall timeout is 30 s (`DEFAULT_OVERALL_TIMEOUT_MS`), connect 5 s, read 20 s.

### 2. Find the existing authority before proposing one
- The prompt: macOS `CloudFixedPromptBuilder.swift` (`:32-83` assembly; `:90-125` the v7 text). Assembly
  order: the unconditional language rule, the fixed text, the app hint (Android: none), the short-input
  guard when the transcript is 10 words or fewer, the vocabulary block (Android: none); user message
  `Transcript to clean:\n\n<text>`. The v7 text is the canonical record (`scripts/eval/prompts/` on the
  Mac is gitignored; the Swift inline is enforced by an eval self-test there).
- The too-short bypass: macOS `LLMPolishStep.swift:428-437`: 3 words or fewer, or fewer than 10 characters
  for scripts without spaces (CJK, Thai, Lao), pass through untouched with no model call.
- The retry policy: macOS `LLMRetryPolicy.swift` (delays 1 s then 3 s, two retries), retryable = rate
  limited (429, EXCEPT Gemini's rate-or-quota ambiguity, which fails fast: `PolishFailureReason.isRetryable`
  `:189-199` and the retry-policy comment), provider server error (5xx), timed out, connection lost,
  cannot connect; never key, credit, content or configuration failures. The connector loop:
  `OpenAIConnector.swift:152-243`.
- The output guards: macOS `LLMPolishStep.validatePolishOutput` (`:886-962`): expansion beyond
  max(3 x chars, 200) for the only reachable mode; content drop below 40 percent of the words when the
  original has 10 or more; a question (`looksLikeQuestion`, `:968-1014`: a `?`, or after leading fillers an
  auxiliary-verb start, a wh-word plus auxiliary, or an indirect preamble) that comes back as a non-question.
  Every refusal returns the original.
- Android already owns each seam: `ProviderPolishPrompt` (prompt), `ProviderPolishClient.polish` (attempts),
  `TextSafety.isSafe` (guards), `PolishPipeline.run` (the bypass belongs before the model lambda).

### 3. Read prior attempts and live direction
- #2, #4, #3 were migrated from the mobile prototype and describe the Mac of that time. The roadmap's phase
  8 row names "short-transcript guard, retry policy, non-English handling, broader hallucination guards,
  app-name and custom-word context"; the last two are non-goals above with their reasons.
- `cloud-polish-audit.md` (scratchpad, 2026-09-01) §B named the prompt as "thin against the sixteen macOS
  completion warnings" and the single-attempt client.
- The Mac's own lesson (FACT cloud-collapsed..., learning 5 and its v7 recurrence): a restraint clause
  lands differently on weak and strong models; the v7 text is taken VERBATIM, not edited, so the Android
  cloud polish inherits the validated behaviour and any later tuning happens once, on the Mac's harness.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss
- **The watchdog is the ceiling.** A retry that runs past 35 s is cancelled by the session and reported as
  a timeout anyway, so the retry loop lives inside ONE deadline of `overallTimeoutMs`, capped at
  `DEFAULT_OVERALL_TIMEOUT_MS` (30 s, the watchdog's documented client cap: a test may shorten it, no
  caller may lengthen it), computed once at `polish` entry; each attempt receives the remaining time and
  `run`'s connect and read clipping is unchanged. A first attempt that itself timed out leaves no room
  (the Mac retries timeouts under a 60 s per-request ceiling; the phone's watchdog is the stated
  deviation).
- **Cancellation wins.** The session's cancel (#75) interrupts the current attempt; the loop never sleeps
  or retries after a cancel.
- **The key is per attempt, never re-read.** The request carries it; retries reuse the same request.
- **The prompt is process-local.** `:polish` builds it; nothing new crosses AIDL. Word counting for the
  short guard and the bypass is on the cleaned text, the same text the model sees.
- **The guards see the cleaned text, not raw ASR**, as today (`PolishPipeline` passes `cleaned`).
- **Scripts without spaces.** The bypass counts characters for CJK, Thai and Lao (the Mac's rule); the
  word-based guards keep the Mac's behaviour (they under-count such scripts, which only makes them more
  lenient, never a false refusal).

### 5. Prove the high-risk premises
- **The v7 text works on the three providers Android calls.** The Mac validated it on gpt-4o and
  gemini-2.5-flash (91.6 / 90.1 percent on 1,890 cases) and ships it to Claude; Android's Responses API
  takes the same `instructions` string (the parser change from #62 already tolerates a leading reasoning
  item).
- **An echoed `Transcript to clean:` label is rejected, never inserted.** The Mac's finding: the
  `<transcript>` WRAPPER made models echo tags; the plain label did not in its evals (v6 and v7 ship it).
  Android does not rely on that: `isTranscriptOnly` refuses output that begins with the label, so an echo
  becomes MALFORMED_RESPONSE and the deterministic fallback.
- **Retrying a 429 helps.** The Mac's policy is the evidence; Gemini's 429 is excluded because its body
  cannot distinguish a moment's limit from an exhausted quota (Mac `PolishFailureReason` comment).
- **The guards refuse rarely on good output.** The Mac measured the mode collapse at +11 fallbacks in
  1,690 on a 3B local model; on the cloud models the guard set has run since 2026-07 without a reported
  false refusal in the knowledge corpus.

## 3. Design

### The prompt (`providers/ProviderPolishPrompt.kt`)
- `SYSTEM_INSTRUCTION` (removed) becomes the Mac's v7 text, verbatim, as `CLOUD_FIXED_PROMPT_V7`.
- `fun systemInstruction(transcript: String): String`: the unconditional language rule, then the v7 text,
  then the short-input guard when `wordCount(transcript) <= SHORT_INPUT_WORDS` (10). Exactly the Mac's
  strings and order (`CloudFixedPromptBuilder.build`), minus the app hint and the vocabulary block.
- `fun userMessage(transcript: String): String` = `"Transcript to clean:\n\n" + transcript`.
- `requestPlan` uses both for OpenAI, Gemini and Claude; the self-hosted chat body uses the same system
  text and user message (one prompt for every cloud path, as on the Mac's `cloudFixed` (external) family).
- `isTranscriptOnly` also refuses output that BEGINS with the new `Transcript to clean:` label (an
  echoed wrapper is MALFORMED_RESPONSE and the deterministic fallback), with a regression test (grounded
  round 1).

### The too-short bypass (`cleanup/PolishPipeline.kt`)
- A PRIVATE helper in `PolishPipeline` (coverage C1): `tooShortForPolish(cleaned)` is true for 3 words or
  fewer when the text is whitespace-segmented, or fewer than 10 characters when its letters are CJK, Thai
  or Lao (Unicode script blocks, no regex on ASCII). `PolishPipeline.run` returns the cleaned text with a
  new `PipelineOutcome.TOO_SHORT` before calling the model; `PolishReason.resolve` maps it to a new
  `PolishReason.TOO_SHORT` (appended). Nothing user-facing changes: `PolishFailure.from` maps it to null
  like OFF, History labels it "Cleaned up on this phone" as every no-model outcome, and the service's
  "Polish fell back" warning is NOT emitted for it (it is a success bypass; coverage A2).

### The retry loop (`providers/ProviderPolishClient.kt`)
- ONE monotonic deadline of `overallTimeoutMs` (30 s today, the watchdog's documented client cap;
  `PolishWatchdogBudget.kt:6-13`) starts at `polish` entry; validation, request planning, every delay and
  every attempt consume it (coverage D1). The session watchdog stays 35 s.
- `polish` runs the attempt loop itself (one attempt is `attemptOnce`): at most `MAX_RETRIES` (2) retries with delays
  `RETRY_DELAYS_MS` (1000, 3000); another attempt starts whenever its delay completed and positive
  deadline time remains (no second threshold; coverage C2), each attempt's own overall timeout clipped to
  the remaining time.
- `ProviderRetryPolicy.isRetryable(failure, provider)` (pure, tested), precedence in this order
  (grounded round 1): cancellation stops; any non-null signal (KEY_REJECTED, OUT_OF_CREDITS,
  INPUT_TOO_LONG, CONTENT_BLOCKED) stops, even on an otherwise-retryable 429 such as OpenAI's
  `insufficient_quota`; otherwise the OBSERVED status decides (429 retries unless the provider is Gemini;
  5xx retries; every other status stops), whether the body was read or not; otherwise the transport kind
  decides (NETWORK and TIMEOUT retry; NO_API_KEY, INVALID_CONFIGURATION, MALFORMED_RESPONSE,
  RESPONSE_TOO_LARGE, REDIRECT_REJECTED stop). Status controls ELIGIBILITY only and never reclassifies the
  final failure: a stalled 401 body returns `Failure(TIMEOUT, 401, null)` after one request and the
  timeout notice, not KEY_REJECTED, because no complete body was classified. The LAST failure's kind,
  status and signal are what the service receives (#77).
- Cancellation always wins: checked before each delay and each attempt; the delay is a latch the cancel
  hook releases, and every delay's cancel registration is closed in `finally` like the request's
  (coverage A6).
- The assembled UTF-8 body is validated against `MAX_REQUEST_BYTES` before the FIRST attempt (the longer
  prompt lowers the largest transcript that fits; coverage A4).
- Discovery probes are untouched: `requestPlan(probe = true)` keeps "Hi", no system instruction and its
  caps (coverage A5).
- Log per retry: `Cloud retry 1/2 after 1s (status=503)`; content-free.
- The delays are a constructor parameter with the production defaults, so the fake-server tests run in
  milliseconds.

### The output guards (`cleanup/DeterministicCleanup.kt`, `TextSafety`)
- `isSafe(input, output)` keeps its FOUR rules (blank output, control characters, expansion, character
  contraction; coverage B2) and adds the Mac's: expansion beyond max(3 x chars, 200) (reconciled with
  today's 3x + 200), a word-count drop below 40 percent when the input has 10 or more words, and
  `looksLikeQuestion(input) && !looksLikeQuestion(output)` with the Mac's detector (fillers stripped;
  auxiliary starts; wh-word plus auxiliary or "many/much/long/often"; the indirect preambles). One owner,
  one file, the Mac's numbers.
- Rejections stay `MODEL_REJECTED` → `OUTPUT_REJECTED`, the deterministic text is returned and the engine
  label stays deterministic because no model output was used (`PolishPipeline.kt:48-49`,
  `PolishService.kt:341-345`; pinned by a test; coverage A7). The log line names which rule refused.

### Alternatives rejected
- Detecting the language on Android (#3 as written): the Mac dropped it for cloud; no detector exists on
  Android and the unconditional rule needs none.
- Retrying timeouts regardless of budget: the watchdog would cut the retry and report a timeout anyway.
- A separate gate object for the too-short bypass: the pipeline already owns every pre-model exit.
- Editing the v7 wording for Android: the Mac's harness is the only place a wording change is measured.

## 3b. Ownership justification
Every piece lands in the file that already owns its seam. No new coordinator, no new process, no AIDL
change. `PolishGate` (removed) is a pure object beside `PolishPipeline` because the bypass is a pipeline decision,
not a service one.

## 4. Contract deltas
- `ProviderPolishPrompt`: `systemInstruction(transcript)`, `userMessage(transcript)`, the v7 constant;
  `SYSTEM_INSTRUCTION` (removed) deleted (`GR-MIGRATION-COMPLETE`).
- `ProviderPolishClient`: retry loop under the one deadline, `ProviderRetryPolicy`, two constants
  (`MAX_RETRIES`, `RETRY_DELAYS_MS`), one constructor parameter (the delays).
- `PolishPipeline`: `PipelineOutcome.TOO_SHORT`; `PolishReason.TOO_SHORT` appended (AIDL carries the enum
  by name; append-only). The exhaustive arms that gain a member are exactly two: `PipelineOutcome.TOO_SHORT`
  in `PolishReason.resolve`, and `PolishReason.TOO_SHORT` returning null in `PolishFailure.from`.
- `TextSafety.isSafe`: stricter by the Mac's two rules; the expansion rule reconciled.
- No manifest, Room, preference or AIDL signature change.

## 5. End-to-end state and lifecycle audit
| # | Step | State | Exit |
|---|---|---|---|
| 1 | cleaned text of 3 words or fewer (or under 10 CJK chars) | TOO_SHORT, no model | inserted as cleaned; History "Cleaned up on this phone" |
| 2 | 4 to 10 words, cloud | prompt carries the short guard | model returns, guards judge |
| 3 | attempt 1 fails 503 at 0.4 s | delay 1 s, attempt 2 with 28.6 s left | success or attempt 3 after 3 s |
| 4 | attempt 1 times out at the deadline | nothing left | no retry; TIMEOUT reported as today |
| 4b | a 401 whose body read stalled | status policy stops the retry | one attempt; `TIMEOUT` with status 401, the timeout notice |
| 5 | cancel during a sleep | latch released | CANCELLED, no further attempt |
| 6 | 429 from Gemini | not retryable | HTTP_ERROR 429 → RATE_OR_QUOTA notice as today |
| 7 | output 5x the input | expansion guard | last good text, OUTPUT_REJECTED |
| 8 | "should we ship friday" → "We will ship Friday." | question guard | last good text, OUTPUT_REJECTED |
| 9 | Spanish dictation | prompt rule | Spanish back (the Mac's measured behaviour) |

## 6. Downstream consumer matrix
| Producer | Consumer | Today | After | Test |
|---|---|---|---|---|
| `systemInstruction` / `userMessage` | `requestPlan` for four paths | one constant, raw text | assembled per transcript | `ProviderPolishPromptTest`, `ProviderPolishClientTest` (bodies) |
| `PipelineOutcome.TOO_SHORT` | `PolishReason.resolve`, History label, `PolishFailure.from` | n/a | a no-model outcome like OFF | `PolishPipelineTest`, `PolishReasonTest`, `PolishFailureTest` |
| `ProviderRetryPolicy` | `polish` | none | two retries in budget | `ProviderRetryPolicyTest`, `ProviderPolishClientTest` (scripted server) |
| `TextSafety.isSafe` | `PolishPipeline.run` (local and cloud) | four checks | six checks (the Mac's set) | `TextSafetyTest` |

## 7. Failure-mode × caller table
| Failure | Caller sees | Recovery |
|---|---|---|
| 429 (OpenAI, Claude), 5xx, no network, connect refused | up to two retries, then the #77 notice for the LAST failure | none needed |
| 429 Gemini | the notice at once (rate or quota) | as today |
| 401/402/403/404/413, key or content signals | the notice at once | as today |
| timeout with no deadline left | TIMED_OUT notice | as today |
| a body over the request cap after the longer prompt | INVALID_CONFIGURATION before any attempt | as today (BAD_REQUEST notice) |
| guard refusal | OUTPUT_REJECTED notice, last good text inserted | as today |
| too short | nothing: the cleaned text inserted, no notice | none |

## 8. Caller-visible signals audit
No new user-facing surface. Logs: `Cloud retry n/2 after Ns (status=...)`, `Polish guard refused: <rule>
in=<chars>/<words> out=<chars>/<words>`, `Polish bypassed: too short (<n> words)`; all content-free.

## 9. Fallback source-of-truth audit
Unchanged: the last successful text (the deterministic cleanup output) is what every refusal and every
exhausted retry returns; `PolishPipeline` holds it as `fallback`.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/providers/ProviderPolishPrompt.kt`: the v7 text, `systemInstruction`,
  `userMessage`, `SHORT_INPUT_WORDS`, `wordCount`.
- `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt`: `requestPlan` uses the two
  functions; `attemptWithRetry` (removed); `ProviderRetryPolicy`; the constants and parameters.
- `app/src/main/java/com/envi/wispr/cleanup/PolishPipeline.kt`: the private too-short helper, `TOO_SHORT`.
- `app/src/main/java/com/envi/wispr/polish/PolishService.kt`: no "fell back" warning for `TOO_SHORT`.
- `app/src/main/java/com/envi/wispr/polish/PolishReason.kt`: `TOO_SHORT` appended; `resolve` maps it.
- `app/src/main/java/com/envi/wispr/polish/PolishFailure.kt`: `TOO_SHORT` → null in `from`.
- `app/src/main/java/com/envi/wispr/polish/PolishEngineLabels.kt`: the History summary for `TOO_SHORT`
  (the no-model line).
- `app/src/main/java/com/envi/wispr/cleanup/DeterministicCleanup.kt`: `TextSafety` rules and
  `looksLikeQuestion`.
- Tests: `ProviderPolishPromptTest` (new), `ProviderRetryPolicyTest` (new), `ProviderPolishClientTest`
  (retry cases on the scripted server; the four request bodies carry the assembled prompt and the user
  message), `PolishPipelineTest` (bypass cases), `TextSafetyTest` (new; the Mac's cases), `PolishReasonTest`
  and `PolishFailureTest` (the new member), `PolishEngineLabelsTest`.

## 11. Testing
1. **Class.** `ProviderPolishPromptTest`: product outcome; when it fails a Spanish dictation can come back
   in English or a two-word reply grows a sentence. `ProviderRetryPolicyTest` and the client retry cases:
   product outcome; when they fail a momentary 503 drops the polish, or a rejected key is retried three
   times against the user's account. `TextSafetyTest`: product outcome; when it fails an essay replaces a
   "yeah" or a question is answered. `PolishPipelineTest` bypass: product outcome; when it fails a one-word
   dictation is sent to a model.
2. **Revert that turns it red.** Drop the language sentence → the prompt test. Append the short guard at
   11 words → the prompt test. Retry a Gemini 429 → the policy test. Retry a 401 → the policy test. Sleep
   past the budget → the client budget case. Accept a 50 percent word drop → the safety test. Bypass at 4
   words → the pipeline test.
3. **Client cases (scripted server).** 503 then 200 → Success with two requests and the second after
   about the first delay (delays shortened by the constructor); 429 then 200 for OpenAI → Success; 429
   for Gemini → Failure at once, one request; 401 → one request; a 401 whose body stalls past the read
   timeout → one request (status policy wins); three 503s → Failure(HTTP_ERROR, 503) after three requests;
   a cancel during the delay → CANCELLED with one request; a deadline spent by the first attempt → one
   request; a transcript that fits `MAX_PROMPT_CHARS` but whose assembled multibyte body exceeds
   `MAX_REQUEST_BYTES` → INVALID_CONFIGURATION with no request, for all four bodies. One-request cases for
   OpenAI 429 `insufficient_quota` and every other closed signal (signals override a retryable status).
   The four bodies are asserted by EXACT JSON location and role (OpenAI `instructions` and `input`; Gemini
   `systemInstruction` and the user `contents`; Claude `system` and the user message; self-hosted system
   and user chat messages) with exact assembled strings, never a body-wide `contains`. Harness: the
   `ScriptedServer` for sequential retry responses and request counts; the one-connection `TestServer`
   with `chunkDelayMs` for the stalled-body 401, which the scripted server cannot stage. The four request bodies carry the language sentence, the
   v7 text and, for a 7-word transcript, the short guard; the user message starts with `Transcript to
   clean:`.
4. **Not tested.** A live cloud dictation (no key can be typed; owed with #84's run).

### 11.2 Other obligations
| Test | Class | Proves | Revert |
|---|---|---|---|
| `ProviderPolishPromptTest` | product outcome | assembly order, language rule, short guard boundary, user message | see 11.2 |
| `ProviderRetryPolicyTest` | product outcome | the retryable set per provider | see 11.2 |
| `ProviderPolishClientTest` retry cases | contract | attempts, delays, budget, cancel | see 11.2 |
| `TextSafetyTest` | product outcome | expansion, drop, question | see 11.2 |
| `PolishPipelineTest` bypass | product outcome | the two thresholds | see 11.2 |

## 12. Blast radius & rollback
The cloud request bodies (every cloud dictation), the client's attempt loop, the pipeline's gate and the
output guard (local AND cloud: a stricter guard can refuse a local polish it accepted before; the Mac's
numbers bound that). One revert restores all of it; no data changes.

## 13. Ship criteria specific to THIS change
- [x] The four request bodies on the fake server carry the v7 prompt with the language rule, the short
      guard at 10 words and not at 11, and the labelled user message (exact-location assertions; the text
      pinned by the Mac's SHA-256).
- [x] 503-then-200 succeeds with two requests; a Gemini 429 and a 401 make one request each; a cancel
      during the delay makes one (scripted server).
- [x] The Mac's guard cases pass ported; a one-word dictation never reaches the model (pure tests).
- [x] `TOO_SHORT` reaches History as the no-model line and produces no notice (`PolishFailure.from` and
      `PolishReason.resolve` tests; the History label is the deterministic line by construction).
- [ ] On the phone: a one-word "yeah", a 7-word sentence, a question and a paragraph, on the local S1 path
      and on a cloud path with a real key. NOT RUN (§13.1).

## 13.1 Not run on hardware

The founder asked on 2026-09-01 that the phone not be used for testing, and no session can type a vault
key into the emulator (the classifier block recorded on #84), so neither the local S1 dictations nor a live
cloud dictation ran. Both are owed with #84's real-key run, one recipe: on the phone, dictate "yeah", a
seven-word sentence, a question and a paragraph with polish on the phone, then with OpenAI; read History's
polish line for each and logcat for `Polish bypassed: too short`, `Cloud retry`, `Polish guard refused`.
Until then the fake-server and pure tests (381 green, seven receipts red) are the evidence.

## 14. Open questions
None the rules do not answer. Not retrying a timeout that exhausted the budget is the stated deviation.

## 15. Related
#2, #4, #3 (this), #77 (notices), #79 (client), #84 (the live run this is owed with), catalog
`hallucination-protection`, `context-aware-polish`, `cloud-polish`.

---

## Review log

- **Code round 2 (confirming), 2026-09-02, same session:** six CONFIRMED, one residue adopted: tokens shed
  every boundary mark including a single quote (an internal apostrophe stays). VERDICT after the fix:
  treated as CLEAN (a one-character tokenisation residue, no further round).
- **Code round 1, 2026-09-02, same session:** seven findings, all adopted: the deadline starts at `polish`
  entry (validation, assembly and sizing consume it); cancellation is checked before and after every
  attempt, ahead of the deadline and the verdict; kinds a second attempt cannot change (an oversized or
  unreadable body, a redirect, a refused configuration) never retry whatever status came with them; the
  too-short bypass counts code points so supplementary-plane Han is not one token; the question detector
  gains the plain auxiliaries the Mac's list lacked ("was", "were", "had", "shall", "may", "might",
  "must", "am") and sheds quotes from tokens (a stated widening of the Mac's set); the verbatim test pins
  the Mac text's SHA-256 (1382f158…, computed from the Swift source, equal on both sides); the
  pre-attempt size case covers the self-hosted body.
- **Grounded round 2 (confirming), 2026-09-02, same session:** four remnants, applied: hardware UAT is
  Y because the bypass and the guards change the local S1 path (owed with the phone and the real-key run);
  the label premise reads "echoes are rejected"; the gate-object sentence deleted; the stalled 401 exits
  as `TIMEOUT` with status 401 after one attempt. Treated as PROCEED-AS-PLANNED (wording only).
- **Grounded round 1, 2026-09-02, same session:** PROCEED-WITH-REVISIONS, seven, all adopted: the 33 s
  text deleted and the deadline capped at the client's 30 s default; status controls eligibility only and
  never reclassifies (a stalled 401 is `TIMEOUT` with status 401); precedence cancellation, then signal,
  then observed status, then transport kind, with one-request cases per signal; `isTranscriptOnly`
  refuses an echoed `Transcript to clean:`; the four bodies asserted by exact JSON location; the stale
  `PolishGate` (removed) text and "three rules" corrected and the two exhaustive arms named; the scripted server for
  retries and the one-connection server for the stalled body.
- **Coverage round, 2026-09-02, Codex session `01a060a6-a227-7492-be7a-1c2091e08ffc`:**
  PROCEED-WITH-REVISIONS, seven drop-ins, all adopted: one monotonic 30 s client deadline from `polish`
  entry (the watchdog's documented cap; the 33 s figure withdrawn); status policy wins over a failed body
  read; discovery probes untouched; the assembled body validated before the first attempt with near-limit
  multibyte cases; `TOO_SHORT` never warns and every delay's cancel registration is closed; the too-short
  helper private to the pipeline and no second threshold; the engine label after a guard refusal pinned.

## Checklist for the plan author
- [x] Every claim above carries a `file:line` or names the run that produced it.
- [x] §2.5 preceded §3.
- [x] Every backticked identifier greps against the working tree or carries /.
