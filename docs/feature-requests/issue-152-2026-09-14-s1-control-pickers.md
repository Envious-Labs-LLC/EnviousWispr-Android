# Issue #152 — S1-mini Tone, Structure and Context pickers — 2026-09-14

GitHub issue: `#152`. Tier: MEDIUM. Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/java/com/envi/wispr/{polish,providers,ui}/`, `app/src/test/`, `app/src/androidTest/`.

**PAR rows closed:** `PAR-065` partially (the "formatting intent" half: the user's tone, structure and
context intent reaches the local model). The context and vocabulary halves stay open (#88).

**Hardware UAT:** Y. On the S26, with AI Polish set to This phone: the founder sets Structure to prose,
dictates "buy milk, buy eggs, buy bread" into Google Keep, and the text lands as one sentence with no
bullets; he sets Structure back to lists, dictates the same, and a bulleted list lands. He sets Context to
email, dictates "hi Priya, thanks for the update, I will review tomorrow, best, Saurabh" into Gmail, and the
greeting and sign-off land on their own lines. Each change applies to the NEXT recording, never a running
one.

## Preface — User Rubric

1. **Who.** Diana Foster, senior PM, on her phone between meetings. Thirty seconds ago she read a Slack
   thread; thirty seconds from now she wants her reply sent without typing.
2. **Why.** "I do not want my Slack replies to read like a memo, and I do not want my email replies to read
   like a text."
3. **How invoked.** Voluntary, once, on the AI Polish tab. She sets Tone to casual for a Slack day, or
   Context to email for an inbox-clearing session, and forgets it. It fires on every dictation after that.
4. **Which app.** Slack, Gmail, Notion, Google Keep. Slack tolerates a wrong register badly (a formal
   reply reads cold); Gmail tolerates a missing greeting line badly.
5. **Natural input.**
   - "hey can you push the standup to two thirty tomorrow thanks"
   - "hi Marcus thanks for the draft I have three comments first the intro is long second the data
     section needs a chart third the close is great best Diana"
   - "reminder for me pick up the dry cleaning call the dentist book the flight"
   - "so the plan is we ship the beta friday and then we do the retro monday"
   - "quick note I am running five minutes late"
6. **Success.** Her Slack reply reads the way she talks. Her Gmail reply has "Hi Marcus," on its own line
   and "Best, Diana" at the bottom. She notices nothing else changed.
7. **Wrong-not-broken.** She sets Context to email and a Slack message gains a sign-off block. On the
   shipped weights that needs a spoken sign-off, so it is rare, but if it happens she stops trusting the
   email pick.
8. **Power-user hack.** She would say "new paragraph" and "bullet" out loud, or set polish to a cloud
   provider and paste a tone instruction into the custom words. Neither is the designed path.
9. **Control ladder.** Off (deterministic cleanup only, already exists), a fixed pick per axis (this plan),
   automatic per-destination selection (a later upgrade, catalog decision 2026-09-04). This plan ships the
   middle rung and leaves the top rung to telemetry, exactly as the Mac did.

### Cross-persona check

- **Priya Ramachandran** (SWE): sets Tone to casual for Slack, wants no bullets in code comments; Structure
  prose fixes it. Approves.
- **Marcus Weber** (writer): Structure prose keeps his spoken list as sentences. Approves; he wanted this
  most.
- **Diana Foster** (PM): above.
- **Dr. Elena Vasquez** (privacy): the picks never leave the phone. Indifferent.
- **Aaron Wu** (RSI): three more taps once, no modal. Indifferent.
- **Meera Patel** (parent): never opens the tab; defaults keep today's behaviour. Indifferent.
- **Frank Chen** (72): "≤3 settings". Three more pickers under This phone is a tension. Resolved in §3:
  the pickers live inside the This phone branch under the model card, collapsed with the rest of that
  branch when Off or Cloud is selected, so his first screen is unchanged.

---

## 0. TL;DR

**Consolidation:** the dominant root is "who decides the S1 control line". Today `S1PromptBuilder` decides
it alone with a literal. After this change the one owner is `S1ControlSettings` (proposed):
`S1PromptBuilder` composes from it, `PolishPolicy` carries it, `ProviderConfigurationRepository` stores
it, `PolishLadder` labels it. The consolidation site is the single literal at `S1PromptBuilder.kt:15`,
which is deleted; no second site composes the line.

The Android app writes `[Styling: semi-formal] [Structure: lists] [Context: general]` as a constant on
every local S1-mini polish (`S1PromptBuilder.buildUserPrompt`). The founder decided on 2026-09-04 that the
three axes are user settings on every platform that runs S1-mini, and macOS shipped them the same day.
This change adds three closed enums whose raw values ARE the trained wire tokens, stores the picks in the
provider-configuration preferences, carries them on the existing per-session `PolishPolicy` snapshot to
the `:polish` process, composes the control line from them, and shows three pickers under This phone on
the AI Polish tab. Defaults equal today's constants, so an untouched install behaves byte-identically.
Evidence: JVM tests on decode, parcel round trip and prompt composition; a device run on the S26 in Keep
and Gmail per the Preface.

## 1. Problem

`app/src/main/java/com/envi/wispr/polish/S1PromptBuilder.kt:15` returns the control line as a string
literal. Nothing on the phone can change it. The founder asked on 2026-09-14 for the Mac's pickers.
Catalog row `s1-mini-polish` / `android` records the gap as unread; this plan closes it.

## 2. Goals & non-goals

### 2.1 Goals

1. Three pickers on the AI Polish tab, visible only under This phone: Tone (Casual, Semi-casual,
   Semi-formal, Formal), Structure (Prose, Lists), Context (General, Email). Labels and helper copy are
   the Mac's, verbatim from the catalog `user_copy` rows.
2. The picked values reach the control line of the next local polish, exactly as
   `[Styling: <tone>] [Structure: <structure>] [Context: <context>]`, and nothing outside the trained sets
   can reach the wire.
3. Picks are frozen per recording: a change mid-recording applies to the next one.
4. Defaults are semi-formal, lists, general. An install that never opens the pickers produces the same
   prompt bytes as today.

### 2.2 Non-goals

- Automatic per-destination selection (email when Gmail is focused). Catalog decision 2026-09-04 names it
  a later upgrade over the pickers.
- Any effect on cloud polish (OpenAI, Gemini, Claude, self-hosted). The cloud prompt is unchanged.
- Sending the identical S1 prompt to a self-hosted Ollama running S1-mini, which the Mac does. Android
  has no S1-detection on the self-hosted path today; separate issue if wanted.
- Telemetry on which axis users change. Stage 2.
- Crash-recovery spool. Android has none (`grep -rli spool app/src/main` is empty), so there is nothing to
  record the picks into.
- Writing-style presets. Retired product-wide 2026-08-30; this is a different shape (trained inputs, one
  engine).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

The thing being changed is the S1 control line. Today:

- Composed: `S1PromptBuilder.buildUserPrompt(rawText)` at
  `app/src/main/java/com/envi/wispr/polish/S1PromptBuilder.kt:9-16`, a literal.
- Consumed: `PolishService.polishWithS1` at `app/src/main/java/com/envi/wispr/polish/PolishService.kt:463`,
  the only caller (`grep -rn "S1PromptBuilder" app/src/main` → `PolishService.kt:463,464` and the file
  itself; `CustomWordsStore.kt` calls only `sanitizeCustomWords`).

After the change the picks travel this path:

1. **Producer:** the AI Polish tab (`PolishScreen`, `app/src/main/java/com/envi/wispr/ui/PolishScreen.kt:190`
   `RungOne.THIS_PHONE` branch) calls a new view-model method through `updateProviderSettings`
   (`AppViewModel.kt:676`), the same mutex-serialised write path `setPolishMode` uses (`AppViewModel.kt:447`).
2. **Owner (persistence):** `ProviderConfigurationRepository` writes three string preferences in the same
   `SharedPreferences` file as `KEY_MODE` (`ProviderConfigurationRepository.kt:269-274`), with
   `commit()` checked like `setMode` (`:176`).
3. **Snapshot:** `ProviderConfigurationRepository.loadPolicy()` (`:103`) reads ONE `preferences.all`
   snapshot and `decodePolicy` (`:235`) maps `PolishMode.OFFLINE_S1 -> PolishPolicy.LocalS1`. The new
   fields are decoded from that same snapshot, so mode and picks can never come from two different reads.
4. **Latch:** `DictationSessionService.kt:460` calls `loadPolicy()` once at session start and stores it in
   `SessionPreferences.policy` (`:145`); `EngineWarmUp.kt:49` calls it for `warmUpWithPolicy`. Both are the
   only callers (`grep -rn "loadPolicy()" app/src/main` → 3 hits incl. the definition).
5. **Process boundary:** `PolishPolicy` is a hand-written `Parcelable` (`PolishPolicy.kt:40-72`) sent on
   `IPolishService.polishRequest` and `warmUpWithPolicy` (`PolishService.kt:116-123,162`).
6. **Consumer:** `PolishService.run` (`:330`, the `when` at `:346`) branches on `PolishPolicy.LocalS1` and calls
   `polishWithS1` (`:459`), which calls `S1PromptBuilder.buildUserPrompt` (`:463`).

Every hop is a plain in-process value or the one binder call, so the interception point (the policy
snapshot) observes the picks on every path.

### 2. Find the existing authority before proposing one

Capability: "a per-session snapshot of polish choices that crosses to `:polish`". Owner:
`PolishPolicy` + `ProviderConfigurationRepository.loadPolicy` (#69, `polish-engines.md` FACT:
the-engine-holds-no-settings). Not a new authority.

Capability: "closed set of trained tokens". `grep -rn "semi-formal\|Styling:" app/src/main` → only
`S1PromptBuilder.kt:15`. No enum exists. `new authority proposed`: `S1ControlSettings` (proposed) with
three enums, mirroring the Mac's `S1ControlSettings.swift`.

Capability: "a segmented single-choice control on a settings screen". `grep -rn "SegmentedButton\|FilterChip"
app/src/main` → `DictionaryScreen.kt:657` uses `FilterChip`; no segmented row exists. The Mac uses a
segmented picker. Android uses `FilterChip`s in a row, matching the Dictionary precedent.

### 3. Read prior attempts and live direction

- **Catalog decision 2026-09-04:** the three axes are user settings, closed enums, defaults are the
  shipped constants, automatic selection is a later upgrade. Binding; this plan follows it exactly.
- **Catalog decision 2026-08-30:** no writing-style presets on any platform. Not in conflict: presets were
  a cross-provider UI that most prompt builders ignored; these are one engine's trained inputs.
- **Issue #1** (closed, not planned): the presets. Not reopened.
- **Session log 2026-09-02 "Prompt parity check":** S1-mini keeps its training prompt by design. This plan
  keeps the training prompt shape and changes only the values inside the trained sets.
- **`S1PromptBuilder.kt:10-14` comment:** custom terms are deliberately not appended (off-distribution
  input hallucinates). Unchanged.
- **`polish-engines.md` FACT: the-engine-holds-no-settings:** the engine reads no preference; the session
  owner latches one snapshot. This plan puts the picks on that snapshot for exactly that reason.
- **Mac measurements (catalog):** told lists, 104/114 list cases format and 23/1,348 non-list cases
  over-trigger; told prose, zero lists. Email context changed nothing on a note-to-self or a code comment.

### 4. Lifecycle, trust and process boundaries

| Boundary | Current | Planned |
|---|---|---|
| App process → `:polish` | `PolishPolicy` parcel, tag byte then per-variant fields | `TAG_LOCAL_S1` followed by three strings; writer and reader are one class in one APK (§2.5.5) |
| Live settings vs latched session | Policy latched at `STARTING` (`DictationSessionService.kt:460`) | Same; picks are part of the latched object |
| Warm-up vs request | `warmUpWithPolicy` compares `policy == PolishPolicy.LocalS1` (`PolishService.kt:163`) | `is PolishPolicy.LocalS1`; warm-up ignores the picks |
| Stale instrumentation APK | `androidTest` references `PolishPolicy.LocalS1` as an object | Test APK is compiled against app classes and loads them from the app APK at runtime; a stale test APK fails with a link error in the test, never in production. The parcel format is always the installed app's. |
| Preference absent (fresh install, or upgrade from a build before this) | n/a | Each axis decodes to its default; an unparseable value also decodes to the default (same shape as `decodeMode`, `:244`) |
| Write failure | `setMode` throws on a failed `commit()` (`:176`) | Same; the tab shows the write's error under the This phone branch |

### 5. Prove the high-risk premises

- **The control line is composed in exactly one place.** `grep -rn "Styling:" app/src/main` → one hit,
  `S1PromptBuilder.kt:15`. ✔
- **`loadPolicy` has two callers.** Pasted above. ✔
- **`PolishPolicy.LocalS1` sites (closed world: the compiler).** `grep -rn "LocalS1" app/src --include='*.kt'`:
  main → `PolishWatchdogBudget.kt:16`, `ProviderConfigurationRepository.kt:237`, `PolishContext.kt:47`,
  `PolishReason.kt:94`, `PolishService.kt:151,163,188,346`, `PolishPolicy.kt:25,42,64`; test →
  `PolishWatchdogBudgetTest`, `PolishContextTest`, `PolishReasonTest`, `PolishPolicyTest`; androidTest →
  `ProviderConfigurationRepositoryTest.kt:291,306`, `VoicePipelineDeviceTest.kt:97,139`,
  `PolishServiceDeviceTest.kt:51,76`. Every one is enumerated in §6. Turning the `object` into a
  `data class` makes the compiler fail every `==`/`when` site not updated, which is the enumeration
  guarantee.
- **No default argument on the new field.** A Kotlin default hides call sites from grep
  (`validation-discipline.md` FACT: silent-empty-traps). `LocalS1(control: S1ControlSettings)` takes the
  value explicitly; the ONE place that supplies the default is `decodePolicy`.
- **The trained token strings.** From the Mac's `S1ControlSettings.swift:23-46`: casual, semi-casual,
  semi-formal, formal; prose, lists; general, email. The Android constant already uses
  `semi-formal`, `lists`, `general`. ✔
- **No cross-version parcel exists.** The only separately installed binder client is the instrumentation
  APK (`architecture-rules.md` RULE: aidl-is-append-only), and it loads `PolishPolicy` from the app under
  test, so writer and reader are one class. The parcel change is therefore not a compatibility question
  and the plan makes no truncated-parcel claim (grounded review finding 3, adopted by deletion).
- **Tone label.** The Mac labels the Styling axis "Tone" on screen (`AIPolishSettingsView.swift:427`) while
  the wire token stays `Styling`. Android does the same.

No Codex problem-only consult: every premise above is a grep result, not a lifecycle claim.

## 3. Design

**Type.** `S1ControlSettings` (proposed) in `com.envi.wispr.polish`: three enums `S1Styling` (proposed), `S1Structure` (proposed),
`S1Context` (proposed) with a `token` property that is the wire string, a data class holding one of each,
`DEFAULT` (proposed) = (semiFormal, lists, general), and `controlLine()` (proposed) returning the bracketed line. This is the
one place the line is composed; `S1PromptBuilder.buildUserPrompt(rawText, control)` prepends
`control.controlLine()`.

**Policy.** `PolishPolicy.LocalS1` becomes `data class LocalS1(val control: S1ControlSettings)`. Parcel:
`TAG_LOCAL_S1` then three `writeString(token)`. Read: `readString()` each, mapped by token with the
default for null or unknown. The tag byte and every other variant are untouched, so the wire is
append-only within the variant.

**Persistence.** Three keys in the provider-configuration preferences, `s1_styling` (proposed), `s1_structure` (proposed) and
`s1_context` (proposed), storing the enum `name`. `decodePolicy` reads them from the same `values` map it already
decodes mode from. A new `setS1Control` (proposed) writes all three in one `commit()`.

**Screen.** `ProviderSettingsUiState` gains `s1Control` (proposed) populated by
`refreshProviderSettings`. `PolishScreen`'s `RungOne.THIS_PHONE` branch gains a card after `S1Card`:
eyebrow "WRITING STYLE", the Mac intro sentence, then three rows, each a label, a wrapping
`FlowRow` (external, Compose foundation, BOM 2026.02.01) of `FilterChip`s (one per enum member,
selected = current value), and the Mac helper sentence. The Dictionary precedent (`DictionaryScreen.kt:655`)
uses a plain `Row`; the wrap is this card's own, needed for four Tone chips at phone width. A tap calls
`onSetS1Control` (proposed) with the changed copy through the tab's existing write tracking
(`PolishScreen.kt:106-160`): `start(WriteKind.S1_CONTROL) { onSetS1Control(next) }` records the saveable
`target` and `targetKindName`, the chips are disabled while `saving` is true, completion is observed
through `settings.writeSequence` exactly as a mode tap is, and a failure's `settings.error` is shown
under this card when the recorded kind is `S1_CONTROL` (proposed). No second tracking mechanism.

**Alternatives rejected.**
- Store in `AppPreferences` (DataStore): the policy snapshot is one `preferences.all` read of the provider preferences
  so mode and selection cannot disagree; a second store would reintroduce the two-read gap #69 closed.
- Let `:polish` read the preferences itself: ruled out by #69 (stale per-process cache).
- Keep `LocalS1` an `object` and send the picks as a separate AIDL argument: a new binder parameter is
  a signature change (`architecture-rules.md` RULE: aidl-is-append-only) and splits one decision across
  two values.
- `SingleChoiceSegmentedButtonRow` (external): four segments with "Semi-formal" and "Semi-casual" do not fit a
  400 dp row; chips in a `FlowRow` (external) wrap.

## 3b. Ownership justification

This will live on `PolishPolicy` because it is the one object the session owner latches per recording
and the engine reads per request, which is exactly the freeze-per-recording behaviour the Mac has; the
alternative was a separate preference read in the engine, but #69 measured that a live `:polish` process
never sees a changed preference.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `PolishPolicy.LocalS1` | `object` → `data class LocalS1(control)` | "polish locally" now also says HOW. Equality is structural; two sessions with different picks are different policies. |
| `PolishPolicy` parcel | `TAG_LOCAL_S1` gains three trailing strings | Only `PolishPolicy.CREATOR` reads it. Writer and reader are always the same installed APK's class (the instrumentation APK loads app classes from the app under test), so no cross-version parcel exists and no compatibility claim is made. A null or unknown token decodes to the default, which the writer never produces. |
| `S1PromptBuilder.buildUserPrompt` | gains `control` parameter | The control line is the caller's choice, never the builder's. |
| `ProviderConfigurationRepository` | `+setS1Control`, `decodePolicy` reads three keys | A stored pick is part of the policy; absent reads as default. |
| `ProviderSettingsUiState` | `+s1Control` | The tab renders the persisted picks, never a local draft. |
| `PolishScreen` | `+onSetS1Control` callback, `WriteKind.S1_CONTROL` | One more write kind the tab can wait on. |

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every site that constructs `PolishPolicy.LocalS1` | `ProviderConfigurationRepository.decodePolicy` (main), `PolishPolicyTest`, `PolishContextTest`, `PolishReasonTest`, `PolishWatchdogBudgetTest`, three androidTests (§2.5.5). All pass the explicit default. |
| Every site that compares against `LocalS1` | `PolishService.kt:151,163,188,346`, `PolishContext.kt:47`, `PolishReason.kt:94`, `PolishWatchdogBudget.kt:16`, `ProviderConfigurationRepositoryTest.kt:291,306`. `==` becomes `is`; `when` arms become `is`. |
| Every reader of the provider preferences map | `decodeMode`, `decodeSelection`, `decodePolicy`, `loadMode`, `load`, `storedProviders` (`ProviderConfigurationRepository.kt`). Only `decodePolicy` gains keys; the others ignore unknown keys. |
| Every writer that clears provider preferences | `clearSelection` (`:205-215`) removes provider/model/endpoint/protocol and sets mode. It must NOT remove the S1 keys: switching Cloud → This phone keeps the user's picks. Enumerated, and the plan leaves them. |
| Session states that read `SessionPreferences.policy` | Latched at `STARTING` (`DictationSessionService.kt:460`); read at polish request. A pick change during `RECORDING` is invisible until the next `STARTING`. That is the intended freeze. |
| Warm-up | `EngineWarmUp.kt:49` sends the policy; `warmUpWithPolicy` only checks `is LocalS1`. Picks are irrelevant to loading weights. |
| Process death of `:polish` mid-request | Existing: fallback to deterministic text. The picks are in the request parcel, so a restarted engine gets them again on the next request. Unchanged. |
| Upgrade from a build before this | Keys absent → defaults → prompt bytes identical to today. |
| Test fixtures that wipe the preferences file | `ProviderConfigurationRepositoryTest.kt:25,48` clear the whole file between cases (coverage review). By design; each case then reads defaults, which is the fresh-install path. No production path clears the file. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `LocalS1` data class | `PolishService.kt:151,188` `tracksLocal`/`armed` | `== LocalS1` | `is LocalS1` | Y | compile + `PolishServiceDeviceTest` |
| | `PolishService.kt:163` warm-up | `== LocalS1` | `is LocalS1` | Y | compile |
| | `PolishService.kt:346` pipeline `when` | arm on object | `is LocalS1 ->` and pass `policy.control` to `polishWithS1` | Y | `S1PromptBuilderTest`, device run |
| | `PolishContext.kt:47` | arm on object | `is LocalS1 -> Local` | Y | `PolishContextTest` |
| | `PolishReason.kt:94` | arm on object | `is LocalS1, is Cloud ->` | Y | `PolishReasonTest` |
| | `PolishWatchdogBudget.kt:16` | arm on object | `is LocalS1` | Y | `PolishWatchdogBudgetTest` |
| | `ProviderConfigurationRepository.kt:237` | returns object | `LocalS1(decodeS1Control(values))` | Y | `PolishPolicyTest` |
| | 3 androidTests | pass object | pass `LocalS1(S1ControlSettings.DEFAULT)` | Y | compile |
| Parcel | `PolishPolicy.CREATOR` | reads tag only | reads three strings, default on null/unknown | Y | new JVM parcel round-trip test (Robolectric absent → androidTest `PolishPolicyParcelTest`, see §11) |
| `buildUserPrompt(raw, control)` | `PolishService.polishWithS1` | one arg | two args | Y | `S1PromptBuilderTest` |
| `+s1Control` on UI state | `PolishScreen` | n/a | render chips from it | Y | emulator UAT |
| `+setS1Control` | `AppViewModel` | n/a | `updateProviderSettings { repo.setS1Control(it); "" }` | Y | emulator UAT |
| `AppShell.kt:382` | wiring | n/a | pass `onSetS1Control` | Y | compile |
| `TranscriptEntity.polishContext` | history | stores `PolishContext` token `local` | unchanged; picks are not recorded | N | enumerated |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Preference `commit()` false | `setS1Control` | tab chip tap | The tab's existing generic failure sentence, "Could not update AI Polish settings" (`AppViewModel.kt:718`), under the card; the chips are refreshed from the repository's read-back (`refreshProviderSettings` re-reads the store, `:714`), so they show whatever the store now holds. Persistence is NOT confirmed and the plan promises neither that the old value survived nor that the new one did | whatever the store read back | tap again |
| Stored value unparseable | `decodePolicy` | session start | nothing; default applies | left as is | none needed |
| Model not loaded | existing `LOCAL_NOT_READY` | engine | existing deterministic fallback | n/a | existing |

The tab's existing write-failure copy is reused verbatim; no new sentence. Helper sentences under the
chips are the Mac's four `user_copy` rows, verbatim, which already satisfy the no-dash rule.

## 8. Caller-visible signals audit

| Field | Signal | Reader |
|---|---|---|
| `LocalS1.control` | which trained tokens the NEXT polish uses; equality between two `LocalS1` values means the same prompt bytes | `PolishService`, tests |
| absent preference key | "user never chose" and reads as default; indistinguishable from an explicit default pick, by design (the Mac is the same) | `decodePolicy` |
| `ProviderSettingsUiState.s1Control` | the PERSISTED picks; the chips never show an unsaved draft | `PolishScreen` |
| `WriteKind.S1_CONTROL` | which card the last write error belongs under | `PolishScreen` |

Staleness: not present in this change (the snapshot is re-read at every session start).

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| unparseable or absent pick | `S1ControlSettings.DEFAULT` | the constants that ship today | they are the values the app has always sent | token in the enum | n/a, DEFAULT always qualifies | `decodePolicy`, `CREATOR` |
| failed write | current repository read-back; persistence unconfirmed | `refreshProviderSettings` re-reads the store | reflects the store's current readable state, not a guaranteed rollback | n/a | n/a | `PolishScreen` |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/polish/S1ControlSettings.kt` (proposed, new): three enums with
  `token` (proposed), `fromToken` (proposed), default on miss, the data class, `DEFAULT`, `controlLine()`. Label text lives in
  `PolishLadder` beside the other tab copy, not here (the engine process never needs labels).
