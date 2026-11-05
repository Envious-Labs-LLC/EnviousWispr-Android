# Issue #510: portable CPU sampler experiment, 2026-11-05

GitHub issue: #510. Tier: MEDIUM. Status: APPROVED by the founder on 2026-11-05. This plan covers a bounded experiment, not a production-default change.

**Lane:** Code
mixed_pr: true
Docs/dev-tooling applies to this plan. **PAR rows closed:** none. **Hardware UAT:** Y, exact paired native calls on one permitted physical Galaxy A26; emulator first for package, native controls and fixture discovery. No personal S26.

**User Rubric: N/A:** internal experiment; the ordinary production entry remains on its existing sampler. A later winning-default change requires its own reviewed user-impact sign-off and full pipeline acceptance. No app setting or public speed promise is added.

## 0. TL;DR

Test whether the pinned library's explicit greedy selector avoids unnecessary portable-CPU sampling work without changing intended words. Add a debug-only finite experiment entry around the same native generation loop, keep the normal JNI entry on baseline, and compare both arms on one phone. Reject a mismatch or insignificant result. Release uploads stay held for the agreed enhancement batch.

## 1. Problem

Physical debug Galaxy A26/API36 characterization at source f6755e7e produced six successful calls. The two 99-reference-word fixtures spent9274/8229ms in generation, versus historical load1294/1499ms. No timeout reproduced. These calls are not release/thermal-controlled performance acceptance or proof of all customer timeout causes. Receipts: artifact:enviouswispr-android/20261105-141511-mobile-enhancements-batch-6205/soak-physical-characterization.json.

Current portable JNI sets temperature0 but still computes a full distribution after retaining the maximum. The saved sampler problem consult verifies this work and its known upstream context. Gain is unmeasured and may be too small to justify a default change.

## 2. Goals and non-goals

### 2.1 Goals
- Compare baseline and explicit greedy using identical model, native libraries, prompt/control, context, threads, token caps and generation loop on one device.
- Preserve healthy-input complete bytes, selected token sequence and ending/accounting across paired arms and repetitions.
- Exercise actual native finite/tied/masked-logit controls; retain model suppression and common acceptance/history.
- Produce a bounded screening/confirmation decision with every rejected or incomplete row retained.

### 2.2 Non-goals
No production-default flip, new model/backend, prompt change, output truncation, deadline/residency change, cloud switch, speech/capture/editor change, broad-device or customer-root-cause claim. No release upload or paid testing. Prompt-lookup speculation is deferred.

## 2.5 Grounding brief

Producer/owner/consumer: PolishService.polishWithS1 at705-720 builds the production prompt/cap, then S1PortableRuntime.generate at57-78 resets abort, arms its cooperative timer and calls S1Native.generate. JNI at298-305 serializes through g_mutex and calls generate_text at148-234. The result and lastEnd return through the unchanged runtime/service/outcome path. The proposed experiment calls the same loop directly; it does not claim owner-watchdog or editor acceptance.

Reproducible source searches:
- rg -n 'S1Native\.generate|generate_text\(' app/src llama-android/src returned one production Kotlin call, one native helper and its one JNI caller.
- JNI187-193: temp0,top_k0,top_p1,min_p0,repeat1,common_sampler_init.
- Pinned common/sampling.cpp325-337 installs model suppression separately. At340-400 configured samplers then final distribution are installed. common/common.h224-271 supplies defaults/order.
- Pinned llama-sampler.cpp265-285 temperature uses strict-greater first-maximum masking;1053-1059 greedy uses the same comparison.1150-1214 distribution scans/normalizes/draws. sampling.h exposes common_sampler_get; library chain APIs are declared in include/llama.h.

The existing native mutex, model/context, prompt builder, abort flag and common sampler own this concern. No new runtime/manager is proposed. Pinned llama commit is ca3d5a3e10d53f7ea672cb9b6178faca3e2807bc. No third-party source edit.

Prior work: #465 slow-load hypothesis remained unverified after120loads; #433 rejected its configuration candidates; #453 already changed real-length speech processing. PR511 added diagnostics and PR512 reports them in the sustained fixture. This is a new narrow portable-sampler experiment, not a claim to have passed the old433 campaign's20percent target. Local catalog ai-polish/s1-mini-polish establishes the optional polish/fallback product contract; no macOS behavior is being reimplemented.

