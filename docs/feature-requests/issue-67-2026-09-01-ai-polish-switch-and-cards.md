# Issue #67 — AI Polish: master switch, two engine cards, provider setup page — 2026-09-01

GitHub issue: `#67`. Tier: MEDIUM. Status: DRAFT.

**Consolidation:** the dominant root this plan protects is "what polish is running right now", whose one
owner is `ProviderConfigurationRepository` (SharedPreferences `mode` + provider keys) surfaced through
`AppViewModel.providerSettings`. Today `ui/PolishScreen.kt` keeps a second, screen-local copy of `mode`,
`provider` and a key "rung" and reconciles it against that owner after the fact (`PolishScreen.kt:164-187`).
This plan deletes the second copy: the tab renders the owner's state directly, and the only draft left in the
feature is the setup page's form, which is written once through `saveProvider` and never read back into a
mirror. Consolidation sites: `PolishScreen.kt` (mirror removed), `PolishScreenProviderTilesTest.kt` (tests
for the mirror's helpers removed).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. Android-only screen shape; no macOS parity claim.

**Hardware UAT:** Y

Success looks like: Saurabh opens AI Polish on the S26 Ultra with polish off. He sees the switch off, one
quiet line, no S1-mini card. He flips the switch on and the "On this phone" card comes back selected with
"S1-mini · Ready", because that was the mode he used last. He taps "Your provider", the sheet lists OpenAI,
Gemini and Claude, he picks OpenAI, the setup page opens with the key box and the model list, he pastes a
key, picks a model, taps Save, and lands back on the tab with the OpenAI card selected, "gpt-5.6-terra ·
Configured", and the app-bar badge showing that model with a green dot. He dictates into Messages and the
History row says OpenAI polished it. He removes the provider from the setup page, the tab returns to "On
this phone", and the phone's preferences file diffs byte-identical to the pre-run backup.

## Preface — User Rubric

1. **Who is this user in this moment?** Frank Chen, 72, opens AI Polish because his last text came out
   with filler words left in. He wants to see, in one glance, whether polish is on and where it runs.
2. **Why would they want this?** "Just show me a switch. If it is on, tell me what it is using."
3. **How would they invoke it?** Voluntary, from the AI Polish tab, once at setup and rarely after.
4. **What app are they in?** The EnviousWispr settings surface itself; the result shows up later in
   Messages and Gmail.
5. **What is their natural input?** Taps only: the switch, a card, a provider row, a model row, Save. One
   paste of an API key for the cloud path.
6. **What does success feel like?** He flips the switch off and the page goes quiet. He flips it on and it
   comes back exactly as he left it, with no re-setup.
7. **What does wrong-not-broken look like?** The page shows a model card that says "Ready" while the switch
   is off, so he is not sure whether polish is on. That is today's screenshot.
8. **What would a power user hack around this to get?** Priya Ramachandran would keep the tab open while
   dictating to watch the badge; the badge already answers that and stays unchanged here.
9. **What level of control would they want?** Off, on-device, cloud with a chosen model. The ladder is
   unchanged; only its presentation changes.

### Cross-persona check

Dr. Elena Vasquez needs the privacy consequence stated on the card she selects ("Text stays on this phone"
versus "Text is sent using your key") and on the setup page above Save; both are in §3. Meera Patel and
Frank Chen need the off state to be one switch with nothing else to read; §3's Off branch is one line.
Marcus Weber and Aaron Wu are unaffected. The only tension is Elena wanting more words on the provider card
versus Frank wanting fewer; the card carries one privacy line and the setup page carries the full
disclosure, which is where Elena reads before committing a key.

---

## 0. TL;DR

Rebuild the AI Polish tab to the founder's `AI-POLISH-EXPERIENCE.md`: a master switch, two full-width
single-choice cards (On this phone, Your provider), S1-mini status inside its card and hidden when polish is
off, a Material 3 bottom sheet to pick a provider, and a full-screen setup page with one Save. Turning the
switch on restores the mode used last (founder decision 2026-09-01, option 1). The structural fix is that
the tab holds no draft of persisted state, which removes the reconcile-after-the-fact class that cost #62
23 review rounds. MEDIUM. Proof: unit tests on the pure card-state, turn-on and subpage-routing functions,
plus the hardware pass above.

## 1. Problem

Screenshot of the founder's phone, 2026-09-01 14:49, mode Off (scratchpad `polish-top.png`):

1. **The S1-mini card renders in every mode.** `PolishScreen.kt:618-640` calls `ModelCard(eyebrow = "LOCAL
   POLISH", ...)` after the `when (mode)` block, unconditionally. With polish Off the page shows "S1-mini ·
   Ready" and a Remove button under the sentence "No language model runs."
2. **The three mode pills are uneven.** `PolishScreen.kt:259-300` puts `Button`/`OutlinedButton` in a
   `Row` with `weight(1f)`; "This phone" wraps to two lines on the S26 Ultra, so the middle pill is taller.
3. **Provider setup is inline.** The key field, the catalog and the disclosure stack under the pills
   (`PolishScreen.kt:372-597`), so the tab is a form, not a status page.
4. **The tab keeps a local mirror of persisted facts.** `mode`, `provider` (`rememberSaveable`,
   `PolishScreen.kt:164-165`) and `rung` (`remember`, `:183-187`) are seeded from `settings` and then kept
   honest by hand (`cloudReactivatesImmediately`, `initialKeyRung`, `savedModelFor`, `resetLocalStateAfterClear`).
   The session log entry for #62 records six-plus rounds each finding a new timing gap in that mechanism.

## 2. Goals & non-goals

### 2.1 Goals

- Master `AI Polish` switch. Off collapses the engine choice and shows `AI Polish is off. Basic cleanup
  still runs.` No S1-mini card in the Off state.
- Two single-choice cards under `WHERE POLISH RUNS` with radio semantics for TalkBack. The selected card
  carries the primary-colour border and the processing-route glyph.
- `On this phone` shows `S1-mini · <status>`, `Text stays on this phone`, and `Manage model` (or
  `Download model` when the model is missing). The radio activates only when the status is Ready.
- `Your provider` shows, once configured, the provider name, `<model> · Configured`, `Text is sent using
  your key`, and `Edit provider`. Unconfigured it shows `OpenAI, Gemini, or Claude` and opens the picker.
- Picker: modal bottom sheet, drag handle, system back, three radio rows (OpenAI, Gemini, Claude).
- Setup page: back arrow, no bottom bar, API key field, the #66 model catalog, the privacy disclosure above
  a pinned `Save provider`, IME padding. Back discards the key draft. Editing shows `Remove saved provider
  and key` beneath the disclosure; a new setup never does.
- Save success returns to the tab with a snackbar `<Provider> saved`.
- Turning the switch on restores the last non-off mode; if that was a provider whose configuration is gone,
  it falls back to On this phone.
- The tab body reads only `settings` and `s1State`; its only local state is whether the picker sheet is
  open.

### 2.2 Non-goals

- A live API key check against the provider. #61 owns it. The inline `Check` control from #66 is removed
  because Save now runs the same local format check; #61 reintroduces a real check on the setup page.
- Offering self-hosted for fresh setup. Catalog decision 2026-09-01 stands. An existing self-hosted
  configuration shows on the provider card with `Turn on` (radio) and `Remove`; no Edit.
- Changing `PolishService`, `ProviderPolishClient`, the Keystore store, `PolishStatusChip`, or the model
  catalog contents.
- Animating the processing-route glyph. It is a static Canvas row of dots; motion is future polish.
- Replacing the mockup's free-text Model ID with anything other than the existing catalog (decision at
  Gate 1, 2026-09-01: keep the catalog).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

