# Issue #373 — Keep the last 10 dictation recordings — 2026-09-25

GitHub issue: `#373`. Tier: MEDIUM (new runtime behaviour on the post-capture file path). Status: APPROVED (founder, 2026-09-25).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. The founder dictates three takes on the S26 (one normal, one cancelled, one longer than a
minute). `adb pull /sdcard/Android/data/com.envi.wispr/files/recordings` on the Mac returns three `.wav` files
that play in QuickTime with his voice for the whole take, each named with the take's time and its History
take id. After eleven more takes the folder holds exactly ten, the oldest gone. Dictation into the target app
is unchanged.

## Preface — User Rubric

User Rubric: N/A — internal diagnostics for the founder's own phone in stage 1. Nothing appears on any screen,
no dictation outcome changes, and the files never leave the phone except when the founder pulls them over ADB.
Customer exposure is decided in #375 before stage 3.

## 0. TL;DR

When a take ends, its recording is deleted (`CapturedAudioFiles.delete`). #374 (long takes lose their last
sentences) cannot be diagnosed because the audio is gone. Change: before the delete, copy the recording into
`getExternalFilesDir("recordings")` (external) as a 16 kHz mono 16-bit WAV named
`<yyyyMMdd-HHmmss-SSS>-<takeId>.wav` (UTC), and keep only the newest 10. The folder is readable by `adb pull` on a
release (non-debuggable) build. Evidence: unit tests on the archive and the phone pull above.

## 1. Problem

2026-09-25 12:22, S26, Play internal build: an 80.2 s take (`Stopped by a stop request. 2567040 bytes
(80.2s)`) produced 665 characters from ASR. The audio file was deleted after the answer
(`DictationSessionCoordinator.kt:859`), the build is not debuggable (`run-as: package not debuggable`), so the
take cannot be replayed on the emulator, on the Mac app, or against a changed recognizer.

## 2. Goals & non-goals