Problem-only consult: artifact:enviouswispr-android/20261105-150355-mobile-recovery-preview-ea8c/sampler-problem-consult.out. It confirms meaningful work, preservation of model suppression, EOS/abort accounting and no unconditional equivalence for invalid logits. Even finite baseline output is not mathematically unconditional: distribution drawzero can select a zero-probability first candidate. The experiment requires compatibility on its observed healthy corpus, not an impossible proof of every legacy RNG state.

Boundary: only the portable native bridge is in play. GenieX does not use this JNI generator. Existing production JNI signatures and normal dispatch remain intact. Debug-only exports/classes must be absent in release. Model/context access stays under the existing mutex; abort remains the one lock-free exception.

## 3. Design

Add SamplerMode (proposed), with Baseline and LibraryGreedy only. Factor current sampler construction without changing baseline values. For LibraryGreedy, leave the configured optional stages empty, initialize the common sampler (preserving its model suppression prefix), verify the expected final distribution sampler, remove/free that final sampler using library ownership APIs, and append llama_sampler_init_greedy (external). Continue common_sampler_sample/accept (external) and the current generation loop. Do not rebuild suppression, grammar or history manually. Unexpected chain shape is an experiment failure, never an implicit alternative.

The ordinary S1Native.generate entry always selects Baseline. A new debug-source S1SamplingExperiment (proposed) exposes finite-arm generation and shaped observations under the same mutex. The existing Gradle configuration forces native CMAKE_BUILD_TYPE=Release even for debug APKs (llama-android/build.gradle.kts21), so a CMake CONFIG:Debug/NDEBUG assumption is wrong. Set a fixed experiment compile definition from the existing Android debug build type only, default OFF and explicitly OFF for Android release; leave native optimization settings unchanged. No user/environment selector or mutable production default. Release Kotlin API and exported symbols must exclude it.

The candidate checks that its selected logit is finite before accepting it; invalid selected logits return an existing-style native ERROR with END_NONE, not fabricated text. Negative-infinity masks are valid. Native invalid-distribution controls do not assert equality to legacy assertion/fallback behavior. Empty-array controls reject before invoking library selection. Baseline generation remains unchanged.

Capture experiment-only native elapsed/sampling time, selected token IDs, effective cap/ending and actual chain identity. Both arms pay the same observation settings. Observation cost and transfer to ordinary uninstrumented generation are UNMEASURED unless separately established; equal instrumentation alone is not proof of transfer. Full synthetic outputs go only into the test's narrow owned result file, never logcat or telemetry. Console reports shape/hashes only. Test APKs have empty telemetry configuration.

Alternative: top-k1 alone is smaller and an upstream-known configuration, but partial_sort tie behavior introduces a compiler-specific equivalence premise. Explicit library greedy has the same first-max loop as temperature0 and avoids that premise. Manual argmax, new adaptive policy and prompt lookup are excluded.

## 3b. Ownership

Native construction stays beside the existing generator; debug API stays in the library's debug source set; study orchestration is instrumentation only. No app/process coordinator or persistent model owner is introduced. The direct-native test process is a benchmark fixture, not a change that loads models in the production main process.

## 4. Contract deltas

Normal production JNI/API/outcomes remain unchanged. Debug experiment results identify actual arm/chain and retain missing/error states. Candidate selected-logit refusal uses existing native failure handling. No new telemetry or database schema.

## 5. State/lifecycle audit

Enumerated populations: two sampler modes, normal/debug JNI entry points, Debug/Release builds, native end states NONE/EOS/LENGTH/ABORTED, valid/invalid logits, package/model availability, complete/incomplete study rows. Existing mutex serializes all generation and observation; no concurrent model loads. Abort reset precedes timer arm, timer closes in finally, and failure never becomes a completed timing sample. Model load/unload and context clearing remain identical. No priority/lifecycle change to production services.

## 6. Consumers

| Delta | Consumer | Required behavior | Verification |
| --- | --- | --- | --- |
| Factory mode | normal JNI | Baseline only | release export/DEX checks and baseline source/call test |
| Debug generation | study driver | finite arm, same loop and exact metadata | native controls and model calls |
| Shaped study results | analyst | no missing/hot/failed row counted as winner | fixed schema, whole-row checks |
| Candidate error | existing runtime | no invented output or new deadline | END_NONE/error and existing fallback tests |

## 7. Failure by caller

