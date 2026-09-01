# Issue #69 — Polish engine contract: policy on every request, one typed outcome, cancel — 2026-09-01

GitHub issue: `#69`. Tier: REFACTOR (AIDL surface). Status: APPROVED.

Phase 1 of [`plan-2026-09-01-ai-polish-refinement-roadmap.md`](plan-2026-09-01-ai-polish-refinement-roadmap.md).
Every later phase depends on this one.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (unit-tests.xml, codex-review.md, hardware-uat.json; the polish path is a limb
but the change touches the session owner's publication path, so hardware UAT is declared Y) and
`Docs/dev-tooling` (this plan, `.claude/knowledge/architecture.md`, `.claude/knowledge/polish-engines.md`;
cited-symbols).

**PAR rows closed:** `none`. `PAR-066` (isolated process, timeout, cancellation, crash recovery,
unchanged-text fallback) gains its cancellation half here and is closed by phase 3, which adds the deadline.

**Hardware UAT:** Y. On the S26 Ultra with AI Polish saved as Off: start and cancel a dictation so the
engine process is alive; switch to This phone; dictate "um so the meeting moved to thursday" into Samsung
Notes. Success: logcat shows `S1-mini loaded` on the SAME engine pid that served the first session, the
words land in Notes with the filler gone and the sentence polished, and the History card reads
"Polished by S1-mini by Superwhisper (…)". Then switch to a cloud provider with a saved key, dictate again:
the History card names the provider. Then switch back to Off: the History card reads "AI Polish was off".
No app kill between any two steps.

## Preface — User Rubric

1. **Who is this user in this moment?** Diana Foster, senior PM, in Slack on her phone. Thirty seconds ago
   she switched AI Polish from Off to This phone because a customer thread deserves clean sentences. In
   thirty seconds she wants to read back a paragraph that sounds like her, not like a transcript.
2. **Why would they want this?** "I turned it on. It should be on." She would never say "the engine process
   kept its old settings", and today that is what happens to her.
3. **How would they invoke it?** She does not invoke this; it is the contract behind the switch she already
   used. It fires on the next dictation after any change on the AI Polish screen, voluntary, from the app she
   is already in.
4. **What app are they in?** Slack, Notion, Gmail, Samsung Notes. None of them tolerates a paragraph that
   was promised polish and arrived raw; Gmail is where she would notice first.
5. **What is their natural input?** "um so the meeting moved to thursday can you update the deck" ·
   "hey quick one the customer wants the pricing page by friday" · "ok three things first the roadmap second
   the hiring plan third um the offsite" · "sorry running late start without me" · "can we push the demo I
   have a conflict at two".
6. **What does success feel like?** Nothing. She flips the switch, dictates, and the paragraph is polished.
   She never learns that a process boundary existed.
7. **What does wrong-not-broken look like?** She switches to a cloud provider, dictates, and the text comes
   back cleaned by the rules only. She assumes the provider is bad and stops using it.
8. **What would a power user hack around this to get?** Priya Ramachandran would kill the app from Recents
   between switches, which is exactly what the measurement in §1 had to do to prove the defect.
9. **What level of control would they want?** The ladder is unchanged: Off, This phone, a cloud provider.
   This change makes the rung she picked the rung that runs, every time. The one new control, cancellation,
   is the app's, not hers, until phase 3 gives her a visible cancel during processing.

### Cross-persona check

Dr. Elena Vasquez cares most: "This phone" must mean no cloud call, and with the policy carried on every
request the engine cannot act on a stale "cloud" it read an hour ago. Frank Chen and Meera Patel never touch
the screen after setup, so they see nothing. Marcus Weber and Aaron Wu gain from the request identity: a
late result from an abandoned dictation can never be inserted over the current one. No persona disagrees.

---

## 0. TL;DR

The polish engine reads its own settings from a per-process SharedPreferences cache, so a mode change on the
screen never reaches an engine process that is already alive (measured, §1). The fix makes the engine hold no
settings: the session owner latches one immutable policy snapshot at session start and sends it with every
request and with warm-up; the engine answers with one typed outcome carrying the request id, the text, the
engine label, a reason from a closed set, an HTTP status and the latency; a `cancel(requestId)` method exists
and the engine delivers at most once per request; the session owner's fallback becomes the engine's own
deterministic pipeline so there is one fallback owner. REFACTOR because the AIDL surface changes. Proof: the
§1 measurement repeated with the engine process kept alive, plus six JVM tests and one new device case, each with its stated proof or revert receipt.

## 1. Problem

**Measured on the S26 Ultra, 2026-09-01** (session log, evening entry). Saved mode Off; a dictation started and
cancelled; the `:polish` process pid 27263 survived the session. Mode tapped to This phone; the preference
file on disk read `OFFLINE_S1`. A second dictation bound to the same pid, memory stayed at about 90 MB and no
`S1-mini loaded` line appeared. Control: `am kill com.envi.wispr`, and the fresh pid 29843 logged
`S1-mini loaded: GenieX 0.4.0 llama.cpp on gpu; Ready on GPU in 1843ms`.

**Cause.** `ProviderConfigurationRepository` opens SharedPreferences (`ProviderConfigurationRepository.kt:22-25`)
and the engine reads it inside its own process on every call: `PolishService.kt:68-69` (mode and provider
per request), `:119-124` and `:127-133` (`isReady`, `getStatus`), `:137` (`warmUp`) and `:144` (`onCreate`
seeds a model load from the preference). SharedPreferences is cached per process; the `:polish` process keeps
the values it read when it was created. The cleanup switches do not have this defect because they travel with
every request (`IPolishService.aidl:6-12`, `DictationSessionService.kt:519-525`).

**Four contract gaps on the same interface**, all from the runtime audit (scratchpad `polish-runtime-audit.md`,
2026-09-01) and the Codex consult (`roadmap-consult-output.txt.last`):

1. No request identity. `IPolishCallback.onResult(text, engine, latencyMs)` (`IPolishCallback.aidl:4`)
   cannot say which request it answers, so a late result from an abandoned session and the current result
   are indistinguishable. `onError` (`:5`) has no producer; the client handler at
   `DictationSessionService.kt:534-537` is unreachable.
2. Delivery is not once. The callback inside the `try` at `PolishService.kt:105` is retried from the `catch`
   at `:108`; when the first throws because the client is gone, the second throws the same way and escapes
   the executor task.
3. No cancellation. `activeCancellation.set(cancellation)` at `PolishService.kt:63` runs on the binder thread
   before the work is queued, so a second call overwrites the in-flight token; `onDestroy` (`:150`) cancels
   only the newest. The interface has no cancel method (`IPolishService.aidl:5-16`).
4. Two fallback owners. The engine's deterministic result is `PolishPipeline.run` (`PolishService.kt:72`,
   `:78`, `:109`); the session owner's is `regexFallback` (`DictationSessionService.kt:547-554`), which runs
   `DeterministicCleanup` and then `RegexPolisher`, and capitalises sentences and appends a terminal period
   (`RegexPolisher.kt:29-36`). The same dictation therefore ends differently depending on which side failed,
   and the label written to History is the literal `"Regex fallback"` at five sites
   (`DictationSessionService.kt:181`, `:206`, `:516`, `:536`, `:542`), which
   `PolishEngineLabels.historySummary` renders as "Polished by Regex fallback".

