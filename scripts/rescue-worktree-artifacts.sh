#!/usr/bin/env bash
# Copy authored gitignored work out of a worktree before it is deleted.
#
# WHY THIS EXISTS
# `git status --porcelain` cannot see gitignored files by design, so the dirty
# check that guards worktree removal is blind to them, and `git worktree remove`
# then deletes the whole directory. On this repo the entire operating brain is
# gitignored: `.claude/` (rules, knowledge, session logs), `docs/internal/`, and
# `.validation/` (UAT evidence). All of it is carried by no commit and no PR, so
# an ordinary cleanup would destroy it with no prompt.
#
# Ported from the macOS EnviousWispr repo, which lost three artifacts before it
# existed (a plan section, a Codex audit set, a 4,397-frame measurement corpus).
# This makes the loss impossible rather than asking anyone to remember.
#
# CONTRACT
#   usage: rescue-worktree-artifacts.sh <worktree-path> <branch> <dest-root>
#   exit 0 = everything worth keeping was copied; the caller may delete.
#   exit 1 = something was NOT copied; the caller must NOT delete.
#   exit 2 = usage error; the caller must NOT delete.
#
# The exit code is the whole point, and the ONLY failure that matters is a
# false 0 — anything that could not be inventoried, read, copied or verified
# must reach exit 1. That is why there is no `|| true` on an inventory and no
# unguarded `continue`.
set -uo pipefail

if [ "$#" -ne 3 ]; then
    echo "usage: $(basename "$0") <worktree-path> <branch> <dest-root>" >&2
    exit 2
fi

wt="$1"
branch="$2"
dest_root="$3"

if [ ! -d "$wt" ]; then
    echo "rescue: no such worktree: $wt" >&2
    exit 2
fi

# REFUSE A DESTINATION INSIDE THE WORKTREE. Rescuing into the tree that is about
# to be deleted would destroy the backup along with the originals, and the caller
# would then read this script's exit 0 as licence to delete. Resolve the FULL
# destination with `pwd -P`, which collapses `..` segments and symlink aliases,
# and refuse if it lands inside the worktree. dest_root may not exist yet, so
# CREATE it first and resolve the real thing: a `..` in a not-yet-existing path
# (e.g. /a/missing/../<wt>/x) would slip past a check of only the existing
# ancestor, then mkdir -p would place the backup inside the worktree.
wt_abs=$(cd "$wt" 2>/dev/null && pwd -P) || { echo "rescue: cannot resolve $wt" >&2; exit 2; }
if ! mkdir -p "$dest_root" 2>/dev/null; then
    echo "rescue: failed to create destination root: $dest_root" >&2
    exit 1
fi
dr_abs=$(cd "$dest_root" 2>/dev/null && pwd -P) || {
    echo "rescue: cannot resolve destination '$dest_root'" >&2; exit 1; }
