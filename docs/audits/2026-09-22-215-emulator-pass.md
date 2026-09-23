# #215 emulator pass — 2026-09-22

Device: `emulator-5554` (API 36), debug app and test APKs from this branch, driven through `scripts/uat/wispr_eyes.py`.

- No fixture: `run_real_boundary()` answered `NOT RUN: no usable fixture at com.envi.wispr/cache/enviouswispr-uat.pcm ...`. A bare `am instrument` of `VoicePipelineDeviceTest#transcribesThenPolishesWithSavedCustomWords` reported `FAILURES!!! Tests run: 1, Failures: 1` with `AssertionError: The real-model fixture is missing ...`: the row no longer reads green where nobody staged its fixture.
- `stage_uat_fixture('Saurabh will send the EnviousWispr deck tomorrow.')` staged 104514 bytes (3 s) at `com.envi.wispr/cache/enviouswispr-uat.pcm`; no temporary name was left. Staging again answered `a fixture is already at ... left untouched and nothing was staged`.
- With the fixture staged, `run_real_boundary()` answered `NOT RUN: ... Prerequisite: the saved custom name 'Saurabh' is not in this device's dictionary ...`: the emulator holds no founder dictionary, and the row says so instead of failing or passing.
- The staged fixture was removed and reads absent; `restore()` answered `nothing was changed`.

Found by this pass and fixed on the branch: the first staging design published with a hard link, which SELinux denies to `runas_app` on app data (`avc: denied { link } ... permissive=0`); the harness then misreported it as a race. The publish is now a no-overwrite create plus copy, rerun here at `cfd197b`, `04645da` and `4353d0b`.

NOT RUN: the real-model row end to end, which needs the founder's saved name on his phone; queued with the S26 pass for #115, #161, #212, #213 and #214 by the founder's 2026-09-21 instruction.
