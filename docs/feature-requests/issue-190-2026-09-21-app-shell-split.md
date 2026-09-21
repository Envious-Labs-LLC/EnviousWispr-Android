# Issue #190 — Reduce the app shell to route state and screen composition — 2026-09-21

GitHub issue: `#190`. Tier: MEDIUM (the audit's tier; the surface is Compose layout, which the tier table
files SMALL, and escalation is upward only). Status: APPROVED (coverage 5 gaps folded; grounded G1 to G3 folded; G4 PROCEED-AS-PLANNED, `docs/audits/2026-09-21-190-grounded-r4.md`).

Consolidation: the dominant root is one file owning three concerns (navigation chrome, the shared settings
components, and a 39-callback contract with the activity). One owner per concern after the change:
`AppNavigation.kt` (proposed), `SettingsComponents.kt` (proposed), `AppActions` (proposed). Consolidation
sites: the 42 root parameters and the 42-argument activity call collapse into ten action groups built once;
the two inline saved-name resolutions collapse into `AppRoutes` (proposed).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/ui/**`, `app/src/test/java/com/envi/wispr/**`,
`app/src/androidTest/java/com/envi/wispr/ui/**`).

**PAR rows closed:** `none`. An internal split; every screen keeps its copy and its behaviour.

**Hardware UAT:** Y, on the emulator, and the founder's phone only as part of the next build he takes
anyway. The change is pure presentation plumbing: nothing on the heart path, no engine, no permission.
Rung 1 runs the instrumented navigation oracle (`AppShellNavigationTest`, 2 rows today plus one activity-recreation row this change adds) on the AVD
plus a walk of every tab and every drawer page through the harness (`scan()`). Success on his phone: the
app opens on History, the four tabs switch, the drawer opens every one of the nine pages and the back
arrow returns to the tab he left; rotating the phone keeps the open page.

## Preface — User Rubric

User Rubric: N/A — a code split with no new behaviour. Every tab, page, label, glyph, back gesture and
saved route value is unchanged; the instrumented navigation test and the source-text drift guards are the
oracles, and the emulator walk reads the same screens.

## 1. Problem

`app/src/main/java/com/envi/wispr/ui/AppShell.kt` is 1,013 lines (`wc -l`, 2026-09-21). Its root
composable `EnviousWisprApp` takes 42 parameters (`:193-236`): one ui state, one discovery state, one
notices string and 39 callbacks, wired by a 42-argument call in `ui/SettingsActivity.kt:63-114`. The same
file holds the navigation chrome (`AppScaffold`, `SettingsDrawerSheet`, `DestinationIcon`, `MenuGlyph`,
`BackGlyph`, `:464-737`) and ten reusable settings components used by seven other `ui/` files
(`ScreenContainer`, `SettingsGroup`, `SettingsSliderRow`, `SettingsToggleRow`, `SettingsActionRow`,
`MicrophoneGlyph`, `ReadinessChip`, `StatusPill`, `StatusDot`, `AutoPasteAvailability.statusDescription`,
`:739-1013`). The audit (`docs/audits/2026-09-20-senior-audit.md` REF-07) grades this MEDIUM/High against
`architecture-rules.md` RULE: keep-central-types-thin, which already names this file as a standing
extraction target. Adding a screen today touches the root signature, the activity call and the render
`when`; a change to a shared row is reviewed in the same unit as navigation.

## 2. Goals & non-goals

### 2.1 Goals
1. `EnviousWisprApp` takes state plus grouped actions: `(uiState, providerDiscovery, licenseNotices,
   actions: AppActions)`. Verifiable: four parameters.
2. Navigation and drawer chrome live in `AppNavigation.kt`; the reusable rows, chips and glyphs live in
   `SettingsComponents.kt`; the shell defines none of them. Verifiable: a shape guard.
3. Every screen stays in its existing owning file; no screen body moves.
4. Navigation behaviour and state restoration are unchanged: the four `rememberSaveable` values keep
   their positional scope and fallbacks; the three-row `AppShellNavigationTest` passes on the AVD.
5. The route resolution that lives inline today (saved tab name → tab with a History fallback; saved
   page name → page or null) becomes a pure object with JVM rows, one per route class.

### 2.2 Non-goals
- Folding `providerDiscovery` or `licenseNotices` into `EnviousWisprUiState` (state ownership, a
  different change).
- Changing any label, glyph, colour, transition, snackbar rule or WorkManager read.
- Touching `SettingsPages.kt`, `PolishScreen.kt`, `HistoryScreen.kt`, `DictionaryScreen.kt`,
  `TranscriptionScreen.kt`, `OnboardingScreen.kt`, `ModelCards.kt`, `PolishStatusChip.kt` beyond nothing:
  they call the moved helpers by unqualified name in the same package, so they do not change.
- Broadly changing existing public UI declarations to `internal` remains #191; this change performs only
  the five private-to-internal widenings the file split requires.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

`ui/SettingsActivity.kt:63-114` is the ONLY caller of `EnviousWisprApp` (`/usr/bin/grep -rn
"EnviousWisprApp(" app/src`): it passes `uiState` (collected from `AppViewModel`), `providerDiscovery`,
`thirdPartyNotices` and 39 lambdas, 34 of them `viewModel::method` references, four of them activity
intents or permission launches (`onStartDictation`, `onRequestMicrophone`, `onRequestNotifications`,
`onOpenAccessibility`) and one `::refreshReadiness`. Inside the shell (`AppShell.kt:238-447`): the
loading gate, `ModelWorkReadinessObserver(onRefreshReadiness)`, the onboarding gate (returns early with
`OnboardingScreen`), four `rememberSaveable` values (`destinationName`, `settingsPageName`,
`lastShownWriteSequence`, `expandedTranscriptId`), a `BackHandler` armed only while a page is open, the
snackbar `LaunchedEffect` over `PolishSnackbarPolicy`, the Polish-tab-only WorkManager read, then
`ModalNavigationDrawer` → `AppScaffold` → `AnimatedContent` over the sealed `Screen`, whose exhaustive
`when` composes four tab screens and nine pages, each from its own file.

The ten reusable components are consumed by (`/usr/bin/grep -rln` over `app/src/main`):
`DictionaryScreen.kt`, `ModelCards.kt`, `OnboardingPermissionIcon.kt`, `PolishScreen.kt`,
`PolishStatusChip.kt`, `SettingsPages.kt`, `TranscriptionScreen.kt`, all in `com.envi.wispr.ui`, all by
unqualified name.

### 2. Find the existing authority before proposing one

- **Grouped actions**: `/usr/bin/grep -rn "Actions\b\|class .*Actions(" app/src/main/java/com/envi/wispr/ui`
  finds no action-group type; the screens take individual lambdas. `new authority proposed`:
  `AppActions` (proposed).
- **Route resolution**: the two `entries.firstOrNull { it.name == saved }` reads at `AppShell.kt:268-269`
  and `:280-282` are the only readers of the saved strings (the comment at `:266-267` says so for the
  tab). No other file parses a destination name. `new authority proposed`: `AppRoutes` (proposed).
- **Component files**: the precedent for "a file per concern in `ui/`" is the rule itself
  (`architecture-rules.md` RULE: keep-central-types-thin names `TranscriptionScreen.kt`,
  `PolishScreen.kt`, `SettingsPages.kt`, `ModelCards.kt`, `HistoryScreen.kt`, `DictionaryScreen.kt`).
  `PolishStatusChip.kt` already holds one chip family outside the shell.

### 3. Read prior attempts and live direction

Gate 0 comment on #190. Binding: screens leave the shell one file at a time (#39, #47, #48, the
History extraction 2026-09-02); the four route values are `rememberSaveable` and survive rotation; "one
History card open" is ONE nullable id beside the route state; the snackbar memory lives above the
animated body (#67); the Polish WorkManager read is computed only on that tab (RULE: own-state-locally);
`Screen` is sealed so the render `when` is exhaustive; the drawer is rendered from `SettingsPage.entries`
so a page cannot be unreachable. The catalog `decision` table has no row on the shell's structure (this
is Android-only plumbing).

### 4. Boundaries a naive design would miss

- **Recomposition**: today the 39 lambdas are re-created on every recomposition of the activity's
  `setContent` block (`viewModel::x` allocates). Grouping them into one object created the same way
  would make every screen see a new `actions` value each time. The activity builds `AppActions` inside
  `remember(viewModel)`, so the object is stable across recompositions; the four activity-bound lambdas
  capture `this` (the activity), which outlives the composition.
- **Saved state**: the four values retain positional scoping by staying in the same composable, in the
  same order and control-flow position. No custom `rememberSaveable` key is added; that parameter is
  deprecated because it bypasses positional scoping. This protects activity and process recreation for the
  installed build; the plan does not claim saved-state compatibility across a package replacement, and
  `AppRoutes` separately handles an older enum name if one is restored. Neither existing
  `AppShellNavigationTest` row recreates the activity (they press system back and the back arrow,
  `AppShellNavigationTest.kt:92-131`), so this change ADDS a third row,
  `anOpenSettingsPageSurvivesActivityRecreation` (proposed), that opens Storage, calls
  `composeRule.activityRule.scenario.recreate()` and asserts the Back control and the Storage subtitle are
  still displayed (G1 D4).
- **Visibility**: `AppDestination` is `private` today and is read by `AppScaffold`, `DestinationIcon`
  and `Screen`; once those move to `AppNavigation.kt` it must become `internal`. `Screen` and
  `PageChrome` move with the scaffold and stay `private` to that file only if the shell does not name
  them; the shell builds `Screen` and `PageChrome` values, so both become `internal` too.
- **Source-text drift guards**: `paste/AutoPasteWiringTest.noReadinessSurfaceReportsThePermissionAsIfItWereLiveness`
  reads `ui/AppShell.kt` + `ui/SettingsPages.kt` and asserts every `AutoPasteAvailability` member is
  named in that text; today the names come from `statusDescription()`'s `when` in the shell. After the
  move the haystack must include `ui/SettingsComponents.kt` or the guard goes red for the wrong reason.
  `ui/TriggerNameTest` reads the shell for "right button"; the moved chrome carries user-facing strings
  ("Open settings menu", "Start dictation", tab labels), so the new files join its list.
- **Instrumented oracle**: `AppShellNavigationTest` drives the real `SettingsActivity`; this change adds
  only the activity-recreation row described above, and the suite is the behaviour oracle for goal 4. `connectedDebugAndroidTest` uninstalls the app afterwards; on
  the AVD that is acceptable and the emulator walk re-installs.

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| One caller of the root | `/usr/bin/grep -rn "EnviousWisprApp(" app/src` → `SettingsActivity.kt:63` and the definition |
| 39 callbacks | counted from `AppShell.kt:193-236`: 42 parameters (42 lines ending in a comma) minus `uiState`, `licenseNotices`, `providerDiscovery`; the activity call has 42 named arguments |
| Ten reusable components and their seven consumers | `/usr/bin/grep -rln "ScreenContainer\|SettingsGroup(\|SettingsSliderRow\|SettingsToggleRow\|SettingsActionRow\|MicrophoneGlyph\|ReadinessChip\|StatusPill\|StatusDot\|statusDescription()" app/src/main` → the shell plus seven files listed in §2.5.1 |
| Two source-text guards read the shell | `/usr/bin/grep -rn "AppShell" app/src/test` → `TriggerNameTest.kt:19`, `AutoPasteWiringTest.kt:277-330`; `SilenceStopSettingsTest` reads `TranscriptionScreen.kt` for `SettingsGroup("Recording")`, a CALL, so only the helper's name must survive |
| The saved-name readers are the only parsers | `AppShell.kt:266-267` comment; `/usr/bin/grep -rn "AppDestination\|SettingsPage.entries" app/src/main` → the shell only |
| Nothing pending touches these files | `git diff --name-only origin/main...origin/worktree-issue-186-session-coordinator` and `...origin/worktree-issue-189-provider-client-split` contain no `AppShell.kt`, `SettingsActivity.kt` or screen file |

No problem-only Codex consult: the who-calls-whom is one caller and seven same-package consumers, all read.

## 3. Design

Four files in `com.envi.wispr.ui` where one stood:

1. **`AppActions.kt`** (proposed): `AppActions` (proposed), an internal immutable class of ten groups,
   each an internal class of lambdas named after the CAPABILITY that owns the action; screens that share a
   capability read the same group (G1 D1):
   - `ShellActions` (proposed): `onStartDictation`, `onRefreshReadiness`
   - `PermissionActions` (proposed): `onRequestMicrophone`, `onRequestNotifications`, `onOpenAccessibility`
     (read by onboarding, the Microphone page and the Permissions page)
   - `OnboardingActions` (proposed): `onOnboardingStep`, `onDismissOnboarding`, `onResumeOnboarding`,
     `onCompleteOnboarding` (read by onboarding and the Permissions page's continue-setup control)
   - `HistoryActions` (proposed): `onSearchChange`, `onKeep`, `onDelete`, `onDeleteAll`
   - `DictionaryActions` (proposed): `onSearchChange`, `onAdd`, `onEdit`, `onDelete`, `onBulkDelete`, `onImport`
   - `TranscriptionActions` (proposed): the five cleanup and silence callbacks
   - `PolishActions` (proposed): `onSetMode`, `onSetS1Control`, `onSaveProviderSettings`,
     `onClearProvider`, `onCheckKey`, `onKeyDraftChanged`, `onLoadCachedModels`
   - `MicrophoneActions` (proposed): `onInputDevicePickChanged`, `onShowBluetoothTipsChanged`, `onKeepEarbudsReadyChanged`
   - `ClipboardActions` (proposed): `onAutoCopyChanged`, `onRestoreClipboardChanged`, `onSmartInsertionChanged`
   - `AppearanceActions` (proposed): `onDynamicColorChanged`, `onBubbleLookChanged`
   Thirty-nine lambdas (2 + 3 + 4 + 4 + 6 + 5 + 7 + 3 + 3 + 2), the same types as today, grouped; no lambda changes signature. Plain classes
   (not `data class`): identity equality is what `remember` gives and value equality over lambdas buys
   nothing.
2. **`AppNavigation.kt`** (proposed): `AppDestination` (now `internal`), `SettingsPageGroup`,
   `SettingsPage`, `Screen` (now `internal`), `PageChrome` (now `internal`, the shell constructs it), `AppScaffold` (now `internal`),
   `SettingsDrawerSheet` (now `internal`), `DestinationIcon`, `MenuGlyph`, `BackGlyph`, moved verbatim,
   plus `AppRoutes` (proposed), an internal object with `destination(savedName: String): AppDestination`
   (the History fallback and its comment) and `settingsPage(savedName: String?): SettingsPage?`.
3. **`SettingsComponents.kt`** (proposed): the ten reusable components, moved verbatim, visibility
   unchanged (`internal`).
4. **`AppShell.kt`** (kept): `EnviousWisprApp(uiState, providerDiscovery, licenseNotices, actions)` with
   the loading gate, the readiness observer, the onboarding gate, the four `rememberSaveable` values in
   their present order (now resolved through `AppRoutes`), the back handler, the snackbar effect, the
   Polish WorkManager read, the drawer, the scaffold call and the exhaustive `Screen` `when`, each screen
   call reading its group (`actions.history.onKeep` and so on). Target under 300 lines.

`ui/SettingsActivity.kt`: builds `val actions = remember(viewModel) { AppActions(...) }` inside
`setContent` and calls `EnviousWisprApp(uiState, providerDiscovery, thirdPartyNotices, actions)`. The four
activity-bound lambdas move into the groups unchanged.

**Alternatives rejected.**
- *One flat `AppActions` with 39 members.* Removes the parameter count without giving a screen a
  narrow view; a screen file would take the whole bag. Capability groups give the shell a narrow, coherent
  contract while allowing screens that share a capability to read the same group.
- *Fun interfaces per group implemented by the ViewModel.* Ties the ViewModel to a UI contract and
  makes the four activity-bound actions awkward. Lambdas keep the ViewModel unaware of the UI.
- *Moving each screen's `when` arm into its screen file.* The render `when` IS the shell's job (goal 1
  of the issue: destination state and screen composition); moving arms out would scatter the exhaustive
  routing the `Screen` type exists to keep in one place.
- *A `navigation/` package.* One file in `ui/` follows the six precedents; a package move is REFACTOR
  tier by the routing table and is not asked for.

### 3.5 Build chunks

Each chunk ends with `./gradlew :app:testDebugUnitTest --tests 'com.envi.wispr.ui.*' --tests
'com.envi.wispr.paste.AutoPasteWiringTest' --tests 'com.envi.wispr.settings.*'` and `:app:assembleDebug`
green.

1. **Components and navigation out.** Create `SettingsComponents.kt` and `AppNavigation.kt` by moving the
   declarations verbatim; widen `AppDestination`, `Screen`, `PageChrome`, `AppScaffold`,
   `SettingsDrawerSheet` to `internal` in the same change; add `AppRoutes` and point the two saved-name
   reads at it; repoint both source-text guards; add `AppRoutesTest` (proposed), `AppNavigationShapeTest` and the
   activity-recreation row in `AppShellNavigationTest`, and compile the instrumentation APK with
   `:app:assembleDebugAndroidTest`. The root signature is unchanged; the app builds and behaves the same.
   The three-row device suite runs once, after the review gate, with the one build.
2. **Grouped actions.** Add `AppActions.kt`; change the root signature to the four parameters; build the
   remembered holder in `SettingsActivity`; every screen call reads its group. Delete the 39 callback
   parameters.
3. **Final structural guard and knowledge.** `AppShellShapeTest` (proposed), revert receipts, the two
   knowledge updates (`architecture-rules.md` RULE: keep-central-types-thin's file list and `wc -l` line,
   `architecture.md` source map `ui/` row). No production change.

## 3b. Ownership justification

The chrome lives in `AppNavigation.kt` because it is what the four tabs and nine pages share and nothing
else; the alternative, leaving it in the shell, is the defect. The reusable rows live in
`SettingsComponents.kt` because seven `ui/` files consume them and none owns them; the alternative,
`SettingsPages.kt`, would make one screen file the owner of every other's rows. `AppActions` lives in
`ui/` beside the shell because it is the shell's contract with the activity and nothing outside `ui/`
names it.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `EnviousWisprApp` | 42 parameters → `(uiState, providerDiscovery, licenseNotices, actions)` | `SettingsActivity` (the one caller) builds `AppActions` once per ViewModel |
| `AppActions` and ten groups (proposed) | new, `internal` | the activity fills them; the shell reads them |
| `AppDestination`, `Screen`, `PageChrome`, `AppScaffold`, `SettingsDrawerSheet` | `private` → `internal` | reachable from the shell and from `app/src/test`; nothing else names them |
| `AppRoutes` (proposed) | new; owns the two saved-name resolutions | the shell; `AppRoutesTest` |
| the ten reusable components | file move, same names, same visibility | seven screen files compile unchanged |
| `SettingsPage`, `SettingsPageGroup` | file move | `SettingsPages.kt` and the drawer compile unchanged |

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every `rememberSaveable` in the shell | `destinationName` (`:265`), `settingsPageName` (`:270`), `lastShownWriteSequence` (`:274`), `expandedTranscriptId` (`:279`): all four stay in `EnviousWisprApp` in this order; none moves into a child composable |
| Every `remember` | `snackbarHostState` (`:275`), `drawerState` (`:283`), `scope` (`:284`): stay in the shell; `rememberScrollState` (`:568`) moves verbatim with `SettingsDrawerSheet` to `AppNavigation.kt` |
| Every effect | `ModelWorkReadinessObserver` (`:245`), `BackHandler` (`:290`), `LaunchedEffect` on the snackbar (`:292`), the two `collectAsStateWithLifecycle` reads inside the Polish gate (`:307-313`): stay in the shell |
| Every callback the shell wraps rather than forwards | `onStartDictation` (haptic then forward, `:337-340`), `onSave` (adds the self-hosted defaults, `:397-399`): both wrappings stay in the shell; the groups carry the raw lambdas |
| Every user-facing string in the moved chrome | "Open settings menu", "Back", "Start dictation", the four tab labels, the four group headings, the nine page titles and subtitles: unchanged, and `TriggerNameTest` reads the new file |
| Every `when` over `AppDestination` or `SettingsPage` | the render `when` (`:358-441`, stays), `DestinationIcon` (`:617`, moves), the drawer (`:560`, moves): all exhaustive, no `else`, unchanged |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| root signature | `SettingsActivity:63-114` | 42 named arguments | `AppActions` built in `remember(viewModel)`, four arguments | yes | compile; `AppShellNavigationTest` on the AVD |
| component move | `DictionaryScreen`, `ModelCards`, `OnboardingPermissionIcon`, `PolishScreen`, `PolishStatusChip`, `SettingsPages`, `TranscriptionScreen` | call by unqualified name | same | none | compile |
| component move | `AutoPasteWiringTest:277-330` | haystack is `AppShell.kt` + `SettingsPages.kt` | haystack adds `SettingsComponents.kt` | yes | the row stays green; its mutation (the permission token written into the components file) is red |
| chrome move | `TriggerNameTest:19` | reads `AppShell.kt` | reads `AppShell.kt`, `AppNavigation.kt`, `SettingsComponents.kt` too | yes | the row |
| `SettingsGroup` name | `SilenceStopSettingsTest:97-98` | finds `SettingsGroup("Recording")` in `TranscriptionScreen.kt` | same | none | the row |
| navigation | `AppShellNavigationTest` (androidTest) | drives the real activity | same, plus restoration across recreation | yes, one restoration row added | run on the AVD |

## 7. Failure-mode × caller table

No new failure mode: the change moves declarations and groups parameters. The one behaviour-adjacent
risk, a `rememberSaveable` key shift dropping a route on rotation, has the instrumented row as its
oracle; a failure there is a red row, not a shipped defect.

## 8. Caller-visible signals audit

| Signal | Carried by | Change |
|---|---|---|
| `settingsPageName == null` means a tab is showing | `PageChrome?` to the scaffold, `Screen.Tab`/`Screen.Page` | none |
| an unknown saved tab name means History | `AppRoutes.destination` | moved, same rule |
| an unknown saved page name means no page | `AppRoutes.settingsPage` | moved, same rule |
| `expandedTranscriptId == null` means every History card closed | shell state | none |
| `topBarBadge == null` means no Polish badge | scaffold parameter | none |

## 9. Fallback source-of-truth audit

Not present in this change: no limb, no fallback text; the only fallback is the History tab for an
unknown saved name, owned by `AppRoutes` and pinned by `AppRoutesTest`.

## 10. File-by-file changes

Production, `app/src/main/java/com/envi/wispr/ui/`:
- `AppShell.kt`: shrinks to the root composable and its route state.
- `AppActions.kt`, `AppNavigation.kt`, `SettingsComponents.kt`: new, contents in §3.
- `SettingsActivity.kt`: the call site.

Tests, `app/src/test/java/com/envi/wispr/`:
- `paste/AutoPasteWiringTest.kt`: haystack gains `ui/SettingsComponents.kt`; the comment naming
  "onboarding step 4 in `ui/AppShell.kt`" is corrected to where the surfaces live now.
- `ui/TriggerNameTest.kt`: the user-facing file list gains `ui/AppNavigation.kt` and `ui/SettingsComponents.kt`.
- `ui/AppRoutesTest.kt` (proposed): the two route-resolution rows in §11.2.
- `ui/AppNavigationShapeTest.kt` (proposed): the drawer grouping row in §11.2.
- `ui/AppShellShapeTest.kt` (proposed): rows in §11.2.

Instrumented, `app/src/androidTest/java/com/envi/wispr/ui/`:
- `AppShellNavigationTest.kt`: gains the activity-recreation row.

Knowledge (main checkout, gitignored): `architecture-rules.md` RULE: keep-central-types-thin (the file
list of precedents gains the three files; the `wc -l` line stays as the regeneration command);
`architecture.md` FACT: source-map `ui/` row names the shell's three companions.

## 11. Testing

1. **Class of every new test.** `AppRoutesTest`: Product Outcome (when it fails the app opens on the
   wrong tab after an update or drops the user onto a page that no longer exists). `AppNavigationShapeTest`:
   Drift Guard, counted as such: it pins the drawer's direct derivation from both enum entry sets by
   reading `AppNavigation.kt` as text, because a JVM calculation that groups `SettingsPage.entries` would
   rebuild the expected value with the mechanism under test; the emulator walk is the Product Outcome
   evidence for the drawer. `AppShellShapeTest`: Drift Guard, counted as such. The added
   `AppShellNavigationTest` row is Product Outcome (when it fails, rotating the phone drops the user off
   the page they had open). The two repointed guards keep their classes.
2. **Revert that turns each red.** Named per row below; performed once, receipts in
   `docs/audits/2026-09-21-190-revert-receipts.txt` (proposed).
3. **Deliberately not tested on the JVM.** The scaffold, drawer and glyphs (Compose UI; the instrumented
   navigation test and the emulator walk are the oracle); recomposition counts (no observable outcome
   named; the `remember(viewModel)` wrapper is a reasoned improvement, not a measured one).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (presentation).
- **Recipe:** on the AVD, `./gradlew :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.envi.wispr.ui.AppShellNavigationTest` with the
  report timestamp read (`android-tooling.md` RULE: read-the-count-not-the-exit-code); then reinstall
  and `scan()` through the harness to walk every tab and every drawer page.
- **Expected observation:** 3 instrumented rows pass with a fresh timestamp; `scan()` reaches all four
  tabs and all nine pages and every switch reads back.
- **Phone state to restore afterwards:** none on the AVD beyond `restore()`; the founder's phone sees
  this only as part of a later build.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `anUnknownSavedTabNameLandsOnHistory` (proposed) in `AppRoutesTest` | Product Outcome | `AppRoutes.destination("Home")` is `History`; every current name resolves to itself (each enum member's literal name as input, the member as the expected value) | return `AppDestination.Dictionary` for an unknown saved tab name (History is the first entry, so "return the first entry" would stay green) |
| `anUnknownSavedPageNameShowsTheTab` (proposed) in `AppRoutesTest` | Product Outcome | `AppRoutes.settingsPage("Home")` is null; null is null; every current page name resolves to itself | return the first page for an unknown name |
| `drawerIsDerivedFromTheCompletePageEnums` (proposed) in `AppNavigationShapeTest` (proposed) | Drift Guard | `AppNavigation.kt` contains the literal `SettingsPageGroup.entries.forEach { group ->` and the literal `SettingsPage.entries.filter { it.group == group }`, so the drawer is derived from both complete enum sets and cannot omit or duplicate a page | append `&& it != SettingsPage.Storage` to the page filter; the exact complete-filter literal no longer matches |
| `anOpenSettingsPageSurvivesActivityRecreation` (proposed) in `AppShellNavigationTest` (AVD) | Product Outcome | open the drawer, open Storage, `scenario.recreate()`, the Back control and the Storage subtitle are still displayed | replace `settingsPageName`'s `rememberSaveable` with `remember`; the restored Storage assertion turns red |
| `AppShellShapeTest` (proposed) | Drift Guard | the set of names declared by `fun` in `AppShell.kt` (regex `^\s*(?:private\|internal\|public)?\s*fun\s+(?:AutoPasteAvailability\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(`, which also catches the extension) intersects the fifteen forbidden names (ten components, five chrome) in the empty set; the exact four-parameter signature `internal fun EnviousWisprApp(uiState: EnviousWisprUiState, providerDiscovery: ProviderDiscoveryUiState, licenseNotices: String, actions: AppActions) {` matches as a whitespace-tolerant regex; `SettingsActivity.kt` matches `val actions = remember(viewModel) { AppActions(` | move one component back; add a fifth parameter; move `AppActions(...)` outside `remember(viewModel)` |
| `AutoPasteWiringTest` (repointed) | Drift Guard | unchanged assertions over the widened haystack; the widening's new coverage is the direct-permission prohibition over `SettingsComponents.kt` | write the token `accessibilityPermitted` into `SettingsComponents.kt` (deleting an arm from `statusDescription` stays green because `SettingsPages.kt` also names every member, measured in the receipts) |
| `TriggerNameTest` (repointed) | Drift Guard | no "right button" in the five `ui/` files (`AppShell.kt`, `OnboardingScreen.kt`, `SettingsPages.kt`, `AppNavigation.kt`, `SettingsComponents.kt`) or `about/ReleaseNotes.kt` | write "right button" into `AppNavigation.kt` |
| `AppShellNavigationTest`'s two existing rows (AVD) | Product Outcome | open a page and come back; the back arrow | unchanged |

## 12. Blast radius & rollback

- **Touched:** `ui/AppShell.kt`, `ui/SettingsActivity.kt`, three new production files, three new unit-test
  files, two repointed unit tests, and one modified instrumented test.
- **Not touched:** every screen file, `AppViewModel.kt`, `PolishSnackbarPolicy`, manifests, Gradle,
  anything outside `ui/` and its tests.
- **Rollback:** revert the squash commit; no schema, no stored format, no saved-state key change.
- **Debt:** none; no shim, no deferred extraction. #191 (visibility) follows and is independent of this
  file layout.

## 13. Ship criteria specific to THIS change

- [ ] `EnviousWisprApp` has four parameters and `AppShell.kt` is under 300 lines (`wc -l`).
- [ ] `AppShellNavigationTest` passes on the AVD with a fresh report timestamp; `scan()` walks every
      tab and page.
- [ ] Both source-text guards pass with their assertions unchanged and their mutations red.
- [ ] Codex ALL-CLEAR with a confirming rerun.

## 14. Open questions

None that block.

## 15. Related

#190 (this), #185 (the audit, REF-07), #39/#47/#48 and the History extraction (the prior shell splits),
#191 (visibility, follows), #186/#189 (independent, pending the founder's pass).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas, or N/A with a specific reason
- [x] §2.5 grounded in real code before §3 was written, never the reverse
- [x] §4-9 answered, briefly or in full, none struck through
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
