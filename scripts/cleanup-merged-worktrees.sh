#!/usr/bin/env bash
# Worktree lifecycle owner. SOLE remover of merged worktrees and their local
# branches on EnviousWispr-Android.
#
# WHY THIS EXISTS AS A SEPARATE SCRIPT (ported from the macOS EnviousWispr repo,
# adapted for Android). The repo's per-task rule creates a worktree via the
# harness EnterWorktree tool and expects ExitWorktree to remove it after merge.
# Nothing enforces that: a Codex session, a plain shell, or a crashed/compacted
# session leaves the worktree behind with no backstop. That is exactly how a
# merged worktree and two empty leftover folders were found stranded on
# 2026-09-13. A scripted engine catches orphans regardless of whether a session
# remembered to clean up.
#
# TWO MODES, and the difference is the whole point:
#   --report                  read-only. Never mutates anything. Safe on a read.
#   --apply <path> [<path>…]  destructive, and ONLY for the paths named.
#
# AN UNSCOPED APPLY REFUSES. Explicit path scope is the ownership boundary: the
# caller (wind-down, or a human) names the exact worktree to reclaim. The engine
# never expands "apply" to "every candidate".
#
# DIVISION OF LABOR WITH THE HARNESS. The harness owns the ACTIVE worktree of a
# live session. The safe hand-off is: the session calls
# ExitWorktree({action:"keep"}) to release ownership and RETAIN the directory,
# then this engine removes it from outside. This engine refuses to remove the
# worktree it is being run from (the self-guard below), so it can never delete a
# tree still in use by its own caller.
#
# AN ABANDONED, UNMERGED WORKTREE IS REPORTED AND RETAINED, NEVER DELETED. It
# cannot pass the merged-PR guard, and nothing here may delete work that never
# shipped. The `llama.cpp` submodule makes `git worktree remove` refuse EVERY tree
# whose HEAD lists the gitlink, deinitialised or not ("working trees containing
# submodules cannot be moved or removed"). That one objection is answered by our
# own checks (clean tree, no lock, proven merged) and then `--force`; every other
# refusal is honored, so an unsafe tree is retained, not forced.

set -uo pipefail

usage() {
    cat >&2 <<'USAGE'
usage:
  cleanup-merged-worktrees.sh --report [--repo <path>]
  cleanup-merged-worktrees.sh --apply <worktree-path> [<worktree-path>...] [--repo <path>]

--report  read-only; names candidates and deletes nothing.
--apply   removes ONLY the worktree paths given. At least one is required;
          an unscoped apply is refused, never interpreted as "all".
--repo    repository to operate on. Defaults to the repo containing the CWD.
USAGE
}

GREP=/usr/bin/grep
PYTHON=/usr/bin/python3

# The directory the operator invoked from, captured BEFORE any cd. The self-guard
# uses it to refuse deleting the worktree the caller is standing in.
CALLER_PWD=$(pwd -P 2>/dev/null || echo "")

MODE=""
REPO=""
declare -a TARGETS=()

while [ "$#" -gt 0 ]; do
    case "$1" in
        --report) MODE="report"; shift ;;
        --apply)  MODE="apply";  shift ;;
        --repo)
            [ "$#" -ge 2 ] || { echo "ERROR: --repo needs a path." >&2; exit 2; }
            REPO="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        --*) echo "ERROR: unknown option '$1'." >&2; usage; exit 2 ;;
        *) TARGETS+=("$1"); shift ;;
    esac
done

[ -n "$MODE" ] || { echo "ERROR: one of --report or --apply is required." >&2; usage; exit 2; }

# THE REFUSAL THAT MAKES THIS SAFE. An apply with no paths is an ERROR, never
# "every candidate". A wrong refusal costs a rerun; a wrong sweep costs another
# session's work.
if [ "$MODE" = "apply" ] && [ "${#TARGETS[@]}" -eq 0 ]; then
    echo "ERROR: --apply requires at least one explicit worktree path." >&2
    echo "       An unscoped apply is refused; it is never read as 'all candidates'." >&2
    exit 2
fi
if [ "$MODE" = "report" ] && [ "${#TARGETS[@]}" -gt 0 ]; then
    echo "ERROR: --report takes no paths." >&2
    exit 2
fi

