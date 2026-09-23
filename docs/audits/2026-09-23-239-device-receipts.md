# #239 device receipts, 2026-09-23

Emulator `emulator-5554`, each mutation built, installed and run through `scripts/uat/wispr_eyes.py` `run_device_test` (external audio, `am instrument`); the source restored after each. Neither run reported a cleanup suppression, so the take was proven ended both times.

- R1 capture starts with auto-stop off -> ISSUE: VoicePipelineDeviceTest.aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce FAILED: java.lang.AssertionError: silence did not end this take; rows since the start: id=126 status=draft insertion=pending
- R2 a manual stop in place of silence -> ISSUE: VoicePipelineDeviceTest.aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce FAILED: java.lang.AssertionError: the take ended, but the capture process never logged a silence ending: [09-23 05:02:40.031 I/AudioCapture(13616): Stopped by a stop request. 224256 bytes (7.0s) -> /data/user/0/com.envi.wispr/cache/recording-take-05026aac-73df-4db0-8897-e0ef08359776.pcm]

Green on the correct build after the fixes: `aSilenceStoppedTakeLandsInTheFocusedEditorExactlyOnce` passed. Before the round 1 fixes, `aSideButtonTakeLandsInTheFocusedEditorExactlyOnce` and `aTakeStartedInAAndFocusMovedToBInsertsNowhereAndKeepsTheWordsOnTheClipboard` passed; `aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure` was NOT RUN, skipped by its own precondition.
