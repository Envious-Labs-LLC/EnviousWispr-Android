# Issue #275: narrow the app-only members of the Android-started components (2026-09-24)

GitHub issue: `#275`. Tier: SMALL (visibility only; no runtime behaviour changes). Status: built by the issue's own four steps; Codex reviewed this plan and the diff together (round 1: four findings, all adopted; round 2: three more member kinds, the second round of the same class, so the class was enumerated, see §2.1).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take starts and stops by the Service commands whose constants changed visibility.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. The constants and helper methods our Android-started classes declare for their own use stop being public Kotlin API: Kotlin callers outside this module cannot use these `internal` declarations directly; JVM visibility and Android component access are unchanged. The gain is a smaller surface to reason about when the owners change.

---

## 0. TL;DR

`architecture-rules.md` RULE: minimize-visibility, and REF-07 of `docs/audits/2026-09-23d-senior-audit.json` (the `DictationSessionService` action and extra constants). `ComponentMemberVisibilityTest` (#261) now judges all 13 components in `scripts/visibility-allowlist.txt`, and a row pins its list to that file. Its failure listed 60 exposed members in five classes; 58 declarations were marked `internal` (the other two entries were the same property's getter and the companion method view of one function).

Consolidation: none.

## 1. Grounding (main 3973e23)

The first run of the extended test listed: `AudioCaptureService` (18 companion constants, `takeBindIntent`, `waitForFileReady`), `DictationSessionService` (4 actions, 2 extras), `ModelDeliveryWorker` (13 keys and buckets, `BYTES_BUCKETS`, `bytesBucket`), `SilenceVadService` (8 constants), `VoiceInputActivity` (6 extras). The other eight components had none.

## 2. Design

1. The class "a public JVM member of a component the scan does not judge", enumerated by container and member kind (round 2):
   - Component class and its companion: every public method and field, judged by the Kotlin visibility its metadata records (a function or extension function, a property's or extension property's getter, setter or backing field), so neither a module-tagged name nor a static field can pass on its JVM shape alone.
   - Every other nested type at any depth, including types nested in the companion (objects, classes, interfaces, enums): the type itself must not be public, so its members need no separate scan. Synthetic classes (the compiler's `$WhenMappings`) are skipped; `@JvmSynthetic` cannot target a class.
   - Constructors: pass. Android and WorkManager construct the component.
   - Compiler-made members pass only when no Kotlin declaration exists: synthetic or bridge methods, synthetic fields, Compose's `$stable` by exact name, and the `INSTANCE` and `Companion` handles by exact type. A `@JvmSynthetic` or backticked `$` source declaration is still judged.
   - Overrides pass only against a supertype's instance method with an assignable return type, over the whole supertype graph.
   A component with no `Companion` is tolerated.
2. A companion `const val` compiles to a public static field whatever its Kotlin visibility, so companion properties are judged by their Kotlin visibility, read through Kotlin reflection (`kotlin-reflect` 2.0.21, test only, the compiler's version).
3. Constants that other processes of this app read by value (`AudioCaptureService`'s status and reason codes, `SilenceVadService`'s results) need nothing beyond `internal`: every process runs this module's code, and the values cross the binder as integers.
4. `theComponentsAreExactlyTheAllowlist`: the test's list equals the allowlist row for row, compared as full class names (each row's package read from its file) in sorted lists, so a duplicated or moved row fails too.

## 3. Tests and receipts

- Mutations (`275-mut.py`): m1 `PasteAccessibilityService.windowTreeXml` back to public (a method), m2 `DictationSessionService.ACTION_TOGGLE` back to public (a constant), m3 `SilenceVadService` dropped from the test's list, m4 `ModelDeliveryWorker.KEY_STATE` back to public, m5 a public instance `@JvmField`, m6 a nested object's public member, m7 a public method given a module-tagged JVM name, m8 a duplicated allowlist row, m9 a public `@JvmField` named `` `$leak` ``, m10 a public `@JvmSynthetic` function, m11 a public nested class, m12 a public class nested in the companion: all RED.
- Suite 1342, 0 failures; app and androidTest build (the device tests call `waitForFileReady` as a friend module); visibility and cited-symbol checks clean.
- Emulator: on the branch build a take started, recorded and stopped by the Service commands. Its speech decoded as silence, and the `main` build (3973e23) did the same on the same emulator, so the silence was not introduced here; its cause is open as #319.

## 4. Blast radius

Kotlin code outside this module that named one of these members would stop compiling; none exists (the only users are this module, its unit tests and its device tests). Rollback: revert the squash commit.
