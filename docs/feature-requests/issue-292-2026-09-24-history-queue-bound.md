# Issue #292: give History writes an overload result (2026-09-24)

GitHub issue: `#292`. Tier: MEDIUM (the per-take History write queue; a limb). Status: revised after the coverage round (`292-cov`) and grounded round 1 (`292-g1`), every finding adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a normal take still saves its History row (the queue never fills on a healthy disk; overload is a JVM row with a stalled repository).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing changes on a healthy phone. If the History database stalls, today every take keeps adding writes to a queue with no limit, in memory, forever. After this change the queue holds a bounded backlog; past it, History writes are refused (History is a limb), insertion and clipboard delivery continue without waiting for History (#277; if both of those fail, #288 owns that loss), and one defect says the database is stuck.

---

## 0. TL;DR

REF-03 of `docs/audits/2026-09-23d-senior-audit.json` (named principle: background work queues need a finite capacity or an explicit overload policy). `HistoryWriteQueue` uses `Channel.UNLIMITED`. Bound it, return a typed `Enqueued.ACCEPTED | REJECTED` (proposed), give every caller an explicit answer to a refusal, and raise one content-free defect per overload episode.

Consolidation: none. One queue gains a capacity and a result; its nine call sites each handle a refusal.

Prior context: #115 (one application queue, enqueue order is write order, `enqueue` never suspends or blocks), #277 (insertion never waits on History; outcomes resolve the row on the queue), #235 (the save's diagnostic bound).

## 1. Grounding (main 944b6a7)

`HistoryWriteQueue.enqueue(label, body): Boolean` is `writes.trySend(...).isSuccess` on an UNLIMITED channel; every caller ignores the result. Callers (`git grep -n '.enqueue("'` in `app/src/main`): `TakeHistory.insertDraft`, `markStatus`, `discard`, `markInterrupted`; `SessionFinalizer.enqueueSave` (finalize), the promotion, the clipboard-only and history-only outcomes; `AccessibilityInsertionRunner`'s insertion outcome. The queue is built once per process in `ModelBootstrapApplication.historyWrites`, and by the test rig.

A danger specific to a refusal: `TakeHistory.resolvedId()` awaits the draft's deferred; a REFUSED draft insert that never completes it would make every later write of that take wait forever on the worker, wedging the whole queue.

## 2. Design

1. `HistoryWriteQueue(repository, scope, warn, capacity = HISTORY_QUEUE_CAPACITY, ordinaryLimit = HISTORY_QUEUE_ORDINARY_LIMIT, onOverload)`, requiring `0 < ordinaryLimit < capacity` (defaults 64 and 48); the limit check, the send, the pending count and the episode state are guarded together: `enqueue(label, kind, body)` still never suspends or blocks and returns `Enqueued.ACCEPTED` or `REJECTED`. `HISTORY_QUEUE_CAPACITY` (proposed) = 64 writes (a take enqueues about eight). Two tiers (coverage finding A): a `WriteKind.ORDINARY` write (draft insert, `markStatus(STATUS_PROCESSING)`, promotion) is refused once 48 writes are pending (`markStatus` of any other status, today `STATUS_ASR_ERROR`, is terminal); a `WriteKind.TERMINAL` write (finalize, the three insertion outcomes, discard, interrupted) may use the full 64, so a take's last word is refused only in a far deeper stall. Accepted writes keep enqueue order. The pending count is incremented on acceptance and decremented after each write finishes (a guarded counter, not the channel's own size).
2. Overload episode (coverage finding B): starts at the first refusal and ends only once the worker has finished the whole accepted backlog (pending back to 0); `onOverload` runs once per episode, guarded across concurrent callers. Production: one `AppDefect.HistoryQueueOverloaded` (proposed), nothing but the fixed id.
3. Every caller answers a refusal:
   - `insertDraft`: completes the draft's deferred exceptionally at once (`HistoryQueueFullException`, proposed), so `resolvedId()` answers 0 and nothing waits on it; the take's finalize then inserts its own saved row.
   - `enqueueSave`: answers the `SaveSlot` with `SaveOutcome.Failed(HistoryQueueFullException)` at once.
   - The runner's insertion outcome: emits its terminal telemetry without a write.
   - `markStatus`, `discard`, `markInterrupted`, the promotion and the two owner outcomes: the refusal is logged by label.
