# Issue #187 — Push the recorder's picture from the audio process — 2026-09-21

GitHub issue: `#187`. Tier: REFACTOR (an AIDL surface change; `workflow-process.md` RULE: tier-routing). Status: APPROVED (coverage, grounded G1 to G5, PROCEED-AS-PLANNED 2026-09-21; Gate 2 under the founder's standing approval of technical decisions).

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
protect-audio-asr-stability: measured evidence from the physical phone). The pass is the session's (founder
decision 2026-09-21). Success: on the emulator the rail battery (`scripts/uat/rail_signals.py`) judges every
signal as it did on the baseline captured before this change; on the S26 a silent take
(`scripts/uat/silent-audio/run.py`) lands its sentence while a screen recording of the pill shows the bars
moving during the injected speech and resting after it; and the take-end diagnostic line on both devices
reports pushes greater than zero and polls equal to zero.

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
- G1. No production code calls `getSpectrumBands` or `spectrumBands` (grep count over every `.kt` under
  `app/src/main/java` is the Stub override in `AudioCaptureService` and nothing else; guarded
  repository-wide, §11.2).
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
- **Dead client.** If the app process dies, a later transact throws `RemoteException` and the service clears
  the listener it was pushing to (identity compare), logging once. An ordinary unbind clears the slot in
  `onUnbind`. Post-end picture delivery follows the §2.5.5 in-flight premise.
- **Dead service.** `:audio` dies mid-take: `onCaptureDisconnected` already ends the take; no pushes arrive;
  the pill hides. Nothing to unregister; `stopListeningForSpectrum` is wrapped in `runCatching`.
- **Stale take.** A push in flight when the take ends carries the serial the coordinator captured at
  registration. Post-end picture delivery follows the §2.5.5 in-flight premise. Unchanged mechanism.
- **Registration before or after start.** Registration happens in `publishLive` after `surface.show()`
  (where `startMeter()` is called today), so the serial is stamped first. Pictures analysed before
  registration are simply not delivered; the pill did not exist yet.
- **Second registration on one binding.** The coordinator is one instance per session and unbinds at
  `finishSession` (`:1481`), so one binding sees one take. The service still replaces rather than
  accumulates: one `AtomicReference` slot, last registration wins, and unregister uses `asBinder` (external)
  identity plus `compareAndSet(current, null)` so a late unregister cannot drop a newer listener.
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
| A oneway `in float[]` is marshalled before the call returns, so the analyser may pass its own array | the generated proxy for the existing oneway `IPolishService.cancel` writes every argument into `_data` and only then calls `mRemote.transact(..., FLAG_ONEWAY)`, recycling the parcel in `finally` (`app/build/generated/aidl_source_output_dir/debug/out/com/envi/wispr/polish/IPolishService.java:342-352`); a `float[]` argument generates `writeFloatArray` (external) in the same position. Re-read the generated `IAudioSpectrumListener.java` after chunk 1 |
| A push in flight when the client unbinds is harmless | `onUnbind` clears the slot on the service's main thread while the analyser (interrupted by `releaseSession`, `audio/AudioCaptureService.kt:1277`, never joined) may already hold a reference and push once more: the client's Stub object still exists in the app process after `unbindService`, so the call is delivered and `RecordingOverlayState.updateBands` treats it as today's meter thread's own in-flight read would (`finishSession` already called `showProcessing()` at `:1474`, which hides the pill and replaces its take serial (`shortcuts/RecordingOverlayState.kt:113-115`, a fresh `Snapshot` with `visible = false` and `takeSerial = 0`), so `updateBands` refuses the callback at `:142` just as the current meter breaks at its serial check (`:707`); `destroy()` called `hide()`, or the serial moved on); if the app process is gone instead, the push throws and clears the slot. Neither outcome reaches a user |
| The instrumentation APK binds the real service by generated transaction numbers | `CaptureWithSilenceStopDeviceTest.kt:32` binds through `IAudioCaptureService.Stub.asInterface`, generated from the SAME `.aidl` at the same build, so numbers agree when both APKs are fresh; the append-only law protects an OLDER installed instrumentation APK against a NEWER production APK, which is the case the rule names |

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

