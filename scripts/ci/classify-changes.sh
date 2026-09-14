#!/usr/bin/env bash
# Decide whether a PR needs the full Android build or is docs-only and can skip
# it. Prints exactly "build" or "skip" on stdout.
#
# FAILS SAFE TOWARD BUILD. Anything it cannot classify with confidence — no base
# ref, a failed diff, an empty list, or a single non-docs file — prints "build".
# Skipping is only ever chosen when EVERY changed file is documentation, so a
# real code change can never be waved through by a classification miss.
set -uo pipefail

base="${1:-}"
[ -n "$base" ] || { echo build; exit 0; }

# --no-renames so a rename is reported as BOTH its deletion and its addition.
# With rename detection on, moving app/build.gradle.kts to docs/build.gradle.kts
# would print only the docs-side path and wrongly classify as skip, even though
# the app lost its build config.
files=$(git diff --no-renames --name-only "$base"...HEAD 2>/dev/null) || { echo build; exit 0; }
[ -n "$files" ] || { echo build; exit 0; }

while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
        docs/*|*.md|LICENSE) ;;   # documentation only
        *) echo build; exit 0 ;;  # anything else needs the build
    esac
done <<< "$files"

echo skip
