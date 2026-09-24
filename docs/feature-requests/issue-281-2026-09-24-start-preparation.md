# Issue #281: move start preparation out of the session state machine (2026-09-24)

GitHub issue: `#281`. Tier: LARGE (the session owner, `ui/DictationSessionCoordinator.kt`). Status: revised after the coverage round (`281-cov`), all four findings adopted; grounded round 1 (`281-g1`), five clarifications adopted (it confirmed the plan keeps every dispatcher and scope). Built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take starts and stops and logs `Take terminal: ... start: settings=.. matcher=.. policy=.. admission=.. bind=.. live=..` with every step stamped. The emulator's speech injection records silence on main too (#319), so the words are not the oracle; the start chain is.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes: a take starts on the same settings, custom words and polish policy, within the same bounds, and a failed or slow step still never stops it. The session owner loses the preparation work, so later changes to how a take starts touch a smaller, separately tested piece.

---

## 0. TL;DR

REF-05 of `docs/audits/2026-09-23c-senior-audit.json` (`architecture-rules.md` RULE: keep-central-types-thin). `beginSession`'s coroutine does four preparation steps before the owner decides to bind: the settings answer wait and its fallback facts, the vocabulary matcher compile, the policy read (both under one deadline, #290), and the journal admission wait. Move them into `ui/TakeStartPreparer.kt` (proposed), which returns one immutable `TakeStartPreparation`. The owner keeps the state check on main, the cancel of the jobs, the fallback decisions that report (they must run only after the state check, #290), `takePolicy`, the freeze and `bindPipelineServices`.

Consolidation: the preparation helpers (`Timed`, `Prepared`, `preparing`, `awaitBy`) move with the steps; no second copy.

Prior context: #193 (the readers are limbs), #258 (the start chain's stamps), #290 (one deadline for matcher and policy, late means stamped late, take-owned jobs), #278 (a failed policy read is never Off), #176 (the admission under a deadline that never gates).

## 1. Grounding (main 891cb1a)

- `beginSession` (L345 to L488): main-thread prologue (CAS, take id, arbiter, admission enqueue, surface, pin, `TakeContext`, polish controller, `capture.begin`), then `scope.launch { ... }` with the four steps (L418 to L457), then `withContext(mainDispatcher)` with the state check, fallback resolution with its log and defect, `takePolicy`, freeze, bind (L458 to L486).
- Helpers L490 to L521: `Timed`, `preparationJobs` (read by cancel at L1164), `Prepared`, `preparing`, `awaitBy`.
- Collaborators the steps use, all owner constructor parameters: `preferences` (`SessionPreferencesSource`), `answerBoundMs`, `preparationBoundMs`, `compileMatcher`, `loadPolicy`, `lastReadPolicy`, `host.elapsedRealtimeMs`, `scope`, `log`.
- `TakePreparationBoundTest` drives these steps through the rig (matcher throws, never finishes, healthy; policy never answers, answers while the matcher is held; admission throws or fails; cancel during the wait; an answer stamped late). `SessionOwnerShapeTest` and others may read the owner's source text (the grounded round names them).

## 2. Design

1. `ui/TakeStartPreparer.kt`: `internal class TakeStartPreparer(preferences, answerBoundMs, preparationBoundMs, compileMatcher, loadPolicy, lastReadPolicy, clock: () -> Long, scope, log)` with
   `suspend fun prepare(takeId: String, facts: TakeFacts, admission: Deferred<Boolean>?, sinceAccepted: () -> Long, jobs: (List<Job>) -> Unit, stillStarting: () -> Boolean): TakeStartPreparation`.
   It runs the same four steps in the same order with the same stamps (`settingsAnswerMs`, `settingsFallback` and its breadcrumb and warn, `inputDevice`, `matcherReadyMs`, `policyLoadedMs`), registers the two jobs through `jobs` then rechecks `stillStarting` exactly as today, and waits for the admission under `JOURNAL_ADMISSION_DEADLINE_MS` with the same logs.
