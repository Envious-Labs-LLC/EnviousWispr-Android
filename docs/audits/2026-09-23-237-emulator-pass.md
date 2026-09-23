# #237 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #237 branch, driven only through `scripts/uat/wispr_eyes.py` (`dictate_emulator`, `kill_process`).

| Check | Result |
|---|---|
| Two back-to-back Gmail dictations | VERIFIED: both takes by COMMIT, the app's outcome VERIFIED, the editor's whole text equal to one sentence and then to both |
| Polish process killed once the take was running (`kill_process("com.envi.wispr:polish")`) | VERIFIED: the log shows `Polish service disconnected`, then `Polish service reconnected; this take already lost it and keeps the deterministic text`, then `Polish fell back on the session owner: reason=SERVICE_DIED` and `Polish result received (Deterministic fallback, 0ms, chars=45)`; the words landed by COMMIT and the editor's whole text equals the sentence. The same lines, in the same order, as the #234 pass |
| Restore | `restore()` answered "nothing was changed" |

The watchdog, invalid answers, a cancel before the engine registered, and the lock discipline are not stageable on the device; `TakePolishControllerTest` covers them.
