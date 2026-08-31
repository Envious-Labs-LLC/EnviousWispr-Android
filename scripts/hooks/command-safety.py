#!/usr/bin/env python3
"""The Bash guard: the shell write shapes, and the one token that turns git's own hooks off.

THE COMMIT CHECK USED TO LIVE HERE, AND MOVING IT OUT IS THE POINT. It tried to predict, from the TEXT of
a command, whether a commit was about to happen and what it would contain. Five review rounds each found
another form it got wrong — an option value that looked like a flag, `--dry-run`, a bare positional
pathspec, `-S` swallowing `-a`, `git -C.`, `env FOO=1 git`, a bare `&`, a newline splitting two lines.
Those were not eight defects. They were one: git's option grammar and the shell's grammar are not ours,
and a private parser for either has no last divergence. `scripts/githooks/pre-commit` asks git instead, at
the moment the staged set actually exists, and `scripts/githooks/reference-transaction` guards every
update to `refs/heads/main` — including the merges, rebases, fast-forwards and resets that move the
branch without creating a commit for any commit hook to see.

THREAT MODEL, carried over from macOS `git_target.py` because it BOUNDS THE WORK. The only actor here is
Claude, often five or more concurrent instances, and THERE IS NO ADVERSARY. These gates enforce workflow
etiquette so cooperative agents do not corrupt `main`. The failure to prevent is the path-of-least-
resistance mistake, never a deliberately obfuscated evasion. **Do not build an evasion-proof parser.**

WHAT IS LEFT HERE, and why neither piece can move to a git hook.

1. THE WRITE SHAPES. Measured 2026-08-30: every file written during the session that designed this guard
   went through a Bash heredoc, including the design document itself. An Edit/Write matcher would have
   watched that happen and said nothing, and git never sees a write that is not a commit. So `> path`,
   `>> path`, `>| path`, `tee path` and `sed -i` into a ship path on `main` are denied here. The set of
   ways to write a file is open, so this will never be complete, and it does not need to be: to reach
   `main`, a write has to move that ref, and the reference-transaction hook is on it.

2. `--no-verify`, which skips the applicable verification hooks, including the commit check for whichever
   git operation is running. This is GR-NEVER-WEAKEN-GUARDRAILS at the only place that can see it coming.

   **It is matched as a bare token anywhere in a git command, and that has ONE known false positive:
   `git commit -m --no-verify`, where the flag is the commit MESSAGE.** Naming it is the point. Telling
   an option from an option's VALUE is exactly the grammar this file just deleted 150 lines of, and
   reintroducing it for one implausible message is the trade the threat model exists to refuse. The cost
   is bounded and visible: a denial that names the token, on a commit message nobody writes by accident,
   which the author fixes by wording the message differently. Weigh that against what the general parser
   cost — eight false denials of ordinary work across five review rounds — and this is the cheaper side.

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

# The shell operators that end one command and begin the next. A bare `&` and a NEWLINE are both here
# because both start a new command, and while either was missing a whole command hid behind an earlier one.
SHELL_BREAKS = {"|", "||", "&", "&&", ";", "\n"}

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
    except Exception:
        # NOT just OSError: `text=True` DECODES git's output, and a branch name carrying bytes the
        # locale cannot decode raises UnicodeDecodeError, which would escape `main`, print a traceback,
        # exit 1 with no decision, and let the command through — the guard disarmed by the thing it uses
        # to decide. Same class as the plan gate's write and read paths.
        return ""


def shell_tokens(command: str) -> list[str]:
    """The command split as SHELL SYNTAX, with operators kept as their own tokens.

    `shlex.split` folds `>` and `|` into whatever word they touch, so it can neither find a redirect nor
    see where one command ends and the next begins. A newline has to be BOTH removed from whitespace and
    added to the operators — removing it alone glues two lines into a single token. A newline inside
    quotes is untouched by this, which the controls assert.
    """
    lexer = shlex.shlex(command, posix=True, punctuation_chars="|&;<>\n")
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        return []


def command_segments(tokens: list[str]) -> list[list[str]]:
    """One list per command actually being RUN, split on every operator in SHELL_BREAKS.

    Reading to the end of the token list instead makes every later word an argument of an earlier
    command: `printf x | tee /tmp/log | cat app/src/main/Foo.kt` read the file being CAT-ed as a third
    `tee` target. A guard that fires on correct work is worse than no guard, because it trains the reader
    to route around it.
    """
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

    Matching a word anywhere in the token list rather than in the position where a shell would execute it
    denied `printf '%s' tee app/src/main/Foo.kt`, which runs no tee at all. A segment can open with
    environment assignments or with a redirection, and `env FOO=1 tee x` really does run tee; none of
    those is the command, and skipping them is what makes the next token the executable.
    """
    index = 0
    while index < len(segment) and (ASSIGNMENT.match(segment[index])
                                    or segment[index] in (">", ">>", ">|", "<")):
        index += 2 if segment[index] in (">", ">>", ">|", "<") else 1
    if index >= len(segment):
        return None
    name = os.path.basename(segment[index])
    if name == "env":
        rest = segment[index + 1:]
        while rest and (ASSIGNMENT.match(rest[0]) or rest[0] in ("-i", "--ignore-environment")):
            rest = rest[1:]
        return (os.path.basename(rest[0]), rest[1:]) if rest else None
    return name, segment[index + 1:]


