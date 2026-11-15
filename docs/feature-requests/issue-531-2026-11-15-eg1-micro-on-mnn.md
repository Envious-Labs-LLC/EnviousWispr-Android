# Issue #531: Run EG-1 Micro through MNN: 2026-11-15

GitHub issue: `#531` (Part of `#510`). Tier: REFACTOR (adds a Gradle module; also an engine plus model delivery). Status: DRAFT.
Gate 1 approved by the founder 2026-11-15 ("Ok continue" to the intent: EG-1 Micro on MNN, processor everywhere,
graphics chip only where it qualifies, S1-mini untouched, built on the EG-1 Micro branch, memory as a gate).

Stacked on `feat/polish-speed-510` (PR #530, issue #510), which sits on `feat/eg1-micro-local-polish` (PR #529,
issue #528). #529 adds EG-1 Micro on llama.cpp CPU; #530 adds S1-mini copy-ahead and the founder's single 15 s
local polish ceiling, which already sets EG-1 Micro to the shared 10 s cooperative / 12 s hard / 15 s watchdog
budgets (founder Gate 2, #510, 2026-11-14; `polish/EngineDeadline.kt` `forModel`, `ui/PolishWatchdogBudget.kt`
`forPolicy` on that branch). This change replaces EG-1 Micro's engine only and inherits those budgets unchanged.
Merge order: #529, #530, this PR (§12).

**Revision 1 (after the coverage round, 2026-11-15):** restacked on #530 and adopted its 15 s ceiling (EG-1's
18/20/23 s budgets no longer exist); cross-engine upgrade no longer uses `previous` (§3.2); privacy wording
corrected (§2.5.4); consumer dispositions (§5.1), async-edge matrix (§5.2), GPU qualification details (§3.5),
storage/packaging and memory measurements (§11) added.

**Revision 3 (after grounded round 2):** superseded statements removed (§4 `previous`, §7 storage row, §2.5.4
network paragraph); `ProviderConfigurationRepository` C for chunk 5 with separate CPU/OpenCL evidence; tokenizer
error branch proven by a host-build observation, not an end code; memory goal and ship criterion stated as a sampled
comparison.

**Revision 2 (after grounded round 1):** generation recipe now prefills explicitly; HTTP support turned off at build
(the earlier omni exclusion claim was wrong); storage checks include the cache; dispositions corrected
(`PolishSettingsViewModel`, `PolishSettingsViewModelTest`, `Eg1LocalPolishUiDeviceTest`, `ModelDeliveryWorker`,
`PolishPolicyParcelTest` to C); falsifiable telemetry, deadline and privacy tests; citations and claims narrowed.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code (`app/**`, a new `mnn-android/**` module, `third_party/MNN` submodule, `settings.gradle.kts`,
`.gitmodules`).
mixed_pr: true. Also `Docs/dev-tooling` (`docs/**`, `scripts/**` for the R2 publish recipe): `cited-symbols`.

**PAR rows closed:** none. Catalog row `eg1-polish|android|absent` stays absent until #528 and this change both
ship; the row then moves with the evidence in §13.

**Hardware UAT:** Y. A person with EG-1 Micro selected double-presses the side button, dictates *"hey Sam, quick
update, the launch moved to Thursday, sorry, Friday, and we need three things: the deck, the budget and the demo"*
into Gmail, and gets a polished email with "Friday" only and a three-item list, on the S26 and on a mid-range phone,
inside the EG-1 budget, with the `:polish` process's sampled private memory meeting the §13 comparison.

## Preface — User Rubric

Answered against named personas (primary: Meera Patel). The heading spelling is the plan checker's.

1. **Who and when.** **Meera Patel**, one-handed in Messages between school pickups, on a mid-range Galaxy A-series
   phone. Thirty seconds ago she dictated a list for her partner; thirty seconds from now she wants to send it.
   Today on a mid-range phone EG-1 Micro can take 14 to 25 s on a long message (llama.cpp CPU, §1) and S1-mini
   times out for some users.
2. **Why.** "It fixes my rambling into a proper list, and it doesn't make me wait."
3. **How invoked.** Unchanged: side button, Quick Settings tile or notification; polish runs after every take
   when "This phone" and EG-1 Micro are selected.
4. **Apps.** Messages, WhatsApp, Gmail, Slack, Google Docs, Notes. Email apps get the email shape EG-1 was trained
   for; chat apps get restraint.
5. **Natural input.** "ok so milk eggs and um the bread the seeded one"; "tell Raj I can't do three, sorry, four
   works"; "hi team, three updates, first the vendor signed, second, uh, QA starts Monday, third, the launch moved";
   "remind me to call the dentist tomorrow no wait Thursday"; "can you send me the photos from Saturday".
6. **Success.** The text lands as fast as S1-mini or faster, with self-corrections resolved and lists shaped. She
   notices nothing.
7. **Wrong-not-broken.** Output that is fast but subtly worse than today's EG-1 (a dropped correction, a lost
   name). That is why quality is gated on the same blind exam as the engine study (§11).
8. **Power-user hack.** Switching to cloud polish to avoid the wait. Speed here keeps text on the phone.
9. **Control ladder.** Unchanged: Off, deterministic cleanup only, This phone (S1-mini or EG-1 Micro), cloud. The
   engine is not a user setting; nothing new to choose.

### Cross-persona check

- **Priya** (S26-class phone): wants sub-second; the graphics-chip path targets her (§3, chunk 5).
- **Marcus**: restraint must not regress; covered by the dev-400 restraint rows in the exam.
- **Diana**: zero switching; unchanged path.
- **Dr. Elena Vasquez**: offline and verifiable. No network change; the engine runs in `:polish`; MNN is built
  from pinned source, its logging kept out of the phone's log (§2.5.4).
- **Aaron**: no modal interruptions; the first-load cache build happens inside the existing load deadline.
- **Meera**: the main beneficiary (mid-range speed).
- **Frank**: nothing new to configure. The one visible change: the EG-1 Micro card shows more storage (§3.4).

Tension: Frank and Meera (mid-range, storage-limited) versus memory. The cache file trades about 580 MB of storage
for about 0.9 GB less memory the phone cannot free (Mac measurement; phone numbers owed, §3.4). Memory wins: it is what decides whether Android closes the
app mid-dictation; storage is shown honestly before download.

---

## 0. TL;DR

