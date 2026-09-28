#!/usr/bin/env bash
# Shared CI dependency setup: the exact SDK toolchain the native build needs, plus
# local.properties. (The sherpa-onnx AAR it used to fetch left with #374: speech and
# silence detection now run on the ONNX Runtime Maven dependency.)
#
# WHY THIS IS ITS OWN FILE. The PR check (pr-check.yml) and the release build
# (release/build.sh) must install IDENTICAL dependencies, or a PR could pass
# against a different toolchain than the one that ships. Extracting the setter
# here and sourcing it from both is the only way they cannot drift. release
# build.sh keeps its release-only steps (version code, bundleRelease, receipts);
# this file is exactly the part they share.
set -euo pipefail

: "${ANDROID_HOME:?Android SDK required}"

# Accept the SDK licences only for the explicitly requested toolchain packages.
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6' <<'SDK_LICENSES'
y
y
y
y
SDK_LICENSES

printf 'sdk.dir=%s\ncmake.dir=%s/cmake/3.31.6\n' "$ANDROID_HOME" "$ANDROID_HOME" > local.properties
