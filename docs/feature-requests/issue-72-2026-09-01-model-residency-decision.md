# Issue #72 — Model residency: decide by measurement when the polish model may load — 2026-09-01

GitHub issue: `#72`. Tier: LARGE (session ownership and both engines' load timing). Status: SHIPPED (decision recorded; today's shape kept).

Phase 2 of [`plan-2026-09-01-ai-polish-refinement-roadmap.md`](plan-2026-09-01-ai-polish-refinement-roadmap.md).
Depends on #69 (shipped in #71): the warm-up call now carries the session's policy, so moving it is safe.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (unit-tests.xml, codex-review.md, hardware-uat.json) and `Docs/dev-tooling` (this
plan, `.claude/rules/architecture-rules.md`, `.claude/knowledge/polish-engines.md`, `scripts/` measurement
tooling; cited-symbols).

**PAR rows closed:** `none`. `PAR-066` is unchanged; `PAR-038` (unload schedule, #37) follows this decision
and is not closed here.

**Hardware UAT:** Y. The measurement IS the hardware run: repeated dictations on the S26 Ultra with real
speech through the phone speaker, in four warm-up shapes and two memory conditions, recording time to
text, model latencies, per-process memory, the phone's free-memory margin, thermal status and any process
kills. Success is a decision the numbers support, recorded in the architecture rule, and the winning shape
shipped with the founder's dictation feeling no slower than today.

## Preface — User Rubric

1. **Who is this user in this moment?** Priya Ramachandran, staff engineer, in Slack with Chrome, Gmail and
   Notion open behind it. Thirty seconds ago she pressed the side button and spoke a two-sentence reply.
   In thirty seconds she wants the text in the field and her other apps exactly where she left them.
2. **Why would they want this?** "It should be fast, and it should not throw my other apps out of memory."
   She would never say "the two models are resident at once".
3. **How would they invoke it?** Never. This decides what the app does behind every dictation with This
   phone selected.
4. **What app are they in?** Slack, Chrome, Gmail, Notion; Marcus Weber in Google Docs with long takes.
   The felt cost of a wrong decision is either a longer wait after speaking or Chrome reloading its tabs.
5. **What is their natural input?** The fixture sentence set, spoken by a synthetic voice: "um so the
   meeting moved to thursday can you update the deck and send the pricing page to the customer by friday,
   three things first the roadmap second the hiring plan third the offsite" (eleven seconds).
6. **What does success feel like?** Nothing changes for her. The text arrives as fast as today and her
   apps are still warm when she switches back.
7. **What does wrong-not-broken look like?** A serial shape that adds a second and a half after every take;
   she blames the phone and turns polish off.
8. **What would a power user hack around this to get?** Turn off This phone and use a cloud provider,
   which is faster only when the network is.
9. **What level of control would they want?** None here; #37 gives her the unload schedule afterwards.

### Cross-persona check

Priya and Aaron Wu want the shortest wait; Dr. Elena Vasquez and Frank Chen want the phone to stay stable;
Marcus Weber's long takes are the worst case for both. The tension is latency against memory margin, which
is exactly what the measurement resolves. No persona wants the decision made by feel.

---

## 0. TL;DR

The architecture rule says one heavy inference process is resident by default and both resident is not a
supported state (`architecture-rules.md` RULE: isolate-limbs). Every This-phone dictation today loads the
polish model the moment the polish service connects, while the speech model is already resident, so the
product runs the unsupported state on every take and nobody has measured whether that is safe on the
founder's phone. This plan adds a debug-only override for WHEN the polish model warms (at connect, at
recording stop, at the transcription request, or when the transcript arrives), runs a scripted measurement
campaign on the phone in those four shapes under a rested and a loaded memory condition, decides from the numbers, records the
decision in the rule, ships the winning shape as plain code and removes the override.

## 1. Problem

**What runs today.** `bindPipelineServices` (`DictationSessionService.kt:333-355`) binds ASR and polish back
to back at session start. `AsrService.onCreate` (`AsrService.kt:133-138`) loads Parakeet on its executor
the moment the process is created. `onServiceConnected` for polish (`DictationSessionService.kt:196-198`)
calls `warmUpWithPolicy(sessionPreferences.policy)`, and with `LocalS1` the engine loads S1-mini
(`PolishService.kt:133-135` → `ensureModelLoaded`). Both heavy models are therefore resident for the whole
recording.

**Measured 2026-09-01, one trial dictation on the S26 Ultra** (harness in §2.5.1, scratchpad
`runs-trial/trial2.log` and `mem-trial2.csv`): S1-mini `Ready on GPU in 1495ms` at connect, before the
user had finished speaking; transcription 0.52 s; polish 0.82 s (`720ms` engine-side); peak RSS during
the session `:asr` 1,416 MB, `:polish` 379 MB, main 157 MB, `:audio` 111 MB; free memory fell from 4,155 MB
to 2,448 MB. One take, rested phone, nothing else open: enough to prove the shape, not the margin.

