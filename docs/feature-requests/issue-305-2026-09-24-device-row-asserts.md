# Issue #305: the silence device rows fail when their real boundary fails (2026-09-24)

GitHub issue: `#305`. Tier: SMALL (instrumentation rows only; no app code). Status: revised after the combined coverage and grounded round (`305-cov`), all findings adopted; grounded round 2 (`305-g2`), all four findings adopted; grounded round 3 (`305-g3`), all three adopted; round 4 (`305-g4`), the stale runner sentence replaced.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N for the app (no app code changes). The changed rows split by what they need (grounded finding 3): the rows that play speaker audio (`SilenceStoppedTakeTranscribesDeviceTest.aTakeThatEndedOnSilenceStillProducesWords`, `CaptureWithSilenceStopDeviceTest.realSpeechThroughTheMicrophoneEndsTheTakeWhenItStops` and `theWaitSettingDecidesWhetherAThinkingPauseEndsTheTake`) need a physical phone's speaker-to-microphone path and are NOT RUN here. The rest need only the capture process or a pushed file (`CaptureWithSilenceStopDeviceTest`'s other five rows, all four `SilenceDetectorDeviceTest` rows) and are run on the emulator through `am instrument`, their results read per row.

## Preface — User Rubric

User Rubric: N/A — test-only change; it makes three phone-test files report a capture that will not start, a broken detector, a missing fixture or a missing speech model as a failure instead of a pass, and a missing microphone permission as NOT RUN.

---

## 0. TL;DR

REF-02 of `docs/audits/2026-09-24-senior-audit.json` (`.claude/rules/testing-philosophy.md` RULE: the-heart-crosses-a-real-boundary-at-least-once). An instrumented `assumeTrue` that fails is written as PASS (`validation-discipline.md` FACT: silent-empty-traps, the `assumeTrue` row), so `SilenceStoppedTakeTranscribesDeviceTest` passes when capture will not start, when the detector never becomes ready, or when the speech model is missing. Replace the skips with assertions; the one remaining skip, a missing microphone permission checked independently, logs NOT RUN.

The class, enumerated (`git grep -n "assumeTrue" -- app/src/androidTest`): the same skips exist in `CaptureWithSilenceStopDeviceTest` (capture start and detector readiness, every row) and `SilenceDetectorDeviceTest` (the pushed speech fixture). Detector readiness, the speech model and the pushed fixture are staged by the rows themselves or by the documented push (`phone-audio-playback.md`), so each becomes an assertion. Capture START (coverage finding 2, grounded finding 1): a `false` start alone does not establish a harness restriction; it could hide a capture defect. So: stage the microphone grant before running. Skip only for a separately observed harness restriction (the one observed here: the app package does not hold `RECORD_AUDIO`, read with `PackageManager.checkPermission`, in which case `DeviceNotRun.skipUnless` logs `NOT RUN: <row>: <reason>` under the tag `DeviceNotRun` and skips); when the prerequisites hold, assert capture starts and include `lastStartFailure` in the failure. `getLastStartFailure()` has a broad "anything else" result, so it names the failure but never proves a harness cause (grounded round 3).

Kept elsewhere, with the reason (coverage finding 1): `VoicePipelineDeviceTest` keeps three skips in its no-field row. Its shape test limits their location, but does not justify them: notification permission and accessibility can be configured; the post-run no-field state may fail to stage. `MacVocabularyMigrationDeviceTest` keeps the skip for a private source absent from an ordinary run. Both are outside this change.

Consolidation (grounded finding 4): the three silence device test files change; `DeviceNotRun.kt` (androidTest) is added; the code-call helpers move out of `VoicePipelineDeviceShapeTest` into a shared JVM test utility that it and the new `SilenceDeviceRowsShapeTest` both use.

Prior context: #161 (harness true verdicts), #239 (the end-to-end silence take lives in `VoicePipelineDeviceTest`), the `assumeTrue` trap in `validation-discipline.md`.

## 1. Grounding (main a33e407)

