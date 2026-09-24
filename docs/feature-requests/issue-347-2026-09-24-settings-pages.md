# Issue #347: unrelated settings pages get their own files (REF-05, regrade 7) (2026-09-24)

GitHub issue: `#347`. Tier: SMALL (a move; no behaviour change). Status: built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. Each moved composable's text and body is byte-identical, and the pages are reached the same way.

## 0. TL;DR

`ui/SettingsPages.kt` (762 lines) held unrelated pages. Moved, with their private helpers, into their own files in the same package:

- `MicrophonePage` with `rememberConnectedInputs` and `InputDeviceRow` go to `ui/MicrophonePage.kt`.
- `PrivacyPage` goes to `ui/PrivacyPage.kt`.
- `StoragePage` with `StorageRow` go to `ui/StoragePage.kt`.
- `LicensesPage` goes to `ui/LicensesPage.kt`.

Each file keeps only the imports it uses, including the Compose `getValue`/`setValue` operator imports that its `by` properties need. `SettingsPages.kt` keeps What's new, Appearance, Sounds, Clipboard and Permissions (421 lines). The moved declarations are deleted from it (GR-MIGRATION-COMPLETE).

## Results (2026-09-24)

- `TriggerNameTest` reads the four new files as user-facing text, so its "right button" guard still covers every page.
- `AutoPasteWiringTest` still reads the Permissions page in `SettingsPages.kt`.
- The app builds. Suite 1390, 0 failures. The visibility check is clean.
- Codex code review round 1: all nine moved declarations, with their KDoc, and every declaration left behind are byte-identical to origin/main. The only finding was the line count above, now corrected.
