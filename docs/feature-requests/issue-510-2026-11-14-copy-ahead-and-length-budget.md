# Issue #510: copy-ahead decoding and one 15 s local polish ceiling, 2026-11-14

GitHub issue: `#510` (also closes the load branch question of `#465`, see §2.5.3). Tier: MEDIUM (one engine plus
the session owner's watchdog value; no AIDL, process or Room change). Status: DRAFT.

Stacked on PR #529 (`feat/eg1-micro-local-polish`, head `f30652ba`), because the seq-0 prefix cache this change
builds on and EG-1 Micro both arrive there. Retarget to `main` when #529 merges.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none (performance of an Android-only runtime; macOS polish uses a different runtime).

**Hardware UAT:** Y. Success: on a mid-tier phone (lab Galaxy A26 and Pixel 7, physical), a long dictation of about
500 characters (the field timeout median) polished on the processor returns polished text within the 10 s
budget, where the shipped build returns the cleaned-up text after the deadline; short dictations come back faster
than the shipped build with the same words; no take waits longer than 15 s.

## Preface — User Rubric

User Rubric: answered below against Meera Patel (primary) with a cross-persona check.

1. **Who.** Meera Patel, time-pressed parent, on a Galaxy A26 she bought for value. She double-presses the side
   button in WhatsApp to send a long message about pick-up times, then wants to put the phone down.
2. **Why.** "It just sits there for ten seconds and then gives me the rough version anyway."
3. **How.** Reactive, already in the app, one hand, mid-task.
4. **Apps.** WhatsApp, Messages, Gmail, Google Docs (the Android equivalents of her persona list).
5. **Natural input.** "hey can you grab Leo at three fifteen instead of four I've got the dentist", "so for
   Saturday we need the cake the balloons and um the paper plates", a two-minute school-email ramble of ~800
   characters, "running ten minutes late", "Tell Mom the flight lands at 5:40."
6. **Success.** She notices nothing: the polished text lands a few seconds sooner, and long messages arrive
   polished instead of rough.
7. **Wrong-not-broken.** Polish rewrites "three fifteen" as "3:50" or drops a name. The words must not change.
8. **Hack.** She turns polish off, or goes back to the keyboard's own voice typing.
9. **Control.** Off / This phone / Cloud already exists. No new control: speed is not a setting.

### Cross-persona check
Frank Chen and Meera want it faster and unchanged with no new setting (agree). Marcus Weber and Priya
Ramachandran care that words are not altered (the fidelity gate, §11). Dr. Elena Vasquez: nothing leaves the
phone (unchanged). Diana Foster and Aaron Wu: shorter waits, no new modal. Tension: polishing very long
dictations would need a longer wait. Resolved by the founder at Gate 2 (§3.2): no wait beyond 15 s; very long
dictations on slow phones keep returning the cleaned-up text.

Consolidation: the per-model local budgets collapse to one value: EG-1 Micro's separate engine and watchdog
constants are removed and both models read S1's shipped budget. The decode change merges no owners.

## 0. TL;DR

Local polish times out on mid-tier phones because writing out a long dictation takes longer than the 10 s budget
(field data, §1). Two changes on the portable llama.cpp runtime only: (1) copy-ahead decoding: guess the next 4
tokens from where the output last matched the input, check them in one batch with the same greedy sampler, keep
the matching prefix; measured 1.33x to 1.56x faster at the median on three physical lab phones; (2) one 15 s
ceiling for every local model (founder, Gate 2): EG-1 Micro's 18/20/23 s budget comes down to S1's shipped
10/12/15 s. GenieX (STANDARD phones) is untouched by (1); (2) changes it only for EG-1, which never runs there.

## 1. Problem

PostHog `dictation.terminal`, 30 days to 2026-11-14 (one-off SQL, governed catalog had no matching metric):

| Build | Tier | Runtime | Result | Takes | Generation p50 | Raw chars p50 |
|---|---|---|---|---:|---:|---:|
| 316 | MID | portable CPU | POLISHED | 45 | 1789 ms | 105 |
| 316 | MID | portable CPU | LOCAL_TIMEOUT | 5 | 10060 ms | 493 |
| 315 | MID | portable CPU | LOCAL_TIMEOUT | 4 | 10022 ms | 557 |
| 316 | STANDARD | GenieX | LOCAL_TIMEOUT | 1 | 11866 ms | 769 |

Every MID timeout on builds with the #511 fields spent its whole budget in generation; queue p90 was 1 to 3 ms and
the model was already loaded. Sentry `ENVIOUSWISPR-ANDROID-G` (`polish.local_deadline`): 110 events, 10 installs,
2026-10-29 to 2026-11-14, devices RMX3660 (58), 2510DRA23E (14), SM-A266B (19), SM-G998B, Pixel 7, SM-S366V,
Pixel 8, ALI-NX1. Generation time grows with length because the model rewrites the whole dictation one token at
a time and most of the output repeats the input.

## 2. Goals & non-goals

### 2.1 Goals
- G1. On the portable runtime, median generation at least 1.3x faster on the three lab phones in §2.5.5, same
  cases, same loaded model.
- G2. No dictated word added or lost against the shipped one-token loop on the fidelity set (§11); cosmetic
  differences (digit vs word, comma) are reported, not hidden.
- G3. No local polish holds the text longer than S1's shipped ceiling: cooperative 10 s, hard 12 s, watchdog 15 s,
  for both models and every dictation length.
- G4. G4 guarantees ordinary decoding after recoverable speculative failures; reconstruction failure returns the
  existing decode error and deterministic fallback.

### 2.2 Non-goals
- GenieX internals (vendor SDK; no decode loop of ours).
- Thread-count changes: measured, not a win with copy-ahead on (§2.5.5).
- Native OpenCL: measured slower than the cached processor path in-app on the S26 (2026-11-14 run
  `20261114-155819-eg1-micro-opencl-in-app-687d`).
- A user-visible setting or notice. #510's "too slow on this phone" notice stays a separate founder call.

## 2.5 Grounding brief

### 1. Producer to consumer
Raw text: `TakePolishController.prepare` (`ui/TakePolishController.kt`) arms the watchdog with
`timeout.await(takePreferences.policy)` and sends the request through the session owner's binder call; in
`:polish`, `PolishService` passes `localBudget(effectivePolicy)` into `work`, which calls
`S1PortableRuntime.generate(systemPrompt, userPrompt, maxTokens, cooperativeMs)` and then `S1Native.generate`,
whose loop is `generate_text` in `llama-android/src/main/cpp/s1_jni.cpp`. The hard deadline is
`EngineDeadline` with `LocalPolishBudget`. The qualification canary (`PolishService.qualify`) also generates
through the same runtime and must keep working.

### 2. Existing authority
Budgets: `LocalPolishBudget` (engine, `polish/EngineDeadline.kt`) and `PolishWatchdogBudget` (session owner,
`ui/PolishWatchdogBudget.kt`), ordered by `PolishWatchdogBudgetTest`. Decode: `generate_text` only. llama.cpp
ships a speculative framework (`common/speculative.h`, n-gram types `COMMON_SPECULATIVE_TYPE_NGRAM_*`) built
around the server's multi-sequence state; this plan uses the loop directly (§3.1 says why).

### 3. Prior attempts
`#510` comments: #511 instrumentation merged; the A26 six-call characterization showed generation as the larger
stage (8.2/9.3 s for 99-word fixtures); the library-greedy sampler study stopped at its quality gate. `#465`:
load hypothesis not reproduced; field data now shows load is not the timeout cause on MID (§1).
`polish-engines.md` FACT: tuning-was-tried-and-the-default-won records n-gram speculative decoding as slower,
measured on GenieX NPU with short inputs, a different runtime and input class.

### 4. Boundaries
`:polish` process; cooperative abort through `g_abort` checked between steps and inside decode; hard deadline
poisons and ends the process; prefix cache restored per request (PR #529). A recurrent/hybrid model (EG-1 Micro,
`general.architecture = qwen35`) cannot drop a partial sequence unless its context keeps recurrent-state
snapshots (`n_rs_seq`, `llm_arch_supports_rs_rollback` includes `LLM_ARCH_QWEN35`).

### 5. Premises, with evidence (bench `CopyAheadBenchDeviceTest`, branch `exp/polish-copy-ahead` `c72476e0`,
run folder `20261114-171908-polish-copy-ahead-e31f`, process held in the foreground cgroup)
Paired, same process, same loaded S1-mini, 12 cases (10 held-out benchmark cases plus 2 synthetic ~800-char):