case "$dr_abs/" in
    "$wt_abs"/*)
        echo "rescue: destination '$dest_root' resolves inside the worktree '$wt'; refusing so the backup is not deleted with it." >&2
        exit 1 ;;
esac

# A text artifact is never this big. A file over the cap is NOT silently
# dropped: it is reported and forces exit 1, so the worktree survives and a
# human decides. UAT evidence can be larger than a plan, so the floor is higher
# than the macOS 50 MB, but a screen recording still trips it on purpose.
MAX_FILE_KB="${RESCUE_MAX_FILE_KB:-204800}"

# The rescue contract is deliberately narrow: authored work under `docs/`,
# `.claude/` and `.validation/`, not every ignored cache in the worktree. The
# exclusions are defence in depth for regenerable output nested inside an
# allowed root (a build tree written under `.validation/`, say). Android build
# outputs dominate the disk and nobody authors inside them.
is_skipped_path() {
    case "$1" in
        build/* | */build/* | .gradle/* | */.gradle/* | \
            .cxx/* | */.cxx/* | .kotlin/* | */.kotlin/* | \
            node_modules/* | */node_modules/* | \
            .venv/* | venv/* | \
            __pycache__/* | */__pycache__/* | \
            .mypy_cache/* | .pytest_cache/* | .ruff_cache/*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

entry_manifest=$(mktemp "${TMPDIR:-/tmp}/ew-rescue-entries.XXXXXX") || exit 1
file_manifest=$(mktemp "${TMPDIR:-/tmp}/ew-rescue-files.XXXXXX") || {
    rm -f "$entry_manifest"
    exit 1
}
trap 'rm -f "$entry_manifest" "$file_manifest"' EXIT

# NUL-delimited: `-z` disables git's filename quoting, so a name containing a
# newline, tab, quote or backslash survives intact. An inventory failure is
# FATAL: an empty list is indistinguishable from "nothing to rescue", and
# reading it as the latter permits deletion.
if ! git -C "$wt" ls-files \
    --others --ignored --exclude-standard --directory -z -- docs .claude .validation \
    > "$entry_manifest" 2> /dev/null; then
    echo "rescue: failed to inventory ignored files in $wt" >&2
    exit 1
fi

if [ ! -s "$entry_manifest" ]; then
    exit 0
fi

safe_branch=$(printf '%s' "$branch" | tr '/' '-')
stamp=$(date -u +%Y%m%dT%H%M%SZ)

if ! mkdir -p "$dest_root" 2> /dev/null; then
    echo "rescue: failed to create destination root: $dest_root" >&2
    exit 1
fi
# `mktemp -d`, not a plain path: `/` becomes `-` above, so `feat/a` and `feat-a`
# collide, and the stamp is only second-precision.
if ! dest=$(mktemp -d "$dest_root/$safe_branch-$stamp.XXXXXX"); then
    echo "rescue: failed to create a unique destination" >&2
    exit 1
fi

incomplete=0
copied=0

copy_one() {
    local f="$1"
    local src="$wt/$f"
    local bytes kb kind

    is_skipped_path "$f" && return 0

    if [ -L "$src" ]; then
        kind=symlink
    elif [ -f "$src" ]; then
        kind=file
        if ! bytes=$(wc -c < "$src" 2> /dev/null); then
            printf '  rescue FAILED to read size: %q\n' "$f"
            incomplete=1
            return 0
        fi
        kb=$(((bytes + 1023) / 1024))
        if [ "$kb" -gt "$MAX_FILE_KB" ]; then
            printf '  rescue SKIPPED (%sKB > %sKB): %q\n' "$kb" "$MAX_FILE_KB" "$f"
            incomplete=1
            return 0
        fi
    else
        # A socket, fifo, or a path that vanished mid-scan. Not copyable, so not
        # provably safe to delete — report it rather than skipping quietly.
        printf '  rescue FAILED unsupported or vanished path: %q\n' "$f"
        incomplete=1
        return 0
    fi

    if ! mkdir -p "$dest/$(dirname "$f")" 2> /dev/null; then
        printf '  rescue FAILED to create directory for: %q\n' "$f"
        incomplete=1
        return 0
    fi

    # -P copies a symlink AS a symlink rather than following it into a target
    # that may sit outside the worktree.
    if ! cp -pP "$src" "$dest/$f" 2> /dev/null; then
        printf '  rescue FAILED to copy: %q\n' "$f"
        incomplete=1
        return 0
    fi

    if [ "$kind" = file ] && ! cmp -s "$src" "$dest/$f"; then
        printf '  rescue FAILED verification: %q\n' "$f"
        incomplete=1
        return 0
    fi

    copied=$((copied + 1))
}

# Fed from FILES, never a pipe: a `while read` on the right-hand side of a pipe
# runs in a subshell, so `incomplete`/`copied` set there would be discarded.
while IFS= read -r -d '' entry; do
    [ -z "$entry" ] && continue
    is_skipped_path "$entry" && continue

    if [ -d "$wt/$entry" ] && [ ! -L "$wt/$entry" ]; then
        : > "$file_manifest" || {
            incomplete=1
            continue
        }
        if ! (cd "$wt" && find "$entry" ! -type d -print0) > "$file_manifest" 2> /dev/null; then
            printf '  rescue FAILED while traversing: %q\n' "$entry"
            incomplete=1
        fi
        while IFS= read -r -d '' f; do
            copy_one "$f"
        done < "$file_manifest"
    else
        copy_one "$entry"
    fi
done < "$entry_manifest"

if [ "$copied" -gt 0 ]; then
    echo "Worktree cleanup: rescued $copied gitignored file(s) from '$branch' -> $dest"
fi

exit "$incomplete"
