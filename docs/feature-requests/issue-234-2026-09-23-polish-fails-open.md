# Issue #234 — A broken polish connection never costs the user their words — 2026-09-23

GitHub issue: `#234`. Tier: LARGE (the heart path's handling of a limb failure). Status: DRAFT.

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
2. A polish disconnect in any state before its request is sent latches `polishLost` for the take; the take continues; at `polishAndPublish` a lost or null polish publishes the fallback (`SERVICE_DIED` when it died, `SERVICE_UNAVAILABLE` when it never bound). A disconnect after the request is open keeps today's `publishFallback(... SERVICE_DIED)`.
3. A reconnect during the same take does not un-latch it: the take uses the fallback; the next take binds fresh.
4. The user sees the existing polish failure notice (`PolishFailure.UNEXPECTED`, through `PolishPublicationFacts.notice`), unchanged copy.
5. Logged to be fixed: `SERVICE_UNAVAILABLE` and `SERVICE_DIED` move to the DEFECT channel with new `AppDefect.PolishServiceUnavailable` (proposed) and `AppDefect.PolishServiceDied` (proposed), and the bind refusal logs one warning naming it.
6. `TerminalReason.POLISH_BIND_FAILED` and `TerminalReason.POLISH_PROCESS_DIED` are removed with their `TakeNotices` lines, `TelemetryChannels` arms and `PipelineController.BindResult.POLISH_BIND_FAILED` (no member is kept that nothing emits; the wire only ever carried them outward, nothing parses a stored reason: `TerminalReason.valueOf` has no caller).

### 2.2 Non-goals
- No change to readiness, setup, the polish default or the Off option (#238 decision).
- No change to the warm-up call (#236) or the History wait (#235); both are their own issues.
- No change to healthy-polish behaviour, the watchdog, or the ledger.

## 2.5 Grounding brief

Grounded by Codex (`234-g0`) and re-read by Claude: the null-service fallback at `polishAndPublish` (`if (service == null) publishFallback(rawText, takePreferences, PolishReason.SERVICE_UNAVAILABLE)`); `PipelineBindings.polishConnection.onServiceDisconnected` sets `polish = null` before calling the listener; `rawTranscript` resets at take start; OFF still binds `:polish` and gets its deterministic answer from the service (unchanged here); cloud polish also runs inside `:polish`, so the same path covers it. `DictationSessionRig.FakePipeline.bind` returns early on any non-BOUND result and its `disconnect("polish")` does not clear the fake's polish link; both need a rig change.

## 3. Design

- `PipelineController.BindResult`: `BOUND`, `AUDIO_BIND_FAILED`, `ASR_BIND_FAILED`. `PipelineBindings.bind` keeps binding polish; on refusal it logs `Polish service did not bind; this take publishes the deterministic text` (through the owner's log via a returned flag or the listener; exact seam chosen in the build and named in review) and returns `BOUND`.
- Coordinator: a per-take `polishLost: PolishReason?` (proposed), reset in `beginSession` with `rawTranscript`; set to `SERVICE_UNAVAILABLE` when the bind reports polish refused, to `SERVICE_DIED` in `onPolishDisconnected` whatever the state. `onPolishDisconnected` keeps its PROCESSING-with-text branch (`publishFallback(rawTranscript, ..., SERVICE_DIED)`) and drops the ending branch. `polishAndPublish`: `val service = pipeline.polish; val lost = polishLost; if (service == null || lost != null) publishFallback(rawText, takePreferences, lost ?: PolishReason.SERVICE_UNAVAILABLE)`.
- Telemetry: `TelemetryChannels.of(PolishReason)` sends `SERVICE_UNAVAILABLE` and `SERVICE_DIED` to DEFECT; `defectOf` maps them to the two new `AppDefect` members; `AppDefect.all()` lists them.
- Removal: the two `TerminalReason` members and every arm that names them, in one change.

Alternatives rejected: (a) keep ending the take and only improve the message: loses the words, against the founder decision; (b) retry the polish bind inside the take: adds a wait to the heart for a limb; the next take binds fresh anyway.

## 4. Contract deltas

`BindResult` loses a member; `TerminalReason` loses two; two `AppDefect` members added; two `PolishReason`s change telemetry channel from breadcrumb to defect.

## 5. State audit

| Population | Enumeration |
|---|---|
| Polish failure points in a take | bind refused (STARTING); disconnect in STARTING, RECORDING, PROCESSING before speech answers, PROCESSING after the request opened; reconnect after a disconnect. Each has a row in §11. |
| Consumers of the removed members | `PipelineBindings.kt`, `DictationSessionCoordinator.kt`, `PipelineLinks.kt`, `TakeNotices.kt`, `TerminalReason.kt`, `TelemetryChannels.kt` (two lists), `TakeNoticesTest.kt`; entries-loop tests (`TakeNoticesTest`, `TelemetryContractsTest`) follow automatically. |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| BindResult | coordinator, rig | polish refusal no longer a result | compile; §11 rows |
| TerminalReason members removed | TakeNotices, TelemetryChannels, tests | arms removed | compile; `TakeNoticesTest`, `TelemetryContractsTest` |
| Defect channel | Sentry | two new defects | `TelemetryContractsTest` snapshot, new rows |

## 7-9. Failure modes, signals, fallbacks

New signals: the two defects, each with the take id; the bind refusal warning in the take log. Fallback: the existing deterministic fallback and its notice.

## 10. Files

`PipelineLinks.kt`, `PipelineBindings.kt`, `DictationSessionCoordinator.kt`, `TerminalReason.kt`, `TakeNotices.kt`, `TelemetryChannels.kt`, `DefectIdentity.kt`; tests `DictationSessionRig.kt`, `DictationSessionCoordinatorTest.kt` (or a new `PolishFailsOpenTest.kt` (proposed) on the rig), `TakeNoticesTest.kt`, `TelemetryContractsTest.kt` as needed.

## 11. Testing

Product Outcome rows on the session rig (real coordinator, fake pipeline), each with a named revert:
1. Polish bind refused: the take records, speech answers, the deterministic text is published and delivered, the notice is shown, the terminal is COMPLETED, `PolishServiceUnavailable` is raised. Revert: restore the `POLISH_BIND_FAILED` ending.
2. Polish dies in RECORDING, then speech answers: fallback text delivered with `SERVICE_DIED`, `PolishServiceDied` raised. Revert: drop the latch (the test's fake then reconnects and polishes; the row pins fallback).
3. Polish dies in PROCESSING before speech answers: the take does NOT end; speech answers; fallback delivered; History row completed, never `asr_error`. Revert: restore the `POLISH_PROCESS_DIED` ending.
4. Polish dies after the request opened: unchanged `SERVICE_DIED` fallback (regression row).
5. Polish dies and reconnects before speech answers: the take still uses the fallback, and the reconnected service gets no request. Revert: clear the latch on reconnect.
6. Healthy polish: polished text published exactly as today (regression row).
7. Telemetry: `TelemetryChannels.of(SERVICE_UNAVAILABLE)` and `of(SERVICE_DIED)` are DEFECT with the new defects. Revert: put them back on BREADCRUMB.
Rig changes: `FakePipeline` gains a polish-refused bind that still connects capture and speech, and `disconnect("polish")` clears its polish link as `PipelineBindings` does.

### 11.1 UAT
One healthy dictation into Gmail by COMMIT (polished path unchanged). A polish-killed take only if the harness can stop `:polish` without raw `adb shell input`/process tools it refuses; otherwise NOT RUN with the JVM rows as the proof, never counted as a pass.

## 12. Blast radius

The take's failure handling around polish; healthy takes unchanged. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1 to 7 green and each named revert red.
- [ ] A healthy emulator dictation lands by COMMIT.

## 14. Open questions
None; the founder decided the behaviour on 2026-09-23.

## 15. Related
#235 (History wait), #236 (warm-up on main), #238 (closed: polish stays required), audit REF-01.
