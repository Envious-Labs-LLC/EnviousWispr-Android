# Issue #261: narrow the auto-paste service's own members (2026-09-23)

GitHub issue: `#261`. Tier: SMALL (visibility only; no behaviour). Status: APPROVED after the coverage round (`261-cov`: the test-design finding adopted, with the module tag matched at the end of the name instead of any `$`).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. On the emulator: auto-paste binds, and the debug window dump (`wispr_eyes` fast eye, which reads `windowTreeXml` through `DebugDumpReceiver`) still answers.

## Preface: User Rubric

User Rubric: N/A — internal visibility only; no screen, text, timing or delivery path changes.

---

## 0. TL;DR

REF-10 of `docs/audits/2026-09-23b-senior-audit.json` names `PasteAccessibilityService.windowTreeXml`: a debug-only reader of every window's accessibility tree, left at Kotlin's public default. The same default holds for every member the service declares for the app's own use. Mark all six `internal`, and add a test that reads visibility from the compiled class, so a new public member fails.

Consolidation: none. This narrows six declarations in one file and adds one guard; it merges no owners.

Prior context: #181 (PR #184) added `windowTreeXml` for the harness fast eye; it lives on the service because `instance` is private, and `DebugDumpReceiver` is gated by `android.permission.DUMP`.

## 1. The class (grounded 2026-09-23 against main c3bb66e)

`PasteAccessibilityService` must stay public (the manifest names it; `scripts/visibility-allowlist.txt`). `scripts/check-visibility.py` rule A covers top-level declarations only, by design (#191), so members of the 13 allowlisted components are unguarded. In this file, the members at the public default:

| Member | Where declared | Callers (all in `:app`) |
|---|---|---|
| `isBound` | companion (property; JVM getter `getIsBound`) | main (8 files), test (6), androidTest `VoicePipelineDeviceTest` |
| `releasePinnedTarget` | companion | main (3), test (2) |
| `pinnedFieldId` | companion | main (2), test (2) |
| `windowTreeXml` | companion | debug `DebugDumpReceiver`, test `PasteServiceShapeTest` (source text only) |
| `refreshBubble` | companion | main `OnboardingViewModel`, test (source text) |
| `startDictationFromBubble` | instance | main `RecordingAccessibilityOverlay`, test (source text) |

Sweep: `git grep -lw <name> -- '*.kt' '*.java' '*.aidl'` per member; no hit in `accelerator-benchmark/`, `llama-android/` or any AIDL. Every `override` stays as it is (Android calls those). `pasteWhenTargetReturns` and `pinTargetForDictation` are already `internal`.

All six become `internal`. `internal` is visible to the `debug`, `test` and `androidTest` source sets of the same module, so no caller moves. The issue's "keep its only caller in the debug source set" already holds.

**Out of scope, filed as a follow-up issue:** the same default on the other 12 allowlisted components (their companion constants, `takeBindIntent`, `ModelDeliveryNotification` members and so on). The test below takes its component list as data so that issue extends it rather than writing a new guard.

## 2. Tests

1. `ComponentMemberVisibilityTest` (new, JVM unit test). For each class in its list (today only `PasteAccessibilityService`), read the compiled class with Java reflection. Kotlin compiles an `internal` member as a public JVM method whose name ends in the module tag (`$app_debug`), so the test counts a public, non-synthetic, non-bridge method as exposed unless its name ends in `$app_<variant>`, or (on the class, not the companion) it overrides a public method of the superclass or an interface (`getMethod`, same name and parameters). The companion's property getter `getIsBound` is covered the same way. Fails naming each offender. It reads the compiler's output rather than the source text. The mangling is a compiler convention, not the language's definition of visibility; the row is run red before the change as evidence it tells the two apart here.
   MUTATION m1: put `windowTreeXml` back to public. MUTATION m2: put `startDictationFromBubble` back to public.
2. Existing `PasteServiceShapeTest` source-text rows that quote `fun windowTreeXml()` and similar are updated to the new `internal fun` text.

## 3. Blast radius

None at runtime: `internal` changes only Kotlin compile-time access and JVM method names; no reflection or intent reaches these members. Rollback: revert the squash commit.

## 4. Ship criteria

- [ ] Row 1 green, both mutations red; the suite green; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` build.
- [ ] Emulator: auto-paste bound, the debug window dump answers.
- [ ] Follow-up issue filed for the other 12 components.

## 5. Related

#191 (REF-08, top-level visibility sweep), REF-10 of the second 2026-09-23 audit.
