#!/usr/bin/env bash
# validate-pr.sh — scaffold a Phase 3 run directory for the current change.
#
# IT SCAFFOLDS. IT ENFORCES NOTHING. `check-validation.sh` is the verifier that fails closed, and this
# script calls it at the end as the final assertion. Keeping those two jobs in separate scripts is the
# macOS shape and the reason is worth carrying: a scaffolder that also judged its own output would be
# marking its own homework.
#
#   scripts/validate-pr.sh              # detect lane, create the run, collect what it can, verify
#   scripts/validate-pr.sh --no-tests   # skip the test run (for a Docs lane change)
#
# Lane globs, required artifacts and obligation IDs are owned by .claude/rules/workflow-process.md
# FACT: lanes. This script must stay in step with check-validation.sh's per-lane dispatch.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

RUN_TESTS=1
[ "${1:-}" = "--no-tests" ] && RUN_TESTS=0

BASE=$(git merge-base origin/main HEAD 2>/dev/null) || { echo "no merge-base with origin/main" >&2; exit 2; }
HEAD_SHA=$(git rev-parse HEAD)
SHORT=$(git rev-parse --short HEAD)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RUN=".validation/runs/${STAMP}-${SHORT}"

# Working tree included on purpose: Phase 3 runs BEFORE the commit, so a detector that reads only
# committed history would classify the lane from a change set that does not exist yet.
CHANGED=$(git diff --name-only "$BASE"; git ls-files --others --exclude-standard)
CHANGED=$(printf '%s\n' "$CHANGED" | sed '/^$/d' | sort -u)
[ -n "$CHANGED" ] || { echo "no changes against origin/main — nothing to validate" >&2; exit 2; }

detect() {
  local lanes=""
  printf '%s\n' "$CHANGED" | grep -qE '^(app/|llama-android/|third_party/|build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle/|gradlew|gradlew\.bat|\.gitmodules)' && lanes+="Code "
  printf '%s\n' "$CHANGED" | grep -qE '^accelerator-benchmark/' && lanes+="Benchmark "
  printf '%s\n' "$CHANGED" | grep -qE '^\.github/' && lanes+="CI/workflow "
  printf '%s\n' "$CHANGED" | grep -qE '^(\.claude/|docs/|CLAUDE\.md|scripts/)' && lanes+="Docs/dev-tooling "
  printf '%s' "$lanes"
}

DETECTED=$(detect)
[ -n "$DETECTED" ] || { echo "changed files matched no lane — add a glob to FACT: lanes" >&2; exit 2; }
PRIMARY=$(printf '%s' "$DETECTED" | awk '{print $1}')
COUNT=$(printf '%s' "$DETECTED" | wc -w | tr -d ' ')
MIXED=false; [ "$COUNT" -gt 1 ] && MIXED=true

mkdir -p "$RUN"
echo "Phase 3 run: $RUN"
echo "  detected lanes: $DETECTED"
echo "  primary: $PRIMARY   mixed: $MIXED"

SATISFIED=(); SKIPPED=(); SKIPNOTE=""

# ---- Code lane: the test count, from the only script allowed to produce one
if printf '%s' "$DETECTED" | grep -q Code; then
  if [ "$RUN_TESTS" -eq 1 ]; then
    if scripts/measure-tests.sh --quiet > "$RUN/unit-tests.txt" 2>&1; then
      mkdir -p "$RUN/unit-tests"; cp app/build/test-results/testDebugUnitTest/*.xml "$RUN/unit-tests/" 2>/dev/null
      cat app/build/test-results/testDebugUnitTest/*.xml > "$RUN/unit-tests.xml" 2>/dev/null
      SATISFIED+=("tests"); echo "  tests: $(cat "$RUN/unit-tests.txt")"
    else
      echo "  tests FAILED — see $RUN/unit-tests.txt" >&2
      cat "$RUN/unit-tests.txt" >&2
    fi
  else
    SKIPPED+=("tests"); SKIPNOTE+="tests: skipped by --no-tests"$'\n'
  fi
fi

# ---- Docs lane: conditional, and the condition is answered by the tool's own extractor
if printf '%s' "$DETECTED" | grep -q "Docs/dev-tooling"; then
  if scripts/check-cited-symbols.py --detect-only --base origin/main >/dev/null 2>&1; then
    if scripts/check-cited-symbols.py --base origin/main > "$RUN/cited-symbols.txt" 2>&1; then
      SATISFIED+=("cited-symbols"); echo "  cited-symbols: clean"
    else
      echo "  cited-symbols FAILED — see $RUN/cited-symbols.txt" >&2
    fi
  else
    echo "no backticked identifiers in added lines" > "$RUN/cited-symbols.txt"
    SKIPPED+=("cited-symbols"); SKIPNOTE+="cited-symbols: no backticked identifiers in added lines"$'\n'
    echo "  cited-symbols: nothing to check, obligation skipped with a reason"
  fi
fi

# ---- Obligations this script cannot satisfy on its own. Named, never silently omitted.
for pair in "Code:codex-review" "Code:hardware-uat" "Benchmark:benchmark-assemble" "CI/workflow:workflow-run"; do
  lane=${pair%%:*}; ob=${pair#*:}
  if printf '%s' "$DETECTED" | grep -q "$lane"; then
    SKIPPED+=("$ob"); SKIPNOTE+="$ob: not yet recorded — run scripts/attest.sh $ob <artifact>"$'\n'
  fi
done

printf '%s' "$SKIPNOTE" > "$RUN/skip-note.txt"

json_array() { printf '['; local first=1; for x in "$@"; do [ $first -eq 1 ] || printf ', '; printf '"%s"' "$x"; first=0; done; printf ']'; }
cat > "$RUN/run.json" <<JSON
{
  "schema_version": 1,
  "head_sha": "$HEAD_SHA",
  "branch": "$BRANCH",
  "declared_lane": "$PRIMARY",
  "detected_lanes": $(json_array $DETECTED),
  "changed_files": $(printf '%s\n' "$CHANGED" | python3 -c 'import sys,json; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))'),
  "is_mixed_pr": $MIXED,
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "obligations_satisfied": $(json_array "${SATISFIED[@]+"${SATISFIED[@]}"}"),
  "obligations_skipped": $(json_array "${SKIPPED[@]+"${SKIPPED[@]}"}")
}
JSON

echo
echo "Verifying with check-validation.sh (this script scaffolds; that one judges):"
scripts/check-validation.sh "$RUN"
