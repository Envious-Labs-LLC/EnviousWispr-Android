# Guards

## Arming them in a fresh clone

One setting. The hook FILES are tracked; git does not arm them, so run this once in every clone:

```bash
git config core.hooksPath scripts/githooks
```

That one setting arms both git hooks. `test-hooks.sh` asserts this checkout has it, so forgetting it is a
red control rather than a silent absence.

The PreToolUse and SessionEnd guards are armed separately, by the `.claude/settings.json` block below,
which has to be restored by hand for the reason that follows.

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
| `check-plan-gates.py` | Edit/Write/MultiEdit | a plan file missing its prior-context attestation, its User Rubric, a valid lane, or — past a size threshold — a consolidation answer. All four run before anything is refused, so one denial lists every failure and preserves the draft | every file that is not a plan, and any edit whose result it cannot reconstruct |
| `session-end-check.sh` | SessionEnd | nothing, it reports | the tree is clean and nothing is unpushed |
| `../githooks/pre-commit` | git's pre-commit | any commit whose staged set adds, changes, renames or DELETES a ship path, on `main` | on a branch, or a commit touching nothing shipped |
| `../githooks/reference-transaction` | every update to `refs/heads/main` | any move — forward OR divergent — onto ship-path commits that are not on the upstream | a reviewed upstream fetch or pull, including `--rebase` with no local ship commits; a reset backwards; an amend of the current commit; a move carrying nothing shipped |

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
`--pathspec-from-file`, `--interactive`, `--amend` and a user alias all arrive there identically.

**Then the same lesson repeated one level out, and it took three rounds to see.** `pre-commit` fires on
one git event. A merge, `git am` and a rebase raise DIFFERENT events or none, and a fast-forward, a
`git reset --hard <branch>`, a `git checkout -B main` and a `git update-ref` create no commit at all —
every one of them moves `main` without `pre-commit` running. The answer was not more commit-event hooks:
two of those were written and then deleted, along with a `--no-ff` merge setting, because
`githooks/reference-transaction` guards the REF that all of them were proxies for. Two hooks and one
setting now cover more than three hooks and two settings did.

**The hard half of that hook is what it must NOT refuse**, because it fires inside `fetch`, `pull` and
every other armed ref transaction, and a version that refused work arriving from the remote would make
the repository unusable while looking exactly like protection. An ordinary clone is not among them: the
setting that arms it does not exist until the clone finishes. Three things pass without inspection. A commit already reachable from `main`'s CONFIGURED UPSTREAM,
resolved with `for-each-ref` and never a hard-coded `origin` — that refused a legitimate pull in any
checkout whose remote is named otherwise.

**Once `main` is deleted its upstream configuration goes with it, and two fallbacks were tried before the
guess was removed.** Any remote-tracking ref treats a pushed feature branch as reviewed. A remote's
DEFAULT BRANCH looks tighter and is not: in a fork workflow `origin` is your own fork and its default
branch is `main`, so pushing unreviewed work there would have authorised recreating `main` at it. Nothing
in git says which remote is canonical once the branch config is gone, so nothing is guessed. One optional
setting says it, and it survives the branch because it is not tied to it:

```bash
git config workflow.mainUpstream refs/remotes/upstream/main   # only if you need it
```

Without it, recreating a deleted `main` is judged rather than trusted. That refuses a rare operation with
a clear message; the alternative was a standing hole reachable by pushing to your own fork. A move BACKWARD, which is how a mistake
gets undone. And a new commit with the SAME TREE AND THE SAME PARENTS as the current one, which is
`git commit --amend -m "reword"` and changes no file and no earlier commit.

**That third one is where a looser rule breaks.** Tree equality alone waves through a divergent history
that changes a ship path, reverts it, and lands on the same tree — carrying both commits. Requiring the
parents to match pins the exception to a rewrite of exactly one commit. Two controls hold each other
honest: the reword must pass and the divergent revert must fail.

Everything else is judged, forward and divergent alike: reading "not forward" as "backward" let a rebase
and a reset onto a divergent branch straight through, under a comment claiming rebase was covered.

The enumeration reads every commit in the range, because two endpoints cancel a change-and-revert out
entirely. It uses `git log --cc`, and the choice between that and `-m` is the whole question for merges: a
merge shows none of its own conflict resolution without one of them, and a resolution is exactly where a
smuggled path would live — but `-m` reports the merge against EACH parent, so merging reviewed upstream
ship work into a local docs branch listed every upstream path as local and refused an ordinary merge. The
combined diff reports only what differs from ALL parents, which is the resolution itself. Controls hold
both ends: the resolution-added path denies, the ordinary merge allows.

**One accepted gap:** where history is shallow or incomplete, an ancestry test cannot answer and the hook
allows the move. It fails open on its own errors, unlike `pre-commit`, because it runs inside `fetch` and
`pull` and a broken version failing closed would break the repository rather than protect one branch.

**The edit-time and write-shape layers are still best effort, and the branch is still the protection.** A
PreToolUse matcher on Edit/Write sees the assistant's file tools and nothing else — not a shell heredoc,
not `tee`, not another process. Measured 2026-08-30: every file written by the session that designed
these guards went through a Bash heredoc, including the design document. The ways to write a file are
open-ended, so that half will never be complete. It does not need to be: to reach `main` a write has to
move that ref, and `reference-transaction` is on it — which is the whole reason the check is there rather
than only on the commit.

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
