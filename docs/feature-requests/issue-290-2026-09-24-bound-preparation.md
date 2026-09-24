# Issue #290: bound the preparation before capture (2026-09-24)

GitHub issue: `#290` (also closes `#294`). Tier: LARGE (app start of a take; the heart's trigger path). Status: revised after the coverage round (`290-cov`) and grounded round 1 (`290-g1`), every finding adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take still reaches live and its start timings are recorded (spoken audio blocked by #273; the start chain runs before any audio). The failure paths are JVM rows through `DictationSessionRig`.

## Preface — User Rubric

User Rubric: persona, the founder pressing the side button on his S26. Today, if building his custom-words matcher throws or hangs, or reading his polish setting hangs, the recorder can sit on "starting" and never record. After this change the take always goes on to record within a fixed time: a slow or broken matcher means that take restores no custom words, and a slow policy read means that take polishes on the last setting read (or publishes the cleaned-up words with the polish notice); either way one defect is raised.

---

## 0. TL;DR

REF-01 of `docs/audits/2026-09-23d-senior-audit.json` (`architecture-rules.md` FACT: heart-and-limbs; RULE: isolate-limbs). In `DictationSessionCoordinator.beginSession`'s start coroutine, the settings read is bounded (#193, `SETTINGS_ANSWER_BOUND_MS`) and the journal admission is bounded (#176, `JOURNAL_ADMISSION_DEADLINE_MS`), but the vocabulary matcher compile (`withContext(Dispatchers.Default) { StructuredTermRestorer.compile(...) }`) and the policy read (`withContext(Dispatchers.IO) { loadPolicy() }`) are not: a hang holds the take in STARTING forever, and a throw ends the start coroutine with no handler, which also leaves it in STARTING. Bound both under one preparation deadline, with a limb's fallback for each and one defect.

Consolidation: none. One start chain gains one deadline and two fallbacks.

Prior context: #193 (settings readers are limbs with a bound and a fallback token), #176 (journal admission bounded at 300 ms and never gating), #258 (the start timings `matcherReadyMs`, `policyLoadedMs`), #278 (`PolicyRead.Failed(lastRead)` and `takePolicy`; the process-wide `PolicyReader`).

## 1. Grounding (main 2bfc820)

- The start coroutine runs on the owner's `scope` (IO in production; no exception handler). A throw from `StructuredTermRestorer.compile` propagates out of `withContext`, ends the coroutine, and nothing moves the take out of STARTING (the capture's live deadline is armed only after `bindPipelineServices`).
- `loadPolicy()` is `ProviderConfigurationRepository.loadPolicy()` = `loadPolicyWith { preferences.all }`; `PolicyReader.read` never throws, but `preferences.all` can block on the first disk load.
- Both calls are plain blocking work: `withTimeoutOrNull` around `withContext` cannot interrupt them, so the bound must race an `async` against the deadline and abandon the loser.

## 2. Design

Every step from the start command to `bindPipelineServices`, classified (coverage finding A):

