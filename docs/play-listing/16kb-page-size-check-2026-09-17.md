# 16 KB page-size check on the signed bundle, 2026-09-17

Play requires every native library the Android linker loads to be built with 16 KB-aligned segments for
apps targeting Android 15+ devices with 16 KB pages. This is the measurement on the bundle that went to
internal testing as version 136 (`published-play-bundle` artifact of GitHub run 35182761958, commit
b155cf3, signed bundle SHA-256 `a50f21dd…`).

## Method

```
unzip -o signed.aab 'base/lib/arm64-v8a/*'
for f in base/lib/arm64-v8a/*.so; do llvm-objdump -p "$f" | awk '$1=="LOAD" {print $NF}' | sort -u; done
llvm-readelf -h <lib> | grep Machine
```
(`llvm-objdump` and `llvm-readelf` from NDK 29.0.13113456.) Control: `libsherpa-onnx-jni.so` reads
`2**14`, so the instrument distinguishes the two alignments.

## Result

59 libraries in `base/lib/arm64-v8a`.

- **48 are AArch64 and every one has every LOAD segment at `2**14` (16 KB).** These are the libraries the
  Android linker maps into the app's processes: sherpa-onnx and ONNX Runtime (speech), llama.cpp and ggml
  (polish), the GenieX runtime, AndroidX and DataStore natives, the OpenMP runtime, ONNX Runtime.
- **11 are `2**12` (4 KB), and all 11 are `Qualcomm Hexagon` ELF files**, not AArch64:
  `libCalculator_skel.so`, `libggml-htp-v73/v75/v79/v81.so`, `libQnnHtpV79.so`, `libQnnHtpV79Skel.so`,
  `libQnnHtpV81.so`, `libQnnHtpV81Skel.so`, `libQnnNetRunDirectV79Skel.so`, `libQnnNetRunDirectV81Skel.so`.
  These are DSP-side binaries the Hexagon runtime loads onto the DSP; the Android linker never maps them
  into a process, so they cannot fault on a 16 KB-page phone. They ship because the GenieX AAR carries
  them for the NPU path, which is a development override and never selected in a release build
  (`.claude/knowledge/polish-engines.md`).

## What this means for launch

The app is 16 KB compliant for everything Android loads. Two follow-ups, neither blocking:

1. **Play's automated pre-launch warning may still list the 11 Hexagon files**, because Google's published
   checker scans every `.so` under `lib/arm64-v8a` without reading the ELF machine. If it does, the
   answer above is the response; there is no runtime risk.
2. **Excluding the Hexagon files from the release bundle** would remove the warning and shrink the AAB
   (117 MB today). It is a packaging rule in `app/build.gradle.kts` (a `jniLibs` exclude for the release
   variant) and needs one emulator and one phone run to prove the CPU polish path never touches them.
   Queued as a SMALL change for after launch; not done tonight because it changes what ships without a
   phone pass.
