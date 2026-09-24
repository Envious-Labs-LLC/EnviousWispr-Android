# Regrade 9: the founder's challenge and the auditor's correction (2026-09-24)

Regrade 9 (`2026-09-24-senior-audit-9.json`, blind, at 8ec5323) scored **B, 84**, Medium confidence, with zero CRITICAL, zero HIGH, 2 MEDIUM and 6 LOW targets. It was the fourth B in a row (regrades 6 to 9: 82, 84, 83, 84) while the findings shrank from 1 CRITICAL and 3 HIGH in the first audit (C, 76, 2026-09-20) to none.

## The pattern across eleven audits

- Performance and latency, and Resource lifecycle, were Medium Risk in every audit since 2026-09-22. The prompt calls them Static Risk Assessments that static reading cannot settle, and forbids building or running anything.
- Confidence was Medium in every audit, for the same reason.
- Which letter dimension reached A varied from run to run (the bounded ~350k-token sample reads different files).
- The prompt requires six to twelve refactor targets every run.

## The challenge

The founder asked, through the same Codex session that issued the B: either give better feedback, or say the grade is unfair. The prompt asked five questions: what exactly separates the code from 90, whether 90 is reachable without runtime evidence, whether the letter is held by the number or the severity of findings, which runtime evidence would move the two risk dimensions, and whether the grading is fair.

## The auditor's answer, in substance

1. Nothing in its cited findings justifies keeping the code below 90. Per dimension: Error handling, Testability, Code hygiene and API surface are already A-level; Architecture needs the lower packages to stop importing `ui` and the speech request mechanics moved out of the session owner; Concurrency needs the second unbounded rescue `await` removed; Security needs unknown PostHog event names rejected and a canary-prompt check; Performance and Resource lifecycle need committed S26 receipts.
2. 90 is reachable under the prompt as written: no rule caps the letter without runtime evidence. The smallest set of code changes for 90 is none. It corrected its own assessment to **A, 90, Medium confidence**.
3. It would have scored higher without the six-to-twelve target minimum, which "encourages marginal debt to be presented as a roadmap". The letter should follow severity and demonstrated impact, not finding count. It had treated static uncertainty and low-impact future debt as an implicit cap, "That was wrong."
4. Runtime evidence for the two risk dimensions: an S26 latency and idle-cost receipt (30 takes, cold and warm, Bluetooth hold on and off; trigger to first PCM, stop to final text, text to verified insertion; a ten-minute idle trace) at `docs/audits/s26-stage1-performance-receipt.md`, and a lifecycle receipt (start, stop, cancel; kill `:audio`, `:asr`, `:polish` at each phase; earbuds removed during the hold; expiry-post refusal) at `docs/audits/s26-stage1-lifecycle-receipt.md`. The prompt must name those files for a later auditor to read them.
5. "My B was unfair." It proposed changing the prompt to allow fewer than six targets and to state that unmeasured runtime risk affects confidence, not automatically the overall letter.

## Status

The corrected grade came from the same session after the challenge, so it is weaker evidence than a blind run. Not decided at this commit: whether to record A, 90 from the correction, or to fix the prompt as proposed and run a fresh blind regrade 10. Regrade 9's eight targets are not filed as issues yet.