| Failure | Caller | User/test outcome | Persistence/retry |
| --- | --- | --- | --- |
| Bad mode/chain/logits | debug/native study | explicit failed control, no performance claim | retain row, no automatic retry |
| Missing/mismatched model | fixture | fail staging before native call | production adoption checks, no checksum bypass |
| Abort/native failure | model study | fail pair, retain reason and counts | no accepted latency/quality row |
| Environment changes | study | NOT QUALIFIED environment row | retain all data, do not substitute another trial |
| Partial/empty result set | analysis | no aggregate winner | preserve raw output, stop stage |
| Release export leaked | package check | validation fails before distribution | fix/review, no upload |

## 8. Signals

Arm is the actual installed chain, not requested label. EOS/length/abort/none retain existing meaning and token accounting. A missing measurement is absent, never zero. Output/token hashes establish compatibility, not semantic correctness. Thermal/charging records are part of acceptance, not descriptive footnotes. Native timing excludes load, and is not stop-to-editor latency.

## 9. Fallback audit

Normal production behavior is baseline throughout this plan. Failed experiment/native selected-logit validation produces no candidate winner; current deterministic text fallback is unchanged. An unverified/slow/mismatching candidate is rejected, not enabled. No alternative sampler/model is selected automatically.

## 10. Files

- llama-android/src/main/cpp/s1_jni.cpp: preserve baseline; finite factory/test instrumentation behind Debug export guard.
- llama-android/build.gradle.kts: fixed per-Android-build-type experiment argument; retain native Release optimization in both arms.
- llama-android/src/main/cpp/CMakeLists.txt: experiment definition default OFF, enabled only by the fixed debug argument.
- llama-android/src/debug/java/com/envi/wispr/llama/S1SamplingExperiment.kt (proposed): experiment JNI only.
- app/src/androidTest/java/com/envi/wispr/polish/S1SamplerStudyDeviceTest.kt (proposed): same-APK counterbalanced driver, native controls, abort/accounting.
- app/src/androidTest/assets/sampler-study-v1.json (proposed): frozen synthetic texts and independent critical-intent expectations, sourced from prior sealed fixture manifest plus explicit quality controls.
- This tracked plan; raw data/reviews stay in the artifact library.

## 11. Validation and preregistered study

New native controls/study plumbing are Harness/Observability Contracts. Real-model no-new-omission compatibility is bounded Product Outcome evidence, not capture/editor acceptance. Revert the candidate installation to baseline: chain/arm control must fail, so two identical arms cannot manufacture a speed comparison. Remove equality/complete-row checks in controlled copies and require mismatch/missing-row fixtures to fail. Wait on actual completion signals with failure deadlines; no sleep-then-success assertion.

Native controls cover unique finite maxima at different positions, first tied maximum including signed zero, masked entries, the real model's suppression metadata (or explicit absence), empty input and candidate nonfinite winner refusal. Test the actual shared construction/application code, not a copied algorithm. EOG/cap/abort accounting must remain literal and unchanged. For both arms, force an actual model-suppressed token above a finite allowed alternative and require suppression; if metadata is empty, record that absence and use a controlled suppression-prefix case separately. Observe actual common history for controlled printable-token sequences and the final accepted token. The shared generation-loop source audit must retain its single accept call before the EOG test; do not invent a vendor history API or claim duplicate-EOG proof from an opaque getter.

Exercise normal baseline, debug baseline and debug greedy for unloaded error, EOS, effective cap, abort before generation, during prompt decode, between generation steps and during token decode. A debug observer must prove the intended actual library phase; parking before JNI is not an in-flight native control. Assert literal end/count/error data, successful next call, unload/reload, and timer cancellation so a completed call cannot abort its successor. Do not fabricate an abort phase from a wall-clock guess.

Before execution, freeze a correctness-only corpus covering names/numbers/negation, list/prose formatting and Unicode; add default controls plus one non-default choice on each control axis, without a Cartesian expansion. Include context/cap boundaries. These cases run before timing in the same selected test and are not added to the three frozen timing fixtures. Explicitly record language/content populations not qualified.

Local result-copy mutations: change a critical name, number or negation identically in both arms while preserving equality; the independent intent oracle must reject it. Change only end/count metadata; compatibility must reject it. Delete a row; completeness must reject it. No extra physical executions for these controls.

Same production S1 model, prompt/control builders, context2048/threads4/batch512, cap builder and native libs in both arms. Three frozen source texts contain6/39/99 reference words; complete output and token sequence must agree across arms and repetitions. Independently audit names, numbers, intent and formatting against predeclared accepted forms; baseline agreement alone is not accuracy. No corpus/reference changes after viewing candidate outcomes.

