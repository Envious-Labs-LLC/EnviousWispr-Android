# Issue #187 — Push the recorder's picture from the audio process — 2026-09-21

GitHub issue: `#187`. Tier: REFACTOR (an AIDL surface change; `workflow-process.md` RULE: tier-routing). Status: DRAFT.

Consolidation: this plan is one document; §2.5 carries the evidence once and §§3 to 11 point back at it rather
than repeating it.

**Build order.** Planned against the #186 branch while PR #195 was open; #195 merged as a18be74 on
2026-09-21 and this worktree fast-forwarded onto that `main` (`9eb9161`). Every coordinator `file:line`
below was re-read there and holds.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/aidl/**`, `app/src/main/java/**`, `app/src/test/**`, `app/src/androidTest/**`)
`mixed_pr: true`: Docs/dev-tooling (this plan, `docs/audits/*`; `cited-symbols` conditional).

**PAR rows closed:** none. No `PAR-###` row names the transport of the live picture; the shipped feature is
the catalog row `live-audio-meter` (Android `shipped`), which this change does not alter.

**Hardware UAT:** Y. The picture is a limb, but it rides the capture process (`architecture-rules.md` RULE:
protect-audio-asr-stability: measured evidence from the physical phone). Success: the founder opens the
recorder on the S26, speaks, and the bars move with his voice exactly as on build 149; a quiet room rests the
rail; a take stopped by the side button, by silence and by cancel each leaves no picture behind; and the
take-end diagnostic line reports pushes greater than zero and polls equal to zero.

## Preface — User Rubric

1. **Who.** Aaron Wu, front-end engineer with RSI, dictating a code review comment into GitHub on his phone
   with one hand. Thirty seconds ago he pressed the side button twice; thirty seconds from now he wants the
   comment posted and the phone back in his pocket.
2. **Why.** "The bars show me it is hearing me." He does not ask for the transport; he would notice a rail
   that froze, lagged or drained the battery on a long take.
3. **Invoke.** Never. The picture appears with the pill on every take, reactive, in whatever app he is in.
4. **App.** GitHub, Slack, Gmail, Chrome: the picture is the recorder's, not the target app's.
5. **Input.** "Nit, this can be a const." / "LGTM once the test name matches the behaviour." / "Can we pull
   the retry into its own function." / "Approving, the flake is unrelated." / "Hold on, the null check moved."
6. **Success.** He notices nothing. The bars move as they did yesterday.
7. **Wrong-not-broken.** The bars lag his voice by a beat or hold a shape after he stops talking; he stops
   trusting the pill as a listening indicator without filing anything.
8. **Hack-around.** He watches the timer instead of the bars, or speaks louder to see them jump.
9. **Control.** None wanted. The picture is not a setting on macOS either (`live-audio-meter`).

### Cross-persona check

All seven personas want the same thing here: the picture as it is today at no extra cost. Dr. Elena Vasquez
would ask whether anything about her voice leaves the phone; eleven display levels cross from one process of
the app to another and nothing crosses the network, so the privacy boundary is untouched. No tension to resolve.

---

## 0. TL;DR

Today the app process asks the audio process for the recorder's picture thirty times a second over a
synchronous binder call (`DictationMeterThread`, `METER_INTERVAL_MS` = 33) and the audio process locks and
copies an eleven-float array per ask. The analyser thread that computes the picture already publishes once
per wake; this change makes it push that publication to one registered listener over a `oneway` callback
appended to `IAudioCaptureService`, deletes the meter thread and the `CaptureLink.spectrumBands()` seam, and
keeps `getSpectrumBands()` as a legacy transaction with no production caller. REFACTOR tier because the AIDL
surface grows. Evidence: repointed source guards, a coordinator row on the JVM rig, one device row across the
real binder boundary, the take-end diagnostic line reporting pushes and polls, and the session's pass on the emulator and the S26 (founder decision 2026-09-21).

## 1. Problem

`DictationSessionCoordinator.startMeter()` (`ui/DictationSessionCoordinator.kt:700-713`) runs
`while (state.get() == SessionState.RECORDING) { service.spectrumBands(); ...; Thread.sleep(METER_INTERVAL_MS) }`.
Each iteration is a synchronous transaction into `:audio`, where `getSpectrumBands()`
(`audio/AudioCaptureService.kt:368-372`) takes `bandsLock` and returns `publishedBands.copyOf()`. The audit
(`docs/audits/2026-09-20-senior-audit.md` REF-05) counts about 30 transactions, 30 array allocations and 30
snapshot publications per second of recording. `architecture-rules.md` RULE: no-idle-cost says audio levels
are PUSHED from the capture process during a session, not polled by each surface; the comment on
`startMeter` calls the consumer "the ONLY reader", which is the pushed rule half-kept. Nothing is broken for
the user; the cost is budget spent on an optional picture and a design #188 must not inherit.

## 2. Goals & non-goals

### 2.1 Goals
- G1. No production code calls `getSpectrumBands` or `spectrumBands` (grep count in `app/src/main` is the
  Stub override in `AudioCaptureService` and nothing else).
- G2. The analyser thread pushes each published picture to at most one registered listener, at most once per
  wake, never from the capture thread, never under `bandsLock`.
- G3. The owner registers once per take when the pill appears and unregisters where every take ends; a
  picture for an older take is refused as today (`RecordingOverlayState.updateBands` compares the serial under
  its lock).
- G4. The two appended AIDL transactions sit last and the order guard names them.
- G5. Capture behaviour, dropped-audio counters and the picture's shape are unchanged: `SpectrumAnalyzer`,
  `BlockRing`, `captureLoop` and `RecordingLevelMeterView` are not edited.
- G6. The take-end diagnostic line carries `pushed=` and `polled=` counts (shape only, no content).

### 2.2 Non-goals
- No change to what the picture looks like, its band count, floor or pre-emphasis (settled by the founder's
  pass on builds 127 to 131).
- No extraction of a SpectrumPublisher type (the audit's name) or any other audio-service split; that is #188.
- No change to `getCurrentAmplitude`, `getTakePeakAmplitude` or any other getter the polling tick reads.
- No Perfetto instrumentation shipped in the app.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

Producer, `:audio`. `captureLoop` offers every 32 ms read to the take's ring with its position
(`audio/AudioCaptureService.kt:883`, `active.spectrumRing.offer(buffer, bytesRead, position)`) and unparks
the analyser (`LockSupport.unpark(active.analyserThread)`, pinned by
`LiveAudioMeterWiringTest.theAnalyserDrainsTheRingWithPositionsAndPublishesOncePerWake`). `analyserLoop`
(`:978-1002`) drains the ring, runs `SpectrumAnalyzer.analyze` per chunk, and when anything was analysed
copies `bands` into `active.publishedBands` under `active.bandsLock`, then parks for `ANALYSER_PARK_NS`
(50 ms) or until unparked. It exits when `session !== active`, `active.stopRequested`, or interrupted;
`releaseSession` and `onDestroy` interrupt it (`theAnalyserIsStartedAfterCaptureAndStoppedWithTheTake`).
So the publish cadence is at most one per wake, and a wake is at most one per read: about 31 per second of
audio, fewer when reads coalesce.

Owner, `:audio` binder. `getSpectrumBands()` (`:368-372`) returns `FloatArray(BAND_COUNT)` when no session
is open, else the copy under the lock.

Consumer, the app process. Before #186 it was `DictationSessionService.startMeter()`; now it is `DictationSessionCoordinator.startMeter()`
(`:700-713`), called from `startPolling()` (`:678`) which `publishLive` calls (`:601`) under `publishLock`
right after `surface.show()` stamped the serial. It reads `pipeline.capture?.spectrumBands()` through
`CaptureLink.spectrumBands()` (`ui/PipelineLinks.kt:30`), implemented by
`PipelineBindings.CaptureProxy.spectrumBands()` (`ui/PipelineBindings.kt:148`, `service.spectrumBands`),
and publishes through `RecorderSurface.updateBands(takeSerial, bands)` (`ui/RecorderSurface.kt:20`), whose
production is `RecordingOverlayState.updateBands` (`shortcuts/RecordingOverlayState.kt:137-143`): copies,
clamps, and under `change {}` refuses when `!it.visible || it.takeSerial != takeSerial`. The overlay view
eases toward the snapshot's `bands` (`paste/RecordingLevelMeterView.kt`, not edited).

Command that found every hop (run against the #186 branch, re-run on `9eb9161` with the same hits):
```
git grep -n "spectrumBands\|getSpectrumBands\|METER_INTERVAL_MS\|startMeter\|emptyBands\|DictationMeterThread\|currentTakeSerial" -- app scripts docs/*.md
```
Hits (production): `IAudioCaptureService.aidl:37`, `AudioCaptureService.kt:368`,
`DictationSessionCoordinator.kt:118,678,700,701,706,707,709,711`, `PipelineBindings.kt:148`,
`PipelineLinks.kt:30`, `RecorderSurface.kt:23,26,40,41`. Hits (tests): `LiveAudioMeterWiringTest.kt`
(lines 38 to 70, 123, 128), `SilenceStopWiringTest.kt:39` (comment), `DictationSessionRig.kt:229,230,308`
(the fakes). No hit in `scripts/` or `docs/*.md`. The overlay and the meter view never name either getter
(`thePictureIsOnlyEverReadInOnePlace` asserts it).

### 2. Existing authority

Capability searched: "a helper process calls back into the app process". Found:
`asr/IAsrCallback.aidl` (`onResult`, `onError`, appended `onFailure`, with its own APPENDED marker) and
`polish/IPolishCallback.aidl`, both passed per request as a parameter; `IPolishService.cancel` is the one
`oneway` transaction in the tree (`git grep -n oneway app/src/main/aidl`). Callback Stubs are built in
`PipelineBindings` (`:161`, `object : IAsrCallback.Stub()`), never in the coordinator, because `Binder`
cannot be constructed on the JVM (`ui/PipelineLinks.kt` header comment). No registration-style listener
exists yet: `git grep -n "RemoteCallbackList\|linkToDeath\|DeathRecipient" app/src/main` returns nothing.
`new authority proposed`: one registered listener per binding, held by the audio service.

Platform facts (Tier 4, `developer.android.com/reference/android/os/IBinder`, read 2026-09-21): a
`FLAG_ONEWAY` (external) transaction returns immediately, and "multiple one-way calls to the same IBinder object are
dispatched in the order they were made, though they may execute on different threads"; a dead remote is
detected by a `RemoteException` (external) on transact, `pingBinder` (external), or `linkToDeath` (external).

### 3. Prior attempts and live direction

#151 (session log 2026-09-15) built the picture and the founder settled its shape over builds 127 to 131;
the polling meter thread was introduced there as "its own thread rather than a step in the polling tick"
so the picture could never delay the heart. The audit (REF-05) names the polling as the remaining
contradiction of the pushed rule; #188 (REF-11) orders itself after this change. Catalog: `live-audio-meter`
Android `shipped`; no decision row constrains the transport. Binding: the picture stays a limb whose
failure costs the picture only (#151's comment, kept verbatim where it still holds).

### 4. Boundaries a naive design misses

- **Process.** The listener Stub lives in the app process; the push is a transaction from `:audio` into it.
  `oneway` so the analyser thread never blocks on the app process (a wedged app process, #115, cannot stall
  the analyser; the async buffer absorbs about 3 KB/s).
- **Ordering.** Oneway calls to the same Stub dispatch in order (above), so pictures cannot overtake each
  other; they may run on different binder threads, and `RecordingOverlayState.change {}` serialises the
  commit under its lock.
- **Dead client.** The app process dies or unbinds mid-take: the next push throws `RemoteException`; the
  service clears the listener it was pushing to (identity compare) and logs once. `onUnbind` clears it too.
- **Dead service.** `:audio` dies mid-take: `onCaptureDisconnected` already ends the take; no pushes arrive;
  the pill hides. Nothing to unregister; `stopListeningForSpectrum` is wrapped in `runCatching`.
- **Stale take.** A push in flight when the take ends carries the serial the coordinator captured at
  registration; `updateBands` refuses it under the same lock that commits `hide()`. Unchanged mechanism.
- **Registration before or after start.** Registration happens in `publishLive` after `surface.show()`
  (where `startMeter()` is called today), so the serial is stamped first. Pictures analysed before
  registration are simply not delivered; the pill did not exist yet.
- **Second registration on one binding.** The coordinator is one instance per session and unbinds at
  `finishSession` (`:1481`), so one binding sees one take. The service still replaces rather than
  accumulates: one `@Volatile` slot, last registration wins, unregister compares `asBinder` (external) identity so a
  late unregister cannot drop a newer listener.
- **Instrumentation APK.** `app-debug-androidTest.apk` binds the real service by transaction number
  (`architecture-rules.md` RULE: aidl-is-append-only); the two new methods are appended after
  `getTakePeakAmplitude`, and `getSpectrumBands` stays.

### 5. High-risk premises, with evidence

| Premise | Evidence |
|---|---|
| The analyser publishes at most once per wake | `analyserLoop` `if (analysed) { synchronized ... arraycopy }` once per outer iteration (`:991-995`) |
| A wake is at most one per read plus the 50 ms park | `LockSupport.unpark(active.analyserThread)` once per `captureLoop` read; `ANALYSER_PARK_NS = 50_000_000L` (`:71`) |
| The capture thread never touches `bandsLock` or the listener | `theAudioProcessPublishesUnderOneLockAndTheGetterReadsUnderIt` asserts `captureLoop` has no `bandsLock`; the new guard asserts no `spectrumListener` either |
| Only the coordinator reads the picture | grep above: `PipelineBindings.kt:148` is the only production caller of `service.spectrumBands` |
| Every take ends through `finishSession` | `finishSession` (`:1471`) is reached from `stopAndTranscribe`'s publication, `cancelCaptureAndFinish` (`:1344,1363`), `endAsFailure`, `showError`; the rig's `awaitStopped` asserts "every terminal path ends in" the Service stopping; verified by reading each path again after the rebase (§13) |
| The Stub cannot be built on the JVM | `PipelineLinks.kt` header: callback Stubs "extend `android.os.Binder` and cannot be constructed off the phone"; the rig fakes the link instead |
| Oneway calls to one Stub keep order | IBinder reference, quoted in §2.5.2 |

No problem-only Codex consult: the who-calls-whom is one caller and the lifecycle is the coordinator's own
state machine, read above; the coverage round is the first reviewer.

## 3. Design

**AIDL.** New file `app/src/main/aidl/com/envi/wispr/audio/IAudioSpectrumListener.aidl` (proposed) with one
method, `oneway void onSpectrum(in float[] bands);` (`onSpectrum` (proposed)). Appended to
`IAudioCaptureService` after `getTakePeakAmplitude`:
`void registerSpectrumListener(IAudioSpectrumListener listener);` (`registerSpectrumListener` (proposed)) and
`void unregisterSpectrumListener(IAudioSpectrumListener listener);` (`unregisterSpectrumListener` (proposed)).
`getSpectrumBands()` keeps its position with its comment rewritten: legacy transaction, no production caller
since #187, kept because the interface is append-only.

**Service.** `AudioCaptureService` gains `@Volatile private var spectrumListener: IAudioSpectrumListener?`
(`spectrumListener` (proposed)), set by `registerSpectrumListener`, cleared by `unregisterSpectrumListener`
when `listener.asBinder() == current.asBinder()`, by `onUnbind`, and by a failed push. `analyserLoop`, after
the `arraycopy` under the lock and OUTSIDE it, calls `pushSpectrum(bands)` (proposed): reads the slot once,
calls `onSpectrum(bands)` (oneway marshals at call time, so the analyser's own array is safe to pass), and
on `RemoteException` clears the slot if it still holds that listener and warns once per take. The failure
branch that fills zeros also pushes zeros, so a rail whose analyser died rests. Two `AtomicInteger`
counters on the `CaptureSession`, `spectrumPushes` (proposed) and `spectrumPolls` (proposed), are incremented by the
push and by `getSpectrumBands`; `releaseSession` logs `Live picture: pushed=N polled=M` next to the
existing take-end diagnostics (shape only).

**Owner.** `CaptureLink` gains `listenForSpectrum` (proposed), as `fun listenForSpectrum(listener: SpectrumListener)`, and
`stopListeningForSpectrum` (proposed), as `fun stopListeningForSpectrum()`; `SpectrumListener` (proposed) is a Kotlin `fun interface` in
`PipelineLinks.kt` with `fun onSpectrum(bands: FloatArray)`. `CaptureLink.spectrumBands()` is removed.
`PipelineBindings.CaptureProxy` builds one `IAudioSpectrumListener.Stub` per `listenForSpectrum`, keeps it in
a field so `stopListeningForSpectrum` unregisters the same binder, and forwards each `onSpectrum` to the
Kotlin listener. `DictationSessionCoordinator.startMeter()` becomes `listenForPicture()` (proposed):
captures `surface.currentTakeSerial()` and calls
`pipeline.capture?.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }` inside
`runCatching`, warning "Live picture unavailable for this take" on failure exactly as today; still called
from `startPolling()` so the isolation comment keeps its position. `finishSession` calls
`runCatching { pipeline.capture?.stopListeningForSpectrum() }` before `pipeline.unbind()`.
`METER_INTERVAL_MS`, `DictationMeterThread` and `RecorderSurface.emptyBands()` are deleted with their fakes
(`GR-MIGRATION-COMPLETE`); `currentTakeSerial()` stays.

**Rejected.** (a) `RemoteCallbackList` (external): built for many clients and death-linking; there is one client and
a failed oneway push already reports death. (b) Passing the listener through a new
start overload (a startCaptureForTakeWithListener): the pill appears at live, after start, and a listener that outlives the
take's serial is what registration-per-take avoids; also a fifth start overload. (c) A rate limiter in the
push: the analyser's own cadence is the bound (§2.5.5); adding a second clock would be a parallel machine
(`grounding-discipline.md` RULE: grep-the-dependency-before-hand-rolling). (d) Pushing from the capture
thread: forbidden outright (RULE: protect-audio-asr-stability).

## 3b. Ownership justification

The listener slot lives on `AudioCaptureService` because the binding, not the take, owns the client; the
alternative was `CaptureSession`, but a registration can precede a session and would be lost, and the
service already owns the binder. The push lives in `analyserLoop` because that thread already owns the
publish moment; the alternative was a third thread, which is one more lifetime for #188 to extract.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `IAudioCaptureService` | two appended registration transactions | a client may receive pushes; every existing transaction keeps its number and meaning |
| `IAudioSpectrumListener` (proposed) | new oneway callback | at most one call per analyser wake, `BAND_COUNT` floats 0..1, in order, only while a take is open on that binding |
| `getSpectrumBands` | unchanged wire, comment rewritten | legacy; returns the same picture, counted as a poll |
| `CaptureLink` | `spectrumBands()` removed; `listenForSpectrum`, `stopListeningForSpectrum` added | the owner cannot poll the picture; it subscribes |
| `RecorderSurface` | `emptyBands()` removed | no caller publishes an empty picture from the owner; the service pushes zeros when its analyser fails |
| `DictationSessionCoordinator` | `startMeter` renamed `listenForPicture`; no meter thread | one fewer thread per take |

## 5. State and lifecycle audit

| Population | Enumeration |
|---|---|
| Registration sites | one: `listenForPicture()` from `startPolling()` from `publishLive` |
| Unregistration sites | one explicit: `finishSession`; two implicit on the service: `onUnbind`, failed push. The destroyed-session path (`destroy()`'s `DestroyedSessionCleanup` thread and its `postUnbindToMain`, `:1639`; the direct `pipeline.unbind()` at `:1644`) never reaches `finishSession`, so it relies on `onUnbind` clearing the slot: no push can arrive after the binding is gone |
| Take exits that reach `finishSession` | `stopAndTranscribe` publication chain, `cancelCaptureAndFinish` (both branches), `endAsFailure`/`showError` paths, `handleServiceFailure`; re-read after rebase (§13) |
| Threads that touch the listener slot | binder threads (register, unregister, unbind) and the analyser thread (push, clear on failure); the capture thread never (guard) |
| Threads that call `surface.updateBands` | binder threads in the app process only, after this change; the coordinator's own threads never |
| Serial comparisons | one, inside `RecordingOverlayState.updateBands` under `change {}`; the owner stamps, never compares (the meter's `break` on serial change goes with the loop) |
| Counters reset | both on `CaptureSession` construction, per take |

## 6. Consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `CaptureLink.spectrumBands` removed | `PipelineBindings.CaptureProxy` | forwards to the getter | method gone | yes | compile |
| same | `DictationSessionRig.FakeCapture` | returns `FloatArray(0)` | fake gains `listenForSpectrum`/`stopListeningForSpectrum` recording events | yes | coordinator rows |
| `RecorderSurface.emptyBands` removed | `OverlayRecorderSurface`, `FakeSurface` | return `NO_BANDS`/empty | gone | yes | compile |
| appended AIDL | `SilenceStopWiringTest` order list | 23 names | 25 names | yes | the test |
| appended AIDL | `LiveAudioMeterWiringTest.theAidlMethodIsAppendedLast` | getter below marker | plus the two new methods below the getter, listener file has `oneway` | yes | the test |
| take-end log line | `scripts/uat/wispr_eyes.py` `logs`/`last_take` | read existing tags | unchanged; the new line uses the existing `TAG` | no | harness run |

## 7. Failure-mode × caller table

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| register throws (dead binder) | `:audio` | `listenForPicture` | rail rests for this take; take proceeds | none | next take |
| push throws (dead app process) | app | `pushSpectrum` | nothing (the app is gone) | none | next binding |
| analyser throws | `:audio` | `analyserLoop` | rail rests (zeros pushed) | none | next take |
| unregister throws | `:audio` | `finishSession` | nothing; `runCatching` | none | n/a |
| push after `hide()` | `:audio` | `updateBands` | nothing; refused by serial/visible | none | n/a |

Copy: no new sentence. The one existing warning line, "Live picture unavailable for this take", is kept.

## 8. Caller-visible signals

| Signal | Meaning |
|---|---|
| `pushed=0` in the take-end line on a take longer than a second | the listener never registered or the analyser never ran; a defect in this change |
| `polled>0` | a production poller survived; a defect in this change |
| no `onSpectrum` for 50 ms during a take | the analyser is parked with nothing new; not a defect |
| `bands` length | always `BAND_COUNT`; `updateBands` pads or truncates defensively as today |

## 9. Fallback source-of-truth audit

| Failure branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| analyser failure | zeros | the analyser's own `catch` | it is the last writer of the picture | `bands.all { it == 0f }` | rail keeps last shape until `hide()` | `updateBands` |
| register failure | no picture | `runCatching` in the owner | the take does not depend on it | log line present | same | none |

## 10. File-by-file changes

- `app/src/main/aidl/com/envi/wispr/audio/IAudioSpectrumListener.aidl` (new): the oneway callback with its
  own APPENDED marker.
- `app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl`: two appended methods; the getter's
  comment.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`: the slot, `registerSpectrumListener`,
  `unregisterSpectrumListener`, `onUnbind` clear, `pushSpectrum`, the two counters, the take-end line.
- `app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt`: `SpectrumListener`; `CaptureLink` delta.
- `app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt`: the Stub and its field.
- `app/src/main/java/com/envi/wispr/ui/RecorderSurface.kt`: `emptyBands` removed from both.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt`: `listenForPicture`, the
  `finishSession` unregister, constants and thread gone, comment rewritten to the push.
- `app/src/test/java/com/envi/wispr/audio/LiveAudioMeterWiringTest.kt`: repointed (§11).
- `app/src/test/java/com/envi/wispr/audio/SilenceStopWiringTest.kt`: order list.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt`: fakes.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt` (on the #186 branch): one row.
- `app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt`: one row.
- `.claude/rules/architecture-rules.md` RULE: no-idle-cost: name the push as the mechanism (one clause).
- `.claude/knowledge/architecture.md`: the audio row names the listener.

## 11. Testing

1. **Classes.** `LiveAudioMeterWiringTest` rows: Drift Guard (source text). `SilenceStopWiringTest` order
   row: Drift Guard. Coordinator rig row: Product Outcome ("when this fails, the user sees a rail that never
   moves, or a picture from the last take on this one"). Device row: Product Outcome across the real binder
   boundary ("the bars never move on the phone").
2. **Reverts.** Each named in §11.2 and performed once with the suite watched red
   (`docs/audits/2026-09-21-187-revert-receipts.txt` (proposed)).
3. **Not tested.** Push cadence as a number (a timing assertion on a debug emulator is a flake by
   construction; the cadence is the analyser's, already guarded); binder death of the app process (the
   harness cannot kill the app process without ending the take it would measure; the failed-push clear is a
   source guard).

### 11.1 Hardware UAT spec

- **Subsystem:** limb riding the heart's process.
- **Recipe:** `device-testing.md` FACT: silent-physical-phone-audio-injection-for-night-uat for the phone;
  on the emulator `scripts/uat/rail_signals.py` (injected tones and hiss, pill screen-recorded, bars measured
  per frame) for the picture, plus one `dictate_emulator` take judged by the editor's text for the heart.
- **Expected observation:** the rail battery reports the same bars-per-tone as on the current build; the
  take-end line reads `pushed=` greater than zero and `polled=0`; the editor holds the sentence. The oracle
  for "no polling" is the `polled` counter, incremented inside `getSpectrumBands` itself, which the buggy
  code (a surviving poller) cannot avoid incrementing. A Perfetto trace of one take is attempted once on the
  emulator as a second instrument; if the emulator's `perfetto` (external) lacks binder tracks it is recorded NOT RUN
  with the reason, never as a pass.
- **Restore:** `restore()`; the emulator's host microphone state; nothing on the phone.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `theOwnerSubscribesToThePictureAndNeverPollsIt` (proposed) (repointed from `theSessionOwnerReadsThePictureOnItsOwnThreadAndPublishesIt`) | Drift Guard | `listenForPicture` registers with the stamped serial inside `runCatching`; coordinator source has zero `spectrumBands`; `startPolling` calls it | reintroduce one `spectrumBands` read |
| `theOwnerUnsubscribesWhereEverySessionEnds` (proposed) (new) | Drift Guard | `finishSession` calls `stopListeningForSpectrum` before `pipeline.unbind()` | delete the call |
| `theAnalyserPushesOutsideTheLockAndNeverFromCapture` (proposed) (repointed from `theAudioProcessPublishesUnderOneLockAndTheGetterReadsUnderIt`) | Drift Guard | `pushSpectrum` is called after the `synchronized` block closes; `captureLoop` names neither `bandsLock` nor `spectrumListener`; a `RemoteException` clears the slot | move the push inside the lock |
| `theListenerIsOnewayAndTheRegistrationIsAppendedLast` (proposed) (repointed from `theAidlMethodIsAppendedLast`) | Drift Guard | listener file contains `oneway void onSpectrum(in float[] bands);`; both register methods sit after `getTakePeakAmplitude` | drop `oneway` |
| `startCaptureIsStillTheFirstTransactionAndNothingWasReordered` | Drift Guard | 25 names in order | swap the two new names |
| `theOwnerRegistersForThePictureWhenTheTakeGoesLiveAndUnregistersWhenItEnds` (proposed) (new, rig) | Product Outcome | `FakeCapture.events` holds "listen" after "show" and "stopListening" before the Service stops; a picture pushed through the fake's listener reaches `FakeSurface.updateBands` with the stamped serial | delete `listenForPicture()` call |
| `aRegisteredListenerReceivesThePictureDuringATake` (proposed) (new, device) | Product Outcome | binding the real service, registering, a two-second take: at least one `onSpectrum` with `BAND_COUNT` floats, then none after unregister | comment out the push |

## 12. Blast radius & rollback

Touched: `audio/` (service and AIDL), `ui/` seams and coordinator, the tests named. Not touched:
`SpectrumAnalyzer`, `BlockRing`, `captureLoop`, `RecordingOverlayState`, `RecordingLevelMeterView`,
`RecordingAccessibilityOverlay`, any other AIDL file, `:asr`, `:polish`. Rollback: revert the squash
commit; the appended transactions become unused declarations until the next append, which is the
append-only law's normal state.

## 13. Ship criteria specific to THIS change

- [ ] `git grep -n "spectrumBands\|getSpectrumBands" app/src/main` returns the AIDL line and the Stub
      override only.
- [ ] The take-end line on the emulator and on the S26 reads `polled=0`.
- [ ] The rail battery on the emulator matches the current build's bars per tone.
- [ ] The pass on the internal build's commit: the rail battery on the emulator, silent takes on the S26,
      and `polled=0` on both.

## 14. Open questions

None that block. The Perfetto attempt (§11.1) is an instrument question, not a design one.

## 15. Related

#151, PR #154 (the picture); #186, PR #195 (the coordinator this builds on); #188 (REF-11, ordered after
this); #115 (a wedged audio process; the oneway push cannot make it worse); catalog `live-audio-meter`;
`docs/audits/2026-09-20-senior-audit.md` REF-05.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it
