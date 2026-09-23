# Issue #257: the warm hold observes its silence writer's exit, bounded (2026-09-23)

GitHub issue: `#257`. Tier: MEDIUM (the `:audio` process's earbud hold; no take's words). Status: APPROVED after grounded round 3 (PROCEED-AS-PLANNED); earlier rounds (gate 0 `257-g0`: PROCEED-WITH-CHANGES, adopted or declined in §3; coverage `257-cov`: all four findings adopted verbatim; grounded round 1 `257-g1`: its three findings (deferred quit ordering, a rejected reporter post, the lock) answered by one simplification, §3; grounded round 2 `257-g2`: all three adopted verbatim).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Partial. The emulator has no Bluetooth route, so a real hold cannot start there; two Gmail dictations by COMMIT show the take path is untouched. A blocked `AudioTrack.write` cannot be staged on a device; JVM rows with a blocked-writer fake cover expiry, handover, device removal and destroy. NOT RUN: a hold on the founder's phone (excluded).

## Preface: User Rubric

User Rubric: a take is never cut short to clean up the earbud hold; a silence writer that will not stop is reported once and never followed by another hold in that process.

---

## 0. TL;DR

`WarmHoldOwner.AudioTrackSilence` starts a `WarmHoldSilence` thread running `SilenceWriter` and never observes its exit after `stop()` (REF-06 of the second 2026-09-23 audit). Track every stopped writer in a process-scoped watch; admit a new hold only when every stopped writer has exited; after a 2,000 ms grace, a writer still running is reported once as a content-free defect and latches the process against any further hold.

## 1. Problem

Grounded by Codex (`257-g0`), re-read by Claude:
- `WarmHoldOwner.kt:171-219`: `AudioTrackSilence.play` starts the writer thread; `stop()` stops the writer, then the track (which should wake a blocked write), then releases it. Nothing observes the thread afterwards.
- Every end reaches `WarmHold.end` or `WarmHold.handOver`, which call `track.stop()` and then the owner's `onEnded` (`WarmHold.kt:56-72`): expiry, device change, device removal, track failure, destroy (`AudioCaptureService.onDestroy` closes the hold twice) and the handover to a new take.
- Admission is `WarmHoldOwner.eligible`, under the session lock, from `AudioCaptureService.releaseSession`.
- `onDestroy` quits the route thread with `quitSafely()`, which drops a delayed check that has not run yet.

## 2. Goals & non-goals

### 2.1 Goals
1. `WarmHold.SilentTrack` gains `fun writerExited(): Boolean`. The writer's thread moves into a small `SilenceWriterThread(write, onFailed)` (start, stop, `exited()` = its thread is not alive or never started), so a JVM row drives the real thread with a blocked fake write; `AudioTrackSilence` builds one per play and answers `writerExited()` from it (coverage finding 4, row 5).
2. `SilenceWriterWatch` (one process-scoped instance, `PROCESS`, like `RecorderLease.PROCESS`, so a replacement service instance in the same process sees it): `stopped(track)` records a stopped track with the clock's time; `admit(): Boolean` drops every exited track, reports and latches if any remaining track has `now - stoppedAt >= EXIT_BOUND_MS` (2,000 ms); if the scheduler fires early relative to the injected clock, the sweep reschedules the remaining delay (grounded round 2, finding 1); and returns true only when nothing is pending and the process is not latched; `sweep()` does the same check without admitting. Either may claim the once-only report; the report itself always runs on the watch's own worker, never inline: the watch is recorded from a start binder call (handover), capture cleanup (a failed start), a `finishTake` binder call (keep-alive failure) and main (destroy), which is fine for a short synchronized record, and the reporter runnable takes neither lock and only sends the claimed defect (grounded round 1, finding 3).
3. `WarmHoldOwner` records the hold's track in the watch from its `onEnded` callback (every end and the handover); `stopped` schedules one `sweep` on the watch's own worker `EXIT_BOUND_MS` later, so a wedge is reported even when no further hold is asked for. `eligible` returns false unless `SilenceWriterWatch.admit()`.
3b. The watch's worker: one process-scoped, lazily started single daemon thread (a `ScheduledExecutorService`), the pattern of #253's `CapturedAudioCleanup`. It parks with no timer between stops (`architecture-rules.md` RULE: no-idle-cost) and outlives every Service instance, so neither `onDestroy` nor `AudioRouteThread.quitSafely()` can drop a pending sweep or a report.
4. The defect: `AppDefect.SilenceWriterExitWedged` (`silence_writer_exit_wedged`, `audio.silence_writer_exit_wedged`) declared in `DefectIdentity.all`, `SentrySchema.semanticIdOf`, `SentrySchema.defects` and the `TelemetryContractsTest` snapshot, raised once per process through `Telemetry.defect` with no data beyond the schema's fixed keys.
5. The watch is a class (`SilenceWriterWatch(clock, schedule, report)`) with one production instance, `SilenceWriterWatch.PROCESS` (`SystemClock.elapsedRealtime`, the daemon worker, `Telemetry.defect`), which `WarmHoldOwner` takes by default; JVM rows build their own with a fake clock, a fake scheduler and a counting reporter.

