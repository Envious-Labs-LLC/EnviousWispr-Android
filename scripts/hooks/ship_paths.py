"""The one list of paths whose edits belong on a branch, shared by every guard that needs it.

Two guards asking this question separately is two answers to one question, which is the failure this whole
enforcement port exists to prevent. Import it; never re-spell it.

`.github/` is included although it does not exist yet: this is a PREFIX match, so it starts protecting the
directory the day issue #13 creates it, with no edit here.
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
    r"|\.gitmodules$"           # the submodule pointer
    r"|\.github/"
    r")"
)


def is_ship_path(path: str) -> bool:
    """True when editing `path` changes shipped behaviour or build reproducibility."""
    return bool(SHIP_PATH.match(path.lstrip("./")))
