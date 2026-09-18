# Earbuds: open the pill only when the link is live, and keep the link warm for 30 s

GitHub issue: `#26` (phone findings on build 137, 2026-09-18). Tier: MEDIUM (audio capture, the recorder's
start, a setting). Status: APPROVED (Gate 2, founder "go", 2026-09-18; Codex PROCEED-AS-PLANNED after four rounds). Parent plan:
[`issue-26-2026-09-16-microphone-picker-and-bluetooth.md`](issue-26-2026-09-16-microphone-picker-and-bluetooth.md);
this plan amends its §3 sequence, deletes its silent rescue, and supersedes its "no hold" non-goal.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/**`).

**PAR rows closed:** none. This closes the founder's three asks of 2026-09-18 on the #26 branch.

**Hardware UAT:** Y. With AirPods in, the founder taps the bubble: the lips spin, then the pill opens
(about 2 s cold, at once within 30 s of the previous take), he speaks, the words land in Gmail, and the
History card says "Microphone: AirPods Pro 3". Five back-to-back takes with pauses of up to 25 s all read
AirPods and all carry his words; a take after a 40 s pause is cold again and still works.

## Preface — User Rubric

Persona: **Maya** (brand guide), dictating on the move with earbuds, phone in a pocket.

- **What she gets:** every dictation into the earbuds is heard from the first word; the second and later
  dictations start the instant she taps.
- **What she sees:** the lips spin for a moment before the pill opens on the first dictation; nothing
  else changes. A new switch on the Microphone page, on by default.
- **What she pays:** for 30 s after each dictation her music on the earbuds stays in call quality (the
  same drop she hears during the dictation itself). The switch turns it off.
- **What must never happen:** words spoken after the pill opened are lost; the dictation moves to the
  phone microphone while the earbuds are connected; a green microphone dot with no dictation running.

### Cross-persona check

**Sam** (desk, wired headset or phone mic): nothing changes; the gate passes at once on a non-Bluetooth
route and the hold never starts. **Priya** (accessibility, TalkBack): the spinning lips already carry the
"Starting" content description; no new surface.

**Consolidation:** the dominant root is "who decides the take is live and who keeps the earbud route":
one owner, `AudioCaptureService` in `:audio`, through `LiveGate` and `WarmHold`. Consolidation sites: the
RECORDING transition in `DictationSessionService.tryStartRecording` (moved behind the gate, not duplicated),
`RouteHold.release` (the one release path, now also the hold's end), and the deleted `SilentRouteRescue`
(a second, wrong answer to "is this route delivering sound").

## 0. TL;DR

Three measured facts (`bluetooth-capture-android.md` FACT: what-the-2026-09-18-hold-probe-measured…):
the earbud link opens 0.5 to 1.0 s after the recorder starts and the earbuds' own microphone stays muted
for about 1.2 s more; a cold link sometimes comes up deaf for the whole take; silent playback on a
`VOICE_COMMUNICATION` track keeps the link open indefinitely with no recording. So: (1) the take stays in
STARTING (lips spinning, no pill, no timer, nothing written) until the earbuds deliver sound; (2) after a
take on earbuds the `:audio` service keeps the route and plays silence for 30 s, so the next take is live
in about 120 ms; (3) the exact-zero "move to the phone" rescue is deleted; a deaf link gets one reset of
the communication device and then a notice, never a different microphone.

## 1. Problem

Build 137 on the founder's phone, 2026-09-18 13:29: five takes on the AirPods, the first two with words,
the last three `textChars=0` with the SCO graph up each time. The probe reproduced it: the cold take of
the spoken run had peaks of 2 to 110 for 5 s while the founder counted (deaf), the take after a 30 s
silent-playback hold had peak 1770 at 122 ms. Today the pill opens and the timer runs while the link is
still closed, so the first second of speech is always lost and a deaf link produces an empty take with no
explanation.

## 2. Goals & non-goals

### 2.1 Goals

1. The pill opens, the timer starts and the file starts only once the chosen route delivers sound.
2. Within 30 s of a take on earbuds, the next take is live within about 350 ms (two 100 ms windows
   after the ~120 ms the route needs).
3. A deaf link gets one reset; if still deaf the recorder says so and stays on the earbuds.
4. The founder's rule, verbatim: the phone microphone is used only when the earbuds are not connected or
   he picked it himself.

### 2.2 Non-goals

- Pre-warming the link before the tap (when the bubble appears). Not asked; costs call-quality music
  while typing.
- Detecting a deaf link by level during the take. Measured: a live-and-quiet AirPods microphone (peaks
  12 to 22) is not separable from a deaf one under speech (20 to 110). A thinking user must never be
  treated as a fault.
- LE Audio (`BLE_HEADSET`). Same code path; untested on this pair (parent plan).
- Holding through a phone call, or across the `:audio` process dying. The hold is best-effort; a lost
  hold means one cold take.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

- **Producer of "live":** the capture thread in `AudioCaptureService` reading `AudioRecord` in
  `READ_CHUNK_BYTES` chunks (`AudioCaptureService.kt:600-640`). Today every byte goes to the file and
  `bytesWritten` from the first read.
- **Owner of the take's phase:** `DictationSessionService.tryStartRecording` (`:517-583`) moves
  STARTING→RECORDING the moment `startCaptureWithInputDevice` returns true, then `RecordingOverlayState.show()`
  (the pill), the haptic, `startPolling()` (timer, notices).
- **Consumer of the phase:** `RecordingAccessibilityOverlay` (`:299-305`) already spins the lips
  (`bubbleMark.setBusy`) for every phase but IDLE and draws the pill only for RECORDING; the onboarding
  practice screen reads the same `RecordingOverlayState.snapshots`.
- **Route ownership:** `RouteHold` (parent plan) created before the session, released in `closeResources`
  (`:1020`); `releaseCommunicationDevice()` is what drops the link.
- **Route liveness signal:** `AudioManager.OnCommunicationDeviceChangedListener` fires when the platform
  applies the communication device (the probe's `comm-device-changed`, 0.5 to 1.0 s). Not used by the app
  today; the app reads `routedDevice`, which is the SCO source from the first read (V2) and says nothing
  about the link.

### 2. Find the existing authority before proposing one

- Phase authority: `RecordingOverlayState.Phase` (IDLE, STARTING, RECORDING, PROCESSING) and
  `DictationSessionService.SessionState`. STARTING already exists and already spins; nothing new is
  drawn.
- Silence authority: `SilenceStopDetector` (auto-stop) reads blocks from the ring; the live gate is a
  different question (has sound EVER arrived) and stays separate, but reuses `PcmAudio` peak math.
- Hold authority: none. `RouteHold` releases at close; the warm hold extends the same object's life.
- Platform authority for the hold: `AudioDeviceBroker.updateCommunicationRouteClientsActivity`
  (`.claude/tools/bt-probe/aosp-android-16/AudioDeviceBroker.java:2810-2845`): active while the uid has
  any active `AudioPlaybackConfiguration` or an unsilenced recording.

### 3. Read prior attempts and live direction

- Parent plan §2.2: "no warm hold" (founder 2026-09-17, on V9's "route drops at 6 s"). V13 shows V9 measured
  the no-playback case only. Founder reversed on 2026-09-18 after the phone pass ("unusable in this
  current state"). Catalog decision `android-no-warm-microphone-hold` is superseded by this plan.
- macOS "Microphone Readiness" window (catalog `warm-engine`): the product shape is the same (a setting,
  a window after a take); the mechanism differs (macOS keeps the AVAudioEngine running; Android keeps
  playback running).
- FUTO Voice Input: no hold, no gate; loses the first word, which is what we ship today.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **The hold outlives the session.** `DictationSessionService` (the FGS) stops after a take; `:audio`'s
  `AudioCaptureService` is a started+bound service the session `stopService`s (`:1399`, 12 call sites).
  The hold needs `:audio` alive for 30 s with no FGS: a started service with an active `AudioTrack`.
  Android may kill it under pressure (lmkd reclaimed it as cached at 13:34:50 today); a killed hold is a
  cold next take, nothing worse.
- **Cancel during STARTING already exists** (`ACTION_STOP`/`ACTION_TOGGLE` → `cancelStarting`). The
  window grows from ~0.3 s to ~2 s on a cold link; a tap-and-tap-again inside it cancels, as today. The
  bubble's hold-to-talk early release (`stopAfterRecording`) is consumed at the RECORDING transition
  and keeps working: it waits for live, then stops.
- **The route can change during the hold:** earbuds into the case (V7), a phone call, the user picking
  Phone. The hold ends on any communication-device change away from ours and on device removal.
- **Two processes:** the gate runs in `:audio`; the phase lives in the default process. The signal
  crosses the binder as an int getter polled by the owner (like `silenceStopStatus`), never a callback.

### 5. Prove the high-risk premises

| Premise | How proven |
|---|---|
| Silent VOICE_COMMUNICATION playback keeps the route with no recording | V13 on the S26, three runs: `scoOn=true`, `activeRecordings=0` for 30 s |
| The take after the hold is live at once | V13: routed at 120 ms, first audible block at 122 ms, peak 1770 |
| Zeros mean "link closed", 2 to 6 mean "earbud mic muted", ≥ 10 sustained means live | V13 quiet run PCM: 0 ×8 blocks, click, 2 to 6 ×12 blocks, then 12 to 22 |
| A deaf link exists and level cannot separate it from a thinking user | V13 spoken cold take vs quiet run, plus build 137's log |
| Any active playback counts, whatever the usage | `AudioDeviceBroker.java:2820-2826` |

## 3. Design

### 3.1 The live gate (`:audio`)

New pure class `LiveGate(route: InputRouteKind)` fed every read block by the capture thread:

- Non-Bluetooth routes: live on the first read (no behaviour change for phone or wired).
- Bluetooth: live when two consecutive 100 ms blocks have peak ≥ `LIVE_PEAK` (10 of 32767). Reads before
  live are **not written to the file** and do not count toward `bytesWritten` or the timer.
- Deadline: `LIVE_DEADLINE_MS` = 3,500 ms from `startRecording`. On the first miss the service
  clears and re-sets the communication device once (`resetsUsed = 1`) and the deadline restarts. On the
  second miss the gate forces live with `LIVE_FORCED`; the take proceeds on the earbuds with the notice
  "Earbuds are not sending sound." (recorder line; `CaptureNotices.EARBUDS_SILENT`).
- New AIDL getter `getLiveState()`: `LIVE_WAITING 0`, `LIVE_READY 1`, `LIVE_FORCED 2`; plus
  `getLiveAfterMs()` for the log.
- **Block accumulation:** reads are 32 ms (`READ_CHUNK_BYTES`, possibly partial). The gate keeps a running
  peak over a 1,600-sample window (100 ms) and closes the window on sample count, carrying the remainder
  into the next window; a window is "live" when its peak ≥ `LIVE_PEAK`; the gate opens on the second
  consecutive live window and a low window resets the count to zero.
- **The deadline is a clock, not a read.** It runs on the route `HandlerThread` (`postDelayed`), so a
  blocked or zero-length read cannot starve it; its message is removed when the take ends. The reset is
  performed by the service (`clearCommunicationDevice` then `setCommunicationDevice(sink)`) only if the
  sink is still in `availableCommunicationDevices`; a removed sink means Android has already moved the
  route (V7) and the reset is skipped.
- **Clocks.** `startedAtMs` stays the recorder's start (the STARTING bound: `2 × LIVE_DEADLINE_MS + 1 s`,
  after which the waiter fails the take with `START_FAILURE_OTHER`). A new `liveAtMs` is set when the
  gate opens; `getElapsedMs`, the duration warning and `MAX_DURATION_MS` measure from `liveAtMs` (0 while
  waiting); the byte ceiling is unchanged because nothing is written before live; History's `durationMs` is computed from the finished file's byte count (`PcmAudio.durationSeconds`),
  snapshotted immediately after `waitForFileReady` succeeds (`DictationSessionService.kt:776-789`) and
  before transcription can delete the file (`:810-814`), then carried into finalize; this replaces the
  post-stop elapsed read and the wall-clock fallback (`:780-783`).
- **Refused links keep the rule.** Today a refused `setCommunicationDevice` or `setPreferredDevice`
  re-resolves without Bluetooth (`AudioCaptureService.kt:508-515`, `:534-541`), which lands on the phone
  with earbuds connected. Under the founder's rule that path goes: keep the earbud source as the
  preferred device, record `LINK_REFUSED` / `PREFERRED_REFUSED` as today, and let the gate's reset and
  FORCED notice speak. The "resolve without Bluetooth" branch is deleted. Pure-silence on a refused link
  (V4) then ends as FORCED with the notice, on the earbuds.
- **What READY admits.** READY and FORCED are admissions of the OBSERVED route (`routedDevice`, the
  routing listener), never of the requested one: with a Bluetooth target and no explicit Phone pick, a
  window that arrives while the observed route is the phone does not count and the take cannot be forced
  onto the phone; if the deadline passes twice in that state the take FAILS with the new
  `START_FAILURE_EARBUDS` ("Earbuds could not be used.") instead of recording from the phone. The V4 shape
  (refused link, source on the earbuds, pure silence) stays on the earbuds and ends FORCED with the
  "not sending sound" notice, which is the founder's rule applied to a link that never opened.
- **What the pre-live reads feed: only the gate.** Until the gate opens, a read goes to `LiveGate` and
  nowhere else: not the file, not `bytesWritten`, not the silence detector (`offerToDetector`), not the
  spectrum ring, not `currentAmplitude`. The detector's positions therefore start at the first saved
  block, and a long wait cannot silence-stop the take.
- **The guarantee is startup readiness, nothing more.** A deaf link that delivers 20 to 110 opens the gate
  (the threshold is 10); the gate promises that the earbuds are delivering SOME sound, and the hold is
  what makes a deaf cold link rare. `LiveGateTest` replays the measured deaf trace and asserts READY, so
  the limit is written down, not discovered.
- **Device removal during WAITING:** Android moves the route itself (V7); the routing listener records it
  and the gate, now on a non-Bluetooth route, opens on the next window (the phone is allowed: the
  earbuds are disconnected). A pending reset finds no sink and does nothing.

### 3.2 The session waits for live (default process)

`tryStartRecording` keeps STARTING after a true start and hands off to a `waitForLive` thread that polls
`getLiveState()` every 20 ms; on READY or FORCED it performs today's RECORDING transition (the CAS, the draft row,
`RecordingOverlayState.show()`, the haptic, `startPolling`). `getLiveState()` itself reports READY or
FORCED only once the first admitted block has been WRITTEN to the file (the verdict stays internal to
`:audio` until then; a FORCED verdict during a blocked read is not visible until a read returns), so the
waiter needs no byte-count getter and the plan's "nothing signals recording before the first saved byte"
holds by construction. The waiter's CAS and its publication (`show()`, haptic, surface state) run under
one lock, `publishLock`, and `onDestroy` takes the same lock for its state invalidation and overlay
cleanup, so a waiter that won the CAS cannot publish after teardown and a teardown cannot interleave
between the CAS and the publication. The waiter ends without
publishing RECORDING when: the state is no longer STARTING (cancel or destroy won the CAS), the binder
is gone (`audioService == null` or a `DeadObjectException`), `isCapturing` went false (a capture error or
cap during the wait: the terminal reason is read and shown as today's capture-ended path), or the
STARTING bound passed (fails the take with `START_FAILURE_OTHER`). FORCED publishes the notice through
the existing one-slot recorder line BEFORE `startPolling` runs, and `publishMicrophoneNoticesIfNeeded`
returns early when the forced notice was shown, so neither the Bluetooth tip nor a pick-missing line can
overwrite it; the silence auto-stop warning still ranks above it (parent plan round 6).
`cancelStarting` now runs with capture live: it stops capture, waits for the file, deletes it, and hands
the service `finishTake()` (so a cancelled take still leaves the earbuds warm), then finishes the session.
`onDestroy` first moves the state out of STARTING or RECORDING atomically (a CAS on the `AtomicReference`
to ERROR at entry, before any blocking cleanup, `DictationSessionService.kt:1470`), so the waiter's own
CAS to RECORDING fails and nothing is published during teardown; it then takes the same stop-and-join
path RECORDING does today (`:1508-1520`). `stopAfterRecording` is consumed after the transition, unchanged.

### 3.3 The warm hold (`:audio`)

New `WarmHold` owned by `AudioCaptureService`. At `endTakeLocked` on a route with
`needsBluetooth` and the setting on, the `RouteHold` is NOT released; instead a silent `AudioTrack`
(`USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, 16 kHz mono, `MODE_STREAM`, zeros written from a
`HandlerThread`) starts, and the service arms a 30 s expiry. The hold ends, releasing the route and
`stopSelf()`, on: expiry; a communication-device change away from our sink; the sink's removal
(`AudioDeviceCallback`); a new take that resolves to a different route; `onDestroy`. A new take on the
same route stops the track, keeps the `RouteHold`, and the gate goes live at ~120 ms. The setting crosses
the binder per take as a new boolean on the start call (`startCaptureWithInputDevice(..., keepEarbudsReady)`),
frozen per take like the pick.

**Lifetime.** The service is only ever bound today (`bindService(..., BIND_AUTO_CREATE)`,
`DictationSessionService.kt:491-495`) and `releaseSession` calls `stopSelf()` (`AudioCaptureService.kt:1012`),
so the last unbind destroys it. `finishTake()` (a new binder call, returns `true` when a hold started)
makes the service call `startService(Intent(this, AudioCaptureService::class.java))` on itself BEFORE the
client unbinds, which gives it a started lifetime; `releaseSession` skips `stopSelf()` when a hold is
running; the hold's end calls `stopSelf()`. When `finishTake()` returns `false` or the binder is dead the
session falls back to `stopService` as today. Enumerated `stopAudioCaptureService()` sites
(`DictationSessionService.kt`): 789 (stop-and-transcribe, file ready) and 1229 (cancel, file ready) become
`finishTake()`; 497, 531, 578, 775, 825, 1222, 1273 (bind, start and file failures, error path) and
1515/1519 (service destroy) keep `stopService`.

**Which endings hold.** The handoff sits in `releaseSession`, the one path every ending reaches: a hold
starts for the manual stop, the silence stop, the wall-clock cap and the byte cap when the take ended on
a `needsBluetooth` route with the setting on; an error ending and `onDestroy` release everything and
start no hold.

**Listener versus route ownership.** `RouteHold.release()` today drops the recorder's routing listener and
the communication device together (`InputDeviceResolver.kt:131-134`). It splits: `releaseListener()` runs
at every close (the listener belongs to that `AudioRecord` and dies with it), and the communication
ownership either releases (no hold) or transfers to `WarmHold`, which becomes the only holder until the
next take's `RouteHold` takes it back under `sessionLock`. A stale callback from a released recorder can
no longer reach a later take because the listener is removed before `release()` on the recorder.

### 3.4 The rescue is deleted

`SilentRouteRescue`, `RESCUE_AFTER_BYTES`, `rescueSilentRoute`, `InputRouteReason.RESCUED`, the `route
rescue=` log, `EffectiveDevice.markRescued`, `EffectiveDevice.rescued` and `wasRescued()`
(`InputDevicePick.kt:141`, `:176-177`) and the `builtIn` field of `ResolvedRoute`/`CaptureSession` that
existed only for the rescue are removed (`GR-MIGRATION-COMPLETE`). The "then Phone"
label survives for V7 (earbuds removed mid-take), which is a real route change Android performs.

### 3.5 Setting and copy

`AppPreferences.keepEarbudsReady` (key `keep_earbuds_ready`, default true). Microphone page, under
"When using Bluetooth": switch **"Keep earbuds ready after dictating"**, note "For 30 seconds after a
dictation, the earbud link stays open so the next one starts at once. Music on the earbuds stays in
call quality for those 30 seconds." Copy follows `content-brand.md`; the macOS setting is "Microphone
Readiness" (catalog `warm-engine`), renamed here because Android holds the LINK, not the engine.

## 3b. Ownership justification

The gate and the hold live in `:audio` beside the `AudioRecord` because the communication device
selection dies with the process that made it (parent plan §3b) and because the bytes that must not be
written are read there. The alternative, a hold in the session owner, would need a second
`setCommunicationDevice` client in another process and two owners of one route.

## 4. Contract deltas

- AIDL (append only, drift guards updated): a NEW method
  `startCaptureWithInputDeviceHeld(boolean, float, String, boolean keepEarbudsReady)`; the three older
  starts (`startCapture`, `startCaptureWithSilenceStop`, `startCaptureWithInputDevice`) stay, pass
  `keepEarbudsReady = false`, and go through the same gate (the gate is a property of the route, not of
  the start method). `getLiveState()`, `getLiveAfterMs()`, `boolean finishTake()`.
- `InputRouteReason`: `RESCUED 5` removed; values 0..4 unchanged.
- Log tokens: `route live=<label> after <ms> ms resets=<n>`; `route forced=<label> after <ms> ms`;
  `route hold start=<label>`; `route hold end=<expired|device-changed|device-removed|new-take|destroyed|off>`.
- History: `captureDevice` unchanged; the live-after time is log-only.

## 5. End-to-end state and lifecycle audit

| Step | STARTING | RECORDING | PROCESSING | Hold |
|---|---|---|---|---|
| Tap, cold link | capture started, gate WAITING, lips spin | on READY: pill, haptic, timer at 0 | unchanged | none yet |
| Tap within 30 s of a take | hold track stopped, RouteHold kept | READY at ~120 ms | unchanged | ends (new-take) |
| Deaf link | WAITING → reset at 3.5 s → FORCED at 7 s, notice | proceeds | unchanged | starts after the take (the reset link may be live now) |
| Tap again before READY | `cancelStarting`: capture stopped, file empty | never | never | starts if on earbuds |
| Earbuds removed during hold | n/a | n/a | n/a | ends (device-removed), route released |
| Phone call during hold | n/a | n/a | n/a | ends (device-changed) |
| `:audio` killed during hold | n/a | n/a | n/a | gone; next take cold |
| Setting off | gate unchanged | unchanged | unchanged | never starts; a running hold ends at its expiry |

## 6. Downstream consumer matrix

| Consumer | Reads | Change |
|---|---|---|
| `RecordingAccessibilityOverlay` | `Phase` | none (STARTING already spins) |
| Onboarding practice (`OnboardingScreen`) | `snapshots.phase` | none; its "Listening" copy waits for RECORDING as before |
| `HistoryScreen` | `captureDevice` | none |
| `DictationSurfaceState` (tile, notification) | `Phase.LISTENING` | moved to the live transition with the rest |
| Instrumented UAT scripts (`scripts/uat/*`) | `route start=` tokens | keep working; new tokens are additive |
| `InputRouteReason` readers (`InputDevicePick.kt`, tests, drift guards) | `RESCUED` | removed with its readers |

## 7. Failure-mode × caller table

| Failure | Bubble tap | Tile / notification / ASSIST | Effect |
|---|---|---|---|
| Link never opens (deaf, no reset help) | FORCED at 7 s, notice, earbuds kept | same | empty or partial take, explained |
| Comm-device reset throws | logged, treated as a miss | same | FORCED path |
| `AudioTrack` cannot be built | hold skipped, route released as today | same | next take cold |
| Hold expiry while a new take is starting | new take takes the RouteHold under `sessionLock` first; expiry sees a session and does nothing | same | no double release |
| `finishTake()` binder dead | session falls back to `stopService` | same | no hold |

## 8. Caller-visible signals audit

Pill (RECORDING), haptic, timer, and `DictationSurfaceState.LISTENING` all move to the live transition
together; nothing signals "recording" before the file has its first byte. The FORCED notice uses the
existing one-slot recorder line and ranks below the silence auto-stop warning like the other microphone
lines (parent plan round 6).

## 9. Fallback source-of-truth audit

Which microphone recorded is still `routedDevice` observed at start, on change and at stop (Android's
answer). The gate's READY/FORCED is the service's own claim about SOUND, logged with the byte count of
the first live block so a reader can check it against the file.

## 10. File-by-file changes

- `audio/LiveGate.kt` (new): the gate, pure.
- `audio/WarmHold.kt` (new): the silent track and expiry, driven by `AudioCaptureService`.
- `audio/AudioCaptureService.kt`: gate in the capture loop (skip writes until live), reset-once,
  `getLiveState`, `finishTake`, hold start at `endTakeLocked`, hold end paths, rescue removed.
- `audio/InputDeviceResolver.kt`: `SilentRouteRescue` removed; `RouteHold` gains `keep()`/`release()`
  semantics for the hold (release is already idempotent).
- `audio/InputDevicePick.kt`: `RESCUED` and `markRescued` removed.
- `aidl/IAudioCaptureService.aidl`: three getters and `finishTake`, `startCaptureWithInputDeviceHeld`.
- `ui/DictationSessionService.kt`: `waitForLive` thread; the RECORDING transition moved; `finishTake` on
  the normal paths; enumerated `stopAudioCaptureService()` sites: keep on the 9 failure/cancel paths,
  replace on the 3 normal-completion paths (listed in the commit body from the grep).
- `ui/CaptureNotices.kt`: `EARBUDS_SILENT`.
- `settings/AppPreferences.kt`, `ui/SettingsPages.kt` (Microphone page switch), `ui/AppViewModel.kt`.
- Tests: `LiveGateTest`, `WarmHoldTest` (pure timing/state), `CaptureNoticesTest`, drift guards
  (`AidlSurfaceTest`, route-reason tests), `InputDeviceRouteTest` minus the rescue rows.
- Docs/knowledge: parent plan §2.2 and §3 pointers, `bluetooth-capture-android.md` design section,
  catalog `data/095-android-earbud-hold-2026-09-18.sql` superseding `android-no-warm-microphone-hold`.

### 3.6 New start failure

`START_FAILURE_EARBUDS` joins `START_FAILURE_*` (`AudioCaptureService.kt`), read through
`lastStartFailure` and mapped by `CaptureNotices.startFailureLine` to "Earbuds could not be used."
(exhaustive `when`, drift guard updated).

## 11. Testing

1. Product outcome: `LiveGate` rows: zeros stay WAITING; 2-to-6 blocks stay WAITING; one click block then
   quiet stays WAITING; two ≥10 blocks go READY; a phone route is READY on the first block; the deadline
   asks for one reset then forces. `WarmHold` rows: starts only on Bluetooth with the setting on; expiry,
   device change, device removal, new take, destroy each end it exactly once.
2. Reverts: drop the pre-live write skip → the "bytes before live are not written" test fails; drop the
   reset → the deadline test fails; drop the setting read → the "off never holds" test fails.
3. Not testable off-device: the real link timing (the emulator has no Bluetooth); the phone is the oracle.

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (capture).
- **Recipe (emulator, rung 1):** phone route: pill opens at once, take works, no `route hold` lines.
- **Recipe (S26, rung 2, hold survives the unbind; the emulator has no Bluetooth so this is phone-only):**
  after a take, `adb logcat -s AudioCapture` shows `route hold start=` and, 30 s later, `route hold
  end=expired`, with the session service already stopped in between (`dumpsys activity services
  com.envi.wispr` lists only the audio service).
- **Recipe (S26, rung 2, founder):** AirPods in, five takes into Gmail with 5 to 25 s pauses, then one
  after 40 s: `adb logcat -s AudioCapture` shows `route live=… after <ms>` with ms ≈ 1500 to 2500 on the
  first and the 40 s take, ≤ 350 on the others (two 100 ms windows plus the 20 ms poll); `route hold start` after each; every History card
  "Microphone: AirPods Pro 3"; every card has his words. Music on the earbuds during the pauses: call
  quality for 30 s, then normal (ears).
- **Restore:** the switch back on if he turned it off; nothing else.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `LiveGateTest.zerosAndMutedBlocksNeverOpenTheGate` | product outcome | the measured shapes stay WAITING | accept any non-zero |
| `LiveGateTest.twoLiveBlocksOpenIt` | product outcome | WAITING after one live window, WAITING after live-low-live, READY on the second consecutive live window, from 32 ms partial reads | require speech level, or open on one window |
| `LiveGateTest.aPhoneRouteIsLiveAtOnce` | product outcome | no behaviour change off Bluetooth | gate every route |
| `LiveGateTest.theMeasuredDeafTraceOpensTheGate` | limit, written down | the 20-to-110 trace is READY: the gate promises sound, not speech | raise the threshold |
| `LiveGateTest.aPhoneWindowDoesNotCountForAnEarbudTarget` | product outcome | windows observed on the phone route are refused unless Phone was picked | admit any route |
| `AudioCaptureServiceRouteTest.theSecondDeadlineOnAPhoneRouteFailsTheTake` | product outcome | `START_FAILURE_EARBUDS`, no file, route released | force onto the phone |
| `AudioCaptureServiceRouteTest.waitingCannotSilenceStopTheTake` | product outcome | 10 s of pre-live zeros with the detector on: no silence ending, detector position 0 at live | feed the detector before live |
| `DictationSessionServiceTest.theWaiterPublishesOnlyAfterTheFirstSavedByte` | product outcome | FORCED with `bytesWritten = 0` stays STARTING | publish on the verdict alone |
| `LiveGateTest.theDeadlineResetsOnceThenForces` | product outcome | one reset, then FORCED | reset forever |
| `AudioCaptureServiceRouteTest.theDeadlineAsksTheServiceForExactlyOneReset` | product outcome | the reset lambda is invoked once and only while the sink is still present | never call it |
| `DictationSessionServiceTest.theSavedSettingReachesTheStartCall` | product outcome | `keepEarbudsReady` read from preferences arrives on the binder start | drop the preference read |
| `WarmHoldTest.aHoldPlaysAndStopsTheTrack` | product outcome | with a fake track factory: `play()` on start, `stop()`+`release()` on every end path exactly once | never start playback |
| `WarmHoldTest.*` | product outcome | start and end conditions | drop any one end |
| `AudioCaptureServiceRouteTest.bytesBeforeLiveAreNotWritten` | product outcome | pre-live input then live input: the file holds exactly the live bytes | write from first read, or write nothing |
| drift guards | drift | AIDL and reason enums match | any surface change |

## 12. Blast radius & rollback

- Touched: `audio/`, the session owner's start and finish, the Microphone page, one preference.
- Not touched: ASR, polish, insertion, history schema, onboarding.
- Rollback: revert the squash; the preference key is ignored by older code.

## 13. Ship criteria specific to THIS change

- [ ] Emulator: phone route unchanged (pill at once, one dictation inserted).
- [ ] Codex code review to an explicit all-clear with a confirming rerun.
- [ ] Founder's phone pass per §11.1.

## 14. Open questions

None for the founder. Deadline and peak constants are measured on one pair; the log line carries the
numbers so a second headset can retune them.

## 15. Related

#26, PR #165, `bluetooth-capture-android.md`, catalog `warm-engine`, `android-no-warm-microphone-hold`.

---

## Checklist for the plan author

- [x] Gate 0 prior context (parent plan, build 137 log, V13)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
