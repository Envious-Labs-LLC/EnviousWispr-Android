# Issue #252 — The polish fallback never throws — 2026-09-23

GitHub issue: `#252`. Tier: MEDIUM (the heart's last-resort text). Status: APPROVED (coverage round: five findings adopted; grounded round 1: three findings adopted; grounded round 2: one wording finding adopted verbatim, confirmed in code review).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N for the failure itself: a throwing language detector, vocabulary restorer or cleanup cannot be staged on a device. JVM rows drive each one through the controller and the coordinator rig. Two emulator dictations by COMMIT show the healthy path is unchanged.

## Preface — User Rubric

User Rubric: the words always land (founder decision 2026-09-23). If the deterministic fallback itself breaks, the person still gets the last good version of their words, with the polish notice, published once; at most one preparation defect and at most one independent polish-failure defect are raised per take (coverage finding 3).

---

## 0. TL;DR

`TakePolishController.fallBack` claims the take's result, then builds the deterministic text through vocabulary restoration, language detection and cleanup. If any step throws, `onPrepared` is never called: no words, no notice, and the take can stay in Processing (REF-01 of the second 2026-09-23 audit). The same hole exists before the request (`prepare`'s vocabulary restore) and after a healthy answer (`onOutcome`'s restore of the engine text). Make every preparation step fail open to the last good text, hand back exactly once, and raise one new defect.

## 1. Problem

Grounded by Codex (`252-g0`), re-read by Claude:
- `fallBack` (`TakePolishController.kt`): `cancelOpen()`, the log line, then `onPrepared(PreparedText.Fallback(deterministic(rawText, …), …))`. A throw inside `deterministic` skips `onPrepared`.
- `deterministic`: `restoreVocabulary(rawText)`, then `PolishFallback.deterministic(prepared, cleanup, languageDetector)` (detection and `PolishPipeline.run`; the detector and the language policy sit outside the pipeline's own catch), then `restoreVocabulary(cleaned)`.
- `prepare`: `restoreVocabulary(rawText)` runs before the ledger opens; a throw fails the coroutine and nothing is handed back.
- `onOutcome`: `restoreVocabulary(outcome.text)` runs after the claim; a throw loses the engine's answer.
- Fallback runs on the owner's scope (watchdog, request failure), on main (disconnects) and on binder threads (listener callbacks).

## 2. Goals & non-goals

### 2.1 Goals
1. `deterministic` returns the last good text: each step (restore, cleanup with detection, final restore) runs in its own `try`/`catch (Exception)`. On the first thrown step, preparation STOPS and returns the value completed before that step; later steps do not run (coverage finding 4). It never catches an `Error`.
2. A guarded `preparedFallback` returns the whole `PreparedText.Fallback` (grounded round 1, finding 2); `fallBack` cancels, logs, then hands that value to `onPrepared` exactly once, with its own reason, so the existing polish notice shows.
3. `prepare`'s pre-request restore fails open to `rawText`; the request is sent with the raw words.
4. On a failed restore of a nonblank engine answer, publish the un-restored answer as `Polished` with the engine's original reason and status. The existing notice follows that reason: `POLISHED` shows none; a failure reason retains its notice (grounded round 2).
5. Every caught preparation failure raises one `AppDefect.PolishPreparationFailed` per take (its own once-gate, separate from the polish-failure gate, because it is a different defect), with the step name as a finite `step` value. The new defect is added to `DefectIdentity.all`, `SentrySchema`'s defect list and exhaustive `when`, the fingerprint snapshot, and `step` to `SentrySchema.keys`.

### 2.2 Non-goals
- No change to which failures fall back, to the reasons, or to the healthy path's text.

## 3. Design

- Two seams, defaulted to today's calls, so a JVM row can make each step throw: `restore: (String, StructuredTermRestorer.Matcher) -> String = { text, matcher -> matcher.restore(text) }` and `cleanup: (String, CleanupOptions, LanguageDetector) -> String = PolishFallback::deterministic` on the controller. The coordinator does not pass them.
- `restoreVocabulary` keeps the `TextSafety.isSafe` check inside the same guarded step.
- `reportPreparationFailure(step)`: `if (preparationFailureReported.compareAndSet(false, true))`, then `defectSink(AppDefect.PolishPreparationFailed, mapOf("take_id" to takeId, "step" to step))` inside `try/catch (Exception)` that logs a warning; an `Error` is not caught (grounded round 1, finding 1), outside the lock. A reporting failure never blocks `onPrepared` (coverage finding 2). `onPrepared` itself is NOT caught or retried: owner publication may already have partly run, so a retry could deliver twice; owner publication failures are a separate heart-path concern.
- Steps: `restore_raw`, `cleanup`, `restore_cleaned`, `restore_answer`.

## 4. Contract deltas

A new `AppDefect` (fingerprint `polish_preparation_failed`, semantic id `polish.preparation_failed`) and a new Sentry key `step` with a finite set.

## 5-9. State, consumers, failure modes

| Delta | Consumer | Change |
|---|---|---|
| New defect | `DefectIdentity.all`, `SentrySchema.semanticIdOf` and `SentrySchema.defects`, `TelemetryContractsTest.everyDefectFingerprintEqualsTheSnapshot` | declared (coverage finding 5) |
| `step` key | `SentrySchema.keys` | `OneOf(restore_raw, cleanup, restore_cleaned, restore_answer)`; a row asserts exactly these four pass and other text is redacted |

Risks: a catch inside the lock (none: every step runs outside it); a failure after the polish defect already fired (its own gate, so both are reported once each).

## 10. Files

`TakePolishController.kt`, `DefectIdentity.kt`, `SentrySchema.kt`; tests `TakePolishControllerTest`, `PolishFailsOpenTest`, `DictationSessionRig` (a detector parameter), `TelemetryContractsTest`, `SentrySchemaTest`.

## 11. Testing

1. Controller rows, each through the injected seam configured to throw only on the intended invocation (grounded round 1, finding 3: one seam serves four restores), each asserting its exact `step` value and one hand-back with the expected text and one `PolishPreparationFailed` with its step: the first restore throws (raw lands); cleanup throws (the restored raw lands); the final restore throws (the cleaned text lands); the answer's restore throws (the engine answer lands as `Polished`); the pre-request restore throws (the request carries the raw words). Mutation for each: remove that step's catch.
2. Coordinator row (`PolishFailsOpenTest`): `DictationSessionRig.coordinator` accepts a detector, defaulting to today's abstaining fake (coverage finding 1). With a throwing detector and local polish selected, a polish death: the take completes, the words land once, the polish notice shows (the posted main-thread notice drained before the assert), one `polish_preparation_failed` and one `polish_service_died`. Mutation: remove the cleanup step's catch.
4. A defect sink that throws on the preparation defect still lets the words land once. Mutation: call the sink without its catch.
3. `SentrySchemaTest`'s source rows see the new defect and the `step` key (they fail if either is undeclared); a new row asserts `step` keeps exactly its four tokens.

## 12. Blast radius

The fallback and the vocabulary restore in `TakePolishController`. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Rows 1, 2 and 4 green, each mutation red; row 3 green.
- [ ] Two emulator dictations by COMMIT.

## 14. Open questions
None.

## 15. Related
#234, #237, #240, REF-01 of the second 2026-09-23 audit.

## 16. As built

- `TakePolishController`: `preparedFallback` builds the whole `PreparedText.Fallback`; `deterministic` runs three guarded steps and stops at the first that throws; `prepare` and `onOutcome` guard their restore; `guarded` catches `Exception` only; `reportPreparationFailure` has its own once-gate and a `try/catch (Exception)` around the sink; the `restore` and `cleanup` seams default to today's calls; the four step names are public constants.
- `AppDefect.PolishPreparationFailed` is declared in `DefectIdentity.all`, `SentrySchema.semanticIdOf` and `SentrySchema.defects`, and the fingerprint snapshot; `SentrySchema.keys` gains `step`.
- `DictationSessionRig.coordinator` takes a detector; `DeterministicFallbackTest`'s two source rows now read the `cleanup` seam, whose default is the shared fallback.
- Tests: `TakePolishControllerTest` rows 1a to 1e and 4, `PolishFailsOpenTest` row 7, `SentrySchemaTest` step row. Receipts 8 of 8 RED; full suite 1221, 0 failures; both polish classes 20 of 20 repeats; two emulator dictations by COMMIT.