| Phone (Test Lab) | 4 threads, shipped loop | 4 threads, 4 guesses | Long cases shipped → copy-ahead |
|---|---:|---:|---|
| Pixel 7 (panther, API 33) | 3144 ms | 2194 ms (1.35x) | 15.4-19.3 s → 8.9-13.8 s |
| Galaxy A15 5G (a15x, API 36) | 3364 ms | 2366 ms (1.33x) | 17.5-23.3 s → 12.8-20.1 s |
| Galaxy A26 5G (a26x, API 36) | 3172 ms | 1926 ms (1.56x) | 16.2-20.0 s → 8.2-13.6 s |

Two threads beat four only with copy-ahead off (Pixel 7, A15); with it on, four is equal or better.
Text: 10 of 12 identical to the shipped loop at 4 guesses on each phone; every difference so far is cosmetic or
removes an unspoken word the shipped loop had added ("five"/"5", a comma, "can you" vs the spoken "can"). The
likely cause is batched numerics flipping near-ties (thread count alone does the same: 11 or 12 of 12); not
verified at the first-divergence logits, and the fidelity gate (§11) judges the words regardless.
EG-1 Micro on A26 (unpaired runs): 4 guesses with 5 snapshots, median 1.30x against the plain build; the two
synthetic rambles it reformats as lists did not speed up (12.6→13.1 s, 15.0→15.3 s); snapshots alone cost about
13% on the plain loop. A test process in Android's background cgroup gets 4 of 8 cores on the S26 and produced
4x timing noise; every number above was taken with a foreground service held.

