# Issue #47 — Drop Home, four tabs, settings behind a drawer — 2026-08-31

GitHub issue: `#47`. Tier: MEDIUM. Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/java/com/envi/wispr/ui/**`, `app/src/test/java/com/envi/wispr/paste/AutoPasteWiringTest.kt`.
Detected with `git diff --name-only $(git merge-base origin/main HEAD)..HEAD`; the merge base is
`fix/16-auto-paste-readiness`, which this branch is stacked on.

**PAR rows closed:** `PAR-007` (What's New) partially, by a release-notes page with no unread marker. No
other row is claimed.

**Hardware UAT:** Y.

Success in plain English: the founder opens EnviousWispr on the S26 Ultra and lands on History. Four tabs
sit along the bottom: History, Dictionary, Transcription, AI Polish. A hamburger sits in the top left. He
taps it, a menu slides in with What's New, Appearance, Microphone, Sounds, Clipboard, Permissions and
Open Source Licenses. He opens Appearance, changes the colour setting, presses the back arrow, and is
back where he was with the tab he left still selected. He starts a dictation from the microphone button
in the top bar, speaks into Gmail, and the words land.

## Preface — Consolidation

**Dominant root: a settings surface.** Today the app expresses one concept five different ways: a
bottom-bar destination (SettingsScreen), two ModalBottomSheets (TextCleanupSettingsSheet,
ClipboardInsertionSettingsSheet), an `AlertDialog` (PolishSettingsDialog), a second bottom-bar
destination that is really a settings page (ModelsScreen), and a landing page that restates permission
state (HomeScreen). Each has its own container, its own header, its own dismissal gesture, and its own
answer to "how do I get out of here".

**One owner: `SettingsPage`**, an enum in `ui/AppShell.kt` carrying the title, the subtitle and the drawer
group, rendered by one full-screen page container with one back control. Everything reachable from the
drawer is a `SettingsPage`; nothing else is.

**Consolidation sites, enumerated:**

| Site today | After |
|---|---|
| HomeScreen (`ui/AppShell.kt:412`) | deleted; its readiness surfaces become `SettingsPage.Permissions` |
| SettingsScreen (`:1246`) | deleted; its rows are distributed to the pages that own their subject |
| ModelsScreen (`:1082`) | deleted; its two `ModelCard`s move beside the settings they belong to |
| ClipboardInsertionSettingsSheet (`:1394`) | `SettingsPage.Clipboard` |
| TextCleanupSettingsSheet (`:1475`) | the text-cleanup section of the Transcription tab |
| PolishSettingsDialog (`:1560`) | the body of the AI Polish tab |

**Every line number in this table and in §1 and §2.5 is the file BEFORE this change.** They are the
evidence for the problem, not a description of the result; §10 describes the result.

**Not consolidated, and stated so it is not read as an oversight:** `OnboardingScreen` keeps its own
full-screen flow above the shell, because it is a one-time gated sequence rather than a place you
navigate to.

The licenses `AlertDialog` in `SettingsActivity` IS consolidated, which the first draft of this plan said
it would not be. The activity still reads the bundled asset off the composition and now passes the text
down, so no file read moved into a composable and `kotlin-patterns.md`
RULE: compose-state-is-hoisted-and-lifecycle-aware still holds. The founder asked for a back button on
every settings surface, and a dialog does not have one.

## Preface — User Rubric

**Persona: Frank Chen**, 72, retired teacher with arthritis. He is the binding persona here because his
stated want is "≤3 settings" and a screen he can understand without being taught.

1. **Who is this user in this moment?** Frank has just installed EnviousWispr and opened it for the second
   time. Thirty seconds ago he was reading a message from his daughter. Thirty seconds from now he wants
   to have replied by speaking.
2. **Why would they want this?** "I opened it and there was a page with a big button and some coloured
   dots. I did not know what any of it meant, and I could not find where to turn the noises off."
3. **How would they invoke it?** He opens the app from the launcher when something is not working, and
   otherwise never opens it at all: the side button is his dictation. So the app's job is to be a place
   where he can FIND a setting, not a place he starts from.
4. **What app are they in?** Messages and Gmail, the two the founder dictates into daily.
5. **What is their natural input?** Not applicable to this change: nothing here transforms speech.
6. **What does success feel like?** He opens the menu, reads seven plain words, and taps the one he meant.
7. **What does wrong-not-broken look like?** He taps "Sounds", finds a page that promises settings and
   delivers none, and concludes the app is half-finished. That is the risk this change carries and §7
   answers it: the page states what happens today instead of listing switches that do nothing.
8. **What would a power user hack around this to get?** Priya would want the settings she uses most as
   tabs rather than two taps deep. That is exactly what the four tabs are.
9. **What level of control would they want?** Frank wants fewer visible choices, Priya wants all of them
   reachable. The drawer resolves this: four tabs are the everyday surface, and everything else is one
   deliberate tap away rather than absent.

### Cross-persona check

Priya, Diana and Aaron never open the app in a working week; the side button and the hotkey are the
product for them, so the shell only has to be navigable when something breaks. Marcus and Elena read
settings before trusting the app, and both are better served by named pages than by a wall of rows.
Meera and Frank want fewer things on screen. The tension is Priya's depth against Frank's simplicity and
the drawer resolves it in Frank's favour on the default screen.

---

## 0. TL;DR

The app opens on a Home page that starts a dictation nobody starts from the app, and repeats readiness
state that belongs beside the permissions it describes. Settings are a fifth tab holding two dead rows and
three bottom sheets. This change deletes Home, makes the bottom bar exactly History, Dictionary,
Transcription and AI Polish, and moves every settings page behind a hamburger drawer in the top left with a
back button on each page. Names and page contents follow macOS `SettingsSection`. Tier MEDIUM because it is
new runtime navigation behaviour across every screen, not a layout tweak. Evidence: the unit suite with the
readiness-surface guard updated to the new surface names, and a hardware run on the S26.

## 1. Problem

Three concrete failures, all in `app/src/main/java/com/envi/wispr/ui/AppShell.kt`.

**Home has no job.** HomeScreen (line 412) is the landing destination. Its hero is a
LargeFloatingActionButton that starts a dictation, but the primary entry point is the Samsung side
button (`CLAUDE.md` § Compatibility), so the button is a development convenience on the app's most
prominent surface. The rest of the screen is a readiness card, a setup card, an auto-paste card and four
readiness chips, all of which describe permissions and models that are configured on other screens.

**Two settings rows do nothing.** Issue #39, still open:

```kotlin
SettingsActionRow("Microphone and sounds", "Routing, warm start, cues", null, onClick = {})
SettingsActionRow("Version 0.1.0", "Android parity foundation", null, onClick = {})
```

The first advertises routing, warm start and cues. None of the three exists (#26, #28, #36).

**Settings is a tab holding sheets.** SettingsScreen (line 1246) is a fifth bottom-bar destination whose
rows open TextCleanupSettingsSheet, ClipboardInsertionSettingsSheet and PolishSettingsDialog. AI
Polish, which the founder wants as a destination, is a dialog two taps inside a tab.

## 2. Goals & non-goals

### 2.1 Goals

- The bottom bar holds exactly History, Dictionary, Transcription and AI Polish.
- A hamburger in the top left opens a drawer listing every settings page the app offers.
- Every settings page opened from the drawer has a back control, and the system back gesture does the
  same thing.
- No row in the app is tappable and inert.
- Every readiness surface pinned by `AutoPasteWiringTest` still exists, on the Permissions page.
- The app keeps an in-app way to start a dictation.

### 2.2 Non-goals

- Pinning Light or Dark. `appearance-theme` is `partial` on Android and stays that way.
- An unread marker or badge on What's New. macOS has one; this ships the page only.
- Start and stop sounds, an input picker, Bluetooth routing, warm start. Named as not built, not built.
- Any change to the recorder overlay, the notification, the tile or the side-button path.
- Any change to onboarding, which keeps its own full-screen flow ahead of the shell.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

The thing being changed is the destination the user is looking at, and it is produced and consumed
entirely inside one composable tree in one process.

- **Producer.** `SettingsActivity.onCreate` (`ui/SettingsActivity.kt`) calls `setContent` and hands
  `EnviousWisprApp` a `EnviousWisprUiState` collected with `collectAsStateWithLifecycle`.
- **Owner.** `EnviousWisprApp` (`ui/AppShell.kt:138`) holds the current destination in
  `var destinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }` (line 200) and
  resolves it against `AppDestination.entries` (line 201). This is the only navigation state in the app;
  there is no NavHost and no `navigation-compose` dependency.

  ```
  $ /usr/bin/grep -rn "NavHost\|rememberNavController\|navigation-compose" app/ gradle/ build.gradle.kts app/build.gradle.kts
  (no output)
  ```

- **Consumer.** NavigationSuiteScaffold (line 210) renders the bar from `AppDestination.entries` and the
  body from a `when (current)` over the same enum (line 235). `DestinationIcon` (line 291) draws one glyph
  per entry with an exhaustive `when`.
- **Second entry point.** `VoiceInputActivity` (`ui/VoiceInputActivity.kt:30`) launches
  `SettingsActivity` by Intent with no extras, so it cannot request a destination and needs no change.
- **Third entry point.** `res/xml/accessibility_service_config.xml:10` names `SettingsActivity` as the
  service's settings activity, so Android's Accessibility screen opens the same shell. Also no extras.
- **Persistence.** `rememberSaveable` puts the destination in saved instance state. Nothing writes the
  destination to DataStore or Room, so renaming the enum constants cannot corrupt stored data. The one
  cost is that a process death across the rename restores a name that no longer resolves, which the
  existing `?: AppDestination.Home` fallback already handles and which the replacement keeps.

### 2. Find the existing authority before proposing one

Searched by capability rather than symbol.

- **Drawer.** `/usr/bin/grep -rn "ModalNavigationDrawer\|DrawerState\|NavigationDrawerItem" app/src/` returns
  nothing. `new authority proposed`: `ModalNavigationDrawer` from Material 3, already on the classpath
  through `androidx.compose.material3`, which `AppShell.kt` imports 20 symbols from today.
- **Settings page list.** macOS owns this: `SettingsSection` and `SettingsGroup` in
  `~/Developer/EnviousLabs/EnviousWispr/Sources/EnviousWisprAppKit/Views/Settings/SettingsSection.swift`.
  Labels, subtitles and group membership are ported rather than invented.
- **Back handling.** `/usr/bin/grep -rn "BackHandler" app/src/` returns nothing. `new authority proposed`:
  `androidx.activity.compose.BackHandler`, from `androidx.activity:activity-compose`, which
  `AppShell.kt` already imports `rememberLauncherForActivityResult` from.
- **Release notes.** `/usr/bin/grep -rni "releasenotes\|whatsnew\|changelog" app/src/` returns nothing.
  `new authority proposed`: a `ReleaseNotes` object in `com.envi.wispr.about`.
- **Readiness.** `AppReadiness` (`ui/AppViewModel.kt:53`) and `AutoPasteAvailability` already own every
  fact the Permissions page shows. Nothing new.
- **Model cards.** `ModelCard`, `workUiState`, `preferredModelWork` and `ModelWorkReadinessObserver`
  (`ui/AppShell.kt:1144`, `:1200`, `:1212`, `:1218`) already own model presentation. The Transcription and
  AI Polish pages call them; ModelsScreen itself is deleted.

### 3. Read prior attempts and live direction

- **Founder direction, this session.** Verbatim: "can we get rid of the home page? I only want history,
  dictionary, Transcription, AI Polish -> I want a hamburger menue on the top left that expands out into a
  menue bar where our settings options will live: what's new, appearance, microphone, sounds. These will
  all need a back button. Now I know a lot of these settings haven't been built yet. So we can for the
  time being speak and catalog what settings we do offer."
- **Session log, 2026-08-30 (evening).** `fix/16-auto-paste-readiness` is unmerged and its
  `AutoPasteWiringTest` pins five readiness surfaces, two on Home. Recorded in the Gate 0 comment on #47.
- **Catalog `decision` table.** Read; no row governs shell navigation on any platform.
- **Binding decision not to redesign.** macOS `SettingsSection` labels `speechEngine` "Transcription" and
  `wordCorrection` "Dictionary", and SpeechEngineSettingsView.swift lines 342 to 372 puts
  `fillerRemovalEnabled`, `emojiFormatterEnabled` and `spokenPunctuationEnabled` on that page. Android
  follows both rather than inventing a split.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **Onboarding versus the shell.** `EnviousWisprApp` returns early into `OnboardingScreen` while
  `shouldShowOnboarding` is true (line 184). The drawer, the tabs and the top bar must all be inside the
  branch below that return, or a first-run user gets a hamburger over a setup flow.
- **Loading versus loaded.** The same function returns a "Preparing EnviousWispr" surface while
  `uiState.loading` (line 172). Unchanged, and the shell stays below it.
- **Saved state across process death.** `rememberSaveable` restores both the tab and, in this change, the
  open settings page. A settings page open at process death must restore with its back control working,
  which it does because the back control reads the same saved state.
- **The accessibility service alive versus enabled-but-crashed.** `AutoPasteAvailability` already carries
  the three-way answer. The Permissions page consumes all three; nothing collapses it to a boolean.
- **WorkManager observation.** `ModelWorkReadinessObserver` is called once at the top of
  `EnviousWisprApp` and must stay there, not move onto a page, or model downloads stop refreshing
  readiness when the user is on another tab.
- **A drawer open across a configuration change.** DrawerState is remembered with
  `rememberDrawerState`, which is saveable, so a rotation with the drawer open keeps it open.

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| There is no navigation library in this app | `/usr/bin/grep -rn "NavHost\|rememberNavController\|navigation-compose" app/ gradle/ build.gradle.kts app/build.gradle.kts` returns nothing |
| No drawer exists today | `/usr/bin/grep -rn "ModalNavigationDrawer\|DrawerState\|NavigationDrawerItem" app/src/` returns nothing |
| No back handler exists today | `/usr/bin/grep -rn "BackHandler" app/src/` returns nothing |
| No release-notes source exists today | `/usr/bin/grep -rni "releasenotes\|whatsnew\|changelog" app/src/` returns nothing |
| The app plays no sound and does vibrate | `/usr/bin/grep -rn "SoundPool\|ToneGenerator\|MediaPlayer" app/src/main/java/` returns nothing; `DictationSessionService.HapticCue` (`ui/DictationSessionService.kt:912`) defines `SESSION_TRANSITION`, `SESSION_CANCELED` and `FAILURE`, and `vibrate` is called at lines 392, 442, 768, 791 and 801 |
| No microphone input picker exists | `/usr/bin/grep -rn "AudioDeviceInfo\|setPreferredDevice\|getDevices" app/src/main/java/` returns nothing |
| `AppDestination` has exactly five members and one `when` over it | `ui/AppShell.kt:130-136`; consumers at `:216`, `:235`, `:293` |
| The two dead rows are the only `onClick = {}` in settings | `/usr/bin/grep -n "onClick = {}" app/src/main/java/com/envi/wispr/ui/AppShell.kt` returns lines 1338 and 1343 |
| `versionName` is `0.1.0` | `app/build.gradle.kts` |

## 3. Design

**One shell composable owns two orthogonal pieces of state**: which tab is selected, and which settings
page is open on top of the tabs, if any.

```kotlin
enum class AppDestination(val label: String) {
    History("History"),
    Dictionary("Dictionary"),
    Transcription("Transcription"),
    Polish("AI Polish"),
}

enum class SettingsPage(val group: SettingsPageGroup, val title: String, val subtitle: String) { … }
enum class SettingsPageGroup(val heading: String) { APP("APP"), RECORD("RECORD"), OUTPUT("OUTPUT"), SYSTEM("SYSTEM") }
```

`ModalNavigationDrawer` wraps a `Scaffold`. The scaffold's `topBar` is a `TopAppBar` whose navigation icon
is the hamburger when a tab is showing and a back arrow when a settings page is showing, and whose title is
the tab or page name. Its `bottomBar` is a `NavigationBar` shown only when no settings page is open. A
`BackHandler`, enabled only when a settings page is open, closes the page.

**Rejected: a NavHost from `navigation-compose`.** It would add a dependency and a route-string layer for
two enum-typed pieces of state that never nest, never take arguments and never deep-link. The trade-off is
that a future deep link into a settings page has to be built rather than declared, which is cheap while
`SettingsActivity` is launched with no extras from all three entry points.

**Rejected: keeping NavigationSuiteScaffold.** It can host a `Scaffold` and can itself sit inside a
`ModalNavigationDrawer`, so there is no framework limitation here; it is removed deliberately, to give
the same four phone-style bottom tabs at every window size. The trade-off is losing the automatic
navigation rail on a wide screen. Accepted: the target device is one phone, and the drawer is itself the
wide-screen answer. Corrected after the plan review, which caught the original wording asserting a
limitation that does not exist.

**Rejected: keeping the bottom sheets.** Clipboard and text cleanup become pages. The trade-off is one
more tap to reach text cleanup than a sheet from a row. Accepted because a sheet has no back button and the
founder asked for back buttons.

**The three model surfaces split by subject.** Parakeet goes to Transcription, S1-mini goes to AI Polish,
and the "Broad-language speech and cloud providers" card goes to Transcription because it is about speech
engines. ModelsScreen is deleted rather than hidden.

**Start dictation** becomes a microphone `IconButton` in the top bar, present on the four tabs and absent
on settings pages.

## 3b. Ownership justification

**The shell's navigation state lives on `EnviousWisprApp` because that is where it already lives** —
`destinationName` at `ui/AppShell.kt:200` is the app's only navigation state, and the settings page is the
same kind of state with the same lifetime. The alternative was a NavigationCoordinator-style holder
matching macOS's NavigationCoordinator.swift, but macOS needs one because its window, its menu bar and
its dock icon all navigate; Android has one entry point that never asks for a destination. The trade-off is
that when deep links arrive, this becomes a holder; that is a small refactor of one file.

## 4. Contract deltas

| Type | Before | After | What it now means |
|---|---|---|---|
| `AppDestination` | `Home, History, Words, Models, Settings` | `History, Dictionary, Transcription, Polish` | A tab in the bottom bar, and nothing else. `Home`, `Words`, `Models` and `Settings` are not renamed, they are gone; `Words` becomes `Dictionary` as a rename in fact but not in identity, so a restored saved name falls back to `History`. |
| `SettingsPage` | new | 7 members in 4 groups | A full-screen page reachable only from the drawer, always with a back control. |
| `SettingsPageGroup` | new | 4 members | A heading in the drawer. Ported from macOS `SettingsGroup`. |
| `ReleaseNotes` | new | `entries: List<ReleaseNote>` | The bundled, version-grouped release history. The only source the What's New page reads. |
| `EnviousWisprApp` parameters | `onOpenLicenses: () -> Unit` | `licenseNotices: String` | The licenses dialog in `SettingsActivity` is deleted and the notices become a page with a back control, like every other drawer page. The activity still reads the asset off the composition, so no file read moves into a composable. |

## 5. End-to-end state and lifecycle audit

| Population | Enumerated |
|---|---|
| Every consumer of `AppDestination` | `ui/AppShell.kt:216` (bar items), `:235` (body `when`), `:293` (`DestinationIcon` `when`). All three change. No other file names the type: `/usr/bin/grep -rn "AppDestination" app/src/` returns only `AppShell.kt`. |
| Every screen composable in the old shell | HomeScreen (deleted), `HistoryScreen` (kept, header changes), WordsScreen (kept, renamed to `DictionaryScreen`), ModelsScreen (deleted, its two cards re-homed), SettingsScreen (deleted, its rows re-homed), `OnboardingScreen` (untouched). |
| Every bottom sheet and dialog in settings | TextCleanupSettingsSheet (becomes part of Transcription), ClipboardInsertionSettingsSheet (becomes the Clipboard page), PolishSettingsDialog (becomes the AI Polish tab). |
| Every state that survives process death | tab name and open settings page, both `rememberSaveable`; drawer open state, `rememberDrawerState`. |
| Every path that can leave a settings page | the back arrow, the system back gesture via `BackHandler`, and picking a different page from the drawer. Enumerated; there is no fourth. |
| Every early return above the shell | `uiState.loading` (line 172) and `shouldShowOnboarding` (line 184). Both stay above the drawer. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `AppDestination` members | `AppShell.kt` bar, body, icons | five destinations | four | yes | it does not compile otherwise; `when` is exhaustive with no `else` |
| HomeScreen deleted | `AutoPasteWiringTest.noReadinessSurfaceReportsThePermissionAsIfItWereLiveness` | names two surfaces "the Home …" | names the same two evidence literals on the Permissions page | yes, test prose only | the test's evidence strings are unchanged, so a genuine regression still turns it red |
| SettingsScreen deleted | same test, "the Settings auto-paste row" | reads `AppShell.kt` | still reads `AppShell.kt`, row is on the Permissions page | yes, test prose only | same |
| ModelsScreen deleted | nothing outside `AppShell.kt` | — | — | no | `/usr/bin/grep -rn "ModelsScreen" app/src/` returns only `AppShell.kt` |
| new `ReleaseNotes` | What's New page | — | renders every entry | yes | a unit test asserting the current `versionName` has an entry |
| `EnviousWisprApp` signature | `SettingsActivity.setContent` | passes 30 lambdas | unchanged | no | compiles |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| A settings page has no settings yet (Sounds) | this change | drawer | The page says what happens today: "EnviousWispr vibrates briefly when recording starts and stops." and "Start and stop sounds are not available yet." No switches. | none | not applicable |
| A settings page has one setting and a named gap (Microphone) | this change | drawer | The microphone permission row, then "EnviousWispr listens with the phone's current microphone. Choosing a different microphone is not available yet." | none | tapping the row opens the system permission prompt |
| Restored saved state names a destination that no longer exists | process death across an app update | `AppDestination.entries.firstOrNull { … } ?: AppDestination.History` | History | saved instance state | none needed |
| The accessibility service is permitted but not running | `AutoPasteAvailability.PERMITTED_NOT_RUNNING` | Permissions page | the existing calm card, moved verbatim from Home | none | the existing "Accessibility settings" button |
| Core setup incomplete | `readiness.coreReady` false | Permissions page | the existing setup card, moved verbatim from Home | none | "Continue" resumes onboarding |
| What's New has no entry for this build | a release note not written | What's New page | the entries that exist; the newest is at the top | none | none |

Every sentence above is either moved verbatim from a screen that already shipped it, or new and checked
against `content-brand.md` RULE: no-dashes-in-user-facing-text and RULE: brand-voice-relief-centered.

## 8. Caller-visible signals audit

- **Absence of an open settings page** means "show the bottom bar and the hamburger". It is the null in
  `SettingsPage?` and it is the only thing that decides which navigation icon the top bar draws.
- **`AutoPasteAvailability`'s three-way value** keeps its existing meaning; this change only moves the
  surfaces that read it.
- **Staleness:** `ModelWorkReadinessObserver` stays at the top of `EnviousWisprApp`, so model readiness
  refreshes regardless of which page is open. Moving it onto Transcription would make readiness stale on
  every other page, which is the trap this row exists to name.
- No new identity, presence or value signal is introduced.

## 9. Fallback source-of-truth audit

| Failure branch | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer |
|---|---|---|---|---|---|---|
| restored tab name unknown | `AppDestination.History` | the enum | it is the founder's chosen landing tab and always exists | `entries.firstOrNull { it.name == saved }` | `History` | the body `when` |
| restored settings page unknown | `null` | absence | closing to the tabs is always safe | `SettingsPage.entries.firstOrNull { it.name == saved }` | `null` | the top bar icon |

## 10. File-by-file changes

- **`ui/AppShell.kt`** — `AppDestination` reduced to four. New `SettingsPage`, `SettingsPageGroup` and a
  private `Screen` sealed interface. `EnviousWisprApp` rebuilt around `ModalNavigationDrawer` +
  `Scaffold` + `TopAppBar` + `NavigationBar`, with `AppScaffold` and `SettingsDrawerSheet` beside it.
  HomeScreen, ModelsScreen, SettingsScreen, TextCleanupSettingsSheet,
  ClipboardInsertionSettingsSheet and PolishSettingsDialog deleted. WordsScreen renamed
  `DictionaryScreen`. `DestinationIcon` reduced to four glyphs, plus `MenuGlyph` and `BackGlyph`. The
  shared rows became `internal` so the new screen files can use them. **Net: the file shrank from 2,163
  lines to about 1,540.**
- **`ui/SettingsPages.kt`, `ui/TranscriptionScreen.kt`, `ui/PolishScreen.kt`, `ui/ModelCards.kt`** — new,
  and new because `architecture-rules.md` RULE: keep-central-types-thin says to add a screen file rather
  than a section to `AppShell.kt`. `SettingsPages.kt` holds all seven drawer pages including
  `PermissionsPage`, which is where the readiness surfaces landed.
- **`about/ReleaseNotes.kt`** — new. A `ReleaseNote(version, date, lines)` data class and the bundled list.
- **`ui/SettingsActivity.kt`** — the licenses `AlertDialog` deleted; the notices are passed to the shell.
- **`app/build.gradle.kts`** — `buildConfig = true` so `BuildConfig.VERSION_NAME` exists;
  `material3-adaptive-navigation-suite` removed with its last user.
- **`test/about/ReleaseNotesTest.kt`** — new.
- **`test/paste/AutoPasteWiringTest.kt`** — surface names updated and the source set widened to
  `ui/AppShell.kt` plus `ui/SettingsPages.kt`; every evidence literal unchanged.

### Copy re-asserted rather than moved

`content-brand.md` RULE: rewriting-inherited-copy-re-asserts-every-claim-in-it applies to the model cards,
which move to two different tabs. Three open issues were about exactly those strings, so moving them
verbatim would have re-published three claims already known to be wrong. All three are fixed here:

- **#23** — "already proven on this S26 Ultra" is gone; no shipped string names a phone model.
- **#24** — "the first-party local cleanup model by Superwhisper" is gone. The card describes what the
  model does for the user; attribution stays in the third-party notices.
- **#22** — "separate parity slices", "4 threads" and "Q4_K_M" are gone. The chips are plain words, and
  the languages paragraph is a promise the user can act on.

## 11. Testing

1. **Class of every new test.** `ReleaseNotesTest` is a **drift guard**: when it fails the user sees
   nothing, because it protects the invariant that the shipped `versionName` has release notes. Declared
   as such in its KDoc and not counted as product coverage. `AutoPasteWiringTest` keeps its existing class.
2. **What revert would turn it red?** `ReleaseNotesTest` goes red if `versionName` is bumped without a
   note being added, which is the exact drift it exists to catch. `AutoPasteWiringTest` goes red if any of
   the five readiness surfaces is dropped during the move, which is the specific risk of deleting Home.
3. **What is deliberately NOT tested, and why?** The navigation itself. Asserting that a drawer opens or a
   back handler fires needs a Compose UI test, and this repo has no Compose test rig
   (`/usr/bin/grep -rn "createComposeRule" app/src/` returns nothing). Standing up one for a shell that a
   human is about to drive on hardware would be a harness contract test counted as product coverage,
   which `testing-philosophy.md` RULE: every-test-declares-which-of-four-things-it-protects forbids. The
   hardware run below is the oracle instead. Routed, not skipped: this is worth an issue of its own.

### 11.1 Hardware UAT spec

- **Subsystem:** limb for the shell itself; the run also crosses the heart because it starts a dictation.
- **Recipe:** `device-testing.md` install and drive recipe, then by hand: open the app, confirm the
  landing tab is History and the bar has exactly four items; open the drawer and read the seven rows;
  open each of the seven pages and return with the back arrow, then with the system back gesture; rotate
  the phone with a page open; start a dictation from the top-bar microphone into Gmail.
- **Expected observation:** the tab selected before opening a settings page is still selected after the
  back control. The dictation's words appear in the Gmail draft. No row in the drawer or on a page is
  tappable without doing something.
- **Phone state to restore afterwards:** nothing is changed by this run except the app itself, which is
  reinstalled from the branch build. The accessibility service stays enabled.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `ReleaseNotesTest.theShippedVersionHasANote` | drift guard | the What's New page is never empty for the build the user is running | bump `versionName` with no note |
| `AutoPasteWiringTest.noReadinessSurfaceReportsThePermissionAsIfItWereLiveness` | drift guard | all five readiness surfaces survived the deletion of Home | drop any one of them during the move |

## 12. Blast radius & rollback

- **Touched:** `app` module UI only. One new package, `com.envi.wispr.about`.
- **Deliberately not touched:** `:audio`, `:asr`, `:polish`, the accessibility service, the recorder
  overlay, the notification controller, the tile, Room, DataStore, WorkManager, the manifest, and
  `llama-android`. No schema, no permission, no process.
- **Rollback:** revert the branch's commits. Nothing persists a value this change introduces, so a revert
  needs no migration.

## 13. Ship criteria specific to THIS change

- [ ] The app opens on History, and the bottom bar has exactly History, Dictionary, Transcription and AI Polish.
- [ ] The hamburger in the top left opens a menu, and every page in it comes back with a back arrow and with the system back gesture.
- [ ] Nothing in the app can be tapped without something happening.
- [ ] Confirmed on the founder's S26 Ultra, with one dictation started from the app landing in Gmail.

## 14. Open questions

None blocking. Routed rather than answered here: a Compose UI test rig for the shell; the What's New
unread marker; theme pinning.

## 15. Related

Issues #47 (this); #39, #22, #23 and #24 closed by this; #38 partly; #26, #28 and #36 named on a page as
not built; #16, whose branch this is stacked on and which merges first. Catalog features `whats-new`,
`appearance-theme`. macOS reference SettingsSection.swift, SpeechEngineSettingsView.swift.

## 16. Review log

**Plan review, one combined coverage and grounded round, 2026-08-31.** Direction confirmed. Nine findings,
five FOUND. Adjudication:

| # | Finding | Disposition |
|---|---|---|
| 1 | The API-key draft has no defined lifetime once AI Polish is a long-lived page | ADOPTED. It stays a plain `remember`, is cleared after a successful save and after clearing the provider, and the file's KDoc states it must never move to `rememberSaveable` or into the ViewModel. |
| 2 | The five pinned surfaces and the `Insert` slice window must survive | ADOPTED. All five kept, every evidence literal unchanged, and the guard was verified by deleting the `Insert` chip and watching it go red. |
| 3 | Rejecting NavigationSuiteScaffold on a framework limitation that does not exist | ADOPTED. §3 rewritten to say the removal is deliberate. |
| 4 | Full-screen conversion contract: dismissal, IME, scrolling, local state | ADOPTED IN PART. Scrim dismissal is deliberately replaced by the back arrow and the system gesture, every page scrolls, and AI Polish carries `imePadding()`. **DEVIATION:** the reviewer asked to preserve the sheets' local toggle mirrors; the mirrors are gone and the switches read `uiState.preferences` directly. The vocabulary toggle in `DictionaryScreen` has always read preferences with no mirror, so the mirror is not load-bearing, and keeping it would have left a second home for a value that already has an owner. |
| 5 | The installed-version row is lost | ADOPTED. The version appears in the drawer footer and again at the top of What's New, both from `BuildConfig.VERSION_NAME`. |
| 6 | `ModelCard` fact chips are `AssistChip(onClick = {})`, so they are tappable and inert | ADOPTED. Replaced by a display-only `FactPill`. |
| 7, 8, 9 | No consumer outside `AppShell.kt`; saved-state validation covers any restore; `BackHandler` is the right primitive | NOT FOUND, no action. |

**Code review round 1, `GR-REVIEW-GATE`, 2026-08-31. Verdict DO NOT SHIP.** Twelve axes searched, five
FOUND. Adjudication:

| # | Finding | Disposition |
|---|---|---|
| 1 | Two `when (…​.action)` blocks over `ModelUiAction` carry an `else`, so a new member starts a download instead of failing the build | ADOPTED as written. Both are exhaustive now, and the four members `ModelCard` never routes to `onAction` are named explicitly with the reason. |
| 3 | The API key crosses into the ViewModel | REJECTED, and routed. Four reasons, each checkable: the "never reaches the ViewModel" rule was in my reviewer prompt and not in the repository, whose list is "logs, Room, DataStore, saved state, or intents"; `saveProviderSettings` is byte-identical to `origin/main`, so this change did not introduce the path; the key transits as an argument to `providerRepository.saveProvider`, which encrypts it, and never enters `ProviderSettingsUiState`; and the proposed direction, a composable calling the repository, breaks `kotlin-patterns.md` RULE: compose-state-is-hoisted-and-lifecycle-aware. |
| 6 | A granted permission row keeps its chevron and its tap, and re-launching a granted request draws nothing | ADOPTED. `SettingsActionRow` gained `enabled`, the chevron is drawn only when enabled, and all three permission rows disable once granted. This is the finding that made the change miss its own ship criterion. |
| 7 | A release note promised the clipboard, which is false when auto-copy is off | ADOPTED as written. |
| 8 | Seven symbols wider than their call sites | ADOPTED. `AppDestination` and `MicrophoneGlyph` are `private`; `SettingsPageGroup`, `SettingsPage`, `EnviousWisprApp`, `ReleaseNote` and `ReleaseNotes` are `internal`. |
| 2, 4, 5, 9, 10, 11, 12 | Saved state, back-handler order, insets, the readiness observer's position, the repeated microphone row, the guard failing closed across two files, accidental deletion | NOT FOUND, no action. |
