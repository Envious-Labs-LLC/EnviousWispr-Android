# EnviousWispr for Android

On-device voice-to-text for Android. Press, speak, and finished text lands in the app you were already
typing in. Transcription and AI polish both run on the phone.

Sister project to EnviousWispr for macOS, which is shipping.
**V1 is submitted for worldwide Google Play review. Public availability awaits approval.**
The project now focuses on reliable updates, customer support and ongoing maintenance.
[Release status and maintenance](docs/maintenance.md).

## What works today

- Dictation from the Samsung side button, a Quick Settings tile, a notification, or the app.
- Offline transcription with NVIDIA Parakeet running on ONNX Runtime.
- On-device AI polish with S1-mini on the Qualcomm GenieX runtime.
- Deterministic cleanup, plus a custom vocabulary with aliases and fuzzy matching.
- Transcript history, stored locally.
- Optional cloud polish through a provider you choose, using your own key.
- Text insertion through the accessibility service, with a clipboard fallback.

EnviousWispr never replaces your keyboard. The recorder floats over whatever you are using.

## Privacy

**Your audio never leaves the phone.** Transcription is always local.

If you turn on cloud polish, the transcribed **text** goes to the provider you picked, under your own API
key. There is no Envious Labs server in that path. Automatic usage/crash reports exclude dictated
content. Voluntary feedback sends the message, optional reply email and diagnostics you choose through
Sentry. [Privacy policy](https://enviouswispr.com/privacy-policy/).

## Requirements

- Android 13 (API 33) or later, 64-bit ARM.
- Nominal support: 6 GB memory or more; Android Go excluded; about 1.2 GB model storage.
- No chipset restriction. Local polish uses qualified GPU/CPU routes.
- Extensive S26 use and a short real Pixel 8 CPU check; this is not every-phone qualification.
- [Device support and actual store filter](docs/device-support-decision.md).

## Building

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # debug APK
```

A fresh clone needs `local.properties` with your `sdk.dir` and `cmake.dir`
(`scripts/ci/setup-android-deps.sh` writes it and installs the pinned toolchain).

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
NVIDIA Parakeet (CC-BY-4.0), S1-mini (Apache 2.0), ONNX Runtime, onnx-asr, and llama.cpp. The full list is in
`THIRD-PARTY-NOTICES.txt`.

## Status

The signed release and store paperwork are submitted. Google review is pending.
Use [the maintenance guide](docs/maintenance.md) and the issue tracker for current work.
Recording sounds, local history, dictionary and privacy disclosures are included in v1.
