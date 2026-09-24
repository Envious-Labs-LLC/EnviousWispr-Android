# Issue #358: the recording file has one owner outside the session owner (REF-03, regrade 8) (2026-09-24)

GitHub issue: `#358`. Tier: SMALL (a move, no behaviour change). Status: plan and build, before the combined Codex coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take still lands its words, and its recording is gone afterwards.

## Preface: User Rubric

No user-visible change. The session owner (`DictationSessionCoordinator`, about 1,270 lines) held the captured file's existence check, delete and failure lines (the removed deleteCapturedAudio) and read the file's length for the take's duration, with a direct `java.io.File` dependency. `ui/CapturedAudioCleanup` was only the executor.

## 0. TL;DR

- `ui/CapturedAudioCleanup` is replaced, in the same change, by `ui/CapturedAudioFiles`: a class holding the executor and a warn sink, with `delete(path)` (queued on the process worker, never on main's time, never cancelled by teardown, #253; the existence check, the delete and both failure lines inside) and `durationMs(path)` (the finished file's audio length, 0 when unreadable).
- `CapturedAudioFiles.PROCESS` is the production instance: the process-owned daemon worker and `DebugSessionLog`, so the failure lines keep the session's log tag the UAT collectors read (review round 1).
- The owner takes `capturedAudio: CapturedAudioFiles` in place of `audioCleanup: (Runnable) -> Unit`, calls `capturedAudio.delete(...)` at every site that called the removed deleteCapturedAudio, and reads the duration through `capturedAudio.durationMs(...)`. Its `java.io.File` import goes.
- The rig keeps its `audioCleanup` parameter and builds a `CapturedAudioFiles` from it, so the #253 teardown row is unchanged.

## 1. Tests

- `CapturedAudioFilesTest`: a delete is queued, not run inline, and removes the file when it runs (m1: the delete runs inline); a delete that fails says so on the warn sink (m2: the failure line dropped); a blank path queues nothing; `durationMs` reads a 32,000-byte file as 1,000 ms and a missing file as 0.
- `SessionOwnerShapeTest` gains a drift row: the owner imports no `java.io.File` and names no `File(` (m3: the owner reads the file itself again).
- `aTeardownRightAfterABlankTakeStillDeletesItsAudio` and `SpeechWaitTest` keep proving the deletes happen at the right endings.
