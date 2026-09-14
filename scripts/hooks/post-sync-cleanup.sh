#!/usr/bin/env bash
# PostToolUse: after a successful `git fetch` / `git pull`, REPORT finished
# worktrees and gone-upstream branches. Read-only, never removes anything.
#
# WHY REPORT-ONLY HERE. A fetch/pull is a READ, issued constantly and by any of
# several concurrent sessions with nobody watching the result. The macOS repo
# learned this the hard way: a hook that REMOVED worktrees as a side effect of a
# read stranded eight of them holding 92 GB, because "is deletion safe at this
# instant" has no reliable answer at a random moment. So removal is only ever the
# explicitly-scoped `--apply`, run from wind-down or by hand. This hook only
# names candidates, so a fresh clone or a busy machine loses nothing.
#
# SILENT UNLESS THE COMMAND WAS A FETCH/PULL, and silent unless there is
# something to report (the report scripts print nothing when clean). Always
# exits 0: a reporting hook must never block a command, and must never fail one.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && pwd -P)" || exit 0

# The PostToolUse payload arrives as JSON on stdin. Pull the command text out
# with the interpreter, never a regex over the raw JSON: a command can contain
# quotes and braces that would fool a text match. A parse failure means "not a
# command I act on", so exit 0.
command -v /usr/bin/python3 >/dev/null 2>&1 || exit 0
CMD="$(/usr/bin/python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
ti=d.get("tool_input") or {}
print(ti.get("command","") or "")' 2>/dev/null)" || exit 0

[ -n "$CMD" ] || exit 0

# Only a git fetch or git pull. Match the subcommand as a word so `git fetchfoo`
# or a filename containing "pull" does not trip it. This is a cheap gate, not a
# security boundary: the report is harmless, so a false positive costs one extra
# read, and a false negative just skips one report.
case " $CMD " in
    *" git "*|*"git "*) ;;
    *) exit 0 ;;
esac
if ! printf '%s' "$CMD" | /usr/bin/grep -Eq '(^|[^[:alnum:]_])(fetch|pull)([^[:alnum:]_]|$)'; then
    exit 0
fi

command -v git >/dev/null 2>&1 || exit 0
[ -x "$ROOT/scripts/cleanup-merged-worktrees.sh" ] || exit 0

# Both reports are read-only and cheap by contract (git worktree list + git
# branch -vv, no find/du). Bound the WHOLE sequence with ONE overall deadline, so
# a wedged git cannot spend two budgets or, without `timeout`, hang unbounded.
report_seq='
"$1/scripts/cleanup-merged-worktrees.sh" --repo "$1" --report 2>/dev/null || true
if [ -x "$1/scripts/cleanup-local-branches.sh" ]; then
    ( cd "$1" && "$1/scripts/cleanup-local-branches.sh" --report 2>/dev/null ) || true
fi
'
if command -v timeout >/dev/null 2>&1; then
    timeout 20 bash -c "$report_seq" _ "$ROOT" || true
else
    bash -c "$report_seq" _ "$ROOT" || true
fi
exit 0
