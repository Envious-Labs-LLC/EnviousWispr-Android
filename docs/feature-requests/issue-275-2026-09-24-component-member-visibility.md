# Issue #275: narrow the app-only members of the Android-started components (2026-09-24)

GitHub issue: `#275`. Tier: SMALL (visibility only; no runtime behaviour changes). Status: built by the issue's own four steps; Codex reviews this plan and the diff together.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take starts and stops by the Service commands whose constants changed visibility.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. Today another app on the phone could link against the constants and helper methods our Android-started classes declare for their own use; after this change only our app can.

---

## 0. TL;DR

`architecture-rules.md` RULE: minimize-visibility, and REF-07 of `docs/audits/2026-09-23d-senior-audit.json` (the `DictationSessionService` action and extra constants). `ComponentMemberVisibilityTest` (#261) now judges all 13 components in `scripts/visibility-allowlist.txt`, and a row pins its list to that file. Its failure listed 60 exposed members in five classes; 58 declarations were marked `internal` (the other two entries were the same property's getter and the companion method view of one function).

Consolidation: none.

## 1. Grounding (main 3973e23)

The first run of the extended test listed: `AudioCaptureService` (18 companion constants, `takeBindIntent`, `waitForFileReady`), `DictationSessionService` (4 actions, 2 extras), `ModelDeliveryWorker` (13 keys and buckets, `BYTES_BUCKETS`, `bytesBucket`), `SilenceVadService` (8 constants), `VoiceInputActivity` (6 extras). The other eight components had none.

## 2. Design

1. The test reads every allowlisted component. A component with no `Companion` is tolerated (`singleOrNull`). Override detection walks the whole superclass chain, not the direct parent only.
2. A companion `const val` compiles to a public static field whatever its Kotlin visibility, so companion properties are judged by their Kotlin visibility, read through Kotlin reflection (`kotlin-reflect` 2.0.21, test only, the compiler's version).
3. Constants that other processes of this app read by value (`AudioCaptureService`'s status and reason codes, `SilenceVadService`'s results) need nothing beyond `internal`: every process runs this module's code, and the values cross the binder as integers.
4. `theComponentsAreExactlyTheAllowlist`: the test's list equals the allowlist, so a component added there is judged without a second edit.

## 3. Tests and receipts

- Mutations (`275-mut.py`): m1 `PasteAccessibilityService.windowTreeXml` back to public (a method), m2 `DictationSessionService.ACTION_TOGGLE` back to public (a constant), m3 `SilenceVadService` dropped from the test's list, m4 `ModelDeliveryWorker.KEY_STATE` back to public: all RED.
- Suite 1342, 0 failures; app and androidTest build (the device tests call `waitForFileReady` as a friend module); visibility and cited-symbol checks clean.
- Emulator: on the branch build a take started, recorded and stopped by the Service commands. Its speech decoded as silence, and the `main` build (3973e23) did the same on the same emulator, so the silence is the emulator's microphone injection, filed as #319, not this change.

## 4. Blast radius

A member another app linked against would stop compiling for that app; none exists (all are ours). Rollback: revert the squash commit.
