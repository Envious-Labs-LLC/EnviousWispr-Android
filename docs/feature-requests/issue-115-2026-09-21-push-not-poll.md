# Issue #115 — A wedged audio process leaves the polling thread hanging, and the take is lost in silence — 2026-09-21

GitHub issue: `#115` (with REF-04 and the History write ordering folded in by the founder's two comments).
Tier: LARGE (session ownership, the AIDL surface, both the session and the audio process, three services'
teardown; `workflow-process.md` RULE: tier-routing). Status: DRAFT after the coverage round (A1, A2, B1, C1, C2, D1, D2, E1 to E4, F1, G1, G2, H1 folded in; H2 half adopted); grounded round 1 next.

Consolidation: this plan is one document; §2.5 carries the trace and the measured premises once and §§3 to 11 point back at it.

**Build order.** Grounded against `main` at 87e07ca (after #193), the base of worktree `issue-115-push-not-poll`.
Three chunks, each leaving the app usable on the emulator: (A) push, not poll; (B) the History write queue;
(C) teardown without a blocking wait.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/aidl/**` (append only), `app/src/main/**` (the capture service, the owner, the
bindings, the paste service, History), `app/src/test/**`, `app/src/androidTest/**` if the instrumentation client
needs the new transaction. `mixed_pr: true`: `Code` (`unit-tests.xml`, `codex-review.md`, `visibility.txt`,
`hardware-uat.json`: the heart path) and `Docs/dev-tooling` (`cited-symbols`, conditional).

**PAR rows closed:** none named; the outcome contract is `architecture-rules.md` FACT: heart-and-limbs (a take
that cannot continue ends visibly, never in silence) and RULE: no-idle-cost (levels are PUSHED).

**Hardware UAT:** Y. The heart's capture and stop stages. On the emulator through wispr-eyes (the founder's phone
is his today): ordinary takes end and transcribe on the pushed ending; a take whose audio process is KILLED by
pid mid-take ends with the existing sentence; a take whose audio process is FROZEN (`kill -STOP` by pid on the
disposable emulator) ends with the new wedge sentence within the bound instead of hanging; the next take after
each starts normally. The stalled Room write and the stalled capture thread are JVM rig rows (§11.1 says which).

## Preface — User Rubric

1. **Who.** Aaron Wu, front-end engineer with RSI, dictating a pull-request description in GitHub on his phone by
   the side button, one hand. Thirty seconds ago he pressed to start; thirty seconds from now he wants the text
   in the box and his hand off the phone.
2. **Why.** "I said the whole thing, pressed stop, and it just sat there. No words, no error. I had to say it
   all again." A silent loss is the one failure he cannot work around one-handed.
3. **How.** Reactive: the side button to stop, as always; the app decides the rest.
4. **Apps.** GitHub, Slack, Notion, VS Code's web editor: any editor, because the failure is inside the app.
5. **Input.** "This PR moves the retry loop into the client", "closes issue four twelve", "the flaky test is
   quarantined, see the comment", "ready for review after CI", "thanks Priya for the catch".
6. **Success.** He notices nothing: stop, a second of processing, the words. When the microphone helper dies
   or freezes, he gets one sentence within a few seconds and can try again; the words are not stuck in a
   recorder that never closes.
7. **Wrong-not-broken.** The take ends with a sentence too eagerly (a slow phone makes a healthy capture
   look frozen) and he loses a long dictation to a false alarm; he stops trusting long takes.
8. **Power user.** He force-stops the app from Settings and re-grants accessibility (#131 territory); he
   should never need to.
9. **Control.** None wanted. The ladder has one rung: the take always ends, with words or with a sentence.

**Cross-persona.** Priya, Diana, Marcus, Meera, Elena, Frank: identical. Frank's wrong-not-broken is the false
alarm on an old, slow phone, which sets the wedge bound (§3 A5) well above any measured tick gap. No tension.

## 0. TL;DR

- Three members of one class on 87e07ca: the owner BLOCKS on something another thread or process may never
  finish. (1) `DictationSessionCoordinator.startPolling` and `waitForLive` run a thread per take that asks
  `:audio` synchronously every tick (`elapsedMs`, `isCapturing`, `terminalReason`, `liveState`,
  `silenceStopStatus`, the route kind); a binder that stops answering parks the thread inside a call. (2)
  Three services block their main thread at teardown (`runBlocking` in `DictationSessionCoordinator.destroy`
  and `PasteAccessibilityService.onDestroy`, `Thread.join` in `AudioCaptureService.onDestroy`), and the stop
  path `runBlocking`s for the draft id. (3) History writes are launched on the IO pool and can land out of
  order (`processing` after `asr_error`).
- After (A): `:audio` PUSHES the take's events to the owner over an appended `ITakeListener` (proposed):
  live, a tick each second, the silence status, and the ending WITH the closed file's path. The owner's two
  polling threads are deleted. A take whose pusher goes silent for `TAKE_SILENT_BOUND_MS` (proposed) while
  RECORDING ends as `AUDIO_PROCESS_UNRESPONSIVE` (proposed) with a sentence: the hang becomes a reported
  failure, never a silent loss, and the bound is a main-thread timer that exists only during a take.
- After (B): one application-owned `HistoryWriteQueue` (proposed) applies every History write in the order it
  was issued, on one worker; the owner and the paste service enqueue and never await.
- After (C): the three teardowns commit in-memory state, cancel owned work, release bindings and return; the
  interrupted-row write is enqueued, the capture thread is told to stop and abandoned, nothing joins.
- Guards: rig rows for a silent pusher (ends with the sentence within the bound), a killed pusher (unchanged
  sentence), an ordering row for the two stop-path writes (the #186 row `asrNotReadyEndsProcessing` grows its
  status assertion back), a stalled-Room row (destroy returns; the write lands later), a stalled-capture-thread
  row (`AudioCaptureService.onDestroy` returns), shape rows (no `runBlocking`/`Thread.join` in the three
  teardowns, no polling thread in the owner), the AIDL append rule pinned.

## 1. Problem

`startPolling` (`DictationSessionCoordinator.kt:616-687`) starts `DictationPollingThread`, which every 100 ms calls
`service.elapsedMs()`, `service.isCapturing()`, then on an ending `service.terminalReason()`, and inside the
notice helpers `service.silenceStopStatus()` and `service.inputRouteKind()`; `waitForLive` (`:508-553`) polls
`service.liveState()`, `isCapturing()` and `lastStartFailure()` every `LIVE_POLL_MS` under a wall-clock bound
that a hung call never reaches. Every one is a synchronous binder call into `:audio`. A DEAD `:audio` is handled
(`onServiceDisconnected` → `handleServiceFailure(AUDIO_PROCESS_DIED)`, `:416`); a WEDGED one (alive, not
answering: binder thread pool exhausted, the process frozen by the OS, a native hang) is not: the thread blocks,
`isCapturing` is never read false, `stopAndTranscribe` never runs, and the recorder stays up with the take
inside it.

Teardown (`destroy`, `:1585-1620`): three `runBlocking(Dispatchers.IO)` on the Service's main thread (the
interrupted draft id, `serviceJob.cancel(); serviceJob.join()`, the interrupted write); `stopAndTranscribe`
(`:838`) `runBlocking { draftCreation?.await() }` on the polling thread; `PasteAccessibilityService.onDestroy`
(`:631-633`) joins every child of `historyScope`; `AudioCaptureService.onDestroy` (`:747`) and
`waitForFileReady` (`:728`) join the capture thread for up to 2 s.

Ordering: `updateDraftStatus` and `discardDraft` (`:1503-1556`) each `scope.launch` a Room write; Room's query
executor is a pool, so `processing` then `asr_error` land in either order (#186 found it; the row
`asrNotReadyEndsProcessing` asserts the insertion result only, `DictationSessionCoordinatorTest.kt:360`).

## 2. Goals & non-goals

### 2.1 Goals
- A take whose audio process dies OR stops answering ends with a sentence within a bounded time; nothing in the
  owner blocks on `:audio` during a take.
- History writes for one row land in the order they were issued.
- No service blocks its main thread at teardown; the next take starts normally after every staged failure.
- The AIDL surface grows by append only; the instrumentation client keeps binding.

### 2.2 Non-goals
- Recovering audio from a wedged process (the words up to the wedge are in its file; if the process never
  closes the file they are lost; the sentence says so). A later issue may transcribe a partial file.
- #131 (force-stop clears the accessibility grant).
- Changing what a take's ending MEANS (the `CaptureEnding` set and its sentences stand).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

**The audio process's facts and where each is born** (`AudioCaptureService.kt` at 87e07ca):
- live: `active.route.markLive()` in the capture loop (`:554`) when the gate opens; `liveVisible = true` (`:560`)
  on the first written buffer; the owner reads `getLiveState()` (`:215-222`) which folds both.
- elapsed: `getElapsedMs()` (`:264`) from the route's start stamp.
- the ending: `claimEnding` (`:597-602`) writes `terminalReason` first-wins; `releaseSession` (`:668`) is "the
  one handoff point every ending reaches" (a stop, a silence stop, a cap, a capture error, teardown): it closes
  the file (`closeResources`), records `lastSilenceStatus`, clears `session`, then closes the detector and the
  picture. The file is CLOSED before `session = null`, so a push from the end of `releaseSession` can carry a
  path that is ready.
- the silence status: `active.detector.status`, read by `getSilenceStopStatus()`.
- the picture: `PicturePublisher` (`PicturePublisher.kt:20`) pushes `onSpectrum` to the one registered
  `IAudioSpectrumListener` (`AtomicReference`, `registerSpectrumListener` `:252`); `oneway`, so the analyser
  never waits on the owner. THIS is the pattern.

**The owner's asks** (`DictationSessionCoordinator.kt`), the closed population from `grep "service\."` inside
`waitForLive`, `startPolling`, `publishSilenceNoticeIfNeeded`, `publishMicrophoneNoticesIfNeeded`,
`stopAndTranscribe` and `finishTakeOrStop`:
| Call | Where | Becomes |
|---|---|---|
| `liveState()`, `isCapturing()`, `lastStartFailure()` | `waitForLive` `:513-538` | `onLive(forced)` / `onEnded` pushed; `lastStartFailure` read ONCE on an ending before live, inside the ending handler |
| `elapsedMs()` | `startPolling` `:621` | `onTick(elapsedMs)` pushed once a second |
| `isCapturing()`, `terminalReason()` | `:629-630` | `onEnded(reason, audioFilePath)` pushed |
| `silenceStopStatus()` | `publishSilenceNoticeIfNeeded` `:718`, `stopAndTranscribe` `:829` | `onSilenceStatus(status)` pushed on change; the stop reads the last pushed value |
| `inputRouteKind()`, `inputRouteReason()`, `liveAfterMs()` | `publishMicrophoneNoticesIfNeeded` `:731`, `publishLive` `:559-561` | carried on `onLive(forced, routeKind, routeReason, liveAfterMs)` |
| `waitForFileReady(2000)` | `stopAndTranscribe` path, `waitForLive` failures | gone: `onEnded` carries the closed file's path; the failure paths that stop the service keep `stopCapture()` (a command, bounded by the binder's own timeout? NO: a binder call has no timeout; see §2.5.4) |
| `takePeakAmplitude()`, `effectiveInputDevice()`, `audioFilePath()`, `lastStartFailure()` | one-shot reads at the ends | gone (coverage A2): every fact the owner read after an ending rides `onEnded` (peak, device label, path, the start failure code); no synchronous read remains after an event ends or advances the take |
| `stopCapture()`, `finishTake()`, `startCaptureForTake(...)` | commands | stay synchronous COMMANDS, each on the owner's scope, never main; a hang there is the same wedge, and the bound (§3 A5) is armed from before the listener is registered until the binding is released, so it covers a command outstanding after `onEnded` (`finishTake`) too |
| `listenForSpectrum`, `stopListeningForSpectrum` | the picture (#187) | unchanged; part of the closed `CaptureLink` population (coverage A1) |

**Consumers of the pushed facts.** `surface.updateElapsed(second)`, `publishDurationWarningIfNeeded(elapsedMs)`,
`publishSilenceNoticeIfNeeded`, `publishMicrophoneNoticesIfNeeded`, `publishLive(forced)`, the ending `when`
over `CaptureEnding` (`:636-665`), `takeFacts.captureTerminal`, `takeFacts.silenceStopStatus`, the route facts.

**The History writes** (`grep "transcripts\."` in the owner and the paste service): `insert` (`:577`, `:1233`),
`updateStatus` via `setDraftStatus` (`:1513`), `discard` (`:1550`), the destroy write (`:1613`);
`PasteAccessibilityService` writes through `historyScope.launch` (`:337`, the insertion outcome) and the bubble
position store (`:303`). `TranscriptRepository` is all `suspend` (`TranscriptRepository.kt:12-67`).

**The teardowns.** `DictationSessionCoordinator.destroy` (`:1560-1640`) called from the Service's `onDestroy`;
`PasteAccessibilityService.onDestroy` (`:609-640`); `AudioCaptureService.onDestroy` (`:738-760`) and
`waitForFileReady` (`:724-732`).

### 2. Existing authority
The spectrum push (#187) is the transport; `TakeArbiter` is the owner's first-wins ending latch; `CaptureEndingClaim`
is the audio side's; `pendingHistoryUpdates` (`:185`) already collects the launched History jobs for the
destroy path. `ModelBootstrapApplication` (`models/ModelBootstrapApplication.kt:7`) is the application class an
application-owned queue hangs off. No History queue exists (`grep -rn "Queue" app/src/main/java/com/envi/wispr/history`: nothing; `HistoryPublicationPolicy`, `HistoryRecovery` are policy objects).

### 3. Prior attempts and live direction
- #44 added the fifth poll; Codex routed the class here rather than guarding one call.
- #187 replaced the amplitude poll with the push and left the ending poll (its KDoc: "the take and its polling
  thread carry on"; "a picture that never arrives (a wedged audio process, #115) leaves the rail holding its last
  shape until the pill hides").
- #186 moved the loop into the coordinator unchanged, found the write race, and named this issue as the owner
  of the queue (`asrNotReadyEndsProcessing`).
- #188 made every audio-side owner's `close` non-blocking ("each owner's close tells its thread to stop and
  abandons it"); the capture thread's `join` at teardown is the one wait left there.
- The audit (REF-04) named the three teardown waits and proposed the queue.

### 4. Boundaries a naive design misses
- **A binder call has no timeout.** Replacing the polls with a push removes the ASKS; the COMMANDS
  (`stopCapture`, `finishTake`, `startCaptureForTake`) stay synchronous binder calls and can hang the same way.
  The bound therefore watches the WHOLE take (armed at `startCaptureForTake`, disarmed at `onEnded`), and the
  commands run off the main thread (they already run on the owner's scope or the main thread via
  `handleCommand`; §3 A6 says which move to the scope). A hang inside a command on the MAIN thread would
  freeze the app's UI; the plan moves every capture command off main.
- **Process death versus wedge.** `onServiceDisconnected` fires for death; a wedge fires nothing. The bound
  is the only signal for a wedge, and it must be long enough that a slow phone under load never trips it:
  ticks come once a second from the capture loop, which runs at audio rate; three missed seconds is the
  proposal (§3 A5), measured against the emulator's tick gaps in §11.1.
- **The instrumentation client.** `app-debug-androidTest.apk` binds `IAudioCaptureService` by transaction
  number (`architecture-rules.md` RULE: aidl-is-append-only); the new methods are appended after
  `unregisterSpectrumListener`, and the old polled getters STAY (legacy, like `getSpectrumBands`).
- **oneway and ordering.** `oneway` calls from one binder to one listener are delivered in order on the
  receiver's binder thread pool but may run CONCURRENTLY on different pool threads; the owner posts each
  event to its main thread (as `publishLive` already demands) so the sequence is serialised there. The
  ending after the ticks: the owner's `TakeArbiter` and the RECORDING check make a late tick harmless.
- **The file path on the ending.** `releaseSession` closes the file BEFORE the push, so the owner never
  calls `waitForFileReady`; the two `waitForLive` failure paths that call it today are replaced by the
  ending push carrying the path (and `stopAudioService` after).
- **The queue and process death.** A queue in the Application's scope survives a Service's destroy but not
  the process; a write still queued when the process dies is lost exactly as a launched job was. The
  `interrupted` write at destroy is enqueued FIRST in `destroy` (before anything else) so it is the next
  write applied; `TranscriptDao.recoverStaleDrafts` on the next start remains the backstop, unchanged.
- **The clean-stop marker.** `PasteAccessibilityService.onDestroy` marks the stop clean AFTER its blocking
  drain on purpose (a kill during the drain must not read as clean). With the queue, the marker is written BY
  the queue as the last enqueued job, so the ordering survives without the wait.
- **The capture thread owns `AudioRecord` and the file.** `onDestroy` today joins it 2 s and, if still alive,
  abandons the resources to process death (`:750-756`). Without the join the same abandonment happens 2 s
  sooner; nothing else in `onDestroy` needs the thread gone (`stopRecording()` above it signals the stop).
  `waitForFileReady` stays as a legacy AIDL method (append-only) with no production caller.

### 5. High-risk premises, with evidence
- **P1. A wedged `:audio` hangs the take today.** Staged on the JVM rig: a `FakeCapture` whose `isCapturing()`
  blocks forever leaves the take RECORDING with no ending (a row written first, red-by-today, then green after
  chunk A). On the emulator: `kill -STOP <pid of com.envi.wispr:audio>` mid-take (the disposable emulator; the
  pid read from `pidof` in the same command) leaves the recorder up with no sentence today; after chunk A it
  ends with the wedge sentence within the bound. To be MEASURED before chunk A (§11.1 a).
- **P2. `oneway` pushes from `:audio` arrive while the owner's binder pool is free.** The owner's process is the
  default process; its binder pool serves the paste service and the owner's AIDL. Measured today by the
  spectrum push (fifteen a second, #187, on the phone and the emulator). Not re-measured.
- **P3. One tick per second from the capture loop is regular enough for a 3 s bound.** The loop reads at audio
  rate (`:540-566`); the emulator run records every tick gap over three takes (§11.1 b); the largest gap sets
  the floor the bound must clear by 3x.
- **P4. The two stop-path writes race today.** Evidence: #186's hosted-runner failure (session log 2026-09-20)
  and the row's comment. The build's ordering row uses a fake DAO that applies writes on two threads with a
  staged delay and must go red on 87e07ca.

## 3. Design

### A. Push, not poll (chunk A)
A1. **`ITakeListener` (proposed)**, `app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl`, all `oneway`:
   `onLive` (proposed) `(boolean forced, int routeKind, int routeReason, long liveAfterMs)`, `onTick` (proposed) `(long elapsedMs)`,
   `onSilenceStatus` (proposed) `(int status)`, `onEnded(int terminalReason, int startFailure, String audioFilePath, int silenceStatus, float takePeakAmplitude, String effectiveInputDevice)`.
   `IAudioCaptureService` appends `registerTakeListener(ITakeListener)` and `unregisterTakeListener(ITakeListener)`
   after `unregisterSpectrumListener`; the polled getters stay, marked LEGACY like `getSpectrumBands`.
A2. **`TakeEventPublisher` (proposed)** in `:audio`, service-scoped like `WarmHoldOwner`, holding one
   `AtomicReference<ITakeListener?>`; every call is `runCatching` (a dead owner must not throw into the capture
   loop) and never waits. Sites: `markLive` path (`:554`, the first buffer after the gate opens) → `onLive`;
   the capture loop → `onTick` when the elapsed second changes (one line, no allocation beyond the binder call,
   on the capture thread AFTER the write: `protect-audio-asr-stability` says no work on the audio callback
   thread; the capture loop is our own reader thread, where the picture is already offered); the detector's
   status change → `onSilenceStatus` (from `DetectorFeed`, where the status is written); the end of
   `releaseSession`, after `closeResources` and `session = null`, → `onEnded` with the reason, the closed
   file's path, the last silence status, the peak and the device label.
A3. **The owner** registers its listener in `startCaptureForTake`'s caller (`tryStartRecording`, next to
   `listenForPicture`) BEFORE the start call, so no event can precede registration; each event is
   `host.postToMain { … }` and then handled by the existing code paths: `onLive` → `publishLive(forced)` with
   the route facts stamped; `onTick` → `updateElapsed`, `publishDurationWarningIfNeeded`,
   `publishMicrophoneNoticesIfNeeded` once (the tip gate); `onSilenceStatus` → `publishSilenceNoticeIfNeeded`
   with the pushed value; `onEnded` → the existing `when (CaptureEnding.fromAidl(reason))` with the path
   handed to `stopAndTranscribe`, `takeFacts.captureTerminal`, `takeFacts.silenceStopStatus`,
   `takeFacts.peakAmplitude` (from the payload's peak, replacing `takePeakAmplitude()`) and `captureDeviceLabel`
   for the row's `captureDevice` (from the payload's label, replacing `effectiveInputDevice()`), with no
   replacement getter call (coverage B1). `startPolling`, `waitForLive`, `DictationPollingThread`, `LIVE_POLL_MS` and the `liveState()`,
   `isCapturing()`, `elapsedMs()`, `terminalReason()`, `silenceStopStatus()`, `inputRouteKind()`,
   `inputRouteReason()`, `liveAfterMs()`, `waitForFileReady()`, `takePeakAmplitude()`, `effectiveInputDevice()`,
   `audioFilePath()` and `lastStartFailure()` members of `CaptureLink` are deleted (`GR-MIGRATION-COMPLETE`);
   `onEnded` carries the start failure code, so the ending-before-live case reads nothing. After the change the
   only synchronous calls into `:audio` are the three commands and the two listener registrations.
A4. **Live wait.** STARTING ends by `onLive` (→ RECORDING), by `onEnded` before live (→ the existing
   `CAPTURE_ENDED_BEFORE_LIVE` / `CAPTURE_START_EARBUDS_REFUSED` by reading `lastStartFailure()` once), or by the
   existing `LIVE_WAIT_BOUND_MS` deadline, now a main-thread `postDelayed` instead of a polling thread.
A5. **The wedge bound.** `TAKE_SILENT_BOUND_MS` (proposed, 3 000 ms, revisited after §11.1 b): a main-thread
   `Runnable` re-posted on every event from the pusher, armed BEFORE the listener is registered and the start
   command is sent, and effective until the binding is released (coverage C1: it covers a start that never
   returns, a registration that hangs, a command outstanding after `onEnded` such as `finishTake`, and a wedge
   after the take moved to PROCESSING or CANCELLING while a capture command was still out); when it fires,
   `handleServiceFailure(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)` if the take is STARTING or RECORDING, else
   the outstanding command is abandoned and the binding released (the take's words are already in hand)
   (proposed member, `TerminalResult.AUDIO_INTERRUPTED`, sentence in `TakeNotices`: "The microphone stopped
   answering. Try again.", `TelemetryChannels` both sets, `TakeNoticesTest` and `TakeFactsTest` rows), then
   `pipeline.stopAudioService()` and `unbind` (a binder to a wedged process is released, never called). The
   bound is disarmed when the last outstanding command returns after `onEnded`, and by every terminal path's
   release of the binding. No timer exists outside a take (RULE: no-idle-cost). Non-triggers, each a row
   (coverage C2): a long silence with auto-stop OFF (ticks continue; the pusher is the capture loop, not the
   detector), the picture push stopping (a different listener), a STARTING gate open longer than the bound
   (ticks start at the first buffer, BEFORE the gate opens: `onTick` is sent from the loop from the first read,
   live or not), and a paused tick source (the only thing that pauses the capture loop is a blocked
   `AudioRecord.read`, which IS the wedge).
A6. **Commands off main.** `stopCapture()` and `finishTake()` are called from `stopAndTranscribe` and
   `finishTakeOrStop`, which run on the owner's scope today except the cancel path; the plan routes the cancel's
   `stopCapture` through the scope too, so no synchronous binder call to `:audio` runs on the Service's main
   thread (`never-block-a-binder-or-ui-thread`).

### B. The History write queue (chunk B)
B1. **`HistoryWriteQueue` (proposed)**, `history/HistoryWriteQueue.kt`, owned by `ModelBootstrapApplication`
   (one per process; the default process holds the owner and the paste service): a `Channel<HistoryWrite>`
   (unlimited) drained by ONE coroutine on `Dispatchers.IO` in an application `SupervisorJob` scope; each write
   is a `suspend (TranscriptRepository) -> Unit` with a content-free label for the log; a write that throws is
   logged by label and the drain continues. `enqueue` never suspends and never blocks.
B2. The draft INSERT (`:577-595`, today `scope.async` on the owner's scope) is the FIRST queued write of a take
   (coverage D1): it runs on the queue's worker under application ownership, publishes its id to a
   `CompletableDeferred<Long>` the later queued writes await INSIDE the worker, and is never cancelled by
   `destroy` (the owner's scope cancel no longer touches it). `surface.attachTranscript(id)` and the journal
   association run from the same queued job. `updateDraftStatus`, `discardDraft`, `setDraftStatus`'s callers, the
   destroy `interrupted` write and the paste service's outcome write enqueue instead of
   `scope.launch`/`historyScope.launch`; `pendingHistoryUpdates` and the destroy join are deleted. Ordering is by
   ENQUEUE time, on one worker, so a row's writes land in issue order.
   **Writer inventory** (coverage D2, `TranscriptRepository.kt:12-69`): per-take writers are `insert`,
   `updateStatus`, `discard`, `finalize`, `finalizeInsertionOutcome` (the owner and the paste service) and
   they ALL go through the queue; `setKept`, `delete`, `deleteAll`, `pruneWordlessRows` and
   `recoverStaleOpenRows` are the History screen's and the start-up recovery's writers, on their own scopes,
   and stay outside it. The single-writer claim is narrowed to per-take writes.
B3. `asrNotReadyEndsProcessing` asserts `status == asr_error` again; the rig's fake DAO gains a staged-delay mode
   (two worker threads, the first write delayed) that reproduces the race on 87e07ca (P4).

### C. Teardown without a blocking wait (chunk C)
C1. `DictationSessionCoordinator.destroy`: enqueue the `interrupted` write FIRST (it needs the id: the queue
   worker awaits the draft's deferred), then `serviceJob.cancel()` (no join), then the existing in-memory
   invalidation and release; the `runBlocking` blocks are deleted. The late-polish-callback race the join
   protected against (coverage E1): every finalization write ADMITTED before `destroy` was already enqueued
   before the `interrupted` write, so the queue applies it first and the `interrupted` write lands last; a
   finalization not yet admitted loses the `TakeArbiter` claim to `destroy` and never enqueues. Nothing can
   restore `ready` after `interrupted`.
C2. `stopAndTranscribe`'s `runBlocking { draftCreation.await() }` (`:838`) becomes an enqueued write that awaits
   the id on the worker.
C3. `PasteAccessibilityService.onDestroy`: the outcome write is enqueued (it is a per-take writer); the
   `joinAll` is deleted and `markStopWasClean` is enqueued as the LAST job on the queue. The bubble-position
   saves (`:303`) stay on the service's scope and teardown may discard an in-flight one (coverage E3): the
   position is a preference whose next save wins, and the last landed value is what the bubble reopens at.
   Teardown never calls a synchronous unregister on the audio binder (coverage E4): the listener registration is
   released by `unbindService` alone, on every path, including the wedge.
C4. `AudioCaptureService.onDestroy`: `stopRecording()` signals; the `thread.join(2_000L)` and its branch are
   deleted; the comment's abandonment contract stands from the moment `onDestroy` returns. The interleaving
   where the old capture thread still owns `AudioRecord` when a new take starts in the SAME process (coverage
   E2): `startRecording` refuses while `session != null` (`:328`), and `releaseSession` clears `session` only
   after `closeResources` released the recorder, so a new take cannot open a second recorder while the old one
   is held; a thread parked inside `AudioRecord.read` keeps `session` set and the next start fails with
   `START_FAILURE_OTHER`, which the owner reports as `CAPTURE_ENDED_BEFORE_LIVE`; measured at build by
   `AudioLimbCloseTest`'s stuck-thread row starting a second take.

**Pipe or bucket.** The leak is created wherever the owner ASKS or WAITS; a bound on the polling thread alone
(the issue's shape 2) is a bucket. Chunk A removes the asks (pipe); the wedge bound is the one bucket that must
stay, because a synchronous command has no timeout at the binder, and it is named as such. Chunks B and C remove
the waits (pipe). Upstream of all three: nothing; the process boundary is the design.

## 3b. Ownership justification
The publisher lives in `:audio` because the facts are born there (`releaseSession` is the one handoff every
ending reaches); the listener adapter lives in `PipelineBindings` next to the spectrum adapter; the bound
lives in the owner because the owner owns the take's state. The queue lives in the Application because two
Services in one process write History and a Service's scope dies with it.

## 4. Contract deltas
- AIDL: `ITakeListener` new; `IAudioCaptureService` gains two appended methods. Nothing renamed or reordered.
- `CaptureLink`: nine polled members deleted, `listenForTake` (proposed) / `stopListeningForTake` (proposed) added.
- `TerminalReason.AUDIO_PROCESS_UNRESPONSIVE` added with a sentence; `dictation.terminal` `reason` gains a value.
- `TranscriptRepository` unchanged; `HistoryWriteQueue` new; `ModelBootstrapApplication` exposes it.

## 5. State and lifecycle audit
Take: IDLE → STARTING (`startCaptureForTake`, bound armed, listener registered) → RECORDING (`onLive`) →
PROCESSING (`onEnded`) → … → IDLE (bound disarmed, listener unregistered in `finishSession`). Exits from
STARTING: `onLive`, `onEnded` before live, the live deadline, the wedge bound, cancel, destroy,
`onServiceDisconnected`. Exits from RECORDING: `onEnded` (five reasons), the wedge bound, cancel, destroy,
`onServiceDisconnected`. Every exit claims the `TakeArbiter` first; a late `onEnded` after a claimed ending is
dropped by the RECORDING check on the main thread. Destroy: the `interrupted` write enqueued, the job cancelled,
the arbiter claimed, the listener unregistered (`runCatching`), the binding unbound. Queue: the worker outlives
every Service and dies with the process; a write left in the channel at process death is lost as a launched job
was (§2.5.4).

## 6. Consumer matrix
- The recorder surface: unchanged inputs (`updateElapsed`, `publishLive`), now from pushed events.
- Telemetry: `captureTerminal`, `silenceStopStatus`, the route facts from the pushed values; one new reason.
- `AudioLimbCloseTest`, `CaptureThreadPathTest`, `AudioServiceShapeTest` (#188): the shape allowlist grows by
  the publisher field and `TakeEventPublisher`'s close; the log-template multiset gains the publish lines.
- The instrumentation APK: binds by transaction number; the appended methods do not move any.
- `DictationSessionRig.FakeCapture`: drops the polled members, gains a way to push events and a way to go silent.

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| `:audio` dies mid-take | the OS | `onServiceDisconnected` | the existing "Microphone service stopped unexpectedly" | `interrupted`-class row as today | next take |
| `:audio` freezes (no events for the bound) | the OS or a native hang | the wedge bound | "The microphone stopped answering. Try again." within ~3 s | row discarded as a mid-take failure (as `CAPTURE_FAILED_MID_TAKE`) | next take (the service is stopped and unbound; a frozen process is killed by the OS or by `stopService`) |
| `onEnded` arrives after the bound fired | a slow pusher | main thread | nothing more; the take already ended | unchanged | none |
| a History write throws | Room | the queue worker | nothing; the next write proceeds | that write lost, logged by label | `recoverStaleDrafts` on next start |
| the process dies with writes queued | the OS | none | nothing | those writes lost, as before | `recoverStaleDrafts` |
| the capture thread outlives `onDestroy` | a stuck `AudioRecord` read | `AudioCaptureService.onDestroy` | nothing; the process is being torn down | the file abandoned to process death, as today after 2 s | next take in a new process |

## 8. Caller-visible signals
Added: one sentence for the wedge. Removed: none. The `no speech`/cap/silence sentences unchanged.

## 9. Fallback source-of-truth audit
The ending is the pushed reason; the legacy getters are not consulted. The bound is the fallback for a missing
push and produces its own reason. The queue is the only History writer in the default process; the DAO's
`recoverStaleDrafts` remains the on-start backstop.

## 10. File-by-file changes
- `app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl` (new), `IAudioCaptureService.aidl` (two appended).
- `app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt` (new), `AudioCaptureService.kt` (register,
  publish at the four sites, `onDestroy` without the join), `DetectorFeed.kt` (status change hook).
- `app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt` (`CaptureLink` delta), `PipelineBindings.kt` (the
  listener stub), `DictationSessionCoordinator.kt` (events, bound, deletions, destroy), `TerminalReason.kt`,
  `TakeNotices.kt`, `telemetry/TelemetryChannels.kt`.
- `app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt` (new), `models/ModelBootstrapApplication.kt`,
  `paste/PasteAccessibilityService.kt` (enqueue, `onDestroy`).
- Tests: `DictationSessionRig.kt`, `DictationSessionCoordinatorTest.kt`, `SessionOwnerShapeTest.kt`,
  `audio/AudioServiceShapeTest.kt`, `audio/AudioLimbCloseTest.kt`, `history/HistoryWriteQueueTest.kt` (new),
  `TakeNoticesTest.kt`, `telemetry/TakeFactsTest.kt`, `TelemetryContractsTest.kt`, `paste/*ShapeTest` as needed.
- `docs/audits/2026-09-21-115-revert-receipts.txt`, `docs/audits/2026-09-21-115-emulator-pass/`.

## 11. Testing
1. Classes: the silent-pusher row, the killed-pusher row, the ordering row and the stalled-Room destroy row are
   Product Outcome (Aaron's words or a sentence within seconds); the shape rows are Drift Guards; the AIDL
   append row is a Drift Guard; the publisher's close row is a Harness/Drift row on `:audio`.
2. Reverts: §11.2.
3. Not tested: a wedge on the physical phone (never staged there); a native hang inside `AudioRecord.read`
   (the emulator's freeze stands in for every wedge shape, since the owner sees only silence).

### 11.1 Hardware UAT spec
- Emulator, wispr-eyes, debug build: (a) BEFORE chunk A on 87e07ca: start a take, `kill -STOP` the `:audio` pid,
  press stop: measure that the recorder stays up with no sentence (P1 red-by-today), then `kill -CONT` and
  `restore()`. (b) After chunk A: three ordinary takes ending by stop, by silence and by the cap (the cap on the
  emulator: NOT RUN, ten minutes; stop and silence only), transcribed and inserted by COMMIT, every `onTick`
  gap recorded from the owner's log (P3). (c) The frozen-pusher scene (`kill -STOP` by pid): the wedge sentence within
  the bound; a frozen process keeps its binder alive from the owner's view and `stopService` cannot make it
  run teardown (coverage H1), so the scene then `kill -CONT`s (or `kill -9`s) the pid and only THEN runs the
  next take, which must be normal; the record states which. (d) The killed-pusher scene (`kill -9` by pid):
  the existing sentence, the next take normal. (g) The cap: one take with auto-stop OFF left running to the
  ten-minute cap on the emulator (coverage H2), ending by the pushed `MaxDuration` with its sentence and the
  words transcribed. A blocked Room write on the emulator is NOT stageable without a debug seam on the
  database, which would be a test seam on production storage; it stays a rig row.
  (e) After chunk C: force-stop during a take and the next take starts; the History row reads `interrupted`.
  (f) `restore()`. Founder's phone: NOT RUN (his instruction); build delivered through Play.
- The stalled Room write and the stalled capture thread: JVM rig only, stated in `hardware-uat.json`.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `DictationSessionCoordinatorTest.aSilentAudioProcessEndsTheTakeWithinTheBound` (proposed) | Product Outcome | the fake pushes `onLive` then nothing; the take ends `AUDIO_PROCESS_UNRESPONSIVE` with the sentence within the rig's bound; capture is stopped and the service unbound | disarm the bound |
| `DictationSessionCoordinatorTest.aPushedEndingTranscribesWithoutAsking` (proposed) | Product Outcome | `onLive`, ticks, `onEnded(manual, path)`: the take transcribes and inserts | reintroduce a polled getter on `CaptureLink` and call it: the shape row below goes red on the name, and this row goes red because the fake's getter throws (coverage G2) |
| `DictationSessionCoordinatorTest.audioDiedWhileRecordingEndsAsFailure` (exists) | Product Outcome | process death still ends the take with its sentence on the push design | remove the disconnect handler |
| `DictationSessionCoordinatorTest.theNextTakeStartsAfterAWedge` (proposed) | Product Outcome | after the wedge ending, a new take on a fresh fake goes live and completes | leave the bound armed or the binding held |
| a paste-service shape row (`paste/AutoPasteWiringTest` grown, or a new `PasteServiceTeardownTest` (proposed)) | Drift Guard | `onDestroy` has no `runBlocking`/`joinAll`; the outcome write enqueues | restore the drain |
| `AudioServiceShapeTest` (grown, coverage G1) | Drift Guard | the only synchronous AIDL calls the owner makes are `startCaptureForTake`, `stopCapture`, `finishTake` and the two registrations (a token inventory over `PipelineBindings`) | add a getter call |
| the instrumentation client's binding | Drift Guard | `app/src/androidTest` binds `IAudioCaptureService` by the unchanged transaction numbers: the AIDL append row plus one instrumented row already binding (UNCHECKED which; named at build) | reorder |
| `DictationSessionCoordinatorTest.aLateEndingAfterTheBoundIsDropped` (proposed) | Drift Guard | `onEnded` after the bound fired changes nothing | remove the RECORDING check |
| `DictationSessionCoordinatorTest.asrNotReadyEndsProcessing` (grown) | Product Outcome | status `asr_error` AND insertion result, with the racing fake DAO | launch the writes instead of enqueueing |
| `HistoryWriteQueueTest.writesLandInIssueOrderAcrossWorkers` (proposed) | Product Outcome | two enqueued writes with the first delayed land first-then-second | drain on two workers |
| `DictationSessionCoordinatorTest.destroyReturnsWhileARoomWriteIsStalled` (proposed) | Product Outcome | a fake DAO that blocks: `destroy()` returns on the main thread within the rig's bound; the `interrupted` write lands when the DAO unblocks | restore the `runBlocking` |
| `AudioLimbCloseTest.onDestroyReturnsWhileTheCaptureThreadIsStuck` (proposed) | Product Outcome | a capture thread parked in a read: `onDestroy` returns without waiting | restore the join |
| `SessionOwnerShapeTest.theOwnerNeverBlocksAndNeverPolls` (proposed) | Drift Guard | the coordinator source has no `runBlocking`, no `Thread(`, no `Thread.sleep`; the paste service and the audio service have no `runBlocking`/`join` in `onDestroy` | restore any |
| `AudioServiceShapeTest` (grown) | Drift Guard | the publisher field and its log lines | drop the field |
| an AIDL append row in `AudioServiceShapeTest` or a new `AidlAppendTest` (proposed) | Drift Guard | the two new methods are the LAST in `IAudioCaptureService.aidl`; nothing above `unregisterSpectrumListener` changed (a hash of the text above it) | reorder |

## 12. Blast radius & rollback
Every take's live wait, tick and ending; every History write in the default process; three teardowns. Chunked
so each lands usable; rollback is the PR revert of the Kotlin. The appended AIDL methods are NOT reverted once
any client shipped against them (coverage F1): they stay as legacy transactions, like `getSpectrumBands`.

## 13. Ship criteria specific to THIS change
- P1 measured red-by-today on the rig and the emulator before chunk A; P3's tick gaps under 1 s on the emulator
  (else the bound and the tick rate are re-examined before shipping).
- Every row in §11.2 green; receipts red; the three emulator scenes (c), (d), (e) as specified.
- Codex all-clear with a confirming rerun per chunk's review, then one for the whole.

## 14. Open questions
- Bound value (3 s) versus Frank's slow phone: the emulator's tick gaps decide; the physical phone is not
  measured this session. Default: 3 s, revisited when the founder's phone pass reports.
- Whether `waitForFileReady` should be deleted from the AIDL: no (append-only; legacy like `getSpectrumBands`).

## 15. Related
#44 (the fifth poll), #187 (the picture push), #186 (the coordinator, the race), #188 (non-blocking closes in
`:audio`), #131, REF-04 in `docs/audits/2026-09-20-senior-audit.md`.
