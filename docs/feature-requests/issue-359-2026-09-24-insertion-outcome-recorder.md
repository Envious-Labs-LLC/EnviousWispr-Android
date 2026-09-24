# Issue #359: an insertion's ending is recorded by its own owner, apart from the editor actions (REF-04, regrade 8) (2026-09-24)

GitHub issue: `#359`. Tier: MEDIUM (a move on the insertion path, no behaviour change). Status: built, before the combined Codex coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take still lands by COMMIT, and its History row and outcome line read as before.

## Preface: User Rubric

No user-visible change. `paste/AccessibilityInsertionRunner` (716 lines) decided what the editor did AND wrote the History outcome, built the `insertion.terminal` event and its breadcrumb, and chose when to report (first-wins on the row, a refused queue write, a debug probe). #362 (REF-07) will change how the runner's first attempt is scheduled; it depends on this split so that the recording is not in the way.

## 0. TL;DR

- New `paste/InsertionOutcomeRecorder` owns the recording: `record(ending)` enqueues the TERMINAL History write, which resolves the row on the queue (#277), writes first-wins and emits only when this writer won; a refused queue write still reports once (#292); a probe with no take leaves only a breadcrumb. `recordInsertionOutcome` moves with it unchanged.
- New `InsertionEnding` is the one immutable hand-over: row, take id, target package, status, result, interrupted, clipboard outcome, latency.
- The runner keeps request timing, the editor actions, the clipboard, the Toast and the haptic. Its `finalizeInsertion` only freezes the `InsertionEnding` and calls `outcomes.record(...)`. `outcomes` defaults to `InsertionOutcomeRecorder.forProcess(service)`, the application's History queue and the telemetry sinks, so `PasteAccessibilityService` is unchanged.

## 1. Tests

- `InsertionOutcomeRecorderTest`: the winning writer records and reports once, with the same values (the take, the target package, the latency, the clipboard, the SCHEDULED handoff, the breadcrumb); a losing writer reports nothing (m1); a refused write reports once (m2); a probe leaves only a breadcrumb; a drift row keeps History and telemetry out of the runner (m3).
- `HistoryNeverHoldsTheWordsTest` keeps pinning `recordInsertionOutcome` at its new home.

## 2. Combined coverage and review round (Codex)

Two findings, both adopted:

- The runner is built in `PasteAccessibilityService`'s field initializer, before Android attaches the service's context, so reading `service.applicationContext` at construction could crash the service at start. `forProcess` takes the service itself and reads its application context only when an ending is recorded, as the old code did.
- Row 1 now asserts the event's result, route and `recovered` flag against literals; rows 3 and 4 assert what was reported, not only how many.

Codex confirmed the seam is the right one for #362: `requestInsertion` still calls editor work before returning, which is #362's change to make.
