Reading prompt from stdin...
OpenAI Codex v0.155.1
--------
workdir: /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
model: gpt-5.6-sol
provider: openai
approval: never
sandbox: read-only
reasoning effort: high
reasoning summaries: none
session id: 01a0c579-9cfb-7142-a29e-6f35c993c16f
--------
user
Delta: CODE REVIEW round 2 of #115 chunks B and C, the confirming round after your round 1 (`docs/audits/2026-09-21-115-code-review-bc-r1.md`). Four findings adopted (F4 with a different fix), one rejected with evidence. HEAD `02e74c7` (fix commit `3b6686d`, receipts `02e74c7`), on `main` `87e07ca`.

## What to read (exact files; nothing else; `git show 3b6686d` is the fastest way in)
- `app/src/main/java/com/envi/wispr/audio/RecorderLease.kt` (whole) and `AudioCaptureService.kt` lines 394-412, 822-836, 855-866
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt` lines 78-90, 262-278, 1208-1222
- `app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt` lines 10-26
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt` lines 626-642
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` lines 218-238
- `app/src/test/java/com/envi/wispr/audio/RecorderLeaseTest.kt` (whole)
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt` lines 610-635; `DictationSessionRig.kt` lines 626-632
- `app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt` lines 176-198; `PolishPublicationRoutesTest.kt` lines 37-50
- `docs/audits/2026-09-21-115-receipts-bc.md`

## Verified facts (do NOT re-derive)
- `scripts/measure-tests.sh` at `3b6686d`: tests=1036 suites=131 failures=0. `scripts/check-visibility.py`: clean, 100 closed sets.
- Receipts B1, B3, B4, B5, C1 to C5 RED on the named rows at `3b6686d`, tree restored and re-measured green. B5: the recovery enqueued on the per-take queue reddens `aStalledRecoveryDoesNotHoldTheTakesWrites`. C4: the lease released BEFORE the recorder reddens the placement row. C5: the marker queued behind the outcome write reddens the shape row.