EG-1 Micro on Android runs on llama.cpp's portable CPU path (PR #529), which is too slow on mid-range phones. A
2026-11-15 study (artifact `enviouswispr-android/20261115-103824-eg1-gpu-spike-b3b7`) found Alibaba MNN's CPU path
1.1 to 2x faster on every tested phone, its OpenCL path about 3x faster on the S26's Adreno GPU (slower than its own
CPU on every Mali phone), and an MNN export (`--hqq --quant_block 64 --lm_quant_bit 8`) that grades level or
better than today's file with fewer serious errors. With MNN's memory-mapped weight cache, the engine also holds
far less memory the phone cannot free (Mac: 238 MB versus llama.cpp's 681 MB). Fix: add an MNN runtime in a new
native module, deliver the MNN export as EG-1 Micro's model files, route EG-1 Micro to it (CPU everywhere, OpenCL
only on a qualified Adreno), and leave S1-mini on GenieX and llama.cpp. Evidence: unit tests, the full 4,352-case
blind exam, Test Lab speed and memory on four phones, and a phone pass on the S26 through Play internal testing.

## 1. Problem

- Telemetry, 30 days to 2026-11-15, production local polishes: 114 of 1,941 ended `LOCAL_TIMEOUT` (about 6%), on 13
  of 58 installations, concentrated on mid-range phones (Realme RMX3660, Galaxy A26, Pixel 7, a Redmi). Founder and
  internal installs not yet excluded (`.claude/knowledge/telemetry.md` RULE: customer-counts-exclude-test-installs);
  recompute in §11 before quoting.
- EG-1 Micro on llama.cpp CPU, same 45 fidelity cases, no prefix cache (study `RESULTS.md`): Galaxy A26 p50 3.78 s,
  long and email 13.7 to 16.0 s; Galaxy A15 8.24 s, 21.7 to 25.1 s (max 48.4 s); Pixel 7 5.96 s, 16.1 to 18.6 s.
  With #530, EG-1's budget is the shared 10 s cooperative, 12 s hard, 15 s watchdog, so on llama.cpp most long
  dictations on all three phones fail.
- Honest limit of this change: MNN CPU long and email cases measured 7.8 to 12.2 s on the A26 and 12.8 to 18.0 s on
  the A15 and Pixel 7 (study, hqq64 and base exports, no prefix cache). MNN brings the A26 class largely inside the
  10 s cooperative budget but not the A15/Pixel 7 class for long dictations; short dictations (p50 2.9 to 5.6 s)
  fit everywhere. Remaining levers (prefix cache, copy-ahead for hybrids) are follow-ups (§2.2).
- #528's own hardware run measured EG-1 Micro `:polish` peak resident 1,325 to 1,332 MiB on the S24 and Pixel 8.

## 2. Goals and non-goals

### 2.1 Goals

1. EG-1 Micro polish runs through MNN on every supported phone, CPU by default.
2. On each Test Lab phone (A26, A15, Pixel 7) and the S26, MNN CPU p50 and long-case time are below today's
   llama.cpp numbers on the same phone, measured in one matrix per phone.
3. On the S26 (and any phone whose Adreno GPU passes the canary), EG-1 Micro uses MNN OpenCL when it is faster and
   its output matches the CPU canary.
4. Quality: the full 4,352-case exam on the shipped MNN file is not significantly worse than the Q4_K_M file on any
   set, and serious errors do not rise.
5. Memory: every speed and quality run reports peak resident, private and file-backed memory. On each phone, the
   `:polish` process's maximum sampled RssAnon with EG-1 on MNN does not exceed llama.cpp's under the same sampling
   method (50 ms), separately for first-build and reused-cache runs, with the interval and observation windows
   reported. This is a sampled comparison, not an exact peak-private guarantee.
6. S1-mini behaviour, files, runtimes and budgets are byte-for-byte unchanged.

### 2.2 Non-goals

- Copy-ahead decoding for Qwen 3.5 (blocked in both engines; follow-up issue).
- Moving S1-mini to MNN (follow-up, measured separately).
- Making EG-1 Micro the default (founder product decision after the speed comparison).
- MNN prompt prefix cache (`warmPrefix`): v1 returns `false` (§3.6); the study's times already include re-reading
  the instructions on every request.
- NPU backends (MNN neuropilot / QNN).
- Releasing the speech model before polish on low-memory phones. Only if §11's memory numbers show pressure on a
  6 GB phone, as its own issue (heart path).
- A RAM eligibility gate (none exists today; §2.5.5).

## 2.5 Grounding brief

Commands: `git diff --stat 9276cae4..HEAD`; `grep -rnE "LocalPolishModel\.(EG1_MICRO|S1_MINI) ->"`;
`grep -rnE "S1RuntimeKind\.(GENIEX|PORTABLE_CPU) ->"`; `grep -rnE "polish_runtime|polish_provider"`;
`grep -rniE "16 ?KB|max-page-size|useLegacyPackaging"`; plus every file cited, read on branch `feat/eg1-mnn-531`
at `f30652ba`. MNN facts are from the pinned checkout `alibaba/MNN@b6a32165e2855106b76852fba80793d58a829663`.

Names from the pinned MNN checkout, the study artifact or git (not in this tree): `MNN_USE_LOGCAT` (external), `b6a32165e2855106b76852fba80793d58a829663` (external), `f30652ba` (external), `generate_init` (external), `kvcache_mmap` (external), `modelUUID` (external), `mtok` (external), `set_config` (external), `stoped` (external), `tmp_path` (external), `use_cached_mmap` (external), `use_mmap` (external), `LLM_LOG_TO_STRING` (external), `MNN_BUILD_LLM_OMNI` (external), `MNN_ERROR` (external), `MNN_PRINT` (external), `e768a8ea` (external), `httplib` (external), `LLM_SUPPORT_HTTP_RESOURCE` (external), `MNN_LLM_BUILD_DEMO` (external).

### 2.5.1 Producer to owner to consumer

1. Take ends in the app process; `PipelineBindings` binds `PolishService` with `BIND_AUTO_CREATE`
   (`ui/PipelineBindings.kt:110`) and unbinds at take end (`:120`). Setup warm-up and qualification also bind
   (`ui/EngineWarmUp.kt:81`, `polish/ProcessingCheckClient.kt:61`).
2. `PolishService` runs in `:polish` (`AndroidManifest.xml:104-107`); AIDL `IPolishService.aidl:14-59` is
   append-only.
3. Policy carries the model: `LocalPolishModel.EG1_MICRO` (`polish/LocalPolishModel.kt:12`).
4. Runtime choice: `S1RuntimeKind.runtimeKindFor` (`polish/S1RuntimeKind.kt:29-35`) maps EG-1 to `PORTABLE_CPU`;
   `PolishService.runtimeFor` closes the live runtime before creating one of another kind
   (`polish/PolishService.kt:88-109`); the factory is the exhaustive `when` at `polish/S1Runtime.kt:34-37`.
