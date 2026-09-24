# Issue #277: insertion never waits on History (2026-09-23)

GitHub issue: `#277`. Tier: LARGE (the insertion path; supersedes part of #235). Status: revised after the coverage round (`277-cov`) and grounded round 1 (`277-g1`), every finding adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Emulator: a Gmail take lands in the editor (spoken takes are blocked by #273; `debug_insert` does not pass through the owner, so the owner path is covered by `DictationSessionRig` rows and the founder's phone pass through Play).

## Preface — User Rubric

User Rubric: persona, the founder dictating into Gmail on his S26. Today every take waits for its History row to be written before the words are pasted; if that write takes longer than one second the words go to the clipboard instead of his editor, with a notice. After this change the words are pasted as soon as they are final, whatever History is doing; History records the take when its write lands, and a failed History write is a defect, never a lost paste.

---

## 0. TL;DR

REF-01 of `docs/audits/2026-09-23c-senior-audit.json`. `architecture-rules.md` FACT: heart-and-limbs puts insertion in the heart and History among the limbs, but `SessionFinalizer.deliver` routes by `HistoryPublicationPolicy.route(persistedId, persistenceSucceeded)` (removed): no durable row id, no insertion. #235 bounded that wait at 1 s and sent the words to the clipboard on a timeout. This change removes the dependency: the owner hands the words to insertion at once, and the paste service resolves the row id on the History queue, where every write of the row already runs in order.

Consolidation: the dominant root is "insertion needs the row id before it starts"; its one owner becomes `TakeHistory` (the take-scoped row handle, resolved on the queue); consolidation sites: `SessionFinalizer.deliver`, `DictationSessionCoordinator.publishResult`'s save wait, `InsertionGateway`/`PasteAccessibilityService.pasteWhenTargetReturns`, `AccessibilityInsertionRunner`'s outcome write and announcement. `HistoryPublicationPolicy` (removed) and the #235 timed-out copy reconciliation are deleted, not bypassed.

Prior context: #235 (`docs/feature-requests/issue-235-2026-09-23-bounded-history-save.md`) rejected "insert before the save" only because the paste service reports its outcome to the row id (its section on alternatives, (a)); #115 put every History write of a row on one application queue, in order; `TakeHistory.insertDraft` writes the draft row at take start and `resolvedId()` answers on the queue.

## 1. Grounding (main 94db48c)

- `DictationSessionCoordinator.publishResult`: enqueues the finalize save (`finalizer.enqueueSave(current.history, payload, saveGate, saved)` (removed)), then in `scope.launch` awaits `saved` for up to `HISTORY_SAVE_BOUND_MS` (1 000), claims `saveGate` (removed) on a timeout, records `takeFacts.historySave`, commits `COMPLETED`, and calls `finalizer.deliver(..., saveOutcome, ..., saveGate)` (removed).
- `SessionFinalizer.deliver`: `persistedId = (saveOutcome as? Saved)?.id ?: 0`; `HistoryPublicationPolicy.route` (removed) picks `AUTO_INSERT` (removed) only with a durable id, else `InsertionHandoff.HISTORY_NOT_DURABLE` (removed); a non-scheduled handoff copies to the clipboard when auto-copy is on or `persistedId <= 0` (`mustPreventDataLoss` (removed)), announces, and (on a timed-out save) reconciles the late row (`reconcileCopy` (removed)).
- `SessionFinalizer.enqueueSave`: on the queue, `history.resolvedId()` (the draft) is finalized to `STATUS_SAVED_UNROUTED`, or a new saved row is inserted; `history.remember(id)`. After a handoff the owner enqueues the promotion to `ready_for_insertion` (#235).
- `AccessibilityInsertionRunner`: `PendingInsertion.transcriptId: Long`; the outcome write is enqueued on `ModelBootstrapApplication.historyWrites` and calls `repository.finalizeInsertionOutcome(pending.transcriptId, ...)` (first-wins on `ready_for_insertion` or `saved_unrouted` with `insertionResult = 'pending'`); `transcriptId <= 0` emits the telemetry without a write; the announcement's `savedInHistory = transcriptId > 0`.
- Queue order already guarantees: draft insert (take start) < finalize (publication) < promotion (after handoff) < insertion outcome (after the paste). The paste itself never touches the database.

## 2. Design

1. **A row handle instead of a row id.** `interface HistoryRow { fun resolveOnQueue(): Long; val savedNow: Boolean }`. `TakeHistory` implements it and records the finalize ANSWER separately from the draft id: `resolveOnQueue()` returns the finalized or inserted id only when the finalize answered `Saved`, otherwise 0 (a draft id after a failed finalize is not a saved row; coverage finding A). It is called only inside queued writes, behind the finalize. `savedNow` is true once the finalize answered `Saved`, false while pending or after a failure.
2. **The owner never waits on the save before the handoff.** `publishResult` enqueues the save as today, commits `COMPLETED`, and calls `finalizer.deliver` at once. `takeFacts.historySave` records the save's answer if it is already in, else `pending`; the committed facts are never mutated afterwards. The diagnostic bound (1 s) starts when the save is enqueued; an independent observer reports `HistorySaveTimedOut` once if the save is unanswered at the bound and any eventual failure once (`historySaveDefect` and its breadcrumb); it never changes delivery or committed facts. Deleted: `HistorySaveGate` (removed), the delivery role of `SaveOutcome.TimedOut`, `reconcileCopy` (removed), the timed-out-copy DAO and repository methods and their tests.
3. **`deliver` always hands nonblank final text to insertion.** `HistoryPublicationPolicy.kt` (removed) and its test are deleted; `Delivery` loses its route field and the route-based log branch; `InsertionHandoff.HISTORY_NOT_DURABLE` (removed) is deleted from the enum, its message handling, its telemetry mapping and their tests. On every non-scheduled handoff the owner copies when `autoCopyToClipboard || !row.savedNow`, then enqueues its measured outcome (`keepOnClipboard` / `keepInHistoryOnly`) through the same queue-time resolver.
4. **The runner resolves the id on the queue.** `PendingInsertion.row: HistoryRow`; the outcome write calls `row.resolveOnQueue()` inside the enqueued lambda. `0` (the save failed) emits the insertion telemetry once without a write. A nonzero id keeps today's first-wins rule: a zero-row update because recovery or another writer won first emits nothing. The announcement's `savedInHistory` reads `row.savedNow`.
5. **Promotion.** Still enqueued after a scheduled handoff and still conditional on `saved_unrouted`/pending; it resolves the id on the queue. Promotion and the insertion outcome are both queued after the finalize but may land in either order; both stay conditional first-wins writes, so either order ends in the same row.
6. **Recovery** (`TranscriptDao.recoverStaleReadyRows` and the #235 neutral-row scan) is unchanged. The `transcriptId` fields of the overlay, onboarding and the take journal are separate consumers with no signature change; each is confirmed in the build sweep.

Files expected to change beyond section 1 (coverage sweep): `TranscriptRepository.kt`, `TranscriptDao.kt`; tests `HistoryPublicationPolicyTest.kt` (removed) (deleted), `AutoPasteWiringTest.kt`, `PasteServiceShapeTest.kt`, `PolishPublicationRoutesTest.kt`, `SessionOwnerShapeTest.kt`, `HistoryWriteQueueTest.kt`, `TranscriptRouteDaoTest.kt`, `DictationSessionCoordinatorTest.kt`.

## 3. Tests

Rig rows (`DictationSessionRig` has `holdFinalize`, `failFinalize` and `failInserts`; this change adds a gate on the saved-row `insert()` path and a fake insertion that enqueues its outcome through the handle):
1. A held finalize: the insertion request arrives BEFORE the held finalize is released; `takeFacts.historySave = pending`; the fake insertion's outcome, enqueued through the handle, lands on the finalized row after the release. MUTATION m1: `deliver` routes by `saveOutcome is Saved` again.
2. A failed finalize: a draft is established first, then `failFinalize`; the draft stays unfinalized, the resolver returns 0, and the runner seam emits exactly one terminal insertion event. (Not `failInserts`: that can fail the draft before any finalize.) MUTATION m2: the zero branch skips the emit.
3. Queue-time resolution: the draft insert fails, the saved-row `insert()` is held by the new gate; the insertion request happens; the gate is released; the outcome updates the new row id. MUTATION m3: `resolveOnQueue` is read when the paste is requested instead of inside the queued write.
4. A failed handoff with auto-copy off and the save still pending copies to the clipboard. MUTATION m4: the copy condition reads only `autoCopyToClipboard`.
5. Promotion and outcome in both orders: each writer is gated; the row asserts the actual enqueue and write order in each case, then the same terminal row state.
6. #235 rows: rewritten, the timeout-copy, late-reconciliation, save-failure-copy and save-versus-bound rows (the words are handed to insertion); kept and adapted, the destroy, promotion and recovery rows; deleted with their code, the timed-out-copy reconciliation rows. Each is named in the as-built notes.

## 4. Blast radius

The heart's insertion path. On a normal day the paste starts up to the save's latency earlier (the save is a local Room write, typically milliseconds; the bound was 1 s). On a slow or failed save the words land in the editor instead of the clipboard, and History records the take when (if) its write lands. Recovery after a process death is unchanged. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m4 RED; the suite green 20 times for the new rig rows; app and androidTest build.
- [ ] Emulator UAT as the preface says; Play build to the founder's phone.
- [ ] Codex code review ALL-CLEAR.

## 6. As built (2026-09-23)

- `history/HistoryRow.kt` (removed names below are marked): `TakeHistory` implements it with a `savedId` written only by `remember` (the save answered SAVED); `resolveOnQueue()` returns it, `savedNow` reads it. The draft id is never an insertion target.
- `DictationSessionCoordinator.publishResult` enqueues the save, commits and calls `finalizer.deliver(takeId, current.targetPin, payload, current.history, sessionPreferences.clipboard)` at once; `takeFacts.historySave` is `ok`, `failed` or `pending`; `watchSave` reports `HistorySaveTimedOut` and a failure's breadcrumb and defect, never changing delivery.
- `SessionFinalizer.deliver` always hands the words to insertion; the copy is forced when `autoCopyToClipboard || !row.savedNow`; the owner's clipboard and history-only outcome writes and the promotion resolve the row on the queue.
- `AccessibilityInsertionRunner`: `PendingInsertion.row`; the outcome write goes through the new top-level `recordInsertionOutcome(id, write, emit)`, the JVM seam for row 2. The two debug probes pass `HistoryRow.None`.
- Deleted: the History route policy and its test, the save gate, the late-copy reconciliation, the History-not-durable handoff member (enum, message, telemetry), the timed-out-copy DAO and repository method and its androidTest row, and `BoundedHistorySaveTest` (removed) (its surviving rows moved to `HistoryNeverHoldsTheWordsTest`: promotion after a scheduled handoff, a stalled promotion, a non-scheduled handoff never promoted, the no-draft neutral row, both recovery rows; rewritten: stuck save, failed save, copy with auto-copy off, failed copy, a save that never answers, destroy before the commit; deleted with their code: the timeout race and the late-copy reconciliation rows).
- Rewritten elsewhere: `DictationSessionCoordinatorTest.historySaveFailureStillHandsTheWordsToInsertion`; source rows in `AutoPasteWiringTest`, `SessionOwnerShapeTest` (eight History writes) and `PolishPublicationRoutesTest`.
- Deviation: row 2's "the failure is diagnosed once" is asserted by the log line, not by counting defects (a storage failure is a breadcrumb, not a defect, in `TelemetryChannels.historySaveDefect`).
- Code review round 1 (both findings adopted): `watchSave` measures the bound from the save's enqueue time (`host.elapsedRealtimeMs()` read inside the reservation) and reports a save whose answer is observed past it; recovery reads a stale `processing` row as delivery unknown (`TranscriptDao.recoverStaleProcessingRows`, counted with the neutral rows), because its words may already have been handed over; a stale `draft` stays not attempted. Its androidTest row ran on the emulator with `am instrument` (`TranscriptRouteDaoTest`, 4 tests OK). The review's third note (a non-scheduled handoff whose clipboard copy and save both fail) predates this change and is #288.
- Code review round 2 (same class as round 1's first finding: when "late" is measured; the class closed as a whole): the History worker stamps the save's answer (`SaveAnswer(outcome, answeredAtMs)`), and one rule, `saveMissedBound(enqueuedAtMs, answeredAtMs, boundMs)`, decides late from the enqueue and that stamp, or no answer at the deadline. The watcher's own start or wake time never enters it; the timeout is reported once. Every measurement point of the class: the wait's deadline (from the enqueue), an observer that starts after the deadline (looks, never waits), an answer seen during the wait (its stamp), an answer after the deadline (its stamp, deduplicated).
- Mutation receipts: `docs/audits/2026-09-23-277-mutation-receipts.txt`, 7 of 7 RED in one final run. Full unit suite: 1273 tests, 0 failures. `HistoryNeverHoldsTheWordsTest` and `DictationSessionCoordinatorTest` each 20 of 20 runs green. App and androidTest build; `check-visibility.py` clean.
- Emulator: a debug insert through the paste service with no History row landed in Gmail by COMMIT (verified by the app's own outcome line). A spoken take through the owner is NOT RUN (#273).

## 7. Related

#235, #115, #176; REF-01 of the third 2026-09-23 audit.
