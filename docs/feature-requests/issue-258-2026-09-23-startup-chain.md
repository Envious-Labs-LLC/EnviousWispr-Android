# Issue #258: measure the pre-capture startup chain (2026-09-23)

GitHub issue: `#258`. Tier: MEDIUM (take facts and the analytics row; no take behaviour). Status: APPROVED after grounded round 1 (PROCEED-AS-PLANNED, its row 2 instruction adopted verbatim); earlier rounds (gate 0 `258-g0`: PROCEED-WITH-CHANGES, adopted in §3; coverage `258-cov`: all four findings adopted).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y. Three Gmail dictations by COMMIT on the emulator; the debug log's `Take terminal:` line for each carries the six new timings, and the as-built section records them with build, device and cold or warm state, labelled emulator measurements. The founder's S26 is excluded from this harness; its numbers arrive from the founder's own takes on the internal-testing build through the same analytics row.

## Preface: User Rubric

User Rubric: nothing a person sees changes; the team learns where the time between a tap and a live recorder goes, on the phone that matters.

---

## 0. TL;DR

Before capture, a take runs in order: the settings answer, the vocabulary matcher's compile (on `Default`), the polish policy read (on `IO`), a bounded wait for its journal admission, the hop to main, and the pipeline bind; live arrives later from the capture process (REF-07 of the second 2026-09-23 audit). Nothing measures these steps against the accepted command. Add six content-free durations, each relative to the accepted owner command, to `TakeFacts` and the `dictation.terminal` row. Defer both narrowing changes the audit names until S26 numbers exist, and file that decision as a follow-up issue.

## 1. Problem

Grounded by Codex (`258-g0`), re-read by Claude:
- `DictationSessionCoordinator.beginSession` (`:332-409`): admission is queued on main; then one coroutine awaits the settings answers, compiles the matcher, reads the policy, waits up to `JOURNAL_ADMISSION_DEADLINE_MS` for admission, hops to main and binds.
- `TakeFacts.liveAfterMs` starts at the recorder, not the trigger; the `recording_start` mark uses the audio process's pipeline clock; the journal's admission is a wall-clock database fact. No trigger-relative timing exists.
- `TakeJournalWriter.admit` returns a `Deferred<Boolean>` completed when `dao.admit` has landed.

## 2. Goals & non-goals

### 2.1 Goals
1. One monotonic origin per take, `host.elapsedRealtimeMs()` read right after `beginSession`'s successful `IDLE -> STARTING` transition: the durations are **relative to the accepted owner command**, not the physical trigger (they exclude the time before the Service receives the command; coverage finding 1).
2. Six nullable durations on `TakeFacts`, each milliseconds since that origin, written once when its step completes: `settingsAnswerMs` (immediately after `awaitAnswers`; a failed read still answers), `matcherReadyMs` (after the `Default` block), `policyLoadedMs` (after the `IO` block; a failed read still returns `Off`), `admissionObservedMs` (when the admission `Deferred`'s completion is observed true through `invokeOnCompletion`, registered right after the origin: the callback can follow the database write, so the name says observed, not landed; coverage finding 2), `bindRequestedMs` (on main, after the `STARTING` check, immediately before `bindPipelineServices`; a bind failure keeps it and has no live time), `liveReceivedMs` (after the owner's winning `STARTING -> RECORDING` transition on `Live`, an upper bound on the first frame: that block was written, then crossed a worker and Binder).
3. `AnalyticsEvent.DictationTerminal` carries them as `settings_answer_ms`, `matcher_ready_ms`, `policy_loaded_ms`, `admission_observed_ms`, `bind_requested_ms`, `live_received_ms`; `PayloadSanitizer.allowedKeys` admits them; the literal payload tests pin them. Durations are numbers, so content-free under the allowlist. No Sentry breadcrumb carries them, so `SentrySchema` is unchanged. The debug `Take terminal:` line (`recordTakeEnding`) adds the six numbers, so a UAT reads them without PostHog.
4. A step that never ran leaves its field null ("not measured", never zero), as every other take fact.

### 2.2 Non-goals (deferred, gate 0)
- Not awaiting journal admission before bind. Write order is preserved without the wait, but the pre-capture durability window is not: issue #176 §3.3 waits so an admitted row counts a take killed during capture. Decide with `admission_observed_ms` from real takes.
- Caching the compiled matcher. A cache in `SessionPreferencesSource` dies with each take (the Service builds it in `onCreate` and stops after the take), and the compile's cost on a real dictionary is unmeasured. Decide with `matcher_ready_ms - settings_answer_ms`.
- The first-take settings answer and its two-second fallback are unchanged.

## 3. Design

Adopted from gate 0: ship the measurement first; the emulator shows where this code spends time but cannot establish the S26 trigger-to-audio delay, so both narrowing changes wait for device data. Admission is timed where its completion is observed, not where the wait returns. Live's receipt is named for what it is, an upper bound. The Default and IO hops, the return to main and the bind are covered by the adjacent timestamps (matcher, policy, bind).

Follow-up: a GitHub issue, filed before the ship criteria are called complete, requiring S26 samples before either deferred change, "Decide the pre-capture narrowing from S26 startup timings (#258)", naming the two deferred changes and the fields that decide each.

