# Issue #260: remove Android-version checks the app can never fail (2026-09-23)

GitHub issue: `#260`. Tier: SMALL (dead branches; no behaviour on any supported phone). Status: APPROVED after the coverage round (`260-cov`: all three findings adopted verbatim).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the emulator (API 36): a Gmail dictation by COMMIT (start and stop haptics run through `vibrate`), the Appearance page's wallpaper-colours row, the quick-settings tile's subtitle, and Permissions' notification row.

## Preface: User Rubric

User Rubric: nothing a person on Android 13 or later sees changes; code that could only run on an older phone the app no longer installs on is gone.

---

## 0. TL;DR

`minSdk` is 33 (`app/build.gradle.kts`, since #141). REF-09 of the second 2026-09-23 audit names one dead branch, the pre-API-31 `Vibrator` fallback in `DictationSessionService.vibrate`. The class is wider: every `Build.VERSION.SDK_INT` comparison against a level at or below 33 is decided at compile time on every phone the app installs on. Remove all seven, keep the one live check, and add a source guard that refuses a new one.

## 1. The class (every `SDK_INT` comparison under `app/src/main/java`)

This sweep applies to the shipping `:app` source and variants. `:accelerator-benchmark` is a separate experimental app with a lower floor; `:llama-android` is a library.

Dead at `minSdk` 33, removed:
1. `ui/DictationSessionService.vibrate`: `SDK_INT >= S` picks `VibratorManager`, else the deprecated `Vibrator` (removed). Keep: `getSystemService(VibratorManager::class.java)?.defaultVibrator`, the `runCatching`, and the cue's haptic-setting gate. The stale comment ("the oldest supported phone") goes; `Vibrator` and `Build` imports go if unused.
2. `ui/ReadinessViewModel.refreshPermissions`: `SDK_INT < 33 ||` before the `POST_NOTIFICATIONS` check.
3. `ui/ReadinessViewModel` (the snapshot builder): `SDK_INT < TIRAMISU ||` before the same check.
4. `ui/SettingsActivity` `onRequestNotifications`: `if (SDK_INT >= 33) request(...) else refreshReadiness()` becomes the request alone.
5. `ui/theme/EnviousWisprTheme`: `dynamicColor && SDK_INT >= S` becomes `dynamicColor`.
6. `ui/SettingsPages` Appearance: `wallpaperColoursSupported` (removed) (always true) goes with the stale "`minSdk` is 30" comment. Remove both unreachable else strings; retain both API 33+ strings; the switch is always enabled and reads `preferences.dynamicColorEnabled`.
7. `shortcuts/DictationTileService`: `if (SDK_INT >= Q)` around the subtitle becomes the assignment alone.

Live, kept: `shortcuts/DictationTileService.onClick`'s `SDK_INT >= UPSIDE_DOWN_CAKE` (34) choice of `startActivityAndCollapse(PendingIntent)`. `paste/AccessibilityInsertionRunner`'s `api = SDK_INT` is a logged value, not a comparison.

## 2. Tests

1. Guard `MinSdkBranchesTest`: reads `minSdk` from `app/build.gradle.kts`; finds every `SDK_INT` comparison (`<`, `<=`, `>`, `>=`, `==`) under `app/src/main/java` against a number or a `VERSION_CODES` name (mapped: Q 29, R 30, S 31, S_V2 32, TIRAMISU 33, UPSIDE_DOWN_CAKE 34, VANILLA_ICE_CREAM 35, BAKLAVA 36; an unknown name fails the test by name); fails naming each comparison that `minSdk` decides. Reject `>= N` and `< N` when `N <= minSdk`; reject `> N`, `<= N`, `== N` and `!= N` only when `N < minSdk`. Cover the equality boundary with positive and negative guard fixtures. The planned `>= S` mutation must fail. MUTATION: put `SDK_INT >= Build.VERSION_CODES.S` back in `EnviousWisprTheme`.
2. Existing Appearance, readiness, haptic and tile tests stay green; any that pinned the removed Android 12 sentence is updated to the one remaining sentence.

## 3. Blast radius
Seven branches no supported phone can take. Rollback: revert the squash commit.

## 4. Ship criteria
- [ ] Row 1 green, its mutation red; the suite green.
- [ ] Emulator: dictation haptics, the Appearance row, the tile subtitle and the notification row as before.
- [ ] `.claude/rules/code-gotchas.md` RULE `vibratormanager-is-api-31-and-minsdk-is-30` (anchor kept verbatim): its body names the guard test instead of the two now-removed branches. It lives in the primary checkout outside git and is updated there after the merge.

## 5. Related
#141 (`minSdk` 33), REF-09 of the second 2026-09-23 audit.
