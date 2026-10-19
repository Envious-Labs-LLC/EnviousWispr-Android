# Issue #449 — Local polish that runs on every supported phone — 2026-10-19

GitHub issue: `#449`. Tier: LARGE (a second local runtime beside the shipped one, native packaging, the
`:polish` engine). Status: DRAFT (grounded review PROCEED-AS-PLANNED, round 7, 2026-10-19, including §16; awaiting Gate 2).

Builds on [`issue-449-2026-10-18-compatibility-research.md`](issue-449-2026-10-18-compatibility-research.md),
which stays the source for regional chip variety, catalog access and the vendor 0.9.1 inspection.
Private receipts for everything measured here: `docs/internal/p0-polish-hardware/2026-10-19/`.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true (Code: `app/**`, `llama-android/**`; Benchmark: `accelerator-benchmark/**`, the remote
qualification test; Docs/dev-tooling: `docs/**`, `scripts/isa-census.py` (proposed), `.claude/knowledge/**`).

**PAR rows closed:** none. Android-only platform compatibility; PAR-040 (last-good-text fallback) is relied
on unchanged.

**Consolidation:** none. One new decision (which runtime this processor can run) gets one owner,
`S1RuntimeKind` (proposed); the existing runtime surface is extracted into an interface, nothing is merged.