## 4. Contract deltas
`TakeFacts` and `AnalyticsEvent.DictationTerminal` gain six fields; `PayloadSanitizer.allowedKeys` gains six keys.

## 5-9. State, consumers, failure modes
- Fields are `@Volatile`, written from the start coroutine, the journal writer's completion and main; each is written at most once per take.
- On cancellation, retain milestones already reached; later milestones remain absent. Journal admission may land independently during the settings wait. A late admission completion after the terminal row was built is simply not in the row.

## 10. Files
`telemetry/TakeFacts.kt`, `telemetry/AnalyticsEvent.kt`, `telemetry/PayloadSanitizer.kt`, `ui/DictationSessionCoordinator.kt` (the timings, an injected `admit` seam defaulting to `Telemetry.journal?.admit`, and the terminal log line); tests `TakeFactsTest`, `TelemetryContractsTest` (the literal payload and its positional constructor), `TakeJournalWriterTest` (its positional constructor), `PayloadSanitizerTest` (the witness), `DictationSessionRig` (a scriptable host clock and the `admit` seam), `DictationSessionCoordinatorTest` (coverage finding 3).

## 11. Testing
1. Rig row: a take driven to live with the rig's host clock scripted to return 0, 10, 20, ... on successive reads and an admission already completed true (so its completion is observed synchronously at registration, right after the origin). Every read in the start chain then happens in a fixed order (origin, admission, settings, matcher, policy, bind, live), so the row asserts literal offsets 10 to 60 with no real clock (coverage finding 2: an advanceable clock and a controllable admission; the matcher and policy steps need no gate because each takes exactly one read). MUTATIONS: take the origin after the settings answer; record admission when the wait returns instead of when its completion is observed.
2. Rig row: a take ended (the Service's destroy, as the rig's capture is bound from the start and a cancel would wait for a capture ending that never comes) while the settings answer is pending (silent readers, as `aSilentStoreStartsOnTheLastSnapshotAfterTheBound`) keeps the admission it observed and leaves every later milestone absent. Give the silent readers a long answer bound; cancel after the starting signal, before that bound expires (grounded round 1). MUTATION: default the durations to 0.
3. The terminal row carries the six keys with their values, and a null field is absent (literal payload, `TakeFactsTest`/`TelemetryContractsTest`). MUTATION: drop one key from `properties()`.
4. The sanitizer keeps the six keys and still drops an unknown key (`PayloadSanitizerTest`). MUTATION: drop one key from `allowedKeys`.
All rows wait on signals, never a clock.

## 12. Blast radius
One analytics row gains six numbers. Rollback: revert the squash commit.

## 13. Ship criteria
- [x] Rows 1 to 4 green, each mutation red.
- [x] Three emulator dictations by COMMIT; the six timings reported from their terminal rows.
- [x] The follow-up issue filed (#267).

## 14. Open questions
None.

## 15. Related
#176 (the take journal and its admission wait), #193 (the settings answer), REF-07 of the second 2026-09-23 audit.

## 16. As built

- `TakeFacts` gains `settingsAnswerMs`, `matcherReadyMs`, `policyLoadedMs`, `admissionObservedMs`, `bindRequestedMs` and `liveReceivedMs`; `AnalyticsEvent.DictationTerminal` carries them under the six `_ms` keys; `PayloadSanitizer.allowedKeys` admits them. `TakeContext` gains `acceptedAtMs`, the origin, so `publishLive` can time live; its description sits in the class notes, since `SessionOwnerShapeTest` reads every constructor line as a property (now seven).
- `DictationSessionCoordinator`: the origin is read right after `TakeFacts` is built in `beginSession`; the admission comes from an injected `admitTake` (default `Telemetry.journal?.admit`) whose completion is observed through `invokeOnCompletion`; settings, matcher, policy and bind are written after their steps; live after the winning transition in `publishLive`. `recordTakeEnding`'s `Take terminal:` line appends the six numbers.
- The rig: `FakeHost.clock` scripts every clock read; `coordinator(admit = ...)`. Row 2 ends the take with the Service's destroy (INTERRUPTED_STARTING) rather than a cancel, because the rig's capture is bound from the start and a cancel there waits for a capture ending that never comes.
- Guards and constructors updated: `AutoPasteWiringTest` (the `TakeContext` call), `SessionOwnerShapeTest` (seven properties), both positional and the named `DictationTerminal` calls in `TelemetryContractsTest` and `TakeJournalWriterTest`.
- Receipts 6 of 6 RED (`docs/audits/2026-09-23-258-mutation-receipts.txt`); full suite 1263, 0 failures.
- Emulator measurements (not the S26): debug build at this branch on emulator-5554, each take cold (the app's processes stopped before it), three Gmail dictations by COMMIT with the editor's text exact, in ms since the accepted command:

| take | settings | matcher | policy | admission | bind | live |
|---|---|---|---|---|---|---|
| 1 | 10 | 12 | 13 | 5 | 13 | 370 |
| 2 | 23 | 24 | 24 | 3 | 25 | 454 |
| 3 | 10 | 16 | 17 | 10 | 17 | 344 |

  On the emulator the chain before bind is about 13 to 25 ms and bind to live dominates. Two earlier runs of the same script each lost their first cold take to the known emulator empty-take flake (it also happens on main); the run above had none. The deferred decisions are #267.
