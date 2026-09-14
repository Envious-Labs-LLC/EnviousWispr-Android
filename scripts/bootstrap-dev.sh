#!/usr/bin/env bash
# Arm this checkout's guards after a fresh clone. Idempotent and safe to re-run.
#
# WHY. Two things git does not restore on a clone: the hooks path (a local config
# setting) and the PreToolUse/SessionEnd/PostToolUse registration (which lives in
# gitignored .claude/settings.json). scripts/hooks/README.md carries a tracked
# copy of that registration for exactly this reason; this script restores it.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "ERROR: not inside a git repository." >&2; exit 1; }
cd "$ROOT"

# 1. Arm the git hooks (pre-commit, reference-transaction).
git config core.hooksPath scripts/githooks
echo "bootstrap: git hooks armed (core.hooksPath=scripts/githooks)."

# 2. Restore the Claude hook registration if it is absent. NEVER overwrite an
#    existing settings.json: it may carry local edits. test-hooks.sh asserts the
#    live file and the README mirror agree, so a drift is caught there, not here.
SETTINGS=".claude/settings.json"
if [ -e "$SETTINGS" ]; then
    echo "bootstrap: $SETTINGS already exists; leaving it untouched."
else
    mkdir -p .claude
    if python3 - "$SETTINGS" <<'PY'
import json, re, sys
readme = open("scripts/hooks/README.md", encoding="utf-8").read()
m = re.search(r"```json\n(.*?)```", readme, re.S)
if not m:
    print("bootstrap: no json block found in scripts/hooks/README.md", file=sys.stderr)
    sys.exit(1)
hooks = json.loads("{" + m.group(1).strip() + "}")
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(hooks, f, indent=2)
    f.write("\n")
PY
    then
        echo "bootstrap: restored $SETTINGS from the README mirror."
    else
        echo "bootstrap: could not restore $SETTINGS; arm the hooks by hand from scripts/hooks/README.md." >&2
    fi
fi

# 3. Say what a clone still does not have, so nobody mistakes a fresh clone for a
#    fully-provisioned one.
cat <<'NOTE'
bootstrap: done. A fresh clone still lacks the gitignored operating brain:
  .claude/rules/, .claude/knowledge/, .claude/session-log.md and docs/internal/.
  These are not tracked and not backed up by git; restoring them (and where to
  back them up privately) is a founder decision, still open.
Verify the guards with: scripts/hooks/test-hooks.sh
NOTE
