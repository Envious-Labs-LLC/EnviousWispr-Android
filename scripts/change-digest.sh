#!/usr/bin/env bash
# change-digest.sh — a fingerprint of the CONTENT a Phase 3 run validated.
#
# WHY head_sha IS NOT ENOUGH, and this is the hole it closes. Phase 3 runs BEFORE the commit, on purpose:
# a detector reading only committed history would classify the lane from a change set that does not exist
# yet. But that means the thing being validated is the WORKING TREE, and an uncommitted edit does not move
# HEAD. So a run directory stayed "current" while its subject changed underneath it — the same class as a
# cached test count, which check-validation.sh already refuses for a different reason.
#
# IT IS A SNAPSHOT, NOT A DIFF, AND THAT IS THE WHOLE DESIGN. The first version hashed `git diff` plus the
# untracked files listed separately. Staging a file moved it from one half to the other with its content
# untouched, so `git add` alone changed the digest — and `git add` is the NEXT STEP of the normal Phase 3
# progression. A freshness check that fires on the ordinary route to a commit is a check that gets
# disabled rather than fixed, which is the failure this whole port exists to avoid.
#
# So it enumerates the working tree once, tracked and untracked together, and records what GIT would
# store: the path, the git-relevant mode, and the content. Staged or not is not part of that, so the
# digest is unchanged by `git add` and moves for every change git could record — content, path, the
# executable bit, a symlink's target, a deletion, a rename, a binary file, a submodule pointer.
#
# ONE OWNER, DELIBERATELY. validate-pr.sh records this and check-validation.sh recomputes it. If each
# spelled the digest itself, two answers to one question would drift and the comparison would fail on
# correct runs.
#
# Untracked files are included, and they are the half a diff cannot see: 15 of the 23 files in the
# regression that motivated this whole port were untracked. Ignored files are deliberately excluded, the
# same population git itself reports.
#
# Exit: 0 and the digest on stdout · 2 the digest could not be computed, which is not a digest.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2

exec python3 - "$@" <<'PY'
import hashlib
import os
import subprocess
import sys


def die(message):
    print(f"digest could not be computed: {message}", file=sys.stderr)
    sys.exit(2)


def git(*args):
    """Every git call is checked. A half-failed enumeration that still hashes SOMETHING is the shape this
    script must never take: it returns a well-formed digest describing an unknown subset."""
    result = subprocess.run(("git",) + args, capture_output=True)
    if result.returncode != 0:
        die(f"git {' '.join(args)} exited {result.returncode}: "
            f"{result.stderr.decode('utf-8', 'replace').strip()[:200]}")
    return result.stdout


base = git("merge-base", "origin/main", "HEAD").decode().strip()
if not base:
    die("no merge-base with origin/main")

# --cached AND --others in ONE listing, so a path's presence here does not depend on whether it is staged.
raw = git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
paths = sorted({p for p in raw.split(b"\0") if p})

digest = hashlib.sha256()
digest.update(b"BASE " + base.encode() + b"\0")

for path in paths:
    name = path.decode("utf-8", "surrogateescape")
    digest.update(b"PATH " + path + b"\0")
    try:
        info = os.lstat(name)
    except FileNotFoundError:
        # A tracked path deleted from the working tree is a change, and one that reads as "nothing to
        # hash" unless it is recorded explicitly.
        digest.update(b"DELETED\0")
        continue
    except OSError as exc:
        die(f"could not stat {name}: {exc}")

    if os.path.islink(name):
        # The link's own target, never the bytes it points at. Repointing a symlink at a different file
        # with identical contents is a change git would store, and following the link hides it.
        try:
            digest.update(b"SYMLINK " + os.readlink(name).encode("utf-8", "surrogateescape") + b"\0")
        except OSError as exc:
            die(f"could not read the symlink {name}: {exc}")
        continue

    if os.path.isdir(name):
        # A gitlink: the submodule's commit is the content, and the directory below it is not ours.
        head = subprocess.run(("git", "-C", name, "rev-parse", "HEAD"), capture_output=True)
        if head.returncode != 0:
            die(f"could not read the submodule HEAD at {name}")
        digest.update(b"GITLINK " + head.stdout.strip() + b"\0")
        continue

    # Git records exactly one bit of mode for a regular file.
    digest.update(b"MODE 755\0" if info.st_mode & 0o111 else b"MODE 644\0")
    try:
        with open(name, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
    except OSError as exc:
        die(f"could not read {name}: {exc}")

print(digest.hexdigest())
PY
