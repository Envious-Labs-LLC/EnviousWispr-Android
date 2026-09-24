# Issue #343: a take's Live never allocates on the capture thread, even when an earlier Live is undelivered (REF-01, regrade 7) (2026-09-24)

GitHub issue: `#343`. Tier: MEDIUM. Status: revised after the coverage round (`343-cov`), all findings adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take goes live and lands its words. The allocation claim is pinned by source, and the slot behaviour by staged JVM rows.

## Preface: User Rubric

Persona: the founder dictating. `architecture-rules.md` RULE: protect-audio-asr-stability. Since #327 a take's Live goes through one preallocated slot. When that slot still holds an earlier take's undelivered Live, `TakeEventPublisher.publishLive` falls back to building an `Event.Live` and a queue node on the capture thread.

## 0. TL;DR

When the slot is still occupied at the next take's first live block, the event worker is behind, and the earlier take's Live is stale:

- One capture runs at a time.
- The worker delivers a waiting Live before any event polled after it, so that take's ending has not been delivered either.
- Its owner is therefore gone, or no longer the owner of the live take, and every owner discards events that name another take.

Design (replacing the queue fallback):

1. Two Live slots, each with states IDLE, WRITING, READY and READING, and a sequence number written by the one producer (the capture thread).
2. `publishLive` claims the first slot it can with one compare-and-set: IDLE to WRITING, or READY to WRITING. The second case replaces that slot's stale Live. It writes the fields and the next sequence, then sets READY. A slot the worker is READING is skipped, and the worker reads one slot at a time, so the other slot can always be claimed. There is no wait, no allocation and no queue.
3. The worker takes a slot with a compare-and-set, READY to READING, copies its fields, sets it IDLE, then runs the hook and the binder call. With two READY slots it delivers the lower sequence first. A waiting Live still goes before any queued event it polls after it.
4. The `Event.Live` class and its queue case are deleted (GR-MIGRATION-COMPLETE).

The hook (`onDelivered`) of a replaced stale Live never runs. It posts that route's announce, whose deadline already found the gate open or the take gone.

## 1. Tests

- Staged: take A's Live is held in slot 0 while the worker is blocked. Take B's Live goes to slot 1. Take C's Live then replaces the stale READY slot. Every Live that is delivered arrives in sequence order. B and C arrive, and C's hook runs. MUTATION m1: the worker reads a slot without the READING claim, so the replace is torn. MUTATION m2: `publishLive` no longer replaces a READY slot, so C is lost.
- Drift guard (`CaptureThreadPathTest`): `publishLive` has no `Event.Live(`, no `queue.offer(` and no loop. MUTATION m3: the queue fallback comes back.
- The existing publisher rows (order, close, the hook) stay green.

## Results (2026-09-24)

- Coverage round (`343-cov`), adopted:
  - The stale-Live argument holds for the app's session owner, and the doc scopes it that way.
  - The worker claims and copies each READY slot, releasing it before the next, and only then orders the copies by sequence. Choosing before claiming would race a replace.
  - The exit waits for both slots to be IDLE.
  - A superseded stale Live and its hook are discarded by contract.
  - The 500-take soak now asserts publish order among the Lives that arrive, and that the newest take's Live always arrives. The staged row checks the replace.
- Found while building: the first claim loop tried both claims on slot 0 before slot 1, so a second Live replaced the first while slot 1 was free. `publishLive` now claims a free slot first and replaces only when both are full, the older first. The drift guard pins that order.
- MUTATIONS RED: m2 (no replace), m3 (the queue fallback back), m4 (delivery by descending sequence) and m5 (the hook dropped), each on a fresh compile (`-Pkotlin.incremental=false`) with the old result file removed first. An earlier m4 form did not compile and first read as NOT RED from a stale result. The script now reports a build failure separately. m1 (a read without the READING claim) is not staged: a torn read cannot be forced deterministically on the JVM, so the claim is pinned by the design and the review.
- Suite 1391, 0 failures; `TakeEventPublisherTest` 20 of 20; visibility and citation checks clean.
- Emulator (`343-uat.py`): a spoken take into Gmail went live and landed by COMMIT with exactly the expected text.
- Code review round 1 (`343-r1`):
  - Finding 1 adopted: the worker could empty both slots between the free-slot pass and the replace pass, so both replace claims failed and the Live was lost. A final free-slot pass follows; one slot is then always free, because the worker holds at most one READING and only the capture thread makes a slot READY. It is staged through a capture-thread seam (`afterFreePass`, a no-op in production) in `aLiveStillLandsWhenTheWorkerEmptiesBothSlotsBetweenThePasses`; m6 RED.
  - Finding 2 adopted: the order doc now says a retained Live precedes a queued event polled after it, that earlier queued events may be overtaken, and that a superseded Live is never delivered.
  - Finding 3 came from the branch predating the #348 merge; the branch is rebased onto origin/main.
