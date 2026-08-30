# Guards

**Registration lives in `.claude/settings.json`, which this repository GITIGNORES.** The scripts are here in
`scripts/` so git can see them; the wiring that arms them cannot be tracked the same way. This file is that
wiring in a form git does keep, so a fresh clone can restore it.

The block below is therefore a second copy of a live file, which is normally a defect. It stays because
deleting it would leave a fresh clone with four scripts and no way to arm them, and the alternative —
tracking `.claude/` in a public repository — is a founder decision, not a cleanup. What makes the copy
safe is that it is CHECKED: `test-hooks.sh` parses this block and the live `.claude/settings.json` and
fails when they disagree, so the two cannot drift apart quietly. Edit either one and run that script.

```json
"hooks": {
  "PreToolUse": [
    { "matcher": "Edit|Write|MultiEdit", "hooks": [
      { "type": "command", "command": "$CLAUDE_PROJECT_DIR/scripts/hooks/check-protected-paths.py" },
      { "type": "command", "command": "$CLAUDE_PROJECT_DIR/scripts/hooks/check-plan-gates.py" } ] },
    { "matcher": "Bash", "hooks": [
      { "type": "command", "command": "$CLAUDE_PROJECT_DIR/scripts/hooks/command-safety.py" } ] }
  ],
  "SessionEnd": [
    { "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/scripts/hooks/session-end-check.sh" } ] }
  ]
}
```

## What each one is for

| Guard | Event | Denies | Silent when |
|---|---|---|---|
| `check-protected-paths.py` | Edit/Write/MultiEdit | a ship-path edit while on `main` | on a branch, or editing a local-only path |
| `command-safety.py` | Bash | a ship-path commit, an index-bypassing flag, or an explicit `-- <pathspec>` on `main`; a shell write into a ship path on `main` | on a branch, or any other command |
| `check-plan-gates.py` | Edit/Write/MultiEdit | a plan file missing its prior-context attestation, its User Rubric, or a valid lane | every file that is not a plan |
| `session-end-check.sh` | SessionEnd | nothing, it reports | the tree is clean and nothing is unpushed |

## The two things worth knowing before changing any of them

**Edit-time protection is BEST EFFORT. The commit gate is the guarantee.** A PreToolUse matcher on
Edit/Write sees the assistant's file tools and nothing else — not a shell heredoc, not `tee`, not another
process. Measured 2026-08-30: every file written by the session that designed these guards went through a
Bash heredoc, including the design document. `command-safety.py` covers the direct shell shapes, and it will
never be complete, because the set of ways to write a file is open. Every author must pass through
`git commit` to reach history, which is why that gate is the only one that can be.

**There is no adversary.** The only actor is Claude, often several instances at once. These enforce workflow
etiquette so cooperative agents do not corrupt `main`. The failure to prevent is a path-of-least-resistance
mistake, never a deliberate evasion. Do not build an evasion-proof parser: it is cost with no threat behind
it.

## Testing

```bash
scripts/hooks/test-hooks.sh          # on a branch: the allow halves
git checkout main && scripts/hooks/test-hooks.sh   # the deny halves
```

Every guard is asserted in both directions. The silent-when-clean half is the one that matters most: an
always-firing guard looks like protection while training the reader to skim past it.

## What a fresh clone will NOT have

These scripts are tracked. **The rules that explain them are not.** This repository gitignores `.claude/`
and `docs/internal/`, so a clone gets the guards and none of their reasoning:

| Lives in | Tracked? | What is lost |
|---|---|---|
| `scripts/` | yes | — |
| `.claude/rules/workflow-process.md` | **no** | the ten-step process, the four lanes, definition-of-done, and the seven rules that explain why each guard exists |
| `.claude/settings.json` | **no** | the registration that arms them, reproduced above |
| `.claude/knowledge/` | **no** | 14 files |
| `docs/internal/` | **no** | the port plan and its seven review rounds |

**A guard whose rule is unreachable is a denial nobody can understand**, and the first response to a denial
nobody understands is to look for the bypass. That makes this a correctness problem for the guards, not only
a backup problem.

Measured 2026-08-30: `.claude/` holds 35 files here and 263 on macOS, none tracked in either. The Windows
repository tracks its `.claude/`, so the three products already disagree. No Time Machine destination is
configured on this machine.

Deciding whether to track it is a founder call, not a session's. Until it is made, this table is the record
of what a clone is missing.
