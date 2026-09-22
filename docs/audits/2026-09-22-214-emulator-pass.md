# #214 emulator pass — 2026-09-22

Device: `emulator-5554`, debug build from `2c7daeb`, driven through `scripts/uat/wispr_eyes.py`, Gmail compose body.

- Two back-to-back ordinary takes (`the quarterly marker sentence lands tomorrow`): each `VERIFIED` ended by a stop request, 45 characters transcribed, `route=COMMIT written=true outcome=VERIFIED` into `com.google.android.gm`, and the editor's whole text equal to the expectation (46 chars, the trailing space stored by Gmail as U+00A0).
- The malformed blank polish answer cannot be produced by the correct engine; it is covered by `DictationSessionCoordinatorTest` rows `aBlankPolishedAnswerPublishesTheExactCleanedVocabularyText` and `aNonblankFillerRecoveryUnderOffStillInsertsWhatWasSaid`.

NOT RUN: the S26 pass (two back-to-back takes into a real third-party editor), queued with #115, #161, #212 and #213 by the founder's 2026-09-21 instruction.