## 2. Goals & non-goals

### 2.1 Goals

- G1. After a mode change with the engine process alive, the next dictation runs the new mode on that same
  process. Verified by the hardware recipe in §11.1.
- G2. The engine reads no preference. Verified by grep: `ProviderConfigurationRepository` has no reference
  under `app/src/main/java/com/envi/wispr/polish/`.
- G3. Every outcome names its request; an outcome for a request that is not current is discarded before
  publication. Verified by `PolishRequestLedgerTest` (proposed).
- G4. At most one `onOutcome` per request leaves the engine, through one gated v2 delivery site per request;
  the retained v1 compatibility path separately delivers a deterministic `onResult`. Verified by
  `PolishOnceDeliveryTest` and by reading the v2 site.
- G5. `cancel(requestId)` exists, cancels a cloud request in flight, is called by the session owner on every
  early end, and every outstanding token is cancelled when the engine is destroyed.
- G6. One fallback owner: the session owner's fallback text equals the engine's deterministic text for the
  same input and options. Verified by `DeterministicFallbackTest` (proposed) against a literal.
- G7. The reason set is closed and every producer path maps to a member; `ProviderFailureKind` maps
  exhaustively. Verified by `PolishReasonTest` (proposed).

### 2.2 Non-goals

- A deadline on local generation, a client watchdog, or a user-visible cancel: phase 3.
- Which models are resident and when S1 loads relative to ASR: phase 2. `warmUp` keeps firing at connect.
- History or the completion surface rendering the reason: phase 4. The reason is logged by name only here.
- Splitting `HTTP_ERROR` by status into user reasons: phase 4 and phase 7. The status is carried, not read.
- The cloud client cleanups of phase 5, with one exception stated in §3: `UNSUPPORTED_PROVIDER` is deleted
  here because the exhaustive `when` this plan adds would otherwise carry an arm nothing can reach.
- The AI Polish screen: phase 6. No UI file changes.
- A settings change applying to a session already in progress. The contract is: latched at session start,
  applies from the next session.

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

**The policy.** Written by the main process: `AppViewModel.updateProviderSettings` (`AppViewModel.kt:422-445`)
calls `ProviderConfigurationRepository.setMode` (`ProviderConfigurationRepository.kt:94-98`),
`saveProvider` (`:56-83`, which also forces mode `PROVIDER` at `:76`) or `clearSelection` (`:100-116`, which
forces `OFFLINE_S1` at `:107`); each is a `commit()` to the `envious_wispr_provider_configuration` file.
Read by the `:polish` process (`AndroidManifest.xml:68-71`) through its own `ProviderConfigurationRepository`
instance created in `PolishService.onCreate` (`PolishService.kt:143`) at the five sites in §1. Decided by
the `when (mode)` at `PolishService.kt:71-90`. Nothing else reads it at runtime: the badge and the screen read
`ProviderSettingsUiState` in the main process (`AppShell.kt:300-302`, `PolishScreen.kt`).

```
/usr/bin/grep -rln "ProviderConfigurationRepository" app/src
  → androidTest ProviderConfigurationRepositoryTest.kt, main PolishService.kt, AppViewModel.kt, SettingsActivity.kt
```

**The cleanup precedent.** `AppPreferences.authoritativeState` is collected in the session owner
(`DictationSessionService.kt:243-247`) into `cleanupOptions`; `beginSession` awaits it (`:301`) and latches
`SessionPreferences` (`:319-326`) once per session; `polishAndPublish` reads `sessionPreferences` (`:512`)
and passes the three booleans over the binder (`:519-525`). This is the mechanism the policy joins.

**The result.** `PolishService` calls `callback.onResult` (`PolishService.kt:105`, retried `:108`) →
`DictationSessionService` callback (`:526-531`) → `restoreTakeVocabulary` (`:557-560`) → `publishResult`
(`:562`), which claims `publicationStarted` (`:563`), writes `polishEngine` and `polishLatencyMs` to Room
(`:583-591`) and hands off insertion. History reads the label back through
`PolishEngineLabels.historySummary` (`HistoryScreen.kt:198`).

**The fallback paths.** ASR disconnect (`:180-183`), polish disconnect (`:204-208`), no bound service
(`:516`), the dead `onError` (`:536`), and a thrown binder call (`:542`) all publish `regexFallback` under the
literal `"Regex fallback"`.

**The engine-side model load.** `ensureModelLoaded` (`PolishService.kt:159-189`) is reached from `onCreate`
(`:144`) and `warmUp` (`:137`); `warmUp` is called at connect (`DictationSessionService.kt:195`).

### 2. Find the existing authority before proposing one

| Concern | Existing authority | Callers |
|---|---|---|
| Policy persistence | `ProviderConfigurationRepository` | the three main-process writers above; the engine (to be removed) |
| Per-session settings latch | `SessionPreferences` (`DictationSessionService.kt:101-106`) | `beginSession`, `polishAndPublish`, the fallbacks, insertion |
| Cancellation token | `ProviderCancellation` (`ProviderPolishClient.kt:66-92`) | `PolishService.kt:62-63`, `:81`, `:114`, `:150`; `ProviderPolishClient.polish` |
| Client-side once-publication | `publicationStarted` (`DictationSessionService.kt:109`, `:563`) | every publish and error path |
| Deterministic text | `PolishPipeline.run` (`PolishPipeline.kt:7-22`) | `PolishService.kt:72`, `:73`, `:78`, `:80`, `:109` |
| API key at request time | `SecretStore` via `AndroidKeystoreSecretStore` (`AndroidKeystoreSecretStore.kt:28-30`), file read per `get`, no in-process cache | `ProviderConfigurationRepository.load` (`:40`), `saveProvider` (`:69`), `clearSelection` (`:104`) |
| Request identity across the binder | none | `/usr/bin/grep -rn -i "requestId\|request id\|generationId\|sessionId" app/src/main/java/com/envi/wispr/polish app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` → no hits |
| Engine-side once-delivery | none | the retry at `PolishService.kt:108` is the negative evidence |

