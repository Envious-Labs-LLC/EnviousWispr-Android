#!/usr/bin/env bash
# SessionEnd: say what this session is about to leave behind. Silent when there is nothing to say.
#
# WHY NOT SessionStart, which is what the first draft of the plan proposed. The regression that motivated
# this guard was 23 uncommitted ship-path files on `main` — and the tree was CLEAN at minute one and went
# dirty during the session. A SessionStart hook would have looked, seen nothing, and missed the entire
# thing. The evidence licensed an edit-time guard and a session-END guard; it never licensed the one that
# was proposed for it.
#
# UNTRACKED FILES COUNT. 15 of those 23 were untracked. A leftovers check reading only modified tracked
# files would have reported 8 and looked reassuring.
#
# SILENT WHEN CLEAN, deliberately. `check-must-not-fire-on-a-clean-tree` calls the always-fires case the
# more dangerous one, because it looks like protection while training the reader to skim past it. The first
# draft printed a one-line all-clear every session; that line is gone.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null || exit 0
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0
MODIFIED=$(git status --porcelain 2>/dev/null | grep -c '^ M' || true)
UNTRACKED=$(git status --porcelain 2>/dev/null | grep -c '^??' || true)
STAGED=$(git status --porcelain 2>/dev/null | grep -c '^[MARD]' || true)
UNPUSHED=0
if git rev-parse --abbrev-ref "@{u}" >/dev/null 2>&1; then
    UNPUSHED=$(git rev-list --count "@{u}"..HEAD 2>/dev/null || echo 0)
fi

TOTAL=$((MODIFIED + UNTRACKED + STAGED))
[ "$TOTAL" -eq 0 ] && [ "$UNPUSHED" -eq 0 ] && exit 0

echo "This session is leaving work behind on \`$BRANCH\`:"
[ "$MODIFIED"  -gt 0 ] && echo "  $MODIFIED modified"
[ "$STAGED"    -gt 0 ] && echo "  $STAGED staged"
[ "$UNTRACKED" -gt 0 ] && echo "  $UNTRACKED untracked"
[ "$UNPUSHED"  -gt 0 ] && echo "  $UNPUSHED commit(s) not pushed"
echo
echo "workflow-process.md RULE: definition-of-done — work that exists only in the working tree is not"
echo "delivered. Measured 2026-08-29: a Codex-cleared change sat in 23 uncommitted files for six hours."
exit 0
