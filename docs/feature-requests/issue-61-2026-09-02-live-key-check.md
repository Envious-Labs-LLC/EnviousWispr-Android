# Issue #61 — Live key check: Save asks the provider whether the key works — 2026-09-02

GitHub issue: `#61`. Tier: MEDIUM. Status: SHIPPED (2026-09-02, PR to follow this commit). Phase 7 of `plan-2026-09-01-ai-polish-refinement-roadmap.md`;
depends on phase 4 (#77, shipped: the status-aware failure vocabulary) and phase 6 (#67, shipped: the
provider setup page that owns the key draft). The four-rung Ladder (#81) needs this check and is not this plan.

**Consolidation:** the one root this plan protects is "a saved cloud key is one the provider accepted at
save time". Today `ProviderConfigurationValidator.validate` (`providers/ProviderValidation.kt:31-45`) checks
shape only, so a typo'd or revoked key saves and fails at the first dictation. The check lives beside the
only HTTP stack the app has (`ProviderPolishClient`), reuses its auth headers, timeouts, redirect refusal
and body classification, and is invoked from the one operation that resolves the effective key and owns the secret store
(`ProviderConfigurationRepository.saveProvider`). No second HTTP client, no second status matrix.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The macOS behaviour (`LLMModelDiscoveryCoordinator.validateKeyAndDiscoverModels` (external))
is the reference; the catalog carries no key-check row for any platform, so `cloud-polish` gains one.

**Hardware UAT:** Y, on the EMULATOR (`device-testing.md` RULE the-emulator-cannot-answer...: a screen-only
limb change validates there). Success looks like: on the setup page a stand-in Gemini key is typed and Save
tapped; the button reads "Checking key" for under two seconds, then the page stays with "Gemini rejected
this key. Nothing was saved." under Save, the key box still holding the draft, and the preference file
unchanged. A VALID verdict is proven against the fake server in the unit tests; no real provider key is
typed into any device by a session (a key in a tool call is a key in the transcript).

## Preface — User Rubric

1. **Who.** Meera Patel, on the sofa, phone in one hand, has just pasted an OpenAI key from her email into
   the setup page. Thirty seconds from now she wants to dictate a message and see it come out clean.
2. **Why.** "Tell me now if the key is wrong, not after I've talked into it."
3. **Invoke.** Reactive: she taps Save. Nothing else changes in her day.
4. **App.** EnviousWispr's own setup page. The dictation target is not involved.
5. **Input.** No speech. Five keys she might paste: a full key; a key with a trailing space; a key from a
   revoked project; a Gemini key pasted into the OpenAI page; nothing (she thinks the saved one is fine).
6. **Success.** Save takes a beat and the page closes. She never learns a check happened.
7. **Wrong-not-broken.** The provider is rate-limiting and Save refuses with a network-sounding line; she
   assumes her key is bad and pastes it three more times.
8. **Power user hack.** Priya would paste the key, watch it fail, and curl the models endpoint herself to
   see the real status. The line under Save must already say which of those it was.
9. **Control.** None needed: a check that costs one free request and says nothing on success has no
   setting. Off is not offered; a user who cannot reach the provider cannot use it anyway.

### Cross-persona check

Priya and Aaron want the exact reason (rejected, denied, unreachable) and get it. Marcus, Diana and Meera
want Save to just work and see nothing new on success. Elena never opens this page. Frank does not use
cloud polish. No tension to resolve.

## 0. TL;DR

Save on the provider setup page first asks the provider's free model-list endpoint whether the key works,
with the same auth headers the polish request uses. Accepted: the save proceeds as today. Rejected or
denied: nothing is saved and one sentence under Save says which. Unreachable, rate-limited or a provider
error: nothing is saved and the sentence says the key could not be checked and why. No dictated text, no
prompt, no model name is sent. Self-hosted saves are unchanged (no key to check).

## 1. Problem

`ProviderConfigurationValidator` accepts any non-blank key without control characters. A bad key is found
only by the first dictation, which then reports "OpenAI rejected your API key" through the #77 notice.
For a stranger's phone (stage 2) that is the wrong moment; for the founder it is a wasted dictation.

## 2. Goals & non-goals

### 2.1 Goals
- A cloud key is saved only after the provider accepted it, or the user is told exactly why not.
- The verdict vocabulary is the #77 one (`PolishFailure`), not a new set of strings.
- No new HTTP stack, no user content on the wire, no key in any log.

### 2.2 Non-goals
- A separate Check button (the Ladder, #81, adds an inline Check pill; this plan puts the check on Save).
- Using the returned model list to populate the catalog (`ai-model-discovery`, a later item).
- Checking self-hosted endpoints, or re-checking a stored key on app start.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end
**Key.** Typed into `ProviderSetupPage`'s plain `remember` draft (`ui/ProviderSetupPage.kt:66`), passed to
`onSave` → `AppViewModel.saveProviderSettings` (`ui/AppViewModel.kt:391-406`) → inside `updateProviderSettings`
on `Dispatchers.IO` under `providerSettingsMutex` → `ProviderConfigurationRepository.saveProvider`
(`providers/ProviderConfigurationRepository.kt:73-101`), which validates shape, stores the key in the Keystore
and the metadata in preferences. A blank draft while editing means "keep the stored key": `saveProvider`
reads `secrets.get(provider)` for validation (`:87-88`).
**Verdict consumer.** `updateProviderSettings` (`ui/AppViewModel.kt:442-470`) folds success to
`refreshProviderSettings(message)` and any throwable to `refreshProviderSettings(error = "Could not update
AI Polish settings")` (#67 code round 1).
The page waits on its sequence (`ProviderSetupSavePolicy`) and on FAILED shows `settings.error` under Save.
**Auth headers.** `ProviderPolishClient.requestPlan` (`providers/ProviderPolishClient.kt:304-350`): OpenAI
`Authorization: Bearer`, Gemini `x-goog-api-key`, Claude `x-api-key` + `anthropic-version`.
**Status classification.** `ProviderErrorSignal.classify` (`:81-110`) reads the error body into a closed
signal; `PolishFailure.from` / `fromStatus` (`polish/PolishFailure.kt:72-115`) maps reason + status to the
sentence set: 401 KEY_REJECTED, 403 ACCESS_DENIED, 429 RATE_LIMITED (Gemini RATE_OR_QUOTA), 5xx
PROVIDER_ERROR, NETWORK UNREACHABLE, TIMEOUT TIMED_OUT.

### 2. Find the existing authority before proposing one
- Endpoints and headers: the macOS reference `LLMModelDiscovery.swift` (`:166-200` Gemini `GET
  /v1beta/models` with `x-goog-api-key`, 403 → invalid key, 400 with `API_KEY_INVALID` → invalid key; `:244-262`
  OpenAI `GET /v1/models`, 401 → invalid key; `:351-380` Claude `GET /v1/models`, 401 → invalid key; all
  15 s). The roadmap §Phase 7 names the same three endpoints.
- Verdict states: macOS `KeyValidationState { idle, validating, valid, invalid(String) }`; the rail shows
  "Validating" / "Key valid" / "Key needed". On ANY error macOS reports invalid with the error text, so a
  network failure blocks the save there too. This plan keeps that (a save is refused until the provider
  answers) but names the reason from the #77 set instead of a raw error string.
- HTTP mechanics: `ProviderPolishClient.executeRequest` (`:221-300`) already refuses redirects, caps body
  size, classifies errors, and maps cancel/timeout/IO. A GET breaks only `requestMethod = "POST"` and
  `doOutput = true` (`:243-244`) and the polish-shaped parsers (`:402-423`), and a Success carries no
  status (`:56-65`), so the key check classifies the transport result before parsing rather than adding
  a probe response format; the audit's section F named the first two seams (`cloud-polish-audit.md` §F).

### 3. Read prior attempts and live direction
- Issue #61 body: Check calls the provider's own API; success moves on; failure shows "that key didn't
  work" and saves nothing. The #67 page has no Check button, so Save is the moment.
- `cloud-polish-audit.md` §F: reuse the plan machinery, add a method field; 200 valid, 401 rejected, 403
  denied, 429 not a verdict, network unknown. Its "any JSON" probe format is superseded by the envelope
  rule above (coverage round).
- #81 (Ladder): an inline Check pill with "Checking" and "Retry" states. This plan's client function is the
  one that pill will call.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss
- **Where the key travels.** Only to the provider named on the page, over HTTPS, in the same header the
  polish request uses. The response body (a model list) is read into memory for the JSON check and dropped;
  it is not logged and not stored. `DebugLogger` lines carry provider, status and verdict only.
- **The settings mutex.** The check runs inside `saveProvider`, which `updateProviderSettings` calls under
  its `withLock` on IO, so a tab tap during a 15 s probe queues behind it. Acceptable: the user is on the setup page, the page shows "Checking
  key", and every queued write still completes in tap order (#67's ordering guarantee).
- **Process death mid-check.** The page's saved target pops it during the initial load (#67); the queued
  write and the check die with the process; nothing was saved. The tab shows the truth.
- **Editing with a blank draft.** `saveProvider` already resolves "draft, else stored key" (`:96-100`);
  the check runs on that effective key, so a revoked key is caught when the user changes only the model.
  `SELF_HOSTED_POLISH` is never checked: exhaustive `when`.
- **A key with surrounding whitespace.** A NEW key is trimmed once before the check and the store; a stored
  key is never re-normalised on an unrelated edit.
- **A verdict that is not a verdict.** 429, 5xx, network, timeout, redirect, a read failure on ANY status,
  a 2xx other than 200, and a 200 without the provider's list envelope all refuse the save with "could not
  check" wording; they never say "rejected". The Gemini 403 is a rejected key on this endpoint (macOS
  `:180-182`); the dictation path's 403 mapping is untouched.

### 5. Prove the high-risk premises
- **The three endpoints answer a bare GET with the polish headers.** Gemini: proven on the phone 2026-09-01
  (#77 UAT: a fake key against `generateContent` returned 400 `API_KEY_INVALID`; the models endpoint uses
  the same header and the same key check upstream). OpenAI and Claude: macOS ships these exact calls.
  Verified on the emulator during this plan's UAT with a stand-in key (expect 401 / 403 / 400).
- **`HttpURLConnection` GET without `doOutput`.** Platform behaviour; the fake-server test asserts the
  method line reads `GET` and no body is sent.
- **A 200 body from these endpoints carries a list envelope.** OpenAI and Claude answer `{"data":[...]}`,
  Gemini `{"models":[...]}` (macOS `LLMModelDiscovery.swift:191,262,380`); Accepted requires that envelope,
  so an unrelated 200 (a captive portal, a proxy page) cannot validate a key.

## 3. Design

### The verdict type (`providers/ProviderKeyCheck.kt`, new)
`sealed interface ProviderKeyCheck`: `Accepted`, `NotApplicable` (self-hosted: no key to check),
`Rejected(status)`, `Denied(status)`, `Unverified(failure: PolishFailure, status?)` where the failure is one
of the existing #77 members RATE_LIMITED, RATE_OR_QUOTA, PROVIDER_ERROR, UNREACHABLE, TIMED_OUT, BAD_REQUEST
or UNEXPECTED (no new vocabulary; grounded round 1).
`fun interface ProviderKeyChecker` `{ fun check(provider: Provider, apiKey: String): ProviderKeyCheck }`, the seam the
repository is given, so a test injects a recording fake and never subclasses the final client.
`class ProviderKeyRefusedException(val provider: Provider, val verdict: ProviderKeyCheck) : RuntimeException`: the
typed refusal the repository throws; the view model reads the provider's display name from it and never
shows the exception text.

### The client (`providers/ProviderPolishClient.kt`)
- `ProviderPolishClient : ProviderKeyChecker`. One private `authHeaders(provider, apiKey)` builds the
  headers for BOTH `requestPlan` and the key check (Codex coverage C3): OpenAI `Authorization: Bearer`,
  Gemini `x-goog-api-key`, Claude `x-api-key` + `anthropic-version`; self-hosted keeps its optional bearer.
- `RequestPlan` gains `method` ("POST" today) and a nullable body; `executeRequest` sets
  `requestMethod = plan.method`, `doOutput = plan.body != null`, writes the body only when present.
- The executor, cancellation, deadline, redirect refusal and `readResponse` path become one internal
  runner returning a transport result (`Transport.Response(status, body)` or
  `Transport.Failed(kind, status?)`); `polish` parses a `Response` as today and `check` classifies
  one (Codex coverage C4, B1). A read failure on an error status is a `Failed` WITH the status, so a 401
  whose body times out or is oversized can never classify as Rejected (Codex coverage A4).
- `override fun check(provider, apiKey)`: exhaustive over `Provider`: OpenAI `GET
  OPENAI_MODELS_URL`, Gemini `GET GEMINI_MODELS_URL`, Claude `GET CLAUDE_MODELS_URL`, self-hosted →
  `NotApplicable` with no request. Overall `KEY_CHECK_TIMEOUT_MS = 15_000` (macOS's value).
  `keyCheckOverrides` mirrors `endpointOverrides` for the fake server. Classification, in one `when`:
  `Failed(NETWORK)` → Unverified(UNREACHABLE); `Failed(TIMEOUT)` → Unverified(TIMED_OUT); any other `Failed`
  (redirect, oversize, malformed, cancelled) → Unverified(BAD_REQUEST); `Response(200, body)` whose body is
  a JSON object whose provider field is a LIST, empty allowed (`data` for OpenAI and Claude, `models` for
  Gemini) → Accepted, any other 2xx or shape → Unverified(BAD_REQUEST) (Codex coverage A5); 401 →
  Rejected; 403 → Gemini Rejected (macOS `LLMModelDiscovery.swift:180-182`), else Denied; 400 with
  `ProviderErrorSignal.classify` = KEY_REJECTED → Rejected; 429 → Unverified(Gemini RATE_OR_QUOTA, else
  RATE_LIMITED); 5xx → Unverified(PROVIDER_ERROR); other 4xx → Unverified(BAD_REQUEST); anything else →
  Unverified(UNEXPECTED). `PolishFailure.fromStatus` stays
  private and the dictation path is untouched (Codex coverage B3, C1).

### The repository (`providers/ProviderConfigurationRepository.kt`)
- The internal primary constructor gains `keyCheck: ProviderKeyChecker`; the `Context` constructor passes
  `ProviderPolishClient()`.
- `saveProvider`: a supplied key is first rejected if it contains ISO control characters on the RAW value
  (today's rule, `ProviderValidation.kt:33-35`), then trimmed once, then validated, checked and stored as
  that exact trimmed value; a blank draft selects the untouched stored key, which is never re-normalised
  (Codex coverage A3, grounded round 1). After the shape
  validation and before any write, when the provider `requiresApiKey`, `keyCheck.check(provider,
  effectiveApiKey)` runs; ONLY `Accepted` proceeds. `NotApplicable` for a provider that needs a key is a
  checker fault and refuses like every other verdict (code round 1); every non-acceptance throws
  `ProviderKeyRefusedException` before `secrets.put` or any preference edit. The stored key is what gets
  checked when the draft is blank (Codex coverage C2), so a revoked key is caught on a model-only edit.

### The copy (`ui/KeyCheckCopy.kt`, new, pure)
`fun keyCheckLine(verdict: ProviderKeyCheck, providerName: String): String?`: null for Accepted and
NotApplicable; Rejected "{name} rejected this key. Nothing was saved."; Denied "{name} denied access for
this key. Check your billing or API access. Nothing was saved."; Unverified "Couldn't check the key with
{name}: {reason}. Nothing was saved." with reason from an exhaustive `when` over `PolishFailure` whose
setup-page wording is: RATE_LIMITED and RATE_OR_QUOTA "too many requests right now"; PROVIDER_ERROR "{name}
is having problems"; UNREACHABLE "no connection"; TIMED_OUT "it took too long"; BAD_REQUEST and UNEXPECTED
"an unexpected reply"; every other member (which the client never produces here) "an unexpected reply".
It never calls the dictation-specific `message()`. No dashes.

### The view model (`EnviousWisprViewModel`, `ui/AppViewModel.kt`)
- `updateProviderSettings`' `onFailure` maps a `ProviderKeyRefusedException` to
  `keyCheckLine(exception.verdict, exception.provider.capabilities().displayName)` and everything else to
  the calm sentence; the #67 rule (internal text never reaches the user) holds because the line is built
  from the verdict and the provider, never from the exception text. `saveProviderSettings` itself is unchanged: the check lives where the key is resolved.

### The page (`ui/ProviderSetupPage.kt`)
- The Save button reads "Checking key" while `saving`.
- **Rotation during the check**: the wait target and the model draft survive, the key draft clears (plain
  `remember`, by design: never saveable, never hoisted). On a refusal the user pastes the key again; the
  line under Save says so implicitly ("Nothing was saved"). Documented, not changed (Codex coverage A1, B4).
- **Back during the check**: the page pops; the queued write continues in the view model and completes. An
  Accepted check then saves and the tab reflects the saved configuration; a refusal saves nothing and the
  tab reflects the previous one, with no line anywhere (the error is setup-page origin and the page is
  gone). No snackbar is promised by this change (grounded round 1). No cancel: the key has already been sent, and cancelling a
  write mid-flight would add a third outcome for no user benefit (Codex coverage A2).

### Alternatives rejected
- A separate Check button now: two taps where one does; the Ladder (#81) owns that shape and will call
  `ProviderKeyChecker`.
- Saving on an Unverified verdict with a warning: a key that cannot be checked cannot be used, and a
  silent save of a bad key is the defect this plan removes; macOS blocks too.
- A dedicated `ProviderKeyChecker` implementation beside the client: a second HTTP stack with its own
  redirect, size and timeout bugs.
- Running the check in the view model with the key re-resolved there: decrypts the stored key in a second
  place (Codex coverage C2).

## 3b. Ownership justification
The request lives in `ProviderPolishClient` because the headers, executor, timeouts and body classifier
live there. The refusal lives in `ProviderConfigurationRepository.saveProvider` because that is the one
operation that resolves the effective key and owns the secret store, so "no write without an accepted key"
is a property of the writer, not of a caller remembering to ask.

## 4. Contract deltas
- `ProviderKeyChecker`, `ProviderKeyCheck`, `ProviderKeyRefusedException` (new, `providers`).
- `ProviderPolishClient` implements `ProviderKeyChecker`; `RequestPlan.method`, nullable body, internal
  runner (private).
- `ProviderConfigurationRepository` internal constructor gains `keyCheck`; `saveProvider` may throw
  `ProviderKeyRefusedException`; a new key is stored trimmed.
- `PolishFailure` unchanged. `ProviderSettingsUiState` unchanged. No AIDL, Room, preference key or
  manifest change (INTERNET exists since #77).

## 5. End-to-end state and lifecycle audit
| # | Step | State | Exit |
|---|---|---|---|
| 1 | Save tapped, cloud provider | page `target` set, button "Checking key" | write queued |
| 2 | `saveProvider` resolves the effective key (trimmed draft, else stored) and calls the checker under the settings mutex on IO | ≤ 15 s | Accepted → 3; else → 4 |
| 3 | store the key (if new), commit metadata and `last_on_mode` | saved | completed write, page pops |
| 4 | `ProviderKeyRefusedException` | nothing written, secret store untouched | completed write with `error` = the line; page stays, model draft intact, key draft intact |
| 5 | rotation during 2 | `target` and model draft saved; key draft cleared | the page keeps waiting; a later refusal shows its line |
| 6 | Back during 2 | page gone, write continues | Accepted saves and the tab reflects it; a refusal saves nothing |
| 7 | process death during 2 | the page pops on the next launch's initial load | nothing saved |
| 8 | self-hosted save (existing config path) | NotApplicable, no request | unchanged |
| 9 | model-only edit with a stored key | the stored key is checked | a revoked key is refused before the model changes |

## 6. Downstream consumer matrix
| Producer | Consumer | Today | After | Test |
|---|---|---|---|---|
| `ProviderKeyChecker.check` | `saveProvider` | n/a | refuses or proceeds before any write | `ProviderConfigurationRepositoryTest` with a recording fake (device) |
| `ProviderKeyRefusedException` | `updateProviderSettings` | n/a | the key-check line | emulator UAT; `KeyCheckCopyTest` for the line |
| `settings.error` | setup page | calm sentence | the key-check line or the calm sentence | `PolishCardStateTest` (unchanged policy) |
| `authHeaders` | `requestPlan`, `check` | headers inline | one helper | `ProviderPolishClientTest` (existing header assertions plus the GET cases) |
| `PolishFailure.fromStatus` | `PolishFailure.from` | one caller | unchanged | `PolishFailureTest` |

## 7. Failure-mode × caller table
| Failure | Caller sees | Saved? | Recovery |
|---|---|---|---|
| 401; Gemini 403; Gemini 400 API_KEY_INVALID | "{name} rejected this key. Nothing was saved." | no | fix the key, Save |
| 403 (OpenAI, Claude) | "{name} denied access for this key. Check your billing or API access. Nothing was saved." | no | check billing or access |
| 429 | "Couldn't check the key with {name}: too many requests right now. Nothing was saved." | no | Save again in a moment |
| 5xx | "…{name} is having problems…" | no | later |
| no network, DNS | "…no connection…" | no | connect, Save |
| 15 s timeout, or a read timeout on any status | "…it took too long…" | no | Save again |
| redirect, non-JSON, wrong envelope, other 2xx, other 4xx, oversized | "…an unexpected reply…" | no | later; the log names the kind and status |
| Keystore or commit failure AFTER an accepted check | "Could not update AI Polish settings" | partial as today | Save again |
| Back mid-check | nothing on screen | only if Accepted | the tab shows the truth |

## 8. Caller-visible signals audit
One new signal: the line under Save. `DebugLogger.info("Key check: $provider status=$status verdict=$verdict")`
is content-free (no key, no body). No toast, notification or History row: a settings action, not a
dictation.

## 9. Fallback source-of-truth audit
On any refusal nothing is written; the page re-reads `settings` after the write completes. No verdict is
cached anywhere; the next Save checks again.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/providers/ProviderKeyCheck.kt` (new): `ProviderKeyCheck`,
  `ProviderKeyChecker`, `ProviderKeyRefusedException`.
- `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt`: `authHeaders`, `RequestPlan.method`
  and nullable body, the internal runner and `Transport`, `check`, the three models URLs,
  `KEY_CHECK_TIMEOUT_MS`, `keyCheckOverrides`.
- `app/src/main/java/com/envi/wispr/providers/ProviderConfigurationRepository.kt`: `keyCheck` constructor
  parameter, trim-once, the check before any write.
- `app/src/main/java/com/envi/wispr/ui/KeyCheckCopy.kt` (new): `keyCheckLine`.
- `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt`: the `onFailure` mapping.
- `app/src/main/java/com/envi/wispr/ui/ProviderSetupPage.kt`: the button label.
- Tests: `ProviderPolishClientTest` (key-check cases), `KeyCheckCopyTest` (new), androidTest
  `ProviderConfigurationRepositoryTest` (orchestration with a recording fake checker), `PolishFailureTest`
  unchanged.

## 11. Testing
1. **Class.** `ProviderPolishClientTest` key-check cases: contract tests against the fake server; when they
   fail a bad key saves or a good key is refused. `KeyCheckCopyTest`: product outcome; when it fails the
   user reads "rejected" for a network problem (rubric 7) or a refusal has no line. Repository orchestration
   (device, real preferences, memory secrets, recording fake checker): product outcome; when it fails the
   check runs but the save proceeds anyway, or the wrong key is checked.
2. **Revert that turns it red.** Send POST instead of GET → the method assertion goes red. Map 403 to
   Rejected for OpenAI → the Denied case goes red. Return Accepted on 429 → the Unverified case goes red.
   Accept `{}` as a valid envelope → the envelope case goes red. Store the key before the check → the
   "refusal writes nothing" case goes red. Return null for Rejected → the copy test goes red.
3. **Client cases (Codex coverage D).** All three headers; GET with no body (the fake server records the
   method line and an empty body); 200 with the right envelope → Accepted; `{}`, an array, the wrong
   envelope, a `data` that is not a list, 204, malformed JSON, oversized → Unverified(BAD_REQUEST); an
   empty list → Accepted; 401 → Rejected; OpenAI 403 → Denied; Gemini 403 → Rejected; Gemini 400
   `API_KEY_INVALID` → Rejected; 429 → Unverified(RATE_LIMITED), Gemini 429 → Unverified(RATE_OR_QUOTA);
   503 → Unverified(PROVIDER_ERROR); a redirect → Unverified(BAD_REQUEST); a 401 whose body stalls past the
   read timeout → Unverified(TIMED_OUT), never Rejected; a server that accepts and closes before a status
   line → Unverified(UNREACHABLE) (a new fake-server mode; grounded round 1). Cancellation is not a key-check
   case (the checker takes no cancellation); the existing polish cancellation tests guard the runner
   extraction.
4. **Repository cases (device).** Accepted saves once with the key stored TRIMMED; a raw key with a control
   character is refused before any check; Rejected, Denied and Unverified each save nothing (before and
   after snapshots of the preference map and the memory secrets are equal); a blank draft with a stored
   key passes the stored key to the checker; self-hosted never calls the checker; the checker is called
   exactly once per save.
5. **Not tested.** A real Accepted verdict against a live provider (needs a real key; never typed by a
   session). Rotation and Back during the check (the #67 wait mechanism is unchanged and was seen on the
   phone; the Back outcome is by construction: the write is the view model's).

### 11.1 Hardware UAT spec
- **Subsystem:** limb (polish setup). Emulator, per the Preface.
- **Recipe:** open AI Polish, Choose a provider, Gemini, type a stand-in key, pick a model, Save. Read
  the page source for "Checking key" then the sentence; read the preference file and confirm no provider
  metadata; repeat with airplane mode on for the "no connection" line; then OpenAI with a stand-in key
  (expect 401 → rejected).
- **Expected observation:** the sentences named in §7; `mode` and `provider` unchanged in the file.
- **State to restore:** none (nothing is saved on a refusal).

### 11.2 Other obligations
| Test | Class | Proves | Revert |
|---|---|---|---|
| `ProviderPolishClientTest` key-check cases | contract | method, path, headers, verdict per status and envelope | see 11.2 |
| `KeyCheckCopyTest` | product outcome | the right line per verdict | swap Rejected and Denied |
| `ProviderConfigurationRepositoryTest` orchestration | product outcome | no write without Accepted; the stored key is what is checked | see 11.2 |

## 12. Blast radius & rollback
Touches the cloud client's request execution (a method and a nullable body, defaulting to today's values,
plus a runner extraction with the existing tests as the guard), the repository's save path (a check before
the first write), one view model mapping and one button label. Rollback is one revert; no data changes.

## 13. Ship criteria specific to THIS change
- [x] A stand-in Gemini key and a stand-in OpenAI key are refused on the emulator with the "rejected" line
      and nothing saved (Gemini 400 API_KEY_INVALID, OpenAI 401).
- [x] Airplane mode gives the "no connection" line and nothing saved.
- [x] The fake-server tests prove GET, the headers, the envelope rule and every verdict row of §7.
- [x] The device test proves no write without Accepted and that a blank draft checks the stored key.
- [x] A self-hosted save (existing config) still saves without a request (device test).

## 13.1 Found on the emulator

2026-09-02 00:20 to 00:27, Appium plus the preference file and logcat: a stand-in Gemini key was refused
with "Gemini rejected this key. Nothing was saved." (the live models endpoint answered 400
API_KEY_INVALID); with airplane mode on the line read "Couldn't check the key with Gemini: no connection.
Nothing was saved."; a stand-in OpenAI key was refused on 401. The preference file did not change on any
of the three. The repository device tests ran on the emulator: OK, 15 tests. A real Accepted verdict was
not run live: the founder allowed his vault keys, but the harness classifier blocked typing one into the
emulator; the fake-server case (200 with the list envelope) stands for it.

**Founder direction, 2026-09-02, during this phase:** the model list must come live from the provider, as
macOS does (fetch, filter, probe, recommend, cache), not from `PolishModelCatalog`. That is the next plan,
built on `ProviderKeyChecker`, and is tracked as its own issue; the catalog row `ai-model-discovery`
stays "absent" for Android until it ships.

## 14. Open questions
None the rules do not answer. Refusing on Unverified follows macOS and the "no silent bad key" goal.

## 15. Related
#61 (this), #67 (the page), #77 (the vocabulary), #81 (the Ladder's Check pill will call
`ProviderKeyChecker`), `ai-model-discovery` (the model list these endpoints return). Catalog feature
`cloud-polish`.

---

## Review log

- **Code round 2 (confirming), 2026-09-02, same session:** all three CONFIRMED; carrying a status on a
  polish() TIMEOUT changes no consumer (`PolishFailure.from` maps TIMEOUT before any status handling).
  VERDICT: CLEAN.

- **Code round 1, 2026-09-02, same session:** three findings, all adopted: the runner keeps the status it
  had already received when a later read fails (a stalled 401 is `Unverified(TIMED_OUT, 401)`; one
  existing polish timeout test now accepts the kept 200); `NotApplicable` refuses for a cloud provider
  and the device test covers it; the suite-wide `isReturnDefaultValues` (external) is removed in favour of two
  injected log sinks on the client (`logInfo`, `logWarn`) that the JVM tests pass as no-ops.

- **Grounded round 2 (confirming), 2026-09-02, same session:** PROCEED-AS-PLANNED; axes: ownership and
  mutex, runner and precedence, envelopes, Back/rotation/death, raw validation and trim, client and device
  test stageability, privacy and copy.
- **Grounded round 1, 2026-09-02, same session:** PROCEED-WITH-REVISIONS, five, all adopted: the refusal
  exception carries the provider; `Unverified` carries an existing `PolishFailure` member instead of a new
  reason enum; control characters are rejected on the raw key before the trim; Back mid-check promises
  state, not a snackbar; the cancellation case is dropped and connection-refused becomes an accept-and-close
  fake-server mode.

- **Coverage round, 2026-09-02, Codex session `01a06039-220e-71a0-adb0-5ca694a6283b`:**
  PROCEED-WITH-REVISIONS, eight drop-ins, all adopted: trim a new key once; Accepted needs 200 plus the
  provider's list envelope; transport and read failures outrank the status; the dictation path's Gemini 403
  is untouched and `fromStatus` stays private; the check moves into the repository's secret-owning
  operation behind a small `ProviderKeyChecker` seam (the client is final, so no fake subclass);
  orchestration is proven by the repository device test with a recording fake; rotation clears the key
  draft by design and Back lets the write finish; one auth-header helper and one internal runner serve
  both requests, with the full client case list in §11.3.

## Checklist for the plan author
- [x] Every claim above carries a `file:line` or names the run that produced it.
- [x] §2.5 preceded §3.
- [x] Every backticked identifier greps against the working tree or carries /.