| Step | Where | Today | After |
|---|---|---|---|
| `admitTake(takeId, trigger)` | synchronous, main | a throw ends `beginSession` | wrapped: a throw logs, gives no admission, and never prevents capture |
| settings `awaitAnswers(answerBoundMs)` | start coroutine | bounded, fallback (#193) | unchanged |
| matcher compile | start coroutine | unbounded; a throw kills the coroutine | bounded, fallback (below) |
| policy read | start coroutine | unbounded | bounded, fallback (below) |
| admission wait | start coroutine | bounded; a FAILED admission throws through `withTimeoutOrNull` | a failed admission is caught and logged; never gates |
| `takePolicy`, `preferences.freeze` | start coroutine | pure construction | unchanged (no blocking work, nothing that throws on valid input) |

The new bound covers the matcher and the policy only, after the settings wait. The 3 300 ms figure (settings 2 000 + preparation 1 000 + admission 300) bounds the three planned WAITS before bind; it excludes the synchronous start work (telemetry, surface, foreground, the pin, `capture.begin`), main-thread scheduling, the bind and the capture. `admitTake` must stay a non-blocking enqueue; the wrapper catches a throw, it cannot bound a blocked call.

1. `PREPARATION_BOUND_MS` (proposed) = 1 000, a constructor parameter like `answerBoundMs`. One deadline, taken once from the monotonic host clock before either job starts.
2. The matcher and the policy start as SIBLING `scope.async` jobs on their dispatchers (`Default`, `IO`), not inside a `coroutineScope` (external), so a blocked loser never holds the start coroutine on exit. Each is awaited with the time left; a result that completed is kept even if the other timed out.
3. Each job catches ordinary exceptions itself and rethrows `CancellationException` (never a bare `runCatching`).
4. Matcher fallback: the empty matcher, compiled once outside the raced job, AND empty effective terms: `freeze` gains an `effectiveTerms` parameter (the snapshot's terms on success, `emptyList()` on the matcher fallback), so the frozen take restores nothing and prompts with nothing; one `AppDefect.TakePreparationFailed` (proposed) with `take_id`, `step = matcher`, `kind = timeout | error`.
5. Policy fallback on a timeout or an ordinary exception: `PolicyRead.Failed(lastReadPolicy())`, where the required constructor seam `lastReadPolicy` is wired in `DictationSessionService` to `ProviderConfigurationRepository.lastProcessRead()` (proposed), a read of the process reader's volatile value that never waits for its synchronized `read`. `takePolicy` then does exactly what #278 does, and the controller raises `PolishPolicyUnreadable` once; no second defect. A late policy read that completes after the deadline still updates the process reader's last read (for the next take); this take ignores it.
6. A cancel or a destroy during the wait: after the waits, the coroutine switches to main and checks `STARTING` BEFORE calling `takePolicy`, reporting a matcher defect, freezing or binding, so neither path reports a preparation or policy defect. A cancel of the take cancels both jobs; a destroy cancels their owner scope.
7. The matcher and policy compile and read are injected (`compileMatcher`, the existing `loadPolicy`), so the rig can hold or throw them.
8. Telemetry: `TakePreparationFailed` joins `AppDefect.all()`, `SentrySchema.semanticIdOf` and `defects`, and `TelemetryContractsTest`'s fingerprint snapshot; `SentrySchema` gains the values `step = matcher` and `kind = timeout | error`. Only those fixed tokens and `take_id` leave; no terms, policy contents or messages.
9. `takeFacts.matcherReadyMs` and `policyLoadedMs` record when each result or its fallback was taken.

## 3. Tests

Rig seams: an injected matcher compile (throws, or holds on a latch) and a suspend `loadPolicy` that holds; `lastReadPolicy` returns a set value; a shortened preparation bound. Each row waits on a subject-fired signal (the capture start or the terminal) and asserts its fallback and the exact defect count.
1. A matcher compile that throws: the take reaches capture; the frozen matcher and the frozen terms are both empty (both fields asserted); exactly one `take_preparation_failed` with `step = matcher, kind = error`. MUTATION m1: the matcher job's catch removed.
2. A policy read that never answers (#294): the take reaches capture within the bound; the request policy is the `lastReadPolicy` value (a `Cloud` policy); exactly one `polish_policy_unreadable`. MUTATION m2: the policy awaited without the deadline.
3. A matcher that never finishes: the take reaches capture with the empty matcher and empty terms (both fields asserted); exactly one defect with `kind = timeout`. MUTATION m3: the matcher awaited without the deadline.
4. Parallel: with the matcher held, the injected policy reader starts AND answers before the preparation deadline and before the matcher's fallback is taken (the rig records the times against the scripted deadline), then the matcher is released. MUTATION m4: the policy job started only after the matcher returned.
5. `admitTake` throws: the take still reaches capture. MUTATION m5: the wrapper removed.
6. An admission that completes exceptionally: the take still reaches capture and logs the failure. MUTATION m6: the admission wait's catch removed.
7. A cancel during the preparation wait: no preparation or policy defect, no bind. MUTATION m7: `takePolicy` called before the `STARTING` check.

## 4. Blast radius

Every take's start. On a normal day both finish in milliseconds (#258's emulator timings: matcher and policy under 15 ms) and nothing changes. On a hang or a throw the take now records instead of sitting in STARTING. Telemetry: one new defect. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m7 RED; the suite green 20 times for the new rows; app and androidTest build.
- [ ] Emulator: a take reaches live with its start timings logged.
- [ ] Codex code review ALL-CLEAR.

## 6. As built (2026-09-24)

- `DictationSessionCoordinator`: `PREPARATION_BOUND_MS` = 1 000; the matcher and the policy run as sibling `scope.async` jobs; `preparing` catches ordinary exceptions and rethrows cancellation; `awaitBy` takes each answer by the one deadline, or an answer already finished, else `timeout`. On main, after the `STARTING` check: a failed matcher freezes `StructuredTermRestorer.compile(emptyList())` with empty terms and raises `TakePreparationFailed` (`step = matcher`, `kind = timeout | error`); a failed policy becomes `PolicyRead.Failed(lastReadPolicy())` for `takePolicy`. `admitTake` is wrapped; a failed admission is caught.
- New seams: `lastReadPolicy` (required; production `ProviderConfigurationRepository.lastProcessRead()`), `preparationBoundMs`, `compileMatcher`. `SessionPreferencesSource.freeze` takes `effectiveTerms`.
- Telemetry: `AppDefect.TakePreparationFailed` in `DefectIdentity.all()`, both `SentrySchema` lists and the fingerprint snapshot; `SentrySchema` `step` gains `matcher`, and a new `kind` key allows `timeout` and `error` only.
- Also corrected (#295): the History bound's constructor comment and the publication comment still said the words wait for the save; both now describe #277's order.
- Code review round 1 (all four findings adopted): (1) each job stamps its finish on the host clock (`Timed`), and `awaitBy` refuses an answer stamped past the deadline even if it is already there, and takes one stamped in time even if the wait timed out first (row 8, m9); (2) a cancel in STARTING DOES change the state (`cancelStarting`, STARTING to CANCELLING) while the scope lives, so the jobs are take-owned (`preparationJobs`, registered then rechecked) and the cancel cancels them; row 7 cancels while the matcher is held and requires the held policy read to be cancelled well before a 3 s bound, with no defect and no bind (m7, m8); (3) the frozen `terms` field was read by nothing in `app/src/main`, so it is deleted with `freeze`'s extra parameter rather than tested; (4) rows 2 and 3 assert the subject's own start timings: the bind came at least the bound and under a second past it after the settings answer.
- Deviations: (a) row 7 cannot await the take's terminal (the rig's capture never sends the ending a cancel waits for, as the #258 rows note); it asserts the cancel reaching the jobs. (b) The #258 start-timing row now checks that the first two offsets are literal and the rest rise in step order and stay offsets: the jobs' finish stamps are clock reads on their own threads, so later literals would be scheduling-dependent.
- Code review round 2 (both adopted): the policy fallback is `lastReadPolicy()` read BEFORE this take's read starts (`priorPolicy`), because a late read replaces the process's last read as it lands; row 8's rig reader now does the same (m10). Row 7 cancels only once the policy read is in its held read (`policyEntered`).
- Mutation receipts: `docs/audits/2026-09-24-290-mutation-receipts.txt`, 10 of 10 RED in one final run. Full unit suite: 1285 tests, 0 failures. `TakePreparationBoundTest` and `DictationSessionCoordinatorTest` each 20 of 20. App and androidTest build; `check-visibility.py` clean.
- Emulator (the audio input works again): a spoken Gmail take went live, transcribed 45 characters and landed by COMMIT, verified by the app's own outcome line; its terminal log read `start: settings=151 matcher=161 policy=161 admission=26 bind=180 live=4726` (no fallback).

## 7. Related

#193, #176, #258, #278, #294; REF-01 and REF-05 of the fourth 2026-09-23 audit.
