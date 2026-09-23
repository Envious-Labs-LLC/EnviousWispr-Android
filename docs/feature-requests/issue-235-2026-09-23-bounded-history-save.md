# Issue #235 — A stuck History save never holds the user's words — 2026-09-23

GitHub issue: `#235`. Tier: LARGE (the heart path's wait on a limb). Status: APPROVED (coverage adopted; grounded rounds 1 to 3 adopted, round 2 a PIVOT to a neutral row; round 4 PROCEED-AS-PLANNED).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y (the heart). Success for a person: a dictation lands its words in the field exactly as today; if the History save is stuck, the words still reach the person within about a second, on the clipboard with the existing "Copied. Press and hold, then tap Paste." line, and the History row appears later with the true outcome. Run on the emulator: healthy dictations by COMMIT; the stuck-save case is proven by JVM rows because the harness cannot stall Room on a device.

## Preface — User Rubric

User Rubric: the words always reach the person (founder decision 2026-09-23: the customer always gets their words; failures are logged to be fixed).

---

## 0. TL;DR

After polish, the session owner waits for the History save before it hands the words to insertion (`DictationSessionCoordinator.publishResult`, `saved.await()`), with no bound. A stuck History worker, Room call or database lock holds the words forever. REF-02 of the 2026-09-23 senior audit (`architecture-rules.md` FACT: heart-and-limbs; RULE: isolate-limbs). Bound the wait at 1 s; on a timeout take the existing no-durable-row route (copy to the clipboard with its announcement), raise a defect, and reconcile the row when the save lands late so it never pastes a second time or reads as an interrupted insertion.

## 1. Problem

- `publishResult` enqueues the save under `publishLock`, then `scope.launch { val saveResult = saved.await(); ... commit(COMPLETED); finalizer.deliver(...) }`. Nothing bounds `saved.await()`.
- The History queue (`history/HistoryWriteQueue.kt`) has one worker; an earlier held write, a stalled Room call or a lock holds every later save.
- The insertion needs the row id (`InsertionGateway.pasteWhenTargetReturns(persistedId, ...)`) so the paste service can record its outcome on the row; that is why the save goes first (`HistoryPublicationPolicy.kt`).

## 2. Goals & non-goals

### 2.1 Goals
1. The owner waits at most `HISTORY_SAVE_BOUND_MS` = 1000 (proposed), injectable like `answerBoundMs`, for the save. A save that answers in time delivers exactly as today (insertion with the row id). No second History write may hold the owner.
2. The save result is typed: `SaveOutcome.Saved(id)`, `SaveOutcome.Failed(cause)`, `SaveOutcome.TimedOut` (proposed), and `SessionFinalizer.deliver` takes it. `Failed` and `TimedOut` both take today's no-durable-row route: commit `COMPLETED`, force the clipboard copy even with auto-copy off, announce once, and return the measured `ClipboardOutcome`.
3. Use one atomic decision with `Pending`, `Saved(id)`, `Failed(cause)`, and `TimedOut`. Save completion and timeout compete to set it. The owner's continuation reads the winner and alone commits and delivers once; a completion after `TimedOut` may only reconcile History.
4. The row is neutral until its route is recorded. Once the finalized `saved_unrouted` (proposed, non-ready) row is durable, let `Saved(id)` compete directly with `TimedOut`. The winner decides delivery within the one second bound.
   - `Saved` wins: hand the durable `saved_unrouted` id to insertion; allow the service's first-wins outcome update on that state (`finalizeInsertionOutcome` accepts `status IN ('ready_for_insertion', 'saved_unrouted') AND insertionResult = 'pending'`); after the handoff the owner enqueues, without waiting, one conditional promotion of that row to `ready_for_insertion`, so a process death after the promotion is recovered as today's interrupted insertion. Enqueue promotion only when `Delivery.handoff == SCHEDULED`. Require `WHERE status = saved_unrouted AND insertionResult = pending`; allow the outcome writer to update either neutral/pending or ready/pending. Test both queue orders and a failed non-scheduled outcome write.
   - `TimedOut` wins: copy immediately and reconcile the neutral row to the measured copy result asynchronously (`clipboard` or `insertion_failed`, as today's no-handoff rows: `STATUS_INSERTION_INTERRUPTED`, `interrupted = true`), by a conditional update on `saved_unrouted` plus `pending`; whichever of the owner's copy result and the late row id arrives second enqueues it.
   - Recovery: use one cutoff and scan drafts, then neutral, then ready. Make every recovery update conditional on its source status and pending result. After the 30 second cutoff (the same cutoff as ready rows; a row younger than it is never eligible; a same-process row CAN age past the cutoff while a write is queued, which row 12's race test covers), recover a surviving neutral row as **delivery unknown**, with its final text intact; do not claim `not_attempted` or an interrupted paste without a durable route record. Give that result an explicit stored token (`InsertionResults.DELIVERY_UNKNOWN` = `delivery_unknown` (proposed)) and telemetry reading (`InsertionResultKind` classifies it explicitly; the recovered-insertion event is not emitted for it). The exact recovered pair: `status = 'completed'`, `insertionResult = 'delivery_unknown'`, `interrupted = 1`. Hide the internal neutral status (`saved_unrouted`) in History explicitly; `completed` already shows no status line. Keep neutral/pending rows in `TakeJournal.insertionTakeIdsToKeep` until resolved. Classify `DELIVERY_UNKNOWN` in telemetry without reporting it as an interrupted insertion.
5. Logged to be fixed: `takeFacts.historySave` gains `timed_out`; a timeout raises new `AppDefect.HistorySaveTimedOut` (proposed) once per take, and the take log says so. On a late failure, keep the delivery outcome fixed but emit the content-free failure breadcrumb and existing defect classification (`TelemetryChannels.historySaveDefect`).

### 2.2 Non-goals
- No change to the healthy order (save, commit, insertion with the row id).
- No change to the History queue itself or its single worker.
- No new user-facing copy: the existing clipboard announcement is reused.

## 2.5 Grounding brief

Grounded by Codex (`235-g0`) and re-read by Claude: the enqueue and the unbounded await in `publishResult`; `SessionFinalizer.enqueueSave` completes `saved` with the row id or the failure on the History worker and calls `history.remember(id)`; `SessionFinalizer.deliver` routes through `HistoryPublicationPolicy.route(persistedId, persistenceSucceeded)`, and a non-durable route forces the clipboard copy (`mustPreventDataLoss`) and announces through `announceInsertionFallback`; `TakeHistory` lives in `SessionFinalizer.kt`; the rig already has `holdFinalize` (#234) and `failInserts`; `completedTakeInsertsOnce` and `historySaveFailureCopiesToClipboard` pin today's two routes.

## 3. Design

- `HistorySaveGate` (proposed), one per take: an `AtomicReference` over `Pending`, `Saved(id)`, `Failed(cause)`, `TimedOut(clipboard: ClipboardOutcome?, lateRowId: Long?)`. Use the typed gate in every save branch. The History worker writes the row as `saved_unrouted` (both finalize and insert paths), then compare-and-sets `Pending -> Saved(id)` or `Pending -> Failed(cause)` and completes the owner's deferred; if `TimedOut` already won, it records `lateRowId` instead and, if the copy result is already recorded, enqueues the reconciling update.
- Owner: `withTimeoutOrNull(historySaveBoundMs) { saved.await() }`; on null it compare-and-sets `Pending -> TimedOut`. If that fails, the save won an instant earlier and the gate already holds `Saved` or `Failed`, which the owner reads without waiting. The winner decides the route. On `TimedOut`: log `History save did not answer in ${bound} ms; the words go to the clipboard`, set `takeFacts.historySave = "timed_out"`, raise `HistorySaveTimedOut`, commit `COMPLETED`, deliver the copy route, record the measured copy result on the gate, and if `lateRowId` is already set enqueue the reconciling update.
- Keep late-save reconciliation under application-owned History work. Join the late save ID and the measured clipboard outcome without blocking the queue. The handler may log and enqueue one idempotent row update; it must not touch the service, insertion, announcement, or terminal `TakeFacts`.
- Return `ClipboardOutcome` from copy-only delivery. Force a copy for both `Failed` and `TimedOut`, even with auto-copy off.
- `SessionFinalizer.deliver(takeId, targetPin, publication, saveOutcome: SaveOutcome, clipboardPolicy)`; the existing `Result<Long>` callers move to `SaveOutcome`.
- Change both finalize and insert paths, plus the repository and DAO contracts, to write the chosen status and result: `TranscriptRepository.finalize` and `insertReadyTranscript` (removed), now `insertSavedTranscript`, write `saved_unrouted`; new DAO updates for the promotion to `ready_for_insertion`, the copy reconciliation, and the neutral recovery (`recoverStaleUnroutedRows` (proposed), `status = 'saved_unrouted' AND insertionResult = 'pending' AND stateChangedAtMs <= cutoff` to `delivery_unknown`); `finalizeInsertionOutcome` accepts both non-final statuses.
- History: hide the internal neutral status (`InsertionOutcomeMessages` shows no status line for `saved_unrouted`, and the recovered `delivery_unknown` reads as a calm line or none, decided in review). `TakeJournal.insertionTakeIdsToKeep` also keeps neutral/pending rows until resolved; `Telemetry.insertionsRecovered` keeps reading only recovered ready rows; the neutral recovery is its own reading.

Alternatives rejected: (a) insert before the save with a provisional id: the paste service reports its outcome to the row id; (b) promote the row to ready before the handoff (grounded round 2): a stalled promotion would hold the words again; (c) no bound, only a defect: the words would still never land.

## 4. Contract deltas

`SessionFinalizer.deliver` takes `SaveOutcome`; new `AppDefect.HistorySaveTimedOut`; `takeFacts.historySave` gains `timed_out`.

## 5. State audit

| Population | Enumeration |
|---|---|
| Save endings | answered in time with an id; answered in time with a failure; no answer within the bound then success; no answer within the bound then failure; no answer ever (worker gone); a destroy while waiting (the reservation is revoked: today's path, unchanged). Each has a row in §11. |
| Row endings | Enumerate no row, surviving draft, neutral row, ready row, and terminal copy row separately (the neutral row replaces the late ready row: after a timeout no ready row can exist): healthy, the neutral row handed to insertion, promoted to ready in the background, then the paste service's outcome; failed finalize after a saved draft, the blank draft survives for the draft recovery (unchanged); failed insert with no draft, no row; timed out then late success, the neutral row reconciled to the measured clipboard outcome; timed out then late failure, as the failed cases; any neutral row that outlives the cutoff, `delivery_unknown` with its text. After a timeout no ready row can exist. |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| `deliver` signature | coordinator, tests | `SaveOutcome` | compile; existing rows |
| New defect | Sentry, `AppDefect.all()` | one member | `TelemetryContractsTest` snapshot |
| `historySave` token | take journal, telemetry | `timed_out` | new row |

## 7-9. Failure modes, signals, fallbacks

New signal: `HistorySaveTimedOut` with the take id, and the take log line. Fallback: the existing clipboard route and announcement.

## 10. Files

`DictationSessionCoordinator.kt`, `SessionFinalizer.kt`, `DefectIdentity.kt`; tests on the rig.

## 11. Testing

Product Outcome rows on the rig, each with a compiling mutation recorded RED. Add a named red mutation for row 5. Stage the timeout/save race with a gate, not a fast timer. Test a failed finalize after a saved draft, no answer, clipboard failure, and real recovery behavior. Assert `history_save=timed_out` in the terminal event before commit and unchanged after late completion. Make fake recovery mirror all three DAO queries exactly; serialize the ready-ID snapshot and update for race tests. Add a Room DAO test for the actual recovery transaction, the promotion, the copy reconciliation and the neutral recovery (instrumented, run on the emulator through `am instrument`). Stage the worker-read-before-timeout race with `holdFinalize`.
1. A save held past the bound: the take completes, the words are copied, one announcement, `historySave = timed_out`, one `HistorySaveTimedOut`. Mutation: await without the bound (the row hangs to its deadline and fails).
2. The held save then succeeds: no paste, no second announcement, and the row is reconciled to the clipboard outcome. Mutation: skip the reconciliation.
3. The held save then fails: nothing more happens; no row. Mutation: announce on the late failure.
4. A save that answers inside the bound: today's paste with the row id (the existing `completedTakeInsertsOnce`, plus the bound injected small to prove a fast save still wins). Mutation: always take the timeout route.
5. A failed save inside the bound: today's clipboard route (the existing `historySaveFailureCopiesToClipboard`). Mutation: take the auto-insert route on `Failed`.
7. The race: the save completes exactly as the bound fires, staged with a gate; one delivery, whichever won. Mutation: replace the decision with a Boolean flag.
8. Clipboard write fails after a timeout; the late row records `insertion_failed`. Mutation: record `clipboard` unconditionally.
9. Recovery: test death before route choice, after clipboard copy, and after insertion handoff (before and after the promotion). A surviving neutral row becomes `delivery_unknown` with its text intact, never `not_attempted` or an interrupted paste; a promoted row is today's interrupted insertion. Test a stalled reconciliation with delivery already complete. Mutation: write the saved row as `ready_for_insertion`.
10. No answer ever (the worker never runs the save): the take completes on the clipboard, and nothing is owed afterwards. Mutation: wait for the save after the timeout.
12. Recovery ordering: a promotion between the neutral and ready scans, and recovery against a queued paste outcome; both leave one honest reading. Mutation: scan ready before neutral.
13. A Saved handoff that is not scheduled: no promotion; the fallback outcome stands. Mutation: promote on every Saved.
11. The promotion write stalls after a Saved handoff: the words were already handed off, the owner is not held, and the row is neutral until the promotion lands. Mutation: await the promotion before the handoff.
6. A destroy while the save is held: no delivery, no announcement (today's revoked-reservation path). Mutation: deliver after a revoked commit.

### 11.1 UAT
Two healthy Gmail dictations by COMMIT. The stuck save is NOT RUN on the emulator (the harness cannot stall Room); the JVM rows are the proof.

## 12. Blast radius

The wait between the History save and the insertion. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 13 green and each named mutation red.
- [ ] Healthy emulator dictations land by COMMIT.

## 14. Open questions
None. The bound (1 s) is a product latency ceiling for a stuck save, not a measured Room time; a healthy save is milliseconds, and the defect rate after release says whether it needs tuning.

## 15. Related
#234 (polish fails open), #236 (warm-up off main), audit REF-02.
