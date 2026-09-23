# Issue #218 — Each screen owns its state — 2026-09-23

GitHub issue: `#218`. Tier: REFACTOR (the app's view-model layer; every screen's state source moves). Status: APPROVED (coverage adopted; grounded rounds 1 to 4 adopted; round 5 PROCEED-AS-PLANNED).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The change moves code; no parity row changes status.

**Hardware UAT:** Y (the settings app, not the heart). Success for a person: they open the app, every screen shows what it showed before (History with their dictations and search, the Dictionary with search and add, AI Polish with its provider tiles and status, Transcription, Microphone, Clipboard, Appearance, Permissions), a dictation they just made appears at the top of History, and changing a setting sticks. Run on the emulator through `wispr_eyes.scan()`; the founder's phone pass is queued with the rest of the audit work.

## Preface — User Rubric

User Rubric: N/A — a move of the settings app's state into one view model per feature; every screen reads the same values from the same stores, and the dictation path does not touch these classes.

---

## 0. TL;DR

`ui/AppViewModel.kt` (836 lines) holds `EnviousWisprViewModel`, one view model for every screen: History, the Dictionary, AI Polish provider settings and model discovery, readiness and auto-paste, onboarding and every settings toggle. REF-09 of the 2026-09-22 senior audit (against `architecture-rules.md` RULE: own-state-locally) asks for `history/ui/HistoryViewModel` (proposed), `vocabulary/ui/DictionaryViewModel` (proposed), `providers/ui/PolishSettingsViewModel` (proposed) and `ui/ReadinessViewModel` (proposed), with `EnviousWisprUiState` replaced by a small shell projection assembled from the feature states, and `AppShell` receiving grouped state plus the existing grouped `AppActions`. Evidence: the existing screen and policy rows, a new Drift Guard red before and green after, and an emulator walk of every screen.

## 1. Problem

One class, eight concerns, 836 lines; its `uiState` (removed) combines eleven flows, so a History search re-emits the whole app's state and every screen recomposes on any feature's change. The audit cites `AppViewModel.kt:L145-L180`, `L183-L250`, `L286-L380`, `L587-L787`.

## 2. Goals & non-goals

### 2.1 Goals
1. `HistoryViewModel` owns the transcripts, the search, the error, keep/delete/delete-all, and the start-up recovery and prune now in `EnviousWisprViewModel`'s `init`.
2. `DictionaryViewModel` owns the terms, the search, the message and error, add/edit/delete/bulk-delete/import, and the legacy SharedPreferences migration now in `init`.
3. `PolishSettingsViewModel` owns `ProviderSettingsUiState`, `ProviderDiscoveryUiState` (both move with it), the settings mutex, the write sequence, the discovery sequences and draft results, `setPolishMode`, `setS1Control`, `saveProviderSettings`, `removeProviderKey`, `discoverModels`, `keyDraftChanged`, `loadCachedModels`, and the initial load in `init`, statement for statement.
4. `ReadinessViewModel` owns `AppReadiness` (moves with it), `refreshPermissions`, `updateVerifiedModels`, and the auto-paste derivation (`AutoPasteReadiness.observe` over `PasteAccessibilityService.isBound`).
5. `EnviousWisprViewModel` keeps the app preferences, onboarding progress and the settings toggles (`changeSetting` and its thirteen setters); `EnviousWisprUiState` has `loaded` and `preferences`, plus derived `shouldShowOnboarding`.
6. A shell projection `AppUiState` (proposed) groups the six feature states; `EnviousWisprApp(state, licenseNotices, actions)` takes it. Root loading ends after preferences, terms, transcripts, and auto-paste have each emitted, as today's single `combine` did.
7. `SettingsActivity` builds the five view models and one `AppActions` inside one `remember` keyed on all five.

### 2.2 Non-goals
- No behaviour change on any screen; every string, search rule, error sentence, telemetry event and sequence rule moves as it is.
- Add two test dependencies: `kotlinx-coroutines-test` and a JVM `org.json` runtime (§11 2b); no production dependency changes.
- The per-screen composables keep their parameter lists.

## 2.5 Grounding brief