- `S1PromptBuilder.kt`: `buildUserPrompt(rawText, control)`; the literal becomes `control.controlLine()`.
  The comment about custom terms stays; the comment explaining why `lists` was chosen moves to the enum.
- `PolishPolicy.kt`: `LocalS1` data class; parcel write/read as §3.
- `PolishService.kt`: four sites as §6; `polishWithS1(rawText, control, cooperativeMs, record)`.
- `PolishContext.kt`, `PolishReason.kt`, `PolishWatchdogBudget.kt`: `is` arms.
- `ProviderConfigurationRepository.kt`: three keys, `setS1Control`, `decodeS1Control(values)` (proposed),
  `decodePolicy` uses it. `loadS1Control()` (proposed) for the screen refresh.
- `AppViewModel.kt`: `s1Control` on the state, `refreshProviderSettings` populates it, `setS1Control(control): Int`.
- `PolishLadder.kt`: labels for each enum member (a total `when`, no `else`), the intro and three helper
  sentences.
- `PolishScreen.kt`: `WriteKind.S1_CONTROL`, `S1ControlCard` (proposed) after `S1Card` inside
  `RungOne.THIS_PHONE`, `onSetS1Control` parameter.
- `AppShell.kt`: wire the callback.
- Tests: §11.

## 11. Testing

