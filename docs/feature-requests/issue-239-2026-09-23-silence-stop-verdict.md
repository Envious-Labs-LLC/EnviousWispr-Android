# Issue #239 — Give the silence-stop device test its own verdict — 2026-09-23

GitHub issue: `#239`. Tier: SMALL (instrumented test only; no production change). Status: APPROVED, grounded round 1 PROCEED-AS-PLANNED (coverage round: findings 1, 3, 4, 5, 6 adopted; finding 2 adopted with a different proof, §3).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, as the verdict itself: the new row runs on the emulator through `scripts/uat/wispr_eyes.py` `run_device_test` (external audio) and must pass; its revert receipt must fail by name. The founder's phone is excluded (`-e audio speaker` stays available for a later phone pass).

## Preface — User Rubric

User Rubric: when "stop recording on silence" is on, a take the user simply stops talking in ends by itself and the words land in the field once.

---

## 0. TL;DR

`SilenceStopEndToEndDeviceTest.driveOneSilenceStoppedDictationIntoTheEditor` sleeps 2.5 s, 2.5 s and 45 s and asserts nothing; it passes when silence never ends the take (REF-06, `testing-philosophy.md` RULE: never-guess-when-the-subject-is-finished). `VoicePipelineDeviceTest` already owns a take driven on subject signals with an in-process receipt read (`run-as` through `UiAutomation`). Add the silence-stop take there as a row with its own verdict, and delete the sleeping driver (GR-MIGRATION-COMPLETE).

## 1. Problem

Grounded by Codex (`239-g0`), re-read by Claude:

- `SilenceStopEndToEndDeviceTest.kt:34-47`: three fixed sleeps, no assertion; its KDoc says the receipt cannot be read from the app process. That is no longer true: `VoicePipelineDeviceTest.receipt` reads the editor's file with `run-as com.envi.wispr.test` through `UiAutomation` (`:638-644`, `:680-685`).
- `VoicePipelineDeviceTest.SideButtonRun.recordOneTake` waits for the owner's LISTENING phase, delivers audio (external driver or speaker), then always sends a manual stop (`:383-389`); `awaitFinalRow` waits for this take's History row (`:453-474`).
- The capture process logs `Stopped by silence. N bytes ...` (`AudioCaptureService.kt:867`, `CaptureEnding.label`); the stop cause is not persisted in Room (`TakeFacts.captureTerminal` goes to telemetry only).

## 2. Goals & non-goals

### 2.1 Goals
1. A row `aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce` in `VoicePipelineDeviceTest`: stop-on-silence on for the run, no stop sent, the take ends by itself, and the editor's whole receipt equals the literal expectation once, route COMMIT.
2. The verdict names silence as the cause: the capture process's own `Stopped by silence.` line for this take, read after the take's start; a take that never ends fails with "silence did not end this take".
3. `SilenceStopEndToEndDeviceTest.kt` is deleted; `phone-audio-playback.md` drops it from its list.

### 2.2 Non-goals
- No production change. `SilenceStoppedTakeTranscribesDeviceTest` and `CaptureWithSilenceStopDeviceTest` (capture-level rows) are unchanged.

## 3. Design