Pilot: one A26/API36, one execution capped5m, zero retries/shards, same APK. Three paired blocks AB,BA,AB; both arms receive short/medium/long in fixed order,18 measured calls plus6 retained excluded warmups (one per arm/load). One arm/load per block processes the three cases in that fixed order; load/context boundaries are identical. Warmup output/end are checked but its timing excluded. Report initialization separately. Direct-native per-call failure bound30s is experimental only; production10s budget is untouched. Same APK/target/fixtures first run locally; emulator speed is not phone evidence.

Environment preregistration: start thermal status at most1, keep charging state unchanged, reject a whole matched pair if thermal exceeds1 or its battery-temperature span exceeds2C. Record per-arm before/after state. No unrecorded cooling or fastest-run selection. A hot/invalid/incomplete stage does not qualify; no automatic rerun.

Pilot screening threshold: exact output/token/end compatibility and zero independent critical-intent regression, then median paired total-native generation reduction at least5percent AND100ms in both39/99-word classes, with all three paired differences nonnegative. Three pairs are screening only, not a confidence claim. If it misses, reject this candidate and retain evidence.

Only a passing pilot permits one preregistered confirmation execution, capped10m on one physical A26 with both arms,10 matched pairs per39/99-word class plus3 short-case pairs, with this complete frozen schedule: block1 AB M,L,S;2 BA L,M;3 AB M,L;4 BA L,M;5 AB M,L,S;6 BA L,M;7 AB M,L;8 BA L,M;9 AB M,L;10 BA L,M,S. M/L/S are39/99/6-word fixtures. Each arm loads once per block and has one retained excluded short warmup,20 warmups plus46 measured calls in confirmation. Same frozen inputs/settings/oracles/environment rules. Require the same materiality thresholds, a paired95percent bootstrap lower bound above zero (50,000 paired resamples, seed51020261105, percentile2.5 lower endpoint), and no accepted short-case regression above5percent or50ms. For each fixture, reductions are (baselineMs-candidateMs)/baselineMs per matched pair, with median fractional reduction and median absolute milliseconds reported separately. Resample whole matched pairs and compute the median fractional reduction, never independent arms. Report raw pair count, estimator and every excluded row; no claim of independence from repeated calls on one device.

Before every submission, recheck fully paginated project usage and free headroom. Total proposed physical exposure: at most15minutes for pilot+conditional confirmation, each individually bounded, no paid overflow. If insufficient free headroom, do not submit. No additional device sweep or AWS/Azure provisioning.

### 11.1 Hardware scope

This plan qualifies an engine-level experiment only. It cannot establish whole-app speed, microphone/editor delivery, battery impact or all phones. A winning production-default proposal must separately validate real-service fallback/restart, ordinary speech-to-editor/back-to-back delivery and release-native behavior before merging that default. No release upload follows this study.

## 12. Blast radius and rollback

Portable bridge/debug experiment/test assets/plan only. No third-party/GenieX/model/runtime routing/ASR/capture/insertion/provider/telemetry/database changes. Revert the study commits to remove its debug export and helper; ordinary entry stays baseline throughout. Preserve all research evidence and any rejected candidate data.

## 13. Specific completion criteria

- Actual two-arm native controls cannot pass with both arms accidentally identical.
- Exact model/package/data identities and every requested row accounted for; no skipped/empty/hot run described as a winner.
- Candidate rejected or a bounded confirmation result recorded; no production-default or speed claim silently enabled.
- All release uploads remain held.

## 14. Questions

Gain and exact real-model compatibility are NOT RUN, not assumptions. A candidate default is outside this plan and requires explicit concrete review/sign-off after evidence. The normal Gate2 sign-off on this concrete reviewed experiment is required before native edits; broad continuation authorizes its preparation. No founder engineering choice is requested. Code/package/local gates still precede any physical execution.

## 15. Related

#510, #465, prior #433 research (not its claimed acceptance), PR511/512. No issue closure or PAR closure from this study.

## Review record

Coverage: one pass, sampler-coverage.out in the current artifact run. All five coverage areas addressed: lifecycle phase/turnover, functional suppression/history, content/settings/bounds, independent-oracle mutations, and complete schedule/observation caveat. History wording is modified: observable actual history plus the unchanged single-accept/EOG source boundary, rather than claiming an unexposed exact history count. Independent source read also corrected the native-Release-for-debug build assumption. Grounded review sampler-grounded-r1.out returned PROCEED-AS-PLANNED with no blockers. The founder approved this exact bounded experiment at Gate2 on 2026-11-05; code/package/local gates remain before execution. Production-default change remains outside scope.
