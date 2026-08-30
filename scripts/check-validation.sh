#!/usr/bin/env bash
# check-validation.sh — the verifier for a Phase 3 run directory. THIS is the thing that fails closed.
#
# validate-pr.sh scaffolds a run; this asserts it is real. Separate scripts on purpose: a scaffolder that
# judged its own output would be marking its own homework.
#
# It asserts: run.json parses, carries schema_version 1, and carries EVERY field this script reads, each
# with the right TYPE; head_sha matches the CURRENT HEAD, so a run cannot be reused across commits;
# declared_lane and every detected lane is one of the four exact-case names; declared_lane is among
# detected_lanes; more than one detected lane requires is_mixed_pr; every artifact each detected lane
# requires exists and is not BLANK; and every skipped obligation carries a written reason on its own line.
#
# Three of those exist because the earlier version passed without them, and each failure looked like a
# pass rather than an error. A blank-but-nonzero artifact satisfied a size check. `"detected_lanes":
# "Code"` is truthy, so it survived, and iterating a string yields four characters that require no
# artifact at all — a clean PASS having verified nothing. And a skip-note was satisfied by the obligation
# id appearing ANYWHERE in the file, including inside a different obligation'"'"'s reason.
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
import json, os, re, sys

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
if not isinstance(data, dict):
    print(f"FAIL: run.json must be a JSON object, got {type(data).__name__}", file=sys.stderr)
    sys.exit(2)

if data.get("schema_version") != 1:
    fails.append(f"schema_version is {data.get('schema_version')!r}, expected 1")

# A run pinned to another commit is evidence about other code. This is the check that stops a stale run
# being re-presented after an edit, which is the same class as a cached test count.
if data.get("head_sha") != head:
    fails.append(f"head_sha {str(data.get('head_sha'))[:12]} is not the current HEAD {head[:12]} — "
                 f"this run describes different code")

# Every field this verifier reads must be PRESENT and the right TYPE before any check consumes it.
# A missing list defaults to empty and a check over nothing passes; a string where a list belongs is
# worse, because `for lane in "Code"` iterates four characters, matches no required artifact, and
# reports PASS. Absence and wrong-shape both have to fail here, before the first read.
# Presence is only half of it. A field of the WRONG TYPE is the plausible-value trap: `"is_mixed_pr":
# "false"` is a truthy string, so the mixed-lane check reads it as yes and passes.  `type(...) is not`
# rather than isinstance, because bool is a subclass of int and `"schema_version": true` must not pass.
EXPECTED_TYPES = {
    "schema_version": int, "head_sha": str, "branch": str, "declared_lane": str,
    "detected_lanes": list, "changed_files": list, "is_mixed_pr": bool,
    "obligations_satisfied": list, "obligations_skipped": list,
}
for field, expected in EXPECTED_TYPES.items():
    if field not in data:
        fails.append(f"run.json is missing required field {field!r}")
    elif type(data[field]) is not expected:
        fails.append(f"{field} must be {expected.__name__}, got {type(data[field]).__name__}")

for field in ("detected_lanes", "changed_files", "obligations_satisfied", "obligations_skipped"):
    if isinstance(data.get(field), list) and not all(isinstance(v, str) for v in data[field]):
        fails.append(f"{field} must contain only strings")

declared = data.get("declared_lane")
if declared not in LANES:
    fails.append(f"unknown declared_lane: {declared!r}. Exactly one of {sorted(LANES)}")

detected = data.get("detected_lanes")
if not isinstance(detected, list) or not detected:
    fails.append(f"detected_lanes must be a non-empty JSON array, got {type(detected).__name__}")
    detected = []
else:
    for lane in detected:
        if lane not in LANES:
            fails.append(f"unknown detected lane: {lane!r}. Exactly one of {sorted(LANES)}")

for field in ("obligations_satisfied", "obligations_skipped"):
    if not isinstance(data.get(field, []), list):
        fails.append(f"{field} must be a JSON array, got {type(data.get(field)).__name__}")
        data[field] = []

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
        elif not open(full, "rb").read().strip():
            fails.append(f"{lane}: required artifact {artifact} exists but is BLANK, which is not "
                         f"evidence. Size alone cannot see this: a file of spaces passes a size check")

skipped = data.get("obligations_skipped", [])
note = os.path.join(run, "skip-note.txt")
if skipped:
    if not os.path.exists(note) or os.path.getsize(note) == 0:
        fails.append(f"{len(skipped)} obligation(s) skipped with no skip-note.txt. A skip without a "
                     f"reason is an omission wearing a label")
    else:
        body = open(note).read()
        for name in skipped:
            # `name in body` is satisfied by the word appearing anywhere, including inside another
            # obligation's reason. Anchor it: the id starts a line and a reason follows it.
            if not re.search(rf"(?m)^{re.escape(name)}:\s+\S.*$", body):
                fails.append(f"obligation {name!r} is skipped without a reason. Write a line in "
                             f"skip-note.txt of the form `{name}: <why it does not apply>`")

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