def redirect_targets(tokens: list[str]) -> set[str]:
    """`> p`, `>> p` and `>| p` as SHELL SYNTAX, never as characters inside a quoted string.

    A regex over the raw command text cannot tell the two apart, so `printf 'see > app/src/Foo.kt'` —
    ordinary correct work — was denied as a write to a ship path.
    """
    return {tokens[i + 1] for i, tok in enumerate(tokens[:-1]) if tok in (">", ">>", ">|")}


def check_no_verify(segments: list[list[str]]) -> None:
    """A bare token match, accepting one named false positive rather than parsing git's options again.

    `--no-verify` turns off `scripts/githooks/pre-commit`, which is where the real commit check lives now.
    Denied on EVERY branch, not only `main`: skipping a hook is never the fix for what the hook says.

    `git commit -m --no-verify` is denied although the flag is the message. See the module docstring for
    why that is accepted and not fixed: distinguishing an option from its value is the grammar this file
    deleted, and it cost eight false denials of ordinary work while it was here.
    """
    for segment in segments:
        found = executable(segment)
        if found is None or found[0] != "git":
            continue
        if "--no-verify" in found[1]:
            deny(
                "BLOCKED: `--no-verify` turns off scripts/githooks/pre-commit, which is the check that "
                "keeps ship-path work off `main`.\n\n"
                "GR-NEVER-WEAKEN-GUARDRAILS has no local exception. If the hook is wrong, fix the hook; "
                "if it is right, take the work to a branch.\n\n"
                "  git checkout -b <type>/<issue>-<slug>"
            )


def check_writes(tokens: list[str], segments: list[list[str]]) -> None:
    if branch() != "main":
        return  # the branch IS the protection
    targets = redirect_targets(tokens)
    for segment in segments:
        found = executable(segment)
        if found is None:
            continue
        name, args = found
        if name == "tee":
            targets.update(t for t in args if not t.startswith("-"))
        elif name == "sed" and any(a == "-i" or a == "--in-place" or a.startswith("-i.")
                                   or (a.startswith("-") and not a.startswith("--") and "i" in a[1:])
                                   for a in args):
            # `-i`, `-i.bak`, `--in-place`, and `-i` inside a cluster like `-Ei` are all in-place edits.
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
        segments = command_segments(tokens)
    except Exception:
        return 0  # fail open

    check_no_verify(segments)
    check_writes(tokens, segments)
    return 0


if __name__ == "__main__":
    sys.exit(main())
