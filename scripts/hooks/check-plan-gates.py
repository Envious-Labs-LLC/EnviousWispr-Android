#!/usr/bin/env python3
"""The Tier 0 plan-file gates, in one hook because they share one trigger and one parse.

macOS runs these as four separate hooks. One file here, four checks, for a reason worth stating: they all
fire on the same event (a write to a plan file), all need the same payload, and splitting them would mean
four processes doing the same JSON parse and the same path test to answer four questions about one
document. Splitting is right when the triggers differ; these do not.

THE FOUR CHECKS

1. PRIOR CONTEXT (Gate 0). For an Edit, Write or MultiEdit call this hook can reconstruct, creating an
   issue plan is refused until prior context has been read and posted. It sees no other way of writing a
   file, so this is an objection, not a prohibition. Prose
   posted in chat is NOT MECHANICALLY OBSERVABLE, so this is sentinel-armed exactly as macOS does it:
   summarise prior context, `touch /tmp/.ew-android-issue-<N>-context-read`, then write.
   **The sentinel is issue-scoped and reusable for 30 minutes, not one-shot.** Nothing removes it, so any
   plan file naming the same issue passes while it is fresh, and it is not evidence of WHICH plan body was
   reviewed. Calling it one-shot would be a claim the code does not keep. Making it truly one-shot needs a
   PostToolUse hook to remove it after the write SUCCEEDS; removing it here would spend the attestation
   before knowing whether the write happened, and a denial would then cost the session its own gate.
   Draft preservation is no longer Gate 0's business; `preserve` runs on every denial and says so in the
   denial either way. The write can fail — `/tmp` full or read-only — and a denial that claimed a recovery
   copy it never made would be worse than one that admits it.

2. USER RUBRIC (Gate 0.5). Every plan carries the rubric or `User Rubric: N/A — <reason>`. **Structural
   completeness only.** Whether the reason is honest is a job for grounded review; a hook cannot infer
   user-visibility, and a pipeline change reaches the user without touching any screen.

3. LANE. The plan declares one of the four exact-case lanes. `check-validation.sh` dispatches on the
   spelling, so a lane it will later reject must not pass here.

4. CONSOLIDATION. A plan over a size threshold names what it consolidates, or says none.

ALL FOUR RUN BEFORE ANYTHING IS REFUSED, and one denial carries every failure. Refusing at the first
failure is what makes a plan expensive: a file that does not exist yet can only be created by sending the
whole document again, so each gate that stays silent until the next attempt costs one more full re-send.
Measured on the #47 plan (2026-08-31): 28,847 characters sent three times, the middle one byte-identical
to the first, because Gate 0 and Consolidation reported one at a time.

Exits 0 silently for any file that is not a plan. On malformed or unreadable input it emits NO permission
decision, which the harness treats as no objection.
"""

import json
import os
import re
import sys
import time

SENTINEL_TTL = 30 * 60
LANES = ["Code", "Benchmark", "CI/workflow", "Docs/dev-tooling"]
PLAN = re.compile(r"docs/feature-requests/(issue-(\d+)|plan)-[\w.-]+\.md$")


def resulting_document(tool_input: dict, full_path: str) -> str | None:
    """The document as it will EXIST after this call, never the fragment the call carries.

    Write hands over the whole file in `content`. Edit and MultiEdit hand over pieces, and judging a piece
    denies ordinary work: replacing one typo in a finished plan yields a `new_string` with no rubric and
    no lane, so checks 2-4 all fire on a plan that satisfies every one of them.

    `None` means "I could not reconstruct this", and it is a SEPARATE value from `""` on purpose. An
    earlier version returned `""` for both, and `""` is also the exact, correct result of writing an empty
    plan file — so `if not body: return 0` waved an empty plan past every gate while looking like the
    careful branch. A guess is a worse input to a gate than no input; an empty document is neither.
    """
    if isinstance(tool_input.get("content"), str):
        return tool_input["content"]
    try:
        body = open(full_path, encoding="utf-8").read()
    except OSError:
        return None
    edits = tool_input.get("edits")
    if not isinstance(edits, list):
        old, new = tool_input.get("old_string"), tool_input.get("new_string")
        if not isinstance(old, str) or not isinstance(new, str):
            return None
        edits = [{"old_string": old, "new_string": new}]
    for edit in edits:
        if not isinstance(edit, dict):
            return None
        old, new = edit.get("old_string"), edit.get("new_string")
        if not isinstance(old, str) or not isinstance(new, str) or body.count(old) != 1:
            return None
        body = body.replace(old, new, 1)
    return body


def deny(reason: str) -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                             "permissionDecision": "deny",
                                             "permissionDecisionReason": reason}}))
    sys.exit(0)


