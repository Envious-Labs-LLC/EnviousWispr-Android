# ASR recipe reference (#374)

Evidence behind the speech engine: the Kotlin `TdtRecipe` is a port of the macOS FluidAudio batch recipe (fork `b29591ad`).

- `macbatch_reference.py`: the Python reference port the Kotlin code was written from. It runs on onnx-asr internals, and every rule cites its FluidAudio line.
- `golden/`: a desktop JVM harness. It compiles the app's own `TdtRunner.kt`, `TdtRecipe.kt` and `TdtModel.kt` against `com.microsoft.onnxruntime:onnxruntime:1.30.0` and transcribes a list of WAV files.
  - Setup: symlink those three files into `golden/src/com/envi/wispr/asr/`, copy the Gradle wrapper in, then run `./gradlew installDist`.
  - Run: `build/install/golden/bin/golden <model_dir> <nemo128.onnx> <list.tsv> <out.jsonl>`.

Results measured 2026-09-27 on an M4 Pro (arm64):
- **Word error rate:** on 37 human-captioned YouTube clips from 3 videos (video `7zP-IlalrU4` excluded because its captions omit speech), the Kotlin engine scored 6.38%. The Python reference scored 6.28% and the macOS app's FluidAudio batch 6.03%.
- **Lost endings:** 0 of 75 on the fixed-ending stress cuts, and 0 of 39 over the start offsets.
- **Agreement with the Python reference:** word-identical on 140 of 165 inputs. The rest differ by single words, from front-end numerical differences.

No audio or transcript text is committed here. The clips are public videos whose captions belong to their authors, and the founder's recordings are private. Both stay on the development machine.
