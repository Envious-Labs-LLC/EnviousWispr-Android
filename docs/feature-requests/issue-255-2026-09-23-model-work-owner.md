# Issue #255: one owner watches model delivery for the settings shell, off main (2026-09-23)

GitHub issue: `#255`. Tier: MEDIUM (settings UI state ownership; no take path). Status: APPROVED after grounded round 3 (PROCEED-AS-PLANNED); earlier rounds (gate 0 `255-g0`: PROCEED-WITH-CHANGES, all four changes adopted; coverage `255-cov`: all four findings adopted verbatim; grounded round 1 `255-g1`: all three findings adopted; grounded round 2 `255-g2`: both findings adopted verbatim, §3).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. On the emulator, open AI Polish and Transcription and read each model card and the Polish badge (both models installed, so READY); leave and reopen each tab; a dictation by COMMIT to show the take path is untouched. A download in progress is not staged on a device (it would re-download a 480 MB model); JVM rows cover the progress and finish states.

## Preface: User Rubric

User Rubric: the AI Polish and Transcription tabs show the same model state as before, never block on storage while drawing, and the app still notices a finished download without a restart.

---

## 0. TL;DR

Three composables observe WorkManager model work and project it with `workUiState`, which reads storage during composition, on main: `AppShell` (S1, only while AI Polish shows), `TranscriptionScreen` (Parakeet), and `ModelWorkReadinessObserver` (removed) (all four works, refreshes readiness when one finishes). REF-04 of the second 2026-09-23 audit asks for the S1 pair to move out of `AppShell`. Move the whole class into one owner, `ModelWorkViewModel`: it projects each visible model's state on IO, publishes `ModelWorkUiState(speech, polish)` through `AppUiState`, and emits one refresh signal per finished-work snapshot, which the activity turns into its existing `refreshReadiness()`. No composable in the settings shell touches WorkManager afterwards.

## 1. Problem

Grounded by Codex (`255-g0`), re-read by Claude:
- `AppShell.kt:110-123`: two `getWorkInfosForUniqueWorkFlow` collections and `workUiState` for S1, inside the root composable, while AI Polish shows.
- `TranscriptionScreen.kt:52-54`: the same for Parakeet.
- `ModelCards.kt:152-172` `ModelWorkReadinessObserver` (removed): four collections, always, from `AppShell.kt:55`; calls `onRefreshReadiness` when any first work is finished.
- `ModelCards.kt:134-144` `workUiState` reads `ModelDeliveryControlStore.read` and `ModelDeliveryWorker.hasStaleInstallation` (disk) and is called from composition, so on main at every recomposition of those tabs.
- `OnboardingViewModel.kt:84-101` already observes both models in a view model, with its own verification and staged-byte projection.
- No other caller of `getWorkInfos*`, `workUiState` or `preferredModelWork` exists under `app/src/main/java`.

## 2. Goals & non-goals

### 2.1 Goals
1. `ModelWorkViewModel` (ui/) is the settings shell's one owner of model-delivery observation. Per model it combines the download and adoption lists and the model's verified readiness (`ReadinessViewModel.state`, mapped and `distinctUntilChanged`), picks `preferredModelWork`, and projects with `workUiState` on an injected IO dispatcher (`mapLatest` + `withContext`).
2. Visibility: `AppShell` reports its visible screen with one `LifecycleStartEffect(destination, settingsPage)` (already used by `OnboardingScreen`) placed after the onboarding return, calling `modelWork.show(destination)` only when no settings page is open and `show(null)` in `onStopOrDispose`. So a model is "visible" only while its tab shows AND the activity is started: a stopped activity holds no projection subscription even while its view model survives, and composition disposal clears it too; the restored `rememberSaveable` destination sets it again. Only the visible model is observed (AI Polish: S1; Transcription: Parakeet; anything else: neither).
   The owner publishes `models` from a `MutableStateFlow`, not `stateIn` (grounded round 1, finding 2: a retained `stateIn` value could show on re-entry before `show` runs). `show` synchronously cancels the previous activation's job and resets both models to the placeholder before it launches the new activation's collection in `viewModelScope`; `show(null)` cancels and resets, and is idempotent because the effect's cleanup can run more than once. Call `show` on Main; publish results on Main after `withContext(io)`, and never swallow cancellation (grounded round 2, finding 1). The placeholder is `ModelUiState("Checking", ModelHealth.UNKNOWN)`, whose default action is NONE; the old blank-label sentinel is never rendered as a card (grounded round 1, finding 1). So each activation starts at a neutral Checking card and then shows the fresh projection: a download, removal or repair can finish while the tab is hidden, and disk verification is the authority (`.claude/knowledge/model-delivery.md`), so a cached card is never shown as current. A settings page, onboarding and the loading screen report no visible model. Process death starts a new owner, which queries persisted work rather than restoring an old card.