5. Model path: `S1ModelSelector.resolve` passes `descriptor.files.first()` (`polish/S1ModelSelection.kt:80`) to
   `S1Runtime.load`.
6. Prompts: `EGOneNamedLanguagePromptBuilder` (`:16-87`) chosen at `PolishService.kt:804-818`; the chat template is
   applied natively for llama.cpp (`llama-android/src/main/cpp/s1_jni.cpp:387-398`), greedy (`:448-453`), on
   the restacked branch.
7. Output: `PolishService.kt:830-856` (trim, `ERROR:` protocol, `</think>` strip, blank rejected, refusal check).
8. Outcome and telemetry: `LocalPolishDiagnostics.properties()` (`polish/LocalPolishDiagnostics.kt:19-26`) with
   `polish_runtime` closed over `S1RuntimeKind` identities (`telemetry/PostHogSchema.kt:118`,
   `telemetry/SentrySchema.kt:156`), parcelled by enum name (`LocalPolishDiagnostics.kt:35, 43`).
9. Model files: delivered by `ModelDeliveryWorker` into `noBackupFilesDir/models/<id>/` (`models/ModelStorage.kt:8-9`),
   verified as exactly the manifest's files plus `.verified-receipt` (`models/ModelDelivery.kt:137-146`).

### 2.5.2 Existing authority

- Runtime abstraction: `S1Runtime` (`polish/S1Runtime.kt:11-31`) with exactly two implementations,
  `S1GenieXRuntime` and `S1PortableRuntime`. MNN joins as a third implementation; no new abstraction.
- Engine identity for telemetry: `S1RuntimeKind.identity`. New members flow into both closed allowlists
  automatically (`.entries`).
- Multi-file models: already supported in a flat folder (Parakeet 3 files, EG-1 2 shards;
  `models/ModelManifest.kt:36-38, 105`).
- Native libraries: `:llama-android` builds pinned `third_party/llama.cpp` from source with renamed `ew-*` libraries
  (`llama-android/src/main/cpp/CMakeLists.txt:20-29`). MNN follows the same pattern (pinned submodule, source
  build) in its own module so the two native trees stay independent.
- 16 KB alignment: enforced at release by `scripts/release/publish.py:120-122`.
- Memory reporting in tests: `Eg1MicroHardwareDeviceTest` asserts VmRSS and VmHWM at most 1.5 GiB (`:152-153`,
  `:399`); no private/file-backed split. `polish-engines.md` RULE: every-model-run-reports-memory requires it.

### 2.5.3 Prior attempts and live direction

- #449: portable llama.cpp CPU path for S1 (landed). #528 / PR #529: EG-1 Micro on llama.cpp CPU (open, not
  shipped). #510 / PR #530: S1-mini copy-ahead and the 15 s ceiling (open).
- The 2026-11-15 study (`polish-engines.md` FACT: eg1-micro-gpu-and-engine-spike-2026-11-15) is the evidence for
  this plan; its `RESULTS.md` is the numbers owner.
- macOS precedent: EG-1 runs on its own engine (MLX + EnviousDecode) while S1 stays in a separate module. Android
  follows the split: EG-1 on MNN, S1-mini on GenieX/llama.cpp.
- Binding decisions kept: only one heavy model resident (`architecture-rules.md` RULE: isolate-limbs); polish model
  leaves with its process (RULE: no-idle-cost); one 15 s local polish ceiling for every local model (founder, #510,
  2026-11-14), unchanged here.

### 2.5.4 Boundaries

- **Process:** MNN loads only in `:polish`, never in the app process. `ModelSwap` already kills `:polish` before
  admitting model files (`models/ModelSwap.kt:32-69`).
- **Lifecycle:** `PolishService` destroy closes the runtime on the worker (`PolishService.kt:581-587`); stalls kill
  the process (`:594-600`, `:670-673`); local timeout poisons and kills (`:362-394`). The MNN runtime's `close()`
  must release the `Llm` and unmap the cache on the worker thread, and a kill mid-cache-build must leave a cache the
  next load rebuilds rather than trusts (§2.5.5 premise P3).
- **Cancellation:** the cooperative deadline must stop MNN generation. `Llm::response` runs to completion; the JNI
  layer instead drives `generate_init` plus single-step `generate(1)` and checks an abort flag between tokens
  (`transformers/llm/engine/include/llm/llm.hpp:219-223`).
- **Privacy:** MNN on Android logs through `__android_log_print` only when built with `MNN_USE_LOGCAT`
  (`include/MNN/MNNDefine.h:29-30`); otherwise `MNN_PRINT`/`MNN_ERROR` are `printf` (`MNNDefine.h:38-39`). Logging
  is NOT compiled out: an app process's stdout is not routed to logcat by default, so the text is discarded, but
  this is a platform default, not our guarantee. Build with `MNN_USE_LOGCAT=OFF`, and in chunk 1 trace every
  reachable MNN print in the LLM path for prompt or output text (`llm.cpp` prints shown in §2.5.5 P10) and run a
  synthetic-prompt canary with logcat captured, asserting the canary string never appears. Network: our build
  explicitly disables `LLM_SUPPORT_HTTP_RESOURCE` (P10); the packaged-library import audit verifies the resulting
  boundary, and until that audit runs the packaged result is NOT VERIFIED. Automatic telemetry stays
  allowlist-only (`telemetry/PayloadSanitizer.kt`).
- **Storage:** the weight cache must live outside the model folder (verification rejects extra files,
  `ModelDelivery.kt:142`).

### 2.5.5 High-risk premises and evidence

