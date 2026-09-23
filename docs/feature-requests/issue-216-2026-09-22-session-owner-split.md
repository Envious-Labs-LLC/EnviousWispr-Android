# Issue #216 — The session owner delegates capture and finalization — 2026-09-22

GitHub issue: `#216`. Tier: REFACTOR (session ownership and both engines' callers move across files; behaviour unchanged). Status: APPROVED (coverage adopted; grounded round 1 adopted; round 2 PROCEED-AS-PLANNED). Built: one deviation, §11 item 2.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The change moves code; no parity row changes status.

**Hardware UAT:** Y. The session owner is the heart. Success for a person: they dictate a sentence into Gmail, it lands once; they dictate again at once, it lands once; they cancel a take while it records and while it processes, and nothing is left in History. Run on the emulator through `wispr_eyes`; the founder's phone pass is queued with #115, #161, #212, #213, #214 and #215 (his 2026-09-21 instruction excludes the phone from the audit queue).

## Preface — User Rubric

User Rubric: N/A — a statement-for-statement move of the session owner's code into two collaborators; every take the user runs takes the same path in the same order, and the 50 coordinator behaviour rows that drive the owner through its constructor are the evidence that it does.

---

## 0. TL;DR

`ui/DictationSessionCoordinator.kt` (1,850 lines) still runs the capture transport (the command lane, the take listener, the silence bound, the live deadline) and the History and insertion half of publication itself. REF-01 of the 2026-09-22 senior audit asks it to keep command admission, `SessionState` and `TakeArbiter`, and delegate the rest. This plan adds an immutable `TakeContext` (proposed), moves the capture transport into `CaptureSessionController` (proposed) and the History row plus the insertion delivery into `SessionFinalizer` (proposed) with its per-take `TakeHistory` (proposed). Both collaborators report to the owner through typed values and cannot move the session state: `SessionState` stays a private enum of the owner, and a new Drift Guard reads the two files as code and refuses any arbiter call in them. Evidence: the 50 behaviour rows unchanged and green, the Drift Guard red before and green after, every source-reading row re-pointed (§11), two emulator takes plus both cancel paths.

## 1. Problem

The audit (`docs/audits/2026-09-22-senior-audit.json` REF-01, Architecture integrity, confidence High) cites `DictationSessionCoordinator.kt:L61-L98`, `L502-L951` and `L1229-L1490` against `architecture-rules.md` RULE: keep-central-types-thin: one class holds the take's state machine, a thread pool of one, two main-thread timers, the binder listener, eleven per-take `@Volatile` fields, the History row's whole lifecycle and the insertion decision. Measured today:

```
wc -l app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt   -> 1850
grep -c "@Volatile" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt -> 20
```

`recordingStartedAtMs` is written at `:782` and read nowhere (`grep -n "recordingStartedAtMs"` returns `:213` and `:782` only).

## 2. Goals & non-goals

### 2.1 Goals
1. `DictationSessionCoordinator` keeps command admission, `SessionState`, `TakeArbiter` reservation and commit, the polish submission and every state transition; it holds one `TakeContext` per take instead of `takeId`, `facts`, `arbiter`, `targetPinAtStart` (removed), `draftId` and `draftCreation`.
2. `CaptureSessionController` owns the capture command lane (`CaptureCommands` thread), `commandCapture` (removed), the `TakeListener`, the silence bound, the live deadline, the ending de-duplication, the start's lane body, the picture registration and `finishTakeOrStop`; it reports to the owner through a sealed `CaptureEvent` (proposed) on the main thread.
3. `SessionFinalizer` owns the History row (`TakeHistory`: draft insert, status, discard, `interrupted`, finalize-or-insert) and the delivery after the commit (route, handoff, clipboard, announcement, insertion telemetry, the delivery log line); it returns a `Delivery` (proposed) value.
4. Neither collaborator can move the session: `SessionState` is private to the owner (the compiler enforces it) and neither file calls `reserve`, `commit`, `commitNow` or `interrupt` (a Drift Guard enforces it).
5. Behaviour is unchanged: every coordinator behaviour row passes without an edit to its assertions.

### 2.2 Non-goals
- The recorder notices (silence unavailable, the Bluetooth tip, the earbuds line, the duration warning and cap) stay in the owner. They read the take's state at the moment they render, and the audit's proposal names only two collaborators; a `RecordingNotices` (proposed) split is a follow-up if the regrade asks for it.
- The per-take progress values `rawTranscript`, `recordingDurationMs`, `captureDeviceLabel` and `takePeakAmplitude` stay owner fields. They are written after admission by the ending and the speech answer and read by the disconnect callbacks, which carry no parameters; the service instance serves one take (it stops itself after every take and a FINISHING owner never returns to IDLE), so they are per-take already. `TakeContext` holds what is fixed at admission.
- The ASR request (`continueAfterEnding`) and the polish submission (`polishAndPublish`, the watchdog, `publishFallback`) stay in the owner: they share `polishSubmissionLock` and the ledger with `cancelProcessing`, which is a transition.
- No AIDL, manifest, process or Gradle change. The constructor of `DictationSessionCoordinator` does not change, so `DictationSessionRig` builds it as today.

## 2.5 Grounding brief

### 1. Producer → owner → consumer

- Commands: `DictationSessionService.onStartCommand` → `coordinator.handleCommand` (`DictationSessionCoordinator.kt:290`), main thread, arrival order. Unchanged.
- Capture events: `:audio`'s `TakeEventPublisher` → `ITakeListener` binder thread → the production link in `PipelineBindings` → `TakeListener` (`PipelineLinks.kt`) → today `takeListener` (`:595`) posts each to main with `host.postToMain`, filters by `takeId`, re-arms the bound, then calls `publishLive`, `onTakeTick`, `publishSilenceNoticeIfNeeded` or `onTakeEnded`. After: the controller's listener does the hop, the filter and the re-arm and calls `events(CaptureEvent.X)` inside the same posted runnable; the owner's `onCaptureEvent` (proposed) dispatches to the same four handlers. Same thread, same order, one runnable per event as today.
- Capture commands: owner → `commandCapture` (`:581`) → `captureCommands` executor (`:243`) → `CaptureLink` binder call. After: owner → `capture.command(what, block)` (proposed) → the same executor in the controller.
- Timers: `armSilenceBound` / `rearmSilenceBound` / `disarmSilenceBound` (`:615-636`) and `host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)` (`:518`) through `SessionHost`. After: the controller posts the same runnables through the same `SessionHost`, so `FakeHost.delayed` sees them as today (`DictationSessionRig.kt:253-272`).
- History: `historyWrites.enqueue` at `:801` (draft insert), `:1282` (finalize), `:1463` (clipboard outcome), `:1501` (history-only outcome), `:1708` (status), `:1752` (discard), `:1791` (interrupted). After: all seven go through `TakeHistory` / `SessionFinalizer`, on the same `HistoryWriteQueue`, in the same call positions (the finalize enqueue stays inside the owner's `synchronized(publishLock)` block, called as `finalizer.enqueueSave`).
- Insertion: `insertion.pasteWhenTargetReturns` (`:1359`), `insertion.releasePinnedTarget` (`:1370`), `host.copyToClipboard` (`:1460`), `FallbackAnnouncement` (`:1488`). After: inside `SessionFinalizer.deliver` (proposed), called by the owner after its commit, exactly where the code runs today.

### 2. Existing authority

`TakeArbiter` (`TakeArbiter.kt`) is the one referee of how a take ends and stays the owner's; `HistoryWriteQueue` is the one writer order and is reused, not wrapped; `SessionHost`, `RecorderSurface`, `InsertionGateway`, `SessionLog` and `PipelineController` are the seams #186 created and both collaborators take them as constructor arguments. No existing `CaptureSessionController`, `SessionFinalizer`, `TakeContext` or `TakeHistory`: `grep -rn "CaptureSessionController\|SessionFinalizer\|TakeContext\|TakeHistory" app/src` returns nothing (new authority proposed, as the audit names them).

### 3. Prior attempts and live direction

#186 (PR #195, commit a18be74) moved every statement of the Service into this class and filed two follow-ups in its plan §14: a `dictation/` package move and "a per-take value replacing the volatile fields". This plan is the second. #115 added the lane, the bound and the pushed events; its review findings (the lane never on main, the bound disarmed only with the binding, the start re-checked on the lane) are carried as-is. #176 made the arbiter the one referee; #214 added `defectSink`. The catalog has no row for an internal split (`sqlite3 ~/.claude/knowledge/enviouswispr/catalog.db "SELECT feature_slug FROM feature_platform WHERE feature_slug LIKE '%session%'"` names product behaviour only); no decision governs the file layout.

### 4. Boundaries

- Threads: main (commands, posted events, timers, `publishLive`), the `CaptureCommands` lane (binder calls into `:audio`), the session scope on `Dispatchers.IO` (settings wait, ASR, polish, the save await), the history queue's worker. Every call keeps its thread: the controller runs its lane bodies on the lane and posts events to main; the finalizer's `deliver` is a `suspend` function called from the owner's coroutine where the code runs today.
- Take identity: the lane re-checks "still STARTING and still this take" before and after `startCaptureForTake` (`:528`, `:555`). The controller cannot read `SessionState`, so the owner hands it a read-only `TakePhaseView` (proposed); a read, never a transition. Exact semantics, matching `:528` and `:555`: `isStarting(id)` is true when `id` is the current take's id AND the state is STARTING; `hasEnded(id)` is true when `id` is NOT the current take's id OR the state is IDLE, FINISHING or ERROR, and false for RECORDING, PROCESSING and CANCELLING.
- Timer events: the silence bound and the live deadline are already main-thread runnables; they invoke `events(...)` inline, with no second post, so the owner's handler runs in the same main-thread task as today's `onCaptureSilent` / `onLiveDeadline`.
- Capture binding: every `pipeline.capture` read moves into the controller (`commandCapture` `:582`; the null check in `cancelCaptureAndFinish` `:1532` becomes `capture.isBound`, proposed); the owner holds no `CaptureLink`.
- Destroy: `destroy` disarms both timers and shuts the lane down after its last command (`:1807`, `:1832`); after, `capture.disarm()` and `capture.shutdown()` (proposed) in the same positions.

### 5. High-risk premises

- One take per owner instance: `finishSession` sets FINISHING (`:1669`) and nothing sets IDLE afterwards (`grep -n "set(SessionState.IDLE\|, SessionState.IDLE)" DictationSessionCoordinator.kt` finds no assignment: the only IDLE is the initial `AtomicReference(SessionState.IDLE)` at `:151`), and `handleCommand` begins only from IDLE (`:309`, `:314`). So one controller per owner instance serves one take; `begin(takeId, phase)` (proposed) still resets the ending latch.
- The 50 behaviour rows reach the owner only through its constructor and `handleCommand`, `onCreated`, `destroy` and `TAKE_SILENT_BOUND_MS` (inventory of `DictationSessionRig.kt` and `DictationSessionCoordinatorTest.kt`, no reflection).
- Fourteen test files read the owner's source as text; the full inventory with the rows that must be rewritten is §6.

## 3. Design

**TakeContext** (proposed, `ui/TakeContext.kt`): `internal class TakeContext(val takeId: String, val trigger: TriggerSource, val facts: TakeFacts, val arbiter: TakeArbiter, val targetPin: DictationTargetPin, val history: TakeHistory)` with `NONE` (empty id, `TakeArbiter.closed()`, `NO_TARGET`, a history whose writes are no-ops because no draft exists). Built once in `beginSession` after the pin, published in one `@Volatile` owner field `take`. Immutable: every property is a `val`; `facts` and `history` are single-owner holders exactly as `facts` is today.

**CaptureSessionController** (proposed, `ui/CaptureSessionController.kt`), constructed once by the owner with `host`, `surface`, `log`, `pipeline` and `events: (CaptureEvent) -> Unit`:
- `begin(takeId, phase: TakePhaseView)`: records the take id for the listener's filter and resets the ending latch.
- `start(preferences)`: `armSilenceBound()`, `host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)`, then the lane body of today's `tryStartRecording` unchanged (register, re-check through `phase`, `startCaptureForTake` with the snapshot's five arguments, the `DeadObjectException` split now reported as `CaptureEvent.StartFailed(processDied)` posted to main, the "started for a take that already ended" stop). Returns whether it was issued.
- `command(what, block)`, `stop(what)`, `listenForPicture(takeSerial)`, `finishTakeOrStop()`, `cancelLiveDeadline()`, `disarm()`, `endingArrived`, `shutdown()`.
- The listener posts `CaptureEvent.Live`, `Tick`, `SilenceStatus` and `Ended` (the first ending only); the bound and the deadline post `Silent` and `LiveDeadlinePassed`.
- `TAKE_SILENT_BOUND_MS` and `LIVE_WAIT_BOUND_MS` move to its companion.

**Ordering contracts (grounded round 1).**
- Ending de-duplication happens inside the listener's posted main-thread runnable, never on the binder thread. The exact order for an ending is: reject a foreign take; re-arm the silence bound; atomically claim the first ending; then invoke `events(CaptureEvent.Ended(ending))` inline. A duplicate ending for this take still re-arms the bound but emits no event. `endingArrived` reads that same atomic, so `cancelCaptureAndFinish` cannot observe arrival before the owner has begun processing the ending on main.
- `CaptureSessionController.command` snapshots `pipeline.capture` on the calling thread before executor submission; the lane closure captures that link and never re-reads `pipeline.capture`. `start` preserves the current three error boundaries: synchronous setup remains under the owner's outer `try/catch`; executor rejection returns false and leaves the silence bound to decide the ending; `startCaptureForTake` exceptions are caught on the lane, logged there, and delivered through exactly one `postToMain` as `StartFailed`. Destroy preserves this exact order: interrupt and enqueue `interrupted` under `publishLock`; `capture.disarm()`; cancel polish; cancel `serviceJob`; queue the captured-link stop when needed; stop the audio service; cancel polish again; unbind; `capture.shutdown()`. Shutdown accepts already-queued work.
- Inside `publishLive` and still under `publishLock`, the owner calls `take.history.insertDraft`, immediately registers `invokeOnCompletion`, and only then publishes LISTENING, shows the surface and registers the picture. Completion posts once to main and attaches the row only when `!destroyed.get()` and `take.history.isCurrent(draft)`. `TakeHistory` never touches `RecorderSurface`.
- `beginSession` publishes `take` and then calls `capture.begin(take.takeId, phaseView)` immediately, before `scope.launch`; the whole of `beginSession` is one uninterrupted main-thread call, so nothing reads the previous context between STARTING and publication.

**Owner's event handler**: `onCaptureEvent(event)` is an exhaustive `when` over the sealed `CaptureEvent` with no `else`, calling today's `publishLive`, `onTakeTick`, `publishSilenceNoticeIfNeeded`, `onTakeEnded`, the per-state body of today's `onCaptureSilent`, the body of `onLiveDeadline`, and the start failure's `handleServiceFailure` / `showError`.

**SessionFinalizer** (proposed, `ui/SessionFinalizer.kt`), constructed once with `host`, `insertion`, `log`, `historyWrites`:
- `TakeHistory` (proposed, same file): `insertDraft(takeId, createdAtMs)` returns the draft's deferred (the enqueue body of `:801-823`, with `Telemetry.journal?.associate`), `isCurrent(draft)`, `markStatus(...)` (today's `updateDraftStatus` (removed)), `discard()`, `markInterrupted()` (the `interrupted` write of `:1791`), `resolvedId()`.
- `Publication` (proposed): the immutable payload built today at `:1267-1272`.
- `enqueueSave(history, publication)`: the finalize-or-insert enqueue of `:1282-1309`, returning the deferred save result; called by the owner inside `synchronized(publishLock)` after its reservation, as today.
- `deliver(takeId, targetPin, publication, saveResult, clipboard)`: `:1350-1422` unchanged (route, `InsertionJudgement.handoffToJudge` with `startPin = targetPin`, `pasteWhenTargetReturns`, `releasePinnedTarget`, `keepOnClipboard`, `keepInHistoryOnly`, `announceInsertionFallback`, `InsertionTerminal`, the delivery log line); returns `Delivery(route, handoff)`.