### 2.1 Goals
- G1. Every take whose file reaches `CapturedAudioFiles.delete` is first kept as a playable WAV, or a warning
  names why it was not (F1's lost-to-the-sweep case included; grounded r1 F2).
- G2. At most 10 kept files at any time: the oldest go BEFORE the new file appears. A prune delete that fails
  is warned, and the folder can then exceed 10 until a later prune succeeds.
- G3. The folder is readable with `adb pull` from a release build.
- G4. A failure to keep a recording never blocks, delays or changes the take, and never stops the cache delete.

### 2.2 Non-goals
- A Settings switch, a History play button, or any UI (#375 decides customer exposure).
- Files removed by `sweepEarlierTakeFiles` (takes whose ending never reached the owner: process death). Rare,
  and the capture process has no view of the archive.
- The #374 fix.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Producer: `:audio` writes `cacheDir/recording-take-<takeId>.pcm` (`AudioCaptureService.kt:462`,
  `CaptureFiles.nameFor`), raw PCM16 LE mono 16 kHz (`PcmAudio.SAMPLE_RATE`, `PcmAudio.BYTES_PER_SAMPLE`).
- Carried: the ending's `audioFilePath` over `ITakeListener` to the owner (`DictationSessionCoordinator`).
- Consumers: `AsrService` reads the file's LENGTH at the request (`AsrService.kt:147`) and its bytes later on
  the recognizer worker (`AsrService.kt:159`, `RecognizerOwner.kt:85`); `CapturedAudioFiles.durationMs` (`DictationSessionCoordinator.kt:822`); and
  `CapturedAudioFiles.delete` at every ending site. `grep -n "capturedAudio\." DictationSessionCoordinator.kt`:
  lines 725 (cancel), 835 (ASR not ready), 846 (take ended before request), 859 (ASR answer), 870 (legacy
  onError), 882 (onFailure), 902 (request threw), 1166 (speech unresponsive), 1193 (`closeSpeechWait`: cancel,
  destroy, disconnect during the wait). Plus `sweepEarlierTakeFiles` in `:audio` (`AudioCaptureService.kt:693`).
- So `CapturedAudioFiles.delete` is the single place every owner-side DELETE passes through. It is not every
  ending (coverage r1 F2): a pre-live ending (`DictationSessionCoordinator.kt:663`), the capture-failure branches
  (688 to 699), the already-ended cancel (1046), destroy with no open speech wait (1235; with one open, `closeSpeechWait`
  at 1193 queues the delete) and a setup failure in `:audio`
  (`AudioCaptureService.kt:717`) delete nothing today; their file is left to the next take's
  `sweepEarlierTakeFiles`. Those takes carry no speech answer to diagnose, so they are out of scope (§2.2).

### 2. Existing authority
`CapturedAudioFiles` (#358) is "the one owner of a take's captured-audio file on the main process's side".
Search for an existing archive: `grep -rn -i -E "keepRecordings|retainAudio|audioRetention|saveRecording|
getExternalFilesDir" app/src/main/java` finds only `ModelDeliveryWorker.kt:64` (legacy model path). No
archive exists: new authority proposed, `RecordingArchive`, used only by `CapturedAudioFiles`.

### 3. Prior attempts and direction
macOS has `debug-audio-archive` in the catalog ("In debug builds only, optionally archive local dictation audio
for diagnostics"). Android differs on purpose: the founder's phone only receives Play builds
(`delivery.md` RULE: every-reviewed-change-goes-to-the-phone-through-play-at-once), so a debug-only archive would
never see his real takes. Founder decision 2026-09-25: 10 recordings, on the Play internal build. Design doc
`docs/enviouswispr-android-architecture.md:227` already foresees "debug audio retention".

### 4. Boundaries
- Process: the archive runs in the main process on the existing `captured-audio-cleanup` worker, after the
  take's ending; `:audio` and `:asr` are unaware.
- ASR in flight (corrected, coverage r1 F3): on the speech-unresponsive path (`DictationSessionCoordinator.kt:1166`)
  the delete can be queued before `:asr`'s worker runs `readBytes()` (`AsrService.kt:159`,
  `RecognizerOwner.kt:85`). That race exists today and this change neither widens nor fixes it: the copy only
  reads, and that take has already ended as a failure, so its answer is discarded either way.
- Next take's sweep (coverage r1 F1): `sweepEarlierTakeFiles` (`AudioCaptureService.kt:573`) runs when the next
  take starts. If that start lands before the worker copies the previous file, that one recording is lost.
  Classified HYPOTHETICAL: the delete is queued at the speech answer, before polish (about 2 s on the phone
  log) and insertion, and a one-minute copy is about 2 MB; only a cancel followed by an instant new take could
  lose it. Grounded r1 F2 adds a delayed worker; the worker's only jobs are these copies (a one-minute take is
  about 2 MB, the 600 s cap about 19 MB), so a delay of seconds needs several maximum-length takes queued at
  once. Kept as a limit, made visible: a missing source now warns instead of returning silently
  (`validation-discipline.md`: fix a hypothetical only when trivial AND silent; the silence is fixed). The staging
  rename Codex proposes would move file work into the capture process or onto the caller's thread and change
  ASR's input path on the heart path, for a diagnostic folder.
- Process death after the Service is destroyed (coverage r1 F4): the worker is a daemon thread; if Android
  kills the process before the copy runs, that recording is lost. A cut-short copy leaves a `.part` file, which
  the next keep removes.
- Storage: `getExternalFilesDir` needs no permission (API 19+), is removed on uninstall, and can return null
  when shared storage is unavailable; then nothing is kept.
- Service death: the worker is process-wide and outlives the Service (#253), so a copy queued at destroy still
  runs.

### 5. High-risk premises
- ADB can read `Android/data/com.envi.wispr/files` on this phone: `adb -s 100.94.206.47:5555 shell ls
  /sdcard/Android/data/com.envi.wispr/files` returned without error on 2026-09-25 (empty folder). Re-proved in
  the phone UAT with a real file.
- Size: 32,000 bytes per second; ten one-minute takes are about 19 MB; the 600 s cap makes a worst case of
  about 192 MB.

## 3. Design

`RecordingArchive`, a small class with `keep(source: File)`:
1. Resolve the folder lazily on the worker (`dir: () -> File?`); null means skip.
2. Write `<yyyyMMdd-HHmmss-SSS>-<takeId>.wav` (UTC) (takeId parsed from the `recording-take-<id>.pcm` name, else the
   source file's base name): a 44-byte RIFF header for PCM16 mono 16 kHz, then the source bytes streamed.
   Written to a `.part` file and renamed, so a half-written file never counts as a recording.
3. Prune BEFORE the rename publishes the new file: list `*.wav`, sort by name (UTC to the millisecond, so neither
   a zone change nor two takes in one second reorders; grounded r1 F3), delete all but the newest 9; a delete
   that fails throws `RecordingArchive.NotKept`, which `CapturedAudioFiles` logs by its reason. Every keep first
   removes leftover `.part` files. "Ten" means after a successful prune; a manual clock change can prune out of
   order, accepted for a diagnostic folder (coverage r1 F5).

Privacy (coverage r1 F7): the app never sends the audio anywhere; the founder pulling files off his own phone
over a debug cable is not the app sending it. #375 decides the customer wording before stage 3.

`CapturedAudioFiles` gains an `archive: (File) -> Unit` constructor parameter. Its queued delete runs
`archive(file)` inside its own `runCatching` (a failure warns "Unable to keep captured audio: <class>") and then
the existing delete, unchanged.

Cancelled takes are kept too: the founder wants data on every take, and a cancel is often the symptom.

Rejected: a debug-only archive (never sees his phone's takes); keeping the PCM in `cacheDir` (not readable on a
release build, and the OS may clear it); copying in `:audio` at stop (the file is still ASR's input, and the
capture process would need a second policy on file lifetime).

Consolidation: none. This adds one behaviour at the existing single owner (`CapturedAudioFiles`, #358) and
merges nothing; every delete site already routes through it.

## 3b. Ownership
This will live in `CapturedAudioFiles` because it already owns the file's end of life on the main process and
every ending passes through its delete; the alternative was each delete site calling the archive, but that is
nine call sites that must stay in step.

## 4. Contract deltas
- `CapturedAudioFiles.delete(path)`: now "keep a copy in the archive, then delete". Callers see no change: still
  queued, still never throws.
- `CapturedAudioFiles.PROCESS` (removed) becomes `CapturedAudioFiles.forProcess(archive)`, sharing
  the same single worker. The coordinator's default argument is removed so no construction can silently drop
  the archive; `DictationSessionService` passes it, the test rig passes a no-op.

## 5. State and lifecycle audit
- Every path that deletes a take file: enumerated in §2.5.1, all through `CapturedAudioFiles.delete`, except
  `sweepEarlierTakeFiles` (non-goal).
- Two deletes for one path: the delete is guarded by the speech wait (`closeSpeechWait`, `wait.answer()`), so
  one path is deleted once; if it happened, the second archive call finds no file and keeps nothing.
- Concurrent takes: one worker, so archives run in order; prune is never concurrent.

## 6. Consumer matrix
`DictationSessionService` (production), `DictationSessionRig` and `CapturedAudioFilesTest` (tests) construct
`CapturedAudioFiles` (`grep -rn "CapturedAudioFiles(" app/src`; none in `androidTest` or `debug`). No other
reader of the recordings folder.

## 7. Failure modes
| Failure | Effect |
|---|---|
| External storage unavailable (dir null) | nothing kept; warning "recordings storage unavailable"; delete runs |
| Write fails (disk full) | `.part` removed, warning logged, delete runs |
| Source missing (swept by the next take, F1) | nothing kept; warning "recording was gone before it could be kept" |
| Prune delete fails | the new take is not published; warning names how many old recordings stuck |
| Old or current `.part` cannot be removed | the take IS kept; a separate `Recordings folder:` warning |
| Folder unreadable | warning; nothing kept |
The user sees nothing in every case.

## 8. Signals
One log line on failure only, through `DebugSessionLog.warn`, no content, no path. No telemetry change.

## 9. Fallback source of truth
None: the archive is a copy; the take's outcome never reads it.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/ui/RecordingArchive.kt` (new).
- `app/src/main/java/com/envi/wispr/ui/CapturedAudioFiles.kt`: `archive` parameter, `forProcess`.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt`: drop the default argument.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: pass `CapturedAudioFiles.forProcess(...)`
  with `getExternalFilesDir("recordings")`.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt`: pass a no-op archive.
- `app/src/test/java/com/envi/wispr/ui/RecordingArchiveTest.kt` (new), `CapturedAudioFilesTest.kt` rows.

## 11. Testing
Class: product outcome (the founder's recordings exist and play). Rows, each with its revert:
1. A kept file is a valid WAV of the source: header fields and data equal the source bytes (revert: raw copy
   without header).
2. The name carries the UTC time to the millisecond and the take id, under a non-UTC zone (revert: source-only
   name, local-time stamp).
3. Eleven keeps leave the newest ten (revert: no prune). 3b. Eleven keeps inside one second, with take ids in
   reverse order, prune the oldest (revert: second-only stamp).
7. A source already gone throws `NotKept` with its reason (revert: return silently). 7c. `CapturedAudioFiles` logs a
   `NotKept` by its reason and still deletes (revert: class name only).
8. A stuck old `.part` is reported through `warn` and the take is still kept (revert: drop the warning).
Grounded r2 mutations m4, m7c, m8 each turned their row red, 2026-09-25.
9. A failed copy whose warning callback itself throws still deletes the cache file (grounded r3 F1; revert:
   the warning outside its own catch, mutation m9 run red).
10. Inside the archive, a warning that throws neither stops the copy nor replaces a failure in flight (grounded
   r4 F1; `RecordingArchive` routes both warnings through one contained `report`; mutation m10 run red). Not tested: a current `.part` that
cannot be removed after a failed write (needs a filesystem that refuses one delete).
Row 1 asserts the full literal 44-byte header and the file length (grounded r1 F6). Mutations run 2026-09-25,
each turned its named row red: m1 (the header), m3, m3b, m6 (archive outside its catch: `aFailedCopyStillDeletesAndSaysSo`),
m7; m3b also reddened rows 2 and 6.
4. A null folder keeps nothing and throws `NotKept("recordings storage unavailable")` (revert: return silently).
5. `CapturedAudioFiles`: the archive runs before the delete, and a throwing archive still deletes and warns
   (revert: archive outside `runCatching`).
6. A leftover `.part` is removed and never counts (revert: no cleanup). Row 2 also runs under a non-UTC zone
   (revert: local-time stamp).
Not tested: `getExternalFilesDir` itself (framework), the `adb pull` path (phone UAT).

### 11.1 Hardware UAT
As in the Preface. Evidence: the `adb pull` listing, one file played, `afinfo` (external) output, and the eleven-take count.

## 12. Blast radius & rollback
Only the post-ending file path. Worst case the archive throws; it is inside its own `runCatching` on the
worker, after the take's outcome. Rollback: revert the PR.

## 13. Ship criteria
Unit tests green with count; the phone pull above; dictation still lands.

## 14. Open questions
None for the founder. #375 holds the customer decision.

## 15. Related
#374 (missing end of long takes), #375 (customer decision), #358, #253.