### 2.2 Non-goals
- A blocked `AudioTrack.stop()` or `release()` (they run under the session lock inside `WarmHold.end`) is not covered by this bound; Codex gate 0 (e). It is out of scope, stated here.
- No change to the hold's start, its expiry, or its listeners.

## 3. Design

Adopted from gate 0: an exit signal on the `SilentTrack` seam; the track captured in `WarmHoldOwner.start` so `onEnded` covers every end and the handover; a nonblocking check on a worker, never main or a binder thread (§3, changed); admission gated from stop until exit is observed (not only after the timeout); a process-scoped gate so a replacement service cannot start another hold meanwhile; the defect's identity and declarations; 2,000 ms as a grace period, not a measured guarantee.

Changed from gate 0: admission reads each stopped writer's liveness synchronously in `eligible` (a thread `isAlive` read, no wait), so a writer that exited in 5 ms admits the next hold at once instead of 2 s later; the delayed sweep only reports.

Changed from coverage finding 2 and grounded round 1 (one simplification answers all three of its findings): the sweep and the report run on the watch's own process-scoped worker instead of `AudioRouteThread`. The route thread's lifecycle is untouched (`onDestroy` still quits it at once, and `LiveGateWiringTest` and `AudioServiceShapeTest` keep pinning that), so there is no deferred quit whose order depends on the Handler queue, no rejected post to fall back from, and no path that could report under the session lock. The audit's "on the route worker, never main or a binder thread" is kept in its intent: the check runs on a worker, never main or a binder thread, and never blocks one. Second deliberate audit deviation: a process-owned watch worker replaces the service-owned route worker so destroy cannot drop the check.

Declined from the audit and gate 0 (founder rule: the words always land): ending the `:audio` process. A handed-over route may already serve a recording, and the take's ending is delivered by `TakeEventPublisher`'s own worker after the session slot clears, so no point in the audio process is both reachable and provably after every delivery without new machinery (a delivery acknowledgment); a kill there can lose a take's audio or its ending. **Deliberate audit deviation:** a stuck writer remains until process death; at most one can be retained, and no later hold starts in that process (coverage finding 1). The stuck thread holds a stopped, released track and no lock.

## 4. Contract deltas
`WarmHold.SilentTrack.writerExited()`; `AppDefect.SilenceWriterExitWedged`.

## 5-9. State, consumers, failure modes
- The watch's state: the set of stopped, unexited tracks with their stop times, and the once-latched `wedged` flag. Written under the session lock or on the watch's worker; `@Synchronized` inside.
- A reporter that throws is caught and logged; the latch is set before it is called.

## 10. Files
`audio/WarmHold.kt`, `audio/WarmHoldOwner.kt`, `audio/SilenceWriterWatch.kt` (new, with `SilenceWriterThread`), `telemetry/DefectIdentity.kt`, `telemetry/SentrySchema.kt`; tests `SilenceWriterWatchTest` (new), `AudioLimbCloseTest` (extend its `CountingScheduler`, `CountingTrack` and `HoldRig`), `WarmHoldTest` (its `FakeTrack` gains `writerExited`), `TelemetryContractsTest`.