`new authority proposed`: `PolishPolicy` (proposed), `PolishOutcome` (proposed), `PolishReason` (proposed),
`PipelineOutcome` (proposed), and a request ledger on the session owner, `PolishRequestLedger` (proposed).

### 3. Read prior attempts and live direction

- Roadmap consult, Codex session `01a05e59-b013-71f0-b3ed-3af5726e5254` (`roadmap-consult-output.txt.last`):
  a bind-time apply-policy call is insufficient because a write between bind and polish leaves the engine on the
  old policy; the policy must accompany each request as one immutable snapshot; the `onCreate` preference
  seed is a trap; `onError` cannot simply be deleted under the append-only rule; the outcome must carry the
  text; `publicationStarted` guards publication, not the losing engine operation. All folded into §3.
- Issue #62 (shipped in PR #66) took 23 Codex rounds because the screen mirrored persisted state; the same
  shape, a second copy of the policy, is what the engine holds today.
- Catalog decisions binding here: 2026-06-30 reject the whole polish and keep the deterministic text that
  entered the polish step; 2026-08-01 on-device and user-selected cloud polish are both valid offerings;
  2026-07-15 the locked completion sentence belongs to phase 4. `polish-fallback` android row: "Off,
  unavailable, failed, timed-out, blank or safety-rejected polish returns the last deterministic text",
  which is the contract G6 makes true on the session-owner side too.
- Session log 2026-09-01 (evening): the measurement, the restored phone state, and the founder's instruction
  to run the roadmap end to end.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

| Boundary | Today | After this change |
|---|---|---|
| main ↔ `:polish` preference cache | engine reads a stale cache | engine reads no preference; policy arrives on each call |
| Session start ↔ settings write | none: engine reads at call time | policy latched in `beginSession` beside `SessionPreferences`; a later write applies to the next session |
| Bind ↔ first request | `onCreate` may load S1 before the client's policy exists | `onCreate` loads nothing; `warmUpWithPolicy(policy)` at connect decides |
| Process reuse across sessions | measured stale | each session's requests carry their own snapshot |
| Live service ↔ dead service | `onServiceDisconnected` publishes the regex fallback | same path, deterministic pipeline, reason `SERVICE_DIED` (proposed) |
| Current request ↔ stale completion | indistinguishable | request id checked before publication |
| Cancel ↔ completion | no cancel | `cancel(requestId)` cancels the token; a cloud call aborts (`ProviderPolishClient.kt:145-148`); a local generation cannot be interrupted (`S1GenieXRuntime.kt:92-98`), so its outcome arrives late with reason `CANCELLED` or `POLISHED` (proposed) and is discarded by id. The window on the engine's compute is phase 3's; the window on the user's text is closed here |
| Engine `onDestroy` ↔ queued work | cancels one token | cancels every token, then closes the runtime on the worker |
| Session owner `onDestroy` (`DictationSessionService.kt:984`) | sets `publicationStarted` | also calls `cancelOpenPolishRequest()` as it begins |
| Key changed mid-session | engine reads the key per call | unchanged: the engine reads the key per call from the Keystore-backed file, which is not cached per process; a key is a credential, not policy |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| SharedPreferences is cached per process | the §1 measurement with its control run |
| The production client and service ship in one APK; the three instrumentation tests ship in a separately installed APK that binds the same service | `AndroidManifest.xml:68-71` `exported="false"`; `/usr/bin/grep -rln "IPolishService"` → `DictationSessionService.kt`, `PolishService.kt`, and three `androidTest` files; `device-testing.md` RULE: drive-the-instrumented-tests-with-am-instrument-not-gradle installs the two APKs separately |
| `isReady` and `getStatus` have no production caller | the grep above restricted to `app/src/main`: `DictationSessionService.kt` calls `warmUp()` (`:195`) and `polish` (`:519`) only; `isReady`/`status` appear in `VoicePipelineDeviceTest.kt:97,100` and `PolishServiceDeviceTest.kt:52,55` |
| `RegexPolisher` has one production caller | `/usr/bin/grep -rn "RegexPolisher" app/src/main/java` → `DictationSessionService.kt:48` (import) and `:553` |
| `SecretStore.get` is process-safe | `AndroidKeystoreSecretStore.kt:28-30`: `readAll()` reads the file on every call; no field caches values |
| `UNSUPPORTED_PROVIDER` has no producer | cloud audit §B: zero construction sites; re-checked `/usr/bin/grep -rn "UNSUPPORTED_PROVIDER" app/src` → the declaration only |
| `PolishPipelineResult` consumers | `PolishService.kt` only in main sources; tests to be re-grepped at build |
| The problem-only consult was run | the roadmap consult above traced current reality and named the traps; it is cited rather than re-run because the phase 1 design has not changed since |

## 3. Design

### 3.1 The policy snapshot

`PolishPolicy` (proposed), a sealed hierarchy in `polish/`, hand-written Parcelable (no parcelize plugin in
`app/build.gradle.kts:1-6`; no Parcelable exists in the app today):

- `Off`
- `LocalS1` (proposed)
- `Cloud(provider: Provider, model: String, endpoint: String?, protocol: SelfHostedProtocol)`
- `CloudUnconfigured` (proposed): the user chose a provider mode but no valid selection exists
  (`ProviderConfigurationRepository.load` returned null).

Built in the main process by `ProviderConfigurationRepository.loadPolicy()` (proposed): ONE read of the
preference map (`SharedPreferences.getAll()` (external), which copies the map under the preference lock, so
a commit between two `getString` calls cannot produce a policy assembled from two states), decoded by a pure
`PolishPolicy.from(values)` (proposed) that the JVM tests exercise, with the credential never read; any
read or decoding failure returns `Off`. The API key is NOT in the snapshot: the engine asks `SecretStore.get(provider)` at request
time, which reads the encrypted file each call. The parent `CREATOR` (external) writes a one-byte tag then the fields;
`writeToParcel` (external) and `createFromParcel` (external) sit side by side in one file so the order cannot drift apart.

### 3.2 The binder surface

```
interface IPolishService {
    // v1, kept declared: polish, isReady, getStatus, warmUp (transaction codes 1-4)
    void polishRequest(long requestId, String rawText,
                       boolean removeFillers, boolean spokenEmoji, boolean spokenPunctuation,
                       in PolishPolicy policy, IPolishCallback callback);
    void warmUpWithPolicy(in PolishPolicy policy);
    oneway void cancel(long requestId);
    boolean isLocalModelReady();
    String localModelStatus();
}
interface IPolishCallback {
    // v1, kept declared: onResult, onError
    void onOutcome(in PolishOutcome outcome);
}
```