## 3. Design

### 3.1 Copy-ahead in `generate_text` (portable runtime only)
After each accepted token, find the longest recent 3-, 2- or 1-token n-gram earlier in prompt+output and take up
to 4 following tokens as guesses. Decode the accepted token plus guesses in one batch with logits for each; the
same greedy `common_sampler` samples each position in order and accepts while it matches; the first mismatch is
the sampler's own token (already correct) and the rejected positions are removed with `llama_memory_seq_rm`. Abort
is checked after every decode, as today. If `seq_rm` fails or a batch decode fails, the remaining generation runs
the one-token loop (G4). Draft size 4: best or tied-best median on all three phones. Rejected alternative: the
`common_speculative` framework, designed for server slots with checkpoints and a draft context; for one sequence it
adds state we do not need. Rejected: a draft model (memory, one heavy model resident).

EG-1 Micro: load with `n_rs_seq = 5` and allow at most `n_rs_seq - 1` guesses; any other recurrent/hybrid model
with no snapshots keeps the one-token loop. S1-mini (`qwen3`, transformer) needs no snapshots.

**Closed classification.** Every loaded model is classified with `llama_model_is_recurrent` and
`llama_model_is_hybrid` (not a model list). Transformers use ordinary cache removal. Recurrent/hybrid models
speculate only within the EFFECTIVE `llama_n_rs_seq(ctx) - 1` read after context creation (llama.cpp may clamp
the request to zero); zero means the one-token loop.

**Recovery state on fallback (G4).** On speculative failure (batch decode failure or refused `seq_rm`), disable
copy-ahead for the rest of the generation, preserve committed token history, output, count, sampler state and
pending-token accepted/emitted status, then clear memory and restore the existing valid prefix or full prompt and
replay committed output without sampler accepts to rebuild attention state, recurrent state and valid logits
before resuming under the original allowance and deadline. A refused rollback leaves rejected recurrent state in
place, so re-decoding one token is not a repair; replay is. Reconstruction failure returns the existing decode
error and deterministic fallback.

