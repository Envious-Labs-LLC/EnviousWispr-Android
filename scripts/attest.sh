#!/usr/bin/env bash
# attest.sh — record an obligation as satisfied in the latest Phase 3 run.
#
# For the obligations no script can satisfy on its own: a Codex review, a hardware UAT, a CI run. It moves
# the id from obligations_skipped to obligations_satisfied and files the artifact. It does NOT judge the
# artifact's contents; check-validation.sh asserts it exists and is not blank, and a human is responsible
# for it being true.
#
#   scripts/attest.sh codex-review /path/to/review.md
#   scripts/attest.sh hardware-uat /path/to/uat.json
#
# Exit: 0 recorded · 2 could not record, which is not an attestation.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

OBLIGATION="${1:-}"; SOURCE="${2:-}"
[ -n "$OBLIGATION" ] && [ -n "$SOURCE" ] || { echo "usage: attest.sh <obligation-id> <artifact-file>" >&2; exit 2; }
[ -s "$SOURCE" ] || { echo "FAIL: $SOURCE is missing or empty. An empty artifact is not evidence." >&2; exit 2; }

RUN=$(ls -d .validation/runs/*/ 2>/dev/null | sort | tail -1)
[ -n "$RUN" ] || { echo "FAIL: no run directory. Run scripts/validate-pr.sh first." >&2; exit 2; }
RUN=${RUN%/}

case "$OBLIGATION" in
  codex-review)       DEST="codex-review.md" ;;
  hardware-uat)       DEST="hardware-uat.json" ;;
  benchmark-assemble) DEST="benchmark-assemble.log" ;;
  workflow-run)       DEST="workflow-run.txt" ;;
  tests|cited-symbols) echo "FAIL: $OBLIGATION is produced by validate-pr.sh, not attested by hand." >&2; exit 2 ;;
  *) echo "FAIL: unknown obligation id: $OBLIGATION" >&2; exit 2 ;;
esac

cp "$SOURCE" "$RUN/$DEST" || exit 2
python3 - "$RUN" "$OBLIGATION" <<'PY'
import json, os, sys
run, obligation = sys.argv[1], sys.argv[2]
path = os.path.join(run, "run.json")
data = json.load(open(path))
data["obligations_skipped"] = [x for x in data.get("obligations_skipped", []) if x != obligation]
if obligation not in data.get("obligations_satisfied", []):
    data.setdefault("obligations_satisfied", []).append(obligation)
json.dump(data, open(path, "w"), indent=2)

# The skip note must stop claiming a reason for something now satisfied, or the run carries two answers.
note = os.path.join(run, "skip-note.txt")
if os.path.exists(note):
    kept = [ln for ln in open(note).read().splitlines() if not ln.startswith(obligation + ":")]
    open(note, "w").write("\n".join(kept) + ("\n" if kept else ""))
print(f"recorded {obligation} in {run}")
PY
