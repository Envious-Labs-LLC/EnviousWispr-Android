# Issue #280: the capture thread's heartbeat allocates nothing (2026-09-24)

GitHub issue: `#280`. Tier: MEDIUM (audio capture, the heart; one class on its hot path). Status: revised after the coverage round (`280-cov`): findings A1, A2, A3, B and C adopted; B by relaxing heartbeat order against queued events, with the owner's reason stated. Grounded round 1 (`280-g1`): all four findings adopted (Live before a later heartbeat preserved; a failed claim never advances the throttle; rows wait between heartbeats; close staged at the exit decision). Grounded round 2 (`280-g2`): all three adopted (a bounded drain, the exit read order, the throttle's one-caller assumption). Grounded round 3 (`280-g3`): the drain is replaced by one sequence counter and an O(1) peek. Pre-committed consequence: if the next round finds another order or fairness defect, the ordering machinery is deleted and heartbeat order against queued events is not promised at all (the cost, a recorder timer that can skip one second at the start when the worker is a second behind, is then stated in §4). Grounded round 4 (`280-g4`) found one more (the heartbeat's number was never published); the deletion is applied. Grounded round 5 (`280-g5`): two test findings adopted (a leaked count after a failed claim; queued events are not starved by heartbeats). Grounded round 6 (`280-g6`): row 8's reverse case starts its heartbeat chain; row 3 named as the leak proof.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Heart path (capture). Emulator: a spoken take lands its words, and the recorder's elapsed timer advances each second (the heartbeat is what drives it). The founder's phone gets the build through Play after the merge (rung 2).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he can see changes: the recorder's timer still ticks every second and a take still ends with his words. The capture loop, the one piece that must never stall, stops creating two small objects every second while he speaks, so the garbage collector has less reason to pause it.

---

## 0. TL;DR

REF-04 of `docs/audits/2026-09-23c-senior-audit.json` (`.claude/rules/architecture-rules.md` RULE: protect-audio-asr-stability). `TakeEventPublisher.offerTick`, called by `AudioCaptureService.captureLoop` after every positive read, allocates an `Event.Tick` and a `ConcurrentLinkedQueue` node once a second. Replace it with preallocated primitive fields and an atomic pending flag that the publisher's worker consumes, keeping every object construction and queue node on the worker. A heartbeat is no longer ordered against the three queued events, which keep their order among themselves (§2.2).

Consolidation: none. One publisher's heartbeat path changes; the other three events keep the queue.

Prior context: #115 (the publisher: one worker, lock-free offers, the owner's silence bound re-armed by every event), the #115 review that found the sentinel overflow in the throttle.

## 1. Grounding (main 820eaf9, after #279)

- `captureLoop` (`audio/AudioCaptureService.kt`): after each read with `bytesRead > 0`, `takeEvents.offerTick(active.takeId, <elapsed or 0>)`, before the gate branch.
- `TakeEventPublisher.offerTick(takeId, elapsedMs)`: a wall-clock throttle on `lastTickNanos` (sentinel `Long.MIN_VALUE`), then `offer(Event.Tick(takeId, elapsedMs))`. `offer` enters by a CAS on `lifecycle` (closed bit plus an entered count), `queue.offer(event)`, decrements, unparks the worker.
- The worker `drain()` polls the queue, parks when empty, leaves once closed with nothing entered and the queue empty; each event is delivered under `runCatching`.
- Producers (`git grep -n "takeEvents\.\(publish\|offer\)" -- app/src/main`): `offerTick` at `AudioCaptureService.kt:678` (capture loop); `publishLive` at `:694` (capture loop); `publishSilenceStatus` at `:543` (the detector's status callback); `publishEnded` at `:757` (a refused start) and `:903` (the take's teardown). Their threads are to be confirmed by the coverage round.
- The owner (`ui/CaptureSessionController.takeListener.onTick`) re-arms the silence bound and forwards `CaptureEvent.Tick`; `DictationSessionCoordinator.onTakeTick` ignores a tick unless RECORDING.
- `TakeEventPublisherTest.eventsArriveInTheOrderTheyHappenedUnderTheirTakesId` pins live, tick, silence, ended in offer order.

## 2. Design

1. The heartbeat slot: `tickTakeId: String?` and `tickElapsedMs: Long` (plain fields), and `tickState: AtomicInteger` with three states, IDLE, WRITING, READY. `offerTick` checks the throttle, then enters the lifecycle exactly as a queued offer does (`enter()`, the CAS that fails once `close` has set the closed bit), then claims the slot with `tickState.compareAndSet(IDLE, WRITING)`. On a successful claim it writes the two fields, sets READY (the release that publishes them) and only then advances the throttle (`lastTickNanos = now`). On a failed claim (the worker has not yet taken the previous heartbeat) it writes nothing and does NOT advance the throttle, so the very next positive read tries again. Either way it leaves the lifecycle (the decrement runs in a `finally`) and unparks the worker. Nothing on this path allocates, locks, logs or calls across a process: a reference store (the take id string already exists), a long store, two CAS, one decrement and one unpark. The CAS makes the slot safe under overlapping callers; each accepted heartbeat carries its own take id and the worker never reads a current-take field. One-per-second throttling assumes one `offerTick` caller at a time (one capture loop), as today; overlapping callers could exceed the rate, never corrupt a heartbeat.
2. No ordering promise between a heartbeat and the queued events (the pre-committed consequence of grounded round 4: the ordering machinery is deleted). The worker's pass: if the slot is READY, it reads both fields, sets IDLE and delivers the heartbeat; then it polls the queue and delivers one event; with nothing queued, its exit decision reads `lifecycle` FIRST, then `tickState`, then `queue.isEmpty()`, never reusing an earlier IDLE observation, and leaves only when closed, nothing is entered, the slot is IDLE and the queue is empty; else it parks. Consequences:
   - Each pass delivers at most one heartbeat and one queued event, so neither can starve the other.
   - A heartbeat may be delivered before or after any queued event. The owner's handling: `CaptureSessionController.takeListener.onTick` re-arms the silence bound (armed until `disarm`) and forwards `CaptureEvent.Tick`; `DictationSessionCoordinator.onTakeTick` returns unless the state is RECORDING. So a heartbeat delivered after Ended changes nothing but the bound, and one delivered before Live is ignored; the cost is in §4.
   - A heartbeat accepted before `close` is delivered before the worker exits: its READY is set before its lifecycle decrement, and the exit decision reads `lifecycle` before `tickState`, so observing the final decrement means also observing READY unless the worker already cleared the slot. One offered after `close` is refused by `enter()`.
3. `resetTicks` (the start caller's thread) keeps its one volatile write. A previous take's heartbeat still in the slot is delivered under its own take id (the owner discards it by id); the new take's first heartbeat is claimed on the first positive read after the worker takes the old one, because a failed claim never advances the throttle (grounded finding 2).
4. The class doc and `offerTick`'s doc say what the positive-read path does, the relaxed order (§2.2), and the skip: a heartbeat is not sent while the previous one is still in the slot; the one in the slot is still delivered.

Producer threads (coverage finding A2): `offerTick` and `publishLive` run in `captureLoop` on the capture thread; the teardown `publishEnded` follows that loop; the refused-start `publishEnded` and `resetTicks` run on the start caller's thread; `publishSilenceStatus` runs on the detector's callback thread (identity not relied on).

## 3. Tests

`TakeEventPublisherTest`; every row that offers a second heartbeat waits for the first delivery before advancing the fake clock (grounded finding 3), and asserts one delivery per accepted heartbeat:
1. The throttle row and the order row, adjusted that way; the order row keeps live, silence, ended in order and asserts the heartbeat is delivered, at any position.
2. A heartbeat never touches the queue: with a recording queue, accepted heartbeats cause zero queue offers and the listener receives each `tick`. MUTATION m1: `offerTick` offers an `Event.Tick` to the queue again. The capture loop's heartbeat line is covered by review, not by this row (coverage finding A3).
3. A heartbeat in the slot is not overwritten, and a skipped one retries: hold the worker inside an earlier delivery, offer heartbeat A, advance the clock a second, offer B (skipped), release and await A; then, with the clock unchanged, offer C: it is accepted at once (the throttle did not advance on B). The listener receives exactly A then C, each intact. Then close the publisher and join the worker (read by reflection, as the existing close row does) with a bounded wait: a leaked entered count from B's failed claim keeps the worker alive and fails the row (grounded round 5 finding 1). MUTATION m2: `offerTick` writes the fields without claiming the slot. MUTATION m5: a failed claim advances the throttle (C is not sent). MUTATION m8: a failed claim returns without leaving the lifecycle.
4. A take change with the old heartbeat in the slot: hold the worker, offer t1's heartbeat, `resetTicks`, offer t2's (skipped), release and await t1's; offer t2's again: delivered under t2 with t2's elapsed. Covered by m2 and m5.
5. Close waits for an accepted heartbeat, staged at the exit decision (grounded finding 4): a test queue whose `poll` on an empty queue blocks on a latch holds the worker after its slot check; the test offers a heartbeat (accepted, READY), calls `close()`, then releases `poll` to return null; the worker must deliver the heartbeat and exit. MUTATION m3: the exit condition omits the slot (the worker exits and the heartbeat is lost).
6. A heartbeat after close is refused: hold the worker, `close()`, offer a heartbeat, release; no heartbeat is delivered and the worker exits. MUTATION m4: `offerTick` skips `enter()`.
7. The owner ignores a heartbeat outside RECORDING (the stated owner behaviour), in `DictationSessionCoordinatorTest` with the rig: a heartbeat before Live and one after the take's Ended update no elapsed second; one while recording does. The rig's surface records `updateElapsed` in a list of its own (today a no-op), leaving its event list unchanged. MUTATION m6: `onTakeTick` drops its RECORDING check.
8. Neither starves the other: hold the worker in an earlier delivery, queue three silence statuses and offer a heartbeat, release; the heartbeat is delivered within the first two deliveries after the release. A second case (grounded round 5 finding 2): while the worker is held, queue three statuses and offer the first accepted heartbeat. On each tick callback, advance the fake clock a second and offer another accepted heartbeat, up to 50. After release, assert a status arrives between heartbeats and all three statuses arrive. MUTATION m7: the worker drains the queue until empty before taking the slot. MUTATION m9: the worker delivers every READY heartbeat before polling the queue again (loops on the slot).

A failed claim still leaves the lifecycle: row 3's bounded join after close is that proof (m8).

## 4. Blast radius

The capture thread's heartbeat path only. Stated costs: (1) a heartbeat is skipped while the previous one is still in the slot, and the next positive read retries, so the owner's silence bound (`CaptureSessionController.TAKE_SILENT_BOUND_MS`, 3 s) is re-armed whenever the worker delivers, as today when ticks queue behind a slow delivery; (2) with no ordering, when the worker is at least a second behind at the start of a take, a heartbeat can reach the owner before Live and be ignored, so the recorder's elapsed timer can skip one second at the start; the next heartbeat corrects it. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m9 RED; the class 20/20; the suite green; app and androidTest build.
- [ ] Emulator: a spoken take lands its words, and the recorder's elapsed second advances during it.
- [ ] Codex code review ALL-CLEAR with a confirming round.
