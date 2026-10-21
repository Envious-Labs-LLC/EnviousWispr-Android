# Issue #453 — Real-length encoding and one pass up to 30 s — 2026-10-21

GitHub issue: `#453`. Tier: MEDIUM (one engine, `:asr` only). Status: APPROVED (founder full approval 2026-10-21; grounded r3 PROCEED-AS-PLANNED).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/asr/TdtRecipe.kt`, its unit test, the speed device test)
mixed_pr: true — Docs/dev-tooling for this plan and the knowledge update; obligation `cited-symbols`.

**PAR rows closed:** none (speed, not parity; macOS pads because its encoder has a fixed 15 s input, see §2.5.3).

**Hardware UAT:** Y. Success: on the S26 the founder dictates a short sentence into Messages and the words
appear in well under a second after he stops (transcription before polish), with the whole sentence intact;
a 20 to 30 second dictation also lands whole, faster than today.

## Preface — User Rubric

Persona: the busy professional (brand guide) firing off replies between meetings.

1. **Moment:** in Slack or Messages, thumb on the side button, 3 to 10 seconds of speech, wants to send now.
2. **Why:** "I talk faster than I type, but only if it shows up straight away."
3. **Invocation:** reactive, many times a day, already in the target app.
4. **Apps:** Messages, WhatsApp, Gmail, Slack, Chrome.
5. **Input:** "running ten late, start without me"; "yes Thursday works, send the deck"; "can you call me back
   after four"; a 25 s voice reply explaining a decision; "thanks, got it".
6. **Success:** the text is there the instant he lifts his thumb; he notices nothing.
7. **Wrong-not-broken:** faster but the last word goes missing ("send the" instead of "send the deck"); he stops
   trusting it. The lost-words gate exists for exactly this.
8. **Power-user hack:** splitting thoughts into short dictations because long ones feel slow.
9. **Control:** none wanted; this is pure engine speed with no new setting.

Cross-persona check: every persona benefits; none wants a slower, padded transcription. The tension is only
accuracy versus speed, resolved by the gate in §11: nothing ships that loses an ending.

---

Consolidation: none. One owner (`TdtRecipe`) changes how it sizes a window; nothing is merged or moved.

## 0. TL;DR

The speech engine pads every take shorter than 15 s to a full 15 s before the encoder runs, so a 3 s sentence
pays for 15 s of CPU work, and takes of 15 to 30 s pay for two overlapping 15 s windows. Encode short takes
at their real length (rounded to the 80 ms frame) and encode takes up to 30 s in one pass. Measured on Test
Lab phones 2026-10-21 (§1): a 3 s take drops from 0.87 to 0.18 s on a Galaxy S24, 2.1 to 0.54 s on an A35,
3.5 to 1.2 s on an A15; a 26 s take drops 35 to 38% on the S24 and A35. Transcripts were identical in
length on every clip and phone, and the lost-words gate lost 0 of 372 endings. Thread count stays 4 (6 and 2
were slower everywhere).

## 1. Problem

`TdtRecipe.decodeWindow` pads to `WINDOW_SAMPLES` (`samples.copyOf(WINDOW_SAMPLES)`), and `transcribe`
windows anything over 15 s into 15 s chunks with 2 s overlap.

Test Lab, personal project, 2026-10-21, `AsrSpeedDeviceTest` through the real `:asr` service, synthetic
speech, median of 3 resident requests per clip, five builds differing only in the recipe or thread count
(experiment worktree `feat/453-real-length`, never committed; logcats under the session scratchpad
`speed2/res/`):

| Phone | Clip | Padded today | Real length | Real + one pass | 6 threads | 2 threads |
|---|---|---|---|---|---|---|
| Galaxy S24 (SD 8 Gen 3) | 3.1 s | 870 ms | 178 | 189 | 324 | 281 |
| | 8.1 s | 923 | 473 | 454 | 664 | 713 |
| | 14.0 s | 828 | 735 | 758 | 985 | 1147 |
| | 26.0 s | 2433 | 2461 | **1513** | 2770 | 3804 |
| Galaxy A35 (Exynos 1380) | 3.1 s | 2135 | 535 | 536 | 686 | 729 |
| | 8.1 s | 2017 | 1119 | 1121 | 2761 | 1457 |
| | 14.0 s | 1708 | 1473 | 1556 | 3925 | 2218 |
| | 26.0 s | 5372 | 4715 | **3085** | 10736 | 7392 |
| Galaxy A15 (Helio G99) | 3.1 s | 3547 | 1192 | 1925 | 1108 | 1180 |
| | 8.1 s | 3314 | 1974 | 3033 | 2198 | 2370 |
| | 14.0 s | 2902 | 2666 | 3866 | 2782 | 3097 |
| | 26.0 s | 9025 | 8471 | **7197** | 7844 | 9484 |

The "real + one pass" build runs exactly the real-length code for clips under 15 s, so its A15 short and
medium figures (slower than the real-length build on identical code) measure a different physical device,
not the change: A15 numbers vary by about 1.5x between allocated phones. Speech memory (`:asr` PSS) during a
26 s one-pass request: 1316 / 1325 / 1371 MB on S24 / A35 / A15 against 1181 / 1195 / 1236 MB windowed.

## 2. Goals & non-goals

### 2.1 Goals
1. Takes under 15 s encode at their real length (frame-aligned). Verified by the unit test and the speed run.
2. Takes from 15 s up to 30 s decode in one pass, no seam. Same verification.
3. Takes over 30 s keep today's windowed path byte for byte: same schedule, merge and repair, and every
   window, final chunk and repair probe still padded to 15 s (review r1 finding 1: those windows are
   238,080 or 239,360 samples, so unpadding them would have changed long takes too).
4. Lost-words gate: 0/288 lost endings, 0/84 raw lost, no new lost starts, WER increase at most 0.005.

### 2.2 Non-goals
Thread counts, quantisation, other execution providers, other engines, warm-up changes (the model now stays
loaded by default, #37), the lower-tier unblock decision of #449 (a separate founder decision with its own
evidence), the macOS recipe.

## 2.5 Grounding brief

### 1. Producer to consumer
Audio file → `AsrService` worker (`asr/AsrService.kt`, `transcribeFileForTake`) → `ParakeetEngine.transcribe`
(`asr/ParakeetEngine.kt:12`) → `TdtRecipe.transcribe` → `decodeWindow` → `TdtRunner.encode(samples, length)`
(`asr/TdtModel.kt:32`, passes the tensor and the real length to the preprocessor and encoder) → greedy TDT
loop → `Result(text, windows, repairs)` → back to `AsrService` for the callback. Nothing else calls
`TdtRecipe`: `grep -rln TdtRecipe app/src` lists `ParakeetEngine.kt`, `TdtRunner.kt`, `TdtRecipe.kt` and
`TdtRecipeTest.kt` (plus `scripts/eval/asr-recipe/golden/Probe.kt`, the host harness). The other
`WINDOW_SAMPLES` constants (`audio/LiveGate.kt`, `vad/SilenceStopDetector.kt`) are unrelated.

### 2. Existing authority
`TdtRecipe` owns windowing; no second owner. The encoder already takes the real length. `decodeWindow` has
exactly four callers, all in `TdtRecipe.kt` (`grep -n "decodeWindow(" TdtRecipe.kt`): the one-window branch
of `transcribe` (line 45), the backfilled last chunk (63), an ordinary chunk (65) and the seam-repair probe
(348). Only line 45 changes to real length; 63, 65 and 348 keep the 15 s pad.

### 3. Prior attempts and live direction
#374 ported the macOS FluidAudio recipe, whose CoreML encoder has a fixed 15 s input on the Neural Engine, so
padding is free there; the Android encoder runs on the CPU with dynamic length axes. #433's note that "15 s
padding is fixed" predates this evidence; #433 recorded that unpadding ADDED final words on two human clips.
#421 kept the last window's end token (the "Frida" fix); that path is unchanged. Founder 2026-10-20 (session
log): keep the 15 s window until #453 earns a change with evidence, and lost words must not return; every
change runs the gate. Founder 2026-10-21 approved the speed work with the gate and paid Test Lab resources.

### 4. Boundaries
Only `:asr` and only the decode of one take. No AIDL, process, setting or persistence change. The `:asr`
watchdog bounds a decode by audio length; a faster decode only moves further inside it.

### 5. High-risk premises, with evidence
- The encoder accepts non-15 s tensors: executed, 15 phone runs and 372 host clips above.
- One pass over 30 s fits in memory: peak +135 to +150 MB measured at 26 s on three phones; 30 s is the cap,
  and §11.1 measures exactly 30 s and mixed lengths back to back before ship (review r1 finding 5).
- One pass bypasses `speechEnd` trimming, `lastChunkWarmup`, merge, duplicate collapse and seam repair for
  15 to 30 s takes (review r1 finding 3). Takes under 15 s already bypass all of them today; the gate's stress
  set includes trailing-silence and internal-pause cuts, where the one-pass arm lost 0 endings and fixed 3
  lost starts.
- Context-frame arithmetic (`context`, `startFrame`, `emitAfterFrame`) is untouched: the one-pass call uses
  `context = 0`, and every windowed call keeps today's padded tensor.
- The per-window token cap (`MAX_TOKENS_PER_WINDOW` = 150 for 15 s) would truncate a 30 s pass, so it scales
  with the real frame count (§3).

## 3. Design

`decodeWindow` gains a required parameter `padToWindow` (proposed; no default, so every caller states it).
`true` keeps today's `samples.copyOf(WINDOW_SAMPLES)`; `false` encodes the frame-aligned samples as they are.
In `transcribe`, the one-window branch covers takes up to `ONE_PASS_SAMPLES` (proposed, 30 s) instead of
`WINDOW_SAMPLES` and passes `false`; the three windowed and repair callers pass `true`. The token cap becomes
`max(MAX_TOKENS_PER_WINDOW, frames * MAX_TOKENS_PER_WINDOW / FRAMES_PER_WINDOW)` with `FRAMES_PER_WINDOW`
(proposed) `= WINDOW_SAMPLES / FRAME` = 187: 188 frames still allow 150 tokens, 375 allow 300, so every
windowed call keeps its cap of 150 (review r1 finding 4). It applies to ordinary emissions and the kept end
token alike.

Rejected: keeping a padded minimum of 8 or 12 s (slower, no accuracy gain in the gate); one pass for every
length (memory grows with length and 60 s of audio was not measured); changing threads (measured slower).

## 3b. Ownership
Stays in `TdtRecipe` because it already owns windowing; the alternative, a padding decision in `TdtModel`,
would split one rule across two files.

## 4. Contract deltas
`TdtRecipe.transcribe`: for takes up to 30 s, `Result.windows` is 1 (was 2 for 15 to 30 s) and `repairs` is
0; trailing silence is no longer trimmed and no seam processing runs for 15 to 30 s takes (it never ran under
15 s). Text contract unchanged. `decodeWindow`: the caller chooses padded or real length; only the one-pass
call chooses real length.

## 5. Lifecycle audit
- Takes by length: under the minimum (unchanged, empty), under 15 s (real length), 15 to 30 s (one pass),
  over 30 s (windowed, unchanged). Enumerated from `transcribe`'s branches.
- Process death mid-decode, cancellation, watchdog: unchanged paths in `AsrService`; enumerated, none touched.

## 6. Consumer matrix
| Delta | Consumer | Current | Required | Change? | Verified by |
|---|---|---|---|---|---|
| `windows` = 1 up to 30 s | `AsrService` log line `windows=` | logs 2 for 15-30 s | logs 1 | no | log read in UAT |
| real-length tensor | `TdtModel.encode` | 15 s tensor plus length | shorter tensor plus same length | no | phone runs |

## 7. Failure modes
| Failure | Origin | Caller | User sees | State | Retry |
|---|---|---|---|---|---|
| Encoder rejects a short tensor | ORT throws | `AsrService` worker | the existing transcription-failed outcome | none new | none automatic; next take |
| Allocation failure on a 30 s pass (Java or native) | ORT or `:asr` | `AsrService` / `RecognizerOwner` | an ORT exception is the transcription-failed outcome; a native abort is `:asr` death, the existing `ASR_PROCESS_UNRESPONSIVE`-class recovery | none new | none automatic; next take binds a fresh `:asr` |
| Silent wrong text: a word dropped at a 15 to 30 s ending or start | the recipe | user | a missing word, no error | History holds the short text | none; this is what the gate and §13 exist to prevent |
Existing recovery is unchanged; audio recoverability and the Java versus native allocation outcomes are
not verified by this plan's review. None was observed in 15 phone runs; the first two are bounded by the 30 s cap and §11.1's exact-30 s run.

## 8. Signals
`windows` and `repairs` in the `AsrService` decode log change meaning only by count; no UI reads them.

## 9. Fallbacks
Not present in this change: no new failure branch; existing ones keep their fallbacks.

## 10. Files
- `app/src/main/java/com/envi/wispr/asr/TdtRecipe.kt`: the edits in §3, comments updated.
- `app/src/test/java/com/envi/wispr/asr/TdtRecipeTest.kt`: assert the encoded tensor size the fake runner
  receives (it records it today and ignores it): real length for a 3 s and a 30 s take, padded 240,000 for
  every window of a 31 s take including its final chunk; one window and zero repairs at 30 s, more than one at
  30 s plus one frame; the cap at 187 frames (150) and 375 frames (300) for an ordinary emission and for the
  kept end token separately; a repair probe driven by a scripted speech-bearing gap encodes 240,000 samples.
- `app/src/androidTest/java/com/envi/wispr/AsrSpeedDeviceTest.kt`: adds the 26 s clip and per-request `:asr`
  memory to its report.
- `.claude/knowledge/tech-stack.md` or the owning ASR knowledge entry: the measured table, in place.

## 11. Testing
1. Class: `TdtRecipeTest` rows are Product Outcome against a fake runner (the take's words reach the
   transcript in one pass); the speed test is a Harness Contract.
2. Reverts, each performed and watched red before commit: pad the one-pass call again (tensor-size row);
   unpad a windowed call (31 s row); one-pass limit back to 15 s (one-window row); fixed cap of 150 (both cap
   rows); denominator 188 instead of 187 (the 375-frame row: 299 instead of 300); the repair caller alone set to
   real length (the repair-probe row).
3. Not tested by unit tests: real encoder accuracy (the host gate and phones own it).

Gate on every change: `scripts/eval/asr-recipe/golden/` host run of the 288 stress cuts and 84 raw clips,
scored by `endings/score_endings.py` against today's code.

### 11.1 Hardware UAT
- Subsystem: heart path (ASR).
- Recipe: `AsrSpeedDeviceTest` on Test Lab A15, A35, S24 with this build, clips of 3, 8, 14, 26 and exactly
  30 s, three requests each back to back in mixed order; the 40-take `SustainedDictationDeviceTest` on the
  same phones; the founder's S26 through Play.
- Oracle: transcripts identical to the measurement run's real-length arm per clip; the speed table reproduced
  within device noise; `:asr` PSS at 30 s reported and no process death, allocation failure or request past
  its bound; soak 40/40.
- Restore: nothing on the phones.

### 11.2 Other obligations
| Test | Class | Proves | Revert |
|---|---|---|---|
| unit suite count | — | nothing else broke | — |

## 12. Blast radius
`:asr` decode of takes up to 30 s only. Not touched: the windowed path above 30 s, capture, polish, insertion,
setup, models, settings. Rollback trigger: any lost ending reported by the founder or a later gate run, any
`:asr` death in a 15 to 30 s take, or the phone run missing its oracle. Revert the one commit (no data
migrates), then rerun the gate to confirm today's numbers return.

## 13. Ship criteria
- [ ] A short dictation on the S26 shows its words well under a second after stopping, whole.
- [ ] The A15, A35 and S24 reproduce the table with identical transcripts and 40/40 soak.
- [ ] Lost-words gate on the final code: 0/288 lost endings, 0/84 raw lost, no new lost starts, WER increase
  at most 0.005.

## 14. Open questions
None for the founder: the change has no setting and keeps every transcript.

## 15. Related
#449, #433, #374, #421, #37, #458.
