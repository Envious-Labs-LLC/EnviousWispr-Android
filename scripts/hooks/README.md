# Guards

## Arming them in a fresh clone

Two settings, and neither is optional. The commit-hook FILES are tracked; git does not arm them, so run
these once in every clone:

```bash
git config core.hooksPath scripts/githooks         # arms the commit check
git config branch.main.mergeOptions --no-ff        # routes merges into main through it
```

**The second is not a preference.** A fast-forward merge creates no commit, so no hook of any kind runs
and `main` moves to a branch's ship-path work unexamined — measured, not assumed. Forcing a merge commit
when merging INTO main is what routes it through `pre-merge-commit`; other branches are unaffected.

`test-hooks.sh` asserts this checkout is armed, so forgetting it is a red control rather than a silent
absence.

The second is the `.claude/settings.json` block below, which has to be restored by hand for the reason
that follows.

**Registration for the PreToolUse and SessionEnd guards lives in `.claude/settings.json`, which this
repository GITIGNORES.** The scripts are here in
`scripts/` so git can see them; the wiring that arms them cannot be tracked the same way. This file is that
wiring in a form git does keep, so a fresh clone can restore it.

The block below is therefore a second copy of a live file, which is normally a defect. It stays because
deleting it would leave a fresh clone with four scripts and no way to arm them, and the alternative —
tracking `.claude/` in a public repository — is a founder decision, not a cleanup. What makes the copy
safe is that it is CHECKED: when `.claude/settings.json` exists, `test-hooks.sh` parses this block and
that file and fails when their `hooks` objects disagree. In a fresh clone the live file is legitimately
absent, and the suite says so rather than passing quietly. Edit either one and run that script.

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
| `command-safety.py` | Bash | a recognised shell write into a ship path on `main`; `--no-verify` on any branch | on a branch, or any other command |
| `check-plan-gates.py` | Edit/Write/MultiEdit | a plan file missing its prior-context attestation, its User Rubric, a valid lane, or — past a size threshold — a consolidation answer | every file that is not a plan, and any edit whose result it cannot reconstruct |
| `session-end-check.sh` | SessionEnd | nothing, it reports | the tree is clean and nothing is unpushed |
| `../githooks/pre-commit` | git's pre-commit | any commit whose staged set adds, changes, renames or DELETES a ship path, on `main` | on a branch, or a commit touching nothing shipped |
| `../githooks/pre-merge-commit` · `pre-applypatch` | git's merge and `am` events | the same, delegated | the same |

## The two things worth knowing before changing any of them

**ASK GIT; DO NOT MODEL GIT.** This is the one lesson that cost the most rounds, and it applies twice.

The commit check used to live in `command-safety.py`, which read the TEXT of a Bash command and tried to
predict whether a commit was about to happen and what it would contain. Five review rounds each found
another form it got wrong: an option value that looked like a flag, `--dry-run`, a bare positional
pathspec, `-S` swallowing `-a`, `git -C.`, `env FOO=1 git`, a bare `&`, a newline. That is not eight
defects. It is one — a private parser for somebody else's grammar has no last divergence — and the same
mistake produced four separate defects in the validation fingerprint before that script was rewritten to
ask `git write-tree` instead of describing what git would record.

`githooks/pre-commit` runs at the moment the answer exists, so `git diff --cached` is the real staged set
rather than a guess at one. `-a`, an explicit `--` pathspec, a bare positional pathspec,
`--pathspec-from-file`, `--interactive`, `--amend` and a user alias all arrive there identically. Its
controls run real commits rather than feeding strings to a parser.

**A merge and `git am` use DIFFERENT git events**, so they are not covered by that file alone —
`pre-merge-commit` and `pre-applypatch` sit beside it and delegate, and `pre-merge-commit` only fires for
a merge that CREATES a commit, which is why arming also forces `--no-ff` into main. They exist because the claim was
checked against git's own hook templates rather than assumed; without them a merge onto `main` carrying a
ship path passed unexamined. **`git rebase` runs no pre-commit hook for its replayed commits at all.**
That is a real gap and it is stated rather than papered over.

**The edit-time and write-shape layers are still best effort, and the branch is still the protection.** A
PreToolUse matcher on Edit/Write sees the assistant's file tools and nothing else — not a shell heredoc,
not `tee`, not another process. Measured 2026-08-30: every file written by the session that designed
these guards went through a Bash heredoc, including the design document. The ways to write a file are
open-ended, so that half will never be complete. It does not need to be: every write must reach history
through a commit, and that is where the check is now.

**There is no adversary.** The only actor is Claude, often several instances at once. These enforce workflow
etiquette so cooperative agents do not corrupt `main`. The failure to prevent is a path-of-least-resistance
mistake, never a deliberate evasion. Do not build an evasion-proof parser: it is cost with no threat behind
it.

## Testing

```bash
scripts/hooks/test-hooks.sh          # every control, from any branch
```

Every guard is asserted in both directions, and the suite no longer needs you to be on `main` to reach the
deny halves: it builds a throwaway git repository that is on `main`, copies the guards into it, and runs
them there. The guards derive their repository root from their own location, so the shipped bytes execute
and no test seam is added to a guard.

Three outcomes, not two. `allow`, `deny`, and `error` — because a guard that crashed produces no stdout,
and a helper that read empty stdout as `allow` would have passed every allow control while nothing ran.

The allow halves matter most: an always-firing guard looks like protection while training the reader to
skim past it.

**The commit check is exercised by RUNNING COMMITS, not by feeding strings to a parser.** The suite builds
a second throwaway repository and drives real git operations through it in both directions, including a
paired run with the hook deliberately unarmed so a control cannot pass without the hook. Read the
`githooks/pre-commit` section of `test-hooks.sh` for the current population; a count copied here is a
number with nothing linking it to the file.

## What a fresh clone will NOT have

These scripts are tracked. **The rules that explain them are not.** This repository gitignores `.claude/`
and `docs/internal/`, so a clone gets the guards and none of their reasoning:

| Lives in | Tracked? | What is lost |
|---|---|---|
| `scripts/` | yes | — |
| `.claude/rules/workflow-process.md` | **no** | the ten-step process, the four lanes, definition-of-done, and every rule that explains why each guard exists (`grep -c '^## RULE:' .claude/rules/workflow-process.md`) |
| `.claude/settings.json` | **no** | the registration that arms the PreToolUse and SessionEnd guards, reproduced above |
| `core.hooksPath` | n/a, it is local config | the commit check, until `git config core.hooksPath scripts/githooks` is run |
| `.claude/knowledge/` | **no** | its whole contents (`find .claude/knowledge -type f \| wc -l`) |
| `docs/internal/` | **no** | the port plan and its seven review rounds |

**A guard whose rule is unreachable is a denial nobody can understand**, and the first response to a denial
nobody understands is to look for the bypass. That makes this a correctness problem for the guards, not only
a backup problem.

Regenerate the local inventory with `find .claude -type f | wc -l` rather than reading a number here.
The Windows repository tracks its `.claude/`; this one and macOS do not, so the three products already
disagree. Measured 2026-08-30: no Time Machine destination is configured on this machine.

Deciding whether to track it is a founder call, not a session's. Until it is made, this table is the record
of what a clone is missing.
