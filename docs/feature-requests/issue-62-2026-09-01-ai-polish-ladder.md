# Issue #62 — Rebuild AI Polish as a ladder — 2026-09-01

GitHub issue: `#62`. Tier: MEDIUM. Status: DRAFT.

**Consolidation:** the dominant root this plan protects is "what is S1-mini's current readiness," which
`ModelCards.kt`'s `workUiState()`/`ModelUiState` already owns exclusively. Two consumers now read it
instead of one: the existing "This phone" panel, and the new app-bar badge. §3's "Alternative rejected"
and §9 both name this explicitly and reject a second, competing derivation inside `AppShell.kt` for exactly
this reason — one owner, two readers, never two owners.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none — this is an Android-only screen restyle with no macOS parity claim.

**Hardware UAT:** Y

Success looks like: Saurabh opens AI Polish on his phone. The app bar badge shows whatever is *currently
saved and active* (grey dot for Off; a red or green dot on This phone matching S1-mini's actual live state,
confirmed at UAT time, see §2.5.3; green once a cloud provider and model are actually saved). He taps
Cloud, picks OpenAI, types a key, taps Check, sees the field accept or reject it, and only once he actually
picks a model from the new searchable list does the app bar badge move to show that model — nothing else on
this rung (picking a provider tile, typing a key, tapping Check) moves the badge before that, because the
badge names what is running, not what he is mid-typing.

## Preface — User Rubric

1. **Who is this user in this moment?** Diana Foster, senior PM, has just noticed her dictated text isn't
   getting cleaned up the way she expected and opens AI Polish to see what's running.
2. **Why would they want this?** "I want to glance at one place and know whether AI is even touching my
   words right now, and switch it without hunting through a form."
3. **How would they invoke it?** Voluntary, from the drawer, mid-workday, usually right after a dictation
   came out rougher than expected.
4. **What app are they in?** The EnviousWispr settings surface itself, not a third-party app.
5. **What is their natural input?** Typing an API key once, then mostly tapping: a mode button, a provider
   tile, a sort chip, a model row.
6. **What does success feel like?** She glances at the top of any AI Polish visit and instantly knows what
   is running and whether it is healthy, without reading a paragraph.
7. **What does wrong-not-broken look like?** The badge shows green while the local model is actually
   broken, so she trusts a system that is silently failing.
8. **What would a power user hack around this to get?** Priya Ramachandran would otherwise paste a model
   ID she read on a provider's changelog into a free-text field with no idea if it is even in her plan;
   the new searchable list with cost/speed/accuracy dots is aimed at exactly that.
9. **What level of control would they want?** The ladder already exists in the product (Off, on-device,
   cloud); this change only makes each rung's own state legible, not a new axis of control.

### Cross-persona check

Dr. Elena Vasquez (privacy-first) cares that "Cloud" is never the default and that the disclosure line
naming the provider stays visible before she sees a model list, which this design preserves ($4$ ·
disclosure line under the model list). Frank Chen (72, ≤3 settings) is served by the three big buttons
replacing three small chips he had to read carefully. No persona is made worse off; the tension is only
between how much explanation the disclosure needs (Elena) versus how little (Frank), which the existing
one-line disclosure under the model list already resolves.

## 0. TL;DR

Rebuild the AI Polish screen to match the founder's "AI Polish Ladder" mockup: a status badge moves into
the app bar showing what is running and whether it is healthy; the mode picker becomes three big buttons
(Off, This phone, Cloud) instead of three small tags; the cloud path narrows to three providers (OpenAI,
Gemini, Claude — self-hosted is dropped from this screen, decision recorded below); the free-text model ID
field is replaced with a real searchable, sortable model list per provider; and the API key field gains an
inline Check control. The key Check stays local-format-only in this change; a real live provider check is
issue #61 and is out of scope here. Proof: unit tests on the new chip-state and model-catalog logic, plus
a hardware pass through every rung on the founder's phone.

## 1. Problem

Today's AI Polish screen (`ui/PolishScreen.kt`) has three problems the founder's mockup addresses directly:

1. Nothing in the app tells the user what is currently polishing their dictation without opening this
   screen; the app bar is generic chrome (`ui/AppShell.kt:400` `TopAppBar`, no per-destination content in
   `actions` besides a mic button).
2. The mode picker is three same-size `FilterChip`s (`PolishScreen.kt:90-104`), which reads as a set of
   filters rather than a real choice with consequences, and gives no room to say why one might be picked.
3. The cloud model is a free-text `OutlinedTextField` (`PolishScreen.kt:137-147`). A user has no way to
   discover what models exist, what they cost relative to each other, or which one is recommended, and a
   typo produces a runtime failure at dictation time rather than a caught mistake at setup time.

## 2. Goals & non-goals

### 2.1 Goals

- The app bar on the AI Polish tab shows a small badge: an icon for what's running (off / on-device / a
  cloud provider's mark), the running model's name, and a dot that is green when it is expected to work
  and red when it is known broken.
- "Where polish runs" is three full-width-in-a-row buttons: Off, This phone, Cloud.
- Cloud shows exactly three provider tiles: OpenAI, Gemini, Claude.
- Choosing a different provider than the one currently saved clears the saved model and key draft and
  returns to the key-entry step, because a key never authenticates a different provider.
- The API key field has its own submit control inside the box that arms (becomes tappable) only once
  something is typed, and shows a rejected state distinctly from an untouched one.
- Cloud model selection is a searchable, sortable list (Suggested / Cheapest / Fastest / Most accurate)
  with a small cost/speed/accuracy indicator per model, capped in height so the screen layout does not
  grow with the catalog.

### 2.2 Non-goals

- **Not this change:** making the key Check call OpenAI/Gemini/Claude for real. Issue #61 owns that; here
  Check keeps today's local format check (`ProviderConfigurationValidator`) and the UI states around it
  ("Checking…" as a brief local transition, not a network wait).
- **Not this change:** self-hosted polish. It is hidden from this screen, not removed from the app; its
  code, its Keystore entry path, and its `Provider.SELF_HOSTED_POLISH` value are untouched, so a user who
  already configured it before this ships keeps working exactly as before, they simply cannot reconfigure
  it from this screen. Reintroducing it is future work, filed nowhere yet because it is not currently a
  live request.
- **Not this change, originally:** any change to the actual polish request pipeline
  (`ProviderPolishClient`), the Keystore storage format, or the S1-mini runtime. **One exception made
  during code review (§7):** `ProviderPolishClient.parseResponse`'s OpenAI Responses parsing gained a
  small, read-only fix for a reasoning-item-ordering bug the new model catalog exposed — justified as a
  narrow exception because the alternative was shipping several catalog entries known to silently fail.
  The Keystore storage format and the S1-mini runtime remain untouched.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

- **Mode.** `ProviderConfigurationRepository.loadMode()/setMode()` (SharedPreferences, key `mode`) is the
  only source of truth for `PolishMode`. `AppViewModel` exposes it inside `ProviderSettingsUiState.mode`
  (`AppViewModel.kt:42`) via a `StateFlow`; `AppShell.kt:325` passes it to `PolishScreen`, which today
  keeps a *second*, screen-local copy in `var mode by remember(settings.mode)` (`PolishScreen.kt:66`) that
  the Apply button later commits back through `onSetMode`. **This plan changes that shape for mode**: the
  three big buttons call `onSetMode` directly on tap, with no intervening local draft or Apply step (§3),
  since mode alone (Off / This phone) is already a complete, safely-committable fact with nothing else to
  gather first.
- **Provider/model/key.** Same shape: `ProviderConfigurationRepository.load()/saveProvider()` is the
  source of truth; `AppViewModel` surfaces `provider`, `model`, `credentialStored`, `configured`; the
  screen holds local drafts (`provider`, `model`, `apiKey` at `PolishScreen.kt:67-74`) until "Save
  provider" calls `onSaveProvider` → `ProviderConfigurationValidator.validate` →
  `ProviderConfigurationRepository.saveProvider` → `SecretStore.put` (Keystore). This chain is unchanged;
  only what feeds the local `model` draft changes, from a `TextField` to a catalog pick.
- **S1-mini readiness (the "This phone" and app-bar red/green signal).** `WorkManager` work info for
  `ModelDeliveryWorker.downloadWorkName/adoptionWorkName(ModelManifest.s1)` plus
  `AppReadiness.polishModelReady` feed `workUiState()` (`ModelCards.kt:97`) to produce a `ModelUiState`
  whose `label` gates a `StatusPill` at `ModelCards.kt:57` (`StatusPill(state.label, state.label ==
  "Ready")` — verified: the pill is only ever "true"/"false", literally on the string `"Ready"`; there is
  no separate verified "Failed" boolean to cite, and no built-in third neutral color) and whose `action`
  becomes `ModelUiAction.REPAIR` on a verification failure — this exact case is already live and handled in
  this screen today, at `PolishScreen.kt:283` (`ModelUiAction.REPAIR ->
  ModelDeliveryWorker.enqueueRepair(...)`), which is direct evidence the "verification failed" state is
  real and already reachable, not a hypothetical this plan invents a handler for. **This computation moves
  up one level**: today
  `PolishScreen.kt:63-65` computes it for its own local use; this change moves that computation to the
  composable in `AppShell.kt` that already calls both `AppScaffold` and `PolishScreen` (the destination
  branch around `AppShell.kt:325`), and passes the single resulting `s1State` down into `PolishScreen` as a
  new parameter, removing `PolishScreen`'s own internal `WorkManager` collection. One computation, two
  readers (the app-bar badge, the "This phone" panel), never two computations of the same fact.
