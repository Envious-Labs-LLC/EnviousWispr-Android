#!/usr/bin/env bash
# Local branch reaper. Removes local branches whose work has provably shipped.
#
# WHY. Merges here do not pass `--delete-branch`, and `fetch --prune` leaves the
# local branch behind when GitHub auto-deletes the remote. So finished branches
# pile up: 8 with a gone upstream were counted on 2026-09-13. This reaps them on
# the SAME proof the worktree engine uses, so it can never delete unshipped work.
#
# TWO MODES:
#   --report                 read-only. Names candidates, deletes nothing.
#   --apply <branch> [<branch>...]  deletes ONLY the branches named. Unscoped
#                            apply refuses; it is never read as "all candidates".
#
# A candidate is a local branch with a gone upstream OR one behind no other
# signal, but deletion requires a MERGED PR whose head OID equals the branch SHA.
# NEVER-DELETE: the current branch, any branch checked out in a worktree, and the
# integration branches main / internal-testing / uat/integration. A branch with
# commits not in a merged PR is reported and RETAINED; its recovery SHA is
# printed so it can always be recreated.
set -uo pipefail

GREP=/usr/bin/grep
PYTHON=/usr/bin/python3
PROTECTED_RE='^(main|internal-testing|uat/integration)$'

usage() {
    cat >&2 <<'USAGE'
usage:
  cleanup-local-branches.sh --report
  cleanup-local-branches.sh --apply <branch> [<branch>...]

--report  read-only; names candidates and deletes nothing.
--apply   deletes ONLY the branches given. At least one is required.
USAGE
}

MODE=""
declare -a TARGETS=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --report) MODE="report"; shift ;;
        --apply)  MODE="apply";  shift ;;
        -h|--help) usage; exit 0 ;;
        --*) echo "ERROR: unknown option '$1'." >&2; usage; exit 2 ;;
        *) TARGETS+=("$1"); shift ;;
    esac
done
[ -n "$MODE" ] || { echo "ERROR: one of --report or --apply is required." >&2; usage; exit 2; }
if [ "$MODE" = "apply" ] && [ "${#TARGETS[@]}" -eq 0 ]; then
    echo "ERROR: --apply requires at least one explicit branch name." >&2
    echo "       An unscoped apply is refused; it is never read as 'all candidates'." >&2
    exit 2
fi
if [ "$MODE" = "report" ] && [ "${#TARGETS[@]}" -gt 0 ]; then
    echo "ERROR: --report takes no branch names." >&2
    exit 2
fi

git rev-parse --show-toplevel >/dev/null 2>&1 || {
    echo "ERROR: not inside a git repository." >&2; exit 2; }

current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")

# Every branch checked out in ANY worktree, so the reaper never deletes a branch
# a live worktree depends on.
branches_in_worktrees() {
    git worktree list --porcelain 2>/dev/null | awk '/^branch refs\/heads\//{print substr($0,19)}'
}

# gone-upstream candidates, one per line.
gone_upstream_branches() {
    git branch -vv 2>/dev/null | "$GREP" ': gone]' \
        | sed -E 's/^[*+ ]+//' | awk '{print $1}'
}

is_protected() {
    printf '%s\n' "$1" | "$GREP" -qE "$PROTECTED_RE"
}

in_a_worktree() {
    branches_in_worktrees | "$GREP" -qxF "$1"
}

do_report() {
    local b n=0
    while IFS= read -r b; do
        [ -n "$b" ] || continue
        is_protected "$b" && continue
        [ "$b" = "$current_branch" ] && continue
        if [ "$n" -eq 0 ]; then
            echo "Local branch cleanup: branches whose remote is gone (candidates only)."
        fi
        n=$((n + 1))
        if in_a_worktree "$b"; then
            echo "  $b  (checked out in a worktree; reap the worktree first)"
        else
            echo "  $b  ($(git rev-parse --short "$b" 2>/dev/null))"
        fi
    done <<< "$(gone_upstream_branches)"
    if [ "$n" -gt 0 ]; then
        echo "  $n candidate(s). Nothing was deleted. Clear them with:"
        echo "    scripts/cleanup-local-branches.sh --apply <branch> [<branch>...]"
    fi
    return 0
}

