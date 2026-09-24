# Issue #304: the History save's diagnostics outlive the Service (2026-09-24)

GitHub issue: `#304`. Tier: MEDIUM (a limb's diagnostics move from the session owner to an application-owned observer). Status: revised after the coverage round (`304-cov`), all findings adopted; grounded round 1 (`304-g1`): §3.4 rewritten, and the refusal's defect corrected (§2.6); grounded round 2 (`304-g2`): m4 made decisive by a direct classifier assertion.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take lands its words and its History row (a healthy save answers well inside the bound, so nothing is reported).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. Today, when a History save is slow or fails, the report that would tell us is usually lost: the Service stops right after the words are handed over, and the watch dies with it. After this change the report arrives, so a History problem on his phone is visible to us instead of silent.

---

## 0. TL;DR

REF-01 of `docs/audits/2026-09-24-senior-audit.json` (`.claude/rules/architecture-rules.md` FACT: heart-and-limbs). `DictationSessionCoordinator.watchSave` (removed) runs `watchSaveBound` on the Service's `scope`, which `destroy` cancels (`serviceJob.cancel()`). After an ordinary take the Service stops at once, so a save still pending at that moment is never diagnosed: no `history_save_timed_out`, no failure breadcrumb or defect. Reproduced before any change (§3 row 1b, RED on main 6cbd5fb).

Move the watch and its reporting into `HistorySaveObserver`, one per process, owned by `ModelBootstrapApplication` beside `HistoryWriteQueue`, on its own application scope. `SessionFinalizer.enqueueSave` hands it the slot, the enqueue time and the take id in the same operation that enqueues the save. Insertion never waits on it; the owner no longer watches.

Consolidation: the save's diagnostics have one owner (the observer); `DictationSessionCoordinator.watchSave` (removed) and its `historySaveBoundMs` seam are deleted.

Prior context: #277 (insertion never waits on History; `SaveSlot`, `watchSaveBound`, `saveMissedBound`), #252 (a throwing defect sink never stops what follows), #292 (a refused save answers its slot failed), #115 (the application-owned History queue).

## 1. Grounding (main 6cbd5fb)

- `DictationSessionCoordinator.publishResult`: under `publishLock`, `saveEnqueuedAtMs = host.elapsedRealtimeMs()` then `finalizer.enqueueSave(current.history, payload, saved)`; after the reservation, `watchSave(saved, saveEnqueuedAtMs, takeId)`.
- `watchSave` (removed) launches on `scope`: `watchSaveBound(saved, enqueuedAtMs, historySaveBoundMs, host::elapsedRealtimeMs) { warn; reportDefect(HistorySaveTimedOut, take_id) }`, then for `SaveOutcome.Failed`: warn, `Telemetry.breadcrumb("take", "history_save_failed", take_id, error_type)`, `TelemetryChannels.historySaveDefect(error)?.let { reportDefect(...) }`.
- `destroy` calls `serviceJob.cancel()`, the Job of the scope the Service passes in.
- `ModelBootstrapApplication.historyWrites` builds the process's one `HistoryWriteQueue` lazily; the Service passes it to the owner.
- Tests: `HistoryNeverHoldsTheWordsTest` passes `historySaveBoundMs` to `DictationSessionRig.coordinator` and reads `rig.defects` and `rig.log`; `PolishPublicationRoutesTest` asserts the owner's exact `finalizer.enqueueSave(current.history, payload, saved)` line under the lock.

## 2. Design

1. `ui/HistorySaveObserver(scope, clock, warn, defectSink, breadcrumb, boundMs = HISTORY_SAVE_BOUND_MS)` (as built: `defectSink` so `SentrySchemaTest`'s key scan reads it, and a `(category, message, data)` breadcrumb sink so the call names both literally) with `observe(slot, enqueuedAtMs, takeId)`: launches `watchSaveBound` on its own scope and reports exactly what `watchSave` (removed) reports today, each once: a late save gets its warning and `HistorySaveTimedOut`; a failed save gets its warning, the `history_save_failed` breadcrumb and, when `TelemetryChannels.historySaveDefect` classifies the error, its defect, including a failure that arrives after the timeout. The three sinks are guarded SEPARATELY (#252): if one throws, the later reports still run. The observer's clock is the same time base as the slot's answer stamp (`host.elapsedRealtimeMs` in the owner, `SystemClock.elapsedRealtime` in production).
2. `ModelBootstrapApplication` owns one: `historySaves(context)`, built lazily on `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, clock `SystemClock::elapsedRealtime`, warn to `DebugLogger`, defect and breadcrumb to `Telemetry`. It is never cancelled; it lives as long as the process, like the queue.
3. `SessionFinalizer` takes the observer; `enqueueSave(history, publication, saved, takeId)` takes the monotonic enqueue time from `host.elapsedRealtimeMs()` IMMEDIATELY BEFORE `historyWrites.enqueue` (so the refusal path has it too) and registers the slot once, after that call, with `observe(saved, enqueuedAtMs, takeId)`. The owner's lock still covers the reservation and the enqueue in one operation. Every other constructor of the finalizer and caller of `enqueueSave` is updated: the Service (through the owner), `DictationSessionRig`, and `HistoryWriteQueueTest`'s direct construction and `enqueueSave` call (coverage finding A); `PolishPublicationRoutesTest`'s exact source assertion follows the new call.
4. Delete `DictationSessionCoordinator.watchSave` (removed), the `historySaveBoundMs` constructor seam and the post-reservation `watchSave` (removed) call. The owner takes the observer and passes it to its finalizer. The Service passes `ModelBootstrapApplication.historySaves(applicationContext)`.
5. The rig builds its observer on a scope of its own (never the coordinator's), with the rig's clock, `rig.defects` and `rig.log`, and keeps a `historySaveBoundMs` parameter for the observer only.
6. A refused save is overload, not a broken contract (found by grounded round 1): `HistoryQueueFullException` extends `IllegalStateException`, so `TelemetryChannels.historySaveDefect` classifies a refusal as `HistoryContractViolation`, on top of the one `HistoryQueueOverloaded` the queue raises per overload episode (#292). `historySaveDefect` returns null for `HistoryQueueFullException` (a branch before the `IllegalStateException` one), so a refused save keeps its warning and breadcrumb and the queue's episode defect is the only defect. This is a deviation from REF-01's wording, taken because the move would otherwise carry a double report forward; it changes which defect a refusal raises and nothing else.

## 3. Tests

1. Row 1b `aSaveStillHeldWhenTheServiceIsDestroyedIsStillDiagnosed` (added before the change, RED on main 6cbd5fb: "never: the bound's defect after the Service is gone"). Made decisive (coverage finding C): the rig's clock is scripted; hold the save, complete the take, destroy the owner, and only THEN advance the clock past the bound; await the timeout defect. GREEN after the change. MUTATION m1: the rig hands the observer the coordinator's own scope, so `destroy` cancels the watch and the row goes RED.
2. Row 2b, a classified failure after destroy: hold a finalize that then throws `IllegalStateException` (classified `HistoryContractViolation`), destroy the owner, release it; assert the failure warning, the captured `history_save_failed` breadcrumb (the rig records the observer's breadcrumbs) and exactly one matching defect. MUTATION m2: the observer drops the failure report.
3. Row 2c, a throwing warning sink: an observer whose warn sink throws; a classified failure still produces its breadcrumb and its defect. MUTATION m3: one guard around all three sinks (the breadcrumb and defect are lost).
4. Keep the existing bound, late save, and failure rows passing through the observer. Extend `HistoryWriteQueueTest.aRefusedSaveAnswersItsSlotFailed` to wait, after the refused slot answer, for one failure warning and one `history_save_failed` breadcrumb. Add a direct classifier assertion that `historySaveDefect(HistoryQueueFullException())` is null, while `historySaveDefect(IllegalStateException())` remains `HistoryContractViolation`; the direct assertion is m4's red check. MUTATION m4: drop the §2.6 carve-out. Row 2c covers a throwing warning sink.
5. `PolishPublicationRoutesTest`: the lock still holds the reservation and `finalizer.enqueueSave(current.history, payload, saved, takeId)`; the owner source no longer names `watchSaveBound`.

## 4. Blast radius

Only diagnostics move; insertion, the take's facts and the History writes are unchanged. A take whose save never answers now keeps one small coroutine alive until the save answers (it waits on the save, as `watchSaveBound` does today, but no longer dies with the Service). Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Row 1b RED before and GREEN after; rows green, m1 to m4 RED; the suite green; app and androidTest build; checks clean.
- [ ] Emulator: a take lands its words and its row.
- [ ] Codex code review ALL-CLEAR with a confirming round.