def preserve(body: str, path: str) -> str:
    """Write the resulting document aside, and report honestly whether that worked.

    Called on EVERY denial, not only Gate 0's. A gate that destroys work teaches its own bypass. The
    author's text also survives in the session transcript, so this is insurance against the transcript
    being compacted between the denial and the retry, not the primary copy.

    The body written here is the RESULTING document from `resulting_document`, so an Edit is preserved as
    a whole plan rather than as the fragment the call carried. A fragment saved under a plan's name is the
    shape that invites someone to paste it over a finished document.
    """
    recovery = f"/tmp/.ew-android-{os.path.basename(path)}.blocked-draft.md"
    try:
        with open(recovery, "w", encoding="utf-8") as handle:
            handle.write(body)
    except OSError as exc:
        return f"DRAFT NOT PRESERVED: could not write {recovery}: {exc}. Do not claim it was saved."
    # Never suggest `cp` into place: that reaches the plan path without passing these gates, so a gate
    # that preserves work would also be teaching the way around itself.
    return (f"DRAFT PRESERVED at {recovery} ({len(body)} chars). Do NOT regenerate it from memory: read "
            f"that file, fix it there, then re-issue the SAME call so every gate runs again. Never cp or "
            f"mv it over {path}.")


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path")
    if not isinstance(path, str):
        return 0
    match = PLAN.search(path.replace(os.sep, "/"))
    if not match:
        return 0  # not a plan file: silent, which is every other write in the repository

    full_path = path if os.path.isabs(path) else os.path.join(os.getcwd(), path)
    body = resulting_document(tool_input, full_path)
    if body is None:
        return 0  # an edit we cannot reconstruct is not a plan we can judge

    issue = match.group(2)

    # EVERY gate is evaluated before anything is refused, and all failures are reported together.
    # Refusing at the FIRST failure costs the author one full re-send of the document per gate, because
    # a file that does not exist yet can only be created by sending the whole thing again. Measured on
    # the #47 plan (2026-08-31): a 28,847-character plan was sent three times — refused by Gate 0, sent
    # again byte-identical, refused by Consolidation, sent a third time with both fixed. Two of those
    # three sends bought nothing. Order is preserved so the numbering reads as a checklist.
    problems: list[str] = []

    # --- 1. Prior context
    sentinel = f"/tmp/.ew-android-issue-{issue}-context-read" if issue else ""
    if issue and not os.path.exists(full_path):  # only a NEW plan; edits to an existing one are not Gate 0
        fresh = os.path.exists(sentinel) and (time.time() - os.path.getmtime(sentinel)) < SENTINEL_TTL
        if not fresh:
            problems.append(
                f"Gate 0 — prior context has not been attested for issue #{issue}.\n"
                f"Read the issue AND .claude/knowledge/session-log.md, plus the PAR rows and the owning "
                f"knowledge file, then post `Prior context for #{issue}: ...` in chat.\n"
                f"Prose in chat is not mechanically observable, so attest it:\n"
                f"  touch {sentinel}      # issue-scoped, reusable for 30 minutes"
            )

    # --- 2. User Rubric, structural only
    has_rubric = re.search(r"User Rubric:\s*N/A\s*[-—]\s*\S", body) or "## Preface — User Rubric" in body
    if not has_rubric:
        problems.append(
            "User Rubric — the plan carries neither the rubric nor a reasoned N/A.\n"
            "Answer it against a named persona, or state\n"
            "  User Rubric: N/A — <specific internal-only reason>\n"
            "A bare `N/A` is not an answer. This check is structural only: whether the reason is honest "
            "is for grounded review, because a hook cannot infer user-visibility and a pipeline change "
            "reaches the user without touching any screen."
        )

    # --- 3. Lane. The two branches are mutually exclusive, so at most one lane problem is reported.
    declared = re.search(r"\*\*Lane:\*\*\s*`?([A-Za-z/-]+)`?", body)
    if not declared:
        problems.append("Lane — the plan declares none.\nWrite the lane token first on the line:\n"
                        f"  **Lane:** {' | '.join(LANES)}")
    elif declared.group(1).strip() not in LANES:
        problems.append(
            f"Lane — unknown spelling {declared.group(1)!r}.\n"
            f"check-validation.sh dispatches on the exact spelling, so a lane it will later reject must "
            f"not pass here. One of: {', '.join(LANES)}. `Mixed` is not a lane; declare a primary lane "
            f"and add `mixed_pr: true` on its own line."
        )

    # --- 4. Consolidation, only once a plan is big enough for the question to mean anything
    if len(body) > 4000 and not re.search(r"(?i)consolidat", body):
        problems.append(
            "Consolidation — this plan is long enough to be consolidating something and does not say "
            "what.\nName the dominant root, its one owner, and the consolidation sites, or write "
            "`Consolidation: none` and say why."
        )

    if not problems:
        return 0

    numbered = "\n\n".join(f"{n}. {text}" for n, text in enumerate(problems, 1))
    plural = "gate" if len(problems) == 1 else "gates"
    deny(
        f"BLOCKED: {len(problems)} plan {plural} refused this write. Every gate ran, so this is the "
        f"COMPLETE list. Fix all of it in one pass, then re-issue once.\n\n"
        f"{numbered}\n\n"
        f"{preserve(body, path)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
