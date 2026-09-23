# #235 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build and test APK of the #235 branch. Takes through `scripts/uat/wispr_eyes.py`; instrumentation through `adb shell am instrument`, never `connectedDebugAndroidTest`.

| Check | Result |
|---|---|
| Two back-to-back Gmail dictations | VERIFIED: both takes by COMMIT, the app's outcome VERIFIED, the editor's whole text equal to both sentences; the log shows `Auto-insert handed to accessibility target tracker (handoff=SCHEDULED)`, so the saves answered inside the bound |
| First take right after the reinstall | The speech engine returned nothing for the first take after the fresh install (the model loads cold after an install); the save path was not reached. The rerun of the pair passed as above. Not counted as a pass for anything |
| `TranscriptRouteDaoTest` on Room (the real SQL of the neutral row, the promotion, the outcome, the copy reconciliation and the recovery) | VERIFIED: 4 of 4 |
| `TranscriptOutcomePersistenceTest`, `TranscriptRepositoryTest` | VERIFIED |
| A stuck History save on the device | NOT RUN: the harness cannot stall Room on a device; `BoundedHistorySaveTest` stages it with a held write |

The founder's phone pass is queued with the earlier audit items.