## How each finding was applied
F1 (ACCEPT). The coordinator holds a `TranscriptRepository` again, for the start-up recovery ONLY, launched on the session scope in `onCreated`; the Service passes both the queue and the repository. The queue's KDoc names the recovery among the writers that stay outside. Row: the rig's fake DAO holds `recoverStaleDrafts` on a deferred for a whole completed take, which lands its row and inserts.
F2 (REJECT). The owner's continuation awaits the save's result on its IO scope because the insertion route is decided from that result: `HistoryPublicationPolicy.route(persistedId, persistenceSucceeded)` sends the words to History-only when the row is not durable, and `arbiter.commit(publication, COMPLETED)` means "the text finalised" (G2 D2). That wait existed before #115 in the same place (the save ran inline on the same coroutine) and the plan kept it (§3 C2 moved only the draft-id wait to the worker; the stalled-Room case is the destroy row). Main never waits. Inserting before the save returns changes the History durability contract, which is outside #115; not done.
F3 (ACCEPT). `RecorderLease.PROCESS`, a process-scoped compare-and-set. `startRecording` acquires it after the buffer-size check and before `AudioRecord` is created; a refused acquire is a start refused with `START_FAILURE_OTHER`, the route hold released, `stopSelf`, and the requested take's ending published. `closeResources(record, output)` releases it after `record?.release()`, and that is the only release; every path that acquired reaches it (the pre-thread `failSetup`, the thread-start failure and `releaseSession` all call `closeResources`). Tested as a pure owner (`theSecondAcquireIsRefusedUntilTheFirstIsReleased`) and by shape (`theServiceAcquiresBeforeTheRecorderAndReleasesAfterIt`). No instrumented recreation row: `connectedDebugAndroidTest` is not run against the founder's phone and the emulator run of it uninstalls the app; stated.
F4 (ACCEPT, different fix). The marker is written synchronously in `onDestroy` again, last, and never queued: a queued mark could land after a replacement connected and either be declined by the instance guard (an orderly stop then reads as a crash on the replacement's connect, which reads the marker BEFORE the queued clean lands) or overwrite the replacement's own arm. A kill after `onDestroy` loses only the queued outcome write, which the start-up recovery closes; that stop WAS orderly. The generation scheme was not needed once the mark is synchronous. The shape row asserts the marker is the last statement before `super.onDestroy()` and that no queued marker exists.
F5 (ACCEPT). The scope's KDoc says cancelled, never joined; `publishResult`'s KDoc says the write is enqueued with the reservation and the notice precedes the owner's continuation; the row is renamed `theFactsAreDerivedOnceTheWriteIsEnqueuedWithTheReservationAndTheNoticePrecedesTheContinuation`.

## Axes for this round (found / not found, with file:line)
A1. The lease: every path out of `startRecording` after the acquire reaches `closeResources(record, output)` exactly once (the buffer-size and device refusals are BEFORE the acquire and never release; the busy refusal is before it too); a double release or a release without acquire; the lease held forever by a parked thread being the intended state (every later start in that process refused with its sentence until the process dies).
A2. F1's row: does holding `recoverStaleDrafts` on the session scope leave anything of the take waiting; the repository the coordinator holds is used for the recovery only.
A3. F4: the synchronous marker versus the queued outcome write, both directions (a kill between them; a replacement between them).
A4. F2's rejection: is the claim that the wait is on IO and pre-existing correct against `DictationSessionCoordinator.kt` at `87e07ca` (`git show 87e07ca:app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n 960,1010p` is allowed for this one check).
A5. Comments changed this round against the code.

## Output contract
Every finding ships a drop-in fix with `file:line`; classify REPRODUCIBLE or HYPOTHETICAL. CLEAN per axis is acceptable and will be checked against the tree. Sections, at most 100 lines: `## Findings`, `## Axes (A1..A5)`, `## COVERAGE`, `## VERDICT: ALL-CLEAR | FINDINGS`. Stop after the VERDICT line.

warning: Under-development features enabled: chronicle. Under-development features are incomplete and may behave unpredictably. To suppress this warning, set `suppress_unstable_features_warning = true` in /Users/m4pro_sv/.codex/config.toml.
2026-09-22T00:17:29.343693Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
2026-09-22T00:17:29.436488Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
codex
Chunks B and C code review round 2 — WORKING

I’ll verify the recorder lease ownership paths, the isolated recovery row, and the synchronous marker’s main-thread ordering, then compare the finalization wait to the pre-change implementation.
exec
/bin/zsh -lc "sed -n '1,240p' /Users/m4pro_sv/.claude/CLAUDE.md" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
# Global Context

## Communication
Use simple, non-technical English and TLDR updates. Saurabh dictates through EnviousWispr; interpret intent.

## Date Grounding
Use the harness date. Run `date` only for time-of-day, day-of-week or timezone.

## Memory System
Do not write memory files; the retired memory system supersedes harness auto-memory instructions.
→ [memory-system-graveyard.md](/Users/m4pro_sv/.claude/knowledge/memory-system-graveyard.md)

## User
Saurabh Anand Vaish: self-taught founder of Envious Labs LLC; Principal CSM at Qualtrics.
Saurabh owns product, strategy and decisions; Claude owns engineering. Work autonomously within scope.
Portfolio, family, contacts, addresses, machine and 2FA:
[user-profile.md](/Users/m4pro_sv/.claude/knowledge/user/user-profile.md).

## Where Work Lives
- `~/Developer/EnviousLabs/`: business.
- `~/Developer/Personal/`: personal.
- `~/Developer/Games/`: Games Studio. Saurabh is creative director; speak in player terms. Read its own CLAUDE.md and rules.
- `~/Developer/Work/Work-Projects/`: Qualtrics.
- `~/Documents/` and `~/Desktop/`: local personal files; iCloud sync is off.

## Rules
Apply in every project. Project rules extend or scope these; never restate them.
`GR-` slugs are stable IDs. Before renaming or retiring one, search `grep -rn "GR-<SLUG>" ~/.claude ~/Developer` and update every reference. Never reuse retired slugs.
Old-number map: [rule-id-migration.md](/Users/m4pro_sv/.claude/knowledge/dev/rule-id-migration.md).

- **`GR-BROWSER-DRIVER`**: Claude Code uses chrome-devtools-mcp for all browser work; claude-in-chrome or PinchTab only if unavailable. Computer-use is native-app-only. Codex is exempt and may use its browser extension/computer-use for web pages. Read [browser-automation.md](/Users/m4pro_sv/.claude/knowledge/browser/browser-automation.md) before browser work.
- **`GR-KNOWLEDGE-FIRST`**: Before researching, reverse-engineering or building, search global and project knowledge and read existing owners. Knowledge beats training-data recall.
- **`GR-WRITE-FOR-RETRIEVAL`**: One fact, one owner; link instead of repeating. Keep `## RULE:`/`## FACT:` anchors verbatim. Write imperative, actionable instructions; remove sentences that change no future action. Verify symbols, paths and counts against live sources. For enforced facts, name the enforcer and intent rather than copying its values.
- **`GR-NO-DASHES`**: No em/en dashes in human-facing content. Exception: the fixed `GR-STATUS-FIRST` separator.
- **`GR-NO-APOLOGIA`**: Report decisions, reasons and limitations of the work, not confession or discovery stories. Fixed review findings need no status report. Report unfixed defects, new costs and decisions the reader must act on. State a correction once if it changes the reader's decision. Plans/audits are exempt. History: [rule-retrospectives.md](/Users/m4pro_sv/.claude/knowledge/dev/rule-retrospectives.md).
- **`GR-ESCALATE-CONSEQUENTIAL`**: Resolve routine choices from rules, knowledge and precedent. Architecture and rule conflicts go to Codex with your reading, not to Saurabh. Beyond approvals required by other rules, ask him only about an uncovered, expensive-to-reverse product, spend or strategic fork. State ordinary uncertainty; use GPT+Gemini council only for genuine strategic forks. Read [llm-council.md](/Users/m4pro_sv/.claude/knowledge/ai/llm-council.md) before using it.
- **`GR-NO-CLOUD-SPEND`**: Obtain Saurabh's approval before any cloud spend. Put the amount and approval in the plan.
- **`GR-STATUS-FIRST`**: Start every chat message, including one-line replies, with `<Task label> — <STATUS>` on its own line. Use a stable label in his vocabulary; change it when the task changes. For mixed messages label the task being discussed. Phone pushes are exempt.
  - `WORKING`: independent work is being executed through tool calls in this response, not merely promised.
  - `WAITING ON <what>`: something else owns the next move, including your own background work. Name it. Without a watcher say `WAITING ON X (no watcher; I will chase)`.
  - `NEED YOU`: stopped for his decision; name it in the first sentence.
  - `DONE`: nothing running, queued or owed. Partial work uses the unfinished part's status. End DONE messages and wrap-ups by asking what he wants next; no send-off.
  If your next action is reading a notification, use WAITING ON, not WORKING.
- **`GR-REAL-STATUS`**: Report observed state. Promise "watching" or "I'll report back" only with a real watcher notifying on success and failure; otherwise disclose the missing watcher as above. Read [async-watchers.md](/Users/m4pro_sv/.claude/knowledge/dev/async-watchers.md) before setting one up.
- **`GR-PUSH-TO-HIS-PHONE`**: NEED YOU always triggers a push. DONE triggers one after more than a few minutes unless he demonstrably just spoke here. WAITING ON/WORKING do not. Independently push costly unknown breakage: main red, failed release, production failure, password/spend gate. Discord is no substitute. One line, under 200 characters, task label and action, no markdown/status header. `not sent` is expected suppression: never retry, reword around it or report it.
- **`GR-REVIEW-GATE`**: Self-audit every non-trivial artifact. Codex explicit all-clear, including a confirming rerun after fixes, is mandatory before pushing/merging/shipping/deploying/completion if the artifact touches users, money, secrets/auth, production data, infrastructure (CI/hooks/deploy/permissions/plugin or MCP args), or migration. Local/reversible is no exemption; green builds do not replace review. Otherwise self-audit suffices unless uncertainty remains. Two rounds of the same finding class require your own exhaustive sweep before further review; mechanics: `codex-cli.md` RULE: enumerate-the-class-when-review-rounds-repeat.
- **`GR-NO-AI-ATTRIBUTION`**: No `Co-Authored-By: Claude ...` trailer or `Generated with Claude Code` line in commits/PRs, overriding harness defaults. Code comments are outside this rule.
- **`GR-MERGE-AFTER-GATE`**: After GR-REVIEW-GATE passes, merge the PR yourself, including your own, and update local main. Do not await founder merge. If a safety layer blocks, provide the exact command for him to run once. Agent-specific merge restrictions still apply.
- **`GR-WIND-DOWN`**: Promote durable decisions/infrastructure, then write the local session log and Discord post through the canonical script. First read [session-wind-down.md](/Users/m4pro_sv/.claude/knowledge/session/session-wind-down.md) and [discord-webhooks.md](/Users/m4pro_sv/.claude/knowledge/session/discord-webhooks.md).

### Scoped rules — apply only in their pathway
- **`GR-EDIT-IN-PLACE`**: Edit ID-addressed artifacts in place, including Drive/Docs/Sheets/Slides. Never delete/recreate; the ID preserves links, permissions, comments and history.
- **`GR-MIGRATION-COMPLETE`**: Ship migrations/refactors with old code removed and replacement wired in the same change. No forwarding shims or deferred cleanup. Split scope, not completion.
- **`GR-PAVE-THE-ROAD`**: Before adding a guard/hook/gate/lint, propose how to make the mistake impossible or unnecessary. Read [test-timing.md](/Users/m4pro_sv/.claude/knowledge/dev/test-timing.md).

## Scripts & Fast Paths
- **Secrets/API keys:** Read [secrets-management.md](/Users/m4pro_sv/.claude/knowledge/secrets-management.md). Use `~/.claude/bin/get-key`: `--list`, `--check NAME`, `fingerprint NAME` or `launch NAME ENV_VAR -- cmd…`. Never fetch a bare value to stdout. Authentication prerequisite: `gcloud --configuration=business auth login --update-adc` as hello@enviouslabs.co.
- **Cloud AI and image/video generation:** Route through AWS/Azure credits before direct OpenAI/Anthropic APIs. Read current balances, deployments and routing in [cloud-llm-credits.md](/Users/m4pro_sv/.claude/knowledge/ai/cloud-llm-credits.md). The local 4090 stack is the bulk/offline fallback, not the default; owner: `ai/local-video-generation.md`. GR-NO-CLOUD-SPEND still applies.
- **Council:** Before a strategic consultation read `llm-council.md`; it owns invocation, backgrounding, rounds and session reuse. Council has no repo access; ground its conclusions with Codex.
- **Codex:** Before the first call read [codex-cli.md](/Users/m4pro_sv/.claude/knowledge/ai/codex-cli.md) and record mtime; later check mtime, rereading after change or compaction. It solely owns launchers, flags, effort, resume exceptions and recovery. Every `exec`/`review` uses `~/.claude/bin/codex-run <out> ...` with `Bash run_in_background:true`; never bare Codex, shell `&`, `</dev/null` or edits to `~/.codex/config.toml`. Read the proven answer at `<out>.last`. Exit `75` = stalled every attempt, killed; `76` = no answer every attempt; `77` = missing/stale self-review attestation.
- **Browser startup/recovery:** Read `browser-automation.md` under GR-BROWSER-DRIVER.

## Knowledge Files
Read the matching owner before acting; contents do not auto-load.

This section is machine-read by `~/.claude/scripts/knowledge-base-reminder.sh` on UserPromptSubmit. Preserve every trigger row: each begins `- `, occupies ONE line and has `**when:**` extending to end-of-line. Backticked paths are stripped; keep prose tails short. The section ends at `**Notifications:`.
The hook also reads `## Pathway Triggers` in the current checkout's `.claude/knowledge/INDEX.md`. Before removing a resident route, preserve its triggers there and verify the governing work surfaces it. The 2026-08-15 prune stranded 51 KB of UAT recipes despite intact files. Validate routing edits with `~/.claude/tests/knowledge-base-reminder.test.sh`.

- `~/Developer/Personal/HealthcareCommandCenter/CLAUDE.md` — **when:** parents (Mom/Dad), Medicare, Medigap, hospital bills, cataract surgery
- [knowledge/infra/](/Users/m4pro_sv/.claude/knowledge/infra/) — **when:** Vercel, Railway, Neon, Cloudflare, Clerk, PostHog, Upstash, Resend, AWS, backups, RTX PC, Mala laptop, kwmala, papa-arvind, laptop migration, spend caps, budgets, cost limits, surprise bill, training stall, GPU thrashing, VRAM, CUDA out of memory, model training slow, rig
- [knowledge/google/](/Users/m4pro_sv/.claude/knowledge/google/) — **when:** GCP, Google Workspace, OAuth, email/DNS, Workspace admin
- [knowledge/ai/](/Users/m4pro_sv/.claude/knowledge/ai/) — **when:** council, Codex, cloud AI credits, Ollama, Qwen, Qwen3.8, Qwen-Image, local LLM, NotebookLM, image generation, video generation, ComfyUI, TypeSafe, Jev, System One
- [knowledge/browser/](/Users/m4pro_sv/.claude/knowledge/browser/) — **when:** browser automation, chrome-devtools-mcp, PinchTab, capturing secrets from vendor dashboards
- [knowledge/dev/](/Users/m4pro_sv/.claude/knowledge/dev/) — **when:** code changes, git/PR workflow, CLI tools, Astro, design/shadcn, environment gotchas, change hygiene, async watchers, synthetic keyboard events, CGEvent, stuck modifier
- [knowledge/dev/test-timing.md](/Users/m4pro_sv/.claude/knowledge/dev/test-timing.md) — **when:** writing or editing any test with async/timing/wait-for behavior
- [knowledge/dev/change-hygiene.md](/Users/m4pro_sv/.claude/knowledge/dev/change-hygiene.md) — **when:** enumerating, enumerate, root cause, upstream fix, leak, patching a symptom, band-aid, post-build cleanup, before building a new mechanism, downstream consumers
- [knowledge/dev/context-token-budget.md](/Users/m4pro_sv/.claude/knowledge/dev/context-token-budget.md) — **when:** context bloat, token usage, CLAUDE.md size, trimming a rule, weekly cleanup, knowledge cleanup
- [knowledge/dev/rule-retrospectives.md](/Users/m4pro_sv/.claude/knowledge/dev/rule-retrospectives.md) — **when:** rewording a GR- rule, a rule keeps failing
- [knowledge/session/](/Users/m4pro_sv/.claude/knowledge/session/) — **when:** session wind-down, Discord webhooks
- [knowledge/user/](/Users/m4pro_sv/.claude/knowledge/user/) — **when:** address selection, identity, 2FA, family context, dev machine, hardware specs, benchmark baseline, Xcode version
- [knowledge/secrets-management.md](/Users/m4pro_sv/.claude/knowledge/secrets-management.md) — **when:** API key work, MCP wrappers, Bash hook debugging
- `~/Developer/EnviousLabs/EnviousMarketing/CLAUDE.md` — **when:** marketing, content, blog, social, SEO, GEO, SEM, posting, scheduling, outreach, campaign
- [enviousstaging-ga4-outreach.md](/Users/m4pro_sv/.claude/knowledge/google/enviousstaging-ga4-outreach.md) — **when:** EnviousStaging GA4, outreach UTM, email campaign analytics, marketing puller
- [search-console.md](/Users/m4pro_sv/.claude/knowledge/google/search-console.md) — **when:** Search Console, indexing alert, organic-performance question

**Notifications:** built-in questions/permissions; `~/.claude/notify-claude.sh` is Stop-only for desktop pings. Pair phone via `/rc`.
→ [notifications.md](/Users/m4pro_sv/.claude/knowledge/dev/notifications.md)

exec
/bin/zsh -lc "git show 3b6686d --stat
git show 3b6686d -- app/src/main/java/com/envi/wispr/audio/RecorderLease.kt app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
nl -ba app/src/main/java/com/envi/wispr/audio/RecorderLease.kt
nl -ba app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt | sed -n '394,412p'
nl -ba app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt | sed -n '822,836p'
nl -ba app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt | sed -n '855,866p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 3250ms:
2026-09-21 20:18:40.294 xcodebuild[62026:78185477]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:18:40.428 xcodebuild[62026:78185476] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-kV1MFiSh' (errno=Operation not permitted)
2026-09-21 20:18:40.877 xcodebuild[62036:78185523]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:18:41.000 xcodebuild[62036:78185522] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit 3b6686df1691d167685f1c641aaf5c1197d673d3
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 20:15:05 2026 -0400

    fix(ui): #115 chunks B and C after code review round 1; recovery off the queue, a recorder lease
    
    Four of five findings adopted. The start-up stale-row recovery runs on
    the session scope again, so a recovery stalled on the disk cannot sit
    ahead of a new take's writes on the per-take queue (F1, REPRODUCIBLE; a
    row holds the recovery through a whole take). A process-scoped recorder
    lease is acquired before the recorder is created and released only after
    it is released, so a replacement audio Service in the same process cannot
    open a second recorder while an abandoned capture thread holds the first
    (F3, HYPOTHETICAL; the lease is tested as a pure owner and its placement
    by shape). The clean-stop marker is written in onDestroy itself again
    rather than queued: a queued mark could land after a replacement
    connected and either be declined by the instance guard or overwrite the
    replacement's arm (F4, adopted with a different fix than proposed; the
    generation scheme was not needed once the mark is synchronous). Two KDocs
    and a test name describe the queued finalization (F5).
    
    Rejected: F2 asked the take to insert before the History save returns.
    The insertion route is decided from the save's outcome
    (HistoryPublicationPolicy.route reads persistenceSucceeded), and the
    owner's wait for it is on its IO scope, never main, as before this change.
    A stalled disk holding the words is pre-existing and out of #115's scope.
    
    Part of #115

 .../com/envi/wispr/audio/AudioCaptureService.kt    |   19 +-
 .../java/com/envi/wispr/audio/RecorderLease.kt     |   30 +
 .../com/envi/wispr/history/HistoryWriteQueue.kt    |    7 +-
 .../envi/wispr/paste/PasteAccessibilityService.kt  |    9 +-
 .../envi/wispr/ui/DictationSessionCoordinator.kt   |   18 +-
 .../com/envi/wispr/ui/DictationSessionService.kt   |    3 +
 .../com/envi/wispr/audio/AudioServiceShapeTest.kt  |    2 +
 .../java/com/envi/wispr/audio/RecorderLeaseTest.kt |   40 +
 .../wispr/ui/DictationSessionCoordinatorTest.kt    |   21 +
 .../java/com/envi/wispr/ui/DictationSessionRig.kt  |    5 +-
 .../envi/wispr/ui/PolishPublicationRoutesTest.kt   |    2 +-
 .../com/envi/wispr/ui/SessionOwnerShapeTest.kt     |    3 +-
 docs/audits/2026-09-21-115-code-review-bc-r1.md    | 2140 ++++++++++++++++++++
 13 files changed, 2280 insertions(+), 19 deletions(-)
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-l42ccER9' (errno=Operation not permitted)
2026-09-21 20:18:41.978 xcodebuild[62043:78185679]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:18:42.086 xcodebuild[62043:78185678] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-w4j2KhhB' (errno=Operation not permitted)
2026-09-21 20:18:42.486 xcodebuild[62046:78185710]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:18:42.598 xcodebuild[62046:78185709] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit 3b6686df1691d167685f1c641aaf5c1197d673d3
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 20:15:05 2026 -0400

    fix(ui): #115 chunks B and C after code review round 1; recovery off the queue, a recorder lease
    
    Four of five findings adopted. The start-up stale-row recovery runs on
    the session scope again, so a recovery stalled on the disk cannot sit
    ahead of a new take's writes on the per-take queue (F1, REPRODUCIBLE; a
    row holds the recovery through a whole take). A process-scoped recorder
    lease is acquired before the recorder is created and released only after
    it is released, so a replacement audio Service in the same process cannot
    open a second recorder while an abandoned capture thread holds the first
    (F3, HYPOTHETICAL; the lease is tested as a pure owner and its placement
    by shape). The clean-stop marker is written in onDestroy itself again
    rather than queued: a queued mark could land after a replacement
    connected and either be declined by the instance guard or overwrite the
    replacement's arm (F4, adopted with a different fix than proposed; the
    generation scheme was not needed once the mark is synchronous). Two KDocs
    and a test name describe the queued finalization (F5).
    
    Rejected: F2 asked the take to insert before the History save returns.
    The insertion route is decided from the save's outcome
    (HistoryPublicationPolicy.route reads persistenceSucceeded), and the
    owner's wait for it is on its IO scope, never main, as before this change.
    A stalled disk holding the words is pre-existing and out of #115's scope.
    
    Part of #115