1. Producers and consumers: `SettingsActivity` creates the one view model (`by viewModels`), collects `uiState` (removed) and `providerDiscovery`, builds `AppActions` from its methods, and passes both to `EnviousWisprApp` in `AppShell.kt`, which hands each screen its slice. After: five `by viewModels`; collect provider settings and discovery separately from `PolishSettingsViewModel`, so the activity collects six states from five view models; message and write sequence stay in one provider-settings value; `AppUiState` is assembled in the activity's composition.
2. Existing authority: `AppActions` (#190) already groups the actions by capability, one group per feature; the new view models back those groups one to one (history, dictionary, polish, permissions plus shell readiness, onboarding plus the settings groups). `grep -rn "HistoryViewModel\|DictionaryViewModel\|PolishSettingsViewModel\|ReadinessViewModel" app/src` finds nothing.
3. Prior work: #190 split the shell and grouped the actions; the audit's regrade asks for the matching state split.
4. Boundaries: every view model uses `viewModelScope` (Main.immediate) exactly as today; the provider-settings mutex ordering argument (acquire on Main, work on IO) moves unchanged; the discovery counters stay Main-only.
5. Premises: only `SettingsActivity` constructs the view model and only `AppShell.kt` reads `EnviousWisprUiState` (`grep -rln "EnviousWisprViewModel\|EnviousWisprUiState" app/src/main` lists `AppShell.kt`, `AppViewModel.kt`, `SettingsActivity.kt`); two tests read the source (`AppShellShapeTest`, `AutoPasteWiringTest`); seven test files use the state types, which keep their names.

## 3. Design

- `AppUiState(shell: EnviousWisprUiState, readiness: ReadinessUiState, history: HistoryUiState, dictionary: DictionaryUiState, polish: ProviderSettingsUiState, discovery: ProviderDiscoveryUiState)`. Root loading ends after preferences, terms, transcripts, and auto-paste have each emitted. Track first emissions explicitly, including when a real repository result is empty: each of `EnviousWisprUiState`, `HistoryUiState`, `DictionaryUiState` and `ReadinessUiState` carries `loaded = false` in its `stateIn` initial value and `true` in every value built from a real emission, and `AppUiState.loading` is the pure `!(shell.loaded && history.loaded && dictionary.loaded && readiness.loaded)`. Do not include provider-settings loading or idle discovery in the root gate; keep Polish's existing local gate (`ProviderSettingsUiState.loading`). Carry provider message and write sequence together, and keep the snackbar keyed to that sequence across tab changes (the `LaunchedEffect` in `AppShell.kt` is unchanged).
- Use `stateIn(viewModelScope, WhileSubscribed(5_000), initial)` for the shell, History, Dictionary, and Readiness projections. Polish exposes its existing provider-settings and discovery `MutableStateFlow`s as read-only `StateFlow`s; the activity collects six states from five view models, both Polish states separately.
- History filtering, dictionary filtering and the auto-paste join keep their exact expressions.
- Factories: one per view model, each taking only its own dependencies.

Alternatives rejected: (a) keeping one view model and splitting only the state classes: the audit's finding is ownership, not shape; (b) sharing one view model across features through delegation: still one owner.

## 3b. Ownership justification

Each view model lives next to the repository it drives (`history/`, `vocabulary/`, `providers/`) because the audit asks for feature ownership and the repositories already live there; readiness stays in `ui/` because it reads the platform and two packages (`paste/`, `models/`), not one feature.

## 4. Contract deltas

`EnviousWisprApp`'s signature becomes `(state: AppUiState, licenseNotices: String, actions: AppActions)`. `ProviderSettingsUiState` and `ProviderDiscoveryUiState` move to `providers.ui`; `AppReadiness` stays in `ui`. `EnviousWisprUiState` loses every feature field.

## 5. State audit

| Population | Enumeration |
|---|---|
| `EnviousWisprViewModel` members | To History: `historySearch`, `historyError`, `history`, the recovery launch, `updateHistorySearch`, `setHistoryKept`, `deleteHistory`, `deleteAllHistory`, `updateHistory`. To Dictionary: `customTermSearch`, `customTermMessage`, `customTermError`, `customTerms` (removed), the migration launch, the six term functions, `updateCustomTerms`. To Polish: `providerDiscoveryState`, `providerDiscovery`, `nextDiscoverySequence`, `latestDiscoveryByProvider`, `draftResults`, `providerSettings`, `providerSettingsMutex`, the initial-load launch, `polishPolicyToken`, `setPolishMode`, `setS1Control`, `saveProviderSettings`, `removeProviderKey`, `keyCheckToken`, `loadCachedModels`, `keyDraftChanged`, `discoverModels`, `refreshProviderSettings`, `nextWriteSequence`, `updateProviderSettings`, `discoverer`, `modelCache`. To Readiness: `readiness`, `autoPaste`, `updateVerifiedModels`, `refreshPermissions`. Stay: `appPreferences`, `changeSetting`, thirteen settings setters, the four onboarding functions. Move `clock` entirely to History (its only use is the recovery), `readAppReadiness` to Readiness, and replace the old factory with feature-specific factories. Keep the provider initial load inside its write mutex; preserve Main acquisition before IO work, write-sequence allocation and publication, and the branch-specific discovery guards described in §11: latest and active for empty, refused, and final UI publication; latest only before a stored-key cache write. |
| `uiState` (removed) fields | Each maps to exactly one feature state (§3). |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| State split | `AppShell.kt` | Reads `state.history`, `state.dictionary`, `state.polish`, `state.readiness`, `state.shell` | compile, `AppShellShapeTest` |
| View models | `SettingsActivity.kt` | Five view models, one `remember` | `AppShellShapeTest.theActivityBuildsTheActionsOnce` rewritten |
| Moved state types | `PolishScreen.kt`, `PolishStatusChip.kt`, `PolishLadder.kt`, `AppShell.kt`, and tests `PolishLadderTest`, `PolishScreenProviderTilesTest`, `PolishStatusChipTest` | import `com.envi.wispr.providers.ui` | compile |
| Auto-paste source row | `AutoPasteWiringTest.theViewModelDerivesAutoPasteFromLivenessAndNotFromAConstant` | reads `ui/ReadinessViewModel.kt` | revert |

## 7-9. Failure modes, signals, fallbacks

Unchanged: every error sentence and telemetry event moves with its code. `not present in this change` for new signals.

## 10. Files

New `history/ui/HistoryViewModel.kt`, `vocabulary/ui/DictionaryViewModel.kt`, `providers/ui/PolishSettingsViewModel.kt`, `ui/ReadinessViewModel.kt`, `ui/AppUiState.kt`; `ui/AppViewModel.kt` reduced; `AppShell.kt`, `SettingsActivity.kt` and the importers updated; tests per §6 and §11.

## 11. Testing

1. New Drift Guard `AppStateOwnershipShapeTest` (proposed), code only through `codeOnly`: `EnviousWisprViewModel` names no `TranscriptRepository`, `CustomTermRepository`, `ProviderConfigurationRepository`, `ModelListCache`, `PasteAccessibilityService`, `AccessibilityPermission`; each feature view model names only its own repository among those; positively, each feature view model wires its repository to its state (`repository.transcripts` feeds `HistoryViewModel.state`, `customTermRepository.observe()` feeds `DictionaryViewModel.state`, `AutoPasteReadiness.observe(` with `PasteAccessibilityService.isBound` feeds `ReadinessViewModel.state`, `refreshProviderSettings` writes `providerSettings` in `PolishSettingsViewModel`); `EnviousWisprUiState` declares only `loaded` and `preferences`; `EnviousWisprApp` takes `AppUiState`. A further code-only row is anchored to the `customTermRepository.observe()` state transform: every emitted term list constructs `DictionaryUiState(loaded = true, ...)`, including an empty list; named compilable revert: change it to `loaded = terms.isNotEmpty()`, and this row must be red. Revert: put `repository.transcripts` back in `EnviousWisprViewModel`; add a `history` field to `EnviousWisprUiState`; replace `HistoryViewModel`'s transcripts source with `flowOf(emptyList())` (compiles; the positive wiring row goes red).
2a. New pure rows `AppUiStateTest` (proposed), Product Outcome: the root gate stays up until preferences, terms, transcripts and readiness have each emitted; an empty but REAL History or Dictionary ends the gate (`loaded = true`, empty list); a still-loading provider settings or an idle discovery never holds the gate. Revert: make `AppUiState.loading` read `polish.loading`, or read list emptiness instead of `loaded`.
2b. Adopted from the coverage round: `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<the resolved coroutines version>")`, and `PolishSettingsViewModelTest` (proposed), Product Outcome, with `Dispatchers.setMain` on a test dispatcher and controlled suspend points. The view model is built from test doubles through constructors that already exist: `ProviderConfigurationRepository`'s internal `(SharedPreferences, SecretStore, ProviderKeyChecker)` constructor, `ModelListCache`'s internal `(SharedPreferences)` constructor, and a fake `ProviderModelDiscoverer`. Test doubles: fake preferences, secrets and key checker; a real JVM JSON test runtime (`testImplementation("org.json:json:<version>")`) for `ModelListCache`, which runs `org.json`. IO reads and commits are coordinated with bounded latches in the doubles (the view model's hard-coded `Dispatchers.IO` is not the test dispatcher). Rows: the initial load and a first write published in launch order under the mutex; the first write acquires the lock on Main before a second tap (asserted, not assumed from `setMain`); two rapid mode taps persist the last one tapped; each completed write publishes its own `writeSequence` with its message. Discovery, the existing branch-specific contract: empty and refused results apply only while latest and active; a nonempty stored-key result may refresh its cache after its page closes if still latest; its UI result applies only while latest and active. Hold discovery, cache read and cache write separately. Named revert: remove `providerSettingsMutex.withLock` from the shared `updateProviderSettings` path. Hold the first write on IO, enqueue a second mode tap, release the first, and assert persisted order and both completed sequences; this row must be red on that exact revert. The empty, refused and final-publication guards each get their own named revert and red assertion. `HistoryViewModelTest` (proposed), with the fake DAO the session rig already has, seeded before constructing a fresh `TranscriptRepository` (its `observeAll()` captures a snapshot; rows are never added after the repository is built): the first value is `loaded = false`; an empty but real store emits `loaded = true` with an empty list; a populated store emits its rows filtered by the search. `DictionaryViewModel` has no JVM row: `CustomTermRepository` takes a Room database (no Robolectric in this module); `AppUiStateTest` proves the root gate; the Dictionary Drift Guard checks `loaded = true` for every term emission. Record whether an empty Dictionary was observed on the emulator or guarded only.
2. Existing rows unchanged in substance: the policy rows, `AppShellShapeTest` (signature updated), `AutoPasteWiringTest` (path updated).
3. Not tested in the JVM: `DictionaryViewModel` (Room-backed store); the emulator walk covers it.

### 11.1 UAT
`wispr_eyes.scan(toggle=True)` walks every tab and drawer page and flips and restores each switch; Onboarding is walked on an incomplete emulator profile; "Continue guided setup" only clears the dismissal and cannot reopen a completed onboarding, so if the emulator's profile has completed onboarding, the walk is recorded NOT RUN with that reason (no app-data wipe on the emulator for it); one dictation into Gmail, then the History tab shows its text at the top; the AI Polish tab shows its tiles and status chip; a Dictionary search filters; one setting is changed, the app is restarted, the setting reads back changed, and it is restored to its original value. On an emulator with an empty Dictionary, verify startup leaves 'Preparing' and shows the empty Dictionary; if the emulator's Dictionary is not empty, record that the empty path was guarded, not observed, and rely on the Drift Guard row that the state built from `customTermRepository.observe()` sets `loaded = true` on every emission (the same construction `HistoryViewModelTest` proves with an empty store). Restore: `restore()`.

## 12. Blast radius

The settings app's view-model layer and `AppShell`; the dictation path (`DictationSessionCoordinator`, the paste service) is untouched. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] All tabs and drawer pages pass the emulator walk; Onboarding passes on an incomplete profile or is recorded NOT RUN with the completed-profile reason. Do not count NOT RUN as a pass.
- [ ] History shows a new dictation; a toggle persists across an app restart.

## 14. Open questions
None.

## 15. Related
#190 (the shell and `AppActions`), #216, #217; audit REF-09.