if [ -z "$REPO" ]; then
    REPO=$(git rev-parse --show-toplevel 2>/dev/null) || {
        echo "ERROR: not inside a git repository and no --repo given." >&2; exit 2; }
fi

# Resolve relative --apply targets against the INVOCATION directory BEFORE the cd
# into the repo. A later cd would silently re-anchor a relative path to $REPO and
# select a different worktree than the caller named.
if [ "$MODE" = "apply" ]; then
    declare -a _resolved=()
    for _t in "${TARGETS[@]}"; do
        case "$_t" in
            /*) _resolved+=("$_t") ;;
            *)  _resolved+=("${CALLER_PWD:-$PWD}/$_t") ;;
        esac
    done
    TARGETS=("${_resolved[@]}")
fi

cd "$REPO" || { echo "ERROR: cannot enter repo '$REPO'." >&2; exit 2; }

# The primary checkout, resolved once. Rescued gitignored work is written HERE,
# never under a target worktree — a rescue destination inside the tree being
# deleted would be destroyed along with the originals.
MAIN_ROOT=$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10); exit}')
[ -n "$MAIN_ROOT" ] || MAIN_ROOT="$REPO"
MAIN_ROOT=$(cd "$MAIN_ROOT" 2>/dev/null && pwd -P || echo "$MAIN_ROOT")

# Branch checked out in a given worktree path, via --porcelain (path-safe).
branch_for_worktree() {
    git worktree list --porcelain 2>/dev/null | awk -v want="$1" '
        /^worktree /       { w = substr($0, 10) }
        /^branch refs\/heads\// { if (w == want) { print substr($0, 19); exit } }
    '
}

# THREE-VALUED: 0 registered, 1 not registered, 2 COULD NOT TELL.
# A failed `git worktree list` must NOT collapse to "not registered", because the
# recovery path below treats "not registered" as a reason to refuse — so a
# genuine read failure must be its own value, not a false negative that could be
# misread elsewhere.
worktree_registration_state() {
    local wt="$1" listing rc
    if ! listing=$(git worktree list --porcelain 2>/dev/null); then
        return 2
    fi
    awk -v want="$wt" '
        /^worktree / && substr($0, 10) == want { found = 1 }
        END { exit(found ? 0 : 1) }
    ' <<< "$listing"
    rc=$?
    case "$rc" in
        0|1) return "$rc" ;;
        *)   return 2 ;;
    esac
}

# True when `git worktree remove` refused ONLY because the tree lists a submodule
# and our own checks find nothing else in the way: no lock; no modified, staged or
# untracked file (the status flags are PINNED so a `status.showUntrackedFiles=no`
# or `ignore = all` in some config cannot hide work; ignored files such as build
# output do not count, git itself ignores them for this refusal); and no submodule
# repository under the worktree's `modules/` holding anything of its own (a local
# branch, a stash, or a HEAD that is not the parent's pinned gitlink), because
# `--force` deletes those repositories with the tree. Any other refusal text, or
# any read that fails, is false. Codex review 2026-09-18 reproduced both holes.
submodule_only_refusal() {
    local wt="$1" err="$2" dirty
    case "$err" in
        *"working trees containing submodules cannot be moved or removed"*) ;;
        *) return 1 ;;
    esac
    worktree_is_locked "$wt" && return 1
    dirty=$(git -c status.showUntrackedFiles=all -C "$wt" status --porcelain \
        --untracked-files=all --ignore-submodules=none 2>/dev/null) || return 1
    [ -z "$dirty" ] || return 1
    modules_hold_nothing_of_their_own "$wt"
}

# Every repository STORED under the worktree's `modules/` (enumerated from disk,
# never from the index, so a repository left by a removed or renamed submodule is
# seen too): it must belong to a current gitlink, have no local branch, no stash
# ref, and a HEAD exactly the pinned gitlink. Then every object it holds came from
# a fetch and is lost by nothing. Any repository this cannot vouch for refuses.
modules_hold_nothing_of_their_own() {
    local wt="$1" gitdir moddir listing headfile mod rel sha heads stashes head
    gitdir=$(git -C "$wt" rev-parse --absolute-git-dir 2>/dev/null) || return 1
    moddir="$gitdir/modules"
    [ -d "$moddir" ] || return 0
    listing=$(git -C "$wt" ls-files -s 2>/dev/null) || return 1
    # A repository is a directory holding HEAD, config and objects; a HEAD under
    # logs/ or refs/ belongs to such a repository and is not one itself.
    while IFS= read -r headfile; do
        [ -n "$headfile" ] || continue
        mod=$(dirname "$headfile")
        { [ -f "$mod/config" ] && [ -d "$mod/objects" ]; } || return 1
        rel=${mod#"$moddir"/}
        sha=$(awk -v p="$rel" '$1 == "160000" && $4 == p { print $2 }' <<< "$listing")
        [ -n "$sha" ] || return 1
        heads=$(git --git-dir="$mod" for-each-ref --format='%(refname)' refs/heads 2>/dev/null) || return 1
        [ -z "$heads" ] || return 1
        # `stash list` needs a work tree; the ref itself answers on a bare git dir too.
        stashes=$(git --git-dir="$mod" for-each-ref --format='%(refname)' refs/stash 2>/dev/null) || return 1
        [ -z "$stashes" ] || return 1
        head=$(git --git-dir="$mod" rev-parse --verify HEAD 2>/dev/null) || return 1
        [ "$head" = "$sha" ] || return 1
    done < <(find "$moddir" -type f -name HEAD -not -path '*/logs/*' -not -path '*/refs/*' 2>/dev/null)
    return 0
}

worktree_is_locked() {
    git worktree list --porcelain 2>/dev/null | awk -v want="$1" '
        /^worktree / { w = (substr($0, 10) == want) }
        /^locked/    { if (w) { found = 1 } }
        END { exit(found ? 0 : 1) }
    '
}

# ─── report mode ──────────────────────────────────────────────────────────────
# READ-ONLY AND CHEAP BY CONTRACT. Runs from the PostToolUse hook on every
# successful `git fetch` / `git pull` under a 15-second budget, so it does only
# `git worktree list` and `git branch -vv` (each well under 0.1 s) plus a shallow
# scan of `.claude/worktrees/`. It never runs `find`/`du` over a candidate.
do_report() {
    local gone_list line branch wt n=0
    gone_list=$(git branch -vv 2>/dev/null | "$GREP" ': gone]' || true)

    if [ -n "$gone_list" ]; then
        while IFS= read -r line; do
            [ -n "$line" ] || continue
            branch=$(printf '%s' "$line" | sed -E 's/^[*+ ]+//' | awk '{print $1}')
            [ -n "$branch" ] || continue
            wt=$(git worktree list --porcelain 2>/dev/null | awk -v br="refs/heads/$branch" '
                /^worktree / { w = substr($0, 10) }
                /^branch /   { if ($2 == br) { print w; exit } }')
            if [ "$n" -eq 0 ]; then
                echo "Worktree cleanup: finished branches whose remote is gone."
            fi
            n=$((n + 1))
            if [ -n "$wt" ]; then
                echo "  $branch  ->  $wt"
            else
                echo "  $branch  (no worktree)"
            fi
        done <<< "$gone_list"

        echo "  $n branch candidate(s). Nothing was deleted. Clear a worktree with:"
        echo "    scripts/cleanup-merged-worktrees.sh --apply <path> [<path>...]"
        echo "  A branch with no worktree: scripts/cleanup-local-branches.sh --apply <branch>."
    fi

    # UNREGISTERED CHILD FOLDERS under .claude/worktrees/. These are the empty
    # `.gradle`-only leftovers found on 2026-09-13: directories that git does not
    # know as worktrees. The shape (e.g. only a `.gradle` cache) PROVES NOTHING,
    # so they are reported for manual inspection and NEVER swept: apply mode
    # refuses any path that is not a registered worktree.
    local wtroot registered child m=0
    wtroot="$REPO/.claude/worktrees"
    if [ -d "$wtroot" ]; then
        registered=$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10)}')
        for child in "$wtroot"/*; do
            [ -d "$child" ] || continue
            local rp
            rp=$(cd "$child" 2>/dev/null && pwd -P) || continue
            if ! printf '%s\n' "$registered" | "$GREP" -qxF "$rp"; then
                if [ "$m" -eq 0 ]; then
                    echo "Worktree cleanup: folders under .claude/worktrees/ that git does not track as worktrees."
                fi
                m=$((m + 1))
                echo "  $rp  (unregistered; inspect by hand, do not sweep)"
            fi
        done
        [ "$m" -gt 0 ] && echo "  $m unregistered folder(s). Inspect, then remove by hand if truly empty."
    fi
    return 0
}

# ─── apply mode ───────────────────────────────────────────────────────────────
# Returns 0 when the path is gone, 1 when it was deliberately KEPT (a bypass),
# 2 when removal was attempted and FAILED. The caller distinguishes them.
apply_one() {
    local wt="$1"
    local branch sha pr_oid wt_status status_rc remove_rc registration_rc

    # 1. RESOLVE THE PATH FIRST. Every later comparison is against git's own
    #    resolved spelling, so a caller passing a symlinked or relative path is
    #    compared correctly.
    if ! wt=$(cd "$wt" 2>/dev/null && pwd -P); then
        echo "SKIPPED: $1 — path does not exist or is not a directory." >&2
        return 1
    fi

    # 2. SELF-GUARD. Never remove the worktree the caller is standing in, and
    #    never one that CONTAINS the caller directory. With harness ownership the
    #    active session runs inside its worktree; deleting it (or an ancestor of
    #    it) pulls the ground out from under the caller. This does NOT fail open:
    #    the git-toplevel check is a nicety, but the physical-containment test
    #    below is what actually decides, so a failed or nested git lookup cannot
    #    turn the guard off.
    if [ -n "$CALLER_PWD" ]; then
        local caller_wt
        caller_wt=$(git -C "$CALLER_PWD" rev-parse --show-toplevel 2>/dev/null || echo "")
        if [ -n "$caller_wt" ]; then
            caller_wt=$(cd "$caller_wt" 2>/dev/null && pwd -P || echo "$caller_wt")
            if [ "$caller_wt" = "$wt" ]; then
                echo "SKIPPED: $wt — this is the worktree you are running from; leave it first (ExitWorktree keep)." >&2
                return 1
            fi
        fi
        # Physical containment: the caller directory is the target or sits inside
        # it. The trailing slashes stop a sibling whose name merely starts the
        # same (".../wt2" is not inside ".../wt") from matching.
        case "$CALLER_PWD/" in
            "$wt"/*)
                echo "SKIPPED: $wt — you are standing inside this worktree; leave it first (ExitWorktree keep)." >&2
                return 1 ;;
        esac
    fi

    # 3. REGISTRATION. An unregistered directory is refused whatever it looks
    #    like — shape, count, pathname and stale state are all rejected as
    #    evidence. This is what keeps the `.gradle`-only leftovers safe.
    worktree_registration_state "$wt"
    case "$?" in
        0) ;;
        1) echo "SKIPPED: $wt — not a registered worktree. Refusing to delete by shape." >&2
           return 1 ;;
        *) echo "SKIPPED: $wt — could not determine worktree registration." >&2
           return 1 ;;
    esac

    if worktree_is_locked "$wt"; then
        echo "SKIPPED: $wt — worktree is locked." >&2
        return 1
    fi

    branch=$(branch_for_worktree "$wt")
    if [ -z "$branch" ]; then
        echo "SKIPPED: $wt — detached HEAD or no branch; no lifecycle identity." >&2
        return 1
    fi

    # Resolve the BRANCH ref explicitly (refs/heads/), never the bare name: a
    # same-named lightweight tag pointing at the merged commit could otherwise
    # satisfy the proof while the branch itself carries unshipped commits.
    sha=$(git rev-parse --verify "refs/heads/$branch" 2>/dev/null) || sha=""
    if [ -z "$sha" ]; then
        echo "SKIPPED: $wt — could not resolve 'refs/heads/$branch' to a SHA." >&2
        return 1
    fi

    # 4. MERGEDNESS FROM THE AUTHORITY, NOT THE PROXY.
    #    A SQUASH merge writes a NEW commit, so `merge-base --is-ancestor` answers
    #    NO for work that IS shipped — the dangerous direction. The authority is a
    #    MERGED PR whose head OID equals this branch's SHA. A failed GitHub read
    #    REFUSES: unproven is not merged.
    local pr_json merged_count
    pr_json=$(gh pr list --head "$branch" --state all --limit 5 \
                 --json number,state,headRefOid 2>/dev/null) || pr_json=""
    if [ -z "$pr_json" ]; then
        echo "SKIPPED: $wt — could not read PR state for '$branch' (GitHub unavailable?)." >&2
        return 1
    fi
    merged_count=$(printf '%s' "$pr_json" | "$PYTHON" -c '
import json,sys
try: rows=json.load(sys.stdin)
except Exception: print("ERR"); sys.exit(0)
print(sum(1 for r in rows if r.get("state")=="MERGED"))' 2>/dev/null) || merged_count="ERR"
    if [ "$merged_count" = "ERR" ] || [ -z "$merged_count" ]; then
        echo "SKIPPED: $wt — could not parse PR state for '$branch'." >&2
        return 1
    fi
    if [ "$merged_count" -eq 0 ]; then
        echo "SKIPPED: $wt — no MERGED PR for '$branch'. Work that never shipped is never deleted." >&2
        return 1
    fi
    if [ "$merged_count" -gt 1 ]; then
        echo "SKIPPED: $wt — $merged_count merged PRs match '$branch'; refusing rather than choosing." >&2
        return 1
    fi
    pr_oid=$(printf '%s' "$pr_json" | "$PYTHON" -c '
import json,sys
rows=json.load(sys.stdin)
for r in rows:
    if r.get("state")=="MERGED": print(r.get("headRefOid") or ""); break' 2>/dev/null) || pr_oid=""
    if [ "$pr_oid" != "$sha" ]; then
        echo "SKIPPED: $wt — merged PR head ($pr_oid) is not this branch's SHA ($sha)." >&2
        echo "         The worktree holds commits that did not ship." >&2
        return 1
    fi

    # 5. CLEANLINESS IS THREE-VALUED: clean, dirty, or COULD NOT TELL. Only a
    #    SUCCESSFUL empty status passes; a git that exits nonzero with empty
    #    stdout must not read as clean.
    if wt_status=$(git -C "$wt" status --porcelain 2>/dev/null); then
        if [ -n "$wt_status" ]; then
            echo "SKIPPED: $wt — uncommitted changes present." >&2
            return 1
        fi
    else
        status_rc=$?
        echo "SKIPPED: $wt — could not determine cleanliness (git status exited $status_rc)." >&2
        return 1
    fi

    # 6. RESCUE GITIGNORED WORK, IMMEDIATELY BEFORE DELETION. `git status` cannot
    #    see gitignored files, so `.claude/`, `docs/internal/` and `.validation/`
    #    work carried by no commit is invisible to step 5. A PARTIAL RESCUE SKIPS
    #    THE REMOVAL.
    if ! "$(dirname "${BASH_SOURCE[0]}")/rescue-worktree-artifacts.sh" \
            "$wt" "$branch" "$MAIN_ROOT/.claude/_rescued-worktrees"; then
        echo "SKIPPED: $wt — could not fully rescue gitignored files. Keeping it so nothing is lost." >&2
        return 1
    fi

    # 7. RE-VERIFY THE TARGET'S OWN IDENTITY, then ORDINARY REMOVE, ONCE. NEVER
    #    --force. Between the proof and here the target could have switched to a
    #    different (unmerged) branch, or the branch could have moved, so checking
    #    only the captured NAME is not enough: verify the target worktree's ACTUAL
    #    symbolic HEAD is still refs/heads/$branch AND its HEAD is still $sha. A
    #    failed read refuses. `git worktree remove` then performs git's own final
    #    dirty-tree, submodule and lock refusal (honoring the `llama.cpp` submodule
    #    restriction); `--force` bypasses exactly that.
    local cur_ref cur_head
    cur_ref=$(git -C "$wt" symbolic-ref --quiet HEAD 2>/dev/null) || cur_ref=""
    cur_head=$(git -C "$wt" rev-parse HEAD 2>/dev/null) || cur_head=""
    if [ "$cur_ref" != "refs/heads/$branch" ] || [ "$cur_head" != "$sha" ]; then
        echo "SKIPPED: $wt — its HEAD is no longer '$branch' at $sha (now ${cur_ref:-?} at ${cur_head:-?}); refusing. Recovery SHA: $sha" >&2
        return 1
    fi
    remove_rc=0
    remove_err=$(git worktree remove "$wt" 2>&1 >/dev/null) || remove_rc=$?
    if [ "$remove_rc" -ne 0 ]; then
        if submodule_only_refusal "$wt" "$remove_err"; then
            # The gitlink is git's ONLY objection: the tree is clean, unlocked, its
            # branch is merged on GitHub and its HEAD is the proven SHA. --force here
            # overrides exactly that objection and nothing else (2026-09-18).
            remove_rc=0
            git worktree remove --force "$wt" || remove_rc=$?
        else
            printf '%s\n' "$remove_err" >&2
        fi
    fi

    # 8. SAME-PROCESS OBSERVATION, AND UNEXPECTED LEFTOVERS ARE RETAINED. Once git
    #    has unregistered the tree, any files that remain cannot be PROVEN to be
    #    the removed tree's rather than a replacement created in the window, so a
    #    blind delete could destroy unrelated work. macOS completed such a
    #    leftover with a bounded delete; here the safer choice is to retain it and
    #    let a human look, because the payoff of auto-completing a rare partial
    #    removal is not worth the tail risk of deleting a replacement.
    worktree_registration_state "$wt"
    registration_rc=$?
    if [ "$registration_rc" -eq 2 ]; then
        echo "FAILED: $wt — removal ran, but registration could not be read. Keeping '$branch' at $sha." >&2
        return 2
    fi
    if [ "$registration_rc" -eq 0 ]; then
        echo "FAILED: $wt — removal refused or failed; still registered (submodule, lock or dirty tree?). Keeping '$branch' at $sha." >&2
        return 2
    fi
    if [ -e "$wt" ] || [ -L "$wt" ]; then
        echo "FAILED: $wt — git unregistered the tree but files remain; retaining them rather than a blind delete. Inspect $wt by hand. Keeping '$branch' at $sha." >&2
        return 2
    fi

    # 9a. CHECKED-OUT-BRANCH PROTECTION, which `update-ref -d` lacks (unlike
    #     `git branch -D`). After removing this worktree the branch should be free
    #     (git allows a branch in at most one worktree), but a new worktree could
    #     have claimed it in the window. Re-check and refuse if it is checked out
    #     anywhere, or if the worktree list cannot be read — a failed read is not
    #     "not checked out".
    local wt_listing wtl_rc
    wt_listing=$(git worktree list --porcelain 2>/dev/null); wtl_rc=$?
    if [ "$wtl_rc" -ne 0 ]; then
        echo "FAILED: $wt — directory is gone but '$branch' checkout state could not be read; not deleting the ref. Recovery SHA: $sha" >&2
        return 2
    fi
    if printf '%s\n' "$wt_listing" | awk '/^branch refs\/heads\//{print substr($0,19)}' | "$GREP" -qxF "$branch"; then
        echo "FAILED: $wt — '$branch' is checked out in another worktree; not deleting the ref. Recovery SHA: $sha" >&2
        return 2
    fi

    # 9b. ATOMIC BRANCH DELETE against the proven SHA. `update-ref -d <ref> <old>`
    #     deletes ONLY if the ref still points at <old>, so a branch that moved
    #     after verification is never deleted. The recovery SHA is printed either
    #     way. No unscoped `worktree prune` — removing this one tree is the whole
    #     job, and a repo-wide prune could unregister unrelated missing worktrees.
    if git update-ref -d "refs/heads/$branch" "$sha" 2>/dev/null; then
        echo "REMOVED: $wt  ('$branch' at $sha; restore the branch with: git branch $branch $sha)"
        return 0
    fi
    echo "FAILED: $wt — directory is gone but '$branch' was not at $sha or could not be deleted. Recovery SHA: $sha" >&2
    return 2
}

case "$MODE" in
    report) do_report; exit 0 ;;
    apply)
        removed=0; kept=0; failed=0
        for target in "${TARGETS[@]}"; do
            apply_one "$target"
            case "$?" in
                0) removed=$((removed + 1)) ;;
                1) kept=$((kept + 1)) ;;
                *) failed=$((failed + 1)) ;;
            esac
        done
        echo "Worktree cleanup: $removed removed, $kept kept, $failed failed."
        [ "$failed" -eq 0 ] || exit 1
        exit 0
        ;;
esac