1. **Class.** `S1ControlSettingsTest` (proposed) (product outcome: the line bytes for every member; when it fails the
   user's pick is not what reaches the model). `PolishPolicyTest` additions (product outcome: decode of
   absent, valid and unknown keys). `PolishPolicyParcelTest` (proposed) (androidTest; harness contract on the parcel:
   when it fails the engine polishes with the wrong tone). `S1PromptBuilderTest` update (drift guard on the
   exact prompt bytes for the default, proving the untouched-install promise). `PolishLadderTest` (drift
   guard: every enum member has a label and no user string contains a dash).
2. **Revert that turns it red.** Replace `control.controlLine()` with the old literal → every non-default
   row in `S1ControlSettingsTest` and the pipeline test fails. Drop the three `readString` calls → parcel
   test fails on a non-default policy. Drop the keys from `decodePolicy` → `PolishPolicyTest` non-default
   row fails. Each revert is performed once during the build and the red is recorded in the validation
   run.
3. **Not tested.** Compose rendering of the chips (emulator UAT covers it; a Compose UI test would assert
   the harness). The model's obedience to the tokens (the Mac measured it; Android hardware UAT observes
   it).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (polish), but observed through the heart (insertion into Keep and Gmail).
- **Recipe:** `device-testing.md` local-polish dictation recipe, with the three scenarios in the Preface.
- **Expected observation:** the inserted text in Keep (prose vs bullets) and Gmail (greeting on its own
  line). Oracle: the target field's content read by `wispr-eyes`, not the log line. Plus the `:polish`
  log line printing the control line it used, which must match the picks.
