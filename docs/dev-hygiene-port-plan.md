# Developer hygiene port plan (worktrees, cleanup, CI, delivery)

Status: PROPOSED, 2026-09-13. Author reviewed with Codex (grounded read-only against both repos).
Goal: make GitHub + worktree + UAT + cleanup + dev-build-push on EnviousWispr-Android a well-oiled
machine, matching the reliability the macOS EnviousWispr repo already has.

## Root cause

macOS stays clean because cleanup is a **scripted engine** (`cleanup-merged-worktrees.sh`), independent
of any editor tool, triggered by wind-down and by a fetch/pull report hook. Orphans get caught whether or
not a session remembered to clean up.

Android relies solely on a session calling the harness `ExitWorktree` tool. A Codex session, a plain
shell, or a crashed/compacted session leaves orphans with no backstop. Android has the *rule*
(`.claude/rules/workflow-process.md` one-worktree-per-project, 2026-09-12) but not the *machinery*. Same
gap produced: a lingering merged worktree, two empty leftover folders, ~8 dead local branches, local
`main` 23 commits behind origin, and zero CI on PRs or `main`.

## Android-specific hazards (must be honored by any implementation)

- **The `third_party/llama.cpp` submodule can make `git worktree remove` refuse.** Prove a safe,
  non-forced submodule deinit that does not affect peer worktrees before removing; otherwise RETAIN.
- **The harness owns the active worktree.** The engine must not fight it. Safe division of labor: the
  session calls `ExitWorktree({action:"keep"})` to release ownership and retain the directory, then the
  external script removes it. Never `ExitWorktree({action:"remove", discard_changes:true})` as the
  cleanup path once the engine exists.
- **Plain-shell / Codex fetches cannot be caught by Claude PostToolUse hooks.** Provide a `sync-main.sh`
  and `cleanup-*` scripts that shell and Codex sessions call directly.
- **`.gradle`-only leftover shape proves nothing.** Report and quarantine exact paths for manual
  inspection; never sweep-delete by shape.
- **Public-repo GitHub Actions are $0** (standard Ubuntu runners). CI does not by itself violate
  GR-NO-CLOUD-SPEND, but verify repo visibility and storage billing before enabling; paid capacity needs
  founder approval.

## Plan, ordered by leverage

### 1. Worktree lifecycle + cleanup engine (HIGHEST LEVERAGE, do first)

New tracked scripts: `scripts/cleanup-merged-worktrees.sh`, `scripts/rescue-worktree-artifacts.sh`,
`scripts/worktree-lifecycle.sh`. Document `.claude/worktrees/<issue>-<slug>` layout and branch naming in
`.claude/rules/workflow-process.md`.

- Two modes: `--report` (read-only) and `--apply <path...>` (destructive, only explicitly-named paths; an
  unscoped `--apply` refuses).
- Removal gates (ALL required, else report+retain): path is a registered, unlocked, non-root, clean
  worktree; repo/base match; a MERGED PR whose `headRefOid` equals the branch SHA (squash-merge-aware, no
  ancestry requirement); no active build/UAT; ownership released by the harness.
- Rescue gitignored `.validation/`, `docs/internal/`, and local `.claude/` rules outside the target
  before removal. Handle the `llama.cpp` submodule restriction (prove-safe-or-retain).
- Abandoned/unmerged worktrees are reported and RETAINED; deleting them is the founder's call.

### 2. Backstops + clear the current backlog

- `scripts/hooks/post-sync-cleanup.sh`: on a successful fetch/pull, run `--report` only, 15-second budget.
  Register in `.claude/settings.json`; document in `scripts/hooks/README.md`.
- Extend `scripts/hooks/session-end-check.sh` to also report; add an explicit scoped-cleanup step to the
  `.claude/rules/session-behavior.md` wind-down.
- `scripts/sync-main.sh` for shell/Codex: fetch/prune, verify root ownership and no local changes, then
  `merge --ff-only origin/main`. Never reset or stash away work.
- `scripts/cleanup-local-branches.sh` (report / scoped-apply). Gone-upstream is a candidate, not
  permission; apply the same PR/SHA proof; exclude checked-out and integration branches
  (`internal-testing`, `uat/integration`); record recovery SHAs; park unfinished work as annotated tags.
- One-time: clear the ~8 dead local branches and fast-forward local `main` to origin.

### 3. Minimal required PR CI, then protect `main`

- `.github/workflows/pr-check.yml` + `scripts/ci/check.sh` + `scripts/ci/classify-changes.sh`. Reuse the
  dependency setup from `scripts/release/build.sh` (Java 21, pinned SDK/NDK/CMake, recursive submodule,
  checksum-verified sherpa-onnx AAR).
- Run `:app:testDebugUnitTest` + `:app:assembleDebug` (NOT whole-project `build`; benchmarks are
  unrelated). Assert fresh nonzero test XML. Cache deps, cancel superseded runs, SHA-pin actions, expose
  one always-reporting `build-check`. Skip docs-only changes inside the workflow.
- After watching it pass/fail on a few PRs: require `build-check`, require PRs on `main`, block
  force-push and branch deletion. This adds a debug validation, not a second signed Play build.

### 3b. Repair the self-review gate

`~/.claude/bin/codex-run` is hardcoded to the macOS repo, so the pre-Codex self-review gate silently
skips on Android. Adapt the wrapper to recognize Android's git common directory; add
`scripts/validate-selfreview-marker.sh` binding a single-use attestation to repo + worktree + HEAD +
change digest. Test stale/missing/cross-worktree receipts. Risk: global-wrapper regression, so gate it.

### 4. Make three-rung delivery reproducible

- `scripts/finish-task.sh`; update `docs/releasing.md` and `.claude/rules/github.md`.
- Keep the three rungs: emulator+review, then Play internal to the founder's phone, then merge + main.
- Arm `gh pr merge --squash --auto --delete-branch` only AFTER phone approval; run the remote merge
  outside the local checkout so local-branch removal stays engine-owned.
- Serialize the delivery cycle: concurrent unapproved features must not ride in the "approved" build.
  Reconcile `main` into `internal-testing` without force-push, verify publication, then clean the task
  worktree. Defer the macOS-style duplicate main-build CI and hourly cron.

### 5. Fix the knowledge contradictions and the no-backup risk

- Correct `CLAUDE.md`, `docs/internal/workflow-enforcement-port-plan.md`, and
  `.claude/knowledge/current-state.md` so nothing still says "Android runs one worktree / revisit if
  Android adopts worktrees." Point them at the 2026-09-12 one-worktree rule.
- Add `docs/development-workflow.md` (tracked) and `scripts/bootstrap-dev.sh` for reproducible setup.
- The whole hygiene system lives in gitignored `.claude/` with no remote backup. Privately back up the
  sensitive brain files (do NOT blanket-unignore `.claude/`). This plan doc lives in tracked `docs/` for
  that reason.

## What NOT to port

macOS LaunchServices/app-bundle deregistration, root dev-app rebuild after merge, cloud-review polling,
and the 30-minute review waiver. None apply to the Android/Play flow.

## Acceptance

Extend `scripts/hooks/test-hooks.sh` with refusal/success controls (submodule present, competing owner,
unmerged work, cross-worktree receipt). Every destructive path must refuse without an explicit scope.

## Open items to verify at implementation time (Codex could not reach the GitHub API)

Live `main` protection state; open issue #13 status (tracks the no-CI gap). Each infra change ships only
after a Codex ALL-CLEAR per GR-REVIEW-GATE (CI, hooks, and the global wrapper are all infrastructure).
