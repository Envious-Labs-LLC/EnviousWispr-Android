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

1. THE DIRECT COMMIT CHECK. On `main`, three shapes are denied: an index-bypassing flag (`-a`, `-am`), a
   ship path in the pathspec whether written bare or after `--`, and a staged set that touches a ship
   path. The first two matter because they change what gets committed WITHOUT it appearing in
   `git diff --cached`, so the staged-set check cannot see what it would be approving.

WHAT THIS PARSER RECOGNISES, AND WHY THE LIST IS CLOSED. Four review rounds each produced a new shell or
git form, which is what happens when the population is somebody else's grammar: it has no last member.
The threat model above already decides how far to go — there is no adversary, so the requirement is the
DIRECT shapes a cooperative author actually types.

So the rule is stated once, and here is exactly where it is implemented, because a promise wider than
its code is the worse failure. **These four inputs yield NO decision:** an unrecognised `git` global
option, where `git_subcommand` refuses to guess which token is the subcommand; a dangling option value at
the end of the arguments; a commit whose subject this parser cannot read, listed in `COMMIT_ABSTAIN`; and
anything that is not a recognised executable in a recognised position. An unrecognised COMMIT option is
NOT in that list — it is collected as an option and the staged set is still judged, because `--amend`
and its neighbours are ordinary shapes that must not become a way through. A missed exotic
form costs a mistake that still has to get through review. A false denial costs the guard itself, because
the next session routes around it. Those are not symmetric, and this parser is tuned accordingly.

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

# The shell operators that end one command and begin the next. A bare `&` and a NEWLINE are both here
# because both start a new command, and leaving either out kept `git status & git commit -am x` and a
# two-line command as ONE segment whose executable was `git status`.
SHELL_BREAKS = {"|", "||", "&", "&&", ";", "\n"}

# `git` options that come BEFORE the subcommand. Without these, `git -C /tmp commit` misses its
# subcommand entirely and `git -c user.name=x status` finds one that is not there.
GIT_GLOBAL_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
                         "--super-prefix", "--config-env"}
GIT_GLOBAL_NO_VALUE = {"-p", "-P", "--paginate", "--no-pager", "--bare", "--literal-pathspecs",
                       "--no-literal-pathspecs", "--glob-pathspecs", "--icase-pathspecs",
                       "--no-optional-locks", "--no-replace-objects"}
# The same options written compactly, `-C.` and `-cuser.name=x`, which are valid and were not recognised.
GIT_GLOBAL_COMPACT = ("-C", "-c")

# `git commit` options that CONSUME THE NEXT ARGUMENT. Without this table every scan of the argument list
# reads an option's VALUE as an option: `git commit -m -a` has the message "-a", and was denied as an
# index bypass. It is the false-positive half that matters most, because a guard that fires on correct
# work is worse than no guard.
COMMIT_WITH_VALUE = {"-m", "--message", "-F", "--file", "--author", "--date", "-C", "--reuse-message",
                     "-c", "--reedit-message", "--fixup", "--squash",
                     "-t", "--template", "--cleanup", "--trailer"}
# `-S[<keyid>]` and `-u[<mode>]` take an OPTIONAL value and only when ATTACHED. They are absent from the
# table above for that reason, and it matters in both directions: consuming the next argument ate the
# pathspec out of `git commit --gpg-sign docs/note.md`, which then fell through to the unrelated staged
# set, and it ate the `-a` out of `git commit -S -a -m x`, which is a real all-files commit.
# Shapes of `git commit` that write no history at all, so nothing here has anything to object to.
COMMIT_NO_WRITE = {"--dry-run", "--help", "-h", "--short", "--porcelain", "--long"}
# Shapes whose SUBJECT this parser cannot see: the pathspec lives in a file, or the author is about to
# choose hunks interactively. Judging the index instead would be judging the wrong thing, so abstain.
COMMIT_ABSTAIN = {"--pathspec-from-file", "--interactive", "-p", "--patch"}

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


def commit_shape(args: list[str]) -> "tuple[bool, list[str], list[str]] | None":
    """(writes history, the OPTIONS, the PATHSPEC) for what follows `commit`, or None to abstain.

    Reading the argument list as a flat bag of strings produced a false denial for every option value
    that happens to look like a flag — `git commit -m -a`, `git commit -F -a` — and missed that bare
    positional arguments ARE a pathspec, so `git commit app/src/main/Foo.kt` looked like an ordinary
    commit of an empty index. Position is the only way to tell an option from its value.
    """
    options, positional = [], []
    index = 0
    while index < len(args):
        token = args[index]
        if token == "--":
            return True, options, args[index + 1:]
        if token in COMMIT_NO_WRITE:
            return False, options, []
        if token in COMMIT_ABSTAIN or token.split("=")[0] in COMMIT_ABSTAIN:
            return None  # the subject is somewhere this parser cannot read
        if token in COMMIT_WITH_VALUE:
            if index + 1 >= len(args):
                return False, [], []  # a dangling option: git will reject it, and so this decides nothing
            options.append(token)
            index += 2
            continue
        if token.startswith("-"):
            options.append(token)
        else:
            positional.append(token)
        index += 1
    return True, options, positional


