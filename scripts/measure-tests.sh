#!/usr/bin/env bash
# measure-tests.sh — run the unit tests and report a count that is actually a measurement.
#
# WHY THIS EXISTS (2026-08-29). A verification agent reported "165 tests, 0 failures" from a report
# timestamped earlier, because Gradle answered `:app:testDebugUnitTest UP-TO-DATE` and never ran them.
# The agent said so in its own notes and the number was carried forward as a receipt anyway.
#
# `BUILD SUCCESSFUL` is not a test result, and an UP-TO-DATE task returns the PREVIOUS run's XML. So this
# script always passes --rerun-tasks and reports the count parsed from the XML it just caused to be written.
# `definition-of-done` names this script as the only quotable source for a test count.
#
#   scripts/measure-tests.sh            # run and report
#   scripts/measure-tests.sh --quiet    # counts only, for a Phase 3 artifact
#
# Exit: 0 all green · 1 a test failed · 2 the measurement could not be made (which is not a pass).

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

RESULTS="app/build/test-results/testDebugUnitTest"
say() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$*"; }

# The XML is the evidence, so remove it first: a run that fails to produce it must not be able to report
# the previous run's numbers. Absence is then distinguishable from staleness.
rm -rf "$RESULTS"

say "Running :app:testDebugUnitTest --rerun-tasks (never UP-TO-DATE, so the count is a measurement)..."
./gradlew :app:testDebugUnitTest --rerun-tasks >/tmp/measure-tests.log 2>&1
GRADLE_EXIT=$?

if [ ! -d "$RESULTS" ]; then
    echo "MEASUREMENT FAILED: $RESULTS does not exist after the run, so there is no count to report." >&2
    echo "This is not a pass. Gradle exit was $GRADLE_EXIT; see /tmp/measure-tests.log" >&2
    tail -20 /tmp/measure-tests.log >&2
    exit 2
fi

python3 - "$RESULTS" "$QUIET" <<'PY'
import glob, os, re, sys
results, quiet = sys.argv[1], sys.argv[2] == "1"
tests = failures = errors = skipped = 0
suites = 0
failed_names = []
for path in glob.glob(os.path.join(results, "*.xml")):
    text = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r'<testsuite\b[^>]*>', text)
    if not m:
        continue
    header = m.group(0)
    def attr(name):
        # A missing attribute silently becoming 0 is the plausible-value trap: the run still prints a
        # well-formed count, and the count is about a file it could not read.
        got = re.search(name + r'="(\d+)"', header)
        if not got:
            print(f"MEASUREMENT FAILED: {path} has no {name!r} attribute on its <testsuite>.",
                  file=sys.stderr)
            sys.exit(2)
        return int(got.group(1))
    suites += 1
    tests += attr("tests"); failures += attr("failures")
    errors += attr("errors"); skipped += attr("skipped")
    for case in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(failure|error)\b', text):
        failed_names.append(f"{os.path.basename(path)[5:-4]}.{case.group(1)}")

if suites == 0:
    print("MEASUREMENT FAILED: result directory exists but contains no suite XML.", file=sys.stderr)
    sys.exit(2)
if tests == 0:
    # `tests=0 failures=0` reads as a clean run and is a run that asserted nothing.
    print("MEASUREMENT FAILED: suites were written but they contain no tests.", file=sys.stderr)
    sys.exit(2)

print(f"tests={tests} suites={suites} failures={failures} errors={errors} skipped={skipped}")
if failed_names and not quiet:
    print("\nFailed:")
    for n in failed_names:
        print(f"  {n}")
sys.exit(1 if (failures or errors) else 0)
PY
PY_EXIT=$?

# A green parse while Gradle failed means the failure was outside the tests: still not a pass.
if [ "$PY_EXIT" -eq 0 ] && [ "$GRADLE_EXIT" -ne 0 ]; then
    echo "Tests parsed clean but Gradle exited $GRADLE_EXIT — the failure is outside the test run." >&2
    echo "See /tmp/measure-tests.log" >&2
    exit 2
fi
exit "$PY_EXIT"