`git grep -n "assumeTrue" -- app/src/androidTest`:
- `SilenceStoppedTakeTranscribesDeviceTest.kt:77` capture start, `:86` detector ready, `:125` and `:127` speech model (the first is `error.isBlank() || done.await(1, SECONDS)`, which skips nothing useful: it passes whenever the error is blank).
- `CaptureWithSilenceStopDeviceTest.kt:97`, `:156`, `:208`, `:241`, `:267`, `:286`, `:325` capture start; `:107`, `:165` detector ready.
- `SilenceDetectorDeviceTest.kt:65` the pushed speech fixture.
- `VoicePipelineDeviceTest.kt:328`, `:333`, `:354` (kept, policed).
- `MacVocabularyMigrationDeviceTest.kt:33` (kept, unstageable).

## 2. Design

1. Detector readiness and the pushed fixture become `assertTrue(<same message>, <same condition>)`.
2. Capture start: before starting, `DeviceNotRun.skipUnless(<the app holds RECORD_AUDIO>, "<row>", "the app does not hold the microphone permission")`; then `assertTrue("capture must start (last start failure ${capture.lastStartFailure})", started)`. `DeviceNotRun` (new, `app/src/androidTest/java/com/envi/wispr/DeviceNotRun.kt`) is the ONE place in these files' reach that calls `assumeTrue`: it logs `NOT RUN: row: reason` with `android.util.Log.w("DeviceNotRun", ...)` and then assumes. The runner's verdict (grounded finding 2, round 4): for these direct runs, read each instrumentation status: `-3/-4` means NOT RUN. Pair that status with the `DeviceNotRun` log for the reason. `run_device_test` is a separate driver and supplies no verdict for these rows.
3. The speech-model block (coverage finding 3): remove the one-second assumption; await the answer once (60 s), then fail for a missing model (`assertFalse("the speech model must be installed on this phone: $error", error.contains("not ready", true))`) or empty words (the existing assertion).
4. `SilenceDeviceRowsShapeTest` (JVM), reusing `VoicePipelineDeviceShapeTest`'s code-call detection (coverage finding 4; its helpers move to a small shared test utility so both use one detector): the three files contain no `assumeTrue` CALL (comments and strings do not count) and no `org.junit.Assume` import; `DeviceNotRun.kt` logs before it assumes.

## 3. Tests

1. `SilenceDeviceRowsShapeTest` RED on main a33e407 (the skips are there), GREEN after. MUTATION m1: one `assumeTrue` back in `SilenceStoppedTakeTranscribesDeviceTest` (RED). MUTATION m2: one back in `CaptureWithSilenceStopDeviceTest` (RED). MUTATION m3: the fixture skip back in `SilenceDetectorDeviceTest` (RED). MUTATION m4: `DeviceNotRun` assumes without logging (RED). All run against the finished guard.
2. `:app:assembleDebugAndroidTest` compiles the three rows.
3. The rows themselves, on the emulator through `am instrument` (never `connectedDebugAndroidTest`): `CaptureWithSilenceStopDeviceTest`'s five rows without speaker playback and `SilenceDetectorDeviceTest`'s four rows (`enviouswispr-uat.pcm` pushed to the TARGET app package's cache first, since the row reads `targetContext.cacheDir`), for these direct runs, each instrumentation status is read: assumption codes `-3/-4` are NOT RUN, with the `DeviceNotRun` log for the reason; the three speaker rows NOT RUN (physical phone only).
4. m1 to m4 are planned controls until run; their results go in the receipt.

## 4. Blast radius

Test-only: three device test files, one new androidTest helper, one new JVM shape test, one shared JVM test utility, and `VoicePipelineDeviceShapeTest` switched to it. A phone run that used to report PASS with a broken detector, fixture or model now reports FAIL with the reason, as does a capture that will not start while the app holds the microphone permission; a missing permission still skips, but logs `NOT RUN` with the row and reason. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] The shape row RED before and GREEN after; m1 to m4 RED; the suite green; the androidTest build compiles; checks clean.
- [ ] Codex code review ALL-CLEAR with a confirming round.
