#!/usr/bin/env bash
# check-validation.sh — the verifier for a Phase 3 run directory. THIS is the thing that fails closed.
#
# validate-pr.sh scaffolds a run; this asserts it is real. Separate scripts on purpose: a scaffolder that
# judged its own output would be marking its own homework.
#
# It asserts: run.json parses and carries schema_version 1; head_sha matches the CURRENT HEAD, so a run
# cannot be reused across commits; declared_lane is one of the four exact-case names; declared_lane is
# among detected_lanes; more than one detected lane requires is_mixed_pr; and every artifact each detected
# lane requires exists and is NON-EMPTY. An empty artifact is the silent-empty trap: it looks like evidence
# and carries none.
#
#   scripts/check-validation.sh .validation/runs/<id>
#   scripts/check-validation.sh .validation/runs/<id> --strict   # promote WARN to FAIL
#
# Exit: 0 PASS · 1 WARN (advisory) · 2 FAIL.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

RUN="${1:-}"; STRICT=0; [ "${2:-}" = "--strict" ] && STRICT=1
[ -n "$RUN" ] && [ -d "$RUN" ] || { echo "FAIL: no such run directory: ${RUN:-<none>}" >&2; exit 2; }

exec python3 - "$RUN" "$STRICT" "$(git rev-parse HEAD)" <<'PY'
import json, os, sys

run, strict, head = sys.argv[1], sys.argv[2] == "1", sys.argv[3]
fails, warns = [], []

LANES = {"Code", "Benchmark", "CI/workflow", "Docs/dev-tooling"}
OBLIGATIONS = {"tests", "codex-review", "hardware-uat", "benchmark-assemble", "workflow-run", "cited-symbols"}
REQUIRED = {
    "Code": ["unit-tests.xml", "codex-review.md"],
    "Benchmark": ["benchmark-assemble.log"],
    "CI/workflow": ["workflow-run.txt"],
    "Docs/dev-tooling": ["cited-symbols.txt"],
}

path = os.path.join(run, "run.json")
if not os.path.exists(path):
    print("FAIL: run.json is missing, so there is nothing to verify", file=sys.stderr)
    sys.exit(2)
try:
    data = json.load(open(path))
except json.JSONDecodeError as exc:
    print(f"FAIL: run.json does not parse: {exc}", file=sys.stderr)
    sys.exit(2)

if data.get("schema_version") != 1:
    fails.append(f"schema_version is {data.get('schema_version')!r}, expected 1")

# A run pinned to another commit is evidence about other code. This is the check that stops a stale run
# being re-presented after an edit, which is the same class as a cached test count.
if data.get("head_sha") != head:
    fails.append(f"head_sha {str(data.get('head_sha'))[:12]} is not the current HEAD {head[:12]} — "
                 f"this run describes different code")

declared = data.get("declared_lane")
if declared not in LANES:
    fails.append(f"unknown declared_lane: {declared!r}. Exactly one of {sorted(LANES)}")

detected = data.get("detected_lanes") or []
if declared and declared not in detected:
    fails.append(f"declared_lane {declared!r} is not among detected_lanes {detected}")
if len(detected) > 1 and not data.get("is_mixed_pr"):
    (fails if strict else warns).append(f"{len(detected)} lanes detected but is_mixed_pr is false")

for name in data.get("obligations_satisfied", []) + data.get("obligations_skipped", []):
    if name not in OBLIGATIONS:
        fails.append(f"unknown obligation id: {name!r}. Exactly one of {sorted(OBLIGATIONS)}")

overlap = set(data.get("obligations_satisfied", [])) & set(data.get("obligations_skipped", []))
if overlap:
    fails.append(f"obligation both satisfied and skipped: {sorted(overlap)}")

for lane in detected:
    for artifact in REQUIRED.get(lane, []):
        full = os.path.join(run, artifact)
        if not os.path.exists(full):
            fails.append(f"{lane}: required artifact {artifact} is missing")
        elif os.path.getsize(full) == 0:
            fails.append(f"{lane}: required artifact {artifact} exists but is EMPTY, which is not evidence")

skipped = data.get("obligations_skipped", [])
note = os.path.join(run, "skip-note.txt")
if skipped:
    if not os.path.exists(note) or os.path.getsize(note) == 0:
        fails.append(f"{len(skipped)} obligation(s) skipped with no skip-note.txt. A skip without a "
                     f"reason is an omission wearing a label")
    else:
        body = open(note).read()
        for name in skipped:
            if name not in body:
                fails.append(f"obligation {name!r} is skipped but not named in skip-note.txt")

for line in fails:
    print(f"FAIL: {line}")
for line in warns:
    print(f"WARN: {line}")

if fails:
    print(f"\n{len(fails)} failure(s). This run does not stand as evidence.")
    sys.exit(2)
if warns:
    print(f"\nPASS with {len(warns)} warning(s).")
    sys.exit(1)
print(f"PASS: {run} is complete for lanes {detected}")
sys.exit(0)
PY
