#!/usr/bin/env bash
# Create a task worktree the same way for a plain shell or a Codex session as the
# harness EnterWorktree tool does for a Claude session: one worktree per task,
# under .claude/worktrees/, on a fresh branch from origin/main.
#
# WHY. The one-worktree-per-task rule is honored by Claude sessions through the
# EnterWorktree tool. A Codex session or a plain shell has no such tool, so
# without this they either build in the main checkout (which the rule forbids) or
# invent an ad-hoc `git worktree add`. This gives them the same contract:
#   .claude/worktrees/<name>  on branch  <branch>  from origin/main.
#
# It does NOT remove worktrees. Removal is cleanup-merged-worktrees.sh, on the
# merged-PR proof. Creation and removal are separate tools on purpose.
set -uo pipefail

usage() {
    cat >&2 <<'USAGE'
usage:
  worktree-lifecycle.sh create <name> [<branch>]

create   add a worktree at .claude/worktrees/<name> on <branch> from origin/main.
         <branch> defaults to feat/<name>. <name> is one path segment:
         letters, digits, dot, underscore, dash.
USAGE
}

[ "${1:-}" = "create" ] || { usage; exit 2; }
shift
name="${1:-}"
[ -n "$name" ] || { echo "ERROR: create needs a <name>." >&2; usage; exit 2; }
case "$name" in
    */*|.|..) echo "ERROR: <name> is one path segment, not a path: '$name'." >&2; exit 2 ;;
esac
if ! printf '%s' "$name" | /usr/bin/grep -Eq '^[A-Za-z0-9._-]+$'; then
    echo "ERROR: <name> may use only letters, digits, dot, underscore, dash." >&2
    exit 2
fi
branch="${2:-feat/$name}"

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "ERROR: not inside a git repository." >&2; exit 2; }
# Resolve the MAIN checkout (the one holding the common dir's default worktree),
# so worktrees are always created as siblings under the primary checkout even
# when this is invoked from inside another worktree.
main_root=$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10); exit}')
if [ -z "$main_root" ]; then
    echo "ERROR: could not determine the primary checkout (git worktree list failed);" >&2
    echo "       refusing to guess a location and risk nesting a worktree inside another." >&2
    exit 1
fi

dest="$main_root/.claude/worktrees/$name"
if [ -e "$dest" ]; then
    echo "ERROR: $dest already exists." >&2
    exit 1
fi
if git show-ref --verify --quiet "refs/heads/$branch"; then
    echo "ERROR: branch '$branch' already exists; pick another name or delete it first." >&2
    exit 1
fi

echo "worktree-lifecycle: fetching origin…"
git -C "$main_root" fetch --prune origin >/dev/null 2>&1 || {
    echo "ERROR: git fetch failed; not creating a worktree from a stale base." >&2; exit 1; }

if git -C "$main_root" worktree add "$dest" -b "$branch" origin/main >/dev/null 2>&1; then
    echo "worktree-lifecycle: created $dest on '$branch' from origin/main."
    echo "  When its PR merges: leave it (ExitWorktree keep, or just cd out), then reclaim it with"
    echo "    scripts/cleanup-merged-worktrees.sh --apply $dest"
    exit 0
fi
echo "ERROR: could not create the worktree." >&2
exit 1
