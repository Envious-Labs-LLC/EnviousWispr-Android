# Issue #191 — App-only code is public and new states can silently take default branches — 2026-09-21

GitHub issue: `#191`. Tier: SMALL by the issue (REF-08, "-20 lines net"); the diff touches many files but moves
no logic, no process, no package and no AIDL. Status: DRAFT for the coverage round.

Consolidation: this plan is one document; §2.5 carries the measured populations once and §§3 to 11 point back at it.

**Build order.** Grounded against `main` `0da76e4` (after #188, PR #199), the base of worktree
`issue-191-visibility-sweep`; every count below was produced on that commit by the scripts named beside it.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/**` (visibility modifiers and `when` arms), `app/src/test/**` (one new guard),
`scripts/**` (the source check). `mixed_pr: true`: `Code` (`unit-tests.xml`, `codex-review.md`;
`hardware-uat.json` because the sweep touches capture, ASR, polish and insertion files, so the emulator takes are
the receipt) and `Docs/dev-tooling` (`cited-symbols`, conditional).

**PAR rows closed:** none. Internal-only.

**Hardware UAT:** Y. Every file on the heart path gains modifiers, so the whole take must still run: on the
emulator through wispr-eyes, a spoken take lands in Gmail's compose body from the recorder; nothing a person
sees changes. The founder's phone is not used (his instruction 2026-09-21 morning).

## Preface — User Rubric

User Rubric: N/A — the change adds Kotlin visibility modifiers and replaces `else` arms with the members they
stood for; every branch keeps the value it had, the manifest is untouched, and no string, screen, timing or
persisted byte changes. The persona check would compare identical before-and-after surfaces.

## 0. TL;DR

- 213 of the 227 top-level declarations in `app/src/main/java` that carry Kotlin's public default become
  `internal`; the 14 the framework constructs by name stay public, and their app-only companion members and
  public members become `internal` where the compiler otherwise reports "exposes internal type".
- The 14 `else ->` arms over an app enum or sealed type become explicit members, so a new member breaks the
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
- Every app-only top-level declaration is `internal` or `private`; only framework-constructed components are
  public (§2.5.1 lists the 14).
- Every `when` over a closed set has no `else` (§2.5.1 lists the 14 sites).
- A check fails on a staged violation of either kind and passes on the shipped tree.

### 2.2 Non-goals
- No logic, name, package, process or AIDL change. No member-level sweep inside `internal` classes (their
  members are already unreachable outside the module). No `debug`/`test`/`androidTest` source-set sweep.
- No detekt or lint dependency: the check is a scoped Python script like `check-cited-symbols.py`.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, per population

**Population A, top-level public defaults.** Producer: every `.kt` under `app/src/main/java`. Measured by
`scratchpad/191-public-decls.py` on `0da76e4`: 227 declarations (139 `class`, 63 `object`, 15 `interface`,
10 `fun`) across 196 files; 187 more are already `internal` and 127 `private`. Fourteen extend a class the
framework constructs by name, all twelve manifest components plus the worker and the Room database:
`AsrService`, `AudioCaptureService`, `EnviousWisprDatabase`, `ModelBootstrapApplication`,
`ModelDeliveryCancelReceiver`, `ModelDeliveryWorker`, `PasteAccessibilityService`, `PolishService`,
`DictationTileService`, `AccessibilityGuideActivity`, `DictationSessionService`, `SettingsActivity`,
`VoiceInputActivity`, `SilenceVadService` (the manifest's `android:name` set, read with
`grep 'android:name="\.' app/src/main/AndroidManifest.xml`, is exactly the twelve).
Consumers of a public declaration outside `:app`: none. `accelerator-benchmark/build.gradle.kts` and
`llama-android/build.gradle.kts` contain no `project(":app")` (`grep` on both, no hit); `test` and
`androidTest` are friend source sets of `:app`, so `internal` stays visible to every existing test.

**Population B, `else` over a closed set.** Producer: every `else ->` in `app/src/main/java`, 109 by
`grep -rn "else ->"`; `scratchpad/191-else-sites.py` attributes each to its `when`: 57 have no subject
(`when { … }`, where `else` is the only total match) and 52 have one. Reading each subject's declaration
(`scratchpad/191-else-context.py`), 14 are over an app enum or sealed type:

| Site | Subject | Closed set (declaration) |
|---|---|---|
| `models/ModelDeliveryNotification.kt:56` | `state` | `DownloadState` (`models/ModelDelivery.kt:11`, 6 members) |
| `models/ModelDeliveryNotification.kt:72` | `state` | `DownloadState` |
| `models/ModelDeliveryWorker.kt:142` | `result.state` | `DownloadState` |
| `paste/InsertionAttempt.kt:334` | `byWindow` | `AccessibilityInsertionRules.Judgement` (`paste/AccessibilityInsertionRules.kt:25`) |
| `providers/ProviderModelDiscoveryClient.kt:117` | `verdict` | `ProviderKeyCheck` (`providers/ProviderKeyCheck.kt:10`, sealed) |
| `ui/DictationSessionCoordinator.kt:249` | `state.get()` | `SessionState` (`:124`, private enum) |
| `ui/DictationSessionCoordinator.kt:254` | `state.get()` | `SessionState` |
| `ui/DictationSessionCoordinator.kt:260` | `state.get()` | `SessionState` |
| `ui/DictationSessionCoordinator.kt:319` | `resolveCommand(...)` | `BubbleRequestLedger.CommandDecision` (`shortcuts/BubbleRequests.kt:68`, sealed) |
| `ui/OnboardingDemo.kt:135` | `moment.scene` | `DemoScene` (`ui/OnboardingDemoScript.kt:8`) |
| `ui/OnboardingScreen.kt:73` | `stage` | `OnboardingStage` (`ui/OnboardingPolicy.kt:12`) |
| `ui/OnboardingScreen.kt:199` | `action` | `ModelUiAction` (`models/ModelDeliveryUi.kt:3`) |
| `ui/OnboardingViewModel.kt:272` | `outcome` | `PracticeOutcome` (`ui/OnboardingPolicy.kt:36`) |

The other 38 subjects are `Int`, `String`, `Char`, `Byte`, `Any?` or `Throwable` (for example
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
- **Framework construction by name** (manifest components, `Worker` (external), Room): `internal class` compiles to a
  public JVM class with the same name, so the framework could still construct it; the audit keeps these
  public on purpose so the boundary reads truthfully. The 14 stay public.
- **"Exposes internal type"**: a public member of a still-public class whose signature names a now-internal
  type is a compile error. The producer of that set is the 14 classes' public members; the compiler
  enumerates it, and each such member becomes `internal` (they are app-only by construction: the binder
  objects, the companion constants, the `isBound` flow).
- **Room's generated code** (`EnviousWisprDatabase_Impl` (external), `TranscriptDao_Impl` (external)) is generated by KSP into
  the same module and can extend or implement `internal` declarations; the database class stays public
  regardless (framework row). Proven by compiling, §2.5.5.
- **`@Composable` functions and `@Preview`s** are same-module; `internal fun` composables are ordinary.
- **AIDL**: the generated `IAudioCaptureService.Stub` and friends live in `build/` and are public;
  app classes implementing them may be `internal`; the AIDL surface itself does not change.
- **`debug` source set** (`DebugDumpReceiver` and the harness receivers) is the same module and variant;
  it reads `internal` types today already.
- **Sealed `when` with a private enum** (`SessionState`): the three coordinator sites list the remaining
  members explicitly (`PROCESSING`, `IDLE` as the `when` requires), keeping each arm's current behaviour
  (`stopIfIdle()` / `Unit`), never a new one.

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
- Codex problem-only consult: not run before §3, because every premise above has a mechanical oracle (the
  compiler, a grep, the suite); the coverage round and the grounded rounds review the design.

## 3. Design

1. **Sweep A (mechanical, scripted, one commit).** `scratchpad/191-sweep-visibility.py` inserts `internal `
   before each of the 213 declarations (the 227 minus the 14 framework rows), file by file, at the exact
   line the enumeration names. Compile; where the compiler reports "exposes internal type", make the named
   member of the framework class `internal` too; where a framework-constructed or annotation-processed
   declaration genuinely needs public, add it to `scripts/visibility-allowlist.txt` with the reason. The
   alternative, hand-editing 196 files, was rejected: a script driven by the enumeration cannot skip one.
2. **Sweep B (14 sites by hand, one commit).** Each `else` arm is replaced by the members it covered today,
   with the SAME right-hand side, so behaviour is byte-for-byte the old default for every existing member.
   Where the old `else` covered one member, the arm names it; where it covered several, they share the
   arm. The alternative of adding a new "unknown" branch was rejected: the point is that there is no
   unknown branch.
3. **The check, `scripts/check-visibility.py` (one commit).** Scope: `app/src/main/java/**/*.kt`.
   - A: a top-level `class|object|interface|fun|val|var|typealias` line with no `private|internal`
     modifier fails unless the declaration name is in `scripts/visibility-allowlist.txt` (one name per
     line, `#` reasons); the shipped allowlist is the 14 framework classes.
   - B: for every `when (subject) {` block, if any arm's left side is Name.MEMBER or a bare MEMBER
     where `Name` is an enum/sealed type declared under `app/src/main/java` and the member one of its
     members (both sets read from the source by regex: `enum class Name … { A, B, … }` and
     `object|class|data class X : Name` inside a sealed declaration), then an `else ->` arm in that block
     fails. Exemption: none; a genuinely open subject never names a closed member on its arms.
   - Exit 0/1, prints every hit with `file:line`; `--detect-only` is not needed (always applicable to the
     Code lane). `scripts/validate-pr.sh` runs it for the Code lane and records `visibility.txt`;
     `scripts/check-validation.sh` requires it for `Code`. FACT: lanes gains the artifact and obligation
     id `visibility` (a rule-file edit the classifier refuses for the session; the replacement line is
     filed for the founder like the pass rule).
   - A JVM drift guard, `VisibilityCheckTest` (Drift Guard), runs the script on the tree and asserts exit 0,
     and runs it on two staged fixtures under `app/src/test/resources/visibility/` (a public class, an
     `else` over an enum) and asserts exit 1 with the expected line, so the check's own two directions are
     pinned.

## 3b. Ownership justification
The check lives in `scripts/` beside `check-cited-symbols.py` because it is a source-text guard with a
Phase 3 hook, not runtime code; the alternative, a detekt rule, adds a dependency and a plugin for two
regexes. The allowlist lives in `scripts/` so the check reads a file the founder can edit without touching
Python.

## 4. Contract deltas
- 213 declarations: visible inside `:app` (main, debug, test, androidTest) and to nothing else. Consumers
  outside the module: none exist (§2.5.1).
- 14 `when` sites: the same value for every existing member; a new member is a compile error at the site.
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
| a new enum member | any of the 14 sites | the compiler | nothing | none | add the arm |

## 8. Caller-visible signals
Not present in this change.

## 9. Fallback source-of-truth audit
Not present in this change (no runtime failure branch).

## 10. File-by-file changes
- 196 files under `app/src/main/java`: `internal` on 213 declarations (the enumeration file
  `scratchpad/191-public-decls.txt` is the list; the commit body carries its count and the framework 14).
- The 14 `when` sites in §2.5.1.
- `scripts/check-visibility.py` (new), `scripts/visibility-allowlist.txt` (new, 14 names with reasons),
  `scripts/validate-pr.sh` and `scripts/check-validation.sh` (the `visibility` obligation for `Code`).
- `app/src/test/java/com/envi/wispr/VisibilityCheckTest.kt` (new) and two fixtures under
  `app/src/test/resources/visibility/`.
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
  there). Restore afterwards. NOT RUN: anything on the founder's phone.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `VisibilityCheckTest.theTreeHasNoPublicDefaultOutsideTheAllowlist` (proposed) | Drift Guard | the check exits 0 on the tree | drop `internal` from one class |
| `VisibilityCheckTest.aStagedPublicClassIsRefused` (proposed) | Harness Contract | the check's A direction fires on the fixture | delete the fixture's hit from the script's regex |
| `VisibilityCheckTest.aStagedElseOverAnEnumIsRefused` (proposed) | Harness Contract | the B direction fires | same |
| the compiler | — | a new `DownloadState` member breaks `ModelDeliveryWorker.kt`, `ModelDeliveryNotification.kt` and `OnboardingScreen.kt` | add `DownloadState.QUEUED` and read the three errors (receipt R3, then remove it) |

## 12. Blast radius & rollback
Compile-time only. Rollback is `git revert` of three commits; nothing persisted changes shape.

## 13. Ship criteria specific to THIS change
- `scripts/check-visibility.py` exits 0 on the tree and 1 on each fixture.
- `scripts/measure-tests.sh` count reported; `:app:assembleDebug` and both test compilations green.
- Receipt R3 shows three compiler errors for one added enum member.
- The emulator takes in §11.1 land.

## 14. Open questions
None.

## 15. Related
#186, #188, #189, #190 (the splits that already made their files internal); `docs/audits/2026-09-20-senior-audit.md` REF-08.
