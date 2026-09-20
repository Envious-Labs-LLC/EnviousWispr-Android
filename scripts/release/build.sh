#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${PLAY_VERSION_CODE:?Release version code required}"
# Install the shared, pinned dependencies: the sherpa-onnx AAR, the SDK toolchain
# (platform, build-tools, NDK, CMake) and local.properties. Shared with the PR
# check (pr-check.yml) via scripts/ci/setup-android-deps.sh so a PR builds against
# exactly the toolchain that ships.
# shellcheck source=../ci/setup-android-deps.sh
source "$(dirname "$0")/../ci/setup-android-deps.sh"
# Telemetry keys from the workflow's repository variables (#176). A production bundle with either one
# empty ships silent: refuse, so a missing variable is a red build and never a quiet blind fleet.
: "${TELEMETRY_POSTHOG_KEY:?TELEMETRY_POSTHOG_KEY repository variable required for a Play build}"
: "${TELEMETRY_SENTRY_DSN:?TELEMETRY_SENTRY_DSN repository variable required for a Play build}"
./gradlew :app:testReleaseUnitTest :app:bundleRelease --rerun-tasks --console=plain --max-workers=2 \
  -PplayVersionCode="$PLAY_VERSION_CODE" \
  -PtelemetryPostHogKey="$TELEMETRY_POSTHOG_KEY" \
  -PtelemetrySentryDsn="$TELEMETRY_SENTRY_DSN"
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
