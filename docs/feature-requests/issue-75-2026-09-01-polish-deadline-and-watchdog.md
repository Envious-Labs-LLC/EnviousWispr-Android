# Issue #75 — Polish deadline, session watchdog, cancel while processing — 2026-09-01

GitHub issue: `#75`. Tier: LARGE (heart: the session owner's publication path; one engine). Status: SHIPPED 2026-09-01.

Phase 3 of [`plan-2026-09-01-ai-polish-refinement-roadmap.md`](plan-2026-09-01-ai-polish-refinement-roadmap.md).
Depends on #69 (request ids, cancel, ledger) and #72 (the measured latencies the budgets come from).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (unit-tests.xml, codex-review.md, hardware-uat.json) and `Docs/dev-tooling` (this
plan, `.claude/knowledge/polish-engines.md`, `.claude/knowledge/device-testing.md`; cited-symbols).

**PAR rows closed:** `PAR-066` (isolated process, timeout, cancellation, crash recovery, unchanged-text
fallback): the timeout and the user cancel land here; isolation shipped with the process split, cancellation
with #69, the unchanged-text fallback with #69's one fallback owner. Evidence: §11.1's three observations.

**Hardware UAT:** Y. On the S26 Ultra: (1) with the debug deadline override set to one millisecond, a
dictation into Samsung Notes lands as rules-cleaned text within the budget, logcat shows the timeout outcome
and then the engine process ending, and the very next dictation with the override removed is polished
normally on a fresh engine pid; (2) Cancel on the processing notification ends the session with no text
inserted, no History row, and the engine logs the cancelled request; (3) one ordinary dictation in each
mode is unchanged: no timeout, History names the engine.

## Preface — User Rubric

1. **Who is this user in this moment?** Marcus Weber, writing a long paragraph into Google Docs. Thirty
   seconds ago he stopped speaking. Now the notification says "Preparing your words" and nothing happens.
2. **Why would they want this?** "Give me my words back." He does not care why the model stalled; he cares
   that what he said is not lost and that the phone is not stuck.
3. **How would they invoke it?** He does not; the deadline fires for him. The one thing he can do is tap
   Cancel on the notification when he has changed his mind mid-processing.
4. **What app are they in?** Docs, Gmail, Slack, Messages: whichever field he pinned. The text must arrive
   there, rules-cleaned, rather than never.
5. **What is their natural input?** The 40-second fixture: "okay so here is where we landed after the
   customer call this morning …", the worst case for a local generation.
6. **What does success feel like?** Within about fifteen seconds of stopping, at the latest, his words are
   in the field. Usually far sooner, because nothing wedged.
7. **What does wrong-not-broken look like?** A budget too short for a long take on a hot phone: polish is
   thrown away while it was about to finish, and he wonders why long dictations never get polished.
8. **What would a power user hack around this to get?** Priya would kill the app from Recents to unstick
   it, which is exactly what the engine now does for her, with her words delivered first.
9. **What level of control would they want?** None beyond Cancel. The budget is the app's promise.

### Cross-persona check

Frank Chen and Meera Patel gain the most: a stuck notification they cannot diagnose becomes a delivered
sentence. Dr. Elena Vasquez gains a cancel that reaches the engine, so an abandoned take stops computing.
Aaron Wu wants no modal interruption; a notification action is not one. No persona wants a shorter budget.

---

## 0. TL;DR

Polish is the one limb with no deadline. This plan gives local generation an honest engine-side deadline
(deliver the deterministic text with a timeout reason, poison the runtime, end the engine process after
delivery, because a wedged native generation cannot be interrupted), gives the session owner a watchdog per
policy that publishes the deterministic text and cancels the request when the engine never answers, and
wires a Cancel action on the processing notification through to the engine's `cancel`. Budgets come from
the #72 campaign and the macOS precedent. Proof: a staged wedge on the phone, a real cancel while
processing, and unchanged ordinary dictations.

## 1. Problem

- `S1GenieXRuntime.generate` (`S1GenieXRuntime.kt:65-101`) blocks in `runBlocking` collecting the SDK's
  stream and checks nothing between tokens; `PolishService.polishWithS1` (`PolishService.kt:262-292`) and the
  worker that calls it have no time limit. A wedged generation holds the single worker forever.
- `DictationSessionService.polishAndPublish` (`:507-576`) sends `polishRequest` and waits for `onOutcome`
  with nothing else in flight. A hung engine leaves the session in `PROCESSING` with the "Preparing your
  words" notification up; only process death (`onServiceDisconnected`, `:204-218`) rescues it.
