#!/usr/bin/env python3
"""The Bash umbrella: the commit gate, plus the write shapes an Edit/Write matcher cannot see.

THREAT MODEL, carried over from macOS `git_target.py` because it BOUNDS THE WORK. The only actor here is
Claude, often five or more concurrent instances, and THERE IS NO ADVERSARY. These gates enforce workflow
etiquette so cooperative agents do not corrupt `main`. The failure to prevent is the path-of-least-
resistance mistake — a plain `git commit -am` in the wrong checkout — never a deliberately obfuscated
evasion. A cooperative agent emits the DIRECT shape; it never emits `sh -c '...'` or `C=commit; git $C`,
because those are MORE effort. **Do not build an evasion-proof parser.** Matching the direct shapes is the
whole requirement, and anything beyond it is cost with no threat behind it.

TWO JOBS.

1. THE COMMIT GATE, which is the GUARANTEE. Every author — this assistant, a shell heredoc, Codex — must
   pass through `git commit` to reach history, so this is the only place that can be complete. On `main`,
   a commit whose staged set touches a ship path is denied, as is any index-bypassing flag (`-a`, `-am`,
   a pathspec), because those change what gets committed without it appearing in the index.

2. THE WRITE SHAPES, which are BEST EFFORT. Measured 2026-08-30: every file written during the session
   that designed this guard went through a Bash heredoc, including the design document itself. An
   Edit/Write matcher would have watched that happen and said nothing. So `> path`, `>> path`, `tee path`
   and `sed -i` into a ship path on `main` are denied here. This will never be complete — the set of ways
   to write a file is open — and it does not need to be, because job 1 is.

Exits 0 silently to allow; emits a deny and exits 0 to block. Fails OPEN on its own error: a broken guard
must not block every command.
"""

import json
import os
import re
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ship_paths import is_ship_path  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Flags that CHANGE WHAT GETS COMMITTED without passing through the index.
INDEX_BYPASSING = {"-a", "--all", "-am", "-am.", "--include", "-i"}


def deny(reason: str) -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                             "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


def branch() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"],
                              cwd=ROOT, capture_output=True, text=True).stdout.strip()
    except OSError:
        return ""


def staged() -> list[str]:
    try:
        out = subprocess.run(["git", "diff", "--cached", "--name-only"],
                             cwd=ROOT, capture_output=True, text=True).stdout
    except OSError:
        return []
    return [ln.strip() for ln in out.splitlines() if ln.strip()]


def check_commit(tokens: list[str], command: str) -> None:
    if branch() != "main":
        return  # the branch IS the protection

    bypass = [t for t in tokens if t in INDEX_BYPASSING or re.fullmatch(r"-[a-zA-Z]*a[a-zA-Z]*", t)]
    if bypass:
        deny(
            f"BLOCKED: `git commit {' '.join(bypass)}` on `main` bypasses the index.\n\n"
            f"It changes what gets committed without that appearing in `git diff --cached`, so the gate "
            f"cannot see what it is approving. On a branch this is fine.\n\n"
            f"  git checkout -b <type>/<issue>-<slug>"
        )

    ship = [p for p in staged() if is_ship_path(p)]
    if ship:
        listed = "\n".join(f"    {p}" for p in ship[:8])
        more = f"\n    ... and {len(ship) - 8} more" if len(ship) > 8 else ""
        deny(
            f"BLOCKED: committing {len(ship)} ship-path file(s) on `main`.\n\n{listed}{more}\n\n"
            f"Ship-path work goes on a branch, per .claude/rules/workflow-process.md RULE: ten-step-shape. "
            f"Measured 2026-08-29: a Codex-cleared change to the insertion path sat in 23 uncommitted "
            f"files on `main` for six hours with that rule in context.\n\n"
            f"  git checkout -b <type>/<issue>-<slug>   # your staged set follows you"
        )


# `> p`, `>> p`, `tee p`, `tee -a p`, `sed -i ... p`: the direct shapes, per the threat model.
REDIRECT = re.compile(r">>?\s*([^\s|;&>]+)")


def check_writes(tokens: list[str], command: str) -> None:
    if branch() != "main":
        return
    targets = set(REDIRECT.findall(command))
    for i, tok in enumerate(tokens):
        if tok == "tee":
            targets.update(t for t in tokens[i + 1:] if not t.startswith("-"))
        if tok == "sed" and "-i" in tokens[i + 1:i + 3]:
            targets.update(t for t in tokens[i + 1:] if not t.startswith("-") and "/" in t)
    for raw in targets:
        rel = os.path.relpath(raw, ROOT) if os.path.isabs(raw) else raw
        if rel.startswith("..") or not is_ship_path(rel):
            continue
        deny(
            f"BLOCKED: this command writes to {rel}, a ship path, and you are on `main`.\n\n"
            f"An Edit/Write matcher cannot see a shell write, which is why this check exists here. "
            f"Measured 2026-08-30: every file written by the session that designed this guard went "
            f"through a Bash heredoc.\n\n"
            f"  git checkout -b <type>/<issue>-<slug>"
        )


def main() -> int:
    try:
        payload = json.load(sys.stdin)
        command = (payload.get("tool_input") or {}).get("command") or ""
        if not command:
            return 0
        try:
            tokens = shlex.split(command)
        except ValueError:
            tokens = command.split()
    except Exception:
        return 0  # fail open

    if "git" in tokens and "commit" in tokens:
        check_commit(tokens, command)
    check_writes(tokens, command)
    return 0


if __name__ == "__main__":
    sys.exit(main())