| # | Premise | Evidence | Status |
|---|---|---|---|
| P1 | MNN runs Qwen 3.5 (Gated DeltaNet) correctly on phone CPU and Adreno OpenCL | Study: 45-case runs on 4 phones; MNN OpenCL vs CPU 36 to 39 of 45 identical | Measured |
| P2 | The hqq64 export matches or beats Q4_K_M quality | Study round 2: dev-400 69.0 vs 68.5, email 77.4 vs 74.6, serious 34/40 and 11/25 (648 cases) | Measured on 648; full 4,352 owed (§11) |
| P3 | `use_mmap` cache cuts private memory with identical output, and an interrupted build is rebuilt | Mac: 45/45 identical, private 238 MB vs 1,166 MB; sync marker `0_0_0_0_sync.static` checked once at backend creation (`source/backend/cpu/CPUBackend.cpp:289-303`) | Mac measured; phone and kill-mid-build owed in chunk 1 |
| P4 | The cache file is not keyed by model content | `CPUBackend.cpp:282-287` prefix is precision/memory/power only; `modelUUID` is commented out | Read; so the cache folder must be per revision and per MNN build (§3.4) |
| P5 | MNN library meets 16 KB pages | `llvm-readelf -lW libMNN.so`: LOAD align `0x4000` | Measured on the study build; re-check the Gradle build |
| P6 | Generation can stop mid-way at the deadline | `llm.hpp:219-223` (`generate_init`, `generate(int)`, `stoped()`) | Read; proven by a unit-on-device abort test in chunk 2 |
| P7 | No RAM gate exists | `device/DeviceTier.kt:22-43` (cores and i8mm only); `ModelDeliveryWorker.kt:61` | Read |
| P8 | Thinking stays off without a `context.json` file | Study used `context.json` `{"enable_thinking": false}` next to the model | Not verified: chunk 1 sets it through `set_config` and checks no `<think>` appears; otherwise ship the file in the manifest |
| P9 | MNN source build fits the existing CI build | Not verified: chunk 1 measures the clean `assembleDebug` time with MNN; CI `build-check` runs unit tests only |
| P10 | No MNN path we build can send or log dictated text | The LLM engine globs every source file (`transformers/llm/engine/CMakeLists.txt:44`), so `src/omni.cpp` is built, and its HTTP client (`omni.cpp:2208-2218`) is compiled in because `LLM_SUPPORT_HTTP_RESOURCE` defaults ON (`CMakeLists.txt:4`). Our build sets `LLM_SUPPORT_HTTP_RESOURCE=OFF` explicitly; `transformers/llm/engine/app/*` (the other `httplib.h` users) is not built. This removes network capability; it was never evidence of transmission. One print can carry user text: `tokenizer/tokenizer.cpp:575` prints an unencodable fragment through `MNN_ERROR` (stdout, discarded by default). `LLM_LOG_TO_STRING` stays OFF (`transformers/llm/engine/CMakeLists.txt:84-87`). | Read; chunk 1 adds the synthetic-canary logcat test and an `llvm-readelf --dyn-syms` / `llvm-nm -D` audit of the packaged `libMNN.so` imports that FAILS if it cannot read them and fails on any socket/connect/getaddrinfo import |

## 3. Design

### 3.1 Runtime

Add `S1RuntimeKind.MNN_CPU` (proposed) and `S1RuntimeKind.MNN_OPENCL` (proposed), identities
`mnn-<pinned short sha>-cpu` and `mnn-<pinned short sha>-opencl`, backends `{CPU}` and `{GPU}`. Add
`EgOneMnnRuntime` (proposed) implementing `S1Runtime`, constructed by the factory's exhaustive `when`.
`runtimeKindFor(EG1_MICRO)` returns `MNN_OPENCL` only when the EG-1 GPU qualification for this phone and model
revision passed (§3.5), else `MNN_CPU`. `S1_MINI` is unchanged.

`EgOneMnnRuntime` calls `MnnNative` (proposed), a JNI library `libenviouswispr-mnn.so` (proposed) in a new Gradle
module `:mnn-android` (proposed), which links `libMNN.so` built from `third_party/MNN` (proposed submodule, pinned
to `b6a32165e2855106b76852fba80793d58a829663`).

JNI surface (one `Llm` per process, guarded like `s1_jni.cpp`):
- `load(configPath, backend, threads, cacheDir): String` creates the `Llm`, applies one `set_config` with
  `{"backend_type", "thread_num": 4 or 68 for OpenCL, "precision": "low", "memory": "low", "sampler_type":
  "greedy", "reuse_kv": false, "use_mmap": true, "tmp_path": cacheDir, "jinja": {"context": {"enable_thinking":
  false}}}` and loads. Returns `""` or an `ERROR:` string, matching the existing protocol.
- `generate(system, user, maxTokens, timeoutMs): String` resets, applies the chat template through
  `apply_chat_template(ChatMessages)`, tokenizes, `generate_init`, then PREFILLS with `generate(input_ids, 0)`
  (`llm.cpp:1131-1176`; `generate_init` only initialises state, `:983-1013`, and `generate(int)` decodes from
  existing outputs, `:1117-1128`), checks status and the abort flag, then steps `generate(1)` until stop, cap or
  abort. Chunk 1 compares this loop's complete output with `response()` on all 45 fidelity cases (must be identical)
  and measures any speed cost.
- `abort()`, `close()`, and `lastGeneration` counters (prompt tokens, generated tokens, prefill and decode ms).

### 3.2 Model files

Replace `ModelManifest.eg1Micro`'s files with the MNN export, revision `eg1-micro-1.0-g3d-mnn-hqq64` (proposed),
`engineId = "mnn"`, `previous = emptyList()`. `previous` cannot carry the GGUF revision: its contract is "older
revisions this build's engine still loads unchanged", same engine and file names (`models/ModelManifest.kt:28-33`,
`ModelManifestTest.previousRevisionsAreTheSameModelAtAnotherPin`), and listing it would hand a GGUF to MNN through
`usableDescriptor` (`models/ModelDelivery.kt:133-134`). #529 never reached production, so GGUF installs exist only on
internal test phones. On those, the folder no longer verifies against the MNN descriptor, EG-1 Micro reads as not
installed (deterministic fallback, existing copy), and the user downloads again; admission replaces the old folder
through the existing `.old` swap. Chunk 3 verifies on a phone holding the GGUF: not-installed state, download,
admission, the old files gone, and no GGUF path ever reaching `MnnNative.load` (the runtime also refuses a load path
whose name is not `config.json`). Files, flat, with
`config.json` first so `files.first()` stays the load path: `config.json` (our greedy config), `llm_config.json`,
`llm.mnn`, `llm.mnn.weight`, `tokenizer.mtok`. The unused vision files (`visual.mnn`, `visual.mnn.weight`, 63 MB) and
the debug graph dump `llm.mnn.json` are not shipped. Sizes and SHA-256 come from the published files; download about
560 MB versus 542 MB today. Published to R2 under `eg1/<revision>/` (existing 3-segment rule,
`ModelDelivery.kt:563-567`).

### 3.3 Prompts and output

Unchanged Kotlin prompts (`EGOneNamedLanguagePromptBuilder`). The chat template comes from the export's
`llm_config.json` (Qwen 3.5 Jinja). Output handling at `PolishService.kt:830-856` is unchanged; `EgOneMnnRuntime`
returns text or `null` exactly as `S1PortableRuntime` does. The builder's 1,024 is an OUTPUT ceiling
(`EGOneNamedLanguagePromptBuilder.kt:82-86`); it still subtracts from `S1Config.CONTEXT_SIZE`, which stays the input
plus output budget for EG-1 on MNN (MNN's own default is larger; the JNI layer refuses a prompt that would exceed it,
returning `ERROR:` so the deterministic fallback runs).