**Why it matters.** The rule exists because both models are several hundred megabytes and the phone is the
founder's daily driver; an unsupported state that happens to fit today is a kill of his other apps on a
busier day, or of ours. The alternative, loading S1 only after transcription delivers, moves its load time
(about 1.2 to 1.5 s cached on GPU, 10 s on the first ever GPU load) into the wait the user feels.

## 2. Goals & non-goals

### 2.1 Goals

- G1. A measurement on the S26 Ultra, ten dictations per cell, in four shapes × two memory conditions,
  interleaved, with cold-process and cached-process runs reported as separate strata, plus a long-take
  stratum for the overlapping shape and the winning shape. Per run: time from recording stop to text handed
  off, transcription latency, S1 load latency and where it fell relative to the transcript, polish latency,
  peak PSS per process from an on-device sampler, minimum free memory and memory-pressure stalls, thermal
  status and battery temperature before and after, and every kill of our processes or of the everyday apps
  (a kill record in the log, a vanished pid, or a changed process start time). Individual values, median,
  maximum and range per cell; no p95 is claimed from ten runs.
- G2. A decision written into `architecture-rules.md` RULE: isolate-limbs with the numbers beside it:
  either the S26 exception for overlapping residency, or the serial shape as the supported default.
- G3. The winning shape shipped as plain code, with the measurement override removed.
- G4. `polish-engines.md` carries the campaign's numbers with date, device, build and thermal state.

### 2.2 Non-goals

