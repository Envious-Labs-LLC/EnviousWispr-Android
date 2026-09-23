# #234 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #234 branch, driven only through `scripts/uat/wispr_eyes.py` (`dictate_emulator`, `kill_process`).

| Check | Result |
|---|---|
| Healthy dictation into Gmail | VERIFIED: route COMMIT, the app's outcome line VERIFIED, the editor's whole text equal to the sentence |
| Polish process killed during the take (`kill_process("com.envi.wispr:polish")` once the take was running) | VERIFIED: the log shows `Polish service disconnected`, then `Polish service reconnected; this take already lost it and keeps the deterministic text`, then `Polish fell back on the session owner: reason=SERVICE_DIED` and `Polish result received (Deterministic fallback, 0ms, chars=45)`; the words landed by COMMIT, outcome VERIFIED, the editor's whole text equal to the sentence |
| Restore | `restore()` answered "nothing was changed" |

Limits, stated rather than counted as passes: the kill landed while the take was RECORDING, where today's build also kept the words (it polished on the reconnected service); the two paths that used to lose the take (a refused polish bind, and polish dying after stop but before speech answers) are proven by `PolishFailsOpenTest` rows 1, 3 and 6e, not on the emulator, because the harness cannot refuse a bind and the speech-to-polish window is too short to aim a kill into. The founder's phone pass is queued with the earlier audit items.
