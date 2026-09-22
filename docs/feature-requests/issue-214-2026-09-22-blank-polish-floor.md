# Issue #214 — A blank polish answer over real words publishes the owner's cleaned text, not the raw transcript — 2026-09-22

GitHub issue: `#214`. Tier: SMALL (the owner's handling of one polish answer shape; no AIDL, no engine change). Status: APPROVED (grounded round 3 PROCEED-AS-PLANNED; code review round 1 ALL-CLEAR).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (this plan: `cited-symbols` conditionally).

**PAR rows closed:** none.

**Hardware UAT:** Y (polish and text finalization are on the heart path). The malformed blank outcome cannot be produced by the correct engine on demand and is covered by JVM row 1. Regression coverage: an ordinary emulator take and two back-to-back ordinary takes into Gmail on the emulator; the S26 Ultra pass (two back-to-back takes into a real third-party editor) is queued with #115, #161, #212 and #213, because the founder excluded his phone from this audit queue (his instruction, 2026-09-21 morning).

## Preface — User Rubric

User Rubric: N/A — internal-only defence: no reachable engine answer changes what a user sees today (section 1); the change decides what reaches the editor if the polish engine ever breaks its contract.

## 0. TL;DR
When the polish engine answers with blank text for a nonblank transcript, the session owner publishes `publishResult("")`, and `publishResult` substitutes `rawTranscript`: the raw words, with no filler cleanup and no custom-word restoration, labelled `RAW_FALLBACK`. No correct engine answer is blank for a nonblank request (section 1), so a blank answer is a broken engine. Fix: treat it like the four existing protocol violations (null, mismatched, v1 result, v1 error): one `PolishProtocolViolation` defect with shape `blank`, and the owner's own floor through `publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)`, whatever the reason the engine gave.

## 1. Problem
For every nonblank v2 request, every correct `PolishOutcome` carries nonblank text. `DeterministicCleanup` refuses any transformation from nonblank input to blank output and recovers the original text; `PolishPipeline.run` observes that recovery before its blank exit. The same protection applies to every direct `fallbackText` producer. Model decline, rejection, failure and timeout also retain the already nonblank cleaned text. `NO_SPEECH` is the only blank outcome producer, and `PolishService.accept` produces it only for blank service input, which this owner does not submit. Therefore any blank v2 outcome received for this owner's nonblank transcript is a protocol violation, regardless of `PolishReason`. Separate language detectors can produce different nonblank floors, but neither correct side may erase a nonblank request.

(Verified at `TextSafety.isDeterministicSafe`, `DeterministicCleanup.kt`: `if (input.isNotBlank() && output.isBlank()) return false`, and the caller returns `CleanupResult(original, false, true)` when the check fails. The coverage round's contrary reading, that `EMPTY_AFTER_CLEANUP` and six other reasons can carry blank, was withdrawn by grounded round 1 on this evidence.)

So the defect is: a blank answer, under any reason, reaches the editor as raw, uncleaned words, silently. Classification: HYPOTHETICAL (it needs an engine that breaks its contract); fixed because it is small and silent, the rule's exception.

## 2. Goals & non-goals
### 2.1 Goals
- Every blank engine answer for a nonblank transcript is treated as a protocol violation and publishes the owner floor, reason `CALL_FAILED`, engine label `Deterministic fallback`, and exactly one `PolishProtocolViolation` defect with shape `blank`.
- Every nonblank engine answer behaves exactly as today, including a filler-only cleanup recovery.
### 2.2 Non-goals
- Changing `publishResult`'s defensive `text.ifBlank { rawTranscript }` behavior. The new listener guard prevents a blank v2 outcome for a nonblank take from reaching that fallback.
- Any engine-side change.

## 2.5 Grounding brief
Producer → owner → consumer: `PolishService.run` / fallbacks → `IPolishCallback.onOutcome` → `DictationSessionCoordinator` `PolishListener.onOutcome` (`DictationSessionCoordinator.kt:1115`) → `publishResult`. Existing authority: the four protocol-violation branches in the same listener (null or mismatched outcome, v1 result, v1 error) all raise `PolishProtocolViolation` and call `publishFallback(..., CALL_FAILED)`; this adds a fifth member to that set. Prior attempt: none. Audit REF-04.

## 3. Design
In `onOutcome`, after the ledger claim succeeds and before `publishResult`:

```kotlin
// No correct engine answer is blank for a nonblank request: deterministic cleanup recovers the original
// rather than erase it, and every fallback keeps that text (#214). A blank answer is a broken engine.
if (outcome.text.isBlank() && rawText.isNotBlank()) {
    log.warn("Blank polish outcome for request ${outcome.requestId} (reason=${outcome.reason}); publishing the owner's fallback")
    defectSink(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "blank"))
    publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
    return
}
```

`defectSink` (proposed) is a constructor parameter `(AppDefect, Map<String, Any?>) -> Unit`, defaulting to `Telemetry::defect`, the `endingSink` shape. The five `Telemetry.defect` calls inside `DictationSessionCoordinator` route through `defectSink`. The top-level `recordTakeEnding` remains unchanged because tests already replace that whole path through `endingSink`. The rig records defects in a `CopyOnWriteArrayList`.

## 4-9
Contract: none on AIDL. Consumers: History row reason `CALL_FAILED` and engine `Deterministic fallback` for this shape (was `RAW_FALLBACK` with the engine's reason); telemetry gains one `shape=blank` value on an existing defect. Failure table: engine blank for a nonblank transcript, any reason → the owner floor published, `CALL_FAILED`, one defect (was the raw transcript under the engine's reason); engine nonblank → unchanged. Signals: one new warn, content-free (request id and reason only).

## 10. Files
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt`: the branch, `defectSink`.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt`: a defect recorder passed as `defectSink`.
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt`: rows below.

## 11. Testing
`DictationSessionCoordinatorTest` (Harness Contract for the owner's decisions; the engine and insertion boundary are fakes):

1. `aBlankPolishedAnswerPublishesTheExactCleanedVocabularyText`: freeze the custom term `Envious` with alias `envious`; transcribe `hello envious`; return blank text with `POLISHED`. Assert the exact paste list is `listOf(1L to "hello Envious")`, the History `finalText` is exactly `hello Envious`, the engine is `Deterministic fallback`, the reason is `CALL_FAILED`, and exactly one `PolishProtocolViolation` with `shape=blank` was recorded.

2. `aNonblankFillerRecoveryUnderOffStillInsertsWhatWasSaid`: transcribe `um uh`; return `um uh` with reason `OFF`, representing the producer-valid result when deterministic safety recovers a cleanup that would otherwise erase the take. Assert the exact single paste is `um uh`, the reason remains `OFF`, and no protocol violation was recorded.

Receipts: remove the blank-outcome branch and row 1 turns red; make the branch reject nonblank outcomes and row 2 turns red; delete or duplicate the protocol-defect emission and row 1 turns red.

### 11.1 Hardware UAT

Emulator: two back-to-back ordinary takes into Gmail, each landing exactly once, judged by the editor's own whole text.

S26 Ultra, through `wispr-eyes`, when the founder's phone pass runs: two back-to-back ordinary takes into a real third-party editor, recording what was dictated, the target app, and the exact editor text after each take. Both takes must land exactly once. This validates regression safety only; the malformed blank outcome remains JVM-only. NOT RUN in this change (founder instruction above).

## 12-15
Blast radius: one branch in one listener and a constructor parameter; worst case a nonblank answer mistaken for a violation (row 2 guards it). Ship: rows green, receipts red, suite count, Codex ALL-CLEAR. Related: audit `docs/audits/2026-09-22-senior-audit.json` REF-04.