**Mode.** Owner `ProviderConfigurationRepository` (`providers/ProviderConfigurationRepository.kt:27-29`
`loadMode`, `:94-98` `setMode`, `:76` `saveProvider` forces `PROVIDER`, `:107` `clearSelection` forces
`OFFLINE_S1`). Storage: SharedPreferences `envious_wispr_provider_configuration`, key `mode`. Consumers,
from `/usr/bin/grep -rn "PolishMode\." app/src/main/java`:

- `ui/AppViewModel.kt:368-376` `setPolishMode` (refuses `PROVIDER` when `load()` is null), `:397-401`
  `clearProviderSettings`, `:404-419` `refreshProviderSettings` builds `ProviderSettingsUiState`.
- `ui/AppShell.kt:345-352` passes `uiState.providerSettings` to `PolishScreen`; `:300-302` builds the badge
  from the same value.
- `ui/PolishScreen.kt` (the mirror, deleted by this plan) and `ui/PolishStatusChip.kt` (unchanged).
- `polish/PolishService.kt:68-77` reads `loadMode()` per polish call in the `:polish` process; `:119-123`
  `isReady`; `:127-134` `getStatus`; `:136-138`, `:143-145` warm-up. These read the preference file
  directly, so a new key in the same file is invisible to them unless they ask for it, which they do not.

**Provider, model, key.** Owner `ProviderConfigurationRepository.load()/saveProvider()/clearSelection()`
(`:31-54`, `:56-83`, `:100-117`); key in `SecretStore` (Keystore), never in preferences. Producer of a save:
today `PolishScreen.pickModel` (`PolishScreen.kt:203-240`) → `onSaveProvider` → `AppViewModel.saveProviderSettings`
(`:378-395`) → `updateProviderSettings` (`:422-445`, mutex, IO) → `refreshProviderSettings`. After this plan
the producer is the setup page's Save, through the same view-model function.

**S1-mini readiness.** `AppShell.kt:261-270` computes `polishS1State` from WorkManager and passes it to both
the badge and `PolishScreen`. Unchanged; the tab's phone card and the new local-model page both read it.

