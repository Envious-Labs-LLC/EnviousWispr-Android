#!/usr/bin/env python3
"""The Bash umbrella: the commit gate, plus the write shapes an Edit/Write matcher cannot see.

THREAT MODEL, carried over from macOS `git_target.py` because it BOUNDS THE WORK. The only actor here is
Claude, often five or more concurrent instances, and THERE IS NO ADVERSARY. These gates enforce workflow
etiquette so cooperative agents do not corrupt `main`. The failure to prevent is the path-of-least-
resistance mistake — a plain `git commit -am` in the wrong checkout — never a deliberately obfuscated
evasion. A cooperative agent emits the DIRECT shape; it never emits `sh -c '...'` or `C=commit; git $C`,
because those are MORE effort. **Do not build an evasion-proof parser.** Matching the direct shapes is the
whole requirement, and anything beyond it is cost with no threat behind it.

TWO JOBS, AND NEITHER IS A GUARANTEE. An earlier draft of this file called job 1 "the GUARANTEE" and said
it "is the only place that can be complete". That was a false enforcement claim, which is the most
expensive kind of comment: it retires the reader's check instead of failing it. `git commit` is one of
several ways to write history — `git merge`, `git cherry-pick`, `git rebase --continue`, `git am` and a
user-defined alias all reach it without passing this parser — and even inside `git commit` the parse is a
best-effort match on the direct forms. Both jobs below are best effort. The BRANCH is the protection; this
raises the cost of the accidental shape.

1. THE DIRECT COMMIT CHECK. On `main`, three shapes are denied: an index-bypassing flag (`-a`, `-am`),
   an explicit pathspec after `--`, and a staged set that touches a ship path. The first two matter
   because they change what gets committed WITHOUT it appearing in `git diff --cached`, so the staged-set
   check cannot see what it would be approving.

2. THE WRITE SHAPES. Measured 2026-08-30: every file written during the session that designed this guard
   went through a Bash heredoc, including the design document itself. An Edit/Write matcher would have
   watched that happen and said nothing. So `> path`, `>> path`, `tee path` and `sed -i` into a ship path
   on `main` are denied here. The set of ways to write a file is open, so this will never be complete.

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

# The shell operators that end one command and begin the next.
SHELL_BREAKS = {"|", "||", "&&", ";", "\n"}

# `git` options that come BEFORE the subcommand. Without these, `git -C /tmp commit` misses its
# subcommand entirely and `git -c user.name=x status` finds one that is not there.
GIT_GLOBAL_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
                         "--super-prefix", "--config-env"}
GIT_GLOBAL_NO_VALUE = {"-p", "-P", "--paginate", "--no-pager", "--bare", "--literal-pathspecs",
                       "--no-literal-pathspecs", "--glob-pathspecs", "--icase-pathspecs",
                       "--no-optional-locks", "--no-replace-objects"}

# A leading `VAR=value` is an environment assignment, not the command.
ASSIGNMENT = re.compile(r"[A-Za-z_]\w*=")


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


def check_commit(args: list[str]) -> None:
    """`args` is what follows the `commit` SUBCOMMAND, and nothing else in the command line."""
    if branch() != "main":
        return  # the branch IS the protection

    bypass = [t for t in args if t in INDEX_BYPASSING or re.fullmatch(r"-[a-zA-Z]*a[a-zA-Z]*", t)]
    if bypass:
        deny(
            f"BLOCKED: `git commit {' '.join(bypass)}` on `main` bypasses the index.\n\n"
            f"It changes what gets committed without that appearing in `git diff --cached`, so the gate "
            f"cannot see what it is approving. On a branch this is fine.\n\n"
            f"  git checkout -b <type>/<issue>-<slug>"
        )

    # `git commit -- <path>` commits the WORKING TREE content of exactly those paths, staged or not.
    # It is therefore BOTH a shape the staged-set check cannot see AND a shape that makes the staged set
    # irrelevant: whatever else is in the index is not what this commit will contain. Judge the pathspec
    # and return, or an ordinary `git commit -m x -- docs/note.md` is denied for an unrelated staged file.
    if "--" in args:
        pathspec = args[args.index("--") + 1:]
        if pathspec:
            ship = [p for p in pathspec if is_ship_path(p)]
            if ship:
                deny(
                    f"BLOCKED: `git commit -- {' '.join(ship)}` on `main` commits working-tree content "
                    f"without it passing through the index.\n\n"
                    f"  git checkout -b <type>/<issue>-<slug>"
                )
            return

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


def shell_tokens(command: str) -> list[str]:
    """The command split as SHELL SYNTAX, with operators kept as their own tokens.

    `shlex.split` folds `>` and `|` into whatever word they touch, so it can neither find a redirect nor
    see where one command ends and the next begins. Both of those are needed below.
    """
    lexer = shlex.shlex(command, posix=True, punctuation_chars="|&;<>")
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        return []


def command_segments(tokens: list[str]) -> list[list[str]]:
    """One list per command actually being RUN, split on the shell operators between them."""
    segments, current = [], []
    for token in tokens:
        if token in SHELL_BREAKS:
            if current:
                segments.append(current)
                current = []
        else:
            current.append(token)
    if current:
        segments.append(current)
    return segments


def executable(segment: list[str]) -> "tuple[str, list[str]] | None":
    """The command being RUN and its arguments, or None when the segment runs nothing.

    THE DEFECT THIS EXISTS TO CLOSE ran through every recognition in this file: a word was matched
    anywhere in the token list rather than in the position where a shell would execute it.
    `printf '%s' git commit -a` contains `git`, `commit` and `-a`, runs none of them, and was denied. So
    was `printf '%s' tee app/src/main/Foo.kt`, and so was a `tee` target belonging to a later pipeline
    stage. Reading position is what makes those ordinary commands ordinary again.

    Leading `VAR=value` assignments are skipped, because `FOO=1 git commit` really does run git.
    """
    index = 0
    while index < len(segment) and ASSIGNMENT.match(segment[index]):
        index += 1
    if index >= len(segment):
        return None
    return os.path.basename(segment[index]), segment[index + 1:]


def git_subcommand(args: list[str]) -> "tuple[str, list[str]] | None":
    """The git subcommand and ITS arguments, skipping the global options that precede it.

    Returns None when the subcommand cannot be identified — an option this parser does not know, or
    nothing after the options. That is deliberate: an ambiguous parse must produce NO decision rather
    than a denial of work that may be perfectly ordinary. There is no adversary here, so the cost of
    missing an exotic form is a mistake that still has to get past review; the cost of a false denial is
    a guard the next session routes around.
    """
    index = 0
    while index < len(args):
        token = args[index]
        if token in GIT_GLOBAL_WITH_VALUE:
            index += 2
            continue
        if token.startswith("-"):
            if "=" in token or token in GIT_GLOBAL_NO_VALUE:
                index += 1
                continue
            return None
        return token, args[index + 1:]
    return None


def redirect_targets(tokens: list[str]) -> set[str]:
    """`> p` and `>> p` as SHELL SYNTAX, never as characters inside a quoted string.

    A regex over the raw command text cannot tell the two apart, so `printf 'see > app/src/Foo.kt'` —
    ordinary correct work — was denied as a write to a ship path.
    """
    return {tokens[i + 1] for i, tok in enumerate(tokens[:-1]) if tok in (">", ">>")}


def check_writes(tokens: list[str]) -> None:
    if branch() != "main":
        return
    targets = redirect_targets(tokens)
    for segment in command_segments(tokens):
        found = executable(segment)
        if found is None:
            continue
        name, args = found
        if name == "tee":
            targets.update(t for t in args if not t.startswith("-"))
        elif name == "sed" and "-i" in args[:2]:
            targets.update(t for t in args if not t.startswith("-") and "/" in t)
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
        tokens = shell_tokens(command)
    except Exception:
        return 0  # fail open

    for segment in command_segments(tokens):
        found = executable(segment)
        if found is None or found[0] != "git":
            continue
        sub = git_subcommand(found[1])
        if sub is not None and sub[0] == "commit":
            check_commit(sub[1])
    check_writes(tokens)
    return 0


if __name__ == "__main__":
    sys.exit(main())