diff --git a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
index 8066ff6..40f5fa4 100644
--- a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
+++ b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
@@ -394,6 +394,16 @@ class AudioCaptureService : Service() {
                 return false
             }
 
+            // The process's one recorder (#115 review, F3): a previous Service instance's capture thread may
+            // still hold it, parked in a read, and `session` cannot see across instances.
+            if (!RecorderLease.PROCESS.acquire()) {
+                DebugLogger.error(TAG, "A recorder is still held in this process; refusing to start")
+                lastStartFailure = START_FAILURE_OTHER
+                routeHold.release()
+                stopSelf()
+                publishStartRefused(takeId, lastStartFailure)
+                return false
+            }
             var record: AudioRecord? = null
             var threadStarted = false
             var output: FileOutputStream? = null
@@ -818,6 +828,9 @@ class AudioCaptureService : Service() {
             }
         runCatching { record?.release() }
             .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
+        // Every caller acquired the lease before creating the recorder (or failing to); released AFTER
+        // the recorder is, so the next start in this process cannot open a second one first.
+        RecorderLease.PROCESS.release()
     }
 
     /** Wait for the capture thread to finish writing and close the file. */
@@ -844,9 +857,9 @@ class AudioCaptureService : Service() {
         // NOT joined (#115): the capture thread is the sole owner of AudioRecord and the file, and it
         // releases both in its own releaseSession whenever its read returns; a thread parked inside a
         // read is abandoned to process termination rather than held on the main thread for two seconds.
-        // A new take in this process cannot open a second recorder while the old one is held:
-        // startRecording refuses while `session` is set, and releaseSession clears it only after the
-        // recorder is released.
+        // A new take in this process cannot open a second recorder while the old one is held, in THIS
+        // instance (startRecording refuses while `session` is set) or in a replacement instance in the
+        // same process (`RecorderLease.PROCESS` is released only after the recorder is).
         if (captureThread?.isAlive != true) {
             captureThread = null
             synchronized(sessionLock) {
diff --git a/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt b/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt
new file mode 100644
index 0000000..ee5ddd1
--- /dev/null
+++ b/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt
@@ -0,0 +1,30 @@
+package com.envi.wispr.audio
+
+import java.util.concurrent.atomic.AtomicBoolean
+
+/**
+ * The one `AudioRecord` this process may hold, as a lease that outlives any Service instance (#115).
+ *
+ * `AudioCaptureService.onDestroy` no longer joins the capture thread, and that thread is the sole owner of
+ * the recorder: a thread parked inside a read keeps the recorder until process termination. `session` is
+ * instance-local, so a NEW Service instance Android creates in the same cached `:audio` process could
+ * otherwise open a second recorder while the old thread still holds the first. The lease is acquired before
+ * the recorder is created and released only after the owning thread has released it, on every path.
+ */
+internal class RecorderLease {
+    private val held = AtomicBoolean(false)
+
+    /** True when this caller now holds the lease; false when another recorder is still held in this process. */
+    fun acquire(): Boolean = held.compareAndSet(false, true)
+
+    fun release() {
+        held.set(false)
+    }
+
+    val isHeld: Boolean get() = held.get()
+
+    companion object {
+        /** The process's lease. */
+        val PROCESS = RecorderLease()
+    }
+}
diff --git a/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
index 65d935b..6bcfd7e 100644
--- a/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
+++ b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
@@ -18,9 +18,10 @@ import kotlinx.coroutines.launch
  * content-free label while the drain continues. A write left in the channel at process death is lost, as a
  * launched coroutine was; the start-up recovery owns those rows.
  *
- * Per-take writers (insert, status, discard, finalize, the insertion outcome) go through here. The History
- * screen's and the start-up recovery's writers (`setKept`, `delete`, `deleteAll`, `pruneWordlessRows`,
- * `recoverStaleOpenRows`) stay on their own scopes: they never write a live take's row.
+ * Per-take writers (insert, status, discard, finalize, the insertion outcome, the teardown's interrupted
+ * mark) go through here and nothing else does: the History screen's writers (`setKept`, `delete`,
+ * `deleteAll`, `pruneWordlessRows`) and the start-up recovery (`recoverStaleOpenRows`) stay on their own
+ * scopes, so a write of theirs stalled on the disk never sits ahead of a live take's writes.
  */
 internal class HistoryWriteQueue(
     private val repository: TranscriptRepository,
diff --git a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
index e416a15..f93ad98 100644
--- a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
+++ b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
@@ -631,9 +631,12 @@ class PasteAccessibilityService : AccessibilityService() {
         historyScope.cancel()
         clearPinnedTarget()
         clearTarget()
-        // LAST on the queue, behind the outcome write above: a system kill that lands while that write is
-        // still pending must not read as an orderly stop, which is the case the marker exists to catch.
-        ModelBootstrapApplication.historyWrites(applicationContext).enqueue("clean-stop marker") { markStopWasClean() }
+        // LAST, and written HERE rather than queued behind the outcome write (#115 review, F4): the marker
+        // answers whether THIS service stopped in order, which it did once this method runs, and a
+        // queued mark could land after a replacement connected and either be declined by the instance
+        // guard (the orderly stop then reads as a crash) or overwrite the replacement's own arm. A kill
+        // after this point loses only the queued outcome write, which the start-up recovery closes.
+        markStopWasClean()
         super.onDestroy()
     }
 
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
index b70b8fe..e97def7 100644
--- a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
+++ b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
@@ -77,10 +77,12 @@ internal class DictationSessionCoordinator(
     private val preferences: SessionPreferencesSource,
     /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
     private val historyWrites: HistoryWriteQueue,
+    /** For the start-up recovery ONLY, on the session scope: a stalled recovery must not sit ahead of a take's writes on the queue. */
+    private val transcripts: TranscriptRepository,
     private val languageDetector: LanguageDetector,
     private val loadPolicy: suspend () -> PolishPolicy,
     private val pipeline: PipelineController,
-    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is joined on destroy. */
+    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is cancelled on destroy, never joined (#115). */
     private val scope: CoroutineScope,
     /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
     private val mainDispatcher: CoroutineDispatcher,
@@ -262,10 +264,11 @@ internal class DictationSessionCoordinator(
 
     /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
     fun onCreated() {
-        // On the queue, AHEAD of anything this take will write (#115): the recovery closes rows an earlier
-        // process left open, and it must not race the new take's own draft insert on another thread.
-        historyWrites.enqueue("stale-row recovery") { repository ->
-            runCatching { repository.recoverStaleOpenRows(System.currentTimeMillis()) }
+        // On its own scope, never on the per-take queue (#115 review): a recovery stalled on the disk
+        // would otherwise sit ahead of every write of the take that follows. It closes rows an EARLIER
+        // process left open; the new take's rows are not among them.
+        scope.launch {
+            runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
                 .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
                 .onFailure { error -> log.warn("Unable to recover stale history: ${error.message}") }
         }
@@ -1210,8 +1213,9 @@ internal class DictationSessionCoordinator(
     /**
      * The one place a polish outcome becomes a History row and, when it did not do its job, a sentence
      * (#77). Two routes reach it, the outcome callback and `publishFallback`; the facts are derived once
-     * here, and the notice is posted BEFORE persistence and insertion begin so it precedes the delivery
-     * line when both fire.
+     * here. The History write is enqueued WITH the reservation (#115); the notice is posted before the
+     * owner's continuation (the commit, the handoff) starts, so it precedes the delivery line when both
+     * fire.
      */
     private fun publishResult(
         text: String,
     1	package com.envi.wispr.audio
     2	
     3	import java.util.concurrent.atomic.AtomicBoolean
     4	
     5	/**
     6	 * The one `AudioRecord` this process may hold, as a lease that outlives any Service instance (#115).
     7	 *
     8	 * `AudioCaptureService.onDestroy` no longer joins the capture thread, and that thread is the sole owner of
     9	 * the recorder: a thread parked inside a read keeps the recorder until process termination. `session` is
    10	 * instance-local, so a NEW Service instance Android creates in the same cached `:audio` process could
    11	 * otherwise open a second recorder while the old thread still holds the first. The lease is acquired before
    12	 * the recorder is created and released only after the owning thread has released it, on every path.
    13	 */
    14	internal class RecorderLease {
    15	    private val held = AtomicBoolean(false)
    16	
    17	    /** True when this caller now holds the lease; false when another recorder is still held in this process. */
    18	    fun acquire(): Boolean = held.compareAndSet(false, true)
    19	
    20	    fun release() {
    21	        held.set(false)
    22	    }
    23	
    24	    val isHeld: Boolean get() = held.get()
    25	
    26	    companion object {
    27	        /** The process's lease. */
    28	        val PROCESS = RecorderLease()
    29	    }
    30	}
   394	                return false
   395	            }
   396	
   397	            // The process's one recorder (#115 review, F3): a previous Service instance's capture thread may
   398	            // still hold it, parked in a read, and `session` cannot see across instances.
   399	            if (!RecorderLease.PROCESS.acquire()) {
   400	                DebugLogger.error(TAG, "A recorder is still held in this process; refusing to start")
   401	                lastStartFailure = START_FAILURE_OTHER
   402	                routeHold.release()
   403	                stopSelf()
   404	                publishStartRefused(takeId, lastStartFailure)
   405	                return false
   406	            }
   407	            var record: AudioRecord? = null
   408	            var threadStarted = false
   409	            var output: FileOutputStream? = null
   410	            try {
   411	                record = AudioRecord(
   412	                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
   822	            .onFailure { DebugLogger.warn(TAG, "Failed to flush audio file: ${it.message}") }
   823	        runCatching { output?.close() }
   824	            .onFailure { DebugLogger.warn(TAG, "Failed to close audio file: ${it.message}") }
   825	        runCatching { record?.stop() }
   826	            .onFailure {
   827	                if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.message}")
   828	            }
   829	        runCatching { record?.release() }
   830	            .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
   831	        // Every caller acquired the lease before creating the recorder (or failing to); released AFTER
   832	        // the recorder is, so the next start in this process cannot open a second one first.
   833	        RecorderLease.PROCESS.release()
   834	    }
   835	
   836	    /** Wait for the capture thread to finish writing and close the file. */
   855	            active.picture.close()
   856	        }
   857	        // NOT joined (#115): the capture thread is the sole owner of AudioRecord and the file, and it
   858	        // releases both in its own releaseSession whenever its read returns; a thread parked inside a
   859	        // read is abandoned to process termination rather than held on the main thread for two seconds.
   860	        // A new take in this process cannot open a second recorder while the old one is held, in THIS
   861	        // instance (startRecording refuses while `session` is set) or in a replacement instance in the
   862	        // same process (`RecorderLease.PROCESS` is released only after the recorder is).
   863	        if (captureThread?.isAlive != true) {
   864	            captureThread = null
   865	            synchronized(sessionLock) {
   866	                isRecording.set(false)

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '78,90p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '262,278p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1208,1222p'
nl -ba app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt | sed -n '10,26p'
nl -ba app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt | sed -n '626,642p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt | sed -n '218,238p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
    78	    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
    79	    private val historyWrites: HistoryWriteQueue,
    80	    /** For the start-up recovery ONLY, on the session scope: a stalled recovery must not sit ahead of a take's writes on the queue. */
    81	    private val transcripts: TranscriptRepository,
    82	    private val languageDetector: LanguageDetector,
    83	    private val loadPolicy: suspend () -> PolishPolicy,
    84	    private val pipeline: PipelineController,
    85	    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is cancelled on destroy, never joined (#115). */
    86	    private val scope: CoroutineScope,
    87	    /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
    88	    private val mainDispatcher: CoroutineDispatcher,
    89	    private val polishTimeout: PolishTimeout = DelayPolishTimeout,
    90	    /** How long a take waits for the settings readers to answer before starting on the last values; a test shortens it (#193). */
   262	    /** True while a take is in PROCESSING; the Service picks the processing notification on a foreground command. */
   263	    val isProcessing: Boolean get() = state.get() == SessionState.PROCESSING
   264	
   265	    /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
   266	    fun onCreated() {
   267	        // On its own scope, never on the per-take queue (#115 review): a recovery stalled on the disk
   268	        // would otherwise sit ahead of every write of the take that follows. It closes rows an EARLIER
   269	        // process left open; the new take's rows are not among them.
   270	        scope.launch {
   271	            runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
   272	                .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
   273	                .onFailure { error -> log.warn("Unable to recover stale history: ${error.message}") }
   274	        }
   275	        preferences.start(scope)
   276	    }
   277	
   278	    /**
  1208	    private fun restoreTakeVocabulary(text: String, preferences: SessionPreferences): String {
  1209	        val restored = preferences.matcher.restore(text)
  1210	        return if (TextSafety.isSafe(text, restored)) restored else text
  1211	    }
  1212	
  1213	    /**
  1214	     * The one place a polish outcome becomes a History row and, when it did not do its job, a sentence
  1215	     * (#77). Two routes reach it, the outcome callback and `publishFallback`; the facts are derived once
  1216	     * here. The History write is enqueued WITH the reservation (#115); the notice is posted before the
  1217	     * owner's continuation (the commit, the handoff) starts, so it precedes the delivery line when both
  1218	     * fire.
  1219	     */
  1220	    private fun publishResult(
  1221	        text: String,
  1222	        engine: String,
    10	/**
    11	 * Every per-take History write of this process, applied in the order it was ENQUEUED by one worker (#115).
    12	 *
    13	 * Application-owned, one per process: the session owner and the paste service both write a take's row,
    14	 * and before #115 each launched its writes on its own scope, so two writes to one row could land in either
    15	 * order (a `processing` status after the `asr_error` that followed it) and a teardown had to `runBlocking`
    16	 * on the main thread to make sure its own write was the last word. Here the order IS the enqueue order and
    17	 * nothing waits: [enqueue] never suspends and never blocks, and a write that throws is logged by its
    18	 * content-free label while the drain continues. A write left in the channel at process death is lost, as a
    19	 * launched coroutine was; the start-up recovery owns those rows.
    20	 *
    21	 * Per-take writers (insert, status, discard, finalize, the insertion outcome, the teardown's interrupted
    22	 * mark) go through here and nothing else does: the History screen's writers (`setKept`, `delete`,
    23	 * `deleteAll`, `pruneWordlessRows`) and the start-up recovery (`recoverStaleOpenRows`) stay on their own
    24	 * scopes, so a write of theirs stalled on the disk never sits ahead of a live take's writes.
    25	 */
    26	internal class HistoryWriteQueue(
   626	        }
   627	        pendingInsertion = null
   628	        // Nothing is drained here (#115): the take's History writes are the application queue's, in
   629	        // order, and the bubble-position saves on this scope are a preference whose next save wins, so a
   630	        // cancelled in-flight one costs nothing the next open does not restore.
   631	        historyScope.cancel()
   632	        clearPinnedTarget()
   633	        clearTarget()
   634	        // LAST, and written HERE rather than queued behind the outcome write (#115 review, F4): the marker
   635	        // answers whether THIS service stopped in order, which it did once this method runs, and a
   636	        // queued mark could land after a replacement connected and either be declined by the instance
   637	        // guard (the orderly stop then reads as a crash) or overwrite the replacement's own arm. A kill
   638	        // after this point loses only the queued outcome write, which the start-up recovery closes.
   639	        markStopWasClean()
   640	        super.onDestroy()
   641	    }
   642	
   218	            log = DebugSessionLog,
   219	        )
   220	        bindings = PipelineBindings(applicationContext, mainHandler, DebugSessionLog)
   221	        val host = createHost()
   222	        coordinator = DictationSessionCoordinator(
   223	            host = host,
   224	            surface = OverlayRecorderSurface,
   225	            insertion = AccessibilityInsertionGateway,
   226	            log = DebugSessionLog,
   227	            preferences = preferences,
   228	            historyWrites = ModelBootstrapApplication.historyWrites(applicationContext),
   229	            transcripts = TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao()),
   230	            languageDetector = languageDetector,
   231	            loadPolicy = { providerConfiguration.loadPolicy() },
   232	            pipeline = bindings,
   233	            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
   234	            mainDispatcher = Dispatchers.Main.immediate,
   235	        )
   236	        coordinator.onCreated()
   237	    }
   238	

exec
/bin/zsh -lc "nl -ba app/src/test/java/com/envi/wispr/audio/RecorderLeaseTest.kt
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt | sed -n '610,635p'
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt | sed -n '626,632p'
nl -ba app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt | sed -n '176,198p'
nl -ba app/src/test/java/com/envi/wispr/ui/PolishPublicationRoutesTest.kt | sed -n '37,50p'
nl -ba docs/audits/2026-09-21-115-receipts-bc.md" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
     1	package com.envi.wispr.audio
     2	
     3	import org.junit.Assert.assertFalse
     4	import org.junit.Assert.assertTrue
     5	import org.junit.Test
     6	import java.io.File
     7	
     8	/**
     9	 * Product Outcome (#115): when this fails, a replacement audio Service in the same process opens a
    10	 * second recorder while an abandoned capture thread still holds the first, and the new take records
    11	 * silence or fails to start with no sentence. The lease is a pure owner here; the service's use of it is
    12	 * pinned by shape (acquired before the recorder is created, released after it is released, refused
    13	 * with the start-failure ending).
    14	 */
    15	class RecorderLeaseTest {
    16	    @Test
    17	    fun theSecondAcquireIsRefusedUntilTheFirstIsReleased() {
    18	        val lease = RecorderLease()
    19	        assertTrue(lease.acquire())
    20	        assertFalse("a second recorder in the process is refused", lease.acquire())
    21	        assertTrue(lease.isHeld)
    22	        lease.release()
    23	        assertFalse(lease.isHeld)
    24	        assertTrue("released, the next start may hold it", lease.acquire())
    25	    }
    26	
    27	    @Test
    28	    fun theServiceAcquiresBeforeTheRecorderAndReleasesAfterIt() {
    29	        val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    30	        val start = service.substringAfter("private fun startRecording(").substringBefore("\n    private fun ")
    31	        val acquire = start.indexOf("if (!RecorderLease.PROCESS.acquire())")
    32	        val create = start.indexOf("record = AudioRecord(")
    33	        assertTrue("the lease is taken before the recorder is created", acquire in 0 until create)
    34	        val refusal = start.substring(acquire, create)
    35	        assertTrue("a refused lease ends the take with the start-failure ending", refusal.contains("publishStartRefused(takeId, lastStartFailure)") && refusal.contains("return false"))
    36	        val close = service.substringAfter("private fun closeResources(record: AudioRecord?, output: FileOutputStream?)").substringBefore("\n    }\n")
    37	        assertTrue("the lease is released after the recorder is", close.indexOf("RecorderLease.PROCESS.release()") > close.indexOf("record?.release()"))
    38	        assertTrue("and only there", Regex("RecorderLease\\.PROCESS\\.release\\(\\)").findAll(service).count() == 1)
    39	    }
    40	}
   610	        assertFalse(row.interrupted)
   611	    }
   612	
   613	    /**
   614	     * Product Outcome (#115 review of chunks B and C, F1): the start-up recovery is a write of its own; a
   615	     * disk that stalls it must not hold a new take's draft, status and finalization behind it. Here the
   616	     * recovery is HELD for the whole take, which still completes and lands its row.
   617	     * REVERT: enqueue the recovery on the per-take queue.
   618	     */
   619	    @Test
   620	    fun aStalledRecoveryDoesNotHoldTheTakesWrites() {
   621	        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
   622	        rig.dao.holdRecovery = held
   623	        val coordinator = rig.coordinator()
   624	        startAndGoLive(coordinator)
   625	        val polish = stopAndTranscribe(coordinator, "hello world")
   626	        polish.listener!!.onOutcome(polish.outcome("Hello world."))
   627	        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
   628	        rig.host.awaitStopped()
   629	        assertEquals("ready_for_insertion", theOnlyRow().status)
   630	        assertEquals(listOf(1L to "Hello world."), rig.insertion.pastes.toList())
   631	        held.complete(Unit)
   632	    }
   633	
   634	    @Test
   635	    fun destroyWhileStartingMarksInterrupted() {
   626	            }
   627	            return updated
   628	        }
   629	        /** When set, the start-up recovery is held until the test completes it (the #115 review's F1 row). */
   630	        @Volatile var holdRecovery: kotlinx.coroutines.CompletableDeferred<Unit>? = null
   631	        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int { holdRecovery?.await(); return 0 }
   632	        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int = 0
   176	    fun theOwnerNeverBlocksAndNeverPolls() {
   177	        val coordinator = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
   178	        listOf("runBlocking", "Thread.sleep", "startPolling", "waitForFileReady", "Thread.join", ".join(").forEach {
   179	            assertFalse("the coordinator must not contain $it", coordinator.contains(it))
   180	        }
   181	        // The ONE thread the owner makes is the capture command lane's, inside its executor's factory;
   182	        // no other `Thread(` may appear (the old live waiter, poller, transcribe and cleanup threads).
   183	        assertEquals("one Thread( in the coordinator, the lane's", 1, Regex("""(^|[^A-Za-z0-9_.])Thread\(""").findAll(coordinator).count())
   184	        assertTrue(coordinator.contains("Thread(runnable, \"CaptureCommands\")"))
   185	        val paste = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText().substringAfter("override fun onDestroy()")
   186	        listOf("runBlocking", "joinAll", ".join(").forEach { assertFalse("the paste service's onDestroy must not contain $it", paste.contains(it)) }
   187	        assertTrue("the clean-stop marker is written in onDestroy itself, last", paste.substringBefore("super.onDestroy()").trimEnd().endsWith("markStopWasClean()"))
   188	        assertFalse("and never queued behind a History write", paste.contains("enqueue(\"clean-stop marker\")"))
   189	        val audio = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText().substringAfter("override fun onDestroy()")
   190	        listOf("runBlocking", ".join(", "Thread.sleep").forEach { assertFalse("the audio service's onDestroy must not contain $it", audio.contains(it)) }
   191	    }
   192	
   193	    @Test
   194	    fun serviceLineCountIsReported() {
   195	        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
   196	        println("DictationSessionService.kt: ${service.lines().size} lines")
   197	    }
   198	}
    37	    @Test fun theFactsAreDerivedOnceTheWriteIsEnqueuedWithTheReservationAndTheNoticePrecedesTheContinuation() {
    38	        assertEquals(1, Regex("""PolishPublicationFacts\.from\(""").findAll(source).count())
    39	        val publication = section("private fun publishResult(", "private suspend fun TranscriptRepository.insertReadyTranscript")
    40	        val notice = publication.indexOf("host.showPolishNotice(notice)")
    41	        // The host delegate must still reach the notification controller (Codex review C1, 2026-09-20).
    42	        assertTrue(File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText().contains("DictationNotificationController.showPolishNotice(this@DictationSessionService, notice)"))
    43	        // Since #115 the History write is ENQUEUED in the same operation as the reservation, under the
    44	        // publish lock, so it precedes the notice in the source; the notice still precedes the owner's
    45	        // continuation coroutine, which is where the save's result is consumed.
    46	        val reservation = publication.indexOf("historyWrites.enqueue(\"finalize\")")
    47	        val continuation = publication.indexOf("scope.launch")
    48	        assertTrue("the write is enqueued with the reservation", reservation >= 0 && reservation < notice)
    49	        assertTrue("the notice is posted before the continuation coroutine starts", notice >= 0 && notice < continuation)
    50	    }
     1	# #115 chunks B and C revert receipts
     2	
     3	HEAD before receipts: 3b6686d
     4	
     5	- B1 drain the queue on two workers (launch per write): red rows ['aWriteThatThrowsCostsItselfAndNothingAfterIt', 'writesLandInEnqueueOrderWhateverTheDiskDoes'] :: RECEIPT OK
     6	- B3 destroy waits on the interrupted write (a runBlocking behind it on the queue): red rows ['destroyReturnsWhileARoomWriteIsStalled'] :: RECEIPT OK
     7	- B4 destroy enqueues interrupted whether or not the interrupt won: red rows ['destroyAfterACompletedTakeLeavesTheRowAlone'] :: RECEIPT OK
     8	- B5 the start-up recovery on the per-take queue again (round 1, F1): red rows ['aStalledRecoveryDoesNotHoldTheTakesWrites'] :: RECEIPT OK
     9	- C4 the lease released before the recorder is (round 1, F3): red rows ['theServiceAcquiresBeforeTheRecorderAndReleasesAfterIt'] :: RECEIPT OK
    10	- C5 the clean-stop marker queued behind the outcome write again (round 1, F4): red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    11	- C1 the paste service drains Room on the main thread again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    12	- C2 the audio service joins the capture thread again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    13	- C3 the owner starts a cleanup thread of its own again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    14	
    15	Restored tree, red rows per class: {'com.envi.wispr.history.HistoryWriteQueueTest': [], 'com.envi.wispr.ui.DictationSessionCoordinatorTest': [], 'com.envi.wispr.ui.SessionOwnerShapeTest': [], 'com.envi.wispr.audio.RecorderLeaseTest': []}
    16	git status --short: (clean)

exec
/bin/zsh -lc "git show 87e07ca:app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '960,1010p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1422ms:
2026-09-21 20:19:36.120 xcodebuild[62193:78188035]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:19:36.231 xcodebuild[62193:78188034] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-2fJJ0ZSD' (errno=Operation not permitted)
2026-09-21 20:19:36.633 xcodebuild[62195:78188067]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:19:36.747 xcodebuild[62195:78188066] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
                                if (polishLedger.claim(requestId)) {
                                    log.warn("Invalid polish outcome for request $requestId")
                                    Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to if (outcome == null) "null" else "mismatched"))
                                    publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                                }
                                return
                            }
                            if (!polishLedger.claim(outcome.requestId)) {
                                log.warn(
                                    "Ignoring polish outcome for request ${outcome.requestId}: not the open request (reason=${outcome.reason})",
                                )
                                return
                            }
                            log.log("Polish outcome ${outcome.requestId}: reason=${outcome.reason} status=${outcome.statusCode}")
                            publishResult(
                                restoreTakeVocabulary(outcome.text, takePreferences),
                                outcome.engine,
                                outcome.latencyMs,
                                outcome.reason,
                                outcome.statusCode,
                                PolishContext.from(takePreferences.policy),
                            )
                        }

                        // v1 answers are never produced for a v2 request. If one ever arrives it is an
                        // engine defect, and the session still fails open to the deterministic text.
                        override fun onResult(text: String?, engine: String?, latencyMs: Long) {
                            if (polishLedger.claim(requestId)) {
                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_result"))
                                publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                            }
                        }

                        override fun onError(message: String?) {
                            if (polishLedger.claim(requestId)) {
                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_error"))
                                publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
                            }
                        }
                    },
                )
            } catch (error: Exception) {
                log.error("Unable to call polish service", error)
                if (polishLedger.claim(requestId)) publishFallback(rawText, takePreferences, PolishReason.CALL_FAILED)
            }
            // A cancel that landed between the ledger open and the engine's registration found nothing to
            // cancel on the engine. Now the request is registered, so send it again; on a delivered or
            // never-registered id the engine treats it as a no-op. A read, never a claim: on a normal day
            // the ledger is still open here and must stay open for the outcome.
            if (polishLedger.openId != requestId) runCatching { service.cancel(requestId) }
        }

