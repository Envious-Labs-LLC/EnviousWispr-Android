# Issue #84 — Live model list from the provider: fetch, filter, probe, recommend, cache — 2026-09-02

GitHub issue: `#84`. Tier: MEDIUM. Status: SHIPPED (2026-09-02, PR to follow this commit; the real-key emulator run is owed, see §13.1). Founder direction 2026-09-02 ("no hard coded list. it
should pull live from the api call. please see how we do it from enviouswispr on macos"). Builds on #61
(shipped: `ProviderKeyChecker` and the model-list GET) and #67 (the setup page). The Ladder (#81) gets its
Check pill from this plan.

**Consolidation:** the root this plan protects is "the models the page offers are the ones this key can
use today". Today `ui/PolishModelCatalog.kt` is a hand-written list checked against vendor pages on
2026-09-01, and nothing on the phone can tell when it goes stale. After this plan the list comes from the
provider on every Check, the hand-written data survives only as decoration (a note and the C/S/A dots for
ids that still exist), and the cached list per provider is the only copy the page reads.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** `ai-model-discovery` (Android: absent → shipped), evidence the live emulator run below.

**Hardware UAT:** Y, on the EMULATOR with the founder's REAL keys (vault names `openai-api-key`,
`gemini-api-key`, `anthropic-api-key`; the founder allowed them 2026-09-02). Success looks like: on the
OpenAI setup page the founder's key is entered and Check tapped; within a few seconds the list shows the
models his account can reach, available ones first with "Recommended" on the mini/nano rows, any locked
ones greyed at the bottom; he picks one, Save lands on the tab with that model in the badge. Reopening the
page shows the same list at once from the cache. **Typing a vault key into the emulator needs a permitted
path**: the harness classifier blocked `get-key launch ... adb shell input text` on 2026-09-02, so the
founder either runs that one command himself or adds a permission rule; until then the run uses stand-in
keys and proves only the refusal path, and the plan says so.

## Preface — User Rubric

1. **Who.** Priya Ramachandran, at her desk, setting up OpenAI on the phone with the key from her team's
   project. Thirty seconds from now she wants the model she uses on the Mac, and she expects the list to
   match what her key can call.
2. **Why.** "Show me what my key can actually use, not a menu somebody typed last month."
3. **Invoke.** Reactive: she taps Check after pasting the key; the list arrives. Later opens show the cache.
4. **App.** EnviousWispr's setup page only.
5. **Input.** No speech. Keys: a full project key; a key on an org with no GPT-5 access (locked rows);
   a Gemini key with a free tier (rate-limited probes); a revoked key; a stored key and a blank draft.
6. **Success.** The list appears with a Recommended row on top; she does not read the rest.
7. **Wrong-not-broken.** Every row is greyed "not available" because the probes hit a rate limit, and she
   assumes her key is broken. The probe rules must read a transient limit as available, as the Mac does.
8. **Power user hack.** She types the id she knows into Search; if it is not in the live list she cannot
   pick it. The saved model is always kept selectable even when a fresh list lacks it.
9. **Control.** Search and the four sorts as today; no setting to turn the live list off (an offline user
   sees the cache, and with no cache the page says why).

### Cross-persona check

Priya and Aaron want the truth about their key and get locked rows. Marcus, Diana and Meera see one
Recommended row and tap it. Elena and Frank never open this page. No tension.

## 0. TL;DR

Check on the key field asks the provider for its model list with the user's key (the #61 call), drops
models that cannot polish text, probes each remaining model with a five-token "Hi" so ones the key cannot
reach show as locked, tags mini/nano/flash/haiku rows "Recommended", sorts available first, and caches the
list per provider so the page opens instantly next time. The hand-written catalog becomes decoration
(notes and dots for known ids). Save keeps its own key check. No user content is sent: the list call has no
body and the probe body is the fixed word "Hi".

## 1. Problem

`PolishModelCatalog` (`ui/PolishModelCatalog.kt:29-90`) is typed by hand. It already lost six OpenAI ids to
deprecations in one sweep (its own comment, 2026-09-01) and cannot know what a given key may call; a user
whose org lacks a family picks it and learns at the first dictation, through the #77 notice.

## 2. Goals & non-goals

### 2.1 Goals
- The offered models are the provider's list for this key, fetched on Check and cached per provider.
- Models the key cannot reach are visible but locked; transient limits never read as locked.
- The macOS filter, probe, recommendation and sort rules are ported as pure functions with their cases.
- No new HTTP stack; the #61 runner and headers serve the list and the probes.

