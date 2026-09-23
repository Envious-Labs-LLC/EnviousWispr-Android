# #236 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #236 branch at its final commit, driven only through `scripts/uat/wispr_eyes.py` (`dictate_emulator`).

| Check | Result |
|---|---|
| Two back-to-back Gmail dictations | VERIFIED: both takes by COMMIT, the app's outcome VERIFIED, the editor's whole text equal to one sentence and then to both |
| The warm-up at connect | The log shows `Polish service connected` at once, and the take starts, stops and lands its words; the emulator's local polish model fails to load (`S1 unavailable; deterministic fallback active`), as on main |
| A warm-up that never returns | NOT RUN on the device: the harness cannot stall one binder call. `WarmUpOffMainTest` row 1 stages it with a held warm-up |
| Setup's warm-up | NOT RUN: setup is complete on the emulator. `WarmUpOffMainTest` shape row covers it |

Empty takes, measured on both builds with the same script (`dictate_emulator` six times in fresh Gmail drafts): the branch gave 4 empty takes in 12, main at `f64254c` gave 2 in 12. In each empty take the recorder captured the full stop-to-stop audio and the speech engine returned no characters, before polish is reached. Main shows the same behaviour, so it is an emulator audio-injection flake and not this change. Host load average was 13, below the 50 that `device-testing.md` names.
