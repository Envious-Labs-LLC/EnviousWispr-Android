# Issue #345: the start's independent steps stop adding to each other (REF-03, regrade 7) (2026-09-24)

GitHub issue: `#345`. Tier: SMALL. Status: built 2026-09-24, before the coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take lands its words. The real-phone timings stay #267's (the S26 still runs build 185).

## Preface: User Rubric

Persona: the founder pressing dictate. `TakeStartPreparer.prepare` waits in sequence:

1. The settings and terms, bounded at 2 s.
2. The matcher and the policy, one shared 1 s deadline.
3. The journal admission, bounded at 300 ms.

So the worst case before the capture bind is about 3.3 s. The policy read and the admission need nothing from the settings.

## 0. TL;DR

- The policy job is launched at the start of `prepare`, beside the settings wait. It is published to the owner at once and the state rechecked, so a cancel during the settings wait cancels it. It is published again with the matcher, as before. `priorPolicy` is still read before the policy read starts.
- The journal admission's window counts from the start of `prepare` (`preparedFromMs + JOURNAL_ADMISSION_DEADLINE_MS`), not from after the matcher. The time spent on the settings and the matcher is spent on it too. An admission already landed is taken without waiting.
- The matcher still waits for the settings, because it compiles their terms, and it keeps its deadline. The policy keeps the same deadline, having run for longer.

The worst case falls from about 3.3 s to about 3 s: the settings, then the matcher. In the common case the policy and the admission are already done when the matcher finishes. The larger question, overlapping preparation with the capture bind itself, is #267's to decide from real S26 timings.

## 1. Tests

- `TakeStartPreparerTest` row 7: the policy read starts before the settings answer. MUTATION m6: the policy is launched after the settings wait.
- Row 8: an admission that never lands adds no wait once its window passed during the settings. MUTATION m7: the window counts from after the matcher.
- The existing rows stay green: the stamp order, the jobs cancelled after publication, the prior policy before the launch, the admission deadline and its logs, and the owner shape.
