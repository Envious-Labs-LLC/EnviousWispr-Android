# Issue #67 — AI Polish: master switch, two engine cards, provider setup page — 2026-09-01

GitHub issue: `#67`. Tier: MEDIUM. Status: SHIPPED (2026-09-01, PR to follow this commit). Phase 6 of `plan-2026-09-01-ai-polish-refinement-roadmap.md`;
depends on phase 1 (#69, shipped: the engine holds no settings) and phase 4 (#77, shipped: the failure
notice and History facts); closes #64 (semantic model health) because the phone card reads it. Updated
2026-09-02 to carry the coverage-round findings explicitly (Review log).

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
Marcus Weber is unaffected. Aaron Wu wants no modal interruption: the sheet and the page are user-initiated,
never pushed. Diana Foster wants zero app-switching, and the setup page is the one place she leaves the
tab, once. Priya Ramachandran switches providers often and uses new model IDs: the saved model stays
selectable even when the catalog does not list it (§3, setup page). The only tension is Elena wanting more words on the provider card
versus Frank wanting fewer; the card carries one privacy line and the setup page carries the full
disclosure, which is where Elena reads before committing a key.

---

## 0. TL;DR

Rebuild the AI Polish tab to the founder's `AI-POLISH-EXPERIENCE.md`: a master switch, two full-width
single-choice cards (On this phone, Your provider), S1-mini status inside its card and hidden when polish is
off, a Material 3 bottom sheet to pick a provider, and a full-screen setup page with one Save. Turning the
switch on restores the mode used last (founder decision 2026-09-01, option 1). The structural fix is that
the tab renders persisted `settings` directly and only `showPicker` is local; the setup page owns the
unsaved key and model drafts, which are form input, never a second representation of active polish state.
That removes the reconcile-after-the-fact class that cost #62 23 review rounds. MEDIUM. Proof: unit tests on the pure card-state, turn-on and subpage-routing functions,
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
- Changing `PolishService`, `ProviderPolishClient`, the Keystore store, or the model catalog contents.
  `PolishStatusChip` and `StatusPill` change only where they classify readiness (#64): their visuals and
  copy stay.
- Animating the processing-route glyph. It is a static Canvas row of dots; motion is future polish.
- Replacing the mockup's free-text Model ID with anything other than the existing catalog (decision at
  Gate 1, 2026-09-01: keep the catalog).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

**Mode.** Owner `ProviderConfigurationRepository` (`providers/ProviderConfigurationRepository.kt:28`
`loadMode`, `:97-101` `setMode`, `:79` `saveProvider` forces `PROVIDER`, `:103-119` `clearSelection` forces
`OFFLINE_S1`; lines as of 2026-09-02). Storage: SharedPreferences `envious_wispr_provider_configuration`, key `mode`. Consumers,
from `/usr/bin/grep -rn "PolishMode\." app/src/main/java`:

- `ui/AppViewModel.kt:368-376` `setPolishMode` (refuses `PROVIDER` when `load()` is null), `:397-401`
  `clearProviderSettings`, `:404-419` `refreshProviderSettings` builds `ProviderSettingsUiState`.
- `ui/AppShell.kt:345-352` passes `uiState.providerSettings` to `PolishScreen`; `:300-302` builds the badge
  from the same value.
