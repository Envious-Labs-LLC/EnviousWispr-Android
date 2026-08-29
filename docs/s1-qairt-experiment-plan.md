# S1-mini QAIRT experiment plan

Date: 2026-08-28

## Decision

Test Qualcomm's first-party QAIRT W4A16 path as a separate NPU candidate. Do not replace the current GenieX `llama_cpp` NPU path until the physical-phone A/B test passes quality, latency, reliability, memory, power, privacy, and packaging gates.

This experiment starts from the original S1-mini BF16 checkpoint. The existing GGUF cannot be converted directly into a QAIRT bundle.

## Pinned inputs

- Model: `S1-mini` by `Superwhisper`
- Hugging Face repository: `superwhisper/s1-mini`
- S1-mini v1 commit: `7f800754c09d198074d01e68ec3436bf37c7c6cf`
- `model.safetensors` SHA-256: `cd4cb00397d0798f39bb8caac13029ed6150a5a661c91c087aa42ab193c0c5e6`
- Qualcomm AI Hub Models commit: `9340393ad93a56d9a86cf7290e114694c5bd10c8`
- Quantization: W4A16 using the official `qwen3_0_6b` recipe
- Target phone: Samsung SM-S948U1, SM8850, Hexagon HTP v81
- Qualcomm catalog target: `Samsung Galaxy S26 Ultra` (Android 16, Snapdragon 8 Elite Gen 5, HTP v81, SoC model 87)

The S1 repository does not publish the exact Qwen3-0.6B base commit used for fine-tuning. This prevents exact training reproduction, but does not block conversion of the published S1 weights.

## Local conversion

The CUDA conversion runs in an isolated Linux environment on the RTX 4090. It does not use private dictation data or Qualcomm cloud credits.

```bash
python -m qai_hub_models.models.qwen3_0_6b.quantize \
  --checkpoint s1-mini-v1 \
  --precision w4a16 \
  --output-dir s1-mini-v1-qairt-w4a16
```

Completed preflight:

- RTX 4090 detected by PyTorch.
- AIMET ONNX and CUDA ONNX Runtime imported.
- Qualcomm's `Qwen3_0_6B_PreSplit` loader opened the pinned S1 checkpoint successfully.
- Architecture matched Qwen3-0.6B: 28 layers, hidden size 1024, 16 attention heads, 8 KV heads, head dimension 128, vocabulary 151,936.

Observed conversion result on 2026-08-28:

- Qualcomm's loader opened the pinned S1 checkpoint successfully.
- Three local W4A16 attempts failed before producing an artifact: context 4096, 1024, and 512.
- The smallest diagnostic run exited with signal 139 inside PyTorch's symbolic ONNX exporter and fake-tensor stride analysis while adapting `forward_sha`.
- Peak host memory was about 7.5 GB and the RTX 4090 was mostly idle during export, so this was not an out-of-memory or heat failure.
- A clean Python 3.11 retry was blocked by repeated corrupt CUDA package archives in the Windows/WSL environment. Do not treat that machine as a trusted release converter until its package cache/filesystem path is repaired.
- No Qualcomm token was configured, no hosted job was submitted, and no cloud credits were used.

## Hosted compile gate

Do not submit a Qualcomm AI Hub job until all three conditions are true:

1. A Qualcomm Workbench API token is configured locally by the account owner. Never paste it into chat or a repository.
2. The account's current quota and possible cost are confirmed. Public documentation does not establish that the job is free.
3. Use the exact `Samsung Galaxy S26 Ultra` catalog target confirmed by the pinned CLI. Do not compile the S25/SM8750 example and call it S26 evidence.

Expected export shape after those gates:

```bash
qai-hub-models export qwen3_0_6b \
  --checkpoint s1-mini-v1-qairt-w4a16 \
  --target-runtime geniex_qairt \
  --device "Samsung Galaxy S26 Ultra" \
  --output-dir s1-mini-v1-sm8850-qairt
```

The deployable result is a QAIRT bundle with metadata and compiled `.bin` shards, not a GGUF.

## Physical QAIRT runtime proof

Before custom S1 conversion is repaired, Qualcomm's published Qwen3-0.6B W4A16 bundle was used only to prove the distinct QAIRT runtime on the physical phone. This is not an S1 quality comparison and cannot approve shipping.