`polishRequest` (proposed), `warmUpWithPolicy` (proposed), `cancel`, `isLocalModelReady` (proposed) and
`localModelStatus` (proposed) are APPENDED. `isReady` and `getStatus` answered a policy question the engine
no longer knows; the only readiness the engine owns is its model. `cancel` is `oneway` (external) so a terminal
transition never blocks on the engine.

**The v1 transactions stay declared and safely implemented** (`aidl-is-append-only`, adjudicated by Codex
twice, §14.1): `polish` runs the deterministic pipeline and answers `onResult` with the `DETERMINISTIC`
label; `warmUp` is a no-op; `isReady` and `getStatus` delegate to the local-model answers. Every production
and test caller migrates to the appended methods in this change, so nothing in this repository calls v1
after it; their removal is its own interface-version migration, filed as a follow-up issue when this ships.
The client-side `onError` stays implemented as a deterministic fallback with reason `CALL_FAILED`.

### 3.3 The outcome

`PolishOutcome` (proposed), a Parcelable data class: `requestId: Long`, `text: String`, `engine: String`
(the History label, unchanged vocabulary), `reason: PolishReason`, `statusCode: Int` (0 when none),
`latencyMs: Long`.

`PolishReason` (proposed), an enum, closed, one member per producer path:

| Member | Produced by |
|---|---|
| `POLISHED` | pipeline `usedModel` |
| `OFF` | policy `Off` |
| `NO_SPEECH` | blank raw text (`PolishService.kt:57-60` today) |
| `EMPTY_AFTER_CLEANUP` (proposed) | pipeline: cleaned text blank (`PolishPipeline.kt:16`) |
| `CLEANUP_RECOVERED` (proposed) | pipeline: `DeterministicCleanup` returned the original (`PolishPipeline.kt:15`) |
| `LOCAL_NOT_READY` (proposed) | policy `LocalS1`, model not loaded (`PolishService.kt:74`) |
| `LOCAL_FAILED` (proposed) | S1 returned an `ERROR:` result or threw (`PolishService.kt:198-201`) |
| `OUTPUT_REJECTED` (proposed) | model text blank after `</think>` or unsafe (`PolishService.kt:203-208`, `PolishPipeline.kt:18-20`) |
| `CLOUD_NOT_CONFIGURED` (proposed) | policy `CloudUnconfigured`, or `ProviderFailureKind.INVALID_CONFIGURATION` |
| `NO_API_KEY` | `ProviderFailureKind.NO_API_KEY` |
| `NETWORK` | `ProviderFailureKind.NETWORK` |
| `TIMEOUT` | `ProviderFailureKind.TIMEOUT` |
| `CANCELLED` | `ProviderFailureKind.CANCELLED`, or a queued request whose token was cancelled before it ran |
| `HTTP_ERROR` | `ProviderFailureKind.HTTP_ERROR`, status carried |
| `MALFORMED_RESPONSE`, `RESPONSE_TOO_LARGE`, `REDIRECT_REJECTED` | the same-named kinds |
| `UNEXPECTED` (proposed) | the engine's catch |
| `SERVICE_UNAVAILABLE` (proposed), `SERVICE_DIED`, `CALL_FAILED` (proposed) | session-owner side only: never bound, disconnected mid-polish, binder call threw |

The mapping from `ProviderFailureKind` is an exhaustive `when` with no `else`
(`kotlin-patterns.md` RULE: exhaustive-when-no-else), which is why `UNSUPPORTED_PROVIDER` is deleted here.

`PipelineOutcome` (proposed), an enum on `PolishPipelineResult`, lets the engine map without guessing:
`CLEANUP_RECOVERED`, `EMPTY_AFTER_CLEANUP`, `NO_MODEL` (proposed), `MODEL_DECLINED` (proposed) (lambda returned null or threw),
`MODEL_REJECTED` (proposed) (safety), `MODEL_ACCEPTED` (proposed). `usedModel` and `recovered` stay for the existing consumers.
Each model adapter records a typed attempt reason before returning to `PolishPipeline`: a local exception
or an `ERROR:` result → `LOCAL_FAILED`; a provider failure → its exhaustively mapped reason and status; a
successful call whose output is blank or unsafe → `OUTPUT_REJECTED`. `MODEL_DECLINED` or `MODEL_REJECTED`
with no recorded failure maps to `OUTPUT_REJECTED`, never to an inferred cloud kind.

### 3.4 Engine internals

- `PolishRequestRegistry` (proposed), a pure class the engine owns: a `ConcurrentHashMap` (external) from
  request id to `ProviderCancellation`, registered with `putIfAbsent` (external) (a colliding id is refused and the
  request answers `UNEXPECTED` rather than sharing a token) and removed with `remove(requestId, token)` so
  an older request's `finally` cannot remove a newer token; `cancel(requestId)` cancels and removes exactly
  that token, is a no-op on an unknown or delivered id, and `cancelAll()` (proposed) is what `onDestroy` calls before
  queuing `s1Runtime.close()` and shutting the executor down, as today (`PolishService.kt:149-157`). The
  worker checks the token before starting and answers `CANCELLED` for a request cancelled while queued.
- One gated v2 `onOutcome` delivery site per request (the retained v1 path separately delivers a deterministic `onResult`): the registry's per-request once-gate (an `AtomicBoolean` (external) created with the
  token, not per call) guards a private `deliver(callback, outcome)` wrapped in `runCatching`; the catch
  path builds its outcome and calls the same function, so the second-throw escape at `PolishService.kt:108`
  cannot recur.
- The provider comes out of the `when` as part of a small result (`text`, `engine`, `reason`, `status`), so
  `selectedProvider!!` (`PolishService.kt:97`) is gone.
- `DebugLogger.error(TAG, "Polish failed", exception)` passes the throwable (`DebugLogger.kt:142`).
- `onCreate` constructs `AndroidKeystoreSecretStore` only. No `ProviderConfigurationRepository` in the file.
- The single worker thread stays. Re-entrancy policy is phase 2 and phase 3's.

### 3.5 Session-owner internals

- `SessionPreferences` gains `policy: PolishPolicy` (default `Off`), read in `beginSession` on
  `Dispatchers.IO` through `ProviderConfigurationRepository(applicationContext).loadPolicy()` after the
  existing awaits (`DictationSessionService.kt:300-304`), latched at `:319-326`.
- `onServiceConnected` calls `warmUpWithPolicy(sessionPreferences.policy)`; v1 `warmUp()` stays declared and is a no-op.
- `PolishRequestIdSource` (proposed), process-wide: an `AtomicLong` (external) seeded from elapsed realtime
  that returns `max(previous + 1, clock)` atomically, so two session-owner instances sharing one engine
  process, or two calls in one clock tick, never receive the same id. The clock is injected for JVM tests.