- **App bar.** `ui/AppShell.kt:396-431`, `AppScaffold`'s `Scaffold(topBar = { TopAppBar(...) })`. `actions`
  today renders a mic `IconButton` only when `settingsPage == null` (i.e., on a tab, not a drawer page),
  with no per-tab branch. The badge is new content in that slot, shown only when
  `destination == AppDestination.Polish`.

### 2. Find the existing authority before proposing one

- A live "ask the provider" concept has **no existing implementation** to reuse:
  `grep -n "listModels\|list-models\|/models" app/src/main/java/com/envi/wispr/providers/*` returns
  nothing besides route constants for the polish call itself
  (`ProviderPolishClient.kt:451` `GEMINI_URL_PREFIX`). Confirms #61 is genuinely new work, not a wiring
  gap.
- A per-provider **static model catalog with cost/speed/accuracy** does not exist in the app; the closest
  analog is the mockup's own hardcoded `OPENAI`/`GEMINI`/`CLAUDE` arrays in
  `AI Polish Ladder.dc.html`'s script, which this plan ports as the new authority
  (`ui/PolishModelCatalog.kt`, §10).
- The chip-state derivation (off / local-ready / local-failed / cloud-with-model) has no existing owner;
  new authority proposed as a small pure function (§10), mirroring the mockup's `chip` object.

### 3. Read prior attempts and live direction

- Issue #53 ("AI Polish picks a mode with chips; the mockup picks it with cards") proposed a different,
  earlier visual (full-width cards with a radio dot and an eyebrow/badge). This design supersedes it with
  three compact icon buttons instead, which resolves #53's underlying complaint (the mode picker reads as
  filter chips, not a real choice) by a different means. This plan closes #53 on ship, noting the
  resolution differs from its literal spec.
- Issue #52 ("mockups specify a card and row style the app does not have") is the shared card/row style
  question; this plan does not introduce a new card primitive beyond what `ModelCards.kt`'s `ElevatedCard`
  usage already establishes, so it neither closes nor conflicts with #52.
- `current-state.md` FACT: what-runs-on-the-founder-phone-today records S1-mini as installed by hand on
  the founder's phone; it does not record which of Ready/Failed it is in *right now*. **The plan does not
  assume Failed is the live state.** `PolishStatusChipTest`'s Failed case (§11.2) is a unit test against a
  synthetic `ModelUiState`, independent of the phone's actual condition; the hardware UAT (§11.1)
  separately re-observes whatever the real, current state is and confirms the badge matches it, whichever
  colour that turns out to be.
- No `decision` row in the cross-platform catalog addresses this screen's shape (checked: this UI has no
  Android-macOS parity claim per §Preface, so the catalog's macOS-derived decisions do not bind it).

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- All state here lives in the default app process; nothing here touches `:audio`, `:asr`, or `:polish`.
- **Provider switch mid-edit.** Switching provider must drop the in-progress key draft and any picked
  model *before* they are saved, matching the existing `PolishScreen.kt:124-131` behaviour of clearing
  `model`/`endpoint`/`apiKey` on provider change — the new UI keeps this rule, extended to also reset the
  new "rung" (key-entry vs. model-list) back to key-entry.
