# Issue #374 — Mac-parity speech engine: SmoothQuant Parakeet on ONNX Runtime with the macOS batch recipe — 2026-09-27

GitHub issue: `#374` (evaluation: `#379`). Tier: LARGE (both engines' runtime: ASR and VAD move off the sherpa-onnx AAR). Status: APPROVED (founder, Gate 2, 2026-09-27).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The macOS behaviour this matches is the batch Parakeet path (FluidAudio fork `b29591ad` (external), `AsrManager(ASRConfig(melChunkContext: true))`), evidenced by the #379 comments, not by a catalog row.

**Hardware UAT:** Y. The founder dictates a long, fast take on the S26 (the 12:05 PM take shape, 25 to 60 s, trailing clause spoken quickly after a full stop), stops, and the final clause lands in the editor. Silence, a noise burst and background noise produce no words.

## Preface — User Rubric

1. **Who, in this moment.** Diana Foster, senior PM, walking between meetings, dictating a 40-second Slack reply with the side button; thirty seconds from now she is in the next meeting and will not reread it.
2. **Why.** "When I talk for a while, the end of what I said just disappears."
3. **How invoked.** Side-button double press, recorder floats over Gboard, stop, words land. Unchanged.
4. **Apps.** Slack, Gmail, Messages, Notion, Google Docs. A dropped last sentence is worst in email and Slack, where the last clause is usually the ask.
5. **Natural input.** "Okay so the vendor call moved to Thursday, can you ping legal before then, and would it make sense to loop in Priya?" / "Running five late, start without me." / "Can you check if the build is green and would it correctly apply the right choice?" / a 45-second rambling status update / "Yeah."
6. **Success.** Nothing to notice: every word she said is there, as on her Mac.
7. **Wrong-not-broken.** The text reads fine but her final question is gone, so the recipient never answers it. She stops trusting long dictations and types instead.
8. **Power-user hack.** Splitting a long thought into several short takes, or repeating the last sentence as its own take (what the founder does today).
9. **Control ladder.** None needed: transcription accuracy is not a preference. The later compute-target picker (Auto / CPU / GPU / NPU, founder 2026-09-27) is out of scope here.

### Cross-persona check
- **Priya, Aaron:** want the technical words right and sub-second waits. The new engine is as fast as today on 27 s and 60 s takes (§1).
- **Marcus:** wants every word kept. This change is mostly about deletions.
- **Elena:** on-device only. Unchanged; no network in the decode.
- **Meera, Frank:** notice nothing except fewer missing words.

No persona prefers the old behaviour. The tension is memory: a bigger model would be more accurate still, but the founder refused extra RAM. It is resolved in §3 by a model that is smaller at peak than today's.

---

## 0. TL;DR

Android drops the end of a dictation because the stock int8 Parakeet encoder quantisation mis-decides the end of speech, and a whole-take decode compounds it. The fix:
- **Model:** replace the sherpa-onnx recogniser with our own Parakeet TDT decode on ONNX Runtime 1.30.
- **Recipe:** run the model through a Kotlin port of the macOS FluidAudio batch recipe (15 s windows, fresh decoder per window, token-aligned merge, seam-gap repair).
- **Weights:** the SmoothQuant int8 encoder (`Olicorne/parakeet-tdt-0.6b-v3-smoothquant-onnx` @ `9d104194`, 650 MB, same size as today).

**Why the VAD moves too:** sherpa's bundled ONNX Runtime 1.17.1 cannot load that model (`MatMulNBits` (external) 8-bit, measured on the S26). So Silero VAD moves to the same ORT 1.30, and the sherpa-onnx AAR is removed.

**Evidence (#379):**

| Measure | Today | New |
|---|---|---|
| WER on natural speech | 12.26% | 6.28% (macOS app: 6.03%) |
| Stress endings lost | 53 of 75 | 0 of 75 |
| S26 known-failing endings kept | 5 of 9 | 9 of 9 |
| S26 peak RSS, 27 s take | 1.87 GB | 1.13 GB |

Proof at the end is the S26 binder path: known endings, noise fixtures, 600 s completion, latency, RAM.

## 1. Problem

- **Founder's take:** the founder's 27.32 s take lost "would it correctly apply the right choice" on the phone. The same loss reproduces through the real `:asr` binder on the emulator.
- **Evaluation (#379 comment 5860338189):**
  - **Fragile at many lengths:** the whole-take sherpa decode is length-fragile. It loses the clause on 53 of 75 cuts of 45 to 60 s (fixed ending) and 2 of 184 cuts of 20 to 27 s.
  - **Exact span:** on the exact failing 14.84 s span, macOS CoreML, fp32 ONNX and both SmoothQuant int8 exports keep the clause. The stock int8 encoder drops it, even with an fp32 decoder/joint.
  - **Decoder trace:** a 0.69-logit near-miss blank at the first frame of the clause, then maximum-duration skips over it.
- **Accuracy gap:** 12.26% WER against the macOS app's 6.03% on 37 human-captioned clips.
- **Long takes:** a whole-take decode fails outright from about 400 s (encoder self-attention broadcast error, `tech-stack.md` speech bench).

## 2. Goals & non-goals

### 2.1 Goals
1. **Endings:** zero lost endings on the #379 stress set (75 cuts, 39 start offsets) through the shipped engine on the S26. The four known-failing lengths keep the clause through the real binder.
2. **Accuracy:** on the 37-clip set, run on the Mac against the Kotlin engine code, WER is at most 6.5% and within 0.3 points of the Python reference port (`bench/long/macbatch2.py` + SmoothQuant, 6.28%).
3. **Noise:** silence, sudden burst and background noise fixtures produce "".
4. **Memory:** peak RSS of `:asr` during a 27 s and a 60 s take is at or below today's on the S26, measured on the same build type. Also reported, before and after: the combined peak of `:asr`, `:vad` and `:polish` through ASR and then S1-mini polish (`PipelineBindings.kt:99-105` binds both); warm and cold post-stop p95; battery for a fixed set of takes. ORT gives each session its own thread pool and arena, so ORT session teardown at `onDestroy` is logged.
5. **Speed:** a 27 s take decodes in at most 1.2 s and a 60 s take in at most 3.2 s on the S26 (today: 1.17 / 2.94 s).
6. **Long takes:** a 600 s take completes with correct text and no process death. The post-stop time is measured and reported (§14, question 1).
7. **Detector parity:** Silero VAD on ORT 1.30 produces the same per-window probabilities as today, within 1e-4, on a fixed PCM fixture, so auto-stop behaviour is unchanged.
8. **Clean removal:** the sherpa-onnx AAR, its CI fetch and its notices are removed. The assembled APK and the signed AAB each carry exactly one `libonnxruntime.so` and one `libonnxruntime4j_jni.so`. For both ORT libraries, the ELF LOAD alignment is inspected in the assembled APK and in the signed AAB. The existing release code checks ELF alignment in the AAB only (`scripts/release/publish.py:38`), and onnxruntime issue 24902 found the JNI library misaligned in an earlier release. `zipalign -c -P 16 -v 4` (external) must pass on the APK, and `PAGE_ALIGNMENT_16K` (external) must be confirmed in the bundletool config. APK and AAB sizes are compared with the baseline. Loading is proven on the S26, a 4 KB-page device; a 16 KB-page emulator image proves 16 KB loading.

### 2.2 Non-goals
- Releasing the model after transcription (#73). Warm-at-connect stays.
- Live or streaming transcription (founder: never on Android).
- The compute-target picker (GPU/NPU).
- Languages beyond what Parakeet v3 already does (#36).
- Polish, insertion, History, the AIDL surface and the session owner.
- Changing auto-stop thresholds.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
Grounding sweep (read-only agent, 2026-09-27; `grep -rnE -i "sherpa|k2fsa|loadLibrary|OfflineRecognizer|VadModelConfig|SileroVadModelConfig|onnxruntime"` gives 53 hits):
- **Capture.** `:audio` writes the take to a PCM16 16 kHz file and hands its path to `:asr` via `IAsrService.transcribeFileForTake(path, takeId, cb)` (`app/src/main/aidl/com/envi/wispr/asr/IAsrService.aidl:20`).
- **`AsrService`** (`process=":asr"`, `AndroidManifest.xml:98-101`):
  - checks the file (`AsrService.kt:145,155`), reads it (:167) and converts it with `PcmAudio.toFloatSamples` (:206);
  - decodes on the single `RecognizerOwner` executor under `AsrWatchdog`/`AsrBounds`: `createStream`/`acceptWaveform`/`decode`/`getResult` (:211-218);
  - delivers trimmed raw text by `IAsrCallback.onResult(String)` (:238). No timestamps cross the binder.
  - Failures are `AsrFailureReason` codes (`AsrFailureReason.kt:13`) via `onFailure(int, String)`.
- **Model load.** `onCreate` → `owner.load(initRecognizer)` (:249), then `initRecognizer` (:262-301). It gates on `ModelStorage.isReady` and reads `encoder/decoder/joiner.int8.onnx`, `tokens.txt` with `numThreads=4`, `greedy_search`.
- **VAD.** `SilenceVadService` (`process=":vad"`, manifest :93-96) receives `processBlock(token, pcm16)` (`ISilenceVadService.aidl:26`). `SileroVadSession` runs sherpa `Vad.compute()` on 8 × 512-sample windows per 4096-sample block (`SileroVadSession.kt:37-51`), and the probabilities feed our own `SilenceStopDetector` (`:93-118`). The model is the APK asset `silero_vad.onnx` (643,854 B, sha `9e2449e1…`, `SileroVadSession.kt:74-75`) with inputs `x[1,512]`, `h[2,1,64]`, `c[2,1,64]` and outputs `prob`, `new_h`, `new_c` (onnxruntime session inspection, pasted in #379 work).
- **Model delivery.** `ModelManifest.parakeet` (`ModelManifest.kt:50-55`: 4 files, revision `2bda32ec…`, host `models.enviouslabs.co/parakeet-onnx/<rev>/`, Hugging Face fallback) → `ModelDelivery` → `ModelStorage` (`noBackupFilesDir/models/<id>`, verified receipt).
  - Consumers: `ModelWorkViewModel.kt:63,65,85`, `ReadinessViewModel.kt:105`, `OnboardingViewModel.kt:103`, `TranscriptionScreen.kt:64-81`, `OnboardingScreen.kt:121`, `ModelBootstrapApplication.kt:109`.

### 2. Existing authority
- **Decode owner.** `AsrService` + `RecognizerOwner` + `AsrWatchdog` + `AsrBounds`. The new decode lives inside them; no new owner.
- **Model owner.** `ModelManifest`/`ModelDelivery`/`ModelStorage`. The new model is a manifest entry.
- **VAD owner.** `SilenceVadService` + `SileroVadSession` + `SilenceStopDetector`; only the `compute` source changes.
- **Other ONNX Runtime users.** No Kotlin/Java ORT dependency exists (0 hits for `com.microsoft.onnxruntime|OrtSession|OrtEnvironment`). The geniex AAR has no ORT. The only ORT today is 1.17.1 inside the sherpa AAR, loaded in `:asr` and `:vad`.

### 3. Prior attempts and live direction
- #379, Codex rounds r1 to r18. Decisions made there:
  - no live transcription on Android;
  - memory must not grow (founder, 2026-09-27);
  - Finalist A over B (r18);
  - ORT 1.17.1 fails the model, so VAD moves to 1.30 and the AAR goes (r18 fallback path);
  - unload is deferred to #73.
- `docs/feature-requests/issue-72-2026-09-01-model-residency-decision.md` §11: warm-at-connect stays.
- macOS decision: the batch recipe is the default (`EnviousWispr/.claude/knowledge/pipeline-mechanics.md` FACT parakeet-pipeline). Streaming is rejected: the FluidAudio CLI `--streaming` repeats seam phrases.

### 4. Boundaries
- **Processes:** `:asr` and `:vad` stay separate processes with their own watchdogs. Model load stays in `:asr` `onCreate` (warm-at-connect). A watchdog kill of `:asr` still ends the take as today.
- **Native library:** one `libonnxruntime.so` (ORT 1.30, from the Maven AAR) in both processes. A second copy would collide on the library name, so the sherpa AAR must leave in the same change.
- **Model files:** the new model sits in a new storage id (`parakeet-sq`, proposed). Delivery cleanup is scoped to the id it is given (`ModelDelivery.kt:205-249`), so it will NOT remove the old `parakeet` folder by itself. The chunk 2 build removes the old `parakeet` folder only after its first successful binder take on the new model (coverage r1, gap 1; grounded r1, finding 2). Until then the old files stay. §14, question 2.
- **Load once (unchanged).** `RecognizerOwner` loads once (`RecognizerOwner.kt:41-45`), only from `AsrService.onCreate` (`AsrService.kt:245-249`), with requests queued behind that load on the single bounded worker (`RecognizerOwner.kt:18,53`). Request-triggered reload was DELETED by the consequence pre-committed in grounded r4: reload drew findings in r1, r2, r3 and r4, and the r4 hole was an unbounded synchronous failure callback outside the worker. So an `:asr` that started without the model stays unready until that process next starts after its idle end, exactly as today. The update path does not depend on reload: chunk 2 is installed only after chunk 1's verified `parakeetSq` receipt is recorded on the S26, so the new build's first `onCreate` load finds the model. Test: the first take after the chunk 2 build binds decodes on the S26 (hardware UAT).

### 5. High-risk premises, with evidence
- **ORT 1.17.1 cannot load the model.** S26 run: `MatMulNBits (external) … nbits_ == 4 was false. Only 4b quantization is supported` (`bench/ort117/tdt_ort117`, 2026-09-27).
- **ORT 1.30 on arm64 runs it and keeps endings.** S26 static binary `tdt_ort` (external) with the ORT 1.30.0 AAR libraries: 9 of 9 endings kept, 1130 MB peak on 27 s.
- **The Python recipe port matches the macOS app on endings.** Real FluidAudio CLI with the app's layout: 0 of 76 lost. Port with SmoothQuant: 0 of 75, and 0 of 39 start offsets.
- **Preprocessor identity matters.** The evaluated 6.28% used onnx-asr 0.12's `nemo128.onnx` (sha `5b4a84c5…`, within 3.4e-5 of NeMo's NumPy features). The Olicorne repo ships the older `nemo128.onnx` (sha `a9fde148…`, max feature difference 2.73). This plan ships `5b4a84c5…` (onnx-asr, MIT) and keeps `a9fde148…` out.
- **ORT Android 1.30.0 is the latest Maven release.** `repo1.maven.org …/onnxruntime-android/maven-metadata.xml`: `<release>1.30.0`. Its 16 KB alignment is NOT VERIFIED; checked in chunk 2.

## 3. Design

**Engine.** `ParakeetEngine` (proposed), Kotlin, in `asr/`, on the ORT Java API (`ai.onnxruntime.OrtSession` (external)):
- Three sessions: `nemo128.onnx` (preprocessor), `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`. Intra-op threads 4, graph optimisation ALL, CPU provider.
- `transcribe(samples: FloatArray): String` runs the macOS batch recipe, ported from `bench/long/macbatch2.py` (itself line-cited against FluidAudio fork `b29591ad` `ChunkProcessor.swift` and `TdtDecoderV3.swift`):
  - takes of 240,000 samples or fewer are one window;
  - longer takes use fixed-stride chunks of 238,080 samples with 32,000 samples of overlap and 1,280 samples of mel context for chunks after the first;
  - the last chunk is backfilled with real audio up to the speech end;
  - each window gets a fresh decoder state and 15 s zero padding with the real length passed;
  - greedy TDT uses the macOS duration rules, a non-blank emitted only inside the real frames, a 150-token cap, and punctuation-only finalisation on the last window;
  - `mergeChunks` (external) does contiguous, then LCS, then midpoint matching on splice-safe ids with case variants, then monotonic clamp, then seam-duplicate collapse;
  - seam-gap repair runs at most three passes of 32 probes each.
- **Split for testability:** `TdtRecipe` (proposed): pure Kotlin windows, merge, repair and text, is separate from `TdtModel` (proposed): the ORT calls behind an interface `TdtRunner` (proposed). Unit tests drive the recipe with a fake runner.

**Weights.** Olicorne SmoothQuant int8 at revision `9d104194420cfe48c3374385bb42b42a788b9225`:
- `encoder-model.int8.onnx` 649,524,002 B, sha `019f798a…`;
- `decoder_joint-model.int8.onnx` 18,203,490 B, sha `63a6cd89…`;
- `vocab.txt` 93,939 B, sha `d5854467…`;
- `nemo128.onnx` from onnx-asr 0.12.0, 139,764 B, sha `5b4a84c5…`, shipped as an APK asset like `silero_vad.onnx` (build deviation, 2026-09-27). Every public model repo carries the older `a9fde148…` instead, and `validateModelSource` admits only our host and the Hugging Face resolve path, so there is no admissible fallback for it. As an asset it cannot drift, and it adds 140 KB to the APK.
- Fallbacks (build deviation): Hugging Face serves the two model files from an `int8/` folder, a six-segment path `validateModelSource` rejects, so only `vocab.txt` carries a Hugging Face fallback; the encoder and decoder_joint come from our host only (uploaded 2026-09-27, public ranged GETs 206).

No blank penalty: the SmoothQuant port scores 6.28% at 0, against 6.80% at 1.0, and at 1.0 it writes "It was" on background noise.

**VAD.** `SileroVadSession` keeps its API and its `SilenceStopDetector`. `compute` becomes an ORT session on the v4 contract pinned by `SileroVadSessionTest.kt:36` (inputs `x,h,c`; outputs `prob,new_h,new_c`):
- both returned `new_h` and `new_c` are carried to the next 512-sample window, in order;
- both are zeroed at every take start (`reset`);
- every `OrtSession.Result` and input tensor is closed.

It keeps the asset pin, the threshold and the window. The parity test compares each probability, and the final auto-stop decision, with sherpa on the same PCM. The sherpa outputs are recorded as a fixture before the AAR is removed.

**Alternatives rejected** (evidence in #379):
- **sherpa + SmoothQuant 882 MB + blank penalty (B):** 2.3 GB peak RSS, 6.59% WER.
- **sherpa with the stock model + blank penalty:** 6.65% WER with the recipe, but 8.42% whole-take, and endings still depend on bp.
- **fp32 / fp16:** 2.4 GB and 1.2 GB. The ORT CPU provider does not support fp16 operations.
- **transcribe.cpp:** 4 s per 27 s take.
- **ExecuTorch and LiteRT:** a 50 s export cap, an open XNNPACK bug, 5 s windows.
- **Streaming Mac recipe:** repeats seam phrases.
- **C++ JNI harness instead of Kotlin:** measured equivalent on the phone, but it adds a CMake target and JNI surface. The per-step Java-to-native cost (about 700 decoder_joint calls per 27 s) is the one risk, gated by goal 5.

**Consolidation:** the dominant root is the Parakeet decode, with one owner, `AsrService` via `RecognizerOwner`. The consolidation sites are the two ONNX Runtime copies: sherpa's bundled 1.17.1, loaded in `:asr` and `:vad`, becomes one ORT 1.30 dependency shared by `ParakeetEngine` (proposed) and `SileroVadSession`. Nothing else is merged.

## 3b. Ownership justification
This will live in `:asr` inside `AsrService`'s existing `RecognizerOwner` because that is the one owner of decode, bounds and failure reporting. The alternative, a new native service, would duplicate the watchdog and the binder for no isolation gain. VAD stays in `SileroVadSession` because only its probability source changes.

## 4. Contract deltas
- **`IAsrService` / `IAsrCallback`:** unchanged. Still raw text, the same failure codes and the same bounds.
- **`AsrFailureReason`:** unchanged set. An ORT load failure maps to `MODEL_NOT_LOADED`, as a sherpa load failure does today; an ORT run failure maps to `DECODE_FAILED`.
- **`ModelManifest.parakeet` → `parakeetSq` (proposed):** in chunk 2 the old descriptor is deleted, and every consumer reads `parakeetSq`: id `parakeet-sq`, engine `onnxruntime`, the Olicorne revision, four onnx-asr-layout files, host prefix `parakeet-sq`, and CC BY 4.0 notices naming NVIDIA and Olicorne. The display name "Parakeet" and the licence string are kept, so on-screen text is unchanged.
- **`SileroVadSession.compute`:** the same probability semantics, and the same `open` null-on-failure contract.

## 5. End-to-end state and lifecycle audit
| Population | Members, next-take result, test |
|---|---|
| New-model delivery states (`DownloadState`, from `ModelDeliveryWorker`) | **Absent or queued:** old build dictates (chunk 1); a new-build `:asr` process started before verification returns `MODEL_NOT_LOADED` until that process ends. **Running or paused:** same as absent (`ModelStorage.isReady` false until the receipt). **Cancelled or failed:** same as absent; the old folder is kept. **Partially staged:** never visible to `isReady`, because admission is atomic after verification (`model-delivery.md:41-44`); test by an existing delivery test. **Verified:** the next NEW `:asr` process loads the model. A next-take success is guaranteed for the S26 update only because chunk 1 verifies the receipt before chunk 2 is installed (test: first-take-after-install UAT). |
| `:asr` states when the receipt lands | Unchanged from today: **not bound:** the next bind's `onCreate` load finds the model. **Bound, loaded:** unaffected. **Bound, started without the model:** stays unready (`MODEL_NOT_LOADED`) until the process ends and restarts. The S26 cannot reach that state in chunk 2, because the receipt precedes the install. Test: the first-take-after-install UAT. |
| ORT initialisation | **Preprocessor, encoder or decoder_joint session fails partway:** every created session is closed, the recogniser stays absent, and the take reports `MODEL_NOT_LOADED` until that `:asr` process ends; the next new process retries the load. Test: a fake session factory throwing on the second session asserts the first is closed. |
| ORT teardown | `owner.close` in `onDestroy` (`AsrService.kt:256`) closes the three sessions and the environment reference; process death frees everything. Enumerated. |
| VAD session lifetimes | `open` per take, `release` at finish, process death (`SilenceVadService.kt`). The session and every per-window tensor and result are closed. |
| Take lengths | 4,800 samples (0.3 s) or fewer: "" (today's `onResult("")`). Up to 240,000: one window. More: the chunked recipe. The maximum is bounded by `RecordingLimits.MAX_AUDIO_BYTES`; `AsrBounds` allows about 500 ms per audio second (600 s allows 320 s). Goal 6 measures it. |
| Model-id consumers | `ModelDeliveryNotification.notificationId` (`ModelDeliveryNotification.kt:23`: an explicit case `parakeet-sq` -> 3 is added, so the id is not a hash) and its channel id; `PostHogSchema` "model" (`PostHogSchema.kt:133`: today derived from `ModelManifest.all`, it becomes `ModelManifest.deliverable` in chunk 1, so the staged download's telemetry is admitted, and returns to `all` in chunk 2, when `all` holds `parakeetSq`); `SentrySchema` "model" (`SentrySchema.kt:156`) follows the same path. Test: add `ModelDeliveryNotificationTest` (proposed) for the explicit `parakeet-sq` id and channel; keep the existing schema test for the derived telemetry id. |
| Content-change sweep | `model-delivery.md` RULE a-model-version-bump-is-a-content-change: in-app strings, the Play listing and outbound links naming the model version or quantisation are swept in chunk 3. |

## 6. Downstream consumer matrix
| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| Manifest `parakeet` content | `ModelWorkViewModel`, `ReadinessViewModel`, `OnboardingViewModel`, `TranscriptionScreen`, `OnboardingScreen`, `ModelBootstrapApplication`, `AsrService` | Reference `ModelManifest.parakeet` directly | Reference `ModelManifest.parakeetSq` in chunk 2 | Yes: every direct `ModelManifest.parakeet` reference is replaced (the list is regenerated in chunk 2 by grepping for `ModelManifest.parakeet`, not trusted from this row) | Compilation, updated tests, on-device readiness |
| Manifest host prefix | `ModelDelivery.validateModelSource` (`ModelDelivery.kt:296-297,312`) | Allows `models.enviouslabs.co` and the Hugging Face resolve path | New prefix `parakeet-sq` under the same host, Hugging Face `Olicorne/...` | No, if the validator is prefix-agnostic (checked in chunk 1) | `ModelManifestTest` URL cases |
| Decode engine | `AsrService.doTranscribe` path | sherpa stream | `ParakeetEngine.transcribe` | Yes | Unit + S26 binder |
| VAD probability source | `SilenceStopDetector` | sherpa `Vad.compute` | ORT session | Yes (`SileroVadSession`) | Parity test, goal 7 |
| Notices | `ThirdPartyNoticesTest`, the About screen asset | sherpa + ORT 1.17.1 | ORT 1.30, onnx-asr (MIT), Olicorne SmoothQuant (CC BY 4.0), NVIDIA | Yes | `ThirdPartyNoticesTest` |
| CI | `pr-check.yml:39` and `scripts/release/build.sh:9-10` → `setup-android-deps.sh` | Fetches the AAR and sets up the SDK | Sherpa block removed, SDK setup kept | Yes | PR build and release build green |
| Launch readiness | `AppLaunchFacts.kt:36` | Reads `ModelManifest.parakeet` | Reads `ModelManifest.parakeetSq` | Yes (same grep) | Compilation, launch facts test |

## 7. Failure-mode × caller table
| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Model files missing or unverified | `ModelStorage.isReady` false | `AsrService.initRecognizer` | Today's "model not ready" path (`MODEL_NOT_LOADED`) | History row as today | After verification, the next new `:asr` process (today's behaviour) |
| ORT session create throws (a bad file, or an operator missing on a phone) | `ParakeetEngine` init | `RecognizerOwner.load` | As above | As above | The next `:asr` start retries the load (today's behaviour) |
| ORT run throws mid-take | `TdtModel` | `AsrService` decode try | `DECODE_FAILED`, today's copy | As today | Next take |
| Decode outlives `AsrBounds` | Long take on a slow phone | `AsrWatchdog` | `ASR_PROCESS_UNRESPONSIVE`, today's copy | As today | Fresh `:asr` |
| VAD session fails to open | ORT load | `SileroVadSession.open` returns null | Auto-stop unavailable for the take, recording continues (today's contract) | None | Next take |

## 8. Caller-visible signals audit
- Raw text is still the only payload, and the empty string still means "no speech".
- `isReady` still means the recogniser loaded and the watchdog is not wedged.
- Decode logs keep `Decode: Nms, RTF, textChars`. A `windows=N repairs=M` field is added (no user content). `DebugLogger` (`DebugLogger.kt:9-23,96-114`) stays the logcat boundary. The recipe and engine log counts only; transcript words go only through `log.words` to the local log file. ORT exception messages are reduced to the class name, as `AsrService` does today (:225). A source test asserts that no engine log call carries text. On the S26, a unique canary sentence is dictated with detailed logging on and off. Logcat is captured across all app processes, and the run fails if the canary, or an ORT exception message containing it, appears. The canary is separately confirmed present in the phone-only detailed log when that log is on.
- The model display name "Parakeet" and the licence "CC-BY-4.0" are unchanged on screen.

## 9. Fallback source-of-truth audit
| Failure branch | Candidate expression | Source | Why authoritative | Acceptance predicate | If none qualifies | Consumer |
|---|---|---|---|---|---|---|
| New model not yet delivered after update | Old sherpa model | Disk | It is not: the old engine is removed | — | `MODEL_NOT_LOADED` from any `:asr` process that started before verification, until it ends; unreachable on the S26 because the chunk 1 receipt precedes the chunk 2 install (§14, question 2) | Session owner |
| Recipe window produced no tokens | Empty for that window | Recipe | macOS behaves the same | — | Merge continues | Text |

## 10. File-by-file changes
**Chunk 0: Java-path probe, no app change.** Before the engine swap, run the exact `onnxruntime-android:1.30.0` Java AAR with the SmoothQuant model on the S26 (a throwaway debug harness in the worktree, never committed). Confirm that the 8-bit `MatMulNBits` (external) executes (onnxruntime issue 24769 failed on arm64 in 1.22), that the 9 endings are kept, and measure the Java per-call cost of about 700 decoder_joint steps on a 27 s take against the native `tdt_ort` (external) figures. If it fails either gate, stop and fall back to the C++ JNI harness (§3 alternatives).

**Chunk 1: model delivery staged; the app is unchanged in behaviour.**
- `models/ModelManifest.kt`:
  - add `parakeetSq` (proposed) with the four files, hosted prefix `parakeet-sq` and the Hugging Face fallback;
  - add `staged` (proposed) = `listOf(parakeetSq)` and `deliverable` (proposed) = `all + staged`;
  - `all` stays on the current two models, so the Storage page (`StoragePage.kt:81`) and every `all` consumer is unchanged.
- `ModelDeliveryWorker.kt:27` resolves ids through `deliverable` instead of `all`.
- The chunk 1 trigger: from `ModelBootstrapApplication.kt:109` (main process only), a background IO task reads both receipts; `ModelStorage.isReady` hashes files and is restricted to a worker or service executor (`ModelDelivery.kt:81`). When the old `parakeet` verifies and `parakeetSq` does not, it calls the existing `ModelDeliveryWorker.enqueueSetup(context, parakeetSq, mobileData = false)` (`ModelDeliveryWorker.kt:296-307`). With `restart` false, that is unique work with `ExistingWorkPolicy.KEEP`, unmetered and storage-not-low, and it does not clear the control store. No new enqueue primitive is added. Pause and cancel are read from `ModelDeliveryControlStore` by the worker, so a KEEP re-submit after a user cancel must not resume the download; `StagedModelTriggerTest` covers that case, and if it fails the trigger checks the control store first. It never calls `enqueue`, which clears pause and cancel state and replaces on every launch (`ModelDeliveryWorker.kt:313`), so active, paused and cancelled work is preserved. Adoption is not used, because bootstrap is adoption-only and exits without a matching legacy folder (`ModelDeliveryWorker.kt:65`). The trigger applies to every installation with the verified old model. Test (`StagedModelTriggerTest` (proposed)): repeated app starts during a partial download and a paused download leave the existing work unchanged, and the trigger never runs on the main thread. Acceptance before chunk 2: the worker's Ready result and `ModelStorage.isReady(parakeetSq)` read on the S26, both recorded in the validation folder.
- `PostHogSchema` "model" (`PostHogSchema.kt:133`) and `SentrySchema` "model" (`SentrySchema.kt:156`) read `deliverable` instead of `all`, because without the change PostHog drops the staged model id (`PayloadSanitizer.kt:92`) and Sentry redacts it (`SentrySchema.kt:169`); with it, both keep `parakeet-sq`. The notification id gets its `parakeet-sq` case (§5). Found by the founder's telemetry question at Gate 2; the Sentry allowlist was missing from every earlier round.
- Telemetry meaning: no event, property or schema is added or renamed. The only visible difference is a new value, `parakeet-sq`, of the existing `model` property; dashboards filtering `model = parakeet` must include it. Dictation events carry no engine field, and `AsrFailureReason` codes are unchanged, so dictation and Sentry defect history stays comparable across the switch.
- Upload the four files to R2 under `parakeet-sq/9d104194…/`. This is existing storage, and the cost stays inside the existing R2 plan (NOT VERIFIED; founder approval required if not).
- `ModelManifestTest`: pins.

**Chunk 2: engine swap, one runtime.**
- `app/build.gradle.kts`:
  - remove `files("libs/sherpa-onnx.aar")` (:115);
  - add `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`;
  - keep `noCompress += "onnx"`.
- `asr/ParakeetEngine.kt`, `asr/TdtRecipe.kt`, `asr/TdtModel.kt` (all proposed).
- `asr/AsrService.kt`: replace the sherpa imports and calls (:14-17, :211-218, :262-301) with the engine. The owner, watchdog, bounds, callbacks and logging stay.
- `vad/SileroVadSession.kt`: an ORT session in place of sherpa `Vad`.
- `models/ModelManifest.kt`: `all` becomes `listOf(parakeetSq, s1)`; the old `parakeet` descriptor, `staged` and `deliverable` are removed. `ModelDeliveryWorker` model-id resolution and the PostHog and Sentry `model` allowlists go back to reading `all`. The chunk 1 staged trigger is deleted with them. `AsrService`, and every `ModelManifest.parakeet` consumer (§6), reads `parakeetSq`.
- `models/LegacyModelSweep.kt` (proposed): called by `AsrService` after its first successful new-engine result is delivered. It removes `no_backup/models/parakeet` once, logs the byte count and never throws. Test `LegacyModelSweepTest` (proposed): no removal before a successful callback; exactly the old folder after one; a missing folder is a no-op.
- Notices: `scripts/generate-notices.py`, `THIRD-PARTY-NOTICES.txt`, `app/src/main/assets/THIRD_PARTY_NOTICES.txt`, `ThirdPartyNoticesTest`.
- CI: keep `scripts/ci/setup-android-deps.sh`, which `scripts/release/build.sh:9-10` sources for the SDK toolchain; remove only its sherpa download and checksum block (:17-24). Both the PR build and the release build run with the updated script.

**Chunk 3: knowledge and docs.**
- `tech-stack.md`, `architecture.md` (asr and vad rows), `model-delivery.md`, `current-state.md`, `github.md:52`, `INDEX.md`, `android-tooling.md:27`, `docs/device-support-decision.md:12`, `README.md`, `CLAUDE.md:86`, the native-symbol note in `telemetry.md` (it names sherpa; update it for the libraries kept after sherpa's removal), `docs/releasing.md:20`, `docs/play-listing/16kb-page-size-check-2026-09-17.md:15-24`.

## 11. Testing
0. **Oracles.** Three independent sources, committed under `scripts/eval/asr-recipe/` (proposed) so every cited reference is in the tree:
   - the FluidAudio CLI text built from the pinned fork `b29591ad` (external) with the app layout (`--mel-context`) on the 37 clips, the stress cuts and the fixtures;
   - the Python reference port (`macbatch2.py`, copied from the evaluation) with its raw per-window tokens, timestamps and durations, and its merged tokens;
   - hand-derived literal cases for each merge and repair branch, each citing its Swift line.
   Exact algorithm parity with Swift is claimed only where the FluidAudio CLI text agrees.
1. **Classes.**
   - `TdtRecipeTest` (proposed): product outcome. When it fails, the user sees missing or repeated words at chunk seams. It runs a fake runner that replays the pinned per-window fixtures, and asserts literal outputs for: the window layout (single window, fixed stride, mel context, last-chunk backfill and speech end); contiguous, then LCS, then midpoint merge; the monotonic clamp; seam-duplicate collapse; repair limits (3 passes, 32 probes, ±6-frame edges); and a fresh decoder state per window (the runner records that every window starts from the zero state).
   - `SileroVadParityTest` (proposed): drift guard. When it fails, auto-stop fires at different moments.
   - `ParakeetGoldenBench` (proposed): harness contract. It runs the same Kotlin `TdtRecipe` through a host ORT runner: the desktop Java artifact `com.microsoft.onnxruntime:onnxruntime:1.30.0` (external), a test-only dependency. The run covers the 37 clips, the 75 stress cuts and the fixtures, against the committed oracles, and must pass before the S26 gate. The Mac is an M4 Pro (arm64). Whether that artifact ships an osx-arm64 native library is NOT VERIFIED; if it does not, the bench runs under an x64 JDK with Rosetta, and the run records the architecture. Android inference and binder parity are proven separately on the S26 (chunk 0 and §11.1). It needs the 650 MB model, so it is excluded from CI.
2. **Reverts that turn tests red.**
   - Carry the decoder state across windows: `TdtRecipeTest` "every window starts from the zero state" fails.
   - Remove the contiguous-match merge: the seam-duplicate case fails.
   - Swap in the old preprocessor sha: the golden bench fails.
   - Change the VAD state carry: the parity test fails.
3. **Not tested in CI.** Real-model accuracy (model size) and phone RAM/latency: the hardware UAT owns those.

### 11.1 Hardware UAT spec
- **Subsystem:** heart path.
- **Recipe:**
  - `device-testing.md` FACT: silent-physical-phone-audio-injection-for-night-uat, for real takes through the binder: the four failing-length WAVs, five 45 to 60 s stress cuts, the control, and the three noise fixtures.
  - One 600 s take built from the founder's kept recordings.
  - Latency and RAM sampled from `:asr` VmHWM and the decode log line.
- **Expected observation:**
  - the editor's text contains "would it correctly apply the right choice" for every stress input;
  - "" for the noise fixtures;
  - the 600 s take completes;
  - RAM and latency meet goals 4 and 5.
  - The oracle is the editor's own text via the silent helper, never a log line.
- **Phone state to restore afterwards:** the model folder keeps the new model; the old model folder is removed only by delivery housekeeping; any developer switch toggled for the log is restored.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `ModelManifestTest` (`:16-18,44-55`), `ModelFolderFootprintTest` (`:23-25`), `ModelFootprintTest` (`:85-105`), `ModelWorkViewModelTest` (`:103-104`), updated | Drift guard | New id, URLs, file set, storage footprint and work names | Change a sha, a file name or the id |
| `ThirdPartyNoticesTest` (updated) | Drift guard | Attribution present | Drop the Olicorne entry |
| `AsrServiceShapeTest` (updated) | Drift guard | Owner, watchdog and failure wiring kept | Bypass `RecognizerOwner` |
| `PostHogSchemaTest`, `SentrySchemaTest` (a new case in each, chunks 1 and 2) | Drift guard | A literal `parakeet-sq` model-delivery event survives PostHog sanitization, and a model-delivery breadcrumb survives Sentry sanitization, with `model = parakeet-sq` | Point either schema back at the wrong catalog |

## 12. Blast radius & rollback
- **Touched:** `asr/`, `vad/SileroVadSession.kt`, `models/ModelManifest.kt`, the build file, notices, CI script.
- **Also touched:** both telemetry schemas' `model` allowlist and launch readiness (`AppLaunchFacts.kt:36`).
- **Not touched:** AIDL, the session owner, capture, polish, insertion, History, telemetry events and properties.
- **Revert:** revert the chunk 2 PR. The old manifest entry and the AAR come back, and phones re-download the old model on demand (about 650 MB). Chunk 1 is harmless on its own.

## 13. Ship criteria specific to this change
- [ ] On the S26, a fast final clause after a full stop is kept on every stress input, through the side button path.
- [ ] Noise, silence and burst dictations produce no words.
- [ ] A 600 s dictation completes on the S26; its post-stop time is reported to the founder.
- [ ] `:asr` peak RAM is at or below today's on 27 s and 60 s takes.

## 14. Open questions
1. **600 s post-stop target (founder).** The 3 s target for a 10-minute take cannot be met by after-stop decoding on the CPU. The expected time is about 20 s, estimated from the speed of the 27 s and 60 s takes, not measured. Options: accept a longer wait for very long takes (shown by the recorder's existing working state), or revisit later with the NPU picker.
2. **Update window (resolved by grounded r1, finding 2).** Bootstrap only queues adoption (`ModelBootstrapApplication.kt:109`, `ModelDeliveryWorker.kt:331`); it is not an automatic download. On the S26, chunk 1 ships first, and the new model is admitted and verified there while the old build still dictates. The engine swap (chunk 2) is installed only after that. The old `parakeet` folder is kept until the new engine completes a binder take, then removed. Automatic delivery on update, for strangers' phones, is routed to stage 2 as a follow-up issue.
3. **R2 upload.** Uses the existing bucket. The cost increase is about 670 MB of storage; founder approval if any spend is involved.

## 15. Related
#374, #379, #73, #72, #36, #109, #180, #204, #6. The macOS recipe is FluidAudio fork `b29591ad` (`EnviousWispr/Package.resolved`).