- `PolishRequestLedger` (proposed), a small pure class over that source: `open(): Long` mints and holds the
  open id; `accepts(id): Boolean` compare-and-swaps exactly that id to null, so it is true once;
  `close(): Long?` atomically swaps the open id to null and returns it. The callback's `onOutcome` (proposed)
  asks the ledger before `publishResult`; a rejected outcome is logged by id and reason name and dropped. The
  ledger is what makes `publicationStarted` a second guard rather than the only one.
- `deterministicFallback(raw, take)` (proposed) replaces `regexFallback`: `PolishPipeline.run(restored,
  take.cleanup).text` then `restoreTakeVocabulary`, published under `PolishEngineLabels.DETERMINISTIC` with a
  logged reason. `RegexPolisher` and `RegexPolisherTest` are deleted; the replacement test is
  `DeterministicFallbackTest`.
- `cancelOpenPolishRequest()` (proposed): `ledger.close()` first, then `cancel(id)` for the returned id
  inside `runCatching`, so cancel versus outcome is first-wins on the ledger. `finishSession` (`:808`),
  `showError` (`:791`) and `onDestroy` (`:984`) call it as the terminal transition BEGINS, before the wait for
  pending History work (`:812-816`) that would otherwise delay the cancel; `unbindPipelineServices`
  (`:924-935`) calls it again as an idempotent backstop.

### 3.6 Alternatives rejected

- **Re-read preferences in the engine per call.** No public API forces a fresh read of a cached
  SharedPreferences in another process; a `MODE_MULTI_PROCESS` (external) flag is deprecated and documented as
  unreliable. And the engine would still own settings.
- **Push the policy once at bind, through an apply-policy call.** Rejected by the consult: a write between bind and
  polish is a stale window, and a shared mutable field can be replaced under a queued request.
- **Flat primitives instead of Parcelables.** Eleven positional parameters with string-typed enums parsed
  by `valueOf` at the engine, which is the plausible-default trap the repository code already has
  (`ProviderConfigurationRepository.kt:28`). A sealed Parcelable is typed at both ends.
- **Keep `RegexPolisher` by moving it into the pipeline.** That changes Off-mode text for every user
  (terminal periods, capitalisation) and is a product change outside a contract phase.

## 3b. Ownership justification

`PolishPolicy`, `PolishOutcome`, `PolishReason` live in `polish/` because AIDL parcelables must sit in the
interface's package and both processes compile them; `loadPolicy()` lives on `ProviderConfigurationRepository`
because it already owns every key the policy is made of; the latch lives in `DictationSessionService` beside
`SessionPreferences` because that is the existing per-session settings owner and the precedent the consult
named. The alternative, a new policy-provider object, would be a second reader of the same file with
no caller but the session owner. `DictationSessionService.kt` is a standing extraction target
(`architecture-rules.md` RULE: keep-central-types-thin, 1043 lines today); this change adds the ledger as its
own file and removes `regexFallback`, so the file does not grow.

## 4. MANDATORY — contract deltas

| Type | Delta | What it now means to consumers |
|---|---|---|
| `IPolishService` | appends `polishRequest` (id, flags, policy, callback), `warmUpWithPolicy`, `cancel`, `isLocalModelReady`, `localModelStatus`; v1 kept, safely implemented, unused in this repository | the engine acts only on the policy it was handed; readiness is about the local model alone |
| `IPolishCallback` | appends `onOutcome(PolishOutcome)`; `onResult`/`onError` kept for v1 | one delivery, identified, with a reason |
| `PolishPolicy` | new | an immutable snapshot of what the user chose, taken at session start |
| `PolishOutcome` | new | the whole answer: text, label, reason, status, latency, for one request |
| `PolishReason` | new | closed vocabulary; phase 4 renders it, phase 7 splits `HTTP_ERROR` |
| `PolishPipelineResult` | gains `outcome: PipelineOutcome` | consumers can name why the model was not used without inferring it from two booleans |
| `ProviderFailureKind` | loses `UNSUPPORTED_PROVIDER` | no consumer arm for a value nothing produces |
| `ProviderConfigurationRepository` | gains `loadPolicy()` | the one composition of mode and selection |
| `SessionPreferences` | gains `policy` | latched with the rest of the take's settings |
| `PolishEngineLabels` | no change; the `"Regex fallback"` literal stops being written | rows already holding it keep rendering through the `else` branch |

## 5. MANDATORY — end-to-end state and lifecycle audit

Populations enumerated from the producing code:

| Population | Members | Disposition |
|---|---|---|
| Engine preference reads | `PolishService.kt:68`, `:69`, `:119`, `:122`, `:127`, `:130`, `:131` (calls `isReady`, so re-reads mode and configuration), `:137`, `:144` | all removed |
| Engine callback invocations | `:58`, `:105`, `:108` | one gated v2 `onOutcome` site per request; the retained v1 path separately delivers a deterministic `onResult` |
| Cancellation token writers | `:63` (set), `:114` (clear), `:150` (cancel) | map put, map remove, cancel-all |
| Executor tasks | `polish` body `:64-116`, `ensureModelLoaded` `:163-188`, `onDestroy` close `:151-154` | unchanged count |
| Session-owner publish sites | `:179`, `:204`, `:516`, `:527`, `:536`, `:542` | four fallbacks through `deterministicFallback`; v2 outcomes through the ledger; v1 `onResult` and `onError` at `:527` and `:536` stay implemented as deterministic fallbacks, unused by current callers |
| Session-owner ends that must cancel | `showError` `:791-801`, `cancelRecording` `:759-780`, `cancelStarting` `:783-789`, `finishSession` `:808-822`, `onDestroy` `:984+` | `finishSession`, `showError` and `onDestroy` call `cancelOpenPolishRequest()` as their transition begins; `cancelRecording` and `cancelStarting` end through `finishSession`; `unbindPipelineServices` is an idempotent backstop, not the primary consumer |
| AIDL clients | `DictationSessionService.kt`, `VoicePipelineDeviceTest.kt`, `PolishServiceDeviceTest.kt`, `PolishServiceCleanupOptionsDeviceTest.kt` | all migrated in this change |

Async edge cases (`code-design-rules.md` RULE: async-edge-case-enumeration):

