# Issue #307: every PostHog string value comes from its key's closed set (2026-09-24)

GitHub issue: `#307`. Tier: MEDIUM (the PostHog half of `telemetry/PayloadSanitizer.kt`, one of CLAUDE.md's three privacy enforcers; this change narrows what it admits). Status: revised after the coverage round (`307-cov`), all findings adopted; grounded round 1 (`307-g1`), all four findings adopted; round 2 (`307-g2`), the debug log's event name closed. Round 3 (`307-g3`): PROCEED-AS-PLANNED. Built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take still produces its PostHog rows; the debug build's `PostHog row <name> kept:` lines show `dictation.terminal` and `insertion.terminal` with their string keys kept, and no `PostHog value dropped` line appears.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. Today any short word-like string can ride under a PostHog field (the sanitizer accepts any `[A-Za-z0-9_.:-]{1,64}` token under an allowed key), so a dictated word that reached a telemetry field by mistake would leave the phone. After this change each field admits only the values its producer can make, so a stray word is dropped.

---

## 0. TL;DR

REF-04 of `docs/audits/2026-09-24-senior-audit.json` (`.claude/rules/kotlin-patterns.md` RULE: no-content-in-diagnostics). `PayloadSanitizer.sanitizeString` falls back to `TOKEN` for every allowed key without a declared shape. Replace that fallback with a per-key closed value set (fail closed: a string under a key with neither a set nor a shape is dropped). Sets are derived from the producing enums' `entries` wherever an enum produces the value, so a new enum member is admitted without a second edit; a value written inline today becomes a named constant used by both its emit site and its set. Three keys are not finite and get a declared SHAPE instead. Keys with no producer leave the allowlist.

Consolidation: one table of admissible string values per key (`PostHogSchema`, proposed, in `telemetry/`), read by the sanitizer; the existing bounded shapes move into it.

Prior context: #176 (the allowlist and pattern pass), #240 (SentrySchema's per-key shapes, the model for this), G2/G3 refutations in `PayloadSanitizer`'s doc (a length rule alone admits prose).

## 1. Grounding (main 31d64aa)

`PayloadSanitizer.allowedKeys` has 93 members: 19 in `boundedStringKeys` (removed) (UUID, package, label and name shapes), 29 that only ever carry numbers or booleans, 41 that can carry a string, and 4 with no producer in `app/src/main` (`asr_cold_start` (removed), `start_failure` (removed), `$sample_type`, `$sampled_events`). Producer map for the 41 (file:line per key, finite sets enumerated) was gathered read-only and is reproduced in §2's table; highlights:
- Enum-derived: `result` (`TerminalResult.wire`, `InsertionResultKind.stored`, plus the api_key literals), `reason` (`TerminalReason.name` for FAILED members, `busy`/`stale`, `DeliveryFailureReason.wire`), `asr_failure_reason`, `trigger_source`, `route_kind`, `route_reason`, `polish_reason`, `stage`, `handoff`, `route`, `clipboard_outcome`, `bubble_look`, `lesson`, `outcome`, `source_host`, `provider`.
- Literal-produced: `live_state`, `silence_stop_status`, `capture_terminal`, `input_device`, `history_save`, the ten on/off settings, `setting`, `bytes_bucket`, `action`, `model` (the two `ModelManifest` ids).
- `polish_provider` / `polish_policy`: `PolishContext.encode()`, finite (off, local, cloud-unconfigured, cloud:<PROVIDER>, cloud:SELF_HOSTED_POLISH:ollama).
- Not finite: `settings_fallback` (`exception:<Throwable class name>` inside a composite), `from`/`to` (a settings-change pair; `silence_pause_seconds` sends `Float.toString()`), `silence_pause_seconds` itself is a number except the string `unknown`.
- Uppercase values ride today (`TerminalReason.name`, provider names); the sets keep the producers' spelling.

## 2. Design

1. ONE schema map (coverage finding B): `telemetry/PostHogSchema.kt` (proposed) holds `values: Map<String, ValueRule>` where `ValueRule` is `Closed(set)` or `Shaped(regex)`; the existing UUID, package, label and name shapes move into it unchanged, so every string rule is inspected in one place. `PayloadSanitizer.sanitizeString(key, value)` applies the key's rule; a key with no rule admits NO string (the `TOKEN` fallback is deleted).
2. Sets are built from the producing expression, never retyped: enum values by the producer's own mapping over `entries` (`TerminalResult.entries.map { it.wire }`, `TerminalReason.entries.filter { it.result == TerminalResult.FAILED }.map { it.name }`, `PolishReason.entries.map { it.name }`, lowercased names exactly as the producer lowercases them). A value written inline at an emit site today (the literal-produced keys below) becomes a named constant in its producer's file, used at the emit site AND referenced by the set, so the two cannot differ.
3. Shapes for the non-finite keys: `settings_fallback` = `settings:R` or `terms:R` (exactly one reason) or `both:R:R` (exactly two), where R is `completed_without_value`, `timed_out`, or `exception:` + a Throwable class name in the shape `SentrySchema` already uses for exception types; `from`/`to` = a member of the settings-value union (the ten on/off keys' `on`, `off`, `unknown`; `auto`, `picked`; the three `BubbleLook` names; the `PolishContext` tokens) or a plain decimal (`-?[0-9]+(\.[0-9]+)?`); `silence_pause_seconds` admits the string `unknown` only (its number passes as a number).
4. `asr_cold_start` (removed), `start_failure` (removed), `$sample_type`, `$sampled_events` leave `allowedKeys` (no producer; a future producer adds its key with its rule).
5. A development-time signal for drift (grounded finding 4): `PayloadSanitizer` takes an injectable key-only drop reporter, `(String) -> Unit`, called with the KEY (never the value) when a string is dropped under a key that has a rule; a JVM test injects a recorder. `PostHogBootstrap.processProperties` wires it to `DebugLogger` in debug builds only, and in debug builds also logs `PostHog row <name> kept: <sorted kept keys>`, the observable for the emulator UAT that accepted fields stayed intact. Log the event name only when it belongs to the app's closed `AnalyticsEvent` name set (a companion `AnalyticsEvent.NAMES` listing the twelve names the subclasses pass, pinned by a source row against every `AnalyticsEvent("...")` literal); otherwise log `unknown`. Log only sorted, allowlisted property keys. Never print the raw `name` or a value (grounded round 2). Release wires neither. The shaped values keep the sanitizer's existing pattern and length pass.
6. The doc comment's token sentence is corrected (the sets keep the producers' uppercase spellings).

### The table (every string key; main 31d64aa; producers under `app/src/main/java/com/envi/wispr/`)

| key | rule | derived from |
|---|---|---|
| `result` | Closed | `TerminalResult.entries.wire`; `InsertionResultKind.entries.stored` (includes `INSERTION_INTERRUPTED`'s stored value); `success`, `failed` (api_key.changed); `valid`, `not_applicable`, `rejected`, `denied`, and `unverified_` + every `PolishFailure` name lowercased (`keyCheckToken`) |
| `reason` | Closed | FAILED `TerminalReason` names; `busy`, `stale` (refused start, DSC); `DeliveryFailureReason.entries.wire` |
| `asr_failure_reason` | Closed | `AsrFailureReason.entries.name` |
| `trigger_source` | Closed | `TriggerSource.entries.wire` |
| `route_kind`, `route_reason` | Closed | `InputRouteKind` / `InputRouteReason` entries, lowercased as `AnalyticsEvent` does |
| `live_state` | Closed | `ready`, `forced` (constants, DSC) |
| `silence_stop_status`, `capture_terminal` | Closed | `TakeFacts.silenceStatusToken` over every status int and unknown; `TakeFacts.captureEndingToken` over every `CaptureEnding` |
| `input_device` | Closed | `auto`, `picked` (`TakeFacts.inputDeviceToken`), `unknown` (`AppLaunchFacts`) |
| `polish_provider`, `polish_policy` | Closed | `PolishContext.encode()` over every context (`off`, `local`, `cloud-unconfigured`, `cloud:<Provider.name>`, `cloud:SELF_HOSTED_POLISH:ollama`); `unknown` for `polish_policy` |
| `polish_reason` | Closed | `PolishReason.entries.name` |
| `history_save` | Closed | `ok`, `failed`, `pending` (constants, DSC) |
| `stage` | Closed | `TakeStage` and `OnboardingStage` entries, lowercased |
| `handoff`, `route`, `clipboard_outcome` | Closed | `InsertionHandoff` lowercased; `InsertionRouteKind.entries.wire`; `ClipboardOutcome` lowercased |
| ten on/off settings | Closed | `on`, `off`, `unknown` (`AppLaunchFacts.onOff`) |
| `bubble_look` | Closed | `BubbleLook` lowercased, `unknown` |
| `setting` | Closed | the 14 setting names (constants, `AppViewModel`, `PolishSettingsViewModel`) |
| `lesson`, `outcome` | Closed | `PracticeLesson`/`PracticeOutcome` lowercased (no `working`); `DownloadState` lowercased |
| `model` | Closed | `ModelManifest`'s descriptor ids |
| `source_host` | Closed | `ModelSourceHost.entries.wire` |
| `bytes_bucket` | Closed | the six buckets (constants, `ModelDeliveryWorker`) |
| `provider`, `action` | Closed | `Provider` lowercased; `save`, `model_change`, `remove` (constants) |
| `settings_fallback`, `from`, `to`, `silence_pause_seconds` | Shaped | §2.3 |
| the 19 existing bounded keys | Shaped | unchanged shapes |

## 3. Tests

1. `PayloadSanitizerTest`: `hello` under `reason` is dropped; a real FAILED `TerminalReason` name passes; `hello` under every Closed key is dropped. MUTATION m1: restore the `TOKEN` fallback (RED on `hello`).
2. A closed-world row: every key in `allowedKeys` is exactly one of an EXPLICIT number-or-boolean-only list (the 29 keys), or a key with a rule in `PostHogSchema.values`; the two are disjoint and cover all 89. MUTATION m2: one string key's rule removed (RED: uncovered).
3. Producer-driven rows (coverage finding C, grounded finding 3): for each literal-backed output, one witness per output BRANCH is produced by its real producer and asserted retained by the sanitizer: `TakeFacts`' three token functions with a representative input for every branch and its unknown branch; `PolishContext.from(policy).encode()` across provider and protocol cases (never arbitrary `Cloud` combinations: `Cloud(OPENAI, true)` would encode `cloud:OPENAI:ollama`, which no real policy produces); `AppLaunchFacts`' projection over on, off and unreadable preferences; `keyCheckToken`, extracted from `PolishSettingsViewModel` as a pure internal producer, over every validation result; and each extracted emit-site constant (DSC, ViewModels, the worker), with a source row asserting each emit site uses its constant. MUTATION m3: one member dropped from a literal-backed set (RED).
4. The shapes: `settings_fallback` admits `settings:timed_out` and `both:timed_out:exception:IOException` (the producer emits `simpleName`), rejects `both:timed_out:exception:java.io.IOException`, `settings:hello world`, `settings:timed_out:timed_out` and `both:timed_out`; `from`/`to` admit `on`, `1.25`, reject `hello`. MUTATION m4: the decimal alternative removed (RED on `1.25`).
5. `TelemetryContractsTest`: its `polish_provider = "offline"` fixture becomes the real token `local` (coverage finding C: `offline` is not a value any producer emits); its snapshot rows keep passing.
6. The drop signal: with a recording reporter injected, sanitizing `hello` under `reason` reports exactly `reason`, and nothing reported contains `hello`. MUTATION m5: the reporter is handed the value (RED).

### Built as planned, with these placements

- The DSC's inline values became constants on their types, not on the owner: `live_state` and `history_save` on `TakeFacts` (beside `MANUAL_ENDING`, the facts' existing home), the refusal reasons on `AnalyticsEvent.DictationRefused`, so `PostHogSchema` reads no UI owner.
- `keyCheckToken` and the api_key actions and results moved to `providers/ui/ApiKeyTelemetry.kt`; the setting names are `AppLaunchFacts` constants, and `SETTING_NAMES` is the `setting` set.
- `silence_pause_seconds` is `Closed({unknown})`, the same rule as a shape admitting only `unknown`. `from`/`to` are one shape: the union of every setting's closed set, or a decimal.
- `PolishContext.TOKENS` lists every context `from` can make; `PostHogSchema` reads it.
- Extra mutations beyond m1 to m5: m1b (TOKEN fallback beside a rule), m3b (the unknown silence token), m6 (the debug log prints the raw name), m7 (the fallback shape takes any one or two reasons).

## Results (2026-09-24)

- Mutations: m1, m1b, m2, m3, m3b, m4, m5, m6, m7 all RED (`PostHogSchemaTest`).
- Suite: 1334 tests, 0 failures. App and androidTest build; visibility and cited-symbol checks clean.
- Emulator (debug build with the PostHog key, then the plain build reinstalled): a take into Gmail landed by COMMIT and matched; `PostHog row dictation.terminal kept:` listed `result`, `trigger_source`, `route_kind`, `route_reason`, `live_state`, `silence_stop_status`, `capture_terminal`, `input_device`, `polish_provider`, `polish_reason`, `history_save`; `insertion.terminal` kept `handoff`, `result`, `route`, `target_app`; a relaunch's `app.launched` kept all fourteen settings; no `PostHog value dropped` line.

## 4. Blast radius

A string value a producer really emits but the sets miss would now be dropped from the row (a lost field, never a leak); row 3 is the guard against that. Rollback: revert the squash commit.

## 5. Ship criteria

- [x] Rows green, m1 to m5 RED; the suite green; app and androidTest build; checks clean.
- [x] Emulator: a take's PostHog rows keep their string fields.
- [ ] Codex code review ALL-CLEAR with a confirming round.
