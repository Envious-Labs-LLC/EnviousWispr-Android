# Issue #234 — A broken polish connection never costs the user their words — 2026-09-23

GitHub issue: `#234`. Tier: LARGE (the heart path's handling of a limb failure). Status: DRAFT (coverage round adopted; grounded rounds 1 to 3 adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y (the heart). Success for a person: with the polish process refused or killed, a dictation still lands the words, cleaned of fillers and punctuated, with the existing "polish did not run" notice; with polish healthy, a dictation lands polished text exactly as today. Run on the emulator through `wispr_eyes.dictate_emulator`, killing `:polish` with `adb shell am kill` style process control only through a harness call or a debug receiver if one exists (else recorded NOT RUN with the JVM rows as the proof); the founder's phone pass is queued.

## Preface — User Rubric

User Rubric: the words always land. Founder decision 2026-09-23: polish is a core part of the product and stays required for setup and on by default (#238 closed); when polish breaks during one take the customer still gets their words, the failure is logged loudly enough to fix, and the fix ships fast.

---

## 0. TL;DR

Today a polish bind failure ends the take (`POLISH_BIND_FAILED`), and the polish process dying before speech answers ends it too (`POLISH_PROCESS_DIED`, History `asr_error`), although the speech result would have arrived. REF-01 of the 2026-09-23 senior audit (`architecture-rules.md` FACT: heart-and-limbs, RULE: isolate-limbs). The owner already has the right path: when `pipeline.polish` is null at `polishAndPublish` it publishes the deterministic fallback with `PolishReason.SERVICE_UNAVAILABLE`. This change routes both failures into that path, latches the loss for the take, and raises each as a defect so it is fixed, not merely counted.

## 1. Problem

- `PipelineBindings.bind` returns `POLISH_BIND_FAILED` when `bindService` for `PolishService` is refused; `DictationSessionCoordinator.bindPipelineServices` ends the take through `handleServiceFailure` before capture starts.
- `DictationSessionCoordinator.onPolishDisconnected` in PROCESSING with a blank `rawTranscript` (speech not answered yet) commits `POLISH_PROCESS_DIED`, marks History `asr_error` and ends the take; the speech answer that follows is dropped.
- Both reasons today reach telemetry as breadcrumbs only (`TelemetryChannels.of(TerminalReason)`), and the polish reasons `SERVICE_UNAVAILABLE` and `SERVICE_DIED` are breadcrumbs too, so a broken polish process is never raised as an error.

## 2. Goals & non-goals

### 2.1 Goals
1. A polish bind refusal does not stop the take: `bind` reports audio and speech only; polish availability is its own fact (`polishBound` false, polish link null). The take records, transcribes and publishes the deterministic fallback with `SERVICE_UNAVAILABLE`.
2. Latch and report first-time disconnects in STARTING, RECORDING, or PROCESSING. Ignore a first-time callback in CANCELLING, FINISHING, or ERROR; keep an earlier observed failure recorded. A disconnect before the request is sent latches `polishLost` for the take; the take continues; at `polishAndPublish` a lost or null polish publishes the fallback (`SERVICE_DIED` when it died, `SERVICE_UNAVAILABLE` when it never bound). A disconnect after the request is open keeps today's `publishFallback(... SERVICE_DIED)`.
3. A reconnect during the same take does not un-latch it: the take uses the fallback; the next take binds fresh.
4. The user sees the existing polish failure notice (`PolishFailure.UNEXPECTED`, through `PolishPublicationFacts.notice`), unchanged copy. Assert the existing notice for Local and Cloud fallback publication, and no notice under OFF.
5. Logged to be fixed: the bind refusal raises new `AppDefect.PolishServiceUnavailable` (proposed) and the disconnect raises new `AppDefect.PolishServiceDied` (proposed), and a thrown request raises new `AppDefect.PolishCallFailed` (proposed), each once per take at the moment it is observed, with the take id, and the bind refusal also writes one warning to the take log. The publication of a `SERVICE_UNAVAILABLE` or `SERVICE_DIED` fallback stays a breadcrumb, so a take raises one defect, not two.
6. `TerminalReason.POLISH_BIND_FAILED` (removed) and `TerminalReason.POLISH_PROCESS_DIED` (removed) go with their `TakeNotices` lines, `TelemetryChannels` arms and `PipelineController.BindResult.POLISH_BIND_FAILED` (removed). Remove the emitters and enum members without rewriting historical journal strings (`TakeJournal.kt`, `TakeJournalWriter.kt` store the names; no parser reads them back in app code). Document the build/version boundary where terminal failures become completed takes: the new completed takes carry `polish_reason=SERVICE_UNAVAILABLE` or `SERVICE_DIED`; historical terminal reason strings remain stored. State this exact casing in the PR and telemetry note (`telemetry.md`); downstream PostHog queries are UNCHECKED and the PR says so rather than claiming wire compatibility.

### 2.2 Non-goals
- No change to readiness, setup, the polish default or the Off option (#238 decision).
- No change to the warm-up call (#236) or the History wait (#235); both are their own issues.
- No change to healthy-polish behaviour. Keep the ledger API and watchdog deadline; coordinate every claim site with `PolishDecision` under `polishSubmissionLock`.

## 2.5 Grounding brief

Grounded by Codex (`234-g0`) and re-read by Claude: the null-service fallback at `polishAndPublish` (`if (service == null) publishFallback(rawText, takePreferences, PolishReason.SERVICE_UNAVAILABLE)`); `PipelineBindings.polishConnection.onServiceDisconnected` sets `polish = null` before calling the listener; `rawTranscript` resets at take start; OFF still binds `:polish` and gets its deterministic answer from the service (unchanged here); cloud polish also runs inside `:polish`, so the same path covers it. `DictationSessionRig.FakePipeline.bind` returns early on any non-BOUND result and its `disconnect("polish")` does not clear the fake's polish link; both need a rig change.

## 3. Design

- `PipelineController.BindResult`: `BOUND`, `AUDIO_BIND_FAILED`, `ASR_BIND_FAILED`. Return the audio/ASR bind result and a separate polish bind outcome (`PipelineController.bind` returns a small `BindOutcome(result, polishBound)` (proposed)). On refusal, the coordinator sets `SERVICE_UNAVAILABLE` and writes one warning through its `SessionLog`.
- Coordinator: a per-take `polishLost: PolishReason?` (proposed), reset in `beginSession` with `rawTranscript`; set to `SERVICE_UNAVAILABLE` when the bind reports polish refused, to `SERVICE_DIED` in `onPolishDisconnected` in STARTING, RECORDING or PROCESSING (§2.1.2). `onPolishDisconnected` keeps its PROCESSING-with-text branch (`publishFallback(rawTranscript, ..., SERVICE_DIED)`) and drops the ending branch. `polishAndPublish`: `val service = pipeline.polish; val lost = polishLost; if (service == null || lost != null) publishFallback(rawText, takePreferences, lost ?: PolishReason.SERVICE_UNAVAILABLE)`.
- One first-wins decision: guard the loss check, request opening, and fallback choice with one first-wins decision. Close or claim the ledger before fallback; only the winning path writes polish facts and raises its publication defect. Concretely: under `polishSubmissionLock`, record an explicit per-take decision: no request, request open, or answer/fallback claimed (`PolishDecision` (proposed)). The disconnect and speech paths claim fallback only from an undecided state. An outcome that wins the ledger marks the decision before publication. Make every ledger claim and its `PolishDecision` update one operation under `polishSubmissionLock`, including watchdog and error exits (invalid, blank, legacy and thrown-call claims). Publish and cancel after releasing it. Write or hand off the ASR text under the same lock before disconnect can use it. Hold `polishSubmissionLock` only to choose and claim the winner; release it before cancellation, deterministic cleanup, `publishResult`, logging, or defect delivery. Never acquire it while holding `publishLock`.
- Reconnect: after this take loses polish, `onPolishConnected` must skip warm-up and requests for that take. A new take may use the new connection.
- Defects when observed: record bind refusal and disconnect when observed, once per take even if no words follow (a cancelled or wordless take still raises it); prevent a second defect at publication. When speech finds a null polish link without a recorded loss, record `SERVICE_UNAVAILABLE` and raise `PolishServiceUnavailable` through the per-take once gate before publishing fallback. Reset the observed-failure flag only at take admission. Keep a failure observed before cancellation, and ignore first-time disconnects after the take enters CANCELLING, FINISHING, or ERROR. Decisions on the other exits (enumerated in §5): a thrown request is not guaranteed to be followed by a disconnect (the service can throw while alive), so raise a distinct call-failed defect when the request throws, once per take (`AppDefect.PolishCallFailed` (proposed)); a later disconnect must not raise a second defect for the same failure; every protocol-shaped `CALL_FAILED` already raises `PolishProtocolViolation`; a swallowed warm-up failure logs one warning (the warm-up itself is #236); watchdog expiry, blank or malformed answers and the service-side fallback keep their existing defects; expected provider and readiness failures (no key, network, local not ready) stay breadcrumbs and distinct.
- Telemetry: the owner raises the three new defects through its `defectSink` when it observes the bind refusal, the disconnect, the null link, or the thrown request, guarded by a per-take once flag; `TelemetryChannels.of(PolishReason)` keeps `SERVICE_UNAVAILABLE` and `SERVICE_DIED` on BREADCRUMB for the publication; `AppDefect.all()` lists the three new members.
- Removal: the two `TerminalReason` members and every arm that names them, in one change.

Alternatives rejected: (a) keep ending the take and only improve the message: loses the words, against the founder decision; (b) retry the polish bind inside the take: adds a wait to the heart for a limb; the next take binds fresh anyway.

## 4. Contract deltas

`BindResult` loses a member; `TerminalReason` loses two; three `AppDefect` members added. `SERVICE_UNAVAILABLE` and `SERVICE_DIED` remain publication breadcrumbs; their observed bind or disconnect events raise the new defects.

## 5. State audit

| Population | Enumeration |
|---|---|
| Polish failure points in a take | bind refused (STARTING); a connection that never answers (bound, no `onPolishConnected` before speech answers: `pipeline.polish` null, the existing `SERVICE_UNAVAILABLE` path); disconnect in STARTING, RECORDING, PROCESSING before speech answers, PROCESSING after the request opened; reconnect after a disconnect; swallowed warm-up failure; a thrown request (`CALL_FAILED`); malformed or blank answers; watchdog expiry; the service's own fallback. The first six have rows in §11; the rest keep their existing handling and defects (§3 decisions). |
| Consumers of the removed members | `PipelineBindings.kt`, `DictationSessionCoordinator.kt`, `PipelineLinks.kt`, `TakeNotices.kt`, `TerminalReason.kt`, `TelemetryChannels.kt` (two lists), `TakeNoticesTest.kt`; entries-loop tests (`TakeNoticesTest`, `TelemetryContractsTest`) follow automatically. |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| BindResult | coordinator, rig | polish refusal no longer a result | compile; §11 rows |
| TerminalReason members removed | TakeNotices, TelemetryChannels, tests | arms removed | compile; `TakeNoticesTest`, `TelemetryContractsTest` |
| Defect channel | Sentry | three new defects | Update the fallback-producer count guard (`PolishPublicationRoutesTest`), frozen notice table (`TakeNoticesTest`), polish channel expectations, and literal defect fingerprint snapshot (`TelemetryContractsTest`). |
| Removed symbols in docs | plans, knowledge | none | Treat old audit and feature plans as historical; mark removed backticked symbols in newly added plan lines as `(removed)` for `check-cited-symbols.py`. |

## 7-9. Failure modes, signals, fallbacks

New signals: the three defects, each with the take id; the bind refusal warning in the take log. Fallback: the existing deterministic fallback and its notice.

## 10. Files

`PipelineLinks.kt`, `PipelineBindings.kt`, `DictationSessionCoordinator.kt`, `TerminalReason.kt`, `TakeNotices.kt`, `TelemetryChannels.kt`, `DefectIdentity.kt`; tests `DictationSessionRig.kt`, `DictationSessionCoordinatorTest.kt` (or a new `PolishFailsOpenTest.kt` (proposed) on the rig), `TakeNoticesTest.kt`, `TelemetryContractsTest.kt` as needed.

## 11. Testing

Product Outcome rows on the session rig (real coordinator, fake pipeline). For each row, name a compiling one-line behavior mutation and assert that its row fails. Reconnect the fake in row 2; add mutations for rows 4 and 6. Name a concrete compiling mutation for every row, and record each red result before claiming the gate: row 1, on a refused polish bind outcome call `handleServiceFailure(TerminalReason.ASR_BIND_FAILED)`; row 2, drop the latch check in `polishAndPublish`; row 3, in `onPolishDisconnected` call `showError(TerminalReason.ASR_PROCESS_DIED)` in place of the latch; row 4, change `SERVICE_DIED` to `SERVICE_UNAVAILABLE` in the open-request disconnect branch; row 5, clear the latch in `onPolishConnected`; row 6, force the fallback on a healthy outcome; row 6b, pass `PolishReason.OFF` as the lost reason (no notice); row 6c, let the disconnect claim fallback without checking the decision state; row 6d, raise the observed defect only at publication; row 7, emit the disconnect defect unconditionally. The rows:
1. Polish bind refused: the take records, speech answers, the deterministic text is published and delivered, the notice is shown, the terminal is COMPLETED, `PolishServiceUnavailable` is raised. Revert: restore the `POLISH_BIND_FAILED` ending.
2. Polish dies in RECORDING, then speech answers: fallback text delivered with `SERVICE_DIED`, `PolishServiceDied` raised. Revert: drop the latch (the test's fake then reconnects and polishes; the row pins fallback).
3. Polish dies in PROCESSING before speech answers: the take does NOT end; speech answers; fallback delivered; History row completed, never `asr_error`. Revert: restore the `POLISH_PROCESS_DIED` ending.
4. Polish dies after the request opened: unchanged `SERVICE_DIED` fallback (regression row).
5. Polish dies and reconnects before speech answers: the take still uses the fallback, and the reconnected service gets no request. Revert: clear the latch on reconnect.
6. Healthy polish: polished text published exactly as today (regression row).
6b. Notice: Local and Cloud fallback publication show the existing notice; OFF shows none.
6c. A disconnect racing the speech answer: use latches to force disconnect first and speech/outcome first; assert the winning text, stored facts, and one defect in each ordering.
6d. A cancelled take that lost polish still raises the defect once.
6e. Add a row where binding succeeds but the rig withholds only `onPolishConnected`: nonblank speech completes with `SERVICE_UNAVAILABLE` and one defect. Mutation: skip the once gate's defect on the null-link path.
6f. Add a live-service `throwOnRequest` row: `CALL_FAILED` fallback, one `PolishCallFailed`, and no second defect after disconnect. Mutation: raise `PolishServiceDied` on the later disconnect regardless of the once gate.
Give each row a compiling mutation and record it red.
7. Telemetry: a refused bind raises `PolishServiceUnavailable` once and a disconnect raises `PolishServiceDied` once, each with the take id, and the publication raises no second defect. Mutation: emit the disconnect defect unconditionally (the disconnect-then-reconnect-then-disconnect row then raises two).
Rig changes: `FakePipeline` gains a polish-refused bind that still connects capture and speech, and `disconnect("polish")` clears its polish link as `PipelineBindings` does.

### 11.1 UAT
One healthy dictation into Gmail by COMMIT (polished path unchanged). A polish-killed take only if the harness can stop `:polish` without raw `adb shell input`/process tools it refuses; otherwise NOT RUN with the JVM rows as the proof, never counted as a pass.

## 12. Blast radius

The take's failure handling around polish; healthy takes unchanged. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 7, including 6b to 6f, green, and each named mutation red.
- [ ] A healthy emulator dictation lands by COMMIT.

## 14. Open questions
None; the founder decided the behaviour on 2026-09-23.

## 15. Related
#235 (History wait), #236 (warm-up on main), #238 (closed: polish stays required), audit REF-01.
