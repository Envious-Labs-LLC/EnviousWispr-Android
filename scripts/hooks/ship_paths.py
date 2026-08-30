"""The one list of paths whose edits belong on a branch, shared by every guard that needs it.

Two guards asking this question separately is two answers to one question, which is the failure this whole
enforcement port exists to prevent. Import it; never re-spell it.

WHAT IT ACTUALLY COVERS, stated so the name does not overclaim. "Ship path" is the repository's own term
(`.claude/rules/workflow-process.md`, and every deny message here), and the set is wider than the files
that literally reach a user: it also holds unit and instrumented tests, debug-only sources under
`app/src/`, Room test schemas, and `.github/`. All of those are branch work; none of them ship. The
predicate answers "does editing this belong on a branch", never "does this reach a phone".

`.github/` is included although it does not exist yet: this is a PREFIX match, so it starts protecting the
directory the day issue #13 creates it, with no edit here.

TWO BUILD INPUTS ARE DELIBERATELY OUTSIDE IT, and the reason is that git already holds the line.
`app/libs/` and `local.properties` are both required to build and both gitignored, so no author can commit
them and there is nothing for a branch rule to protect. Do not read this predicate as covering every input
that affects a build; it covers every input that can enter HISTORY.

`accelerator-benchmark/` is also outside it, and that is a rule, not an omission. FACT: lanes gives the
benchmark its own non-gating lane precisely because it is an experiment whose build failure is not an app
defect. Adding it here would force branch discipline on work the process deliberately does not gate.
"""

import re

SHIP_PATH = re.compile(
    r"^(app/src/"
    r"|app/schemas/"            # Room migration schemas: a shipped data contract
    r"|app/build\.gradle\.kts$"
    r"|build\.gradle\.kts$"
    r"|settings\.gradle\.kts$"
    r"|gradle\.properties$"
    r"|gradle/"                 # the wrapper decides which Gradle actually runs
    r"|gradlew$"
    r"|gradlew\.bat$"
    r"|llama-android/"
    r"|third_party/"            # submodule contents
    r"|\.gitmodules$"           # submodule configuration; `third_party/` above matches the gitlink
    r"|\.gitignore$"            # decides which build inputs and receipts can enter history at all
    r"|\.github/"
    r")"
)


def _normalise(path: str) -> str:
    """Strip a leading `./` PREFIX, never leading characters.

    The first version used `path.lstrip("./")`, which strips any leading `.` or `/` character rather than
    the two-character prefix. `.gitmodules` became `gitmodules` and stopped matching, so the submodule
    pointer — a shipped build input — was silently unprotected. Caught by the two-way control that asserted
    it should deny; nothing else would have noticed, because the guard stays quiet when it allows.
    """
    while path.startswith("./"):
        path = path[2:]
    return path.lstrip("/")


def is_ship_path(path: str) -> bool:
    """True when editing `path` changes shipped behaviour or build reproducibility."""
    return bool(SHIP_PATH.match(_normalise(path)))
