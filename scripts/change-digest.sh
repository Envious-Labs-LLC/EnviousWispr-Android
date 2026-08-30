#!/usr/bin/env bash
# change-digest.sh — a fingerprint of the CONTENT a Phase 3 run validated.
#
# WHY head_sha IS NOT ENOUGH, and this is the hole it closes. Phase 3 runs BEFORE the commit, on purpose:
# a detector reading only committed history would classify the lane from a change set that does not exist
# yet. But that means the thing being validated is the WORKING TREE, and an uncommitted edit does not move
# HEAD. So a run directory stayed "current" while its subject changed underneath it — the same class as a
# cached test count, which check-validation.sh already refuses for a different reason.
#
# ONE OWNER, DELIBERATELY. validate-pr.sh records this and check-validation.sh recomputes it. If each
# spelled the digest itself, two answers to one question would drift and the comparison would fail on
# correct runs, which is the shape that gets a check disabled rather than fixed.
#
# Untracked files are included, and they are the half a diff cannot see: 15 of the 23 files in the
# regression that motivated this whole port were untracked.
#
# Exit: 0 and the digest on stdout · 2 the digest could not be computed, which is not a digest.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

BASE=$(git merge-base origin/main HEAD 2>/dev/null) || { echo "no merge-base with origin/main" >&2; exit 2; }

DIGEST=$({
    printf 'BASE %s\0' "$BASE"
    git diff --binary "$BASE" 2>/dev/null
    # -z and a sorted list, so filenames with spaces and a different readdir order cannot change the
    # answer for identical content.
    git ls-files --others --exclude-standard -z 2>/dev/null | sort -z | while IFS= read -r -d '' f; do
        printf '\0UNTRACKED %s\0' "$f"
        cat -- "$f" 2>/dev/null
    done
} | shasum -a 256 2>/dev/null | awk '{print $1}')

[ -n "$DIGEST" ] || { echo "digest could not be computed" >&2; exit 2; }
printf '%s\n' "$DIGEST"
