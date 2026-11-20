#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${PLAY_VERSION_CODE:?Release version code required}"
# Install the shared, pinned dependencies: the SDK toolchain
# (platform, build-tools, NDK, CMake) and local.properties. Shared with the PR
# check (pr-check.yml) via scripts/ci/setup-android-deps.sh so a PR builds against
# exactly the toolchain that ships.
# shellcheck source=../ci/setup-android-deps.sh
source "$(dirname "$0")/../ci/setup-android-deps.sh"
# Telemetry keys from the workflow's repository variables (#176). A production bundle with either one
# empty ships silent: refuse, so a missing variable is a red build and never a quiet blind fleet.
: "${TELEMETRY_POSTHOG_KEY:?TELEMETRY_POSTHOG_KEY repository variable required for a Play build}"
: "${TELEMETRY_SENTRY_DSN:?TELEMETRY_SENTRY_DSN repository variable required for a Play build}"
./gradlew :app:testReleaseUnitTest :app:lintVitalRelease :app:bundleRelease --rerun-tasks --console=plain --max-workers=2 \
  -PplayVersionCode="$PLAY_VERSION_CODE" \
  -PtelemetryPostHogKey="$TELEMETRY_POSTHOG_KEY" \
  -PtelemetrySentryDsn="$TELEMETRY_SENTRY_DSN"
python3 scripts/release/test_receipt.py
mkdir -p dist
cp app/build/outputs/bundle/release/app-release.aab dist/unsigned.aab
cp app/build/release-notes/release-notes.json dist/release-notes.json
python3 - <<'PY'
import hashlib, json, os, sys, types
from pathlib import Path
for name in ('google', 'google.auth', 'google.auth.transport', 'google.auth.transport.requests'):
    sys.modules.setdefault(name, types.ModuleType(name))
sys.modules['google.auth.transport.requests'].AuthorizedSession = object
sys.path.insert(0, 'scripts/release')
import publish
p = Path('dist/unsigned.aab')
rendered_path = Path('dist/release-notes.json')
rendered = json.loads(rendered_path.read_text(encoding='utf-8'))
receipt = {
    'commit': os.environ['GITHUB_SHA'],
    'version_code': int(os.environ['PLAY_VERSION_CODE']),
    'version_name': rendered['version_name'],
    'release_notes': rendered['release_notes'],
    'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
}
publish.compiled_release_notes(rendered['version_name'], receipt, rendered_path)
Path('dist/build.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
PY
