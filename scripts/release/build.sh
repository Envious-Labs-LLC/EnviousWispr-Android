#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${PLAY_VERSION_CODE:?Release version code required}"
mkdir -p app/libs
curl --fail --location --silent --show-error --retry 3 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.29/sherpa-onnx-1.12.29.aar \
  -o app/libs/sherpa-onnx.aar
printf '%s  %s\n' 2beeb891a6f07043a7993d9957fdd4d6a67ec9b8ccdb573cb9fe57c4834f3376 app/libs/sherpa-onnx.aar | sha256sum --check
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.13113456' 'cmake;3.31.6'
printf 'sdk.dir=%s\ncmake.dir=%s/cmake/3.31.6\n' "$ANDROID_HOME" "$ANDROID_HOME" > local.properties
./gradlew :app:testReleaseUnitTest :app:bundleRelease --rerun-tasks --console=plain --max-workers=2 -PplayVersionCode="$PLAY_VERSION_CODE"
python3 scripts/release/test_receipt.py
mkdir -p dist
cp app/build/outputs/bundle/release/app-release.aab dist/unsigned.aab
python3 - <<'PY'
import hashlib, json, os
from pathlib import Path
p=Path('dist/unsigned.aab')
receipt={'commit':os.environ['GITHUB_SHA'],'version_code':int(os.environ['PLAY_VERSION_CODE']),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
Path('dist/build.json').write_text(json.dumps(receipt,indent=2)+'\n')
PY
