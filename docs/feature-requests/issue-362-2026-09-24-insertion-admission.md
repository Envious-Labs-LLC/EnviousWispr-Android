# Issue #362: accepting an insertion returns before any editor work (REF-07, regrade 8) (2026-09-24)

GitHub issue: `#362`. Tier: SMALL (one line on the insertion path, heart). Status: built, before the combined Codex coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take lands by COMMIT with the expected text: the first attempt now runs on the next main-looper pass and still inserts.

## Preface: User Rubric

Persona: the founder at the end of a take. The session's publication step hands the words to the accessibility service through `MainThreadHandoff`, whose wait has no second deadline once the body starts (RULE: the-handoff-timeout-must-abandon-the-work-not-race-it). `AccessibilityInsertionRunner.requestInsertion` made its first insertion attempt inline, so that wait lasted through an accessibility action: a binder call into the editor's process, bounded only by the framework. The words were already accepted; the caller learned nothing more by waiting.

## 0. TL;DR

- `requestInsertion` admits the words (the refusals, the pending insertion, its deadline, the content-change mode) and then schedules the first attempt as the retry loop's first turn (`scheduleRetry(delayMs = 0L)`) instead of calling it inline. It returns `SCHEDULED`, as it always did, before any editor work.
- Nothing else changes: the attempt, its retries every 125 ms, its 2.5 s deadline, every outcome, the pin release and the recording (#359) are the same code on the same thread, one looper pass later. `close` and `abandon` already remove the retry, so an insertion abandoned before its first attempt finalizes as before.
- `MainThreadHandoff` is unchanged. Its untimed wait stays the right rule for a body that has started; the audit's "remove the untimed get" is not needed once the body is short, and removing it would reopen the double-claim the rule exists for. Its KDoc now says the body no longer runs an accessibility action.

## 1. Edges

| Class | Case | Answer |
|---|---|---|
| Concurrent | a second request before the first attempt | Refused `INSERTION_ALREADY_PENDING`, as before: the pending insertion is set before the schedule. |
| Concurrent | an accessibility event before the first attempt | `scheduleRetry` is a no-op while one is scheduled; the attempt runs once. |
| Interrupted | `abandon` or `close` before the first attempt | Both remove the retry and finalize the pending insertion, as they do between retries today. |
| Stale | the deadline | Set at admission, so the one looper pass counts against the same 2.5 s. |

## 2. Tests

- `InsertionAdmissionShapeTest` (Drift Guard): admission makes no attempt inline, schedules the first after the pending insertion is in place, and the retry runs the attempt (m1: the first attempt made inline again).
- `MainThreadHandoffTest` keeps racing the claim in both orders.

## 3. Combined coverage and review round (Codex)

Every `Tick` still reaches the same handler; terminal results, the pin release and the History recording can now land after the handoff returns, which no caller read before (the answer was always `SCHEDULED`). The untimed wait stays necessary for a claimed handoff. Two findings, both adopted:

- An interrupt before the first attempt cancelled it but left the content-change mode on, which a quick `Verified` used to turn off inline in `finish`. The gap also existed between retries. `abandon` now turns the mode off when it ended a pending insertion (drift row 2, m2).
- `PasteAccessibilityService.callOnMain`'s KDoc still described the inline attempt; it now says admission posts its first attempt.