codex
Chunks B and C code review round 2 — DONE

## Findings

1. **P2, HYPOTHETICAL: the recorder lease is released even when `AudioRecord.release()` fails.** `AudioCaptureService.kt:829-833` catches and logs a release failure, then unconditionally clears `RecorderLease.PROCESS`, contradicting the ownership guarantee in `RecorderLease.kt:8-12`; `RecorderLeaseTest.kt:36-38` pins source order only, not successful release. A replacement Service could then acquire the lease while the old native recorder remains unsafe.  
   **Drop-in fix:** release the lease only when `record == null` or `record.release()` completed successfully; on an exception, keep the lease held until process death and log that conservative choice. Grow the shape row to pin the success condition and add a pure close-result row.

## Axes (A1..A5)

- **A1: FOUND.** Acquire placement, refusal behavior, single release site, and parked-thread ownership are correct, but the lease clears after a failed recorder release.
- **A2: NOT FOUND.** Recovery runs on the separate session scope, uses the repository only there, and the held-recovery row proves a complete take can write and insert independently.
- **A3: NOT FOUND.** Outcome enqueue and synchronous clean marking execute sequentially on the service main thread, so no replacement callback can interleave; a kill before the marker remains conservatively unclean, while a kill afterward represents an orderly completed teardown.
- **A4: NOT FOUND.** The save-result suspension remains on the IO-owned session coroutine, not main, and matches the pre-change persistence-before-route contract; changing that contract is outside #115.
- **A5: FOUND.** Recovery, scope, publication, marker, and test-name comments match; the lease KDoc overstates the failed-release path identified above.