3. `AppUiState.models: ModelWorkUiState(speech, polish)`; `AppShell` passes `state.models.polish` to `PolishStatusBadge` and `PolishScreen`, and `state.models.speech` to `TranscriptionScreen`. Neither file imports WorkManager.
4. The refresh signal is a cold `Flow<Unit>` on the owner: combine the four lists, keep the finished works as a set of `(id, state)`, `distinctUntilChanged`, emit when the set is nonempty. The activity collects it at `STARTED` and calls its existing `refreshReadiness()`, keeping the generation guard (`SettingsActivity.refreshReadiness`). `ModelWorkReadinessObserver` (removed) is deleted.
5. `OnboardingViewModel` keeps its own pipeline (it verifies files and projects staged bytes, `OnboardingViewModel.kt:92`).

### 2.2 Non-goals
- No change to `workUiState`'s or `preferredModelWork`'s projection, to the model cards, or to delivery itself.
- No change to the take path.

## 3. Design

Gate 0 changes adopted from Codex: readiness from `ReadinessViewModel.state` rather than a pushed value; the projection after the combine, on IO; the refresh signal as a finished-snapshot set, so a later completion is not lost behind an older finished work; visibility uses `LifecycleStartEffect(destination, settingsPage)`. Deviation from the audit text: the owner covers the whole class (Transcription and the refresh observer too), because moving only the S1 pair would leave the same disk read in composition on the next tab.

Seams, for JVM rows: `work: (String) -> Flow<List<WorkInfo>>` (production `WorkManager.getInstance(context)::getWorkInfosForUniqueWorkFlow`), `project: (WorkInfo?, Boolean, ModelDescriptor) -> ModelUiState` (production `workUiState(..., context)`), `io: CoroutineDispatcher` (production `Dispatchers.IO`).