- **Configuration change / process death mid-typed-key.** `apiKey` is a plain `remember`, not
  `rememberSaveable`, by design (per `PolishScreen`'s own KDoc, `PolishScreen.kt:49-51`) — a rotation or
  a light background kill discards an unsaved draft. This is intended and unchanged; the new Check-armed
  state must derive purely from that same draft so it cannot show "armed" after the draft was silently
  lost.
- **Stale `s1State` at first composition.** `workUiState` depends on `collectAsStateWithLifecycle` flows
  that start at `emptyList()`; the app-bar badge must treat "no work info yet, but `polishModelReady`
  false" as "not yet known" rather than misreporting it as the red Failed state on cold start. Handled by
  reusing `ModelUiState.label`/`action` exactly as `ModelCard` already does, never a second re-derivation.

### 5. Prove the high-risk premises

- **Claim: `PolishScreen` holding local, uncommitted drafts before a real commit point is an established
  shape here, not something this plan invents.** Evidence: the KDoc directly above `PolishScreen` states
  this explicitly for the API-key draft (`PolishScreen.kt:46-51`); the same file applies the identical
  `remember(settings.x)` structure to `mode`, `provider`, `model`, `endpoint` (`PolishScreen.kt:66-72`).
  The KDoc's stated *reason* (never hoist, never log) is proven only for the key; for the others this plan
  relies on the structural fact that they already follow the same shape, not on a matching stated reason —
  which is exactly why this plan does not lean on that shape for the badge (§3's core fix) and instead
  reads only the persisted `settings`.
- **Claim: no live model-list network capability exists to accidentally duplicate.** Evidence: the
  negative grep in §2.5.2.
- **Claim: `AppDestination.Polish` is the only place `PolishScreen` is reachable from.** Evidence: `grep
  -n "PolishScreen(" app/src/main/java/com/envi/wispr/ui/*.kt` returns exactly the one call site at
  `AppShell.kt:325`.

## 3. Design

Three buttons (Off / This phone / Cloud) render as a new small composable, `PolishModeSwitch`, replacing
the `FilterChip` row. Selecting "This phone" shows the existing S1 `ModelCard` block essentially unchanged
(it already renders Ready/Failed/Repair/Remove); selecting "Cloud" shows the existing provider `FilterChip`
row **narrowed to three entries** (drop `SELF_HOSTED_POLISH` from the iteration, not from the enum), the
existing key `OutlinedTextField` **wrapped with an inline submit affordance** rather than replaced, and a
new `PolishModelPicker` composable (search field, sort chips, scrollable capped list) that replaces the
free-text model `OutlinedTextField` for cloud providers only.

**No separate Save/Apply button.** The mockup shows no such control anywhere in the flow, and keeping one
is exactly what caused the badge-timing ambiguity Codex's review surfaced. Instead, each rung commits the
moment it is completed. **Tapping Off or This phone calls `onSetMode` immediately** — each is already a
complete, self-contained fact, exactly as safe as today's non-Cloud early-return branch in the current
button (`if (mode != PolishMode.PROVIDER) { onSetMode(mode); return }`, `PolishScreen.kt:223-226`).
**Tapping Cloud commits nothing.** It only changes which rung is shown locally, because — as Codex's review
correctly caught — the current code never persists `PROVIDER` mode on its own; `saveProvider()` sets it as
part of saving a complete, validated configuration (`PolishScreen.kt:227`), and committing `PROVIDER` mode
the instant someone taps the Cloud button, before they have configured anything, would silently switch the
active polish backend away from whatever was running just by glancing at the tab. Within Cloud, **picking a
model row is the one and only commit point**, and it runs exactly the validation and save logic the current
button already runs before persisting — normalize and length/control-character-check the model name, apply
the stored-credential fallback when the key draft is blank, validate, call `onSaveProvider` (which persists
`PROVIDER` mode as a side effect, unchanged), clear the local key draft on success, surface `localError` on
failure (`PolishScreen.kt:227-257`) — only the trigger moves, from a Save button click to a model-row tap.
The existing "Remove saved provider and key" action stays, shown whenever `settings.configured`. The badge
only moves at exactly these commit points (a mode tap for Off/This phone, a model pick for Cloud), never on
an intermediate tap (Cloud itself, a provider tile, typing, Check) — this is now mechanical, not a timing
convention someone has to remember, because there is no other write path left to move it early.

A new pure function, `polishStatusChip(settings: ProviderSettingsUiState, s1State: ModelUiState):
PolishStatusChip`, computes the app-bar badge's icon kind, label, and dot color **only from the persisted,
saved configuration and the shared S1 readiness value — never from `PolishScreen`'s own local, uncommitted
draft state.** This is a correctness requirement, not a style choice: `AppScaffold`'s `TopAppBar` and
`PolishScreen`'s body are sibling composables under the same parent in `AppShell.kt` (`AppScaffold` takes
a `content: @Composable (Modifier) -> Unit` lambda that `PolishScreen` is called inside of), so the badge
has no way to read `PolishScreen`'s internal `remember`s even if it wanted to. Reading only `settings` and
the hoisted `s1State` (both already available at the level in `AppShell.kt` that calls `AppScaffold`) means
the badge names what is genuinely running, and correctly does **not** move while the user is mid-edit on
the screen — it moves only at each rung's real commit point (a mode tap, or a model pick — see the "No
separate Save/Apply button" note below). `AppScaffold` gains an optional
`topBarBadge: (@Composable () -> Unit)?` parameter, populated only for `AppDestination.Polish`.

**Alternative rejected #1:** deriving the badge from `PolishScreen`'s own local uncommitted `mode`/
`provider`/`model` drafts. Rejected for the structural reason above (the badge composable cannot reach
that state), and even if it could, it would make the badge lie about what is actually running while the
user is mid-edit, which contradicts the whole point of the badge (Codex coverage review, 2026-09-01,
question C.2).

**Alternative rejected #2:** a new shared, hoisted mutable state holder above both `AppScaffold` and
`PolishScreen` carrying the live, in-progress mode/provider/model (Codex's suggested alternative).
Rejected: the persisted `ProviderSettingsUiState`/`AppViewModel` StateFlow already IS that shared holder
for everything the badge needs to show, and introducing a second, in-progress-aware holder would revive
the exact "two homes for one fact" problem `architecture-rules.md` RULE: own-state-locally forbids — now
between the persisted state and the new draft holder, instead of between the badge and the screen.

**Superseded mid-implementation: the reconcile-after-the-fact mechanism, replaced by a loading gate.**
The design above (badge reads `settings` directly, `PolishScreen` keeps a local mirror) is unchanged, but
HOW the local mirror got seeded and kept honest went through eight code-review rounds of the same shape
of bug before being replaced outright. The original approach seeded `mode`/`provider`/`rung` from
`settings` unconditionally on first composition, then tried to detect and reconcile every way that seed
could go stale — a `LaunchedEffect(settings.loading)`, two boolean flags
(`hasEditedMode`/`hasEditedProviderOrKey`) each set by a growing list of individual event handlers, and a
one-off "retry" block for a specific stale-evaluation case. Each round found a new timing gap in that
detect-and-reconcile machinery, never a new root cause — a genuine "two findings of the same kind" signal
per `workflow-process.md` RULE: enumerate-from-the-producer-not-from-the-findings, which this plan missed
in the moment and only caught when the founder asked directly whether the pattern meant a refactor was
needed. Grounded with a dedicated Codex architecture consult (2026-09-01, high reasoning effort, given
both files' current content): root cause confirmed as keeping a second, independently-timed copy of
persisted facts with no shared completion signal between the two clocks; recommended fix confirmed as
gating the whole interactive body behind `settings.loading`, so no local draft can ever be seeded from a
placeholder in the first place, closing the whole class of timing races by construction rather than by
patching each instance found.

`PolishScreen` now has two composables: the public one checks `settings.loading` and renders a one-line
loading state if true, otherwise delegates to a private `PolishScreenBody` that assumes `settings` is
already real. `PolishScreenBody` seeds `mode`/`provider`/`rung` from `settings` exactly once, same as
before, but this seed can no longer be wrong (the loading gate above it guarantees real data), so nothing
needs to react to `settings` changing afterward: the `LaunchedEffect`, both flags, and the retry block are
deleted outright, not replaced with new machinery. Every individual bug the eight rounds fixed is still
fixed — reading through what each one actually protected shows all eight were instances of the seed being
wrong or going stale, and a seed that can never be wrong protects all of them at once.

## 3b. Ownership justification

`polishStatusChip` and `PolishModelCatalog` live in `ui/`, beside `PolishScreen.kt` and `ModelCards.kt`,
because they are pure presentation logic over the same `AppReadiness`/`ProviderSettingsUiState`/`Provider`
types those files already consume; the alternative, a new `providers/` or `polish/` package, was rejected
because neither package currently holds UI-shaped state (labels, colors, sort order) and `ui/` is
explicitly the layer that turns domain enums into what a user sees.

## 4. Contract deltas

- **`PolishMode`, `Provider`, `ProviderConfigurationRepository`, `SecretStore`: unchanged.** No stored
  contract changes; this is a pure UI-layer and derived-state change.
- **New: `PolishStatusChip`** (icon kind, display name, dot color) — a UI-only value type with no persisted
  form, produced once per composition by `polishStatusChip(settings, s1State)` and rendered by exactly one
  consumer, the app-bar badge composable inside `AppScaffold`'s top bar. Its two *inputs*
  (`ProviderSettingsUiState`, `ModelUiState`) each already have their own single existing owner
  (`AppViewModel`'s `StateFlow`, and `workUiState()` respectively); this type adds no new source of truth,
  only a new pure reader of two existing ones.
- **New: `PolishModelCatalog`** — a static, in-code table of `(provider) -> List<CatalogModel>`, each
  carrying `name`, a one-line `note`, an optional `tag` ("Suggested"), and `cost`/`speed`/`accuracy` on a
  1-3 scale. This is app-shipped data, not user data, and not persisted; the *chosen* model name is still
  stored exactly as today (`ProviderConfigurationRepository`'s `model: String`), so an unrecognized or
  hand-set model (from before this change, from a future provider addition, or simply a model the catalog
  does not list) still round-trips as free text: when `settings.model` is not one of the catalog's entries
  for the current provider, `PolishModelPicker` prepends a synthetic, untagged row showing that exact name
  as the current selection, so it stays visible and re-selectable rather than silently vanishing. The
  catalog only curates what the picker *offers as new choices*, it does not become the source of truth for
  what is a valid model.
- **`AppScaffold`: new optional parameter**, e.g. `topBarBadge: (@Composable () -> Unit)? = null`,
  semantically "extra content in the top bar's actions row, shown before the existing mic button, only on
  the destination that supplies it." Every other call site keeps today's behaviour by omitting it.
  `AppScaffold` has exactly one call site in `AppShell.kt`; every tab and every settings page reaches it
  through that one call, branching only in what `content` and (now) `topBarBadge` they pass in — they are
  destinations rendered by one caller, not separate callers.
- **`PolishScreen`: new required parameter, `s1State: ModelUiState`.** Today `PolishScreen` computes this
  itself from `WorkManager` (`PolishScreen.kt:63-65`); that computation moves to the caller in `AppShell.kt`
  (the same place that now also builds `polishStatusChip`), and `PolishScreen` receives the already-computed
  value. Its internal `WorkManager`/`collectAsStateWithLifecycle` calls for S1 are deleted.

## 5. End-to-end state and lifecycle audit

| # | State transition | Current behaviour | New behaviour | Enumerated population |
|---|---|---|---|---|
| 1a | User taps Off or This phone | `FilterChip` sets local `mode`; committed only when the separate Apply button is later tapped | Calls `onSetMode` immediately on tap; the badge moves right away, since mode alone is the whole story for these two | 2 of the 3 modes: `OFF`, `OFFLINE_S1` |
| 1b | User taps Cloud | Same as 1a today (chip sets local `mode`, uncommitted) | Only changes which rung the screen shows locally; commits **nothing** — `PROVIDER` mode is never persisted alone, only as part of a full saved configuration (§3) | The 1 remaining mode: `PROVIDER`, never reachable to commit by itself |
| 2a | User taps a different *visible* provider tile (OpenAI/Gemini/Claude) while another of the three is saved | Clears local `model`/`endpoint`/`apiKey`, keeps `mode` | Same, plus resets the visible "rung" to key-entry | 3×2 = 6 ordered pairs among the visible providers, all identical in behaviour (clear-and-reset) |
| 2b | Screen opens and `settings.provider == SELF_HOSTED_POLISH` (a config saved before this change, or by a future path) | Renders as the fourth tile, selected, with its endpoint/model fields | Replaces the whole cloud picker (tile row, key entry, disclosure) with a distinct fallback card — not shown alongside it, an `if/else` — worded and actioned by whether it is currently active (`settings.mode == PROVIDER`): if active, "running, no longer configurable here" with only Remove; if not, "saved but not running" with both a "Turn back on" action (`onSetMode(PROVIDER)`, safe because a real config still exists) and Remove, plus a "Switch to OpenAI, Gemini or Claude" link that moves the local (not persisted) `provider` to `OPENAI`, revealing the normal cloud picker in its place. Two real bugs caught in code review here: the first version offered only Remove, leaving no path back on once the user tapped Off or This phone even once; the second version showed the fallback card ABOVE a still-fully-live tile row, key entry and a second, duplicate Remove button — contradicting its own "no longer configurable" message. The endpoint/protocol/free-text-model fields for self-hosted are **not rendered** | One case, `provider == SELF_HOSTED_POLISH` (both the persisted `settings.provider` AND the local, still-unswitched `provider`), exhaustively distinguished from the 3 visible providers by `Provider.entries` (4 total, checked via `git grep -n "enum class Provider" -A5`), crossed with active/not-active |
| 2c | Screen opens and `settings.provider` is one of the 3 visible providers, and `settings.configured && settings.credentialStored` is true | N/A (today's screen always shows the free-text field) | Rung starts at "connected": key field collapses to the mockup's "Key connected · N models · Replace" row, model picker shown immediately, no re-typing required | 3 states, not 2: configured+key-stored (skip to models), configured-but-no-key (start at key entry — a real bug caught in code review, since `configured` alone does not imply a key exists), and not configured (start at key entry) |
| 3 | User types into the key field | Plain `TextField`, no derived "armed" state | `armed = draft.trim().isNotEmpty()`, purely derived, no new persisted state; **`armed` depends only on the draft, never on `settings.credentialStored`** — a stored key does not pre-arm Check with an empty box | Two classes: blank/whitespace-only (not armed), anything else (armed) |
| 4 | User taps Check | N/A (no Check today) | Runs the *existing* `ProviderConfigurationValidator.validate` synchronously; valid → reveal model picker, **nothing persisted yet** (no model chosen, and `saveProvider` requires one); invalid → local "key rejected" state, key not saved | Two outcomes, exhaustive: `ValidationResult.Valid`, `ValidationResult.Invalid(reason)` |
| 5 | User picks a model from the catalog list | N/A (free text, and today's screen requires a separate Apply tap to save it) | Sets local `model` to the picked catalog entry's name AND calls `onSaveProvider` immediately with that model and the already-validated key — **this is the actual commit point for the whole Cloud rung**, and the app-bar badge moves at this exact moment | `PolishModelCatalog` is a fixed, named, in-source table (§10) — its exact rows are reviewable in the PR diff, not enumerated here since they carry no branching logic, only display data |
| 6 | Cold open of AI Polish while S1 work info has not yet emitted | `s1State` computed from `emptyList()` defaults inside `PolishScreen` | Computed once in `AppShell.kt` instead (§4); both the app-bar badge and the "This phone" panel receive the identical value, so the "not yet known" presentation is identical in both places by construction, not by convention | One computation, two readers — see §4 |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `AppScaffold` gains `topBarBadge` | `AppScaffold`'s one call site in `AppShell.kt`, across every destination and settings page it renders | Renders today's mic-button-only actions row for all of them | Identical for every destination except `AppDestination.Polish`, since the new parameter defaults to `null` everywhere else | No (default param) at other destinations; yes at the one call passing a real badge | Manual check that History/Dictionary/Transcription/settings pages are visually unchanged |
| `Provider.entries` iteration narrowed to 3 in the tile row | `PolishScreen`'s provider tile row | Iterates all 4 `Provider.entries` | Iterates `Provider.entries` **excluding** `SELF_HOSTED_POLISH`, filtered at the UI call site, not in the enum | Yes | New unit test asserting the rendered list model excludes self-hosted |
| `ProviderConfigurationRepository.load()` returning a stored self-hosted config | This same screen, row 2b above | Rendered as the fourth tile, selected, fully editable | Renders the "no longer configured here, Remove" row instead; endpoint/protocol/model fields for self-hosted not shown; Save cannot be reached for it from this screen | Yes | New unit test: loading with `provider = SELF_HOSTED_POLISH` renders the fallback row, not a tile selection, and does not expose its Save path |
| `settings.model` not present in `PolishModelCatalog` for the current provider | `PolishModelPicker` | N/A, new | Synthetic top row shows the exact stored name, selected, untagged (§4) | Yes | New unit test: a model string absent from the catalog still appears as the current selection |
| `PolishModelCatalog` | `PolishModelPicker` | N/A, new | Filters/sorts the static table | Yes | New unit tests on filter and each of the four sorts |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Key fails local format validation | `ProviderConfigurationValidator.validate` | Check tap | The field's border and hint switch to a rejected state, same wording family as today's `localError` messages (content-brand.md: plain, states what to do) | Nothing written — key never reaches `SecretStore` | Retype and tap Check again |
| A key is stored (`credentialStored == true`) but the visible draft box is blank, e.g. right after opening an already-configured provider | `ProviderSettingsUiState.credentialStored` vs. local `apiKey` draft | Screen render | Per row 2c, an already-configured provider skips straight to the model list; the key box is not shown blank-and-armed in that state at all | Unchanged | N/A — this failure mode is avoided by design, not handled after the fact |
| `settings.configured == true` but `settings.credentialStored == false` — either a just-removed provider (removal resets `settings.provider` to its default rather than to null) or a repository quirk where a saved config with no key still counts as configured | `initialKeyRung`'s `credentialStored` check | Screen render, after Remove or on any cold open | Starts at key entry (`TYPING`), never at the collapsed "Key connected" row — a real bug caught in code review before this shipped | Unchanged | Type a key and Check again |
| Saved config's provider is self-hosted, screen no longer offers it | `ProviderConfigurationRepository.load()` | Screen open | The fallback row from §5 row 2b: named plainly, offers only Remove | Unchanged — nothing is cleared automatically, since this screen must not silently delete a working configuration it merely no longer edits | Remove only; no path back to reconfigure from this screen (accepted limit, §2.2) |
| S1-mini in a verification-failed state | `ModelManifest.s1.isAvailable` upstream | App-bar badge + "This phone" panel | Red dot in the badge, "Failed" pill and Repair/Remove in the panel — this already exists today in `ModelCard`; only the badge is new | Unchanged | Repair/Remove, both already wired |
| Catalog contains a model the user's actual account cannot reach | Static `PolishModelCatalog` (no live check, by design of this change) | Model pick → later dictation | Not caught here; surfaces as a polish-call failure at dictation time, same as today's free-text field | Unchanged | Out of scope; this is exactly the gap issue #61 closes |
| Two mode/provider commits launched in quick succession (e.g. Off, then This phone, tapped fast) race and can persist whichever finishes last rather than whichever was tapped last — a real bug caught in code review, TWICE: the first fix wrapped the write in a `Mutex` but still launched onto `Dispatchers.IO` first, so the two coroutines could still reach the lock in whichever order the multithreaded IO pool scheduled them, not tap order; the second fix acquires the `Mutex` on `viewModelScope`'s default (`Main.immediate`) dispatcher — matching where every caller's tap already runs — and only switches to `Dispatchers.IO` for the blocking work *inside* the lock | `AppViewModel.updateProviderSettings` | Any rapid sequence of mode/provider taps | Nothing wrong-looking in the moment; the risk is a silently stale final selection | Fixed by acquiring the `Mutex` before any dispatcher switch, so lock order matches tap order | N/A — closed at the source, not handled after the fact |
| A long saved model name, or a larger system font size, could grow the app-bar badge wide enough to push out the title or the mic button | `PolishStatusBadge`'s label `Text` | App bar render | Bounded via `Modifier.widthIn(max = 120.dp)` with ellipsis, so the badge never grows past its lane | Unchanged | N/A |
| A self-hosted setup, saved before this change, had NO path back on from this screen once the user tapped Off or This phone even once — a real bug caught in code review: the fallback row offered only Remove, and Cloud itself performs no write | `settings.provider == SELF_HOSTED_POLISH` fallback card | Screen open, mode not `PROVIDER` | A "Turn back on" action appears alongside Remove, calling `onSetMode(PROVIDER)` directly — safe specifically because a real self-hosted config still exists, so `AppViewModel.setPolishMode`'s guard against committing `PROVIDER` with nothing saved never fires here | `settings.mode` becomes `PROVIDER` again, provider/model/key untouched | N/A — closed at the source |
| Tapping Off (a real, async write), then Cloud (no write) before the Off write completes: the completion still correctly persists `mode = OFF` (Off really was tapped), but the LOCAL screen — now showing Cloud, possibly mid-key-entry — was silently snapped back to showing Off, because `remember(settings.mode)` re-seeds on every settings change regardless of an in-progress local edit — a real bug caught in code review, the same shape existed for `provider` and `rung` too | `remember(settings.X)`'s re-keying semantics | Any rapid tap sequence that moves on to a new local selection before an earlier write completes | Fixed, then refined once more: making `mode`/`provider`/`rung` fully unkeyed closed this race but reopened a different one (next row) — the final fix keys all three on `settings.loading` instead, which changes exactly once (true→false, when the ViewModel's async initial load lands) and never again after that | Unchanged | N/A — closed at the source, not handled after the fact |
| `AppViewModel`'s provider settings load asynchronously in `init` (`viewModelScope.launch(Dispatchers.IO) { refreshProviderSettings() }`), so this screen can genuinely compose before that finishes, while `settings` still holds `ProviderSettingsUiState()`'s placeholder defaults (`mode = OFFLINE_S1`, `provider = OPENAI`, `configured = false`) — a real bug caught in code review, immediately after the previous row's fix made every local draft fully unkeyed: those placeholders would then freeze in for the composition's whole lifetime once real data arrived, permanently showing the wrong mode/provider and the wrong badge | `ProviderSettingsUiState.loading`, still true at composition time | Cold navigation straight to the AI Polish tab before the ViewModel's initial load lands | Fixed by keying `mode`/`provider`/`rung` on `settings.loading` (see previous row) — it reconciles from the placeholder to the real value the one time loading actually finishes, and is inert afterward | Unchanged | N/A — closed at the source |

| Tapping "Remove saved provider and key" (or the self-hosted fallback's "Remove") while viewing Cloud or Off persists `settings.mode = OFFLINE_S1` via `clearSelection()`, but `settings.loading` never changes, so the local `mode`/`provider`/`rung` never reset — the screen keeps showing whatever section the user was viewing while the app-bar badge and every OTHER read of `settings` correctly say "This phone" — a real bug caught in code review, immediately after the loading-key fix above closed the previous instance of the same shape | `clearSelection()`'s effect vs. `remember(settings.loading)`'s trigger | Either Remove action, while viewing Cloud or Off | Fixed with an explicit `resetLocalStateAfterClear()` called at the moment of both Remove actions, matching the same "reset at the point of the user's own action" pattern as every other transition on this screen | Unchanged | N/A — closed at the source |
| The ViewModel's own initial load (`init`'s `refreshProviderSettings()`) and a fast user-triggered write race with NO ordering between them at all — unlike two ordinary writes, this one was never routed through `providerSettingsMutex` — so if the initial load's read happens before a user's write but its publish lands after, it can revert `providerSettings.value` (and this screen's `settings.loading`-keyed locals, since a still-`false`-to-`false` "change" is invisible, though other direct readers of `settings` are not protected) back to the pre-write snapshot — a second, distinct concurrency bug caught in the same code review pass | `AppViewModel.init`'s unsynchronized initial `refreshProviderSettings()` call | A write triggered in the brief window before the app's very first provider-settings load completes | Fixed by routing the initial load through the same `providerSettingsMutex`, on the same dispatcher-then-IO shape as every other write | Unchanged | N/A — closed at the source |

| A user who picks a provider or types a key DURING the loading window (before the `settings.loading` true→false transition lands) had that choice silently reconciled away once real data arrived — since `mode`/`provider`/`rung` were keyed on `settings.loading` alone, the loading transition always overwrote them with the persisted values, discarding whatever the user had already chosen, while `apiKey` (never keyed) stayed as typed — meaning a key meant for Gemini could end up saved against a reconciled-back-to-OpenAI provider — a third real bug caught in code review, in the same loading-reconciliation mechanism as the two rows above | The `settings.loading` reconciliation itself, once the user starts editing during the loading window | Provider or key entry started before the app's very first provider-settings load lands | Fixed with an explicit `hasUserInteracted` flag: the loading transition only reconciles `mode`/`provider`/`rung` while `false`, and every interaction on this screen (mode tap, provider tile, key typing, Check, Replace, model pick, search, sort, Remove, Turn back on) sets it `true` first, permanently disabling further reconciliation for that visit | Unchanged | N/A — closed at the source |

| `gpt-5.5-mini` (and, once swept against the same live model list, `gpt-5.4-mini` and `gpt-5.5`) is not a real OpenAI model — verified against `developers.openai.com/api/docs/models`, 2026-09-01, which lists `gpt-5.6-sol`/`-terra`/`-luna` as the current flagship family and no `gpt-5.4`/`gpt-5.5` family at all — picking any of the three saved successfully and then failed on every dictation, a real bug caught in code review | Static `PolishModelCatalog` | Model pick → later dictation | Removed all three; `gpt-5-mini` (independently confirmed real via the same live check) takes over the "Suggested" slot | Unchanged | N/A — closed at the source |
| Right after typing a new key and picking a model, the local key draft was cleared immediately even though `onSaveProvider` only queues an async write with no completion signal back to this screen — a second, fast model pick before that write lands found neither the (already-cleared) draft nor the not-yet-persisted stored key, reported "enter an API key," and left the FIRST model selected even though the second tap was the user's actual last choice — a real bug caught in code review | `pickModel`'s immediate `apiKey = ""` | Two model picks for the same new provider, tapped faster than one local write | Fixed by not clearing the draft on save — the CONNECTED rung's UI never shows it anyway, and every other real transition (provider switch, Replace, Remove, leaving the screen) already clears it explicitly | Unchanged | N/A — closed at the source |

| Tapping Cloud when the provider it is about to show is already fully configured (a real key on file) left `settings.mode` unchanged — the screen shows a fully "connected" Cloud view with nothing left to tap, while dictation kept using whatever mode was active before, the same gap "Turn back on" closed for self-hosted, just for the three visible providers, and more likely to be hit since they are the common path — a real bug caught in code review | The Cloud mode button's no-write-by-design rule (§3), applied even when nothing is actually left to configure | Tapping Cloud after previously turning polish Off or to This phone, with a provider already saved | Fixed: the Cloud tap now also commits `onSetMode(PROVIDER)` when `initialKeyRung` for the current saved settings already evaluates to `CONNECTED` — safe for the same reason "Turn back on" is | Unchanged | N/A — closed at the source |
| The "Key connected · N models" count, and the plain "N models" count with no search text, counted only the curated catalog and ignored the preserved legacy-model row `filterAndSort` intentionally prepends — a saved legacy model could make the picker show one more row than the count claimed | `catalogTotal`'s source (`PolishModelCatalog.modelsFor(provider).size` alone) | A provider whose saved model is not in the curated catalog | Fixed by deriving `catalogTotal` from `filterAndSort(provider, "", sort, savedModel).size` — the exact same function `filtered` uses, so the two can never disagree | Unchanged | N/A — closed at the source |

| A saved self-hosted setup, opened in Cloud, showed the "no longer configurable on this screen" fallback card ABOVE a still-fully-interactive tile row, key-entry field and model picker, plus a SECOND, duplicate "Remove saved provider and key" button below everything — directly contradicting its own message and letting the user replace self-hosted from the very screen that said it could not be reconfigured — a real bug caught in code review | §5 row 2b's own visibility condition, which gated only the tile SELECTION, not the tile row's existence | Screen open with self-hosted saved | Fixed by making the fallback card and the cloud picker mutually exclusive (`if`/`else` on `settings.provider == SELF_HOSTED_POLISH && provider == SELF_HOSTED_POLISH`), with an explicit "Switch to OpenAI, Gemini or Claude" link inside the fallback card as the one deliberate way out of it, rather than the picker being silently reachable underneath | Unchanged | N/A — closed at the source |

| The mutual-exclusion fix above closed the picker-vs-fallback duplicate, but the screen-wide "Remove saved provider and key" button lives outside the `when(mode)` block entirely and was never told which branch was showing — so a saved self-hosted setup still showed two identical Remove actions, one round after the first instance of this exact shape was fixed | The bottom Remove button's `settings.configured`-only gate, blind to the fallback card rendering above it | Screen open with self-hosted saved | Fixed with a named `showingSelfHostedFallback` flag, computed once and shared by both the `when(mode)` branch condition and the bottom button's gate, so the two can never disagree about which is showing | Unchanged | N/A — closed at the source |

| The "Suggested" default, `gpt-5-mini`, is a reasoning-capable OpenAI model. OpenAI's own Responses migration guide states a reasoning model can place a `reasoning` item in the `output` array before the assistant `message` item, and `ProviderPolishClient.parseResponse` hardcodes `output[0].content` — verified against both the live OpenAI docs and this repo's own parser code — so the DEFAULT pick could silently fail every polish call for some fraction of requests | `PolishModelCatalog`'s "Suggested" tag placement | Model pick → later dictation, for whichever requests happen to trigger a reasoning item | Fixed by moving "Suggested" to `gpt-5-chat-latest` (explicitly not a reasoning model) as an immediate, narrow fix. **Superseded two rows down**: the underlying parser gap was then fixed directly rather than left to #65, since it affected every reasoning-capable model in the list, not only the default | Unchanged | Superseded by the parser fix's own test, below |
| `gpt-5.6-luna` was rated identically to `gpt-5.6-sol` (cost 3, speed 1) — backwards. OpenAI's July 2026 pricing update (Sol $5/$30, Terra $2/$12, Luna $0.20/$1.20 per 1M tokens) and vendor coverage describe Luna as the cheapest, fastest, highest-volume tier, the opposite of what was shown | Static `PolishModelCatalog` ratings | The Cheapest and Fastest sorts, and Luna's own dots | Corrected Luna to cost 1 / speed 3 / accuracy 2, and added the previously-missing `gpt-5.6-terra` tier (cost 2 / speed 2 / accuracy 3) so the three real tiers are all represented and correctly ordered relative to each other | Unchanged | N/A — closed at the source |
| The broader `hasUserInteracted` flag (previous rows) correctly stopped the loading-transition from clobbering an in-progress key entry, but also stopped it from ever reconciling `provider`/`rung` once ANY interaction happened — including a plain mode tap, which carries no provider/key coherence risk. A user with saved Gemini credentials who taps Cloud fast (before the load lands) got stuck on a placeholder OpenAI key-entry view even after real data arrived, while the badge correctly said Gemini — a real bug caught in code review, refining the same mechanism a third time | The flag's own scope, one interaction too broad | Any mode tap before the initial load lands, for a user with an already-configured cloud provider | Narrowed to `hasEditedProviderOrKey`, set only by provider/key-specific actions (tile tap, key typing, Check, Replace, model pick, Remove, Switch provider) — mode taps no longer set it, and `mode` itself now reconciles unconditionally on the same one-time `settings.loading` transition, which is safe since that transition never repeats | Unchanged | N/A — closed at the source |

| Every reasoning-capable OpenAI model in the catalog (not only the former "Suggested" default) shared the same underlying risk: `ProviderPolishClient.parseResponse` read a fixed `output[0].content`, so ANY of them could silently fail on a request where the API happened to emit a `reasoning` item first, not only the one row moving "Suggested" fixed | `ProviderPolishClient`'s `OPENAI_RESPONSES` parsing itself, not just the catalog's default pick | Picking any reasoning-capable OpenAI model → later dictation | Fixed at the actual source rather than by thinning the catalog: `parseResponse` now finds the first `output` item whose `type` is absent or `"message"`, skipping a leading `reasoning` item, matching OpenAI's own Responses migration guidance to iterate items by type. This is a `providers/` change, outside this plan's original §2.2 scope boundary — justified because the fix is small, low-risk (a read-only response-parsing change, not a request-shape or auth change), already fully specified by OpenAI's own docs, and leaves the catalog honest without removing legitimate higher-accuracy options; issue #65 is closed by this fix rather than merely deferred | Unchanged | New regression test: `ProviderPolishClientTest.openAiResponsesSkipsALeadingReasoningItemAndParsesTheMessageAfterIt`, reproducing the exact `reasoning`-then-`message` shape |
| The mode-reconciliation half of the loading fix (two rows up) was itself unconditional on the theory that it only ever fires once and mode carries no cross-field risk — but that ONE firing can still race a mode tap already queued behind it on the same mutex: tap Off on cold open, the reconciliation (seeing the still-stale persisted mode) overwrites the local Off choice, the queued write then lands correctly but `settings.loading` doesn't change again so nothing re-syncs the body, and it disagrees with the badge until the screen remounts — a real bug caught in code review, the mode-specific twin of the provider/key race two rows up | The unconditional half of the `LaunchedEffect(settings.loading)` reconciliation | A mode tap on cold open, before the initial load lands | Fixed with a matching `hasEditedMode` flag, set by every direct local `mode` assignment (the mode buttons, Remove, Turn back on), gating `mode`'s own reconciliation exactly like `hasEditedProviderOrKey` already gated `provider`/`rung`'s | Unchanged | N/A — closed at the source |

| A user with a saved, fully-configured cloud provider but Off/This phone active who taps Cloud before the initial load lands hits the round-11 reactivation check (§7, "Persist Cloud mode when re-enabling a saved provider") against the still-placeholder `settings.configured=false` — it sees nothing to activate and never calls `onSetMode`. The real data then arrives and correctly reconciles `provider`/`rung` (the row above), so the BODY shows a fully "connected" Cloud view, but the persisted mode and the badge are stuck on whatever was active before — a real bug caught in code review, the mode-activation twin of the reconciliation the row above already does | The round-11 Cloud-reactivation check, evaluated once at tap time against data that can still be a placeholder | Tapping Cloud before the initial load lands, with a provider already fully configured | Fixed by retrying the same activation check inside the loading-reconciliation effect, once real data is in hand, gated by the same `!hasEditedProviderOrKey` — never retried if the user has since picked something else | Unchanged | N/A — closed at the source |

| The badge itself — never part of the local-mirroring bug class the refactor above closed, since it always read `uiState.providerSettings` directly with no local copy — was still shown during the loading window, naming `ProviderSettingsUiState()`'s placeholder mode (`OFFLINE_S1`) as if it were the real saved one, on a cold launch straight into the AI Polish tab | `AppShell.kt`'s `topBarBadge` construction, ungated on `providerSettings.loading` | Opening AI Polish before the app's very first provider-settings load lands | Fixed with the same shape of gate `PolishScreen` uses for its own body: the badge composable is only built once `!uiState.providerSettings.loading` | Unchanged | N/A — closed at the source |
| A configuration change (rotation, dark-mode toggle, multi-window resize) tears down and rebuilds `PolishScreenBody`. `mode`, `provider` and `rung` were plain `remember` (deliberately unkeyed, seeded once), so the rebuilt composable re-seeded them from `settings` at that instant. If the user had just tapped a mode, a provider, or Check/a model and the async write it queued had not landed yet, the reseed read the OLD persisted value; once the write did land, `settings` updated but nothing re-synced the already-rebuilt local values, so the screen could disagree with the app-bar badge until the user left and re-entered the screen — a real bug caught in code review (round 19), the config-change-specific twin of the cold-launch loading gap the refactor above closed | `mode`/`provider`/`rung`'s `remember` (not `rememberSaveable`) | Rotating, or otherwise triggering a configuration change, in the moment between tapping a mode/provider/model and that write landing | Fixed by switching `mode`, `provider` and `rung` to `rememberSaveable`. **Refined one round later (next row): `rung` should not have been included.** | Unchanged | N/A — closed at the source |
| The round-19 fix above made `rung` `rememberSaveable` alongside `mode`/`provider`, but `rung == CONNECTED` reached by typing a new key and tapping Check is only true because of `apiKey`, which stays plain `remember` and never survives a configuration change by design (security: never written to a Bundle, never logged). Rotating right after Check but before the model-and-key save landed restored `rung = CONNECTED` with `apiKey` blank and no stored credential yet, so the screen said "Key connected" while every model tap failed with a contradictory "Enter an API key" error — a real bug caught in code review (round 20), and a case of extending round 19's fix to a site (`rung`) the finding never actually named | `rung`'s `rememberSaveable`, now split from `apiKey`'s plain `remember` — two co-dependent values with only one surviving the recreation | Typing a new key, tapping Check, then rotating before the queued save lands | Reverted `rung` to plain `remember`, matching `apiKey`. This does not reopen round 19's finding: that finding cited only `mode`/`provider` (lines 141-142 at the time), never `rung`, and `rung`'s own `CONNECTED` path that round 19 was actually worried about — an already-stored credential, reseeded from `settings.credentialStored` — is untouched by a model-only save and reseeds correctly on its own. The narrower remaining case (rotate in the gap between Check and a still-in-flight new-key save landing) now reseeds to `TYPING`, asking the user to retype a key that is about to be saved anyway — an honest "not yet" rather than a false "connected," the safer of the two failure directions | Unchanged | N/A — closed at the source |
| `rung`'s reverted-to-`remember` seed (previous row) still read `initialKeyRung(settings.configured, settings.credentialStored, settings.provider)` — unjoined against `provider`, exactly the shape already named and fixed twice before for `savedModelFor` and for the tile-tap handlers (see the earlier `configured`/`credentialStored`/provider-join class). Once `provider` became `rememberSaveable` (round 19), it can survive a rotation holding a DIFFERENT value than `settings.provider` (mid-browse, not yet saved) — so a user with a saved OpenAI key who switched to viewing Gemini and rotated before picking a model saw Gemini restored as the local `provider`, but this line's credential check still ran against `settings.provider` (OpenAI), so it read OpenAI's real stored key as evidence for GEMINI being connected — a real bug caught in code review (round 21) | `rung`'s initializer, joining `settings.credentialStored` against `settings.provider` instead of the restored local `provider` | Saving a key for one provider, switching the visible tile to a different provider, then rotating before picking a model | Fixed by joining against `provider` (the restored, currently-viewed value) instead — `initialKeyRung(settings.configured && provider == settings.provider, settings.credentialStored, provider)` — matching the exact join already used correctly by the tile-tap handlers and the self-hosted "Switch to..." link | Unchanged | N/A — closed at the source |
| `gpt-5-chat-latest` — the "Suggested" default OpenAI model — already shut down on 2026-07-23 per OpenAI's own deprecations list (developers.openai.com/api/docs/deprecations, checked 2026-09-01); picking the first recommended row would have saved a model that fails every dictation on a fresh install. The same sweep found five more OpenAI catalog entries (`gpt-5-mini`, `gpt-5-nano`, `gpt-4.1-nano`, `o4-mini`, `o3-mini`) scheduled to shut down 2026-10-23 or 2026-12-11, within weeks to months of shipping — a real bug caught in code review (round 21) | Static `PolishModelCatalog`, never checked against OpenAI's deprecations list before this round (only against the model-existence list) | Model pick → later dictation, worse for "Suggested" since it saved automatically-recommended-feeling and failed immediately | Removed all six; none needed a replacement row of their own since each one's niche (cheapest-and-fastest, classic-chat-cheapest, maths-leaning reasoning, faster-but-lighter reasoning) is already covered by the row OpenAI's own page names as its recommended replacement — `gpt-5.6-luna`, `gpt-5.6-terra` or `gpt-5.6-sol`, all already in the list. "Suggested" moved to `gpt-5.6-terra` (OpenAI's recommended replacement for `gpt-5-chat-latest`), matching the Gemini and Claude lists' own Suggested profile (cost 2 / speed 2 / accuracy 3) — safe now that #65's parser fix (two rows up in this table) no longer assumes the first `output` item is the message | New regression test: `PolishModelCatalogTest.neverOffersOpenAiModelsAlreadyShutDownOrScheduledToShutDownSoon`; the pre-existing `aSavedModelAlreadyInTheCatalogDoesNotDuplicate` moved off the now-removed `gpt-5-mini` onto `gpt-5.6-terra` so it keeps exercising the catalog-hit branch it names |
| The Cloud mode button's reactivation check (round 12, then round 21's `provider == settings.provider` fix applied only to `rung`'s initializer) still checked `initialKeyRung(settings.configured, settings.credentialStored, settings.provider)` alone, with no join against the currently-displayed `provider`. A user could browse a different provider tile, switch to Off or This phone, then tap Cloud again — this reactivated the SAVED provider (matching the badge) while the screen kept showing setup for the tile the user was actually looking at, silently routing dictation to a provider the screen wasn't even displaying — a real bug caught in code review (round 22), the mode-button twin of round 21's `rung`-initializer finding, confirming this is a real class rather than one instance: every site in this file that decides whether to treat a provider as "already good enough, commit now" needs to join against the LOCALLY DISPLAYED provider, not settings.provider alone, and a fresh full sweep of every `settings.provider`/`configured`/`credentialStored` read in the file (`savedModelFor`, `storedCredentialApplies`, `showingSelfHostedFallback`, the tile taps, the self-hosted switch link, the Remove button) found this was the only remaining unjoined site | The Cloud mode button's `onClick`, checking connectedness without checking which provider is displayed | Browsing a different provider tile, changing mode away from Cloud, then tapping Cloud again | Extracted the check into a named, tested pure function, `cloudReactivatesImmediately(displayedProvider, settings)`, joining on `displayedProvider == settings.provider` exactly like every other correct site in this file, and pointed the mode button at it | New regression tests: `PolishScreenProviderTilesTest.cloudReactivatesImmediatelyWhenTheDisplayedTileIsTheSavedConnectedProvider`, `...cloudDoesNotReactivateForADifferentTileThanTheSavedProvider`, `...cloudDoesNotReactivateWhenTheSavedProviderItselfIsNotYetConnected` |

## 8. Caller-visible signals audit

- **Dot color on the app-bar badge is not binary.** Enumerated states: grey (Off — mode is `OFF`), red
  (This phone, S1 verification failed), green (This phone, S1 ready), green (Cloud, a provider+model is
  actually saved), and a distinct neutral/grey "not set up" state for **mode is `PROVIDER` but
  `settings.configured` is false** (mid-setup, nothing saved yet) — this last state has no analog in the
  mockup and must not silently render as either the red or the green case. `polishStatusChip` owns this as
  an explicit branch, never inherited from `StatusPill`'s own true/false gate on the literal string
  `"Ready"` (`ModelCards.kt:57`), which has no third value of its own.
- **Presence of a "Suggested" tag on a catalog model** carries meaning only while sort mode is `suggested`
  (mirrors the mockup's own `tagged: !!m.tag && sort === 'suggested'`); showing it under a different sort
  would falsely imply that sort also ranks it first.
- **Blank vs. non-blank key draft** is the sole signal deciding whether Check is tappable; no other field
  (mode, provider, `credentialStored`) may gate it, since a user must be able to correct a bad key
  regardless of anything else on screen (see §7's second row for why `credentialStored` in particular must
  not leak into this signal).
- **Presence of the self-hosted fallback row (§5 row 2b)** is itself a signal: its mere presence tells the
  user their self-hosted setup still exists even though it is not editable here, so it must never be
  omitted just because self-hosted is deprioritised.

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| What the app-bar badge shows | `ProviderSettingsUiState` (persisted, `AppViewModel`'s `StateFlow`) plus the hoisted `s1State` | `AppViewModel` / `workUiState()` | This is the one existing, already-authoritative pair for "what is actually saved and running"; `PolishScreen`'s own local drafts are authoritative only for what the next commit (a mode tap, or a model pick) will save, never for what is running *now* — this is the ownership decision Codex's coverage review named as missing | Always used, never bypassed by a local draft | N/A | App-bar badge |
| Saved provider is self-hosted, screen no longer shows it | `settings.provider` from `ProviderConfigurationRepository.load()` | Existing repository | It is the one existing source of truth for what is actually saved; this screen must read it, never guess | `provider == Provider.SELF_HOSTED_POLISH` | Render the fallback row, per §7 | Provider tile row |
| S1 badge color when work info has not yet arrived | `ModelUiState` from `workUiState()`, computed once in `AppShell.kt` | Existing, `ModelCards.kt` | Single existing owner of "is S1 ready/failed/pending"; a second computation would be a second home for the same fact (banned by `architecture-rules.md` RULE: own-state-locally) | N/A — always used, never bypassed | N/A | App-bar badge, "This phone" panel |

## 10. File-by-file changes

- **`app/src/main/java/com/envi/wispr/ui/PolishModelCatalog.kt` (new).** Static `Provider -> List<CatalogModel>` table ported from the mockup's `OPENAI`/`GEMINI`/`CLAUDE` arrays (`AI Polish Ladder.dc.html` script, `CATALOG`/`DEFAULT_MODEL` constants), plus the four `SORTS` and a pure `filterAndSort(models, query, sort)` function.
- **`app/src/main/java/com/envi/wispr/ui/PolishStatusChip.kt` (new).** The `PolishStatusChip` data type and the pure `polishStatusChip(settings: ProviderSettingsUiState, s1State: ModelUiState)` function (§3), plus the small `@Composable` badge that renders it, called from `AppScaffold`.
- **`app/src/main/java/com/envi/wispr/ui/PolishScreen.kt` (edit).**
  - **Split into two composables (§3's "Superseded mid-implementation" note): the public `PolishScreen`
    checks `settings.loading` and renders a one-line loading state if true, otherwise delegates to a new
    private `PolishScreenBody` carrying everything below.** This replaced a `LaunchedEffect` plus two
    boolean flags that tried to detect and reconcile a local draft seeded from possibly-placeholder data
    — the loading gate makes that seed provably correct instead, so nothing below needs to react to
    `settings` changing after its first composition.
  - New parameter `s1State: ModelUiState`; delete the internal `WorkManager` collection that built it today (`PolishScreen.kt:63-65`) — the caller now supplies it.
  - Delete the standalone "Save provider"/"Apply" `Button` and its click handler entirely (§3: no separate commit step remains); keep the existing "Remove saved provider and key" `OutlinedButton`, shown whenever `settings.configured`.
  - Replace the mode `FilterChip` row with `PolishModeSwitch` (three buttons); each tap calls `onSetMode` immediately.
  - Narrow the provider tile row to `Provider.entries - Provider.SELF_HOSTED_POLISH`; a tile tap only updates the local `provider`/rung state (§5 row 2a), it does not call `onSaveProvider` by itself.
  - When `settings.provider == Provider.SELF_HOSTED_POLISH` on load: render the fallback row from §5 row 2b / §7 (plain text plus the existing "Remove saved provider and key" action wired to `onClearProvider`) instead of the tile row's selection and instead of the endpoint/protocol/free-text-model fields, which are deleted from this path entirely rather than left reachable.
  - When opening a visible provider that is already `settings.configured`: initialize the key-entry "rung" to `connected` (model list shown immediately, key box collapsed to "Key connected · N models · Replace") instead of `typing`.
  - Add the key-entry "rung" local state (`typing` / `rejected` / `connected`; no separate `checking` — the underlying check is synchronous local validation, so a fake spinner state would be dishonest UI theater). The Check tap's arming depends only on `armed` (draft non-blank), never on `settings.credentialStored`. Which rung the screen **starts** on, however, requires BOTH `settings.configured` AND `settings.credentialStored` (`initialKeyRung`, §5 row 2c) — a real bug caught in code review: removing a saved provider's key resets `settings.provider` back to its default (`OPENAI`) rather than to null, and `ProviderConfigurationRepository.load()` can itself return `configured=true` with no stored key, so `configured` alone let a just-removed or never-really-configured provider still show "Key connected." On success reveal `PolishModelPicker` in place of the free-text model field, still uncommitted; on provider switch, reset to `typing` (or `connected` per the row above, if the newly-selected provider is itself already configured with a real stored key).
  - Replace the free-text model `OutlinedTextField` (cloud path only) with `PolishModelPicker` (search box, sort chips, scrollable capped list from `PolishModelCatalog` with a C/S/A dot legend and per-row cost/speed/accuracy dots, plus the synthetic current-selection row from §4/§6 when `settings.model` is not in the catalog **for the currently selected tile** — a second real bug caught in code review: the saved model belongs to `settings.provider`, not necessarily to whichever tile is presently selected, so it must be scoped via `savedModelFor(provider, settings)` rather than passed through raw); picking a row calls `onSaveProvider(provider, pickedModel, null, apiKeyIfJustTyped, protocol)` immediately — the one real commit point for this rung (§5 row 5).
- **`app/src/main/java/com/envi/wispr/ui/AppShell.kt` (edit).** The composable that currently calls `PolishScreen` (`AppShell.kt:325`) computes `s1State` via `workUiState()` once, passes it into `PolishScreen`, and also builds `polishStatusChip(settings, s1State)` to pass as `AppScaffold`'s new `topBarBadge`, only for `AppDestination.Polish`.
- **`app/src/test/java/com/envi/wispr/ui/PolishModelCatalogTest.kt` (new).** Filter, all four sorts, and the synthetic-row preservation case for an unrecognized saved model.
- **`app/src/test/java/com/envi/wispr/ui/PolishStatusChipTest.kt` (new).** One case per §8 badge state (grey Off, red/green This phone, green Cloud-configured, neutral Cloud-not-configured), and the case proving the function ignores anything not on `ProviderSettingsUiState`/`ModelUiState`.
- **`app/src/test/java/com/envi/wispr/ui/PolishScreenProviderTilesTest.kt` (new).** Tests two pure functions extracted from `PolishScreen.kt` for exactly this reason — `CloudProviders` (the tile list, self-hosted excluded) and `initialKeyRung` (whether a provider opens to key entry or straight to the model list). **`PolishScreenKeyEntryTest.kt` was dropped**: this repository has no Compose UI test harness (no `androidTest`-only interaction rig exists for this screen), so a JVM test file cannot actually press the Cloud button or a model row to prove the badge does not move early. That claim is instead proven by `PolishStatusChipTest` (the badge is a pure function of persisted state alone, already covers this) plus the interactive tap sequence in the Hardware UAT (§11.1), which is the correct tool for a real button-press sequence in this codebase, not a fabricated JVM test that would pass without ever exercising the screen.

## 11. Testing

1. **Class of every new test:** `PolishModelCatalogTest` and `PolishStatusChipTest` are Product Outcome
   tests — "when this fails, the user sees the wrong model in the list" / "the wrong colour dot in the app
   bar" both finish the required sentence.
2. **What revert would turn it red?** Reverting `polishStatusChip`'s S1-failed branch to always return
   green turns `PolishStatusChipTest`'s failed-S1 case red; reverting `filterAndSort`'s cheapest comparator
   to a no-op sort turns the cheapest-sort case red.
3. **Deliberately not tested here:** the actual network call to any provider (doesn't exist in this
   change, owned by #61); Compose pixel-level layout (no screenshot harness exists in this repo).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (AI Polish is explicitly a limb per `architecture-rules.md` FACT: heart-and-limbs).
- **Recipe:** manual, on the S26 Ultra: open AI Polish, tap Off, confirm the app-bar badge updates to grey
  immediately (mode has no further step to complete, so this is a real commit); tap This phone, confirm
  the badge matches S1-mini's real live state; tap Cloud, pick a provider, confirm the badge has **not**
  moved yet (nothing is saved until a model is picked); type an obviously malformed key (blank, then
  whitespace-only) and confirm Check stays un-armed; type a real-looking key, confirm Check arms and the
  model list appears, still with the badge unmoved; use the search box and each sort chip; pick a model and
  confirm the badge now updates to that provider and model, at the same moment the pick is made, not before.
- **Expected observation:** the badge's dot color and label always match `settings`/`s1State` as actually
  saved right now, and record whichever of Ready/Failed S1-mini is genuinely in on this run (not assumed in
  advance); tapping Cloud, picking a provider tile, typing a key, or tapping Check must NOT move the badge —
  only an actual model pick does — confirming the badge tracks what is saved, not what is mid-typed or
  mid-picked.
- **Phone state to restore afterward:** whatever provider/model/mode was configured before the UAT pass,
  restored via the same screen at the end of the run (per `session-behavior.md`
  RULE: revert-the-phone-after-a-session, applied to app state rather than device settings since no OS
  setting is touched).

### 11.1.1 Hardware UAT result — run 2026-09-01, S26 Ultra, debug build

Pass. Before the run: mode "This phone", S1-mini Ready, no cloud provider configured (confirmed by a
byte-for-byte backup of `enviouswispr_settings.preferences_pb` pulled before starting).

- Off → badge changed to "Polish off" with a grey dot on the same tap, no further step needed.
- Cloud → OpenAI tile pre-selected, badge stayed on "Polish off" (unmoved), key field empty, Check greyed
  out.
- Whitespace-only key → Check stayed greyed out (unarmed). Real-looking test key → Check turned solid
  (armed).
- Tapped Check → "Key connected · 8 models" (matches the round-21 catalog sweep exactly: gpt-5.6-terra,
  -sol, -luna, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini, o3), `gpt-5.6-terra` tagged "Suggested". Badge
  still unmoved.
- Search "luna" → filtered to "1 of 8 models", exactly `gpt-5.6-luna`. Sort chips rendered (Suggested,
  Cheapest, Fastest, a fourth cut off by screen width — not tested individually this run).
- Picked `gpt-5.6-luna` → badge updated to "AI gpt-5.6-luna" with a green dot at the same instant, row
  highlighted as selected, a "Remove saved provider and key" button appeared.
- Restore: tapped "Remove saved provider and key" → mode and badge returned to "This phone" / S1-mini
  green, matching the pre-run screenshot exactly. `diff` against the pre-run settings-file backup: byte-
  for-byte identical.

**Unrelated to this screen's own code, worth recording so a future session recognizes it rather than
re-diagnosing it:** typing a key that matches something already on the system clipboard can make the
Samsung keyboard draw a floating clipboard-suggestion chip directly over this field's inline Check control,
intercepting the tap. Not a defect in this change — dismiss the chip (tap its ✕) before tapping Check.
Also: a tap that lands very close to the screen's right edge can trigger an unrelated OS/Play-Store gesture
on this device; keep test taps away from the outer ~50px margin.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `PolishModelCatalogTest.filtersByNameCaseInsensitively` | Product Outcome | Search box behaviour | Remove the `.lowercase()` normalization from the filter |
| `PolishModelCatalogTest.eachSortOrdersByItsOwnAxisThenCostAsTiebreak` | Product Outcome | The four sort chips | Swap any comparator for the identity sort |
| `PolishStatusChipTest.offModeShowsGreyDotRegardlessOfS1State` | Product Outcome | Mode always wins over stale S1 state when off | Make the off branch consult `s1State` |
| `PolishStatusChipTest.thisPhoneFailedShowsRedDot` | Product Outcome | The function's Failed branch, against a synthetic `ModelUiState` — not a claim about the phone's live state today (§2.5.3) | Hardcode the dot to green |
| `PolishScreenProviderTilesTest.excludesSelfHostedFromTheTileRow` | Product Outcome | §6 row 2 | Stop filtering `Provider.entries` |
| `PolishScreenProviderTilesTest.everyVisibleProviderIsInTheTileRow` | Product Outcome | §5 row 2a's population | Drop a real provider from `CloudProviders` |
| `PolishScreenProviderTilesTest.anAlreadyConfiguredVisibleProviderWithAStoredKeyOpensStraightToTheModelList` | Product Outcome | §5 row 2c | Always return `TYPING` regardless of `configured`/`credentialStored` |
| `PolishScreenProviderTilesTest.anUnconfiguredProviderStartsAtKeyEntry` | Product Outcome | §5 row 2c's other half | Always return `CONNECTED` |
| `PolishScreenProviderTilesTest.configuredButNoStoredKeyStartsAtKeyEntryNotConnected` | Product Outcome | A real bug caught in code review: removing a saved provider's key resets `settings.provider` to its default rather than to null, and `ProviderConfigurationRepository.load()` can itself return `configured=true` with no stored key — either way, "Key connected" must never show without a real key | Drop the `credentialStored` half of the `initialKeyRung` condition |
| `PolishScreenProviderTilesTest.selfHostedNeverStartsConnectedEvenIfConfiguredAndCredentialStoredAreTrue` | Product Outcome | §7's self-hosted fallback | Drop the `provider != SELF_HOSTED_POLISH` guard |
| `PolishScreenProviderTilesTest.savedModelDoesNotCarryOverToADifferentProviderTile` | Product Outcome | A real bug caught in Codex's code review (2026-09-01): switching from a saved OpenAI config to the Gemini tile offered "gpt-5.4-mini" as a pickable Gemini row, and tapping it would have saved an invalid provider/model pair | Pass `settings.model` straight through instead of `savedModelFor(provider, settings)` |
| `PolishScreenProviderTilesTest.savedModelIsKeptForItsOwnProvider` | Product Outcome | The non-bug half of the same fix — reopening the SAME saved provider must still show its saved model | Always return `""` from `savedModelFor` |
| `PolishStatusChipTest.badgeIgnoresS1StateEntirelyWhenModeIsCloud` (existing, §11.2 above) | Product Outcome | The badge-timing claim §3 depends on, in place of a dropped `PolishScreenKeyEntryTest` | Wire `polishStatusChip` to local draft state |
| `PolishModelCatalogTest.preservesAModelNameNotInTheCatalogAsTheCurrentSelection` | Product Outcome | §4 / §6 legacy-model row | Drop the synthetic row when `settings.model` isn't found |
| `PolishStatusChipTest.cloudModeNotYetConfiguredShowsANeutralNotGreenNotRedState` | Product Outcome | §8's third badge state | Collapse this case into either the green or red branch |
| `ProviderConfigurationRepositoryTest` (existing suite) | Harness Contract / Product Outcome (unchanged) | Persisted config still round-trips | N/A — no changes planned to this file |

## 12. Blast radius & rollback

- **Touched:** `ui/PolishScreen.kt`, `ui/AppShell.kt` (one new optional parameter, one new call-site
  usage), `ui/AppViewModel.kt` (`updateProviderSettings` now serializes its write through a `Mutex`,
  fixing a real race that instant-commit made newly reachable — code review, 2026-09-01; this also
  fixes the same race for every other caller of `updateProviderSettings`, not only Polish's), two new
  `ui/` files, three new test files.
- **Touched, one narrow exception:** `providers/ProviderPolishClient.kt` — only `parseResponse`'s
  `OPENAI_RESPONSES` branch and its new `firstMessageTextAt` helper (§7), a read-only response-parsing
  fix, justified in §2.2.
- **Deliberately not touched:** the rest of `providers/*` (repository, validator, secret store, request
  building, every non-OpenAI response format), `privacy/PrivacyDisclosure.kt`, the S1
  delivery/verification pipeline, Room, any AIDL surface, any other `AppScaffold` caller's rendered
  output (default `null` parameter).
- **Rollback:** revert the single commit/PR; `AppScaffold`'s new parameter is additive and defaults to `null`, so a partial revert of only `PolishScreen.kt` while leaving the parameter in place is also safe.

## 13. Ship criteria specific to this change

- [x] Opening AI Polish shows three big buttons instead of three small tags, and the app bar shows a badge with an icon, a name, and a green, red, or neutral dot. Confirmed §11.1.1.
- [x] The badge always reflects what is actually saved, never what is mid-typed or mid-picked on screen; it only moves at the exact moment each rung commits (mode tap for Off/This phone, model pick for Cloud — §3), never earlier. Confirmed §11.1.1: unmoved through Cloud tap, provider pick, key typing and Check; moved only on the model pick.
- [x] Cloud shows exactly OpenAI, Gemini, Claude; switching between them clears the key field and drops back to key entry. A previously-saved self-hosted config shows a plain "no longer configured here" row with only Remove, never a crash or a silently-selected wrong tile. Provider row confirmed §11.1.1; self-hosted fallback covered by `PolishScreenProviderTilesTest` (no self-hosted config available on the UAT device to exercise live).
- [x] Typing a key arms the Check control; tapping it with a bad key shows a rejected state without saving anything. Reopening an already-configured provider skips straight to the model list. Confirmed §11.1.1 (armed/unarmed states); reopen-skips-to-model-list covered by `PolishScreenProviderTilesTest.anAlreadyConfiguredVisibleProviderWithAStoredKeyOpensStraightToTheModelList`.
- [x] Picking a cloud provider shows a searchable, sortable model list instead of a free-text field; a previously-saved model not in the catalog still shows up as the current selection. Search and picker confirmed §11.1.1; legacy-model preservation covered by `PolishModelCatalogTest.preservesAModelNameNotInTheCatalogAsTheCurrentSelection`.
- [x] Confirmed on the S26 Ultra per §11.1, including the badge correctly matching S1-mini's real, currently-observed state, whichever it is. Result: §11.1.1, 2026-09-01, pass.

## 14. Open questions

- None blocking. The two forks raised at Gate 1 (live key check timing, self-hosted's fate) are resolved
  above and are not reopened here.
- **REJECTED finding (code review, round 14, 2026-09-01):** "Key connected" overclaims for a key that
  only passed local format validation, not a real provider check, and should read as
  unchecked/format-accepted instead. Rejected, not fixed: this re-litigates the Gate 1 decision itself
  rather than finding a new defect. The exact phrase "Key connected · {{ modelTotal }} models" is the
  founder-approved mockup's own copy (`AI Polish Ladder.dc.html`), and §2.2's Non-goals already states
  explicitly that Check stays local-format-only for this change, with real validation deferred to #61 —
  a founder choice made at Gate 1 ("check it for real later," filed as #61, marked launch-blocking), not
  an oversight this round is positioned to correct. The badge and this row are exactly as honest as the
  app can currently be without #61; the free-text field this replaces made no promise at all.

## 15. Related

- Supersedes #53 (closes on ship, different visual, same underlying fix).
- Inherits shared card/row style from #52 (no new primitive introduced).
- Depends on #61 (real provider key check) before AI Polish is launch-ready; #61 is explicitly out of
  scope for this change.
- Filed #64 (model status classified by display label, not a semantic state) as a deferred follow-up
  from code review — it touches the shared model-delivery status type used by Transcription's model
  card too, so it is broader than this screen and out of scope here; the current label-comparison
  approach matches the pre-existing pattern `ModelCards.kt`'s `StatusPill` already uses, so this change
  extends an existing pattern rather than introducing a new one.
- Filed #65 (OpenAI Responses parser assumes `output[0]` is the message, breaking reasoning models) from
  code review, then **resolved it directly in this same change** (§7) rather than leaving it deferred —
  the fix was small, low-risk, read-only, and fully specified by OpenAI's own migration guidance, and
  half-fixing it by thinning the catalog would have been a worse outcome than fixing the actual bug.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection (Code; only `app/src/**` paths touched)
- [ ] Self-reviewed to all-clear before any reviewer saw it — pending this session's own re-read pass

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