**Navigation.** `AppShell.kt:393-397` `Screen` is a sealed interface (`Tab`, `Page`); `:149-179`
`SettingsPage` enum owns the drawer pages; `AppScaffold` (`:400-470`) hides the bottom bar and swaps the
menu glyph for a back arrow when `settingsPage != null`; `BackHandler` at `:256`. The provider setup page
and the local-model page become a third `Screen` variant and reuse that chrome.

### 2. Find the existing authority before proposing one

- "Remember the mode used last": `/usr/bin/grep -rn "last_on\|lastOn\|previousMode\|lastMode" app/src/main/java`
  returns nothing. `new authority proposed`: one preference key beside `mode`, owned by the repository.
- Full-screen page with back arrow and no bottom bar: `SettingsPage` + `AppScaffold` already do this for
  seven drawer pages. Reused, not duplicated.
- Modal bottom sheet: `/usr/bin/grep -rln "ModalBottomSheet" app/src/main/java` returns nothing. Compose BOM
  `2026.02.01` (`app/build.gradle.kts:71`) ships `androidx.compose.material3.ModalBottomSheet` (external). Platform
  primitive, not a new authority.
- Snackbar: `/usr/bin/grep -rln "Snackbar" app/src/main/java` returns nothing. `Scaffold(snackbarHost = ...)`
  is the platform primitive; one `SnackbarHostState` (external) hoisted in `EnviousWisprApp`.
- Card with icon tile, title, description, chevron: `DictionaryScreen.kt:668-731` `ImportPickerCard` is the
  house style (44 dp tile, `RoundedCornerShape(12.dp)`, primary at 15% alpha). The polish cards follow the
  same measurements; the composable itself is private to Dictionary and carries no radio, so the polish
  card is its own composable with the same tokens rather than a widened shared one.
- Radio semantics on a card: `Modifier.selectable(selected, role = Role.RadioButton)` plus a
  `RadioButton` (external) glyph; the platform owns the TalkBack contract.

### 3. Read prior attempts and live direction

- #53 asked for cards with a radio and flagged the master-switch-plus-OFF-card conflict. The mockup resolves
  it: the switch IS off, and the cards are only Phone versus Provider. This plan closes #53.
- #62 / PR #66 shipped the badge, the three pills, the catalog. Session log 2026-09-01 "AI Polish Ladder":
  the reconcile mechanism was the bug factory; the loading gate closed most of it and rotation reopened a
  narrower version twice. The loading gate stays. The mirror goes.
- Catalog `decision` 2026-09-01: self-hosted is not a fresh selection. Binding; §3 keeps it.
- Catalog `ai-polish` and `cloud-polish` android rows describe the #66 screen; `data/036-*.sql` updates them
  at wind-down.
