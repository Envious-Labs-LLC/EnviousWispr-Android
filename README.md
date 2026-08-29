# EnviousWispr for Android

On-device voice-to-text for Android. Press, speak, and finished text lands in the app you were already
typing in. Transcription and AI polish both run on the phone.

Sister project to EnviousWispr for macOS, which is shipping.
**Android is an early native product. It is not released yet.**

## What works today

- Dictation from the Samsung side button, a Quick Settings tile, a notification, or the app.
- Offline transcription with NVIDIA Parakeet running on sherpa-onnx.
- On-device AI polish with S1-mini on the Qualcomm GenieX runtime.
- Deterministic cleanup, plus a custom vocabulary with aliases and fuzzy matching.
- Transcript history, stored locally.
- Optional cloud polish through a provider you choose, using your own key.
- Text insertion through the accessibility service, with a clipboard fallback.

EnviousWispr never replaces your keyboard. The recorder floats over whatever you are using.

## Privacy

**Your audio never leaves the phone.** Transcription is always local.

If you turn on cloud polish, the transcribed **text** goes to the provider you picked, under your own API
key. There is no Envious Labs server in that path. Envious Labs receives no dictated content.

## Requirements

- Android 11 (API 30) or later, 64-bit ARM.
- The on-device polish path currently targets Qualcomm Snapdragon hardware.
- Developed and tested on a Samsung Galaxy S26 Ultra.

## Building

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # debug APK
```

Two things a fresh clone needs first:

1. `local.properties` with your `sdk.dir` and `cmake.dir`.
2. `app/libs/sherpa-onnx.aar`. This 38 MB binary is not in the repository yet, so a fresh clone will not
   build until it is supplied. Fixing that is tracked as an open issue.

`./gradlew build` currently fails: three experimental accelerator-benchmark flavors do not compile. That
module is a research sandbox, not part of the app. Build the `:app` targets instead.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The application |
| `llama-android/` | JNI wrapper around a pinned llama.cpp submodule |
| `accelerator-benchmark/` | CPU vs GPU vs NPU experiments. Not shipped. |
| `docs/` | Architecture, the macOS parity contract, and benchmark results |

After cloning, run `git submodule update --init --recursive`.

## License

EnviousWispr for Android is open source under the [GNU General Public License v3](LICENSE) (GPLv3), an
OSI-approved license. You can read, build, modify, and redistribute the code under the terms of the GPL,
including for commercial purposes; distributed derivative works must also be licensed under the GPLv3 with
their source available.

Copyright (C) 2026 Envious Labs LLC.

The app depends on third-party components under their own licenses, including the Qualcomm GenieX runtime,
NVIDIA Parakeet (CC-BY-4.0), S1-mini (Apache 2.0), sherpa-onnx, and llama.cpp. A full third-party notice
file is still outstanding.

## Status

Third-party model and runtime licences, release signing, and the Play Store listing are all outstanding.
See the issue tracker.
