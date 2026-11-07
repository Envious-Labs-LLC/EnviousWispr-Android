# Recording recovery acceptance

Use this focused, operator-assisted check on an owned disposable emulator. It reuses
Wispr Eyes and the existing native recorder/editor fixture. It never installs an app,
clears app data, deletes History, or performs fault injection on a physical phone.

## Prepare once

- Install the paired debug app and test APK from the reviewed source. Record their
  source and SHA-256 values separately from the checkout used to run the script.
- Complete normal microphone, notification and auto-paste permission setup. Confirm
  the app is idle, its speech model is ready, and auto-stop on silence is off.
  The existing native driver rebinds auto-paste after instrumentation restarts;
  the wrapper leaves that readiness step with its existing owner.
- Keep the emulator's host microphone off and recording chimes off. Use synthetic
  speech through the existing injection path; no speaker playback is needed.
- Finish any earlier recovery work through the normal app before starting. Use one
  operator and emulator, with no competing device or build activity.
- Use Python 3.12 or later and a new evidence directory outside the checkout.
  The evidence includes emulator databases and audio; keep that directory private.

The recorder stays open for **20 seconds after the test phrase** before the planned
interruption. This creates breathing room within the existing native driver's
60-second audio-completion bound. The delay stages the input; it never proves success.
One second of silent input precedes the unchanged phrase so the virtual stream can
settle before its opening word. Both the offered audio and actual captured prefix
are retained for checking any mismatch; the prefix is captured before interruption,
not presented as the final complete audio file.

## Run

From the repository root, replace `/absolute/path/new-case` with a fresh directory:

```sh
python3 scripts/uat/recording_recovery.py interrupt --serial emulator-5554 --out /absolute/path/new-case
```

The native runner is intentionally aborted by the verified main-process kill. Its
aborted report is retained and is **not** a passed test. The script opens the real
app once afterward. A temporary interrupted draft with no finalized text is pending.

Wait until History shows the recovered words, then check the actual committed row:

```sh
python3 scripts/uat/recording_recovery.py check --serial emulator-5554 --out /absolute/path/new-case
python3 scripts/uat/recording_recovery.py reopen --serial emulator-5554 --out /absolute/path/new-case
python3 scripts/uat/recording_recovery.py next --serial emulator-5554 --out /absolute/path/new-case
```

`check` returns exit 2 while the row is unfinished. Observe the actual completion
and run `check` again without another app launch. Wrong words, missing provenance,
duplicate rows, editor changes or a failed next recording fail the check.

Only the final `result.json` with `result: PASS` means all named steps completed.
Each run is tied to its newly admitted take ID, so older matching History text
cannot satisfy it. Database snapshots, injected audio and driver reports are retained.
The original editor must remain empty until the separate normal recording.

Record the screen through both actual recordings. Run the existing Android
`screenrecord --time-limit 180` command as a separate foreground command in another
terminal, using a unique device filename; wait for it to exit before pulling its
video. The native driver restores the harness journal, so do not wrap it in an
outer journal-managed screen-recording context. Inspect the resulting video too.

## Interpret and clean up

- Require literal recovered words and the same one stored row after the second restart.
- Require the native next-recording test to check the actual editor's whole text.
- Preserve failed input and output. An emulator audio-feed failure is not an app pass;
  classify it as unstaged only with actual captured-audio evidence.
- Inspect protected storage after the completed run: ordinary cleanup should leave
  its ledger and stable lock files, without this take's audio/context files.
- Restore any temporary settings through their existing owner. Remove only owned
  synthetic files after retaining the evidence. Keep the test History rows unless
  their removal is explicitly part of a separate test.

This check qualifies the named emulator flow. Physical microphone acoustics, phone
insertion, Cancel/Delete and full-batch release acceptance keep their separate evidence.

## First pilot, 2026-11-06

The reviewed debug application code matching internal build 312 captured 26.368
seconds before recovery. The exact phrase recovered once into History, a second
verified main restart left every stored field unchanged, and the original editor
remained empty. The following ordinary recorder/editor test passed. This successful
manual pilot established the steps above before the repeatable runner was added.

The final saved runner subsequently completed all four documented steps in a clean
run, with the earlier matching History entries still present. An earlier unpadded
repeat produced a different opening word and correctly failed its literal check;
that failed capture was retained. The final fixture adds silent pre-roll and retains
the actual captured prefix. No recognition model, application code or word oracle
was changed to obtain the successful run.

## Starting a new recording during recovery

Use the same owned emulator and reviewed paired APKs. This separate native row
stages a protected recording, observes its real speech work, then starts a new
recording while Settings stays visible. Its existing audio driver waits for that
priority check before feeding the new phrase. No human timing window is needed.

From the repository root, retain the entire command output in a private run folder:

```sh
WISPR_SERIAL=emulator-5554 python3 - <<'PY'
import sys
sys.path.insert(0, "scripts/uat")
import wispr_eyes as eyes
try:
    print(eyes.device(), eyes.ready(), eyes.bound())
    print(eyes.stage_uat_fixture("the quarterly marker sentence lands tomorrow"))
    print(eyes.look())
    print(eyes.stop_agent())
    for line in eyes.run_device_test(
        "com.envi.wispr.VoicePipelineDeviceTest#recordingDuringRecoveryKeepsOnlyTheNewWordsAndReleasesTheModelPermit",
        "the quarterly marker sentence lands tomorrow",
    ):
        print(line)
finally:
    print(eyes.restore())
    print(eyes.stop_agent())
PY
```

Require the named native `VERIFIED` verdict, with no issue or staging failure.
The matching recovery request must retire as cancelled after Record is requested.
The fixture confirms retirement at the new take's LISTENING stage, before delivering
its audio or issuing Stop. It then checks the new literal words once in History and on the clipboard,
no recovered old row, and the actual app's model permit released. Reopening checks
the durable refusal of a second replay; it does not infer completion from a quiet
period. This in-app recording has no target editor. Third-party insertion keeps
its separate qualification. Repeated long input supplies scheduling room only;
it does not qualify ten-minute word accuracy, microphone capacity or acoustics.
