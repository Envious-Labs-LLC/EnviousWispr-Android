# Issue #464 — Probe the library GenieX actually loads — 2026-10-21

GitHub issue: `#464`. Tier: MEDIUM (one engine's runtime choice, `:polish` and the main process). Status: APPROVED (founder standing approval 2026-10-21 to work the high-priority queue; grounded r3 PROCEED-AS-PLANNED).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/cpp/capability_probe.cpp`, `app/src/main/java/com/envi/wispr/polish/S1RuntimeKind.kt`)
mixed_pr: true — Docs/dev-tooling for this plan and the knowledge update; obligation `cited-symbols`.

**PAR rows closed:** none (device compatibility under P0 #449).

**Hardware UAT:** Y. Success: on a Pixel 8 a dictation with local polish on comes back polished by S1-mini on the
processor instead of falling back to basic cleanup; a Galaxy S24 still polishes on GenieX.

## Preface — User Rubric

Persona: the privacy-first professional (brand guide) on a Pixel, polish set to "on this phone".

1. **Moment:** dictating an email reply in Gmail; wants it tidy without sending text to a cloud.
2. **Why:** "I picked on-device polish so nothing leaves my phone, and it should actually polish."
3. **Invocation:** every dictation; voluntary.
4. **Apps:** Gmail, Slack, Google Docs, Messages.
5. **Input:** "um so I think we should, like, push the launch to Monday"; "thanks for the notes, I'll fold them in";
   "can we move standup to ten"; "sorry, running late, start without me"; a 30 s status update.
6. **Success:** fillers gone, punctuation right; nothing to notice.
7. **Wrong-not-broken:** today every dictation silently gets only basic cleanup; the user concludes polish is weak.
8. **Power-user hack:** switching to cloud polish with their own key, giving up the privacy they chose.
9. **Control:** none new; the existing polish setting already expresses the choice.

Cross-persona check: no persona loses anything; GenieX phones keep GenieX, failing phones get the portable runtime.

Consolidation: none. One probe gains the check that matches what GenieX loads.

---

## 0. TL;DR

`S1RuntimeKind` picks GenieX when the processor has the needed features and `libOpenCL.so` opens. On a Pixel 8
both pass, but GenieX's plugin dependency `libggml-opencl.so` cannot load ("cannot locate symbol clGetDeviceInfo"),
so local polish never starts and every take falls back (`LOCAL_NOT_READY`, 40/40, Test Lab 2026-10-21). A Test Lab
probe showed `dlopen("libggml-opencl.so")` fails on the Pixel 8 and loads on a Galaxy S24 and A36, while every
`dlsym`/versioned dlvsym lookup of its 34 OpenCL imports succeeds on all three. Fix: the probe also opens
`libggml-opencl.so`; a failure picks the portable runtime and the log line names the linker's text.

## 1. Problem

Evidence in #464 and its two comments: `PolishService: Local S1 runtime: geniex-0.4.0-llama_cpp (all checks
passed)`, then `GenieXSdk: dlopen failed: cannot locate symbol "clGetDeviceInfo" referenced by
".../libggml-opencl.so"`, then `S1 unavailable; deterministic fallback active`. Probe table in the second comment.

## 2. Goals & non-goals

### 2.1 Goals
1. A phone where `libggml-opencl.so` cannot load runs the portable runtime (Pixel 8: 40/40 POLISHED in the soak).
2. Phones where it loads keep GenieX (S24: log line names GenieX; soak 40/40). The A36 is not a GenieX control: its
   processor lacks the required features (`missing hwcap 0x4004000` in the soak), so it ran the portable runtime
   before and after this change.
3. The runtime log line says why: `OpenCL: <linker text>`.

### 2.2 Non-goals
A runtime fallback after a failed GenieX load in the same process; the A35 reload timeout (#465); GenieX itself.

## 2.5 Grounding brief

### 1. Producer to consumer
`OpenClProbe.openError` (`polish/S1RuntimeKind.kt`) → JNI `nativeOpenError` (`cpp/capability_probe.cpp`) →
`S1RuntimeKind.current` (lazy, once per process) → consumers found by
`grep -rn "S1RuntimeKind\.\(current\|reason\|decide\)" app/src`: `polish/PolishService.kt:83` (`S1Runtime.create`, in
`:polish`), `processing/ProcessingEnvironment.kt:17` (`standardPolishBackends`) and `:23` (`s1ContextId`, the
qualification canary key), plus `S1RuntimeKindTest`. `OpenClProbe` has no other caller
(`grep -rln OpenClProbe app/src`: `S1RuntimeKind.kt`, `capability_probe.cpp`).

### 2. Existing authority
`OpenClProbe` is the one owner of "can GenieX's OpenCL dependency load"; this widens its check, no new owner.

### 3. Prior attempts and live direction
#449 (PR #451) added the probe because GenieX never loaded on an A35 without app-visible OpenCL; it checks only
`libOpenCL.so`. Build 283 fixed the release-build processor read. My first theory in #464 (symbol versions) was
retracted by the probe.

### 4. Boundaries
The probe runs in every process that reads `S1RuntimeKind.current`: `:polish` (runtime creation) and the main
process (`ProcessingEnvironment`, read by `ui/PolishScreen.kt` and `providers/ui/PolishSettingsViewModel.kt`, which
compare main's `s1ContextId` with qualification results produced in `:polish`). Both processes must therefore reach
the same answer by the same check, so the check stays in both (review r1 finding 3; moving it out of main would
make them disagree). Loading `libggml-opencl.so` executes native initialisation and may retain mappings after
`dlclose` (review r1 finding 1). Evidence bounding that: its dynamic section has a 24-byte INIT_ARRAY (external) (three
entries), a 16-byte FINI_ARRAY (external) and no NODELETE (external) flag (llvm-readelf on the shipped library); today's probe already
loads `libOpenCL.so`, and with it the vendor GPU driver, in both processes; and the experiment probe loaded and
closed `libggml-opencl.so` in the main process on the S24 and A36 (and failed cleanly on the Pixel 8) with the
process continuing. Constructor and unload safety on other phones is NOT VERIFIED. A native crash or hang in the
load would not return a fallback (review r1 finding 2); the same is true of today's `libOpenCL.so` load, so this
change widens an accepted exposure to one more library rather than creating a new one.

### 5. High-risk premises, with evidence
- `dlopen("libggml-opencl.so")` reproduces the production failure on the Pixel 8 and succeeds on SM8650 and
  SM6475: executed, Test Lab matrix `464-probe`, 2026-10-21.
- The library name resolves from the app's own library directory in the app's linker namespace: same run (the
  probe ran from the app's process with the name only).
- The canary key changes for a phone whose runtime changes (`ProcessingEnvironment.s1ContextId` includes the
  runtime identity), so a Pixel's old GenieX failure record does not carry over: read at
  `processing/ProcessingEnvironment.kt:23`.

## 3. Design

In `nativeOpenError`, after `libOpenCL.so` opens and is closed, `dlopen("libggml-opencl.so", RTLD_NOW | RTLD_LOCAL)`;
on failure return the linker's text, else close it and return null. Rename nothing (the JNI symbol stays); update
the KDoc of `OpenClProbe` and `S1RuntimeKind` to say it checks GenieX's OpenCL library too.

Rejected: `dlsym` or dlvsym checks (they pass on the Pixel, probe table); falling back after a failed GenieX load in
the same process (GenieX may have partly initialised; the probe decides before any GenieX code loads).

## 3b. Ownership
Stays in `OpenClProbe` because it already answers "can GenieX's OpenCL side load"; a second probe would split it.

## 4. Contract deltas
`OpenClProbe.openError`: null now means both `libOpenCL.so` and `libggml-opencl.so` load. Non-null text may now be
the second library's linker error.

## 5. Lifecycle audit
- Processes reading the choice: `:polish` and main, enumerated in §2.5.1 and §2.5.4. Each decides once per process
  by the same deterministic check; the soak verifies they agree.
- A phone that switches runtime: canary key changes, qualification starts fresh (§2.5.5).
- A failure inside `System.loadLibrary("enviouswispr-capability")`: unchanged, returns the class name, portable.

## 6. Consumer matrix
| Delta | Consumer | Current | Required | Change? | Verified by |
|---|---|---|---|---|---|
| probe stricter | `PolishService` runtime creation | GenieX on Pixel 8, fails | portable on Pixel 8 | no | Test Lab soak |
| probe stricter | `ProcessingEnvironment.standardPolishBackends` | GPU and CPU on Pixel 8 | CPU only | no | read |
| probe stricter | `ProcessingEnvironment.s1ContextId` | GenieX identity | portable identity | no | read |
| probe stricter | main and `:polish` must agree | same check in both | same check in both | no | the soak shows main's identity EQUAL to `:polish`'s, cold start, on all three phones (logging alone is not a pass) |

## 7. Failure modes
| Failure | Origin | Caller | User sees | State | Retry |
|---|---|---|---|---|---|
| `libggml-opencl.so` cannot load | linker | `S1RuntimeKind` | polish runs on the processor | none | none needed |
| It loads but GenieX still fails later | GenieX | `PolishService` | today's deterministic fallback | unchanged | unchanged |
| Native crash during load | vendor or ggml initialiser | either process | affected process terminates; no probe fallback returns. Restart and recovery behaviour NOT VERIFIED | NOT VERIFIED | same exposure as today's `libOpenCL.so` load |
| Native hang during load | vendor or ggml initialiser | either process | calling thread remains blocked; process termination is not guaranteed. User impact depends on the calling thread. No probe fallback returns; recovery NOT VERIFIED | NOT VERIFIED | same exposure as today's `libOpenCL.so` load |

## 8. Signals
`S1RuntimeKind.reason` text may now carry the second library's linker error (library and symbol names only).

## 9. Fallbacks
The portable runtime is the existing fallback; no new branch.

## 10. Files
- `app/src/main/cpp/capability_probe.cpp`: the second `dlopen` in `nativeOpenError`, comment updated.
- `app/src/main/java/com/envi/wispr/polish/S1RuntimeKind.kt`: KDoc of `OpenClProbe` and the class.
- `app/src/androidTest/java/com/envi/wispr/SustainedDictationDeviceTest.kt`: report main's runtime identity and
  reason once, so the Test Lab log shows both processes' answers.
- `.claude/knowledge/polish-engines.md` FACT two-runtimes-chosen-by-processor: the extra check and the Pixel 8 case.

## 11. Testing
1. No JVM test can load a vendor library; `S1RuntimeKindTest` already covers `decide` with the probe's answer as a
   lambda. Class of the device evidence: Product Outcome (polish lands on a Pixel 8).
2. Revert: remove the second `dlopen`; the Pixel 8 soak goes back to 0/40 POLISHED.
3. Not tested: other GenieX dependencies failing for other reasons (out of scope; still the existing fallback).

### 11.1 Hardware UAT
- Subsystem: limb (local polish).
- Recipe: `SustainedDictationDeviceTest` (40 takes, local polish) on Test Lab Pixel 8, Galaxy S24, Galaxy A36.
- Oracle: Pixel 8 log `Local S1 runtime: llama_cpp-portable-cpu (OpenCL: dlopen failed: cannot locate symbol
  "clGetDeviceInfo" ...)` and 40/40 POLISHED; S24 and A36 log GenieX and 40/40 POLISHED.
- Restore: nothing.

### 11.2 Other obligations
| Test | Class | Proves | Revert |
|---|---|---|---|
| unit suite count | — | nothing else broke | — |

## 12. Blast radius
The runtime choice only. Not touched: GenieX, the portable runtime, prompts, speech. Revert the commit.

## 13. Ship criteria
- [ ] A Pixel 8 polishes 40 of 40 on the portable runtime.
- [ ] A Galaxy S24 stays on GenieX, 40 of 40.

## 14. Open questions
None for the founder.

## 15. Related
#449, #451, #465, #453.
