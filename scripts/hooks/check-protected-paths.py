#!/usr/bin/env python3
"""Refuse the FIRST ship-path edit on `main`, so the mistake is free to undo.

WHY THIS EXISTS (measured 2026-08-29). A Codex-cleared change to the insertion path was authored directly
on `main`: 8 modified and 15 untracked ship-path files, `origin/main..HEAD` at 0, no feature branch in the
reflog. The repository's own rule already said work belongs on a branch. Nothing objected, because nothing
here could.

Why at EDIT time and not only at commit time: the commit gate fires at the end, after the work is already
built in the wrong checkout. This one refuses the first edit, which is the moment undoing costs nothing.

SCOPE, STATED HONESTLY. A PreToolUse matcher on Edit/Write/MultiEdit sees the assistant's file tools and
nothing else. It does not see a Bash heredoc, `tee`, `sed -i`, or another process writing. The Bash shapes
are covered by command-safety.py; Codex is constrained at its call site by running read-only. **Edit-time
protection is best effort and always will be, because the set of ways to write a file is open. The COMMIT
GATE is the guarantee**, because every author must pass through `git commit` to reach history.

Reads the hook JSON on stdin. Emits a deny and exit 0 to block; exits 0 silently to allow.
"""

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ship_paths import is_ship_path  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def deny(reason: str) -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                             "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # fail open: a guard that breaks must not block every edit

    paths = []
    tool_input = payload.get("tool_input") or {}
    if isinstance(tool_input.get("file_path"), str):
        paths.append(tool_input["file_path"])
    for edit in tool_input.get("edits") or []:
        if isinstance(edit, dict) and isinstance(edit.get("file_path"), str):
            paths.append(edit["file_path"])
    if not paths:
        return 0

    try:
        branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"],
                                cwd=ROOT, capture_output=True, text=True).stdout.strip()
    except OSError:
        return 0
    if branch != "main":
        return 0  # the branch IS the protection

    for raw in paths:
        rel = os.path.relpath(raw, ROOT) if os.path.isabs(raw) else raw
        if rel.startswith(".."):
            continue  # outside this repository
        if is_ship_path(rel):
            deny(
                f"BLOCKED: {rel} is a ship path and you are on `main`.\n\n"
                f"Ship-path work goes on a branch, per .claude/rules/workflow-process.md "
                f"RULE: ten-step-shape. Refusing the FIRST edit rather than the commit, because this is "
                f"the moment undoing costs nothing.\n\n"
                f"  git checkout -b <type>/<issue>-<slug>\n\n"
                f"Local-only paths (.claude/, docs/, CLAUDE.md, scripts/) are allowed on main."
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