- `recordOneTake(stage, stopByUser = true)`: when false, it sets `stopRequested` BEFORE `audio.deliver()` (the take can end by itself as soon as the audio stops, so IDLE must already count) and sends no stop (coverage finding 1).
- The no-stop take stays owned through the verdict (coverage finding 1): the phase listener stays registered until the final row, and if `awaitFinalRow` times out or any later assertion fails, the row cancels any live take and requires both the owner's IDLE and the capture process's close line, the same cleanup `recordOneTake` already runs on its own failures, moved into one helper both call.
- The row saves `AppPreferences.autoStopOnSilenceEnabled`, sets it true and waits for the write to complete BEFORE the start command, and restores the saved value in `finally` after the verdict and cleanup. Why the owner sees it (coverage finding 2, adopted with this proof instead of an acknowledgment): the Service stops after every take and builds a fresh owner and a fresh `SessionPreferencesSource` in `onCreate`, so the first settings emission that owner's `awaitAnswers` waits for is read after the completed write. The row asserts no session Service is running before it writes. If the owner still missed the setting, the take never stops by itself and the row fails "silence did not end this take", never a false pass.
- `awaitFinalRow` gains an optional failure message; this row passes "silence did not end this take".
- Cause (coverage finding 4): after the final-row signal, read the `AudioCapture` log for `Stopped by silence.` stamped after this take began (`AudioCaptureService.kt` logs it before the ending event is published), with the same `-v time` stamp comparison `captureClosedAfter` uses. It is a cause assertion read once after the subject's completion signal, not a completion poll. Only a take that ends inside the 90 s row wait and was never sent a stop can be silence or max duration; max duration is 10 minutes, so the log line is the positive proof and the elimination is the backstop.
- External audio (coverage finding 3): after the done broadcast the row sends no stop and the driver sends no more audio; with the host microphone off (`run_device_test` turns it off) the capture reads silence and the take must end by its own silence ending. The actual emulator behaviour is proven by the device run in §11.

Alternative rejected: keeping the separate class and adding a receipt read there, which would duplicate `SideButtonRun`, the audio sources and the receipt helpers.

## 4. Contract deltas

None.

## 5-9. State, consumers, failure modes

| Delta | Consumer | Change |
|---|---|---|
| `recordOneTake` gains `stopByUser` | existing three rows | default true, unchanged |
| `awaitFinalRow` gains a message | existing rows | default unchanged |
| Deleted class | `phone-audio-playback.md` | live list updated (knowledge, primary checkout); historical plans and audits keep their references (coverage finding 6) |

A leaked setting: restored in `finally`; a failed take is cancelled by the existing cleanup.

## 10. Files

`VoicePipelineDeviceTest.kt`; delete `SilenceStopEndToEndDeviceTest.kt`; `phone-audio-playback.md` (primary checkout).

## 11. Testing

1. The new row passes on the emulator through `run_device_test` (external audio).
2. Revert receipt (coverage finding 5): replace `preferences.autoStopOnSilence,` with `false,` at `CaptureSessionController.kt` (the capture start); the row fails with "silence did not end this take", and its cleanup ends the take. Restore the line after the red receipt. Run through the harness (`run_device_test`, `am instrument`), never `connectedDebugAndroidTest`.
3. Second receipt: the row with `stopByUser = true` (a manual stop) fails the cause assertion (no `Stopped by silence.`).
4. The three existing `VoicePipelineDeviceTest` rows still pass on the emulator.

## 12. Blast radius

Instrumented tests only. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] New row green on the emulator; both receipts red by name.
- [ ] Existing rows green.

## 14. Open questions
None.

## 15. Related
#161 (the harness verdicts), audit REF-06.

## 16. As built

- `VoicePipelineDeviceTest.aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce`; `SideButtonRun` gains `recordOneTake(stopByUser)`, `ownTheVerdict`, `endTheTake` (the one cleanup both paths call; the close line counts from the take's start), `stoppedBySilence`, and `awaitFinalRow(whenMissing)`; `awaitNoSessionService` checks the platform's service list before the setting is written. `SilenceStopEndToEndDeviceTest.kt` deleted.
- Emulator, through `run_device_test` (external audio): the new row passed; `aSideButtonTakeLandsInTheFocusedEditorExactlyOnce` and `aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard` passed; `aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure` was NOT RUN, skipped by its own precondition (a Gmail field was focused behind the launcher), and its code is unchanged here.
- Receipts on the emulator: R1 (capture starts with auto-stop off) failed "silence did not end this take", and its cleanup proved the take ended; R2 (a manual stop) failed "the take ended, but the capture process never logged a silence ending".