### 3.2 One 15 s ceiling for both models (founder decision, Gate 2, 2026-11-14)
The founder rejected longer waits: people will not wait more than 10 to 15 s, and irritation starts near 5 s. So
no budget grows with dictation length. Both local models use S1's shipped budget: cooperative 10 s, hard 12 s,
watchdog 15 s. EG-1 Micro drops from 18/20/23 s. `LocalPolishBudget.forModel` and `PolishWatchdogBudget.forPolicy`
keep their signatures and their exhaustive `when` over `LocalPolishModel` (so a new model must still choose); the
`EG1_*` constants are removed and both arms return the shared value. Speed comes only from copy-ahead (§3.1).

Accepted consequence: a dictation whose generation exceeds 10 s still returns the cleaned-up text at about 10 s,
as today; for EG-1 Micro that now happens on shorter dictations than before (its generation is slower than S1's
on the same phone). EG-1 qualification retains the existing 60 s load allowance, uses a 72 s combined hard deadline and a 10 s cooperative canary timeout, and must still return AVAILABLE on A26 and Pixel 7.

The hard + 3 s relation orders budget durations, not absolute expiry times: Binder and worker-queue delay can make
the watchdog fire first, and debug overrides may intentionally invert ordering (both true today). The debug
override, `debugStall`'s `MAX_OVERRIDE_MS` bound, and the Cloud, Off and CloudUnconfigured budgets are unchanged.

## 3b. Ownership
The decode loop lives in `s1_jni.cpp` because it is the only owner of the portable runtime's generation; the
budgets stay in `LocalPolishBudget` and `PolishWatchdogBudget`, which already own them.

## 4. Contract deltas
`S1Native.loadModel` gains a recurrent-snapshot count (0 for S1). `LocalPolishBudget.forModel` and
`PolishWatchdogBudget.forPolicy` keep their signatures; EG-1's values change to S1's. `lastEnd()` unchanged; a debug-only `lastCopyAheadStats()` reports decode/drafted/accepted counts.

## 5. State and lifecycle
Per generation only: history tokens and the guess buffer live on the native stack; nothing persists between
requests. The prefix cache snapshot is taken before generation and is unaffected (rejected positions are beyond
the cached prefix). Abort mid-batch leaves KV beyond `position`, cleared by the next request's restore, as today.

