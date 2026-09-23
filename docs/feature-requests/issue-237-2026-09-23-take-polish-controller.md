# Issue #237 — Move polish mechanics out of the session owner — 2026-09-23

GitHub issue: `#237`. Tier: LARGE (a split of the session owner's heart path, no behaviour change). Status: APPROVED, round 3 PROCEED-AS-PLANNED (coverage round adopted; grounded round 1: findings 1, 2, 3, 5 adopted, finding 4 adopted as a simplification that also revises coverage findings 4 and 5, see §3 One take per owner; grounded round 2 confirmed it and its one finding is adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Success for a person: dictation behaves exactly as today. Emulator: two Gmail dictations by COMMIT, and one take with the polish process killed while recording (the #234 pass), words by COMMIT with the deterministic text.

## Preface — User Rubric

User Rubric: the words always land (founder decision 2026-09-23); a polish failure delivers the deterministic text plus a notice and raises one defect; nothing a person sees changes.

---

## 0. TL;DR

`DictationSessionCoordinator` (1581 lines) owns the take's lifecycle and also every polish mechanism: the request ledger, the first-wins decision, the loss latch, the once-per-take defect, the warm-up guard, the watchdog, the request listener and its validation, vocabulary restoration and the deterministic fallback. REF-04 of the 2026-09-23 senior audit (`architecture-rules.md` RULE: keep-central-types-thin, RULE: one-owner-for-the-session). Move the polish mechanisms into a per-take `TakePolishController` that hands back one sealed `PreparedText`. The coordinator keeps admission, the terminal arbiter, publication, History and insertion. The moved members are deleted from the coordinator in the same change. No behaviour change: every existing polish row passes unchanged.

## 1. Problem

Grounded by Codex (`237-g0`, section A), re-read by Claude:

- Polish fields in the coordinator: `polishTimeout`, `polishLedger`, `NO_REQUEST`, `polishSubmissionLock`, `PolishDecision` and `polishDecision` (removed), `polishLost` (removed), `polishFailureReported` (removed), `languageDetector`.
- Polish methods: `warmUpStillWanted` (removed), `recordPolishLoss` (removed), `reportPolishFailure` (removed), `claimPolish` (removed), `claimFallback`, `onPolishConnected` body, `onPolishDisconnected` body, the speech-disconnect fallback branch, `polishAndPublish` (from the `scope.launch`), `publishFallback` (removed), `deterministicFallback` (removed), `cancelOpenPolishRequest` (removed), `restoreTakeVocabulary` (removed).
- About 330 lines of the coordinator are polish mechanics that no terminal decision needs to see.

## 2. Goals & non-goals

### 2.1 Goals
1. A new `internal class TakePolishController` in `ui/`, one per admitted take, owns every member in §1 except the coordinator's use of the lock in `cancelProcessing`. The coordinator keeps `rawTranscript` (coverage finding 1).
2. It emits at most one `PreparedText` for an admitted nonblank speech answer, and none when the owner ends the take first; a loss before speech is latched and becomes a fallback only if nonblank speech arrives (grounded round 1, finding 5). It hands it back through a callback: `Polished(text, engine, latencyMs, reason, statusCode, context)` or `Fallback(text, reason, context)`. It never publishes, never touches the arbiter, History, the finalizer or insertion.
3. The coordinator maps `PreparedText` to its existing `publishResult` unchanged.
4. The moved members are deleted from the coordinator (GR-MIGRATION-COMPLETE). No forwarding shims.
5. All existing polish rows (`PolishFailsOpenTest`, `WarmUpOffMainTest`, the polish rows of `DictationSessionCoordinatorTest`) pass unchanged except for the rig's construction.

### 2.2 Non-goals
- No change to polish behaviour, timings, logs, defects or telemetry.
- No AIDL change. No change to `PipelineBindings` or `PipelineLinks`.

## 2.5 Grounding brief

`237-g0` (Codex, read-only): sections A to F. Concurrency (section C): `polishSubmissionLock` joins the PROCESSING and arbiter check to the ledger open and the cancel reservation; the ledger and `PolishDecision` pick one winner between answer, watchdog and disconnect; binder callbacks answer on binder threads (`PipelineBindings.kt:157`). Keep one lock across the owner's cancel reservation and the controller's open, claim and close; never hold it across a binder call.

## 3. Design

**Controller**

```kotlin
internal class TakePolishController(
    private val takeId: String,
    private val lock: Any,                       // the owner's submission lock, shared (see Concurrency)
    private val ledger: PolishRequestLedger,     // the owner's injected ledger; one take per owner, see below
    private val timeout: PolishTimeout,
    private val scope: CoroutineScope,
    private val link: () -> PolishLink?,         // pipeline.polish; the warm-up and each request capture it once, as today
    private val languageDetector: LanguageDetector,
    private val log: SessionLog,
    private val defectSink: (AppDefect, Map<String, Any?>) -> Unit,
    private val isProcessing: () -> Boolean,     // state PROCESSING and the arbiter open, read under the lock
    private val isLive: () -> Boolean,           // the warm-up guard: same take, not destroyed, a live state
    private val onPrepared: (PreparedText) -> Unit,
)
```

Public surface (all others private):
- `bindRefused()`: latches `SERVICE_UNAVAILABLE` and reports `PolishServiceUnavailable` once.
- `connected(policy)`: today's `onPolishConnected` body: the loss check, then capture the current `PolishLink` on main before launching IO; immediately before sending, check the take is still live and polish not lost, then warm that captured link (grounded round 1, finding 3).
- `disconnected()`: today's `onPolishDisconnected` body after the coordinator's state and arbiter gate.
- `claimSpeechLossFallback(rawText)`: called by the coordinator only for a nonblank transcript; claims a `SERVICE_DIED` fallback, and a lost claim does nothing. Only the coordinator's blank branch may commit `ASR_PROCESS_DIED` (coverage finding 2).
- `prepare(rawText, preferences)`: today's `polishAndPublish` from the blank check onward; the coordinator keeps the blank-transcript ending.
- `closeOpen(): Long?`: under the shared lock, closes the ledger and returns the id that was open. `sendCancel(id)` sends the binder cancel and is called only after the lock is released (coverage finding 3). `cancelOpen()` = both, and must never be called while its caller holds the lock.
- `openRequest: Boolean`: for the cancel log line.

**Lock discipline (grounded round 1, finding 2).** Claim or close under the shared lock; release it before `sendCancel`, the deterministic cleanup, a defect report or `onPrepared`. This holds on binder threads too, where the listener and a fallback run today (`PipelineBindings.kt` callbacks).

**Re-send (grounded round 1, finding 1).** After `polishRequestForTake` returns or throws, compare the ledger's `openId` with the request id. If they differ, send cancel on the link captured for that request. This is a read, not a claim, on every path (cancel, answer, watchdog, error).

**Coordinator**
- `@Volatile private var polish: TakePolishController?`, null before admission (and on an idle destroy). `beginSession` builds the take and the controller, publishes `take` first and the controller second in the same uninterrupted main-thread call, and binds only after both exist (coverage finding 4).
- The coordinator keeps `rawTranscript`, recorded under the shared lock as soon as speech answers, before the blank decision or any launch; it passes the text to `prepare` and uses its own copy for the speech-failure check and the payload (coverage finding 1).
- Keeps `polishSubmissionLock` (passed to each controller) so `cancelProcessing` still reserves, sets CANCELLING and closes the ledger (`closeOpen`) in one step; it sends the binder cancel after releasing the lock. The same claim-then-send order applies to the speech-loss and disconnect fallbacks. The submitter's post-registration re-send keeps its captured link.
- `polishAndPublish(raw)` keeps the blank ending and calls `polish.prepare(raw, sessionPreferences)`.
- `onPrepared` maps to `publishResult` (the fallback path logs `Polish fell back on the session owner: reason=...` inside the controller before it hands over, as today).
- Every terminal path calls `polish.cancelOpen()` where it calls `cancelOpenPolishRequest()` today.

**One take per owner (grounded round 1, finding 4).** An owner admits exactly one take: `beginSession` moves IDLE to STARTING by compare-and-set, nothing moves the state back to IDLE, and the Service stops after every take and builds a fresh owner (`DictationSessionCoordinator.kt` class comment and `beginSession`). So a previous take's controller cannot exist beside the current one, and the controller built at admission replaces today's reset block (`polishDecision` (removed), `polishLost` (removed), `polishFailureReported` (removed)) outright. The controller uses the owner's injected ledger, and the rig is unchanged. No take-id check in `onPrepared` and no per-controller ledger factory: they would guard a state this lifecycle cannot reach (revises coverage findings 4 and 5). Instead the shape row pins both halves of one-take admission: `beginSession` requires `compareAndSet(SessionState.IDLE, SessionState.STARTING)`, and no later code writes `SessionState.IDLE`. If either changes, revisit the ledger and stale-callback design (grounded round 2).

Alternatives rejected: (a) a session-scoped controller with a reset at admission: keeps the reset the audit names as fragile and lets a late callback read the next take's latches; (b) moving `publishResult` too: publication and the facts it writes belong to the terminal owner (RULE: one-owner-for-the-session).

## 4. Contract deltas

None outside the process. Inside: `TakePolishController`, `PreparedText` (new, internal); the coordinator's polish members deleted.

## 5. State audit

| Population | Owner after |
|---|---|
| ledger (the owner's, one take per owner), decision, loss latch, once gate | controller (lock shared with the coordinator) |
| raw words | coordinator, under the shared lock |
| watchdog job, warm-up job | controller, on the session scope (cancelled with it on destroy) |
| terminal reservation, CANCELLING, publication facts | coordinator |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| Polish members moved | coordinator call sites (connect, disconnect, speech disconnect, bind refusal, polishAndPublish, cancel, fail, finish, destroy) | call the controller | existing rows unchanged |
| Ledger injection | `DictationSessionRig` | unchanged: passes `polishLedger` to the coordinator, which hands it to its one controller | rig compiles, rows pass |
| Shape | `SessionOwnerShapeTest` | new row | §11 |

## 7-9. Failure modes, signals, fallbacks

Unchanged by design. Risks (from `237-g0` F): a split lock sending an uncancellable request (kept shared); a late answer rewriting facts (`publishResult` writes the polish facts before its arbiter reservation today; unchanged here, and the ledger claim lets only one answer reach it); a reconnect clearing the loss latch (the latch lives in the per-take controller and is never reset); the warm-up returning to main (the `WarmUpOffMainTest` rows); the fallback losing restored words or the notice (the vocabulary rows).

## 10. Files

New `ui/TakePolishController.kt`; `DictationSessionCoordinator.kt`; `DictationSessionRig.kt` if the construction changes; tests.

## 11. Testing

1. Behaviour unchanged: `PolishFailsOpenTest`, `WarmUpOffMainTest`, and every polish row of `DictationSessionCoordinatorTest` pass without edits to their assertions. Full suite, and 20 repeats of the three classes.
2. New `TakePolishControllerTest` (controller alone, fake link and timeout, bounded signals):
   a. an answer and the watchdog race: exactly one `PreparedText`; mutation: drop the claim in the watchdog.
   b. a disconnect with an open request hands back `Fallback(SERVICE_DIED)` once, and the late answer is ignored; mutation: skip the ledger claim in `disconnected`.
   c. a blank answer for a nonblank request gives the deterministic fallback and one `PolishProtocolViolation`; mutation: pass the blank text through.
   d. registration held by a bounded gate in the fake link; cancel; release: the captured link receives the second cancel. Also an answer that arrives before the request call returns: the re-send fires and the answer stands. Mutation: drop the re-send.
   f. every invalid callback shape (null outcome, mismatched id, legacy `onResult`, legacy `onError`) gives one fallback and one `PolishProtocolViolation`; mutation: skip the claim on the null shape.
   g. shape row: `beginSession` requires `compareAndSet(SessionState.IDLE, SessionState.STARTING)` and no later code writes `SessionState.IDLE`; mutations: add a return to IDLE in `finishSession`; widen the admission to also accept FINISHING.
   h. lock state: the fake link's `cancel` and the `onPrepared` callback each record `Thread.holdsLock(lock)`, which must be false, for a cancel while processing and for a fallback from a binder-thread callback; mutations: send the cancel inside the lock; deliver `onPrepared` inside the lock.
   e. a loss before `prepare` gives the fallback with that reason and no request; mutation: ignore the latch.
3. Shape row in `SessionOwnerShapeTest`: the coordinator source carries no `PolishRequestLedger(` use beyond its parameter, no `polishRequestForTake`, no `PolishListener`, no `PolishFallback.deterministic`, no `PolishDecision`; the controller carries no `TakeArbiter`, `publishResult`, `history`, `insertion` or `finalizer`. Mutation: leave `deterministicFallback` (removed) in the coordinator.
4. Coordinator line count reported before and after. Every stated mutation is run and its red row recorded.

### 11.1 UAT
Two Gmail dictations by COMMIT; one take with `kill_process("com.envi.wispr:polish")` while recording, words by COMMIT with the deterministic text and one `PolishServiceDied`.

## 12. Blast radius

Every take's polish path. Mitigated by leaving every behaviour row unchanged. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Existing polish rows green without assertion edits.
- [ ] New controller rows green, each mutation red.
- [ ] Shape row green, its mutation red.
- [ ] Emulator dictations and the polish kill take land by COMMIT.

## 14. Open questions
None.

## 15. Related
#234, #235, #236, audit REF-04.

## 16. As built

- `TakePolishController.kt` (new) and `PreparedText`; `DictationSessionCoordinator.kt` 1581 to 1323 lines.
- `TakePolishControllerTest` rows as §11.2, with two changes found by the mutation receipts: row a is split into a (the watchdog wins, a late answer is ignored) and a2 (an answer wins, the watchdog after it adds nothing; the controller's coroutines finishing is the signal), because the fallback's ledger close already stops a late answer; row b2 is a disconnect after the owner cancelled the request, because the decision state already stops a disconnect after an answer. Row h is h (a binder-thread disconnect's fallback), h2 (the watchdog's cancel) and h3 (the owner's cancel while processing, the lock read from the owner).
- `SessionOwnerShapeTest`: `theOwnerHandsPolishToTheTakesController` (§11.3) and `anOwnerAdmitsExactlyOneTake` (§11.2 g). Four source-reading rows moved to the controller's file: `PolishPublicationRoutesTest` (two routes, nine fallback producers) and `DeterministicFallbackTest` (the shared fallback and its detector).
- Receipts: 17 of 17 named mutations RED, including the two #236 warm-up mutations re-aimed at the controller.