- **Phone state to restore:** the three picks back to defaults, polish mode back to what it was.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `S1ControlSettingsTest` | product outcome | every enum member composes its trained token | literal line |
| `PolishPolicyTest` +3 | product outcome | absent → default, stored → value, unknown → default | keys dropped |
| `PolishPolicyParcelTest` (androidTest) | harness contract | non-default picks survive the parcel | strings not written |
| `S1PromptBuilderTest` | drift guard | default prompt bytes unchanged | any default change |
| `PolishLadderTest` | drift guard | total labels, no dashes | a member without a label |
| `ProviderConfigurationRepositoryTest` `:291,306` | existing, updated | `loadPolicy()` returns `LocalS1(DEFAULT)` on a fresh store | n/a, compile-forced |

## 12. Blast radius & rollback

- Touched: `app` module, `polish`, `providers`, `ui` packages. AIDL interface files untouched (the
  parcelable's Kotlin body changes; the `.aidl` declaration does not).
- Not touched: `:asr`, `:audio`, capture, insertion, cloud prompt builders, history schema, onboarding.
- Revert: `git revert` of the one squash commit. Stored picks become unread keys and are harmless.

## 13. Ship criteria specific to THIS change

- [ ] Three pickers appear under This phone on the AI Polish tab and nowhere else.
- [ ] With every pick at default, the `:polish` log shows the exact line the app sent before this change.
- [ ] Structure prose: "buy milk, buy eggs, buy bread" lands in Keep as one sentence. Structure lists:
      the same lands as bullets.
- [ ] Context email: a dictated greeting and sign-off land on their own lines in Gmail.
- [ ] A pick changed during a recording does not affect that recording.
- [ ] Founder phone pass on the Play internal build.

## 14. Open questions

None the rules do not answer.

## 15. Related

#152, #69 (policy snapshot), #81 (the Ladder), #88 (vocabulary in cloud prompt), #1 (retired presets),
catalog `s1-mini-polish`, `writing-style-presets`, `PAR-065`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it
