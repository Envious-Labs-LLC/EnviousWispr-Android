# Issue #309: move take-ending reporting and notice selection out of the session owner (2026-09-24)

GitHub issue: `#309`. Tier: LARGE (the session owner, `ui/DictationSessionCoordinator.kt`). Status: revised after the coverage round (`309-cov`), all findings adopted; grounded round 1 (`309-g1`), all three findings adopted. Round 2 (`309-g2`) named one more source row (`RecordingCapWiringTest` line 82), the second of the same class, so the class was enumerated by one search of `app/src/test` and `app/src/androidTest` for every moved name (the latches, `tipGate`, the three `publish*` functions, `recordTakeEnding`, `WARNING_AT_MS`, `SILENCE_STATUS_UNAVAILABLE`, `notices.say`, the four notice names, both log lines); §3.0 now lists every hit. Built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take into Gmail lands its words and logs `Take terminal: COMPLETED (completed) start: ...` exactly as today.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees or hears changes: the same recorder lines (earbuds silent, auto-stop unavailable, the Bluetooth tip, the last-minute warning) appear under the same conditions, once each, in the same order. The session owner loses two jobs it does not need, so later changes to take handling touch less code.

---

## 0. TL;DR

REF-06 of `docs/audits/2026-09-24-senior-audit.json` (`.claude/rules/architecture-rules.md` RULE: keep-central-types-thin). Two moves, no behaviour change:

1. `recordTakeEnding` (the arbiter's sink: debug log line, breadcrumb, defect, journal commit, error-scope exit) leaves the owner's file for `telemetry/TakeEndingReport.kt` (proposed), unchanged in body. The owner's `endingSink` default points at it.
2. The per-take notice selection leaves the owner for `SessionNoticePresenter`: the three latches (`silenceNoticeShown`, `forcedNoticeShown`, `durationWarningShown`), the Bluetooth tip gate, and the rules that pick a line. The owner keeps the state checks it owns (RECORDING, auto-stop on) and asks the presenter.

Take admission, state transitions, arbitration and publication stay in the owner; no second session owner.

Consolidation: none new; `SessionNoticePresenter` already owns WHERE a line is said (#256), and now also WHETHER a per-take line is due.

Prior context: #256 (the presenter), #173 (no pick-missing line), #115 (the tip decided once at live; the duration moment from `RecordingLimits`), #186 (the owner is the coordinator; the tip gate is process-scoped), #293 (the presenter's failure lines).

## 1. Grounding (main 1f02edb)

- `DictationSessionCoordinator.kt` is 1407 lines. `recordTakeEnding` is a top-level function at its end (17 lines with doc), used only as `endingSink`'s default; the rig injects `endings::record`.
- Latches: `silenceNoticeShown`, `forcedNoticeShown`, `durationWarningShown` (`@Volatile` fields), reset in `tryStartRecording`. Readers: `publishLive` (forced line, then `publishMicrophoneNoticesIfNeeded(routeKind)`), `publishSilenceNoticeIfNeeded(status)` (from `CaptureEvent.SilenceStatus`), `publishDurationWarningIfNeeded(elapsedMs)` (from `onTakeTick`, inside `runCatching`). All on main.
- `tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS` is an owner constructor parameter; the rig passes `BluetoothTipGate()`; the Service passes none.
- `SessionNoticePresenter(surface, insertion, host, scope, mainDispatcher)` is built by the Service and the rig and by `SessionNoticePresenterTest`.
- `CaptureNoticesTest` pins the current selection by reading the owner's source text (four rows: process gate default, one microphone line per take, forced before tip).

## 2. Design

1. `telemetry/TakeEndingReport.kt`: `internal fun recordTakeEnding(takeFacts: TakeFacts, reason: TerminalReason)`, the same body and doc, moved. The owner imports it; `endingSink = ::recordTakeEnding` is unchanged.
2. `SessionNoticePresenter` gains `log: SessionLog` (the Service passes `DebugSessionLog`, the rig its `FakeLog`, the presenter test a `FakeLog`) and `tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS` (last constructor parameter; the Service passes none, the rig passes `BluetoothTipGate()`), the three latches, and the methods below. The presenter stays per Service (one owner admits one take) and the gate stays process-wide; `beginTake()` is called where the latches are cleared today, in `tryStartRecording` before `capture.start` (coverage finding A). Every `SessionNoticePresenterTest` constructor passes a fresh `BluetoothTipGate()`; production alone uses the process default.
   - `beginTake()`: clears the three latches (the owner calls it where it cleared them).
   - `sayEarbudsSilent()`: latches forced, says `EARBUDS_SILENT`.
   - `sayBluetoothTipIfDue(routeKind: Int, tipsEnabled: Boolean)`: nothing when the silence or forced line was said this take; else asks the gate, and on true logs `Bluetooth tip shown` and then says `BLUETOOTH_TIP` (log before the line, as today, so a failing display still leaves the log; coverage finding D).
   - `saySilenceUnavailableIfDue(status: Int)`: latches and says `SILENCE_UNAVAILABLE` once, only for `SILENCE_STATUS_UNAVAILABLE`. The owner keeps `if (!sessionPreferences.autoStopOnSilence) return` and `if (state.get() != RECORDING) return` before calling it.
   - `sayDurationWarningIfDue(elapsedMs: Long)`: once, at `RecordingLimits.WARNING_AT_MS`; latches, logs `Duration warning shown at <ms>ms`, then says `DURATION_WARNING`. The owner keeps its `runCatching`.
   The docs on the owner's three `publish*` functions move with the rules, and the presenter's class doc (`SessionNotice.kt`, "the owner picks which notice") is corrected: the presenter also decides whether a per-take line is due. The cap-reached line, the failure line and the polish failure stay decided at their owner transitions (not in REF-06's scope).
3. The owner deletes the three fields, `tipGate`, `publishMicrophoneNoticesIfNeeded` (removed), `publishDurationWarningIfNeeded` (removed), and shrinks `publishSilenceNoticeIfNeeded` to its two state checks and the call.

## 3. Tests

0. Source-based rows that read the moved code are updated to read the presenter and the owner's new calls, keeping their behavioural claims: `SilenceStopSettingsTest` (lines 61 to 98), `RecordingCapWiringTest` (82, 117 to 134, 163 to 171; the cap-reached rows at 151 and 159 read code that stays), `SessionNoticePresenterTest.theOwnerPicksTheNoticeAndThePresenterPicksTheSurface` (the five direct `notices.say(SessionNotice.` calls become one, `DURATION_REACHED`; the other four are said inside the presenter's own methods, and the row's set check reads the owner and the presenter together), and `CaptureNoticesTest` rows 4 to 6. Also `SilenceStopWiringTest.losingTheDetectorAfterItWorkedIsSilentButLosingItBeforeIsNot`, which reads the unavailable-status check, now reads it in `SessionNotice.kt` (`LipsBubbleWiringTest` uses the retained function only as a boundary and needs no edit). `DictationSessionRig` line 184 passes `tipGate` to the owner; it moves to the rig's presenter. `DictationSessionCoordinatorTest` 1140 and 1160, `RecordingLimitsTest` and `CaptureWithSilenceStopDeviceTest` use the constants only and need no edit.
1. New `SessionNoticePresenterTest` rows, behaviour on the presenter (replacing `CaptureNoticesTest`'s source-text rows 4 to 6): a forced take then a Bluetooth live says no tip and leaves the gate unspent (a later take gets it); a silence line then a Bluetooth live says no tip; the silence line is said once per take and again after `beginTake()`; only `SILENCE_STATUS_UNAVAILABLE` says it; the duration warning is said once at `WARNING_AT_MS`, not before, and again after `beginTake()`; tips off says no tip. Each selection sequence, including the throwing-surface call, runs inside `rig.onMain { ... }`, asserted after it returns.
2. `DictationSessionCoordinatorTest` rig rows `theSilenceNoticeIsThePillWhenAutoStopNeverBecameAvailable`, `theBluetoothTipIsThePillForATakeLiveOnBluetooth` and `theDurationWarningIsThePillInTheLastMinute` keep passing unchanged (each proves one display end to end).
3. A shape row that is RED on main: the owner's file declares none of the three latches, no `tipGate`, and no `fun recordTakeEnding`; `CaptureNoticesTest`'s process-gate row now reads the presenter's default and still refuses a Service-built gate.
4. Mutations: m1 the tip no longer checks the forced latch (RED: forced row); m2 `beginTake()` stops clearing the silence latch (RED); m3 the duration warning drops its latch (RED: said twice); m4 the owner stops calling `beginTake()` (RED: a source row asserts `notices.beginTake()` precedes `capture.start(` in `tryStartRecording`; one owner admits one take, so no rig row can see a second take); m5 the moved reporter loses its journal commit (RED: a source row on `telemetry/TakeEndingReport.kt` pins its five calls in order, since the rig replaces `endingSink` and no JVM seam reaches `Telemetry.journal`); m6 the tip logs after the line (RED: presenter row with a throwing surface still sees the log).

## Results (2026-09-24)

- Mutations m1 to m6 RED (`309-mut.py`). Row 14 (`theOwnerClearsTheLatchesBeforeCaptureAndKeepsItsOwnChecks`) is RED on main, where the owner holds the latches, the gate and `recordTakeEnding`.
- The owner's file: 1407 to 1334 lines.
- Suite 1341, 0 failures; app and androidTest build; visibility and cited-symbol checks clean (older plans' names of the two removed functions, and two names removed earlier, marked `(removed)`).
- Emulator: a take into Gmail landed by COMMIT and matched; `Take terminal: COMPLETED (completed) start: settings=21 matcher=22 policy=22 admission=8 bind=29 live=484` logged as before.

## 4. Blast radius

A mis-moved latch would repeat or suppress a recorder line; a mis-moved ending sink would lose a terminal row. Both are covered by rows above. Rollback: revert the squash commit.

## 5. Ship criteria

- [x] Rows green, mutations RED; the suite green; app and androidTest build; checks clean.
- [x] Emulator: a take lands and logs its terminal line.
- [ ] Codex code review ALL-CLEAR with a confirming round.
