# Issue #81 — AI Polish: the founder's four-rung Ladder — 2026-09-02

**Status:** BUILDING (Gate 2 passed 2026-09-02 on the founder's standing run-end-to-end instruction and his explicit ask for this layout). Tier: MEDIUM (one screen replaced, one runtime behaviour added: the key is saved with
the suggested model the moment the provider accepts it).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/ui/**`, `app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`, `app/src/test/**`, `app/src/androidTest/**`)

**PAR rows closed:** `none`. The rows this touches (cloud polish setup, model choice) are already claimed by
#67, #61 and #84; this changes their presentation, not their outcome.

**Hardware UAT:** Y. A person on the S26 opens AI Polish, taps Cloud, taps OpenAI, pastes a real key, taps
Check, and within a few seconds the rung collapses to "3 · Key connected", the suggested model is running
(green light in the app bar), and rung 4 lists the models that key can reach. They tap another model and
the app bar names it. They tap This phone and the S1-mini card shows its state. Nothing in the Ladder
touches capture, ASR or insertion, so the dictation itself is not part of this UAT; the emulator covers
every screen state and the phone covers the real-key path only, once the vault-key permission rule exists
(owed from #84, same block).

## Preface — User Rubric

1. **Who is this user in this moment?** Frank Chen, 72, opens AI Polish to move polish from his phone to
   OpenAI because a friend told him it writes better emails. He has a key on his clipboard.
2. **Why would they want this?** "Show me the steps in order, and tell me when each one is done."
3. **How would they invoke it?** Voluntary, from the AI Polish tab; once at setup, then rarely.
4. **What app are they in?** EnviousWispr's own settings; the result shows up later in Gmail.
5. **What is their natural input?** Taps: one of three buttons, one of three tiles, one paste, one Check,
   later one model row. No Save button to find.
6. **What does success feel like?** The moment Check comes back green the page says the key is connected
   and the app bar shows a model with a green light. He never wondered whether he still had to save.
7. **What does wrong-not-broken look like?** A key that is typed and checked but then lost on rotation
   because a key draft is never written to a saved state. The field simply comes back empty and he pastes
   again; nothing claims to be connected that is not.
8. **What would a power user hack around this to get?** Priya Ramachandran wants a model the list does not
   show; the typed-id row from #84 stays, and a saved model the live list lacks stays pinned.
9. **What level of control would they want?** Off, this phone, or cloud with a chosen provider and model.
   Replace or remove the key from the same rung.

### Cross-persona check

Dr. Elena Vasquez reads the disclosure at the foot of rung 4 before she uses a cloud model; it is the
provider's own disclosure line, unchanged. Meera Patel and Frank Chen tap Off and see one sentence. Aaron
Wu gets no modal: the sheet and the setup page are gone, everything is on one page. Diana Foster never
leaves the tab. Marcus Weber is unaffected. The tension is between Elena wanting the privacy consequence
next to each rung-1 button and the design's three plain words; the design wins on rung 1 and the
consequence stays on the S1 card ("nothing is sent anywhere") and rung 4's footer.

---

## 0. TL;DR

Rebuild the AI Polish tab as the founder's Ladder design (Claude Design project
`b3b09861-9868-4684-b20f-6038873bf680`, file `AI Polish Ladder.dc.html`, read 2026-09-02): four numbered
rungs on one page, no master switch, no setup page, no picker sheet. The persisted model from #67 stays
exactly as it is; the tab renders it. The one new behaviour: an accepted key check saves the key with the
suggested model at once, so "Key connected" is always a persisted fact and never a draft. The master
switch's memory (`turnOn`, `last_on_mode` (removed)) loses its only reader and is removed.

## 1. Problem

The tab shipped in #67 is the mockup-document layout (a master switch and two engine cards with a setup
page behind them). The founder's own design is the Ladder, filed as #81 on 2026-09-01 with "I'll want this
version at some point", and on 2026-09-02 he said the screen still does not match what he asked for. The
first Ladder build (#62, PR #66, commit `77588e3`) had the defects #67 fixed: an S1 card in every mode,
uneven buttons, inline setup, and a local mirror of persisted state that took 23 review rounds. This plan
brings the Ladder back on top of #67's persisted-only state, #61's live key check and #84's live model list.

## 2. Goals & non-goals

### 2.1 Goals

- The tab is the Ladder: rung 1 three equal buttons; Off shows one sentence; This phone shows the S1-mini
  card; Cloud shows rungs 2 to 4.
- Rung 3 is one field with the Check pill inside it, and once the key is stored it collapses to one line
  with Replace and Remove.
- Rung 4 is the live list from #84 with search, four sort chips, the group label, the C/S/A legend and a
  capped scroll region; tapping a row saves that model.
- The tab holds no copy of any persisted fact. Its local state is navigation only: which rung-1 button
  was tapped when nothing is configured, which tile is being looked at, whether Replace is open, and the
  key draft, which is never saveable.
- Old code goes in the same change: the setup page, the picker sheet, the local-model page, the polish
  subpage type, the engine-card states, the master switch and its remembered engine.

### 2.2 Non-goals

- Brand marks: the design shows OpenAI, Gemini and Claude logos. No icon dependency exists here
  (`PolishStatusChip.kt` monogram comment); the tiles keep the initial monogram. Filed as a note in §14.
- Fonts, exact hex colours: the app uses its Material theme; the design's palette is not ported.
- Any change to the key check, the discovery, the cache, the polish client, or the session policy.
- The self-hosted provider as a fresh choice (catalog decision 2026-09-01). A configured self-hosted
  setup keeps running and keeps its Remove.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

- **Persisted polish facts.** Owner `providers/ProviderConfigurationRepository.kt`: `setMode` (`:147`),
  `saveProvider` (`:90`, writes `KEY_MODE = PROVIDER` and `KEY_LAST_ON_MODE` at `:128-129` after the #61
  check at `:112-123`), `clearSelection` (`:155`, resets mode to `OFFLINE_S1`), `turnOn` (`:52`, the master
  switch's memory). Read by `loadPolicy` (`:87`) in the session owner and by `refreshProviderSettings`
  (`ui/AppViewModel.kt:608`) into `ProviderSettingsUiState` (`:54-69`). Found with
  `grep -n "fun \|last_on_mode\|PolishMode\." app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`.
- **Writes from the UI.** `AppViewModel.setPolishMode` (`:415`, refuses `PROVIDER` when nothing is
  configured), `turnPolishOn` (`:424`), `saveProviderSettings` (`:430`, sets the cache action from
  `ProviderDiscoveryApplyPolicy.afterSave` and awaits `afterWrite` before publishing), `clearProviderSettings`
  (`:476`). Each returns the request sequence; `writeSequence`/`writeOrigin` (removed)/`error`/`message` are the
  completion signal (`ProviderSettingsUiState`).
- **Live model list.** `discoverModels` (`:527`), `loadCachedModels` (`:495`), `keyDraftChanged` (`:516`),
  state `ProviderDiscoveryUiState` (`:75-86`); consumed today only by `ProviderSetupPage` (removed).
- **The tab.** `ui/AppShell.kt:376-404` builds `PolishScreen` and, behind `polishPageName` (removed)
  (`:253`), `ProviderSetupPage` and `LocalModelPage` (removed); the badge at `:331-332` reads `polishStatusChip`.
  `SettingsActivity.kt` passes `turnPolishOn` (one reference, sweep in §2.5.2).
- **The S1 model state.** `polishS1State` (`AppShell.kt:289-296`) from `workUiState` (`ModelCards.kt`),
  rendered by `ModelCard` (`ModelCards.kt:37`) with `ModelDeliveryWorker` actions in `LocalModelPage`.

### 2. Find the existing authority before proposing one

Sweep of every consumer of what this plan replaces (`/usr/bin/grep -rn ... app/src --include='*.kt'`, run
2026-09-02, counts are references per file):

```
  33 app/src/test/java/com/envi/wispr/ui/PolishCardStateTest.kt
  11 app/src/main/java/com/envi/wispr/ui/AppShell.kt
   8 app/src/test/java/com/envi/wispr/ui/PolishSubpageTest.kt
   8 app/src/main/java/com/envi/wispr/ui/AppViewModel.kt
   6 app/src/test/java/com/envi/wispr/ui/PolishScreenProviderTilesTest.kt
```

and of the master switch's memory (`turnOn|turnPolishOn|LastOnMode|last_on_mode|polishModeWhenTurnedOn` (removed)):

```
  25 app/src/androidTest/java/com/envi/wispr/providers/ProviderConfigurationRepositoryTest.kt
   6 app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt
   2 app/src/main/java/com/envi/wispr/ui/AppShell.kt
   2 app/src/main/java/com/envi/wispr/ui/AppViewModel.kt
   2 app/src/main/java/com/envi/wispr/ui/PolishScreen.kt
   1 app/src/main/java/com/envi/wispr/ui/SettingsActivity.kt
   5 app/src/test/java/com/envi/wispr/providers/PolishModeWhenTurnedOnTest.kt
```

Existing authorities reused, not rebuilt: `ModelListPresentation.present` and `countLine` (rows, pinned
saved row, typed-id row), `ModelSort` (chips and group labels), `ScoreDots`, `relativeAge`, `keyCheckLine`
and `discoveryLine` (`ui/KeyCheckCopy.kt`), `polishStatusChip`, `ModelCard`, `PolishSnackbarPolicy`,
`savedModelFor`, `CloudProviders`. New authority proposed: `ui/PolishLadder.kt` (proposed), pure rules for
the ladder's derived state (§3).

### 3. Read prior attempts and live direction

- #62 / PR #66 (2026-09-01, `77588e3`): the first Ladder. Session log: six-plus rounds each found a new
  timing gap in a local mirror of `mode`/`provider`/`rung` (removed); a saveable `rung` claimed "Key connected" with
  no key after rotation (round 20); the reactivation check joined the saved key against the saved provider
  instead of the displayed one (round 21). Binding lessons: derive, never mirror; a value that depends on
  the key draft must not outlive it; every join is against the DISPLAYED provider.
- #67 (`2efb005`): gated the body on `settings.loading`, moved every draft off the tab, and made the tab
  render persisted state only. Binding: the loading gate and the persisted-only tab stay.
- #61 (merge f9fc796): a write happens only after the provider accepted the key; the repository re-checks on
  every save. Binding: Check and Save are the same verdict; the UI never stores an unaccepted key.
- #84 (`8af5642`): the live list, per-provider sequences, cache only for the stored key, draft results
  promoted on a matching Save. Binding: the discovery sequence handed to Save is the one the Check ran.
- Catalog decision 2026-09-01: self-hosted is not offered fresh. Binding.
- The founder's design (read 2026-09-02) is the target: its script sets a default model at key acceptance
  and shows that model green in the app bar, which is what §3's save-at-accept implements.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **Rotation and recreation.** The key draft is plain `remember` (never a Bundle). Everything that reads
  "connected" reads persisted facts, so a recreation can only show the field again, never a false
  connected row. `checkSequence`, the browsed tile, the Cloud setup flag and the Replace flag are
  `rememberSaveable`; none of them can assert a fact the draft alone supported.
- **Process death mid-write.** The tab has no wait of its own: a completed write publishes
  `writeSequence`; a restored `target` that the new view model can never reach is dropped by the same
  rule `ProviderSetupSavePolicy` used (§3, `PolishWritePolicy` (proposed)).
- **A late discovery completion.** Already owned by the view model (`appliesNow()` after every
  suspension); the tab compares `discovery.provider` and `discovery.sequence` against what it displays.
- **Save-at-accept re-runs the check.** `saveProvider` checks again before writing (#61), so an accepted
  Check followed by a refused Save is possible (the key was revoked between the two calls). The tab shows
  the save error under the field and the pill reads Retry.
- **Session owner.** Unchanged: it latches `loadPolicy()` per dictation; a save flips `mode` to `PROVIDER`
  exactly as the setup page's Save did.

### 5. Prove the high-risk premises

- `saveProvider` sets the mode to PROVIDER itself: `ProviderConfigurationRepository.kt:128`. Pasted in §2.5.1.
- `setPolishMode(PROVIDER)` refuses an unconfigured provider: `AppViewModel.kt:416-418`. So the Cloud
  button must not call it when nothing is configured; it opens setup locally instead (§3).
- `clearSelection` resets mode to OFFLINE_S1: `:160-163`. So Remove lands the user on This phone, and the
  Ladder highlights This phone because that is now the persisted mode.
- Discovery with a blank draft uses the stored key: `discoverModels` (`AppViewModel.kt:527-556`,
  `usingDraft`). So Refresh in rung 4 is `onCheckKey(provider, null)`.
- `ModelCard` renders Repair/Remove/Download from `ModelUiAction` (`ModelCards.kt:80-90`), which is the
  design's S1 card.
- The master switch's memory has no reader outside the switch: the sweep above lists every reference; the
  session owner reads `loadPolicy`, which decodes `KEY_MODE` only (`:184-195`).

## 3. Design

### The tab (`ui/PolishScreen.kt`, rewritten)

Loading gate unchanged. Body, top to bottom, inside `ScreenContainer`:

**Rung 1, "1 · WHERE POLISH RUNS".** Three equal `Modifier.weight(1f)` buttons with fixed height so
"This phone" cannot make the middle one taller. Highlight is `PolishLadder.rungOne(settings.mode,
cloudSetup)` (proposed):

| Persisted mode | `cloudSetup` (proposed) | Highlighted |
|---|---|---|
| OFF | false | Off |
| OFFLINE_S1 | false | This phone |
| PROVIDER | any | Cloud |
| OFF or OFFLINE_S1 | true | Cloud |

Taps: Off → `onSetMode(OFF)`, `cloudSetup = false`. This phone → `onSetMode(OFFLINE_S1)`,
`cloudSetup = false` (no readiness gate: the card below says the truth and the badge goes red, which is the
design's "S1 failed" state). Cloud → `PolishLadder.cloudTap(settings)` (proposed): `ACTIVATE` (proposed) when
`configured && (provider == SELF_HOSTED_POLISH || credentialStored)`, which calls `onSetMode(PROVIDER)`;
otherwise `SETUP` (proposed), which sets `cloudSetup = true` and writes nothing, so S1-mini keeps polishing while the
user sets up (the design's chip shows S1-mini green during key entry).

**Off** shows the design's one sentence: "No language model runs. Deterministic cleanup still removes
obvious filler and spacing issues."

**This phone** shows `ModelCard` with eyebrow "ON THIS PHONE", title `S1Config.MODEL_NAME`, description
from `PolishLadder.s1Line(s1State)` (proposed): READY → "Polishes your words on this phone. Nothing is
sent anywhere."; BROKEN → "S1-mini is not working right now. Your words come back with basic cleanup
only, and nothing is sent anywhere."; NOT_READY with DOWNLOAD/RETRY → "Download S1-mini to polish on this
phone."; other NOT_READY and UNKNOWN → "Getting S1-mini ready."; facts "Offline" and "Stays on this phone";
actions exactly as `LocalModelPage` wires them today (moved, not rewritten).

**Cloud** shows rungs 2 to 4.

**Rung 2, "2 · PROVIDER".** Three tiles from `CloudProviders`, highlighted when equal to
`PolishLadder.displayedProvider(browsed, settings)` (proposed): `browsed ?: settings.provider.takeIf {
settings.configured && it in CloudProviders }`. A tap sets `browsed` and clears the key draft, the check
sequence and the Replace flag (all keyed on the displayed provider with `remember(displayed)`). When a
self-hosted setup is configured, a card under the tiles reads "Self-hosted · <host>" with its status
line and a Remove button (`onClearProvider`); rungs 3 and 4 show only for a displayed cloud provider.
With nothing configured and nothing tapped, rung 2 shows three plain tiles and the page ends there.

**Rung 3, "3 · YOUR <PROVIDER> KEY".** `PolishLadder.keyRung(displayed, settings, replacing)` (proposed) is
`CONNECTED` (proposed) iff `configured && settings.provider == displayed && credentialStored && !replacing`, else
`FIELD` (proposed).

- `FIELD`: one `OutlinedTextField` (password transformation, draft in plain `remember`) with the pill as
  its trailing control. `PolishLadder.keyPill(draftBlank, checking, failed, saving)` (proposed): label
  "Check" and enabled iff draft non-blank; "Checking" and disabled while the discovery for this provider
  and sequence is CHECKING or a save is in flight; "Retry" and enabled after a FAILED discovery or a failed
  save. Hint under the field: default "Encrypted in the Android Keystore. Never written to logs."; checking
  "Asking <Provider> which models this key can reach."; failed: `discovery.line` or the save error, in the
  error colour. When `replacing`, a "Keep current key" text link closes the field.
- Check tap: `checkSequence = onCheckKey(displayed, draft)`.
- **Save at accept.** When `discovery.provider == displayed && discovery.phase == LISTED &&
  discovery.sequence == checkSequence && draft non-blank && checkSequence != savedForSequence`, the tab
  calls `onSave(displayed, PolishLadder.defaultModel(models), draft, checkSequence)` (proposed) once and
  records `savedForSequence = checkSequence` (`rememberSaveable`). `defaultModel` (proposed) reads the DISCOVERED
  models, never presentation rows (a pinned saved row or a typed row is synthetic and may name a model the
  new key cannot reach): the first recommended AVAILABLE model, else the first AVAILABLE model, else the
  first UNVERIFIED model, never an UNAVAILABLE one; null otherwise. **The automatic save also requires no
  pending write:** a Check can finish while a mode, model or remove write is in flight, and the effect
  waits for that target to complete before it saves, so two writes never share one completion signal.
  **The draft is a precondition everywhere:** Check, Retry, the automatic save and the typed-model save all
  require the plain-remember key draft to be non-blank, so after a rotation loses the draft none of them
  can fall back to the stored key (a blank key means "keep the stored key" to the repository). **Typed-model fallback:** when the listing
  arrived (LISTED for this draft's sequence) and `defaultModel` is null, nothing is saved and rung 3 grows a
  second field, "Model id", with a "Use this model" button; that button saves the key and the typed id
  together (`onSave(displayed, typedId, draft, checkSequence)`), so a power user with a key that lists no
  chat model is not stranded and the key is still never stored without a model. The typed id is validated
  the way the repository validates a model (non-blank, at most `MAX_MODEL_CHARS`, no control characters). The save is the same `saveProviderSettings` the setup page used, with
  the same discovery sequence, so #84's promotion of the draft list into the cache is unchanged. The
  repository re-checks the key (#61) and flips the mode to PROVIDER.
- `CONNECTED`: one row with a check mark, "3 · Key connected" plus " · N models" only when the discovery
  for this provider is LISTED with `usedStoredKey` true (a failed replacement's draft listing, kept after
  "Keep current key", never lends its count to the stored key), the line "Encrypted in the Android
  Keystore", and two text links: Replace (`replacing = true`) and Remove (`onClearProvider`). A KEY write closes
  Replace and drops the draft only when `PolishWritePolicy` returns DONE; FAILED keeps Replace open,
  preserves the draft and shows Retry.

**Rung 4, "4 · MODEL".** Shown iff rung 3 is `CONNECTED`. Whenever the displayed provider changes, and
whatever rung 3 shows, the tab calls `onLoadCachedModels(displayed)` at once, so the displayed provider is
the view model's active one from that moment and a late completion for another tile cannot touch it.
Rung 4 shows a listing only when `discovery.usedStoredKey` is true (a cache read, a stored-key Refresh, or a
draft listing promoted by the save that stored its key); a draft's listing never shows under a connected
row, which is the visibility rule `ProviderSetupPage` carried, moved intact. Search field, the four `ModelSort` chips, the group label from
`sort.groupLabel` on the left and the C/S/A legend on the right, then the rows from
`ModelListPresentation.present(displayed, models, query, sort, settings.model)` inside a
`Column` with `verticalScroll` and `heightIn(max = 270.dp)` (about four and a half rows, the design's cap)
which is legal inside the outer `LazyColumn` because the height is bounded. A row is selected iff
`row.id == settings.model` (persisted). A row tap calls `onSave(displayed, row.id, null, null)` (model-only
save; the stored key stays) and rows are disabled while a save is in flight. Under the list: the count line
with the cache age on the left and a Refresh link (`onCheckKey(displayed, null)`) on the right, then the
footer "C cost · S speed · A accuracy. " + `provider.disclosure().summary`. A stored-key Refresh that
FAILS keeps the previous stored-key listing (the view model already keeps it) and shows `discovery.line`
under Refresh.

**Errors and one write at a time.** Every write now originates on the tab, so `ProviderWriteOrigin`
(removed) goes. The tab holds ONE pending write: its target sequence and its rung kind (mode, key, model,
remove), both `rememberSaveable` so a rotation mid-write keeps waiting on the right sequence. Every
mutating control (the three rung-1 buttons, the tiles, the field and its pill, Replace, Remove, the model
rows, Refresh) is disabled while a target is pending, so a later write can never replace `writeSequence`
and `error` under an earlier target. `PolishWritePolicy.outcome(target, completed, error)` is
`ProviderSetupSavePolicy` (removed) renamed and generalised: the same WAITING/DONE/FAILED shape minus the
origin clause. It does not infer process death from sequence numbers: the target is declared above the
loading gate and the loading branch clears it, exactly as the setup page did, so a fresh view model after
process death never leaves the tab waiting on a sequence it cannot reach, while a rotation (loading stays
false) keeps the target. On FAILED the error is shown under the rung that started the write.

**Snackbar.** `PolishSnackbarPolicy` unchanged; the `polishPage == null` clause goes with the pages.

### The view model (`ui/AppViewModel.kt`)

`turnPolishOn` (removed). `ProviderWriteOrigin` (removed) and the `origin` parameter of
`updateProviderSettings`, `setPolishMode`, `saveProviderSettings` and `clearProviderSettings`. One
addition: **a save that supplies a key clears that provider's persisted model cache BEFORE the repository
write** (a `beforeWrite` (proposed) step run inside the write mutex and inside the captured outcome, before
`operation`: if it throws, the repository write does not run and the same sequence publishes FAILED, so the
tab is never left waiting). The commit and the
cache promotion are two steps, and a process death between them would otherwise restart with the old
key's cache labelled as the stored key's. Losing a valid old cache on a failed save is safe; showing an
old key's models under a newly stored key is not. Discovery, sequences and `afterWrite` are untouched.

### The repository (`providers/ProviderConfigurationRepository.kt`)

`turnOn` (removed), `loadLastOnMode` (removed), `decodeLastOnMode` (removed), `polishModeWhenTurnedOn` (removed)
(removed), `KEY_LAST_ON_MODE` (removed) and its three writes. And one repair the coverage round found
(`saveProvider`, `:126-135`): a supplied key is put in the Keystore BEFORE the preferences commit, so a
commit failure on Replace leaves the new key in place of the old one. The save now takes a snapshot of the
previous credential first, and that snapshot is mandatory: if reading it fails the save aborts before
anything is written. When the commit fails, the previous key is put back, or the new one removed when none
existed. When that compensation itself fails, the save throws a typed
`InconsistentProviderStorageException` (proposed), which `updateProviderSettings` maps to "Could not
restore your saved key. Remove the provider and set it up again." (every other exception keeps the generic
sentence), rather than claiming nothing changed. Failures of the secret store's own reads are no longer collapsed into "no key" on this
path. An existing `last_on_mode` value in a user's
preferences is simply never read again; no migration, no cleanup pass, because the preference file is
app-private dev state at stage 1 and an unread key costs nothing.

### Navigation (`ui/AppShell.kt`)

`polishPageName`, `Screen.Polish`, the polish `PageChrome`, `ProviderSetupPage` and `LocalModelPage` routes
go. `PolishScreen` receives `providerDiscovery` and the three discovery callbacks the setup page had.
`SettingsActivity.kt` drops the `turnPolishOn` argument.

### Deleted files

`ui/ProviderSetupPage.kt` (its `ProviderTile`, `ScoreDots`, `userMessage`, `relativeAge` move to
`ui/PolishLadder.kt` (proposed) unchanged), `ui/PolishSubpage.kt`, `ui/PolishCardState.kt` except
`PolishSnackbarPolicy` and `hostOf` (moved to `ui/PolishLadder.kt`), and the tests named in §11.

### Alternatives rejected

- **Show rung 4 from a draft list and save on the first model tap.** Rejected: "Key connected" would then
  describe a key that exists only in a plain `remember`, the exact shape #62 round 20 broke on rotation.
  Saving at acceptance makes the connected row a persisted fact by construction.
- **Persist mode = PROVIDER on the Cloud tap before anything is configured.** Rejected: `setPolishMode`
  refuses it, and the session would attempt cloud polish with no key while the user is still typing one.
  The design keeps S1-mini running during setup.
- **Keep `turnOn` and `last_on_mode` for a future switch.** Rejected under `GR-MIGRATION-COMPLETE`; the
  design has no switch and Cloud activates the configured provider directly.
- **Gate This phone on the model being ready, as #67 did.** Rejected: the design's "S1 failed" state is
  This phone selected with a red pill and a Repair button; a limb that is not ready falls back to cleanup
  (heart-and-limbs), so the tap is safe.

## 3b. Ownership justification

The Ladder's derived rules live in `ui/PolishLadder.kt` (proposed), a pure file beside
`ModelListPresentation.kt`, so `PolishScreen.kt` holds layout and the rules are unit-tested without a rig.
Persistence stays in the repository; sequencing stays in the view model. No new coordinator, no new
shared object.

## 4. Contract deltas

- `PolishScreen` signature: gains `discovery`, `onSave(Provider, String, String?, Int?) -> Int`,
  `onCheckKey`, `onKeyDraftChanged`, `onLoadCachedModels`, `onRefreshReadiness`; loses `onTurnOn` (removed),
  `onOpenProviderSetup` (removed), `onOpenLocalModel` (removed).
- `EnviousWisprViewModel`: `turnPolishOn` removed; `origin` parameters removed; `ProviderSettingsUiState.writeOrigin` removed.
- `ProviderConfigurationRepository`: `turnOn`, `loadLastOnMode` removed. `loadPolicy`, `saveProvider`,
  `setMode`, `clearSelection` unchanged in behaviour.
- No AIDL, process, manifest or schema change.

## 5. End-to-end state and lifecycle audit

| Population | Members | Handling |
|---|---|---|
| Persisted mode × configured × credentialStored | OFF, OFFLINE_S1, PROVIDER × {unconfigured, cloud without key, cloud with key, self-hosted} | Rung 1 highlight from mode; Cloud tap ACTIVATE only for "cloud with key" and "self-hosted"; PROVIDER without key shows the field under the highlighted Cloud (badge red, as today) |
| Displayed provider | none, a cloud tile, the saved cloud provider, a browsed tile while another is saved | Rung 3 CONNECTED only when displayed equals the saved provider with a key; a browsed tile always starts at the field |
| Key draft lifecycle | typing, checking, listed, failed, saved, rotated, process death | Draft never saveable; save fires once per check sequence; rotation shows the field again with nothing claimed |
| Discovery completion | for the displayed provider and current sequence; for another provider; stale sequence | Only the first drives the pill, the save and rung 4 |
| Write completion | mode, key save, model save, remove; success or failure; restored target after death | `PolishWritePolicy`; error shown under the rung that started it; an unreachable target is dropped |
| S1 model state | READY, BROKEN, NOT_READY (download, retry, queued, downloading, verifying, paused, cancelled), UNKNOWN | `s1Line` exhaustive over `ModelHealth`, actions from `ModelUiAction` exhaustive as in `LocalModelPage` |

## 6. Downstream consumer matrix

| Consumer | Effect |
|---|---|
| Session owner (`loadPolicy`) | None; mode and selection are written by the same repository calls |
| App-bar badge (`polishStatusChip`) | None; it reads persisted state and already covers "Cloud, not set up" |
| Snackbar | Same messages ("<Provider> saved", "Provider removed"), no page clause |
| Discovery cache (#84) | Same `afterSave` promotion; the sequence handed to Save is the Check's |
| Onboarding | None |
| `SettingsActivity` | Drops one lambda |
| Instrumented repository test | Loses its `turnOn` cases |

## 7. Failure-mode × caller table

| Failure | Where | What the user sees |
|---|---|---|
| Check refused (401, denied, unverified) | rung 3 | Pill "Retry", the `discoveryLine` in red, nothing saved, S1 still polishing |
| Check listed no models | rung 3 | "No models this key can use for polish.", no save |
| Save refused after an accepted Check | rung 3 | `keyCheckLine` in red under the field, pill "Retry" |
| Storage failure on save | the rung that started it | `settings.error` in red; the previous key is restored by the repository, so nothing else changes |
| Key snapshot unreadable before a save | rung 3 | The save aborts before any write; error in red |
| Restore fails after a failed commit | rung 3 | "Could not restore your saved key. Remove the provider and set it up again." |
| Stored-key Refresh fails | rung 4 | The previous listing stays; `discovery.line` under Refresh |
| Model save fails | rung 4 | Error under the list; the previous model stays selected |
| Remove fails | rung 3 | Error under the connected row |
| Mode change fails | rung 1 | Error under the buttons; highlight follows the persisted mode |

Copy follows `content-brand.md`: plain, no dashes, names the control.

## 8. Caller-visible signals audit

`writeSequence`, `error`, `message` unchanged. `writeOrigin` removed; its only readers were the two
surfaces that are now one.

## 9. Fallback source-of-truth audit

Every "connected", "selected" and "running" the tab shows is read from `ProviderSettingsUiState`, which is
read from the repository after each write. The only local state is navigation and the key draft. A save the
tab fires at acceptance is idempotent per check sequence.

## 10. File-by-file changes

| File | Change |
|---|---|
| `ui/PolishScreen.kt` | Rewritten as the Ladder |
| `ui/PolishLadder.kt` (proposed) | New: `rungOne` (proposed), `cloudTap`, `displayedProvider`, `keyRung`, `keyPill`, `defaultModel`, `s1Line`, `PolishWritePolicy`; `ProviderTile`, `ScoreDots`, `userMessage`, `relativeAge`, `hostOf`, `PolishSnackbarPolicy` moved in |
| `ui/ProviderSetupPage.kt` | Deleted |
| `ui/PolishSubpage.kt` | Deleted |
| `ui/PolishCardState.kt` | Deleted |
| `ui/AppShell.kt` | Pages removed; `PolishScreen` wiring |
| `ui/AppViewModel.kt` | `turnPolishOn`, `ProviderWriteOrigin`, origin parameters removed |
| `ui/SettingsActivity.kt` | One lambda removed |
| `providers/ProviderConfigurationRepository.kt` | `turnOn`, last-on-mode removed |
| `test/ui/PolishLadderTest.kt` (proposed) | New |
| `test/ui/PolishCardStateTest.kt` (removed) | Deleted; its snackbar cases move to `PolishLadderTest` (proposed) |
| `test/ui/PolishSubpageTest.kt` (removed) | Deleted |
| `test/providers/PolishModeWhenTurnedOnTest.kt` (removed) | Deleted |
| `androidTest/providers/ProviderConfigurationRepositoryTest.kt` | `turnOn` cases removed |

## 11. Testing

**Should each test exist?** Product Outcome: a wrong highlight, a false "connected", a save that fires
twice or never, a model default that picks an unavailable row, an S1 line that lies. **Honest?** Every
expectation is a literal; the pure rules take the same inputs the screen passes. **How written?** JVM unit
tests over `PolishLadder` (proposed); the screen itself is checked on the emulator by reading the page source.

| Test | Class | Protects |
|---|---|---|
| `PolishLadderTest.rungOneFollowsThePersistedModeUnlessSetupIsOpen` (proposed) | Product Outcome | The highlight table in §3 |
| `PolishLadderTest.cloudActivatesOnlyAConfiguredProviderWithAKey` (proposed) | Product Outcome | No cloud attempt without a key; self-hosted activates |
| `PolishLadderTest.aBrowsedTileNeverInheritsAnotherProvidersKey` (proposed) | Product Outcome | #62 round 21's join gap |
| `PolishLadderTest.connectedNeedsTheSavedProviderWithItsKeyAndNoReplace` (proposed) | Product Outcome | #62 round 20 |
| `PolishLadderTest.theCheckPillReadsCheckCheckingRetry` (proposed) | Product Outcome | Pill states |
| `PolishLadderTest.theDefaultModelPrefersRecommendedThenAvailableThenAnySelectable` (proposed) | Product Outcome | Save-at-accept picks a DISCOVERED model the key can use, never a pinned or typed row; null when there is none |
| `PolishLadderTest.aKeyWithNoUsableModelWaitsForATypedId` (proposed) | Product Outcome | The typed-model fallback rule |
| `PolishLadderTest.saveAtAcceptFiresOncePerCheck` (proposed) | Product Outcome | The decision function, not the effect |
| `PolishLadderTest.theS1LineIsExhaustiveOverHealth` (proposed) | Product Outcome | Card copy |
| `PolishLadderTest.writeOutcomeWaitsCompletesOrFails` (proposed) | Product Outcome | WAITING while `completed < target`, DONE, FAILED; the loading-gate reset is control flow, checked on the emulator by killing the process mid-write |
| `PolishLadderTest.saveAtAcceptWaitsForAPendingWrite` (proposed) | Product Outcome | No automatic key save while a mode, model or remove write is in flight |
| `ProviderConfigurationRepositoryTest` (androidTest, four added cases) | Product Outcome | Snapshot-read failure aborts before any put; commit failure restores the previous key; commit failure with no previous key removes the new one; a failed restore reports inconsistent storage |
| `ProviderDiscoveryApplyPolicyTest.aSuppliedKeyClearsTheCacheBeforeTheWrite` (proposed) | Product Outcome | The pre-write clear rule |
| `PolishLadderTest.snackbar...` (moved) | Product Outcome | Unchanged behaviour |
| Deleted: `PolishCardStateTest` (removed) (8), `PolishSubpageTest` (removed) (3), `PolishModeWhenTurnedOnTest` (removed) (5) | | Their subjects no longer exist; the replacements above cover the outcomes that remain |

**Seam repair found during the build:** `ProviderPolishClientTest.theDeadlineCancelsQueuedAndActiveProbes`
(#84) went red twice in full-suite runs on 2026-09-02 (`at return 9, later 9`) and green alone. It slept 600
ms per probe against an 800 ms deadline, so under load, when the list fetch ate the budget, every probe timed
out instantly and all nine reached the fake server before the return. The fake server now HOLDS each probe on
a latch the test releases after counting, so the in-flight probes cannot finish early whatever the load. The
latched run then showed the client's real bound (`at return 4, later 6`): a probe thread freed in the same
instant the deadline fires can dequeue its next probe before the cancel reaches the queue, and an interrupt
cannot pull back a request already connecting, so up to the executor's width (three) can still land after
the return. The assertion now states that bound (at most three stragglers, never the whole queue) instead
of "none", which the client never promised (`testing-philosophy.md` RULE:
a-flaky-suite-earns-no-new-cases-until-its-wait-seam-is-fixed).

Receipts: each new rule's test goes red with the rule's branch inverted (a checkpoint commit precedes any
receipt, per `workflow-process.md`).

### 11.1 Hardware UAT spec

Emulator (`emulator-5554`, Appium page source, no dictation): Off sentence; This phone card with the
real S1 state; Cloud with nothing configured shows three tiles and stops; a tile shows the field; a fake
key's Check shows Retry with the provider's rejection line and the badge still says S1-mini; rotation
during typing shows the field again with nothing connected; Remove lands on This phone. Phone (owed with
#84's real-key run): a real key's Check collapses rung 3, the badge shows the suggested model green, rung 4
lists the models, a second model tap changes the badge, Replace and Remove work.

### 11.2 Other obligations

`unit-tests.xml`, `codex-review.md`; `hardware-uat.json` for the emulator pass, with the phone's real-key
part recorded as owed in the skip note if the permission rule is still absent. The instrumented repository
test class runs on the emulator with `am instrument`.

## 12. Blast radius & rollback

One tab, its view-model entry points, and one repository method family with no reader. The session policy
and every polish engine are untouched. Rollback is a revert of one merge.

## 13. Ship criteria specific to THIS change

- Every screen state in §11.1 seen on the emulator from the page source.
- No `rememberSaveable` holds the key draft or anything derived from it.
- The consumer sweeps in §2.5.2 return zero hits for the removed names.
- Unit count reported; the instrumented repository class green on the emulator.

## 14. Open questions

- Brand marks on the tiles and the badge: the design's logos need an icon asset set; monograms until then.
- The design's "Suggested" tag is `ModelListRules.isRecommended` here (mini, nano, flash, haiku); the two
  names mean the same thing and the tag text stays "Recommended" from #84.

## 15. Related

#81 (this), #62 (the first Ladder, superseded by #67 and now closed by this), #67, #61, #84, #66 (PR).

## Review log

**Coverage round (Codex session `01a06264-64c0-7342-9284-13e18975bf38`, 2026-09-02): PROCEED-WITH-REVISIONS, five
drop-ins, all adopted.** (1) Load the cache the moment the displayed provider changes and show a listing
under a connected row only when it describes the stored key: adopted verbatim (§3 rung 4). (2) One pending
write at a time, every mutating control disabled while it is pending: adopted verbatim (§3 errors). (3)
The write policy must not infer process death from sequences; clear a restored target under the loading
gate: adopted verbatim (§3 errors), which is how the setup page already did it. (4) The default model must
come from discovered models, never from pinned or typed rows, and a key with no usable model needs the
typed-id path before anything is stored: adopted (§3 save at accept, typed-model fallback). (5) A supplied
key reaches the Keystore before the preferences commit, so a commit failure on Replace displaces the
working key: adopted as a repository repair (§3 repository), classified HYPOTHETICAL (a failed
SharedPreferences commit) and fixed because the wrong state would be silent. Simplifications: the write
policy is the setup page's policy renamed, and the model-visibility rule moves intact; both accepted.

**Grounded round 1 (same session): PROCEED-WITH-REVISIONS, seven drop-ins, all adopted.** (1) Clear the
provider's persisted cache before a save that supplies a key, so a process death between commit and
promotion cannot label the old key's cache as the new key's (§3 view model). (2) The automatic save waits
for any pending write (§3 save at accept). (3) A completed KEY write closes Replace; Check, Retry, the
automatic save and the typed-model save all require a non-blank draft, so a rotation cannot fall back to
the stored key (§3). (4) The connected row's count needs LISTED plus `usedStoredKey`; a failed stored-key
Refresh keeps the listing and shows its line under Refresh (§3). (5) `defaultModel` order is recommended
AVAILABLE, AVAILABLE, UNVERIFIED (§3). (6) The Keystore snapshot is mandatory and a failed compensation
reports inconsistent storage instead of "nothing changed" (§3 repository, §7). (7) The write-policy test
is renamed to what it asserts (§11). Axes reported not found: late completion, self-hosted, snackbar.

**Grounded round 2 (confirming, same session): PROCEED-WITH-REVISIONS, three wording clarifications, all
adopted, and every other revision confirmed closed.** (1) Replace closes only on DONE, never on FAILED
(§3 rung 3). (2) `beforeWrite` runs inside the mutex and the captured outcome, so a throw publishes FAILED on
the same sequence (§3 view model). (3) The inconsistent-storage error is a typed exception mapped to its
own sentence (§3 repository). No third round: the residue was wording the drafts already honoured, and the
founder's standing guidance is to spend no extra rounds on wording.