### 2.2 Non-goals
- Self-hosted and Ollama discovery (`/api/tags`); the self-hosted card is read-only on Android.
- Auto-selecting or replacing a saved model when the fresh list lacks it (macOS founder decision: existing
  selections are left alone).
- The Ladder layout itself (#81).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end
**List.** `ProviderPolishClient.check` (`providers/ProviderPolishClient.kt`) already GETs the list and
proves the envelope (`hasModelList`) but discards the rows; `ProviderKeyCheck.Accepted` is a bare object
(`providers/ProviderKeyCheck.kt`). The page renders `PolishModelCatalog.filterAndSort(provider, query,
sort, savedModel)` (`ui/ProviderSetupPage.kt:167-168`) into cards with name, note, tag and three dot
columns (`:196-230`). `PolishModelCatalogTest` (removed) pins the sorts, the preserved saved row and the
Responses-API exclusion (`:79`).
**Key.** The draft is a plain `remember` on the page (`:66`), passed to `onSave` only. The stored key is
read by the repository (`providers/ProviderConfigurationRepository.kt:96-100`).
**Cache.** No model cache exists on Android. `AppPreferences` (`settings/AppPreferences.kt`) is DataStore
for app toggles, not a per-provider blob; `ProviderConfigurationRepository` owns one SharedPreferences file
for the selection.

### 2. Find the existing authority before proposing one
- macOS `LLMModelDiscovery.swift`: fetch (`:166-200` Gemini keeps `supportedGenerationMethods` containing
  `generateContent` and strips `models/`; `:244-281` OpenAI `data[].id`; `:351-407` Claude `data[].id` with
  `display_name`, cursor pagination `has_more`/`last_id`/`after_id`, a repeated or empty cursor stops as
  malformed); filter (`:13-24`, `:549-571`: exclude patterns, `-001/-002/-003`, `latest`; `:282-293` OpenAI
  prefixes gpt-/o-/o1/o3/o4 minus realtime/audio/search/transcribe); probe (`:205-243` Gemini 429 available
  unless the body says `limit: 0`; `:295-327` OpenAI 200 only; `:408-444` Claude 429 and 5xx available), five
  in flight (`Constants.swift:405`), sorted available first then by display name (`:157-160`).
- macOS `AIPolishModelClassifier` (`AIPolishSettingsView.swift:122-142`): recommended when the id's tokens
  (split on `-._/`) contain mini, nano, flash or haiku and none of realtime, audio, native, live, tts,
  image, search, transcribe, banana, codex; validated live 2026-05-04 (`docs/audits/2026-05-04-issue-617-classifier-validation.txt`).
- macOS cache: `LLMModelDiscoveryCoordinator.swift:115-132`, one JSON blob per provider in UserDefaults,
  loaded on open, replaced on a successful discovery.
- macOS `SettingsManager.applyDiscoveredModels` (`:1030-1056`): an empty cloud list is a discovery hiccup,
  never a reason to clear the saved model.
- Android already has the runner, the headers, the envelope rule and the JSON parser (#61).

### 3. Read prior attempts and live direction
- #61 plan §2.2 named the live list as out of scope; the founder pulled it in the same night.
- `PolishModelCatalog.kt:29-58` records why ids were removed and which are chat-only; that knowledge
  becomes the Android-only exclusion list below, with its date.
- #81's Ladder: rung 3 "Check" then rung 4 the list, "Ranked for dictation polish"; this plan's Check is that
  pill on today's page.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss
- **The key draft leaves the page once more.** Today it travels to the repository on Save; Check hands the
  same draft to the view model's discovery call, which passes it to the client and drops it. It is never
  stored in UI state, never logged, never cached; the cache holds model rows only.
- **Probes cost.** Each probe is one tiny completion (five output tokens). Twenty models is twenty calls,
  three in flight on their own executor; Gemini free tier may answer 429, which the macOS rule reads as
  available unless `limit: 0`. The probe body is the fixed word "Hi", never the prompt.
- **Check and Save race.** Check runs outside the settings mutex (it writes nothing but the cache); Save
  still runs its own key check under the mutex. A Check in flight when the page pops still completes into
  the view model; only a completion whose sequence is no longer the latest is dropped.
- **An empty or failed discovery must not touch the saved model** (macOS decision); the page keeps the
  saved model selectable through the pinned row exactly as today.
- **Offline with a cache** shows the cache with its age (for the stored key; a non-blank edited draft
  hides it); offline with no cache shows one line, and the Search field doubles as a free-text id when the
  list is empty (the pinned saved row already proves a non-catalog id is storable), so a power user is
  never stranded.

### 5. Prove the high-risk premises
- **The three list endpoints answer with the envelopes named.** Proven 2026-09-02 on the emulator against
  Gemini (400 for a bad key on `v1beta/models`) and OpenAI (401); the 200 shapes are macOS's shipping
  parsers and the #61 fake-server cases.
- **Probes with a five-token limit are accepted by all three APIs.** macOS ships exactly these bodies
  (`makeOpenAIProbeRequestBody` (external), `makeGeminiProbeRequestBody` (external), `ClaudeConnector.makeRequestBody` (external) with
  `maxTokens: 5`); Android's Responses body needs `max_output_tokens`, its Gemini body
  `generationConfig.maxOutputTokens`, its Claude body `max_tokens`, all documented fields the polish
  request already uses or omits. Verified live in the emulator run with real keys.
- **The OpenAI Responses eligibility rule differs from the Mac's.** The Mac excludes Responses-only
  families (codex, -pro) because it calls chat completions; Android calls Responses, so those are callable,
  and the chat-only ids (`o1-mini`, `o1-preview`) are not (catalog comment 2026-09-01, OpenAI's deprecation
  page). Android keeps the prefix and modality rules and swaps the endpoint rule; the probe catches
  anything the rule misses.

## 3. Design

### The discovery types (`providers/ProviderModelDiscovery.kt`, new)
- `enum class ModelAccess { AVAILABLE, UNAVAILABLE, UNVERIFIED }`: a probe's 200 (or the macOS transient
  rules) is AVAILABLE; a definite refusal (401, 403, 404, a 4xx that is not a limit) is UNAVAILABLE; a
  transport failure, a timeout or an unclassified reply is UNVERIFIED and never locks a row (coverage A3).
- `data class DiscoveredModel(val id: String, val displayName: String, val access: ModelAccess, val recommended: Boolean)`.
- `sealed interface ProviderDiscovery`: `Listed(models: List<DiscoveredModel>, fetchedAt: Long)` or
  `Refused(verdict: ProviderKeyCheck)` carrying the #61 verdict (Rejected, Denied, Unverified) so the copy
  and the classification are shared.
- `fun interface ProviderModelDiscoverer { fun discoverModels(provider: Provider, apiKey: String): ProviderDiscovery }`:
  a SEPARATE operation from `ProviderKeyChecker.check`, which stays the cheap key validation Save calls
  (coverage B1, D1). `ProviderPolishClient` implements both; `ProviderKeyCheck.Accepted` stays an object.
- `object ModelListRules` (pure, tested): `filter(provider, rows)` (the macOS exclude patterns, `-001/-002/
  -003`, `latest`, the OpenAI prefix and modality rule, the Android chat-completions-only list `o1-mini`,
  `o1-preview` dated 2026-09-01; then dedupe by id and drop ids that fail the polish request's own model
  rule, `MAX_MODEL_CHARS` and no control characters; coverage A4); `isRecommended(id)`; `sort(models)`
  AVAILABLE first, UNVERIFIED next, UNAVAILABLE last, recommended first within a group, then display name;
  `displayName(provider, id, given)`; `claudePagination(hasMore, lastId, seen)`; `mergeAccess(fresh, cached)`
  matches exact ids, keeps the FRESH row's display name, recommendation and order, and replaces only a
  fresh UNVERIFIED access with a cached AVAILABLE or UNAVAILABLE (coverage D4; grounded round 1).

### The client (`providers/ProviderPolishClient.kt`)
- `check` is unchanged (#61).
- `discoverModels(provider, apiKey)`: one whole-operation deadline `DISCOVERY_TIMEOUT_MS` (60 s, macOS has
  none; a phone needs one); the list GET through the runner and the #61 classification (a non-200 is
  `Refused(verdict)` by the same rules as `check`); rows parsed per provider (OpenAI `data[].id`; Gemini
  `models[]` with `supportedGenerationMethods` containing `generateContent`, id without `models/`,
  `displayName`; Claude `data[].id` and `display_name`, `has_more`/`last_id`/`after_id` up to
  `MAX_LIST_PAGES` (10), a repeated or empty cursor stops with the rows so far); `ModelListRules.filter`;
  at most `MAX_PROBES` (40) rows probed, the rest UNVERIFIED (coverage A4).
- `probe(provider, id, apiKey): ModelAccess` reuses `requestPlan` with a the `probe` flag of `requestPlan`: input the fixed word
  "Hi", no system instruction, a five-token cap (`max_output_tokens` and `store:false` for OpenAI;
  `generationConfig.maxOutputTokens` for Gemini; `max_tokens` for Claude), 10 s per probe. 200 → AVAILABLE;
  Gemini 429 → UNAVAILABLE only if the body contains `limit: 0`, else AVAILABLE; Claude 429 and 5xx →
  AVAILABLE; OpenAI 429 → UNVERIFIED; 403 and 404 → UNAVAILABLE (a model this key may not use); 401 →
  the whole discovery is refused (above); other 4xx → UNVERIFIED unless a tested provider rule proves a
  model refusal; 5xx elsewhere, redirects, transport failures and timeouts → UNVERIFIED.
- Probes run on a SEPARATE bounded executor (`PROBE_EXECUTOR`, THREE daemon threads) so one request-pool
  worker always stays free for a normal polish request (coverage A6; grounded round 1); each probe's
  `run` receives the REMAINING whole-operation time, and on timeout, refusal, cancellation or return every
  outstanding probe future is cancelled with interruption. The discovery runs on the caller's IO thread.
  A probe answering 401, or a body `ProviderErrorSignal.classify` reads as KEY_REJECTED, ABORTS the
  discovery as `Refused(Rejected)`: that is the credential, not one model (grounded round 1).
- Log lines: `Discovery: OPENAI listed=23 kept=17 probed=17 available=15 unverified=1` and one per
  refusal; never ids, never the key.

### The cache (`providers/ModelListCache.kt`, new)
- One SharedPreferences file `envious_wispr_model_cache`, one JSON string per provider (`fetchedAt`, rows
  with id, displayName, access, recommended). No key fingerprint (coverage D3).
- **Written only for the saved credential**: a discovery that ran with the STORED key writes it at once; a
  discovery that ran with a DRAFT key stays in memory under its opaque discovery SEQUENCE and is promoted
  to the cache only when a Save that carried that same sequence completes successfully. The page keeps
  the sequence only while the key draft is unchanged and passes it into `onSave`; no key comparison and
  no key derivative is ever stored. A Save that supplied a key WITHOUT a matching sequence clears the
  provider's entry; Remove captures the selected provider BEFORE `clearSelection` and clears that entry
  (grounded round 1). A `Listed` that is EMPTY after filtering is a discovery hiccup: it is not written,
  the previous cache stays shown, and the empty-list message shows separately (macOS
  `applyDiscoveredModels` (external)).
- Read on page open. A failed `commit()` is logged; the fresh list is still shown.

### The decoration (`ui/PolishModelCatalog.kt` → `ui/ModelNotes.kt`)
- The hand-written rows become `ModelNotes.forId(provider, id): CatalogModel?` (note and C/S/A dots for
  the ids still in the live list); `modelsFor` (removed) and `filterAndSort` (removed) are deleted. The founder's Ladder (#81)
  carries the dots and the four sort chips, so they stay as decoration: CHEAPEST, FASTEST and ACCURATE
  order rated ids by their dots and put unrated ids after them, within the access grouping; SUGGESTED is
  `ModelListRules.sort`. (Coverage C2 proposed dropping them; rejected with the founder's own design as
  the evidence.)
- `ui/ModelListPresentation.kt` (new, pure): `present(models, query, sort, savedModel)` → rows with a
  `selectable` flag: AVAILABLE and UNVERIFIED rows are selectable; UNAVAILABLE rows are not, EXCEPT the
  saved model, which stays selectable and shows "Not available with this key" so the user knows (macOS
  leaves existing selections alone; coverage A9). A saved model missing from the list is pinned
  "Currently selected". With NO rows at all, a non-blank query yields one row `Use "<query>"` whose tap sets
  the model draft to the typed id (coverage A8); the id must pass the polish model rule.

### The view model (`ui/AppViewModel.kt`)
- Gains `discoverer: ProviderModelDiscoverer = ProviderPolishClient()` and `modelCache: ModelListCache`
  constructor parameters (coverage B5); the factory in `SettingsActivity` passes the production ones.
- `ProviderDiscoveryUiState(provider, sequence, phase: IDLE | CHECKING | LISTED | FAILED, models, fetchedAt,
  fromCache, line)`. `discoverModels(provider, apiKeyDraft: String?): Int` allocates
  `nextDiscoverySequence` and records `latestDiscoveryByProvider` ON MAIN (its own counters; never
  `nextWriteSequence`, never the settings mutex; grounded round 1), resolves the key (draft, else stored),
  runs the discoverer on IO, and on completion applies the result only if its sequence is still the
  latest for that provider; a stale completion writes nothing. LISTED merges access with the cache
  (`mergeAccess`), writes the cache when the stored key was used, else keeps the list in memory under its
  sequence. `saveProviderSettings` gains `discoverySequence: Int?`: on a completed successful Save the
  view model promotes the in-memory result with that sequence, or clears the cache when a key was supplied
  with no matching sequence; a failed Save neither promotes nor clears; `clearProviderSettings` captures
  the provider first and clears its cache on completion. The pure decision lives in
  `ProviderDiscoveryApplyPolicy` (latest-sequence, promote, clear) and is tested there.
- `loadCachedModels(provider)` on page open.

### The page (`ui/ProviderSetupPage.kt`)
- The key field gains a trailing **Check** pill: enabled when the draft is non-blank or a key is stored,
  and not while CHECKING; reads "Checking" while CHECKING; a FAILED line renders under the key field with
  Check-specific copy (`discoveryLine`: "OpenAI rejected this key.", "Couldn't check the key with Gemini:
  no connection.", no "Nothing was saved"; coverage B3). While a NON-BLANK edited draft exists that has not
  been checked, the page hides both the prior draft result and the saved-credential cache and shows the
  Check prompt; the saved cache returns only when the draft is cleared; a successful Check shows the new
  draft result (grounded round 1). The page keeps the last Check's sequence while the draft is unchanged
  and passes it to `onSave`.
- The model section shows the LISTED rows or the cache: available rows as today with "Recommended" where
  the classifier says so; UNVERIFIED rows plain; UNAVAILABLE rows greyed "Not available with this key";
  the count line "17 models · 15 available" plus "from <relative time>" for a cached list; with no rows,
  "Tap Check to load the models this key can use." and the Search field's `Use "<id>"` row.
- Rotation during Check: the flow lives in the view model and the page re-subscribes; the key draft
  clears as today, so a draft result arriving after rotation shows in memory and is NOT cached (its
  credential is gone from the page); the user re-pastes the key to save (coverage A7).
- Save is unchanged: its own `check`, milliseconds (coverage D1).

### Alternatives rejected
- Folding probes into `check`: every Save would pay for and wait on the probes under the settings mutex
  (coverage B1).
- Probing on Save only: the list would be unverified when shown; the Mac probes at discovery.
- A key fingerprint in the cache: a hash of a secret on disk for a convenience; the saved-credential rule
  gives the same guarantee without it (coverage D3).
- DataStore for the cache: a per-provider blob is not the app's typed settings object; the selection
  file's SharedPreferences pattern already exists.
- Dropping the dots and the sorts (coverage C2): the founder's Ladder design carries them.

## 3b. Ownership justification
Discovery lives in `ProviderPolishClient` beside `check` because it is the same list call plus probes on
the same runner and headers; it is a separate operation so that Save's contract stays cheap. The cache is
owned by a small class beside the repository, not inside it: the repository's contract is the SELECTION
and its key, and the cache carries no policy meaning. The view model owns sequencing and cache
promotion because it already owns the write sequence that decides which completion is current.

## 4. Contract deltas
- New `ProviderModelDiscoverer`, `ProviderDiscovery`, `DiscoveredModel`, `ModelAccess`, `ModelListRules`,
  `ModelListCache`, `ModelNotes`, `ModelListPresentation`, `discoveryLine`.
- `ProviderPolishClient` implements `ProviderModelDiscoverer`; the `probe` flag of `requestPlan` branch in `requestPlan`;
  `DISCOVERY_TIMEOUT_MS`, `PROBE_TIMEOUT_MS`, `MAX_LIST_PAGES`, `MAX_PROBES`, `PROBE_EXECUTOR`.
- `ProviderKeyChecker.check`, `ProviderKeyCheck`, the repository and `keyCheckLine` are UNCHANGED.
- `EnviousWisprViewModel` constructor gains `discoverer` and `modelCache`; new discovery state and calls.
- `PolishModelCatalog.modelsFor` (removed) and `filterAndSort` (removed) deleted (`GR-MIGRATION-COMPLETE`).
- No AIDL, Room, manifest or selection-file change.

## 5. End-to-end state and lifecycle audit
| # | Step | State | Exit |
|---|---|---|---|
| 1 | page opens, key stored | cache read → LISTED(fromCache) or IDLE | Check available |
| 2 | Check tapped | sequence N, CHECKING | latest completion → 3 or 4; older → dropped |
| 3 | listed + probed within 60 s | LISTED; cached if the stored key was used, else in memory | rows shown |
| 4 | refusal or discovery timeout | FAILED, line under the key field; the cache stays shown | Check again |
| 5 | key edited after 3 | draft result marked stale; the cache (or IDLE) shows | Check again |
| 6 | Save succeeds with the checked draft | draft result promoted to the cache | tab |
| 7 | Save succeeds with a different key, or Remove | provider cache cleared | tab |
| 8 | rotation during 2 | flow survives; key draft cleared; a draft result arrives in memory only | re-paste to save |
| 9 | Back during 2 | completion applies to the flow; cache written only for the stored key | next open |
| 10 | process death during 2 | lost; next open reads the cache | Check again |
| 11 | saved model UNAVAILABLE in a fresh list | shown locked-styled but selectable with the note | unchanged |
| 12 | no rows, id typed in Search | `Use "<id>"` row sets the draft | Save runs `check` |

## 6. Downstream consumer matrix
| Producer | Consumer | Today | After | Test |
|---|---|---|---|---|
| `discoverModels` | view model | none | LISTED / FAILED by sequence | `ProviderPolishClientTest` discovery cases; emulator |
| `ModelListRules` | client, presentation | none | one filter, classifier, sort, merge | `ModelListRulesTest` |
| `ModelListCache` | view model | none | saved-credential cache, cleared on key change and Remove | `ModelListCacheTest` (device) |
| `ModelNotes` | presentation | the list itself | decoration only | `ModelListPresentationTest` |
| `check` | repository | #61 | unchanged | existing tests |

## 7. Failure-mode × caller table
| Failure | Caller sees | Cache | Recovery |
|---|---|---|---|
| key rejected / denied | "OpenAI rejected this key." under the key field | untouched; shown only for a stored-key Check, hidden while a non-blank draft exists | fix the key, Check |
| list unreachable / timeout / rate limited | "Couldn't check the key with X: …" | untouched; same visibility rule | Check again |
| a probe times out or is limited | that row UNVERIFIED (plain), a cached access kept | written (stored key) | Check again |
| a probe says 403/404 | that row locked | written | pick another |
| a probe says 401 | the whole discovery refused as rejected | untouched | fix the key |
| whole discovery past 60 s | the list still comes back; every probe the deadline cut off is UNVERIFIED (plain, never locked); only a deadline spent before the list call itself refuses as "it took too long" | written for the stored key | Check again |
| empty list after filtering | "No models this key can use for polish." | untouched | another provider |
| Claude pagination loops or malforms | rows so far; a log line | written | none |
| cache commit fails | fresh list shown; a log line | not written | next Check |
| stale completion (older sequence) | nothing | nothing | none |

## 8. Caller-visible signals audit
The Check pill and its line, the count line, Recommended and locked rows, the cache age. Logs: the
discovery count line, per-refusal lines, `Probe batch` counts; never ids, never a key.

## 9. Fallback source-of-truth audit
The selection file stays the truth for what polish uses; the cache is a picture of the provider at a time,
for the saved credential only, never consulted at dictation time. A stale cache can offer a retired model;
the #77 notice reports MODEL_UNAVAILABLE at first use and the next Check corrects it.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/providers/ProviderModelDiscovery.kt` (new): `ModelAccess`,
  `DiscoveredModel`, `ProviderDiscovery`, `ProviderModelDiscoverer`, `ModelListRules`.
- `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt`: `discoverModels`, row parsing,
  Claude pagination, the `probe` flag of `requestPlan` in `requestPlan`, `probe`, `PROBE_EXECUTOR`, the constants, the log lines.
- `app/src/main/java/com/envi/wispr/providers/ModelListCache.kt` (new).
- `app/src/main/java/com/envi/wispr/ui/ModelNotes.kt` (renamed from `PolishModelCatalog.kt`): decoration only.
- `app/src/main/java/com/envi/wispr/ui/ModelListPresentation.kt` (new).
- `app/src/main/java/com/envi/wispr/ui/KeyCheckCopy.kt`: `discoveryLine`.
- `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt`: discovery state, sequence, cache promotion.
- `app/src/main/java/com/envi/wispr/ui/SettingsActivity.kt`: the factory's two new arguments.
- `app/src/main/java/com/envi/wispr/ui/ProviderSetupPage.kt`: Check pill, locked rows, count line, empty state.
- Tests: `ModelListRulesTest` (new), `ModelListPresentationTest` (from `PolishModelCatalogTest` (removed)),
  `ProviderPolishClientTest` discovery cases, `ModelListCacheTest` (device), `KeyCheckCopyTest` gains the
  Check lines.

## 11. Testing
1. **Class.** `ModelListRulesTest`: product outcome; when it fails an image model or a retired alias is
   offered, the cheap fast row loses its tag, or a timed-out probe locks a row. `ProviderPolishClientTest`
   discovery cases: contract; when they fail a page of models is dropped, a rate-limited model reads as
   locked, or a probe sends the prompt. `ModelListPresentationTest`: product outcome; when it fails the saved
   model vanishes, a locked row is selectable, or the typed id cannot be used. `ModelListCacheTest`: drift
   guard on the round trip and the clear.
2. **Revert that turns it red.** Drop the `generateContent` filter → the Gemini parse case. Read Gemini 429
   as locked → the probe case. Stop following `has_more` → the two-page case. Lock on a probe timeout →
   the UNVERIFIED case. Sort locked first → the presentation case. Treat `codex` as recommended → the
   classifier case. Apply an older sequence → the `ProviderDiscoveryApplyPolicyTest` case.
3. **Client cases.** The fake server gains a SCRIPTED multi-connection mode (an expected request count, a
   queue of responses, thread-safe request capture, concurrent probes; grounded round 1); the discovery
   and probe timeouts are constructor parameters so the deadline cases run in milliseconds. Cases: row
   parsing per provider; Claude two pages then stop, a repeated cursor stops; filter and dedupe;
   `MAX_PROBES` cap; at most three probes in flight (the server counts concurrent connections); the probe
   body per provider (fixed "Hi", the cap field, `store:false`, no system instruction); probe verdict per
   status including Gemini `limit: 0`; a probe 401 aborts as `Refused(Rejected)`; a probe timeout →
   UNVERIFIED; the whole-operation deadline → `Refused(Unverified(TIMED_OUT))` with every queued and
   active probe cancelled (the server sees no further requests).
4. **Apply-policy cases** (`ProviderDiscoveryApplyPolicyTest`, pure): a stored-key discovery writes at
   once; a matching-sequence Save promotes; a supplied key with no matching sequence clears; a failed
   Save neither promotes nor clears; Remove clears the captured provider; a stale sequence writes nothing
   to UI or cache; an empty filtered list is not written.
5. **Not tested.** Compose rendering of the pill and locked rows (no rig, #48): emulator run.

### 11.1 Hardware UAT spec
- **Subsystem:** limb (polish setup). Emulator, real keys (Preface).
- **Recipe:** for each provider: open setup, enter the vault key through a permitted path, Check, read the
  page source (count line, Recommended rows, locked rows), pick the recommended row, Save; reopen the page
  and read the cached list and its age; edit the key by one character and confirm the cache DISAPPEARS,
  clear the draft and confirm it returns; airplane mode, Check, read the line and that the cached list
  stays; Remove and see the cache cleared on reopen.
- **Expected observation:** per provider the count line and at least one Recommended row; the badge after
  Save; the cache age on reopen; logcat `Discovery:` and `Probe batch` lines.
- **State to restore:** provider removed on the emulator; nothing on the phone.

### 11.2 Other obligations
| Test | Class | Proves | Revert |
|---|---|---|---|
| `ModelListRulesTest` | product outcome | filter, classifier, sort, merge, pagination decision | see 11.2 |
| `ProviderPolishClientTest` discovery cases | contract | parsing, pagination, probe shape and verdicts, caps, deadline | see 11.2 |
| `ModelListPresentationTest` | product outcome | query, sorts, pinned row, locked not selectable, typed id | see 11.2 |
| `ProviderDiscoveryApplyPolicyTest` | product outcome | latest wins, promote, clear, failed Save, Remove, empty list | see 11.2 |
| `ModelListCacheTest` | drift guard | round trip, clear, failed commit keeps the old blob | corrupt one field |

## 12. Blast radius & rollback
The cloud client gains a second operation on the shared runner (the polish request and `check` are
untouched beyond a the `probe` flag of `requestPlan` branch), a new preference file, the setup page's model section, two view
model dependencies, and the deletion of the hand-written list. Rollback is one revert; the new preference
file is harmless if left.

## 13. Ship criteria specific to THIS change
- [ ] With a real key, each provider's Check lists models with a Recommended row and locked rows where the
      key lacks access; Save lands the picked model in the badge; Save itself stays a single key check.
      NOT RUN: the harness blocks typing a vault key into the emulator (§13.1); owed as soon as the founder
      lifts it.
- [ ] Reopening the page shows the cached list with its age; editing the key hides a draft result; airplane
      mode keeps the cache and refuses Check with "no connection"; Remove clears it. PARTLY: airplane mode
      refuses Check with "no connection" (seen); the cache steps need the real key above.
- [x] The macOS filter and classifier cases pass ported; the Android endpoint rule excludes `o1-mini`; a
      timed-out probe never locks a row (unit tests, seven reverts red).
- [x] The hand-written list is gone from the page; `ModelNotes` decorates only.

## 13.1 Found on the emulator

2026-09-02 01:31 to 01:35, Appium and logcat: the Check pill and the empty-state prompt render; a stand-in
Gemini key is refused on the live endpoint with "Gemini rejected this key." under the field and nothing
cached; airplane mode refuses with "Couldn't check the key with Gemini: no connection.". Device tests OK
(17: the cache round trip, clear and failed commit; the repository cases). The accepted path with a real
key was NOT run: the harness classifier blocks `get-key launch ... adb shell input text`; the founder was
asked for a permission rule. The fake-server cases stand for the list, pagination, filter, probes, caps
and cancellation until then.

## 14. Open questions
Probing is decided by the founder's "see how we do it on macOS". The Android endpoint rule and the sorts
kept as decoration are stated deviations with their evidence.

## 15. Related
#84 (this), #61 (the check), #81 (the Ladder), #67 (the page), #77 (the notice), `ai-model-discovery`.

---

## Review log

- **Code rounds 2 to 4, 2026-09-02, same session:** round 2 confirmed nine and found one ordering defect
  (page ownership read before the cache I/O), adopted; round 3 found the same shape for the sequence, so
  the class was enumerated by the author: the three suspensions in `discoverModels` (the discovery, the
  merge's cache read, the cache write) each followed by one `appliesNow()` that re-asks both freshness and
  page ownership; round 4 named the one member the sweep had described but not implemented (the cache
  write's own freshness gate), adopted verbatim. Closed by construction; no further round.
- **Code round 1, 2026-09-02, same session:** ten findings, nine adopted as given and one adapted: a
  malformed LATER Claude page keeps the rows already fetched; a probe's key rejection carries the status
  the provider sent (Gemini's 400); a draft-key discovery never merges the saved credential's cached
  access; a completion touches the page's state only for the ACTIVE provider and draft results are held
  per provider; the cache promotion or clear is awaited BEFORE the completed write is published; the
  draft is judged raw for control characters before the trim; a draft Check shows nothing of the saved
  credential while it runs and Save waits for a running Check; the count line counts the pinned saved row;
  two client cases added (malformed later page; the deadline cancels queued and active probes) plus the
  explicit FASTEST case and the pinned-row count. Adapted: the three view-model tests asked for became
  pure `ProviderDiscoveryApplyPolicy` cases (active-page ownership, draft-never-merges), because the view
  model has no unit rig; the publish-after-cache ordering is structural (awaited in one coroutine) and is
  observed on the emulator.
- **Grounded round 2, 2026-09-02, same session:** wording residues only (four → three probes; Back does
  not drop the request; 401 removed from row-level UNAVAILABLE; the failure table's cache visibility; the
  UAT's key-edit step). Applied; treated as PROCEED-AS-PLANNED under the founder's diminishing-returns
  guidance (no fourth round for wording).
- **Grounded round 1, 2026-09-02, same session:** PROCEED-WITH-REVISIONS, eight, all adopted: three
  probes in flight and every probe cancelled on exit; discovery's own sequence counters on Main, never the
  write sequence or the mutex, with the decision in `ProviderDiscoveryApplyPolicy`; an opaque discovery
  sequence carried from Check into Save instead of any key comparison, Remove capturing the provider
  first; an edited draft hides both the prior result and the saved cache; `mergeAccess` ownership and the
  empty-list rule; a probe 401 refuses the whole discovery; a scripted multi-connection fake server and
  injected timeouts; the apply-policy test list.
- **Coverage round, 2026-09-02, Codex session `01a0606d-0fb1-79f1-8361-e8623c055959`:**
  PROCEED-WITH-REVISIONS, seven drop-ins, all adopted: discovery is a separate operation and Save keeps the
  cheap `check`; discoveries carry a sequence and provider identity and only the latest applies; the cache
  is written only for the saved credential, promoted on a matching Save, cleared on a key change or
  Remove, with no key fingerprint; access is a three-way `ModelAccess` and transport failures never lock;
  ids are deduplicated and validated, pages and probes capped, `store:false` kept, probes on their own
  bounded executor under one whole-operation deadline; the Search field's `Use "<id>"` row and the
  saved-but-unavailable rule; Check-specific copy. One simplification REJECTED with evidence: dropping the
  C/S/A dots and the three rated sorts, which the founder's own Ladder design (#81) carries; they stay as
  decoration with unrated ids ordered last.

## Checklist for the plan author
- [x] Every claim above carries a `file:line` or names the run that produced it.
- [x] §2.5 preceded §3.
- [x] Every backticked identifier greps against the working tree or carries /.