2. `TakeStartPreparation` (immutable): `start: PreferenceStart`, `matcher: Prepared<Matcher>`, `policy: Prepared<PolicyRead>`, `priorPolicy: PolishPolicy?`, `jobs: List<Job>`. `Prepared`, `Timed`, `preparing` and `awaitBy` move into the preparer's file as `internal`/`private`.
3. The owner's coroutine becomes: `val prepared = preparer.prepare(...)`, then the same `withContext(mainDispatcher)` block: state check (cancel `prepared.jobs` when not starting), `preparationJobs = emptyList()`, the two fallback `when`s with their logs and the matcher defect (unchanged, still after the check), `takePolicy`, freeze, `bindRequestedMs`, bind.
4. The preparer is built by the owner from its existing constructor parameters (no constructor change for the Service or the rig), so every rig row drives the real preparer.
5. (coverage A) The coordinator remains the only session owner. It keeps its volatile `preparationJobs` field and the `cancelStarting` path that cancels it. The preparer launches matcher and policy as sibling jobs on the existing scope and dispatchers, publishes both jobs to the owner through `jobs`, then rechecks STARTING through `stillStarting` and cancels them if needed. The owner clears the field only after its main-thread state check. Keep the outer `scope.launch` and both `scope.async` dispatchers unchanged (g1).
6. (coverage B) The admission ENQUEUE and its completion handler stay in the owner's uninterrupted main-thread prologue; only the bounded wait moves. `admissionObservedMs` is still stamped at completion, not at wait return. The settings, matcher, policy and bind stamps, the fallback token, warn and breadcrumb, and the matcher defect keep their current order; matcher and policy fallback reporting stays after the owner's main-thread STARTING check.

## 3. Tests

0. (coverage C) Retarget `TelemetryContractsTest.theSettingsFallbackBreadcrumbHasOneShapeAndIsSentOnlyOnAFallbackTake` to the preparer; add the preparer to `SessionSources.all` so the source-wide negative scans read it; keep `SessionOwnerShapeTest`'s owner-only admission and pin checks; leave `PolishPolicyTest`'s provider-source `loadPolicy` check in place (g1).
1. `TakePreparationBoundTest` rows pass unchanged (they drive the real preparer through the owner).
2. New `TakeStartPreparerTest`, the preparer alone with a fake clock and fakes: the stamps are written in order; a settings fallback writes the token, the breadcrumb data and the warn; the admission wait logs `did not land` on timeout and `failed:` on an exception; the jobs are registered before the recheck and cancelled when `stillStarting` is false; the result carries `priorPolicy` read before the policy job starts. It starts the fake preferences source, then calls `prepare` directly with the rig's fakes and a controlled clock (g1).
3. A shape row RED on main: the owner's file declares none of `awaitAnswers(`, `compileMatcher(`, `loadPolicy()`, `withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS`, `awaitBy(`, `class Timed`, `interface Prepared`; the owner calls `preparer.prepare(`.
4. Mutations (coverage D), each with a witness built to catch it: m1 the preparer stops rechecking `stillStarting` after publishing the jobs (RED: a preparer row where `stillStarting` turns false between publication and recheck, and both jobs must be cancelled); m2 `priorPolicy` read after the policy job starts (RED: a preparer row with a held policy job whose start changes the last-read value, and `priorPolicy` must be the value before); m3 the admission wait drops its deadline (RED: a preparer row with an admission that never completes, which must return by the deadline); m4 the owner reports a fallback before its state check (RED: an owner source row that the STARTING check precedes both fallback `when`s in the main-thread block; a gated-dispatch rig row is feasible (g1) but needs a rig extension out of this scope, so the source-order row is the declared deviation); m5 the preparer drops the settings fallback breadcrumb (RED: the retargeted breadcrumb row).

## Results (2026-09-24)

- Mutations m1 to m5 RED (`281-mut.py`); row 6 (`theOwnerHoldsNoPreparationStepAndChecksBeforeAnyFallback`) is RED on main, where the owner holds the steps.
- `TakeStartPreparerTest` and `TakePreparationBoundTest` each 20 of 20 green.
- The owner's file: 1334 to 1273 lines.
- Suite 1348, 0 failures; app and androidTest build; visibility and cited-symbol checks clean.
- Emulator: a take started and logged `Take terminal: NO_SPEECH (no_speech) start: settings=23 matcher=31 policy=31 admission=5 bind=31 live=980`, every step stamped and no fallback line; the silence is #319, as on main.

## 4. Blast radius

A mis-moved step would change what a take starts on or stall a start. The rig rows and the new preparer rows cover each step. Rollback: revert the squash commit.

## 5. Ship criteria

- [x] Rows green, mutations RED; the suite green; app and androidTest build; checks clean.
- [x] Emulator: a take starts and logs every start-chain stamp.
- [ ] Codex code review ALL-CLEAR with a confirming round.
