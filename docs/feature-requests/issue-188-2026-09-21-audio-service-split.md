# Issue #188 — One lifetime owner per audio resource — 2026-09-21

GitHub issue: `#188`. Tier: MEDIUM by the issue (audio capture, one service) and treated as REFACTOR for review
depth: the diff moves about 700 lines across the capture thread's boundary. Status: APPROVED (coverage, grounded G1 to G7, PROCEED-AS-PLANNED 2026-09-21; Gate 2 under the founder's standing approval of technical decisions).

Consolidation: this plan is one document; §2.5 carries the evidence once and §§3 to 11 point back at it.

**Build order.** Grounded against `worktree-issue-187-spectrum-push` at `0582f7f` (#187, the pushed picture),
which this change is ordered after by the issue's own text; #198 merged as `2ed2cdf` on 2026-09-21 and this
worktree is cut from that `main`, where every `file:line` below was re-read and holds (the squash kept the
content byte for byte).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/audio/**`, `app/src/test/**`)
`mixed_pr: true`: Docs/dev-tooling (this plan, `docs/audits/*`; `cited-symbols` conditional).

**PAR rows closed:** none. No `PAR-###` row names the audio service's internal shape.

**Hardware UAT:** Y. Capture is the heart. The pass is the session's (founder decision 2026-09-21): on the
emulator, spoken takes into Gmail (the phone route), the rail battery against
`docs/audits/2026-09-21-187-rail-baseline.txt`, the silence device rows; on the S26, silent takes with the
take-end lines read. The earbud cells (a Bluetooth route, the warm hold, route loss) cannot be staged by the
emulator or by the silent injection path, which bypasses the microphone and Bluetooth; they are declared
UNVERIFIED on hardware here and listed for the founder's next earbud use, never claimed.

## Preface — User Rubric

User Rubric: N/A — this change moves code between files inside the `:audio` process and introduces no
user-visible behaviour change; every user-facing path (phone microphone, earbuds, the hold, silence stop,
the picture) keeps its statements. The rubric's persona questions have the same answers as #26 and #151.

---

## 0. TL;DR

`audio/AudioCaptureService.kt` (1,570 lines) owns five lifecycles in one class: the take's route (resolve,
prefer, listen, watch the sink, the live deadline), the warm hold between takes, the silence detector
feed, the picture, and the capture session itself. Each has its own threads, callbacks and cleanup, all
reading `session`, `sessionLock`, `isRecording` and `routeHandler`. This change gives each of the four
limbs one internal type with one idempotent `close`, leaves the service as the binder plus the owner of
`CaptureSession`, and keeps the capture thread the sole owner of `AudioRecord` and the PCM file. Every
statement moves; none is rewritten. Evidence: the same guards repointed to the new owners plus new guards
on the capture thread's allocation-free path, the full unit suite, the emulator pass and the silent phone
takes. The earbud cells stay UNVERIFIED on hardware and are said so.

## 1. Problem

The audit (`docs/audits/2026-09-20-senior-audit.md` REF-11) counts five lifecycles reviewed and changed
together. Concretely, on `0582f7f`: the route lives in `resolveRoute` (`:659`), `applyPreferredDevice`
(`:715`), `registerRoutingListener` (`:731`), `observeFinalRoute` (`:748`), `routeAdmissible` (`:763`),
`watchSink` (`:772`), `markLive` (`:800`), `armDeadline` (`:819`), `resetCommunicationDevice` (`:853`)
plus nine `CaptureSession` fields; the hold in six service fields (`:229-240`) and `holdEligible`
(`:1363`), `startWarmHold` (`:1376`), `onHoldEnded` (`:1416`), `clearHoldBookkeeping` (`:1424`),
`finishTake` (`:1442`), `AudioTrackSilence` (`:1462`); the detector feed in `vadConnectionFor` (`:305`),
`offerToDetector` (`:962`), `startSilenceDetection` (`:1073`), `feederLoop` (`:1110`), `abandonDetector`
(`:1209`), `unbindVad` (`:1226`) plus ten session fields; the picture in `startSpectrumAnalysis` (`:993`),
`analyserLoop` (`:1015`), `pushSpectrum` (`:1055`) plus six session fields and the service's listener
slot. #26 needed six review rounds to get the hold and the gate right because every fix touched the same
class; #187 touched the picture next to the detector's fields. The next change (any of #192, #115, #114)
lands in the same file.

## 2. Goals & non-goals

### 2.1 Goals
- G1. Four internal types in `audio/`, each with one idempotent `close()`: `TakeRoute` (proposed),
  `DetectorFeed` (proposed) and `PicturePublisher` (proposed) (per take, closed from `releaseSession`, and again from `onDestroy` for a take still
  open) and `WarmHoldOwner` (proposed) (service-scoped, closed from `onDestroy`).
- G2. `AudioCaptureService` keeps the binder, `CaptureSession`, `startRecording`, `captureLoop`,
  `claimEnding`/`endTake`/`endTakeLocked`/`releaseSession`, `closeResources` for the record and file,
  `waitForFileReady`, `onDestroy`. Nothing else.
- G3. The capture thread still owns `AudioRecord` and the file alone; its loop calls exactly two limb
  methods per read (`DetectorFeed.offer`, `PicturePublisher.offer`), and neither allocates, logs, takes a
  lock another thread holds, binds or unbinds a service, or crosses a process (guarded).
- G4. Every existing guard row keeps its protected meaning; §6 enumerates the rows whose source
  assertions are rewritten because the text they pin changes, and every other row is repointed to the
  file that now holds the text. None is deleted or weakened (RULE:
  deleting-a-test-carries-the-burden-of-adding-one).
- G5. The `:audio` log lines, AIDL, and every user-visible statement are byte-identical.

### 2.2 Non-goals
- No change to `LiveGate`, `WarmHold`, `RouteHold`, `InputDeviceResolver`, `BlockRing`,
  `SpectrumAnalyzer`, `CaptureEndingClaim` (already extracted; this change composes them).
- No fix for #114 (the log cannot tell a user's stop from a teardown), #115, or #192.
- No change to the detector process (`SilenceVadService`) or to the AIDL surface.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, per lifecycle

**The take.** `startRecording` (`:443-634`) under `sessionLock`: builds `RouteHold`, hands over the warm
hold (`:480-484`), `resolveRoute`, the `AudioRecord`, the file, `CaptureSession` (`:545-564`),
`registerRoutingListener`, then starts the detector (`:593`), the capture thread (`:595-607`), the
analyser (`:608`), the sink watch and deadline or `markLive` (`:609-614`). `captureLoop` (`:869-950`)
reads, gates (`:908-911`), writes the file, offers to the detector and the picture (`:917-921`),
computes the level, and on any exit runs `releaseSession` (`:948`). `releaseSession` (`:1310-1343`):
under the lock removes the deadline and the sink watch, observes the final route, decides the hold,
closes the record and file, clears `session`; outside the lock abandons the detector, interrupts the
feeder and the analyser, logs the picture line, `stopSelf` unless holding.

**The route.** Owned per take by `RouteHold` (built at `:472`), `ResolvedRoute` (`:637`), the routing
listener (`:731`, registered on `routeHandler`, removed by the hold), the sink watch (`:772-797`,
`AudioDeviceCallback` on `routeHandler`, removed at `:1318`), the deadline (`:819-850`, a `Runnable` on
`routeHandler` that reads `session === active`, `isRecording`, `gate.state` under `sessionLock` and calls
`resetCommunicationDevice` or `endTakeLocked`, writing `lastStartFailure`), `markLive` (`:800`, capture
thread then `routeHandler`), `routeAdmissible` (`:763`, read on the capture thread and in the deadline).
Session fields: `routeHold`, `gate`, `targetBluetooth`, `phonePicked`, `sink`, `sinkGone`, `sinkWatch`,
`deadline`, `effective`, `liveAtMs`.

**The warm hold.** Service-level, between takes: `warmHold`, `heldSinkType`, `heldSinkName`, `holdExpiry`,
`holdCommListener`, `holdDeviceCallback` (`:229-240`), `destroyed` (`:235`). `holdEligible` (`:1363`,
under the lock, exhaustive over `CaptureEnding`), `startWarmHold` (`:1376-1413`: builds `WarmHold` with
`AudioTrackSilence`, registers the expiry on `routeHandler` and two `AudioManager` listeners, each ending
the hold under `sessionLock` only if `warmHold === hold`), `onHoldEnded` (`:1416`, `stopSelf` when no
session), `clearHoldBookkeeping` (`:1424`), `finishTake` (`:1442`, `startService` to outlive the unbind),
and the handover at `:480-484` plus `resolveRoute`'s adopt-or-release (`:678-698`). `onDestroy` ends it
twice (`:1540`, `:1565`) around the join.

**The detector feed.** Per take: `ring`, `pendingBlock`, `pendingBytes`, `pendingPosition`,
`detectorAbandoned`, `silenceStatus`, `vadService`, `vadBound`, `feederThread`, `vadConnection`
(`:132-135`, `:199-206`). `offerToDetector` (`:962-984`, capture thread, no allocation, flag-only on a
full ring), `startSilenceDetection` (`:1073-1101`, binds `SilenceVadService` with a per-take
`ServiceConnection` from `vadConnectionFor` `:305-330`, starts `feederLoop` `:1110-1200`, the only thread
that calls the detector; a `RESULT_SILENCE` calls `endTake(active, SILENCE)` `:1179`), `abandonDetector`
(`:1209`, the status ladder), `unbindVad` (`:1226`). `releaseSession` and `onDestroy` abandon, interrupt
and unbind. The binder reads `silenceStatus` (`:361-362` area, `getSilenceStopStatus`) and the service
keeps `lastSilenceStatus` after release (`:273`, `:1326`).

**The picture.** Per take: `spectrumRing`, `publishedBands`, `bandsLock`, `analyserThread`,
`spectrumPushes`, `spectrumPolls` (`:181-191`); service-level `spectrumListener` (`:260`, owned by the
binding, #187). `startSpectrumAnalysis` (`:993`), `analyserLoop` (`:1015-1053`), `pushSpectrum`
(`:1055-1067`); the capture thread offers and unparks (`:920-921`); the binder's `getSpectrumBands`
(`:386-392`) reads under `bandsLock` and counts; `releaseSession` interrupts and logs the counters
(`:1340-1342`); `onDestroy` interrupts (`:1545`).

Command that found every site: `git grep -n "routeHold\|sinkWatch\|deadline\|warmHold\|holdExpiry\|vadService\|feederThread\|analyserThread\|spectrumRing\|detectorAbandoned" 0582f7f -- app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`, 96 hits, every one inside the functions named above.

### 2. Existing authority

Already-extracted owners in `audio/`: `LiveGate` (the gate state machine, `LiveGate.kt`), `WarmHold`
(the hold state machine with `SilentTrack`, `handOver`, `end`, `WarmHold.kt`), `RouteHold` (the platform
route request's release, `InputDeviceResolver.kt:119`), `InputDeviceResolver` (pick resolution),
`BlockRing`, `SpectrumAnalyzer`, `CaptureEndingClaim`. Each is a VALUE machine with no thread, no
`Context` and no session identity; the service holds every thread, callback and identity check. The four
new types own exactly that layer: threads, callbacks, per-take fields, and the close. `new authority
proposed` for the layer; the value machines are composed, not replaced.

Capability search for "a per-take owner with an idempotent close": `git grep -n "fun close()\|fun release()\|isReleased" -- app/src/main/java/com/envi/wispr/audio` finds `RouteHold.release`/`isReleased` (idempotent by its `released` flag) and `WarmHold.end` (guarded by `ended.compareAndSet`, `WarmHold.kt:56-60`); both are the shape the new types follow.

### 3. Prior attempts and live direction

#26 (session log 2026-09-18): six code rounds, each a lifecycle race in this class; the fixes are the
identity checks and the single lock this plan carries whole (RULE: port-proven-patterns-wholesale).
#151 and #187: the picture's thread, ring and push. #176: the monotonic capture token and the take id
forwarded to the detector. #114 and #115 are open and land in this file. The audit orders this after
#187. No decision row constrains the internal shape.

### 4. Boundaries a naive design misses

- **The lock is the service's; session identity stays in service lambdas or direct service checks.**
  The deadline, the VAD connection, the feeder and the analyser decide "am I still the live take" by
  `session === active` under `sessionLock` or by `stopRequested` (the routing listener uses
  `RouteHold.isReleased`; the sink and `markLive` callbacks only capture their take); an extracted type must not grow its own lock or its own
  notion of session liveness: it receives `stillLive` (proposed), as `stillLive: () -> Boolean`
  (evaluated by the caller under the service's lock where the original did) and never reads the session
  slot. The three `warmHold === hold` checks are the hold's own identity, not the session's; they stay
  inside `WarmHoldOwner` and execute through `locked`. Where the original ran a block under `sessionLock` inside a callback (the
  deadline `:822`, the hold's expiry and listeners `:1392-1403`), the type receives a `locked: (() ->
  Unit) -> Unit` runner from the service so the same lock is held.
- **Ending a take is the service's.** The deadline calls `endTakeLocked` and writes `lastStartFailure`
  (`:839-841`); the feeder calls `endTake(active, SILENCE)` (`:1179`). `TakeRoute` receives
  `onRefused` and `DetectorFeed` receives `endOnSilence` (proposed), each `() -> Unit`; they never hold a
  reference to the service.
- **The capture thread's contract survives the move.** `DetectorFeed.offer` and `PicturePublisher.offer`
  are the moved bodies of `offerToDetector` and lines `:920-921`; a source guard proves each body
  contains no `ByteArray(`, no `DebugLogger`, no `synchronized`, no binder call, no `String` template.
- **`routeHandler` outlives every take; after the capture-thread join, `onDestroy` requests
  `routeThread.quitSafely()` without joining `routeThread` (`:1548-1567`).** The types receive the
  `Handler`, never create one, and `TakeRoute.close` removes exactly its own callbacks (deadline `:1314-1315`, sink watch `:1316-1319`, routing listener `InputDeviceResolver.kt:143-147`, `:160-164`).
- **The warm hold is service-scoped, not take-scoped.** `WarmHoldOwner` lives on the service, receives
  `lock`, `handler`, `audioManager`, `onIdle: () -> Unit` (the `stopSelf` at `:1420`) and `keepAlive: ()
  -> Boolean` (the `startService` at `:1447`); `destroyed` stays on the service and is passed into
  `eligible`.
- **`lastSilenceStatus` and `lastEffective` are read after release.** They stay on the service, copied
  from the take at `:1326` and `:567` as today.
- **The binder's picture getter and listener slot** stay on the service (the slot is binding-owned);
  `PicturePublisher` receives the `AtomicReference` and the counters live on it.

### 5. High-risk premises, with evidence

| Premise | Evidence |
|---|---|
| Every session field of the four lifecycles is touched only by the functions listed for that lifecycle, plus `startRecording`, `releaseSession` and `onDestroy` | the grep in §2.5.1 (96 hits) read one by one; the exceptions are the three named functions |
| `routeHandler` is the only thread for routing callbacks, the deadline and the hold's expiry | `:246-247` comment and `:739`, `:786`, `:849`, `:1394`, `:1407`, `:1409` |
| The identity checks are exactly twelve: deadline `:823`, VAD connection `:307`, feeder `:1124`, analyser `:1020`, hold expiry and listeners `:1392`, `:1397`, `:1402`, `releaseSession` `:1313`, `endTake` `:1268`, `captureLoop` `:872`, the start's return `session === newSession` `:615`, and the capture-error path `session === active` under the lock `:943-945` | grep `session !== active\|session === active\|session === newSession\|warmHold === hold` on `2ed2cdf`: those and no others |
| The `sessionLock` critical sections are exactly fourteen, at `:439`, `:461`, `:822`, `:943`, `:1252`, `:1267`, `:1312`, `:1392`, `:1397`, `:1402`, `:1443`, `:1540`, `:1560`, `:1565`; every one keeps its lock boundary after the move (the type receives `locked` where the block sits inside a callback, and the service keeps the block where it sits in its own function) | grep `synchronized(sessionLock)` on `2ed2cdf` |
| Callbacks that may already be in flight after release, beyond the ones §2.5.4 names: the routing listener (`:731-745`, guarded by `RouteHold.isReleased`), the sink callback (`:777-784`, touches only the captured take's `sinkGone`), and the `markLive` Runnable posted to `routeHandler` (`:802-810`, reads and clears only that take's `deadline`) | read at those lines; each moves into `TakeRoute` with the same guard |
| No test constructs the service or its session on the JVM | `git grep -n "AudioCaptureService(" app/src/test` returns nothing; every JVM audio guard reads the file as text (§6) |
| The device rows bind the real service by AIDL and never name its internals | `CaptureWithSilenceStopDeviceTest`, `SilenceStoppedTakeTranscribesDeviceTest`, `VoicePipelineDeviceTest` read via `IAudioCaptureService` only |

Problem-only Codex consult: run as the coverage round's first axis (the who-calls-whom above is what it
refutes), not as a separate step.

## 3. Design

Four `internal class`es in `app/src/main/java/com/envi/wispr/audio/`, each a MOVE of the functions and
fields named in §2.5.1 with the service's identity and lock decisions passed in as lambdas:

1. **`PicturePublisher`** (proposed, per take): fields `spectrumRing`, `publishedBands`, `bandsLock`,
   `analyserThread`, `pushes`, `polls`; its companion owns the picture-only constants
   (`SPECTRUM_RING_CHUNKS`, `ANALYSER_PARK_NS`) and `READ_CHUNK_BYTES` moves to `PcmAudio` (proposed
   home) because the service (`:504`, `:552`) and the picture (`:181`, `:1016`) both size buffers from it; the detector-only
   constants (`READ_BLOCK_BYTES`, `RING_BLOCKS`, `FEEDER_IDLE_MS`) move to `DetectorFeed`'s companion and
   the service references the owner constants where it allocates and logs (`:504`, `:552-555`).
   Constructor takes the listener `AtomicReference` and the tag. `offer(buffer, n, position)` (the two capture-thread
   lines), `start(stillLive: () -> Boolean)` (`startSpectrumAnalysis` + `analyserLoop` + `pushSpectrum`),
   `snapshot(): FloatArray` (the getter's copy under the lock, counting a poll), `close()` (interrupt,
   idempotent). `stillLive` is `{ session === active && !active.stopRequested && !interrupted }` as today.
2. **`DetectorFeed`** (proposed, per take): the ten fields; `offer(buffer, n, position)`
   (`offerToDetector`), `start(context, pauseSeconds, token, takeId, isCurrent, stillLive, endOnSilence)`
   (`startSilenceDetection` + `vadConnectionFor` + `feederLoop`), `abandon()`, `status: Int`,
   `close(context)` (abandon, interrupt, unbind; idempotent). Two liveness lambdas, because the code has
   two checks: `isCurrent` (proposed), `isCurrent = { session === active }`, for `onServiceConnected` (`:307-310`) and
   `stillLive = { session === active && !active.stopRequested }` for the feeder (`:1116-1125`);
   `endOnSilence` is `{ endTake(active, SILENCE) }` (`:1179`).
3. **`TakeRoute`** (proposed, per take): fields `hold: RouteHold`, `resolved: ResolvedRoute` (moved
   type), `effective`, `gate`, `targetBluetooth`, `phonePicked`, `sink`, `sinkGone`, `sinkWatch`,
   `deadline`, `liveAtMs` (moved from the session: `markLive` `:800-809` and the forced branch `:833-837`
   write it; the capture loop `:874-875` and the binder read `active.route.liveAtMs`), the routing
   listener reference (moved from the service-local `routingListener` at `:471-475`, so the `RouteHold`
   remover is built by `TakeRoute`'s factory against the reference it owns), and `startedAtMs` received
   at construction; `admissible()`, `applyPreferred(record)`, `registerListener` (proposed) as
   `registerListener(record, handler, bytesWritten: () -> Long)` (the callback's log names the take's
   byte count, `:736`), `observeFinal(record)`, `watchSink(audioManager, handler)`, `markLive(handler)`,
   `armDeadline(handler, locked, stillWaiting, onRefused)` where `stillWaiting` (proposed) is
   `{ session === active && isRecording.get() && gate.state == WAITING }` under the service's lock and
   `onRefused` (proposed) is `{ lastStartFailure = START_FAILURE_EARBUDS; endTakeLocked(active, ERROR) }`,
   `reset(audioManager)`, `close(handler, audioManager, keepRoute)` (deadline removed, sink watch
   unregistered, hold released or listener-only; idempotent). `resolveRoute` becomes
   `TakeRoute.resolve(...)` in its companion, the one function that runs before the session exists.
4. **`WarmHoldOwner`** (proposed, on the service): the six fields; `handOver(): HandedRoute?`,
   `eligible(route, ending, keepEarbudsReady, destroyed)` (`keepEarbudsReady` stays on `CaptureSession`
   and is passed, `:148`, `:1365`), `start(route, sink, label, audioManager, handler, locked)`,
   `keepAlive` (proposed), as `keepAlive()` (calls the injected `startService`, `:1447`), and one idempotent `close(reason)`
   (proposed), the sole shutdown entry, which replaces the two `onDestroy` calls at `:1540` and `:1565`;
   `onHoldEnded`'s log and `clearHoldBookkeeping` are internal to the owner, and the one decision that
   crosses ownership, service lifetime, is the injected `onIdle` (proposed), `onIdle = { if (session == null) stopSelf() }`
   called for every end except `END_NEW_TAKE` (`:1420`).

`CaptureSession` keeps `record`, `file`, `output`, `startedAtMs`, `readBuffer`, `token`, `takeId`,
`keepEarbudsReady`, `liveVisible`, `bytesWritten`, `endingClaim`, and the three per-take owners `route`,
`detector`, `picture` (`liveAtMs` moves to `route`). `releaseSession` copies `detector.status` into
`lastSilenceStatus` BEFORE `detector.close()`, because `close` abandons the detector and abandoning can
move the status (`:1209-1222` versus the snapshot at `:1326` today). `captureLoop`, `startRecording`, `claimEnding`, `endTake`, `endTakeLocked`,
`releaseSession`, `closeResources(record, output)`, `waitForFileReady`, `onDestroy`, the binder and the
counters' log line stay in the service. Log lines, tags and sentences move verbatim.

**Every lambda and the line it carries.** `PicturePublisher.stillLive` `:1020`; `DetectorFeed.isCurrent`
`:307`, `.stillLive` `:1124`, `.endOnSilence` `:1179`; `TakeRoute.locked` `:822`, `.stillWaiting` `:823`,
`.onRefused` `:839` and `:841` (the log at `:840` stays inside `TakeRoute`), `.bytesWritten` `:736`; `WarmHoldOwner.locked` `:1392`, `:1397`, `:1402`, `.onIdle` `:1420`,
`.keepAlive` `:1447`. Nothing else in those bodies reads the service.

**Rejected.** (a) One CaptureLimbs type holding all three per-take limbs: hides the lifetimes the
issue asks to expose. (b) Giving each type its own lock: the #26 races were closed by ONE lock; two locks
reopen them. (c) Interfaces plus fakes for JVM tests of the service: the service still cannot be built
off the phone (`AudioRecord`, `Context`); the guards stay source-level and the device rows stay the
product coverage, as today. The four owners, by contrast, take their platform edges as constructor
parameters (a poster and remover for the handler, an unbinder, an unregister, a release, an interrupt),
so `AudioLimbCloseTest` builds the real owners on the JVM; production passes the platform calls.

## 3b. Ownership justification

The per-take limbs live on `CaptureSession` because their lifetime IS the take's (they are created in
`startRecording` and closed in `releaseSession`); the alternative was the service, whose fields would
have to be nulled per take and could be read by a finished take's callback. The hold lives on the
service because it exists BETWEEN takes; the alternative was the session, which is gone while the hold
runs.

## 4. Contract deltas

| Type | Delta | Meaning |
|---|---|---|
| `AudioCaptureService` | 1,570 lines to about 700; same binder, same log lines | nothing changes for any client |
| `CaptureSession` | four fields become three owner objects | the capture thread reads `route.gate`, offers to `detector` and `picture` |
| the four new types | new, `internal` | each is constructed by the service and closed by it exactly once per take (or per hold) |
| every JVM guard reading the service (§6) | repointed, or rewritten where §6 says so | same protected meaning |

## 5. State and lifecycle audit

Bare enumerations; the behaviour at each line is the code's, read there, not restated here (every line on
`2ed2cdf`, `audio/AudioCaptureService.kt` unless named).

| Population | Members (`file:line`) |
|---|---|
| Identity checks, become liveness lambdas | `:307`, `:1020`, `:1124`, `:823` |
| Identity checks, stay inside `WarmHoldOwner` through `locked` | `:1392`, `:1397`, `:1402` |
| Identity checks, stay direct in the service | `:615`, `:872`, `:944`, `:1268`, `:1313` |
| `sessionLock` sites that receive `locked` | `:822`, `:1392`, `:1397`, `:1402` |
| `sessionLock` sites that stay in the service | `:439`, `:461`, `:943`, `:1252`, `:1267`, `:1312`, `:1443`, `:1540`, `:1560`, `:1565` |
| Threads created | `:595` (capture, service), `:995` (analyser, `PicturePublisher`), `:1092` (feeder, `DetectorFeed`), `:1494` (silence track, `WarmHoldOwner`), `:334` (`routeThread`, service) |
| Callbacks registered | `:739`, `:786`, `:849`, `:802`, `:1078`, `:1394`, `:1407`, `:1409` |
| Callbacks removed | deadline `:1314-1315` and `:803-804`; sink watch `:1316-1319`; routing listener `InputDeviceResolver.kt:143-147`, `:160-164`; VAD `:1226-1234`; hold callbacks `:1426-1431` |
| Close calls after the move | `releaseSession`: `route.close` from `:1325`, `:1355-1357`; `detector.close` and `picture.close` from `:1336-1338`; `onDestroy`: `route.close` (new, inside the `session?.let` at `:1542-1546`, where today only the detector and the analyser are interrupted) and the detector and picture closes from `:1542-1546`, `warmHoldOwner.close(WarmHold.END_DESTROYED)` from `:1540` and `:1565`; pre-session release paths `:487`, `:510`, `:620`, `:628` |
| Service values that outlive release | `lastEffective` `:567`, `lastAudioFile` `:568`, `takePeakAmplitude` `:940`, `terminalReason` `:1245`, `lastSilenceStatus` `:1326` (copied from `detector.status` before `detector.close`); `PicturePublisher.pushes`/`polls` read at `:1342` after `close` |

## 6. Consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| functions move | `SilenceStopWiringTest` (18 rows, reads the service) | asserts detector text in the service | reads `DetectorFeed.kt` for the feed rows, the service for the binder and stop-line rows | yes | the suite |
| functions move | `LiveGateWiringTest` (12) | route and gate text in the service | reads `TakeRoute.kt` and the service | yes | the suite |
| functions move | `LiveAudioMeterWiringTest` (15) | analyser and push text in the service | reads `PicturePublisher.kt`; the getter and the take-end line stay in the service | yes | the suite |
| functions move | `RecordingCapWiringTest` (10), `CaptureBufferOwnershipTest` (5), `CaptureTokenTest` (1) | capture-loop text | unchanged where the loop stays; repointed where an offer moved | yes where needed | the suites |
| `holdEligible` moves | `WarmHoldTest` (7, value machine) | unchanged | unchanged | no | the suite |
| device rows | `CaptureWithSilenceStopDeviceTest`, `SilenceStoppedTakeTranscribesDeviceTest`, `VoicePipelineDeviceTest` | bind by AIDL | unchanged | no | run on the emulator (phone route) |

The exact row-by-row repoint table is produced from the producer before chunk 4: `grep -n 'body(' app/src/test/java/com/envi/wispr/audio/*.kt` lists every function a guard reads; each is mapped to the file that holds it after the move. Four rows are REWRITES of the assertion, not repoints, because the text they pin changes with the move: `LiveGateWiringTest.bytesBeforeLiveAreNotWrittenAndFeedNothingButTheGate` (`:31-36`, pins `offerToDetector(` and the two picture lines; becomes `detector.offer(` and `picture.offer(`); `LiveAudioMeterWiringTest.theCountersAndTheTakeEndLineAreWired` (`:189-200`, spans the moved push and counters and the kept take-end line, whose interpolation becomes `active.picture.pushes`); the `SilenceStopWiringTest` rows at `:68-88`, `:108-115`, `:232-239` (name `offerToDetector`, `vadConnectionFor(active: CaptureSession)`, `ring = if`, `pendingBlock = if`, which become `DetectorFeed`'s constructor and members); `CaptureBufferOwnershipTest` (`:73-76`, expects the detector ring built inside the service; becomes the service constructing `DetectorFeed` and `DetectorFeed` owning the storage). Also rewrites, because the constant moves: `CaptureBufferOwnershipTest` `:54`, `:64-67`, `:98-103` (the constant's owner, the read buffer's size, the buffer-size log). Every rewrite keeps the assertion's MEANING and is listed in the receipts with its revert.

## 7. Failure-mode × caller table

Bare enumerations; what each site does on failure is the code's, read there.

| Failure | Sites (`file:line`) |
|---|---|
| a limb's start throws | `:994-1001`, `:1077-1090`, `:1094-1100`, `:786-787`, `:1388-1390`, `:1407-1410` |
| a limb's start throws, no local catch | `:849` (`postDelayed`), `:1092` (feeder `Thread` construction) |
| a close step throws | `:1317-1318`, `:1232-1233`, `:1428-1431` |
| a close step throws, no catch today | `:1336-1338`, `:1543-1546` (the `interrupt()` calls) |
| a callback fires or a worker iteration runs for a finished take | `:732`, `:778`, `:821`, `:802`, `:306-329`, `:1116` (the feeder's loop guard), `:1392`, `:1395`, `:1400` |
| `onDestroy` runs while the capture thread is in `releaseSession` | `:1540-1546` (reads `session` outside the lock), `:1312-1330` (under it) |
| `handOver` during a new take's start | `:480-484`, `WarmHold.kt:48-52` (`ended.compareAndSet(null, END_NEW_TAKE)`), `:680`, `:684`, `:693`, `:698` |
| a queued `routeHandler` message after `quitSafely` | `:1567`; the capture-thread join at `:1550` does not join `routeThread` |

No new sentence anywhere.

## 8. Caller-visible signals

| Signal | Meaning |
|---|---|
| the `:audio` log lines | byte-identical, so every existing log-reading recipe (`wispr_eyes.last_take`, `recording()`, the silence UAT) keeps working; guarded by the repointed rows that pin the literals |

## 9. Fallback source-of-truth audit

No new fallback: each limb's failure keeps the take, exactly as before, and the last-successful-text rule
is untouched (`architecture-rules.md` FACT: heart-and-limbs).

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/audio/PicturePublisher.kt` (new): moved from `:181-191`, `:993-1067`, the getter's body.
- `app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt` (new): moved from `:132-135`, `:199-206`, `:305-330`, `:962-984`, `:1073-1234`.
- `app/src/main/java/com/envi/wispr/audio/TakeRoute.kt` (new): moved from `:637-867` and the session's route fields.
- `app/src/main/java/com/envi/wispr/audio/WarmHoldOwner.kt` (new): moved from `:229-240`, `:1360-1510`.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`: what remains, with the constructor calls and the close calls.
- `app/src/test/java/com/envi/wispr/audio/{SilenceStopWiringTest,LiveGateWiringTest,LiveAudioMeterWiringTest,RecordingCapWiringTest,CaptureBufferOwnershipTest}.kt`: repointed.
- `app/src/test/java/com/envi/wispr/audio/CaptureThreadPathTest.kt` (proposed, new): the allocation-free guard over the two `offer` bodies and `captureLoop`.
- `app/src/test/java/com/envi/wispr/audio/AudioLimbCloseTest.kt` (new): the twice-close rows with fakes for the platform edges.
- `app/src/test/java/com/envi/wispr/audio/AudioServiceShapeTest.kt` (proposed, new): the service declares none of the moved functions; each new type declares exactly one `close`; the identity checks live in the service only.
- `docs/audits/2026-09-21-188-revert-receipts.txt` (proposed).
- `.claude/knowledge/architecture.md` FACT: source-map, the `audio/` row names the four owners.

## 11. Testing

1. **Classes.** The repointed rows keep their declared class (Drift Guards). `CaptureThreadPathTest` (proposed):
   Drift Guard (source text; when it fails a future edit put an allocation or a log on the capture thread,
   which the user feels as dropped audio). `AudioServiceShapeTest` (proposed): Drift Guard. Product coverage stays
   the device rows and the pass.
2. **Reverts.** Named in §11.2, each performed once and seen red.
3. **Not tested.** JVM construction of the service (impossible off the phone); the earbud cells on
   hardware (declared UNVERIFIED, §11.1).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (capture) with three limbs.
- **Recipe:** emulator: six `dictate_emulator` takes into Gmail (judge takes 1 and 3 onward), the rail
  battery against the #187 baseline, `am instrument` for `CaptureWithSilenceStopDeviceTest` (all rows,
  phone route) and `SilenceStoppedTakeTranscribesDeviceTest`; S26: two silent takes
  (`scripts/uat/silent-audio/run.py`) into Gmail and one into a Chrome web input, the take-end lines read.
- **Expected observation:** the editor holds each sentence; every `:audio` log line named by the guards
  appears unchanged (`Recording started`, `route start=`, `route live=`, `Stopped by`, `Live picture:`);
  `polled=0`.
- **UNVERIFIED on hardware, beyond the earbud cells:** the physical S26 phone microphone (selection,
  captured signal, the vendor `AudioRecord` stop and release), because the silent injection path bypasses
  the microphone and an emulator take is not physical-device proof; listed for the founder's next ordinary
  spoken take on the phone, which any later build's use provides.
- **UNVERIFIED on hardware, listed for the founder's next earbud use:** a take on the AirPods (live after
  the link, words present), the hold adopting the route on the next take (`route adopt=` line), the
  earbuds removed mid-wait (`route earbuds removed`), the phone refused with earbuds connected
  (`route refused=`). Neither the emulator nor the injection path can stage a Bluetooth route.
- **Restore:** `restore()`; the emulator's dark mode; nothing on the phone.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| every repointed row in the five guards | Drift Guard | the moved text is where the plan says | move one function back into the service |
| `theCaptureThreadOffersAndNeverWaits` (proposed) in `CaptureThreadPathTest` | Drift Guard | `captureLoop` calls `detector.offer` and `picture.offer` and nothing else of the limbs; the two `offer` bodies contain no `ByteArray(`, `DebugLogger`, `synchronized`, `Thread.sleep`, `.transact`, `remote.` or string template | add a `DebugLogger.log` to `DetectorFeed.offer` |
| `everyLimbIsClosedWhereEveryEndingReaches` (proposed) in `AudioServiceShapeTest` | Drift Guard | `releaseSession` calls `route.close`, `detector.close`, `picture.close`; `onDestroy` calls the same three and `warmHoldOwner.close(WarmHold.END_DESTROYED)` | drop `picture.close()` from `releaseSession` |
| `closingTwiceCleansUpOnce` (proposed) in `AudioLimbCloseTest` (proposed), a JVM suite | Harness Contract | the subjects are the four REAL owner classes; the fakes are constructor-injected operations for every platform edge (`post`/`remove` on the handler, `unbind`, `unregister`, `release`, `interrupt`), each armed to count, and `close` is called twice; every operation counts exactly one | remove the owner's close guard while leaving an armed fake cleanup reference reachable, so its count becomes two (a nulled field or an idempotent platform object cannot mask it because the fake counts calls, not effects) |
| `theServiceKeepsOnlyTheSessionAndTheBinder` (proposed) in `AudioServiceShapeTest` | Drift Guard | the service declares NONE of the functions and fields enumerated in §2.5.1 for the four lifecycles (the full list, generated from that section); each new type declares exactly one `fun close(`; `session ===`/`session !==` appear in the service only | move any one back |
| `everyLogTemplateAndThreadNameSurvivesTheMove` (proposed) in `AudioServiceShapeTest` | Observability Contract | the multiset of `DebugLogger.<level>(TAG, "..."` templates and of `Thread(..., "<name>")` AND `HandlerThread("<name>")` literals (so `AudioRouteThread` `:334` is in it) across `audio/AudioCaptureService.kt` and the four new files equals the multiset captured from `2ed2cdf` (a literal list in the test) | drop or reword one template |
| `theCaptureThreadOffersAndNeverWaits` (forbidden tokens widened) | Drift Guard | as above plus `bindService(` and `unbindService(` in `captureLoop` and the two `offer` bodies | add a `bindService` to `DetectorFeed.offer` |

## 12. Blast radius & rollback

Touched: `audio/` (the service and four new files), the five JVM guards, two new guards, knowledge. Not
touched: every other package, the AIDL, the detector process, the value machines listed in §2.2, the
device tests. Rollback: revert the squash commit.

## 13. Ship criteria specific to THIS change

- [ ] `wc -l` of the service is under 800 and `git diff --stat` shows the moves, not rewrites (`git diff
      --color-moved=zebra` reviewed once).
- [ ] The emulator pass and the two S26 silent takes pass; the four earbud cells are listed UNVERIFIED
      in the PR body.

## 14. Open questions

None that block. The earbud cells' verification date is the founder's next use of the AirPods.

## 15. Related

#26 (the gate and the hold), #151 and #187 (the picture), #176 (the token), #114 and #115 (open, land in
this file next), `docs/audits/2026-09-20-senior-audit.md` REF-11.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered (N/A with the reason)
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it
