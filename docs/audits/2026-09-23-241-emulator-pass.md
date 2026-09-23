# #241 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #241 branch, driven through `scripts/uat/wispr_eyes.py` (`dictate_emulator`).

| Check | Result |
|---|---|
| A Gmail dictation | VERIFIED: route COMMIT, the app's outcome VERIFIED, the editor's whole text equal to the sentence (third run, take 1) |
| Empty takes | Four of six takes across three runs came back empty (`textChars=0` on a full recording, the recognizer loading in up to 8.7 s after the full test suite had just run at host load 12). This is the emulator audio-injection flake measured on main during #236 (2 of 12 there), not this change: the warm hold never starts on the emulator |
| The warm hold | NOT RUN: the emulator has no Bluetooth earbuds, so no hold starts (no `route hold` line in the log) and a failing `AudioTrack.write` cannot be staged. `AudioLimbCloseTest` and `SilenceWriterTest` drive it on the JVM |

The founder's phone pass with earbuds is queued with the earlier audit items.