- `ui/PolishScreen.kt` (the mirror, deleted by this plan) and `ui/PolishStatusChip.kt` (unchanged).
- Since #69 the repository's session-policy snapshot, `loadPolicy()`, derives from `mode` (repository line
  131 as reviewed 2026-09-02); the caller that latches it once per session and sends it with every
  request is the session owner (`polish-engines.md` FACT: the-engine-holds-no-settings; that caller is
  outside this plan's read set and is cited from the knowledge file, not re-read here). `last_on_mode` has
  no policy meaning: nothing derives a policy from it.

**Provider, model, key.** Owner `ProviderConfigurationRepository.load()/saveProvider()/clearSelection()`
(`:31-54`, `:56-83`, `:100-117`); key in `SecretStore` (Keystore), never in preferences. Producer of a save:
today `PolishScreen.pickModel` (`PolishScreen.kt:203-240`) → `onSaveProvider` → `AppViewModel.saveProviderSettings`
(`:378-395`) → `updateProviderSettings` (`:422-445`, mutex, IO) → `refreshProviderSettings`. After this plan
the producer is the setup page's Save, through the same view-model function.

**S1-mini readiness.** `AppShell.kt:261-270` computes `polishS1State` from WorkManager and passes it to both
the badge and `PolishScreen`. Unchanged; the tab's phone card and the new local-model page both read it.

**Navigation.** `AppShell.kt:393-397` `Screen` is a sealed interface (`Tab`, `Page`); `:149-179`
`SettingsPage` enum owns the drawer pages; `AppScaffold` (`:409-485`) hides the bottom bar and swaps the
menu glyph for a back arrow when `settingsPage != null`; `BackHandler` at `:256`. The provider setup page
and the local-model page become a third `Screen` variant and reuse that chrome.

### 2. Find the existing authority before proposing one

- "Remember the mode used last": `/usr/bin/grep -rn "last_on\|lastOn\|previousMode\|lastMode" app/src/main/java`
  returns nothing. `new authority proposed`: one preference key beside `mode`, owned by the repository.
- Full-screen page with back arrow and no bottom bar: `SettingsPage` + `AppScaffold` already do this for
  seven drawer pages. Reused, not duplicated.
- Modal bottom sheet: `/usr/bin/grep -rln "ModalBottomSheet" app/src/main/java` returns nothing. Compose BOM
  `2026.02.01` (`app/build.gradle.kts:71`) ships `androidx.compose.material3.ModalBottomSheet`. Platform
  primitive, not a new authority.
- Snackbar: `/usr/bin/grep -rln "Snackbar" app/src/main/java` returns nothing. `Scaffold(snackbarHost = ...)`
  is the platform primitive; one `SnackbarHostState` hoisted in `EnviousWisprApp`.
- Card with icon tile, title, description, chevron: `DictionaryScreen.kt:668-731` `ImportPickerCard` is the
  house style (44 dp tile, `RoundedCornerShape(12.dp)`, primary at 15% alpha). The polish cards follow the
  same measurements; the composable itself is private to Dictionary and carries no radio, so the polish
  card is its own composable with the same tokens rather than a widened shared one.
- Radio semantics on a card: `Modifier.selectable(selected, role = Role.RadioButton)` plus a
  `RadioButton` glyph; the platform owns the TalkBack contract.

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
  in §3 with the reason: fresh Self-hosted setup (excluded by the catalog decision of 2026-09-01) and the
  free-text Model ID (the catalog, with the saved model pinned). "Keep entered values on save failure" IS
  followed since the 2026-09-02 revision (Save waits and stays on failure).

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **The engine never reads the preference file (#69).** `turnOn()` writes `mode` through the same
  `commit()` path as `setMode`; the next dictation's latched policy sees the restored mode exactly as it
  sees a mode tap today, and a running dictation keeps the policy it started with. The coverage round's
  "already-running `:polish` retaining a stale view" is closed by construction since phase 1.
- **Setup page across a configuration change.** The key draft is plain `remember` and dies (by design,
  unchanged). The model draft is `rememberSaveable` (non-secret). The page identity itself is saved as a
  string in `rememberSaveable` beside `settingsPageName`, so rotation stays on the page.
- **Save then process death before the write lands.** `updateProviderSettings` runs in `viewModelScope`;
  process death loses the queued write, and the page's saved wait target names a write the new view model
  (sequence 0) will never complete, so the page pops itself to the tab while the initial load is still
  running; the tab then shows the truth. Nothing on the tab claims otherwise because the tab has no draft.
- **Turn on when the last mode was a provider that is gone.** `polishModeWhenTurnedOn` (pure) returns
  `OFFLINE_S1` unless `lastOnMode == PROVIDER && load() != null`. `setPolishMode`'s existing refusal of
  `PROVIDER` with no configuration is therefore never reached from the switch.
- **Phone card tap when the model is not Ready.** The radio is disabled unless `s1State.health == READY`
  or the card is already selected, per the spec line "Do not activate the local mode until the model is
  ready." Turning the switch on can still land on `OFFLINE_S1` with a missing model, because that is the
  last mode used; the card then shows `Model needed` and `Download model`, and `PolishService` already falls
  open to deterministic text (`PolishService.kt:73-75`).
- **Two writers, one mutex.** All writes still go through `updateProviderSettings`'s mutex; the sheet and
  the page add no write path of their own.

### 5. Prove the high-risk premises

- **Claim: `PolishScreen` is reached from exactly one call site.** `grep -n "PolishScreen(" app/src/main/java/com/envi/wispr/ui/*.kt`
  → `AppShell.kt:345` and the definition. Verified 2026-09-01.
- **Claim: no code outside `PolishScreen.kt` and its test references `KeyRung` (removed), `initialKeyRung`,
  `cloudReactivatesImmediately`.** `/usr/bin/grep -rn "KeyRung\|initialKeyRung\|cloudReactivatesImmediately" app/src`
  → `ui/PolishScreen.kt`, `ui/PolishScreenProviderTilesTest.kt`, and one comment at
  `ui/PolishStatusChip.kt:74` that names `initialKeyRung`; that comment is reworded in this change. Verified
  2026-09-01.
- **Claim: `settings.message` and `settings.error` are displayed nowhere today.** `/usr/bin/grep -rn "settings.message\|settings.error\|providerSettings.message\|providerSettings.error" app/src/main/java`
  → nothing. A failed provider write is silent today; §7 fixes that.
- **`ModelUiState.label` is NOT a closed vocabulary** (coverage round): `modelUiState` produces twelve
  strings today, but the field accepts any string and `AppShell` already constructs one with `""`. The
  card therefore does not classify by label. #64 lands here: `ModelUiState` gains `health: ModelHealth`
  (`READY`, `NOT_READY`, `BROKEN`, `UNKNOWN`), set in every `modelUiState` branch beside its label
  so the two cannot drift: Ready is `READY`; Failed, Repair needed and Update failed are `BROKEN`; every
  other known non-ready state is `NOT_READY`; only the `AppShell` placeholder is `UNKNOWN`. Every consumer
  that compared strings (`ModelCards.StatusPill`, `PolishStatusChip`'s dot, the new `phoneCard`)
  matches on `health` with an exhaustive `when`, never on a label. `ModelDeliveryUiTest` asserts the health
  of every branch and `PolishStatusChipTest` is updated. Visuals and copy do not change.
- **Claim: SharedPreferences across processes is already how `mode` works.** `PolishService.onCreate`
  (`PolishService.kt:141-145`) constructs its own repository over the same file. No change in risk.
- **Codex problem-only consult before §3:** the coverage round named the three questions grep cannot settle
  (cross-process preference visibility, snackbar replay after recreation, who consumes an asynchronous
  write result). The first is moot since #69 (above); the other two are answered in §3 (snackbar keyed
  and remembered; Save waits for the write result) and put to the grounded round.

## 3. Design

### The tab (`ui/PolishScreen.kt`, rewritten)

Inputs: `settings: ProviderSettingsUiState`, `s1State: ModelUiState`. Callbacks: `onTurnOn`, `onSetMode`,
`onOpenProviderSetup(Provider)`, `onOpenLocalModel`, `onDownloadModel`, `onClearProvider`. Local state:
`showPicker: Boolean` (`rememberSaveable`). The loading gate from #66 stays as the first line.

1. **Switch card.** Sparkle tile, `AI Polish`, `Turns rough speech into ready-to-send text.`, `Switch`
   checked when `settings.mode != OFF`. On → `onTurnOn()`. Off → `onSetMode(OFF)`.
2. **Off:** one line, `AI Polish is off. Basic cleanup still runs.`, `bodyMedium`, `onSurfaceVariant`.
3. **On:** eyebrow `WHERE POLISH RUNS`, then two `EngineCard`s inside `AnimatedContent`
   (`animateContentSize`; whether it honours the system animator scale is checked on the phone during UAT
   with Developer options animator scale set to off, not assumed).
   - `EngineCard(selected, enabled, tile, title, status, statusColour, privacyLine, action, onSelect)`:
     `Card` with a 1 dp `primary` border when selected, `selectable(role = RadioButton)` on the whole card,
     `RadioButton` at the trailing edge, the route glyph (`RouteGlyph`, a Canvas row of coloured dots and a
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
5. **Snackbar.** `lastShownWriteSequence` is a `rememberSaveable` int hoisted to `EnviousWisprApp`,
   OUTSIDE the animated screen body, because `AnimatedContent` removes the tab while another page shows and
   a remembered value inside the tab would be recreated with it. The effect shows `settings.message` when
   non-blank, `writeSequence > lastShownSequence`, and the AI Polish tab is current; then records the
   sequence. After process recreation, if the new view model's sequence is lower than the restored value,
   the value resets to the current sequence before later writes are judged. One message, once. `settings.error`, when non-null, renders as a
   line under the cards in the error colour.

### The picker (a `ModalBottomSheet` (external) inside `PolishScreen`)

`ModalBottomSheet` with `rememberModalBottomSheetState`, drag handle, title `Choose a provider`, line `Your
words are sent only when this mode is used.`, one `selectable` row per `CloudProviders` entry with the
provider glyph, name, `Use your API key`, a `RadioButton` (checked iff `settings.configured &&
settings.provider == it`) and a chevron. Tap → dismiss, then `onOpenProviderSetup(it)`. `Cancel` text button.

### The setup page (`ui/ProviderSetupPage.kt`, new)

Inputs: `provider`, `settings`, `onSave(Provider, model, apiKey?)`, `onClear`, `onDone`. Title `Set up
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
- Editing only: `OutlinedButton("Remove saved provider and key")` → `onClear()` returns its request
  sequence; the page waits on it exactly as Save does and pops only on a completed write with no error.
- Pinned `Save provider`, enabled iff the model draft is non-blank and (key non-blank or a key is stored for
  this provider). On tap: normalise the model (same checks as today's `pickModel`), run
  `ProviderConfigurationValidator.validate(ProviderConfiguration(provider, null), effectiveKey)`; on
  `Invalid` show `reason.userMessage()` under the key field and stay; on `Valid` call `onSave`, which returns the
  request sequence of THIS write; the page stores it in `rememberSaveable`, shows `Saving` with Save
  disabled (across rotation), and completes only when `settings.writeSequence >= target`: with no error →
  `onDone()` (the tab shows the snackbar); with an error → stay, show it under Save, drafts intact (the
  mockup's "keep the entered non-secret values"). Remove waits the same way on its own sequence. Process
  death while the write is queued loses the page and the draft; the tab then shows the truth.
- The saved model for this provider stays selectable even when the catalog no longer lists it: the list
  is the catalog plus, pinned first and marked `Saved`, the saved model when absent from it (coverage
  finding: a valid newer model ID must not be stranded). Free-text IDs stay out (Gate 1 decision).
- One line under the header, always: `Your saved provider stays in use until you save.` Opening another
  provider's setup changes no routing (the latched policy is read at session start, #69), and the line says
  so, which closes the coverage round's "visible setup versus actual routing" finding without a mechanism.
- Back (arrow or system) → `onDone()`, discarding the key draft.

### The local-model page (`LocalModelPage`, in `PolishScreen.kt`)

Title `S1-mini`. Renders the existing `ModelCard` with the action wiring lifted verbatim from
`PolishScreen.kt:618-640`. This is where Pause, Resume, Retry, Repair, Update and Remove live; the tab
shows none of them.

### Navigation (`ui/AppShell.kt`)

- `internal sealed interface PolishSubpage { data class ProviderSetup(val provider: Provider); data object
  LocalModel }` with pure `toSaved(): String` / `fromSaved(String): PolishSubpage?` (`setup:OPENAI`,
  `model`; unknown → null, so a stale saved string falls back to the tab).
- `Screen` gains `data class Polish(val page: PolishSubpage)`. `polishPageName` is a `rememberSaveable`
  string beside `settingsPageName`; a drawer page cannot open over a polish subpage because the drawer's
  `gesturesEnabled` is `settingsPage == null && polishPage == null`, and the back arrow and the system
  `BackHandler(enabled = settingsPage != null || polishPage != null)` share ONE clearing callback that
  nulls both names. The provider sheet owns Back while it is open. `Screen.Polish` and `PolishSubpage` are
  rendered by exhaustive `when` expressions with no `else`.
- `AppScaffold` takes `page: PageChrome?` (`title`) instead of `settingsPage: SettingsPage?`; both page
  kinds map to it. Bottom bar hidden and back arrow shown whenever it is non-null. The badge and the mic
  stay tab-only, as today.
- `Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) })`: `EnviousWisprApp` owns the host, its
  state, the sequence memory and the display effect. `PolishScreen` receives none of them.

### Persistence (`providers/ProviderConfigurationRepository.kt`)

- New key `last_on_mode`. Every non-Off mode write commits `mode` and `last_on_mode` in ONE editor batch:
  `setMode(mode)` writes it when `mode != OFF`; `saveProvider` writes `PROVIDER`; `clearSelection` writes
  `OFFLINE_S1`. Off changes only `mode`. `turnOn()` chooses the saved non-Off mode, then commits the chosen
  mode through that same path.
- `fun loadLastOnMode(): PolishMode` (default `OFFLINE_S1`, unknown string → default, `OFF` → default).
- `fun turnOn(): PolishMode`: a remembered provider is USABLE only when its metadata is saved and its key
  is still in the Keystore (self-hosted needs none), the same rule as the provider card's radio; then
  `polishModeWhenTurnedOn(loadLastOnMode(), providerUsable).also(::setMode)`.
- `internal fun polishModeWhenTurnedOn(lastOnMode: PolishMode, providerUsable: Boolean): PolishMode`,
  top-level, pure, tested.

### View model (`ui/AppViewModel.kt`)

- `fun turnPolishOn()` → `updateProviderSettings { providerRepository.turnOn(); "" }`.
- `setPolishMode` message becomes `""` (a mode tap needs no toast); `saveProviderSettings` keeps
  `"<Provider> saved"`; `clearProviderSettings` becomes `"Provider removed"`.
- `ProviderSettingsUiState` gains `writeSequence: Int`, the sequence number of the LAST COMPLETED write,
  success or failure; the initial refresh does not advance it. Every write is allocated a request sequence
  BEFORE it is enqueued, and `saveProviderSettings`, `clearProviderSettings` and `setPolishMode` return
  that number, so a caller can wait for ITS write and not for an older queued one. Each completed result
  also carries its origin (`ProviderWriteOrigin`: `TAB` or `SETUP_PAGE`), so the tab renders only
  failures started on the tab and the page only its own.

### Alternatives rejected

- **Keep the tab's local mirror and add the switch on top.** Rejected: the mirror is the defect (§1.4).
- **Inline expansion inside the provider card instead of a page.** Rejected: the mockup and #53 both put
  credentials behind the card, and a page is the only place the pinned Save and IME padding work cleanly.
- **Pop immediately on Save and let the tab show the error** (the first draft of this plan). Rejected by the
  coverage round: it discards the drafts, may follow a partial key write, and moves the error away from the
  form. `writeSequence` already exists for the snackbar, so the page waits on it (§3).
- **Store `enabled` and `engine` as two preferences instead of `mode` plus `last_on_mode`.** Rejected: `mode` is
  what `loadPolicy()` derives the session policy from and what `PolishStatusChip` reads; changing its shape
  is a migration for no user-visible gain.

## 3b. Ownership justification

`last_on_mode` lives in `ProviderConfigurationRepository` because it is a fact about the same preference
file and must be written in the same `commit()` as `mode`; the alternative was `AppPreferences` (DataStore),
but two stores for one transition means one can land without the other. The setup page and the card-state
functions live in `ui/` beside `PolishScreen.kt`, `PolishStatusChip.kt` and `PolishModelCatalog.kt`, the
layer that already turns these enums into what a user sees.

## 4. Contract deltas

- **`ProviderConfigurationRepository`:** new `turnOn()`, `loadLastOnMode()`; `setMode`, `saveProvider`,
  `clearSelection` additionally maintain `last_on_mode`. `loadPolicy()` still derives the session policy from
  `mode` (the latching caller is outside this plan's read set); `last_on_mode` has no policy meaning. To
  `AppViewModel`: one new entry point.
- **`ProviderSettingsUiState.writeSequence`:** the sequence number of the last completed provider-settings
  write. Consumers: the snackbar effect in `EnviousWisprApp`, and the setup page's Save and Remove waits.
- **`AppViewModel.turnPolishOn()`:** "make polish on, using the last engine, or the phone if that engine is
  gone." Never fails the `PROVIDER`-without-config check.
- **`PolishScreen` signature:** loses `onRefreshReadiness` and `onSaveProvider`; gains `onTurnOn`,
  `onOpenProviderSetup`, `onOpenLocalModel`, `onDownloadModel`; the snackbar is not its concern. `KeyRung`,
  `initialKeyRung`, `cloudReactivatesImmediately` are deleted. `CloudProviders` and `savedModelFor` stay.
- **`Screen`:** third variant `Polish(PolishSubpage)`. The `when` over it is exhaustive with no `else`.
- **`AppScaffold`:** `settingsPage: SettingsPage?` → `page: PageChrome?`. One call site.

## 5. End-to-end state and lifecycle audit

| # | Transition | Population enumerated | Behaviour |
|---|---|---|---|
| 1 | Switch off | 1 write: `setMode(OFF)`; `last_on_mode` untouched | Tab collapses to the quiet line; badge shows Off |
| 2 | Switch on | `last_on_mode` ∈ {`OFFLINE_S1`, `PROVIDER`, unset/garbage} × `load()` ∈ {null, config} = 6 cells | `PROVIDER` only for (`PROVIDER`, config); all other five → `OFFLINE_S1` |
| 3 | Phone card tap | `s1State.health` ∈ {READY, NOT_READY, BROKEN, UNKNOWN} | Radio enabled only for `READY`, or when already selected; tap → `setMode(OFFLINE_S1)` |
| 4 | Provider card tap | {unconfigured, configured key provider with key, configured without key, self-hosted} | sheet / `setMode(PROVIDER)` / disabled / `setMode(PROVIDER)` |
| 5 | Sheet row tap | 3 providers | dismiss, open setup page for that provider |
| 6 | Setup Save | validator ∈ {Valid, Invalid(API_KEY_REQUIRED), Invalid(control chars)}; endpoint reasons unreachable because endpoint is always null here | Valid → `saveProvider` (forces `PROVIDER`, writes `last_on_mode`); the page waits for its own sequence and pops only on success; Invalid → inline message, stay |
| 7 | Setup Back | key draft, model draft | key dropped, model draft dropped with the page |
| 8 | Setup Remove | 1 write: `clearSelection` → `OFFLINE_S1` | waits on its own sequence; pops on success, stays with the error on failure |
| 9 | Rotation on the tab | `showPicker` (saveable) | picker reopens; nothing else is local |
| 10 | Rotation on the setup page | page id (saveable), model draft (saveable), key (not) | same page, model kept, key blank |
| 11 | Drawer swipe while a polish subpage is open | `gesturesEnabled` false | the drawer stays closed and the draft intact; a drawer page and a polish page are never open together |
| 12 | Cold start with `mode` = `OFFLINE_S1` and model Missing | tab | phone card selected, `Model needed`, `Download model` |
| 13 | `settings.loading` true | tab | loading line, nothing else, unchanged from #66; no unit test (no Compose rig, #48); seen on the phone that no card renders while loading |
| 14 | Upgrade: `mode` set, `last_on_mode` absent | repository | `loadLastOnMode()` defaults to `OFFLINE_S1`; a provider mode with no `last_on_mode` restores to the phone, never to a provider it cannot prove |
| 15 | Provider metadata present, key missing | tab, setup page | card `<model> · Key missing`, radio disabled; setup page title `Edit`, key placeholder says a key is required |
| 16 | Rotation or tab re-entry after a message showed | tab | no replay: `lastShownWriteSequence` remembered |
| 17 | Process death while Save is queued | setup page | the restored page pops itself during the initial load; the tab shows whatever landed |
| 18 | Save tapped, write not yet complete | setup page | page stays with `Saving`; pops only on a completed successful write |
| 19 | A dictation while another provider's setup page is open | session owner | the latched policy is the SAVED provider; the page says so |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `last_on_mode` key | `loadPolicy()` (the session policy) | derives from `mode` | unchanged | no | §2.5.1 |
| `last_on_mode` key | `PolishStatusChip` | reads `settings.mode` | unchanged | no | `PolishStatusChipTest` |
| `turnOn()` | `AppViewModel.turnPolishOn` | none | new | yes | `polishModeWhenTurnedOn` test |
| `writeSequence` | snackbar effect in `EnviousWisprApp`; setup page Save and Remove | none | key and wait target | yes | `PolishCardStateTest` (the snackbar and setup-wait cases), hardware UAT |
| `PolishScreen` signature | `AppShell.kt:345` | old params | new params | yes | compiles |
| `Screen.Polish` | `AppShell` `when` | 2 variants | 3, exhaustive | yes | compiles |
| `AppScaffold.page` | `AppShell` one call | `settingsPage` | `page` | yes | compiles |
| Deleted `KeyRung` helpers | `PolishScreenProviderTilesTest` | 8 tests on them | tests removed | yes | suite count |

## 7. Failure-mode × caller table

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| 1 `setMode` commit fails | SharedPreferences | switch, card | `Could not update AI Polish settings` under the cards | unchanged | tap again |
| 2 `turnOn` with provider gone, or its key gone | repository | switch | phone card selected | `mode=OFFLINE_S1` | none needed |
| 3 Save with invalid key format | validator on page | Save | `Enter an API key for this provider.` / `The API key contains invalid characters.` under the field | none | fix and Save |
| 4 Save with blank model | page | Save | button disabled | none | pick a model |
| 5 Save storage failure | `SecretStore.put` / `commit` | Save | stays on the setup page, `Could not update AI Polish settings` under Save, drafts retained | partial: an encrypted key may have landed before the metadata failed; `load()` then returns null and the card shows unconfigured | Save again |
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
| `s1State.health == READY` | the only health that enables the phone radio |
| `s1State.action ∈ {DOWNLOAD, RETRY}` | the phone card's action reads `Download model` |
| `settings.message.isNotBlank()` | a snackbar is due; `writeSequence` is the key that makes two identical messages two toasts |
| `settings.error != null` | the last write failed; cleared at the start of the next write (`AppViewModel.kt:423`) |
| `polishPageName == null` | the tab is showing; the bottom bar and badge are visible |

## 9. Fallback source-of-truth audit

| Failure branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| 2 | `polishModeWhenTurnedOn(last, load() != null)` | repository | same file, same lock as `mode` | returns `OFFLINE_S1` for five of six cells (§5.2) | n/a, total function | switch |
| 5 | the completed result matching the setup page's target sequence and `SETUP_PAGE` origin | `refreshProviderSettings` | the only writer of the field | non-null after that failed write | fallback text in `AppViewModel` | setup page; the tab does not render it |
| 6 | `s1State` | `AppShell.kt:261-270` | single computation shared with the badge | label set of twelve | none, total | phone card |
| 7 | `settings.credentialStored` | `refreshProviderSettings` | reads the Keystore on every refresh | joined with provider equality | radio disabled | provider card |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`: `KEY_LAST_ON_MODE`,
  `loadLastOnMode`, `turnOn`, writes in `setMode`/`saveProvider`/`clearSelection`, top-level
  `polishModeWhenTurnedOn`, and the constructor split (`internal` primary over `SharedPreferences` and
  `SecretStore`; public `Context` secondary delegating to it) for the failing-fake test only.
