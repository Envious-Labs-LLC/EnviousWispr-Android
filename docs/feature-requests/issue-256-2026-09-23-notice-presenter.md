# Issue #256: the session's recorder notices have their own presenter (2026-09-23)

GitHub issue: `#256`. Tier: LARGE per the audit (the heart's session owner), small in lines. Status: APPROVED after grounded round 2 (PROCEED-AS-PLANNED); earlier rounds (gate 0 `256-g0`: PROCEED-WITH-CHANGES, every change adopted; coverage `256-cov`: all three findings adopted verbatim; grounded round 1 `256-g1`: both findings adopted verbatim, §3).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Two Gmail dictations by COMMIT on the emulator. The five notices need a Bluetooth route, a silence-detector failure or a 10-minute take to fire; they are not staged on a device. The existing forced-toast rig row and four new owner-driven rows cover the five notices.

## Preface: User Rubric

User Rubric: every recorder notice reads the same words, on the same surface (the recorder pill while the recorder is up, a toast otherwise), at the same moment as before.

---

## 0. TL;DR

`DictationSessionCoordinator.sayWhileRecording` (removed) and `sayAfterRecording` (removed) choose between the recorder pill and an application toast and dispatch it, for five raw strings (REF-05 of the second 2026-09-23 audit). Move that choice and dispatch into `SessionNoticePresenter`, injected beside `RecorderSurface`; the coordinator selects a typed `SessionNotice` and calls `notices.say(...)`. No person-visible change.

## 1. Problem

Grounded by Codex (`256-g0`), re-read by Claude:
- `DictationSessionCoordinator.kt:806-821`: the chooser (pill when `insertion.isBound()`, else `sayAfterRecording`) and the toast (`scope.launch(mainDispatcher) { runCatching { host.toastFromApplication(line) } }`).
- Five callers with raw strings: `:668` the duration cap (after the recorder), `:731` earbuds silent, `:759` silence detector unavailable, `:778` Bluetooth tip, `:795` the duration warning (all while recording). Three sentences are coordinator companion constants; two live in `CaptureNotices`.
- Not in the class (no pill-versus-toast choice): `announceError`'s service toast (teardown ordering), the polish notice (toast plus notification together), the insertion fallback (`SessionFinalizer`).

## 2. Goals & non-goals

### 2.1 Goals
1. `SessionNotice` (enum, ui/): `DURATION_REACHED` (AFTER_RECORDER), `EARBUDS_SILENT`, `SILENCE_UNAVAILABLE`, `BLUETOOTH_TIP`, `DURATION_WARNING` (WHILE_RECORDING), each with its exact current sentence. The three coordinator sentences move into the enum; the two earbud sentences stay owned by `CaptureNotices` and are referenced.
2. `SessionNoticePresenter(surface, insertion, host, scope, mainDispatcher)` with one `say(notice)`: WHILE_RECORDING and bound shows the pill synchronously; otherwise the application toast launches on `mainDispatcher` in `runCatching`, as today.
3. The coordinator takes the presenter as a constructor parameter beside `surface`; the Service and the rig build it from the same surface, insertion, host, scope and dispatcher they already pass. The five calls become `notices.say(SessionNotice.X)`; the coordinator holds no `showNotice(` or `toastFromApplication(`.
4. The duration cap keeps its "continue, then explain" order.

### 2.2 Non-goals
The failure toast, the polish notice and the insertion fallback stay where they are. No sentence changes.

## 3. Design

Gate 0 changes adopted from Codex: inject beside `RecorderSurface` (the audit's shape) instead of constructing inside the owner; keep `CaptureNotices` as the earbud sentences' owner; leave the other three notices; add the presenter to `SessionSources.all` so the owner's whole-collaborator negative scans read it.

## 4. Contract deltas
`DictationSessionCoordinator` gains `notices: SessionNoticePresenter`; its companion loses the three notice sentences.

## 5-9. State, consumers, failure modes
The presenter holds no state. A throwing toast is swallowed as today; a throwing pill is not caught today and is not caught after (the overlay state call does not throw).

## 10. Files
`ui/SessionNotice.kt` (new: the enum and the presenter), `ui/DictationSessionCoordinator.kt`, `ui/DictationSessionService.kt`; tests `SessionNoticePresenterTest` (new), `DictationSessionRig.kt` (a configurable live route kind), `DictationSessionCoordinatorTest.kt` (four owner-driven notice rows), `SessionSources.kt`, `SilenceStopSettingsTest.kt`, `CaptureNoticesTest.kt`. Retarget `RecordingCapWiringTest`: check limit interpolation in `SessionNotice.kt`, and check that `continueAfterEnding(ending)` precedes `notices.say(SessionNotice.DURATION_REACHED)` (coverage finding 1).

## 11. Testing
Drain the injected main dispatcher before asserting no toast; for the throwing-toast row, drain it and assert the scope's exception handler received nothing. Use bounded signal waits for expected events. Assert literal expected text **and timing** for every enum member, so changing a shared `CaptureNotices` sentence or another member's timing turns row 5 red (coverage finding 3).
1. While recording and bound: the pill shows the sentence; no toast. MUTATION: always toast.
2. While recording and unbound: one toast, on the main dispatcher's thread; no pill. MUTATION: pill regardless of binding.
3. After the recorder, even when bound: a toast, no pill. MUTATION: ignore the timing.
4. A throwing toast does not escape `say` or the scope. MUTATION: drop the `runCatching`.
5. Each notice's sentence and timing is exactly today's (a table row per member, literal text). MUTATIONS: change one sentence; change one member's timing.
6. Source guard: the coordinator holds no `showNotice(` or `toastFromApplication(` and calls `notices.say(SessionNotice.` five times; `SilenceStopSettingsTest`'s chooser checks read the presenter. MUTATION: put a direct `surface.showNotice(` back into the coordinator.
7. Owner-driven rows through the rig (coverage finding 2): keep the existing forced-toast row (`mainImmediateRunsInlineOnTheOwnerThread`). Add owner-driven event rows for the other four notices; the fake can push a silence status, duration tick, and cap ending without waiting for real time (`DictationSessionRig.kt:483`). Make its live route configurable for the Bluetooth tip. Set auto-stop on before starting; after live, push `SILENCE_STATUS_UNAVAILABLE`. For the tip, set tips on, use a non-forced live event with `InputRouteKind.BLUETOOTH.code` (3), and make both fake live-event paths use the configurable kind. After live, push `tick(RecordingLimits.WARNING_AT_MS)` for the warning; push `endOnItsOwn(AudioCaptureService.TERMINAL_REASON_MAX_DURATION)` for the cap. Call `rig.capture.settle()` before pill assertions; await the application toast for the cap (grounded round 1, finding 2). Each row asserts the notice's exact sentence on the surface it belongs on (bound: `notice:` for the three while-recording ones; the cap: an application toast). MUTATION: pass the wrong `SessionNotice` at one call site.
All rows wait on signals, never a clock.

## 12. Blast radius
The five recorder notices. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 7 green, each mutation red; the existing forced-toast row unchanged and green.
- [ ] Two emulator dictations by COMMIT.

## 14. Open questions
None.

## 15. Related
#186 (the owner's seams), REF-05 of the second 2026-09-23 audit.

## 16. As built

- `ui/SessionNotice.kt` (new): `NoticeTiming`, the five-member `SessionNotice` with each sentence and its comment moved from the coordinator (the earbud pair references `CaptureNotices`), and `SessionNoticePresenter.say`, which shows the pill for a while-recording notice when the recorder service is bound and otherwise launches the application toast on `mainDispatcher` in `runCatching`.
- `DictationSessionCoordinator` takes `notices` beside `surface`; its five sites call `notices.say(SessionNotice.X)`; its companion lost the three sentences; `sayWhileRecording` (removed) and `sayAfterRecording` (removed) are gone. `DictationSessionService` names the session scope and main dispatcher once and passes both to the owner and the presenter; the rig does the same.
- Guards retargeted: `SilenceStopSettingsTest` (the chooser and the macOS sentence now read `SessionNotice.kt`), `CaptureNoticesTest` (the forced notice precedes the tip), `RecordingCapWiringTest` (the limit is interpolated in `SessionNotice.kt`; `continueAfterEnding(ending)` precedes `notices.say(SessionNotice.DURATION_REACHED)`); `SessionSources.all` reads the presenter.
- Tests: `SessionNoticePresenterTest` rows 1 to 6; `DictationSessionCoordinatorTest` gains four owner-driven rows (silence, the Bluetooth tip through a configurable `liveRouteKind` in the rig, the warning at `RecordingLimits.WARNING_AT_MS`, the cap as a toast after the take continues). Receipts 11 of 11 RED (`docs/audits/2026-09-23-256-mutation-receipts.txt`); full suite 1253, 0 failures; the `ui` package 20 of 20 repeats.
- Emulator: two Gmail dictations and a polish-kill take by COMMIT, each with the editor's whole text as expected.
