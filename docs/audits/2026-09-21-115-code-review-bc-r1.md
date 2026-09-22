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
Delta: CHUNKS B and C of #115 are BUILT on top of chunk A (your ALL-CLEAR at `649095a`). CODE REVIEW round 1 of chunks B and C. HEAD `6a786bc`, on `main` `87e07ca`. Commits: `624f933` (chunk B, the History write queue), `b3f4a38` (chunk C, the three teardowns), `f294d92` (one row), `6a786bc` (receipts). Also `75cfa0e` (the emulator harness gains freeze/thaw/kill; not app code) landed between.

## What to read (exact files; nothing else; `git show 624f933` and `git show b3f4a38` are the fastest way in)
- `app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt` (whole, 54 lines) and `models/ModelBootstrapApplication.kt` (whole)
- `app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt` lines 74-100, 205-225, 262-275, 770-812, 1210-1300, 1395-1480, 1636-1660, 1672-1730, 1738-1802
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt` lines 604-640, 1398-1414
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt` lines 832-870
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` lines 218-236; `PipelineLinks.kt` lines 120-140 (the posted-unbind member is gone)
- `app/src/test/java/com/envi/wispr/history/HistoryWriteQueueTest.kt` (whole)
- `app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt` lines 86-110, 236-252, 585-615
- `app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt` lines 60-70, 545-620
- `app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt` lines 168-195; `PolishPublicationRoutesTest.kt` lines 37-50
- `docs/audits/2026-09-21-115-receipts-bc.md`

## Verified facts (do NOT re-derive)
- `scripts/measure-tests.sh` at `f294d92`: tests=1033 suites=130 failures=0 (1032 at `b3f4a38`). `scripts/check-visibility.py`: clean, 100 closed sets.
- Receipts B1, B3, B4, C1, C2, C3 RED on the named rows at `f294d92`, tree restored and re-measured green. B1: two workers on the queue redden the order row and the throw row. B3: a `runBlocking` behind the `interrupted` write reddens `destroyReturnsWhileARoomWriteIsStalled` (the rig's main-thread call has a 5 s bound). B4: enqueueing `interrupted` whether or not the interrupt won reddens `destroyAfterACompletedTakeLeavesTheRowAlone`.
- The emulator scenes for chunk A, on `75cfa0e`: the baseline (96a3070) with a frozen capture process reached NO ending 20 s after the stop; the new build ends the take 3 s after the freeze with `AUDIO_PROCESS_UNRESPONSIVE`, a killed process ends it in 0.5 s, the next take lands in Gmail by COMMIT, and a ten-minute take with auto-stop off (host idle) ended by the pushed cap with the nine-minute warning and no false bound. A first ten-minute run was cut at 277 s by the bound while the HOST stalled the emulator for 11 s (Gradle tests running); with the host idle it did not recur. The ten-minute clip's transcription then died to the emulator's low-memory killer (a 19 MB clip on a small AVD), which is an emulator limit outside this change and is being noted as a gap.

## What was built (against plan §3 B and C)
B1. `HistoryWriteQueue(repository, scope = SupervisorJob + IO, warn)`: a `Channel<Write>(UNLIMITED)` drained by one coroutine; `enqueue(label, body)` is `trySend`, never suspends; a throwing body is logged by label and the drain continues. Owned by `ModelBootstrapApplication` (a lazy, one per process, reached by `historyWrites(context)`).
B2. The coordinator takes the queue instead of the repository. The draft insert is the first queued write; it completes a `CompletableDeferred<Long>` on the worker and sets `draftId`; the owner attaches the row to its surface from MAIN via `invokeOnCompletion` + `postToMain`, guarded by `destroyed` and by the deferred still being this take's. `resolvedDraftId()` (worker-side) reads `draftId` or awaits the deferred; every status/discard/finalize/interrupted write resolves the id there. The finalization: the immutable payload is built first, then under `publishLock` the publication is RESERVED and the finalize write ENQUEUED in one operation; the owner's coroutine awaits the save's result through a `CompletableDeferred<Result<Long>>` and continues as before (commit, handoff). The two insertion-outcome writers enqueue. `finishSession` joins nothing. `destroy` enqueues `interrupted` under `publishLock` ONLY when `arbiter.interrupt` won, cancels the session job without joining, and is free of `runBlocking`.
B3. `asrNotReadyEndsProcessing` asserts the status again, with the rig's fake DAO holding the FIRST status write 200 ms. `destroyReturnsWhileARoomWriteIsStalled` holds every status write on a deferred, destroys on main, then releases and reads `interrupted`.
C1. `destroy` stops capture down the command lane (no thread), stops the audio service by intent and unbinds on main. `postUnbindToMain` had no caller and is deleted from the link, the bindings and the rig.
C3. The paste service's `onDestroy`: the Room drain is gone; the outcome write is on the queue; `markStopWasClean` is queued LAST, behind it.
C4. The audio service's `onDestroy`: the 2 s join is gone; the comment states the abandonment contract and why a second recorder cannot open.
Shape: `SessionOwnerShapeTest.theOwnerNeverBlocksAndNeverPolls` (no `runBlocking`, `Thread.sleep`, `.join(`, `startPolling`, `waitForFileReady` in the coordinator; exactly one `Thread(`, the lane's factory; no `runBlocking`/join in the two service teardowns; the marker queued last).

## Deviations from the plan; adjudicate each
D1. The start-up stale-row recovery (`onCreated`) rides the queue too (label "stale-row recovery"), ahead of the take's insert, because the coordinator no longer holds a repository; the plan kept it on its own scope. The other non-take writers (`setKept`, `delete`, `deleteAll`, `pruneWordlessRows`) stay outside as planned.
D2. `PolishPublicationRoutesTest.theFactsAreDerivedExactlyOnceAndTheNoticePrecedesPersistence` now asserts the write is enqueued WITH the reservation (before the notice in the source) and the notice precedes the owner's continuation coroutine; the old "notice before the persistence coroutine" ordering cannot hold once the write is part of the reservation.
D3. `markStopWasClean` on the queue runs the marker's own `instance != null` guard later than before; a replacement service that connected in between declines the clean mark (its existing rule), which reads as an unclean stop on the next connect. Not seen; stated.
D4. The plan's `AudioLimbCloseTest.onDestroyReturnsWhileTheCaptureThreadIsStuck` is not a JVM-stageable row (the Service's `onDestroy` needs Android); the shape row pins the absence of the join instead.

## Axes (found / not found, with file:line)
A1. Ordering: every per-take write of a row enqueued in the order the plan's C1 requires (draft insert, status, finalize reserved+enqueued under the lock, outcome, interrupted last); a write that can be enqueued BEFORE the insert and resolve a 0 id (harmless?) or AFTER `interrupted`; the paste service's outcome write versus the owner's finalize (the outcome is enqueued from the paste service after the owner's handoff, which follows the finalize's completion).
A2. Deadlock and starvation: a queued write awaiting a deferred that only a LATER write completes; the owner's coroutine awaiting `saved` while the queue is stalled (it waits on IO, never main; is that the plan's intent?); `awaitHistoryIdle` in the rig.
A3. `destroy`: main-thread only, nothing awaited; `destroyed` guarding the surface attach; the lane's `commandCapture` after `shutdown` (logged, false); `sessionWasOpen` still stopping the service by intent.
A4. The paste service: what the deleted drain protected (bubble-position saves, the outcome write) and where each now lands; the marker's timing (D3).
A5. The audio service: what the deleted join protected (the second-recorder interleaving, the route thread's quit, the publisher close after the capture thread's ending) and whether each holds without it.
A6. The rows: `destroyReturnsWhileARoomWriteIsStalled` proves the return, not merely that the row reads `draft` at that instant; `asrNotReadyEndsProcessing` with the held first write; `HistoryWriteQueueTest`'s three rows; every wait positive.
A7. Comments and KDoc changed in B and C against the code.

## Output contract
Every finding ships a drop-in fix with `file:line`; classify REPRODUCIBLE or HYPOTHETICAL. CLEAN per axis is acceptable and will be checked against the tree. Sections, at most 150 lines: `## Findings`, `## Axes (A1..A7)`, `## Deviations (D1..D4 ACCEPT/REJECT)`, `## COVERAGE`, `## VERDICT: ALL-CLEAR | FINDINGS`. Stop after the VERDICT line.

warning: Under-development features enabled: chronicle. Under-development features are incomplete and may behave unpredictably. To suppress this warning, set `suppress_unstable_features_warning = true` in /Users/m4pro_sv/.codex/config.toml.
2026-09-22T00:01:54.960212Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
2026-09-22T00:01:55.077071Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
codex
Chunks B and C code review — WORKING

I’ll trace every queued write and teardown edge from the two build commits, then verify the stalled-write and ordering rows without treating the intentionally deferred hardware case as missing code.
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
/bin/zsh -lc 'git show 624f933 --stat
git show b3f4a38 --stat
git show 624f933 -- app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
git show b3f4a38 -- app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 6563ms:
2026-09-21 20:02:28.173 xcodebuild[48554:78112939]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:28.397 xcodebuild[48554:78112938] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-rhKF1eLi' (errno=Operation not permitted)
2026-09-21 20:02:28.876 xcodebuild[48560:78112981]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:28.987 xcodebuild[48560:78112980] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit 624f933fafb7685dea9774ec719306ddcec2df6a
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 19:49:34 2026 -0400

    feat(history): #115 chunk B, one ordered History write queue and a teardown that waits on nothing
    
    Every per-take History write (the draft insert, each status, the discard,
    the finalization, the insertion outcome from both the owner and the paste
    service, and the destroy's interrupted mark) is enqueued on one
    application-owned queue and applied in enqueue order by one worker that
    outlives every Service. The owner never waits on storage: the draft's id
    is a deferred the queued insert completes on the worker, later writes
    resolve it there, and the owner attaches the row to its surface from main
    when it lands. The finalization is reserved and enqueued as one operation
    under the publish lock; destroy takes the same lock, enqueues interrupted
    only when the interrupt won, cancels the session job without joining and
    returns. The three runBlocking blocks and the finishSession join are gone.
    
    Proven on the rig: the stop path's two status writes land in order with
    the first one held (the P4 race on 87e07ca); destroy returns on main with
    the disk held and the interrupted row lands when it answers.
    
    Part of #115

 .../com/envi/wispr/history/HistoryWriteQueue.kt    |  54 ++++
 .../envi/wispr/models/ModelBootstrapApplication.kt |  18 ++
 .../envi/wispr/paste/PasteAccessibilityService.kt  |  11 +-
 .../envi/wispr/ui/DictationSessionCoordinator.kt   | 328 +++++++++++----------
 .../com/envi/wispr/ui/DictationSessionService.kt   |   5 +-
 .../envi/wispr/history/HistoryWriteQueueTest.kt    |  80 +++++
 .../wispr/ui/DictationSessionCoordinatorTest.kt    |  36 ++-
 .../java/com/envi/wispr/ui/DictationSessionRig.kt  |  29 +-
 .../envi/wispr/ui/PolishPublicationRoutesTest.kt   |  13 +-
 9 files changed, 407 insertions(+), 167 deletions(-)
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-9PCYMaU0' (errno=Operation not permitted)
2026-09-21 20:02:29.888 xcodebuild[48573:78113031]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:29.997 xcodebuild[48573:78113030] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-f76x8XS4' (errno=Operation not permitted)
2026-09-21 20:02:30.426 xcodebuild[48710:78113343]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:30.536 xcodebuild[48710:78113342] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit b3f4a38a32b041a3f438ca40817bf7b16edccb46
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 19:55:55 2026 -0400

    refactor(ui): #115 chunk C, three teardowns that wait on nothing
    
    The owner's destroy stops capture down its command lane instead of a
    thread of its own, stops the audio service by intent and unbinds on main
    at once. The paste service's onDestroy no longer drains its scope on the
    main thread: the outcome write is on the application's History queue and
    the clean-stop marker is queued behind it, so a kill during the write still
    reads as unclean. The audio service's onDestroy no longer joins the capture
    thread for two seconds: the thread releases the recorder and the file in
    its own releaseSession when its read returns, and a thread parked in a read
    is abandoned to process termination; a new take cannot open a second
    recorder while the old one is held because startRecording refuses while
    the session is set. The posted-unbind helper had no caller left and is
    gone. A shape row pins all three teardowns.
    
    Part of #115

 .../com/envi/wispr/audio/AudioCaptureService.kt    | 27 ++++++++++------------
 .../envi/wispr/paste/PasteAccessibilityService.kt  | 25 ++++++++------------
 .../envi/wispr/ui/DictationSessionCoordinator.kt   | 26 +++++++++------------
 .../java/com/envi/wispr/ui/PipelineBindings.kt     |  7 ------
 .../main/java/com/envi/wispr/ui/PipelineLinks.kt   |  6 -----
 .../com/envi/wispr/audio/AudioServiceShapeTest.kt  |  2 +-
 .../java/com/envi/wispr/ui/DictationSessionRig.kt  |  1 -
 .../com/envi/wispr/ui/SessionOwnerShapeTest.kt     | 23 ++++++++++++++++++
 8 files changed, 57 insertions(+), 60 deletions(-)
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-7F1T5kg7' (errno=Operation not permitted)
2026-09-21 20:02:31.427 xcodebuild[48719:78113410]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:31.542 xcodebuild[48719:78113409] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-7oFyOR1P' (errno=Operation not permitted)
2026-09-21 20:02:31.925 xcodebuild[48721:78113430]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:32.036 xcodebuild[48721:78113428] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit 624f933fafb7685dea9774ec719306ddcec2df6a
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 19:49:34 2026 -0400

    feat(history): #115 chunk B, one ordered History write queue and a teardown that waits on nothing
    
    Every per-take History write (the draft insert, each status, the discard,
    the finalization, the insertion outcome from both the owner and the paste
    service, and the destroy's interrupted mark) is enqueued on one
    application-owned queue and applied in enqueue order by one worker that
    outlives every Service. The owner never waits on storage: the draft's id
    is a deferred the queued insert completes on the worker, later writes
    resolve it there, and the owner attaches the row to its surface from main
    when it lands. The finalization is reserved and enqueued as one operation
    under the publish lock; destroy takes the same lock, enqueues interrupted
    only when the interrupt won, cancels the session job without joining and
    returns. The three runBlocking blocks and the finishSession join are gone.
    
    Proven on the rig: the stop path's two status writes land in order with
    the first one held (the P4 race on 87e07ca); destroy returns on main with
    the disk held and the interrupted row lands when it answers.
    
    Part of #115

diff --git a/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
new file mode 100644
index 0000000..65d935b
--- /dev/null
+++ b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
@@ -0,0 +1,54 @@
+package com.envi.wispr.history
+
+import com.envi.wispr.debug.DebugLogger
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.channels.Channel
+import kotlinx.coroutines.launch
+
+/**
+ * Every per-take History write of this process, applied in the order it was ENQUEUED by one worker (#115).
+ *
+ * Application-owned, one per process: the session owner and the paste service both write a take's row,
+ * and before #115 each launched its writes on its own scope, so two writes to one row could land in either
+ * order (a `processing` status after the `asr_error` that followed it) and a teardown had to `runBlocking`
+ * on the main thread to make sure its own write was the last word. Here the order IS the enqueue order and
+ * nothing waits: [enqueue] never suspends and never blocks, and a write that throws is logged by its
+ * content-free label while the drain continues. A write left in the channel at process death is lost, as a
+ * launched coroutine was; the start-up recovery owns those rows.
+ *
+ * Per-take writers (insert, status, discard, finalize, the insertion outcome) go through here. The History
+ * screen's and the start-up recovery's writers (`setKept`, `delete`, `deleteAll`, `pruneWordlessRows`,
+ * `recoverStaleOpenRows`) stay on their own scopes: they never write a live take's row.
+ */
+internal class HistoryWriteQueue(
+    private val repository: TranscriptRepository,
+    /** An application scope: `SupervisorJob() + Dispatchers.IO`, never a Service's, so a write outlives the Service that issued it. */
+    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
+    private val warn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
+) {
+    companion object {
+        private const val TAG = "HistoryWriteQueue"
+    }
+
+    private class Write(val label: String, val body: suspend (TranscriptRepository) -> Unit)
+
+    private val writes = Channel<Write>(Channel.UNLIMITED)
+
+    init {
+        scope.launch {
+            for (write in writes) {
+                runCatching { write.body(repository) }
+                    .onFailure { warn("History write failed (${write.label}): ${it.javaClass.simpleName}") }
+            }
+        }
+    }
+
+    /**
+     * Queue one write. Returns at once; `true` unless the queue is closed, which it never is in production.
+     * [label] names the write for the log and carries no content.
+     */
+    fun enqueue(label: String, body: suspend (TranscriptRepository) -> Unit): Boolean =
+        writes.trySend(Write(label, body)).isSuccess
+}
diff --git a/app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt b/app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt
index 29454a4..6a3bd36 100644
--- a/app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt
+++ b/app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt
@@ -1,10 +1,28 @@
 package com.envi.wispr.models
 
 import android.app.Application
+import android.content.Context
+import com.envi.wispr.history.EnviousWisprDatabase
+import com.envi.wispr.history.HistoryWriteQueue
+import com.envi.wispr.history.TranscriptRepository
 import com.envi.wispr.telemetry.Telemetry
 
 /** Enqueues idempotent model bootstrap before any launcher or side-button activity can run. */
 class ModelBootstrapApplication : Application() {
+    companion object {
+        /**
+         * The process's one History write queue (#115): the session owner and the paste service both write a
+         * take's row, and the order those writes land in is the order they were enqueued here, on one worker
+         * that outlives every Service. Built on first use, in the default process, where both writers live.
+         */
+        internal fun historyWrites(context: Context): HistoryWriteQueue =
+            (context.applicationContext as ModelBootstrapApplication).historyWrites
+    }
+
+    private val historyWrites: HistoryWriteQueue by lazy {
+        HistoryWriteQueue(TranscriptRepository(EnviousWisprDatabase.get(this).transcriptDao()))
+    }
+
     override fun onCreate() {
         super.onCreate()
         // ABOVE the process gate on purpose (#176): every process, including `:audio`, `:asr`, `:vad`
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
index e90ef84..9b05d8c 100644
--- a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
+++ b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
@@ -12,6 +12,7 @@ import com.envi.wispr.audio.SpeechEvidence
 import com.envi.wispr.cleanup.LanguageDetector
 import com.envi.wispr.cleanup.TextSafety
 import com.envi.wispr.history.HistoryPublicationPolicy
+import com.envi.wispr.history.HistoryWriteQueue
 import com.envi.wispr.history.TranscriptEntity
 import com.envi.wispr.history.TranscriptRepository
 import com.envi.wispr.insertion.ClipboardOutcome
@@ -41,16 +42,12 @@ import com.envi.wispr.telemetry.Telemetry
 import com.envi.wispr.telemetry.TelemetryChannels
 import com.envi.wispr.vocabulary.CustomTerm
 import com.envi.wispr.vocabulary.StructuredTermRestorer
+import kotlinx.coroutines.CompletableDeferred
 import kotlinx.coroutines.CoroutineDispatcher
 import kotlinx.coroutines.CoroutineScope
-import kotlinx.coroutines.CoroutineStart
-import kotlinx.coroutines.Deferred
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.Job
-import kotlinx.coroutines.async
-import kotlinx.coroutines.joinAll
 import kotlinx.coroutines.launch
-import kotlinx.coroutines.runBlocking
 import kotlinx.coroutines.withContext
 import kotlinx.coroutines.withTimeoutOrNull
 import java.io.File
@@ -78,7 +75,8 @@ internal class DictationSessionCoordinator(
     private val insertion: InsertionGateway,
     private val log: SessionLog,
     private val preferences: SessionPreferencesSource,
-    private val transcripts: TranscriptRepository,
+    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
+    private val historyWrites: HistoryWriteQueue,
     private val languageDetector: LanguageDetector,
     private val loadPolicy: suspend () -> PolishPolicy,
     private val pipeline: PipelineController,
@@ -190,7 +188,6 @@ internal class DictationSessionCoordinator(
     private val draftId = AtomicLong(0L)
     /** The injected scope's job, read once; `destroy` cancels and joins exactly this. */
     private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }
-    private val pendingHistoryUpdates = java.util.Collections.synchronizedList(mutableListOf<Job>())
 
     private var rawTranscript = ""
     // What the START of this dictation saw when it tried to pin an editor. Read at the end,
@@ -210,7 +207,14 @@ internal class DictationSessionCoordinator(
     @Volatile private var stopAfterRecording = false
     private var recordingStartedAtMs = 0L
     @Volatile private var recordingDurationMs = 0L
-    private var draftCreation: Deferred<Long>? = null
+    /**
+     * The take's History row id, completed by the queued draft insert ON THE QUEUE'S WORKER (#115). Every
+     * later write of the row is queued after that insert, so awaiting this inside a write never waits on
+     * anything but a write already applied; the owner itself never awaits it on main.
+     */
+    private var draftCreation: CompletableDeferred<Long>? = null
+    /** Set by [destroy]; the owner's surface is never touched from the application queue after it. */
+    private val destroyed = AtomicBoolean(false)
     private var lastElapsedSecond = -1
     /**
      * The take's cancel, reserved under [publishLock] and committed when the capture process publishes
@@ -258,8 +262,10 @@ internal class DictationSessionCoordinator(
 
     /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
     fun onCreated() {
-        scope.launch {
-            runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
+        // On the queue, AHEAD of anything this take will write (#115): the recovery closes rows an earlier
+        // process left open, and it must not race the new take's own draft insert on another thread.
+        historyWrites.enqueue("stale-row recovery") { repository ->
+            runCatching { repository.recoverStaleOpenRows(System.currentTimeMillis()) }
                 .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
                 .onFailure { error -> log.warn("Unable to recover stale history: ${error.message}") }
         }
@@ -762,25 +768,43 @@ internal class DictationSessionCoordinator(
                 "take", "live",
                 mapOf("take_id" to takeId, "route_kind" to takeFacts.routeKind?.name?.lowercase(), "live_after_ms" to takeFacts.liveAfterMs, "live_state" to takeFacts.liveState),
             )
-            draftCreation = scope.async {
-                val id = transcripts.insert(
-                    TranscriptEntity(
-                        originalText = "",
-                        finalText = "",
-                        createdAtMs = System.currentTimeMillis(),
-                        durationMs = 0L,
-                        speechEngine = "Parakeet",
-                        polishEngine = PolishEngineLabels.NOT_RECORDED,
-                        polishLatencyMs = 0L,
-                        insertionResult = "pending",
-                        status = TranscriptEntity.STATUS_DRAFT,
-                    ),
-                )
+            // The FIRST queued write of the take (#115): it runs on the application's worker, under
+            // application ownership, so destroy cannot cancel it and every later write of the row is
+            // queued behind it. The id comes back through the deferred; the still-live owner attaches it
+            // to its surface from MAIN (a dead surface is never called from the queue).
+            val draft = CompletableDeferred<Long>()
+            draftCreation = draft
+            val rowTakeId = takeFacts.takeId
+            val createdAtMs = System.currentTimeMillis()
+            historyWrites.enqueue("draft insert") { repository ->
+                val id = try {
+                    repository.insert(
+                        TranscriptEntity(
+                            originalText = "",
+                            finalText = "",
+                            createdAtMs = createdAtMs,
+                            durationMs = 0L,
+                            speechEngine = "Parakeet",
+                            polishEngine = PolishEngineLabels.NOT_RECORDED,
+                            polishLatencyMs = 0L,
+                            insertionResult = "pending",
+                            status = TranscriptEntity.STATUS_DRAFT,
+                        ),
+                    )
+                } catch (error: Exception) {
+                    draft.completeExceptionally(error)
+                    throw error
+                }
+                draftId.set(id)
+                Telemetry.journal?.associate(rowTakeId, id)
+                draft.complete(id)
+            }
+            draft.invokeOnCompletion { cause ->
+                if (cause != null) return@invokeOnCompletion
+                val id = draft.getCompleted()
                 // The row's identity goes out on the bridge so a reader judges THIS take's row, never a
                 // row it guessed at by time or order (onboarding practice; Codex reviews 2 to 9).
-                surface.attachTranscript(id)
-                Telemetry.journal?.associate(takeFacts.takeId, id)
-                id
+                host.postToMain { if (!destroyed.get() && draftCreation === draft) surface.attachTranscript(id) }
             }
             host.updateSurfacePhase(DictationSurfaceState.Phase.LISTENING)
             surface.show()
@@ -958,11 +982,9 @@ internal class DictationSessionCoordinator(
                 )
                 finishTakeOrStop()
 
-                val readyDraftId = runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
-                if (readyDraftId > 0L) {
-                    draftId.set(readyDraftId)
-                    updateDraftStatus(TranscriptEntity.STATUS_PROCESSING)
-                }
+                // Queued behind the draft insert; the id is resolved on the worker (#115). Nothing here
+                // waits on storage.
+                updateDraftStatus(TranscriptEntity.STATUS_PROCESSING)
                 if (audioFilePath.isNullOrBlank()) {
                     updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                     showError(TerminalReason.AUDIO_FILE_MISSING)
@@ -1209,21 +1231,62 @@ internal class DictationSessionCoordinator(
         takeFacts.polishStatus = statusCode
         Telemetry.breadcrumb("take", "polish_done", mapOf("take_id" to takeId, "polish_reason" to reason.name, "polish_ms" to latencyMs, "polish_provider" to takeFacts.polishProvider))
         TelemetryChannels.defectOf(reason)?.let { Telemetry.defect(it, mapOf("take_id" to takeId, "polish_status" to statusCode)) }
+        // The immutable payload FIRST, so the reservation and its write can be one operation below.
+        val finalText = text.ifBlank { rawTranscript }
+        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
+        val originalText = rawTranscript
+        val durationMs = recordingDurationMs
+        val captureDevice = captureDeviceLabel
+        val polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext)
         // RESERVE, never commit: `completed` is unknown until the History save returns (G2 D2). A cancel
-        // that already owns the take, or a second final callback, loses here and does nothing.
-        val publication = arbiter.reserve(Claimants.PUBLICATION)
+        // that already owns the take, or a second final callback, loses here and does nothing. Under
+        // publishLock, and the write is ENQUEUED in the same operation (#115): destroy takes the same lock
+        // before enqueueing `interrupted`, so a reserved finalization is always queued ahead of it and
+        // `interrupted` is the last word on the row.
+        val saved = CompletableDeferred<Result<Long>>()
+        val publication = synchronized(publishLock) {
+            val reserved = arbiter.reserve(Claimants.PUBLICATION) ?: return@synchronized null
+            if (finalText.isNotBlank()) {
+                historyWrites.enqueue("finalize") { repository ->
+                    saved.complete(
+                        runCatching {
+                            val existingId = resolvedDraftId()
+                            val persistedId = if (existingId > 0L) {
+                                val updated = repository.finalize(
+                                    id = existingId,
+                                    originalText = originalText,
+                                    finalText = finalText,
+                                    speechEngine = "Parakeet",
+                                    polishEngine = finalEngine,
+                                    polishLatencyMs = latencyMs,
+                                    insertionResult = "pending",
+                                    durationMs = durationMs,
+                                    polishReason = polishFacts.reasonToken,
+                                    polishStatus = polishFacts.statusCode,
+                                    polishContext = polishFacts.contextToken,
+                                    captureDevice = captureDevice,
+                                )
+                                if (updated > 0) existingId else repository.insertReadyTranscript(originalText, finalText, finalEngine, latencyMs, durationMs, captureDevice, polishFacts)
+                            } else {
+                                repository.insertReadyTranscript(originalText, finalText, finalEngine, latencyMs, durationMs, captureDevice, polishFacts)
+                            }
+                            draftId.set(persistedId)
+                            persistedId
+                        },
+                    )
+                }
+            }
+            reserved
+        }
         if (publication == null) {
             log.warn("Ignoring a final transcript that arrived after the take was claimed")
             return
         }
-        val finalText = text.ifBlank { rawTranscript }
-        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
         log.log("Polish result received ($finalEngine, ${latencyMs}ms, chars=${finalText.length})")
         if (finalText.isBlank()) {
             if (arbiter.commit(publication, TerminalReason.FINAL_TEXT_EMPTY)) finishSession()
             return
         }
-        val polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext)
         polishFacts.notice?.let { notice ->
             log.log("Polish notice shown: ${polishFacts.failure}")
             host.postToMain {
@@ -1233,30 +1296,8 @@ internal class DictationSessionCoordinator(
         }
 
         scope.launch {
-            val saveResult = runCatching {
-                val existingId = draftId.get()
-                val persistedId = if (existingId > 0L) {
-                    val updated = transcripts.finalize(
-                        id = existingId,
-                        originalText = rawTranscript,
-                        finalText = finalText,
-                        speechEngine = "Parakeet",
-                        polishEngine = finalEngine,
-                        polishLatencyMs = latencyMs,
-                        insertionResult = "pending",
-                        durationMs = recordingDurationMs,
-                        polishReason = polishFacts.reasonToken,
-                        polishStatus = polishFacts.statusCode,
-                        polishContext = polishFacts.contextToken,
-                        captureDevice = captureDeviceLabel,
-                    )
-                    if (updated > 0) existingId else insertReadyTranscript(finalText, finalEngine, latencyMs, polishFacts)
-                } else {
-                    insertReadyTranscript(finalText, finalEngine, latencyMs, polishFacts)
-                }
-                draftId.set(persistedId)
-                persistedId
-            }
+            // The save's result, from the queue's worker; the owner's coroutine waits on it, main never.
+            val saveResult = saved.await()
             val persistedId = saveResult.getOrNull() ?: 0L
             takeFacts.historySave = if (saveResult.isSuccess) "ok" else "failed"
             saveResult.exceptionOrNull()?.let { error ->
@@ -1352,25 +1393,32 @@ internal class DictationSessionCoordinator(
         }
     }
 
-    private suspend fun insertReadyTranscript(finalText: String, engine: String, latencyMs: Long, polishFacts: PolishPublicationFacts): Long {
-        return transcripts.insert(
-            TranscriptEntity(
-                originalText = rawTranscript,
-                finalText = finalText,
-                createdAtMs = System.currentTimeMillis(),
-                durationMs = recordingDurationMs,
-                speechEngine = "Parakeet",
-                polishEngine = engine,
-                polishLatencyMs = latencyMs,
-                insertionResult = "pending",
-                status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
-                polishReason = polishFacts.reasonToken,
-                polishStatus = polishFacts.statusCode,
-                polishContext = polishFacts.contextToken,
-                captureDevice = captureDeviceLabel,
-            ),
-        )
-    }
+    /** The ready row when there is no draft to finalize; every value is the payload's, read before the reservation. */
+    private suspend fun TranscriptRepository.insertReadyTranscript(
+        originalText: String,
+        finalText: String,
+        engine: String,
+        latencyMs: Long,
+        durationMs: Long,
+        captureDevice: String,
+        polishFacts: PolishPublicationFacts,
+    ): Long = insert(
+        TranscriptEntity(
+            originalText = originalText,
+            finalText = finalText,
+            createdAtMs = System.currentTimeMillis(),
+            durationMs = durationMs,
+            speechEngine = "Parakeet",
+            polishEngine = engine,
+            polishLatencyMs = latencyMs,
+            insertionResult = "pending",
+            status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
+            polishReason = polishFacts.reasonToken,
+            polishStatus = polishFacts.statusCode,
+            polishContext = polishFacts.contextToken,
+            captureDevice = captureDevice,
+        ),
+    )
 
     /** @return whether the words actually reached the clipboard, which the copy depends on. */
     private suspend fun keepOnClipboard(
@@ -1380,15 +1428,13 @@ internal class DictationSessionCoordinator(
         val copied = host.copyToClipboard(text)
         if (transcriptId <= 0L) return copied
 
-        runCatching {
-            transcripts.finalizeInsertionOutcome(
+        historyWrites.enqueue("clipboard-only outcome") { repository ->
+            repository.finalizeInsertionOutcome(
                 transcriptId,
                 TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                 if (copied) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
                 interrupted = true,
             )
-        }.onFailure { error ->
-            log.warn("Unable to finalize clipboard-only history: ${error.message}")
         }
         return copied
     }
@@ -1418,17 +1464,15 @@ internal class DictationSessionCoordinator(
         }
     }
 
-    private suspend fun keepInHistoryOnly(transcriptId: Long) {
+    private fun keepInHistoryOnly(transcriptId: Long) {
         if (transcriptId <= 0L) return
-        runCatching {
-            transcripts.finalizeInsertionOutcome(
+        historyWrites.enqueue("history-only outcome") { repository ->
+            repository.finalizeInsertionOutcome(
                 transcriptId,
                 TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
                 InsertionResults.HISTORY_ONLY,
                 interrupted = true,
             )
-        }.onFailure { error ->
-            log.warn("Unable to finalize history-only transcript: ${error.message}")
         }
     }
 
@@ -1594,9 +1638,9 @@ internal class DictationSessionCoordinator(
         cancelOpenPolishRequest()
         surface.showProcessing()
         host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
-        val historyUpdates = synchronized(pendingHistoryUpdates) { pendingHistoryUpdates.toList() }
+        // The take's History writes are on the application's queue, in order; nothing here waits for
+        // them (#115). The Service may stop while the last of them is still landing.
         scope.launch {
-            historyUpdates.joinAll()
             host.postToMain {
                 cancelOpenPolishRequest()
                 // Both subscriptions go with the binding; nothing is called on the capture process here,
@@ -1629,17 +1673,18 @@ internal class DictationSessionCoordinator(
     }
 
     private fun updateDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
-        val update = scope.launch(start = CoroutineStart.LAZY) {
-            setDraftStatus(status, interrupted, insertionResult)
+        historyWrites.enqueue("draft status") { repository ->
+            val id = resolvedDraftId()
+            if (id > 0L) repository.updateStatus(id, status, interrupted, insertionResult)
         }
-        pendingHistoryUpdates += update
-        update.start()
     }
 
-    private suspend fun setDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
-        val id = draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
-        if (id > 0L) transcripts.updateStatus(id, status, interrupted, insertionResult)
-    }
+    /**
+     * The row's id, ON THE QUEUE'S WORKER: the draft insert is queued before every other write of the row,
+     * so this awaits at most a write already applied; a failed insert or a take that never went live is 0.
+     */
+    private suspend fun resolvedDraftId(): Long =
+        draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
 
     /**
      * A dictation that produced no words leaves nothing behind.
@@ -1667,34 +1712,33 @@ internal class DictationSessionCoordinator(
      * anything and the row is the only signal that words were lost. That is why the prune leaves
      * `interrupted` rows alone.
      *
-     * The id is cleared after the delete. That does not make a late write impossible — `setDraftStatus`
-     * can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
+     * The id is cleared after the delete. That does not make a late write impossible — a later queued
+     * write can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
      * `UPDATE` matches zero rows and cannot bring the draft back.
      */
     private fun discardDraft() {
-        val discard = scope.launch(start = CoroutineStart.LAZY) {
-            val id = draftId.get().takeIf { it > 0L }
-                ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
+        historyWrites.enqueue("discard") { repository ->
+            val id = resolvedDraftId()
             if (id > 0L) {
-                transcripts.discard(id)
+                repository.discard(id)
                 draftId.set(0L)
             }
         }
-        pendingHistoryUpdates += discard
-        discard.start()
     }
 
     /**
-     * The Service's `onDestroy` body, in the order it always ran: invalidate under [publishLock], close
-     * the ledger, resolve the interrupted row while its creation job is alive, cancel and join the
-     * session job, write `interrupted`, then clean up capture on its own thread or unbind here. The
-     * Service stops foreground and dismisses the notification after this returns. The `runBlocking`
-     * calls are a known conflict with `kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread,
-     * carried unchanged; #115 owns their removal.
+     * The Service's `onDestroy` body, on the main thread and never waiting on anything (#115): invalidate
+     * and claim the arbiter under [publishLock], and in the SAME operation enqueue the `interrupted` write
+     * when the interrupt won; disarm the bound; cancel the session job without joining it; cancel polish;
+     * then clean up capture on its own thread or unbind here. The Service stops foreground and dismisses
+     * the notification after this returns. A finalization reserved before this took the lock is already
+     * queued ahead of `interrupted`, so `interrupted` is the last word on the row; one not yet reserved
+     * loses the arbiter to this interrupt and never enqueues.
      */
     fun destroy() {
-        // Invalidate a live wait or a running take BEFORE any blocking cleanup, under the same lock the
-        // waiter publishes under: after this, no pill can appear for a take being torn down.
+        destroyed.set(true)
+        // Invalidate a live wait or a running take BEFORE any cleanup, under the same lock the live
+        // transition publishes under: after this, no pill can appear for a take being torn down.
         val destroyedState = synchronized(publishLock) {
             val seen = state.get()
             if (seen == SessionState.STARTING || seen == SessionState.RECORDING) state.set(SessionState.ERROR)
@@ -1702,13 +1746,28 @@ internal class DictationSessionCoordinator(
             // The synchronous lifecycle decision (G2 D2): `interrupted` is committed only when nothing
             // was, and it revokes an outstanding publication or cancel reservation so the displaced
             // worker can no longer commit, announce or hand off. Nothing here waits on storage.
-            when (seen) {
+            val interrupted = when (seen) {
                 SessionState.STARTING -> arbiter.interrupt(TerminalReason.INTERRUPTED_STARTING)
                 SessionState.RECORDING -> arbiter.interrupt(TerminalReason.INTERRUPTED_RECORDING)
                 SessionState.PROCESSING -> arbiter.interrupt(TerminalReason.INTERRUPTED_PROCESSING)
                 SessionState.CANCELLING -> arbiter.interrupt(TerminalReason.INTERRUPTED_CANCELLING)
                 SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> false
             }
+            if (interrupted) {
+                // Only when the interrupt WON: every terminal commit site owns its own later status or
+                // discard write, and a reserved finalization is already queued (plan §3 C1).
+                historyWrites.enqueue("interrupted") { repository ->
+                    val id = resolvedDraftId()
+                    if (id > 0L) {
+                        repository.updateStatus(
+                            id,
+                            TranscriptEntity.STATUS_INTERRUPTED,
+                            interrupted = true,
+                            insertionResult = "not_attempted",
+                        )
+                    }
+                }
+            }
             seen
         }
         // Both delayed callbacks go now, on main, before any blocking cleanup: a bound firing into a
@@ -1719,39 +1778,10 @@ internal class DictationSessionCoordinator(
             destroyedState == SessionState.RECORDING ||
             destroyedState == SessionState.PROCESSING ||
             destroyedState == SessionState.CANCELLING
-        val interruptedDraftId = if (sessionWasOpen) {
-            runCatching {
-                runBlocking(Dispatchers.IO) {
-                    draftId.get().takeIf { it > 0L }
-                        ?: draftCreation?.await()?.takeIf { it > 0L }
-                        ?: 0L
-                }
-            }.onFailure { error ->
-                log.warn("Unable to resolve interrupted history row during teardown: ${error.message}")
-            }.getOrDefault(0L)
-        } else {
-            0L
-        }
-        // Stop any in-flight finalization before writing the terminal teardown state.
-        // This keeps a late polish callback from changing an interrupted row back to ready.
-        runBlocking(Dispatchers.IO) {
-            serviceJob.cancel()
-            serviceJob.join()
-        }
-        if (interruptedDraftId > 0L) {
-            runCatching {
-                runBlocking(Dispatchers.IO) {
-                    transcripts.updateStatus(
-                        interruptedDraftId,
-                        TranscriptEntity.STATUS_INTERRUPTED,
-                        interrupted = true,
-                        insertionResult = "not_attempted",
-                    )
-                }
-            }.onFailure { error ->
-                log.warn("Unable to mark interrupted session during teardown: ${error.message}")
-            }
-        }
+        // Cancelled, never joined (#115): a late polish callback cannot restore `ready` after
+        // `interrupted` because the finalization it would write either lost the arbiter above or was
+        // already queued ahead of the `interrupted` write.
+        serviceJob.cancel()
         val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
         if (captureRunning && teardownStarted.compareAndSet(false, true)) {
             // Captures the link and the controller only: the Service is dead once `onDestroy` returns,
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-0embHm9x' (errno=Operation not permitted)
2026-09-21 20:02:32.948 xcodebuild[48761:78113606]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:33.060 xcodebuild[48761:78113605] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-HplOUQec' (errno=Operation not permitted)
2026-09-21 20:02:33.445 xcodebuild[48763:78113622]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:02:33.557 xcodebuild[48763:78113621] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
commit b3f4a38a32b041a3f438ca40817bf7b16edccb46
Author: saurabhav88 <saurabhav@gmail.com>
Date:   Mon Sep 21 19:55:55 2026 -0400

    refactor(ui): #115 chunk C, three teardowns that wait on nothing
    
    The owner's destroy stops capture down its command lane instead of a
    thread of its own, stops the audio service by intent and unbinds on main
    at once. The paste service's onDestroy no longer drains its scope on the
    main thread: the outcome write is on the application's History queue and
    the clean-stop marker is queued behind it, so a kill during the write still
    reads as unclean. The audio service's onDestroy no longer joins the capture
    thread for two seconds: the thread releases the recorder and the file in
    its own releaseSession when its read returns, and a thread parked in a read
    is abandoned to process termination; a new take cannot open a second
    recorder while the old one is held because startRecording refuses while
    the session is set. The posted-unbind helper had no caller left and is
    gone. A shape row pins all three teardowns.
    
    Part of #115

diff --git a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
index 777c105..8066ff6 100644
--- a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
+++ b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
@@ -841,29 +841,26 @@ class AudioCaptureService : Service() {
             active.detector.close(unbindNow = true)
             active.picture.close()
         }
-        val thread = captureThread
-        if (thread != null && thread !== Thread.currentThread()) {
-            thread.join(2_000L)
-            if (thread.isAlive) {
-                // The capture thread is the sole owner of AudioRecord and the file. Do not
-                // close either resource here after the bounded wait. Process termination of
-                // the isolated :audio service will reclaim them without an ANR-length wait.
-                DebugLogger.warn(TAG, "Capture thread did not finish during service teardown")
-            }
-        }
+        // NOT joined (#115): the capture thread is the sole owner of AudioRecord and the file, and it
+        // releases both in its own releaseSession whenever its read returns; a thread parked inside a
+        // read is abandoned to process termination rather than held on the main thread for two seconds.
+        // A new take in this process cannot open a second recorder while the old one is held:
+        // startRecording refuses while `session` is set, and releaseSession clears it only after the
+        // recorder is released.
         if (captureThread?.isAlive != true) {
             captureThread = null
             synchronized(sessionLock) {
                 isRecording.set(false)
             }
         }
-        // A hold that slipped in between the flag and the join is ended here, before its expiry dies.
+        // A hold that slipped in between the flag and the stop is ended here, before its expiry dies.
         synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }
-        // After the join: the capture thread's cleanup removed its listener; nothing else posts here.
+        // The route thread quits after what is already posted; the capture thread's cleanup removes its
+        // own listener when it runs.
         routeThread.quitSafely()
-        // After the join too: a take that ended above published its ending through this worker, which
-        // delivers what is queued and then leaves (#115). Never joined. Both slots cleared here as well:
-        // a destroyed service pushes nothing.
+        // A take that ended above published its ending through this worker, which delivers what is queued
+        // and then leaves (#115). Never joined. Both slots cleared here as well: a destroyed service
+        // pushes nothing.
         takeEvents.close()
         spectrumListener.set(null)
         takeListener.set(null)
diff --git a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
index ff654ae..e416a15 100644
--- a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
+++ b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
@@ -47,15 +47,12 @@ import com.envi.wispr.ui.DictationSessionService
 import com.envi.wispr.settings.AppPreferences
 import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Dispatchers
-import kotlinx.coroutines.Job
 import kotlinx.coroutines.SupervisorJob
 import kotlinx.coroutines.cancel
 import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
-import kotlinx.coroutines.joinAll
 import kotlinx.coroutines.launch
-import kotlinx.coroutines.runBlocking
 import java.util.UUID
 
 class PasteAccessibilityService : AccessibilityService() {
@@ -609,8 +606,7 @@ class PasteAccessibilityService : AccessibilityService() {
     }
 
     override fun onDestroy() {
-        // Retract the publication FIRST. Teardown below blocks this thread draining Room, and a
-        // reader during that window would otherwise see a healthy binding on a dying service.
+        // Retract the publication FIRST, so no reader sees a healthy binding on a dying service.
         if (instance === this) publishBinding(null)
         lookScope.cancel()
         getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDeviceCallback)
@@ -620,25 +616,24 @@ class PasteAccessibilityService : AccessibilityService() {
         mainHandler.removeCallbacks(retryRunnable)
         retryScheduled = false
         cancelDiscoveryRetries()
-        // Announced BEFORE the blocking Room drain below, for the reason spelled out on
-        // recordAndAnnounce: what survives this teardown is the durable notification, and it only
-        // survives if it is handed to the system while this process is still alive.
+        // Announced here, for the reason spelled out on recordAndAnnounce: what survives this teardown
+        // is the durable notification, and it only survives if it is handed to the system while this
+        // process is still alive. Its History write goes on the application's queue (#115).
         pendingInsertion?.let { pending ->
             pending.targetPackage = pinnedTarget?.packageName
             logOutcome(pending, InsertionOutcomeLine.Outcome.DESTROYED, pinnedTarget?.packageName)
             recordAndAnnounce(ServiceFallbackReason.SERVICE_DESTROYED, pending)
         }
         pendingInsertion = null
-        runBlocking(Dispatchers.IO) {
-            historyScope.coroutineContext[Job]?.children?.toList()?.joinAll()
-        }
+        // Nothing is drained here (#115): the take's History writes are the application queue's, in
+        // order, and the bubble-position saves on this scope are a preference whose next save wins, so a
+        // cancelled in-flight one costs nothing the next open does not restore.
         historyScope.cancel()
         clearPinnedTarget()
         clearTarget()
-        // LAST, after the blocking Room drain above. Marking clean before it would record a system
-        // kill that lands during the drain as an orderly stop, which is the case the marker exists
-        // to catch.
-        markStopWasClean()
+        // LAST on the queue, behind the outcome write above: a system kill that lands while that write is
+        // still pending must not read as an orderly stop, which is the case the marker exists to catch.
+        ModelBootstrapApplication.historyWrites(applicationContext).enqueue("clean-stop marker") { markStopWasClean() }
         super.onDestroy()
     }
 
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
index 9b05d8c..b70b8fe 100644
--- a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
+++ b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
@@ -1784,23 +1784,19 @@ internal class DictationSessionCoordinator(
         serviceJob.cancel()
         val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
         if (captureRunning && teardownStarted.compareAndSet(false, true)) {
-            // Captures the link and the controller only: the Service is dead once `onDestroy` returns,
-            // and this thread outlives it (`PipelineBindings` binds through the application context).
-            val capture = pipeline.capture
-            val pipeline = pipeline
+            // Down the lane, like every other call into the capture process (#115): the lane's daemon
+            // thread outlives the Service, and the link it captured stays valid after the unbind below.
             // The file is the capture process's one cache file and the next take overwrites it; nothing
-            // waits for it here (#115).
-            Thread({
-                runCatching { capture?.stopCapture() }
-                pipeline.stopAudioService()
-                pipeline.postUnbindToMain(::cancelOpenPolishRequest)
-            }, "DestroyedSessionCleanup").start()
-        } else {
-            if (sessionWasOpen) pipeline.stopAudioService()
-            cancelOpenPolishRequest()
-            pipeline.unbind()
+            // waits for it here.
+            commandCapture("stop at destroy") { it.stopCapture() }
         }
-        // No further command is accepted; a command already running finishes on its own thread.
+        // The service is stopped by intent whether or not that stop is ever delivered (a wedged process
+        // gets neither, and the OS or the user ends it); the bindings go now, on main, with nothing waited
+        // for.
+        if (sessionWasOpen) pipeline.stopAudioService()
+        cancelOpenPolishRequest()
+        pipeline.unbind()
+        // No further command is accepted; the stop above, if queued, still runs on the lane's own thread.
         captureCommands.shutdown()
     }
 }
diff --git a/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt b/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
index 2319985..4efe687 100644
--- a/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
+++ b/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
@@ -129,12 +129,6 @@ internal interface PipelineController {
     /** Unbinds whatever is bound and clears the links. Idempotent. */
     fun unbind()
 
-    /**
-     * [beforeUnbind] then [unbind], posted to the main looper, for a cleanup thread that outlives the
-     * Service; the owner passes its idempotent polish-cancel backstop, as the old posted unbind ran it.
-     */
-    fun postUnbindToMain(beforeUnbind: () -> Unit)
-
     /** `stopService` on the capture service, for the paths that stop it rather than let it hold the earbuds. */
     fun stopAudioService()
 

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
nl -ba app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '74,100p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '205,225p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '262,275p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '770,812p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
     1	package com.envi.wispr.history
     2	
     3	import com.envi.wispr.debug.DebugLogger
     4	import kotlinx.coroutines.CoroutineScope
     5	import kotlinx.coroutines.Dispatchers
     6	import kotlinx.coroutines.SupervisorJob
     7	import kotlinx.coroutines.channels.Channel
     8	import kotlinx.coroutines.launch
     9	
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
    21	 * Per-take writers (insert, status, discard, finalize, the insertion outcome) go through here. The History
    22	 * screen's and the start-up recovery's writers (`setKept`, `delete`, `deleteAll`, `pruneWordlessRows`,
    23	 * `recoverStaleOpenRows`) stay on their own scopes: they never write a live take's row.
    24	 */
    25	internal class HistoryWriteQueue(
    26	    private val repository: TranscriptRepository,
    27	    /** An application scope: `SupervisorJob() + Dispatchers.IO`, never a Service's, so a write outlives the Service that issued it. */
    28	    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    29	    private val warn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
    30	) {
    31	    companion object {
    32	        private const val TAG = "HistoryWriteQueue"
    33	    }
    34	
    35	    private class Write(val label: String, val body: suspend (TranscriptRepository) -> Unit)
    36	
    37	    private val writes = Channel<Write>(Channel.UNLIMITED)
    38	
    39	    init {
    40	        scope.launch {
    41	            for (write in writes) {
    42	                runCatching { write.body(repository) }
    43	                    .onFailure { warn("History write failed (${write.label}): ${it.javaClass.simpleName}") }
    44	            }
    45	        }
    46	    }
    47	
    48	    /**
    49	     * Queue one write. Returns at once; `true` unless the queue is closed, which it never is in production.
    50	     * [label] names the write for the log and carries no content.
    51	     */
    52	    fun enqueue(label: String, body: suspend (TranscriptRepository) -> Unit): Boolean =
    53	        writes.trySend(Write(label, body)).isSuccess
    54	}
     1	package com.envi.wispr.models
     2	
     3	import android.app.Application
     4	import android.content.Context
     5	import com.envi.wispr.history.EnviousWisprDatabase
     6	import com.envi.wispr.history.HistoryWriteQueue
     7	import com.envi.wispr.history.TranscriptRepository
     8	import com.envi.wispr.telemetry.Telemetry
     9	
    10	/** Enqueues idempotent model bootstrap before any launcher or side-button activity can run. */
    11	class ModelBootstrapApplication : Application() {
    12	    companion object {
    13	        /**
    14	         * The process's one History write queue (#115): the session owner and the paste service both write a
    15	         * take's row, and the order those writes land in is the order they were enqueued here, on one worker
    16	         * that outlives every Service. Built on first use, in the default process, where both writers live.
    17	         */
    18	        internal fun historyWrites(context: Context): HistoryWriteQueue =
    19	            (context.applicationContext as ModelBootstrapApplication).historyWrites
    20	    }
    21	
    22	    private val historyWrites: HistoryWriteQueue by lazy {
    23	        HistoryWriteQueue(TranscriptRepository(EnviousWisprDatabase.get(this).transcriptDao()))
    24	    }
    25	
    26	    override fun onCreate() {
    27	        super.onCreate()
    28	        // ABOVE the process gate on purpose (#176): every process, including `:audio`, `:asr`, `:vad`
    29	        // and `:polish`, boots its own crash reporting here; only main goes on to PostHog. A limb: it
    30	        // never throws and never blocks on the network.
    31	        Telemetry.bootstrap(this)
    32	        if (Application.getProcessName() != packageName) return
    33	        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.parakeet)
    34	        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.s1)
    35	    }
    36	}
    74	    private val surface: RecorderSurface,
    75	    private val insertion: InsertionGateway,
    76	    private val log: SessionLog,
    77	    private val preferences: SessionPreferencesSource,
    78	    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
    79	    private val historyWrites: HistoryWriteQueue,
    80	    private val languageDetector: LanguageDetector,
    81	    private val loadPolicy: suspend () -> PolishPolicy,
    82	    private val pipeline: PipelineController,
    83	    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is joined on destroy. */
    84	    private val scope: CoroutineScope,
    85	    /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
    86	    private val mainDispatcher: CoroutineDispatcher,
    87	    private val polishTimeout: PolishTimeout = DelayPolishTimeout,
    88	    /** How long a take waits for the settings readers to answer before starting on the last values; a test shortens it (#193). */
    89	    private val answerBoundMs: Long = SETTINGS_ANSWER_BOUND_MS,
    90	    /** Process-scoped on purpose: the tip's once-per-process allowance outlives the Service instance. */
    91	    private val tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS,
    92	    /** The one first-wins gate on the polish answer; production mints ids off the device clock, a test off the JVM's. */
    93	    private val polishLedger: PolishRequestLedger = PolishRequestLedger(),
    94	    /** The arbiter's sink: where a committed ending goes. Production records it to telemetry. */
    95	    private val endingSink: (TakeFacts, TerminalReason) -> Unit = ::recordTakeEnding,
    96	) : PipelineController.Listener {
    97	    companion object {
    98	        /**
    99	         * macOS's own sentence for this state, reused rather than reinvented. Android writing its own
   100	         * words for a state macOS has already worded is how the two products drift apart.
   205	     * from STARTING (issue #135 plan §3 "Stop and cancel").
   206	     */
   207	    @Volatile private var stopAfterRecording = false
   208	    private var recordingStartedAtMs = 0L
   209	    @Volatile private var recordingDurationMs = 0L
   210	    /**
   211	     * The take's History row id, completed by the queued draft insert ON THE QUEUE'S WORKER (#115). Every
   212	     * later write of the row is queued after that insert, so awaiting this inside a write never waits on
   213	     * anything but a write already applied; the owner itself never awaits it on main.
   214	     */
   215	    private var draftCreation: CompletableDeferred<Long>? = null
   216	    /** Set by [destroy]; the owner's surface is never touched from the application queue after it. */
   217	    private val destroyed = AtomicBoolean(false)
   218	    private var lastElapsedSecond = -1
   219	    /**
   220	     * The take's cancel, reserved under [publishLock] and committed when the capture process publishes
   221	     * the ending (#115): a cancel no longer waits for the file itself.
   222	     */
   223	    @Volatile private var pendingCancel: TakeArbiter.Token? = null
   224	    @Volatile private var pendingCancelReason: TerminalReason = TerminalReason.CANCELLED_RECORDING
   225	    /** Set once the ending has been consumed for this take, so a duplicate or a late event changes nothing. */
   262	
   263	    /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
   264	    fun onCreated() {
   265	        // On the queue, AHEAD of anything this take will write (#115): the recovery closes rows an earlier
   266	        // process left open, and it must not race the new take's own draft insert on another thread.
   267	        historyWrites.enqueue("stale-row recovery") { repository ->
   268	            runCatching { repository.recoverStaleOpenRows(System.currentTimeMillis()) }
   269	                .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
   270	                .onFailure { error -> log.warn("Unable to recover stale history: ${error.message}") }
   271	        }
   272	        preferences.start(scope)
   273	    }
   274	
   275	    /**
   770	            )
   771	            // The FIRST queued write of the take (#115): it runs on the application's worker, under
   772	            // application ownership, so destroy cannot cancel it and every later write of the row is
   773	            // queued behind it. The id comes back through the deferred; the still-live owner attaches it
   774	            // to its surface from MAIN (a dead surface is never called from the queue).
   775	            val draft = CompletableDeferred<Long>()
   776	            draftCreation = draft
   777	            val rowTakeId = takeFacts.takeId
   778	            val createdAtMs = System.currentTimeMillis()
   779	            historyWrites.enqueue("draft insert") { repository ->
   780	                val id = try {
   781	                    repository.insert(
   782	                        TranscriptEntity(
   783	                            originalText = "",
   784	                            finalText = "",
   785	                            createdAtMs = createdAtMs,
   786	                            durationMs = 0L,
   787	                            speechEngine = "Parakeet",
   788	                            polishEngine = PolishEngineLabels.NOT_RECORDED,
   789	                            polishLatencyMs = 0L,
   790	                            insertionResult = "pending",
   791	                            status = TranscriptEntity.STATUS_DRAFT,
   792	                        ),
   793	                    )
   794	                } catch (error: Exception) {
   795	                    draft.completeExceptionally(error)
   796	                    throw error
   797	                }
   798	                draftId.set(id)
   799	                Telemetry.journal?.associate(rowTakeId, id)
   800	                draft.complete(id)
   801	            }
   802	            draft.invokeOnCompletion { cause ->
   803	                if (cause != null) return@invokeOnCompletion
   804	                val id = draft.getCompleted()
   805	                // The row's identity goes out on the bridge so a reader judges THIS take's row, never a
   806	                // row it guessed at by time or order (onboarding practice; Codex reviews 2 to 9).
   807	                host.postToMain { if (!destroyed.get() && draftCreation === draft) surface.attachTranscript(id) }
   808	            }
   809	            host.updateSurfacePhase(DictationSurfaceState.Phase.LISTENING)
   810	            surface.show()
   811	            host.vibrate(HapticCue.SESSION_TRANSITION)
   812	            log.log("Recording started (live after $liveAfterMs ms, forced=$forced)")

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1210,1300p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1395,1480p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1636,1660p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1672,1730p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1738,1802p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
  1210	    /**
  1211	     * The one place a polish outcome becomes a History row and, when it did not do its job, a sentence
  1212	     * (#77). Two routes reach it, the outcome callback and `publishFallback`; the facts are derived once
  1213	     * here, and the notice is posted BEFORE persistence and insertion begin so it precedes the delivery
  1214	     * line when both fire.
  1215	     */
  1216	    private fun publishResult(
  1217	        text: String,
  1218	        engine: String,
  1219	        latencyMs: Long,
  1220	        reason: PolishReason,
  1221	        statusCode: Int,
  1222	        polishContext: PolishContext,
  1223	    ) {
  1224	        // The polish facts, written before the reservation so an ending committed by anyone after this
  1225	        // point carries them; the reason arrives once per take through the ledger, so the defect it may
  1226	        // name is raised once here, whoever ends up owning the take (issue #176, plan §3.6).
  1227	        val takeFacts = facts
  1228	        takeFacts.polishProvider = polishContext.encode()
  1229	        takeFacts.polishReason = reason
  1230	        takeFacts.polishMs = latencyMs
  1231	        takeFacts.polishStatus = statusCode
  1232	        Telemetry.breadcrumb("take", "polish_done", mapOf("take_id" to takeId, "polish_reason" to reason.name, "polish_ms" to latencyMs, "polish_provider" to takeFacts.polishProvider))
  1233	        TelemetryChannels.defectOf(reason)?.let { Telemetry.defect(it, mapOf("take_id" to takeId, "polish_status" to statusCode)) }
  1234	        // The immutable payload FIRST, so the reservation and its write can be one operation below.
  1235	        val finalText = text.ifBlank { rawTranscript }
  1236	        val finalEngine = if (text.isBlank() && rawTranscript.isNotBlank()) PolishEngineLabels.RAW_FALLBACK else engine
  1237	        val originalText = rawTranscript
  1238	        val durationMs = recordingDurationMs
  1239	        val captureDevice = captureDeviceLabel
  1240	        val polishFacts = PolishPublicationFacts.from(reason, statusCode, polishContext)
  1241	        // RESERVE, never commit: `completed` is unknown until the History save returns (G2 D2). A cancel
  1242	        // that already owns the take, or a second final callback, loses here and does nothing. Under
  1243	        // publishLock, and the write is ENQUEUED in the same operation (#115): destroy takes the same lock
  1244	        // before enqueueing `interrupted`, so a reserved finalization is always queued ahead of it and
  1245	        // `interrupted` is the last word on the row.
  1246	        val saved = CompletableDeferred<Result<Long>>()
  1247	        val publication = synchronized(publishLock) {
  1248	            val reserved = arbiter.reserve(Claimants.PUBLICATION) ?: return@synchronized null
  1249	            if (finalText.isNotBlank()) {
  1250	                historyWrites.enqueue("finalize") { repository ->
  1251	                    saved.complete(
  1252	                        runCatching {
  1253	                            val existingId = resolvedDraftId()
  1254	                            val persistedId = if (existingId > 0L) {
  1255	                                val updated = repository.finalize(
  1256	                                    id = existingId,
  1257	                                    originalText = originalText,
  1258	                                    finalText = finalText,
  1259	                                    speechEngine = "Parakeet",
  1260	                                    polishEngine = finalEngine,
  1261	                                    polishLatencyMs = latencyMs,
  1262	                                    insertionResult = "pending",
  1263	                                    durationMs = durationMs,
  1264	                                    polishReason = polishFacts.reasonToken,
  1265	                                    polishStatus = polishFacts.statusCode,
  1266	                                    polishContext = polishFacts.contextToken,
  1267	                                    captureDevice = captureDevice,
  1268	                                )
  1269	                                if (updated > 0) existingId else repository.insertReadyTranscript(originalText, finalText, finalEngine, latencyMs, durationMs, captureDevice, polishFacts)
  1270	                            } else {
  1271	                                repository.insertReadyTranscript(originalText, finalText, finalEngine, latencyMs, durationMs, captureDevice, polishFacts)
  1272	                            }
  1273	                            draftId.set(persistedId)
  1274	                            persistedId
  1275	                        },
  1276	                    )
  1277	                }
  1278	            }
  1279	            reserved
  1280	        }
  1281	        if (publication == null) {
  1282	            log.warn("Ignoring a final transcript that arrived after the take was claimed")
  1283	            return
  1284	        }
  1285	        log.log("Polish result received ($finalEngine, ${latencyMs}ms, chars=${finalText.length})")
  1286	        if (finalText.isBlank()) {
  1287	            if (arbiter.commit(publication, TerminalReason.FINAL_TEXT_EMPTY)) finishSession()
  1288	            return
  1289	        }
  1290	        polishFacts.notice?.let { notice ->
  1291	            log.log("Polish notice shown: ${polishFacts.failure}")
  1292	            host.postToMain {
  1293	                host.toastFromService(notice.toastLine)
  1294	                host.showPolishNotice(notice)
  1295	            }
  1296	        }
  1297	
  1298	        scope.launch {
  1299	            // The save's result, from the queue's worker; the owner's coroutine waits on it, main never.
  1300	            val saveResult = saved.await()
  1395	
  1396	    /** The ready row when there is no draft to finalize; every value is the payload's, read before the reservation. */
  1397	    private suspend fun TranscriptRepository.insertReadyTranscript(
  1398	        originalText: String,
  1399	        finalText: String,
  1400	        engine: String,
  1401	        latencyMs: Long,
  1402	        durationMs: Long,
  1403	        captureDevice: String,
  1404	        polishFacts: PolishPublicationFacts,
  1405	    ): Long = insert(
  1406	        TranscriptEntity(
  1407	            originalText = originalText,
  1408	            finalText = finalText,
  1409	            createdAtMs = System.currentTimeMillis(),
  1410	            durationMs = durationMs,
  1411	            speechEngine = "Parakeet",
  1412	            polishEngine = engine,
  1413	            polishLatencyMs = latencyMs,
  1414	            insertionResult = "pending",
  1415	            status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
  1416	            polishReason = polishFacts.reasonToken,
  1417	            polishStatus = polishFacts.statusCode,
  1418	            polishContext = polishFacts.contextToken,
  1419	            captureDevice = captureDevice,
  1420	        ),
  1421	    )
  1422	
  1423	    /** @return whether the words actually reached the clipboard, which the copy depends on. */
  1424	    private suspend fun keepOnClipboard(
  1425	        transcriptId: Long,
  1426	        text: String,
  1427	    ): Boolean {
  1428	        val copied = host.copyToClipboard(text)
  1429	        if (transcriptId <= 0L) return copied
  1430	
  1431	        historyWrites.enqueue("clipboard-only outcome") { repository ->
  1432	            repository.finalizeInsertionOutcome(
  1433	                transcriptId,
  1434	                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
  1435	                if (copied) InsertionResults.CLIPBOARD else InsertionResults.INSERTION_FAILED,
  1436	                interrupted = true,
  1437	            )
  1438	        }
  1439	        return copied
  1440	    }
  1441	
  1442	    /**
  1443	     * Tells the user where their words went, in one calm line and nothing else.
  1444	     *
  1445	     * Whether to speak at all is `FallbackAnnouncement`'s decision, not this method's: a user
  1446	     * who never granted the permission is in clipboard-only mode by choice and gets nothing. What
  1447	     * it says is a measured destination and never an inferred fault, which is why there is no
  1448	     * failure haptic and nothing left in the shade: this is an ordinary outcome of a working
  1449	     * product, not an error.
  1450	     */
  1451	    private fun announceInsertionFallback(
  1452	        handoff: InsertionHandoff,
  1453	        clipboard: ClipboardOutcome,
  1454	        savedInHistory: Boolean,
  1455	    ) {
  1456	        val announcement = FallbackAnnouncement.fallbackAnnouncement(
  1457	            autoPaste = host.autoPasteAvailability(),
  1458	            handoff = handoff,
  1459	            clipboard = clipboard,
  1460	            savedInHistory = savedInHistory,
  1461	        ) ?: return
  1462	        host.postToMain {
  1463	            host.toastFromService(announcement.line)
  1464	        }
  1465	    }
  1466	
  1467	    private fun keepInHistoryOnly(transcriptId: Long) {
  1468	        if (transcriptId <= 0L) return
  1469	        historyWrites.enqueue("history-only outcome") { repository ->
  1470	            repository.finalizeInsertionOutcome(
  1471	                transcriptId,
  1472	                TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
  1473	                InsertionResults.HISTORY_ONLY,
  1474	                interrupted = true,
  1475	            )
  1476	        }
  1477	    }
  1478	
  1479	    private fun cancelRecording() {
  1480	        val cancel = synchronized(publishLock) {
  1636	    private fun finishSession() {
  1637	        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
  1638	        cancelOpenPolishRequest()
  1639	        surface.showProcessing()
  1640	        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
  1641	        // The take's History writes are on the application's queue, in order; nothing here waits for
  1642	        // them (#115). The Service may stop while the last of them is still landing.
  1643	        scope.launch {
  1644	            host.postToMain {
  1645	                cancelOpenPolishRequest()
  1646	                // Both subscriptions go with the binding; nothing is called on the capture process here,
  1647	                // which may be the unresponsive process this take just ended over (#115).
  1648	                disarmSilenceBound()
  1649	                pipeline.unbind()
  1650	                host.removeForegroundAndDismiss()
  1651	                // IDLE is published at the last moment this instance can still refuse a start: the
  1652	                // next command creates a fresh instance whose state begins IDLE.
  1653	                admittedRequest = null
  1654	                stopAfterRecording = false
  1655	                surface.hide()
  1656	                host.stopSelfNow()
  1657	            }
  1658	        }
  1659	    }
  1660	
  1672	        }.onFailure { error -> log.warn("Unable to delete captured audio: ${error.message}") }
  1673	    }
  1674	
  1675	    private fun updateDraftStatus(status: String, interrupted: Boolean = false, insertionResult: String? = null) {
  1676	        historyWrites.enqueue("draft status") { repository ->
  1677	            val id = resolvedDraftId()
  1678	            if (id > 0L) repository.updateStatus(id, status, interrupted, insertionResult)
  1679	        }
  1680	    }
  1681	
  1682	    /**
  1683	     * The row's id, ON THE QUEUE'S WORKER: the draft insert is queued before every other write of the row,
  1684	     * so this awaits at most a write already applied; a failed insert or a take that never went live is 0.
  1685	     */
  1686	    private suspend fun resolvedDraftId(): Long =
  1687	        draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)
  1688	
  1689	    /**
  1690	     * A dictation that produced no words leaves nothing behind.
  1691	     *
  1692	     * The draft row is created the moment recording starts, so that a session killed mid-flight is
  1693	     * still recoverable. Every caller here reaches a terminal state with no transcript WORDS — the
  1694	     * microphone heard nothing, or the session ended before transcription could produce any — so
  1695	     * that row has never held a word and never will, and keeping it turns History into a list the
  1696	     * user has to scroll past to reach their own dictations (founder, 2026-08-31: "we shouldn't log
  1697	     * 'no speech' logs -> that's a waste of history space"; issue #19 says the same about
  1698	     * cancelling).
  1699	     *
  1700	     * **The line is whether the outcome was already ACCOUNTED FOR while the app was alive**, and it
  1701	     * is reached three different ways here. A failure the app survived shows the user a message: a
  1702	     * terminal capture failure, a capture that would not close before transcription, a service
  1703	     * failure while recording, and a cancel whose audio did not close cleanly. A successful cancel
  1704	     * shows no message and does not need one — the user pressed cancel, and the haptic and the
  1705	     * overlay closing acknowledge it. Nothing heard is silent on purpose, and leaves nothing behind
  1706	     * for the same reason: hearing nothing is not an event worth reporting twice. In all three, a
  1707	     * blank History card adds nothing.
  1708	     *
  1709	     * The two writers of `STATUS_INTERRUPTED` that REMAIN are the opposite case, and both keep their
  1710	     * row: this owner's own `destroy` teardown, and `TranscriptDao.recoverStaleDrafts` on the
  1711	     * next start. Both run when the app was killed with a dictation live, so nobody told the user
  1712	     * anything and the row is the only signal that words were lost. That is why the prune leaves
  1713	     * `interrupted` rows alone.
  1714	     *
  1715	     * The id is cleared after the delete. That does not make a late write impossible — a later queued
  1716	     * write can still resolve the completed `draftCreation` to the old id — it makes one harmless: the
  1717	     * `UPDATE` matches zero rows and cannot bring the draft back.
  1718	     */
  1719	    private fun discardDraft() {
  1720	        historyWrites.enqueue("discard") { repository ->
  1721	            val id = resolvedDraftId()
  1722	            if (id > 0L) {
  1723	                repository.discard(id)
  1724	                draftId.set(0L)
  1725	            }
  1726	        }
  1727	    }
  1728	
  1729	    /**
  1730	     * The Service's `onDestroy` body, on the main thread and never waiting on anything (#115): invalidate
  1738	    fun destroy() {
  1739	        destroyed.set(true)
  1740	        // Invalidate a live wait or a running take BEFORE any cleanup, under the same lock the live
  1741	        // transition publishes under: after this, no pill can appear for a take being torn down.
  1742	        val destroyedState = synchronized(publishLock) {
  1743	            val seen = state.get()
  1744	            if (seen == SessionState.STARTING || seen == SessionState.RECORDING) state.set(SessionState.ERROR)
  1745	            surface.hide()
  1746	            // The synchronous lifecycle decision (G2 D2): `interrupted` is committed only when nothing
  1747	            // was, and it revokes an outstanding publication or cancel reservation so the displaced
  1748	            // worker can no longer commit, announce or hand off. Nothing here waits on storage.
  1749	            val interrupted = when (seen) {
  1750	                SessionState.STARTING -> arbiter.interrupt(TerminalReason.INTERRUPTED_STARTING)
  1751	                SessionState.RECORDING -> arbiter.interrupt(TerminalReason.INTERRUPTED_RECORDING)
  1752	                SessionState.PROCESSING -> arbiter.interrupt(TerminalReason.INTERRUPTED_PROCESSING)
  1753	                SessionState.CANCELLING -> arbiter.interrupt(TerminalReason.INTERRUPTED_CANCELLING)
  1754	                SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> false
  1755	            }
  1756	            if (interrupted) {
  1757	                // Only when the interrupt WON: every terminal commit site owns its own later status or
  1758	                // discard write, and a reserved finalization is already queued (plan §3 C1).
  1759	                historyWrites.enqueue("interrupted") { repository ->
  1760	                    val id = resolvedDraftId()
  1761	                    if (id > 0L) {
  1762	                        repository.updateStatus(
  1763	                            id,
  1764	                            TranscriptEntity.STATUS_INTERRUPTED,
  1765	                            interrupted = true,
  1766	                            insertionResult = "not_attempted",
  1767	                        )
  1768	                    }
  1769	                }
  1770	            }
  1771	            seen
  1772	        }
  1773	        // Both delayed callbacks go now, on main, before any blocking cleanup: a bound firing into a
  1774	        // destroyed owner would act on a Service that is gone (#115 review round 1, F4).
  1775	        disarmSilenceBound()
  1776	        cancelOpenPolishRequest()
  1777	        val sessionWasOpen = destroyedState == SessionState.STARTING ||
  1778	            destroyedState == SessionState.RECORDING ||
  1779	            destroyedState == SessionState.PROCESSING ||
  1780	            destroyedState == SessionState.CANCELLING
  1781	        // Cancelled, never joined (#115): a late polish callback cannot restore `ready` after
  1782	        // `interrupted` because the finalization it would write either lost the arbiter above or was
  1783	        // already queued ahead of the `interrupted` write.
  1784	        serviceJob.cancel()
  1785	        val captureRunning = destroyedState == SessionState.RECORDING || destroyedState == SessionState.STARTING
  1786	        if (captureRunning && teardownStarted.compareAndSet(false, true)) {
  1787	            // Down the lane, like every other call into the capture process (#115): the lane's daemon
  1788	            // thread outlives the Service, and the link it captured stays valid after the unbind below.
  1789	            // The file is the capture process's one cache file and the next take overwrites it; nothing
  1790	            // waits for it here.
  1791	            commandCapture("stop at destroy") { it.stopCapture() }
  1792	        }
  1793	        // The service is stopped by intent whether or not that stop is ever delivered (a wedged process
  1794	        // gets neither, and the OS or the user ends it); the bindings go now, on main, with nothing waited
  1795	        // for.
  1796	        if (sessionWasOpen) pipeline.stopAudioService()
  1797	        cancelOpenPolishRequest()
  1798	        pipeline.unbind()
  1799	        // No further command is accepted; the stop above, if queued, still runs on the lane's own thread.
  1800	        captureCommands.shutdown()
  1801	    }
  1802	}

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt | sed -n '604,640p'
nl -ba app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt | sed -n '1398,1414p'
nl -ba app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt | sed -n '832,870p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt | sed -n '218,236p'
nl -ba app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt | sed -n '120,140p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
   604	        cancelDiscoveryRetries()
   605	        return super.onUnbind(intent)
   606	    }
   607	
   608	    override fun onDestroy() {
   609	        // Retract the publication FIRST, so no reader sees a healthy binding on a dying service.
   610	        if (instance === this) publishBinding(null)
   611	        lookScope.cancel()
   612	        getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDeviceCallback)
   613	        // Detach only; the bus belongs to the session owner (see onInterrupt).
   614	        recordingOverlay?.stop()
   615	        recordingOverlay = null
   616	        mainHandler.removeCallbacks(retryRunnable)
   617	        retryScheduled = false
   618	        cancelDiscoveryRetries()
   619	        // Announced here, for the reason spelled out on recordAndAnnounce: what survives this teardown
   620	        // is the durable notification, and it only survives if it is handed to the system while this
   621	        // process is still alive. Its History write goes on the application's queue (#115).
   622	        pendingInsertion?.let { pending ->
   623	            pending.targetPackage = pinnedTarget?.packageName
   624	            logOutcome(pending, InsertionOutcomeLine.Outcome.DESTROYED, pinnedTarget?.packageName)
   625	            recordAndAnnounce(ServiceFallbackReason.SERVICE_DESTROYED, pending)
   626	        }
   627	        pendingInsertion = null
   628	        // Nothing is drained here (#115): the take's History writes are the application queue's, in
   629	        // order, and the bubble-position saves on this scope are a preference whose next save wins, so a
   630	        // cancelled in-flight one costs nothing the next open does not restore.
   631	        historyScope.cancel()
   632	        clearPinnedTarget()
   633	        clearTarget()
   634	        // LAST on the queue, behind the outcome write above: a system kill that lands while that write is
   635	        // still pending must not read as an orderly stop, which is the case the marker exists to catch.
   636	        ModelBootstrapApplication.historyWrites(applicationContext).enqueue("clean-stop marker") { markStopWasClean() }
   637	        super.onDestroy()
   638	    }
   639	
   640	    private fun lifecyclePreferences() =
  1398	        if (pending.transcriptId <= 0L) {
  1399	            emit()
  1400	            return
  1401	        }
  1402	        // On the application's History queue (#115), behind the owner's writes of the same row, so the
  1403	        // outcome cannot land before the finalization it belongs to. The update is first-wins; the row
  1404	        // leaves only when THIS writer won it, so a recovery or a second finalizer that got there first
  1405	        // is the one that reports (round 1, F5).
  1406	        ModelBootstrapApplication.historyWrites(applicationContext).enqueue("insertion outcome") { repository ->
  1407	            val changed = runCatching { repository.finalizeInsertionOutcome(pending.transcriptId, status, result, interrupted) }
  1408	                .onFailure { error -> Log.w(TAG, "Unable to update transcript insertion result: ${error.message}") }
  1409	                .getOrNull()
  1410	            if (changed == 1) emit()
  1411	        }
  1412	    }
  1413	
  1414	    /**
   832	    }
   833	
   834	    override fun onDestroy() {
   835	        // Ordered: no hold may start after this flag, so the take stopRecording ends below cannot open
   836	        // one after the route thread is gone (Codex review 1).
   837	        destroyed = true
   838	        synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }
   839	        stopRecording()
   840	        session?.let { active ->
   841	            active.detector.close(unbindNow = true)
   842	            active.picture.close()
   843	        }
   844	        // NOT joined (#115): the capture thread is the sole owner of AudioRecord and the file, and it
   845	        // releases both in its own releaseSession whenever its read returns; a thread parked inside a
   846	        // read is abandoned to process termination rather than held on the main thread for two seconds.
   847	        // A new take in this process cannot open a second recorder while the old one is held:
   848	        // startRecording refuses while `session` is set, and releaseSession clears it only after the
   849	        // recorder is released.
   850	        if (captureThread?.isAlive != true) {
   851	            captureThread = null
   852	            synchronized(sessionLock) {
   853	                isRecording.set(false)
   854	            }
   855	        }
   856	        // A hold that slipped in between the flag and the stop is ended here, before its expiry dies.
   857	        synchronized(sessionLock) { warmHoldOwner.close(WarmHold.END_DESTROYED) }
   858	        // The route thread quits after what is already posted; the capture thread's cleanup removes its
   859	        // own listener when it runs.
   860	        routeThread.quitSafely()
   861	        // A take that ended above published its ending through this worker, which delivers what is queued
   862	        // and then leaves (#115). Never joined. Both slots cleared here as well: a destroyed service
   863	        // pushes nothing.
   864	        takeEvents.close()
   865	        spectrumListener.set(null)
   866	        takeListener.set(null)
   867	        super.onDestroy()
   868	    }
   869	}
   218	        bindings = PipelineBindings(applicationContext, mainHandler, DebugSessionLog)
   219	        val host = createHost()
   220	        coordinator = DictationSessionCoordinator(
   221	            host = host,
   222	            surface = OverlayRecorderSurface,
   223	            insertion = AccessibilityInsertionGateway,
   224	            log = DebugSessionLog,
   225	            preferences = preferences,
   226	            historyWrites = ModelBootstrapApplication.historyWrites(applicationContext),
   227	            languageDetector = languageDetector,
   228	            loadPolicy = { providerConfiguration.loadPolicy() },
   229	            pipeline = bindings,
   230	            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
   231	            mainDispatcher = Dispatchers.Main.immediate,
   232	        )
   233	        coordinator.onCreated()
   234	    }
   235	
   236	    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
   120	    val speech: SpeechLink?
   121	    val polish: PolishLink?
   122	
   123	    /**
   124	     * Binds the three services in order; the owner's bind-failure handling lives at the call site. Returns
   125	     * which of the three bound so the owner can end the take with the right reason.
   126	     */
   127	    fun bind(listener: Listener): BindResult
   128	
   129	    /** Unbinds whatever is bound and clears the links. Idempotent. */
   130	    fun unbind()
   131	
   132	    /** `stopService` on the capture service, for the paths that stop it rather than let it hold the earbuds. */
   133	    fun stopAudioService()
   134	
   135	    enum class BindResult { BOUND, AUDIO_BIND_FAILED, ASR_BIND_FAILED, POLISH_BIND_FAILED }
   136	}

exec
/bin/zsh -lc "nl -ba app/src/test/java/com/envi/wispr/history/HistoryWriteQueueTest.kt
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt | sed -n '86,110p'
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt | sed -n '236,252p'
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionRig.kt | sed -n '585,615p'
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt | sed -n '60,70p'
nl -ba app/src/test/java/com/envi/wispr/ui/DictationSessionCoordinatorTest.kt | sed -n '545,620p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
     1	package com.envi.wispr.history
     2	
     3	import kotlinx.coroutines.CoroutineScope
     4	import kotlinx.coroutines.Dispatchers
     5	import kotlinx.coroutines.SupervisorJob
     6	import kotlinx.coroutines.delay
     7	import kotlinx.coroutines.flow.Flow
     8	import kotlinx.coroutines.flow.flowOf
     9	import org.junit.Assert.assertEquals
    10	import org.junit.Assert.assertTrue
    11	import org.junit.Test
    12	import java.util.concurrent.CopyOnWriteArrayList
    13	import java.util.concurrent.CountDownLatch
    14	import java.util.concurrent.TimeUnit
    15	
    16	/**
    17	 * Product Outcome (#115): when this fails, a History row reads `processing` after its take ended in an
    18	 * error, or `ready` after a teardown marked it `interrupted`, because two writes to one row landed in the
    19	 * wrong order. The queue applies every write in the order it was enqueued, on one worker, and a write
    20	 * that throws costs itself and nothing after it. The DAO is a fake that records the order writes landed.
    21	 */
    22	class HistoryWriteQueueTest {
    23	    private class RecordingDao : TranscriptDao {
    24	        val landed = CopyOnWriteArrayList<String>()
    25	        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(emptyList())
    26	        override suspend fun insert(transcript: TranscriptEntity): Long { landed += "insert"; return 1L }
    27	        override suspend fun setKept(id: Long, kept: Boolean) = Unit
    28	        override suspend fun delete(transcript: TranscriptEntity) = Unit
    29	        override suspend fun deleteAll() = Unit
    30	        override suspend fun deleteById(id: Long): Int { landed += "discard"; return 1 }
    31	        override suspend fun deleteWordlessRows(): Int = 0
    32	        override suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean, insertionResult: String?): Int { landed += "status:$status"; return 1 }
    33	        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean): Int { landed += "finalize"; return 1 }
    34	        override suspend fun finalizeInsertionOutcome(id: Long, status: String, result: String, stateChangedAtMs: Long, interrupted: Boolean): Int { landed += "outcome:$result"; return 1 }
    35	        override suspend fun recoverStaleDrafts(cutoffMs: Long, nowMs: Long): Int = 0
    36	        override suspend fun recoverStaleReadyRows(cutoffMs: Long, nowMs: Long): Int = 0
    37	        override suspend fun staleReadyRowIds(cutoffMs: Long): List<Long> = emptyList()
    38	    }
    39	
    40	    private val dao = RecordingDao()
    41	    private val warnings = CopyOnWriteArrayList<String>()
    42	    private val queue = HistoryWriteQueue(TranscriptRepository(dao, clock = { 1_000L }), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), warn = { warnings += it })
    43	
    44	    private fun drained(): CountDownLatch = CountDownLatch(1).also { latch -> queue.enqueue("marker") { latch.countDown() } }
    45	
    46	    @Test
    47	    fun writesLandInEnqueueOrderWhateverTheDiskDoes() {
    48	        // The first write is SLOW (a held disk); on two workers the second would land first. One worker,
    49	        // enqueue order. REVERT: drain on Dispatchers.IO with `launch` per write instead of one consumer.
    50	        queue.enqueue("processing") { repository -> delay(150L); repository.updateStatus(1L, "processing") }
    51	        queue.enqueue("asr_error") { repository -> repository.updateStatus(1L, "asr_error", insertionResult = "asr_error") }
    52	        queue.enqueue("interrupted") { repository -> repository.updateStatus(1L, "interrupted", interrupted = true) }
    53	        check(drained().await(10, TimeUnit.SECONDS))
    54	        assertEquals(listOf("status:processing", "status:asr_error", "status:interrupted"), dao.landed.toList())
    55	    }
    56	
    57	    @Test
    58	    fun aWriteThatThrowsCostsItselfAndNothingAfterIt() {
    59	        queue.enqueue("bad insert") { throw IllegalStateException("disk full") }
    60	        queue.enqueue("discard") { repository -> repository.discard(1L) }
    61	        check(drained().await(10, TimeUnit.SECONDS))
    62	        assertEquals(listOf("discard"), dao.landed.toList())
    63	        assertEquals(listOf("History write failed (bad insert): IllegalStateException"), warnings.toList())
    64	    }
    65	
    66	    @Test
    67	    fun enqueueReturnsAtOnceWhileTheDiskIsHeld() {
    68	        // The owner's main thread enqueues; it must never wait for storage. A held first write does not
    69	        // delay the enqueue of the next.
    70	        val release = CountDownLatch(1)
    71	        queue.enqueue("held") { repository -> release.await(10, TimeUnit.SECONDS); repository.discard(1L) }
    72	        val started = System.nanoTime()
    73	        assertTrue(queue.enqueue("after the held one") { repository -> repository.discard(2L) })
    74	        val enqueueNanos = System.nanoTime() - started
    75	        release.countDown()
    76	        check(drained().await(10, TimeUnit.SECONDS))
    77	        assertTrue("enqueue took ${enqueueNanos / 1_000_000} ms", enqueueNanos < 100_000_000L)
    78	        assertEquals(listOf("discard", "discard"), dao.landed.toList())
    79	    }
    80	}
    86	    val speech = FakeSpeech()
    87	    val polish = FakePolish()
    88	    val pipeline = FakePipeline(capture, speech, polish)
    89	    val dao = FakeTranscriptDao()
    90	    val transcripts = TranscriptRepository(dao, clock = { 1_000L })
    91	    /** The application-owned History queue (#115), here on its own scope like the real one; tests drain it through [awaitHistoryIdle]. */
    92	    val historyWrites = HistoryWriteQueue(transcripts, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), warn = { line -> log.warn(line) })
    93	
    94	    /**
    95	     * Every History write queued so far has been applied: a marker write is queued and awaited, and the
    96	     * queue is one worker in enqueue order. The rows' assertions read the DAO after this.
    97	     */
    98	    fun awaitHistoryIdle() {
    99	        val landed = CountDownLatch(1)
   100	        check(historyWrites.enqueue("test marker") { landed.countDown() }) { "the History queue refused a write" }
   101	        check(landed.await(10, TimeUnit.SECONDS)) { "the History queue never drained; log: ${log.lines}" }
   102	    }
   103	    val polishTimeout = FakePolishTimeout()
   104	    val endings = Endings()
   105	    val preferenceStates = MutableStateFlow(AppPreferencesState())
   106	    val terms = MutableStateFlow<List<CustomTerm>>(emptyList())
   107	    /** Anything a session coroutine threw: production has no handler, so a JVM-only throw would otherwise vanish. */
   108	    val uncaught = CopyOnWriteArrayList<Throwable>()
   109	    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
   110	
   236	        val delayedPostLog = CopyOnWriteArrayList<Long>()
   237	        fun postsWithDelay(delayMs: Long): Int = delayedPostLog.count { it == delayMs }
   238	        override fun postToMainDelayed(delayMs: Long, runnable: Runnable) { delayedPostLog += delayMs; delayed += delayMs to runnable }
   239	        override fun cancelMainDelayed(runnable: Runnable) { delayed.removeIf { it.second === runnable } }
   240	
   241	        /**
   242	         * Test time passes: every delayed post with [delayMs] due fires, on main, in the order it was
   243	         * posted. Selected and removed ON main, as a Handler would, so a re-arm racing the test thread
   244	         * cannot leave the fired entry behind or fire the re-posted one twice.
   245	         */
   246	        fun fireDelayed(delayMs: Long) {
   247	            onMain {
   248	                val due = delayed.filter { it.first == delayMs }
   249	                delayed.removeAll(due)
   250	                due.forEach { (_, runnable) -> runnable.run() }
   251	            }
   252	        }
   585	    class FakeTranscriptDao : TranscriptDao {
   586	        val rows = java.util.concurrent.ConcurrentHashMap<Long, TranscriptEntity>()
   587	        private val nextId = AtomicLong(1L)
   588	        @Volatile var failInserts = false
   589	        /** When set, the FIRST `updateStatus` is held this long: on two threads the second lands first (the #115 P4 race). */
   590	        @Volatile var delayFirstStatusMs = 0L
   591	        private val statusWrites = AtomicLong(0L)
   592	        /** When set, every status write is held until the test completes it: a stalled disk (the #115 destroy row). */
   593	        @Volatile var holdStatusWrites: kotlinx.coroutines.CompletableDeferred<Unit>? = null
   594	
   595	        override fun observeAll(): Flow<List<TranscriptEntity>> = flowOf(rows.values.toList())
   596	        override suspend fun insert(transcript: TranscriptEntity): Long {
   597	            if (failInserts) throw IllegalStateException("disk full")
   598	            val id = nextId.getAndIncrement()
   599	            rows[id] = transcript.copy(id = id)
   600	            return id
   601	        }
   602	        override suspend fun setKept(id: Long, kept: Boolean) { rows.computeIfPresent(id) { _, row -> row.copy(kept = kept) } }
   603	        override suspend fun delete(transcript: TranscriptEntity) { rows.remove(transcript.id) }
   604	        override suspend fun deleteAll() = rows.clear()
   605	        override suspend fun deleteById(id: Long): Int = if (rows.remove(id) != null) 1 else 0
   606	        override suspend fun deleteWordlessRows(): Int = 0
   607	        override suspend fun updateStatus(id: Long, status: String, stateChangedAtMs: Long, interrupted: Boolean, insertionResult: String?): Int {
   608	            if (statusWrites.getAndIncrement() == 0L && delayFirstStatusMs > 0L) kotlinx.coroutines.delay(delayFirstStatusMs)
   609	            holdStatusWrites?.await()
   610	            return if (rows.computeIfPresent(id) { _, row -> row.copy(status = status, stateChangedAtMs = stateChangedAtMs, interrupted = interrupted, insertionResult = insertionResult ?: row.insertionResult) } != null) 1 else 0
   611	        }
   612	        override suspend fun finalize(id: Long, originalText: String, finalText: String, speechEngine: String, polishEngine: String, polishLatencyMs: Long, insertionResult: String, durationMs: Long, stateChangedAtMs: Long, polishReason: String, polishStatus: Int, polishContext: String, captureDevice: String, status: String, interrupted: Boolean): Int {
   613	            if (failInserts) throw IllegalStateException("disk full")
   614	            return if (rows.computeIfPresent(id) { _, row -> row.copy(originalText = originalText, finalText = finalText, speechEngine = speechEngine, polishEngine = polishEngine, polishLatencyMs = polishLatencyMs, insertionResult = insertionResult, durationMs = durationMs, stateChangedAtMs = stateChangedAtMs, polishReason = polishReason, polishStatus = polishStatus, polishContext = polishContext, captureDevice = captureDevice, status = status, interrupted = interrupted) } != null) 1 else 0
   615	        }
    60	        return rig.polish
    61	    }
    62	
    63	    private fun theOnlyRow(): TranscriptEntity {
    64	        rig.awaitHistoryIdle()
    65	        assertEquals("one History row", 1, rig.dao.rows.size)
    66	        return rig.dao.rows.values.single()
    67	    }
    68	
    69	    @Test
    70	    fun completedTakeInsertsOnce() {
   545	        assertTrue("the draft is discarded", rig.dao.rows.isEmpty())
   546	    }
   547	
   548	    @Test
   549	    fun asrNotReadyEndsProcessing() {
   550	        rig.pipeline.speech = null
   551	        rig.pipeline.connectSpeech = false
   552	        // The P4 race, staged: the stop path writes `processing` then `asr_error` back to back, and the
   553	        // fake holds the FIRST of them. On 87e07ca the two were launched on the IO dispatcher and the held
   554	        // one landed last, leaving the row `processing`; the History queue (#115) applies them in enqueue
   555	        // order whatever the disk does. REVERT: launch the status writes on the owner's scope again.
   556	        rig.dao.delayFirstStatusMs = 200L
   557	        val coordinator = rig.coordinator()
   558	        startAndGoLive(coordinator)
   559	        rig.command(coordinator, DictationSessionService.ACTION_STOP)
   560	
   561	        assertEquals(TerminalReason.ASR_NOT_READY, rig.endings.awaitOne())
   562	        rig.host.awaitStopped()
   563	        assertTrue(rig.host.events.contains("toast:Speech model is still loading. Try again in a moment."))
   564	        val row = theOnlyRow()
   565	        assertEquals("asr_error", row.status)
   566	        assertEquals("asr_error", row.insertionResult)
   567	    }
   568	
   569	    /**
   570	     * Product Outcome (#115, chunk B): when this fails, force-stopping the app during a take freezes its
   571	     * screen for as long as the disk is stalled, because teardown waited on the History write. Here the
   572	     * disk is HELD; destroy returns on the main thread anyway (the rig's main-thread call has a bound),
   573	     * and the `interrupted` row lands once the disk answers. REVERT: restore a `runBlocking` on the
   574	     * `interrupted` write in `destroy`.
   575	     */
   576	    @Test
   577	    fun destroyReturnsWhileARoomWriteIsStalled() {
   578	        val coordinator = rig.coordinator()
   579	        startAndGoLive(coordinator)
   580	        rig.awaitHistoryIdle()
   581	        val disk = kotlinx.coroutines.CompletableDeferred<Unit>()
   582	        rig.dao.holdStatusWrites = disk
   583	        rig.onMain { coordinator.destroy() }
   584	        assertEquals(TerminalReason.INTERRUPTED_RECORDING, rig.endings.awaitOne())
   585	        assertEquals("the row is still the draft while the disk is held", "draft", rig.dao.rows.values.single().status)
   586	        disk.complete(Unit)
   587	        rig.dao.holdStatusWrites = null
   588	        val row = theOnlyRow()
   589	        assertEquals("interrupted", row.status)
   590	        assertTrue(row.interrupted)
   591	    }
   592	
   593	    /**
   594	     * Product Outcome (#115, chunk B): when this fails, a dictation that finished and reached the editor
   595	     * is later shown as "interrupted" in History because the Service's ordinary stop wrote over it.
   596	     * `interrupted` is queued ONLY when the interrupt won the take; after a completed take it loses.
   597	     * REVERT: enqueue `interrupted` in `destroy` whether or not `arbiter.interrupt` won.
   598	     */
   599	    @Test
   600	    fun destroyAfterACompletedTakeLeavesTheRowAlone() {
   601	        val coordinator = rig.coordinator()
   602	        startAndGoLive(coordinator)
   603	        val polish = stopAndTranscribe(coordinator, "hello world")
   604	        polish.listener!!.onOutcome(polish.outcome("Hello world."))
   605	        assertEquals(TerminalReason.COMPLETED, rig.endings.awaitOne())
   606	        rig.host.awaitStopped()
   607	        rig.onMain { coordinator.destroy() }
   608	        val row = theOnlyRow()
   609	        assertEquals("ready_for_insertion", row.status)
   610	        assertFalse(row.interrupted)
   611	    }
   612	
   613	    @Test
   614	    fun destroyWhileStartingMarksInterrupted() {
   615	        val coordinator = rig.coordinator()
   616	        startAndStayStarting(coordinator)
   617	        rig.onMain { coordinator.destroy() }
   618	
   619	        assertEquals(TerminalReason.INTERRUPTED_STARTING, rig.endings.awaitOne())
   620	        // REVERT (#115 review round 1, F4): drop `disarmSilenceBound()` from `destroy`.

exec
/bin/zsh -lc "nl -ba app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt | sed -n '168,195p'
nl -ba app/src/test/java/com/envi/wispr/ui/PolishPublicationRoutesTest.kt | sed -n '37,50p'
nl -ba docs/audits/2026-09-21-115-receipts-bc.md" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
   168	
   169	    /**
   170	     * Drift Guard (#115): the owner never blocks and never polls. No `runBlocking`, no thread of its own,
   171	     * no sleep in the coordinator; no `runBlocking` or join in the three teardowns (the owner's `destroy`,
   172	     * the paste service's and the audio service's `onDestroy`). A blocking wait that comes back here is
   173	     * the hang this change removed, wherever it is placed. REVERT: restore any one of them.
   174	     */
   175	    @Test
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
   187	        assertTrue("the clean-stop marker is queued last, behind the outcome write", paste.contains("enqueue(\"clean-stop marker\") { markStopWasClean() }"))
   188	        val audio = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText().substringAfter("override fun onDestroy()")
   189	        listOf("runBlocking", ".join(", "Thread.sleep").forEach { assertFalse("the audio service's onDestroy must not contain $it", audio.contains(it)) }
   190	    }
   191	
   192	    @Test
   193	    fun serviceLineCountIsReported() {
   194	        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
   195	        println("DictationSessionService.kt: ${service.lines().size} lines")
    37	    @Test fun theFactsAreDerivedExactlyOnceAndTheNoticePrecedesPersistence() {
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
     3	HEAD before receipts: f294d92
     4	
     5	- B1 drain the queue on two workers (launch per write): red rows ['aWriteThatThrowsCostsItselfAndNothingAfterIt', 'writesLandInEnqueueOrderWhateverTheDiskDoes'] :: RECEIPT OK
     6	- B3 destroy waits on the interrupted write (a runBlocking behind it on the queue): red rows ['destroyReturnsWhileARoomWriteIsStalled'] :: RECEIPT OK
     7	- B4 destroy enqueues interrupted whether or not the interrupt won: red rows ['destroyAfterACompletedTakeLeavesTheRowAlone'] :: RECEIPT OK
     8	- C1 the paste service drains Room on the main thread again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
     9	- C2 the audio service joins the capture thread again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    10	- C3 the owner starts a cleanup thread of its own again: red rows ['theOwnerNeverBlocksAndNeverPolls'] :: RECEIPT OK
    11	
    12	Restored tree, red rows per class: {'com.envi.wispr.history.HistoryWriteQueueTest': [], 'com.envi.wispr.ui.DictationSessionCoordinatorTest': [], 'com.envi.wispr.ui.SessionOwnerShapeTest': []}
    13	git status --short: (clean)

codex
Chunks B and C code review — DONE

## Findings

1. **P1, REPRODUCIBLE: stale-row recovery can starve every live-take write.** `DictationSessionCoordinator.kt:263-271` enqueues recovery ahead of the take, and `HistoryWriteQueue.kt:39-44` has one worker, so a stalled recovery prevents the draft insert, statuses, finalization, and outcomes from ever running.  
   **Drop-in fix:** restore recovery to a separate application-owned recovery scope and repository, leaving the ordered queue exclusively for per-take writes; add a row that holds recovery while a new take’s draft and finalization still land.

2. **P1, REPRODUCIBLE: an ordinary take still waits indefinitely for Room before delivering its words.** `DictationSessionCoordinator.kt:1246-1277` queues finalization, but the owner then suspends on `saved.await()` at `:1298-1300`; a stalled write leaves the take in PROCESSING forever even though main remains responsive.  
   **Drop-in fix:** continue immediately using the already-known `draftId` when positive, otherwise take the clipboard fallback; queue the resulting insertion outcome behind finalization using `resolvedDraftId()`, so a late successful save cannot leave a pending row. Keep save telemetry asynchronous rather than gating insertion.

3. **P1, HYPOTHETICAL: the second-recorder protection is instance-local and does not survive service recreation.** `AudioCaptureService.kt:844-849` claims `session != null` prevents a second recorder, but `session` belongs to the destroyed Service instance; Android can create a new instance in the same cached `:audio` process while the old capture thread still owns `AudioRecord`.  
   **Drop-in fix:** add a process-scoped atomic capture lease acquired before recorder creation and released only after the owning thread closes `AudioRecord`; every early failure releases its lease, and every new Service instance refuses while it remains held. Test the lease as a pure JVM owner plus one instrumented recreation row.

4. **P2, HYPOTHETICAL: the delayed clean marker can falsely report an orderly old stop as unclean.** `PasteAccessibilityService.kt:608-636` clears the global instance and queues `markStopWasClean`; if a replacement connects before that closure runs, the marker’s existing instance guard rejects the old clean stop.  
   **Drop-in fix:** give each service connection a persisted lifecycle generation; enqueue a clean mark for that exact generation, and have recovery judge the previous generation rather than the current global instance.

5. **P3, REPRODUCIBLE documentation and test-name drift.** `HistoryWriteQueue.kt:21-23` says startup recovery stays outside the queue, `DictationSessionCoordinator.kt:83-84` says the service job is joined on destroy, and `:1210-1214` plus `PolishPublicationRoutesTest.kt:37-49` still describe or name notice-before-persistence although finalization is enqueued first.  
   **Drop-in fix:** update the first two KDocs to current ownership, describe the notice as scheduled before the owner continuation, and rename the test to `finalizationIsEnqueuedWithReservationAndNoticeIsScheduledBeforeContinuation`.

## Axes (A1..A7)

- **A1: FOUND.** Per-take enqueue order is sound, including finalize, outcome, and conditional interrupted ordering; startup recovery introduces unrelated head-of-line starvation, Finding 1.
- **A2: FOUND.** No deferred cycle exists and rig markers are positive, but normal publication waits without a storage bound, Finding 2.
- **A3: NOT FOUND.** Destroy invalidates and enqueues under the lock, waits on nothing, guards the surface attachment, stops by intent, unbinds on main, and shuts the lane last.
- **A4: FOUND.** Outcome ordering and disposable bubble-position behavior match the plan; clean-marker identity does not, Finding 4.
- **A5: FOUND.** Removing the join returns immediately, but the same-process second-instance claim is not enforced, Finding 3.
- **A6: NOT FOUND.** The destroy row proves main returned before the disk release; the held-first-write and queue rows use positive completion signals and assert the intended outcomes.
- **A7: FOUND.** Finding 5; the remaining queue and teardown comments match their code.

## Deviations (D1..D4 ACCEPT/REJECT)

- **D1: REJECT.** Recovery on the per-take queue can indefinitely starve a new take and reverses the approved isolation without a needed ordering benefit.
- **D2: ACCEPT.** Reservation and enqueue must be atomic; scheduling the notice before the owner continuation preserves the useful ordering, subject to the wording and test-name correction in Finding 5.
- **D3: REJECT.** The existing global-instance guard cannot distinguish the old clean stop from the replacement service’s lifecycle; use a generation-specific marker.
- **D4: REJECT.** A shape guard proves only that the join is absent, while the claimed second-recorder protection fails across Service instances; enforce a process-scoped lease and add boundary evidence.

## COVERAGE

Read all 13 requested files and ranges, `git show 624f933`, `git show b3f4a38`, and receipts B1, B3, B4, C1, C2, and C3. Accepted the supplied 1,033-test, visibility, and emulator results without rerunning them.

## VERDICT: FINDINGS
tokens used
1,136,759
Chunks B and C code review — DONE

## Findings

1. **P1, REPRODUCIBLE: stale-row recovery can starve every live-take write.** `DictationSessionCoordinator.kt:263-271` enqueues recovery ahead of the take, and `HistoryWriteQueue.kt:39-44` has one worker, so a stalled recovery prevents the draft insert, statuses, finalization, and outcomes from ever running.  
   **Drop-in fix:** restore recovery to a separate application-owned recovery scope and repository, leaving the ordered queue exclusively for per-take writes; add a row that holds recovery while a new take’s draft and finalization still land.

2. **P1, REPRODUCIBLE: an ordinary take still waits indefinitely for Room before delivering its words.** `DictationSessionCoordinator.kt:1246-1277` queues finalization, but the owner then suspends on `saved.await()` at `:1298-1300`; a stalled write leaves the take in PROCESSING forever even though main remains responsive.  
   **Drop-in fix:** continue immediately using the already-known `draftId` when positive, otherwise take the clipboard fallback; queue the resulting insertion outcome behind finalization using `resolvedDraftId()`, so a late successful save cannot leave a pending row. Keep save telemetry asynchronous rather than gating insertion.

3. **P1, HYPOTHETICAL: the second-recorder protection is instance-local and does not survive service recreation.** `AudioCaptureService.kt:844-849` claims `session != null` prevents a second recorder, but `session` belongs to the destroyed Service instance; Android can create a new instance in the same cached `:audio` process while the old capture thread still owns `AudioRecord`.  
   **Drop-in fix:** add a process-scoped atomic capture lease acquired before recorder creation and released only after the owning thread closes `AudioRecord`; every early failure releases its lease, and every new Service instance refuses while it remains held. Test the lease as a pure JVM owner plus one instrumented recreation row.

4. **P2, HYPOTHETICAL: the delayed clean marker can falsely report an orderly old stop as unclean.** `PasteAccessibilityService.kt:608-636` clears the global instance and queues `markStopWasClean`; if a replacement connects before that closure runs, the marker’s existing instance guard rejects the old clean stop.  
   **Drop-in fix:** give each service connection a persisted lifecycle generation; enqueue a clean mark for that exact generation, and have recovery judge the previous generation rather than the current global instance.

5. **P3, REPRODUCIBLE documentation and test-name drift.** `HistoryWriteQueue.kt:21-23` says startup recovery stays outside the queue, `DictationSessionCoordinator.kt:83-84` says the service job is joined on destroy, and `:1210-1214` plus `PolishPublicationRoutesTest.kt:37-49` still describe or name notice-before-persistence although finalization is enqueued first.  
   **Drop-in fix:** update the first two KDocs to current ownership, describe the notice as scheduled before the owner continuation, and rename the test to `finalizationIsEnqueuedWithReservationAndNoticeIsScheduledBeforeContinuation`.

## Axes (A1..A7)

- **A1: FOUND.** Per-take enqueue order is sound, including finalize, outcome, and conditional interrupted ordering; startup recovery introduces unrelated head-of-line starvation, Finding 1.
- **A2: FOUND.** No deferred cycle exists and rig markers are positive, but normal publication waits without a storage bound, Finding 2.
- **A3: NOT FOUND.** Destroy invalidates and enqueues under the lock, waits on nothing, guards the surface attachment, stops by intent, unbinds on main, and shuts the lane last.
- **A4: FOUND.** Outcome ordering and disposable bubble-position behavior match the plan; clean-marker identity does not, Finding 4.
- **A5: FOUND.** Removing the join returns immediately, but the same-process second-instance claim is not enforced, Finding 3.
- **A6: NOT FOUND.** The destroy row proves main returned before the disk release; the held-first-write and queue rows use positive completion signals and assert the intended outcomes.
- **A7: FOUND.** Finding 5; the remaining queue and teardown comments match their code.

## Deviations (D1..D4 ACCEPT/REJECT)

- **D1: REJECT.** Recovery on the per-take queue can indefinitely starve a new take and reverses the approved isolation without a needed ordering benefit.
- **D2: ACCEPT.** Reservation and enqueue must be atomic; scheduling the notice before the owner continuation preserves the useful ordering, subject to the wording and test-name correction in Finding 5.
- **D3: REJECT.** The existing global-instance guard cannot distinguish the old clean stop from the replacement service’s lifecycle; use a generation-specific marker.
- **D4: REJECT.** A shape guard proves only that the join is absent, while the claimed second-recorder protection fails across Service instances; enforce a process-scoped lease and add boundary evidence.

## COVERAGE

Read all 13 requested files and ranges, `git show 624f933`, `git show b3f4a38`, and receipts B1, B3, B4, C1, C2, and C3. Accepted the supplied 1,033-test, visibility, and emulator results without rerunning them.

## VERDICT: FINDINGS