# Returns 0 removed, 1 kept (bypass), 2 failed.
apply_one() {
    local b="$1" sha pr_json merged_count pr_oid

    if is_protected "$b"; then
        echo "SKIPPED: $b — protected branch, never reaped." >&2
        return 1
    fi
    if [ "$b" = "$current_branch" ]; then
        echo "SKIPPED: $b — currently checked out here." >&2
        return 1
    fi
    if ! git show-ref --verify --quiet "refs/heads/$b"; then
        echo "SKIPPED: $b — no such local branch." >&2
        return 1
    fi
    if in_a_worktree "$b"; then
        echo "SKIPPED: $b — checked out in a worktree; reap the worktree first." >&2
        return 1
    fi

    sha=$(git rev-parse "$b" 2>/dev/null) || sha=""
    if [ -z "$sha" ]; then
        echo "SKIPPED: $b — could not resolve to a SHA." >&2
        return 1
    fi

    # SAME AUTHORITY AS THE WORKTREE ENGINE: a MERGED PR whose head OID == this
    # branch's SHA. A failed GitHub read REFUSES.
    pr_json=$(gh pr list --head "$b" --state all --limit 5 \
                 --json number,state,headRefOid 2>/dev/null) || pr_json=""
    if [ -z "$pr_json" ]; then
        echo "SKIPPED: $b — could not read PR state (GitHub unavailable?). Recovery SHA: $sha" >&2
        return 1
    fi
    merged_count=$(printf '%s' "$pr_json" | "$PYTHON" -c '
import json,sys
try: rows=json.load(sys.stdin)
except Exception: print("ERR"); sys.exit(0)
print(sum(1 for r in rows if r.get("state")=="MERGED"))' 2>/dev/null) || merged_count="ERR"
    if [ "$merged_count" = "ERR" ] || [ -z "$merged_count" ]; then
        echo "SKIPPED: $b — could not parse PR state. Recovery SHA: $sha" >&2
        return 1
    fi
    if [ "$merged_count" -eq 0 ]; then
        echo "SKIPPED: $b — no MERGED PR. Work that never shipped is never deleted. Recovery SHA: $sha" >&2
        return 1
    fi
    if [ "$merged_count" -gt 1 ]; then
        echo "SKIPPED: $b — $merged_count merged PRs match; refusing rather than choosing. Recovery SHA: $sha" >&2
        return 1
    fi
    pr_oid=$(printf '%s' "$pr_json" | "$PYTHON" -c '
import json,sys
rows=json.load(sys.stdin)
for r in rows:
    if r.get("state")=="MERGED": print(r.get("headRefOid") or ""); break' 2>/dev/null) || pr_oid=""
    if [ "$pr_oid" != "$sha" ]; then
        echo "SKIPPED: $b — merged PR head ($pr_oid) is not this branch's SHA ($sha)." >&2
        echo "         The branch holds commits that did not ship. Recovery SHA: $sha" >&2
        return 1
    fi

    if git branch -D "$b" >/dev/null 2>&1; then
        echo "REMOVED: $b  (was $sha; restore with: git branch $b $sha)"
        return 0
    fi
    echo "FAILED: $b — could not delete. Recovery SHA: $sha" >&2
    return 2
}

case "$MODE" in
    report) do_report; exit 0 ;;
    apply)
        removed=0; kept=0; failed=0
        for t in "${TARGETS[@]}"; do
            apply_one "$t"
            case "$?" in
                0) removed=$((removed + 1)) ;;
                1) kept=$((kept + 1)) ;;
                *) failed=$((failed + 1)) ;;
            esac
        done
        echo "Local branch cleanup: $removed removed, $kept kept, $failed failed."
        [ "$failed" -eq 0 ] || exit 1
        exit 0
        ;;
esac