The owner's `publishResult` keeps the polish facts head, the payload build call, the reservation, the blank-text commit, the notice, the save-failure telemetry, the commit to `COMPLETED`, then `finalizer.deliver(...)`, `log.log(log.pipelineSummary())` and `finishSession()`.

**Alternatives rejected.** (a) Injecting the two collaborators through the constructor: every behaviour row would change its rig, which throws away the evidence that behaviour did not change. (b) Moving the notices too: more rewritten source rows for no change the audit asked for. (c) Letting the controller read a shared state flag instead of `TakePhaseView`: a readable state object is one step from a writable one; two named predicates are the whole need.

## 3b. Ownership justification

The capture transport lives on `CaptureSessionController` because every piece of it (the lane, the listener, the two timers) exists to talk to `:audio` and none of it decides how a take ends; the alternative was leaving it in the owner, which is the finding. The History row and the delivery live on `SessionFinalizer` because they run only after the owner's decision and write where the words went; the alternative was a `HistoryWriter` (proposed) plus an `InsertionDelivery` (proposed), both rejected: two classes for one post-commit step.

## 4. Contract deltas

- `DictationSessionCoordinator`: same constructor, same public members (`handleCommand`, `onCreated`, `destroy`, `isProcessing`); `TAKE_SILENT_BOUND_MS` is no longer on its companion (moved, callers updated, no alias per `GR-MIGRATION-COMPLETE`).
- `CaptureSessionController`: new, `internal`; events always arrive on main through `events`, and only for the begun take.
- `CaptureEvent`: new sealed type; adding a member fails the owner's exhaustive `when`.
- `SessionFinalizer` / `TakeHistory` / `Publication` / `Delivery`: new, `internal`; `deliver` must only be called after the owner committed `COMPLETED`.
- `TakeContext`: new, immutable.

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Owner members (every `val`/`var` at class indentation, the constructor properties, the companion and the nested declarations) | Constructor properties: `host`, `surface`, `insertion`, `log` and `pipeline` stay on the owner and are also passed to the named collaborators; `historyWrites` stays a constructor property and is used only by `SessionFinalizer`; `preferences`, `transcripts`, `languageDetector`, `loadPolicy`, `scope`, `mainDispatcher`, `polishTimeout`, `answerBoundMs`, `tipGate`, `polishLedger`, `endingSink`, `defectSink` stay owner-only. Companion: `SILENCE_UNAVAILABLE_NOTICE`, `DURATION_WARNING_NOTICE`, `DURATION_REACHED_NOTICE`, `JOURNAL_ADMISSION_DEADLINE_MS`, `SETTINGS_ANSWER_BOUND_MS` stay; `TAKE_SILENT_BOUND_MS` moves to the controller's companion. Nested: `SessionState` and `Claimants` stay. Properties: to `TakeContext`: `takeId`, `facts`, `arbiter`, `targetPinAtStart`. To `TakeHistory`: `draftId`, `draftCreation`. To the controller: `LIVE_WAIT_BOUND_MS`, `silenceBoundArmed`, `silenceBound`, `liveDeadline`, `captureCommands`, `endingConsumed`, `takeListener`. Removed as dead: `recordingStartedAtMs`. Stay: `state`, `pendingTrigger`, `polishSubmissionLock`, `teardownStarted`, `serviceJob`, `rawTranscript`, `admittedRequest`, `stopAfterRecording`, `recordingDurationMs`, `destroyed`, `lastElapsedSecond`, `pendingCancel`, `pendingCancelReason`, `sessionPreferences`, `silenceNoticeShown`, `forcedNoticeShown`, `publishLock`, `captureDeviceLabel`, `durationWarningShown`, `takePeakAmplitude`, `isProcessing`. |
| Threads that call each moved function | Listed in §2.5.4; each moved function is called from the same thread as today. |
| History writes | The seven enqueue sites in §2.5.1, all in `TakeHistory` / `SessionFinalizer` after. |
| Timers | Two runnables, both in the controller, both through `SessionHost`. |
| Terminal routes | Every `commit`/`commitNow`/`interrupt`/`reserve` stays in the owner (`grep -n "arbiter\.\(commit\|commitNow\|reserve\|interrupt\)"` lists them; count before equals count after in the owner, zero in the collaborators). |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Required change | Verified by |
|---|---|---|---|
| `TAKE_SILENT_BOUND_MS` moves | `DictationSessionCoordinatorTest` (9 references) | `CaptureSessionController.TAKE_SILENT_BOUND_MS` | compile |
| Code moves out of the owner file | 14 source-reading test files | Rows re-pointed through a shared reader `SessionSources` (proposed, test source): `coordinator`, `capture`, `finalizer`, `all`; every NEGATIVE scan reads `all`; every order check lives inside one file | each rewritten row's revert, §11 |
| The 14 source readers | `SessionOwnerShapeTest`, `PolishPublicationRoutesTest`, `TakeNoticesTest`, `CaptureNoticesTest`, `SilenceStopSettingsTest`, `SilenceStopWiringTest`, `RecordingCapWiringTest`, `LiveGateWiringTest`, `LiveAudioMeterWiringTest`, `InsertionOutcomeMessagesTest`, `DeterministicFallbackTest`, `TelemetryContractsTest`, `LipsBubbleWiringTest`, `AutoPasteWiringTest`. Unchanged in content (the pinned code stays in the owner): `DeterministicFallbackTest`, `TelemetryContractsTest`. Negative scans widened to `all`: `TakeNoticesTest:88-91` (the failure calls now also reachable from the controller's events), `LiveAudioMeterWiringTest:63, 73`, `CaptureNoticesTest:46`, `RecordingCapWiringTest:62, 83, 97`, `LiveGateWiringTest:182`, `SessionOwnerShapeTest:177-179`. Path only, to the controller: `LiveAudioMeterWiringTest:47-55` (`listenForPicture`), `LiveGateWiringTest:202-203` (`finishTakeOrStop`). | per row, §11 |
| Rows that need a rewrite, not a path | SessionOwnerShapeTest:177-184 (blocking tokens and the one `Thread(` over `all`); PolishPublicationRoutesTest:39-49 (`finalizer.enqueueSave(` < `host.showPolishNotice(notice)` < `scope.launch` inside publishResult; the insertReadyTranscript slice in the finalizer); CaptureNoticesTest:45 (the constructor text) and 72-76 (end marker `capture.listenForPicture(`); LiveGateWiringTest:143-148, 167-168, 179-181 (listener line in the controller; `failWhileStarting(` < `capture.stop(` in the owner's deadline arm; snapshot < `capture.start(`; arm-deadline < `startCaptureForTake(` in the controller's `start`; `host.cancelMainDelayed(liveDeadline)` becomes `capture.cancelLiveDeadline()`); SilenceStopSettingsTest:48-53 (snapshot in the owner, arguments in the controller); SilenceStopWiringTest:175-176 (count over `all`) and 233 (the dead `break` marker replaced by `CaptureEnding.MaxDuration`); AutoPasteWiringTest:210-219, 254-265 (finalizer slice; `startPin = targetPin` in the finalizer; count over `all`) and 248-253 (`val targetPin = insertion.pinTargetForDictation()`); LipsBubbleWiringTest:109 (its end marker `startPolling()` does not exist, so it reads to the end of the file; the slice becomes `publishLive`'s own body, where `if (stopAfterRecording)` lives); InsertionOutcomeMessagesTest:286-295 (the finalizer file); RecordingCapWiringTest negative scans over `all` | revert per row, §11 |
| Wiring that a token alone cannot prove | `PolishPublicationRoutesTest` also asserts `SessionFinalizer.enqueueSave` contains `historyWrites.enqueue("finalize")`. `LiveGateWiringTest` asserts both the controller's filtered publication (`if (ours(takeId))` then `events(CaptureEvent.Live(`) and the owner's mapping (`is CaptureEvent.Live ->` calls `publishLive(`). The settings rows assert the owner passes the frozen snapshot to `capture.start(preferences)` and the controller's `start` passes that parameter's five fields to `startCaptureForTake`. | revert per row, §11 |

## 7. Failure-mode × caller table

No new failure mode: every branch keeps its terminal reason, its sentence (`TakeNotices`) and its History write. The only new call edge is `CaptureEvent.StartFailed`, which replaces two `host.postToMain` lambdas with one posted event carrying the same boolean; the owner maps it to the same two calls.

## 8. Caller-visible signals

`not present in this change`: no field, log line, telemetry property or History value changes. The log lines move with their code and keep their text (the delivery line is pinned by `DictationSessionCoordinatorTest.kt:1015`).

## 9. Fallback source-of-truth audit

Unchanged; `publishFallback` and `deterministicFallback` stay in the owner.

## 10. File-by-file changes

- `ui/TakeContext.kt` (new).
- `ui/CaptureSessionController.kt` (new): `CaptureEvent`, `TakePhaseView`, the controller.
- `ui/SessionFinalizer.kt` (new): `TakeHistory`, `Publication`, `Delivery`, the finalizer.
- `ui/DictationSessionCoordinator.kt`: moved code removed, `take` field, `onCaptureEvent`, the two collaborators constructed in its initialiser, `recordingStartedAtMs` removed.
- Tests: `DictationSessionCoordinatorTest` (constant path only), the 14 source readers (§6), new `SessionSources` and the Drift Guard.
- `.claude/knowledge/architecture.md` `ui/` row and `architecture-rules.md` RULE: keep-central-types-thin updated in place (primary checkout, not committed).

## 11. Testing

1. New test: `SessionOwnerSplitShapeTest` (proposed), Drift Guard. Read as code through `scripts/check-visibility.py --code-only` (the one lexer owner, as `SessionOwnerShapeTest` does). Rows: (a) the owner's code has none of `historyWrites.enqueue(`, `Executors.`, `postToMainDelayed(`, `listenForTake(`, `startCaptureForTake(`, `pasteWhenTargetReturns(`, `copyToClipboard(`, `InsertionJudgement.`, `pipeline.capture`, `CaptureLink`; (b) positively, `CaptureSessionController.kt` holds exactly one `Executors.newSingleThreadExecutor`, one `listenForTake(`, one `startCaptureForTake(` and exactly three `postToMainDelayed(` sites (the bound's arm and re-arm, today `:616` and `:623`, and the live deadline, today `:518`), and `SessionFinalizer.kt` holds all seven `historyWrites.enqueue(` sites (draft insert, finalize, clipboard outcome, history-only outcome, status, discard, interrupted) plus the one `pasteWhenTargetReturns(` and the one `copyToClipboard(`; (c) the two collaborators' code has none of `SessionState`, `TerminalReason`, `TakeArbiter`, `.reserve(`, `.commit(`, `.commitNow(`, `.interrupt(`, `arbiter`; (d) the sealed `CaptureEvent` declares exactly `Live`, `Tick`, `SilenceStatus`, `Ended`, `Silent`, `LiveDeadlinePassed` and `StartFailed`, each carrying facts only; (e) every property of `TakeContext` is a `val`. TakeContext exposes `arbiter` to the owner only: the collaborators receive the fields they need (`takeId`, `targetPin`, `history`) as parameters, never the context. When it fails, the user sees nothing directly; a future edit is putting the take's decisions back into a class that cannot see the state machine, or the transport back into the owner. Revert: move the draft insert's `historyWrites.enqueue` back into `publishLive` (rows a and b red); add a second `Executors.newSingleThreadExecutor` in the controller (row b red); add a `TerminalReason` parameter to `SessionFinalizer.deliver` (row c red); add a `CaptureEvent.Failed(reason: TerminalReason)` member (rows c and d red); make `targetPin` a `var` (row e red).
2. The 50 behaviour rows are the product-outcome evidence and are not edited beyond the constant's path, with one declared addition: the revert of `releasePinnedTarget` in `deliver` turned no row red, so `handoffNotScheduledCopiesAndAnnounces` now also asserts the pinned field is released exactly once (the gap predates this change). Revert that turns one red: in the controller's listener, drop the `ours(...)` filter (`anotherTakesEndingIsDiscarded` red); in `deliver`, skip `releasePinnedTarget` (`handoffNotScheduledCopiesAndAnnounces` or the pin rows red, whichever the run shows; recorded in the receipts).
3. Every rewritten source row gets one revert receipt of the property it now pins.
4. Not tested: a JVM row for the lane thread name beyond `noCaptureCommandRunsOnTheMainThread`, which already asserts `"CaptureCommands"`.

### 11.1 Hardware UAT

- Subsystem: heart path.
- Recipe: `device-testing.md` emulator takes through `wispr_eyes.dictate_emulator` into Gmail compose, two back to back; a cancel while recording and a cancel while processing through the recorder; the History screen read after each.
- Expected: each take's sentence equals the editor's own text once (`route=COMMIT`); both cancels leave no History row.
- Restore: `restore()`.

## 12. Blast radius & rollback

Touched: `ui/` only, plus tests. Not touched: `audio/`, `asr/`, `polish/`, `paste/`, `history/`, AIDL, manifest. Rollback: revert the squash commit.

## 13. Ship criteria

- [ ] Two takes back to back land once each in Gmail on the emulator; both cancels leave History empty.
- [ ] The behaviour rows pass unedited.

## 14. Open questions

None blocking. Follow-ups if the regrade asks: `RecordingNotices`; folding the four progress values into a per-take value.

## 15. Related

#186 (the owner split and its §14 follow-ups), #115, #176, #214; audit REF-01; #217 and #218 are the other two refactor targets.
