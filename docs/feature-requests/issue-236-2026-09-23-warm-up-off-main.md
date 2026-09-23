# Issue #236 — The polish warm-up can never hold the main thread — 2026-09-23

GitHub issue: `#236`. Tier: MEDIUM (one cross-process call on the heart's main thread, and its twin in setup). Status: DRAFT (coverage round adopted, one finding declined in §3; grounded round 1 adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Success for a person: dictation starts, stops and lands its words exactly as today, and setup behaves as today. Run on the emulator: two Gmail dictations by COMMIT. The stalled-warm-up case is proven by a JVM row (a warm-up that never returns while the take completes), because the harness cannot stall one transaction on a device, and a pre-take process freeze does not prove this call (coverage round, finding 3).

## Preface — User Rubric

User Rubric: the words always land (founder decision 2026-09-23); a stalled polish process never freezes dictation or setup.

---

## 0. TL;DR

`DictationSessionCoordinator.onPolishConnected` runs on the main thread and makes the synchronous binder call `warmUpWithPolicy`; setup's `EngineWarmUp` makes the same call from its scope's dispatcher. A `:polish` process that stalls in that transaction holds the caller's thread. REF-03 of the 2026-09-23 senior audit (`kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread). Run both warm-up calls on IO, off the main thread, through the scopes the callers already own; no new AIDL, no new thread to manage. Harden the service's warm-up so a refused queue or a failure is logged and never leaves loading stuck.

## 1. Problem

- `onPolishConnected` (main) calls `pipeline.polish?.warmUpWithPolicy(sessionPreferences.policy)` inside `runCatching`.
- `EngineWarmUp.polish.onServiceConnected` launches on its `scope` and calls `service.warmUpWithPolicy(policy)` after reading the policy on IO.
- `PolishService.warmUpWithPolicy` enters the `@Synchronized ensureModelLoaded`, which sets `modelLoading = true` and queues the load on the single worker; if `executor.execute` is refused (after `onDestroy` shut the executor down), `modelLoading` stays true and no later warm-up loads the model.

## 2. Goals & non-goals

### 2.1 Goals
1. The owner's warm-up runs on its existing scope with `Dispatchers.IO` (the same scope and dispatcher `polishAndPublish` already uses), capturing the link, the take id and the policy snapshot on main before launching; before the IO call, require the captured take id, an active state, `!destroyed`, and no polish loss; this is a best-effort pre-send check, and cancellation never waits for the binder call; a failure logs one warning with the take id. Main never waits on it.
2. Setup's warm-up call moves inside the existing `withContext(Dispatchers.IO)` block with the policy read. Keep the launched warm-up job and cancel it on stop; check the binding is still wanted before sending. An in-flight binder call remains uninterruptible.
3. The service's warm-up is hardened: `warmUpWithPolicy` catches and logs a failure; `ensureModelLoaded` resets `modelLoading` when the queue refuses the load; the flags stay `@Volatile` and are written under the method's lock or on the single worker (visibility explicit in a comment).
4. A remote synchronous call on IO still waits for the service; the claim is only that it never holds main, a stop, ASR, or a fallback publication (coverage round, finding 1, adapted to the IO design: no caller-side hard bound is claimed).

### 2.2 Non-goals
- No new AIDL transaction; `cancel` stays `oneway` and `polishRequestForTake` stays on the owner's scope under the watchdog.
- No change to when a local request waits for the model (§3, the declined finding).

## 2.5 Grounding brief

Grounded by Codex (`236-g0`, coverage) and re-read by Claude: the warm-up is the only synchronous polish transaction on main (`onPolishConnected`); setup makes the same call (`EngineWarmUp.kt`); `cancel` is `oneway`; `polishRequestForTake` runs on the owner's scope; `PolishService.warmUpWithPolicy` acts only for `LocalS1` and queues the load on the single worker; `modelReady`, `modelLoading` and `modelStatus` are `@Volatile`; a local request whose model is not ready answers `LOCAL_NOT_READY` with the deterministic text.

## 3. Design

- Owner: `onPolishConnected` keeps its loss check (#234), reads `pipeline.polish`, `take.takeId` and `sessionPreferences.policy` on main, and launches `scope.launch(Dispatchers.IO) { if (take.takeId == takeId && state is STARTING, RECORDING or PROCESSING && !destroyed && polishLost == null) runCatching { link.warmUpWithPolicy(policy) }.onFailure { log.warn(...) } }`. Destroy cancels the scope as today; an uninterruptible binder call may keep its IO thread busy, but holds nothing the take needs.
- Setup: the call moves into the IO block.
- Service: as §2.1.3.

Declined, with evidence (coverage finding 2: "call `ensureModelLoaded` in `accept` before queuing request work"), corrected by grounded round 1: keep `accept` unchanged to preserve a fast `LOCAL_NOT_READY` answer when a request overtakes warm-up. Loading ahead of request work is outside the engine deadline (its 12 s deadline starts when the work begins, `PolishService.kt` `work`) but inside the owner's 15-second watchdog, so an overtaking request would wait for a 0.9 to 3.1 s load (`architecture-rules.md` RULE: isolate-limbs carries the numbers) that today it does not. IO dispatch does not guarantee warm-up queues first; treat that race as an accepted fallback risk: the request then gets `LOCAL_NOT_READY` and the deterministic text, today's answer for a take that outruns the load.

Alternatives rejected: (a) an appended `oneway` warm-up transaction (this plan's first draft): a new cross-process contract for a wait that IO dispatch removes without one; (b) an owner command lane: a thread to create and shut down that the existing scope already provides.

## 4. Contract deltas

None.

## 5. State audit

| Population | Enumeration |
|---|---|
| Warm-up callers | the owner's `onPolishConnected`, setup's `EngineWarmUp`. Both move to IO. |
| Owner-side polish transactions | warm-up (IO), `polishRequestForTake` (owner scope, watchdog), `cancel` (`oneway`). |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| Warm-up dispatch | coordinator, setup | IO | JVM rows; `OnboardingWiringTest` updated |
| Service hardening | `PolishService` | catch, reset | shape rows |

## 7-9. Failure modes, signals, fallbacks

A warm-up failure is logged by the owner (take id), by setup, and now by the service itself. No new defect.

## 10. Files

`DictationSessionCoordinator.kt`, `EngineWarmUp.kt`, `PolishService.kt`; tests.

## 11. Testing

1. Product Outcome (rig): a warm-up that never returns (the fake polish link's `warmUpWithPolicy` blocks on a latch) while the take starts, stops, transcribes and publishes: the take completes and the words land. Mutation: call the warm-up on main (the rig's main thread then blocks and the take never ends).
2. Test a reconnect after the take reaches FINISHING but before destroy; dropping the active-state guard must send warm-up and fail.
3. Existing rows adjusted to the asynchronous call: `PolishFailsOpenTest` row 5 (`aReconnectBeforeSpeechAnswersNeitherWarmsNorPolishesThisTake`) waits for the warm-up's own completion signal rather than reading a count at once; for row 5, mutate both loss checks together, or add a signal-controlled case that tests the IO check. `OnboardingWiringTest` must check that the call itself sits inside the IO block.
4. Service shape rows (code only): `warmUpWithPolicy` catches its failure; `ensureModelLoaded` resets `modelLoading` in the refusal branch; assertions pin the catch-and-log path and the refusal reset separately. Mutations: drop each.
Use bounded signals, release the fake in `finally`, and run each stated mutation to a named failure.

### 11.1 UAT
Two Gmail dictations by COMMIT; setup's warm-up is not exercised on the emulator (onboarding is complete there).

## 12. Blast radius

The warm-up's thread in two callers, and the service's warm-up error handling. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 4 green, each mutation red.
- [ ] Two emulator dictations land by COMMIT.

## 14. Open questions
None.

## 15. Related
#234, #235, audit REF-03.