- `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt`: `turnPolishOn`, `writeSequence`, message strings.
- `app/src/main/java/com/envi/wispr/ui/AppShell.kt`: `PolishSubpage`, `Screen.Polish`, `polishPageName`,
  `PageChrome`, `AppScaffold.page`, `SnackbarHost`, `PolishScreen` wiring, `ProviderSetupPage` and
  `LocalModelPage` rendering, `BackHandler`.
- `app/src/main/java/com/envi/wispr/ui/PolishScreen.kt`: rewrite: loading gate, switch, cards, sheet,
  info card, `LocalModelPage`, `RouteGlyph`, `EngineCard`. Delete `KeyRung`, `initialKeyRung`,
  `cloudReactivatesImmediately`, `resetLocalStateAfterClear`, the inline catalog.
- `app/src/main/java/com/envi/wispr/models/ModelDeliveryUi.kt`: `ModelHealth`, set in every branch (#64).
- `app/src/main/java/com/envi/wispr/ui/ModelCards.kt`: `StatusPill` reads `health`.
- `app/src/main/java/com/envi/wispr/ui/PolishStatusChip.kt`: the dot reads `health`; reword the comment
  that names the deleted `initialKeyRung`.
- `app/src/main/java/com/envi/wispr/ui/PolishCardState.kt` (new): `PhoneCardState`, `ProviderCardState`,
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

1. **Class.** Every pure test below is a LOGIC test: it proves the helper, never that the screen calls it
   (coverage of the wiring is the hardware pass, item 4). `PolishModeWhenTurnedOnTest`: product outcome; when it fails the user flips the switch on and
   lands on the wrong engine or on a provider that no longer exists. `PolishCardStateTest`: product outcome;
   when it fails the phone card lets the user select a model that is not there, or the provider card says
   Configured with no key. `PolishSubpageTest`: drift guard on the saved-string round trip; when it fails a
   rotation on the setup page drops the user back to the tab.
2. **Revert that turns it red.** Return `lastOnMode` unconditionally from `polishModeWhenTurnedOn` → the
   "provider gone" case goes red. Enable the phone radio for every label → the Missing case goes red. Make
   `fromSaved` return `LocalModel` for unknown strings → the garbage case goes red. Each revert is performed
   once during the build and restored.
3. **Persistence and lifecycle (coverage finding).** `ProviderConfigurationRepositoryTest` (androidTest,
   exists) uses the real named SharedPreferences file: preload sentinel values, call the real `setMode`,
   `saveProvider`, `clearSelection` and `turnOn`, and assert BOTH `mode` and `last_on_mode` after every
   transition and all six turn-on cells; the absent-key default. Failure atomicity cannot be staged on real
   preferences, so `SharedPreferences` and `SecretStore` become the repository's `internal` PRIMARY constructor
   parameters and the public `Context` constructor becomes a secondary one that derives the real
   preferences and delegates to it (a secondary constructor cannot replace an already initialised
   property; today `preferences` is a private property built from the `Context`, lines 19 to 26); the
   production `Context` constructor serves the real-file androidTest, and the primary serves only the
   explicitly named failing-fake atomicity test, a deterministic fake whose `commit()` returns false and asserts both keys untouched; that test is named as a fake,
   never as a real-preferences test. `ModelDeliveryUiTest` gains
   the health of every branch (#64). `PolishCardStateTest` also holds the pure "show once per
   sequence" snackbar decision and the pure "stay until the sequence advances, pop only without error"
   setup-page decision.
4. **Not tested.** Compose layout, the sheet, the snackbar host and the pinned button: no Compose test rig
   exists (#48); covered by the hardware pass, which now also covers rotation on the tab and on the setup
   page, a drawer-swipe attempt over a polish page (the drawer stays closed, the draft intact), system back from the sheet, and a storage failure staged
   by a full disk only if it can be staged without harming the phone (else reported not run).

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
| `PolishStatusChipTest`, `ModelDeliveryUiTest` | existing, updated | the dot and the pill classify by `ModelHealth` | swap BROKEN and READY in one branch |
| `PolishModelCatalogTest` | existing | unchanged | n/a |

## 12. Blast radius & rollback

- Touched: `providers/ProviderConfigurationRepository.kt`, `ui/AppViewModel.kt`, `ui/AppShell.kt`,
  `ui/PolishScreen.kt`, two new `ui/` files, three new tests, one trimmed test.
- Touched for #64 only: `models/ModelDeliveryUi.kt`, `ui/ModelCards.kt`, `ui/PolishStatusChip.kt`
  (readiness classification moves from labels to `ModelHealth`; visuals and copy unchanged).
- Not touched: `polish/`, `providers/ProviderPolishClient.kt`, `providers/SecretStore.kt`,
  `ui/PolishModelCatalog.kt`, any other tab.
- Rollback: revert the merge commit. The extra preference key is ignored by the previous build.

## 13. Ship criteria specific to THIS change

- [x] With polish off, the tab shows the switch off, one line, and no S1-mini card, on the S26 Ultra.
- [x] Flipping the switch on returns to the engine used last (phone, S1-mini; then Gemini after a save).
- [x] Provider setup completes on its own page and returns to the tab with the provider card selected and
      the badge updated in the same instant.
- [ ] A dictation into Messages after each mode lands, and History names the engine that polished it. NOT
      RUN (§13.1): the change touched no capture, transcription or insertion code, and the founder was
      using the phone and asked that testing stop there.

## 13.1 Found on the phone and the emulator

Phone (S26 Ultra, debug, 2026-09-01 23:15 to 23:19), read through Appium and the preference file: off state
as designed; switch on wrote `mode` and `last_on_mode` together and selected the phone card with
"S1-mini · Ready" and a green badge; the sheet listed the three providers; "Set up Gemini" took a stand-in
key and a model, kept the page and the model draft across rotation (the key draft clears, by design), and
ignored a drawer swipe; Save returned to the tab with the Gemini card selected and the badge green; off then
on came back to Gemini; "Edit Gemini" pinned the saved model. Emulator (no model): the phone card read
"S1-mini · Failed" with a red dot and refused selection; OpenAI setup, Save, Edit and Remove returned to the
tab with the phone card selected. The repository device test ran on the emulator: OK, 10 tests.

Not restored on the phone at the founder's request to stop: it holds the stand-in Gemini provider; the
two-tap removal was handed to him. The founder's four-rung Ladder design for this tab is recorded as #81.

## 14. Open questions

None at plan time. The one remaining spec deviation (the catalog instead of a free-text Model ID, with the
saved model pinned so it is never stranded) is decided in §3 and surfaced at Gate 2.

## 15. Related

#67 (this), #53 (closed by this), #62 / PR #66 (superseded layout), #61 (live key check, still open), #52
(card style), #48 (no Compose test rig), #81 (the founder's Ladder layout, a later rebuild of this tab).
Catalog features `ai-polish`, `cloud-polish`.

---

## Review log

- **Code round 1, 2026-09-02, same session:** nine findings. Two REJECTED with evidence: they cited
  `polishModeWhenTurnedOn` ignoring its flag and `fromSaved` returning `LocalModel` for garbage, which
  were this author's deliberate revert-receipt edits on disk during the read (the committed code is
  correct and both tests are green; reverts now run only while no review is reading the tree). Seven
  adopted: `turnOn` requires the provider's key (self-hosted excepted), the same rule as the card; the
  write-failure error is always the calm sentence, never the exception text; the setup page pins the
  SAVED model, not the draft; a wait target restored after process death pops the page during the initial
  load and the failure text survives rotation; the page title reads Set up or Edit plus the display
  name; the real-file turn-on test gains the key-gone and absent-`last_on_mode`-with-a-configuration
  cells; the failing fake records the keys of each refused commit so one-batch is asserted.
- **Grounded round 3, 2026-09-02, same session:** one implementation finding, adopted: a secondary
  constructor cannot replace an initialised property, so the seam is an `internal` primary constructor
  over `SharedPreferences` and `SecretStore` with the public `Context` constructor delegating to it. No
  design finding on any axis; per the founder's diminishing-returns guidance no fourth round is run and
  the design is taken as PROCEED-AS-PLANNED.
- **Grounded round 2, 2026-09-02, same session:** six residues, all six adopted: the last label comparison
  in §2.5.4; the `PolishService`-reads-`mode` wording in the alternatives, §4 and §6; `writeSequence`'s
  consumers; the snackbar host, state and memory owned by `EnviousWisprApp` with `PolishScreen` receiving
  none; Save "then pop" and the drawer row and UAT line; and the repository seam: `preferences` is a private
  property built from the `Context` (lines 19 to 26), not a constructor parameter as this plan had said, so
  an `internal` secondary constructor taking `SharedPreferences` is added for the failing-fake test.
- **Grounded round 1, 2026-09-02, Codex session `01a05ff2-125c-7223-bfab-4a8a4c2a1335` (fresh session,
  the coverage session being long):** PROCEED-WITH-REVISIONS, all thirteen adopted: direction and
  `last_on_mode` placement confirmed with tighter wording; `ModelHealth` finished (four members, every
  consumer, `StatusPill` and the chip now touched and listed); the `loadPolicy` premise restated without
  naming an unread caller; the deviations recounted (self-hosted and the catalog; save-failure now follows
  the spec); line citations refreshed; Save and Remove wait on THEIR OWN request sequence with an origin
  tag; the snackbar memory hoisted above the animated body with a recreation reset; drawer gestures off
  over a subpage and one clearing callback for both Backs; the pure tests classified as logic tests; the
  repository test on the real file plus a named failing fake for atomicity.
- **Coverage round, 2026-09-01, Codex session `01a05e59-b013-71f0-b3ed-3af5726e5254`:** seven findings, all
  now carried: the label vocabulary is open (#64 lands here, semantic health); snackbar ownership and replay
  (keyed and remembered on the tab); the missing lifecycle rows (§5 rows 14 to 19); the skipped consult
  (its three questions answered or mooted by #69); persistence and lifecycle tests (§11.3); the stranded
  newer model and popping on failure (the saved model pinned; Save waits for the write result); visible
  setup versus routing (closed by #69's latched policy and said on the page).

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
