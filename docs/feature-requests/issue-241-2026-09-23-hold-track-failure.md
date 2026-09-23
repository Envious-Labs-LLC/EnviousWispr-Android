# Issue #241 — End the earbud hold when silent playback fails — 2026-09-23

GitHub issue: `#241`. Tier: MEDIUM (a resource that outlives its purpose). Status: APPROVED, grounded round 3 PROCEED-AS-PLANNED (coverage round: six findings adopted; grounded round 1: four findings adopted; grounded round 2: one finding adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N on the emulator: it has no Bluetooth earbuds, so no warm hold starts there, and a failing `AudioTrack.write` cannot be staged on a device. The hold's lifecycle is proven by JVM rows with the injected track and scheduler (`AudioLimbCloseTest` pattern). One emulator dictation by COMMIT shows the capture service still runs. The founder's phone pass with earbuds is queued with the earlier audit items.

## Preface — User Rubric

User Rubric: when the silent sound that keeps the earbuds ready stops, the app lets the earbuds go at once instead of holding their route and staying alive for up to 30 seconds with nothing playing.

---

## 0. TL;DR

After an earbud take, `WarmHoldOwner` keeps the communication route and the capture service alive for 30 s while `AudioTrackSilence` plays silence. If a write fails (a negative result) the write loop just stops, and a thrown write kills its thread; nobody learns of it, so the route and the started service stay until expiry or another end (REF-08). Give the track a one-shot failure callback; post it to the route thread; under the session lock end that exact hold as `track-failed` through the existing end path.

## 1. Problem

Grounded by Codex (`241-g0`), re-read by Claude:
- `AudioTrackSilence.play` (in `WarmHoldOwner.kt`): the write loop `if (n < 0) break`; a thrown write escapes the thread; `stop()` sets `stopped` and releases the track.
- `WarmHold.start` ends as `END_TRACK_FAILED` only when `play()` throws; nothing ends the hold when playback fails later.
- `WarmHoldOwner.start` arms the expiry and two listeners, each ending the hold under `locked { if (warmHold === hold) … }`; `onHoldEnded` clears the bookkeeping and calls `onIdle` (the service's `stopSelf` when no session is open).
- `WarmHold.END_TRACK_FAILED = "track-failed"` already exists; no new reason.

## 2. Goals & non-goals

### 2.1 Goals
1. `WarmHold.SilentTrack.play(onFailed: () -> Unit)`: the platform track calls `onFailed` at most once when a write returns a negative result or throws, unless `stop()` won first (a stop can itself fail the blocked write). A single zero-byte write is not a failure: Android documents zero as a possible return of this blocking write (coverage finding 3); repeated zero progress is assessed separately if it is ever observed.
2. `WarmHold` passes its own failure hook; `WarmHoldOwner.start` supplies it as: post to the route scheduler, then `locked { if (warmHold === hold) hold.end(WarmHold.END_TRACK_FAILED) }`. A late failure from an ended or handed-over hold, or from a hold replaced by a newer one, does nothing.
3. The existing end path does the rest: the track stops, the route is released, the expiry and listeners are removed, `onIdle` lets the service stop, and only when no take is live (`AudioCaptureService`'s idle check), so a late failure can never stop a live take (coverage finding 6). `route hold end=track-failed` is a local diagnostic log; this change adds no remote telemetry.

### 2.2 Non-goals
- No change to when a hold starts, its 30 s window, the handover to a new take, or the other end reasons.

## 3. Design

- `SilenceWriter` (new, pure): the loop `AudioTrackSilence` actually runs, with the write function injected: `try { while (state.get() == RUNNING) { if (write() < 0) { fail(); return } } } catch (e: Exception) { fail() }`. One atomic state `RUNNING`/`STOPPED`/`FAILED` (coverage finding 4): `fail()` signals only on the transition `RUNNING` to `FAILED`; `stop()` moves `RUNNING` to `STOPPED`, so a stop that wins suppresses the stop-induced write result, and a failure that won first may still be delivered after a stop, which the hold identity check makes harmless.
- `AudioTrackSilence` runs `SilenceWriter` on its `WarmHoldSilence` thread with `write = { built.write(zeros, 0, zeros.size) }`. Its `stop()` moves the writer to `STOPPED` BEFORE calling `AudioTrack.stop()` or `release()`, since a platform stop can unblock the write with an error (grounded round 1, finding 2).
- `WarmHold.start()` calls `track.play { onPlaybackFailed() }`, where `onPlaybackFailed` is a new REQUIRED constructor parameter, passed explicitly with named arguments in production and tests, so a missed connection cannot compile (grounded round 1, finding 3).
- `WarmHoldOwner.start` builds the hold with `onPlaybackFailed = { scheduler.post { locked { if (warmHold === started) started?.end(WarmHold.END_TRACK_FAILED) } } }`, where the captured hold (`started` as built) is assigned before `hold.start()` so the callback always sees this hold. `start()` runs during the capture thread's cleanup under `sessionLock`, and the post targets `AudioRouteThread`: the route callback may run concurrently with startup, but cannot inspect or end the hold until startup releases `sessionLock` after registration (coverage finding 2), so the end removes what start added. Calling `locked` from the route thread is what the expiry and listeners already do.

## 4. Contract deltas

`WarmHold.SilentTrack.play` gains a parameter (internal interface; three implementations: one production track, `AudioTrackSilence`, and two test fakes).

## 5-9. State, consumers, failure modes

| Delta | Consumer | Change |
|---|---|---|
| `SilentTrack.play(onFailed)` | `AudioTrackSilence`, `WarmHoldTest.FakeTrack`, `AudioLimbCloseTest.CountingTrack` (the three implementations) | new parameter; every `WarmHold` construction uses named arguments and keeps its end callback bound to `onEnded` (coverage finding 1) |
| New end trigger | `WarmHoldOwner` | posted, locked, identity-checked end |

Risks: a stop that fails the blocked write (suppressed by the one state); a failure while `start` is still registering (the locked end waits for the lock); a zero-byte write (not a failure; a repeated zero is out of scope until observed).

## 10. Files

`WarmHold.kt`, `WarmHoldOwner.kt`; tests `WarmHoldTest`, `AudioLimbCloseTest`, and a new `SilenceWriterTest`.

## 11. Testing

1. Owner row: a negative write (the fake track fires `onFailed`) ends the hold exactly once as `track-failed`, stops the track, releases the route, removes the expiry and both listeners, and calls `onIdle`. Mutation: drop the posted end.
2. Writer rows (coverage finding 5), through the same `SilenceWriter` loop `AudioTrackSilence` runs, each with a finite write sequence (grounded round 1, finding 4): zero, positive, then negative signals once, after the zero and the positive write; a thrown write signals once. Mutations: drop the negative-result signal; drop the exception handler.
3. Owner row: a failure that arrives after the hold expired, after a handover to a new take, and after a newer hold started, changes nothing (the current hold stays active, no second route release, no `onIdle`). Mutation: the callback ends the owner's current `warmHold` instead of its captured hold, with no identity check; the newer hold is then ended and the row is red (grounded round 1, finding 1: dropping only the check would stay green, because an ended hold's `end` is already a no-op).
4. Writer rows: a stop that wins suppresses the stop-induced negative write; a second failure never signals again. Mutations: signal on `STOPPED`; signal twice. A source row pins that `AudioTrackSilence` runs `SilenceWriter` (no second write loop). Mutation: restore a private loop.
5. Row 3 proves a late callback cannot end a newer hold. A new take hands the hold over before it goes live, so no hold exists during a take; what keeps a late failure from stopping a live take is the service's own guard. A service source row pins `onIdle = { if (session == null) stopSelf() }`; mutation: remove the `session == null` guard (grounded round 2).

## 12. Blast radius

The earbud warm hold only. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 5 green, each stated mutation red.
- [ ] Emulator dictation by COMMIT.

## 14. Open questions
None.

## 15. Related
#188 (the warm hold owner), audit REF-08.

## 16. As built

- `audio/SilenceWriter.kt` (new): the write loop with one atomic `RUNNING`/`STOPPED`/`FAILED` state; `AudioTrackSilence` runs it on `WarmHoldSilence` and stops it before the platform track.
- `WarmHold`: `SilentTrack.play(onFailed)` and the required `onPlaybackFailed`; `WarmHoldOwner.start` posts the failure to the route scheduler and ends the captured hold under the lock only while it is still the owner's hold.
- Tests: `AudioLimbCloseTest` rows 1 and 3 (`aPlaybackFailureEndsTheHoldAndLetsTheEarbudsGo`, `aLateFailureNeverEndsANewerHold`, after an expiry and after a handover); `SilenceWriterTest` rows 2, 4 and 5. Row 4b was reworded: a single `run` cannot fail twice, so the row checks that the loop stops at the first failure (a script of two failures), and its mutation keeps writing and reporting after a failure. `AudioServiceShapeTest`'s thread-name baseline gains `WarmHoldSilence`, which was always there but hidden from the extraction by the multi-line thread lambda.
- Receipts 9 of 9 RED; full suite 1213, 0 failures; both classes 20 of 20 repeats.