| Class | Case | Answer |
|---|---|---|
| Interrupted | `:polish` dies mid-request | `onServiceDisconnected` → `deterministicFallback`, reason `SERVICE_DIED`; the ledger closes |
| Interrupted | session owner dies mid-request | engine's later delivery throws into `runCatching`; no retry; token removed in `finally` |
| Deleted | provider removed between sessions | next session's snapshot is `Off` or `LocalS1` (`clearSelection` forces `OFFLINE_S1`) |
| Mutated | provider metadata or mode changes during a session | the immutable policy stays in force until the next session, by contract |
| Mutated | the selected provider's key is replaced or removed during a session | the request observes the credential at polish time because the key is deliberately outside the snapshot (`ProviderConfigurationRepository.kt:40`, `:100-104`); a missing key returns `NO_API_KEY` |
| Concurrent | two `polish` calls queued (a second session before the first's unbind completes) | each has its own id and token; the first's outcome reaches the first session owner's closed ledger and is dropped |
| Concurrent | a second `DictationSessionService` instance while the same `:polish` process survives | request ids come from the process-wide `PolishRequestIdSource`: one `AtomicLong` returns `max(previous + 1, clock)` atomically, so a new instance's first id cannot collide with an old instance's token in the engine-wide map; raw `SystemClock.elapsedRealtimeNanos()` (external) is never used as identity |
| Concurrent | `cancel` races `onOutcome` | the ledger is first-wins: `close()` runs before the remote `cancel` call, so an outcome cannot be accepted while cancellation is in progress; an outcome accepted first makes the later `cancel` a no-op on the engine |
| Absent | `bindService` returned true but `onServiceConnected` has not arrived when ASR finishes (`DictationSessionService.kt:351-354`, `:514-517`) | `polishService` is null, so `deterministicFallback` publishes with reason `SERVICE_UNAVAILABLE`; a later connection cannot change the published result |
| Absent | `CloudUnconfigured` | fail open to deterministic text, reason `CLOUD_NOT_CONFIGURED` |
| Absent | key missing for `Cloud` | client returns `NO_API_KEY`; deterministic text |
| Stale | outcome for a closed request | dropped by the ledger before `publicationStarted` is consulted |

Cancellation population, request state × cancel event (`ProviderPolishClient.kt:66-92`, `PolishService.kt:62-64`, `:149-155`):

| Request state | first `cancel` | repeated `cancel` | `onDestroy` cancel-all |
|---|---|---|---|
| queued | token cancelled; the worker skips the body and delivers `CANCELLED` | no-op | same as first |
| cloud running | connection disconnected, future cancelled; client returns `CANCELLED` | no-op | same |
| local running | token cancelled but generation runs to its end (phase 3); outcome delivered late and discarded by id | no-op | runtime closed after the generation, as today |
| delivered | token already removed; no-op | no-op | no-op |
| unknown id | no-op | no-op | n/a |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| appended `polishRequest(...)` | `DictationSessionService.polishAndPublish` | calls v1 `polish` with flags and a callback | calls `polishRequest` with id, flags, policy, callback; v1 `polish(...)` unchanged | yes | compile, hardware recipe |
| appended `polishRequest(...)` | three `androidTest` files | call v1 `polish` | call `polishRequest` with `Off`/`LocalS1` policies | yes | `assembleDebugAndroidTest` compiles; run on the phone is out of this phase's reach (`command-safety.py` denies connected tests off the emulator, and the emulator has no S1 model) |
| `onOutcome` | session owner callback | `onResult` publishes | ledger check, then publish | yes | `PolishRequestLedgerTest` |
| `warmUpWithPolicy(policy)` | `onServiceConnected` | `warmUp()` | `warmUpWithPolicy(sessionPreferences.policy)` | yes | hardware recipe (`S1-mini loaded` on the same pid) |
| `cancel` | `finishSession`, `showError`, `onDestroy` (primary, at transition start) and `unbindPipelineServices` (backstop) | none | close the ledger, then cancel the returned id | yes | read; cloud abort observable in logcat as `CANCELLED` |
| `PolishPipelineResult.outcome` | `PolishService` | reads `usedModel` | reads `outcome` | yes | `PolishReasonTest` |
| `PolishPipelineResult.outcome` | `DeterministicCleanupTest` (the only test reading `PolishPipeline.run`; `/usr/bin/grep -rn "PolishPipeline" app/src/test` → that file alone) | reads `usedModel` | unchanged fields keep compiling | no | unit tests |
| `ProviderFailureKind` | `ProviderPolishClient` producers, the engine's failure branch (`PolishService.kt:70`, `:81-92`), `PolishReason.from`, `ProviderPolishClientTest` | the engine keeps only the kind | exhaustive mapping preserves kind and status in the outcome | yes | compile plus `PolishReasonTest` |
| the new `IPolishService.Stub` surface | `PolishService` | implements `polish`, `isReady`, `getStatus`, `warmUp` | implements the replacement surface | yes | compile, device tests |
| `isLocalModelReady`, `localModelStatus` | `VoicePipelineDeviceTest.kt:97,100`, `PolishServiceDeviceTest.kt:52,55` | call `isReady`, `status` | call the local-only replacements | yes | `assembleDebugAndroidTest` |
| `SessionPreferences.policy` | `beginSession` (`:319-326`), `onServiceConnected` (`:192-196`), `polishAndPublish`, the ASR-disconnect and polish-disconnect fallbacks (`:179-205`) | no policy field | one immutable policy latched, warmed, and carried through every outcome and fallback path | yes | `PolishPolicyTest` plus the hardware recipe |
| the `"Regex fallback"` literal | `publishResult` → Room → `PolishEngineLabels.historySummary` (`HistoryScreen.kt:198`) | literal persisted as the engine label | new rows carry `DETERMINISTIC`; rows already holding the literal keep rendering through the `else` branch ("Polished by Regex fallback"), no migration | producers yes | `PolishEngineLabelsTest` unchanged; History UAT |
| `polishEngine` column | History | renders labels | unchanged | no | `PolishEngineLabelsTest` |
| Catalog | `polish-fallback`, `ai-polish` android rows | describe fallback | add the contract sentence at wind-down (`data/036-*.sql`) | docs | catalog rebuild |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Engine not bound | `bindService` false | `polishAndPublish` | text inserted, cleaned by rules; History "Cleaned up on this phone" | row finalised, engine `DETERMINISTIC` | none |
| Engine dies mid-polish | process kill | `onServiceDisconnected` | same as above | same | none |
| Cloud call fails (any kind) | `ProviderPolishClient` | engine | same as above; phase 4 adds the sentence | same; reason logged by name | none (phase 8) |
| Local model not ready | `ensureModelLoaded` still loading | engine | same as above | same | next session |
| Outcome for a stale request | late engine delivery | ledger | nothing; current text unaffected | none | n/a |
| Cancelled request completes | `cancel` after work started | ledger | nothing | none | n/a |
| Policy read fails at session start | preference file unreadable | `beginSession` | `loadPolicy()` falls back to `Off`; text cleaned by rules | `OFF` label | next session |
| Engine binding pending | `bindService` succeeded, callback not yet arrived | `polishAndPublish` | text cleaned by rules; `SERVICE_UNAVAILABLE` logged | `DETERMINISTIC` | next session |
| Local generation throws or returns `ERROR:` | S1 path (`PolishService.kt:191-201`) | engine | text cleaned by rules; `LOCAL_FAILED` logged | `DETERMINISTIC` | next session |
| Model output blank or unsafe | local or cloud model (`PolishPipeline.kt:17-21`, `PolishService.kt:203-208`) | engine | text cleaned by rules; `OUTPUT_REJECTED` logged | `DETERMINISTIC` | next session |
| `warmUp` binder call fails | service connection (`DictationSessionService.kt:192-196`) | `onServiceConnected` | recording continues; the request later reports `LOCAL_NOT_READY` and falls back | `DETERMINISTIC` | next session |

The user-facing sentences are unchanged in this phase and stay within `content-brand.md`; the locked
sentence and the macOS reason copy are phase 4's.

## 8. MANDATORY — caller-visible signals audit

| Field | Signal | Reader |
|---|---|---|
| `PolishOutcome.requestId` (proposed) | identity: equals the ledger's open id or the outcome is dropped | session owner |
| `PolishOutcome.statusCode` | `0` means absent; any other value is the provider's HTTP status | phase 4 and 7; logged here |
| `PolishOutcome.text` blank | `publishResult` keeps its existing rule: blank text publishes the raw transcript under `RAW_FALLBACK` (`DictationSessionService.kt:567-568`) | `publishResult` |
| `PolishOutcome.engine` | the History label vocabulary of `PolishEngineLabels` or an engine display name | Room, History |
| `PolishReason.CANCELLED` | never published: a cancelled request has no open ledger id | none |
| `PolishPolicy` type | which engine path runs; `CloudUnconfigured` is fail-open, not an error | engine |
| `isLocalModelReady` false | model absent OR loading; `localModelStatus` says which | device tests only |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| every session-owner fallback | `PolishPipeline.run(restored, take.cleanup).text` then vocabulary restore | the shared pipeline | it is the same function the engine's `Off` path runs, so both sides agree | non-blank, else `publishResult` publishes the raw transcript | raw transcript under `RAW_FALLBACK` | `publishResult` |
| every engine fallback | `PolishPipeline.run(raw, options)` without a model | same | unchanged from today | same | same | engine |

## 10. File-by-file changes

- `app/src/main/aidl/com/envi/wispr/polish/IPolishService.aidl`, `IPolishCallback.aidl`: the §3.2 surface;
  new `PolishPolicy.aidl` and `PolishOutcome.aidl` parcelable declarations.
- `app/src/main/java/com/envi/wispr/polish/PolishPolicy.kt` (proposed), `PolishOutcome.kt` (proposed),
  `PolishReason.kt` (proposed): the types, with `PolishReason.from(ProviderFailureKind)` (proposed).
- `app/src/main/java/com/envi/wispr/polish/PolishService.kt`: §3.4.
- `app/src/main/java/com/envi/wispr/polish/RegexPolisher.kt`: deleted.
- `app/src/main/java/com/envi/wispr/cleanup/PolishPipeline.kt`: `PipelineOutcome`.
- `app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`: `loadPolicy()`.
- `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt`: delete `UNSUPPORTED_PROVIDER`.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: §3.5.
- `app/src/main/java/com/envi/wispr/polish/PolishRequestRegistry.kt` (proposed) and `PolishRequestIdSource.kt` (proposed); `app/src/main/java/com/envi/wispr/ui/PolishRequestLedger.kt` (proposed).
- `app/src/test/java/com/envi/wispr/polish/`: `PolishPolicyTest` (proposed), `PolishReasonTest`,
  `DeterministicFallbackTest`, `PolishOnceDeliveryTest`, `PolishRequestRegistryTest`; `RegexPolisherTest`
  deleted. `app/src/test/java/com/envi/wispr/ui/PolishRequestLedgerTest.kt`. One new case in
  `app/src/androidTest/java/com/envi/wispr/providers/ProviderConfigurationRepositoryTest.kt`.
- `app/src/androidTest/...`: the three device tests migrated.
- `.claude/knowledge/architecture.md` FACT: processes-and-their-contract: "cloud polish calls" move from
  main to `:polish`, which is where they already run (`PolishService.kt:81`); FACT: the-text-pipeline gains
  the policy sentence. `.claude/knowledge/polish-engines.md`: one FACT for the contract. No `AGENTS.md`
  exists in this repository. `.claude/rules/architecture-rules.md` RULE: aidl-is-append-only gains one
  sentence naming the instrumentation APK as the separately installed client the rule protects.

## 11. Testing

1. **Class of every new test.** Product Outcome: `PolishRequestLedgerTest` (when it fails, a late result
   from an abandoned dictation is inserted over the current one), `PolishOnceDeliveryTest` (the same text
   published twice, or never), `PolishRequestRegistryTest` (a cancel that stops the wrong request, or none),
   `DeterministicFallbackTest` (different text depending on which side failed), the new
   `ProviderConfigurationRepositoryTest` case (the mode the user picked is not the mode that runs). Drift
   Guard, declared as such: `PolishPolicyTest` (the pure mapper) and `PolishReasonTest`.
2. **What revert would turn it red?** The last column of §11.2, each performed once and seen red.
3. **Deliberately not tested.** The Parcel round trip on the JVM (no framework); it is exercised by every
   device test and by the hardware recipe. Engine-side once-delivery under a throwing binder (needs a dead
   client process; the one gated v2 `onOutcome` site is read instead, beside the retained v1 `onResult` path). Local-generation cancellation (phase 3).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (polish), touching the heart's publication path.
- **Recipe:** the §1 measurement as a recipe, added to `device-testing.md`: saved Off → dictate and cancel →
  `pidof` (external) the engine → switch to This phone → dictate into Samsung Notes → `logcat -s PolishService` shows
  `S1-mini loaded` on the same pid → History card names S1-mini → switch to a cloud provider → dictate →
  History names the provider → switch to Off → dictate → "AI Polish was off".
- **Expected observation:** the pid equality is the oracle; a fresh pid would prove nothing. The buggy code
  prints no `S1-mini loaded` line at all in step three, so the line's presence on the kept pid is the
  discriminator.
- **Phone state to restore afterwards:** AI Polish mode back to Off (the founder's saved value today) and
  the engine process ended; nothing else changes.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `PolishRequestLedgerTest` | Product Outcome | wrong id rejected, the open id accepted once, close-versus-accept first-wins, and two ledgers given the same clock reading receive distinct ids | use raw clock values, remove the id compare-and-swap, or split read from close |
| `PolishOnceDeliveryTest` (proposed) | Product Outcome | two delivery attempts for one request invoke the callback once, including when the first callback throws | remove the per-request gate or recreate it per call |
| `PolishRequestRegistryTest` (proposed) | Product Outcome | queued, running, delivered, repeated-cancel, unknown-id and cancel-all states cancel only the exact token and remove only that token | overwrite on register, unconditional remove, or skip cancel-all |
| `DeterministicFallbackTest` | Product Outcome | the production `deterministicFallback` and the engine's `Off` path both return the literal `"hello world"` for `"hello world"` with default options (`RegexPolisher` would add a capital and a period) | restore `RegexPolisher` or bypass the shared helper |
| `PolishPolicyTest` | Drift Guard | every mode × {no values, valid values} maps exactly, `PROVIDER` with no valid selection giving `CloudUnconfigured` | return `Cloud` on a null selection |
| `ProviderConfigurationRepositoryTest` (existing `androidTest` rig, new case) | Product Outcome | one stored preference snapshot produces the exact policy, and an unreadable store produces `Off` | assemble the policy from separate reads or bypass `loadPolicy` |
| `PolishReasonTest` | Drift Guard | every `ProviderFailureKind` and `PipelineOutcome` maps exactly and the status survives | map `NO_API_KEY` to `NETWORK` or drop the status |
| `scripts/measure-tests.sh` | count | the whole suite, count reported | n/a |
| three device tests | compile only this phase | the new surface is what the tests bind | n/a |

## 12. Blast radius & rollback

- **Touched:** `app/src/main/aidl` (polish), `polish/`, `cleanup/PolishPipeline.kt`, `providers/` (two
  files), `ui/DictationSessionService.kt`, one new `ui/` file, tests, two knowledge files.
- **Not touched:** ASR and audio AIDL and services, the accessibility and insertion path, Room and its
  schema, every UI screen, `AppViewModel`, `PolishEngineLabels`, `S1GenieXRuntime`, the model delivery code.
- **Rollback:** revert the single merge commit. No schema change, no preference key change, no model file
  change, so an older build reads the same phone state.

## 13. Ship criteria specific to THIS change

- [ ] With the engine process kept alive across a mode change, the next dictation runs the new mode on the
      same pid (hardware recipe, all three modes, one run, no app kill).
- [ ] No preference read remains under `polish/`; `RegexPolisher` and the `"Regex fallback"` literal are gone.
- [ ] Six JVM tests green with their reverts performed once and seen red; the new `ProviderConfigurationRepositoryTest` case compiles; suite count reported.
- [ ] Codex explicit all-clear on this plan (coverage round, then grounded rounds) and on the code, with a
      confirming re-run; the §14 tension adjudicated and its answer recorded here.

## 14. Open questions

1. **Rules tension, `aidl-is-append-only` versus `GR-MIGRATION-COMPLETE`. Settled: append, keep v1.** The
   plan first argued that both binder ends ship in one APK, so deleting the old transactions could break no
   installed binary. The grounded round refuted the same-APK premise with a concrete client: the three
   instrumentation tests are compiled into `app-debug-androidTest.apk`, a separately installed package that
   binds the non-exported service through instrumentation, and replacing the production APK does not replace
   it (`device-testing.md` RULE: drive-the-instrumented-tests-with-am-instrument-not-gradle installs the two
   APKs separately). A stale test APK would call v1 transaction codes against a service that had renumbered
   them. Production client and service migrate atomically; installed instrumentation clients are separately
   versioned binaries, so the v1 transactions stay declared and safely implemented, the replacements are
   appended, every current caller migrates, and v1 is removed only through an explicit interface-version
   migration, filed as its own issue at ship time. Kept transactions with a live compatibility contract are
   not the forwarding shim `GR-MIGRATION-COMPLETE` forbids.
2. **`RegexPolisher` removal.** Coverage round confirmed: the fallback uses the shared deterministic pipeline
   exactly, and `RegexPolisher` is deleted because its extra capitalisation and punctuation made the failure
   location change the user's text (`kotlin-patterns.md` RULE: fail-open-to-the-last-good-text). Adopted.

## 15. Related

Issues #69 (this), #37, #21 (phase 2), #18 (phase 4), #67, #53, #64 (phase 6), #61 (phase 7), #2, #4, #3
(phase 8). Roadmap `plan-2026-09-01-ai-polish-refinement-roadmap.md`. `PAR-066`, `PAR-058`, `PAR-067`.
Catalog `polish-fallback`, `ai-polish`, `cloud-polish`.

Consolidation: none. This plan adds the policy, outcome, reason and pipeline-outcome types, the request
registry, the id source and the ledger, and deletes `RegexPolisher`; nothing
existing is merged into another owner.

---

## Review log

- **Coverage round, 2026-09-01, Codex session `01a05e59-b013-71f0-b3ed-3af5726e5254`:**
  PROCEED-WITH-REVISIONS. Adopted: the `:131` preference read; publish-site line numbers; the
  binding-pending, key-changed, second-instance and cancel-race rows in §5; the cancellation population;
  four §7 rows; six §6 rows; monotonic request ids; the `RegexPolisher` adjudication. Rejected with evidence
  at the time: keeping the old binder methods declared (§14.1).
- **Grounded round 2, same session:** PROCEED-WITH-REVISIONS. The §14.1 rejection was itself refuted (the
  instrumentation APK is a separately installed client); adopted, v1 kept. Adopted: a process-wide id
  allocator instead of raw clock values; the ledger's atomic `close()` returning the id; cancel at the start
  of every terminal transition, not only at unbind; `loadPolicy()` from one `getAll()` snapshot; typed
  attempt reasons recorded by each model adapter; `putIfAbsent`/`remove(id, token)` on the registry; the
  once-gate created with the token; the rewritten §11.2 rows and three added tests.
- **Confirming round 3, same session:** PROCEED-WITH-REVISIONS, no new axis; four stale sentences elsewhere in
  the plan still carried the round-1 wording (raw clock ids, cancel only at unbind, four tests, the
  same-APK premise, `warmUp(policy)`). All replaced.
- **Confirming round 4, same session:** three more old-design sentences (a changed `polish` signature, `onError`
  deleted, one delivery site engine-wide). Same shape twice, so the whole plan was swept for the class: every
  sentence describing the pre-round-2 design (replaced signatures, deleted v1 methods, a single delivery
  site, unbind-only cancel, raw clock ids, four tests). Sweep: grep for signature, onError, delivery site,
  deleted, replaced, old methods, unbind, elapsedRealtimeNanos, counter, four; every hit read and fixed.
- **Confirming round 5, same session:** design items all CLOSED; three wording residues (two `deliver`-site
  phrasings, one ledger sentence, the consolidation count) replaced.
- **Confirming round 6, same session:** all CLOSED. **PROCEED-AS-PLANNED.** Gate 2 posted to the founder under
  the standing instruction to run the roadmap end to end (2026-09-01 evening); build started.

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
