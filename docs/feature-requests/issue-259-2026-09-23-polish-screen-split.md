# Issue #259: split the Polish screen by its existing rungs (2026-09-23)

GitHub issue: `#259`. Tier: SMALL (a file move; no behaviour). Status: APPROVED after the coverage round (`259-cov`: all four findings adopted verbatim).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the emulator, open AI Polish and read the ladder (the three mode buttons, the S1-mini card and its controls, a cloud provider's tiles); a Gmail dictation by COMMIT.

## Preface: User Rubric

User Rubric: the AI Polish tab looks and behaves exactly as before.

---

## 0. TL;DR

`ui/PolishScreen.kt` is 892 lines holding the root composable and every rung's presentation (REF-08 of the second 2026-09-23 audit). Move the cloud rungs into `ui/PolishCloudRungs.kt` and the on-phone model cards into `ui/PolishLocalControls.kt`; the root composable, its one-write state and the parts only it uses stay in `PolishScreen.kt`. Code moves unchanged; only visibility widens where a call now crosses files.

## 1. Placement (from a call map of the file)

- `PolishCloudRungs.kt`: `CloudRungs`, `ModelRung`, `GetKeyLink`, `keyPlaceholder`, `ProviderTileButton`, `CheckGlyph`. Only `CloudRungs` is called from outside the file (by `PolishScreen`), so it becomes `internal`; the rest stay `private`.
- `PolishLocalControls.kt`: `S1Card`, `S1ControlCard`, `ControlAxis`, `DevelopmentModelCard`. `S1Card`, `S1ControlCard` and `DevelopmentModelCard` become `internal` (called by `PolishScreen`); `ControlAxis` stays `private`.
- `PolishScreen.kt` keeps `PolishScreen`, `WriteKind`, `QuietCard`, `RungOneButton` and the three mode glyphs. `WriteKind` becomes `internal` because `CloudRungs` takes it. `RungHeader` and `ErrorLine` are used by all three files, so they stay here and become `internal`: the ladder's shared parts, one owner.
- Each moved function keeps its body, KDoc and comments byte for byte. Imports are split to what each file uses. The root receives saved settings once and passes the same state to the moved controls; add no second saved-setting projection.
- Update `.claude/rules/kotlin-patterns.md`'s example to the paired `apiKey` and `checkSequence` state in `PolishCloudRungs.kt`. The rules folder lives in the primary checkout outside git, so this lands there after the merge.

## 2. Tests

1. A layout guard, `PolishScreenLayoutTest`, reads the three files: each function above is declared in exactly its file and nowhere else; `PolishScreen.kt` holds no `CloudRungs`, `S1Card`, `S1ControlCard` or `DevelopmentModelCard` declaration and is under 450 lines. MUTATION: move `DevelopmentModelCard` back into `PolishScreen.kt`.
2. Read `PolishLocalControls.kt`; slice from `internal fun DevelopmentModelCard()` to that function's closing brace. Keep the assertions.
3. Every existing Polish test stays green unchanged (`PolishLadderTest`, `PolishScreenProviderTilesTest`, `PolishStatusChip` and friends): they test behaviour, not the file.
4. Compare moved bodies, KDoc and comments against main at fbd4b14 byte for byte; allow only the specified visibility token and import changes (a scratch check, reported in the as-built; not a committed test).

## 3. Blast radius
The AI Polish tab's source layout only. Rollback: revert the squash commit.

## 4. Ship criteria
- [x] Rows 1 to 3 green; row 1's mutation red; row 4's diff empty.
- [x] Emulator: the AI Polish tab reads as before; a Gmail dictation by COMMIT.

## 5. Related
REF-08 of the second 2026-09-23 audit.

## 6. As built

- `PolishScreen.kt` 257 lines (was 892): `WriteKind` (now `internal`), `PolishScreen`, `RungHeader` and `ErrorLine` (now `internal`), `QuietCard`, `RungOneButton`, the three mode glyphs. `PolishCloudRungs.kt` 451 lines, `PolishLocalControls.kt` 235 lines (`wc -l`), as §1 placed them. Code review round 1: the split kept duplicate `remember` and `mutableStateOf` imports and an unused `key` import; each file now imports each name once, and only what it calls. The counts above are after that cleanup. The move was scripted from the file's top-level declarations, each carried with its KDoc and comments; imports were split to what each file names.
- Row 4: all 18 moved or kept functions and `WriteKind` compared against main at fbd4b14: identical apart from the six widened `private` to `internal` tokens and the imports.
- Tests: `PolishScreenLayoutTest` (new, row 1; its mutation, `DevelopmentModelCard` moved back into `PolishScreen.kt` with the imports it needs, is RED); `DevelopmentPolishModelFactsTest` reads `PolishLocalControls.kt` and slices `internal fun DevelopmentModelCard()` to its closing brace. Full suite 1264, 0 failures; `check-visibility.py` clean.
- Emulator: the AI Polish tab showed the three rung-one buttons, the S1-mini card (Ready, Remove) and its Tone, Structure and Context controls; a Gmail dictation by COMMIT landed exactly. NOT RUN: the cloud provider tiles on the device (tapping Cloud writes the polish mode on the emulator); `PolishScreenProviderTilesTest` covers them.
- The `.claude/rules/kotlin-patterns.md` example update lands in the primary checkout after the merge (§1).
