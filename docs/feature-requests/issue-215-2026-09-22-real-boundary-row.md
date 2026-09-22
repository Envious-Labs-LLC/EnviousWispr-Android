# Issue #215 — The real-model boundary row fails, never skips, when its fixture is missing — 2026-09-22

GitHub issue: `#215`. Tier: SMALL (one instrumented row, its speaker source, and one harness door; no product code). Status: DRAFT (revised after grounded round 2; three findings deferred to #225).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/src/androidTest/**`, `app/src/test/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (this plan, `scripts/uat/`, `.claude/knowledge/`: `cited-symbols` conditionally).

**PAR rows closed:** none.

**Hardware UAT:** Y (the row is the heart's one real ASR-and-local-polish boundary). Emulator: (a) with no fixture, the harness door answers NOT RUN and a bare `am instrument` of the row reports FAILED with the fixture message (baseline on main: OK, the skip); (b) after `stage_uat_fixture`, the row reaches its saved-name prerequisite and the door answers NOT RUN naming it (no `Saurabh` on the emulator); (c) the staged fixture is removed afterwards. A VERIFIED real-boundary pass on the founder's saved term and model is deferred to #225 and is not required to close #215. #215's hardware UAT is emulator (a) through (c), because its contract is honest failure and NOT RUN classification, not successful model or alias behavior.

## Preface — User Rubric

User Rubric: N/A — test and harness only; nothing a user sees changes.

## 0. TL;DR
`VoicePipelineDeviceTest.transcribesThenPolishesWithSavedCustomWords` opens with `assumeTrue("Physical-phone fixture is missing", File(fixturePath).isFile)`. A bare `am instrument` or Gradle run reports an assumption failure as a pass (`validation-discipline.md` FACT: silent-empty-traps), and the row has no harness door, so the heart's only real ASR-plus-local-polish row reads green wherever nobody staged the fixture. Fix: the row ASSERTS its fixture and names its saved-name prerequisite with an exact `Prerequisite:` message; the speaker source's same fixture check becomes an assertion; a harness door `run_real_boundary()` preflights the APKs, the runner registration and the fixture and reports honestly; `stage_uat_fixture(sentence)` writes a 16 kHz fixture only where none exists, with no clobber race.

## 1. Problem
`fixturePath` is `File(context.cacheDir, "enviouswispr-uat.pcm")` in the target app (`ApplicationProvider` supplies the target context, `VoicePipelineDeviceTest.kt:109`). Nothing in the repository creates it; `device-testing.md` tells a person to generate it (Azure TTS) and write it. When it is absent, `assumeTrue` ends the row as an assumption failure, which a bare run reports as OK. `SpeakerAudio.prepare` carries the same assumption for the `-e audio speaker` path. The row also depends on the founder's saved term `Saurabh` (`BuiltinVocabulary` has `EnviousWispr`, not `Saurabh`); on any other device it fails with "Saved name spelling was not applied", blaming the product for a missing prerequisite. Classification: REPRODUCIBLE (run the row without the fixture: it passes).

## 2. Goals & non-goals
### 2.1 Goals
- The row and `SpeakerAudio.prepare` never pass without the fixture: `assertTrue` with a message naming `stage_uat_fixture`.
- The row checks the saved USER term before any audio work, read from `CustomTermRepository` alone (never merged with built-ins first), with a message beginning exactly `Prerequisite:`.
- `run_real_boundary()` preflights with read-only probes, each exit status kept apart from its output: `pm path com.envi.wispr`, `pm path com.envi.wispr.test`, `pm list instrumentation` showing the runner registered against the target, and the fixture probe `run-as com.envi.wispr sh -c 'test -f cache/enviouswispr-uat.pcm && stat -c %s cache/enviouswispr-uat.pcm'`; only status 0 plus a positive even integer is ready. A failed `run-as` answers `NOT RUN: com.envi.wispr is absent or not debuggable`, never "missing fixture". Any missing member answers `NOT RUN: <which, and how to stage it>`.
- Results for this row: code 0 VERIFIED; code -2 whose first stack line begins exactly `java.lang.AssertionError: Prerequisite:` is NOT RUN; any other -2 is ISSUE; any -3/-4 is ISSUE. Searching later stack lines or matching bare `Prerequisite:` is forbidden. `run_device_test` keeps its own -3/-4 NOT RUN policy. Both go through one `_report_runner_results(results, assumption_policy)` (proposed).
- `stage_uat_fixture(sentence)` renders `-f s16le -ar 16000 -ac 1` without changing `_pcm_from_sentence`'s 48 kHz emulator contract; its half-second tail is `FIXTURE_SAMPLE_RATE * FIXTURE_BYTES_PER_SAMPLE / 2` bytes. It streams with `adb exec-in` into a unique temporary file under `com.envi.wispr/cache`, verifies the remote size equals the local size, then publishes with a fail-if-present hard link (`ln`) and removes the temporary name. It never writes with `cat > cache/enviouswispr-uat.pcm`. If the final path already exists or wins a race, the existing file stays untouched and the function reports that no fixture was staged. The parked-fixture restore branch (the older test-package fixture) is not reused. `adb exec-in` exit zero is transport-only and never proves that remote `cat` succeeded. Only the following regular-file and exact-size read-back admits the temporary file. Every exit after temporary creation removes only that unique temporary name in `finally`.
### 2.2 Non-goals (deferred to #225 with exact fixes)
- The alias oracle (a fixture that decodes to an alias, raw lacking the canonical spelling).
- Model readiness in the preflight. A missing or unloadable model surfaces today through either the S1 readiness assertion or the ASR no-text assertion: ISSUE, never a pass. Exact verified members and the S1 selector's choice are deferred to #225.
- Every other `assumeTrue` in `app/src/androidTest` (enumerated in #225, including `SilenceDetectorDeviceTest` and `MacVocabularyMigrationDeviceTest`, which have no harness door). Bare `am instrument` still false-greens those; they do not count as coverage until #225 lands.
- Staging the custom name: that writes to the founder's dictionary.

## 3. Design
Row:
```kotlin
assertTrue(
    "The real-model fixture is missing at $fixturePath: stage it with wispr_eyes.stage_uat_fixture(sentence); " +
        "a skipped row is not a pass",
    File(fixturePath).isFile,
)
```
then, before binding the engines:
```kotlin
val userTerms = runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term)
assertTrue(
    "Prerequisite: the saved custom name 'Saurabh' is not in this device's dictionary, so the row cannot judge " +
        "saved spellings here (it is the founder's phone's data)",
    userTerms.any { it.spelling == "Saurabh" },
)
val terms = BuiltinVocabulary.withUserTerms(userTerms)
```
`SpeakerAudio.prepare`: the same fixture `assertTrue`.

Harness: `_report_runner_results(results, assumption_policy)` with `assumption_policy` either `"not_run"` (`run_device_test`) or `"regression"` (`run_real_boundary`). For the real-boundary door, code -2 is NOT RUN only when the first stack line begins exactly `java.lang.AssertionError: Prerequisite:`; bare or later-frame `Prerequisite:` text remains ISSUE. `run_real_boundary(timeout=240)`: pure `_real_boundary_preflight(probes)` over the probe results, then `am instrument -w -r -e class com.envi.wispr.VoicePipelineDeviceTest#transcribesThenPolishesWithSavedCustomWords <runner>` with a kill timer, groups read with `_instrumentation_groups`.

## 10. Files
- `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt`: the fixture assertion, the user-term prerequisite, `SpeakerAudio.prepare`.
- `app/src/test/java/com/envi/wispr/VoicePipelineDeviceShapeTest.kt` (proposed): Drift Guard reading `src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt`; it extracts balanced bodies and requires no `assumeTrue` in the real-boundary method or `SpeakerAudio.prepare`, plus the fixture assertion and saved-user-term prerequisite (read from the repository alone, before `BuiltinVocabulary.withUserTerms`).
- `scripts/uat/wispr_eyes.py`: `_report_runner_results`, `_real_boundary_preflight`, `run_real_boundary`, `stage_uat_fixture`, `_pcm16_from_sentence` (proposed).
- `scripts/uat/test_wispr_eyes.py`: rows below.
- `.claude/knowledge/device-testing.md`: the recipe points at `stage_uat_fixture` and `run_real_boundary`, and its fixture-assumption guidance is replaced by `## RULE: report-device-preconditions-honestly`: "A stageable missing precondition fails the bare row. Its harness door may preflight it and report NOT RUN. A remaining assumption is NOT RUN only when the harness explicitly maps its runner result; bare `am instrument` still false-greens it and is not evidence."
- `.claude/rules/android-testing-patterns.md`: replace the stale fixture-assumption rule with `## RULE: a-stageable-device-precondition-fails-loudly`: "A precondition the harness can stage, including a PCM fixture, uses an assertion with an actionable staging message. Keep `assumeTrue` only for a state the harness genuinely cannot reach, and give that row a harness door that reports the assumption as NOT RUN. A bare instrumentation assumption is never a passing receipt." (`.claude/` is gitignored here, so both knowledge and rule edits are made in the primary checkout and named in the PR, not committed.)

## 11. Testing
JVM `VoicePipelineDeviceShapeTest` (Drift Guard): the two row requirements above.
`test_wispr_eyes.py` (pure fixtures and fake adb):
1. preflight: a non-regular path at the fixture name answers NOT RUN; each missing member (target APK, test APK, runner registration, fixture absent, fixture empty, fixture odd length) answers its own NOT RUN; a failed `run-as` answers the not-debuggable NOT RUN; all present answers ready;
2. result mapping: 0 VERIFIED; `Prerequisite:` -2 NOT RUN; other -2 ISSUE; -4 ISSUE for the real-boundary policy and NOT RUN for the ordinary one; also preserve and test: -1 ISSUE under both policies; -3 ISSUE for real-boundary and NOT RUN for ordinary; abnormal final `INSTRUMENTATION_CODE` ISSUE; and no test group ISSUE. The `Prerequisite:` fixture uses the real first-line shape `java.lang.AssertionError: Prerequisite: ...`; a later-frame occurrence remains ISSUE;
3. staging: the render arguments are 16 kHz s16le mono and the tail is 16000 bytes; the bytes sent to `exec-in` equal the rendered file; a size mismatch refuses and removes the temporary file; an existing final file is left untouched (no `ln` over it); a final file that appears between the check and the publish is left untouched. Successful-stage row: the fake filesystem observes, in order, binary `exec-in` to the unique temporary path, exact-size read-back, `ln <temporary> cache/enviouswispr-uat.pcm`, exact-size read-back from the final path, and temporary removal; the function reports staged. Removing the `ln`, final read-back, or temporary cleanup makes this row red.
Receipts (each makes one named row red): replace either fixture assertion with `assumeTrue`; remove the saved-user-term assertion; merge built-ins before the check; drop each preflight member independently; change any PCM argument; alter the bytes sent to `exec-in`; write the final path directly; map real-boundary -4 to NOT RUN.

### 11.1 Hardware UAT
As in the preface: emulator (a) to (c); phone NOT RUN (founder instruction).

## 12-15
Blast radius: one instrumented row, its speaker source, and harness calls; no product path. Ship: rows green, receipts red, Codex ALL-CLEAR, emulator (a) to (c). Related: audit REF-05; #225 (deferred findings).