**Service.** `AudioCaptureService` gains `spectrumListener` (proposed), an
`AtomicReference<IAudioSpectrumListener?>`: `registerSpectrumListener` uses `set`; `unregisterSpectrumListener`
reads the current value and, when `listener.asBinder() == current.asBinder()`, clears with
`compareAndSet(current, null)`; a failed push clears with `compareAndSet(observedListener, null)`; `onUnbind`
uses `set(null)`. So neither the unregister nor the cleanup can erase a newer registration. `analyserLoop`, after
the `arraycopy` under the lock and OUTSIDE it, calls `pushSpectrum(bands)` (proposed): reads the slot once,
calls `onSpectrum(bands)` (oneway marshals at call time, so the analyser's own array is safe to pass), and
on `RemoteException` clears the slot if it still holds that listener and warns once per take. The failure
branch fills both the local `bands` array and `publishedBands` (today it zeros `publishedBands` only,
`:998`), then calls `pushSpectrum(bands)` outside `bandsLock`, so a rail whose analyser died rests. Two `AtomicInteger`
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

The listener slot lives on `AudioCaptureService` because registration belongs to the service connection and
must stay independent of `CaptureSession` cleanup (registration happens after `publishLive`, while the
session exists, `:601`); the alternative was `CaptureSession`, whose release would drop a registration the
binding still owns, and the service already owns the binder. The push lives in `analyserLoop` because that thread already owns the
publish moment; the alternative was a third thread, which is one more lifetime for #188 to extract.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `IAudioCaptureService` | two appended registration transactions | a client may receive pushes; every existing transaction keeps its number and meaning |
| `IAudioSpectrumListener` (proposed) | new oneway callback | at most one call per analyser wake, `BAND_COUNT` floats 0..1, in order, originated while a take is open on that binding; post-end delivery follows the §2.5.5 in-flight premise |
| `getSpectrumBands` | unchanged wire, comment rewritten | legacy; returns the same picture, counted as a poll |
| `CaptureLink` | `spectrumBands()` removed; `listenForSpectrum`, `stopListeningForSpectrum` added | the owner cannot poll the picture; it subscribes |
| `RecorderSurface` | `emptyBands()` removed | no caller publishes an empty picture from the owner; the service pushes zeros when its analyser fails |
| `DictationSessionCoordinator` | `startMeter` renamed `listenForPicture`; no meter thread | one fewer thread per take |

## 5. State and lifecycle audit

| Population | Enumeration |
|---|---|
| Registration sites | one: `listenForPicture()` from `startPolling()` from `publishLive` |
| Unregistration sites | one explicit: `finishSession`; two implicit on the service: `onUnbind`, failed push. The destroyed-session path (`destroy()`'s `DestroyedSessionCleanup` thread and its `postUnbindToMain`, `:1639`; the direct `pipeline.unbind()` at `:1644`) never reaches `finishSession`, so it relies on `onUnbind` clearing the slot; post-end delivery follows the §2.5.5 in-flight premise |
| Take exits that reach `finishSession` | Direct callers of `finishSession()` on `9eb9161`: the empty-transcript ending in the speech callback (`:919`), `FINAL_TEXT_EMPTY` (`:1106`), the completed publication after the handoff (`:1234`), `cancelCaptureAndFinish` with no capture link (`:1344`) and after a clean stop (`:1363`), `cancelProcessing` (`:1401`), and `announceError` (`:1463`). Callers entering `announceError`: `endAsFailure` (`:1434`, reached from `showError` `:1423` and the direct `endAsFailure` calls at `:429`, `:449`, `:884`, `:898`, `:1356`) and `failWhileStarting` (`:1450`); `showError` is called at `:368`, `:396`, `:476`, `:494`, `:641`, `:647`, `:812`, `:851`, `:858`, `:906`, `:1468` (`handleServiceFailure`). Every one of these runs the `stopListeningForSpectrum` call inside `finishSession`; the two paths that do not reach it are `destroy()` (above) and a session that never left IDLE |
| Threads that touch the service's listener slot | binder threads (register, unregister), the service's main thread (`onUnbind`), the analyser thread (push, clear on failure); the capture thread never (guard). The slot is an `AtomicReference`: registration `set`s, unregister and failed-push cleanup `compareAndSet(observedListener, null)`, so a late clear cannot erase a newer registration |
| Threads that touch the app-side Stub field in `CaptureProxy` | the app main thread writes it (`publishLive` runs on main under `publishLock`, `:552-601`) and clears it (`finishSession`'s `postToMain` block, `:1479-1489`); binder threads in the app process run its `onSpectrum`. The field is `@Volatile`; `stopListeningForSpectrum` passes the exact Stub it stored |
| Threads that call `surface.updateBands` | binder threads in the app process only, after this change; the coordinator's own threads never |
| Serial comparisons | one, inside `RecordingOverlayState.updateBands` under `change {}`; the owner stamps, never compares (the meter's `break` on serial change goes with the loop) |
| Counters reset | both on `CaptureSession` construction, per take |

## 6. Consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `CaptureLink.spectrumBands` removed | `DictationSessionCoordinator.startMeter` (`:700-713`) | polls it on the meter thread | `listenForPicture` subscribes; the meter thread and `METER_INTERVAL_MS` are gone | yes | the repointed guards and the rig row |
| `CaptureLink.spectrumBands` removed | `PipelineBindings.CaptureProxy` | forwards to the getter | method gone | yes | compile |
| the meter thread gone | `LiveAudioMeterWiringTest` rows `theSessionOwnerReadsThePictureOnItsOwnThreadAndPublishesIt` (`:37`), `theMeterExitsWhenItsTakeIsOver` (`:55`), `thePictureIsOnlyEverReadInOnePlace` (`:63`) | assert the meter thread's text | repointed per §11.2, keeping the stale-take coverage (the serial is still stamped once and compared in `updateBands`) | yes | the tests |
| `emptyBands` removed, the meter thread gone | `RecorderSurface.currentTakeSerial` KDoc (`ui/RecorderSurface.kt:22`, "the meter thread compares against it") | names the meter thread | reworded: "the listener stamps it on every picture; `updateBands` compares" | yes | read |
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
| push after the take ended | `:audio` | `updateBands` | post-end picture delivery follows the §2.5.5 in-flight premise | none | n/a |
| `:audio` dies mid-take | `:audio` | `onCaptureDisconnected` (`:408-416`) | the existing failure sentence; the pill hides; the analyser is gone with its process | none | next take |
| take ends before the first analysed chunk | a very short take | `analyserLoop` | rail rests until hidden; `pushed=0` on the take-end line is valid for a take under one read | none | n/a |
| take leaves RECORDING before registration | the polling thread (`:625-665`) finds capture ended before `listenForPicture` runs (`:678`) | `listenForPicture` | no picture; the listener stays registered until `finishSession`'s unregister or `onUnbind`, whichever the race reaches; a registration attempted after `pipeline.unbind()` finds `capture` null and does nothing | none | n/a |

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
- `docs/audits/2026-09-21-187-rail-baseline.txt`: the pre-change rail baseline (captured, seven signals PASS).
- `docs/audits/2026-09-21-187-revert-receipts.txt`: the watched-red revert receipts.
- `scripts/uat/rail_signals.py`: `find_pill` searches down to 0.97 of the screen, because the pill sits at
  the bottom since the recorder moved (done with the baseline).
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
- **Recipe:** `device-testing.md` FACT: silent-physical-phone-audio-injection-for-night-uat for the phone,
  with a `screenrecord` around the take; on the emulator `scripts/uat/rail_signals.py` (injected tones and
  hiss, pill screen-recorded, bars measured per frame) for the picture, plus one `dictate_emulator` take
  judged by the editor's text for the heart.
- **Baseline, captured BEFORE implementation:** `docs/audits/2026-09-21-187-rail-baseline.txt`, the rail
  battery's full verdict on the emulator against the build-149 code (`83ded85`), seven signals passing, so
  "the same bars-per-tone" compares two artifacts rather than a memory.
- **Expected observation:** the rail battery's per-signal verdicts equal the baseline's; the take-end
  line reads `pushed=` greater than zero and `polled=0` on the emulator and on the S26; the silent take's
  editor holds the sentence; the S26 screen recording (started with `screenrecord` before the take, RULE:
  record-the-screen-through-a-uat-take) shows the bars moving during the injected speech and resting after
  it. The silent path bypasses the microphone, so it proves the picture pipeline on the phone's own build,
  not the microphone's acoustics; that is the same limit the recipe already states. The oracle
  for "no polling" is the `polled` counter, incremented inside `getSpectrumBands` itself, which the buggy
  code (a surviving poller) cannot avoid incrementing. A Perfetto trace of one take is attempted once on the
  emulator as a second instrument; if the emulator's `perfetto` (external) lacks binder tracks it is recorded NOT RUN
  with the reason, never as a pass.
- **Restore:** `restore()`; the emulator's host microphone state; nothing on the phone.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `theOwnerSubscribesToThePictureAndNeverPollsIt` (proposed) (repointed from `theSessionOwnerReadsThePictureOnItsOwnThreadAndPublishesIt`) | Drift Guard | `listenForPicture` registers with the stamped serial inside `runCatching`; `startPolling` calls it | delete the `runCatching` |
| `nothingInProductionReadsThePicture` (proposed) (repointed from `thePictureIsOnlyEverReadInOnePlace`) | Drift Guard | over EVERY `.kt` under `app/src/main/java`, the only occurrence of `spectrumBands` or `getSpectrumBands` is the Stub override in `AudioCaptureService.kt` (G1, repository-wide, so a poller reintroduced in `CaptureProxy` or anywhere else turns it red) | add `service.spectrumBands` to `CaptureProxy` |
| `theProxyRegistersAndUnregistersTheSameStub` (proposed) (new) | Drift Guard | `CaptureProxy` stores the Stub it built in a `@Volatile` field, passes that field to `registerSpectrumListener` and to `unregisterSpectrumListener`, and forwards `onSpectrum` to the Kotlin listener | unregister a fresh Stub instead of the stored one |
| `theServiceClearsOnlyTheObservedListener` (proposed) (new) | Drift Guard | registration uses `set`, unregister and failed-push cleanup use `compareAndSet(observedListener, null)`, and `onUnbind` uses `set(null)` | replace either `compareAndSet` with check-then-`set(null)` |
| `theCountersAndTheTakeEndLineAreWired` (proposed) (new) | Observability Contract | `pushSpectrum` increments `spectrumPushes`, `getSpectrumBands` increments `spectrumPolls`, `releaseSession` logs both with the literal `Live picture: pushed=` | drop the increment in the getter |
| `theOwnerUnsubscribesWhereEverySessionEnds` (proposed) (new) | Drift Guard | `finishSession` calls `stopListeningForSpectrum` before `pipeline.unbind()` | delete the call |
| `theAnalyserPushesOutsideTheLockAndNeverFromCapture` (proposed) (repointed from `theAudioProcessPublishesUnderOneLockAndTheGetterReadsUnderIt`) | Drift Guard | `pushSpectrum` is called after the `synchronized` block closes; `captureLoop` names neither `bandsLock` nor `spectrumListener`; a `RemoteException` clears the slot | move the push inside the lock |
| `theListenerIsOnewayAndTheRegistrationIsAppendedLast` (proposed) (repointed from `theAidlMethodIsAppendedLast`) | Drift Guard | listener file contains `oneway void onSpectrum(in float[] bands);`; both register methods sit after `getTakePeakAmplitude` | drop `oneway` |
| `startCaptureIsStillTheFirstTransactionAndNothingWasReordered` | Drift Guard | 25 names in order | swap the two new names |
| `theOwnerRegistersForThePictureWhenTheTakeGoesLiveAndUnregistersWhenItEnds` (proposed) (new, rig) | Product Outcome | `FakeSurface`, `FakeCapture` and the owner-stop fake share one ordered event sink (a `timeline` list on the rig, proposed); `updateBands` records its serial and bands; the row asserts the order show < listen < updateBands (with the stamped serial and the pushed bands) < capture stop < stopListening < owner stop | delete the `listenForPicture()` call (no "listen", no picture) |
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
- [x] Self-reviewed to all-clear before any reviewer saw it
