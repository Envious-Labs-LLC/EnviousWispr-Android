# Issue #326: every AI Polish engine fallback answers even when its cleanup throws (REF-02, regrade 6) (2026-09-24)

GitHub issue: `#326`. Tier: SMALL. Status: revised after the coverage round (`326-cov`), its detector finding adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. The change is a pure function behind unit tests. The engine process is not reachable from a JVM test, and a detector cannot be made to throw on a device without a debug seam in production.

## Preface: User Rubric

Persona: the founder dictating on his S26. When the AI Polish engine fails, it answers with the deterministic text at once. If building that text throws, the engine answers nothing. The founder then waits for the owner's watchdog before his words arrive. `kotlin-patterns.md` RULE: fail-open-to-the-last-good-text says a failure returns the value it was handed.

## 0. TL;DR

`PolishService.fallbackText` (the engine's deterministic text) calls `PolishFallback.deterministic`, which runs language detection and `PolishPipeline.run` unguarded. Five engine exits call it with no guard around it:

1. The v1 `polish` path.
2. The cancelled answer.
3. The poisoned-runtime answer.
4. The work `catch`, where a throw from `run` (for example from `detectLanguage`) is followed by a second throw from the same detector.
5. The hard-expiry answer in `expireLocal`.

The fallback lane already guards its call (`runCatching { prepare(raw, options) }.getOrDefault(raw)`). The owner's side, `TakePolishController.deterministic`, guards each step and reports one preparation defect, so it stays as it is.

Fix at the one engine function every exit shares, not at the five sites. `PolishFallback.deterministicOrWords(raw, options, detector, warn)` returns the deterministic text, or the words as handed if detection or cleanup throws. A failed detection is never retried as an abstention, because that could still change words after a failure (coverage round, adopted). It warns once, by exception type only. `PolishService.fallbackText` calls it. The lane keeps its own `runCatching`, which is now redundant but harmless. The existing drift guard (`DeterministicFallbackTest`) moves to the new call.

## 1. Tests

- `DeterministicFallbackTest`: a detector that throws, and a cleanup that throws (a test `clean` seam that defaults to `deterministic`), each give the words as handed, with one warning each and no words in it. An ordinary fallback is unchanged. MUTATION m1: the guard is removed.
- The drift guard: `PolishService.fallbackText` calls `PolishFallback.deterministicOrWords(raw, options, languageDetector`.

## 2. Blast radius

The engine process only. The text for a normal failure is unchanged. Only a throwing detector or cleanup, which today gives no answer, now gives an answer.

## Results (2026-09-24)

- Suite 1378, 0 failures; visibility and citation checks clean.
- MUTATION m1 RED (`326-mut.py`: `DeterministicFallbackTest` fails with the detector's own `IllegalStateException`).
- Coverage round (`326-cov`): the five exits are complete, and the guard in `fallbackText` also covers the lane. A throwing detector returns the words, with no abstention retry (adopted). The owner keeps its step guards and defect. The engine needs only the warning, since its outcome reasons already name the failure. A JVM test on the pure function plus the drift guard is enough. The drift guard is wiring evidence, not proof that the Android service delivered an answer: the engine process cannot be built in a JVM test.
