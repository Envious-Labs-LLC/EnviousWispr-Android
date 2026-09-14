#!/usr/bin/env bash
# Bring the local checkout's main up to origin/main, safely, for a plain shell or
# a Codex session that has no editor hook to do it.
#
# WHY. After a merge, local main drifts behind origin (23 commits behind on
# 2026-09-13). The macOS repo fast-forwards main after every merge; a Claude
# PostToolUse hook cannot cover a fetch a plain shell runs, so this is the
# scripted equivalent any session can call.
#
# IT ONLY EVER FAST-FORWARDS. `merge --ff-only` refuses if the local branch has
# diverged, so this can never create a merge commit or move past local work. It
# never resets and never stashes: setting work aside is the caller's decision,
# not a sync's.
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "ERROR: not inside a git repository." >&2; exit 2; }
cd "$ROOT" || exit 2

DEFAULT_BRANCH=main
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")

echo "sync-main: fetching origin (prune)…"
if ! git fetch --prune origin >/dev/null 2>&1; then
    echo "ERROR: git fetch failed; leaving everything as it is." >&2
    exit 1
fi

if [ "$branch" != "$DEFAULT_BRANCH" ]; then
    echo "sync-main: this checkout is on '$branch', not '$DEFAULT_BRANCH'. Fetched only; nothing merged."
    echo "          main is checked out in another worktree; run this from there to fast-forward it."
    exit 0
fi

status=$(git status --porcelain 2>/dev/null) || {
    echo "ERROR: could not read working-tree state; nothing merged." >&2; exit 1; }
if [ -n "$status" ]; then
    echo "sync-main: '$DEFAULT_BRANCH' has uncommitted changes; refusing to merge. Commit or set them aside first."
    exit 1
fi

before=$(git rev-parse HEAD 2>/dev/null || echo "")
if git merge --ff-only "origin/$DEFAULT_BRANCH" >/dev/null 2>&1; then
    after=$(git rev-parse HEAD 2>/dev/null || echo "")
    if [ "$before" = "$after" ]; then
        echo "sync-main: already up to date at $after."
    else
        echo "sync-main: fast-forwarded '$DEFAULT_BRANCH' $before -> $after."
    fi
    exit 0
fi
echo "sync-main: '$DEFAULT_BRANCH' has diverged from origin; a fast-forward is not possible."
echo "          Refusing rather than creating a merge commit. Reconcile by hand."
exit 1
