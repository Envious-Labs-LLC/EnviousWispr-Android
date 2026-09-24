# Issue #291: keep fallback preparation off polish binder threads (2026-09-24)

GitHub issue: `#291`. Tier: MEDIUM (the `:polish` process's request entry). Status: revised after the coverage round (`291-cov`) and grounded rounds 1 and 2 (`291-g1`, `291-g2`), every finding adopted; PROCEED once the round 2 drop-in (the worker's type) is applied.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a normal take still polishes and lands (the three changed branches are failure paths a device cannot stage without a seam; they are JVM rows).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing changes on a normal take. On the three rare failure answers (a request with no policy, a polish engine ending after a timeout, a duplicate request id) the polish process today cleans the words up on the binder thread that delivered the request, which the rules forbid; after this change it answers from its own small worker, so a slow cleanup can never hold that thread.

---

## 0. TL;DR

REF-02 of `docs/audits/2026-09-23d-senior-audit.json` (`kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread). In `PolishService.accept`, three branches build the deterministic text with `fallbackText(raw, options)` (cleanup plus ML Kit language detection) inline on the AIDL call's binder thread: the null policy (#278), the poisoned runtime (#75), and a duplicate request id. Move them to a service-owned single-thread fallback worker; the AIDL method returns after admission.

Consolidation: the three inline sites become one call, `PolishFallbackLane.answer` (proposed).

Prior context: #75 (the poisoned runtime and its daemon deadline thread; the main worker may be wedged, so it cannot carry these answers), #278 (the null-policy branch), #107 (`fallbackText` detects the language first). The v1 `polish` method already runs on the main worker and is unchanged.

## 1. Grounding (main a7bb1e1)

- `accept` delivers `NO_SPEECH` for blank text without any cleanup (unchanged: no preparation work).
- The three branches call `deliver(callback, PolishOutcome(requestId, fallbackText(raw, options), DETERMINISTIC, <reason>, 0, 0))` inline: reasons `UNEXPECTED` (null policy), `LOCAL_FAILED` (poisoned), `UNEXPECTED` (duplicate id).
- `executor` (the single `S1PolishThread`) is unusable for them: the poisoned branch exists because that worker may be wedged in native code.
- `onDestroy` closes the language detector first, then (orderly path) shuts the main executor down; the kill path ends the process.

## 2. Design

Binder-thread work in the `:polish` binder methods, classified (coverage finding A): the three `fallbackText` sites (moved, below); `localBudget()` in `accept`, a debug-build file read (moved onto the main worker: the budget is read at the start of the queued `work`, before the deadline is armed); trimming and `CleanupOptions` (constant-time, kept); the blank answer and every `onOutcome` (a synchronous callback into the session owner, whose listener only posts to its main thread since #253; kept, out of scope); warm-up queues the load; cancel and status do no model or disk work.

1. `PolishFallbackLane(worker: ExecutorService, prepare: (String, CleanupOptions) -> String)` (proposed) in `polish/`. `answer(requestId, raw, options, reason, sink)` admits one task under a per-admission token keyed by the request id; the task prepares the text, checks the token was not cancelled, and delivers `PolishOutcome(requestId, text, DETERMINISTIC, reason, 0, 0)` once. `cancel(requestId)` marks every token for that id; a cancelled task delivers nothing.
2. Refusal (the lane was closed or its worker rejects): the RAW text is delivered from a separate short-lived daemon thread, never inline on the caller's thread. Every answer, a refused one included, keeps its token until delivery or cancellation: the deliverer atomically claims delivery after checking cancellation, and removes the token afterwards.
3. The lane's worker is an `ExecutorService`. `close(then)`: under the admission lock, marks the lane closed, enqueues `then` after every admitted task, and calls `shutdown()`; it never waits. `PolishService.onDestroy` checks the kill condition FIRST: on the kill path it ends the process without closing the detector or waiting for lane work; on the orderly path it closes the lane with the language-detector close as `then`, so admitted fallback answers detect with a live detector (the main worker's own use of the detector is unchanged; the detector's lock owns that race, as today).
4. `PolishService`: the three branches call `fallbackLane.answer(..., sink = { deliver(callback, it) })`; `cancel(requestId)` calls `registry.cancel(requestId)` and `fallbackLane.cancel(requestId)`. A duplicate id never touches the registry's existing entry.

## 3. Tests

1. `PolishFallbackLaneTest` (JVM), a queueing worker: `answer` returns without calling `prepare`; running the queue calls it on the worker and delivers exactly one outcome with the prepared text and the reason. MUTATION m1: `answer` prepares inline before submitting.
2. A rejecting worker: the row waits for the daemon's delivery; the raw text arrives once, on a thread other than the caller's, and `prepare` is never called. MUTATION m2: the refusal delivers inline on the caller.
3. Cancel: `cancel(id)` before the queued task runs means nothing is delivered for it (another id's task still delivers); and a refused answer cancelled before its daemon delivers (the daemon held on a latch) delivers nothing. MUTATION m3: the task ignores the token; MUTATION m6: the refusal's deliverer ignores the token.
4. Close ordering: after `close(then)`, `then` actually runs, and only after the admitted task delivered (both recorded in order); a later `answer` is refused (raw, off-thread). MUTATION m4: `close` runs `then` immediately.
5. Source-shape row in `DeterministicFallbackTest`: `accept` contains no `fallbackText(` and no `localBudget(`; each of the three branches, inside `accept`, calls `fallbackLane.answer` with its reason; `cancel` calls `fallbackLane.cancel`; the #278 null-policy row reads the lane call. MUTATION m5: one branch reverts to the inline `deliver(callback, PolishOutcome(requestId, fallbackText(raw, options), ...))`.

## 4. Blast radius

The `:polish` process's three failure answers now arrive from another thread a moment later; the owner already treats every answer as asynchronous (its ledger and watchdog). Normal takes are unchanged. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m6 RED; the suite green; app and androidTest build.
- [ ] Emulator: a normal take polishes and lands.
- [ ] Codex code review ALL-CLEAR.

## 6. As built (2026-09-24)

- `polish/PolishFallbackLane.kt`: one token per answer with a single `settled` flag won once by the deliverer or by `cancel` (so a cancel between the check and the claim cannot slip through); a cleanup that throws answers the raw words; a refusal delivers the raw words from `startRefusal` (a daemon thread in production); `close(then)` marks closed, enqueues `then` and shuts the worker down under the admission lock.
- `PolishService`: `fallbackLane` on a daemon `PolishFallbackThread`; the three branches call `fallbackLane.answer`; `cancel` also cancels the lane; `localBudget()` is read inside the queued `work` call; `onDestroy` checks the kill condition first, and on the orderly path closes the lane with the detector close as its final step.
- Deviation: m6 is not a separate mutation: the refusal and the queued answer share one `deliver`, so m3 (the token ignored there) covers both, and row 3 asserts both.
- Mutation receipts: `docs/audits/2026-09-24-291-mutation-receipts.txt`, 5 of 5 RED. Full unit suite: 1291 tests, 0 failures. App and androidTest build; `check-visibility.py` clean.
- Emulator: the build installs and a take goes live (`Take terminal: NO_SPEECH ... live=152`), but the emulator's audio input failed again (91 `pcm_readi failed` read errors over two takes, #273), so a spoken polished take is NOT RUN. The three changed branches are failure paths no device run could stage anyway; the only normal-path change is where the debug-build budget file is read.

## 7. Related

#75, #278, #107; REF-02 of the fourth 2026-09-23 audit.