### 3.4 Weight cache (memory)

`use_mmap: true` with `tmp_path` set to `noBackupFilesDir/model-cache/eg1-micro/<revision>-<mnn short sha>/`
(proposed). Per revision and per MNN build because the cache is not content-keyed (P4). Not in `cacheDir`: the OS can
clear that, forcing a slow first load (cache build) mid-dictation. Lifecycle:
- Built on first load (one-time, inside the existing 60 s load deadline; Mac first load 442 ms, phone measured in
  chunk 1). Reused afterwards (`use_cached_mmap`, default true).
- Deleted, file by file (`tools-and-apps.md` RULE: never-unattended-recursive-delete), when EG-1 Micro is removed,
  when a different revision is admitted, and on load if a folder for another revision or build exists.
- Counted on the EG-1 Micro model card and the Storage page as part of EG-1 Micro (about 580 MB), shown before
  download as "about 1.1 GB on this phone".

Alternative rejected: no cache (weights in private memory, 1.2 GB on the Mac): more memory the phone cannot free
than today's engine, which fails goal 5.

### 3.5 Graphics chip (chunk 5)

OpenCL is offered for EG-1 only where: the OpenCL device name reports Adreno (Mali measured slower than CPU on every
tested phone), the existing qualification canary passes on `MNN_OPENCL` for this model revision, and its canary
output matches the `MNN_CPU` canary. Qualification reuses `qualifyProcessingForModel` (#528). The OpenCL tuning file
`mnn_cachefile.bin` lives in the same cache folder. A failed or mismatching canary keeps CPU; no user setting is
added (EG-1's processing selector stays hidden as in #528). If chunk 5 cannot meet this on the S26, it is cut and
filed; chunks 1 to 4 ship without it.

Today EG-1 can only qualify CPU: `ProcessingEnvironment.implementedBackends` returns `{CPU}` for `EG1_MICRO`
(`processing/ProcessingEnvironment.kt:17-23`), and qualification selects the current runtime before testing. Chunk 5
must therefore: (a) let `implementedBackends(MNN_OPENCL, EG1_MICRO)` include GPU only on an Adreno OpenCL device;
(b) qualify GPU the first time with no existing GPU approval, running the canary on `MNN_CPU` and `MNN_OPENCL` in one
qualification and comparing outputs; (c) prove the GPU actually ran (MNN reports its backend after load; a silent CPU
fallback counts as a failed GPU qualification); (d) require a measured speed advantage on the canary (OpenCL total
below CPU total); (e) key the evidence by `s1ContextId`-style identity including the MNN kind, so evidence for one
kind is never read as the other's, and stale or rejected evidence keeps CPU.

### 3.6 Prefix cache

`EgOneMnnRuntime.warmPrefix` returns `false` (interface default). MNN's prefix cache needs `kvcache_mmap` and a
disk state file; it is a separate optimisation, filed as a follow-up. #529's llama.cpp `warmPrefix` code stays for
S1-mini's portable path.

### 3.7 Alternatives rejected

- Keep llama.cpp for EG-1: slower on every phone; no Mali GPU route.
- MNN for both models now: puts S1-mini, the shipping default, at risk in the same change.
- Prebuilt `libMNN.so` checked in: not reproducible from source; the llama.cpp precedent is a pinned submodule.
- Separate `:polish-mnn` process: more binder plumbing and two heavy processes possible at once; the existing
  single-runtime swap already enforces one resident model.

Consolidation: none. This adds a third runtime behind the existing `S1Runtime` owner and merges no duplicate
authorities; the dominant root (per-model runtime choice) keeps its one owner, `S1RuntimeKind.runtimeKindFor`.

## 3b. Ownership justification

The MNN runtime lives in `PolishService` as a third `S1Runtime` implementation because `PolishService` already
owns per-model runtime selection and the one-runtime-at-a-time swap (`PolishService.kt:88-109`). The alternative was
a new service and process for MNN, but that duplicates binding, deadlines, poisoning and telemetry and makes two
heavy processes possible.

## 4. Contract deltas

- `S1RuntimeKind`: two new members. Meaning: "which native engine and processor ran this polish". Telemetry
  `polish_runtime` gains two closed values automatically.
- `ModelManifest.eg1Micro`: new MNN revision, `engineId = "mnn"`, five files, `previous = emptyList()`. Internal GGUF
  installations require downloading the MNN revision.
  Meaning: EG-1 Micro installed means MNN files present and verified.
- `S1Runtime`: unchanged interface; new implementation.
- `ModelStorage` (or a sibling owner): a per-model cache folder outside the model root, removed with the model.
- No AIDL change. No persisted preference change (the model choice key from #528 is reused).

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Exhaustive `when` over `S1RuntimeKind` | `S1Runtime.kt:34-37`, `S1RuntimeKind.kt:32-35` and `decide`/`reason` (`:41-60`). The factory `when` is exhaustive (compiler-enforced); `decide` uses predicate branches, not an enumeration, and is edited by hand (it never returns an MNN kind; MNN kinds come only from `runtimeKindFor`). |
| Exhaustive `when` over `LocalPolishModel` | `PolishService.kt:645, 760, 804`; `polish/EngineDeadline.kt:19`; `PolishWatchdogBudget.kt:20`; `ProcessingEnvironment.kt:20-23`; `ProviderConfigurationRepository.kt:551, 559`; `PolishScreen.kt:136, 193`. None changes branch meaning; `PolishService.kt:645` (warm prefix) becomes a no-op for EG-1 through the runtime. |
| Places assuming llama.cpp for EG-1 | `PolishService.kt:102-106` (hard-coded reason string); `ModelManifest.kt:75` (`engineId`); `ProcessingEnvironment.kt:39` (context identity hashes llama constants); `Eg1MicroHardwareDeviceTest.kt:147, 309-336` (asserts `PORTABLE_CPU`, calls `S1Native`). Each is changed (§10). |
| Files written next to model files | Enumerated: MNN writes only to `tmp_path` (`llm.cpp:265-299`); with `tmp_path` outside the model folder, none. |
| Process deaths during cache build | Local timeout, load stall, ModelSwap, OS kill. All leave no sync marker; next load rebuilds (P3, proven in chunk 1). |
| Stale completions | Unchanged: generation ids and poisoning in `PolishService` are engine-agnostic. |
| Model swap while loaded | `ModelSwap.exclusive` kills `:polish` first (`ModelSwap.kt:57-68`); the cache folder of the old revision is deleted after admission. |

### 5.1 Consumer dispositions (complete population)

Producer sweep on the restacked branch: `grep -rlE "eg1Micro|EG1_MICRO|S1RuntimeKind" app/src` = 43 files.
Dispositions: **C** changed, **U** unchanged with reason, **X** UNCHECKED (none left).

| Files | Disp. | Reason |
|---|---|---|
| `S1RuntimeKind`, `S1Runtime`, `PolishService`, `ProcessingEnvironment`, `ModelManifest` | C | §10 |
| `S1RuntimeKindTest`, `PolishPolicyTest` (asserts `runtimeKindFor(EG1_MICRO) == PORTABLE_CPU` without a hardware probe) | C | expect `MNN_CPU`; keep the no-probe assertion |
| `ModelManifestTest`, `S1ModelSelectorTest` (EG-1 GGUF fixture names) | C | MNN file set, `engineId`, `config.json` first |
| `Eg1MicroHardwareDeviceTest`, `CopyAheadDeviceTest` (EG-1 arm drives `S1Native` with the GGUF) | C | EG-1 arms drive the MNN runtime; memory split added (§11) |
| `LocalPolishDiagnosticsTest` | U | timing tests only, no parcel operations; parcel coverage lives in `PolishPolicyParcelTest` |
| `PolishPolicyParcelTest` | C | add both MNN kinds to its real parcel round trips (`:43-50`) |
| `PolishSettingsViewModelTest` | C | hard-codes EG-1's portable runtime (`:151-153`, `:178`); expect `MNN_CPU`, keep the model-switch assertions with literal expected values |
| `Eg1LocalPolishUiDeviceTest` | C | requires `"541.9 MB"` (`:126`); new literal for the MNN download and the "about 1.1 GB on this phone" line |
| `FirebaseModelFixture` | U | stages whatever `ModelManifest.eg1Micro` lists (`:23`); fixture bytes regenerated from the new descriptor |
| `ModelDeliveryWorker` | C | storage checks use manifest bytes plus 128 MiB (`:104-105`, `:119-126`); add the cache requirement (§3.4) |
| `EngineDeadline`, `PolishWatchdogBudget` and their tests | U | budgets are per model, not per engine; 10/12/15 s from #530 |
| `LocalPolishDiagnostics`, `PostHogSchema`, `SentrySchema`, `MajorFaults` | U | read `S1RuntimeKind.entries`/identity generically |
| `PolishSettingsViewModel` | C (chunk 5 only) | computes the current runtime's context before qualification and discards other contexts' results (`:123-139`, `:187-195`); chunk 5 validates against the explicitly requested qualification runtime, keeps CPU and OpenCL evidence separately, and the take's policy freezes the chosen route. Chunks 1 to 4: unchanged (EG-1 stays CPU-only) |
| `ProviderConfigurationRepository` | C (chunk 5 only) | reads one qualification record per model (`:192-203`), replaces evidence when the context changes (`:240-253`), one EG-1 key (`:550-552`). Chunk 5 persists and retrieves EG-1 CPU and OpenCL evidence separately, preserving S1-mini's storage; recording either runtime's result preserves the other, including after repository recreation; the selected evidence travels in the existing policy snapshot. Chunk 5 is cut if this contract is not completed |
| `LocalPolishModel`, `PolishPolicy`, `ProcessingCheckClient`, `S1ModelSelection` and their tests, `ProviderConfigurationRepositoryTest` | U | carry the model choice and descriptor, not the engine; `S1ModelSelection` keeps `files.first()` (now `config.json`) |
| `AppLaunchFacts`, `TakeFactsTest`, `AppActions`, `AppShell`, `SettingsActivity`, `ReadinessViewModel`, `ModelWorkViewModel` (+ tests, `ModelWorkOwnershipTest`), `PolishScreen`, `PolishLocalControls`, `PolishStatusChipTest`, `PolishScreenLayoutTest`, `ModelDeliveryNotification` | U except size lines | model id and state only; `PolishLocalControls`/model card size lines change for the cache (§3.4) |
| `ProcessingObservationTest`, `S1LoadedConfigurationTest`, `PolishFailsOpenTest`, `TakePolishControllerTest`, `SustainedDictationDeviceTest` | U | engine-agnostic outcomes; S1-only device test |

### 5.2 Async edges (load, cache build, generate, abort, close)

| Class | Load / cache build | Generate | Abort / close |
|---|---|---|---|
| Interrupted | Kill mid-build leaves no sync marker; next load rebuilds (chunk 1 kill test). Kill after the marker but mid-write of a later page: NOT VERIFIED; chunk 1 corrupts a completed cache file and asserts load either rebuilds or fails to `ERROR:` (never garbage text) | Process death mid-generation: existing poison/kill path; no partial text delivered | Close during prefill: abort flag is checked only between tokens, so a prefill (300 to 800 tokens) finishes first; worst case is one prefill (A15 measured under 2 s). Accepted: the hard deadline still kills the process |
| Deleted | Cache folder deleted while not loaded: rebuilt next load. Model folder removed: `ModelSwap` kills `:polish` first | n/a (no files read during generation once loaded, except mmapped cache pages; deleting a mapped file keeps the inode alive) | n/a |
| Mutated | Cache from another revision or MNN build: folder name mismatch, deleted, rebuilt (P4) | n/a | n/a |
| Concurrent | One `Llm` per process, guarded by the same single-worker executor `PolishService` uses; qualification and requests serialise on it | Next-request isolation: `reset()` before every generate; a chunk 2 test runs two different prompts back to back and compares with fresh-process output | `abort()` from the deadline thread races `generate(1)`: atomic flag only, no native free; `close()` runs on the worker after generate returns |
| Absent | Not enough storage: the download check (`ModelDeliveryWorker`) requires model bytes plus the cache plus the existing 128 MiB margin, and `EgOneMnnRuntime` re-checks free space before a cache build; if short, it returns `ERROR:` so the deterministic text is delivered (it never falls back to loading without `use_mmap`, which would break the memory goal) and never fills the disk the recording and History need. An unwritable folder only logs (`llm.cpp:269-285`) and still configures mmap, so the runtime creates and probes the folder itself first. Real full-disk behaviour: NOT VERIFIED until chunk 1 exercises it | Model file missing: existing `LOCAL_NOT_READY` path | n/a |
| Stale | Old-revision cache folders swept on load (file by file) | A completion after the deadline: existing generation ids drop it | n/a |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| New `S1RuntimeKind` members | `PostHogSchema.kt:118`, `SentrySchema.kt:156` | closed over entries | same, two more values | No | `PostHogSchemaTest`, `SentrySchemaTest` |
| New `S1RuntimeKind` members | `LocalPolishDiagnostics.kt:35, 43` | `valueOf` by name | same | No | parcel round-trip test |
| New members | History label `PolishService.kt:515-520` | `<name> by <creator> (<UNIT>)` | same, unit CPU or GPU | No | `ProcessingObservationTest` |
| `eg1Micro` revision and files | `ModelUpdates.kt:16-45` | `previous` empty | still empty (engine change, §3.2); GGUF installs read as not installed and re-download | No (data) | `ModelManifestTest`, chunk 3 phone check |
| `eg1Micro` files | `ModelCards.kt`, `PolishLocalControls.kt:92, 202`, `StoragePage` | manifest sum | manifest sum plus cache | Yes | `ModelDeliveryUiTest`, layout test |
| Cache folder | model remove path `ModelDelivery.kt:389` | removes model folder | also removes cache folder | Yes | new unit test |
| `engineId = "mnn"` | `ModelManifestTest`, notices | `llama.cpp` | `mnn` | Test data | `ThirdPartyNoticesTest` |
| MNN library | `THIRD_PARTY_NOTICES.txt` | llama.cpp, GenieX | plus MNN Apache-2.0 | Yes | `ThirdPartyNoticesTest` |

## 7. Failure-mode by caller table

| Failure | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| MNN load fails | `MnnNative.load` | `PolishService.loadModel` | deterministic text, existing local-not-ready copy | none | next take |
| Cache folder not writable or full disk | MNN `tmp_path` | load | runtime storage or writability checks refuse loading with `ERROR:`; the existing deterministic fallback delivers text; no deliberate cache-disabled retry | none | next take |
| Generation exceeds deadline | step loop | cooperative abort, then watchdog | deterministic text (existing `LOCAL_TIMEOUT` path) | timeout outcome | next take |
| OpenCL canary mismatch | qualification | `PolishSettingsViewModel` | nothing; CPU used | qualification false for revision | re-qualify on new revision |
| Stale cache from another revision | load | runtime | nothing; folder deleted, rebuilt | none | automatic |
| Download of new files fails | `ModelDeliveryWorker` | delivery UI | existing repair and retry copy | staged parts | existing |

Copy follows existing EG-1 strings from #528; no new user-facing sentence except the size line ("about 1.1 GB on
this phone"), checked against `content-brand.md`.

## 8. Caller-visible signals

- `polish_runtime` value now distinguishes MNN CPU and OpenCL from llama.cpp.
- `ProcessingUsage.modelId` and artifact stamp change with the new revision (`PolishService.kt:309-311, 535-540`):
  history entries show which file polished them.
- Presence of the cache folder signals nothing to UI beyond storage size.

## 9. Fallback source of truth

| Failure | Expression | Source | Why | Predicate | If none | Consumer |
|---|---|---|---|---|---|---|
| Any MNN failure | deterministic cleanup text | existing `PolishService` fallback | heart-and-limbs rule | non-empty text | original transcript | session owner |
| OpenCL unqualified | `MNN_CPU` | qualification record | measured per phone | canary pass and match | CPU | runtime choice |

## 10. File-by-file changes

- `.gitmodules`, `third_party/MNN` (new submodule, pinned).
- `settings.gradle.kts`: include `:mnn-android`.
- `mnn-android/build.gradle.kts`, `mnn-android/src/main/cpp/CMakeLists.txt` (MNN options: `MNN_BUILD_LLM=ON`,
  `MNN_LOW_MEMORY=ON`, `MNN_ARM82=ON`, `MNN_OPENCL=ON`, `MNN_SUPPORT_TRANSFORMER_FUSE=ON`, `MNN_USE_LOGCAT=OFF`,
  `MNN_SEP_BUILD=OFF`, `LLM_SUPPORT_HTTP_RESOURCE=OFF`, `MNN_LLM_BUILD_DEMO=OFF`, tests/tools/demos/converter off,
  `-Wl,-z,max-page-size=16384`), `mnn_jni.cpp`,
  `MnnNative.kt`.
- `app/build.gradle.kts`: depend on `:mnn-android`.
- `polish/S1RuntimeKind.kt`: two members, `runtimeKindFor` EG-1 branch, `reason` text.
- `polish/S1Runtime.kt`: factory branch. `polish/EgOneMnnRuntime.kt` (new).
- `polish/PolishService.kt`: reason string at `:102-106` derived from the kind; cache folder passed to load.
- `polish/ProcessingEnvironment.kt`: context identity for MNN kinds hashes MNN settings, not llama constants.
- `models/ModelManifest.kt`: `eg1Micro` revision, files, `engineId`, `previous`.
- `models/ModelStorage.kt` (or `ModelDelivery.kt` remove path): cache folder owner and deletion.
- `models/ModelDeliveryWorker.kt`: storage requirement includes the cache bytes for EG-1 Micro.
- `polish/PolishSettingsViewModel.kt` (chunk 5 only): qualification validated against the requested runtime.
- UI size lines (`ModelCards.kt`, `PolishLocalControls.kt`, `StoragePage`).
- `app/src/main/assets/THIRD_PARTY_NOTICES.txt`, `scripts/generate-notices.py` if it enumerates libraries.
- Tests listed in §11. `Eg1MicroHardwareDeviceTest`: assert MNN kinds, drop the `S1Native` prefix-parity block for
  EG-1 (S1-mini keeps its own), add the memory split.
- `docs/` R2 publish recipe for the MNN files.

## 11. Testing

1. **Classes.** Product outcome: EG-1 polishes through MNN within budget with correct text (device). Drift guard:
   telemetry allowlists include the new kinds; manifest upgrade path; cache folder removal. Harness contract: the
   in-app memory report.
2. **Reverts.** Routing EG-1 back to `PORTABLE_CPU` turns the device test's runtime assertion red; deleting the
   cache-removal call turns the removal unit test red; removing `use_mmap` turns the sampled private-memory comparison red;
   removing the abort check turns the deadline device test red (it asserts native generation returned through the
   cooperative abort before the hard deadline, read from the runtime's own end code, because a timeout can also
   "succeed" through the hard-kill path, `PolishService.kt:315, 373-387`). Each revert is performed and recorded;
   until then every revert claim here is NOT VERIFIED.
3. **Not tested:** MNN's own kernels (vendor), OpenCL on Mali (not offered), NPU.

### 11.1 Hardware UAT

- **Subsystem:** limb (polish). The heart path is untouched, but the take runs end to end.
- **Recipe:** `device-testing.md` silent physical-phone take (FACT: silent-physical-phone-audio-injection-for-night-uat)
  on the S26 from the Play internal build, EG-1 Micro selected; Test Lab `Eg1MicroHardwareDeviceTest` on A26, A15,
  Pixel 7 (approved Test Lab project).
- **Expected observation:** the editor's own text equals the polished email (Friday only, three-item list); local
  log shows `polish_runtime=mnn-…-cpu` or `-opencl`; `:polish` peak and private memory printed.
- **Restore:** model choice back to the founder's prior selection; no data wiped.

### 11.2 Other obligations

| Test | Class | Proves | Revert |
|---|---|---|---|
| Full 4,352-case exam, MNN hqq64 vs Q4_K_M, blind Sol (Azure, standing approval, est. under $30) | product outcome | goal 4 | n/a (measurement) |
| Test Lab speed and memory matrix, one matrix per phone, arms llama.cpp vs MNN CPU (ABAB) | product outcome | goals 2, 5 | n/a |
| `EgOneMnnRuntime` abort device test | product outcome | deadline stops generation | remove abort check |
| Cache build kill test (kill `:polish` mid-first-load, reload, compare canary) | product outcome | P3 | skip sync handling |
| `ModelManifestTest` upgrade and file set | drift guard | §3.2 | revert manifest |
| Cache removal unit test | drift guard | §3.4 | delete removal call |
| `PostHogSchemaTest`, `SentrySchemaTest`: new literal assertions that `polish_runtime` accepts `mnn-<sha>-cpu` and `mnn-<sha>-opencl` (neither test names `polish_runtime` today) | drift guard | §6 | remove members |
| Telemetry recount excluding test installs | measurement | §1 | n/a |
| Memory attribution: a sampler in the device test reads the `:polish` process's `/proc/<pid>/status` (VmHWM, RssAnon, RssFile) every 50 ms across load and generation; reports peak resident (kernel VmHWM, exact), sampled peak private (max sampled RssAnon; a sampled maximum, not an exact peak, since the kernel keeps no private high-water mark) and file-backed, separately for first load (cache build) and reused cache, for llama.cpp and MNN in the same matrix | harness contract | goal 5 | remove `use_mmap`: peak private rises above the limit |
| S1-mini unchanged: the existing S1 device and unit suites pass unmodified, and `git diff` shows no change under S1-only files | drift guard | goal 6 | n/a |
| Storage: download peak disk use, cache allocated bytes (`du`), APK and installed size before and after, clean CI build time | measurement | §3.4, P9 | n/a |
| Packaging: `llvm-readelf -d` and library names in the APK show no clash with GenieX or `ew-*` libraries; `useLegacyPackaging` still extracts; load, cache build and generate run on a 16 KB page-size emulator image (Android 15+) | product outcome | P5 | n/a |
| Privacy canary: a synthetic prompt with a unique marker through load and generate, captured logcat searched for the full marker, which must not appear. Tokenizer error branch (`tokenizer/tokenizer.cpp:565-577` returns `void` and sets no end code): its execution is proven by an explicit observation from that branch, staged first on the host build of the same MNN pin where the branch's stdout print is captured and the exact fragment is seen; the same input then runs on the phone and the exact fragment must be absent from logcat. Until demonstrated, error-path coverage is NOT VERIFIED; the import audit of the packaged `libMNN.so` fails if unreadable or if any socket/connect/getaddrinfo import exists | product outcome | P10 | a control build with `MNN_USE_LOGCAT=ON` must turn it red (performed once, recorded) |
| GPU qualification (chunk 5): first-time qualification, CPU/OpenCL identity separation, actual GPU execution, output comparison, measured speed advantage | product outcome | §3.5 | force CPU fallback: qualification must fail |

## 12. Blast radius and rollback

- Touched: `:polish` EG-1 path, new `:mnn-android`, manifest, model cards, notices. Not touched: S1-mini files,
  runtimes and budgets; `:asr`; capture; insertion; AIDL; persisted settings.
- APK grows by about 8 MB (`libMNN.so` 7.8 MB plus the shim).
- Rollback is a forward change, not a plain revert: restore the #529 descriptor and llama.cpp routing for EG-1 while
  keeping the cache-folder sweep, so the orphaned MNN cache (about 580 MB) is deleted file by file on the next load;
  phones holding MNN files then read EG-1 as not installed and re-download the GGUF. A plain revert would leave the
  cache folder orphaned.
- Merge order: #529, #530, this PR. This branch is already restacked on #530's head (`e768a8ea`), so its budgets
  and its `S1Native`/`s1_jni.cpp` copy-ahead changes are the base; this change does not touch `s1_jni.cpp`. Overlap
  with #530: `Eg1MicroHardwareDeviceTest` and `CopyAheadDeviceTest` (EG-1 arms), resolved here. If #529 or #530
  change before merge, rebase and re-run the unit suite and the S26 pass on the combined tree.

## 13. Ship criteria specific to this change

- [ ] EG-1 Micro polishes through MNN on the S26 (Play internal build) and the polished text lands in Gmail.
- [ ] Each Test Lab phone: MNN p50 and long-case time below llama.cpp on the same phone, same matrix.
- [ ] Per phone, the share of the 45 fidelity cases finishing inside the 10 s cooperative budget, MNN versus
  llama.cpp, reported in the PR; phones and lengths still over budget are named, not hidden.
- [ ] `:polish` maximum sampled RssAnon with MNN at most llama.cpp's on the same phone, same 50 ms sampling,
  first-build and reused-cache runs separately; interval and windows reported in the PR (a sampled comparison, not an
  exact peak-private guarantee).
- [ ] Full exam: no set significantly worse; serious errors not higher.
- [ ] S26: OpenCL used only after a matching canary, or chunk 5 cut and filed.

## 14. Open questions

1. Phone first-load time for the cache build on the A15 class (inside 60 s?). Measured in chunk 1.
2. Whether MNN's step-wise `generate(1)` loop costs speed versus `response()`. Measured in chunk 2.
3. CI build time with MNN from source (P9).

## 15. Related

#510, #528 (PR #529), PR #530, #531. Study artifact `enviouswispr-android/20261115-103824-eg1-gpu-spike-b3b7`.
`polish-engines.md` FACT: eg1-micro-gpu-and-engine-spike-2026-11-15 and RULE: every-model-run-reports-memory.

## Chunks (each leaves the app usable)

1. `:mnn-android` builds from the submodule; `MnnNative` load and one generate on the S26 via a debug-only test;
   proves P3, P5, P8, P9 and first-load time.
2. `EgOneMnnRuntime` with abort; runtime kinds; telemetry; unit tests.
3. Manifest switch, R2 publish, cache folder lifecycle, UI sizes, notices.
4. Device tests with memory split; Test Lab matrices; full exam.
5. OpenCL on qualified Adreno (cut if it fails).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written (in chat, 2026-11-15)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3
- [x] §4-9 answered
- [x] Lane declared
- [ ] Self-reviewed to all-clear before any reviewer saw it
