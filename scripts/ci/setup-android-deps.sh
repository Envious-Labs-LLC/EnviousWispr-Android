#!/usr/bin/env bash
# Shared CI dependency setup: the pinned, checksum-verified sherpa-onnx AAR and
# the exact SDK toolchain the native build needs, plus local.properties.
#
# WHY THIS IS ITS OWN FILE. The PR check (pr-check.yml) and the release build
# (release/build.sh) must install IDENTICAL dependencies, or a PR could pass
# against a different toolchain than the one that ships. Extracting the setter
# here and sourcing it from both is the only way they cannot drift. release
# build.sh keeps its release-only steps (version code, bundleRelease, receipts);
# this file is exactly the part they share.
set -euo pipefail

: "${ANDROID_HOME:?Android SDK required}"

# Pinned to the same release the app is built against. Both values are also
# asserted by the release build; changing the AAR version means changing both.
SHERPA_VERSION=1.12.29
SHERPA_SHA256=2beeb891a6f07043a7993d9957fdd4d6a67ec9b8ccdb573cb9fe57c4834f3376

mkdir -p app/libs
curl --fail --location --silent --show-error --retry 3 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar" \
  -o app/libs/sherpa-onnx.aar
printf '%s  %s\n' "$SHERPA_SHA256" app/libs/sherpa-onnx.aar | sha256sum --check

# Accept the SDK licences only for the explicitly requested toolchain packages.
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6' <<'SDK_LICENSES'
y
y
y
y
SDK_LICENSES

printf 'sdk.dir=%s\ncmake.dir=%s/cmake/3.31.6\n' "$ANDROID_HOME" "$ANDROID_HOME" > local.properties