Person-visible: each visit to AI Polish or Transcription shows the neutral UNKNOWN card and badge, with no model action, for the few milliseconds the IO projection takes (today that frame waited on the disk read instead). Coverage finding 1 declined my first idea (keep the hidden model's last card): it could show a Ready badge and a Remove button for a model removed while the tab was hidden.

Activity wiring (coverage finding 2): `SettingsActivity` builds the owner with `by viewModels`, collects `models` with lifecycle into `AppUiState`, adds the owner to the `remember` keys of the actions holder (`SettingsActivity.kt:82`, pinned by `AppShellShapeTest.kt:48`, updated), and passes `show` as an action. The snackbar is keyed on provider writes and destination and does not move.

## 4. Contract deltas
`AppUiState` gains `models`; `EnviousWisprApp` loses its WorkManager imports; `TranscriptionScreen` gains a `speechModel: ModelUiState` parameter.

## 5-9. State, consumers, failure modes
- Observation failure (coverage finding 3, placed per grounded round 1 finding 2): each activation's collection has its own `catch` that publishes the Checking placeholder with no action; the next `show` launches a new activation, which retries. A cached Ready is never displayed as current.
- Finish-watcher failure (grounded round 1, finding 3): the activity collects the refresh signal in `repeatOnLifecycle(STARTED)`; its `catch` calls `refreshReadiness()` once, and continuous completion detection resumes at the next lifecycle start (the collection restarts). Row 6c pins that boundary.
- Consumers of the moved state: the badge, `PolishScreen`, `TranscriptionScreen`; `PolishStatusChip` reads UNKNOWN as neutral.

## 10. Files
`ui/ModelWorkViewModel.kt` (new), `ui/AppUiState.kt`, `ui/AppShell.kt`, `ui/TranscriptionScreen.kt`, `ui/ModelCards.kt` (observer removed), `ui/SettingsActivity.kt`; tests `ModelWorkViewModelTest`, a source guard.

## 11. Testing
1. Hidden: no projection runs and the projection subscribes to no model work while nothing is shown. The fake counts projection subscriptions separately from the refresh flow's, and the refresh flow is left uncollected in this row. MUTATION: ignore visibility.
2. Visible AI Polish: the S1 state is the projection of the preferred work and S1 readiness; Parakeet is not projected. MUTATION: project Parakeet for Polish.
3. Adoption preferred when active: an active adoption beats a finished download (the existing `preferredModelWork` choice reaches the owner). MUTATION: pass the download only.
4. Projection off main: the projection runs on the injected IO dispatcher's thread, not the test main. MUTATION: drop the `withContext(io)`.
5. Hide, change the fake work while hidden, show again: the value read synchronously right after `show` returns is the Checking placeholder with no action, then the projection of the NEW work; the fake records a second projection subscription. MUTATIONS: keep the last value across activations; omit resubscription (reuse the first subscription's last value).
6. Refresh: one signal per new finished snapshot; the same snapshot emitted twice by the fake gives none (the fake is a `MutableSharedFlow`, which does not conflate equal values); a second work finishing gives a second. MUTATION: drop `distinctUntilChanged`.
6b. Failure: a work flow that throws while visible publishes the Checking placeholder with no action; the next activation resubscribes and projects successfully. MUTATION: drop the `catch`.
5b. Rapid switch: block the S1 projection, call `show(Transcription)`, then release S1: the late S1 result must not replace Transcription's state. MUTATION: omit cancellation.
6c. Finish-watcher failure: the refresh signal's collector, given a work flow that throws, calls refresh once and stops; a new collection (the next start) signals a later finish. The collector is a small function the activity calls (`collectModelRefresh(signal, refresh)`), so the row drives it directly. MUTATION: drop the refresh in its `catch`.
7. Source guard over all of `app/src/main/java`: `getWorkInfosForUniqueWorkFlow` appears only in `ModelWorkViewModel.kt` and `OnboardingViewModel.kt`; calls of `workUiState(` (not its declaration in `ModelCards.kt`) appear only there too. A wiring assertion reads `SettingsActivity` building `AppUiState(models = ...)` from the owner, `AppShell` passing `state.models.polish` to the badge and `PolishScreen` and `state.models.speech` to `TranscriptionScreen`, and the activity collecting the refresh signal into `refreshReadiness()`. MUTATIONS: put an observation back into `AppShell`; pass UNKNOWN to `TranscriptionScreen` instead of `state.models.speech`.
All rows wait on signals, never a clock.

## 12. Blast radius
The AI Polish and Transcription tabs and their badge; the readiness refresh after a download. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 7, 5b, 6b and 6c green, each mutation red.
- [ ] Emulator: both tabs show READY cards and the badge; a dictation by COMMIT.

## 14. Open questions
None.

## 15. Related
#218 (one field per owning view model), #253, REF-04 of the second 2026-09-23 audit.

## 16. As built

- `ModelWorkViewModel` (new): `show(destination)` cancels the previous activation, resets both cards to `CHECKING` and, for AI Polish or Transcription, collects one model's download, adoption and loaded readiness, projected with `workUiState` on `io` and published on main; each activation catches its own failure into `CHECKING`. `finished` emits once per new set of finished works; `collectModelRefresh` refreshes on each and once on failure. The destination `when` names History, Dictionary and null instead of `else` (`check-visibility.py`).
- `AppShell`: one `LifecycleStartEffect(destination, settingsPage)` calls `actions.shell.onShowModels`, and `onStopOrDispose` clears it; the badge and `PolishScreen` read `state.models.polish`; `TranscriptionScreen` takes `speechModel = state.models.speech`. Neither imports WorkManager. `ModelWorkReadinessObserver` (removed) is gone from `ModelCards.kt`.
- `SettingsActivity`: builds the owner, collects `models` into `AppUiState`, adds the owner to the actions `remember` keys (`AppShellShapeTest` updated), and collects the refresh signal in `repeatOnLifecycle(STARTED)`.
- Tests: `ModelWorkViewModelTest` rows 1 to 6c (9 rows) and `ModelWorkOwnershipTest` (row 7, three rows). The view model rows run main as production's `Main.immediate` does (work started on main runs at once), so an activation subscribes inside `show`; with a queued main, the "visibility ignored" mutation was masked by the next `show`'s cancel. Receipts 11 of 11 RED (`docs/audits/2026-09-23-255-mutation-receipts.txt`); full suite 1242, 0 failures; both classes 20 of 20 repeats.
- Emulator: AI Polish and Transcription showed Ready cards with Remove on second visits; on a cold first visit the eye caught the Checking card once, and three timed visits read Ready at the first look, 0.3 s after the tap; a Gmail dictation by COMMIT landed.
