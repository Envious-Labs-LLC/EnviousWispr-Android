#!/usr/bin/env bash
# The PR check's build: debug unit tests plus a debug assemble, then assert the
# tests actually RAN (fresh, nonzero result XML with no failures).
#
# NOT the whole-project `build`: that drags in benchmark and release tasks that
# are unrelated to a pull-request gate and only slow it down. The release path
# (bundleRelease + release unit tests) stays on the internal-testing pipeline.
#
# The nonzero-count assertion is the drift guard gradle does not give: a build
# whose test wiring silently ran ZERO tests exits 0, which would let a gutted
# test config pass the gate. Only a run with tests > 0 and no failures passes.
set -euo pipefail

: "${ANDROID_HOME:?Android SDK required}"

# Fresh results, so a cached green from a previous task run cannot pass a broken
# change; --rerun-tasks forces the test and assemble tasks to execute.
rm -rf app/build/test-results/testDebugUnitTest
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --console=plain --max-workers=2

python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
files = glob.glob('app/build/test-results/testDebugUnitTest/**/*.xml', recursive=True)
if not files:
    print('CI FAIL: no unit-test result XML was produced; the tests did not run.')
    sys.exit(1)
tests = failures = errors = 0
for f in files:
    root = ET.parse(f).getroot()
    tests += int(root.get('tests', '0'))
    failures += int(root.get('failures', '0'))
    errors += int(root.get('errors', '0'))
print(f'CI: {tests} tests across {len(files)} suite file(s), {failures} failures, {errors} errors.')
if tests == 0:
    print('CI FAIL: the suite ran zero tests; refusing to pass a gutted test configuration.')
    sys.exit(1)
if failures or errors:
    sys.exit(1)
PY
