# Issue #191 — App-only code is public and new states can silently take default branches — 2026-09-21

GitHub issue: `#191`. Tier: SMALL by the issue (REF-08, "-20 lines net"); the diff touches many files but moves
no logic, no process, no package and no AIDL. Status: DRAFT after the coverage round (A1, B1, B2, C1 to C5, D1 to D3, E1, E2, F1, F2, G1 folded in); grounded round 1 PROCEED-WITH-REVISIONS (G1.1, G1.2, G2.1, G2.2, G3.1, G4.1 to G4.6 folded in); round 2 PROCEED-WITH-REVISIONS (G1.1 to G1.5, G2.1); the second round of the same class, stale prose beside an enumeration, so every restated count and member list in prose now points at the one table; round 3 PROCEED-WITH-REVISIONS on a new axis (G2.1, the check's rule A binds name to path and reads scope by brace depth); round 4 next.

Consolidation: this plan is one document; §2.5 carries the measured populations once and §§3 to 11 point back at it.

**Build order.** Grounded against `main` `0da76e4` (after #188, PR #199), the base of worktree
`issue-191-visibility-sweep`; every count below was produced on that commit by the scripts named beside it.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/**` (visibility modifiers and `when` arms), `app/src/test/**` (one new guard),
`scripts/**` (the source check). `mixed_pr: true`: `Code` (`unit-tests.xml`, `codex-review.md`, `visibility.txt` (new in this
change, §3.3), and `hardware-uat.json` because the sweep touches capture, ASR, polish and insertion files, so the
emulator takes are the receipt) and `Docs/dev-tooling` (`cited-symbols`, conditional).

**PAR rows closed:** none. Internal-only.

**Hardware UAT:** Y. Every file on the heart path gains modifiers, so the whole take must still run: on the
emulator through wispr-eyes, a spoken take lands in Gmail's compose body from the recorder; nothing a person
sees changes. The founder's phone is not used (his instruction 2026-09-21 morning).

## Preface — User Rubric

User Rubric: N/A — the change adds Kotlin visibility modifiers and replaces `else` arms with the members they
stood for; every branch keeps the value it had, the manifest is untouched, and no string, screen, timing or
persisted byte changes. The persona check would compare identical before-and-after surfaces.

## 0. TL;DR

- 214 of the 227 top-level declarations in `app/src/main/java` that carry Kotlin's public default become
  `internal`; the 13 the framework constructs BY NAME stay public, and their app-only companion members and
  public members become `internal` where the compiler otherwise reports "exposes internal type". The Room
  database is not one of the 13 (coverage A1): `Room.databaseBuilder` receives the class object and reflects
  only the generated `EnviousWisprDatabase_Impl` (external), so `EnviousWisprDatabase` and its three DAO
  interfaces become `internal` together, which keeps the three abstract DAO accessors
  (`history/EnviousWisprDatabase.kt:20-22`, coverage B1) public members of an internal class, unmangled, so the
  generated Java implementation still overrides them by their JVM names. The initial allowlist has 13
  names; if compilation rejects any of the four Room types, the build STOPS and this plan's allowlist,
  §2.5.1 count and sweep count are updated before the sweep continues.
- The 12 `else ->` arms over an app enum or sealed type become explicit members, so a new member breaks the
  build at each of those sites (Kotlin 2.0 makes a non-exhaustive `when` over an enum or sealed type an
  error, statement or expression).
- `scripts/check-visibility.py` rejects a new public-default top-level declaration outside an allowlist, and
  an `else` arm in a `when` whose other arms name members of an app enum or sealed type; run by
  `scripts/validate-pr.sh` for the Code lane and by a JVM drift guard.

## 1. Problem

Kotlin's default is `public`, so every app-only type is API to any module that could depend on `:app`, and a
reader cannot tell a deliberate boundary from an omission. Separately, an `else` over an enum or sealed type
turns the compiler's exhaustiveness error into a silent choice for the next member: the audit's examples are
`OnboardingScreen.kt:73` (a new onboarding stage steps back to WELCOME) and `ModelDeliveryWorker.kt:142` (a
new download state is reported as a failure).

## 2. Goals & non-goals

### 2.1 Goals
- Every app-only top-level declaration is `internal` or `private`; only components the framework constructs
  by name are public (§2.5.1 lists the 13).
- Every `when` over a closed set has no `else` (§2.5.1 lists the 12 sites).
- A check fails on a staged violation of either kind and passes on the shipped tree.

### 2.2 Non-goals
- No logic, name, package, process or AIDL change. No member-level sweep inside `internal` classes (their
  members are already unreachable outside the module). No `debug`/`test`/`androidTest` source-set sweep.
- No detekt or lint dependency: the check is a scoped Python script like `check-cited-symbols.py`.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, per population

**Population A, top-level public defaults.** Producer: every `.kt` under `app/src/main/java`. Measured by
`scratchpad/191-public-decls.py` on `0da76e4`: 227 declarations (139 `class`, 63 `object`, 15 `interface`,
10 `fun`) across 196 files; 187 more are already `internal` and 127 `private`. Thirteen extend a class the
framework constructs by name, all twelve manifest components plus the worker (WorkManager reconstructs it
from the class name it persisted, `models/ModelDeliveryWorker.kt:25`); the Room database
(`history/EnviousWisprDatabase.kt:19`) is handed to Room as a class object and goes internal with its DAOs:
`AsrService`, `AudioCaptureService`, `ModelBootstrapApplication`,
`ModelDeliveryCancelReceiver`, `ModelDeliveryWorker`, `PasteAccessibilityService`, `PolishService`,
`DictationTileService`, `AccessibilityGuideActivity`, `DictationSessionService`, `SettingsActivity`,
`VoiceInputActivity`, `SilenceVadService` (the manifest's `android:name` set, read with
`grep 'android:name="\.' app/src/main/AndroidManifest.xml`, is exactly the twelve). The compiler-reported
"exposes internal type" members are recorded in chunk 1's commit body by owner and signature (coverage B1);
none of the 13 classes' members is consumed reflectively or by the system, since the binder objects are private,
the debug receivers are same-module, and `PendingIntent` carries action strings, never companion fields
(coverage B2, `audio/AudioCaptureService.kt:197-284`, `app/src/debug/java/com/envi/wispr/debug/DebugDumpReceiver.kt:27-30`).
Consumers of a public declaration outside `:app`: none. `accelerator-benchmark/build.gradle.kts` and
`llama-android/build.gradle.kts` contain no `project(":app")` (`grep` on both, no hit); `test` and
`androidTest` are friend source sets of `:app`, so `internal` stays visible to every existing test.

**Population B, `else` over a closed set.** Producer: every `else ->` in `app/src/main/java`, 109 by
`grep -rn "else ->"`; `scratchpad/191-else-sites.py` attributes each to its `when`: 57 have no subject
(`when { … }`, where `else` is the only total match) and 52 have one. Reading each subject's declaration
(`scratchpad/191-else-context.py`), 12 are over an app enum or sealed type (the first draft counted
`ui/DictationSessionCoordinator.kt:319`, whose `else` belongs to the OUTER `when (action: String)`; the inner
sealed `when` there is already exhaustive, coverage C2):

The table is the ONE owner of what each `else` becomes: the `Replacement left side` column names every
member, `is` subtype and `null` arm that replaces the `else`, each keeping the old right-hand side verbatim.
Every other section that mentions these sites points here.

| Site | Subject | Closed set (declaration, every member) | Replacement left side |
|---|---|---|---|
| `models/ModelDeliveryNotification.kt:56` | `state` | `DownloadState` (`models/ModelDelivery.kt:11`): DOWNLOADING, PAUSED, VERIFYING, READY, FAILED, CANCELLED, REPAIR_NEEDED | `DownloadState.DOWNLOADING, DownloadState.PAUSED ->` |
| `models/ModelDeliveryNotification.kt:72` | `state` | `DownloadState` | `DownloadState.READY, DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.REPAIR_NEEDED ->` |
| `models/ModelDeliveryWorker.kt:142` | `result.state` | `DownloadState` | `DownloadState.DOWNLOADING, DownloadState.VERIFYING ->` |
| `paste/InsertionAttempt.kt:334` | `byWindow` | `AccessibilityInsertionRules.Judgement` (`paste/AccessibilityInsertionRules.kt:25`): VERIFIED, MISS, UNREADABLE | `Judgement.MISS, Judgement.UNREADABLE ->` |
| `providers/ProviderModelDiscoveryClient.kt:117` | `verdict` | `ProviderKeyCheck` (`providers/ProviderKeyCheck.kt:10`, sealed): `Accepted`, `NotApplicable` (`data object`), `Rejected`, `Denied`, `Unverified` (`data class`) | `ProviderKeyCheck.NotApplicable, is ProviderKeyCheck.Rejected, is ProviderKeyCheck.Denied, is ProviderKeyCheck.Unverified ->` |
| `ui/DictationSessionCoordinator.kt:249` | `state.get()` | `SessionState` (`:124`, private): IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR | CANCEL: `SessionState.IDLE, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR ->` |
| `ui/DictationSessionCoordinator.kt:254` | `state.get()` | `SessionState` | STOP: `SessionState.IDLE, SessionState.PROCESSING, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR ->` |
| `ui/DictationSessionCoordinator.kt:260` | `state.get()` | `SessionState` | TOGGLE: `SessionState.PROCESSING, SessionState.CANCELLING, SessionState.FINISHING, SessionState.ERROR ->` |
| `ui/OnboardingDemo.kt:135` | `moment.scene` | `DemoScene` (`ui/OnboardingDemoScript.kt:8`): APPS, BUBBLE, TAP, HOLD, YOURS | `DemoScene.TAP, DemoScene.HOLD, DemoScene.YOURS ->` |
| `ui/OnboardingScreen.kt:73` | `stage` | `OnboardingStage` (`ui/OnboardingPolicy.kt:12`): WELCOME, DOWNLOADS, PERMISSIONS, DEMO, PRACTICE | `OnboardingStage.WELCOME, OnboardingStage.DOWNLOADS, OnboardingStage.PERMISSIONS ->` |
| `ui/OnboardingScreen.kt:199` | `action` (`active?.action`, nullable) | `ModelUiAction` (`models/ModelDeliveryUi.kt:3`): DOWNLOAD, PAUSE, RESUME, RETRY, REPAIR, REMOVE, UPDATE, CANCEL, NONE | `ModelUiAction.DOWNLOAD, ModelUiAction.RETRY, ModelUiAction.REPAIR, ModelUiAction.REMOVE, ModelUiAction.UPDATE, ModelUiAction.CANCEL, ModelUiAction.NONE, null ->` |
| `ui/OnboardingViewModel.kt:272` | `outcome` | `PracticeOutcome` (`ui/OnboardingPolicy.kt:36`): WORKING, LANDED, LANDED_BY_TAP, NOTHING_LANDED | `PracticeOutcome.WORKING, PracticeOutcome.NOTHING_LANDED ->` |

The other 40 subjects (52 minus the 12) are `Int`, `String`, `Char`, `Byte`, `Any?` or `Throwable` (for example
`CaptureEnding.fromAidl(reason: Int)` `:81`, `PolishEngineLabels.kt:54` over a `String` vocabulary,
`PolishFailure.fromStatus(statusCode: Int)` `:118`), where `else` is the only total match and stays.

### 2. Existing authority
- Visibility: `architecture-rules.md` RULE: minimize-visibility (the rule, no enforcer);
  `kotlin-patterns.md` RULE: internal-is-the-right-default-inside-app.
- Exhaustive `when`: `kotlin-patterns.md` RULE: exhaustive-when-no-else; the compiler enforces it only once
  the `else` is gone (Kotlin 2.0.21 here, where a non-exhaustive `when` over an enum, sealed type or Boolean
  is an error for statements and expressions alike).
- Source-text checks with an allowlist and a Phase 3 hook: `scripts/check-cited-symbols.py` (the shape:
  scoped roots, exit 0/1, `--detect-only`), wired by `scripts/validate-pr.sh`. `new authority proposed`:
  `scripts/check-visibility.py`, same shape. Negative grep: `grep -rn "public default\|visibility" scripts/`
  finds nothing that checks modifiers today.

### 3. Prior attempts and live direction
The audit (REF-08) and the issue are the whole history; no session touched it. #186, #189, #190 and #188
already made their new files `internal` (`ui/DictationSessionCoordinator.kt`, `providers/*Adapter.kt`,
`ui/AppNavigation.kt`, `audio/TakeRoute.kt` and siblings), which is why 187 declarations are already
internal. The catalog has no decision on code visibility: `sqlite3 ~/.claude/knowledge/enviouswispr/catalog.db
"SELECT decision_slug FROM decision WHERE lower(decision_text) LIKE '%visib%' OR lower(decision_text) LIKE
'% internal %'"` returns 8 rows, all about UI visibility or the internal testing track, none about Kotlin or
Swift access levels (macOS has no module boundary of this kind).

### 4. Boundaries a naive design misses
- **Framework construction by name** (the twelve manifest components and the `Worker` (external)): `internal class`
  compiles to a public JVM class with the same name, so the framework could still construct it; the audit
  keeps these public on purpose so the boundary reads truthfully. The 13 class-name entry points stay
  public; `EnviousWisprDatabase` and its three DAOs become internal, subject to the compile fallback.
- **"Exposes internal type"**: a public member of a still-public class whose signature names a now-internal
  type is a compile error. The producer of that set is the 13 classes' public members; the compiler
  enumerates it, and each such member becomes `internal` (they are app-only by construction: the binder
  objects, the companion constants, the `isBound` flow).
- **Room's generated code** (`EnviousWisprDatabase_Impl` (external), `TranscriptDao_Impl` (external)) is generated by KSP into
  the same module (`app/build.gradle.kts:99-101` sets only the Room schema location; Kotlin code generation for Room
  is off, so the generated code is Java) and extends or implements the app's declarations by their JVM
  names. The three DAO interfaces, `history/TranscriptDao.kt:13`, `vocabulary/CustomTermDao.kt:11` and
  `telemetry/TakeJournal.kt:51`, and the three accessors `history/EnviousWisprDatabase.kt:20-22` all
  carry the public default today, so none of their members is name-mangled, and marking the four TYPES
  `internal` leaves every member's JVM name unchanged (Kotlin mangles only members declared `internal`).
  `EnviousWisprDatabase` and the three DAOs become internal on that premise; the compile is the oracle and
  the allowlist with a reason is the fallback.
- **`@Composable` functions and `@Preview`s** are same-module; `internal fun` composables are ordinary.
- **AIDL**: the generated `IAudioCaptureService.Stub` and friends live in `build/` and are public;
  app classes implementing them may be `internal`; the AIDL surface itself does not change.
- **`debug` source set** (`DebugDumpReceiver` and the harness receivers) is the same module and variant;
  it reads `internal` types today already.
- **`when` over a private enum** (`SessionState`): the three coordinator sites take exactly the CANCEL, STOP
  and TOGGLE member groups in the §2.5.1 table's `Replacement left side` column, each keeping its current
  right-hand side, never a new one.

### 5. High-risk premises, with evidence
- "Removing `else` makes the compiler enforce the set": Kotlin 2.0.21 (`gradle/libs.versions.toml` or
  `build.gradle.kts` pins it), where non-exhaustive `when` statements over enum/sealed/Boolean are errors
  since 1.7. Verified by the build after chunk 2 and by one revert receipt that adds an enum member and
  reads the errors.
- "No module depends on `:app`": grep above; `settings.gradle.kts` lists `:app`, `:llama-android`,
  `:accelerator-benchmark`.
- "`internal` does not break Room, WorkManager, Compose or the tests": the only oracle is the compiler and
  the suite; chunk 1 ends with `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`,
  `:app:compileDebugAndroidTestKotlin` and the full suite green. If Room refuses an `internal` `@Dao` or
  `@Entity`, that declaration is allowlisted with the reason and the check reads the allowlist.
- Codex problem-only consult: performed as axis A of the coverage round (`docs/audits/2026-09-21-191-coverage.md`),
  which found the Room row (A1) and the DAO accessors (B1); the grounded rounds review the revised plan.

## 3. Design

1. **Sweep A (mechanical, scripted, one commit).** `scratchpad/191-sweep-visibility.py` inserts `internal `
   before each of the 214 declarations (the 227 minus the 13 framework rows), file by file, at the exact
   line the enumeration names. Compile; where the compiler reports "exposes internal type", make the named
   member of the framework class `internal` too; where a framework-constructed or annotation-processed
   declaration genuinely needs public, add it to `scripts/visibility-allowlist.txt` with the reason. The
   alternative, hand-editing 196 files, was rejected: a script driven by the enumeration cannot skip one.
2. **Sweep B (12 sites by hand, one commit).** Each `else` arm becomes the `Replacement left side` the
   §2.5.1 table names for its site, with the old right-hand side verbatim, so behaviour is byte-for-byte
   the old default for every existing member. The alternative of adding a new "unknown" branch was
   rejected: the point is that there is no unknown branch.
3. **The check, `scripts/check-visibility.py` (one commit).** Scope: `app/src/main/java/**/*.kt`.
   - A: a top-level declaration (the keyword `class|object|interface|fun|val|var|typealias` with its
     modifiers, possibly after annotation lines, and possibly with the name on the next line, coverage
     D2) with no `private|internal` modifier fails unless it is in `scripts/visibility-allowlist.txt`.
     Top-level means brace depth zero: the script tracks `{`/`}` outside string literals and comments,
     never indentation, so an indented top-level declaration is still top-level and an indented
     companion member is not (round G3). An allowlist entry binds the declaration to its file,
     `app/src/main/java/com/envi/wispr/ui/SettingsActivity.kt:SettingsActivity`, one per line with a
     `#` reason, so a new declaration reusing an allowlisted name in another file fails; the shipped
     allowlist is the 13 framework classes. A public companion member is not this rule's population
     (§2.2 non-goal; the compiler's "exposes internal type" handles the 13).
   - B: closedness is read from the ARMS as a set, never from one label (coverage D1): a `when (subject) {`
     block is treated as closed only when EVERY non-`else` arm's left side is a member of ONE app enum or
     sealed type (a bare member name, a qualified one, or an `is` test on a sealed child; the member sets are read from
     the source by regex over `enum class` bodies at any nesting depth and over `object|data object|class|
     data class X : Sealed` children, coverage D3) or the literal `null`; then an `else ->` arm fails. A
     block with a range, a guard, a literal, a type test on a foreign type or a Boolean subject is open to
     the check and passes; the compiler, not the check, is the authority once the `else` is gone. So the
     check is a drift guard for the shape this change leaves, not a type checker.
   - `--root <dir>` scans that tree's `app/src/main/java` instead of the repository's (coverage F2), which
     is how the JVM test runs it on fixtures. Exit 0/1, prints every hit with `file:line`.
   - Phase 3 (coverage E1, E2): `scripts/validate-pr.sh`'s Code block runs the check and records
     `visibility.txt` (always non-empty: the hits, or `clean`), marks `visibility` satisfied only on exit
     0; `OBLIGATIONS` and `REQUIRED["Code"]` in `scripts/check-validation.sh` gain `visibility`, and for the
     Code lane `visibility` must appear in `obligations_satisfied` (a non-empty `visibility.txt` holding
     hits is a FAILED check, never satisfaction); it is never a `skip-note.txt` entry. Older Code runs under `.validation/runs/` lack the artifact and would
     fail re-verification, which is accepted (they are history, not evidence for a new PR); Docs-only runs
     are untouched. FACT: lanes gains the artifact and obligation id `visibility` (a rule-file edit the
     classifier refuses for the session; the replacement line is filed for the founder like the pass rule).
   - A JVM drift guard, `VisibilityCheckTest` (Drift Guard), resolves the repository root from
     the `app/` working directory of `:app:testDebugUnitTest` (`File("..").canonicalFile`), runs the script
     on the tree and asserts exit code exactly 0; and runs it with `--root` on fixture trees under
     `app/src/test/resources/visibility/<case>/app/src/main/java/` (a public class; a public class whose
     name is on the next line; an INDENTED public top-level class; an allowlisted name declared in the
     wrong file; an `else` over an enum; an `else` over a `data object` sealed member; a nested enum
     matched bare; an open `when` mixing a member with a range, which must PASS) and asserts
     exit code exactly 1 with the expected `file:line` in stdout, or exactly 0 for the open case. A process
     that cannot launch is a test failure, never a pass (coverage F1).

## 3b. Ownership justification
The check lives in `scripts/` beside `check-cited-symbols.py` because it is a source-text guard with a
Phase 3 hook, not runtime code; the alternative, a detekt rule, adds a dependency and a plugin for two
regexes. The allowlist lives in `scripts/` so the check reads a file the founder can edit without touching
Python.

## 4. Contract deltas
- 214 declarations: visible inside `:app` (main, debug, test, androidTest) and to nothing else. Consumers
  outside the module: none exist (§2.5.1).
- 12 `when` sites: the same value for every existing member; a new member is a compile error at the site.
- Phase 3 Code lane: one more required artifact.

## 5. State and lifecycle audit
No runtime state, lifecycle, process or persistence changes; the populations are the two enumerations in
§2.5.1 and the compiler's "exposes internal type" set, which chunk 1 records in the commit body.

## 6. Consumer matrix
| Contract delta | Consumer | Current | Required | Change? | Verified by |
|---|---|---|---|---|---|
| top-level `internal` | `app/src/test`, `app/src/androidTest`, `app/src/debug` | read public | read internal (friend module) | none | `compileDebugUnitTestKotlin` (external), `compileDebugAndroidTestKotlin` (external), the suite |
| top-level `internal` | Room KSP, Compose compiler, WorkManager | public types | internal types | none expected; allowlist if refused | `:app:assembleDebug` |
| `else` removed | each site's callers | old default for known members | identical | none | suite, revert receipt R3 |

## 7. Failure-mode × caller table
| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| a public default lands later | a new file | the check | nothing (build-time) | none | fix or allowlist |
| an `else` over a closed set lands later | a new `when` | the check | nothing | none | name the members |
| a new enum member | any of the 12 sites | the compiler | nothing | none | add the arm |

## 8. Caller-visible signals
Not present in this change.

## 9. Fallback source-of-truth audit
Not present in this change (no runtime failure branch).

## 10. File-by-file changes
- 196 files under `app/src/main/java`: `internal` on 214 declarations (the enumeration file
  `scratchpad/191-public-decls.txt` is the list; the commit body carries its count, the framework 13 and the
  compiler-reported members).
- The 12 `when` sites in §2.5.1.
- `scripts/check-visibility.py` (new), `scripts/visibility-allowlist.txt` (new, the 13 class-name entry points with reasons),
  `scripts/validate-pr.sh` and `scripts/check-validation.sh` (the `visibility` obligation for `Code`).
- `app/src/test/java/com/envi/wispr/VisibilityCheckTest.kt` (new) and eight fixture trees under
  `app/src/test/resources/visibility/`.
- `docs/audits/2026-09-21-191-revert-receipts.txt` (new): the build commands with exit statuses, and every
  receipt of §11.2 with its red output (R3's three compiler errors verbatim).
- `.claude/knowledge/architecture.md` (the `internal` default is now enforced; where) and the filed rule
  line for FACT: lanes.

## 11. Testing
1. Classes: `VisibilityCheckTest` (proposed) is a Drift Guard (when it fails, a public default or a closed-set `else`
   reached the tree, which the user feels the next time a state is added and takes the wrong branch). The
   suite's existing rows are the product coverage; none change.
2. Reverts: §11.2, each once and seen red.
3. Not tested: nothing new at runtime.

### 11.1 Hardware UAT spec
- Emulator, wispr-eyes, debug build of the final commit: three `dictate_emulator` takes into Gmail (judge
  the editor's text), one `debug_insert`, one silence-ended take with auto-stop on (as #188 staged it), and
  `look()` on the Transcription, Models and Permissions pages (the onboarding and delivery `when`s draw
  there). Restore afterwards. NOT RUN: anything on the founder's phone. Not exercised by the emulator takes
  (coverage G1): WorkManager reconstructing a pre-update persisted `ModelDeliveryWorker` row by class
  name; the class stays public and its JVM name does not change, so nothing in this change can alter it.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `VisibilityCheckTest.theTreeHasNoPublicDefaultOutsideTheAllowlist` (proposed) | Drift Guard | the check exits 0 on the tree | drop `internal` from one NON-allowlisted class |
| `VisibilityCheckTest.aStagedPublicClassIsRefused` (proposed) | Harness Contract | the A direction fires on the fixture with exit code exactly 1 and the expected `file:line` | delete the fixture's hit from the script's regex |
| `VisibilityCheckTest.aStagedElseOverAnEnumIsRefused` (proposed) | Harness Contract | the B direction fires (enum, `data object`, nested enum) and the open mixed `when` passes | same |
| the compiler | — | a new `DownloadState` member breaks `ModelDeliveryWorker.kt:142` and the two `ModelDeliveryNotification.kt` sites (`:56`, `:72`) | add `DownloadState.QUEUED`, read the three errors into `docs/audits/2026-09-21-191-revert-receipts.txt` with the command and exit status (receipt R3), then remove it |

## 12. Blast radius & rollback
Compile-time only. Rollback is `git revert` of three commits; nothing persisted changes shape.

## 13. Ship criteria specific to THIS change
- `scripts/check-visibility.py` exits 0 on the tree and on the open mixed fixture, and exactly 1 on each of
  the seven rejecting fixtures (`VisibilityCheckTest` (proposed) rows).
- `scripts/measure-tests.sh` count reported; `:app:assembleDebug`, `:app:compileDebugUnitTestKotlin` (external) and
  `:app:compileDebugAndroidTestKotlin` (external) green, each command and exit status recorded in the receipts file.
- Receipt R3 in `docs/audits/2026-09-21-191-revert-receipts.txt` shows three compiler errors for one added enum member.
- The emulator takes in §11.1 land.

## 14. Open questions
None.

## 15. Related
#186, #188, #189, #190 (the splits that already made their files internal); `docs/audits/2026-09-20-senior-audit.md` REF-08.
