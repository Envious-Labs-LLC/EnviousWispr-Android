# #161 emulator pass, 2026-09-22 (local 01:13–01:40), harness at `bcaef6a`, app APK unchanged from `b94a1e4`

Device: `EnviousWispr_Android_16_Play` AVD (`emulator-5554`); debug APK and the test APK installed with `adb install -r`; driven through `scripts/uat/wispr_eyes.py` (`WISPR_SERIAL=emulator-5554`). The founder's phone was not attached (his instruction: emulator only).

## Scene 1: the repaired take verdict into Gmail (H1, H2, H3, H5, H8)
`dictate_emulator('the quarterly marker sentence lands tomorrow', expected_final=…, route='COMMIT', record='/tmp/wispr-eyes/161-scene1.mp4')` into an empty Gmail compose.

First run, preregistered literal `'The quarterly marker sentence lands tomorrow. '`:
```
NOTE: the owner reported the take live
NOTE: injected 31 packets, 3.1s of audio
NOTE: screen recording saved to /tmp/wispr-eyes/161-scene1.mp4 (822696 bytes)
VERIFIED: a take ran, ended by a stop request
VERIFIED: 45 characters came back from the speech engine
NOTE: insertion api=36 route=COMMIT written=true evidence=SURROUNDING outcome=VERIFIED attempts=1 target=com.google.android.gm
VERIFIED: route=COMMIT, the app's own outcome line says written, verified, into com.google.android.gm
ISSUE: the editor's whole text differs from the expectation; it ends 'The quarterly marker sentence lands tomorrow.' (45 chars), expected 'The quarterly marker sentence lands tomorrow.' (45 chars)
```
Diagnosis (the literal was not changed during that run): the tree read the editor as `'The quarterly marker sentence lands tomorrow.\xa0'`; the paste service's own line read `before=46` after the commit; Gmail's editor stores the inserted trailing space as U+00A0. The old `_excerpt` folded U+00A0 into a space and stripped, which is why both tails printed identical; the mismatch line now prints raw tails. Not a product change; the Gmail literal is `'…tomorrow.\xa0'`.

Second run, literal `'The quarterly marker sentence lands tomorrow.\xa0'`:
```
VERIFIED: route=COMMIT, the app's own outcome line says written, verified, into com.google.android.gm
VERIFIED: the editor's whole text now equals the expectation (46 chars); the same editor is focused
```
Negative control, `expected_final='wrong', route='PASTE'` on the same flow:
```
ISSUE: the app's own outcome line does not say the words were delivered: route=COMMIT (wanted PASTE)
ISSUE: the editor's whole text differs from the expectation; it ends 'The quarterly marker sentence lands tomorrow.' (45 chars), expected 'wrong' (5 chars)
```
The recording: `ffprobe` duration 15.55 s, playable; the device file gone; the book empty after the run.

## Scene 2: the device rows through `run_device_test` (T1, T3, T4, T2)
Three false starts, each a real finding, each fixed before the pass:
1. The rig never wrote its receipt: `PasteTargetActivity` (Kotlin) crashed in the test package's process with `ClassNotFoundException: kotlin.io.FilesKt` (`File.writeText` inside `runCatching`, both stdlib, the catch swallowing it). The rig is now Java, like the other two rigs.
2. `handoff=SERVICE_NOT_RUNNING`: the test's shell helper connected a bare `UiAutomation`, which makes the system unbind every other accessibility service while held (the #177 trap). Now `getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`. Also: `am instrument` restarts the app's process and the paste service dies with it; the AVD did not rebind it on its own (dumpsys: `Crashed services:{{…PasteAccessibilityService}}`), so the rows wait on `PasteAccessibilityService.isBound` and the driver rebinds (journaled) when the runner's first `test=` group arrives.
3. T3's first draft expected the words in editor A after focus moved to B; the paste service waited for the original editor to return and gave up (`outcome=NEVER_RETURNED attempts=20`, row `insertion_interrupted`/`copy_only`). That is the product's fail-safe (`architecture-rules.md` RULE: insertion-fails-safe-never-silently); the row now asserts it.

Passes (each `run_device_test(...)` line on the emulator):
```
NOTE: the accessibility service was unbound by the instrumentation restart; rebound: True
NOTE: injected 31 packets, 3.1s of audio
NOTE: audio done broadcast sent
VERIFIED: VoicePipelineDeviceTest.aSideButtonTakeLandsInTheFocusedEditorExactlyOnce passed on the device
VERIFIED: VoicePipelineDeviceTest.aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard passed on the device
VERIFIED: VoicePipelineDeviceTest.aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure passed on the device
```
T4 needed one honest narrowing: the shade held the polish notice (`POLISH_NOTIFICATION_ID`, "AI cleanup skipped", the emulator has no ready local model) and the OS's own silent-section group summary (id 1, `FLAG_GROUP_SUMMARY`); both are set aside by name, everything else of ours must be absent.

Negative control, `starve=True` (READY answered, no audio fed):
```
NOTE: STARVED on purpose: no audio fed (the negative control)
ISSUE: VoicePipelineDeviceTest.aSideButtonTakeLandsInTheFocusedEditorExactlyOnce FAILED: java.lang.AssertionError: No History row for this take reached a final status within 90 s; rows since the start:
```

## Scene 3: a leaked screen recorder paid from a fresh process (H8, H7's book)
Process A: `with screen_recording('/tmp/wispr-eyes/leak.mp4'): os._exit(3)` → `pidof screenrecord` = 3526, `/sdcard/wispr-eyes/8a47….mp4` present, book: `[('screenrecord', '8a4749c6…')]`.
Process B: `restore()` → `['screenrecord back to 8a4749c6…']`; `pidof screenrecord` empty (rc 1); the device folder empty; book empty.

## Also seen
- `install -r` of the TEST APK, and every `am instrument` start, leave the paste service unbound with `accessibility_enabled` reading 0 while the service is still named; `enable_auto_paste()` now clears then sets in that state (the same string re-put does not rebind), and the clear is written without the strict read-back because the system normalises the flag on its own right after the list empties.
- The unit suite (`scripts/measure-tests.sh`): 1051 tests, 0 failures; the harness's own rows: 175 passed.

`restore()` at the end: nothing owed; auto-paste left ON as the emulator's baseline.