**Hardware UAT:** Y. On the S26, the founder double-presses the side button, says "um so the build is ready
can you test it tomorrow", and polished text lands in Messages exactly as today, still on the graphics
processor. On older phones we do not own (Galaxy A05s with the A23's chip, A34, A35, and the Pixel 8), the
real polish service returns polished text instead of its process dying or never loading.

## Preface — User Rubric

1. **Who.** Meera Patel on a Galaxy A35 in India, replying in WhatsApp one-handed between school pickups.
   Thirty seconds ago she opened WhatsApp; thirty seconds from now she wants the reply sent.
2. **Why.** "I turned on the AI cleanup and it never seems to do anything." She chose cleaner text, not a chip.
3. **How.** Side button or the notification, reactive, already in the target app.
4. **Apps.** WhatsApp, Messages, Gmail; Frank Chen in Gmail and Facebook; Priya in Slack on a Pixel 7.
5. **Input.** "um can you pick up milk on the way back", "so the meeting is moved to 4 not 3 ok",
   "tell grandma we'll call her sunday", "uh the PR is ready for review", "running ten minutes late sorry".
6. **Success.** Nothing to notice: the cleaned sentence appears, a second or two slower than on a flagship.
7. **Wrong-not-broken.** Polish silently never happens on her phone, or takes so long she stops waiting.
8. **Hack.** Turn AI polish off, or paste a cloud key she did not want to manage.
9. **Control.** Unchanged ladder: Off, basic cleanup, local AI polish (Automatic or Custom processor),
   cloud with her own key. This change adds no setting; Automatic simply works.

### Cross-persona check

Meera and Frank (budget and mid-range phones) gain polish that works. Priya on a Pixel 7 gains it too.
Diana and the founder on flagships see no change. Dr. Vasquez keeps everything on the phone (no new network
path). Marcus and Aaron are unaffected. No persona is better served by a crash or a chooser.

---

## 0. TL;DR

The shipped local polish engine (Qualcomm GenieX 0.4.0) only works on phones with a 2022-or-newer Arm
processor AND an app-loadable OpenCL graphics library. Today's remote runs through the real polish service
show it failing on all three older supported phones tried, three different ways (two native crashes of the
`:polish` process and one silent never-loads). The repository's own llama.cpp build ran the same S1-mini
model, prompts and greedy sampling on all four phones tried, with identical output text. Fix: decide once
per process, from the processor's own feature report and an OpenCL dlopen probe, whether GenieX can run;
if yes, keep today's path untouched; if no, run S1-mini on the portable runtime. No new setting, model,
cloud path or recovery machinery.

## 1. Problem

### 1.1 Production
Public build 277: two identical crashes on a Galaxy A23 4G (SM-A235F, Snapdragon 680) at
`libgeniex.so+0x4bd90c`, an `LDAPRB` (external) instruction (Armv8.3 RCpc), matched by build ID (research doc §1).

### 1.2 Instruction census of both engines (new, 2026-10-19)
Every AArch64 instruction in every library, grouped by the Linux feature a processor must report to run it,
with outline-atomics helpers excluded (`docs/internal/p0-polish-hardware/2026-10-19/isa_census.py`,
results `census040.json`, `census-llama.json`).

| Library (GenieX 0.4.0 AAR) | Beyond Armv8.0, on paths every generation runs |
|---|---|
| `libggml-cpu.so` | I8MM 408 (`smmla`, Armv8.6), FP16 531, dot product 1,384, FCMA 4, RCpc 30, RCpc2 3, LSE 50 |
| `libllama.so`, `libllama-common.so` | RCpc 124 / 440, RCpc2 9, LSE 147 / 752 (inline, compiled for Armv8.7) |
| `libgeniex.so`, `libgeniex_core.so`, plugin | RCpc and LSE in `geniex_init` (external), `geniex_register_plugin` (external), `geniex_llm_create` (external) |

GenieX's own toolchain file targets `armv8.7a+fp16+dotprod+i8mm` globally (research doc §1). In contrast the
portable build's base libraries (`libllama`, `libggml-base`, `libllama-common`, `libs1-polish`): the portable build targets an Armv8.0 baseline; beyond-baseline exceptions are runtime-checked atomic helpers and the MTE unwind instruction discussed in §3, whose reachability on non-MTE phones is NOT VERIFIED and is resolved under §3's exemption validation. ggml picks one of seven
processor-variant libraries (`armv8.0` … `armv9.2`) at runtime.

`libgeniex_plugin_llama_cpp.so` also links `libggml-opencl.so`, which links `libOpenCL.so` (llvm-readelf
`DT_NEEDED` (external)). So even GenieX's CPU route cannot load on a phone whose vendor does not publish
`libOpenCL.so` to apps.

### 1.3 Remote physical phones, real polish service (`PolishServiceDeviceTest#s1PolishesTextOnAutomaticStandardBackend`, GenieX 0.4.0, matrix 8271984804397284036)

| Phone | Processor features (`/proc/cpuinfo`) | Result |
|---|---|---|
| Galaxy A05s, SM6225 (A23 4G chip), API 35 | Armv8.0: no LSE, no RCpc, no dot product | **SIGILL**, `:polish` dies loading `libllama-common.so` (constructor) |
| Galaxy A34, Dimensity 1080, API 36 | Armv8.2: LSE, RCpc, FP16, dot product; no I8MM, FCMA, RCpc2 | **SIGILL**, `:polish` dies in `libggml-cpu.so` from `geniex::LlamaLlm::create` |
| Galaxy A35, Exynos 1380, API 34 | same as A34 | never loads: `dlopen failed: cannot find "libOpenCL.so"`; `LOCAL_NOT_READY`, basic cleanup every take |

### 1.4 Same phones, portable runtime (`PortableCpuDeviceTest`, matrices 5865563733416675177 and 8721408296902179664)

Same model file (SHA-256 checked), same system prompt and control line as the 2026-08-28 benchmark, greedy.
Warm wall time per take, two runs each; output text was **identical on all four phones**.

| Phone (RAM) | Variant chosen | Load | Short | Medium | Long | Reload | Process memory |
|---|---|---|---|---|---|---|---|
| Galaxy A05s (3.7 GB unit) | Armv8.0 | 2.2 s | 9.5–10.5 s | 6.9–9.4 s | 13.6–20.2 s | 5.4 s | ~0.69 GB |
| Galaxy A34 (5.6 GB) | Armv8.2 + dot product | 3.9 s | 2.4–2.8 s | 4.0–4.3 s | 8.1–8.4 s | 3.3 s | ~0.99 GB |
| Galaxy A35 (5.6 GB) | Armv8.2 + dot product | 1.5 s | 0.85–1.0 s | 2.0–2.3 s | 4.9–5.3 s | 2.0 s | ~0.97 GB |
| Pixel 8 (7.7 GB) | Armv9 + I8MM + SVE | 1.1 s | 1.1 s | 2.5 s | 5.1–6.2 s | 1.8 s | ~1.05 GB |

Comparison, not like-for-like (different session, warm runs on the S26 only): S26 GenieX CPU 435 / 1,218 /
2,920 ms and GPU 199 / 630 / 1,623 ms (`polish-engines.md` FACT: measured-numbers-2026-08-28).

Reading: Armv8.2 phones polish in about 1–3 s for ordinary sentences and 5–8 s for a 600-character
paragraph, inside today's 10 s cooperative budget. The Armv8.0 class (A23 chip) is executable but slow;
most takes would exceed the budget and return the last good text. The A05s unit was the 4 GB variant, which
the Play rule already excludes; the 6 GB A23 has the same processor. Thread-count probe on that class
(matrix label `issue449-a05s-threads2`, two threads instead of four): short 6.6–12.6 s, medium 10.7–11.1 s,
long 21.6–21.9 s. Fewer threads is slower; thread tuning does not rescue this class.

## 2. Goals & non-goals

### 2.1 Goals
1. No supported phone executes a GenieX library its processor or vendor libraries cannot run: decided from
   the phone's own reports before any GenieX library loads.
2. On every other supported phone, local polish produces real S1-mini output through the portable runtime,
   with the same model file, system prompt, control line, greedy sampling, output safety and fallback.
3. Phones that run GenieX today keep today's route, speed and behaviour (S26 graphics processor first).
4. Budgets unchanged (`LocalPolishBudget`); a take that exceeds them returns the last good text, as today.
5. Remote qualification through the real polish service on A05s, A34, A35 and Pixel 8 before release.
6. (Founder 2026-10-19, §16.) Low-end phones are not offered the app; mid-tier phones keep every feature and
   see a one-time notice that it is slower on their phone; a suggested-phone list ships with the listing.

**Chunks.** Chunk 1: the runtime gate and portable runtime (goals 1-5); this is the crash fix for mid-tier
phones and ships first. Chunk 2: §16, the in-app low-end backstop and mid-tier notice. Chunk 3: the Play
exclusion rule and listing text, each applied only with founder approval at action time.

### 2.2 Non-goals
- No crash journal, quarantine, persistent attempt protocol or retry policy (founder 2026-10-18).
- No new model, no NPU, no automatic cloud switch, no chooser, no budget change.
- The memory rule in Play stays as it is; the only Play change is the §16 chip exclusion (chunk 3).
- Not fixing the qualification-route bookkeeping gaps named on the issue (2026-10-18 comment): they matter
  only while a crash is possible, which this removes. Follow-up issue if still wanted.
- No GPU acceleration on non-Adreno phones; no thread tuning beyond what §14 decides.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- The only GenieX constructor is `PolishService.kt:81`, `s1Runtime by lazy { S1GenieXRuntime(applicationContext) }`
  (`/usr/bin/grep -rn "S1GenieXRuntime(" app/src/main`: one hit). Every native call goes through it: load
  (`PolishService.kt:637-639`), qualification load and canary (`:677-691`), generation (`:715`), close
  (`:553`, `:626`, `:669`).
- First native contact is `S1GenieXRuntime.initializeSdk`: `S1NativeLog.installContentFreeLogger` dlopens
  `libgeniex.so`, then `GenieXSdk.init` (`S1GenieXRuntime.kt:54-62`, `geniex_log_silencer.cpp`).
- All of it runs in `:polish` (`AndroidManifest.xml`, `android:process=":polish"`). The main process reads
  `ProcessingEnvironment.standardPolishBackends` and `s1ContextId()` (`PolishScreen.kt:160-168`,
  `PolishSettingsViewModel.kt:120-183`, `ProcessingPreference.kt:87`, `S1LoadedConfiguration.kt:17`,
  `ProcessingCheckClient.kt:64`, `PolishService.kt:254,258,602,665,673`) and loads no native polish code.

### 2. Existing authority
- `:llama-android` (`S1Native`, `s1_jni.cpp`) is the repository's own pinned llama.cpp runtime, built with
  `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, KleidiAI and OpenMP; consumed today only by
  `:accelerator-benchmark`'s `cpu` flavor. It is the exact artifact measured in §1.4.
- Processor feature detection: no implementation found in `app/src` (`/usr/bin/grep -rn
  "getauxval\|HWCAP\|auxv\|cpuinfo" app/src`, PENDING paste at revision). New authority proposed:
  `polish/CpuFeatures.kt` (proposed).

### 3. Prior attempts and live direction
#418 (GenieX 0.8.0 CPU-only failed at QAIRT; one Pixel 8 CPU pass), #234 (fail-open fallback, kept),
#429/#431 (NPU experimental, untouched), research doc 2026-10-18 (vendor 0.9.1 CPU AAR still enumerates
QAIRT; not a drop-in). Founder direction on the issue 2026-10-18: portable baseline, qualified, no recovery
machinery, no preselected replacement before the compatibility assessment (now §1).

### 4. Boundaries
- Process: each process caches a decision from its auxiliary-vector values and its OpenCL dlopen probe.
  Agreement between the main and polish processes, and between the probe and GenieX loading, is required
  validation, NOT VERIFIED.
- Never two runtimes in one process: the decision is computed once and cached as a `val` for the process
  lifetime, so `PolishService` constructs exactly one runtime kind.
- The capability probe loads the vendor OpenCL library once per process and closes it; it is the
  phone's own graphics driver library, built for that processor, and the A35 case shows absence is a clean
  `dlopen` failure, not a crash.
- Native names: both packages ship `libllama.so`, `libggml.so`, `libggml-base.so`, `libllama-common.so` and
  `libomp.so` (AAR listings). The portable build must rename its outputs or the APK merge picks one.
- `ggml_backend_load_all_from_path` falls back to a base `libggml-<name>.so` when no variant matches
  (`ggml-backend-reg.cpp:543-556`), so in the merged APK it would dlopen GenieX's `libggml-opencl.so` and
  `libggml-hexagon.so` (both carry RCpc). The portable JNI must load only its own CPU variants.

### 5. Premises and evidence
| Premise | Evidence |
|---|---|
| GenieX needs LSE, RCpc, RCpc2, FP16, dot product, FCMA, I8MM | census §1.2; A05s and A34 SIGILL §1.3 |
| GenieX needs app-loadable `libOpenCL.so` even for CPU | `DT_NEEDED` chain §1.2; A35 log §1.3 |
| Portable base libraries target Armv8.0 | census §1.2 and A05s generation §1.4; exceptions: atomic helpers and the MTE unwind word, reachability NOT VERIFIED (§3) |
| Pixel 8 passes the GenieX processor gate | its Features line has all seven (matrix 8721408296902179664) |
| S26 passes both gates | not yet read; founder phone `/proc/cpuinfo`, raw HWCAP values and the OpenCL dlopen probe at chunk 1 validation. If it fails, the plan stops (S26 would lose the graphics route). |

## 3. Design

**Chosen: one capability gate, two runtimes behind one interface.**

1. `CpuFeatures` (proposed) parses `AT_HWCAP` / `AT_HWCAP2` from `/proc/self/auxv`. Unreadable or malformed
   → unknown.
2. `S1RuntimeKind` (proposed), enum `GENIEX` (proposed) / `PORTABLE_CPU` (proposed), decided once per process: `GENIEX` only when
   every feature in `GENIEX_REQUIRED_FEATURES` (proposed) (atomics, fphp, asimdhp, asimddp, lrcpc, ilrcpc,
   fcma, i8mm) is present AND `libOpenCL.so` actually opens in this app's own linker namespace; anything
   unknown → `PORTABLE_CPU`. The OpenCL answer is a direct probe, not a list read (coverage round finding 3:
   list membership misses partition extension files, bitness filtering and namespace rules). A new tiny native
   library, `enviouswispr-capability` (proposed), exports one call that does `dlopen("libOpenCL.so",
   RTLD_NOW | RTLD_LOCAL)` and `dlclose`, and touches nothing of GenieX's. It runs in the same namespace GenieX
   would use, and the merged manifest already declares `<uses-native-library android:name="libOpenCL.so"
   android:required="false"/>` (from the GenieX AAR; `app/build/intermediates/merged_manifest/debug/
   processDebugMainManifest/AndroidManifest.xml:415`), so the probe asks the same app linker namespace GenieX's own `DT_NEEDED` (external) load uses (exact equivalence NOT VERIFIED until the chunk 1 remote run shows probe and GenieX agreeing on A35 and Pixel 8). Failing toward portable is the safe direction: the worst case is a capable phone
   polishing on its processor.
3. `S1Runtime` (proposed) interface extracted from today's `S1GenieXRuntime` surface (`load`, `generate`,
   `close`, `activeComputeUnit`, `failedComputeUnits`, `initializationFailed`, `lastGeneration`).
   `S1GenieXRuntime` implements it unchanged. `S1PortableRuntime` (proposed) implements it over `S1Native`,
   offers only `cpu`, reports the stop reason as `eos`/`length` so the qualification canary
   (`PolishService.kt:688-691`) reads it the same way.
4. `PolishService.s1Runtime` constructs by kind and replaces the hardcoded "GenieX on …" load-status text
   (`PolishService.kt:642`) with the selected runtime identity. Existing load, generation, fallback and
   deadline control flow remains.
5. `ProcessingEnvironment.standardPolishBackends`: `{GPU, CPU}` for `GENIEX`, `{CPU}` for `PORTABLE_CPU`, so
   Settings and Automatic never offer a route the phone cannot run. `s1ContextId()` replaces the literal
   `"geniex-0.4.0-llama_cpp"` with the kind's runtime identity, so qualification never carries across runtimes.
6. `:llama-android`: rename native outputs with an `ew` prefix; OpenMP off (ggml's own thread pool; removes
   the `libomp.so` clash); replace `ggml_backend_load_all_from_path` with a loader that scores and loads only
   our own `ew` CPU variant files; return stop reason and token counts; add a per-token abort flag so the
   cooperative deadline behaves as it does on GenieX. Log silence stays (`llama_log_callback` discards all).
   Outputs are renamed with CMake `OUTPUT_NAME` (external) while keeping target identities, so variant libraries link the
   renamed base through the `ggml-base` target (`ggml/src/CMakeLists.txt:397`). The CPU-only loader keeps
   ggml's scoring, loads only the winning `ew` variant and registers it through `ggml_backend_load`; it never
   calls load-all. OpenMP-off is supported by the build (`ggml/src/CMakeLists.txt:337-353`), but §1.4 timings
   were measured OpenMP-on, so short, medium and long timings and the deadline checks are repeated on the
   final renamed OpenMP-off APK before any equivalence is claimed; if OpenMP-off is materially slower, keep
   OpenMP and link it statically (`-static-openmp`) instead of shipping a second `libomp.so`.

**Why eight features and not the whole Armv8.7 target (grounded round 1, finding 1).** The compiler target
permits more families (JSCVT, RDM, DCPOP, FlagM, FRINT, SB, BF16, non-hint pointer authentication, FHM,
WFxT). The census decodes every instruction in every GenieX library on the CPU/GPU path and checks for each
of those families: none occurs. What remains outside the eight: (a) AES/SHA1/SHA2, SHA3/SHA512 and one
apparent SVE instruction in `libgeniex.so`, all inside an embedded OpenSSL CRYPTOGAMS block (its own banner
strings, for example "ChaCha20 for ARMv8, CRYPTOGAMS"), which selects code by its own runtime capability
check; the apparent SVE word at `0x367980` is ASCII data (`656d6f67`); (b) undecodable words: ASCII string
data in `libgeniex.so`, plus one MTE word (`stg x8, [x8]`, encoding 0xd9200908) in every library. Decoded in the
unstripped portable `libggml-base.so` at `0x10ee2c`, it is inside the statically linked libunwind
`DwarfInstructions::stepWithDwarf`, in a loop entered only when the frame's CIE carries the memory-tagged
stack augmentation (`tbz w23` plus the CIE byte check at `0x10ee0c-0x10ee18`); such frames exist only in code
built with MTE stack tagging. Whether any packaged library was built that way is NOT VERIFIED; the branch is also reached only while unwinding a C++ exception. Its presence in both builds and
the A05s run are supporting observations only. The OpenSSL dispatch claim is from the banner strings, NOT
VERIFIED at instruction level. The eight-feature mask remains provisional. Chunk 1 reads the S26's raw
`AT_HWCAP` / `AT_HWCAP2` values and stops if they contradict the required mask. Successful generation without
a crypto feature supports safety only for the exercised paths; it does not prove crypto dispatch. Missing SVE
provides no dispatch evidence because the apparent SVE word is data. Before finalizing the mask, establish
capability-guarded dispatch or unreachability for excluded instruction families (the OpenSSL block's
`OPENSSL_armcap_P` (external) checks, located by disassembly), or conservatively require their capabilities
(aes, pmull, sha1, sha2, sha3, sha512). Stop if neither can be established while preserving the S26 route.
The Pixel 8 Features line already shows all eight present, and all six crypto capabilities. Receipts: `docs/internal/p0-polish-hardware/2026-10-19/`.

The `GENIEX_REQUIRED_FEATURES` (proposed) set lives beside the GenieX version, with the census command as its
regeneration instruction, and a unit test fails if the Gradle GenieX version changes without that set's
recorded version changing.

**Rejected.**
- *Gate only, no portable runtime:* stops the crashes, but every tested older phone loses local polish
  (A35 already has none today). Fails the founder's "make it work".
- *Vendor GenieX 0.9.1 CPU-only package:* still enumerates the absent QAIRT plugin at init; names collide
  with 0.4.0; unqualified.
- *Portable runtime everywhere:* one path, but drops the S26 graphics route (199 vs 435 ms short, 1,623 vs
  2,920 ms long, §1.4 comparison row).
- *Static single-tier build:* simpler packaging, but loses dot-product speed on Armv8.2 phones and is not the
  artifact measured in §1.4.
- *Device or chipset allowlist:* model names hide different processors by region (research doc §3); the
  processor's own report is the closed-world answer.
- *Catch the crash:* a SIGILL kills the process; recovery machinery is out of scope by founder direction.

## 3b. Ownership
This will live in `polish/` because only the polish engine loads native polish code; `ProcessingEnvironment`
already owns "what this phone can run" for Settings and reads the same decision rather than a second copy.
The alternative, a GenieX init-failure fallback to portable, was rejected: two of the three failures are
signals the process cannot survive, and failover machinery is out of scope.

## 4. Contract deltas
- `ProcessingEnvironment.standardPolishBackends`: was a constant; now "backends this phone's runtime can run".
- `ProcessingEnvironment.s1ContextId()`: now names the runtime. On GenieX phones the string changes once, so
  existing qualifications are invalidated once. Automatic continues to try implemented backends directly
  (`ProcessingPreference.kt:33-35`, `PolishService.kt:637-639`); it does not rerun the Check canary. Custom
  needs fresh qualification, as in §5. Same effect as the fingerprint change after any OS update.
- Engine label stays `S1-mini by Superwhisper (CPU)`; the local log names the runtime kind.
- `S1Native.generate` returns text plus stop reason and token counts; gains `abort()`. Its only other
  consumer, `CpuBenchmarkEngine.kt`, is updated in the same change.

## 5. State and lifecycle audit
| Population | Answer |
|---|---|
| Processes that can load GenieX or the portable runtime | `:polish` only, via `PolishService.s1Runtime` (§2.5.1, one constructor) |
| Native contact before the gate | none: the gate runs in the lazy initializer that constructs the runtime |
| Kind changing mid-process | impossible: inputs are fixed while the process lives |
| Load, warm-up, qualification, generation, close | all through `S1Runtime`; the portable runtime implements each; qualification of GPU on portable returns `NOT_IMPLEMENTED` via `PolishService.kt:673` |
| `:polish` death | unchanged handling; the portable build targets an Armv8.0 baseline; beyond-baseline exceptions are runtime-checked atomic helpers and the MTE unwind instruction discussed in §3, whose reachability on non-MTE phones is NOT VERIFIED and is resolved under §3's exemption validation |
| Cooperative timeout | abort flag ends the native loop; null result then follows today's poison-and-exit path (#75) |
| Stale qualification | context id differs per kind |
| Upgrade from build 277 on an A35 | stored GPU/CPU qualification under the old id is ignored; Automatic offers CPU |
| Upgrade with a saved Custom processor order (`ProcessingPreference.kt:33-35`) | the saved order is kept; qualifications under the old id stop counting (`S1LoadedConfiguration.kt:18`), so Custom has no eligible route until the user's Check re-qualifies CPU. Required validation, NOT VERIFIED yet: empty-qualification state, CPU Check offered, re-qualification succeeds, then warm-up and a take polish |
| Cooperative deadline on portable | the abort flag is armed before chat templating, tokenization and prompt prefill, checked between prefill batches and between tokens, and set without taking the generation mutex (`s1_jni.cpp:213` holds it). Required validation, NOT VERIFIED yet: expiry during prefill and during generation, mutex-free abort, unchanged poison, single fallback delivery and process exit (#75) |

## 6. Consumer matrix
| Delta | Consumer | Now | Required | Change? | Verified by |
|---|---|---|---|---|---|
| backends by kind | `PolishScreen.kt:160-168` | GPU+CPU always | CPU only on portable | no, reads the set | kind unit test |
| backends by kind | `PolishSettingsViewModel.kt:120,183` | GPU check allowed | refused on portable | no | same |
| backends by kind | `ProcessingPreference.kt:87`, `PolishSettingsViewModel.kt:183` | a runtime failure marks every standard backend failed | marks the kind's set | no | existing tests |
| context id | `PolishSettingsViewModel.kt:130-137`, `ProcessingCheckClient.kt:64`, `PolishService.kt:602,665` | id omits runtime | includes it | no, reads the id | id-by-kind unit test |
| runtime | `PolishService.kt:81` | GenieX | by kind | yes | remote run through the real service |
| context id | `PolishScreen.kt:161,167` (qualified set and retry eligibility), `PolishService.kt:254,258` (early qualification replies) | id omits runtime | includes it | no, reads the id | id-by-kind unit test |
| backends by kind | `S1LoadedConfiguration.kt:16-18` (filters load candidates), `PolishService.kt:673` (qualification admission) | GPU+CPU | the kind's set | no | kind unit test + remote run |
| `S1Native` result shape | `CpuBenchmarkEngine.kt` | string | text + end | yes | benchmark assemble |

## 7. Failure modes
| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| auxv unreadable or OpenCL probe fails | `CpuFeatures` | kind decision | polish on processor | none | n/a |
| portable model load fails | `S1Native.loadModel` | `PolishService.loadModel` | basic cleanup, as today | as today | as today |
| slow phone exceeds budget | generation | deadline | last good text, as today | as today | next take |
| our variant libraries missing | JNI loader | load | load fails → basic cleanup | as today | none |

User-facing copy: unchanged in chunk 1 (option (b) in §14 would add one reviewed sentence in chunk 2); the Settings processor list simply shows CPU on portable phones.

## 8. Signals
`activeComputeUnit` stays `cpu`/`gpu`. Runtime kind appears in the local log line and the context id only.
No telemetry property added (founder: no telemetry overhaul).

## 9. Fallback source of truth
Unchanged: service failure outcomes use `PolishFallback.deterministicOrWords`, returning deterministic cleanup, or the input words if cleanup throws (`PolishService.kt:416-419`, `PolishFallback.kt:44-48`).

## 10. File-by-file changes
- `app/build.gradle.kts`: `implementation(project(":llama-android"))`.
- `app/src/main/java/com/envi/wispr/polish/`: `CpuFeatures.kt`, `S1RuntimeKind.kt`, `S1Runtime.kt`,
  `S1PortableRuntime.kt` (all proposed); `S1GenieXRuntime.kt` implements `S1Runtime`; `PolishService.kt:81`.
- `app/src/main/java/com/envi/wispr/processing/ProcessingEnvironment.kt`: set and id by kind.
- `llama-android/src/main/cpp/CMakeLists.txt`, `llama-android/build.gradle.kts`, `s1_jni.cpp`, `S1Native.kt`.
- `accelerator-benchmark/`: `CpuBenchmarkEngine.kt` follows `S1Native`; the remote test from this research.
- `app/src/main/cpp/`: `capability_probe.cpp` (proposed) and its CMake target, separate from the GenieX silencer.
- `scripts/isa-census.py` (proposed): the census, to regenerate the required set on any GenieX bump.
- Knowledge: `polish-engines.md`, `device-support-decision.md`, `firebase-test-lab.md` updated in place.

## 11. Testing
1. **Classes.** `CpuFeaturesTest` (proposed): Product Outcome support, literal auxv byte fixtures built from
   the four Features lines in §1.3–1.4 and the S26's; when it fails, an Armv8.2 user's polish process
   crashes. `S1RuntimeKindTest` (proposed): same class, every gate input. GenieX version drift guard: Drift
   Guard. Remote `PolishServiceDeviceTest` runs: Product Outcome on real hardware.
2. **Reverts that turn them red.** Make the kind always `GENIEX`: kind tests fail and the A05s/A34 remote runs
   crash. Drop the OpenCL check: the A35 case fails. Restore `ggml_backend_load_all_from_path`: a native test
   asserting only `ew` CPU variants were loaded fails (PENDING exact form at build).
3. **Not tested.** Every chip model in the world; GPU on non-Adreno phones; thermals over long sessions.

### 11.1 Hardware UAT
- **Subsystem:** limb (local polish) inside the heart's fallback contract.
- **Recipe:** S26 side-button silent take into Messages per `device-testing.md`; plus remote Test Lab runs of
  the real polish service on A05s, A34, A35, Pixel 8 with the shipped APK pair.
- **Expected:** S26 keeps `GENIEX` and its label ends `(GPU)`. A05s, A34 and A35 select `PORTABLE_CPU` and
  return `POLISHED` with `(CPU)` (A05s only for a take that fits the budget, per §14). Pixel 8 selects whatever
  its recorded processor and OpenCL probe dictate (expected `GENIEX`: its features pass and GenieX's CPU route
  ran there on 2026-10-11); its portable path is already measured in §1.4. Oracle: `PolishOutcome.reason` and the log line's PID in `:polish`.
- **Restore:** S26 screen timeout and anything the harness journal records.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| unit suite count | — | no regression | — |
| APK size measured | — | packaging cost known | — |
| native closure check on the built APK | Drift Guard | every portable output renamed; SONAME, `DT_NEEDED` (external) edges and the `S1Native` load name agree; no portable library needs `libomp.so`; KleidiAI artifacts enumerated; every library 16 KB aligned (`llvm-readelf -l`) | remove the rename or re-enable OpenMP |
| installed-APK variant discovery | Product Outcome (remote) | with `useLegacyPackaging = true` the extracted `ew` CPU variants are found from `nativeLibraryDir` in the Play-shaped release build | break the loader's prefix |
| prompt-marker log check | Observability Contract | a unique marker in the prompt never appears in logcat on success, on a chat-template error, and on a load error (common/chat/Jinja, ggml backend and exception text paths, not only `llama_log_set`); the JNI returns fixed error strings, never `exception.what()` (`s1_jni.cpp:104` today) | restore `exception.what()` |
| 16 KB device load | Product Outcome | the portable runtime loads on a 16 KB page-size emulator image | drop the max-page-size link option |

## 12. Blast radius & rollback
Touched: local polish runtime selection, `:llama-android` native build, app packaging. Not touched:
recording, speech, cleanup, insertion, cloud polish, telemetry payloads, `PayloadSanitizer`,
`Provider.disclosure`, the GenieX silencer, Play device rule. Rollback: revert the PR; phones return to
today's behaviour (GenieX everywhere).

## 13. Ship criteria
- [ ] A Galaxy A34/A35-class phone gets polished text from the real service instead of a crash or nothing.
- [ ] The S26 still polishes on the graphics processor at today's speed (side-button take, Messages).
- [ ] No phone in the remote set runs any GenieX library unless it passes both gates.

## 14. Open questions (founder)

Question 1 is SUPERSEDED by §16 (founder 2026-10-19: block the low end). Kept for the record.

1. **The A23 processor class (Armv8.0, oldest cores).** Polish runs there now without crashing, but takes
   7–20 s, so most takes would hit the 10 s budget and fall back to basic cleanup, after paying the cost.
   Options: (a) run it anyway and let the budget decide per take; (b) do not offer local AI polish on that
   class. Four threads measured 7–20 s and two threads 7–22 s (§1.4), so tuning does not rescue it.
   - (a) is this plan as written: zero extra code. Cost to the user: up to 10 s waiting, then basic
     cleanup on most takes, and each timeout restarts the polish process (#75), so the next take reloads.
   - (b) Chunk 2 adds `NONE` (proposed) for confirmed absence of asimddp, with an empty implemented-backend set
     and its own context identity. An empty set alone is insufficient (grounded round 2: `S1LoadedConfiguration.kt:45-46`
     throws on an empty candidate list; `PolishService.kt:553,624-626,669` touch the runtime on destruction
     and cleanup). So `NONE` is handled explicitly in warm-up, model loading, qualification cleanup and
     destruction, and neither native runtime is initialized. Local requests use the existing deterministic
     cleanup with no S1 load or inference wait; cloud requests are unchanged; saved preferences are kept.
     Under "This phone", an unavailable explanation replaces the model-download, processor check/retry and
     S1 control actions. Unknown processor features stay `PORTABLE_CPU` (the portable build targets an Armv8.0 baseline; beyond-baseline exceptions are runtime-checked atomic helpers and the MTE unwind instruction discussed in §3, whose reachability on non-MTE phones is NOT VERIFIED and is resolved under §3's exemption validation; ggml chooses its variant from its own `getauxval` (external) read, independent of ours). Extra scope:
     review the unavailable-state copy and skip the 484,219,808-byte S1 download on `NONE` phones by updating
     onboarding and model-delivery eligibility (`ModelManifest.kt:62-65` includes S1 unconditionally); keep
     the S1 descriptor and any already downloaded file; validate fresh setup and upgrade. It ships after the
     crash fix, so (b) never delays (a)'s protection.
   - Recommendation: **(b), as chunk 2.** A wait that usually ends in basic cleanup is the "wrong-not-broken"
     outcome the rubric warns about; prompt basic cleanup plus an honest sentence is boring and predictable.

## 14b. Coverage round 1 (Codex, 2026-10-19) adjudication

All seven gaps adopted: §6 rows added (1), Custom-upgrade row (2), OpenCL list replaced by a direct probe (3),
native closure and KleidiAI check (4), 16 KB and extraction checks (5), privacy marker test and fixed JNI
error strings (6), deadline armed before prefill and mutex-free abort (7).

## 14c. Grounded round 1 (Codex, 2026-10-19) adjudication

PROCEED-WITH-REVISIONS, direction confirmed. Adopted: 1 (feature-set justification from the full census,
including undecodable words; S26 mask read first), 2 (rename keeps targets; OpenMP-off re-timed, static
OpenMP as fallback), 3 (Automatic does not rerun canaries; `PolishService.kt:642` text edit), 4 (Pixel 8
oracle), 5 (lifecycle checks restated as required validation).

## 14d. Grounded round 2 (Codex, 2026-10-19) adjudication

PROCEED-WITH-REVISIONS; pre-committed deletion not triggered. Adopted: 1 (MTE word decoded and located;
mask marked provisional pending the S26 read; OpenSSL dispatch marked NOT VERIFIED), 2 (`NONE` handled
explicitly across load, qualification, destruction and Settings), 3 (S1 file is 484,219,808 bytes; download
skip needs onboarding and delivery wiring).

## 14e. Grounded round 3 (Codex, 2026-10-19) adjudication

PROCEED-WITH-REVISIONS, one finding, adopted verbatim: a successful S26 run cannot prove crypto dispatch;
chunk 1 must establish dispatch or require the crypto capabilities, and stops if neither preserves the S26.

## 14f. Grounded round 4 (Codex, 2026-10-19) adjudication

PROCEED-WITH-REVISIONS, three wording findings, all adopted: baseline claims now name the MTE exception;
cross-process and probe agreement stated as required validation; fallback terminal corrected to
`PolishFallback.deterministicOrWords`. No direction blockers.

## 16. Device tiers and suggested phones (founder 2026-10-19)

Founder direction 2026-10-19: block low-end budget phones, warn on mid-tier phones while keeping them
working, and publish a suggested phone list. This supersedes §14 question 1 (options a/b).

Tier rule: the chip's fastest processor core design. Measured phones are marked; every other placement comes
from public chip specifications and is an expectation, not a test. Counts are phone MODELS in Google Play's
device catalog export of 2026-10-18 (phones, 64-bit, Android 13+, more than 4 GB), never users. Regenerate with
`python3 -I docs/internal/p0-polish-hardware/2026-10-19/soc_tiers.py <catalog.csv>` (private folder; output
`soc-tiers.txt` beside it).

| Tier | Rule | Models | What the user gets |
|---|---|---|---|
| **Blocked** | big cores older than Cortex-A76 (A53/A55-only, A73, A75) | 671 | not offered in Play; in-app notice if installed anyway |
| **Mid tier** | A76, A77, A78, X1 class | 1,402 (642 of them borderline A76 at 2.0-2.4 GHz) | works on the portable engine; one-time "slower on this phone" notice |
| **Recommended** | Armv9 cores (A710/X2 and newer) | 597 | today's fastest path |
| Unclassified | chips not in the table or unsourced | 124 | in-app check decides |

**Pre-A76 exclusion is a proposed support policy.** Measurements establish poor performance on the A05s only,
not on every pre-A76 chip, and clock speed does not establish performance across core designs. Before the
chunk 3 exclusion is finalized, representative A75 (Unisoc T606 `lion` (external) moto g04s or Helio G85 `fogorow` (external) moto
g24 power) and borderline A76 (Helio G99 `a15` (external) Galaxy A15) phones are measured with the same two tests on free
Test Lab allowance; any exclusion not backed by those runs is recorded as precautionary. Samsung custom-core
chips (Exynos 9810/9815/9825, Exynos 990's custom cores) stay unclassified until sourced or measured, and
QCM4290 is described as Kryo 260 with A73-class performance cores; counts are regenerated after these fixes.

### 16.1 Measured (remote physical phones, 2026-10-19, synthetic speech and text)

Transcript column: warm prerecorded-file transcription, request to callback through the real speech service
(`AsrSpeedDeviceTest`, research harness on this branch, matrix 6337789428120909812); it excludes the capture
stop and insertion. AI polish column: portable engine (§1.4) except the S26 row.

| Phone | Tier | Transcript, 3 s / 19 s / 57 s of speech | AI polish, line / paragraph |
|---|---|---|---|
| Galaxy S26 Ultra (owned) | Recommended | ~0.7 s for a short take; ~3 s for 40 s (`polish-engines.md`, 2026-09-01) | 0.2 s / 1.6 s on the graphics chip |
| Pixel 8 | Recommended | not run | 1.1 s / 5-6 s |
| Galaxy A35 (Exynos 1380) | Mid | 2.0 s / 3.6-4.6 s / 8.7 s | 0.9 s / 5 s |
| Galaxy A34 (Dimensity 1080) | Mid | 2.7-2.9 s / 5.8 s / 14.5 s | 2.4-2.8 s / 8 s |
| Galaxy A05s (Snapdragon 680, the A23's chip) | Blocked | 8 s / 16.5 s / 40 s | 7-20 s |

Cold first request (binding, model initialization and inference together, not an isolated load time):
8.1-8.4 s mid tier, 15 s on the A05s.
Not yet measured: the borderline A76 chips (Helio G99 in Galaxy A15/A16, Dimensity 6100+/6300). They are
the most common mid-tier chips, so one run on a Galaxy A15 (`a15`, Helio G99) decides whether they stay mid
tier or move to blocked. It runs on the next free daily Test Lab allowance, before chunk 3.

### 16.2 Suggested phones (DRAFT, not for publication yet; copy review pending)

Before publication (chunk 3): list exact models with their regional chip variant and RAM, mark every untested
entry "expected", avoid blanket series claims (a Dimensity 8000/8100 phone is mid tier; a Tecno Spark 30 5G on
Dimensity 6300 is mid tier, not blocked), and describe the Pixel 8 evidence as local-polish testing only.
The working draft below follows that rule loosely and is replaced, not edited, at chunk 3.


- **Works best:** Galaxy S26, S25, S24, S23 and S22 series; Galaxy Z Fold and Z Flip 4 and newer; Pixel 8 and
  newer, including 8a; recent Snapdragon 8 and Dimensity 8000/9000 phones. Tested: Galaxy S26 Ultra, Pixel 8.
- **Works, a little slower:** Galaxy A55, A54, A35, A34, A25; Galaxy S21 and S20 FE; Pixel 6, 6a, 7, 7a; phones
  with Snapdragon 6 and 7 Gen or Dimensity 7000-series chips. Tested: Galaxy A35, A34. Pending: Galaxy A15/A16.
- **Not supported:** entry phones such as Galaxy A05, A05s, A04, A14 4G and A23 4G; Redmi 10, 12C and 13C;
  Tecno Spark and Infinix Smart; Moto g04 and g24; phones with Helio G85/G88, Snapdragon 680/662 or Unisoc
  T606/T615/T616 chips. Tested: Galaxy A05s.

Public wording rule (`content-brand.md`): a phone is "tested" only with a receipt; every other name is
"expected" and the list says so.

### 16.3 Play exclusion (chunk 3, founder approval at action time)

One Device Catalog exclusion rule per Blocked chip in `soc-tiers.txt` (led by Unisoc T606, Helio G85/G88,
Snapdragon 680, Unisoc T615/T616, Helio P35). Catalog chip names hide regional variants and miss new chips,
so §16.4 is required, not optional.

### 16.4 In-app backstop and mid-tier notice (chunk 2)

`DeviceTier` (proposed) reads per-processor implementer, part and variant records from `/proc/cpuinfo` and
blocks only from complete, nonempty identification: Linux lists online processors only, so a snapshot with
offline performance cores could show A55 records alone. Missing, malformed, unknown or incomplete topology
(fewer records than `/sys/devices/system/cpu/possible` reports) means allow. Vendor parts are mapped with a
cited table (for example pytorch/cpuinfo `src/arm/uarch.c`). If every core is on a closed list of old designs (Arm A53 `0xd03`, A55 `0xd05`, A73 `0xd09`, A75 `0xd0a`, and
the Qualcomm and Samsung cores built on them, listed with sources at build time), the phone is low-end:
setup shows one screen saying this phone is not supported and why, and stops there: no model downloads and no
dictation (measured transcription alone is 8 s for a one-line sentence on this class, §16.1). This matches the
Play block for phones it missed. Enforcement is one tier decision applied before onboarding download
scheduling, background model work, and every dictation admission path, including existing installations and the
shortcut, tile, notification and service entries; blocked work is refused before model initialization or
microphone capture, and stored models and user data are kept. Candidate call sites (contents not yet read):
`OnboardingPolicy.kt`, `OnboardingViewModel.kt`, `ModelWorkViewModel.kt`, `ModelBootstrapApplication.kt`,
`ModelDelivery.kt`, `ModelDeliveryWorker.kt`, `VoiceInputActivity.kt`, `DictationTileService.kt`,
`DictationSessionService.kt`, `DictationSessionCoordinator.kt`, and `PolishService.kt:239-241` (warm-up accepted
independently of setup). Chunk 2 gets its own grounded plan and review with these sites mapped before build.
An unrecognised core design is never blocked: the Play rule covers known chips, and a wrong block is worse
than a slow phone. Mid-tier phones (decided by `DeviceTier`, independently of `S1RuntimeKind`, which stays governed by the
instruction mask and OpenCL probe) see one notice in setup and a line under
"This phone" in AI polish settings: works on this phone, a little slower than on newer phones. Copy goes
through `content-brand.md` review. Tests: literal `/proc/cpuinfo` fixtures from the four measured phones.

## 14g. Grounded round 6 (Codex, 2026-10-19, new scope) adjudication

PROCEED-WITH-REVISIONS; chunk 1 has no new blocker. All seven adopted: pre-A76 cutoff stated as policy with A75
and A76 phones measured before chunk 3 (1); custom-core chips unclassified, QCM4290 corrected (2); cpuinfo
completeness rule (3); enforcement points named, chunk 2 gets its own plan and review (4); tier decided apart
from runtime kind (5); phone list held as a draft with publication rules (6); timing labels corrected (7).

## 15. Related
#418, #234, #429, #431, research doc 2026-10-18, matrices 8271984804397284036, 8721408296902179664,
5865563733416675177.