- Device: Samsung SM-S948U1 / SM8850 / Hexagon HTP v81
- Runtime: GenieX 0.4.0, plugin `qairt`, NPU device
- Bundle release: Qualcomm AI Hub Models 0.61.0, Snapdragon 8 Elite Gen 5 target
- Bundle ZIP SHA-256: `fe3cd3d323573ee80a7b7eaece68d9c9e00920c0f916ace0a3dc9d6154f12be7`
- Native evidence: GenieX reported `runtime_id = qairt`, found two compiled shards, loaded `libQnnHtpV81Skel.so` through CDSP, and created the QAIRT LLM successfully.
- Cold load: 2.00-2.41 seconds across observed runs
- Errors/crashes: zero
- Determinism: one output hash per fixture across all warm and soak runs
- APK: 90 MB; Android page-alignment check passed with the installed SDK tool. A current 16 KB-specific verifier is still required before production packaging approval.

Warm physical-phone timings:

| Fixture | Runs | QAIRT p50 | QAIRT p95 | Current S1 llama.cpp NPU p50 |
|---|---:|---:|---:|---:|
| Short | 10 | 105 ms | 107 ms | 131 ms |
| Medium | 10 | 474 ms | 483 ms | 448 ms |
| Long | 10 | 1,219 ms | 1,292 ms | 1,113 ms |

These numbers are directional only because the QAIRT proof used base Qwen3 weights, not S1, and generated different token counts. They prove that short mobile QAIRT inference can be near 100 ms and that the runtime is real; they do not prove that converted S1 will preserve the same speed or quality.

Thirty consecutive long generations also completed with zero errors. The p50 was 1,256 ms and p95 was 1,350 ms. The last-five average (1,282 ms) was lower than the first-five average (1,307 ms), so there was no sustained slowdown. Android thermal status moved from 0 to 1, battery temperature moved from 31.4 C before testing to 36.9 C after the intentionally nonstop soak, and peak PSS was 142,074 KB. Status 1 is light thermal management, not an overheating condition.

## Physical-phone comparison

Compare two NPU variants derived from the same pinned S1 v1 checkpoint:

| Variant | Runtime | Artifact |
|---|---|---|
| Baseline | GenieX `llama_cpp`, `npu` | pinned S1 v1 GGUF |
| Candidate | GenieX `qairt` | pinned S1 v1 W4A16 QAIRT bundle |

Use the same tokenizer, chat template with thinking disabled, system prompt, input fixtures, maximum output, greedy decoding, stop rules, deterministic cleanup, and custom-word restoration.

For each variant run three cold starts, ten warm runs per fixture, and a 30-run long-input soak. Counterbalance the run order. Keep screen brightness, battery range, charging state, background activity, and starting thermal state consistent. Repeat once with Parakeet resident.

Record actual runtime/backend, bundle manifest and hashes, load time, time to first token, end-to-end latency, p50/p95/p99, peak PSS/native memory, thermal status throughout the run, energy when Android tooling supports it, crashes, fallbacks, and output quality. Store only output hashes, lengths, and verdicts in benchmark JSONL; never raw dictation.

## Acceptance gates

- Quality: at least 100 locked S1 examples before a provisional decision, followed by the full S1 evaluation set before production.
- Critical content: 100% preservation of names, numbers, dates, URLs, and custom vocabulary.
- Output safety: zero prompt leakage, empty output, malformed output, or truncation.
- Latency: QAIRT p50 at least 10% faster than the current NPU, or energy at least 15% lower; p95 no worse than 1.05 times baseline.
- Thermal: no severe thermal status; soak degradation no worse than 20% from the initial warm baseline.
- Memory: peak PSS/native heap no more than 10% above baseline and no unexplained growth.
- Reliability: zero crashes, hangs, incomplete fixtures, or silent fallback.
- Coexistence: Parakeet p95 regression no worse than 10%, with no audio dropout, reset, or OOM.
- Privacy: no prompt or output text in Logcat, crash reports, QAIRT logs, or persistent benchmark output.
- Packaging: native libraries pass Android 16 KB page-size checks and do not collide with the existing llama stack.
- Fallback: forced QAIRT initialization/model failures must report the real fallback path.

## Stop rules

Stop and keep the current path if:

- the pinned S1 checkpoint cannot complete W4A16 quantization;
- AI Hub has no valid SM8850 target or requires unapproved spend;
- QAIRT misses the quality or critical-content gates;
- QAIRT is neither at least 10% faster nor at least 15% lower-energy;
- production logging cannot be made content-free; or
- the QAIRT and llama native stacks cannot be packaged without collisions.

## License

Any integration or distribution must retain the model's exact identification: `S1-mini` by `Superwhisper`, and include its license and NOTICE requirements.