## 11. Testing
All rows use a fake track whose writer the test holds or releases, a fake clock, and the owner's fake scheduler; no row sleeps. Row 4 uses two owners sharing an injected fake watch, plus a source assertion that the production default is `PROCESS` (grounded round 2, finding 2).
1. For each of expiry, handover, device removal, destroy, a failed start and a keep-alive failure: first a *blocked* writer denies admission on that end path, then release it and assert admission; the fake reporter saw nothing (coverage finding 4). MUTATIONS: never record the stopped track; record only in `end`, not `handOver`.
2. A writer still running when the next hold is asked for, inside the bound: no hold, no report. The row calls the real `eligible()` with the route's `effective.observe(Bluetooth…)` (its default kind is `NONE`). MUTATION: drop the liveness check from `eligible`.
3. A writer still running past the bound: the delayed sweep reports once; a second sweep and a later admission report nothing more; every later admission refuses (latched), even after the writer exits. MUTATIONS: report on every sweep; clear the latch when the writer exits. 3b. Exact bound: a writer still running at exactly `EXIT_BOUND_MS` is overdue and reported; a sweep that fires early relative to the clock reschedules the remainder and reports nothing yet. MUTATION: `>` instead of `>=`.
4. Destroy then a new owner in the same process: the overdue writer is reported by the new owner's first admission and refused. MUTATION: make the owner's default a new watch instead of `PROCESS`.
5. `SilenceWriterThread` with a blocked fake write: `exited()` is false while the write blocks after `stop()`, true once it returns; a thread never started is exited. MUTATION: `exited()` returns true.
6. The defect is declared in all four places: `SentrySchemaTest` (`:314`) goes red when it is missing from `SentrySchema.defects`, and a new source row asserts `AppDefect.all()` names every `AppDefect` member declared in `DefectIdentity.kt`, since the snapshot alone cannot catch an omitted new member. MUTATIONS: drop it from `SentrySchema.defects`; drop it from `AppDefect.all()`.
7. The sweep and the report run on the watch's worker, never inline: with a fake scheduler, `stopped` queues exactly one sweep at `EXIT_BOUND_MS`; an overdue writer found by `admit` queues the report instead of calling it; running the queue reports once. MUTATION: call the reporter inline from `admit`.

## 12. Blast radius
The earbud hold between takes, only after a writer has failed to exit. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 7 green, each mutation red.
- [ ] Two emulator dictations by COMMIT.

## 14. Open questions
None.

## 15. Related
#188 (the hold's owner), #213 (`ProcessEnd`), #239 and #241 (the hold's track), REF-06 of the second 2026-09-23 audit.

## 16. As built

- `audio/SilenceWriterWatch.kt` (new): `SilenceWriterThread` (the writer's thread, `exited()` from its liveness) and `SilenceWriterWatch` (`stopped`, `admit`, `sweep`; the report claimed once under its monitor and always run by `schedule`, never inline), with one `PROCESS` instance on a lazily started daemon `ScheduledExecutorService` named `SilenceWriterWatch`.
- `WarmHold.SilentTrack.writerExited()`; `AudioTrackSilence` runs a `SilenceWriterThread` and answers from it. `WarmHoldOwner` takes `watch` (default `SilenceWriterWatch.PROCESS`), records each hold's track from `onEnded` (every end and the handover), and `eligible` returns false unless `watch.admit()`.
- `AppDefect.SilenceWriterExitWedged` in `DefectIdentity.all`, `SentrySchema.semanticIdOf`, `SentrySchema.defects` and the `TelemetryContractsTest` snapshot.
- Tests: `SilenceWriterWatchTest` (rows 1 to 5, 3b and 7, the owner rows against the real `eligible` with a Bluetooth route) and `DefectListCompletenessTest` (row 6) are new classes rather than additions to `AudioLimbCloseTest`, whose helpers are private; `AudioLimbCloseTest` and `WarmHoldTest` fakes gain `writerExited`. Guards retargeted: `SilenceWriterTest.theSilentTrackRunsTheWriter` reads the thread in `SilenceWriterWatch.kt`; `AudioServiceShapeTest`'s template baseline adds the file, its thread and its one warning.
- Receipts 11 of 11 RED (`docs/audits/2026-09-23-257-mutation-receipts.txt`); full suite 1260, 0 failures; the `audio` package 20 of 20 repeats; two emulator dictations and a polish-kill take by COMMIT (no Bluetooth route on the emulator, so no hold ran there).