- The user-facing unload schedule (#37) and the development-model surface (#21): they follow the decision
  as their own SMALL/MEDIUM changes.
- Changing when the SPEECH model loads. It is the heart and loads at service creation; only the limb moves.
- A latency budget or watchdog (phase 3) and the reason rendering (phase 4).
- Any change to the polish engine or the binder contract.

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

**The two loads.** Parakeet: `AsrService.onCreate` (`AsrService.kt:133-138`) → `initRecognizer` (`:149-185`)
on `transcriptionExecutor`; released in `onDestroy` (`:140-146`). S1-mini: `warmUpWithPolicy` at connect
(`DictationSessionService.kt:196-198`) → `PolishService.warmUpWithPolicy` (`PolishService.kt:133-135`) →
`ensureModelLoaded` (`:226-260`) on the engine's single worker; closed in `PolishService.onDestroy`
(`:215-223`). Both services are bound with `BIND_AUTO_CREATE` in `bindPipelineServices` and unbound in
`unbindPipelineServices` (`DictationSessionService.kt:979-990`), so both processes' lifetimes are the
session's binding, plus the system's own cached-process lag.

**The four moments a warm-up could be issued**, each already a named point in the session owner:

| Shape | Moment | Site | What overlaps with the S1 load |
|---|---|---|---|
| A, today | polish service connects | `onServiceConnected` (`:196-198`) | the whole recording |
| B | recording stops | top of `stopAndTranscribe` (`:435`), before audio finalisation and the transcription thread | audio finalisation plus transcription |
| B2 | the transcription request is sent | just before `transcribeFile` (`:479`) | transcription only (about 0.5 s on the trial) |
| C | transcript delivered | `IAsrCallback.onResult` (`:480-485`), before `polishAndPublish` | nothing: the load is inside the user's wait |

**The measurement path.** `am start -n com.envi.wispr/.ui.VoiceInputActivity --ez toggle true` starts a
session; `am start -n com.envi.wispr.test/com.envi.wispr.SpeakerPlaybackActivity` (exported in
`app/src/androidTest/AndroidManifest.xml:14-19`) plays the 16 kHz PCM fixture at `cache/enviouswispr-uat.pcm`
through the speaker; `--ez stop true` ends recording; the timeline is read from `logcat -v time` on
`DictationSession`, `PolishService`, `AsrService` and `ActivityManager` (external): `recording_stop`, `asr_request`,
`result_received` (`DebugLogger.mark` sites), `S1-mini loaded` (`PolishService.kt:246`), `polish_done`,
`Polish result received` and the handoff line (`Auto-insert handed` or `transcript kept on clipboard`).
Memory is sampled ON THE PHONE by a shell loop started with `nohup` (external) and read back after the run, not by
an adb round trip per sample: `MemAvailable` and `/proc/pressure/memory` (external) every 100 ms, and
PSS of our processes through `dumpsys meminfo <pid>` about three times a second (summed RSS double-counts
shared pages, so RSS is kept only as a diagnostic); thermal by `dumpsys thermalservice` and battery
temperature before and after every run; kills by `logcat -s lmkd ActivityManager`, by the opened apps'
pids, and by each pid's start time in `/proc/<pid>/stat`, so a restarted process with a reused pid still
counts. Chrome can discard tabs without its main pid changing; that is recorded as an unmeasured limit.

### 2. Find the existing authority before proposing one

| Concern | Existing authority | Note |
|---|---|---|
| When S1 warms | the one `warmUpWithPolicy` call at connect | moves, does not multiply |
| A debug-only behaviour override | `S1ModelSelector` reads the development model only in debuggable builds (`S1ModelSelection.kt:17-34`; issue #21) | the precedent for a `BuildConfig.DEBUG`-gated switch |
| Idle unload policy | `ModelLifecyclePolicy` / `ModelUnloadPolicy` (`model/ModelLifecyclePolicy.kt`), no caller | #37's job, untouched here |
| Latency marks | `DebugLogger.mark` (`DebugLogger.kt:108-113`), seven sites, plus the session owner's "Stopping recording and starting transcription" line as the stop anchor | the campaign reads them; no new marks |
| Speech through the speaker | `SpeakerPlaybackActivity` in the test APK | reused as is |
| Measurement scripts | none in `scripts/` | `new authority proposed`: `scripts/uat/residency-campaign.sh` (proposed) and `scripts/uat/residency-report.py` (proposed) |

### 3. Read prior attempts and live direction

- Roadmap phase 2 and the Codex consult (`roadmap-consult-output.txt.last` §3): "the written rules do not
  genuinely conflict"; stage 1 does not waive the architecture rule; "by default" leaves room for an
  explicit, capability-based exception that must be recorded in the rule; a single PSS snapshot and one
  load time establish the trade-off but not headroom; the settling measurement is repeated end-to-end
  sessions with ordinary apps open.
- `polish-engines.md` FACT: measured-numbers-2026-08-28: GPU cached load 1.21 s, first-ever 10.63 s;
  process memory after inference GPU about 1.62 GB, NPU about 0.36 GB; thirty sustained runs, thermal drift
  from 1,109 ms to 1,405 ms median. Those are benchmark-app numbers, not session numbers.
- #37 measured 2026-08-29 mid-dictation: `:asr` 917 MB, `:polish` 393 MB (NPU path then).
- Catalog: no decision on residency. macOS keeps its models loaded under a user-selectable unload schedule
  (`AppSettings.swift`, per #37), which is a different memory model (desktop) and not a precedent here.
- Session log 2026-09-01 (night): #69 shipped; the trial harness measured once with headphones connected
  by mistake, which routed the speaker fixture away from the microphone, so the harness now asserts
  Bluetooth is off before a campaign.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

| Boundary | Today | Under this plan |
|---|---|---|
| Session owner ↔ `:polish` warm-up | one call at connect | the same call, issued at the shape's moment; the engine is unchanged |
| Warm-up before the policy is latched | cannot happen: the policy is latched before `bindPipelineServices` | unchanged |
| Shape B or C when the service has not connected yet | n/a | `polishService` null → no warm-up; the request itself still loads (`LOCAL_NOT_READY` then next take) or the engine loads on the request's own thread; the campaign records how often this happens |
| Shape C ↔ user cancel between stop and transcript | n/a | `cancelRecording` never reaches C; nothing warms |
| Cached engine process across sessions | S1 stays loaded only while the Service object lives | unchanged; the campaign measures cold and warm loads separately by pid |
| Measurement override ↔ release builds | none | read only when `BuildConfig.DEBUG`; a release build ignores the file by construction |
| The other apps' processes ↔ our sessions | not measured | their pids are recorded before and after each run; a changed or missing pid is a kill |
| Bluetooth audio route | broke the first trial | `dumpsys bluetooth_manager` must read `enabled: false` before a campaign |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| The speaker fixture reaches the microphone and the whole path runs | trial2: `result_received` 0.52 s after `asr_request`, `Polish result received (S1-mini by Superwhisper (GPU), 720ms, chars=175)`, `transcript kept on clipboard` |
| Both models are resident during a take today | trial2 memory samples: `:asr` peak 1,416 MB and `:polish` 379 MB in the same session; S1 `Ready on GPU in 1495ms` logged during recording |
| The phone can host the loaded condition | 11.4 GB total, 4.2 GB available at rest (`/proc/meminfo`, 2026-09-01) |
| The three moments are distinct named sites | `DictationSessionService.kt:196-198`, `:435`, `:480-485` |
| A debug-gated override has precedent | `S1ModelSelection.kt:17-34` |
| Thermal is readable | `dumpsys thermalservice` prints `Thermal Status: 0` at rest |
| Kills are observable | `ActivityManager` logs `Killing` lines; the opened apps' pids are compared before and after |
| No other session holds the phone | `dumpsys package com.envi.wispr | grep lastUpdateTime` reads this session's install (16:46); the founder handed the phone over in chat |

## 3. Design

### 3.1 The override, debug builds only

`PolishWarmUpMoment` (proposed), an enum in `ui/`: `AT_CONNECT` (proposed), `AT_RECORDING_STOP` (proposed), `AT_ASR_REQUEST` (proposed), `AT_TRANSCRIPT` (proposed).
`SessionPreferences` gains `warmUpMoment` (proposed) (default `AT_CONNECT`, today's behaviour). In `beginSession`,
when `BuildConfig.DEBUG`, the session owner reads `files/debug/polish-warmup` (proposed) once per session
(one line, the enum name; absent or unreadable → default). The campaign writes that file with `run-as`
between cells. A release build never reads it: the read is inside an `if (BuildConfig.DEBUG)`, which R8
removes.

The four sites call one private `warmUpPolishIfDue(moment)` (proposed). Reaching the selected moment marks
the warm-up DUE (an atomic flag); if the polish binder exists it fires at once, otherwise
`onServiceConnected` drains the pending warm-up when the service arrives, so a service that connects after
the moment still warms. One atomic ISSUED gate makes the call happen at most once per session; every
terminal transition (`cancelStarting`, `cancelRecording`, `showError`, `finishSession`, `onDestroy`,
`onServiceDisconnected`) clears the pending flag. The engine is unchanged.

### 3.2 The campaign

`scripts/uat/residency-campaign.sh` (proposed): two memory conditions, blocked (rested: nothing else open
after `am kill-all`; loaded: Chrome, Gmail, Messages, Slack, Maps and Docs opened in turn and left in the
background). Inside a condition the four shapes are INTERLEAVED, one run of each per round in a rotating
order, so no shape inherits all the thermal drift; the run order is written to the output. Run 1 of each
shape in each condition is COLD: `am kill` first and a new `:polish` pid verified; the other runs are
process-cached (S1 is closed with the service, so "cached" means process and driver, never model-warm) and
the report keeps the two strata apart. Before every run: thermal status must be back at the baseline band
and battery temperature within 2 °C of the campaign's start, else the run waits; in the loaded condition
every opened app's pid must still exist and its PSS is recorded, a missing one is re-opened and noted.
Every run checks the transcript's length falls in the fixture's expected band (120 to 260 characters for
the eleven-second fixture), else the run is marked failed. A long-take stratum, three runs of a 40-second
fixture in the loaded condition, runs for shape A and for the winning serial shape. Per-run artifacts go
under `.validation/uat/residency/<condition>/<shape>/<n>/`. The script refuses to start when Bluetooth is
on, a wired or USB headset is connected, media volume is muted, the screen is locked, the fixture is
missing, the mode is not This phone, or another build was installed since this session's; it snapshots any
existing override file and restores or deletes it from an `EXIT INT TERM` trap.

`scripts/uat/residency-report.py` (proposed): per cell and stratum, individual values with median, maximum
and range of time to handoff, transcription, S1 load and its placement, polish; peak PSS per process;
minimum free memory and pressure; thermal range; kills of ours and of the opened apps; failed runs listed,
never averaged. One table, written to the plan's §11.1 and to `polish-engines.md`.

### 3.3 The decision rule, stated before the numbers exist

- **Safe** means: across the loaded condition's runs, including the long-take stratum, no kill of an
  EnviousWispr process, no kill of an opened app attributable to the run (a kill record, a vanished pid, or
  a changed process start time), no sustained memory-pressure stall (`/proc/pressure/memory` "full"
  climbing during the run), and thermal status never above 1. The lowest free memory observed in a safe run
  is REPORTED as the guardrail; it is not claimed as the low-memory killer's threshold, whose properties
  (`ro.lmk.*`, read with `getprop` before the campaign) are recorded beside it.
- If shape A is safe: record the S26 exception in `isolate-limbs` with the loaded-condition numbers, keep
  A, and the felt experience is unchanged.
- If A is not safe: ship the cheapest safe shape by median time to handoff (B, then B2, then C), and the
  rule stays as written with the numbers as the reason.
- If no shape is safe, the decision is that this phone cannot run local polish under load, which is a
  product fact for the founder, not an engineering one; the plan stops and reports.

### 3.4 Ship

The winning shape is hard-coded: `warmUpMoment` and the file read are deleted, the one call site remains
where the decision put it and the gate goes with the override. `GR-MIGRATION-COMPLETE`: no override survives the decision.

### 3.5 Alternatives rejected

- **Decide from the trial and the 08-28 benchmark.** One rested take is the "single PSS snapshot" the
  consult named as insufficient.
- **A user setting for the moment.** The user cannot judge memory headroom; #37's unload schedule is the
  user's lever and it comes after the decision.
- **Four builds instead of an override.** Four installs on the daily phone, each unbinding the
  accessibility service, for a knob that a debug-gated file read gives for free.
- **Unload Parakeet before loading S1.** Parakeet is the heart; the rule's "unload before switching" is
  about the limb, and reloading the speech model on every take is a 900 MB load per dictation.

## 3b. Ownership justification

The moment lives in `DictationSessionService` because it already owns the four sites and the policy
latch; the alternative, a separate warm-up scheduler object, would exist to hold one enum and be called from
the same three lines. The scripts live in `scripts/uat/` (new directory) because `scripts/` is the
tracked home for repository tooling and `device-testing.md` is prose, not code.

## 4. MANDATORY — contract deltas

| Type | Delta | Meaning |
|---|---|---|
| `SessionPreferences` | gains `warmUpMoment` (debug builds may set it; release is always `AT_CONNECT` until the decision, then the winner) | which of three named moments issues the one warm-up |
| `DictationSessionService` | one `warmUpPolishIfDue`, three new call sites, and the gate cleared at every terminal transition | no change to what is sent, only when |
| `architecture-rules.md` isolate-limbs | gains the decision and its numbers | the supported residency state on the S26 is written down |
| AIDL, `PolishService`, `AsrService` | no change | |

## 5. MANDATORY — end-to-end state and lifecycle audit

| Population | Members | Disposition |
|---|---|---|
| Warm-up call sites | `onServiceConnected` (`:196-198`) today; plus `stopAndTranscribe` top, the line before `transcribeFile`, and the ASR `onResult` under this plan | the selected moment marks due; connect drains a pending one; the issued gate fires at most once |
| Session ends before the selected moment | `cancelStarting`, `cancelRecording`, `showError`, `onDestroy`, polish `onServiceDisconnected` | clear the pending flag; nothing fires |
| Ends after B, B2 or C fired without using S1 | audio finalisation failure, missing audio path, missing ASR binder, ASR error, transcription exception, blank transcript | warmed-but-unused; the campaign records them; unbind as normal |
| Override lifecycle | previous value snapshotted; restored or deleted from the trap on success, refusal, timeout or interruption | a later session never inherits a campaign value |
| Readers of `SessionPreferences` | `beginSession`, `polishAndPublish`, the fallbacks, `onServiceConnected`, insertion | one new field, read at the four sites only |
| Measurement inputs the campaign must verify before a cell | Bluetooth off, no wired or USB headset, media volume audible, screen awake and unlocked, this session's build installed, fixture present and non-empty in both packages, mode This phone in the live process, thermal at baseline | the script refuses or waits otherwise |
| Everyday apps for the loaded condition | Chrome, Gmail, Messages, YouTube, Maps, Slack (whichever are installed; the report names the actual list) | pids recorded before and after every run |

Async edge cases:

| Class | Case | Answer |
|---|---|---|
| Interrupted | `:polish` dies between B's warm-up and the request | `onServiceDisconnected` → deterministic fallback, as #69 shipped |
| Stale | a warm-up issued for a session that has since ended | harmless: the engine loads, the next session reuses it or the process is reclaimed |
| Concurrent | the campaign's `am start` lands while the previous session is still finishing | the script waits for the handoff line or a 40 s timeout, then pauses before the next run |
| Absent | the override file absent | `AT_CONNECT` |
| Mutated | the file changed mid-session | read once at `beginSession`; applies next session |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `warmUpMoment` | the three call sites | one unconditional call | one conditional call each | yes | `PolishWarmUpMomentTest` (proposed): the parser and the "exactly one moment fires" predicate |
| the override file | campaign script | none | written per cell, deleted at the end | scripts | the script's own final `run-as rm` |
| decision text | `architecture-rules.md`, `polish-engines.md` | rule as written | rule plus numbers | docs | cited-symbols |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| S1 not ready when the request arrives (B or C) | load still running | engine | text cleaned by rules on this take, `LOCAL_NOT_READY` logged | `DETERMINISTIC` row | next take |
| Speaker fixture routed to headphones | Bluetooth on | campaign | no speech, empty transcript, run discarded | none | script refuses to start with Bluetooth on |
| Low-memory kill of an opened app | loaded condition | Android | that app reloads when she returns | none | recorded; it is the measurement |
| Low-memory kill of `:asr` or `:polish` mid-take | loaded condition | Android | error toast or rules-only text, as today | as today | recorded; it decides |

## 8. MANDATORY — caller-visible signals audit

| Field | Signal | Reader |
|---|---|---|
| `warmUpMoment` | which site fires; never more than one | the four sites |
| the S1 load line's pid and timestamp relative to `result_received` | whether the load fell inside the user's wait | the report |
| an opened app's pid changing between before and after | a kill attributable to the run | the report |
| `Thermal Status` | 0 none … 6 shutdown; the rule reads "never above 1" | the report |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch | Expression | Source | Why | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| model not ready at request | deterministic text via `PolishFallback` | #69 | unchanged | non-blank | raw transcript | `publishResult` |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: `warmUpMoment` in `SessionPreferences`,
  the debug read in `beginSession`, `warmUpPolishIfDue`, three new call sites and the gate cleared at every terminal transition.
- `app/src/main/java/com/envi/wispr/ui/PolishWarmUpMoment.kt` (proposed): the enum and its parser.
- `app/src/test/java/com/envi/wispr/ui/PolishWarmUpMomentTest.kt` (proposed).
- `scripts/uat/residency-campaign.sh`, `scripts/uat/dictate-once.sh` (proposed), `scripts/uat/memsample.sh`
  (proposed), `scripts/uat/residency-report.py`: the harness, promoted from the session scratchpad.
- After the decision: the override removed, the winner hard-coded; `.claude/rules/architecture-rules.md`
  RULE: isolate-limbs, `.claude/knowledge/polish-engines.md` (numbers), `.claude/knowledge/device-testing.md`
  (the campaign recipe and the Bluetooth trap).

## 11. Testing

1. **Class.** `PolishWarmUpMomentTest`: Drift Guard (the parser and the one-site predicate; a wrong value
   here shows as a second warm-up, which the campaign's log would also reveal). The campaign itself is the
   Product Outcome evidence and is recorded as hardware UAT, not as a test.
2. **Revert.** Parser: return `AT_CONNECT` for every input. Predicate: fire for every moment.
3. **Not tested.** Thermal and kill behaviour on any device but the S26; the emulator cannot answer any
   of it (`device-testing.md` RULE: the-emulator-cannot-answer-the-questions-that-matter).

### 11.1 Hardware UAT spec

- **Subsystem:** heart (the session owner's transcription-to-polish path) and the polish limb.
- **Recipe:** `scripts/uat/residency-campaign.sh` (proposed), added to `device-testing.md`.
- **Expected observation:** the report table for eight cells plus the long-take stratum; the oracle for the S1 load's placement is the
  pid-stamped `S1-mini loaded` line's position relative to `result_received`; the oracle for kills is the
  pid comparison; the oracle for margin is the sampled `MemAvailable` minimum.
- **Phone state to restore:** override file deleted; opened apps left as the founder had them (they are his
  apps; the script does not kill them); mode back to the value before the campaign; Bluetooth left off as
  he set it; media volume restored by the playback activity itself.

### 11.1.1 What the campaign measured, 2026-09-01

S26 Ultra, debug build installed 17:20:54, four runs per cell (run 1 cold, runs 2 to 4 cached, interleaved),
three 40-second long takes per shape in the loaded condition, no rest between runs (founder's call: the
back-to-back sequence is the stress test). 44 of 44 runs valid, of which eleven are warm-at-connect.
Thermal status reached 2 across shapes and the battery 41 °C by the end; without thermal gating the
campaign cannot assign that rise to the cadence alone or exclude a contribution from any shape.

| condition | shape | stratum | runs ok | wait ms median (min-max) | asr med | polish med | S1 load med | S1 placement med ms | peak PSS asr/polish MB | min free MB | PSI full max (delta) | app kills | ours killed | cached-process reclaims (sum) | thermal max | PSS samples/s | failed runs (why) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rested | AT_CONNECT | cached | 3/3 | 1504 (1463-1525) | 675 | 652 | 1159 | -13314 | 1733/1581 | 2027 | 0.91 (+0.58) | 0 | 0 | 0 | 2 | 3.7 | - |
| rested | AT_CONNECT | cold | 1/1 | 1423 (1423-1423) | 609 | 670 | 1491 | -12597 | 1518/1580 | 2522 | 0.72 (+0.67) | 0 | 0 | 1 | 1 | 4.1 | - |
| rested | AT_RECORDING_STOP | cached | 3/3 | 2404 (2269-2528) | 726 | 655 | 1177 | 940 | 1847/1580 | 2567 | 0.61 (+0.49) | 0 | 0 | 0 | 2 | 3.7 | - |
| rested | AT_RECORDING_STOP | cold | 1/1 | 2400 (2400-2400) | 565 | 644 | 1194 | 1109 | 1784/1579 | 3100 | 1.30 (+0.98) | 0 | 0 | 1 | 1 | 3.7 | - |
| rested | AT_ASR_REQUEST | cached | 3/3 | 2382 (2303-2416) | 741 | 666 | 1201 | 887 | 1737/1578 | 2581 | 0.89 (+0.83) | 0 | 0 | 1 | 2 | 3.8 | - |
| rested | AT_ASR_REQUEST | cold | 1/1 | 2978 (2978-2978) | 568 | 784 | 1572 | 1491 | 1573/1578 | 2496 | 1.78 (+1.31) | 0 | 0 | 6 | 1 | 3.6 | - |
| rested | AT_TRANSCRIPT | cached | 3/3 | 2878 (2818-2972) | 660 | 666 | 1047 | 1428 | 1786/1584 | 2672 | 0.62 (+0.35) | 0 | 0 | 0 | 2 | 3.8 | - |
| rested | AT_TRANSCRIPT | cold | 1/1 | 3508 (3508-3508) | 696 | 929 | 1273 | 1813 | 1639/1579 | 2693 | 1.78 (+1.36) | 0 | 0 | 13 | 2 | 3.4 | - |
| loaded | AT_CONNECT | cached | 3/3 | 1493 (1458-1519) | 679 | 659 | 1165 | -13456 | 1851/1584 | 2379 | 1.20 (+1.09) | 0 | 0 | 4 | 2 | 3.4 | - |
| loaded | AT_CONNECT | cold | 1/1 | 1779 (1779-1779) | 657 | 696 | 1810 | -12352 | 1507/1578 | 2605 | 3.17 (+2.02) | 0 | 0 | 20 | 2 | 3.6 | - |
| loaded | AT_RECORDING_STOP | cached | 3/3 | 2369 (2338-2398) | 695 | 712 | 1197 | 857 | 1856/1582 | 2631 | 1.34 (+1.20) | 0 | 0 | 0 | 2 | 3.5 | - |
| loaded | AT_RECORDING_STOP | cold | 1/1 | 2904 (2904-2904) | 770 | 799 | 1523 | 1138 | 1582/1580 | 2940 | 0.83 (+0.47) | 0 | 0 | 8 | 2 | 3.5 | - |
| loaded | AT_ASR_REQUEST | cached | 3/3 | 2311 (2310-2320) | 716 | 693 | 1170 | 839 | 1857/1580 | 2756 | 0.49 (+0.30) | 0 | 0 | 0 | 2 | 3.5 | - |
| loaded | AT_ASR_REQUEST | cold | 1/1 | 2573 (2573-2573) | 710 | 700 | 1382 | 1072 | 1621/1583 | 2849 | 0.99 (+0.39) | 0 | 0 | 0 | 2 | 3.4 | - |
| loaded | AT_TRANSCRIPT | cached | 3/3 | 2953 (2890-2964) | 659 | 697 | 1104 | 1485 | 1762/1585 | 2740 | 0.68 (+0.58) | 0 | 0 | 1 | 2 | 3.6 | - |
| loaded | AT_TRANSCRIPT | cold | 1/1 | 3089 (3089-3089) | 667 | 691 | 1230 | 1642 | 1604/1579 | 2845 | 0.34 (+0.05) | 0 | 0 | 0 | 2 | 3.4 | - |
| loaded-long | AT_CONNECT | cached-long | 3/3 | 6234 (6094-6542) | 2892 | 2512 | 1431 | -44330 | 1714/1574 | 2426 | 3.33 (+2.14) | 0 | 0 | 39 | 2 | 2.8 | - |
| loaded-long | AT_RECORDING_STOP | cached-long | 3/3 | 7481 (7273-7505) | 3161 | 3413 | 1480 | -1427 | 1817/1574 | 2525 | 1.23 (+1.01) | 0 | 0 | 3 | 2 | 2.7 | - |
| loaded-long | AT_ASR_REQUEST | cached-long | 3/3 | 7349 (7100-7537) | 3279 | 3496 | 1412 | -1447 | 1847/1575 | 2429 | 0.18 (+0.18) | 0 | 0 | 0 | 2 | 2.7 | - |
| loaded-long | AT_TRANSCRIPT | cached-long | 3/3 | 9327 (8759-11703) | 3143 | 3026 | 1935 | 2537 | 1767/1576 | 2765 | 3.19 (+3.19) | 0 | 0 | 26 | 2 | 2.6 | - |

**Reading it.**

- **Every shape reaches the same dual-model peak-residency class.** The speech model stays loaded after it
  transcribes, so by the end of every dictation both models are resident whatever the warm-up moment:
  `:asr` roughly 1.5 to 1.9 GB and `:polish` 1.57 to 1.59 GB PSS (the GPU compatibility model) in all
  twenty cells. A later warm-up only moves the overlap later; it does not remove it. The peak PSS column
  decides this, and it is not affected by run order.
- **The later moments cost the user.** Short take, cached: 1.50 s at connect against 2.31 to 2.40 s at stop
  or at the transcription request and 2.88 to 2.95 s at the transcript. Long take: 6.2 s against 7.3 to
  9.3 s. The single cold run per cell is not enough for a general cold penalty: loaded cold runs added
  0.14 to 0.54 s, while the rested connect and stop cold runs were not slower.
- **The founder's six everyday apps survived every run** (same pid and start time before and after), in
  every shape, including the long takes. The low-memory killer did evict CACHED background processes
  (Play Store, Facebook, Spotify, Chrome sandbox and renderer processes, system agents) during loaded and
  long runs; the counts are confounded with order, because the first shape after the apps were opened had
  the most to evict (shape A ran first in both loaded blocks), and the cold rested transcript run evicted
  thirteen. The reclaim column is therefore not comparative evidence between shapes.
- **No EnviousWispr process was ever killed.** The instrumentation test APK's process was reclaimed three
  times while idle, which is the killer doing its job on a cached process.
- **Memory pressure stalls were brief**: the `full` average never exceeded 3.3 %. On the long takes it was
  3.33 for shape A against 1.23 and 0.18 for the two middle shapes and 3.19 for shape C, so A's long-take
  pressure exceeded B and B2.

**Decision (§3.3 applied, read narrowly).** Warm-at-connect had the lowest wait and no observed
process-survival disadvantage in this 44-run stress campaign; the later moments did not reduce peak
residency and added about 0.8 to 3.1 seconds. It stays as the stage 1 default on the founder's phone.
This is not a claim of safety on every axis: the preregistered thermal criterion was exceeded in every
shape, and A's long-take pressure exceeded the middle shapes'. The architecture rule records the S26 measurement
as the supported state and names the two levers that would actually reduce residency, neither of which is
a warm-up moment: unloading the speech model once it has transcribed (its cold initialisation measured
3.35 s, hidden inside a recording longer than that), and the NPU model (0.36 GB against the GPU model's
1.58 GB). Filed as #73 (release the speech model after transcription) beside #21 (the NPU model surface).

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `PolishWarmUpMomentTest` | Drift Guard | parser maps the three names and defaults on anything else; exactly one moment is due per session | default on a valid name; make two moments due |
| `scripts/measure-tests.sh` | count | suite green | n/a |

## 12. Blast radius & rollback

- **Touched:** `DictationSessionService` (four call sites, six clears, two helpers), one new enum with its gate, one test, `scripts/uat/`,
  two rule/knowledge files.
- **Not touched:** the engine, the AIDL, `AsrService`, every screen, Room.
- **Rollback:** revert the merge; the override file, if left, is ignored by a build without the read.

## 13. Ship criteria specific to THIS change

- [x] Eight cells × four runs (founder's call, for the stress cadence), cold and cached strata apart, plus
      the long-take stratum, recorded under `.validation/uat/residency/`, the report table in §11.1.1.
- [x] The decision written in `isolate-limbs` with the numbers, and the winning shape the only code path.
- [ ] The founder's own dictation after the change feels no slower than before (his felt-experience pass).

## 14. Open questions

1. **Settled by the review (2026-09-01).** The 1 GB free-memory floor was rejected: `MemAvailable` is not
   the low-memory killer's trigger. Safety is now defined by kill records and pressure stalls, with the
   lowest safe free memory reported as a guardrail beside the phone's `ro.lmk.*` (external) properties.
2. **Settled by the review.** Shape B stays (it overlaps audio finalisation as well as transcription, so it
   is distinct from C) and a fourth shape, warm at the transcription request, was added because B and C do
   not bound the partial-overlap option.

## 15. Related

#72 (this), #69 / #71 (phase 1), #37 (unload schedule), #21 (development model), roadmap phase 2,
`polish-engines.md`, `device-testing.md`, `phone-audio-playback.md`.

Consolidation: none. One enum, one helper, one directory of scripts; nothing merges into another owner.

---

## Review log

- **Combined coverage and design round, 2026-09-01, Codex session `01a05e59-b013-71f0-b3ed-3af5726e5254`:**
  PROCEED-WITH-REVISIONS. Adopted, scaled to one phone: a fourth shape at the transcription request; PSS
  from an on-device sampler with RSS as a diagnostic; cold and cached strata reported apart; the loaded
  condition re-verified before every run (pids and PSS; the same in-app workload cannot be scripted for
  Chrome, stated as a limit); kills by record, pid or start time; the 1 GB floor replaced by kill and
  pressure evidence; speaker preflight (Bluetooth, wired, USB, volume, transcript length); a long-take
  stratum; thermal gating; interleaved order with the order recorded; individual values with median,
  maximum and range instead of a ten-run p95; the pending-warm-up drain on connect with an issued gate;
  terminal transitions clear the pending flag; override restore from a trap.

- **Decision round, same session:** PROCEED-WITH-REVISIONS on wording only: "safe by every measured axis"
  overclaimed (thermal exceeded in every shape; A's long-take pressure above the middle shapes), the cold
  penalty was one run per cell, the reclaim column is order-confounded and not comparative, and the rule
  must not claim general S26, release-build or other-device safety. All adopted in §11.1.1 and the rule.
- **Campaign 2026-09-01 17:31 to 17:59, decision:** keep warm-at-connect; override, gate and enum removed;
  #73 filed for unloading the speech model after transcription; #21 already covers the NPU model surface. Two harness findings recorded in `device-testing.md`: a 2 °C battery band stalls a
  campaign (thermal is recorded, not gated) and `grep -q` under `pipefail`.

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code and one real trial before §3
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
