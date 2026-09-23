# #252 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #252 branch, driven through `scripts/uat/wispr_eyes.py` (`dictate_emulator`).

| Check | Result |
|---|---|
| Two back-to-back Gmail dictations | VERIFIED: both takes by COMMIT, the app's outcome VERIFIED, the editor's whole text equal to one sentence and then to both |
| A throwing detector, restorer, cleanup or defect sink | NOT RUN on the device: none can be staged there. `TakePolishControllerTest` rows 1a to 1e, 4 and 4b and `PolishFailsOpenTest` rows 7 and 8 drive each |
