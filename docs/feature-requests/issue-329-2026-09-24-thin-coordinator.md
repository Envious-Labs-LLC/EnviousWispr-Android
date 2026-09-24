# Issue #329: the session owner hands take-fact stamping and telemetry to one per-take recorder (REF-05, regrade 6) (2026-09-24)

GitHub issue: `#329`. Tier: LARGE (a refactor of the heart's owner; no behaviour change). Status: revised after the coverage round (`329-cov`), all findings adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take through the harness lands its words, and the take's breadcrumbs and journal stages read as before.

## Preface: User Rubric

Persona: the founder. There is no visible change. `architecture-rules.md` RULE: keep-central-types-thin: `DictationSessionCoordinator.kt` is 1283 lines. The audit names two regions that mix arbitration with telemetry projection and notice presentation: `polishAndPublish` and `beginSession`.

## 0. TL;DR

The audit lists REF-05 as depending on REF-04 (#328, overlapping optional preparation with capture startup). This change touches none of the preparation that #328 moves, so it goes first. It keeps `beginSession`'s preparation lines where they are.

New `telemetry/TakeOutcomeRecorder.kt` (proposed), one per take, built in `beginSession` beside the arbiter and held in `TakeContext`. It owns every take-fact stamp, journal stage advance and take breadcrumb the coordinator writes today:

| Today in the coordinator | Recorder method |
|---|---|
| `Telemetry.takeStarted` and the `admitted` breadcrumb | `admitted()` |
| `takeFacts.admissionObservedMs`, `bindRequestedMs` | `admissionObserved(ms)`, `bindRequested(ms)` |
| `onTakeEnded`: `peakAmplitude`, `silenceStopStatus`; RECORDING: `captureTerminal` | `captureEnded(peak, silenceStatus)`, `captureTerminal(terminalReason)` |
| `publishLive`: five route and live facts, journal RECORDING, the `live` breadcrumb | `live(routeKind, routeReason, liveAfterMs, receivedMs, forced)` |
| `stopAndTranscribe`: `recordingSeconds`, the manual default, journal PROCESSING, the `stopped` breadcrumb | `stopped(recordingDurationMs)` |
| ASR result: `asrMs`, `asrChars`, the `asr_done` breadcrumb; ASR failure: `asrFailure`, `asrMs` (no breadcrumb, as before) | `asrDone(ms, chars)`, `asrFailed(failure, ms)` |
| `polishAndPublish`: `recordPolish`, the `polish_done` breadcrumb, the defect | `polishDone(reason, latencyMs, statusCode, contextToken)`, which raises the defect through the owner's `reportDefect` sink |
| the publication's `historySave` from the save's answer | `historySaved(outcome)` |

The polish notice's log line and presentation move into `SessionNoticePresenter.sayPolishFailureIfAny(facts)`, which already owns `sayPolishFailure`. It takes the publication's `PolishPublicationFacts`, where the notice lives, not `TakeFacts` (coverage round).

The coordinator keeps admission, state transitions, arbiter reservation and commit, the finalizer calls, and delivery. Its facts are still readable through `take.facts`, the same `TakeFacts` object, so every rig assertion on facts is unchanged. The moved lines are deleted in the same change (GR-MIGRATION-COMPLETE). The ending projection is already its own file (`telemetry/TakeEndingReport.kt`, #309) and is untouched.

## 1. Tests

- The existing coordinator, rig and telemetry suites are the behaviour oracle, and they stay green unchanged except for source-shape rows that name moved lines. Those rows move to the recorder: `PostHogSchemaTest` (the live-state line), `PolishPublicationRoutesTest` (the recordPolish, breadcrumb, defect, payload, reservation order) and `SessionNoticePresenterTest` (one presenter call).
- New `TakeOutcomeRecorderTest`: each method stamps its facts, advances the journal and writes its breadcrumb (fakes for the journal, the breadcrumb and the defect sink). A polish failure raises exactly one defect.
- `SessionOwnerShapeTest`: the coordinator no longer names `Telemetry.journal`, `Telemetry.breadcrumb("take"` or `takeFacts.` as an assignment target.
- Size: `DictationSessionCoordinator.kt` shrinks. The count is taken after the build.

## Results (2026-09-24)

- Coverage round (`329-cov`), adopted:
  - Each call keeps its place against the arbiter: ASR failures before `commitNow`, the polish facts before the payload and the reservation, the History answer before the completed commit.
  - The recorder only records. It never reserves, commits, chooses an ending or delivers.
  - `admitTake` stays the owner's seam, and polish defects go through the owner's guarded `reportDefect`.
  - The presenter reads the publication's facts.
  - The "every" claim is narrowed to the coordinator's writes; `TakeStartPreparer` keeps its own start stamps.
  - REF-04 (#328) is a sequencing note, not a code prerequisite.
  - `SessionSources.all` includes the recorder.
- `DictationSessionCoordinator.kt`: 1283 to 1254 lines. New `telemetry/TakeOutcomeRecorder.kt`. `TakeContext` gains `outcome`, making eight properties.
- Tests:
  - New `TakeOutcomeRecorderTest`.
  - `SessionOwnerShapeTest.theOwnerRecordsTheTakeThroughItsRecorderOnly`: the owner names no journal stage, no take breadcrumb and no fact assignment. The recorder holds no reserve, commit or `TerminalReason`. Both ASR failure callbacks record before their `commitNow`, a check that did not exist before: m4 was not red until it was added.
  - The source-shape rows that named moved lines now read the recorder: `PostHogSchemaTest`, `PolishPublicationRoutesTest`, `SessionNoticePresenterTest`, `AutoPasteWiringTest`, and the `TakeContext` property count.
- MUTATIONS m1 to m4 RED (`329-mut.py`); suite 1386, 0 failures; visibility and citation checks clean.
- Emulator (`329-uat.py`): one spoken take into Gmail landed by COMMIT with exactly the expected text. The polish notice went through the presenter ("Polish notice shown: LOCAL_NOT_READY").