def check_commit(args: list[str]) -> None:
    """`args` is what follows the `commit` SUBCOMMAND, and nothing else in the command line."""
    if branch() != "main":
        return  # the branch IS the protection

    shape = commit_shape(args)
    if shape is None:
        return  # abstain: see COMMIT_ABSTAIN
    writes, options, pathspec = shape
    if not writes:
        return  # a dry run or a help page changes nothing there is anything to object to

    bypass = [t for t in options if t in INDEX_BYPASSING or re.fullmatch(r"-[a-zA-Z]*a[a-zA-Z]*", t)]
    if bypass:
        deny(
            f"BLOCKED: `git commit {' '.join(bypass)}` on `main` bypasses the index.\n\n"
            f"It changes what gets committed without that appearing in `git diff --cached`, so the gate "
            f"cannot see what it is approving. On a branch this is fine.\n\n"
            f"  git checkout -b <type>/<issue>-<slug>"
        )

    # A pathspec — after `--` or as bare positional arguments — commits the WORKING TREE content of
    # exactly those paths, staged or not. So it is BOTH a shape the staged-set check cannot see AND a
    # shape that makes the staged set irrelevant, because whatever else is in the index is not what this
    # commit will contain. Judge the pathspec and RETURN: reading the index too would deny an ordinary
    # `git commit -m x -- docs/note.md` for an unrelated staged file.
    if pathspec:
        ship = [p for p in pathspec if is_ship_path(p)]
        if ship:
            deny(
                f"BLOCKED: `git commit {' '.join(ship)}` on `main` commits working-tree content "
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
    # A newline separates two commands as surely as `;` does. shlex counts it as whitespace by default,
    # so a two-line command arrived as ONE segment whose executable came from the first line. It has to
    # be BOTH removed from whitespace and added to the operators — removing it alone glues the two lines
    # into a single token. A newline inside quotes is untouched by this, which the controls assert.
    lexer = shlex.shlex(command, posix=True, punctuation_chars="|&;<>\n")
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        return []


def command_segments(tokens: list[str]) -> list[list[str]]:
    """One list per command actually being RUN, split on every operator in SHELL_BREAKS.

    `&` and a newline are in that set for the same reason `;` is: each begins a new command, and while
    they were missing, `git status & git commit -am x` and a two-line command were one segment whose
    executable was read from the FIRST command in it.
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

    THE DEFECT THIS EXISTS TO CLOSE ran through every recognition in this file: a word was matched
    anywhere in the token list rather than in the position where a shell would execute it.
    `printf '%s' git commit -a` contains `git`, `commit` and `-a`, runs none of them, and was denied. So
    was `printf '%s' tee app/src/main/Foo.kt`, and so was a `tee` target belonging to a later pipeline
    stage. Reading position is what makes those ordinary commands ordinary again.

    Leading `VAR=value` assignments are skipped, because `FOO=1 git commit` really does run git.
    """
    index = 0
    # A segment can OPEN with a redirection (`> out.txt cat in.txt`), and it can carry environment
    # assignments. Neither is the command; skipping both is what makes the next token the executable.
    while index < len(segment) and (ASSIGNMENT.match(segment[index])
                                    or segment[index] in (">", ">>", ">|", "<")):
        index += 2 if segment[index] in (">", ">>", ">|", "<") else 1
    if index >= len(segment):
        return None
    name = os.path.basename(segment[index])
    # `env FOO=1 git commit` runs git. Classifying it as `env` reads a real commit as an unrelated
    # command, which is the same position mistake one level further out.
    if name == "env":
        rest = segment[index + 1:]
        while rest and (ASSIGNMENT.match(rest[0]) or rest[0] in ("-i", "--ignore-environment")):
            rest = rest[1:]
        return (os.path.basename(rest[0]), rest[1:]) if rest else None
    return name, segment[index + 1:]


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
            # `-C.` and `-cuser.name=x` attach the value to the option, and are as ordinary as the
            # separated forms. Missing them made `git -C. commit -am x` unrecognised, so it escaped.
            if any(token.startswith(f) and len(token) > len(f) for f in GIT_GLOBAL_COMPACT):
                index += 1
                continue
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
    return {tokens[i + 1] for i, tok in enumerate(tokens[:-1]) if tok in (">", ">>", ">|")}


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
