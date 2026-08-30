#!/usr/bin/env bash
# change-digest.sh — a fingerprint of the CONTENT a Phase 3 run validated.
#
# WHY head_sha IS NOT ENOUGH. Phase 3 runs BEFORE the commit, on purpose: a detector reading only
# committed history would classify the lane from a change set that does not exist yet. So the thing being
# validated is the WORKING TREE, and an uncommitted edit does not move HEAD. A run directory stayed
# "current" while its subject changed underneath it — the same class as a cached test count.
#
# ASK GIT; DO NOT MODEL GIT. Two earlier versions of this script built their own answer to "what would
# git record here" — first a diff plus a list of untracked files, then a hand-rolled snapshot recording
# paths, modes, symlink targets, gitlinks and deletions. Review found a new divergence in each: staging a
# file moved the digest, then staging a DELETION moved it, then an untracked mode change was invisible,
# then a symlink was followed to its target. Those are not four defects. They are one — a private model
# of somebody else's data structure has no last divergence, and every round would have found the next.
#
# git computes this exact object already. Read HEAD into a THROWAWAY index, add the whole working tree to
# that index, and write the tree. The result is git's own content hash, so modes, symlinks, gitlinks,
# renames, deletions, binaries and the ignore rules are handled by construction rather than by anything
# written here. The throwaway index is why the real one is untouched, and why staging is invisible:
# `git add -A` into a scratch index reaches the same tree whether or not you had already staged.
#
# ONE OWNER, DELIBERATELY. validate-pr.sh records this and check-validation.sh recomputes it. Two
# spellings of one question would drift and then fail correct runs, which is the shape that gets a check
# disabled rather than fixed.
#
# Exit: 0 and the digest on stdout · 2 the digest could not be computed, which is not a digest.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

INDEX=$(mktemp) || { echo "digest could not be computed: no scratch index" >&2; exit 2; }
trap 'rm -f "$INDEX"' EXIT
rm -f "$INDEX"   # read-tree needs the file ABSENT, not empty

fail() { echo "digest could not be computed: $1" >&2; exit 2; }

export GIT_INDEX_FILE="$INDEX"
git read-tree HEAD 2>/dev/null            || fail "could not read HEAD into the scratch index"
git add -A 2>/dev/null                    || fail "could not stage the working tree into the scratch index"
TREE=$(git write-tree 2>/dev/null)        || fail "could not write the tree"
[ -n "$TREE" ]                            || fail "git write-tree produced nothing"

printf '%s\n' "$TREE"
