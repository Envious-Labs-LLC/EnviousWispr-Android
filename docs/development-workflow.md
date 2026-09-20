# Development workflow

The tracked, backed-up description of how work moves through this repo. The operating rules themselves
live in gitignored `.claude/` (a founder decision, not a cleanup), so this file is the part a fresh clone,
a Codex session, or a plain shell can always read. It links the machinery added by the 2026-09-13 dev
hygiene port (`dev-hygiene-port-plan.md`).

## One task, one worktree, one branch, one PR

Never build in the main checkout. Each task gets its own worktree under `.claude/worktrees/<name>`, on a
fresh branch from `origin/main`.

- A Claude session uses the `EnterWorktree` tool at the start and `ExitWorktree` after the merge.
- A shell or Codex session with no such tool uses `scripts/worktree-lifecycle.sh create <name>`, which
  makes the same one-branch-from-origin/main worktree.

## Cleaning up when a task is done

Removal is never a hand-deletion. It goes through the engine, which removes a worktree only after proving
its PR merged.

- `scripts/cleanup-merged-worktrees.sh --report` names finished worktrees and any leftover folders. It is
  read-only and runs on its own after every `git fetch`/`git pull` (the `post-sync-cleanup` hook) and at
  wind-down. It sees a worktree only once its branch is gone from origin, so the repository's
  "automatically delete head branches" setting is what makes a merged worktree visible to it; while that
  setting is off, delete the merged branch on origin by hand (`git push origin --delete <branch>`) and
  `git fetch --prune` before reading the report.
- `scripts/cleanup-merged-worktrees.sh --apply <path>` removes one named worktree: it must be a registered
  worktree with a MERGED PR at the branch's SHA and a clean tree, it rescues gitignored work first, it
  never uses `--force` (so the `third_party/llama.cpp` submodule can refuse an unsafe removal), and it
  refuses to remove the worktree you are standing in. An active session leaves first with `ExitWorktree`,
  then the engine reclaims the directory.
- `scripts/cleanup-local-branches.sh` reaps local branches whose remote is gone, on the same merged-PR
  proof; it never touches the current branch, a branch checked out in a worktree, or `main` /
  `internal-testing` / `uat/integration`.
- `scripts/sync-main.sh` fast-forwards local `main` to `origin/main` (fetch, then `--ff-only`); it never
  resets or stashes.

Recovery is always printed: every removed worktree or branch reports the SHA to recreate it.

## The safety check on every change

`main` is protected. A pull request into `main` must pass the required `build-check`
(`.github/workflows/pr-check.yml`) before it can merge; force-pushes and branch deletion are blocked.

- `build-check` runs `:app:testDebugUnitTest` + `:app:assembleDebug` against the same pinned
  dependencies the release build uses (`scripts/ci/setup-android-deps.sh`, shared with
  `scripts/release/build.sh`), and asserts the tests actually ran (`scripts/ci/check.sh`).
- A documentation-only PR skips the heavy build but still reports `build-check` as passing
  (`scripts/ci/classify-changes.sh`, which fails safe toward building on any doubt).
- The repo is public, so these Actions minutes are free.

## Getting a build to the founder: the three rungs

1. **Emulator + review.** Build and UAT on your own AVD (`.claude/knowledge/device-testing.md`); Codex
   code review to an explicit all-clear (`GR-REVIEW-GATE`).
2. **The founder's phone, the moment rung 1 is clean.** Push the branch, open the PR, and deliver the
   build through Play by pushing to the `internal-testing` pipeline branch (`docs/releasing.md`).
   Worktrees take turns in push order and never force-push. Tell the founder the build number.
3. **Only after he says it is good.** Merge the PR, fast-forward `main`, and push `main` to
   `internal-testing` so the track he updates through the Play Store carries only approved code. Then
   reclaim the task worktree with the cleanup engine. Never merge before his phone pass.

Phone findings come back as fixes on the same branch and go round again from rung 1.

## Before a Codex review

State plainly what you checked, and run the grep that would find a scattered or duplicate owner, BEFORE
asking Codex (`.claude/rules/workflow-process.md` RULE: self-review-and-grep-before-codex). This is a rule,
not yet a mechanical gate on this repo (see `dev-hygiene-port-plan.md`).

## Fresh clone setup

`scripts/bootstrap-dev.sh` arms the git hooks and restores the local hook registration. A clone does NOT
get the gitignored `.claude/` brain (rules, knowledge, session log) or `docs/internal/`; that content is
not backed up by git, and where to back it up privately is a founder decision, still open.