- `ACTION_CANCEL` in `PROCESSING` reaches `stopIfIdle` (`:264-268`, `:907-909`), a no-op, and the
  processing notification offers no action at all (`DictationNotificationController.kt:40-47`,
  `includeActions = false`). The engine's `cancel(requestId)` from #69 has no user-driven caller.
- The cloud path is bounded by the client (`ProviderPolishClient.kt:439-442`: 5 s connect, 20 s read,
  30 s overall).

**Measured budgets (#72 campaign, S26 Ultra, GPU compatibility model, plan §11.1.1):** local polish median
0.65 s on an 11 s take and 2.5 to 3.5 s on a 40 s take; model load 1.0 to 1.5 s cached, up to 1.9 s cold;
the worst observed local total (cold load plus long generation) about 5.4 s. macOS budgets local generation
at 15 s and Claude at 15 s (macOS `LLMPolishStep.swift`, `maxDuration` (external)).

## 2. Goals & non-goals

### 2.1 Goals

- G1. A local generation that does not finish within the engine deadline ends as a delivered outcome with
  reason `LOCAL_TIMEOUT` (proposed) carrying the deterministic text, and the engine process is gone
  shortly after, so the next session starts clean. Verified by the staged wedge on the phone.
- G2. A session whose engine never answers within the policy's budget publishes the deterministic text with
  reason `WATCHDOG_TIMEOUT` (proposed) and cancels the request on the engine. Verified by
  `PolishWatchdogBudgetTest` (proposed) for the budgets and by the ledger's first-wins tests for the race.
- G3. Cancel during processing ends the session with no text and reaches the engine. Verified on the phone.
- G4. Ordinary dictations are unchanged. Verified on the phone in each mode.

### 2.2 Non-goals

- Rendering the timeout reason to the user (phase 4; the reason is logged and carried in the outcome).
- Retrying a timed-out polish (phase 8's retry policy).
- Cancelling a cloud request's HTTP call more precisely than `ProviderCancellation` already does.
- Escape recovery after cancel (#30).
- Any change to the AIDL surface: `cancel` exists; `onOutcome` carries the reason.

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

**The local generation.** `PolishService.polishRequest` (`PolishService.kt:82-131`) registers the request in
`PolishRequestRegistry` and queues the work on the single `S1PolishThread` executor; `run` (`:154-206`)
calls `polishWithS1` (`:262-292`), which calls `S1GenieXRuntime.generate` (`S1GenieXRuntime.kt:65-101`);
the outcome is delivered once through `Entry.deliverOnce` → `deliver` (`PolishService.kt:147-152`).
Nothing observes the worker from outside it.

**The session side.** `polishAndPublish` (`DictationSessionService.kt:507-576`) opens the ledger, sends
`polishRequest`, and the callback's `onOutcome` (`:533-556`) publishes after `polishLedger.accepts`.
`cancelOpenPolishRequest` (`:607-611`) closes the ledger and sends `cancel`; it runs from `showError`,
`finishSession`, `onDestroy` and `unbindPipelineServices`. Nothing runs on a timer.

**Cancel.** `onStartCommand` (`:264-268`) routes `ACTION_CANCEL` by state; the processing notification
(`DictationNotificationController.kt:40-47`) is built with no actions; the recording notification's Cancel
(`:79-87`) sends `ACTION_CANCEL` through `serviceIntent`. The overlay is hidden at `stopAndTranscribe`
(`DictationSessionService.kt:440`), so the notification is the only surface during processing.

**What the transcription thread does after a cancel.** `stopAndTranscribe` starts `TranscribeThread`
(`:438-505`), whose ASR callback calls `polishAndPublish` regardless of session state (`:483-488`). A cancel
that lands during transcription must therefore be checked at the top of `polishAndPublish`, or the cancelled
session publishes.

### 2. Find the existing authority before proposing one

| Concern | Existing authority | Note |
|---|---|---|
| Per-request token and once-delivery | `PolishRequestRegistry` (`PolishRequestRegistry.kt`) | the deadline reuses `Entry.deliverOnce`; no second gate |
| Request identity and first-wins on the session | `PolishRequestLedger` (`PolishRequestLedger.kt`) | the watchdog is another caller of `accepts` |
| Cancel over the binder | `IPolishService.cancel` (`IPolishService.aidl:36`), `cancelOpenPolishRequest` | reused |
| Cloud deadline | `ProviderPolishClient` timeouts (`ProviderPolishClient.kt:439-442`) | the session watchdog for cloud sits above the client's 30 s cap |
| Deadline primitive | `withTimeoutOrNull` is the coroutine primitive (`kotlin-patterns.md` RULE: structured-concurrency-with-a-real-scope) | inside `generate`'s `runBlocking` as the cooperative half; a `ScheduledExecutorService` (external) as the hard half, because a native call ignores cancellation |
| Ending a wedged process | none; `Process.killProcess` (external) has no caller (`grep -rn "Process.killProcess" app/src/main/java` → none) | `new authority proposed`: the engine's poison-and-exit |
| A user cancel during processing | none: `ACTION_CANCEL` → `stopIfIdle` | `new authority proposed`: `cancelProcessing` (proposed) |
| Debug-only overrides | `S1ModelSelector` reads the development model only in debuggable builds (`S1ModelSelection.kt:17-34`); #72 used the same pattern for its campaign and removed it after | precedent for the deadline override that stages the wedge |

### 3. Read prior attempts and live direction

- Roadmap phase 3 and the Codex consult (`roadmap-consult-output.txt.last` §4): the deadline is not hard,
  the client watchdog preserves insertion, "the inference can remain stuck indefinitely. That is a handled
  window, not a closed one." This plan closes the window the only way available: the engine process ends.
- #69 shipped the ledger, `cancel`, once-delivery and one fallback owner; #72 measured the latencies.
- macOS: per-provider `maxDuration` (`LLMPolishStep.swift:190-210`), local 15 s; a local timeout is a
  SILENT skip that keeps the step's input (`llm-contract.md` line 57). Android phase 4 decides the surface;
  this plan carries the reason.
- Catalog: `polish-fallback` android: "Off, unavailable, failed, timed-out, blank or safety-rejected polish
  returns the last deterministic text" already promises the timed-out case this plan makes true.
- Cancel is permanent on Android by current behaviour (#30); this plan does not add recovery.
- `code-gotchas.md` RULE: the-handoff-timeout-must-abandon-the-work-not-race-it: the caller and the body
  claim through one atomic; once the body holds the claim there is no second deadline. Here the claim is
  `Entry.deliverOnce` on the engine and `accepts` on the session; the timers race for the claim and lose
  cleanly when the work finished first.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

| Boundary | Today | Under this plan |
|---|---|---|
| Worker wedged in native code | forever | the hard deadline fires on the scheduler thread, delivers, poisons, exits the process |
| Worker finishes after the hard deadline fired | n/a | `deliverOnce` refuses the second delivery; the process is already ending |
| Cooperative timeout inside `generate` returns | n/a | the worker delivers `LOCAL_TIMEOUT` itself and cancels the hard timer; no exit needed, the runtime answered |
| Engine process exit while the client is bound | n/a | `onOutcome` is synchronous, so the exit is scheduled only after the client has the outcome; the client may still be `PROCESSING` when the disconnect arrives, and `publicationStarted` keeps `onServiceDisconnected` from publishing twice |
| Poisoned engine receives a new request before exiting | n/a | answers `LOCAL_FAILED` with deterministic text, no generation attempted |
| Session watchdog fires after the outcome was accepted | n/a | `accepts` returns false, nothing happens |
| Outcome arrives after the watchdog published | n/a | `accepts` returns false, dropped; the engine's cancel makes the cloud call abort and marks the local token (which cannot interrupt generation; phase 3's honesty limit is the engine deadline, not the watchdog) |
| Cancel during `PROCESSING` while ASR still runs | ASR result publishes | `polishAndPublish` returns when the state is not `PROCESSING`; audio deleted as today |
| Cancel during `PROCESSING` after `polishRequest` was sent | no-op | `cancelOpenPolishRequest` closes the ledger and sends `cancel`; the outcome is dropped by the ledger |
| Cancel in `PROCESSING` after publication started | no-op | `publicationStarted` wins; the cancel is too late and does nothing, which is correct: the words are on their way |
| Debug override read | none | engine reads `files/debug/polish-deadline-ms` (proposed) only when debuggable; a release build never reads it |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| A wedged native generation cannot be interrupted from Kotlin | `S1GenieXRuntime.kt:92-98` collects a flow with no cancellation check; the consult named it; `runBlocking` cannot be cancelled from outside |
| `Process.killProcess` on the engine process delivers a disconnect to the client, not a crash of the app | Android kills the `:polish` process only; the main process's `ServiceConnection.onServiceDisconnected` fires (the same path the #69 fallback uses for a died engine, `DictationSessionService.kt:204-218`) |
| The processing notification is the only cancel surface | `DictationNotificationController.kt:40-47` `includeActions = false`; overlay hidden at `:440` |
| The ASR callback publishes regardless of state | `:483-488` calls `polishAndPublish` unconditionally; `polishAndPublish` checks `rawText.isBlank()` only |
| Budgets exceed every measured latency with headroom | #72: worst observed local total about 5.4 s; engine deadline 12 s; session watchdog 15 s local (macOS precedent), 35 s cloud (client cap 30 s plus margin) |
| No coroutine test dependency exists | `grep -n "coroutines-test" app/build.gradle.kts` → none; the watchdog's logic is therefore kept in two pure pieces (the budget table and the ledger) and the timer itself is exercised on the phone |

## 3. Design

### 3.1 The engine deadline, honest about what it can and cannot stop

- `PolishReason` gains `LOCAL_TIMEOUT` (the engine's deadline) and `WATCHDOG_TIMEOUT` (the session's).
- `S1GenieXRuntime.generate` gains a `timeoutMs` parameter and wraps the collect in `withTimeoutOrNull`
  inside its `runBlocking`; it returns null on expiry. This is the COOPERATIVE half: it works when the SDK's
  flow reaches a suspension point, which the campaign's every run did, and does nothing for a generation
  wedged inside native code.
- `PolishService` gains `EngineDeadline` (proposed), a pure class over an injected scheduler: `arm(budgetMs,
  onExpiry)` returns a `Handle` (proposed) with one state machine, `ARMED → CANCELLED` by the worker or
  `ARMED → EXPIRED` by the timer, atomically; a cancel on an expired handle does nothing, so a worker
  finishing late can never touch an expiry-owned exit. `onExpiry` runs on the scheduler thread and does, in
  this order and only if `entry.deliverOnce` WINS: mark the engine poisoned (proposed) first, deliver the
  `LOCAL_TIMEOUT` outcome with the deterministic text synchronously, then schedule the process exit 300 ms
  after delivery returned. A timer that loses the delivery race performs no other action. The 300 ms is an
  operational cushion; the correctness mechanism is that delivery is synchronous and returns before the
  exit is scheduled. Cooperative 10 s, hard 12 s.
- **Every local timeout takes the same winning expiry path**, cooperative or hard: a `withTimeoutOrNull`
  that returns cancels the collecting coroutine but cannot prove the SDK's native generation and GPU context
  stopped, so the runtime is never reused after it. The worker treats a null from `generate` exactly as the
  timer treats expiry: poison, deliver `LOCAL_TIMEOUT` once, exit.
- A poisoned engine answers every new request with `LOCAL_FAILED` and the deterministic text, without
  touching the runtime; it is ending within a second. Poison is marked BEFORE delivery so a request that
  enters while the timeout outcome is being delivered already sees it.
- `onDestroy` while poisoned kills the process at once (orderly destruction would queue the runtime close
  behind the wedged worker, `PolishService.kt:215-221`); otherwise it cancels armed deadlines, shuts the
  scheduler down and cleans up as today. Service destruction never cancels an expiry-owned exit.
- The cloud path is not given an engine deadline: `ProviderPolishClient` already bounds it at 30 s and
  cancellation interrupts it.
- Debug builds only: `files/debug/polish-deadline-ms` (proposed), one integer, read per request. A valid
  value in 1 to 60 000 sets the cooperative budget to that value and the hard budget to the value plus
  2 000; absent, unreadable, malformed, zero, negative or oversized means the shipped budgets. So `1` stages
  the engine expiry and `20000` places both engine deadlines beyond the 15 s watchdog. It exists so the wedge
  can be STAGED on the phone; it stays, documented in `polish-engines.md` as the development override for
  this path, because the hardware recipe in §11.1 is unrunnable without it.

### 3.2 The session watchdog

- `PolishWatchdogBudget` (proposed), a pure object: `forPolicy(policy): Long` = 15 000 ms for `Off`,
  `LocalS1` and `CloudUnconfigured` (the engine answers in milliseconds for `Off` and `CloudUnconfigured`,
  so 15 s only matters for a hung process), 35 000 ms for `Cloud`.
- In `polishAndPublish`, open the ledger and launch the watchdog on `serviceScope` BEFORE the binder call,
  so the remote call itself is inside the budget and a very fast outcome never races the job's assignment;
  the ledger is the only first-wins gate. The watchdog: `delay(budget)`; then if
  `polishLedger.accepts(requestId)` → `runCatching { service.cancel(requestId) }` explicitly (the ledger is
  already closed, so `publishFallback`'s own cancel finds nothing), then publish the deterministic text with
  reason `WATCHDOG_TIMEOUT`. A job that fires after an accepted outcome loses `accepts` and does nothing;
  service teardown cancels the harmless losing timer with the scope.

### 3.3 Cancel while processing

- `DictationNotificationController.processing` gains a Cancel action (the same `serviceIntent` as the
  recording notification's), so the notification the user is looking at can end it. `build` takes separate
  `includeStop` and `includeCancel` flags: listening shows both, processing shows Cancel only (a single
  `includeActions` (removed) flag would show Stop on a session that has already stopped).
- `onStartCommand`: `ACTION_CANCEL` in `PROCESSING` → `cancelProcessing()` (proposed). It first claims
  `publicationStarted.compareAndSet(false, true)`; if it loses, the words are already on their way and it
  returns without touching state or data. If it wins it takes the submission lock (below), moves
  `PROCESSING → CANCELLING`, `cancelOpenPolishRequest()`, releases the pinned target, `SESSION_CANCELED`
  haptic, `discardDraft()`, `finishSession()`.
- **A submission lock, because the state check alone is check-then-act.** The transcription thread can read
  `PROCESSING`, then Cancel closes an empty ledger, then the thread opens the ledger and sends the request.
  `polishSubmissionLock` (proposed) serialises the final state and publication check, `ledger.open()`, the
  watchdog launch and the synchronous `polishRequest` call against `cancelProcessing`: if cancel wins no
  request is sent; if submission wins, cancel finds a registered request and cancels it. A transcript
  arriving after the cancel is dropped inside the lock; its audio file is still deleted by the ASR callback.
- `ACTION_TOGGLE` in `PROCESSING` stays ignored: a double press must not cancel by accident.

### 3.4 Alternatives rejected

- **Interrupt the worker thread.** A thread blocked in native code ignores `interrupt()`; the SDK offers no
  cancel for an in-flight generation.
- **Restart only the runtime, not the process.** The wedged native call still holds the worker and the
  GPU context; closing the runtime underneath it is undefined. Ending the process is the honest reset and
  costs one cold load (1.9 s) on the next dictation.
- **One budget for everything.** The cloud client's 30 s cap would always fire the watchdog first on a slow
  provider, publishing rules-only text while a valid answer was seconds away.
- **Put the watchdog in the engine.** The engine cannot watch itself when its worker is wedged, and a dead
  process cannot watch anything.

## 3b. Ownership justification

The deadline lives in `PolishService` because it owns the worker and the runtime it must poison; the watchdog
lives in `DictationSessionService` because it owns the ledger and the publication; `cancelProcessing` lives
beside `cancelRecording` because it is the same transition one state later. The alternative, a
separate supervisor object, would hold a timer and forward three calls. `DictationSessionService.kt` is a
standing extraction target; this change adds one function and one launch, and the budget table is its own
file.

## 4. MANDATORY — contract deltas

| Type | Delta | Meaning |
|---|---|---|
| `PolishReason` | `LOCAL_TIMEOUT`, `WATCHDOG_TIMEOUT` | the engine's deadline fired; the session's watchdog fired |
| `S1GenieXRuntime.generate` | returns `String?`, takes `timeoutMs` | null means the cooperative deadline expired |
| `PolishService` | deadline, poison, exit; the debug override | a local request always ends within the hard budget, one way or the other |
| `DictationSessionService` | watchdog per request; `cancelProcessing`; state check in `polishAndPublish` | a session always leaves `PROCESSING` within the budget |
| processing notification | Cancel action | the user can end processing |
| AIDL | none | |

## 5. MANDATORY — end-to-end state and lifecycle audit

| Population | Members | Disposition |
|---|---|---|
| Engine paths that must arm the deadline | `polishRequest` with `LocalS1` only | armed before the worker starts, cancelled in `finally` |
| Engine delivery sites | the worker's normal delivery, its catch, the deadline's expiry | all through `Entry.deliverOnce`; three callers, one gate |
| Engine exits | the deadline's expiry only | after delivery, delayed 300 ms |
| Session timers | the watchdog per request | one per `polishRequest`; cancelled on accepted outcome; harmless if it fires late |
| Session terminal transitions that must stop a watchdog | `cancelProcessing`, `showError`, `finishSession`, `onDestroy` | the ledger closes first, so the watchdog's `accepts` fails; `serviceJob.cancel()` in `onDestroy` (`:1068`) ends the coroutine outright |
| `ACTION_CANCEL` by state | `STARTING`, `RECORDING`, `PROCESSING`, others | `cancelStarting`, `cancelRecording`, `cancelProcessing` (new), `stopIfIdle` |
| Callers of `polishAndPublish` | the ASR `onResult` only (`:483-488`) | state-checked at the top |
| Notification variants | listening (Stop, Cancel), processing (none today) | listening = Stop + Cancel; processing = Cancel only, through separate flags |
| Engine timers | cooperative timeout, hard timeout, exit grace | completion cancels only an ARMED hard timeout; expiry owns the exit; the cooperative timeout takes the expiry path |
| Engine lifecycle | normal destroy, poisoned destroy, a late worker | normal destroy shuts the scheduler down; poisoned destroy kills at once; a late worker cannot cancel an expiry-owned exit |
| Debug override values | absent, malformed, zero, negative, valid 1 to 60 000, oversized | only a bounded positive value overrides |
| Cancel timing | during ASR; between the state check and registration; after registration; after ledger acceptance before `publishResult`; after the publication claim | the submission lock and the `publicationStarted` claim decide each: no request sent; request cancelled; outcome dropped; too late, words land |
| Hard deadline after a user cancel | the cancel marks the token but cannot interrupt native generation | the hard deadline stays armed and still resets the engine if the generation wedges |
| Service disconnect with a watchdog pending | the fallback closes the ledger | the watchdog later loses `accepts` or is cancelled with the scope |

Async edge cases:

| Class | Case | Answer |
|---|---|---|
| Interrupted | engine exits while the client reads the outcome | binder reply already sent before the 300 ms delay; if not, `SERVICE_DIED` publishes the same text |
| Concurrent | deadline expiry and worker completion at once | `deliverOnce` picks one; the other is dropped |
| Concurrent | watchdog and outcome at once | `accepts` picks one |
| Concurrent | cancel and outcome at once | `close()` versus `accepts()` on the ledger, first wins (#69) |
| Stale | a queued request behind a wedged one | poisoned engine answers `LOCAL_FAILED` at once; the process exits |
| Absent | override file absent | shipped budgets |
| Mutated | override changed between requests | read per request; applies to the next |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `generate` returns null | `polishWithS1` | expects a String | records `LOCAL_TIMEOUT` on null | yes | `PolishReasonTest` (resolve keeps a recorded reason) |
| `LOCAL_TIMEOUT`, `WATCHDOG_TIMEOUT` | `PolishReason.resolve`, phase 4's renderer | closed set | two members | yes | `PolishReasonTest` pins the set |
| processing Cancel | `onStartCommand` | `stopIfIdle` | `cancelProcessing` | yes | phone |
| `polishAndPublish` state check | ASR callback | publishes always | returns unless `PROCESSING` | yes | phone (cancel during transcription) |
| watchdog | `publishFallback` | reasons `SERVICE_*`, `CALL_FAILED` | plus `WATCHDOG_TIMEOUT` | yes | phone (staged: override the engine deadline to a value above the watchdog, so the watchdog fires first) |
| device tests binding the engine | three `androidTest` files | v2 surface | unchanged | no | compile |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| Local generation exceeds 10 s cooperatively | slow model | engine | rules-cleaned text; `LOCAL_TIMEOUT` logged | `DETERMINISTIC` row | next take |
| Local generation wedged | native | engine deadline | rules-cleaned text within 12 s; engine process ends; next take cold-loads | `DETERMINISTIC` row | next take |
| Engine never answers | dead or hung process | session watchdog | rules-cleaned text within 15 s (35 s cloud); `WATCHDOG_TIMEOUT` logged | `DETERMINISTIC` row | next take |
| User cancels while processing | notification | `cancelProcessing` | session ends, no text, cancel haptic | draft discarded | none |
| Cancel after publication started | notification | `cancelProcessing` | nothing; the text lands | as normal | none |

Sentences are phase 4's; this change adds no user-facing text beyond the notification action label
"Cancel", which the recording notification already uses (`content-brand.md` applies to that word alone).

## 8. MANDATORY — caller-visible signals audit

| Field | Signal | Reader |
|---|---|---|
| `PolishOutcome.reason` = `LOCAL_TIMEOUT` | the engine's deadline decided; the engine process is ending | session owner (log), phase 4 |
| `WATCHDOG_TIMEOUT` | the engine never answered; the request was cancelled | session owner (log), phase 4 |
| engine `poisoned` (proposed) | every request answers `LOCAL_FAILED` until the process ends | engine |
| the `:polish` pid changing after a timeout | the reset happened | hardware recipe |
| `polishAndPublish` returning early | the session was cancelled during transcription | none; the log line names it |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch | Expression | Source | Why | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| every timeout | `PolishFallback.deterministic` | #69 | one owner on both sides | non-blank | raw transcript | `publishResult` |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/polish/PolishReason.kt`: two members.
- `app/src/main/java/com/envi/wispr/polish/S1GenieXRuntime.kt`: `generate(..., timeoutMs)` with
  `withTimeoutOrNull`, returning null on expiry.
- `app/src/main/java/com/envi/wispr/polish/EngineDeadline.kt` (proposed): the pure deadline over an injected
  scheduler, with `PoisonState` (proposed).
- `app/src/main/java/com/envi/wispr/polish/PolishService.kt`: arm and cancel the deadline, poison, exit, the
  debug override, `polishWithS1` recording `LOCAL_TIMEOUT`.
- `app/src/main/java/com/envi/wispr/ui/PolishWatchdogBudget.kt` (proposed).
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: the watchdog launch, `cancelProcessing`,
  the state check, the `ACTION_CANCEL` route.
- `app/src/main/java/com/envi/wispr/shortcuts/DictationNotificationController.kt`: Cancel on processing.
- Tests: `EngineDeadlineTest` (proposed), `PolishWatchdogBudgetTest` (proposed), `PolishReasonTest` updated.
- `.claude/knowledge/polish-engines.md`: the budgets and the override; `device-testing.md`: the staged-wedge
  recipe.

## 11. Testing

1. **Class.** `EngineDeadlineTest`: Product Outcome (when it fails, a wedged model keeps a dictation in
   Processing forever, or a finished polish is thrown away, or the engine exits under a healthy request).
   `PolishWatchdogBudgetTest`: Drift Guard (the budget table; a wrong value shows on the phone as an early
   fallback or a late one). `PolishReasonTest` (updated): Drift Guard.
2. **Revert.** Deadline: remove the `deliverOnce` from the expiry path (double delivery), or fire the exit
   before delivery, or forget to cancel on completion (exit under a healthy request). Budget: swap the local
   and cloud values. Reason: drop a member.
3. **Not tested on the JVM.** The coroutine watchdog's timing (no coroutine test dependency; the ledger race
   is tested and the timer is staged on the phone); the real wedge (staged with the override, which fires
   the same path).

### 11.1 Hardware UAT spec

- **Subsystem:** heart (publication) and the polish limb.
- **Recipe:** added to `device-testing.md` as FACT: the-staged-polish-timeout. (1) Write `1` to
  `files/debug/polish-deadline-ms` with `run-as`; dictate the 11 s fixture with This phone selected; expect
  the outcome line with `LOCAL_TIMEOUT`, the deterministic text handed off, and the `:polish` pid gone
  within a second; remove the file; dictate again; expect `S1-mini loaded` on a NEW pid and a `POLISHED`
  outcome. (2) Hard path, added on the phone 2026-09-01 because a shortened budget alone never reaches the
  hard timer against a healthy 0.7 s generation: `files/debug/polish-stall-ms` = `30000` holds the worker
  before generation, outside the cooperative timeout; expect the shipped 12 s hard timer to expire and
  the exit. (3) With the stall in place, `polish-deadline-ms` = `20000` (hard 22 s, above the watchdog):
  expect `WATCHDOG_TIMEOUT` published at about 15 s and the engine's cancel logged; remove both files.
  (4) Start a dictation, stop it, tap Cancel on the processing notification within the transcription window
  (the 40 s fixture makes that window about 3 s): expect no text, no History row, the cancel logged on the
  engine or the transcript dropped by the state check. (5) One ordinary dictation per mode.
- **Expected observation:** the pid comparison and the reason names in logcat; the History screen for the
  cancel; the field for the ordinary dictations.
- **Phone state to restore:** both debug files removed, mode back to Off, engine process ended.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `EngineDeadlineTest` | Product Outcome | expiry delivers once and then exits; completion first cancels the exit; expiry after completion is a no-op; a poisoned engine refuses new work | drop the once-gate; exit before delivering; skip the cancel; ignore poison |
| `PolishWatchdogBudgetTest` | Drift Guard | local 15 s, cloud 35 s, cloud above the client cap | swap the values |
| `PolishReasonTest` | Drift Guard | the two new members resolve when recorded | remove a member |
| `scripts/measure-tests.sh` | count | suite green | n/a |

## 12. Blast radius & rollback

- **Touched:** the engine service and runtime, the session owner's polish path and cancel route, the
  processing notification, three tests, two knowledge files.
- **Not touched:** AIDL, the ledger, the registry, ASR, audio, every screen, Room.
- **Rollback:** revert the merge. No schema or preference change.

## 13. Ship criteria specific to THIS change

- [ ] The staged wedge on the phone ends in rules-cleaned text within 12 s, the engine process is gone, and
      the next dictation is polished on a fresh pid.
- [ ] The staged watchdog publishes at about 15 s with the engine's cancel logged.
- [ ] Cancel on the processing notification ends the session with no text and no History row.
- [ ] One ordinary dictation per mode unchanged; unit tests green with the reverts seen red; Codex all-clear.

## 14. Open questions

1. **Settled by the review: keep it.** Keeping the debug deadline override after shipping (it is the only way to stage the wedge)
   against #72's removal of its override (a settled decision's knob). Reading attached: this one is a test
   rig for a failure that cannot otherwise be produced, gated to debuggable builds, and documented.
2. **Settled by the review: the kill is the only honest reset.** `Process.killProcess` from the scheduler thread 300 ms after delivery, versus
   `stopSelf()` plus letting the OS reclaim: the OS will not reclaim a bound service's process, so the kill
   is the only honest reset; challenge if a gentler mechanism exists that a wedged native thread respects.

## 15. Related

#75 (this), #69, #72, #30, #43, `PAR-066`, roadmap phase 3, `polish-engines.md`, `device-testing.md`.

Consolidation: none.

---

## Review log

- **Combined coverage and design round, 2026-09-01, Codex session `01a05e59-b013-71f0-b3ed-3af5726e5254`:**
  PROCEED-WITH-REVISIONS, all adopted: expiry poisons and exits only when the shared delivery gate wins,
  poison marked before delivery, the cooperative timeout takes the same expiry path (no runtime reuse after a
  coroutine cancellation), a handle state machine so a late worker cannot cancel an expiry-owned exit,
  poisoned destroy kills at once, the client may still be `PROCESSING` at disconnect, bounded semantics for
  the debug value, the watchdog armed before the binder call, a submission lock against cancel, cancel
  claiming `publicationStarted` first, an explicit engine cancel before the watchdog's fallback, separate
  notification flags, and seven §5 rows. Both §14 questions settled as the plan read them.
- **Code round 1, 2026-09-01, same session:** FINDINGS, all five adopted. (1) Orderly destruction cancelled
  the deadline timer and queued the runtime close behind a possibly wedged worker, so a user Cancel during a
  wedge let the engine outlive its only hard deadline: the engine now counts local requests in flight and
  ends its process on destroy while one is active or after a poison (`mustKillEngineOnDestroy`). (2) A
  request arriving during the exit grace queued behind the wedged worker and learned of the death as
  `SERVICE_DIED`: a poisoned engine now answers `LOCAL_FAILED` before registration. (3) The submission
  lock was held across the synchronous binder call while Cancel takes it on the main thread: the lock now
  covers only the state check and the ledger open; the call runs outside it and re-sends the engine cancel
  if the ledger closed meanwhile (adopted with the ledger as the flag rather than a new token). (4) The
  expiry tests rebuilt a miniature of the production path: the path is now `expireOnce`, called by the
  engine and asserted for order (poison, deliver, exit), the losing case, and a throwing delivery. (5) The
  destroy policy is a pure function with its own test. Notification and budget ordering: no finding.
- **Code round 2, 2026-09-01, same session:** FINDINGS, both adopted. (1) The post-call re-send used the
  ledger's consuming check, which would have closed every normal ledger and dropped every real outcome:
  now a read of `openId`, and the consuming method is renamed `claim` so its shape says what it does.
  (2) The in-flight count stayed raised until the worker's finally, after the synchronous delivery, so a
  client that published and unbound first made destroy kill a healthy engine: the count is released
  before a healthy delivery and in finally for the timeout and failure paths. Rejected execute, the
  poisoned early answer, `expireOnce`, and the destroy policy: closed.
- **Code round 3, 2026-09-01, same session:** CLEAN. Ten axes searched, none found: normal ledger
  preservation, cancel before engine registration, watchdog/outcome/cancel first-wins, duplicate or missing
  publication, publication after cancel, healthy and wedged local teardown, rejected executor submission,
  poisoned early response, counter underflow.

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