- `AI-POLISH-EXPERIENCE.md` (untracked, same day, predates the #62 branch per session log line 660) is the
  founder's spec and the source of every copy string in §3. Two lines in it are not followed, each named
  in §3 with the reason: the free-text Model ID (catalog kept) and "keep entered values on save failure"
  (§7 row 5).

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **`:polish` reads the preference file on every call.** Adding `last_on_mode` (proposed) in the same file is invisible
  to it. `turnOn()` (proposed) writes `mode` through the same `commit()` path as `setMode`, so the next polish call
  sees the restored mode exactly as it sees a mode tap today.
- **Setup page across a configuration change.** The key draft is plain `remember` and dies (by design,
  unchanged). The model draft is `rememberSaveable` (non-secret). The page identity itself is saved as a
  string in `rememberSaveable` beside `settingsPageName`, so rotation stays on the page.
- **Save then process death before the write lands.** `updateProviderSettings` runs in `viewModelScope`;
  process death loses the queued write and the user returns to the tab showing the old state, which is the
  truth. Nothing on the tab claims otherwise because the tab has no draft.
- **Turn on when the last mode was a provider that is gone.** `polishModeWhenTurnedOn` (proposed) (pure) returns
  `OFFLINE_S1` unless `lastOnMode == PROVIDER && load() != null`. `setPolishMode`'s existing refusal of
  `PROVIDER` with no configuration is therefore never reached from the switch.
- **Phone card tap when the model is not Ready.** The radio is disabled unless `s1State.label == "Ready"`
  or the card is already selected, per the spec line "Do not activate the local mode until the model is
  ready." Turning the switch on can still land on `OFFLINE_S1` with a missing model, because that is the
  last mode used; the card then shows `Model needed` and `Download model`, and `PolishService` already falls
  open to deterministic text (`PolishService.kt:73-75`).
- **Two writers, one mutex.** All writes still go through `updateProviderSettings`'s mutex; the sheet and
  the page add no write path of their own.

### 5. Prove the high-risk premises

- **Claim: `PolishScreen` is reached from exactly one call site.** `grep -n "PolishScreen(" app/src/main/java/com/envi/wispr/ui/*.kt`
  → `AppShell.kt:345` and the definition. Verified 2026-09-01.
- **Claim: no code outside `PolishScreen.kt` and its test references `KeyRung`, `initialKeyRung`,
  `cloudReactivatesImmediately`.** `/usr/bin/grep -rn "KeyRung\|initialKeyRung\|cloudReactivatesImmediately" app/src`
  → `ui/PolishScreen.kt`, `ui/PolishScreenProviderTilesTest.kt`, and one comment at
  `ui/PolishStatusChip.kt:74` that names `initialKeyRung`; that comment is reworded in this change. Verified
  2026-09-01.
- **Claim: `settings.message` and `settings.error` are displayed nowhere today.** `/usr/bin/grep -rn "settings.message\|settings.error\|providerSettings.message\|providerSettings.error" app/src/main/java`
  → nothing. A failed provider write is silent today; §7 fixes that.
- **Claim: `ModelUiState.label` has a closed vocabulary.** `models/ModelDeliveryUi.kt:14-80` produces exactly:
  Paused, Verifying, Queued, Downloading, Ready, Cancelled, Update failed, Failed, Update available,
  Checking, Repair needed, Missing. §3's `phoneCard` (proposed) maps all twelve by label and `action` together, and
  `PolishCardStateTest` (proposed) enumerates all twelve, so a thirteenth label added later fails a test rather than
  falling into a default branch silently.
- **Claim: SharedPreferences across processes is already how `mode` works.** `PolishService.onCreate`
  (`PolishService.kt:141-145`) constructs its own repository over the same file. No change in risk.
- **Codex problem-only consult before §3:** not run. Every who-calls-whom claim above is a pasted grep, and
  no coroutine lifecycle beyond the existing mutex is introduced. The coverage round (step 4) is asked to
  refute this skip explicitly.

## 3. Design

### The tab (`ui/PolishScreen.kt`, rewritten)

Inputs: `settings: ProviderSettingsUiState`, `s1State: ModelUiState`. Callbacks: `onTurnOn` (proposed), `onSetMode`,
`onOpenProviderSetup(Provider)`, `onOpenLocalModel` (proposed), `onDownloadModel` (proposed), `onClearProvider`. Local state:
`showPicker: Boolean` (`rememberSaveable`). The loading gate from #66 stays as the first line.

1. **Switch card.** Sparkle tile, `AI Polish`, `Turns rough speech into ready-to-send text.`, `Switch`
   checked when `settings.mode != OFF`. On → `onTurnOn()`. Off → `onSetMode(OFF)`.
2. **Off:** one line, `AI Polish is off. Basic cleanup still runs.`, `bodyMedium`, `onSurfaceVariant`.
3. **On:** eyebrow `WHERE POLISH RUNS`, then two `EngineCard` (proposed)s inside `AnimatedContent`
   (`animateContentSize` (external); whether it honours the system animator scale is checked on the phone during UAT
   with Developer options animator scale set to off, not assumed).
   - `EngineCard(selected, enabled, tile, title, status, statusColour, privacyLine, action, onSelect)`:
     `Card` with a 1 dp `primary` border when selected, `selectable(role = RadioButton)` on the whole card,
     `RadioButton` at the trailing edge, the route glyph (`RouteGlyph` (proposed), a Canvas row of coloured dots and a
     sparkle) only when selected, a divider, the privacy line with a shield glyph, a divider, the action row
     with a chevron.
   - Phone card, from pure `phoneCard(s1State, settings)`: title `On this phone`; status `S1-mini · Ready`
     (green) / `S1-mini · Model needed` / `S1-mini · <label>` for in-progress and broken labels; radio
     enabled iff Ready or already selected; action `Manage model` → `onOpenLocalModel`, or `Download model`
     → `onDownloadModel` when the action is `DOWNLOAD` or `RETRY`.
   - Provider card, from pure `providerCard(settings)`: unconfigured → title `Your provider`, status
     `OpenAI, Gemini, or Claude`, privacy `Uses your key`, radio disabled, card tap and action `Choose a
     provider` open the sheet. Configured key provider → title = display name, status `<model> ·
     Configured` (green) or `<model> · Key missing` (error colour, radio disabled), privacy `Text is sent
     using your key`, actions `Edit provider` → setup page, `Switch provider` → sheet. Configured
     self-hosted → title `Self-hosted`, status `<endpoint host> · Configured`, privacy `Text is sent to
     your server`, action `Remove` → `onClearProvider`; radio enabled (existing "Turn back on").
4. **Info card.** `If polish cannot finish` / `Your cleaned transcript is still inserted.`
5. **Snackbar.** `LaunchedEffect(settings.writeSequence)` shows `settings.message` when non-blank.
   `settings.error`, when non-null, renders as a line under the cards in the error colour.

### The picker (`ProviderPickerSheet` (proposed), same file)

`ModalBottomSheet` with `rememberModalBottomSheetState` (external), drag handle, title `Choose a provider`, line `Your
words are sent only when this mode is used.`, one `selectable` row per `CloudProviders` entry with the
provider glyph, name, `Use your API key`, a `RadioButton` (checked iff `settings.configured &&
settings.provider == it`) and a chevron. Tap → dismiss, then `onOpenProviderSetup(it)`. `Cancel` text button.

### The setup page (`ui/ProviderSetupPage.kt`, new)

Inputs: `provider`, `settings`, `onSave(Provider, model, apiKey?)`, `onClear` (proposed), `onDone`. Title `Set up
<Provider>` for a new setup, `Edit <Provider>` when `settings.configured && settings.provider == provider`.
Body is a `Column` with a weighted scrolling section and a pinned bottom `Button` inside
`Modifier.imePadding().navigationBarsPadding()`:

- Header card: provider glyph tile, name, `Your API key`.
- `OutlinedTextField` API key, `PasswordVisualTransformation`, plain `remember`, supporting text
  `Encrypted in the Android Keystore. Never written to logs.` or, when a key is stored for this provider,
  `Leave blank to keep your saved key.` Never `rememberSaveable`, never hoisted, never logged.
- Model: `Text("Model")`, the existing search field, sort chips, `ScoreDots` legend and capped list moved
  from `PolishScreen.kt:488-581` unchanged in behaviour; tapping a row sets the `rememberSaveable` model
  draft (seeded from `savedModelFor(provider, settings)`) instead of saving.
- Disclosure card: `provider.disclosure().summary`.
- Editing only: `OutlinedButton("Remove saved provider and key")` → `onClear(); onDone()`.
- Pinned `Save provider`, enabled iff the model draft is non-blank and (key non-blank or a key is stored for
  this provider). On tap: normalise the model (same checks as today's `pickModel`), run
  `ProviderConfigurationValidator.validate(ProviderConfiguration(provider, null), effectiveKey)`; on
  `Invalid` show `reason.userMessage()` under the key field and stay; on `Valid` call `onSave` then
  `onDone()`.
- Back (arrow or system) → `onDone()`, discarding the key draft.

### The local-model page (`LocalModelPage` (proposed), in `PolishScreen.kt`)

Title `S1-mini`. Renders the existing `ModelCard` with the action wiring lifted verbatim from
`PolishScreen.kt:618-640`. This is where Pause, Resume, Retry, Repair, Update and Remove live; the tab
shows none of them.

### Navigation (`ui/AppShell.kt`)

- `internal sealed interface PolishSubpage { data class ProviderSetup(val provider: Provider); data object
  LocalModel }` with pure `toSaved(): String` / `fromSaved(String): PolishSubpage?` (`setup:OPENAI`,
  `model`; unknown → null, so a stale saved string falls back to the tab).
- `Screen` gains `data class Polish(val page: PolishSubpage)`. `polishPageName` (proposed) is a `rememberSaveable`
  string beside `settingsPageName`; opening a drawer page clears it and vice versa, so at most one page is
  open. `BackHandler(enabled = settingsPage != null || polishPage != null)`.
- `AppScaffold` takes `page: PageChrome?` (`title`) instead of `settingsPage: SettingsPage?`; both page
  kinds map to it. Bottom bar hidden and back arrow shown whenever it is non-null. The badge and the mic
  stay tab-only, as today.
- `Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) })`; the host state is created in
  `EnviousWisprApp` and passed to `PolishScreen`.

### Persistence (`providers/ProviderConfigurationRepository.kt`)

- New key `last_on_mode`. `setMode(mode)` writes it when `mode != OFF`; `saveProvider` writes `PROVIDER`;
  `clearSelection` writes `OFFLINE_S1`. Same `commit()` as `mode`, same edit batch.
- `fun loadLastOnMode(): PolishMode` (default `OFFLINE_S1`, unknown string → default, `OFF` → default).
- `fun turnOn(): PolishMode` = `polishModeWhenTurnedOn(loadLastOnMode(), load() != null).also(::setMode)`.
- `internal fun polishModeWhenTurnedOn(lastOnMode: PolishMode, providerConfigured: Boolean): PolishMode`,
  top-level, pure, tested.

### View model (`ui/AppViewModel.kt`)

- `fun turnPolishOn()` → `updateProviderSettings { providerRepository.turnOn(); "" }`.
- `setPolishMode` message becomes `""` (a mode tap needs no toast); `saveProviderSettings` keeps
  `"<Provider> saved"`; `clearProviderSettings` becomes `"Provider removed"`.
- `ProviderSettingsUiState` gains `writeSequence: Int`, incremented by `refreshProviderSettings` on every
  completed write, success or failure. It is a snackbar key, not state anyone reconciles against.

### Alternatives rejected

- **Keep the tab's local mirror and add the switch on top.** Rejected: the mirror is the defect (§1.4).
- **Inline expansion inside the provider card instead of a page.** Rejected: the mockup and #53 both put
  credentials behind the card, and a page is the only place the pinned Save and IME padding work cleanly.
- **A completion signal so the setup page can stay open on save failure** (mockup line "keep the entered
  non-secret values"). Rejected: every failure Save can hit after the local validator passes is a storage
  failure (`SecretStore.put`, `commit()`), which retyping does not fix. The page pops immediately and the tab
  shows `settings.error`. Stated as a deviation from the spec.
- **Store `enabled` and `engine` as two preferences instead of `mode` plus `last_on_mode`.** Rejected: `mode`
  is read in the `:polish` process and by `PolishStatusChip`; changing its shape is a migration for no
  user-visible gain.

## 3b. Ownership justification

`last_on_mode` lives in `ProviderConfigurationRepository` because it is a fact about the same preference
file and must be written in the same `commit()` as `mode`; the alternative was `AppPreferences` (DataStore),
but two stores for one transition means one can land without the other. The setup page and the card-state
functions live in `ui/` beside `PolishScreen.kt`, `PolishStatusChip.kt` and `PolishModelCatalog.kt`, the
layer that already turns these enums into what a user sees.

## 4. Contract deltas

- **`ProviderConfigurationRepository`:** new `turnOn()`, `loadLastOnMode()` (proposed); `setMode`, `saveProvider`,
  `clearSelection` additionally maintain `last_on_mode`. To `PolishService` nothing changes: it reads
  `mode` only. To `AppViewModel`: one new entry point.
- **`ProviderSettingsUiState.writeSequence` (proposed):** "how many provider-settings writes have completed since the
  view model was created." Consumers: the tab's snackbar effect only.
- **`AppViewModel.turnPolishOn()` (proposed):** "make polish on, using the last engine, or the phone if that engine is
  gone." Never fails the `PROVIDER`-without-config check.
- **`PolishScreen` signature:** loses `onRefreshReadiness` and `onSaveProvider`; gains `onTurnOn`,
  `onOpenProviderSetup` (proposed), `onOpenLocalModel`, `onDownloadModel`, `snackbarHostState` (proposed). `KeyRung`,
  `initialKeyRung`, `cloudReactivatesImmediately` are deleted. `CloudProviders` and `savedModelFor` stay.
- **`Screen`:** third variant `Polish(PolishSubpage)`. The `when` over it is exhaustive with no `else`.
- **`AppScaffold`:** `settingsPage: SettingsPage?` → `page: PageChrome?`. One call site.

## 5. End-to-end state and lifecycle audit

| # | Transition | Population enumerated | Behaviour |
|---|---|---|---|
| 1 | Switch off | 1 write: `setMode(OFF)`; `last_on_mode` untouched | Tab collapses to the quiet line; badge shows Off |
| 2 | Switch on | `last_on_mode` ∈ {`OFFLINE_S1`, `PROVIDER`, unset/garbage} × `load()` ∈ {null, config} = 6 cells | `PROVIDER` only for (`PROVIDER`, config); all other five → `OFFLINE_S1` |
| 3 | Phone card tap | `s1State.label` ∈ 12 labels (§2.5.5) | Radio enabled only for `Ready`, or when already selected; tap → `setMode(OFFLINE_S1)` |
| 4 | Provider card tap | {unconfigured, configured key provider with key, configured without key, self-hosted} | sheet / `setMode(PROVIDER)` / disabled / `setMode(PROVIDER)` |
| 5 | Sheet row tap | 3 providers | dismiss, open setup page for that provider |
| 6 | Setup Save | validator ∈ {Valid, Invalid(API_KEY_REQUIRED), Invalid(control chars)}; endpoint reasons unreachable because endpoint is always null here | Valid → `saveProvider` (forces `PROVIDER`, writes `last_on_mode`) then pop; Invalid → inline message, stay |
| 7 | Setup Back | key draft, model draft | key dropped, model draft dropped with the page |
| 8 | Setup Remove | 1 write: `clearSelection` → `OFFLINE_S1` | pop; tab shows phone selected |
| 9 | Rotation on the tab | `showPicker` (proposed) (saveable) | picker reopens; nothing else is local |
| 10 | Rotation on the setup page | page id (saveable), model draft (saveable), key (not) | same page, model kept, key blank |
| 11 | Drawer page opened while a polish page is open | `settingsPageName`, `polishPageName` | opening one clears the other |
| 12 | Cold start with `mode` = `OFFLINE_S1` and model Missing | tab | phone card selected, `Model needed`, `Download model` |
| 13 | `settings.loading` true | tab | loading line, nothing else, unchanged from #66 |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `last_on_mode` key | `PolishService` (`:polish`) | reads `mode` | unchanged | no | grep §2.5.1 |
| `last_on_mode` key | `PolishStatusChip` | reads `settings.mode` | unchanged | no | `PolishStatusChipTest` |
| `turnOn()` | `AppViewModel.turnPolishOn` | none | new | yes | `polishModeWhenTurnedOn` test |
| `writeSequence` | tab snackbar effect | none | key | yes | hardware UAT |
| `PolishScreen` signature | `AppShell.kt:345` | old params | new params | yes | compiles |
| `Screen.Polish` | `AppShell` `when` | 2 variants | 3, exhaustive | yes | compiles |
| `AppScaffold.page` | `AppShell` one call | `settingsPage` | `page` | yes | compiles |
| Deleted `KeyRung` helpers | `PolishScreenProviderTilesTest` | 8 tests on them | tests removed | yes | suite count |

## 7. Failure-mode × caller table

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| 1 `setMode` commit fails | SharedPreferences | switch, card | `Could not update AI Polish settings` under the cards | unchanged | tap again |
| 2 `turnOn` with provider gone | repository | switch | phone card selected | `mode=OFFLINE_S1` | none needed |
| 3 Save with invalid key format | validator on page | Save | `Enter an API key for this provider.` / `The API key contains invalid characters.` under the field | none | fix and Save |
| 4 Save with blank model | page | Save | button disabled | none | pick a model |
| 5 Save storage failure | `SecretStore.put` / `commit` | Save | back on tab, `Could not update AI Polish settings` (existing fallback text at `AppViewModel.kt:437`) | partial: key may be stored without metadata; `load()` then returns null and the card shows unconfigured | open setup again |
| 6 Model missing on the phone card | WorkManager | tab | `S1-mini · Model needed`, `Download model` | unchanged | Download |
| 7 Key missing for a configured provider | Keystore | tab | `<model> · Key missing`, radio disabled, Edit provider | unchanged | Edit, enter key |
| 8 Stale saved page string | `rememberSaveable` | app restart | tab | none | none |

Copy source: `AI-POLISH-EXPERIENCE.md` for every card and page string; `content-brand.md` RULE:
no-dashes-in-user-facing-text (no dashes in any new string).

## 8. Caller-visible signals audit

| Signal | Meaning beyond its type |
|---|---|
| `settings.mode != OFF` | the switch position; nothing else decides it |
| `settings.configured && settings.provider == p` | "editing" on the setup page; drives the title, the Remove button, the key placeholder |
| `settings.credentialStored` | joined with `provider == settings.provider` before it means anything, as in #66 |
| `s1State.label == "Ready"` | the only label that enables the phone radio |
| `s1State.action ∈ {DOWNLOAD, RETRY}` | the phone card's action reads `Download model` |
| `settings.message.isNotBlank()` | a snackbar is due; `writeSequence` is the key that makes two identical messages two toasts |
| `settings.error != null` | the last write failed; cleared at the start of the next write (`AppViewModel.kt:423`) |
| `polishPageName == null` | the tab is showing; the bottom bar and badge are visible |

## 9. Fallback source-of-truth audit

| Failure branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| 2 | `polishModeWhenTurnedOn(last, load() != null)` | repository | same file, same lock as `mode` | returns `OFFLINE_S1` for five of six cells (§5.2) | n/a, total function | switch |
| 5 | `settings.error` | `refreshProviderSettings` | the only writer of the field | non-null after a failed write | fallback text at `AppViewModel.kt:437` | tab |
| 6 | `s1State` | `AppShell.kt:261-270` | single computation shared with the badge | label set of twelve | none, total | phone card |
| 7 | `settings.credentialStored` | `refreshProviderSettings` | reads the Keystore on every refresh | joined with provider equality | radio disabled | provider card |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`: `KEY_LAST_ON_MODE` (proposed),
  `loadLastOnMode`, `turnOn`, writes in `setMode`/`saveProvider`/`clearSelection`, top-level
  `polishModeWhenTurnedOn`.
- `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt`: `turnPolishOn`, `writeSequence`, message strings.
- `app/src/main/java/com/envi/wispr/ui/AppShell.kt`: `PolishSubpage` (proposed), `Screen.Polish`, `polishPageName`,
  `PageChrome` (proposed), `AppScaffold.page`, `SnackbarHost` (external), `PolishScreen` wiring, `ProviderSetupPage` (proposed) and
  `LocalModelPage` rendering, `BackHandler`.
- `app/src/main/java/com/envi/wispr/ui/PolishScreen.kt`: rewrite: loading gate, switch, cards, sheet,
  info card, `LocalModelPage`, `RouteGlyph`, `EngineCard`. Delete `KeyRung`, `initialKeyRung`,
  `cloudReactivatesImmediately`, `resetLocalStateAfterClear`, the inline catalog.
- `app/src/main/java/com/envi/wispr/ui/PolishStatusChip.kt`: reword the comment at line 74 that names the
  deleted `initialKeyRung`; no logic change.
- `app/src/main/java/com/envi/wispr/ui/PolishCardState.kt` (new): `PhoneCardState` (proposed), `ProviderCardState` (proposed),
  `phoneCard(s1State, settings)`, `providerCard(settings)`, pure.
- `app/src/main/java/com/envi/wispr/ui/ProviderSetupPage.kt` (new): the setup page; `ScoreDots` and the
  catalog list move here; `userMessage()` moves here.
- `app/src/test/java/com/envi/wispr/providers/PolishModeWhenTurnedOnTest.kt` (new).
- `app/src/test/java/com/envi/wispr/ui/PolishCardStateTest.kt` (new).
- `app/src/test/java/com/envi/wispr/ui/PolishSubpageTest.kt` (new).
- `app/src/test/java/com/envi/wispr/ui/PolishScreenProviderTilesTest.kt`: keep the `CloudProviders` and
  `savedModelFor` tests; delete the `initialKeyRung` and `cloudReactivatesImmediately` tests with their
  subjects.
- `.claude/knowledge/current-state.md`: the AI Polish tab description. `.claude/knowledge/session-log.md`.
  `~/.claude/knowledge/enviouswispr/data/036-android-ai-polish-switch-and-cards.sql`.
- `docs/mockups/android-v2-drawer/`: committed with this change as the spec of record.

## 11. Testing

1. **Class.** `PolishModeWhenTurnedOnTest` (proposed): product outcome; when it fails the user flips the switch on and
   lands on the wrong engine or on a provider that no longer exists. `PolishCardStateTest`: product outcome;
   when it fails the phone card lets the user select a model that is not there, or the provider card says
   Configured with no key. `PolishSubpageTest` (proposed): drift guard on the saved-string round trip; when it fails a
   rotation on the setup page drops the user back to the tab.
2. **Revert that turns it red.** Return `lastOnMode` (proposed) unconditionally from `polishModeWhenTurnedOn` → the
   "provider gone" case goes red. Enable the phone radio for every label → the Missing case goes red. Make
   `fromSaved` (proposed) return `LocalModel` (proposed) for unknown strings → the garbage case goes red. Each revert is performed
   once during the build and restored.
3. **Not tested.** Compose layout, the sheet, the snackbar and the pinned button: no Compose test rig exists
   (#48); covered by the hardware pass.

### 11.1 Hardware UAT spec

- **Subsystem:** limb (polish), plus one dictation per mode to prove the heart still inserts.
- **Recipe:** `device-testing.md` RULE: use-appium-to-read-the-screen-not-screenshots for driving; back up
  `envious_wispr_provider_configuration.xml` and `enviouswispr_settings.preferences_pb` (external) before the run.
- **Expected observation:** the Preface narrative, read from the page source (text and `checked` state of
  the radios), the badge text, and the History row's polish line after each dictation into Messages.
- **Phone state to restore:** provider removed, mode back to what the backup holds, preferences files
  diff-identical.

### 11.2 Other obligations

| Test | Class | Proves | Revert |
|---|---|---|---|
| `PolishModeWhenTurnedOnTest` (6 cells) | product outcome | switch-on lands on the right engine | see 11.2 |
| `PolishCardStateTest` (12 labels × selected/not; 4 provider states) | product outcome | radio enablement and status text | see 11.2 |
| `PolishSubpageTest` | drift guard | saved page string round trip | see 11.2 |
| `PolishStatusChipTest`, `PolishModelCatalogTest` | existing | unchanged | n/a |

## 12. Blast radius & rollback

- Touched: `providers/ProviderConfigurationRepository.kt`, `ui/AppViewModel.kt`, `ui/AppShell.kt`,
  `ui/PolishScreen.kt`, two new `ui/` files, three new tests, one trimmed test.
- Not touched: `polish/`, `providers/ProviderPolishClient.kt`, `providers/SecretStore.kt`,
  `ui/PolishStatusChip.kt`, `ui/PolishModelCatalog.kt`, `ui/ModelCards.kt`, any other tab.
- Rollback: revert the merge commit. The extra preference key is ignored by the previous build.

## 13. Ship criteria specific to THIS change

- [ ] With polish off, the tab shows the switch off, one line, and no S1-mini card, on the S26 Ultra.
- [ ] Flipping the switch on returns to the engine used last.
- [ ] Provider setup completes on its own page and returns to the tab with the provider card selected and
      the badge updated in the same instant.
- [ ] A dictation into Messages after each mode lands, and History names the engine that polished it.

## 14. Open questions

None at plan time. The two spec deviations (catalog instead of free-text Model ID; pop on save failure) are
decided in §3 and surfaced at Gate 2.

## 15. Related

#67 (this), #53 (closed by this), #62 / PR #66 (superseded layout), #61 (live key check, still open), #52
(card style), #48 (no Compose test rig). Catalog features `ai-polish`, `cloud-polish`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