## 6. Consumers
`generate_text` callers: `PolishService.work`, qualification canary, warm path (no generation),
`Eg1MicroHardwareDeviceTest`, benchmark module (`CpuBenchmarkEngine`, `PortableCpuDeviceTest`). Budget readers:
`PolishService.work`, `PolishService.qualify` (two reads: `ProcessingQualificationTask`'s load-plus-hard
deadline, now 60 s load + 12 s hard = 72 s, and the canary's 10 s cooperative timeout), `debugStall`
(`MAX_OVERRIDE_MS`, unchanged), `TakePolishController` watchdog through `DelayPolishTimeout`.

## 7. Failure modes
Every exit preserves the output so far, the generated-token count, the stop reason and `lastEnd()`'s four-field
contract.

| Exit or failure | Caller sees |
|---|---|
| EOS before batching | `END_EOS`, as today |
| EOS while accepting guesses | `END_EOS`; guesses after the EOS are discarded, never emitted |
| token allowance reached before or during acceptance | `END_LENGTH`; acceptance stops at the allowance |
| guess batch decode fails | recovery per §3.1, then one-token loop; same outcome as today |
| `seq_rm` refuses | recovery per §3.1, then one-token loop |
| not enough context room or batch slots for the guesses | guesses shrink to fit; zero means a one-token step |
| prefix cache miss, failed or throwing restore, suffix-decode failure | full-prompt retry where today retries, otherwise today's error; copy-ahead starts only after prompt decode |
| abort during batch | `END_ABORTED` takes precedence over acceptance; `LOCAL_TIMEOUT` path unchanged |
| next request after any of the above | starts from a clean restored prefix, as today |
| budget still exceeded | deterministic text, `LOCAL_TIMEOUT`, as today |
| process death | unchanged (`SERVICE_DIED`) |

## 8. Signals
Add copy-ahead counts (decodes, drafted, accepted) to the local log line beside `GenerationEnd.logLine` (counts
only). Telemetry: no new field in this change; generation time already reports the effect.

## 9. Fallback source of truth
Unchanged: the deterministic cleanup text, the last successful text.

## 10. Files
`llama-android/src/main/cpp/s1_jni.cpp` (loop, lookup, snapshots), `llama-android/.../S1Native.kt`,
`polish/S1PortableRuntime.kt`, `polish/EngineDeadline.kt`, `ui/PolishWatchdogBudget.kt` (and the budget comments
in the last two), tests below. Knowledge `polish-engines.md`: update FACT: the-polish-budgets for the single
15 s ceiling (EG-1's separate budget retired, founder 2026-11-14); scope the historical n-gram result in FACT:
tuning-was-tried-and-the-default-won to GenieX NPU with short inputs.

## 11. Testing
- JVM: `EngineDeadlineTest` (both models return 10 s / 12 s; override unchanged), `PolishWatchdogBudgetTest`
  (both local models 15 s; watchdog > hard for both; Cloud, Off, CloudUnconfigured unchanged).
- Native decode paths (device, Harness Contract, through a debug-only fault switch read like the bench's
  environment switches): batch decode failure, `seq_rm` refusal, EOS during acceptance, length during
  acceptance, abort during batch, context nearly full. Each fault test must assert that speculation and its
  targeted branch occurred (from the copy-ahead counts and a branch marker), and the suite must fail with
  copy-ahead removed and with acceptance forced always true on a witnessed mismatching draft. Recovery tests
  compare complete output and end metadata on parity-stable fixtures; abort, EOS and length tests assert their
  specific termination contracts; every test also checks the next request is correct. Gating: S1 (transformer)
  speculates with zero snapshots; EG-1 with zero snapshots falls back to the one-token loop; EG-1 with 5
  speculates.
- Fidelity tests report literal differences and adjudicate every word addition or loss against explicit
  expected text, including independently seeded oracle errors (a planted added word and a planted dropped word
  the oracle must flag).
- `Eg1MicroHardwareDeviceTest`'s direct `loadModel` calls gain the snapshot argument.
  Its `EG1_EMAIL_MAX_LATENCY_MS` (20 s, sized to the old budget) drops to the 10 s cooperative budget.
- EG-1 qualification retains the existing 60 s load allowance, uses a 72 s combined hard deadline and a 10 s cooperative canary timeout, and must still return AVAILABLE on A26 and Pixel 7. (Product Outcome, through `PolishService.qualify`.)
- Device (Harness Contract → Product Outcome where noted): fidelity set of at least 40 cases (held-out benchmark
  plus long synthetic) on the lab A26: words added/lost counted by a word-diff oracle against the shipped loop;
  Product Outcome row through the real `:polish` binder on A26 and Pixel 7: a ~500-char take (the field timeout
  median) returns `POLISHED` within the 10 s budget where the shipped build returns `LOCAL_TIMEOUT` at the same
  input; ~800-char takes are measured and reported, not required to polish.
- `Eg1MicroHardwareDeviceTest` cache-parity rows keep passing.

### 11.1 Hardware UAT
Lab A26 and Pixel 7 (physical, Test Lab, personal-credit project), plus one silent real take on the S26 to prove
the GenieX path is unchanged.

## 12. Blast radius & rollback
MID phones on the portable runtime and every EG-1 Micro user. Rollback: draft size 0 restores the shipped loop
(one constant); restoring the `EG1_*` constants restores EG-1's old budget.

## 13. Ship criteria
G1-G4 met with the evidence above re-taken on the final build; Codex all-clear with confirming re-run; Play
internal build on the founder's phone before merge.

## 14. Open questions
Resolved at Gate 2: one 15 s ceiling (§3.2). Open: whether EG-1's 13% snapshot overhead is worth it on dictations it reformats (current data says
net 1.30x; re-measure paired in the fidelity run).

## 15. Related
#510, #465, #529, #433, `polish-engines.md`, run folders `20261114-155819-eg1-micro-opencl-in-app-687d`,
`20261114-171908-polish-copy-ahead-e31f`.
