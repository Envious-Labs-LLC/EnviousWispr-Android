# #161 — When the harness says a dictation landed, it is true

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/src/androidTest/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (`scripts/uat/wispr_eyes.py`, this plan, the audits, `.claude/**`: `cited-symbols` conditionally).

**PAR rows closed:** none. The change is test tooling and instrumentation; no product outcome changes.

**Hardware UAT:** Y, on the emulator (the founder's phone is excluded this morning, his instruction of 2026-09-21). The repaired harness verdicts and the rewritten device rows are exercised by running them: `dictate_emulator` and `debug_insert` against Gmail with the new exact-value and route gates; the rewritten `VoicePipelineDeviceTest` rows through `am instrument` with the harness feeding the audio.

## Preface — User Rubric

User Rubric: N/A — internal-only: dev tooling and instrumentation tests; nothing a user sees changes.

## 0. TL;DR
The harness stops passing on weak evidence: a take's verdict requires the current take's own `insertion api=` line with `outcome=VERIFIED`, `written=true` and the right target, reports the route (COMMIT or PASTE) as its own line, compares the editor's WHOLE final text with an explicit expected value, accepts only a uniquely FOCUSED editor as the target, reads `bound()` from the whole bound-services block, bounds its log reads to the take it owns, keys its restore book by the phone's hardware identity, says `NOT RUN` (never `BLOCKED`) when recording is off, and can wrap a take in an owned, journaled screen recording. The dormant speaker-playback path (`stage_phone_speech` and friends, the windowed `SpeakerPlaybackActivity` launch) is deleted from the harness, superseded by #162's silent injector on the phone and gRPC on the emulator; the proposed test-APK playback Service is not built. `VoicePipelineDeviceTest` stops sleeping and polling logcat: its two take rows wait on the session owner's phase writes and the History row's insertion outcome, assert the destination editor's whole text from the editor's own receipt file, exactly once, the non-target editor unchanged, and the stored route; the audio is fed by the driver (harness) when the test reports LISTENING, on the emulator over gRPC and on the phone through the #162 injector, so the test itself never plays a speaker. A two-editor side-button row (REF-01) joins it.

## 1. Problem
Fifteen audit findings (2026-09-16) and the REF-09 comment (2026-09-20) name ways the harness and the device test can report success for a dictation that did not land where it should, or did not land at all. Gate 0 (issue comment 2026-09-22) re-read each against `scripts/uat/wispr_eyes.py` at b94a1e4 (3628 lines): still open are findings 2, 3, 4, 9, 10, 11, 12, 13, 14 and REF-09; findings 1, 5, 6, 7, 8, 15 and sections B/C concern the speaker-playback path, dormant behind `RECORDING_IS_OFF` and superseded by #162.

## 2. Goals & non-goals
### 2.1 Goals
- A `VERIFIED` line from `dictate_emulator` / `debug_insert` means: the same focused editor before and after; its whole text equals `before + expected`; the current take's `insertion api=` line says `outcome=VERIFIED written=true target=<package>`; the route is reported separately.
- The harness's evidence is bounded to the take it owns; two takes in the window is `UNKNOWN`, never a pass.
- Every recording-gated entry point answers `NOT RUN: recording from this harness is off …` before touching anything.
- The device test learns the subject is done from the subject (phase writes, the History row), never from `Thread.sleep` or a logcat poll; it asserts the editor's own text, exactly once, the other editor untouched, and the stored route.
- Dead harness code is deleted, not repaired.
### 2.2 Non-goals
- Turning recording on for the phone from the harness (`RECORDING_IS_OFF`): unchanged; the phone's take stays `scripts/uat/silent-audio/run.py`.
- A windowless playback Service in the test APK (issue sections B/C): not built; #162's `REMOTE_SUBMIX` injector needs no APK change and already answers the windowless requirement.
- Deleting `SpeakerPlaybackActivity` from the test APK: it stays for three instrumentation fixtures, `SilenceStoppedTakeTranscribesDeviceTest` (`:69-72`), `CaptureWithSilenceStopDeviceTest` (`:64-67`) and `SilenceStopEndToEndDeviceTest` (`:43`); only the non-instrumentation harness and script launches are deleted (coverage F3). Those callers are out of scope here (they do not sleep on a launch).
- A type-aware or DOM-identity check of a Chrome web field (finding 4's "NOT VERIFIED"): the editor identity stays resource id + bounds + package + focus, stated.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end
- The take's evidence lines are produced by the app: `insertion api=… route=… written=… returned=… evidence=… outcome=… attempts=… ms=… overrun=… target=…` (read live 2026-09-22 on the emulator: `insertion api=36 route=COMMIT written=true returned=VOID evidence=SURROUNDING outcome=VERIFIED attempts=1 ms=54 overrun=false target=com.google.android.gm`); `DebugInsert: pin=… handoff=… textChars=…` (the debug receiver); `Stopped by <what>.`, `Transcription result received (chars=N)`, `(handoff=X)` (the owner). Consumers: `last_take()` (`wispr_eyes.py:2928`, still parses `Insertion completed via (\w+)`, a line no producer writes: `grep -rn 'Insertion completed via' app/src` → zero hits), `dictate_emulator` (`:2586`, prints the `insertion api=` tail as a NOTE, gates nothing on it), `debug_insert` (`:2661`), `_wait_for_the_take_to_finish`, `recording()` (`:1460`).
- The editor's text is produced by the editor and read by `_focused_field(package)` (`:3455`) through the fast eye / uiautomator tree: `(resource_id, bounds/identity, text)`; `dictate_emulator` compares `after[:2] != before[:2]` then `_sentence_landed(sentence, after[2])` (`:2539`, substring on `_plain`, capitalised variant tried).
- Accessibility liveness: `dumpsys accessibility` → `bound()` (`:482`), regex `Bound services:\{Service\[label=EnviousWispr`. The dump on the emulator (captured 2026-09-22): `Bound services:{Service[label=EnviousWispr, feedbackType[…], …]}`; items are comma-separated inside one `{…}`; with another service bound first ours is not at the head. Consumers: `enable_auto_paste`, `dictate_emulator`, `debug_insert`, `ready()`.
- The restore book: `~/.cache/wispr-eyes/restore.json`, keyed by the TRANSPORT serial (`_owe`/`_owed`/`_settled`, `:190-244`; the file today holds `100.94.206.47:5555`, `host`, `emulator-5554`). `ro.serialno` is read at `:389` for device selection only.
- Recording gate: `_recording_is_off()` (`:276`); callers return `BLOCKED: {RECORDING_IS_OFF}` at `dictate_emulator:2595-2596` and three more (enumerated in §10). The skill (`.claude/skills/wispr-eyes/SKILL.md`) says `NOT RUN: recording from this harness is off`.
- Device test signals: `DictationSurfaceState` writes `phase` into `SharedPreferences("dictation_surface_state")` (`shortcuts/DictationSurfaceState.kt:14-15`); `aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure` already waits on it with an `OnSharedPreferenceChangeListener` (`VoicePipelineDeviceTest.kt:252-283`). The insertion outcome is written to the History row (`TranscriptDao.finalizeInsertionOutcome`, `updateStatus(insertionResult=…)`; values `InsertionResults.COMMITTED|PASTED|CLIPBOARD|COPY_ONLY|…`, `insertion/InsertionResults.kt`), observable through `TranscriptDao.observeAll(): Flow<List<TranscriptEntity>>` (Room invalidation, subject-fired). The editor's receipt: `PasteTargetActivity` writes its whole text to `files/paste-target-received.txt` on every change (`PasteTargetActivity.kt:52-63`), an oracle the subject did not write.
- `launcherRecordsPhonePlaybackAndReachesClipboardStep` (`:168-199`): `Thread.sleep(2_000)`, speaker playback with `Thread.sleep(clip length)`, a 30 s `logcat -d` poll for `Auto-insert handed` / `transcript kept on clipboard`, and asserts a polish engine name (`S1-mini by Superwhisper (NPU)`) that the emulator cannot produce.

### 2. Find the existing authority before proposing one
- The harness IS the authority for driving a device (`tools-and-apps.md` RULE: drive-the-phone-through-wispr-eyes-before-any-raw-adb); its journal is the restore authority; `run.py` is the phone's take authority (#162). Nothing new is invented: the verdict gates, the identity key and the screen recording are additions to existing functions.
- For audio into an instrumented take there is no authority today: `VoicePipelineDeviceTest` plays a speaker itself. The harness already owns audio on both devices (`inject_audio` over gRPC; `run.py`'s injector), so the design makes the harness the audio driver for the device test too (D-T2).

### 3. Read prior attempts and live direction
- 2026-09-16: the audit queued here; `take-windowless.py` (the app_process dex hack) proved COMMIT on the phone; Codex said a test-APK Service should be the durable home. 2026-09-20: #162 shipped `run.py` (REMOTE_SUBMIX injection, no APK, silent, fail-closed), making the Service unnecessary; #177 removed Appium and made the harness the only door; #181 the fast eye. 2026-09-21/22: `dictate_emulator` proved COMMIT into Gmail on the emulator on every #115 and #194 run.
- Founder direction: recording from the harness stays off on the phone until a review closes the class (`wispr_eyes.py:247-259`); night UAT on the phone is `run.py`.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss
- Instrumentation runs in the APP process; `PasteTargetActivity` runs in the TEST package's process: the field's text crosses by the receipt FILE (already so). The History DB is in the app process: `observeAll()` works from the test.
- The emulator cannot hear its own speaker; audio enters through the host (gRPC) or the phone's injector, both OUTSIDE the instrumented process, so the test must publish "listening now" and the driver must inject; the phase write IS that publication.
- The restore book is shared by every session and process (`_journal_locked`); re-keying it must migrate under the same lock and never drop a debt.
- A logcat window: `clear_log()` is a device-wide side effect; the bound must be a TIMESTAMP recorded by the owner of the take, not a clear another session could also perform.

### 5. Prove the high-risk premises
- `insertion api=` is the only insertion outcome line the app writes: `grep -rn 'insertion api=' app/src/main/java` → `paste/InsertionOutcomeLine.kt` (one producer); `grep -rn 'Insertion completed via' app/src` → zero.
- `Bound services:{…}` lists every bound service comma-separated in one block (captured above); `label=EnviousWispr` is the app label, so the match is on the label token anywhere in that block.
- `ro.serialno` is readable on both devices without root: emulator EMULATOR37X2X8X0 (read 2026-09-22); the phone's is read at `:389` today.
- `screenrecord` exists on both devices and stops on SIGINT/kill with a playable file: to be proven in the first build chunk (an unexecuted premise until then; `validation-discipline.md` RULE: an-unexecuted-premise-survives-every-review-that-only-reads).
- `am instrument -w -e class …` runs a single `androidTest` class on the emulator without uninstalling anything (the uninstall is `connectedAndroidTest`'s, `device-testing.md` RULE: connectedAndroidTest-UNINSTALLS-the-app-so-never-point-it-at-the-daily-phone); the test APK must be installed once by `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## 3. Design

### Part H, the harness (`scripts/uat/wispr_eyes.py`)
H1. **The take's evidence is parsed, not eyeballed.** `_insertion_line(text)` (proposed) parses one `insertion api=` line into `{api, route, written, returned, evidence, outcome, attempts, ms, overrun, target}`; `last_take()` returns it as `insertion` (the stale `Insertion completed via` key goes) and counts `insertion api=` lines in its window (`insertions` (proposed)). `dictate_emulator` and `debug_insert` require `insertions == 1`, `outcome == "VERIFIED"`, `written == "true"`, `target == target_package`, else `ISSUE: …` naming the field; the route is its own line `VERIFIED: route=COMMIT` (or `PASTE`, still a pass; `route` is reported, never assumed). `dictate_emulator(..., route=None)` (proposed) may REQUIRE a route: `route="COMMIT"` turns a PASTE into `ISSUE`.
H2. **The content verdict is exact.** `dictate_emulator(sentence, target_package, expect=None)`: the expected editor text is `before_text + expect`, where `expect` defaults to the literal rendering rule of a plain sentence, `sentence[:1].upper() + sentence[1:] + "."` (written here as a literal, not computed by the app; if the app's cleanup changes, this goes red loudly and the caller passes `expect`). The line is `VERIFIED: the same editor's whole text now equals the expectation (N chars)` or `ISSUE: the editor's whole text differs from the expectation; it ends …`. `debug_insert(text)` compares with `before_text + text`. `_sentence_landed` is deleted (its only callers were these two); `_plain` stays for `find`.
H3. **Only a uniquely focused editor is a target.** `_focused_field`: `chosen = [n for n in fields if n["focused"]]`; none → `None`; more than one → `Blocked("N editors are focused …")`. `focus_field` already reads back focus.
H4. **`bound()` reads the whole block, and the exact component.** The `Bound services:{…}` entries carry the app LABEL only (`Service[label=EnviousWispr, feedbackType[…], …]`, captured 2026-09-22; no component in that block), and the component is in `Enabled services:{{com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService}}`. So: `re.search(r"Bound services:\{(.*)\}\s*$", out, re.M)` must contain `label=EnviousWispr` anywhere in the block, AND the `Enabled services` block must contain the exact `ACCESSIBILITY_SERVICE` component; either missing → `False`; a missing `Bound services:` line stays `Blocked` (coverage F7, modified: the block cannot name the component, so the label is matched where it lives and the component where it lives).
H5. **Evidence is bounded to the owned take.** `clear_log()` records the device clock (`date '+%m-%d %H:%M:%S.000'`) in `_STATE["log_since"]` (proposed key); `last_take()`, `recording()` and `_wait_for_the_take_to_finish` read `logcat -d -T '<since>'` when it is set and the WHOLE buffer with `"bounded": False` in the result when it is not; `last_take()` requires exactly one `recording_start` in the window, else `{"unknown": "N takes in the window"}` and the callers report `ISSUE: … UNKNOWN`.
H6. **Recording off is `NOT RUN`.** Every report-returning `_recording_is_off()` caller returns `NOT RUN: {RECORDING_IS_OFF}` before staging (four suites, §10); the guard's own two raises inside `open_recorder` stay; `RECORDING_IS_OFF` text unchanged.
H7. **The book is keyed by hardware identity.** Identity is cached PER TRANSPORT, never as one scalar: `_identity_of(transport)` (proposed) reads `ro.serialno` once per transport into `_STATE["identities"]` (proposed key), and `_book_key(transport)` (proposed) returns it; `_owe_locked`, `_owed`, `_settled_locked` use the key of the current transport. `restore()` (which today enumerates `_journal_read()` directly at `:3150` and compares raw keys with attached transport serials at `:3148-3158`, switching device with transport state only, `:2989-2994`) resolves EVERY attached transport to its identity under `_journal_locked`, migrates every attached transport-keyed debt under its identity key (a MOVE, never a drop), enumerates the identity-keyed debts, and uses the matching transport for the device operations that pay them; `_as_serial` carries both transport and identity. The literal `host` scope is never re-keyed. `restore()` reports which key it paid (coverage F4).
H8. **An owned screen recording.** `screen_recording(path)` (proposed) context manager: journals `("screenrecord", pid_or_marker)` BEFORE `adb shell screenrecord --time-limit 180 /sdcard/wispr-eyes/<token>.mp4` starts (background), stops it with `kill -2 <pid>` found by `pidof screenrecord` (refuses when more than one), waits for the file to stop growing, pulls it to `path`, deletes the device file, settles the debt; `restore()` pays a leftover by killing and deleting. `dictate_emulator(..., record=None)`: a host path turns it on; the report carries `NOTE: screen recording at <path>` and the file's size; it is evidence to inspect, never a verdict.
H9. **The speaker-playback path is deleted.** `SPEAKER_ACTIVITY`, `stage_phone_speech`, `play_staged_speech`, `unstage_phone_speech`, `say_on_phone` (`:2036`), `test_dictation` (`:3417`, which calls them at `:3474,3489,3497`) and the `dictate` help/dispatch branch (`:3591-3594`), their harness tests in `test_wispr_eyes.py`, their journal kind, and the three retired raw speaker-launch scripts `scripts/uat/dictate-once.sh`, `scripts/uat/dictate-until-silence.sh`, `scripts/uat/finish-silence-stop-uat.sh` (coverage F2); `SpeakerPlaybackActivity.java` STAYS (three instrumentation fixtures, §2.2). `RECORDING_IS_OFF` and `open_recorder` unchanged. Historical `session-log.md` entries naming them stay as history.
H10. **Docs.** `wispr_eyes.py:1406` and `:3139` "dev state on a phone with no users" → "the founder's daily driver; History and foreground state are not restored; never wipe app data or uninstall `com.envi.wispr`"; `.claude/knowledge/phone-audio-playback.md` becomes a pointer to `device-testing.md` FACT: silent-physical-phone-audio-injection-for-night-uat; the skill's table gains `run_device_test` and drops `test_dictation` (`SKILL.md:22`; coverage F2). Finding 14's two other bullets are already superseded: the `tree()` claim by #181's fast eye (`device-testing.md:147-165`) and the Appium procedure by #177 (`device-testing.md:211-215`); H10 closes only the daily-driver wording and the phone-audio ownership (coverage F8).

### Part T, the device test (`app/src/androidTest`)
T0. **The class-wide `@Before`/`@After` go** (`VoicePipelineDeviceTest.kt:90-103` binds ASR and polish, requires the cached fixture and warms S1 for every row; coverage F1). Only `transcribesThenPolishesWithSavedCustomWords` binds the services, requires the fixture and warms S1, inside itself; T1/T3/T4 require neither the fixture nor S1 when `audio=external`; `audio=speaker` alone checks the fixture with `assumeTrue`.
T1. **`launcherRecordsPhonePlaybackAndReachesClipboardStep` is replaced** by `aSideButtonTakeLandsInTheFocusedEditorExactlyOnce` (proposed): stage `PasteTargetActivity` (field A focused, receipt file cleared by its `onCreate`); register the phase listener; start `VoiceInputActivity` (the side-button stand-in, `android-tooling.md` FACT: driving-the-app-from-the-command-line); await `LISTENING` (20 s bound, the failure names the phase seen); the audio arrives (T2); await the History row's insertion outcome through `observeAll()` (a new row with `insertionResult` in `{committed, pasted}` and `status` final; 90 s bound naming the last row seen); then assert: the receipt file's text `== EXPECTED_TEXT` (a literal, exactly once: `count(EXPECTED_TEXT) == 1`), `insertionResult == "committed"` on API 33+ with this field (the route), and `handoff` from the owner's line is not consulted for the verdict (the log stays diagnostic). No polish engine assertion (the emulator has none; the polish limb's outcome is `transcribesThenPolishesWithSavedCustomWords`'s business).
T2. **The audio is fed by the driver.** The test takes an instrumentation argument `audio` (`-e audio external|speaker`, default `external`): `external` means the test publishes LISTENING (it already does through the owner's phase) and WAITS; `speaker` plays the fixture through `playFixtureThroughSpeaker` (kept for the phone with no driver present; its `Thread.sleep(clip length)` is the clip's duration, not a settle, and stays). The harness gains `run_device_test(test_class, sentence, ...)` (proposed): installs nothing, runs `am instrument -w -r -e class <cls> -e audio external com.envi.wispr.test/androidx.test.runner.AndroidJUnitRunner` in the background, polls the surface phase (`run-as com.envi.wispr cat shared_prefs/dictation_surface_state.xml`, a read) until `LISTENING`, then `inject_audio(pcm)` on the emulator (or, on the phone, refuses: the phone's driver is `run.py`, and `RECORDING_IS_OFF` is checked first), and returns the instrumentation's result stream parsed for the runner's status-code lines (`INSTRUMENTATION_STATUS_CODE` (external)) and the failure text. `dictate_emulator`'s 3 s warm-up before injection is kept in the driver (`grpc-take.sh`, 2026-09-13).
T3. **The two-editor side-button case (REF-01).** `PasteTargetActivity` gains an intent extra `two_fields` (proposed): a second `EditText` B below A with its own receipt `paste-target-b-received.txt`. On creation the activity atomically writes `""` to BOTH receipt files BEFORE installing the text watchers (today it deletes the file and creates it only on the first change, `PasteTargetActivity.kt:55-62`; coverage F5), so an untouched B is an EXISTING empty receipt and a disabled editor write leaves A's receipt empty, which fails. Row `aTakeStartedInAAndFocusMovedToBLandsInAOnly` (proposed): start with A focused, await LISTENING, move focus to B through the activity's own runtime-registered receiver (`ACTION_FOCUS_B` (proposed), registered with `Context.RECEIVER_EXPORTED` (external) on API 33+ because the sender is the app process and the activity the test package's UID, unregistered in `onDestroy`, sent as an explicit intent with the package set to `com.envi.wispr.test`; no manifest receiver, no manifest `android:exported`; coverage F6), the audio arrives, stop; assert both receipts exist, A's `== EXPECTED_TEXT` once and B's `== ""`, `insertionResult == committed`.
T4. **`aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure`** keeps its phase waits (already subject-fired) and swaps `playFixtureThroughSpeaker()` for T2's audio argument; `observedHandoff()` (a logcat read) stays as the PRECONDITION read (it classifies the staging, it does not judge the outcome), stated in its KDoc.
T5. **Class declarations.** The rewritten rows are Product Outcome (when they fail, the user's words did not reach the field they were typing in, or reached it twice, or reached the wrong field); `transcribesThenPolishesWithSavedCustomWords` unchanged (Product Outcome, the polish limb).

## 3b. Ownership justification
No new owner. The harness stays the one driver; the device test stays the heart's one instrumented crossing; `run_device_test` is the harness driving `am instrument` the way it drives everything else.

## 4. Contract deltas
- `last_take()`: `insertion` becomes a dict (or `None`); new keys `insertions` (proposed), `bounded` (proposed), `unknown` (proposed).
- `dictate_emulator(sentence, target_package, expect=None, route=None, record=None)`; `debug_insert(text, target_package)` verdict lines change wording (see H1/H2); the harness's own tests (`scripts/uat/test_wispr_eyes.py`) update.
- `_focused_field` never returns an unfocused editor.
- The restore book's keys change from transport serial to hardware identity; existing entries migrate on first read.
- Recording-gated entry points return `NOT RUN: …` instead of `BLOCKED: …` (four sites).
- Deleted: `SPEAKER_ACTIVITY`, `stage_phone_speech`, `unstage_phone_speech`, the wrapper at `:2038`, `_sentence_landed`, the `speaker` shell subcommands.
- `VoicePipelineDeviceTest`: `launcherRecordsPhonePlaybackAndReachesClipboardStep` removed (its protection, "the launcher path reaches the clipboard step", moves into T1's stronger row: the editor's text, not a log marker; `testing-philosophy.md` RULE: deleting-a-test-carries-the-burden-of-adding-one); new rows T1, T3; instrumentation argument `audio`.
- `PasteTargetActivity`: extra `two_fields`, second receipt, both receipts written `""` at creation, `ACTION_FOCUS_B` (proposed) runtime receiver (exported).
- Deleted scripts: `scripts/uat/dictate-once.sh`, `scripts/uat/dictate-until-silence.sh`, `scripts/uat/finish-silence-stop-uat.sh` (raw speaker launches, superseded).
- The test APK manifest: no new component (a broadcast receiver registered in the activity at runtime).

## 5. End-to-end state and lifecycle audit
Async edges for the device rows (`code-design-rules.md` RULE: async-edge-case-enumeration): Interrupted (the driver dies before injecting: the take ends by the 3 s silence bound or the cap; the test's 90 s wait names the phase seen; `restore()` pays the journal) · Deleted (the receipt file removed mid-run: the read fails loudly, not as `""`, because the row asserts the file EXISTS then its text) · Mutated (a second take writes the receipt: `count(EXPECTED) == 1` fails) · Concurrent (two instrumentation runs: `am instrument` serialises per runner; the harness refuses when `pidof` shows one running) · Absent (no History row within the bound: the failure names the rows seen; fail closed) · Stale (an old row: the row awaited must be CREATED after the test's start timestamp, read from `createdAtMs`).
For the harness: the `log_since` (proposed) key unset → `bounded: False` in the result and the verdict says so; a journal migration racing another session → under `_journal_locked`, first-writer moves, second finds nothing to move.

## 6. Downstream consumer matrix
| Consumer | Reads | Change |
|---|---|---|
| `scripts/uat/test_wispr_eyes.py` | `_focused_field`, `last_take`, `_sentence_landed`, verdict strings | rows updated; new rows for `_insertion_line`, the exact compare, the bound window, the book key |
| `scripts/uat/scenes-115/*` | `dictate_emulator`, `last_take()['ended_by']` | unchanged keys; re-run one scene |
| `.claude/skills/wispr-eyes/SKILL.md`, `device-testing.md` | the call table, `stage_phone_speech` | speaker rows removed, `run_device_test` added |
| `scripts/uat/silent-audio/run.py` | nothing from the harness | unchanged |
| `SilenceStoppedTakeTranscribesDeviceTest` | `SpeakerPlaybackActivity` | unchanged (the activity stays) |

## 7. Failure-mode × caller table
| Failure | `dictate_emulator` | `run_device_test` | `restore()` |
|---|---|---|---|
| no `insertion api=` line in the window | `ISSUE: no insertion outcome was logged for this take` | the row's own 90 s bound fails naming the rows seen | n/a |
| two takes in the window | `ISSUE: … UNKNOWN (2 takes)` | same | n/a |
| editor text ≠ expectation | `ISSUE: … differs; it ends …` | the row fails with both strings | n/a |
| no focused editor / two focused | `BLOCKED` before the take | the rig focuses A itself | n/a |
| `screenrecord` left running | n/a | n/a | killed by pid, file deleted, debt settled, reported |
| `ro.serialno` unreadable | `device()` refuses (identity unknown) | same | pays the transport-keyed book and says so |

## 8. Caller-visible signals audit
Verdict lines are the signals: `VERIFIED:` only on the exact editor text AND the parsed outcome; `ISSUE:` names the field that failed; `NOT RUN:` for recording off; `BLOCKED:` for a precondition the caller can fix; `NOTE:` for evidence (the route line, the recording path).

## 9. Fallback source-of-truth audit
The editor's own text (receipt file on the rig; the tree read on Gmail) is the truth for "landed"; the History row is the truth for the route; the log is diagnostic only. Where they disagree the row fails and prints all three.

## 10. File-by-file changes
- `scripts/uat/wispr_eyes.py`: H1..H10. `_recording_is_off()` callers, enumerated (`grep -n '_recording_is_off()' scripts/uat/wispr_eyes.py`, 2026-09-22): six sites; the four report-returning suites at `:2595` (`dictate_emulator`), `:3176` (`check_recorder`), `:3388` (`room_is_quiet`), `:3438` (`test_dictation`) change to `NOT RUN:`; the two `raise Blocked(RECORDING_IS_OFF)` inside `open_recorder` (`:1674`, `:1741`) STAY: they are the guard itself, and a context manager cannot return a report.
- `scripts/uat/test_wispr_eyes.py`: rows per §6.
- `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt`: T0, T1, T2, T4, T5.
- `scripts/uat/dictate-once.sh`, `scripts/uat/dictate-until-silence.sh`, `scripts/uat/finish-silence-stop-uat.sh`: deleted (H9).
- `app/src/androidTest/java/com/envi/wispr/PasteTargetActivity.kt`: T3.
- `.claude/skills/wispr-eyes/SKILL.md`, `.claude/knowledge/device-testing.md`, `.claude/knowledge/phone-audio-playback.md`: H10 and the `run_device_test` recipe.

## 11. Testing
1. Classes: the harness's JVM rows (`test_wispr_eyes.py`) are Harness Contract; T1 and T3 are Product Outcome (a user's words reach the field they were typing in, once, and no other); T4 stays Product Outcome; the deleted launcher row's protection moves into T1.
2. Two-way controls for the harness parser: `_insertion_line` against the live line captured in §2.5 (parses every field) and against a line with `outcome=UNVERIFIED` (the verdict is `ISSUE`).

### 11.1 Hardware UAT spec (emulator)
- `dictate_emulator('the quarterly marker sentence lands tomorrow', route='COMMIT')` into Gmail: `VERIFIED: route=COMMIT`, `VERIFIED: … whole text now equals the expectation`, and with `expect='wrong'` the same take reports `ISSUE` (the negative control, run once).
- `run_device_test('com.envi.wispr.VoicePipelineDeviceTest#aSideButtonTakeLandsInTheFocusedEditorExactlyOnce', …)` and `…#aTakeStartedInAAndFocusMovedToBLandsInAOnly`: both green on the emulator; the failure control: run T1 with the harness injecting NOTHING (the take ends on the bound) and read the row red naming the phase seen.
- `screen_recording` around one take: the file pulled, non-empty, playable (`ffprobe` duration ≈ take length), the device file gone, the book empty.
- `restore()` after a simulated leak: start `screenrecord` through the helper, kill the Python process, run `restore()` from a fresh process: killed, deleted, reported.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `test_wispr_eyes.py::test_insertion_line_parses_the_live_shape` (proposed) | Harness Contract | every field of the captured line | drop a field |
| `test_wispr_eyes.py::test_verdict_requires_verified_written_and_target` (proposed) | Harness Contract | `outcome=UNVERIFIED`, `written=false`, a foreign target each give `ISSUE` | gate on `outcome` only |
| `test_wispr_eyes.py::test_whole_text_must_equal_before_plus_expected` (proposed) | Harness Contract | a superset, a duplicate, a truncation each give `ISSUE` | fall back to substring |
| `test_wispr_eyes.py::test_only_a_focused_editor_is_chosen` (proposed) | Harness Contract | one unfocused editor → `None`; two focused → `Blocked` | `focused or fields` |
| `test_wispr_eyes.py::test_bound_reads_the_whole_block` (proposed) | Harness Contract | ours second in the block → `True`; absent → `False`; no block → `Blocked` | anchor at the head |
| `test_wispr_eyes.py::test_book_is_keyed_by_identity_and_migrates` (proposed) | Harness Contract | a transport-keyed debt is paid under the identity key; nothing dropped | key by serial |
| `test_wispr_eyes.py::test_recording_off_is_not_run` (proposed) | Harness Contract | all four gated entry points return `NOT RUN:` | `BLOCKED:` at one |
| `VoicePipelineDeviceTest.aSideButtonTakeLandsInTheFocusedEditorExactlyOnce` (proposed) | Product Outcome | receipt `== EXPECTED_TEXT` once, `insertionResult == committed` | write the text twice; disable the editor write (the row fails even though logs remain) |
| `VoicePipelineDeviceTest.aTakeStartedInAAndFocusMovedToBLandsInAOnly` (proposed) | Product Outcome | A holds the text, B is empty | insert into the focused field |

## 12. Blast radius & rollback
Dev tooling and instrumentation only; the app APK does not change (the test APK does). Rollback: revert the PR. Risk: the restore-book re-key touches the founder's phone's journal; the migration runs under the existing lock and is covered by a JVM row against a copied book; `restore()` prints the key it paid.

## 13. Ship criteria specific to THIS change
- `python3 scripts/uat/test_wispr_eyes.py` green with the new rows, count reported.
- The emulator UAT of §11.1, including the two negative controls.
- Codex ALL-CLEAR with a confirming rerun; Phase 3 PASS; PR "Closes #161".

## 14. Open questions
- The founder's phone pass: T1 and T3 with `-e audio external` need the phone's driver; `run_device_test` refuses on the phone while `RECORDING_IS_OFF`, so on the phone the rows run with `-e audio speaker` by hand, or through `run.py` once it learns to drive `am instrument`. Deferred to his pass.
- REF-02's injected settings/vocabulary failure rows on the device: deferred. No seam exists (`grep -rln 'FailOpen\|failOpen\|DebugFailure' app/src/main/java app/src/debug/java` → none), a seam on a guard is a bypass (`validation-discipline.md` RULE: a-test-seam-on-a-GUARD-is-a-bypass), and corrupting the founder's preference files is not a staging step; #193's JVM rows are the evidence.

## 15. Related
#161 (this), #162 (`run.py`), #177 (one door), #181 (fast eye), #192 (REF-01, the pin), #193 (REF-02), #204, #206; audit `docs/audits/2026-09-20-senior-audit.md` REF-09.
