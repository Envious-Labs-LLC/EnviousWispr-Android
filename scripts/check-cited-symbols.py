#!/usr/bin/env python3
"""Every backticked identifier a diff ADDS must resolve to real code, not only to prose.

WHY THIS EXISTS. A wrong identifier in code fails to build; a wrong one in prose ships and is read as
authority. On 2026-08-30 an audit of the macOS knowledge base read all 101 files: 23 were partly stale and
3 were stale, and the dominant failure was exactly this shape — a named symbol, a count, or a "we removed X"
claim that quietly stopped being true. Android has 14 knowledge files and had no such check.

LIVES IN `scripts/`, NOT `.claude/scripts/`, AND THAT IS A DELIBERATE DIVERGENCE FROM macOS. This
repository gitignores `.claude/`, so a checker kept there would be untracked: no history, no review, no
copy anywhere but this disk. A guard that cannot be reviewed is a guard nobody can correct.

A TOOL, NOT A HOOK, deliberately. A gate nothing drives first fires in someone else's run, and prose is
edited by every session. macOS reached the same conclusion for the same reason; neither of its two checkers
of this kind is registered in settings.json.

  scripts/check-cited-symbols.py                     # against origin/main
  scripts/check-cited-symbols.py --base HEAD~1
  scripts/check-cited-symbols.py --detect-only       # exit 0 if there is anything to check

--detect-only exists because the Docs/dev-tooling lane obligation is conditional, and the CONDITION MUST BE
ANSWERED BY THIS EXTRACTOR rather than by a grep beside it. A grep for "any backticked token" is a second,
wider definition: it matches `true`, `null` and short names that this tool deliberately skips, so prose whose
only backticked word is `true` would be sent here and then denied for containing nothing to check. One
extractor, one answer.

VALIDATE THE INSTRUMENT BEFORE BELIEVING A RESULT. The macOS sibling's first version reported 16 unresolved
when the real number was 1, from four defects, and EVERY defect failed the same direction: reporting a
WORKING reference as broken. A checker whose errors all point one way reads as strict rather than as wrong.
The first run here measures the TOOL, not the repository.

Exit: 0 clean (or, with --detect-only, there is something to check) · 1 unresolved citations found
      (or, with --detect-only, nothing to check) · 2 the check could not run, which is not a pass.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Every source root, which is wider than every root that ships. Searching only app/src would report a
# working llama-android or JNI symbol as broken, the direction that trains dismissal — and leaving
# `scripts/` out did exactly that: the Docs/dev-tooling lane is mostly diffs to these scripts, so every
# Python or shell identifier they cited was UNRESOLVED by construction, and the obligation was
# unsatisfiable for the lane that runs it most.
SOURCE_ROOTS = ["app/src", "llama-android", "third_party", "accelerator-benchmark", "scripts"]
SOURCE_SUFFIXES = (".kt", ".java", ".cpp", ".h", ".hpp", ".c", ".cc", ".aidl", ".kts", ".xml",
                   ".py", ".sh")

# Words that appear in backticks as PROSE, not as a claim about a symbol. Kotlin and Gradle prose is full of
# them. Keeping this list short is a design constraint, not a convenience: if it has to grow past ~20 to
# stay quiet, the matcher is wrong and should be reconsidered rather than extended.
NOT_A_SYMBOL = {
    "true", "false", "null", "nil", "TODO", "FIXME", "NOTE", "main", "origin", "HEAD",
    "debug", "release", "Debug", "Release", "gradlew", "adb", "N/A", "n/a", "PASS", "FAIL",
    "UNVERIFIED", "SKIPPED", "Y", "N", "and", "or", "not",
}

MIN_LENGTH = 4  # shorter than this is prose far more often than it is a symbol


def die(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(2)


def run(*args: str) -> str:
    try:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    except OSError as exc:
        die(f"could not run {' '.join(args)}: {exc}")
    if result.returncode != 0:
        die(f"{' '.join(args)} exited {result.returncode}: {result.stderr.strip()[:200]}")
    return result.stdout


def added_lines(base: str) -> list[str]:
    """Committed AND uncommitted, deliberately.

    The first version of this function diffed `merge-base..HEAD`, which sees only what is already
    committed. Run before a commit — which is the only time it is useful — it therefore reported a clean
    result for prose it had never read. It failed LENIENT, which is worse than the macOS sibling's
    strict-direction defects, because a checker that says "all citations resolve" when it read nothing is
    trusted and wrong. Diffing against the merge-base with no second revision includes the working tree.
    """
    merge_base = run("git", "merge-base", base, "HEAD").strip() if base != "HEAD" else "HEAD"
    diff = run("git", "diff", "--unified=0", merge_base)
    untracked = run("git", "ls-files", "--others", "--exclude-standard").split()
    extra = []
    for path in untracked:
        full = os.path.join(ROOT, path)
        if os.path.isfile(full) and path.endswith((".md", ".kt", ".kts", ".py", ".sh")):
            try:
                extra.extend(open(full, encoding="utf-8", errors="replace").read().splitlines())
            except OSError:
                continue
    lines = [ln[1:] for ln in diff.splitlines() if ln.startswith("+") and not ln.startswith("+++")]
    lines.extend(extra)
    if not lines:
        die("empty diff — nothing to check, which is not a pass")
    return lines


def citations(lines: list[str]) -> tuple[set[str], set[tuple[str, int]]]:
    """Returns (symbol claims, location claims).

    A location claim is a backticked filename with a line number after a colon. It is deliberately NOT
    written out as an example here: the first run of this tool against its own source flagged that example
    as an unresolved citation, and it was right to. A checker's own documentation is prose like any other.
    """
    symbols: set[str] = set()
    locations: set[tuple[str, int]] = set()
    for line in lines:
        for token in re.findall(r"`([^`\n]+)`", line):
            token = token.strip()
            location = re.fullmatch(r"([\w./-]+\.(?:kt|java|cpp|h|kts|xml|aidl)):(\d+)", token)
            if location:
                locations.add((location.group(1), int(location.group(2))))
                continue
            # An identifier claim: a bare name, or a dotted/parenthesised member reference.
            identifier = re.fullmatch(r"([A-Za-z_][\w]*)(?:\.[A-Za-z_][\w]*)*(?:\(\))?", token)
            if not identifier:
                continue
            name = token.split("(")[0].split(".")[-1]
            if name in NOT_A_SYMBOL or len(name) < MIN_LENGTH:
                continue
            symbols.add(name)
    return symbols, locations


def source_files() -> list[str]:
    found = []
    for root in SOURCE_ROOTS:
        base = os.path.join(ROOT, root)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in {"build", ".git", ".gradle"}]
            for name in filenames:
                if name.endswith(SOURCE_SUFFIXES):
                    found.append(os.path.join(dirpath, name))
    return found


def main() -> int:
    base = "origin/main"
    detect_only = "--detect-only" in sys.argv
    if "--base" in sys.argv:
        base = sys.argv[sys.argv.index("--base") + 1]

    symbols, locations = citations(added_lines(base))

    if detect_only:
        # The lane obligation branches on this, so it must use THIS extractor and no other.
        if symbols or locations:
            print(f"{len(symbols)} symbol and {len(locations)} location citations to check")
            return 0
        print("no backticked identifiers in added lines")
        return 1

    if not symbols and not locations:
        die("no citations parsed out of a non-empty diff — nothing to check, which is not a pass")

    files = source_files()
    if not files:
        die(f"no source files found under {SOURCE_ROOTS} — the search population is empty, so every "
            f"citation would report as broken")

    corpus = {}
    for path in files:
        try:
            corpus[path] = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
    joined = "\n".join(corpus.values())

    unresolved = []
    for name in sorted(symbols):
        if not re.search(r"\b" + re.escape(name) + r"\b", joined):
            unresolved.append(f"  UNRESOLVED  `{name}` does not appear in any shipped source")

    for filename, lineno in sorted(locations):
        matches = [p for p in corpus if p.endswith("/" + filename) or os.path.basename(p) == os.path.basename(filename)]
        if not matches:
            unresolved.append(f"  NO SUCH FILE  `{filename}:{lineno}`")
            continue
        if len(matches) > 1:
            unresolved.append(f"  AMBIGUOUS     `{filename}:{lineno}` matches {len(matches)} files")
            continue
        body = corpus[matches[0]].splitlines()
        if lineno > len(body):
            unresolved.append(f"  TOO SHORT     `{filename}:{lineno}` but the file has {len(body)} lines")
        elif not body[lineno - 1].strip():
            # The interesting failure: what a citation looks like after the code it named moved away.
            unresolved.append(f"  BLANK LINE    `{filename}:{lineno}` exists but is empty")

    print(f"checked {len(symbols)} symbol and {len(locations)} location citations "
          f"against {len(files)} source files")
    if unresolved:
        print("\n".join(unresolved))
        print(f"\n{len(unresolved)} citation(s) do not resolve. A wrong identifier in prose ships and is "
              f"read as authority.")
        return 1
    print("all citations resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
