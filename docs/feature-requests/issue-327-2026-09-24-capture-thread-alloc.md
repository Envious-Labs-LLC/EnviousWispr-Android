# Issue #327: going live allocates nothing on the capture thread (REF-03, regrade 6) (2026-09-24)

GitHub issue: `#327`. Tier: SMALL. Status: built 2026-09-24, before the coverage round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take through the harness proves the take still goes live and lands. The audit asks for an allocation trace on the S26, which is excluded from agent UAT; that trace is the founder's to run, and this plan does not claim it.

## Preface: User Rubric

Persona: the founder dictating. `architecture-rules.md` RULE: protect-audio-asr-stability says the capture thread never allocates. On the first admitted block of every take it did twice: `TakeRoute.markLive` posted a new lambda, and `TakeEventPublisher.publishLive` built an `Event.Live` and a queue node. That is the read where capture timing is most sensitive.

## 0. TL;DR

- `TakeRoute.markLive` posts a prebuilt `announceLive` runnable, built with the route. The route thread still cancels the deadline and logs the line. The platform handler takes its message from its own pool.
- `TakeEventPublisher.publishLive` writes a preallocated Live slot (claim IDLE to WRITING, five fields, READY), like the heartbeat slot (#280). It falls back to the queue, which allocates, only when the slot still holds an earlier take's undelivered Live.
- The worker delivers a waiting Live before any queued event it polls after it, so a take's Live still precedes its silence status and ending. Its exit waits for the Live slot too.

## 1. Tests

- `CaptureThreadPathTest.goingLiveAllocatesNothingOnTheCaptureThread` is a drift guard on the source. MUTATION m1: `markLive` posts a lambda again. MUTATION m2: `publishLive` builds the event before the claim.
- `TakeEventPublisherTest.aLiveOfferedDuringThePollStillPrecedesTheEndingPolled` is staged. The test queue holds the worker's first poll, after it found the slot empty, until the take's Live and ending are offered. MUTATION m3: the worker delivers a polled event before a waiting Live. `aTakesLiveAlwaysArrivesBeforeItsEnding` offers 500 takes back to back. That one is a soak row, not m3's witness: the race is too rare to catch by chance, as m3's first run showed.

## Results (2026-09-24)

- MUTATIONS m1, m2 and m3 RED (`327-mut.py`). The first m3 run showed the 500-take soak cannot catch the race, so m3 has a staged row.
- Suite 1384, 0 failures; `TakeEventPublisherTest` 20 of 20; visibility and citation checks clean.
