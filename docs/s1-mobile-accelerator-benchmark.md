# S1 mobile accelerator benchmark

Date: 2026-08-28
Device: Samsung SM-S948U1, SM8850, Adreno 840, Hexagon HTP
Runtime: Qualcomm GenieX Android 0.4.0 (`llama_cpp`)

## Decision

Prioritize the Hexagon NPU path. Keep GPU as the first engineering fallback and CPU as the final compatibility fallback.

This is physical-phone benchmark evidence, not production acceptance. The NPU used a Q4_0 model while the CPU and GPU control used the existing Q4_K_M model. A full transcript quality evaluation is required before replacing the production model.

## Median warm inference time

Each cell is the median of five measured runs after one warm-up.

| Fixed synthetic fixture | CPU, Q4_K_M | Adreno GPU, Q4_K_M | Hexagon NPU, Q4_0 |
|---|---:|---:|---:|
| Short | 435 ms | 199 ms | 131 ms |
| Medium | 1,218 ms | 630 ms | 448 ms |
| Long | 2,920 ms | 1,623 ms | 1,113 ms |

Median process memory after inference:

- CPU: about 1.07 GB
- GPU: about 1.62 GB
- NPU: about 0.36 GB

Cold model load observations:

- GPU first-ever load: 10.63 s while OpenCL kernels compiled
- GPU cached load: 1.21 s
- NPU load: about 0.45-0.58 s

Runtime logs confirmed that the GPU test offloaded all 29 layers to Adreno OpenCL and that the NPU test assigned all 29 layers and KV cache to HTP0. There were no silent CPU-fallback claims.

The original GenieX sampler configuration used zero values, which GenieX interprets as plugin defaults rather than greedy overrides. The corrected NPU measurements above use explicit top-k 1, a fixed non-zero seed, and non-zero near-zero temperature/min-p. All five outputs per fixture were then identical. The older 138/467/1,271 ms NPU measurements are superseded.

## NPU tuning matrix

All variants used the same Q4_0 model, runtime, prompt, and explicit deterministic sampler.

| Variant | Short | Medium | Long | Result |
|---|---:|---:|---:|---|
| Pinned NPU, 4 threads, 512 batch, 2048 context | **131 ms** | **448 ms** | **1,113 ms** | Winner |
| Pinned NPU, 6 threads | 146 ms | 478 ms | 1,140 ms | Slower |
| 6 threads, 2048/1024 batch | 152 ms | 500 ms | 1,214 ms | Slower |
| Same, 1024 context | 145 ms | 492 ms | 1,273 ms | Slower; no memory benefit |
| N-gram speculative decoding | 152 ms | 510 ms | 1,320 ms | Slower and used more memory |
| Hybrid scheduler | 208 ms | 702 ms | 1,672 ms | Much slower and used about 1.05 GB |

A counterbalanced 4-thread versus 6-thread check confirmed that 4 threads remained slightly faster and was less likely to raise Android's thermal-management status. A 2-thread sustained run was much slower, so lowering helper threads is not an effective power strategy.

## Sustained NPU run

Thirty consecutive long-fixture runs completed with zero crashes and zero inference errors.

- Median: 1,405 ms
- 95th percentile: 1,447 ms
- First five average: 1,109 ms
- Last five average: 1,434 ms
- Peak Android thermal status: 2 (moderate)
- Process memory stayed between about 349 and 352 MB

This was an intentionally unrealistic nonstop stress test. Android thermal status 2 means moderate performance management, not an overheating warning. The phone never reported a severe thermal state or a user-facing hot-device condition.

With ten seconds between calls, median long latency stayed near 1.11 seconds and thermal status peaked at 1. With thirty seconds between calls, the first three long runs stayed at 1.09-1.10 seconds and Android thermal status remained 0 for all five runs. A later 2.15-second outlier occurred while thermal status was still 0, so it was runtime/governor scheduling variance rather than evidence that the phone was hot.

## Quality observation

The three fixed synthetic fixtures preserved meaning, names, and numbers. With the corrected deterministic sampler, every measured output for each fixture was identical.

This is too small to prove production quality. The published Q4_K_M score cannot be transferred to the newly generated Q4_0 artifact. Run the real S1 evaluation set and founder UAT before shipping it.

## Production blockers

1. GenieX 0.4.0 logs full prompts and generated text at verbose level. Production integration must suppress or patch those upstream logs before real dictation is passed to it.
2. GenieX and the current `llama-android` module ship native libraries with the same names. Production must use one runtime owner; do not package both and do not hide the collision with `pickFirst`.
3. Add an independently pinned Q4_0 model descriptor, size, and SHA-256, plus download, repair, and removal support.
4. Prove cancellation, service-process death, CPU fallback, model updates, battery use, and concurrent Parakeet operation on the phone.
5. Review Qualcomm's Terms of Use and bundled licenses before release.

## Recommended production shape

- Replace the current S1 native runtime with GenieX as the single owner.
- Select `npu` on supported Snapdragon devices.
- Fall back truthfully to GenieX `gpu`, then GenieX `cpu`, recording the actual backend used.
- Preserve deterministic cleanup and custom-word restoration outside the model.
- Never silently relabel a fallback as NPU.

## Benchmark implementation

The standalone `accelerator-benchmark` app has mutually exclusive CPU, GPU, NPU, and NPU-tuning flavors. This keeps benchmark native libraries isolated and leaves the normal EnviousWispr app path unchanged.

Only fixed synthetic fixtures are accepted. Results are written as JSON Lines in the benchmark app's private files directory.

The scrubbed raw synthetic tuning results are preserved under [`docs/benchmark-results/2026-08-28-s1-npu-tuning`](benchmark-results/2026-08-28-s1-npu-tuning). They contain no user dictation.

Official references:

- [GenieX Android API](https://github.com/qualcomm/GenieX/blob/main/docs/en/run/android/api-reference.mdx)
- [GenieX supported models and quantization](https://github.com/qualcomm/GenieX/blob/main/docs/en/models/supported.mdx)
- [GenieX Android installation](https://github.com/qualcomm/GenieX/blob/main/docs/en/run/android/install.mdx)
- [GenieX benchmark design](https://github.com/qualcomm/GenieX/blob/main/notes/bench.md)
- [GenieX runtime tuning notes](https://github.com/qualcomm/GenieX/blob/main/notes/run.md)
