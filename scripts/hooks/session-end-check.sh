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
# files would have reported 8 and looked reassuring. The staged and unstaged lines OVERLAP by design —
# a file that is both is real in both columns — so the total is counted once, from the lines themselves.
#
# SILENT WHEN CLEAN, deliberately. `check-must-not-fire-on-a-clean-tree` calls the always-fires case the
# more dangerous one, because it looks like protection while training the reader to skim past it. The first
# draft printed a one-line all-clear every session; that line is gone.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null || exit 0
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0

# READ THE PORCELAIN COLUMNS, never a leading-character pattern. `grep -c '^ M'` sees an unstaged
# modification and nothing else: an unstaged DELETION (` D`), a rename, a type change and a conflict all
# went uncounted. An `MM` file was reported only as staged, hiding that it also carried unstaged changes.
# Column 1 is the index, column 2 is the working tree, and `??` is neither.
STATUS=$(git status --porcelain=v1 2>/dev/null) || exit 0
count() { printf '%s\n' "$STATUS" | awk "$1"' { n++ } END { print n+0 }'; }
DIRTY=$(count 'NF')
UNTRACKED=$(count 'substr($0,1,2)=="??"')
STAGED=$(count 'NF && substr($0,1,2)!="??" && substr($0,1,1)!=" "')
UNSTAGED=$(count 'NF && substr($0,1,2)!="??" && substr($0,2,1)!=" "')

# A branch that has NEVER been pushed has no upstream, and `@{u}` fails. Counting only against an
# upstream therefore reports 0 for the case with the most work at risk: a local branch nobody else has.
# The fallback asks the question directly — which commits are on NO remote ref — rather than comparing
# against origin/main, which counts commits already pushed under some other branch name.
UNPUSHED=0
UNPUSHED_AGAINST=""
if git rev-parse --abbrev-ref "@{u}" >/dev/null 2>&1; then
    UNPUSHED=$(git rev-list --count "@{u}"..HEAD 2>/dev/null || echo 0)
    UNPUSHED_AGAINST="its upstream"
else
    UNPUSHED=$(git rev-list --count HEAD --not --remotes 2>/dev/null || echo 0)
    UNPUSHED_AGAINST="every remote ref, because this branch has no upstream"
fi

[ "$DIRTY" -eq 0 ] && [ "$UNPUSHED" -eq 0 ] && exit 0

echo "This session is leaving work behind on \`$BRANCH\`:"
echo "  $DIRTY file(s) dirty in total"
[ "$STAGED"    -gt 0 ] && echo "    $STAGED staged"
[ "$UNSTAGED"  -gt 0 ] && echo "    $UNSTAGED with unstaged changes"
[ "$UNTRACKED" -gt 0 ] && echo "    $UNTRACKED untracked"
[ "$UNPUSHED"  -gt 0 ] && echo "  $UNPUSHED commit(s) not pushed, measured against $UNPUSHED_AGAINST"
echo
echo "workflow-process.md RULE: definition-of-done — work that exists only in the working tree is not"
echo "delivered. Measured 2026-08-29: a Codex-cleared change sat in 23 uncommitted files for six hours."
exit 0
