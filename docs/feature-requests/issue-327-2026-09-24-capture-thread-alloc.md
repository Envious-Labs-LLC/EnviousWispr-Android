# Issue #327: going live allocates nothing on the capture thread (REF-03, regrade 6) (2026-09-24)

GitHub issue: `#327`. Tier: SMALL. Status: built 2026-09-24, before the coverage round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take through the harness proves the take still goes live and lands. The audit asks for an allocation trace on the S26, which is excluded from agent UAT; that trace is the founder's to run, and this plan does not claim it.

## Preface: User Rubric

Persona: the founder dictating. `architecture-rules.md` RULE: protect-audio-asr-stability says the capture thread never allocates. On the first admitted block of every take it did twice: `TakeRoute.markLive` posted a new lambda, and `TakeEventPublisher.publishLive` built an `Event.Live` and a queue node. That is the read where capture timing is most sensitive.

## 0. TL;DR

- `TakeRoute.markLive` writes the clock and nothing else. The route's prebuilt `announceFromWorker` travels with the Live event, and the take-event WORKER posts it (review round 1: a `Handler.post` takes the message queue's lock and may allocate its message). The route thread still cancels the deadline and logs "route live=". A deadline that fires first finds the gate open and does nothing. A forced gate announces itself, as before.
- `EffectiveDevice`'s three capture-thread reads (`kind`, `currentKind`, `reasonCode`) are volatile and lock-free (review round 1). Its history label stays synchronized, and the route thread reads it.
- `TakeEventPublisher.publishLive` writes a preallocated Live slot (claim IDLE to WRITING, five fields, READY), like the heartbeat slot (#280). It falls back to the queue, which allocates, only when the slot still holds an earlier take's undelivered Live.
- The worker delivers a waiting Live before any queued event it polls after it, so a take's Live precedes every event of that take offered after it. A silence status published as capture starts was offered earlier, and it still arrives first, as before (review round 1's second finding, answered by this narrower claim with no code change). The fallback stays inside the same lifecycle entry, so a close cannot drop an accepted Live (round 1). The exit waits for the Live slot too.

## 1. Tests

- `CaptureThreadPathTest.goingLiveAllocatesNothingOnTheCaptureThread` is a drift guard on the source. MUTATION m1: `markLive` posts a lambda again. MUTATION m2: `publishLive` builds the event before the claim.
- `TakeEventPublisherTest.aLiveOfferedDuringThePollStillPrecedesTheEndingPolled` is staged. The test queue holds the worker's first poll, after it found the slot empty, until the take's Live and ending are offered. MUTATION m3: the worker delivers a polled event before a waiting Live. `aTakesLiveAlwaysArrivesBeforeItsEnding` offers 500 takes back to back. That one is a soak row, not m3's witness: the race is too rare to catch by chance, as m3's first run showed.

## Results (2026-09-24)

- MUTATIONS m1, m2 and m3 RED (`327-mut.py`). The first m3 run showed the 500-take soak cannot catch the race, so m3 has a staged row.
- Suite 1384, 0 failures; `TakeEventPublisherTest` 20 of 20; visibility and citation checks clean.
- Code review round 1 (`327-r1`): finding 1 (the fallback left its lifecycle entry before `offer`) adopted as given. Finding 2 (a status can precede Live) answered by narrowing the order claim to events offered after Live: the order of events offered before Live is unchanged by #327, and each event names its take. Finding 3 adopted: the worker posts the announce, and the device reads are lock-free (m4, m5 added).
- After round 1: MUTATIONS m1 to m5 RED; suite 1385, 0 failures; `TakeEventPublisherTest` 20 of 20; visibility and citation checks clean.
- Emulator after round 1 (`327-uat.py`), on the build where the worker posts the announce: the first take recorded silence, with 102 `pcm_readi failed` lines (the #273 microphone race). The next take went live ("route live=Phone after 87 ms", logged on the route thread from the worker's post), and its words landed by COMMIT with exactly the expected text. Before round 1 one take also landed (`route live=Phone after 1 ms`). The S26 allocation trace the audit asks for is not run: the phone is excluded from agent UAT, so it is the founder's follow-up.
