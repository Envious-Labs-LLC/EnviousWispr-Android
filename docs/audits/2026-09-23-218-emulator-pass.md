# #218 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #218 branch, driven only through `scripts/uat/wispr_eyes.py`.

| Check (plan §11.1) | Result |
|---|---|
| Startup leaves "Preparing EnviousWispr" | VERIFIED: History opened with its rows on launch |
| Every tab and drawer page (`scan(toggle=True)`) | VERIFIED: 13 screens, 26 controls read. Flipped and put back: the four Transcription switches, Use Galaxy colours, the three Clipboard switches |
| AI Polish Tone, Structure and Context chips | TOOL LIMIT: these are pick-one chips; the walker treats a chosen chip that stays clickable as a switch, so its "flip back" press cannot un-pick it and it reports "pressed but still on". The first press of each chip did move the pick. The walker's journal left Structure on Prose; it was put back to Lists by name and read back from the preference file (`lists`, `general`, `semi-formal`). The app is unchanged here (#218 touches no Compose chip code) |
| Microphone page switches | Page VERIFIED rendered with its values (Auto on, Keep earbuds ready on, Phone off, Show Bluetooth tips on). Flips NOT RUN: the walker found two nodes named "Microphone" (the page and the app bar's readiness chip) and would not guess. These setters go through the same `changeSetting` path the Clipboard flips verified |
| Onboarding | NOT RUN: this emulator profile has completed onboarding, and "Continue guided setup" only clears a dismissal; no app-data wipe was done for it |
| One dictation into Gmail, then History | VERIFIED: route COMMIT, the editor's whole text equal to the sentence; History's top row is the new take (Sep 23, 2026 12:22 AM) |
| AI Polish tab | VERIFIED: Off / This phone / Cloud, the S1-mini tile and the Writing style card render |
| Empty Dictionary | VERIFIED, observed: the Dictionary is empty ("No custom terms yet") and startup still left Preparing |
| Dictionary search filters | NOT RUN: the emulator's Dictionary is empty, so there is nothing to filter; Add is not pressed from this harness |
| A setting persists across an app restart | VERIFIED: Smart insertion on to off, app force-stopped and reopened, still off; restored to on |
| Restore | `restore()` switched Smart insertion back on; the walker's five wrong chip debts were cleared by hand after Lists was put back, and `restore()` then answered "nothing was changed" |

NOT RUN rows are not counted as passes. The founder's phone pass is queued with the earlier audit items.
