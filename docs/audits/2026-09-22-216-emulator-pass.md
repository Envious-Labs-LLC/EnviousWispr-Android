# #216 emulator pass — 2026-09-22

Device: `emulator-5554` (API 36), debug build of this branch after code review round 2 (ALL-CLEAR), driven through `scripts/uat/wispr_eyes.py`; History read from a copy of the app's own database (`transcripts`).

- Two back-to-back takes of `the quarterly marker sentence lands tomorrow` into Gmail compose: each `VERIFIED` ended by a stop request, 45 characters transcribed, `route=COMMIT written=true outcome=VERIFIED` into `com.google.android.gm`, and the editor's whole text equal to the expectation (46 chars, the trailing space stored by Gmail as U+00A0).
- Cancel while recording: `Take terminal: CANCELLED_RECORDING (cancelled)`; the table held 44 rows before and 44 after, so the draft inserted at live was discarded (its id, 71, is gone).
- Cancel after a stop: the harness sends each command 1.5 s apart, so the cancel reached a take whose words were already published: `Take terminal: COMPLETED (completed)`, one row (id 72, `completed`, `committed`). That is the owner's "too late" branch and it behaved as designed.

NOT RUN on the device: a cancel that lands while the words are still being processed. The harness cannot send a cancel inside that window; the JVM row `cancelWhileProcessingBeatsLatePolish` covers it. The founder's phone pass is queued with #115, #161 and #212 to #215 by his 2026-09-21 instruction.
