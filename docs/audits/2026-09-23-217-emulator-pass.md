# #217 emulator pass — 2026-09-23

Device: `emulator-5554` (API 36), debug build of this branch after code review round 4 (ALL-CLEAR), driven through `scripts/uat/wispr_eyes.py`.

- Two back-to-back takes of `the quarterly marker sentence lands tomorrow` into Gmail compose: each `VERIFIED` ended by a stop request, 45 characters transcribed, `route=COMMIT written=true evidence=SURROUNDING outcome=VERIFIED attempts=1` into `com.google.android.gm`, and the editor's whole text equal to the expectation (46 chars, the trailing space stored by Gmail as U+00A0). The insertion path is the new `EditorTargetTracker` pin and the `AccessibilityInsertionRunner` attempt.
- `debug_insert("The quarterly report is ready.")` into a fresh compose field: `pin=PINNED handoff=SCHEDULED`, `route=COMMIT written=true outcome=VERIFIED`, the editor's whole text equal (31 chars).
- The bubble (`AccessibilityBubbleHost`): on a focused compose field the overlay window is seen by the user at 168 x 168 and its label is on screen; on the launcher neither; back on the compose field both again. The log shows the host's discovery (`Bubble discovery found=true windows=4`) and the tracker remembering the editor.
- `restore()` answered `nothing was changed`.

A first bubble check in the script read "not seen" three times: it joined the characters of `look()`'s single string, so it could never match. The overlay window reader showed the bubble up at the same moment; the corrected check above is the evidence.

NOT RUN: the founder's phone pass, queued with #115, #161 and #212 to #216 by his 2026-09-21 instruction.
