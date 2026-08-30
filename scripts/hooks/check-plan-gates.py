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
   **It TRIES to preserve the draft on denial**, and says so in the denial either way. macOS does this and
   it is required, not incidental: a gate that destroys work teaches its own bypass, and the next session
   will route around it rather than attest. The write can fail — `/tmp` full or read-only — and a denial
   that claimed a recovery copy it never made would be worse than one that admits it.

2. USER RUBRIC (Gate 0.5). Every plan carries the rubric or `User Rubric: N/A — <reason>`. **Structural
   completeness only.** Whether the reason is honest is a job for grounded review; a hook cannot infer
   user-visibility, and a pipeline change reaches the user without touching any screen.

3. LANE. The plan declares one of the four exact-case lanes. `check-validation.sh` dispatches on the
   spelling, so a lane it will later reject must not pass here.

4. CONSOLIDATION. A plan over a size threshold names what it consolidates, or says none.

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

    # --- 1. Prior context
    if issue and not os.path.exists(full_path):  # only a NEW plan; edits to an existing one are not Gate 0
        sentinel = f"/tmp/.ew-android-issue-{issue}-context-read"
        fresh = os.path.exists(sentinel) and (time.time() - os.path.getmtime(sentinel)) < SENTINEL_TTL
        if not fresh:
            recovery = f"/tmp/.ew-android-issue-{issue}-pending-plan.md"
            try:
                with open(recovery, "w") as handle:
                    handle.write(body)
                # Never suggest `cp` into place: that reaches the plan path without passing checks 2-4,
                # so a gate that preserves work would also be teaching the way around itself.
                saved = (f"\n\nDRAFT PRESERVED at {recovery} ({len(body)} chars). Do NOT regenerate it.\n"
                         f"  touch {sentinel}\n"
                         f"Then re-issue the SAME Write call, so every remaining plan gate still runs.")
            except OSError as exc:
                saved = f"\n\nDRAFT NOT PRESERVED: could not write {recovery}: {exc}"
            deny(
                f"BLOCKED: Gate 0 has not been attested for issue #{issue}.\n\n"
                f"Read the issue AND .claude/knowledge/session-log.md, plus the PAR rows and the owning "
                f"knowledge file, then post `Prior context for #{issue}: ...` in chat.\n\n"
                f"Prose in chat is not mechanically observable, so attest it:\n"
                f"  touch {sentinel}      # issue-scoped, reusable for 30 minutes"
                f"{saved}"
            )

    # --- 2. User Rubric, structural only
    has_rubric = re.search(r"User Rubric:\s*N/A\s*[-—]\s*\S", body) or "## Preface — User Rubric" in body
    if not has_rubric:
        deny(
            "BLOCKED: the plan carries no User Rubric and no reasoned N/A.\n\n"
            "Every plan answers the rubric against a named persona, or states\n"
            "  User Rubric: N/A — <specific internal-only reason>\n\n"
            "A bare `N/A` is not an answer. This check is structural only: whether the reason is honest "
            "is for grounded review, because a hook cannot infer user-visibility and a pipeline change "
            "reaches the user without touching any screen."
        )

    # --- 3. Lane
    declared = re.search(r"\*\*Lane:\*\*\s*`?([A-Za-z/-]+)`?", body)
    if not declared:
        deny("BLOCKED: the plan declares no lane.\n\nWrite the lane token first on the line:\n"
             f"  **Lane:** {' | '.join(LANES)}")
    if declared.group(1).strip() not in LANES:
        deny(f"BLOCKED: unknown lane {declared.group(1)!r}.\n\n"
             f"check-validation.sh dispatches on the exact spelling, so a lane it will later reject must "
             f"not pass here. One of: {', '.join(LANES)}. `Mixed` is not a lane; declare a primary lane "
             f"and add `mixed_pr: true` on its own line.")

    # --- 4. Consolidation, only once a plan is big enough for the question to mean anything
    if len(body) > 4000 and not re.search(r"(?i)consolidat", body):
        deny("BLOCKED: this plan is long enough to be consolidating something and does not say what.\n\n"
             "Name the dominant root, its one owner, and the consolidation sites — or write "
             "`Consolidation: none` and say why.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