4. The acceptable outcome, stated (coverage finding A): a refused write may leave a History row wrong or missing (a wordless draft the start-up recovery marks interrupted, a neutral row recovery reads as delivery unknown). A refused save does not by itself protect the words: they are preserved by insertion or the clipboard, which never wait on History (#277); the case where both of those also fail is #288.
5. No write is retried.

## 3. Tests

1. `HistoryWriteQueueTest`: a repository whose first write stalls on a latch, `capacity = 4` (ordinary limit 3): the row waits until the worker has ENTERED the held write, then fills the queue; an ordinary write past its limit is `REJECTED` while a terminal write is still `ACCEPTED`; past the full capacity a terminal write is `REJECTED`; `enqueue` never blocks; `onOverload` runs once for the several refusals; after the release exactly the accepted labels land, in enqueue order, and no rejected label ever lands; a second held write and a new refusal start a second episode (`onOverload` twice in total). MUTATION m1: `Channel.UNLIMITED` and no count (never rejects); MUTATION m2: `onOverload` on every refusal; MUTATION m5: terminal writes held to the ordinary limit.
2. `TakeHistory` with a full queue: `insertDraft`'s deferred completes with `HistoryQueueFullException` specifically, within a bounded wait, and `resolvedId()` answers 0. MUTATION m3: the refusal leaves the deferred open (the bounded wait fails).
3. `enqueueSave` with a full queue answers the slot `SaveOutcome.Failed(HistoryQueueFullException)` specifically, within a bounded wait. MUTATION m4: the refusal is ignored.
4. The existing `HistoryWriteQueueTest` assertion on the Boolean result compares with `Enqueued.ACCEPTED`.
5. `HistoryQueueOverloaded` joins `AppDefect.all()`, both `SentrySchema` lists and the fingerprint snapshot.

## 4. Blast radius

Healthy phones: none (the queue drains in milliseconds; 64 is far above one take's writes). A stalled database: memory stays bounded, History writes past the backlog are lost (recovery reads the affected rows as today), insertion and clipboard delivery continue without waiting for History (#288 owns both failing), one defect per episode. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m5 RED; the suite green; app and androidTest build.
- [ ] Emulator: a normal take saves its row.
- [ ] Codex code review ALL-CLEAR.

## 6. As built (2026-09-24)

- `HistoryWriteQueue`: `Channel(capacity)`; `enqueue(label, kind, body): Enqueued` checks the outstanding count against the kind's limit and sends under one lock; the worker decrements after each write and ends the episode at zero; `onOverload` runs outside the lock, once per episode, and cannot throw into a caller. `Enqueued`, `WriteKind` and `HistoryQueueFullException` live beside it. The default `kind` is ORDINARY, the stricter tier.
- Callers: draft insert, `markStatus(STATUS_PROCESSING)` and the promotion are ORDINARY; finalize, the three insertion outcomes, discard, interrupted and any other status are TERMINAL. A refused draft completes its deferred with `HistoryQueueFullException`; a refused save answers its `SaveSlot` `Failed(HistoryQueueFullException)`; a refused runner outcome emits once. Production's queue raises `AppDefect.HistoryQueueOverloaded` (in `DefectIdentity.all()`, both `SentrySchema` lists and the snapshot).
- Mutation receipts: `docs/audits/2026-09-24-292-mutation-receipts.txt`, 5 of 5 RED. Full unit suite: 1295 tests, 0 failures. `HistoryWriteQueueTest` 20 of 20. App and androidTest build; `check-visibility.py` clean.
- Emulator: NOT RUN for a spoken take (the emulator's audio input is failing, #273). A healthy queue never refuses (64 is about eight takes of backlog), so the normal path's only change is the result type its callers now read.

## 7. Related

#115, #277, #235; REF-03 of the fourth 2026-09-23 audit.
