# ASR recipe reference (#374, #453)

Evidence behind the speech engine: the Kotlin `TdtRecipe` is a port of the macOS FluidAudio batch recipe (fork `b29591ad`).

- `macbatch_reference.py`: the Python reference port the Kotlin code was written from. It runs on onnx-asr internals, and every rule cites its FluidAudio line.
- `golden/`: a desktop JVM harness. It compiles the app's own `TdtRunner.kt`, `TdtRecipe.kt` and `TdtModel.kt` against `com.microsoft.onnxruntime:onnxruntime:1.30.0` and transcribes a list of WAV files.
  - Setup: `golden/setup.sh` (links the sources, copies the Gradle wrapper, installs).
  - Run: `golden/build/install/golden/bin/golden <model_dir> <nemo128.onnx> <list.tsv> <out.jsonl>`. `<model_dir>` holds `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx` and `vocab.txt`; use the app's `app/src/main/assets/nemo128.onnx`. A `list.tsv` row is `kind<TAB>name<TAB>wav[<TAB>lastSeconds]`, where `lastSeconds` keeps only the final seconds.
  - Diagnostics: `java -cp "golden/build/install/golden/lib/*" com.envi.wispr.asr.ProbeKt <model_dir> <nemo128.onnx> <wav>` prints each window and merge stage; `Probe1Kt` steps one window's decoder.
- `endings/`: the lost-endings gate (#453), rebuilt after the #374/#379 stress clips were lost.
  - `make_stress_set.py <corpus> <out>`: 288 takes from public LibriSpeech test-clean speech in the #433 corpus (`development-complete.jsonl`), with endings at 1 to 60 s, around the 15 s window and the 28 s seam, fast final clauses, and room-noise pauses, plus 3 silence takes. Writes `list.tsv` and `expected.jsonl`.
  - `score_endings.py <expected.jsonl> <golden-out.jsonl>`: exits non-zero on any lost ending, lost start, words on silence or missing output; prints the word error rate.
  - `control_scorer.py`: two-way control. It must catch injected losses before the scorer's silence is trusted.

Results measured 2026-09-27 on an M4 Pro (arm64):
- **Word error rate:** on 37 human-captioned YouTube clips from 3 videos (video `7zP-IlalrU4` excluded because its captions omit speech), the Kotlin engine scored 6.38%. The Python reference scored 6.28% and the macOS app's FluidAudio batch 6.03%.
- **Lost endings:** 0 of 75 on the fixed-ending stress cuts, and 0 of 39 over the start offsets.
- **Agreement with the Python reference:** word-identical on 140 of 165 inputs. The rest differ by single words, from front-end numerical differences.

Results measured 2026-10-20 on the `endings/` gate (M5 Max), through the same Kotlin code: the shipped SmoothQuant files (hashes match `ModelManifest`) lost 48 of 288 endings at 12.6% WER; Parakeet Ultra int8 ONNX lost 0 at 2.8%, and the scorer caught 288 of 288 injected lost endings on it. Details: #458.

No audio or transcript text is committed here. The clips are public videos whose captions belong to their authors, and the founder's recordings are private. Both stay on the development machine; the endings set is regenerated from the corpus.
