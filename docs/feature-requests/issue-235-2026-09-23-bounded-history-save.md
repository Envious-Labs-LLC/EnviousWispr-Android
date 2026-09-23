# Issue #235 — A stuck History save never holds the user's words — 2026-09-23

GitHub issue: `#235`. Tier: LARGE (the heart path's wait on a limb). Status: DRAFT (coverage round adopted).

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
1. The owner waits at most `HISTORY_SAVE_BOUND_MS` = 1000 (proposed), injectable like `answerBoundMs`, for the save. A save that answers in time behaves exactly as today.
2. The save result is typed: `SaveOutcome.Saved(id)`, `SaveOutcome.Failed(cause)`, `SaveOutcome.TimedOut` (proposed), and `SessionFinalizer.deliver` takes it. `Failed` and `TimedOut` both take today's no-durable-row route: commit `COMPLETED`, copy to the clipboard (forced, as for a failed save), announce once.
3. Use one atomic decision with `Pending`, `Saved(id)`, `Failed(cause)`, and `TimedOut`. Save completion and timeout compete to set it. The owner's continuation reads the winner and alone commits and delivers once; a completion after `TimedOut` may only reconcile History.
4. A late save after a timeout never inserts or announces. Define a durable copy-only state or marker that startup recovery can distinguish from a scheduled insertion: the save closure reads the decision ON the History worker, and when `TimedOut` already won it writes the row directly as the no-handoff row today's clipboard route writes (`STATUS_INSERTION_INTERRUPTED`, `interrupted = true`) with `insertionResult` = the measured clipboard outcome if the owner has recorded it, else `copy_pending` (proposed), never `ready_for_insertion` with `pending`; `recoverStaleReadyRows` only reads `ready_for_insertion`, so it never reports a paste as interrupted when none was requested. When the clipboard outcome arrives after that row, one idempotent update (`WHERE id = :id AND insertionResult = 'copy_pending'`, a new DAO query (proposed)) records it. Cover process death between the late save and reconciliation (the row stays `copy_pending`, visible in History, never recovered as a paste), plus recovery racing reconciliation.
5. Logged to be fixed: `takeFacts.historySave` gains `timed_out`; a timeout raises new `AppDefect.HistorySaveTimedOut` (proposed) once per take, and the take log says so.

### 2.2 Non-goals
- No change to the healthy order (save, commit, insertion with the row id).
- No change to the History queue itself or its single worker.
- No new user-facing copy: the existing clipboard announcement is reused.

## 2.5 Grounding brief

Grounded by Codex (`235-g0`) and re-read by Claude: the enqueue and the unbounded await in `publishResult`; `SessionFinalizer.enqueueSave` completes `saved` with the row id or the failure on the History worker and calls `history.remember(id)`; `SessionFinalizer.deliver` routes through `HistoryPublicationPolicy.route(persistedId, persistenceSucceeded)`, and a non-durable route forces the clipboard copy (`mustPreventDataLoss`) and announces through `announceInsertionFallback`; `TakeHistory` lives in `SessionFinalizer.kt`; the rig already has `holdFinalize` (#234) and `failInserts`; `completedTakeInsertsOnce` and `historySaveFailureCopiesToClipboard` pin today's two routes.

## 3. Design

- Coordinator: `val saveOutcome = withTimeoutOrNull(historySaveBoundMs) { saved.await() }` mapped to `Saved`/`Failed`, and to `TimedOut` on null, claimed through a per-take `AtomicBoolean` route claim shared with the late-completion handler.
- On `TimedOut`: log `History save did not answer in ${bound} ms; the words go to the clipboard`, set `takeFacts.historySave = "timed_out"`, raise `HistorySaveTimedOut`, commit `COMPLETED` and `deliver` the no-durable-row route.
- Keep late-save reconciliation under application-owned History work. Join the late save ID and the measured clipboard outcome without blocking the queue. The handler may log and enqueue one idempotent row update; it must not touch the service, insertion, announcement, or terminal `TakeFacts`. Concretely: one `HistorySaveGate` (proposed) per take, an `AtomicReference` over `Pending`, `Saved(id)`, `Failed(cause)`, `TimedOut(clipboard: ClipboardOutcome?, lateRowId: Long?)`; the owner sets `TimedOut` by compare-and-set from `Pending`, copies, then records the clipboard outcome; the worker writes the copy-only row and records its id; whichever of the two arrives second enqueues the one reconciling update.
- Return `ClipboardOutcome` from copy-only delivery. Force a copy for both `Failed` and `TimedOut`, even with auto-copy off. Reconcile late success as `clipboard` or `insertion_failed` from that measured result, and test both.
- On a late failure, keep the delivery outcome fixed but emit the content-free failure breadcrumb and existing defect classification (`TelemetryChannels.historySaveDefect`).
- `SessionFinalizer.deliver(takeId, targetPin, publication, saveOutcome: SaveOutcome, clipboardPolicy)`; the existing `Result<Long>` callers move to `SaveOutcome`.

Alternatives rejected: (a) insert before the save with a provisional id: the paste service reports its outcome to the row id, so the insertion path and the recovery sweep would both change, far beyond the finding; (b) no bound, only a defect: the words would still never land.

## 4. Contract deltas

`SessionFinalizer.deliver` takes `SaveOutcome`; new `AppDefect.HistorySaveTimedOut`; `takeFacts.historySave` gains `timed_out`.

## 5. State audit

| Population | Enumeration |
|---|---|
| Save endings | answered in time with an id; answered in time with a failure; no answer within the bound then success; no answer within the bound then failure; no answer ever (worker gone); a destroy while waiting (the reservation is revoked: today's path, unchanged). Each has a row in §11. |
| Row endings | Enumerate no row, surviving draft, late ready row, and terminal copy row separately: healthy, the paste service's outcome on the ready row; failed finalize after a saved draft, the blank draft survives for the draft recovery (unchanged); failed insert with no draft, no row; timed out then late success, the terminal copy row (`copy_pending`, then the measured clipboard outcome); timed out then late failure, as the failed cases; a late ready row can no longer be created after a timeout. |

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

Product Outcome rows on the rig, each with a compiling mutation recorded RED. Add a named red mutation for row 5. Stage the timeout/save race with a gate, not a fast timer. Test a failed finalize after a saved draft, no answer, clipboard failure, and real recovery behavior. Assert `history_save=timed_out` in the terminal event before commit and unchanged after late completion. The rig's recovery DAO methods get real bodies over its in-memory rows (matching the `TranscriptDao` queries) so the recovery rows exercise them.
1. A save held past the bound: the take completes, the words are copied, one announcement, `historySave = timed_out`, one `HistorySaveTimedOut`. Mutation: await without the bound (the row hangs to its deadline and fails).
2. The held save then succeeds: no paste, no second announcement, and the row is reconciled to the clipboard outcome. Mutation: skip the reconciliation.
3. The held save then fails: nothing more happens; no row. Mutation: announce on the late failure.
4. A save that answers inside the bound: today's paste with the row id (the existing `completedTakeInsertsOnce`, plus the bound injected small to prove a fast save still wins). Mutation: always take the timeout route.
5. A failed save inside the bound: today's clipboard route (the existing `historySaveFailureCopiesToClipboard`). Mutation: take the auto-insert route on `Failed`.
7. The race: the save completes exactly as the bound fires, staged with a gate; one delivery, whichever won. Mutation: replace the decision with a Boolean flag.
8. Clipboard write fails after a timeout; the late row records `insertion_failed`. Mutation: record `clipboard` unconditionally.
9. Recovery after a timed-out take with a late row, and recovery racing the reconciliation: the row is never marked `insertion_interrupted` with `insertion_interrupted` result, and a process death before reconciliation leaves `copy_pending`. Mutation: write the late row as `ready_for_insertion`.
10. No answer ever (the worker never runs the save): the take completes on the clipboard, and nothing is owed afterwards. Mutation: wait for the save after the timeout.
6. A destroy while the save is held: no delivery, no announcement (today's revoked-reservation path). Mutation: deliver after a revoked commit.

### 11.1 UAT
Two healthy Gmail dictations by COMMIT. The stuck save is NOT RUN on the emulator (the harness cannot stall Room); the JVM rows are the proof.

## 12. Blast radius

The wait between the History save and the insertion. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 10 green and each named mutation red.
- [ ] Healthy emulator dictations land by COMMIT.

## 14. Open questions
None. The bound (1 s) is a product latency ceiling for a stuck save, not a measured Room time; a healthy save is milliseconds, and the defect rate after release says whether it needs tuning.

## 15. Related
#234 (polish fails open), #236 (warm-up off main), audit REF-02.