## COVERAGE

Read all 12 requested files and ranges, `git show 3b6686d`, the permitted `87e07ca` comparison, and receipts B1, B3, B4, B5, and C1 to C5. Accepted the supplied 1,036-test and visibility results without rerunning them.

## VERDICT: FINDINGS
tokens used
1,166,607
Chunks B and C code review round 2 — DONE

## Findings

1. **P2, HYPOTHETICAL: the recorder lease is released even when `AudioRecord.release()` fails.** `AudioCaptureService.kt:829-833` catches and logs a release failure, then unconditionally clears `RecorderLease.PROCESS`, contradicting the ownership guarantee in `RecorderLease.kt:8-12`; `RecorderLeaseTest.kt:36-38` pins source order only, not successful release. A replacement Service could then acquire the lease while the old native recorder remains unsafe.  
   **Drop-in fix:** release the lease only when `record == null` or `record.release()` completed successfully; on an exception, keep the lease held until process death and log that conservative choice. Grow the shape row to pin the success condition and add a pure close-result row.

## Axes (A1..A5)

- **A1: FOUND.** Acquire placement, refusal behavior, single release site, and parked-thread ownership are correct, but the lease clears after a failed recorder release.
- **A2: NOT FOUND.** Recovery runs on the separate session scope, uses the repository only there, and the held-recovery row proves a complete take can write and insert independently.
- **A3: NOT FOUND.** Outcome enqueue and synchronous clean marking execute sequentially on the service main thread, so no replacement callback can interleave; a kill before the marker remains conservatively unclean, while a kill afterward represents an orderly completed teardown.
- **A4: NOT FOUND.** The save-result suspension remains on the IO-owned session coroutine, not main, and matches the pre-change persistence-before-route contract; changing that contract is outside #115.
- **A5: FOUND.** Recovery, scope, publication, marker, and test-name comments match; the lease KDoc overstates the failed-release path identified above.

## COVERAGE

Read all 12 requested files and ranges, `git show 3b6686d`, the permitted `87e07ca` comparison, and receipts B1, B3, B4, B5, and C1 to C5. Accepted the supplied 1,036-test and visibility results without rerunning them.

## VERDICT: FINDINGS
