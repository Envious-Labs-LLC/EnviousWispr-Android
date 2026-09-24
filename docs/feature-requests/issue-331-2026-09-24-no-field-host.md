# Issue #331: the no-field device row stages its own no-field host and asserts (REF-07, regrade 6) (2026-09-24)

GitHub issue: `#331`. Tier: SMALL. Status: built 2026-09-24; coverage and review round 1 (`331-cov`) adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code (device test rig only; no production code).

**PAR rows closed:** none.

**Hardware UAT:** Y. The changed row runs on the emulator through `am instrument`.

## Preface: User Rubric

Persona: the founder. The row proves that a dictation from the tile, the app's microphone or the side button outside any field leaves nothing in his notification shade. Today the row SKIPS whenever another app's editor is still focused behind the 1x1 launcher. So a device run can end with that outcome untested (`testing-philosophy.md` RULE: the-heart-crosses-a-real-boundary-at-least-once).

## 0. TL;DR

- `PasteTargetActivity` gains `EXTRA_NO_FIELD`: a full-screen page with one line of plain text and no editable node. Its ready receipt carries this run's token and is written only at this window's own focus. The host lives in the test package (`com.envi.wispr.test`). The paste service therefore treats it as a third-party page, which it may scan and finds nothing in. Opacity does not remove windows from the service's scan. What stages the state is focus: `EditorTargetTracker`'s scan pins only a FOCUSED editable node, and while the host holds window focus no other app's editor does. After the take the row reads the system's own `input_focus` log and requires that no window but the host took focus (round 1, measured: see Results).
- `VoicePipelineDeviceTest.startRig(twoFields, noField)` launches it and waits on the token, as it already does for the editor host.
- `aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure` stages the host before the take. The post-take `assumeTrue(handoff == "NO_PINNED_TARGET")` becomes `assertEquals`, so a no-field run always gives pass or fail. The notification verdict is unchanged.

## 1. Evidence

- Build: `:app:assembleDebugAndroidTest`.
- Device: the row on emulator-5554 through `am instrument`, repeated.

## Results (2026-09-24)

- Coverage and review round 1 (`331-cov`), adopted with a declared modification. The finding: the first-focus receipt could go stale. Its drop-in, a sticky receipt on ANY focus loss, failed on the emulator. The 1x1 launcher's start and stop each move input focus to NO window for about 70 ms, then back to the host (`input_focus`: "Requesting to set focus to null window", then "Focus entering ... PasteTargetActivity"). That pins nothing, but it wrote the receipt. The row instead reads the system's `input_focus` "Focus entering" lines after clearing the events buffer post-staging, and requires every one to name the host. This is exact where the host's own callback cannot tell a null focus from another window's. The plan and comments name the scan's focus filter, not opacity, as what excludes other windows. The other three `startRig` calls keep the editor mode.
- Device (emulator-5554, `run_device_test`, harness-fed audio): the row VERIFIED three times in a row. m1, the row staging the EDITOR host instead, FAILED rather than skipped: `expected:<NO_PINNED_TARGET> but was:<none>`. That run's handoff line was absent, which also fits a take recorded silent by the emulator microphone race (#273), so m1 proves the row can no longer skip, not which staging cause failed it.
- Before the device run, the emulator's auto-paste grant had been cleared by a raw `am force-stop` during #288 UAT (`device-testing.md`: never raw force-stop after an install). It was restored through the app's Permissions page and Android's accessibility grant, so `restore()` owes nothing for it.
- Build: `:app:assembleDebugAndroidTest`; no production code changed.
