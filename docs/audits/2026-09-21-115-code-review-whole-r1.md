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
Delta: two things in one round. (1) The confirming round for chunks B and C after your round 2 (`docs/audits/2026-09-21-115-code-review-bc-r2.md`, one finding, adopted). (2) The plan's §13 review of the WHOLE #115 change (chunks A, B and C together) before Phase 3 and the PR. HEAD `d3e54b5` on `main` `87e07ca`.

## The round-2 finding, applied
`closeResources(record, output)` now computes `released = record == null || runCatching { record.release() }...isSuccess` and releases `RecorderLease.PROCESS` only when that is true; otherwise it warns "Recorder release failed; the process's recorder lease stays held" and the lease stays held until the process dies. The lease KDoc says "released successfully". `RecorderLeaseTest.theServiceAcquiresBeforeTheRecorderAndReleasesAfterIt` pins the condition and the single release site; receipt C6 (release unconditionally) reddens it. `scripts/measure-tests.sh` at `e49edef`: tests=1036 failures=0; visibility clean.

## What to read for the whole-change review
`git diff 87e07ca d3e54b5 -- app/src/main` in full (about 1,600 changed lines across 17 production files; the list: `IAudioCaptureService.aidl`, `ITakeListener.aidl`, `AudioCaptureService.kt`, `DetectorFeed.kt`, `RecorderLease.kt`, `TakeEventPublisher.kt`, `HistoryWriteQueue.kt`, `ModelBootstrapApplication.kt`, `PasteAccessibilityService.kt`, `TelemetryChannels.kt`, `DictationSessionCoordinator.kt`, `DictationSessionService.kt`, `PipelineBindings.kt`, `PipelineLinks.kt`, `SessionHost.kt`, `TakeNotices.kt`, `TerminalReason.kt`). Tests: read only where a production question sends you. The plan: `docs/feature-requests/issue-115-2026-09-21-push-not-poll.md` §3 (A, B, C), §7 (the failure-mode table), §11.2, §13.

## Verified facts (do NOT re-derive)
- Every prior round's findings are in the tree as adopted or rejected with evidence: chunk A rounds 1 to 4 (ALL-CLEAR at `649095a`), chunks B and C rounds 1 and 2 (F2 of round 1 rejected: the insertion route is decided from the save's result, the wait is on IO and pre-existing).
- Receipts: A R1 to R15 and B/C B1, B3, B4, B5, C1 to C6 RED on their rows, tree restored (`docs/audits/2026-09-21-115-receipts-a.md`, `-bc.md`).
- Emulator, build `75cfa0e` (chunk A only; B and C are not yet on the emulator, the one build for the run comes after this round): the baseline reached NO ending 20 s after a stop against a frozen capture process; the new build ends the take 3 s after the freeze (`AUDIO_PROCESS_UNRESPONSIVE`), a kill ends it in 0.5 s, the next take lands in Gmail by COMMIT, a ten-minute take with auto-stop off ended by the pushed cap with the nine-minute warning and no false bound with the host idle (an earlier run tripped the bound during an 11 s HOST stall of the emulator). The ten-minute clip's transcription died to the emulator's low-memory killer: filed as #204, outside this change.

## Axes for the whole (found / not found, with file:line)
W1. The plan's §7 failure-mode table against the code as it stands: every producer of a take's ending and every exit from STARTING/RECORDING/PROCESSING/CANCELLING, including destroy, ends the take exactly once with a sentence or a clean finish; name any cell where two mechanisms can both end it (the bound and an ending; the bound and a ServiceConnection death; a cancel and an ending) and how the first-wins is enforced there.
W2. Threads: list every place the owner now touches the capture process and confirm each is on the lane; every place main could wait (none intended); every place a queue worker could wait on something only the owner completes (none intended).
W3. Take identity end to end: from `takeId` in `beginSession` through the start command, the publisher, the listener's `ours`, the History row's journal association, the ASR request and the insertion handoff; name any surface still keyed by something else.
W4. AIDL: `IAudioCaptureService` appended only (the legacy getters kept with their transaction numbers); `ITakeListener` new; the instrumentation client (`app/src/androidTest`) binds by unchanged numbers; a `grep -rn "getElapsedMs\|getLiveState\|waitForFileReady\|isCapturing" app/src` to confirm no production caller remains (the Stub overrides in `AudioCaptureService` are the only mentions expected).
W5. Idle cost: no timer, poll or resident thread at idle in the default process or `:audio` beyond the lane's daemon thread per owner instance (shut down at destroy) and the publisher's worker parked on its permit.
W6. Anything the three chunks together leave inconsistent: a comment from chunk A that chunk B or C made stale; a test row that now proves less than its name says.
W7. Anything you would refuse to ship to a phone.

## Output contract
Every finding ships a drop-in fix with `file:line`; classify REPRODUCIBLE or HYPOTHETICAL. CLEAN per axis is acceptable and will be checked against the tree. Sections, at most 160 lines: `## Findings`, `## Axes (W1..W7)`, `## COVERAGE` (say which files of the 17 you read in full), `## VERDICT: ALL-CLEAR | FINDINGS`. Stop after the VERDICT line.

warning: Under-development features enabled: chronicle. Under-development features are incomplete and may behave unpredictably. To suppress this warning, set `suppress_unstable_features_warning = true` in /Users/m4pro_sv/.codex/config.toml.
2026-09-22T00:24:38.610355Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
2026-09-22T00:24:38.934453Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.sentry.dev/.well-known/oauth-protected-resource/mcp?utm_source=plugin\"" })
codex
Whole #115 review — WORKING

I’ll first confirm the recorder-lease fix, then read the full 17-file production diff and trace the plan’s lifecycle, threading, identity, AIDL, and idle-cost contracts end to end.
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
/bin/zsh -lc 'rg -n "''^### A'"\\.|"'^### B'"\\.|"'^### C'"\\.|"'^## 7'"\\.|"'^### 11'"\\.2|"'^## 13'"\\.\" docs/feature-requests/issue-115-2026-09-21-push-not-poll.md
nl -ba docs/feature-requests/issue-115-2026-09-21-push-not-poll.md | sed -n '230,390p'
nl -ba docs/feature-requests/issue-115-2026-09-21-push-not-poll.md | sed -n '410,500p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
230:### A. Push, not poll (chunk A)
314:### B. The History write queue (chunk B)
338:### C. Teardown without a blocking wait (chunk C)
414:## 7. Failure-mode × caller table
471:### 11.2 Other obligations
496:## 13. Ship criteria specific to THIS change
   230	### A. Push, not poll (chunk A)
   231	A1. **`ITakeListener` (proposed)**, `app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl`, all `oneway`:
   232	   `onLive` (proposed) `(boolean forced, int routeKind, int routeReason, long liveAfterMs)`, `onTick` (proposed) `(long elapsedMs)`,
   233	   `onSilenceStatus` (proposed) `(int status)`, `onEnded(int terminalReason, int startFailure, String audioFilePath, int silenceStatus, float takePeakAmplitude, String effectiveInputDevice)`.
   234	   `IAudioCaptureService` appends `registerTakeListener(ITakeListener)` and `unregisterTakeListener(ITakeListener)`
   235	   after `unregisterSpectrumListener`; the polled getters stay, marked LEGACY like `getSpectrumBands`.
   236	A2. **`TakeEventPublisher` (proposed)** in `:audio`, service-scoped like `WarmHoldOwner`, holding one
   237	   `AtomicReference<ITakeListener?>`; every call is `runCatching` (a dead owner must not throw into the capture
   238	   loop) and never waits. Like `PicturePublisher`, it owns ONE worker thread: the capture thread only does a
   239	   primitive clock comparison and a non-blocking offer per read, and the worker makes the at-most-once-per-second
   240	   binder call under `runCatching` (round G2, G1.2: a `oneway` transaction still allocates a Parcel and can
   241	   back-pressure its caller, so it never runs on the capture thread). Sites: the first ADMITTED block, after
   242	   `output.write`, `bytesWritten` and `liveVisible = true` complete (`:558-560`; round G3.1: live is published
   243	   only once the on-disk readiness point is reached) → `onLive`; each positive `AudioRecord.read`, BEFORE the
   244	   gate branch (`:552`) → an offer the worker throttles by MONOTONIC WALL-CLOCK second (round G1.1: elapsed is
   245	   zero before live, `:264-270`, so throttling by elapsed second would send one heartbeat and trip the bound)
   246	   into `onTick(elapsedMs)`, carrying zero before live and the live-based elapsed after; the owner uses the
   247	   value for the timer only once RECORDING and treats every tick as liveness. The detector's
   248	   three status-writing paths in `DetectorFeed.kt` (round G1.2) → `onSilenceStatus`: after
   249	   `markRequestedButRefused` (`:88-90`), after the PREPARING-to-READY compare-and-set (`:257-260`), and after
   250	   every successful abandon transition (`:312-325`). **Endings, one publisher per exit** (round G3 applied the
   251	   pre-committed consequence: the prose is replaced by this table, every row from `AudioCaptureService.kt` at
   252	   87e07ca; a fifth-exit finding in round 3 was the trigger):
   253	
   254	   | Exit | Session state at the exit | Cleanup owner | Ending publisher |
   255	   |---|---|---|---|
   256	   | busy: `session != null` at `:323` | the previous take's session still open | none (nothing was created) | direct start-refused `onEnded` (code `START_FAILURE_OTHER`; the previous take's own ending is published by ITS `releaseSession`) |
   257	   | route refusal `:334-339` | none | that exit (`routeHold.release()`, `stopSelf()`) | direct start-refused `onEnded` (`START_FAILURE_NO_INPUT_DEVICE`) |
   258	   | buffer-size failure `:356-361` | none | that exit | direct start-refused `onEnded` (`START_FAILURE_OTHER`) |
   259	   | thread-start failure `:466-475` | `session = newSession` at `:426`; `DetectorFeed.start` already ran (`:453-461`) | that exit, not `releaseSession`: clear `session`, close resources, close the detector with immediate unbind, close the picture (round G4.1: the exit today leaves the detector running) | direct start-refused `onEnded` (`START_FAILURE_OTHER`) |
   260	   | immediate post-start result false at `:492` (`thread.isAlive && session === newSession && isRecording.get()`) | a session exists; its ending (the earbud refusal or a loop ending) is already claimed | `releaseSession`, from the capture loop | `releaseSession`'s final `onEnded`; this exit publishes nothing |
   261	   | setup exception BEFORE the capture thread started (`:493-508` reached from before `:464`) | none, or set at `:426` with no thread | that catch: clear `session`, close locally (`record`, `output`, the detector and picture if started) | direct start-refused `onEnded` (`START_FAILURE_OTHER`) |
   262	   | setup exception AFTER the capture thread started (`:493-508` reached from `:476-491`: `picture.start`, `watchSink`, `armDeadline`) | set, with a LIVE capture thread that owns the recorder and the file | NOT that catch (clearing and closing from the binder thread would race the live thread, round G4.2): the catch claims `TERMINAL_REASON_ERROR` on the session and signals the recorder to stop; `releaseSession` alone cleans up | `releaseSession`'s final `onEnded`; the catch publishes nothing |
   263	   | every ending after the loop started (stop, silence, cap, byte ceiling, capture error, teardown) | a session exists | `releaseSession` (`:668-695`) | `releaseSession`'s FINAL operation, after `closeResources`, `session = null`, the detector and picture closes, the diagnostic line and the service-lifetime work (round G3.2) |
   264	
   265	   Exactly one publisher per row; the direct publisher carries the failure code and no path, so the owner never
   266	   waits for an ending no session can produce. For the four legacy start transactions (`startCapture`,
   267	   `startCaptureWithSilenceStop`, `startCaptureWithInputDevice`, `startCaptureWithInputDeviceHeld`,
   268	   `IAudioCaptureService.aidl:6,25,49,77`) no listener is registered and the direct publication is best-effort:
   269	   their `false` return remains their complete contract (round G1.3).
   270	A3. **The owner** registers its listener in `startCaptureForTake`'s caller (`tryStartRecording`, next to
   271	   `listenForPicture`) BEFORE the start call, so no event can precede registration; each event is
   272	   `host.postToMain { … }` and then handled by the existing code paths: `onLive` → `publishLive(forced)` with
   273	   the route facts stamped; `onTick` → `updateElapsed`, `publishDurationWarningIfNeeded` (removed),
   274	   `publishMicrophoneNoticesIfNeeded` (removed) once (the tip gate); `onSilenceStatus` → `publishSilenceNoticeIfNeeded`
   275	   with the pushed value; `onEnded` → the existing `when (CaptureEnding.fromAidl(reason))` with the path
   276	   handed to `stopAndTranscribe`, `takeFacts.captureTerminal`, `takeFacts.silenceStopStatus`,
   277	   `takeFacts.peakAmplitude` (from the payload's peak, replacing `takePeakAmplitude()`) and `captureDeviceLabel`
   278	   for the row's `captureDevice` (from the payload's label, replacing `effectiveInputDevice()`), with no
   279	   replacement getter call (coverage B1). `startPolling`, `waitForLive`, `DictationPollingThread`, `LIVE_POLL_MS` and the `liveState()`,
   280	   `isCapturing()`, `elapsedMs()`, `terminalReason()`, `silenceStopStatus()`, `inputRouteKind()`,
   281	   `inputRouteReason()`, `liveAfterMs()`, `waitForFileReady()`, `takePeakAmplitude()`, `effectiveInputDevice()`,
   282	   `audioFilePath()` and `lastStartFailure()` members of `CaptureLink` are deleted (`GR-MIGRATION-COMPLETE`);
   283	   `onEnded` carries the start failure code, so the ending-before-live case reads nothing. After the change the
   284	   only synchronous calls into `:audio` are the three commands and the four listener members.
   285	A4. **Live wait.** STARTING ends by `onLive` (→ RECORDING), by `onEnded` before live (→ the existing
   286	   `CAPTURE_ENDED_BEFORE_LIVE` / `CAPTURE_START_EARBUDS_REFUSED` from the payload's start failure code), or by the
   287	   existing `LIVE_WAIT_BOUND_MS` deadline, now a main-thread `postDelayed` instead of a polling thread.
   288	A5. **The wedge bound.** `TAKE_SILENT_BOUND_MS` (proposed, 3 000 ms, revisited after §11.1 b): a main-thread
   289	   `Runnable` re-posted on every event from the pusher, armed BEFORE the listener is registered and the start
   290	   command is sent, and effective until the binding is released (coverage C1: it covers a start that never
   291	   returns, a registration that hangs, a command outstanding after `onEnded` such as `finishTake`, and a wedge
   292	   after the take moved to PROCESSING or CANCELLING while a capture command was still out); when it fires,
   293	   `handleServiceFailure(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)` if the take is STARTING or RECORDING, else
   294	   the outstanding command is abandoned and the binding released (the take's words are already in hand).
   295	   The timer primitive (round G2.1): `SessionHost` gains `postToMainDelayed(delayMs, runnable)` and
   296	   `cancelMainDelayed(runnable)` (proposed), implemented on the Service's `Handler.postDelayed` /
   297	   `removeCallbacks`; `DictationSessionRig` gains a deterministic delayed-task scheduler driven by test time
   298	   (a queue of `(dueAtMs, runnable)` the test advances), so the bound rows never sleep
   299	   (proposed member, `TerminalResult.AUDIO_INTERRUPTED`, sentence in `TakeNotices`: "The microphone stopped
   300	   answering. Try again.", `TelemetryChannels` both sets, `TakeNoticesTest` and `TakeFactsTest` rows), then
   301	   `pipeline.stopAudioService()` and `unbind` (a binder to a wedged process is released, never called). The
   302	   bound is disarmed when the last outstanding command returns after `onEnded`, and by every terminal path's
   303	   release of the binding. No timer exists outside a take (RULE: no-idle-cost). Non-triggers, each a row
   304	   (coverage C2): a long silence with auto-stop OFF (ticks continue; the pusher is the capture loop, not the
   305	   detector), the picture push stopping (a different listener), a STARTING gate open longer than the bound
   306	   (ticks start at the first buffer, BEFORE the gate opens: `onTick` is sent from the loop from the first read,
   307	   live or not), and a paused tick source (the only thing that pauses the capture loop is a blocked
   308	   `AudioRecord.read`, which IS the wedge).
   309	A6. **Commands off main.** `stopCapture()` and `finishTake()` are called from `stopAndTranscribe` and
   310	   `finishTakeOrStop`, which run on the owner's scope today except the cancel path; the plan routes the cancel's
   311	   `stopCapture` through the scope too, so no synchronous binder call to `:audio` runs on the Service's main
   312	   thread (`never-block-a-binder-or-ui-thread`).
   313	
   314	### B. The History write queue (chunk B)
   315	B1. **`HistoryWriteQueue` (proposed)**, `history/HistoryWriteQueue.kt`, owned by `ModelBootstrapApplication`
   316	   (one per process; the default process holds the owner and the paste service): a `Channel<HistoryWrite>`
   317	   (unlimited) drained by ONE coroutine on `Dispatchers.IO` in an application `SupervisorJob` scope; each write
   318	   is a `suspend (TranscriptRepository) -> Unit` with a content-free label for the log; a write that throws is
   319	   logged by label and the drain continues. `enqueue` never suspends and never blocks.
   320	B2. The draft INSERT (`:577-595`, today `scope.async` on the owner's scope) is the FIRST queued write of a take
   321	   (coverage D1): it runs on the queue's worker under application ownership, publishes its id to a
   322	   `CompletableDeferred<Long>` the later queued writes await INSIDE the worker, and is never cancelled by
   323	   `destroy` (the owner's scope cancel no longer touches it). The queued insert completes the id deferred and
   324	   the journal association from the worker; the still-live OWNER observes that completion (an `invokeOnCompletion` (external)
   325	   on the deferred, posted to main) and attaches the transcript to its surface, so a dead surface is never
   326	   called from the application queue after destroy (round G3.1). `updateDraftStatus`, `discardDraft`, `setDraftStatus`'s callers, the
   327	   destroy `interrupted` write and the paste service's outcome write enqueue instead of
   328	   `scope.launch`/`historyScope.launch`; `pendingHistoryUpdates` and the destroy join are deleted. Ordering is by
   329	   ENQUEUE time, on one worker, so a row's writes land in issue order.
   330	   **Writer inventory** (coverage D2, `TranscriptRepository.kt:12-69`): per-take writers are `insert`,
   331	   `updateStatus`, `discard`, `finalize`, `finalizeInsertionOutcome` (the owner and the paste service) and
   332	   they ALL go through the queue; `setKept`, `delete`, `deleteAll`, `pruneWordlessRows` and
   333	   `recoverStaleOpenRows` are the History screen's and the start-up recovery's writers, on their own scopes,
   334	   and stay outside it. The single-writer claim is narrowed to per-take writes.
   335	B3. `asrNotReadyEndsProcessing` asserts `status == asr_error` again; the rig's fake DAO gains a staged-delay mode
   336	   (two worker threads, the first write delayed) that reproduces the race on 87e07ca (P4).
   337	
   338	### C. Teardown without a blocking wait (chunk C)
   339	C1. `DictationSessionCoordinator.destroy` (round G4.1, the order): FIRST invalidate state and claim the arbiter
   340	   under `publishLock` (as today, `:1570-1585`); THEN enqueue the `interrupted` write, after every previously
   341	   RESERVED finalization is guaranteed enqueued; then `serviceJob.cancel()` without joining; cancel polish;
   342	   release capture and the bindings. The `runBlocking` blocks are deleted. Finalization admission (`:1091-1112`,
   343	   today outside any coordinator lock): the immutable finalization payload is prepared first, then under
   344	   `publishLock` the publication is RESERVED and its non-suspending History write ENQUEUED as one operation
   345	   (round G2, G2.1); `destroy` takes the same `publishLock` before enqueueing `interrupted`, so it cannot land
   346	   between a reservation and its enqueue; `polishSubmissionLock` stays limited to polish request submission and
   347	   cancellation. `destroy` enqueues `interrupted` ONLY when `arbiter.interrupt` wins (round G3, G2.1): the
   348	   terminal `commitNow` sites (`:429`, `:449`, `:876`, `:888`, `:910`, `:1413`, `:1441`) already own their later
   349	   queued status or discard write, and the two text publications (`:974`, `publishFallback` `:1018-1028`) both
   350	   converge on the reservation at `:1091`, with copy-only and the unscheduled handoff downstream of it
   351	   (`:1154-1228`). The deleted join protected nothing else (round G4.3, G3.4): `sessionPreferences` is written
   352	   only under the STARTING check (`:385-388`); `rawTranscript` (`:905-916`) may still change after `destroy`,
   353	   but the cancelled scope and the claimed arbiter prevent every later publication or write from it. The late-polish-callback race the join
   354	   protected against (coverage E1): every finalization write ADMITTED before `destroy` was already enqueued
   355	   before the `interrupted` write, so the queue applies it first and the `interrupted` write lands last; a
   356	   finalization not yet admitted loses the `TakeArbiter` claim to `destroy` and never enqueues. Nothing can
   357	   restore `ready` after `interrupted`.
   358	C2. `stopAndTranscribe`'s `runBlocking { draftCreation.await() }` (`:838`) becomes an enqueued write that awaits
   359	   the id on the worker.
   360	C3. `PasteAccessibilityService.onDestroy`: the outcome write is enqueued (it is a per-take writer); the
   361	   `joinAll` is deleted and `markStopWasClean` is enqueued as the LAST job on the queue. The bubble-position
   362	   saves (`:303`) stay on the service's scope and teardown may discard an in-flight one (coverage E3): the
   363	   position is a preference whose next save wins, and the last landed value is what the bubble reopens at.
   364	   Teardown never calls a synchronous unregister on the audio binder (coverage E4): the listener registration is
   365	   released by `unbindService` alone, on every path, including the wedge.
   366	C4. `AudioCaptureService.onDestroy`: `stopRecording()` signals; the `thread.join(2_000L)` and its branch are
   367	   deleted; the comment's abandonment contract stands from the moment `onDestroy` returns. The interleaving
   368	   where the old capture thread still owns `AudioRecord` when a new take starts in the SAME process (coverage
   369	   E2): `startRecording` refuses while `session != null` (`:328`), and `releaseSession` clears `session` only
   370	   after `closeResources` released the recorder, so a new take cannot open a second recorder while the old one
   371	   is held; a thread parked inside `AudioRecord.read` keeps `session` set and the next start fails with
   372	   `START_FAILURE_OTHER`, which the owner reports as `CAPTURE_ENDED_BEFORE_LIVE`; measured at build by
   373	   `AudioLimbCloseTest`'s stuck-thread row starting a second take.
   374	
   375	**Pipe or bucket.** The leak is created wherever the owner ASKS or WAITS; a bound on the polling thread alone
   376	(the issue's shape 2) is a bucket. Chunk A removes the asks (pipe); the wedge bound is the one bucket that must
   377	stay, because a synchronous command has no timeout at the binder, and it is named as such. Chunks B and C remove
   378	the waits (pipe). Upstream of all three: nothing; the process boundary is the design.
   379	
   380	## 3b. Ownership justification
   381	The publisher lives in `:audio` because the facts are born there (`releaseSession` is the one handoff every
   382	ending reaches); the listener adapter lives in `PipelineBindings` next to the spectrum adapter; the bound
   383	lives in the owner because the owner owns the take's state. The queue lives in the Application because two
   384	Services in one process write History and a Service's scope dies with it.
   385	
   386	## 4. Contract deltas
   387	- AIDL: `ITakeListener` new; `IAudioCaptureService` gains two appended methods. Nothing renamed or reordered.
   388	- `CaptureLink` retains three commands (`startCaptureForTake`, `stopCapture`, `finishTake`) and four listener members (the spectrum pair and `listenForTake` (proposed) / `stopListeningForTake` (proposed)); thirteen read or wait members are deleted (`lastStartFailure`, `waitForFileReady`, `liveState`, `isCapturing`, `audioFilePath`, `elapsedMs`, `silenceStopStatus`, `inputRouteKind`, `inputRouteReason`, `liveAfterMs`, `terminalReason`, `effectiveInputDevice`, `takePeakAmplitude`).
   389	- `TerminalReason.AUDIO_PROCESS_UNRESPONSIVE` added with a sentence; `dictation.terminal` `reason` gains a value.
   390	- `TranscriptRepository` unchanged; `HistoryWriteQueue` new; `ModelBootstrapApplication` exposes it.
   410	  the publisher field and `TakeEventPublisher`'s close; the log-template multiset gains the publish lines.
   411	- The instrumentation APK: binds by transaction number; the appended methods do not move any.
   412	- `DictationSessionRig.FakeCapture`: drops the polled members, gains a way to push events and a way to go silent.
   413	
   414	## 7. Failure-mode × caller table
   415	
   416	| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
   417	|---|---|---|---|---|---|
   418	| `:audio` dies mid-take | the OS | `onServiceDisconnected` | the existing "Microphone service stopped unexpectedly" | `interrupted`-class row as today | next take |
   419	| `:audio` freezes (no events for the bound) | the OS or a native hang | the wedge bound | "The microphone stopped answering. Try again." within ~3 s | row discarded as a mid-take failure (as `CAPTURE_FAILED_MID_TAKE`) | next take only after the frozen process is explicitly continued or killed (`stopService` cannot make a stopped process run teardown); on a device the OS kills a frozen app process on the next memory pressure or the user force-stops it |
   420	| `onEnded` arrives after the bound fired | a slow pusher | main thread | nothing more; the take already ended | unchanged | none |
   421	| a History write throws | Room | the queue worker | nothing; the next write proceeds | that write lost, logged by label | `recoverStaleDrafts` on next start |
   422	| the process dies with writes queued | the OS | none | nothing | those writes lost, as before | `recoverStaleDrafts` |
   423	| the capture thread outlives `onDestroy` | a stuck `AudioRecord` read | `AudioCaptureService.onDestroy` | nothing; the process is being torn down | the file abandoned to process death, as today after 2 s | next take in a new process |
   424	
   425	## 8. Caller-visible signals
   426	Added: one sentence for the wedge. Removed: none. The `no speech`/cap/silence sentences unchanged.
   427	
   428	## 9. Fallback source-of-truth audit
   429	The ending is the pushed reason; the legacy getters are not consulted. The bound is the fallback for a missing
   430	push and produces its own reason. The queue is the sole ORDERED writer for per-take History writes; the DAO's
   431	`recoverStaleDrafts` remains the on-start backstop.
   432	
   433	## 10. File-by-file changes
   434	- `app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl` (new), `IAudioCaptureService.aidl` (two appended).
   435	- `app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt` (new), `AudioCaptureService.kt` (register,
   436	  publish at the four sites, `onDestroy` without the join), `DetectorFeed.kt` (status change hook).
   437	- `app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt` (`CaptureLink` delta), `PipelineBindings.kt` (the
   438	  listener stub), `DictationSessionCoordinator.kt` (events, bound, deletions, destroy), `TerminalReason.kt`,
   439	  `TakeNotices.kt`, `telemetry/TelemetryChannels.kt`.
   440	- `app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt` (new), `models/ModelBootstrapApplication.kt`,
   441	  `paste/PasteAccessibilityService.kt` (enqueue, `onDestroy`).
   442	- Tests: `DictationSessionRig.kt`, `DictationSessionCoordinatorTest.kt`, `SessionOwnerShapeTest.kt`,
   443	  `audio/AudioServiceShapeTest.kt`, `audio/AudioLimbCloseTest.kt`, `history/HistoryWriteQueueTest.kt` (new),
   444	  `TakeNoticesTest.kt`, `telemetry/TakeFactsTest.kt`, `TelemetryContractsTest.kt`, `paste/*ShapeTest` as needed.
   445	- `docs/audits/2026-09-21-115-revert-receipts.txt`, `docs/audits/2026-09-21-115-emulator-pass/`.
   446	
   447	## 11. Testing
   448	1. Classes: the silent-pusher row, the killed-pusher row, the ordering row and the stalled-Room destroy row are
   449	   Product Outcome (Aaron's words or a sentence within seconds); the shape rows are Drift Guards; the AIDL
   450	   append row is a Drift Guard; the publisher's close row is a Harness/Drift row on `:audio`.
   451	2. Reverts: §11.2.
   452	3. Not tested: a wedge on the physical phone (never staged there); a native hang inside `AudioRecord.read`
   453	   (the emulator's freeze stands in for every wedge shape, since the owner sees only silence).
   454	
   455	### 11.1 Hardware UAT spec
   456	- Emulator, wispr-eyes, debug build: (a) BEFORE chunk A on 87e07ca: start a take, `kill -STOP` the `:audio` pid,
   457	  press stop: measure that the recorder stays up with no sentence (P1 red-by-today), then `kill -CONT` and
   458	  `restore()`. (b) After chunk A: two ordinary takes ending by stop and by silence, transcribed and inserted by
   459	  COMMIT, every `onTick` gap recorded from the owner's log (P3); the cap is scene (g). (c) The frozen-pusher scene (`kill -STOP` by pid): the wedge sentence within
   460	  the bound; a frozen process keeps its binder alive from the owner's view and `stopService` cannot make it
   461	  run teardown (coverage H1), so the scene then `kill -CONT`s (or `kill -9`s) the pid and only THEN runs the
   462	  next take, which must be normal; the record states which. (d) The killed-pusher scene (`kill -9` by pid):
   463	  the existing sentence, the next take normal. (g) The cap: one take with auto-stop OFF left running to the
   464	  ten-minute cap on the emulator (coverage H2), ending by the pushed `MaxDuration` with its sentence and the
   465	  words transcribed. A blocked Room write on the emulator is NOT stageable without a debug seam on the
   466	  database, which would be a test seam on production storage; it stays a rig row.
   467	  (e) After chunk C: force-stop during a take and the next take starts; the History row reads `interrupted`.
   468	  (f) `restore()`. Founder's phone: NOT RUN (his instruction); build delivered through Play.
   469	- The stalled Room write and the stalled capture thread: JVM rig only, stated in `hardware-uat.json`.
   470	
   471	### 11.2 Other obligations
   472	
   473	| Test | Class | Proves | Revert that turns it red |
   474	|---|---|---|---|
   475	| `DictationSessionCoordinatorTest.aSilentAudioProcessEndsTheTakeWithinTheBound` (proposed) | Product Outcome | the fake pushes `onLive` then nothing; the take ends `AUDIO_PROCESS_UNRESPONSIVE` with the sentence within the rig's bound; capture is stopped and the service unbound | disarm the bound |
   476	| `DictationSessionCoordinatorTest.aPushedEndingTranscribesWithoutAsking` (proposed) | Product Outcome | `onLive`, ticks, `onEnded(manual, path)`: the take transcribes and inserts | reintroduce a polled getter on `CaptureLink` and call it: the shape row below goes red on the name, and this row goes red because the fake's getter throws (coverage G2) |
   477	| `DictationSessionCoordinatorTest.audioDiedWhileRecordingEndsAsFailure` (exists) | Product Outcome | process death still ends the take with its sentence on the push design | remove the disconnect handler |
   478	| `DictationSessionCoordinatorTest.theNextTakeStartsAfterAWedge` (proposed) | Product Outcome | after the wedge ending, a new take on a fresh fake goes live and completes | leave the bound armed or the binding held |
   479	| a paste-service shape row (`paste/AutoPasteWiringTest` grown, or a new `PasteServiceTeardownTest` (proposed)) | Drift Guard | `onDestroy` has no `runBlocking`/`joinAll`; the outcome write enqueues | restore the drain |
   480	| `AudioServiceShapeTest` (grown, coverage G1) | Drift Guard | the only synchronous AIDL calls the owner makes are `startCaptureForTake`, `stopCapture`, `finishTake` and the four listener members (a token inventory over `PipelineBindings`) | add a getter call |
   481	| the instrumentation client's binding | Drift Guard | `app/src/androidTest` binds `IAudioCaptureService` by the unchanged transaction numbers: the AIDL append row plus one instrumented row already binding (UNCHECKED which; named at build) | reorder |
   482	| `DictationSessionCoordinatorTest.aLateEndingAfterTheBoundIsDropped` (proposed) | Drift Guard | `onEnded` after the bound fired changes nothing | remove the RECORDING check |
   483	| `DictationSessionCoordinatorTest.asrNotReadyEndsProcessing` (grown) | Product Outcome | status `asr_error` AND insertion result, with the racing fake DAO | launch the writes instead of enqueueing |
   484	| `HistoryWriteQueueTest.writesLandInIssueOrderAcrossWorkers` (proposed) | Product Outcome | two enqueued writes with the first delayed land first-then-second | drain on two workers |
   485	| `DictationSessionCoordinatorTest.destroyReturnsWhileARoomWriteIsStalled` (proposed) | Product Outcome | a fake DAO that blocks: `destroy()` returns on the main thread within the rig's bound; the `interrupted` write lands when the DAO unblocks | restore the `runBlocking` |
   486	| `AudioLimbCloseTest.onDestroyReturnsWhileTheCaptureThreadIsStuck` (proposed) | Product Outcome | a capture thread parked in a read: `onDestroy` returns without waiting | restore the join |
   487	| `SessionOwnerShapeTest.theOwnerNeverBlocksAndNeverPolls` (proposed) | Drift Guard | the coordinator source has no `runBlocking`, no `Thread(`, no `Thread.sleep`; the paste service and the audio service have no `runBlocking`/`join` in `onDestroy` | restore any |
   488	| `AudioServiceShapeTest` (grown) | Drift Guard | the publisher field and its log lines | drop the field |
   489	| an AIDL append row in `AudioServiceShapeTest` or a new `AidlAppendTest` (proposed) | Drift Guard | the two new methods are the LAST in `IAudioCaptureService.aidl`; nothing above `unregisterSpectrumListener` changed (a hash of the text above it) | reorder |
   490	
   491	## 12. Blast radius & rollback
   492	Every take's live wait, tick and ending; every per-take History write; three teardowns. Chunked
   493	so each lands usable; rollback is the PR revert of the Kotlin. The appended AIDL methods are NOT reverted once
   494	any client shipped against them (coverage F1): they stay as legacy transactions, like `getSpectrumBands`.
   495	
   496	## 13. Ship criteria specific to THIS change
   497	- P1 measured red-by-today on the rig and the emulator before chunk A; P3's tick gaps under 1 s on the emulator
   498	  (else the bound and the tick rate are re-examined before shipping).
   499	- Every row in §11.2 green; receipts red; emulator scenes (c), (d), (e) and (g), the ten-minute cap included, as specified.
   500	- Codex all-clear with a confirming rerun per chunk's review, then one for the whole.

exec
/bin/zsh -lc 'git diff 87e07ca d3e54b5 -- app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt app/src/main/java/com/envi/wispr/audio/RecorderLease.kt app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1615ms:
2026-09-21 20:25:40.704 xcodebuild[68047:78218236]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:25:40.871 xcodebuild[68047:78218234] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-m9ilKGvF' (errno=Operation not permitted)
2026-09-21 20:25:41.333 xcodebuild[68057:78218271]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:25:41.457 xcodebuild[68057:78218270] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
diff --git a/app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl b/app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl
index 7161aed..0bab309 100644
--- a/app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl
+++ b/app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl
@@ -1,6 +1,7 @@
 package com.envi.wispr.audio;
 
 import com.envi.wispr.audio.IAudioSpectrumListener;
+import com.envi.wispr.audio.ITakeListener;
 
 interface IAudioCaptureService {
     boolean startCapture();
@@ -117,4 +118,19 @@ interface IAudioCaptureService {
 
     /** Stop receiving the picture. A listener that is not the registered one is ignored. */
     void unregisterSpectrumListener(IAudioSpectrumListener listener);
+
+    /**
+     * The take's events are PUSHED to this one listener (#115): live, a heartbeat each second, the silence
+     * status, and the ending with the closed file's path. Register BEFORE startCaptureForTake so no event
+     * precedes the registration. The service clears the slot itself when the last binding leaves (onUnbind)
+     * and on destroy, so an owner tearing down never has to call anything on an unresponsive process;
+     * unregisterTakeListener exists for the instrumentation client and symmetry. Since #115 the owner makes no other read
+     * on this interface: isCapturing, getTerminalReason, getElapsedMs, getLiveState, getLiveAfterMs,
+     * getSilenceStopStatus, getInputRouteKind, getInputRouteReason, getLastStartFailure, getAudioFilePath,
+     * getEffectiveInputDevice, getTakePeakAmplitude and waitForFileReady are LEGACY with no production
+     * caller, kept because this interface is append-only.
+     */
+    void registerTakeListener(ITakeListener listener);
+
+    void unregisterTakeListener(ITakeListener listener);
 }
diff --git a/app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl b/app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl
new file mode 100644
index 0000000..4f77512
--- /dev/null
+++ b/app/src/main/aidl/com/envi/wispr/audio/ITakeListener.aidl
@@ -0,0 +1,48 @@
+package com.envi.wispr.audio;
+
+/**
+ * The take's events, PUSHED by the capture process to the session owner (#115). Until then the owner asked
+ * for every one of them over synchronous binder calls from a polling thread, which a capture process that
+ * stopped answering parked forever, and the take was lost in silence.
+ *
+ * Every method is oneway: the publisher's worker never waits on the owner, and a dead owner costs nothing.
+ * Delivery order across a oneway interface is per-sender, so the owner serialises these onto its main
+ * thread before acting.
+ *
+ * Every event names its take: takeId is the id the owner passed to startCaptureForTake. The publisher is
+ * service-scoped and the listener slot is replaced by whichever owner registers last, so without the id a
+ * previous take's ending (queued after its session closed) would land on the next take's owner and be
+ * read as its own. An owner discards every event whose takeId is not its take's.
+ */
+interface ITakeListener {
+    /**
+     * The route delivered sound and the first admitted block is on disk. forced: the earbuds sent nothing
+     * and the take proceeded on the phone. routeKind and routeReason are InputRouteKind.code and
+     * InputRouteReason.code; liveAfterMs is the wait from start to live.
+     */
+    oneway void onLive(String takeId, boolean forced, int routeKind, int routeReason, long liveAfterMs);
+
+    /**
+     * A heartbeat, at most once per wall-clock second from the first positive read, live or not: elapsedMs
+     * is 0 before live and the recording's elapsed time after. Its arrival is liveness; its value is the
+     * timer.
+     */
+    oneway void onTick(String takeId, long elapsedMs);
+
+    /** The silence detector's status changed (the getSilenceStopStatus codes). */
+    oneway void onSilenceStatus(String takeId, int status);
+
+    /**
+     * The take is over and nothing about it will change again. terminalReason is the getTerminalReason
+     * code (TERMINAL_REASON_NONE for a start refused before capture began), startFailure the
+     * getLastStartFailure code, audioFilePath the CLOSED file (empty when there is none), silenceStatus
+     * the detector's last status, takePeakAmplitude the whole take's peak and effectiveInputDevice the
+     * display label of what captured it. Published exactly once per take, as the last thing the capture
+     * process does for it. A start refused before capture began carries no file, SILENCE_STATUS_DISABLED,
+     * a zero peak and an empty label: nothing of the previous take.
+     */
+    oneway void onEnded(String takeId, int terminalReason, int startFailure, String audioFilePath, int silenceStatus, float takePeakAmplitude, String effectiveInputDevice);
+
+    // APPENDED. Never reorder or rename anything above this line: a separately installed client binds
+    // by transaction number, and renumbering breaks it at runtime with no compile error.
+}
diff --git a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
index 0670e3d..31869c5 100644
--- a/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
+++ b/app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
@@ -140,6 +140,12 @@ class AudioCaptureService : Service() {
      * the capture thread.
      */
     private val spectrumListener = AtomicReference<IAudioSpectrumListener?>(null)
+    /**
+     * The one registered take-event listener (#115): the binding's slot, like the spectrum listener's, so it
+     * is dropped with the binding and never has to be unregistered by an owner tearing down.
+     */
+    private val takeListener = AtomicReference<ITakeListener?>(null)
+    private lateinit var takeEvents: TakeEventPublisher
     /**
      * The loudest sample of the current or most recent take, 0..1 of full scale. Written on the capture
      * thread, reset at start, kept after the take ends until the next start (like `lastEffective`), so a
@@ -192,6 +198,7 @@ class AudioCaptureService : Service() {
             // A new take keeps the service; every other end lets it go once no session is open.
             onIdle = { if (session == null) stopSelf() },
         )
+        takeEvents = TakeEventPublisher(takeListener, TAG).also { it.start() }
     }
 
     private val binder = object : IAudioCaptureService.Stub() {
@@ -259,6 +266,17 @@ class AudioCaptureService : Service() {
                 this@AudioCaptureService.spectrumListener.compareAndSet(current, null)
             }
         }
+
+        override fun registerTakeListener(listener: ITakeListener?) {
+            this@AudioCaptureService.takeListener.set(listener)
+        }
+
+        override fun unregisterTakeListener(listener: ITakeListener?) {
+            val current = this@AudioCaptureService.takeListener.get() ?: return
+            if (listener != null && current.asBinder() == listener.asBinder()) {
+                this@AudioCaptureService.takeListener.compareAndSet(current, null)
+            }
+        }
         override fun getAudioFilePath(): String? = this@AudioCaptureService.lastAudioFile?.absolutePath
 
         override fun getElapsedMs(): Long {
@@ -283,9 +301,15 @@ class AudioCaptureService : Service() {
 
     override fun onBind(intent: Intent?): IBinder = binder
 
-    /** The last binding is gone: clear future pushes; an analyser reference already read may still deliver once. Runs on the service main thread. */
+    /**
+     * The last binding is gone: clear both listener slots so no future push reaches an owner that left; a
+     * reference already read may still deliver once (#115 review round 1, F8: a warm hold keeps this
+     * service alive past the owner's unbind, so the slot is not dropped by the binding going). Runs on the
+     * service main thread.
+     */
     override fun onUnbind(intent: Intent?): Boolean {
         spectrumListener.set(null)
+        takeListener.set(null)
         return super.onUnbind(intent)
     }
 
@@ -320,8 +344,15 @@ class AudioCaptureService : Service() {
             // A stopped session remains here until its reader has closed both resources.
             // Starting another take before that point would make the old thread write into
             // the new take's file or release the new take's AudioRecord.
-            if (session != null) return false
+            if (session != null) {
+                // The previous take publishes its own ending from releaseSession, under ITS id; this refusal
+                // is the REQUESTED take's own event, so the owner never waits for an ending no new session
+                // can produce (#115).
+                publishStartRefused(takeId, START_FAILURE_OTHER)
+                return false
+            }
             lastStartFailure = START_FAILURE_NONE
+            takeEvents.resetTicks()
 
             // Route ownership exists BEFORE the session, so every failure path below can release it.
             val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
@@ -336,6 +367,7 @@ class AudioCaptureService : Service() {
                 routeHold.release()
                 DebugLogger.error(TAG, "No input device at all; refusing to start")
                 stopSelf()
+                publishStartRefused(takeId, lastStartFailure)
                 return false
             }
 
@@ -358,10 +390,22 @@ class AudioCaptureService : Service() {
                 lastStartFailure = START_FAILURE_OTHER
                 routeHold.release()
                 stopSelf()
+                publishStartRefused(takeId, lastStartFailure)
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
+            var threadStarted = false
             var output: FileOutputStream? = null
             try {
                 record = AudioRecord(
@@ -416,6 +460,9 @@ class AudioCaptureService : Service() {
                         autoStop = detectorEnabled,
                         bind = DetectorFeed.bindingThrough(this),
                         unbind = DetectorFeed.unbindingThrough(this),
+                        // The session's own id, captured here: a callback outliving its take must not
+                        // borrow the next take's (review round 2, F2).
+                        onStatus = { status -> takeEvents.publishSilenceStatus(takeId, status) },
                     ),
                     picture = PicturePublisher(spectrumListener, TAG),
                     route = takeRoute,
@@ -465,13 +512,20 @@ class AudioCaptureService : Service() {
                 captureThread = thread
                 try {
                     thread.start()
+                    threadStarted = true
                 } catch (e: Exception) {
                     isRecording.set(false)
                     session = null
                     closeResources(newSession, keepRoute = false)
+                    // The detector was started above and would otherwise keep running for a take that
+                    // never began (#115 review); the picture has not started yet, close is idempotent.
+                    newSession.detector.close(unbindNow = true)
+                    newSession.picture.close()
                     captureThread = null
                     stopSelf()
                     DebugLogger.error(TAG, "Failed to start capture thread", e)
+                    lastStartFailure = START_FAILURE_OTHER
+                    publishStartRefused(takeId, lastStartFailure)
                     return false
                 }
                 newSession.picture.start(stillLive = { session === newSession && !newSession.stopRequested })
@@ -492,19 +546,11 @@ class AudioCaptureService : Service() {
                 return thread.isAlive && session === newSession && isRecording.get()
             } catch (e: SecurityException) {
                 DebugLogger.error(TAG, "RECORD_AUDIO permission not granted", e)
-                lastStartFailure = START_FAILURE_OTHER
-                isRecording.set(false)
-                routeHold.release()
-                closeResources(record, output)
-                stopSelf()
+                failSetup(threadStarted, record, output, routeHold, takeId)
                 return false
             } catch (e: Exception) {
                 DebugLogger.error(TAG, "Failed to start recording", e)
-                lastStartFailure = START_FAILURE_OTHER
-                isRecording.set(false)
-                routeHold.release()
-                closeResources(record, output)
-                stopSelf()
+                failSetup(threadStarted, record, output, routeHold, takeId)
                 return false
             }
         }
@@ -546,6 +592,9 @@ class AudioCaptureService : Service() {
                 val bytesRead = active.record.read(buffer, 0, requested, AudioRecord.READ_BLOCKING)
                 if (bytesRead < 0) throw IOException("AudioRecord.read failed: $bytesRead")
                 if (bytesRead == 0) continue
+                // The heartbeat, live or not, BEFORE the gate branch: its arrival is liveness to the owner
+                // (#115). One primitive comparison here; the publisher's worker makes the binder call.
+                takeEvents.offerTick(active.takeId, if (active.liveVisible) SystemClock.elapsedRealtime() - active.route.liveAtMs else 0L)
 
                 // Until the gate opens, a read feeds the gate and nothing else: not the file, not the
                 // detector, not the picture, not the level. The take's clock starts when the gate opens.
@@ -557,7 +606,18 @@ class AudioCaptureService : Service() {
                 val position = active.bytesWritten
                 active.output.write(buffer, 0, bytesRead)
                 active.bytesWritten += bytesRead
-                active.liveVisible = true
+                if (!active.liveVisible) {
+                    // Live is published only once the first admitted block is on disk (#115 review).
+                    active.liveVisible = true
+                    val effective = lastEffective
+                    takeEvents.publishLive(
+                        active.takeId,
+                        active.route.gate.state == LiveGate.State.FORCED,
+                        effective?.kind?.code ?: InputRouteKind.NONE.code,
+                        effective?.reasonCode() ?: InputRouteReason.AUTO.code,
+                        (active.route.liveAtMs - active.route.startedAtMs).coerceAtLeast(0L),
+                    )
+                }
                 active.detector.offer(buffer, bytesRead, position)
                 active.picture.offer(buffer, bytesRead, position)
 
@@ -590,12 +650,50 @@ class AudioCaptureService : Service() {
         }
     }
 
+    /**
+     * A start refused before any capture began: the owner registered for the take's events and would
+     * otherwise wait for an ending no session can produce (#115). Carries the REQUESTED take's id, the
+     * failure code, no path, and nothing of the previous take: a disabled silence status, a zero peak and
+     * no device label (review round 1, F6).
+     */
+    private fun publishStartRefused(takeId: String, failure: Int) {
+        takeEvents.publishEnded(takeId, TERMINAL_REASON_NONE, failure, null, SILENCE_STATUS_DISABLED, 0f, null)
+    }
+
+    /**
+     * A setup exception in `startRecording`. BEFORE the capture thread started nothing else owns the
+     * recorder or the file, so this clears and closes locally and publishes the refusal itself. AFTER it
+     * started, the live thread owns both, and closing them from the binder thread would race it: the
+     * error is claimed on the session and the recorder signalled, and `releaseSession` alone cleans up
+     * and publishes (#115 review, round 4). Under `sessionLock` in both cases (the caller holds it).
+     */
+    private fun failSetup(threadStarted: Boolean, record: AudioRecord?, output: java.io.FileOutputStream?, routeHold: RouteHold, takeId: String) {
+        lastStartFailure = START_FAILURE_OTHER
+        val active = session
+        if (threadStarted && active != null) {
+            endTakeLocked(active, TERMINAL_REASON_ERROR)
+            return
+        }
+        isRecording.set(false)
+        if (active != null) {
+            session = null
+            captureThread = null
+            active.detector.close(unbindNow = true)
+            active.picture.close()
+        }
+        routeHold.release()
+        closeResources(record, output)
+        stopSelf()
+        publishStartRefused(takeId, lastStartFailure)
+    }
+
     /**
      * Publish the first ending claimed for [active] and stop the loop.
      *
-     * The published reason must outlive [releaseSession], because the client polls `getTerminalReason`
-     * only AFTER `isCapturing` has gone false, by which point the session is gone. So the value lives on
-     * the service while the CLAIM lives on the session, and the claim is what makes it first-wins.
+     * The published reason outlives [releaseSession] on the service for the legacy `getTerminalReason`
+     * getter (append-only interface; no production caller since #115), while the CLAIM lives on the
+     * session and is what makes it first-wins. The owner learns the reason from the ending
+     * [releaseSession] pushes, never from the getter.
      */
     private fun claimEnding(active: CaptureSession, reason: Int): Boolean {
         if (!active.endingClaim.claim(reason)) return false
@@ -693,6 +791,17 @@ class AudioCaptureService : Service() {
         DebugLogger.log(TAG, "Live picture: pushed=${active.picture.pushes.get()} polled=${active.picture.polls.get()}")
         // A hold keeps the service alive; its end calls stopSelf (RULE: the service owns its own end).
         if (!holding) stopSelf()
+        // LAST, once per take, after every close and the service-lifetime work: the owner acts on this
+        // event and nothing about the take changes after it (#115).
+        takeEvents.publishEnded(
+            active.takeId,
+            terminalReason,
+            lastStartFailure,
+            active.file.absolutePath,
+            lastSilenceStatus,
+            takePeakAmplitude,
+            lastEffective?.label(),
+        )
     }
 
     /**
@@ -717,8 +826,18 @@ class AudioCaptureService : Service() {
             .onFailure {
                 if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.message}")
             }
-        runCatching { record?.release() }
+        val released = record == null || runCatching { record.release() }
             .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
+            .isSuccess
+        // Every caller acquired the lease before creating the recorder (or failing to); released only
+        // AFTER the recorder was, so the next start in this process cannot open a second one first. A
+        // recorder whose release failed is still held by the native layer for all this code knows, so the
+        // lease stays held until the process dies (#115 review round 2).
+        if (released) {
+            RecorderLease.PROCESS.release()
+        } else {
+            DebugLogger.warn(TAG, "Recorder release failed; the process's recorder lease stays held")
+        }
     }
 
     /** Wait for the capture thread to finish writing and close the file. */
@@ -742,26 +861,29 @@ class AudioCaptureService : Service() {
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
+        // A new take in this process cannot open a second recorder while the old one is held, in THIS
+        // instance (startRecording refuses while `session` is set) or in a replacement instance in the
+        // same process (`RecorderLease.PROCESS` is released only after the recorder is).
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
+        // A take that ended above published its ending through this worker, which delivers what is queued
+        // and then leaves (#115). Never joined. Both slots cleared here as well: a destroyed service
+        // pushes nothing.
+        takeEvents.close()
+        spectrumListener.set(null)
+        takeListener.set(null)
         super.onDestroy()
     }
 }
diff --git a/app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt b/app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt
index 23b4cd8..f57bc57 100644
--- a/app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt
+++ b/app/src/main/java/com/envi/wispr/audio/DetectorFeed.kt
@@ -30,6 +30,8 @@ internal class DetectorFeed(
     private val bind: (ServiceConnection) -> Boolean,
     private val unbind: (ServiceConnection) -> Unit,
     private val interrupt: (Thread) -> Unit = { it.interrupt() },
+    /** Fired with the new code at each of the three status writes, for the take-event push (#115). */
+    private val onStatus: (Int) -> Unit = {},
 ) {
     companion object {
         /**
@@ -87,6 +89,7 @@ internal class DetectorFeed(
     /** The caller asked for auto-stop and cannot have it (an out-of-range pause): the notice's state. */
     fun markRequestedButRefused() {
         silenceStatus.set(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
+        onStatus(AudioCaptureService.SILENCE_STATUS_UNAVAILABLE)
     }
 
     /**
@@ -254,10 +257,13 @@ internal class DetectorFeed(
                         return
                     }
                     started = true
-                    silenceStatus.compareAndSet(
-                        AudioCaptureService.SILENCE_STATUS_PREPARING,
-                        AudioCaptureService.SILENCE_STATUS_READY,
-                    )
+                    if (silenceStatus.compareAndSet(
+                            AudioCaptureService.SILENCE_STATUS_PREPARING,
+                            AudioCaptureService.SILENCE_STATUS_READY,
+                        )
+                    ) {
+                        onStatus(AudioCaptureService.SILENCE_STATUS_READY)
+                    }
                 }
 
                 val length = ring.poll(block)
@@ -321,7 +327,10 @@ internal class DetectorFeed(
                 AudioCaptureService.SILENCE_STATUS_READY -> AudioCaptureService.SILENCE_STATUS_LOST_AFTER_READY
                 else -> AudioCaptureService.SILENCE_STATUS_UNAVAILABLE
             }
-            if (silenceStatus.compareAndSet(previous, next)) return
+            if (silenceStatus.compareAndSet(previous, next)) {
+                onStatus(next)
+                return
+            }
         }
     }
 
diff --git a/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt b/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt
new file mode 100644
index 0000000..3bcf063
--- /dev/null
+++ b/app/src/main/java/com/envi/wispr/audio/RecorderLease.kt
@@ -0,0 +1,31 @@
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
+ * the recorder is created and released only after the owning thread has released it SUCCESSFULLY, on every
+ * path; a recorder whose release threw keeps the lease until the process dies.
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
diff --git a/app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt b/app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt
new file mode 100644
index 0000000..54a3873
--- /dev/null
+++ b/app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt
@@ -0,0 +1,173 @@
+package com.envi.wispr.audio
+
+import com.envi.wispr.debug.DebugLogger
+import java.util.Queue
+import java.util.concurrent.ConcurrentLinkedQueue
+import java.util.concurrent.atomic.AtomicLong
+import java.util.concurrent.atomic.AtomicReference
+import java.util.concurrent.locks.LockSupport
+
+/**
+ * Pushes the take's events to the one registered [ITakeListener] (#115): live, a heartbeat, the silence
+ * status and the ending. Service-scoped like `WarmHoldOwner`: one publisher for the service's lifetime, one
+ * worker thread, one queue.
+ *
+ * The capture thread only ever does a primitive clock comparison and a lock-free offer here: one small
+ * event object and one queue node are allocated per event, no lock is taken (`ConcurrentLinkedQueue`), and
+ * the worker is unparked, which is a permit write. A `oneway` binder transaction still allocates a Parcel
+ * and can back-pressure its caller, so the worker makes every call, under `runCatching`: a dead or
+ * unresponsive owner costs the event and nothing else, and never the capture loop. Delivery order is the
+ * queue's: the order the producers' offers linearised in, which for one producer is the order it offered.
+ *
+ * Every event names its take, passed by the caller at each publish (never read from a shared field, which a
+ * detector callback outliving its take would read as the NEXT take's): the listener slot is the binding's
+ * and a new owner can register while the previous take's ending is still queued, so the owner discards
+ * events that are not its take's.
+ *
+ * [close] is the end of delivery, decided by ONE atomic word ([lifecycle]: a closed bit and a count of
+ * offers between entry and enqueue): an offer enters only by a compare-and-set that fails once the closed
+ * bit is set, an entered offer is always enqueued and delivered, and one that finds the bit set is dropped
+ * by contract, never lost by a race (the worker leaves only once the bit is set, no offer is between entry
+ * and enqueue, and the queue is empty). After the service's destroy nothing about any take can change, and
+ * the owner's silence bound covers a take whose ending was never published.
+ *
+ * Every event is a limb. The take does not know this class exists.
+ */
+internal class TakeEventPublisher(
+    private val listener: AtomicReference<ITakeListener?>,
+    private val tag: String,
+    private val nowNanos: () -> Long = System::nanoTime,
+    /** The event queue; a test hands in one whose enqueue it can hold, to stage close against an entered offer. */
+    private val queue: Queue<Event> = ConcurrentLinkedQueue(),
+) {
+    companion object {
+        /** Heartbeats are throttled by WALL-CLOCK second: elapsed is 0 before live and would send one. */
+        const val TICK_INTERVAL_NANOS = 1_000_000_000L
+
+        /** The closed bit of [lifecycle]; the low bits count offers between entry and enqueue. */
+        private const val CLOSED = 1L shl 62
+        private const val ENTERED_MASK = CLOSED - 1
+    }
+
+    internal sealed interface Event {
+        val takeId: String
+
+        data class Live(override val takeId: String, val forced: Boolean, val routeKind: Int, val routeReason: Int, val liveAfterMs: Long) : Event
+        data class Tick(override val takeId: String, val elapsedMs: Long) : Event
+        data class SilenceStatus(override val takeId: String, val status: Int) : Event
+        data class Ended(
+            override val takeId: String,
+            val terminalReason: Int,
+            val startFailure: Int,
+            val audioFilePath: String,
+            val silenceStatus: Int,
+            val takePeakAmplitude: Float,
+            val effectiveInputDevice: String,
+        ) : Event
+    }
+
+    /** One word: the [CLOSED] bit and the count of offers that entered and have not enqueued yet. */
+    private val lifecycle = AtomicLong(0L)
+    @Volatile private var lastTickNanos = Long.MIN_VALUE
+    private val worker = Thread({ drain() }, "TakeEventPublisher").apply { isDaemon = true }
+
+    /** Starts the worker; called once by the service's `onCreate`, before any event can be offered. */
+    fun start() {
+        runCatching { worker.start() }.onFailure { DebugLogger.warn(tag, "Take events unavailable: ${it.javaClass.simpleName}") }
+    }
+
+    /** A new take: the next positive read sends a heartbeat at once. */
+    fun resetTicks() {
+        lastTickNanos = Long.MIN_VALUE
+    }
+
+    /**
+     * Capture thread only, after each positive read. One primitive comparison; at most one event a second.
+     * **Nothing here logs, locks, waits or calls across a process**; the allocation is the event and its node.
+     */
+    fun offerTick(takeId: String, elapsedMs: Long) {
+        val now = nowNanos()
+        val last = lastTickNanos
+        // The sentinel is tested by identity: `now - Long.MIN_VALUE` overflows negative and would swallow
+        // the first heartbeat of every take (found by TakeEventPublisherTest).
+        if (last != Long.MIN_VALUE && now - last < TICK_INTERVAL_NANOS) return
+        lastTickNanos = now
+        offer(Event.Tick(takeId, elapsedMs))
+    }
+
+    fun publishLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
+        offer(Event.Live(takeId, forced, routeKind, routeReason, liveAfterMs))
+    }
+
+    fun publishSilenceStatus(takeId: String, status: Int) {
+        offer(Event.SilenceStatus(takeId, status))
+    }
+
+    /** The last thing the capture process does for a take. Exactly once per take is the CALLER's contract. */
+    fun publishEnded(
+        takeId: String,
+        terminalReason: Int,
+        startFailure: Int,
+        audioFilePath: String?,
+        silenceStatus: Int,
+        takePeakAmplitude: Float,
+        effectiveInputDevice: String?,
+    ) {
+        offer(Event.Ended(takeId, terminalReason, startFailure, audioFilePath.orEmpty(), silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
+    }
+
+    /** Stops the worker once every offer that entered before this is delivered. Idempotent; never joined. */
+    fun close() {
+        val before = lifecycle.getAndUpdate { it or CLOSED }
+        if (before and CLOSED != 0L) return
+        LockSupport.unpark(worker)
+    }
+
+    /** Enter: one compare-and-set that fails once the closed bit is set. True means this offer WILL enqueue. */
+    private fun enter(): Boolean {
+        while (true) {
+            val current = lifecycle.get()
+            if (current and CLOSED != 0L) return false
+            if (lifecycle.compareAndSet(current, current + 1)) return true
+        }
+    }
+
+    private fun offer(event: Event) {
+        // Entered by the CAS, or dropped by contract (see the class KDoc); no check-then-act in between.
+        if (!enter()) return
+        try {
+            queue.offer(event)
+        } finally {
+            lifecycle.decrementAndGet()
+            // A permit, never a lock: an unpark before the worker parks makes its next park return at
+            // once, so an offer racing the worker's empty check cannot be lost.
+            LockSupport.unpark(worker)
+        }
+    }
+
+    /**
+     * Parked between events: no timer, no wake at idle (`architecture-rules.md` RULE: no-idle-cost). The
+     * unpark from an offer or from [close] is the only thing that wakes it; after close, what is queued
+     * and what is still being offered is delivered, then the worker leaves.
+     */
+    private fun drain() {
+        while (true) {
+            val event = queue.poll()
+            if (event == null) {
+                val state = lifecycle.get()
+                if (state and CLOSED != 0L && state and ENTERED_MASK == 0L && queue.isEmpty()) return
+                LockSupport.park(this)
+                continue
+            }
+            val target = listener.get() ?: continue
+            runCatching {
+                when (event) {
+                    is Event.Live -> target.onLive(event.takeId, event.forced, event.routeKind, event.routeReason, event.liveAfterMs)
+                    is Event.Tick -> target.onTick(event.takeId, event.elapsedMs)
+                    is Event.SilenceStatus -> target.onSilenceStatus(event.takeId, event.status)
+                    is Event.Ended -> target.onEnded(event.takeId, event.terminalReason, event.startFailure, event.audioFilePath, event.silenceStatus, event.takePeakAmplitude, event.effectiveInputDevice)
+                }
+            }.onFailure { DebugLogger.warn(tag, "Take event not delivered: ${event.javaClass.simpleName} ${it.javaClass.simpleName}") }
+        }
+    }
+}

exec
/bin/zsh -lc 'git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt app/src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt app/src/main/java/com/envi/wispr/telemetry/TelemetryChannels.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1449ms:
2026-09-21 20:25:55.968 xcodebuild[68095:78218914]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:25:56.077 xcodebuild[68095:78218913] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-54o7qh2H' (errno=Operation not permitted)
2026-09-21 20:25:56.471 xcodebuild[68097:78218954]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:25:56.593 xcodebuild[68097:78218953] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
diff --git a/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
new file mode 100644
index 0000000..6bcfd7e
--- /dev/null
+++ b/app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt
@@ -0,0 +1,55 @@
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
+ * Per-take writers (insert, status, discard, finalize, the insertion outcome, the teardown's interrupted
+ * mark) go through here and nothing else does: the History screen's writers (`setKept`, `delete`,
+ * `deleteAll`, `pruneWordlessRows`) and the start-up recovery (`recoverStaleOpenRows`) stay on their own
+ * scopes, so a write of theirs stalled on the disk never sits ahead of a live take's writes.
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
diff --git a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
index a8e49be..f93ad98 100644
--- a/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
+++ b/app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt
@@ -30,6 +30,7 @@ import com.envi.wispr.audio.InputDevicePick
 import com.envi.wispr.audio.InputDeviceResolver
 import com.envi.wispr.history.EnviousWisprDatabase
 import com.envi.wispr.history.TranscriptRepository
+import com.envi.wispr.models.ModelBootstrapApplication
 import com.envi.wispr.history.TranscriptEntity
 import com.envi.wispr.insertion.ClipboardInsertionPolicy
 import com.envi.wispr.insertion.ClipboardOutcome
@@ -46,15 +47,12 @@ import com.envi.wispr.ui.DictationSessionService
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
@@ -608,8 +606,7 @@ class PasteAccessibilityService : AccessibilityService() {
     }
 
     override fun onDestroy() {
-        // Retract the publication FIRST. Teardown below blocks this thread draining Room, and a
-        // reader during that window would otherwise see a healthy binding on a dying service.
+        // Retract the publication FIRST, so no reader sees a healthy binding on a dying service.
         if (instance === this) publishBinding(null)
         lookScope.cancel()
         getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDeviceCallback)
@@ -619,24 +616,26 @@ class PasteAccessibilityService : AccessibilityService() {
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
+        // LAST, and written HERE rather than queued behind the outcome write (#115 review, F4): the marker
+        // answers whether THIS service stopped in order, which it did once this method runs, and a
+        // queued mark could land after a replacement connected and either be declined by the instance
+        // guard (the orderly stop then reads as a crash) or overwrite the replacement's own arm. A kill
+        // after this point loses only the queued outcome write, which the start-up recovery closes.
         markStopWasClean()
         super.onDestroy()
     }
@@ -1403,10 +1402,12 @@ class PasteAccessibilityService : AccessibilityService() {
             emit()
             return
         }
-        historyScope.launch {
-            // The History update is first-wins; the row leaves only when THIS writer won it, so a
-            // recovery or a second finalizer that got there first is the one that reports (round 1, F5).
-            val changed = runCatching { transcriptRepository.finalizeInsertionOutcome(pending.transcriptId, status, result, interrupted) }
+        // On the application's History queue (#115), behind the owner's writes of the same row, so the
+        // outcome cannot land before the finalization it belongs to. The update is first-wins; the row
+        // leaves only when THIS writer won it, so a recovery or a second finalizer that got there first
+        // is the one that reports (round 1, F5).
+        ModelBootstrapApplication.historyWrites(applicationContext).enqueue("insertion outcome") { repository ->
+            val changed = runCatching { repository.finalizeInsertionOutcome(pending.transcriptId, status, result, interrupted) }
                 .onFailure { error -> Log.w(TAG, "Unable to update transcript insertion result: ${error.message}") }
                 .getOrNull()
             if (changed == 1) emit()
diff --git a/app/src/main/java/com/envi/wispr/telemetry/TelemetryChannels.kt b/app/src/main/java/com/envi/wispr/telemetry/TelemetryChannels.kt
index 78852a4..2270d44 100644
--- a/app/src/main/java/com/envi/wispr/telemetry/TelemetryChannels.kt
+++ b/app/src/main/java/com/envi/wispr/telemetry/TelemetryChannels.kt
@@ -148,7 +148,7 @@ internal object TelemetryChannels {
         TerminalReason.CANCELLED_PROCESSING, TerminalReason.NO_SPEECH, TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
         TerminalReason.ASR_EMPTY_UNMEASURED, TerminalReason.FINAL_TEXT_EMPTY, TerminalReason.INTERRUPTED_STARTING,
         TerminalReason.INTERRUPTED_RECORDING, TerminalReason.INTERRUPTED_PROCESSING,
-        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.ASR_PROCESS_DIED,
+        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, TerminalReason.ASR_PROCESS_DIED,
         TerminalReason.POLISH_PROCESS_DIED, TerminalReason.AUDIO_BIND_FAILED,
         TerminalReason.ASR_BIND_FAILED, TerminalReason.POLISH_BIND_FAILED, TerminalReason.CAPTURE_START_NO_MICROPHONE,
         TerminalReason.CAPTURE_START_EARBUDS_REFUSED, TerminalReason.CAPTURE_START_FAILED,
@@ -183,7 +183,7 @@ internal object TelemetryChannels {
         TerminalReason.CANCELLED_PROCESSING, TerminalReason.NO_SPEECH, TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
         TerminalReason.ASR_EMPTY_UNMEASURED, TerminalReason.FINAL_TEXT_EMPTY, TerminalReason.INTERRUPTED_STARTING,
         TerminalReason.INTERRUPTED_RECORDING, TerminalReason.INTERRUPTED_PROCESSING,
-        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.ASR_PROCESS_DIED,
+        TerminalReason.INTERRUPTED_CANCELLING, TerminalReason.AUDIO_PROCESS_DIED, TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, TerminalReason.ASR_PROCESS_DIED,
         TerminalReason.POLISH_PROCESS_DIED, TerminalReason.AUDIO_BIND_FAILED,
         TerminalReason.ASR_BIND_FAILED, TerminalReason.POLISH_BIND_FAILED, TerminalReason.CAPTURE_START_NO_MICROPHONE,
         TerminalReason.CAPTURE_START_EARBUDS_REFUSED, TerminalReason.CAPTURE_START_FAILED,

codex
Whole #115 review — WORKING

The audio and storage ownership changes are mapped. I’m now checking the coordinator’s first-wins state machine and every capture-process touch in the combined diff.
exec
/bin/zsh -lc 'git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1398ms:
2026-09-21 20:26:06.552 xcodebuild[68107:78219301]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:06.660 xcodebuild[68107:78219300] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-oDUfBsaX' (errno=Operation not permitted)
2026-09-21 20:26:07.046 xcodebuild[68109:78219328]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:07.165 xcodebuild[68109:78219326] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
index ba13f66..e97def7 100644
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
@@ -41,20 +42,18 @@ import com.envi.wispr.telemetry.Telemetry
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
 import java.util.UUID
+import java.util.concurrent.ExecutorService
+import java.util.concurrent.Executors
 import java.util.concurrent.atomic.AtomicBoolean
 import java.util.concurrent.atomic.AtomicLong
 import java.util.concurrent.atomic.AtomicReference
@@ -76,11 +75,14 @@ internal class DictationSessionCoordinator(
     private val insertion: InsertionGateway,
     private val log: SessionLog,
     private val preferences: SessionPreferencesSource,
+    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
+    private val historyWrites: HistoryWriteQueue,
+    /** For the start-up recovery ONLY, on the session scope: a stalled recovery must not sit ahead of a take's writes on the queue. */
     private val transcripts: TranscriptRepository,
     private val languageDetector: LanguageDetector,
     private val loadPolicy: suspend () -> PolishPolicy,
     private val pipeline: PipelineController,
-    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is joined on destroy. */
+    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is cancelled on destroy, never joined (#115). */
     private val scope: CoroutineScope,
     /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
     private val mainDispatcher: CoroutineDispatcher,
@@ -124,16 +126,22 @@ internal class DictationSessionCoordinator(
          * and Room deliver in milliseconds; a hung store cannot hold the microphone longer than this.
          */
         const val SETTINGS_ANSWER_BOUND_MS = 2_000L
+
+        /**
+         * How long the capture process may stay SILENT before the take treats it as unresponsive (#115).
+         * The capture loop heartbeats once a second from its first read; three missed beats is a process
+         * that is frozen or wedged, not slow (the emulator's measured gaps are recorded in the #115 plan).
+         * Armed before the take's listener is registered and disarmed when the binding is released, so it
+         * also covers a command outstanding after the ending; no timer exists outside a take.
+         */
+        const val TAKE_SILENT_BOUND_MS = 3_000L
     }
 
     private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }
 
-    /** How often the live waiter asks the capture process. */
-    private val LIVE_POLL_MS = 20L
-
     /**
      * The STARTING bound: two live deadlines (one reset) plus a second, after which a take that never
-     * went live fails rather than spins.
+     * went live fails rather than spins. A main-thread timer since #115; the live waiter thread is gone.
      */
     private val LIVE_WAIT_BOUND_MS = 2 * LiveGate.DEADLINE_MS + 1_000L
 
@@ -182,7 +190,6 @@ internal class DictationSessionCoordinator(
     private val draftId = AtomicLong(0L)
     /** The injected scope's job, read once; `destroy` cancels and joins exactly this. */
     private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }
-    private val pendingHistoryUpdates = java.util.Collections.synchronizedList(mutableListOf<Job>())
 
     private var rawTranscript = ""
     // What the START of this dictation saw when it tried to pin an editor. Read at the end,
@@ -202,8 +209,37 @@ internal class DictationSessionCoordinator(
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
+    /**
+     * The take's cancel, reserved under [publishLock] and committed when the capture process publishes
+     * the ending (#115): a cancel no longer waits for the file itself.
+     */
+    @Volatile private var pendingCancel: TakeArbiter.Token? = null
+    @Volatile private var pendingCancelReason: TerminalReason = TerminalReason.CANCELLED_RECORDING
+    /** Set once the ending has been consumed for this take, so a duplicate or a late event changes nothing. */
+    private val endingConsumed = AtomicBoolean(false)
+    /** Whether the silence bound is armed; the runnable is re-posted on every event from the capture process. */
+    private val silenceBoundArmed = AtomicBoolean(false)
+    private val silenceBound = Runnable { onCaptureSilent() }
+    private val liveDeadline = Runnable { onLiveDeadline() }
+    /**
+     * Every synchronous call INTO the capture process runs here, one at a time, in the order issued, and
+     * never on the main thread (#115 review round 1, F1): a call into a process that has stopped answering
+     * has no timeout, and on main it would park the very thread the silence bound fires on. One lane, not
+     * the shared scope, so a stop can never overtake the start it belongs to. A lane parked in a wedged
+     * process stays parked; the instance ends through the bound and the next take has its own lane.
+     */
+    private val captureCommands: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
+        Thread(runnable, "CaptureCommands").apply { isDaemon = true }
+    }
     @Volatile private var sessionPreferences = SessionPreferences()
     @Volatile private var silenceNoticeShown = false
     /** The take proceeded on earbuds that sent nothing; said once, before any other microphone line. */
@@ -216,8 +252,8 @@ internal class DictationSessionCoordinator(
      */
     private val publishLock = Any()
     /**
-     * What captured the take, read ONCE at stop after `waitForFileReady`, when the capture thread has
-     * exited and the record is complete. Empty means unknown and is stored as such (#26).
+     * What captured the take, carried on the ending the capture process publishes once its thread has
+     * exited and the record is complete (#115). Empty means unknown and is stored as such (#26).
      */
     @Volatile private var captureDeviceLabel = ""
     /** One warning per take, latched so the last minute is not announced ten times a second. */
@@ -228,6 +264,9 @@ internal class DictationSessionCoordinator(
 
     /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
     fun onCreated() {
+        // On its own scope, never on the per-take queue (#115 review): a recovery stalled on the disk
+        // would otherwise sit ahead of every write of the take that follows. It closes rows an EARLIER
+        // process left open; the new take's rows are not among them.
         scope.launch {
             runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
                 .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
@@ -361,6 +400,8 @@ internal class DictationSessionCoordinator(
         rawTranscript = ""
         recordingDurationMs = 0L
         lastElapsedSecond = -1
+        endingConsumed.set(false)
+        pendingCancel = null
         scope.launch {
             // The readers are limbs (#193): a failed or silent read never ends the take. The start carries
             // both outcomes and the values that came with them, taken by one atomic read each, and the
@@ -461,38 +502,52 @@ internal class DictationSessionCoordinator(
             durationWarningShown = false
             forcedNoticeShown = false
             captureDeviceLabel = ""
-            val started = runCatching {
-                // The frozen snapshot, never the live source: a settings emission after the take's answer
-                // belongs to the next take (#193).
-                pipeline.capture?.startCaptureForTake(
-                    sessionPreferences.autoStopOnSilence,
-                    sessionPreferences.silencePauseSeconds,
-                    sessionPreferences.inputDevicePick,
-                    sessionPreferences.keepEarbudsReady,
-                    takeId,
-                )
-            }.getOrNull()
-            if (started != true) {
-                // The one start failure with its own sentence is "nothing can record at all" (macOS copy).
-                val failure = runCatching { pipeline.capture?.lastStartFailure() }.getOrNull()
-                    ?: AudioCaptureService.START_FAILURE_OTHER
-                pipeline.stopAudioService()
-                showError(TakeNotices.startFailureReason(failure))
-                return
+            // The bound is armed and the listener registered BEFORE the start command (#115): a start that
+            // never returns, a registration that hangs, and an event that precedes registration are all
+            // covered. The take is STARTING until the capture process publishes live: the lips spin, no
+            // pill, no timer, nothing written.
+            armSilenceBound()
+            host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)
+            // The frozen snapshot, never the live source: a settings emission after the take's answer
+            // belongs to the next take (#193).
+            val preferences = sessionPreferences
+            val id = takeId
+            captureStarted = commandCapture("start") { capture ->
+                capture.listenForTake(takeListener)
+                // A registration that wedged and then returned: the silence bound may already have
+                // ended this take and released the binding, and a start now would record for nobody
+                // (review round 2, F1). Checked on the lane, before the start and again after it.
+                if (state.get() != SessionState.STARTING || takeId != id) return@commandCapture
+                val started = try {
+                    capture.startCaptureForTake(
+                        preferences.autoStopOnSilence,
+                        preferences.silencePauseSeconds,
+                        preferences.inputDevicePick,
+                        preferences.keepEarbudsReady,
+                        id,
+                    )
+                } catch (error: Exception) {
+                    // A binder that threw: the process is gone or broken. Its ServiceConnection or the
+                    // silence bound would end the take too; this is the same ending, sooner.
+                    log.error("Failed to start recording", error)
+                    host.postToMain { showError(TerminalReason.START_EXCEPTION) }
+                    return@commandCapture
+                }
+                if (!started) {
+                    // Every refused start publishes its own ending with the failure code (the #115 plan's
+                    // table, one publisher per exit). Nothing is read here.
+                    log.warn("Capture start refused; the ending event carries why")
+                } else if (state.get() != SessionState.STARTING || takeId != id) {
+                    // The take ended while the start was in flight: no owner is listening for it.
+                    log.warn("Capture started for a take that already ended; stopping it")
+                    runCatching { capture.stopCapture() }
+                    pipeline.stopAudioService()
+                }
             }
-            captureStarted = true
-            // The take is STARTING until the chosen route delivers sound: the lips spin, no pill, no
-            // timer, nothing written. The waiter performs the RECORDING transition when the capture
-            // process reports live (issue #26, 2026-09-18).
-            Thread({ waitForLive() }, "LiveWaiter").start()
         } catch (error: Exception) {
             if (captureStarted) {
-                val capture = pipeline.capture
-                Thread({
-                    runCatching { capture?.stopCapture() }
-                    runCatching { capture?.waitForFileReady(2_000L) }
-                    pipeline.stopAudioService()
-                }, "StartCaptureFailureCleanup").start()
+                commandCapture("stop after a failed start") { it.stopCapture() }
+                pipeline.stopAudioService()
             }
             log.error("Failed to start recording", error)
             showError(TerminalReason.START_EXCEPTION)
@@ -500,109 +555,273 @@ internal class DictationSessionCoordinator(
     }
 
     /**
-     * Polls the capture process until the take is live, then publishes RECORDING. Four ways out without
-     * publishing: the state left STARTING (a cancel or teardown won), the binder is gone, capture ended
-     * during the wait (a deadline failure or a capture error), or the STARTING bound passed. None of
-     * them can publish a pill for an ended take, and none waits forever.
+     * Issue one call into the capture process on [captureCommands]; returns whether it was issued (the
+     * binding is present and the lane accepts work). The block's own failure is logged and costs nothing
+     * else: the take's ending comes from the process's events or from the silence bound, never from here.
      */
-    private fun waitForLive() {
-        val startedAt = host.elapsedRealtimeMs()
-        while (true) {
-            if (state.get() != SessionState.STARTING) return
-            val service = pipeline.capture ?: return // onServiceDisconnected ends the take for STARTING too.
-            val live = try {
-                service.liveState()
-            } catch (_: Exception) {
-                return // A dead binder: its ServiceConnection callback ends the take.
+    private fun commandCapture(what: String, block: (CaptureLink) -> Unit): Boolean {
+        val capture = pipeline.capture ?: return false
+        return runCatching {
+            captureCommands.execute {
+                runCatching { block(capture) }.onFailure { log.warn("Capture command failed ($what): ${it.javaClass.simpleName}") }
             }
-            if (live != AudioCaptureService.LIVE_WAITING) {
-                // Published on the MAIN thread, where every command is dispatched and where the
-                // bubble's early release sets its flag: the old start published there too, so a stop,
-                // cancel or release can never read STARTING and then act against a take this thread
-                // published in between (Codex review 4, 2026-09-18).
-                val forced = live == AudioCaptureService.LIVE_FORCED
-                host.postToMain { publishLive(forced) }
-                return
+        }.onFailure { log.warn("Capture command not issued ($what): ${it.javaClass.simpleName}") }.isSuccess
+    }
+
+    /**
+     * The take's events, each posted to the main thread and handled there in delivery order (#115). An
+     * event from another take (the previous take's ending, queued in the service-scoped publisher before
+     * this owner registered) is discarded before it can re-arm the bound or touch any state.
+     */
+    private val takeListener = object : TakeListener {
+        private fun ours(eventTakeId: String): Boolean = eventTakeId == takeId
+
+        override fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishLive(forced, routeKind, routeReason, liveAfterMs) } }
+        }
+
+        override fun onTick(takeId: String, elapsedMs: Long) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); onTakeTick(elapsedMs) } }
+        }
+
+        override fun onSilenceStatus(takeId: String, status: Int) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishSilenceNoticeIfNeeded(status) } }
+        }
+
+        override fun onEnded(ending: TakeEnding) {
+            host.postToMain { if (ours(ending.takeId)) { rearmSilenceBound(); onTakeEnded(ending) } }
+        }
+    }
+
+    private fun armSilenceBound() {
+        if (silenceBoundArmed.compareAndSet(false, true)) host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
+    }
+
+    /** Main thread. Every event from the capture process pushes the bound out; nothing else does. */
+    private fun rearmSilenceBound() {
+        if (!silenceBoundArmed.get()) return
+        host.cancelMainDelayed(silenceBound)
+        host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
+    }
+
+    /**
+     * Main thread, when the take's binding is released and only then (a command may still be outstanding).
+     * On an ordinary ending the bound is still pending (the process answered every second and the take
+     * ended by its own ending), so the cancel is the ordinary path, not a defence; both runnables are
+     * cancelled because neither may outlive the binding they watch.
+     */
+    private fun disarmSilenceBound() {
+        silenceBoundArmed.set(false)
+        host.cancelMainDelayed(silenceBound)
+        host.cancelMainDelayed(liveDeadline)
+    }
+
+    /**
+     * Main thread. The capture process published nothing for [TAKE_SILENT_BOUND_MS]: frozen, wedged or
+     * gone without its ServiceConnection noticing. The hang becomes a reported ending (#115): a take still
+     * waiting for sound or recording ends as unresponsive; a stop or a cancel still waiting for the file
+     * ends as the close that never came; a take past its ending has its words already and only the
+     * binding is released. The capture process is never called again: `announceError` stops the service
+     * by intent and `finishSession` unbinds, and a frozen process cannot run either, so the next take
+     * waits for the OS or the user to kill it.
+     */
+    private fun onCaptureSilent() {
+        if (!silenceBoundArmed.compareAndSet(true, false)) return
+        log.error("Capture process silent for ${TAKE_SILENT_BOUND_MS}ms; ending the take")
+        when (state.get()) {
+            SessionState.STARTING -> failWhileStarting(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
+            SessionState.RECORDING -> handleServiceFailure(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
+            SessionState.PROCESSING -> if (!endingConsumed.get()) {
+                discardDraft()
+                showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
             }
-            val capturing = runCatching { service.isCapturing() }.getOrDefault(false)
-            if (!capturing) {
-                val failure = runCatching { service.lastStartFailure() }.getOrDefault(AudioCaptureService.START_FAILURE_OTHER)
-                val reason = if (failure == AudioCaptureService.START_FAILURE_EARBUDS) {
-                    TerminalReason.CAPTURE_START_EARBUDS_REFUSED
-                } else {
-                    TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
+            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
+                pendingCancel = null
+                if (arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) {
+                    pipeline.stopAudioService()
+                    discardDraft()
+                    endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
+                }
+            }
+            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
+        }
+    }
+
+    /** Main thread. The STARTING bound passed with no live event. */
+    private fun onLiveDeadline() {
+        if (state.get() != SessionState.STARTING) return
+        if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
+        log.error("The route never went live within $LIVE_WAIT_BOUND_MS ms")
+        commandCapture("stop at the live deadline") { it.stopCapture() }
+    }
+
+    /** Main thread. A heartbeat: the timer while RECORDING; liveness in every state. */
+    private fun onTakeTick(elapsedMs: Long) {
+        if (state.get() != SessionState.RECORDING) return
+        val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
+        if (second != lastElapsedSecond) {
+            lastElapsedSecond = second
+            surface.updateElapsed(second)
+        }
+        // Below the timer, and a limb: a failure here must not cost the take.
+        runCatching { publishDurationWarningIfNeeded(elapsedMs) }
+    }
+
+    /**
+     * Main thread. The take is over on the capture side and the file is closed (#115). Consumed once:
+     * the capture process publishes one ending per take, and a start refused as busy publishes for the
+     * refused start, which this take (still STARTING) reads as its own ending before live.
+     */
+    private fun onTakeEnded(ending: TakeEnding) {
+        if (!endingConsumed.compareAndSet(false, true)) return
+        val takeFacts = facts
+        captureDeviceLabel = ending.effectiveInputDevice
+        takePeakAmplitude = ending.takePeakAmplitude
+        takeFacts.peakAmplitude = ending.takePeakAmplitude
+        takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(ending.silenceStatus) }.getOrNull()
+        when (state.get()) {
+            SessionState.STARTING -> {
+                // Ended before live: the earbud deadline, a start refused before capture began (no
+                // reason and no file: the one start failure with its own sentence is "nothing can record
+                // at all", macOS copy), or a capture that started and ended before the route went live.
+                val reason = when {
+                    ending.startFailure == AudioCaptureService.START_FAILURE_EARBUDS -> TerminalReason.CAPTURE_START_EARBUDS_REFUSED
+                    ending.terminalReason == AudioCaptureService.TERMINAL_REASON_NONE && ending.audioFilePath == null ->
+                        TakeNotices.startFailureReason(ending.startFailure)
+                    else -> TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
                 }
-                // Claim first: a cancel that stopped capture between the two checks owns the take, and
-                // its stop must not read as a microphone failure.
                 if (!failWhileStarting(reason)) return
-                log.warn("Capture ended while waiting for the route to go live (failure=$failure)")
-                runCatching { service.waitForFileReady(2_000L) }
-                pipeline.stopAudioService()
-                return
+                log.warn("Capture ended while waiting for the route to go live (failure=${ending.startFailure})")
             }
-            if (host.elapsedRealtimeMs() - startedAt > LIVE_WAIT_BOUND_MS) {
-                if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
-                log.error("The route never went live within $LIVE_WAIT_BOUND_MS ms")
-                runCatching { service.stopCapture() }
-                runCatching { service.waitForFileReady(2_000L) }
-                pipeline.stopAudioService()
-                return
+            SessionState.RECORDING -> {
+                // The ending as a fact for the take's row, stamped here at the ONE place it is
+                // classified; a stop the owner itself requested never reaches this branch and is
+                // stamped `manual` at the stop (issue #176).
+                takeFacts.captureTerminal = TakeFacts.captureEndingToken(ending.terminalReason)
+                // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
+                // know cannot fall through into an ordinary transcription.
+                when (CaptureEnding.fromAidl(ending.terminalReason)) {
+                    // StillRunning belongs HERE. Capture that stopped without publishing a
+                    // reason has no successful ending to report, and the type says so:
+                    // StillRunning.transcribes is false. Grouping it with the successes would
+                    // send partial audio on as though it were a finished take.
+                    CaptureEnding.Failure -> {
+                        log.error("Audio capture ended without a successful reason")
+                        discardDraft()
+                        showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
+                    }
+
+                    CaptureEnding.StillRunning -> {
+                        log.error("Audio capture stopped without publishing a reason")
+                        discardDraft()
+                        showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
+                    }
+
+                    // The words up to the cap are kept and transcribed. What the user needs
+                    // to be told is why the recording ended without them asking, because a take
+                    // that stops on its own with no sentence reads as a fault.
+                    // Transcribe FIRST, then say why. The words are the thing that must
+                    // survive; the sentence explaining the ending is a limb, and putting it
+                    // ahead of the transition would let a failure in it cost the take.
+                    CaptureEnding.MaxDuration -> {
+                        if (enterProcessing()) {
+                            continueAfterEnding(ending)
+                            log.log("Take ended at the duration cap")
+                            sayAfterRecording(DURATION_REACHED_NOTICE)
+                        }
+                    }
+
+                    CaptureEnding.Manual,
+                    CaptureEnding.Silence -> if (enterProcessing()) continueAfterEnding(ending)
+                }
+            }
+            SessionState.PROCESSING -> continueAfterEnding(ending)
+            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
+                pendingCancel = null
+                val cancelled = pendingCancelReason
+                scope.launch {
+                    if (!arbiter.commit(cancel, cancelled)) return@launch
+                    discardDraft()
+                    deleteCapturedAudio(ending.audioFilePath)
+                    finishTakeOrStop()
+                    finishSession()
+                }
             }
-            Thread.sleep(LIVE_POLL_MS)
+            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
         }
     }
 
     /** The one STARTING→RECORDING publication: main thread, under [publishLock]. */
-    private fun publishLive(forced: Boolean) {
+    private fun publishLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
         check(host.onMainThread()) { "publishLive runs on the main thread" }
         synchronized(publishLock) {
             if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
-                pipeline.capture?.let { runCatching { it.stopCapture() } }
+                commandCapture("stop a take that went live after its ending") { it.stopCapture() }
                 return
             }
+            host.cancelMainDelayed(liveDeadline)
             recordingStartedAtMs = System.currentTimeMillis()
             val takeFacts = facts
-            pipeline.capture?.let { service ->
-                takeFacts.routeKind = runCatching { InputRouteKind.fromCode(service.inputRouteKind()) }.getOrNull()
-                takeFacts.routeReason = runCatching { InputRouteReason.fromCode(service.inputRouteReason()) }.getOrNull()
-                takeFacts.liveAfterMs = runCatching { service.liveAfterMs() }.getOrNull()
-            }
+            takeFacts.routeKind = runCatching { InputRouteKind.fromCode(routeKind) }.getOrNull()
+            takeFacts.routeReason = runCatching { InputRouteReason.fromCode(routeReason) }.getOrNull()
+            takeFacts.liveAfterMs = liveAfterMs
             takeFacts.liveState = if (forced) "forced" else "ready"
             Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
             Telemetry.breadcrumb(
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
             host.vibrate(HapticCue.SESSION_TRANSITION)
-            log.log("Recording started (live after ${runCatching { pipeline.capture?.liveAfterMs() }.getOrNull() ?: -1} ms, forced=$forced)")
+            log.log("Recording started (live after $liveAfterMs ms, forced=$forced)")
             if (forced) {
                 // Said first, so neither the tip nor a pick-missing line can take the slot from it.
                 forcedNoticeShown = true
                 sayWhileRecording(CaptureNotices.EARBUDS_SILENT)
             }
-            startPolling()
+            // Once, at live, from the pushed route kind (#115): the tip needs nothing more.
+            publishMicrophoneNoticesIfNeeded(routeKind)
+            // After show() stamped the take's serial, which the picture is judged against.
+            listenForPicture()
             if (stopAfterRecording) {
                 // The bubble's hold was already released. Consumed here, at the one transition the
                 // early release waits for, so a short hold keeps its words instead of losing them.
@@ -613,75 +832,6 @@ internal class DictationSessionCoordinator(
         }
     }
 
-    private fun startPolling() {
-        Thread({
-            while (state.get() == SessionState.RECORDING) {
-                try {
-                    val service = pipeline.capture ?: break
-                    val elapsedMs = service.elapsedMs()
-                    val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
-                    if (second != lastElapsedSecond) {
-                        lastElapsedSecond = second
-                        surface.updateElapsed(second)
-                    }
-                    publishSilenceNoticeIfNeeded(service)
-                    publishMicrophoneNoticesIfNeeded(service)
-                    if (!service.isCapturing() && state.get() == SessionState.RECORDING) {
-                        val ending = service.terminalReason()
-                        // The ending as a fact for the take's row, stamped here at the ONE place it is
-                        // classified; a stop the owner itself requested never reaches this branch and is
-                        // stamped `manual` at the stop (issue #176).
-                        facts.captureTerminal = TakeFacts.captureEndingToken(ending)
-                        // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
-                        // know cannot fall through into an ordinary transcription.
-                        when (CaptureEnding.fromAidl(ending)) {
-                            // StillRunning belongs HERE. Capture that stopped without publishing a
-                            // reason has no successful ending to report, and the type says so:
-                            // StillRunning.transcribes is false. Grouping it with the successes would
-                            // send partial audio on as though it were a finished take.
-                            CaptureEnding.Failure -> {
-                                log.error("Audio capture ended without a successful reason")
-                                discardDraft()
-                                showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
-                            }
-
-                            CaptureEnding.StillRunning -> {
-                                log.error("Audio capture stopped without publishing a reason")
-                                discardDraft()
-                                showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
-                            }
-
-                            // The words up to the cap are kept and transcribed. What the user needs
-                            // to be told is why the recording ended without them asking, because a take
-                            // that stops on its own with no sentence reads as a fault.
-                            // Transcribe FIRST, then say why. The words are the thing that must
-                            // survive; the sentence explaining the ending is a limb, and putting it
-                            // ahead of the transition would let a failure in it cost the take.
-                            CaptureEnding.MaxDuration -> {
-                                stopAndTranscribe()
-                                log.log("Take ended at the duration cap")
-                                sayAfterRecording(DURATION_REACHED_NOTICE)
-                            }
-
-                            CaptureEnding.Manual,
-                            CaptureEnding.Silence -> stopAndTranscribe()
-                        }
-                        break
-                    }
-                    // Both of these sit BELOW the terminal check, and the position is the isolation.
-                    // The warning is a limb: it tells the user something useful and nothing depends on
-                    // it, so a failure in it must not carry the loop past the check that starts
-                    // transcription. Above the check, a throw here would cost the take.
-                    publishDurationWarningIfNeeded(elapsedMs)
-                } catch (_: Exception) {
-                    // A binder disconnect is handled by its ServiceConnection callback.
-                }
-                Thread.sleep(100)
-            }
-        }, "DictationPollingThread").start()
-        listenForPicture()
-    }
-
     /**
      * The recorder's live picture, PUSHED by the audio process for exactly as long as the take is open.
      *
@@ -694,16 +844,19 @@ internal class DictationSessionCoordinator(
      *
      * Take identity is the snapshot's serial, captured here after `show()` stamped it and stamped on every
      * picture; `updateBands` refuses a picture whose serial is not the visible take's, under its own lock.
-     * A picture that never arrives (a wedged audio process, #115) leaves the rail holding its last shape
-     * until the pill hides, as the timer holds its last second. `finishSession` unregisters.
+     * A picture that never arrives leaves the rail holding its last shape until the pill hides; a capture
+     * process that also stops publishing take events ends the take through the silence bound (#115). The
+     * registration dies with the binding.
      *
-     * Failing to register costs the picture only: the take and its polling thread carry on.
+     * Failing to register costs the picture only: the take carries on.
      */
     private fun listenForPicture() {
+        // The serial is read HERE, on main, right after show() stamped it; only the binder call moves to
+        // the lane (#115 review round 1, F1).
         val takeSerial = surface.currentTakeSerial()
-        runCatching {
-            pipeline.capture?.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
-        }.onFailure { log.warn("Live picture unavailable for this take: ${it.message}") }
+        commandCapture("listen for the picture") { capture ->
+            capture.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
+        }
     }
 
     /**
@@ -713,9 +866,9 @@ internal class DictationSessionCoordinator(
      * several seconds into one is an interruption for nothing. The floating recorder only exists while
      * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
      */
-    private fun publishSilenceNoticeIfNeeded(service: CaptureLink) {
+    private fun publishSilenceNoticeIfNeeded(status: Int) {
         if (!sessionPreferences.autoStopOnSilence || silenceNoticeShown) return
-        val status = runCatching { service.silenceStopStatus() }.getOrNull() ?: return
+        if (state.get() != SessionState.RECORDING) return
         if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
         silenceNoticeShown = true
         sayWhileRecording(SILENCE_UNAVAILABLE_NOTICE)
@@ -733,9 +886,8 @@ internal class DictationSessionCoordinator(
      * The tip's once-per-process allowance is spent only when the tip is actually said, so a take that
      * had to say something else leaves it for the next Bluetooth take (Codex review 5, 2026-09-17).
      */
-    private fun publishMicrophoneNoticesIfNeeded(service: CaptureLink) {
+    private fun publishMicrophoneNoticesIfNeeded(kind: Int) {
         if (silenceNoticeShown || forcedNoticeShown) return
-        val kind = runCatching { service.inputRouteKind() }.getOrNull() ?: return
         if (tipGate.shouldShow(kind, sessionPreferences.showBluetoothTips)) {
             log.log("Bluetooth tip shown")
             sayWhileRecording(CaptureNotices.BLUETOOTH_TIP)
@@ -783,51 +935,49 @@ internal class DictationSessionCoordinator(
         }
     }
 
+    /**
+     * RECORDING → PROCESSING, the user's stop. The capture process is told to stop; the transcription
+     * continues in [continueAfterEnding] when it publishes the ending with the closed file (#115). If it
+     * never does, the silence bound ends the take as the close that never came.
+     */
     private fun stopAndTranscribe() {
-        // Under publishLock: the live waiter publishes RECORDING (pill, draft) under the same lock, so a
-        // stop that follows its CAS cannot run ahead of its publication (Codex review 3, 2026-09-18).
+        if (!enterProcessing()) return
+        commandCapture("stop") { it.stopCapture() }
+    }
+
+    /** The one RECORDING → PROCESSING transition, under [publishLock] (Codex review 3, 2026-09-18). */
+    private fun enterProcessing(): Boolean {
+        // Under publishLock: live publishes RECORDING (pill, draft) under the same lock, so a stop that
+        // follows its CAS cannot run ahead of its publication.
         synchronized(publishLock) {
-            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
+            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return false
             surface.showProcessing()
         }
         host.updateSurfacePhase(DictationSurfaceState.Phase.PROCESSING)
         host.promoteToForeground(processing = true)
         host.vibrate(HapticCue.SESSION_TRANSITION)
         log.log("Stopping recording and starting transcription")
+        return true
+    }
 
-        Thread({
-            var audioReady = false
+    /**
+     * PROCESSING, with the capture process's ending in hand: the file is closed and every fact about the
+     * take is on the event (#115). Runs on the owner's scope; nothing here asks the capture process.
+     */
+    private fun continueAfterEnding(ending: TakeEnding) {
+        scope.launch {
             try {
-                pipeline.capture?.stopCapture()
-                audioReady = runCatching { pipeline.capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
-                if (!audioReady) {
-                    pipeline.stopAudioService()
-                    discardDraft()
-                    showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
-                    return@Thread
-                }
-                val audioFilePath = pipeline.capture?.audioFilePath()
+                val audioFilePath = ending.audioFilePath
                 // The duration is the audio's own length, read from the finished file NOW, before
-                // transcription deletes it: the elapsed getter is 0 once capture stops, and the wall
-                // clock counted the wait for the earbuds.
+                // transcription deletes it: the wall clock counted the wait for the earbuds.
                 recordingDurationMs = runCatching {
                     audioFilePath?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
                 }.getOrNull()?.coerceAtLeast(0L) ?: 0L
-                // Complete once the capture thread has exited (waitForFileReady above joined it): the
-                // final route was observed before the recorder stopped, and the label persists in the
-                // capture process until its next start.
-                captureDeviceLabel = runCatching { pipeline.capture?.effectiveInputDevice() }.getOrNull().orEmpty()
-                // Same moment, same reason: the capture thread has exited, so the peak is the whole take's.
-                takePeakAmplitude = runCatching { pipeline.capture?.takePeakAmplitude() }.getOrNull()
                 val takeFacts = facts
-                takeFacts.peakAmplitude = takePeakAmplitude
                 takeFacts.recordingSeconds = recordingDurationMs / 1000.0
-                // Stamped by the polling loop when capture ended on its own; otherwise this stop is the
+                // Stamped by the ending handler when capture ended on its own; otherwise this stop is the
                 // owner's own request, which the capture process reports as a manual ending.
                 if (takeFacts.captureTerminal == null) takeFacts.captureTerminal = TakeFacts.MANUAL_ENDING
-                pipeline.capture?.let { service ->
-                    takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(service.silenceStopStatus()) }.getOrNull()
-                }
                 Telemetry.journal?.advance(takeId, TakeStage.PROCESSING)
                 Telemetry.breadcrumb(
                     "take", "stopped",
@@ -835,22 +985,20 @@ internal class DictationSessionCoordinator(
                 )
                 finishTakeOrStop()
 
-                val readyDraftId = runCatching { runBlocking { draftCreation?.await() ?: 0L } }.getOrDefault(0L)
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
-                    return@Thread
+                    return@launch
                 }
                 val speechService = pipeline.speech
                 if (speechService == null) {
                     deleteCapturedAudio(audioFilePath)
                     updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                     showError(TerminalReason.ASR_NOT_READY)
-                    return@Thread
+                    return@launch
                 }
                 log.mark("asr_request")
                 val asrRequestedAtMs = host.elapsedRealtimeMs()
@@ -894,12 +1042,12 @@ internal class DictationSessionCoordinator(
                 })
             } catch (error: Exception) {
                 pipeline.stopAudioService()
-                if (audioReady) deleteCapturedAudio(runCatching { pipeline.capture?.audioFilePath() }.getOrNull())
+                deleteCapturedAudio(ending.audioFilePath)
                 log.error("Transcription failed", error)
                 updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                 showError(TerminalReason.ASR_CALLBACK_EXCEPTION)
             }
-        }, "TranscribeThread").start()
+        }
     }
 
     private fun polishAndPublish(rawText: String) {
@@ -1065,8 +1213,9 @@ internal class DictationSessionCoordinator(
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
@@ -1086,21 +1235,62 @@ internal class DictationSessionCoordinator(
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
@@ -1110,30 +1300,8 @@ internal class DictationSessionCoordinator(
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
@@ -1229,25 +1397,32 @@ internal class DictationSessionCoordinator(
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
@@ -1257,15 +1432,13 @@ internal class DictationSessionCoordinator(
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
@@ -1295,17 +1468,15 @@ internal class DictationSessionCoordinator(
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
 
@@ -1328,43 +1499,47 @@ internal class DictationSessionCoordinator(
         insertion.releasePinnedTarget()
         host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
         host.vibrate(HapticCue.SESSION_CANCELED)
-        scope.launch {
-            // A cancel before the capture process was even bound has nothing to stop: the quiet finish
-            // the old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
-            val service = pipeline.capture
-            if (service == null) {
+        // A cancel before the capture process was even bound has nothing to stop: the quiet finish the
+        // old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
+        val service = pipeline.capture
+        if (service == null) {
+            scope.launch {
                 if (!arbiter.commit(cancel, cancelled)) return@launch
                 discardDraft()
                 finishSession()
-                return@launch
             }
-            val ready = runCatching {
-                service.stopCapture()
-                service.waitForFileReady(2_000L)
-            }.getOrDefault(false)
-            // The outcome is known only now: a close that failed is a failure, not a cancel.
-            if (!ready) {
-                if (!arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) return@launch
-                pipeline.stopAudioService()
+            return
+        }
+        // Otherwise the capture process is told to stop and the cancel is committed when it publishes the
+        // ending with the closed file (`onTakeEnded`, #115); a close that never comes is ended by the
+        // silence bound as CAPTURE_CLOSE_UNSAFE_ON_CANCEL, a failure and not a cancel, as before.
+        if (endingConsumed.get()) {
+            // The ending already arrived (a take that ended on its own as the cancel landed): commit now.
+            scope.launch {
+                if (!arbiter.commit(cancel, cancelled)) return@launch
                 discardDraft()
-                endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
-                return@launch
+                finishTakeOrStop()
+                finishSession()
             }
-            if (!arbiter.commit(cancel, cancelled)) return@launch
-            discardDraft()
-            deleteCapturedAudio(runCatching { service.audioFilePath() }.getOrNull())
-            finishTakeOrStop()
-            finishSession()
+            return
         }
+        pendingCancelReason = cancelled
+        pendingCancel = cancel
+        commandCapture("stop for a cancel") { it.stopCapture() }
     }
 
     /**
      * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
-     * lifetime and the unbind must not stop it), otherwise stop it as before.
+     * lifetime and the unbind must not stop it), otherwise stop it as before. Issued on the lane and never
+     * awaited (#115 review round 1, F3): a process that answered its ending and then wedged must not hold
+     * the transcription; a call that fails or is never issued stops the service by intent.
      */
     private fun finishTakeOrStop() {
-        val held = runCatching { pipeline.capture?.finishTake() == true }.getOrDefault(false)
-        if (!held) pipeline.stopAudioService()
+        val issued = commandCapture("finishTake") { capture ->
+            val held = runCatching { capture.finishTake() }.getOrDefault(false)
+            if (!held) pipeline.stopAudioService()
+        }
+        if (!issued) pipeline.stopAudioService()
     }
 
     /**
@@ -1467,13 +1642,14 @@ internal class DictationSessionCoordinator(
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
-                // The picture subscription goes with the binding; a failure here costs nothing.
-                runCatching { pipeline.capture?.stopListeningForSpectrum() }
+                // Both subscriptions go with the binding; nothing is called on the capture process here,
+                // which may be the unresponsive process this take just ended over (#115).
+                disarmSilenceBound()
                 pipeline.unbind()
                 host.removeForegroundAndDismiss()
                 // IDLE is published at the last moment this instance can still refuse a start: the
@@ -1501,17 +1677,18 @@ internal class DictationSessionCoordinator(
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
@@ -1539,34 +1716,33 @@ internal class DictationSessionCoordinator(
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
@@ -1574,71 +1750,58 @@ internal class DictationSessionCoordinator(
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
+        // Both delayed callbacks go now, on main, before any blocking cleanup: a bound firing into a
+        // destroyed owner would act on a Service that is gone (#115 review round 1, F4).
+        disarmSilenceBound()
         cancelOpenPolishRequest()
         val sessionWasOpen = destroyedState == SessionState.STARTING ||
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
-            // Captures the link and the controller only: the Service is dead once `onDestroy` returns,
-            // and this thread outlives it (`PipelineBindings` binds through the application context).
-            val capture = pipeline.capture
-            val pipeline = pipeline
-            Thread({
-                runCatching { capture?.stopCapture() }
-                val ready = runCatching { capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
-                if (ready) deleteCapturedAudio(runCatching { capture?.audioFilePath() }.getOrNull())
-                pipeline.stopAudioService()
-                pipeline.postUnbindToMain(::cancelOpenPolishRequest)
-            }, "DestroyedSessionCleanup").start()
-        } else {
-            if (sessionWasOpen) pipeline.stopAudioService()
-            cancelOpenPolishRequest()
-            pipeline.unbind()
+            // Down the lane, like every other call into the capture process (#115): the lane's daemon
+            // thread outlives the Service, and the link it captured stays valid after the unbind below.
+            // The file is the capture process's one cache file and the next take overwrites it; nothing
+            // waits for it here.
+            commandCapture("stop at destroy") { it.stopCapture() }
         }
+        // The service is stopped by intent whether or not that stop is ever delivered (a wedged process
+        // gets neither, and the OS or the user ends it); the bindings go now, on main, with nothing waited
+        // for.
+        if (sessionWasOpen) pipeline.stopAudioService()
+        cancelOpenPolishRequest()
+        pipeline.unbind()
+        // No further command is accepted; the stop above, if queued, still runs on the lane's own thread.
+        captureCommands.shutdown()
     }
 }
 

exec
/bin/zsh -lc "git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1,700p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1578ms:
2026-09-21 20:26:18.191 xcodebuild[68138:78219682]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:18.385 xcodebuild[68138:78219681] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-6FOGKWfA' (errno=Operation not permitted)
2026-09-21 20:26:18.842 xcodebuild[68140:78219708]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:18.952 xcodebuild[68140:78219707] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
index ba13f66..e97def7 100644
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
@@ -41,20 +42,18 @@ import com.envi.wispr.telemetry.Telemetry
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
 import java.util.UUID
+import java.util.concurrent.ExecutorService
+import java.util.concurrent.Executors
 import java.util.concurrent.atomic.AtomicBoolean
 import java.util.concurrent.atomic.AtomicLong
 import java.util.concurrent.atomic.AtomicReference
@@ -76,11 +75,14 @@ internal class DictationSessionCoordinator(
     private val insertion: InsertionGateway,
     private val log: SessionLog,
     private val preferences: SessionPreferencesSource,
+    /** Every per-take History write goes through here, in enqueue order, on the application's worker (#115). */
+    private val historyWrites: HistoryWriteQueue,
+    /** For the start-up recovery ONLY, on the session scope: a stalled recovery must not sit ahead of a take's writes on the queue. */
     private val transcripts: TranscriptRepository,
     private val languageDetector: LanguageDetector,
     private val loadPolicy: suspend () -> PolishPolicy,
     private val pipeline: PipelineController,
-    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is joined on destroy. */
+    /** The one session scope; the Service's `SupervisorJob() + Dispatchers.IO`. Its job is cancelled on destroy, never joined (#115). */
     private val scope: CoroutineScope,
     /** `Dispatchers.Main.immediate` in production; a JVM test passes its single owner-thread dispatcher. */
     private val mainDispatcher: CoroutineDispatcher,
@@ -124,16 +126,22 @@ internal class DictationSessionCoordinator(
          * and Room deliver in milliseconds; a hung store cannot hold the microphone longer than this.
          */
         const val SETTINGS_ANSWER_BOUND_MS = 2_000L
+
+        /**
+         * How long the capture process may stay SILENT before the take treats it as unresponsive (#115).
+         * The capture loop heartbeats once a second from its first read; three missed beats is a process
+         * that is frozen or wedged, not slow (the emulator's measured gaps are recorded in the #115 plan).
+         * Armed before the take's listener is registered and disarmed when the binding is released, so it
+         * also covers a command outstanding after the ending; no timer exists outside a take.
+         */
+        const val TAKE_SILENT_BOUND_MS = 3_000L
     }
 
     private enum class SessionState { IDLE, STARTING, RECORDING, PROCESSING, CANCELLING, FINISHING, ERROR }
 
-    /** How often the live waiter asks the capture process. */
-    private val LIVE_POLL_MS = 20L
-
     /**
      * The STARTING bound: two live deadlines (one reset) plus a second, after which a take that never
-     * went live fails rather than spins.
+     * went live fails rather than spins. A main-thread timer since #115; the live waiter thread is gone.
      */
     private val LIVE_WAIT_BOUND_MS = 2 * LiveGate.DEADLINE_MS + 1_000L
 
@@ -182,7 +190,6 @@ internal class DictationSessionCoordinator(
     private val draftId = AtomicLong(0L)
     /** The injected scope's job, read once; `destroy` cancels and joins exactly this. */
     private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }
-    private val pendingHistoryUpdates = java.util.Collections.synchronizedList(mutableListOf<Job>())
 
     private var rawTranscript = ""
     // What the START of this dictation saw when it tried to pin an editor. Read at the end,
@@ -202,8 +209,37 @@ internal class DictationSessionCoordinator(
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
+    /**
+     * The take's cancel, reserved under [publishLock] and committed when the capture process publishes
+     * the ending (#115): a cancel no longer waits for the file itself.
+     */
+    @Volatile private var pendingCancel: TakeArbiter.Token? = null
+    @Volatile private var pendingCancelReason: TerminalReason = TerminalReason.CANCELLED_RECORDING
+    /** Set once the ending has been consumed for this take, so a duplicate or a late event changes nothing. */
+    private val endingConsumed = AtomicBoolean(false)
+    /** Whether the silence bound is armed; the runnable is re-posted on every event from the capture process. */
+    private val silenceBoundArmed = AtomicBoolean(false)
+    private val silenceBound = Runnable { onCaptureSilent() }
+    private val liveDeadline = Runnable { onLiveDeadline() }
+    /**
+     * Every synchronous call INTO the capture process runs here, one at a time, in the order issued, and
+     * never on the main thread (#115 review round 1, F1): a call into a process that has stopped answering
+     * has no timeout, and on main it would park the very thread the silence bound fires on. One lane, not
+     * the shared scope, so a stop can never overtake the start it belongs to. A lane parked in a wedged
+     * process stays parked; the instance ends through the bound and the next take has its own lane.
+     */
+    private val captureCommands: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
+        Thread(runnable, "CaptureCommands").apply { isDaemon = true }
+    }
     @Volatile private var sessionPreferences = SessionPreferences()
     @Volatile private var silenceNoticeShown = false
     /** The take proceeded on earbuds that sent nothing; said once, before any other microphone line. */
@@ -216,8 +252,8 @@ internal class DictationSessionCoordinator(
      */
     private val publishLock = Any()
     /**
-     * What captured the take, read ONCE at stop after `waitForFileReady`, when the capture thread has
-     * exited and the record is complete. Empty means unknown and is stored as such (#26).
+     * What captured the take, carried on the ending the capture process publishes once its thread has
+     * exited and the record is complete (#115). Empty means unknown and is stored as such (#26).
      */
     @Volatile private var captureDeviceLabel = ""
     /** One warning per take, latched so the last minute is not announced ten times a second. */
@@ -228,6 +264,9 @@ internal class DictationSessionCoordinator(
 
     /** The Service's `onCreate` work that is the session's: stale-row recovery and the preference collectors. */
     fun onCreated() {
+        // On its own scope, never on the per-take queue (#115 review): a recovery stalled on the disk
+        // would otherwise sit ahead of every write of the take that follows. It closes rows an EARLIER
+        // process left open; the new take's rows are not among them.
         scope.launch {
             runCatching { transcripts.recoverStaleOpenRows(System.currentTimeMillis()) }
                 .onSuccess { recovered -> Telemetry.insertionsRecovered(recovered.readyRowIds) }
@@ -361,6 +400,8 @@ internal class DictationSessionCoordinator(
         rawTranscript = ""
         recordingDurationMs = 0L
         lastElapsedSecond = -1
+        endingConsumed.set(false)
+        pendingCancel = null
         scope.launch {
             // The readers are limbs (#193): a failed or silent read never ends the take. The start carries
             // both outcomes and the values that came with them, taken by one atomic read each, and the
@@ -461,38 +502,52 @@ internal class DictationSessionCoordinator(
             durationWarningShown = false
             forcedNoticeShown = false
             captureDeviceLabel = ""
-            val started = runCatching {
-                // The frozen snapshot, never the live source: a settings emission after the take's answer
-                // belongs to the next take (#193).
-                pipeline.capture?.startCaptureForTake(
-                    sessionPreferences.autoStopOnSilence,
-                    sessionPreferences.silencePauseSeconds,
-                    sessionPreferences.inputDevicePick,
-                    sessionPreferences.keepEarbudsReady,
-                    takeId,
-                )
-            }.getOrNull()
-            if (started != true) {
-                // The one start failure with its own sentence is "nothing can record at all" (macOS copy).
-                val failure = runCatching { pipeline.capture?.lastStartFailure() }.getOrNull()
-                    ?: AudioCaptureService.START_FAILURE_OTHER
-                pipeline.stopAudioService()
-                showError(TakeNotices.startFailureReason(failure))
-                return
+            // The bound is armed and the listener registered BEFORE the start command (#115): a start that
+            // never returns, a registration that hangs, and an event that precedes registration are all
+            // covered. The take is STARTING until the capture process publishes live: the lips spin, no
+            // pill, no timer, nothing written.
+            armSilenceBound()
+            host.postToMainDelayed(LIVE_WAIT_BOUND_MS, liveDeadline)
+            // The frozen snapshot, never the live source: a settings emission after the take's answer
+            // belongs to the next take (#193).
+            val preferences = sessionPreferences
+            val id = takeId
+            captureStarted = commandCapture("start") { capture ->
+                capture.listenForTake(takeListener)
+                // A registration that wedged and then returned: the silence bound may already have
+                // ended this take and released the binding, and a start now would record for nobody
+                // (review round 2, F1). Checked on the lane, before the start and again after it.
+                if (state.get() != SessionState.STARTING || takeId != id) return@commandCapture
+                val started = try {
+                    capture.startCaptureForTake(
+                        preferences.autoStopOnSilence,
+                        preferences.silencePauseSeconds,
+                        preferences.inputDevicePick,
+                        preferences.keepEarbudsReady,
+                        id,
+                    )
+                } catch (error: Exception) {
+                    // A binder that threw: the process is gone or broken. Its ServiceConnection or the
+                    // silence bound would end the take too; this is the same ending, sooner.
+                    log.error("Failed to start recording", error)
+                    host.postToMain { showError(TerminalReason.START_EXCEPTION) }
+                    return@commandCapture
+                }
+                if (!started) {
+                    // Every refused start publishes its own ending with the failure code (the #115 plan's
+                    // table, one publisher per exit). Nothing is read here.
+                    log.warn("Capture start refused; the ending event carries why")
+                } else if (state.get() != SessionState.STARTING || takeId != id) {
+                    // The take ended while the start was in flight: no owner is listening for it.
+                    log.warn("Capture started for a take that already ended; stopping it")
+                    runCatching { capture.stopCapture() }
+                    pipeline.stopAudioService()
+                }
             }
-            captureStarted = true
-            // The take is STARTING until the chosen route delivers sound: the lips spin, no pill, no
-            // timer, nothing written. The waiter performs the RECORDING transition when the capture
-            // process reports live (issue #26, 2026-09-18).
-            Thread({ waitForLive() }, "LiveWaiter").start()
         } catch (error: Exception) {
             if (captureStarted) {
-                val capture = pipeline.capture
-                Thread({
-                    runCatching { capture?.stopCapture() }
-                    runCatching { capture?.waitForFileReady(2_000L) }
-                    pipeline.stopAudioService()
-                }, "StartCaptureFailureCleanup").start()
+                commandCapture("stop after a failed start") { it.stopCapture() }
+                pipeline.stopAudioService()
             }
             log.error("Failed to start recording", error)
             showError(TerminalReason.START_EXCEPTION)
@@ -500,109 +555,273 @@ internal class DictationSessionCoordinator(
     }
 
     /**
-     * Polls the capture process until the take is live, then publishes RECORDING. Four ways out without
-     * publishing: the state left STARTING (a cancel or teardown won), the binder is gone, capture ended
-     * during the wait (a deadline failure or a capture error), or the STARTING bound passed. None of
-     * them can publish a pill for an ended take, and none waits forever.
+     * Issue one call into the capture process on [captureCommands]; returns whether it was issued (the
+     * binding is present and the lane accepts work). The block's own failure is logged and costs nothing
+     * else: the take's ending comes from the process's events or from the silence bound, never from here.
      */
-    private fun waitForLive() {
-        val startedAt = host.elapsedRealtimeMs()
-        while (true) {
-            if (state.get() != SessionState.STARTING) return
-            val service = pipeline.capture ?: return // onServiceDisconnected ends the take for STARTING too.
-            val live = try {
-                service.liveState()
-            } catch (_: Exception) {
-                return // A dead binder: its ServiceConnection callback ends the take.
+    private fun commandCapture(what: String, block: (CaptureLink) -> Unit): Boolean {
+        val capture = pipeline.capture ?: return false
+        return runCatching {
+            captureCommands.execute {
+                runCatching { block(capture) }.onFailure { log.warn("Capture command failed ($what): ${it.javaClass.simpleName}") }
             }
-            if (live != AudioCaptureService.LIVE_WAITING) {
-                // Published on the MAIN thread, where every command is dispatched and where the
-                // bubble's early release sets its flag: the old start published there too, so a stop,
-                // cancel or release can never read STARTING and then act against a take this thread
-                // published in between (Codex review 4, 2026-09-18).
-                val forced = live == AudioCaptureService.LIVE_FORCED
-                host.postToMain { publishLive(forced) }
-                return
+        }.onFailure { log.warn("Capture command not issued ($what): ${it.javaClass.simpleName}") }.isSuccess
+    }
+
+    /**
+     * The take's events, each posted to the main thread and handled there in delivery order (#115). An
+     * event from another take (the previous take's ending, queued in the service-scoped publisher before
+     * this owner registered) is discarded before it can re-arm the bound or touch any state.
+     */
+    private val takeListener = object : TakeListener {
+        private fun ours(eventTakeId: String): Boolean = eventTakeId == takeId
+
+        override fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishLive(forced, routeKind, routeReason, liveAfterMs) } }
+        }
+
+        override fun onTick(takeId: String, elapsedMs: Long) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); onTakeTick(elapsedMs) } }
+        }
+
+        override fun onSilenceStatus(takeId: String, status: Int) {
+            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishSilenceNoticeIfNeeded(status) } }
+        }
+
+        override fun onEnded(ending: TakeEnding) {
+            host.postToMain { if (ours(ending.takeId)) { rearmSilenceBound(); onTakeEnded(ending) } }
+        }
+    }
+
+    private fun armSilenceBound() {
+        if (silenceBoundArmed.compareAndSet(false, true)) host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
+    }
+
+    /** Main thread. Every event from the capture process pushes the bound out; nothing else does. */
+    private fun rearmSilenceBound() {
+        if (!silenceBoundArmed.get()) return
+        host.cancelMainDelayed(silenceBound)
+        host.postToMainDelayed(TAKE_SILENT_BOUND_MS, silenceBound)
+    }
+
+    /**
+     * Main thread, when the take's binding is released and only then (a command may still be outstanding).
+     * On an ordinary ending the bound is still pending (the process answered every second and the take
+     * ended by its own ending), so the cancel is the ordinary path, not a defence; both runnables are
+     * cancelled because neither may outlive the binding they watch.
+     */
+    private fun disarmSilenceBound() {
+        silenceBoundArmed.set(false)
+        host.cancelMainDelayed(silenceBound)
+        host.cancelMainDelayed(liveDeadline)
+    }
+
+    /**
+     * Main thread. The capture process published nothing for [TAKE_SILENT_BOUND_MS]: frozen, wedged or
+     * gone without its ServiceConnection noticing. The hang becomes a reported ending (#115): a take still
+     * waiting for sound or recording ends as unresponsive; a stop or a cancel still waiting for the file
+     * ends as the close that never came; a take past its ending has its words already and only the
+     * binding is released. The capture process is never called again: `announceError` stops the service
+     * by intent and `finishSession` unbinds, and a frozen process cannot run either, so the next take
+     * waits for the OS or the user to kill it.
+     */
+    private fun onCaptureSilent() {
+        if (!silenceBoundArmed.compareAndSet(true, false)) return
+        log.error("Capture process silent for ${TAKE_SILENT_BOUND_MS}ms; ending the take")
+        when (state.get()) {
+            SessionState.STARTING -> failWhileStarting(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
+            SessionState.RECORDING -> handleServiceFailure(TerminalReason.AUDIO_PROCESS_UNRESPONSIVE)
+            SessionState.PROCESSING -> if (!endingConsumed.get()) {
+                discardDraft()
+                showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
             }
-            val capturing = runCatching { service.isCapturing() }.getOrDefault(false)
-            if (!capturing) {
-                val failure = runCatching { service.lastStartFailure() }.getOrDefault(AudioCaptureService.START_FAILURE_OTHER)
-                val reason = if (failure == AudioCaptureService.START_FAILURE_EARBUDS) {
-                    TerminalReason.CAPTURE_START_EARBUDS_REFUSED
-                } else {
-                    TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
+            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
+                pendingCancel = null
+                if (arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) {
+                    pipeline.stopAudioService()
+                    discardDraft()
+                    endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
+                }
+            }
+            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
+        }
+    }
+
+    /** Main thread. The STARTING bound passed with no live event. */
+    private fun onLiveDeadline() {
+        if (state.get() != SessionState.STARTING) return
+        if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
+        log.error("The route never went live within $LIVE_WAIT_BOUND_MS ms")
+        commandCapture("stop at the live deadline") { it.stopCapture() }
+    }
+
+    /** Main thread. A heartbeat: the timer while RECORDING; liveness in every state. */
+    private fun onTakeTick(elapsedMs: Long) {
+        if (state.get() != SessionState.RECORDING) return
+        val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
+        if (second != lastElapsedSecond) {
+            lastElapsedSecond = second
+            surface.updateElapsed(second)
+        }
+        // Below the timer, and a limb: a failure here must not cost the take.
+        runCatching { publishDurationWarningIfNeeded(elapsedMs) }
+    }
+
+    /**
+     * Main thread. The take is over on the capture side and the file is closed (#115). Consumed once:
+     * the capture process publishes one ending per take, and a start refused as busy publishes for the
+     * refused start, which this take (still STARTING) reads as its own ending before live.
+     */
+    private fun onTakeEnded(ending: TakeEnding) {
+        if (!endingConsumed.compareAndSet(false, true)) return
+        val takeFacts = facts
+        captureDeviceLabel = ending.effectiveInputDevice
+        takePeakAmplitude = ending.takePeakAmplitude
+        takeFacts.peakAmplitude = ending.takePeakAmplitude
+        takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(ending.silenceStatus) }.getOrNull()
+        when (state.get()) {
+            SessionState.STARTING -> {
+                // Ended before live: the earbud deadline, a start refused before capture began (no
+                // reason and no file: the one start failure with its own sentence is "nothing can record
+                // at all", macOS copy), or a capture that started and ended before the route went live.
+                val reason = when {
+                    ending.startFailure == AudioCaptureService.START_FAILURE_EARBUDS -> TerminalReason.CAPTURE_START_EARBUDS_REFUSED
+                    ending.terminalReason == AudioCaptureService.TERMINAL_REASON_NONE && ending.audioFilePath == null ->
+                        TakeNotices.startFailureReason(ending.startFailure)
+                    else -> TerminalReason.CAPTURE_ENDED_BEFORE_LIVE
                 }
-                // Claim first: a cancel that stopped capture between the two checks owns the take, and
-                // its stop must not read as a microphone failure.
                 if (!failWhileStarting(reason)) return
-                log.warn("Capture ended while waiting for the route to go live (failure=$failure)")
-                runCatching { service.waitForFileReady(2_000L) }
-                pipeline.stopAudioService()
-                return
+                log.warn("Capture ended while waiting for the route to go live (failure=${ending.startFailure})")
             }
-            if (host.elapsedRealtimeMs() - startedAt > LIVE_WAIT_BOUND_MS) {
-                if (!failWhileStarting(TerminalReason.LIVE_WAIT_DEADLINE)) return
-                log.error("The route never went live within $LIVE_WAIT_BOUND_MS ms")
-                runCatching { service.stopCapture() }
-                runCatching { service.waitForFileReady(2_000L) }
-                pipeline.stopAudioService()
-                return
+            SessionState.RECORDING -> {
+                // The ending as a fact for the take's row, stamped here at the ONE place it is
+                // classified; a stop the owner itself requested never reaches this branch and is
+                // stamped `manual` at the stop (issue #176).
+                takeFacts.captureTerminal = TakeFacts.captureEndingToken(ending.terminalReason)
+                // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
+                // know cannot fall through into an ordinary transcription.
+                when (CaptureEnding.fromAidl(ending.terminalReason)) {
+                    // StillRunning belongs HERE. Capture that stopped without publishing a
+                    // reason has no successful ending to report, and the type says so:
+                    // StillRunning.transcribes is false. Grouping it with the successes would
+                    // send partial audio on as though it were a finished take.
+                    CaptureEnding.Failure -> {
+                        log.error("Audio capture ended without a successful reason")
+                        discardDraft()
+                        showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
+                    }
+
+                    CaptureEnding.StillRunning -> {
+                        log.error("Audio capture stopped without publishing a reason")
+                        discardDraft()
+                        showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
+                    }
+
+                    // The words up to the cap are kept and transcribed. What the user needs
+                    // to be told is why the recording ended without them asking, because a take
+                    // that stops on its own with no sentence reads as a fault.
+                    // Transcribe FIRST, then say why. The words are the thing that must
+                    // survive; the sentence explaining the ending is a limb, and putting it
+                    // ahead of the transition would let a failure in it cost the take.
+                    CaptureEnding.MaxDuration -> {
+                        if (enterProcessing()) {
+                            continueAfterEnding(ending)
+                            log.log("Take ended at the duration cap")
+                            sayAfterRecording(DURATION_REACHED_NOTICE)
+                        }
+                    }
+
+                    CaptureEnding.Manual,
+                    CaptureEnding.Silence -> if (enterProcessing()) continueAfterEnding(ending)
+                }
+            }
+            SessionState.PROCESSING -> continueAfterEnding(ending)
+            SessionState.CANCELLING -> pendingCancel?.let { cancel ->
+                pendingCancel = null
+                val cancelled = pendingCancelReason
+                scope.launch {
+                    if (!arbiter.commit(cancel, cancelled)) return@launch
+                    discardDraft()
+                    deleteCapturedAudio(ending.audioFilePath)
+                    finishTakeOrStop()
+                    finishSession()
+                }
             }
-            Thread.sleep(LIVE_POLL_MS)
+            SessionState.IDLE, SessionState.FINISHING, SessionState.ERROR -> Unit
         }
     }
 
     /** The one STARTING→RECORDING publication: main thread, under [publishLock]. */
-    private fun publishLive(forced: Boolean) {
+    private fun publishLive(forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
         check(host.onMainThread()) { "publishLive runs on the main thread" }
         synchronized(publishLock) {
             if (!state.compareAndSet(SessionState.STARTING, SessionState.RECORDING)) {
-                pipeline.capture?.let { runCatching { it.stopCapture() } }
+                commandCapture("stop a take that went live after its ending") { it.stopCapture() }
                 return
             }
+            host.cancelMainDelayed(liveDeadline)
             recordingStartedAtMs = System.currentTimeMillis()
             val takeFacts = facts
-            pipeline.capture?.let { service ->
-                takeFacts.routeKind = runCatching { InputRouteKind.fromCode(service.inputRouteKind()) }.getOrNull()
-                takeFacts.routeReason = runCatching { InputRouteReason.fromCode(service.inputRouteReason()) }.getOrNull()
-                takeFacts.liveAfterMs = runCatching { service.liveAfterMs() }.getOrNull()
-            }
+            takeFacts.routeKind = runCatching { InputRouteKind.fromCode(routeKind) }.getOrNull()
+            takeFacts.routeReason = runCatching { InputRouteReason.fromCode(routeReason) }.getOrNull()
+            takeFacts.liveAfterMs = liveAfterMs
             takeFacts.liveState = if (forced) "forced" else "ready"
             Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
             Telemetry.breadcrumb(
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
             host.vibrate(HapticCue.SESSION_TRANSITION)
-            log.log("Recording started (live after ${runCatching { pipeline.capture?.liveAfterMs() }.getOrNull() ?: -1} ms, forced=$forced)")
+            log.log("Recording started (live after $liveAfterMs ms, forced=$forced)")
             if (forced) {
                 // Said first, so neither the tip nor a pick-missing line can take the slot from it.
                 forcedNoticeShown = true
                 sayWhileRecording(CaptureNotices.EARBUDS_SILENT)
             }
-            startPolling()
+            // Once, at live, from the pushed route kind (#115): the tip needs nothing more.
+            publishMicrophoneNoticesIfNeeded(routeKind)
+            // After show() stamped the take's serial, which the picture is judged against.
+            listenForPicture()
             if (stopAfterRecording) {
                 // The bubble's hold was already released. Consumed here, at the one transition the
                 // early release waits for, so a short hold keeps its words instead of losing them.
@@ -613,75 +832,6 @@ internal class DictationSessionCoordinator(
         }
     }
 
-    private fun startPolling() {
-        Thread({
-            while (state.get() == SessionState.RECORDING) {
-                try {
-                    val service = pipeline.capture ?: break
-                    val elapsedMs = service.elapsedMs()
-                    val second = (elapsedMs / 1_000L).toInt().coerceAtLeast(0)
-                    if (second != lastElapsedSecond) {
-                        lastElapsedSecond = second
-                        surface.updateElapsed(second)
-                    }
-                    publishSilenceNoticeIfNeeded(service)
-                    publishMicrophoneNoticesIfNeeded(service)
-                    if (!service.isCapturing() && state.get() == SessionState.RECORDING) {
-                        val ending = service.terminalReason()
-                        // The ending as a fact for the take's row, stamped here at the ONE place it is
-                        // classified; a stop the owner itself requested never reaches this branch and is
-                        // stamped `manual` at the stop (issue #176).
-                        facts.captureTerminal = TakeFacts.captureEndingToken(ending)
-                        // Exhaustive over CaptureEnding with no `else`, so a reason this build does not
-                        // know cannot fall through into an ordinary transcription.
-                        when (CaptureEnding.fromAidl(ending)) {
-                            // StillRunning belongs HERE. Capture that stopped without publishing a
-                            // reason has no successful ending to report, and the type says so:
-                            // StillRunning.transcribes is false. Grouping it with the successes would
-                            // send partial audio on as though it were a finished take.
-                            CaptureEnding.Failure -> {
-                                log.error("Audio capture ended without a successful reason")
-                                discardDraft()
-                                showError(TerminalReason.CAPTURE_FAILED_MID_TAKE)
-                            }
-
-                            CaptureEnding.StillRunning -> {
-                                log.error("Audio capture stopped without publishing a reason")
-                                discardDraft()
-                                showError(TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP)
-                            }
-
-                            // The words up to the cap are kept and transcribed. What the user needs
-                            // to be told is why the recording ended without them asking, because a take
-                            // that stops on its own with no sentence reads as a fault.
-                            // Transcribe FIRST, then say why. The words are the thing that must
-                            // survive; the sentence explaining the ending is a limb, and putting it
-                            // ahead of the transition would let a failure in it cost the take.
-                            CaptureEnding.MaxDuration -> {
-                                stopAndTranscribe()
-                                log.log("Take ended at the duration cap")
-                                sayAfterRecording(DURATION_REACHED_NOTICE)
-                            }
-
-                            CaptureEnding.Manual,
-                            CaptureEnding.Silence -> stopAndTranscribe()
-                        }
-                        break
-                    }
-                    // Both of these sit BELOW the terminal check, and the position is the isolation.
-                    // The warning is a limb: it tells the user something useful and nothing depends on
-                    // it, so a failure in it must not carry the loop past the check that starts
-                    // transcription. Above the check, a throw here would cost the take.
-                    publishDurationWarningIfNeeded(elapsedMs)
-                } catch (_: Exception) {
-                    // A binder disconnect is handled by its ServiceConnection callback.
-                }
-                Thread.sleep(100)
-            }
-        }, "DictationPollingThread").start()
-        listenForPicture()
-    }
-
     /**
      * The recorder's live picture, PUSHED by the audio process for exactly as long as the take is open.
      *
@@ -694,16 +844,19 @@ internal class DictationSessionCoordinator(
      *
      * Take identity is the snapshot's serial, captured here after `show()` stamped it and stamped on every
      * picture; `updateBands` refuses a picture whose serial is not the visible take's, under its own lock.
-     * A picture that never arrives (a wedged audio process, #115) leaves the rail holding its last shape
-     * until the pill hides, as the timer holds its last second. `finishSession` unregisters.
+     * A picture that never arrives leaves the rail holding its last shape until the pill hides; a capture
+     * process that also stops publishing take events ends the take through the silence bound (#115). The
+     * registration dies with the binding.
      *
-     * Failing to register costs the picture only: the take and its polling thread carry on.
+     * Failing to register costs the picture only: the take carries on.
      */
     private fun listenForPicture() {
+        // The serial is read HERE, on main, right after show() stamped it; only the binder call moves to
+        // the lane (#115 review round 1, F1).
         val takeSerial = surface.currentTakeSerial()
-        runCatching {
-            pipeline.capture?.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
-        }.onFailure { log.warn("Live picture unavailable for this take: ${it.message}") }
+        commandCapture("listen for the picture") { capture ->
+            capture.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
+        }
     }
 
     /**
@@ -713,9 +866,9 @@ internal class DictationSessionCoordinator(
      * several seconds into one is an interruption for nothing. The floating recorder only exists while
      * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
      */
-    private fun publishSilenceNoticeIfNeeded(service: CaptureLink) {
+    private fun publishSilenceNoticeIfNeeded(status: Int) {
         if (!sessionPreferences.autoStopOnSilence || silenceNoticeShown) return
-        val status = runCatching { service.silenceStopStatus() }.getOrNull() ?: return
+        if (state.get() != SessionState.RECORDING) return
         if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
         silenceNoticeShown = true
         sayWhileRecording(SILENCE_UNAVAILABLE_NOTICE)
@@ -733,9 +886,8 @@ internal class DictationSessionCoordinator(
      * The tip's once-per-process allowance is spent only when the tip is actually said, so a take that
      * had to say something else leaves it for the next Bluetooth take (Codex review 5, 2026-09-17).
      */
-    private fun publishMicrophoneNoticesIfNeeded(service: CaptureLink) {
+    private fun publishMicrophoneNoticesIfNeeded(kind: Int) {

exec
/bin/zsh -lc "git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '701,1400p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1405ms:
2026-09-21 20:26:30.359 xcodebuild[68177:78220189]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:30.464 xcodebuild[68177:78220188] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-rqHP1Vns' (errno=Operation not permitted)
2026-09-21 20:26:30.871 xcodebuild[68182:78220226]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:30.983 xcodebuild[68182:78220224] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
         if (silenceNoticeShown || forcedNoticeShown) return
-        val kind = runCatching { service.inputRouteKind() }.getOrNull() ?: return
         if (tipGate.shouldShow(kind, sessionPreferences.showBluetoothTips)) {
             log.log("Bluetooth tip shown")
             sayWhileRecording(CaptureNotices.BLUETOOTH_TIP)
@@ -783,51 +935,49 @@ internal class DictationSessionCoordinator(
         }
     }
 
+    /**
+     * RECORDING → PROCESSING, the user's stop. The capture process is told to stop; the transcription
+     * continues in [continueAfterEnding] when it publishes the ending with the closed file (#115). If it
+     * never does, the silence bound ends the take as the close that never came.
+     */
     private fun stopAndTranscribe() {
-        // Under publishLock: the live waiter publishes RECORDING (pill, draft) under the same lock, so a
-        // stop that follows its CAS cannot run ahead of its publication (Codex review 3, 2026-09-18).
+        if (!enterProcessing()) return
+        commandCapture("stop") { it.stopCapture() }
+    }
+
+    /** The one RECORDING → PROCESSING transition, under [publishLock] (Codex review 3, 2026-09-18). */
+    private fun enterProcessing(): Boolean {
+        // Under publishLock: live publishes RECORDING (pill, draft) under the same lock, so a stop that
+        // follows its CAS cannot run ahead of its publication.
         synchronized(publishLock) {
-            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
+            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return false
             surface.showProcessing()
         }
         host.updateSurfacePhase(DictationSurfaceState.Phase.PROCESSING)
         host.promoteToForeground(processing = true)
         host.vibrate(HapticCue.SESSION_TRANSITION)
         log.log("Stopping recording and starting transcription")
+        return true
+    }
 
-        Thread({
-            var audioReady = false
+    /**
+     * PROCESSING, with the capture process's ending in hand: the file is closed and every fact about the
+     * take is on the event (#115). Runs on the owner's scope; nothing here asks the capture process.
+     */
+    private fun continueAfterEnding(ending: TakeEnding) {
+        scope.launch {
             try {
-                pipeline.capture?.stopCapture()
-                audioReady = runCatching { pipeline.capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
-                if (!audioReady) {
-                    pipeline.stopAudioService()
-                    discardDraft()
-                    showError(TerminalReason.CAPTURE_CLOSE_UNSAFE)
-                    return@Thread
-                }
-                val audioFilePath = pipeline.capture?.audioFilePath()
+                val audioFilePath = ending.audioFilePath
                 // The duration is the audio's own length, read from the finished file NOW, before
-                // transcription deletes it: the elapsed getter is 0 once capture stops, and the wall
-                // clock counted the wait for the earbuds.
+                // transcription deletes it: the wall clock counted the wait for the earbuds.
                 recordingDurationMs = runCatching {
                     audioFilePath?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
                 }.getOrNull()?.coerceAtLeast(0L) ?: 0L
-                // Complete once the capture thread has exited (waitForFileReady above joined it): the
-                // final route was observed before the recorder stopped, and the label persists in the
-                // capture process until its next start.
-                captureDeviceLabel = runCatching { pipeline.capture?.effectiveInputDevice() }.getOrNull().orEmpty()
-                // Same moment, same reason: the capture thread has exited, so the peak is the whole take's.
-                takePeakAmplitude = runCatching { pipeline.capture?.takePeakAmplitude() }.getOrNull()
                 val takeFacts = facts
-                takeFacts.peakAmplitude = takePeakAmplitude
                 takeFacts.recordingSeconds = recordingDurationMs / 1000.0
-                // Stamped by the polling loop when capture ended on its own; otherwise this stop is the
+                // Stamped by the ending handler when capture ended on its own; otherwise this stop is the
                 // owner's own request, which the capture process reports as a manual ending.
                 if (takeFacts.captureTerminal == null) takeFacts.captureTerminal = TakeFacts.MANUAL_ENDING
-                pipeline.capture?.let { service ->
-                    takeFacts.silenceStopStatus = runCatching { TakeFacts.silenceStatusToken(service.silenceStopStatus()) }.getOrNull()
-                }
                 Telemetry.journal?.advance(takeId, TakeStage.PROCESSING)
                 Telemetry.breadcrumb(
                     "take", "stopped",
@@ -835,22 +985,20 @@ internal class DictationSessionCoordinator(
                 )
                 finishTakeOrStop()
 
-                val readyDraftId = runCatching { runBlocking { draftCreation?.await() ?: 0L } }.getOrDefault(0L)
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
-                    return@Thread
+                    return@launch
                 }
                 val speechService = pipeline.speech
                 if (speechService == null) {
                     deleteCapturedAudio(audioFilePath)
                     updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                     showError(TerminalReason.ASR_NOT_READY)
-                    return@Thread
+                    return@launch
                 }
                 log.mark("asr_request")
                 val asrRequestedAtMs = host.elapsedRealtimeMs()
@@ -894,12 +1042,12 @@ internal class DictationSessionCoordinator(
                 })
             } catch (error: Exception) {
                 pipeline.stopAudioService()
-                if (audioReady) deleteCapturedAudio(runCatching { pipeline.capture?.audioFilePath() }.getOrNull())
+                deleteCapturedAudio(ending.audioFilePath)
                 log.error("Transcription failed", error)
                 updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
                 showError(TerminalReason.ASR_CALLBACK_EXCEPTION)
             }
-        }, "TranscribeThread").start()
+        }
     }
 
     private fun polishAndPublish(rawText: String) {
@@ -1065,8 +1213,9 @@ internal class DictationSessionCoordinator(
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
@@ -1086,21 +1235,62 @@ internal class DictationSessionCoordinator(
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
@@ -1110,30 +1300,8 @@ internal class DictationSessionCoordinator(
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
@@ -1229,25 +1397,32 @@ internal class DictationSessionCoordinator(
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
@@ -1257,15 +1432,13 @@ internal class DictationSessionCoordinator(
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
@@ -1295,17 +1468,15 @@ internal class DictationSessionCoordinator(
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
 
@@ -1328,43 +1499,47 @@ internal class DictationSessionCoordinator(
         insertion.releasePinnedTarget()
         host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
         host.vibrate(HapticCue.SESSION_CANCELED)
-        scope.launch {
-            // A cancel before the capture process was even bound has nothing to stop: the quiet finish
-            // the old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
-            val service = pipeline.capture
-            if (service == null) {
+        // A cancel before the capture process was even bound has nothing to stop: the quiet finish the
+        // old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
+        val service = pipeline.capture
+        if (service == null) {
+            scope.launch {
                 if (!arbiter.commit(cancel, cancelled)) return@launch
                 discardDraft()
                 finishSession()
-                return@launch
             }
-            val ready = runCatching {
-                service.stopCapture()
-                service.waitForFileReady(2_000L)
-            }.getOrDefault(false)
-            // The outcome is known only now: a close that failed is a failure, not a cancel.
-            if (!ready) {
-                if (!arbiter.commit(cancel, TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)) return@launch
-                pipeline.stopAudioService()
+            return
+        }
+        // Otherwise the capture process is told to stop and the cancel is committed when it publishes the
+        // ending with the closed file (`onTakeEnded`, #115); a close that never comes is ended by the
+        // silence bound as CAPTURE_CLOSE_UNSAFE_ON_CANCEL, a failure and not a cancel, as before.
+        if (endingConsumed.get()) {
+            // The ending already arrived (a take that ended on its own as the cancel landed): commit now.
+            scope.launch {
+                if (!arbiter.commit(cancel, cancelled)) return@launch
                 discardDraft()
-                endAsFailure(TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL)
-                return@launch
+                finishTakeOrStop()
+                finishSession()
             }
-            if (!arbiter.commit(cancel, cancelled)) return@launch
-            discardDraft()
-            deleteCapturedAudio(runCatching { service.audioFilePath() }.getOrNull())
-            finishTakeOrStop()
-            finishSession()
+            return
         }
+        pendingCancelReason = cancelled
+        pendingCancel = cancel
+        commandCapture("stop for a cancel") { it.stopCapture() }
     }
 
     /**
      * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
-     * lifetime and the unbind must not stop it), otherwise stop it as before.
+     * lifetime and the unbind must not stop it), otherwise stop it as before. Issued on the lane and never
+     * awaited (#115 review round 1, F3): a process that answered its ending and then wedged must not hold
+     * the transcription; a call that fails or is never issued stops the service by intent.
      */
     private fun finishTakeOrStop() {
-        val held = runCatching { pipeline.capture?.finishTake() == true }.getOrDefault(false)
-        if (!held) pipeline.stopAudioService()
+        val issued = commandCapture("finishTake") { capture ->
+            val held = runCatching { capture.finishTake() }.getOrDefault(false)
+            if (!held) pipeline.stopAudioService()
+        }
+        if (!issued) pipeline.stopAudioService()
     }
 
     /**
@@ -1467,13 +1642,14 @@ internal class DictationSessionCoordinator(
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
-                // The picture subscription goes with the binding; a failure here costs nothing.
-                runCatching { pipeline.capture?.stopListeningForSpectrum() }
+                // Both subscriptions go with the binding; nothing is called on the capture process here,
+                // which may be the unresponsive process this take just ended over (#115).
+                disarmSilenceBound()
                 pipeline.unbind()
                 host.removeForegroundAndDismiss()
                 // IDLE is published at the last moment this instance can still refuse a start: the
@@ -1501,17 +1677,18 @@ internal class DictationSessionCoordinator(
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
@@ -1539,34 +1716,33 @@ internal class DictationSessionCoordinator(
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
@@ -1574,71 +1750,58 @@ internal class DictationSessionCoordinator(
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
+        // Both delayed callbacks go now, on main, before any blocking cleanup: a bound firing into a
+        // destroyed owner would act on a Service that is gone (#115 review round 1, F4).
+        disarmSilenceBound()
         cancelOpenPolishRequest()
         val sessionWasOpen = destroyedState == SessionState.STARTING ||
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
-            // Captures the link and the controller only: the Service is dead once `onDestroy` returns,
-            // and this thread outlives it (`PipelineBindings` binds through the application context).
-            val capture = pipeline.capture
-            val pipeline = pipeline
-            Thread({
-                runCatching { capture?.stopCapture() }
-                val ready = runCatching { capture?.waitForFileReady(2_000L) == true }.getOrDefault(false)
-                if (ready) deleteCapturedAudio(runCatching { capture?.audioFilePath() }.getOrNull())
-                pipeline.stopAudioService()
-                pipeline.postUnbindToMain(::cancelOpenPolishRequest)
-            }, "DestroyedSessionCleanup").start()
-        } else {
-            if (sessionWasOpen) pipeline.stopAudioService()
-            cancelOpenPolishRequest()
-            pipeline.unbind()
+            // Down the lane, like every other call into the capture process (#115): the lane's daemon
+            // thread outlives the Service, and the link it captured stays valid after the unbind below.
+            // The file is the capture process's one cache file and the next take overwrites it; nothing
+            // waits for it here.
+            commandCapture("stop at destroy") { it.stopCapture() }
         }
+        // The service is stopped by intent whether or not that stop is ever delivered (a wedged process
+        // gets neither, and the OS or the user ends it); the bindings go now, on main, with nothing waited
+        // for.
+        if (sessionWasOpen) pipeline.stopAudioService()
+        cancelOpenPolishRequest()
+        pipeline.unbind()
+        // No further command is accepted; the stop above, if queued, still runs on the lane's own thread.
+        captureCommands.shutdown()
     }
 }
 

exec
/bin/zsh -lc 'git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt app/src/main/java/com/envi/wispr/ui/SessionHost.kt app/src/main/java/com/envi/wispr/ui/TakeNotices.kt app/src/main/java/com/envi/wispr/ui/TerminalReason.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1414ms:
2026-09-21 20:26:42.476 xcodebuild[68371:78221392]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:42.593 xcodebuild[68371:78221391] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-LM0eCLp3' (errno=Operation not permitted)
2026-09-21 20:26:42.991 xcodebuild[68374:78221415]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:26:43.101 xcodebuild[68374:78221414] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
diff --git a/app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt b/app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
index f63b945..7ee007d 100644
--- a/app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
+++ b/app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
@@ -19,6 +19,7 @@ import android.widget.Toast
 import androidx.core.content.ContextCompat
 import com.envi.wispr.history.EnviousWisprDatabase
 import com.envi.wispr.history.TranscriptRepository
+import com.envi.wispr.models.ModelBootstrapApplication
 import com.envi.wispr.paste.AccessibilityPermission
 import com.envi.wispr.paste.AutoPasteAvailability
 import com.envi.wispr.paste.AutoPasteReadiness
@@ -134,6 +135,14 @@ class DictationSessionService : Service() {
             mainHandler.post(runnable)
         }
 
+        override fun postToMainDelayed(delayMs: Long, runnable: Runnable) {
+            mainHandler.postDelayed(runnable, delayMs)
+        }
+
+        override fun cancelMainDelayed(runnable: Runnable) {
+            mainHandler.removeCallbacks(runnable)
+        }
+
         override fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
 
         override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
@@ -216,6 +225,7 @@ class DictationSessionService : Service() {
             insertion = AccessibilityInsertionGateway,
             log = DebugSessionLog,
             preferences = preferences,
+            historyWrites = ModelBootstrapApplication.historyWrites(applicationContext),
             transcripts = TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao()),
             languageDetector = languageDetector,
             loadPolicy = { providerConfiguration.loadPolicy() },
diff --git a/app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt b/app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt
index ecad5dd..77322df 100644
--- a/app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt
+++ b/app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt
@@ -12,6 +12,7 @@ import com.envi.wispr.asr.IAsrService
 import com.envi.wispr.audio.AudioCaptureService
 import com.envi.wispr.audio.IAudioCaptureService
 import com.envi.wispr.audio.IAudioSpectrumListener
+import com.envi.wispr.audio.ITakeListener
 import com.envi.wispr.polish.IPolishCallback
 import com.envi.wispr.polish.IPolishService
 import com.envi.wispr.polish.PolishOutcome
@@ -118,13 +119,6 @@ internal class PipelineBindings(
         polish = null
     }
 
-    override fun postUnbindToMain(beforeUnbind: () -> Unit) {
-        mainHandler.post {
-            beforeUnbind()
-            unbind()
-        }
-    }
-
     override fun stopAudioService() {
         runCatching { appContext.stopService(Intent(appContext, AudioCaptureService::class.java)) }
             .onFailure { error -> log.warn("Unable to stop audio capture service: ${error.message}") }
@@ -132,43 +126,30 @@ internal class PipelineBindings(
 
     /** Pass-through; a binder exception escapes to the caller's `runCatching`, exactly as the proxy's did. */
     private class CaptureProxy(private val service: IAudioCaptureService) : CaptureLink {
-        /**
-         * The Stub registered with the audio process, kept so the unregister passes the SAME binder
-         * (#187). Written on the owner's main thread; its `onSpectrum` runs on binder threads.
-         */
-        @Volatile private var spectrumStub: IAudioSpectrumListener.Stub? = null
 
         override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean =
             service.startCaptureForTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)
-        override fun lastStartFailure(): Int = service.lastStartFailure
         override fun stopCapture() = service.stopCapture()
-        override fun waitForFileReady(timeoutMs: Long): Boolean = service.waitForFileReady(timeoutMs)
-        override fun liveState(): Int = service.liveState
-        override fun isCapturing(): Boolean = service.isCapturing
-        override fun audioFilePath(): String? = service.audioFilePath
-        override fun elapsedMs(): Long = service.elapsedMs
-        override fun silenceStopStatus(): Int = service.silenceStopStatus
-        override fun inputRouteKind(): Int = service.inputRouteKind
-        override fun inputRouteReason(): Int = service.inputRouteReason
-        override fun liveAfterMs(): Long = service.liveAfterMs
-        override fun terminalReason(): Int = service.terminalReason
         override fun listenForSpectrum(listener: SpectrumListener) {
             val stub = object : IAudioSpectrumListener.Stub() {
                 override fun onSpectrum(bands: FloatArray?) {
                     listener.onSpectrum(bands ?: FloatArray(0))
                 }
             }
-            spectrumStub = stub
             service.registerSpectrumListener(stub)
         }
-        override fun stopListeningForSpectrum() {
-            val stub = spectrumStub ?: return
-            spectrumStub = null
-            service.unregisterSpectrumListener(stub)
-        }
-        override fun effectiveInputDevice(): String? = service.effectiveInputDevice
-        override fun takePeakAmplitude(): Float = service.takePeakAmplitude
         override fun finishTake(): Boolean = service.finishTake()
+
+        override fun listenForTake(listener: TakeListener) {
+            val stub = object : ITakeListener.Stub() {
+                override fun onLive(takeId: String?, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = listener.onLive(takeId.orEmpty(), forced, routeKind, routeReason, liveAfterMs)
+                override fun onTick(takeId: String?, elapsedMs: Long) = listener.onTick(takeId.orEmpty(), elapsedMs)
+                override fun onSilenceStatus(takeId: String?, status: Int) = listener.onSilenceStatus(takeId.orEmpty(), status)
+                override fun onEnded(takeId: String?, terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
+                    listener.onEnded(TakeEnding(takeId.orEmpty(), terminalReason, startFailure, audioFilePath?.takeIf { it.isNotEmpty() }, silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
+            }
+            service.registerTakeListener(stub)
+        }
     }
 
     /**
diff --git a/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt b/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
index 5705e53..4efe687 100644
--- a/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
+++ b/app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt
@@ -17,31 +17,54 @@ internal fun interface SpectrumListener {
     fun onSpectrum(bands: FloatArray)
 }
 
-/** `IAudioCaptureService`, the members the owner uses. */
+/**
+ * The take's events as `ITakeListener` delivers them (#115). Every method arrives on a binder thread; the
+ * owner posts each to its main thread before acting, which serialises them in delivery order.
+ */
+internal interface TakeListener {
+    fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long)
+    fun onTick(takeId: String, elapsedMs: Long)
+    fun onSilenceStatus(takeId: String, status: Int)
+    fun onEnded(ending: TakeEnding)
+}
+
+/**
+ * The ending as `ITakeListener.onEnded` carries it: the take it belongs to, the closed file, and every fact
+ * the owner once asked for. The owner discards an ending whose [takeId] is not its take's: the publisher is
+ * service-scoped, so a previous take's ending can reach the next take's listener.
+ */
+internal data class TakeEnding(
+    val takeId: String,
+    val terminalReason: Int,
+    val startFailure: Int,
+    /** The CLOSED file, or null for an ending with no file (a start refused before capture began). */
+    val audioFilePath: String?,
+    val silenceStatus: Int,
+    val takePeakAmplitude: Float,
+    val effectiveInputDevice: String,
+)
+
+/**
+ * `IAudioCaptureService`, the members the owner uses (#115): three COMMANDS and two listener members. The
+ * owner reads nothing from the audio process; every fact it needs is pushed through [TakeListener] and
+ * [SpectrumListener]. A synchronous call into a process that stops answering has no timeout, so the fewer
+ * there are the smaller the surface the owner's silence bound has to cover.
+ */
 internal interface CaptureLink {
     fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean
-    fun lastStartFailure(): Int
     fun stopCapture()
-    fun waitForFileReady(timeoutMs: Long): Boolean
-    fun liveState(): Int
-    fun isCapturing(): Boolean
-    fun audioFilePath(): String?
-    fun elapsedMs(): Long
-    fun silenceStopStatus(): Int
-    fun inputRouteKind(): Int
-    fun inputRouteReason(): Int
-    fun liveAfterMs(): Long
-    fun terminalReason(): Int
+    fun finishTake(): Boolean
     /**
      * Receive the recorder's picture as the audio process publishes it (#187): the production link
      * builds the binder Stub and registers it; a later call replaces the earlier registration.
      */
     fun listenForSpectrum(listener: SpectrumListener)
-    /** Unregister the Stub `listenForSpectrum` registered, if any. Idempotent. */
-    fun stopListeningForSpectrum()
-    fun effectiveInputDevice(): String?
-    fun takePeakAmplitude(): Float
-    fun finishTake(): Boolean
+    /**
+     * Receive the take's events (#115). Registered BEFORE `startCaptureForTake` so no event precedes it.
+     * Both listener slots are the binding's and die with it: the owner never calls the audio process to
+     * drop one, because on the path that matters most that process is the one that stopped answering.
+     */
+    fun listenForTake(listener: TakeListener)
 }
 
 /** The answer to one `IAsrService.transcribeFileForTake`; the engine answers once. */
@@ -106,12 +129,6 @@ internal interface PipelineController {
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
 
diff --git a/app/src/main/java/com/envi/wispr/ui/SessionHost.kt b/app/src/main/java/com/envi/wispr/ui/SessionHost.kt
index 1fa3280..2387b4e 100644
--- a/app/src/main/java/com/envi/wispr/ui/SessionHost.kt
+++ b/app/src/main/java/com/envi/wispr/ui/SessionHost.kt
@@ -43,6 +43,12 @@ internal interface SessionHost {
 
     /** The Service's one `Handler(Looper.getMainLooper())`: FIFO on the main looper. */
     fun postToMain(runnable: Runnable)
+    /**
+     * Run [runnable] on the main thread after [delayMs], unless [cancelMainDelayed] removes it first. The
+     * owner's only timer, armed for exactly as long as a take's binding is up (#115; RULE: no-idle-cost).
+     */
+    fun postToMainDelayed(delayMs: Long, runnable: Runnable)
+    fun cancelMainDelayed(runnable: Runnable)
 
     fun onMainThread(): Boolean
 
diff --git a/app/src/main/java/com/envi/wispr/ui/TakeNotices.kt b/app/src/main/java/com/envi/wispr/ui/TakeNotices.kt
index 139964b..74a4fd4 100644
--- a/app/src/main/java/com/envi/wispr/ui/TakeNotices.kt
+++ b/app/src/main/java/com/envi/wispr/ui/TakeNotices.kt
@@ -45,6 +45,7 @@ internal object TakeNotices {
         -> null
 
         TerminalReason.AUDIO_PROCESS_DIED -> "Microphone service stopped unexpectedly"
+        TerminalReason.AUDIO_PROCESS_UNRESPONSIVE -> "The microphone stopped answering. Try again."
         TerminalReason.ASR_PROCESS_DIED -> "Speech service stopped before transcription finished"
         TerminalReason.POLISH_PROCESS_DIED -> "Polish service stopped before cleanup finished"
 
@@ -73,9 +74,10 @@ internal object TakeNotices {
     }
 
     /**
-     * Which ending a refused capture start is, decided from the code the capture process reports over
-     * the binder (`IAudioCaptureService.getLastStartFailure`), never from a display label. The same
-     * table `CaptureNotices.startFailureLine` used to hold; the sentence now comes from [line].
+     * Which ending a refused capture start is, decided from the code the capture process pushes on the
+     * ending (`TakeEnding.startFailure`, through `ITakeListener.onEnded`; the `getLastStartFailure`
+     * getter stays legacy for the append-only interface), never from a display label. The same table
+     * `CaptureNotices.startFailureLine` used to hold; the sentence now comes from [line].
      */
     fun startFailureReason(lastStartFailure: Int): TerminalReason = when (lastStartFailure) {
         AudioCaptureService.START_FAILURE_NO_INPUT_DEVICE -> TerminalReason.CAPTURE_START_NO_MICROPHONE
diff --git a/app/src/main/java/com/envi/wispr/ui/TerminalReason.kt b/app/src/main/java/com/envi/wispr/ui/TerminalReason.kt
index 98a5581..65576c3 100644
--- a/app/src/main/java/com/envi/wispr/ui/TerminalReason.kt
+++ b/app/src/main/java/com/envi/wispr/ui/TerminalReason.kt
@@ -59,6 +59,8 @@ internal enum class TerminalReason(val result: TerminalResult) {
 
     // A pipeline process went away mid-take. Three members, never one shared "service died".
     AUDIO_PROCESS_DIED(TerminalResult.AUDIO_INTERRUPTED),
+    /** The audio process stopped publishing the take's events for the silence bound: frozen or wedged, not dead (#115). */
+    AUDIO_PROCESS_UNRESPONSIVE(TerminalResult.AUDIO_INTERRUPTED),
     ASR_PROCESS_DIED(TerminalResult.ASR_INTERRUPTED),
     POLISH_PROCESS_DIED(TerminalResult.FAILED),
 

exec
/bin/zsh -lc "grep -rn \"getElapsedMs\\|getLiveState\\|waitForFileReady\\|isCapturing\" app/src" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 180ms:
app/src/androidTest/java/com/envi/wispr/SilenceStoppedTakeTranscribesDeviceTest.kt:77:                if (!capture.isCapturing) { ended = true; break }
app/src/androidTest/java/com/envi/wispr/SilenceStoppedTakeTranscribesDeviceTest.kt:85:            assertTrue("the file must close", capture.waitForFileReady(5_000L))
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:96:                if (!capture.isCapturing) { endedAfterMs = System.currentTimeMillis() - startedAt; break }
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:108:            assertTrue("the recording must have closed", capture.waitForFileReady(3_000L))
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:153:                    if (!capture.isCapturing) { endedAfterMs = System.currentTimeMillis() - startedAt; break }
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:161:                capture.waitForFileReady(3_000L)
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:196:                if (!capture.isCapturing) { stillCapturing = false; break }
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:206:            assertTrue("the file must close", capture.waitForFileReady(3_000L))
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:231:            capture.waitForFileReady(3_000L)
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:256:            capture.waitForFileReady(3_000L)
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:274:            assertTrue("and ordinary recording is untouched", capture.isCapturing)
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:276:            assertTrue(capture.waitForFileReady(3_000L))
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:330:            assertTrue("the take must still be open while the absence is measured", capture.isCapturing)
app/src/androidTest/java/com/envi/wispr/CaptureWithSilenceStopDeviceTest.kt:337:            assertTrue("the recording must have closed", capture.waitForFileReady(3_000L))
app/src/test/java/com/envi/wispr/ui/SessionOwnerShapeTest.kt:178:        listOf("runBlocking", "Thread.sleep", "startPolling", "waitForFileReady", "Thread.join", ".join(").forEach {
app/src/test/java/com/envi/wispr/audio/SilenceStopWiringTest.kt:48:                "startCapture", "stopCapture", "isCapturing", "getTerminalReason", "getCurrentAmplitude",
app/src/test/java/com/envi/wispr/audio/SilenceStopWiringTest.kt:49:                "getAudioFilePath", "getElapsedMs", "getMaxDurationMs", "waitForFileReady", "getAudioData",
app/src/test/java/com/envi/wispr/audio/SilenceStopWiringTest.kt:56:                "startCaptureWithInputDeviceHeld", "getLiveState", "getLiveAfterMs", "finishTake",
app/src/test/java/com/envi/wispr/audio/AudioServiceShapeTest.kt:71:            "endTake", "endTakeLocked", "releaseSession", "closeResources", "waitForFileReady",
app/src/test/java/com/envi/wispr/audio/LiveGateWiringTest.kt:51:        val getter = body(capture, "override fun getLiveState(): Int {")
app/src/test/java/com/envi/wispr/audio/LiveGateWiringTest.kt:53:        val elapsed = body(capture, "override fun getElapsedMs(): Long {")
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:9:    boolean isCapturing();
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:13:    long getElapsedMs();
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:15:    boolean waitForFileReady(long timeoutMs);
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:85:    int getLiveState();
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:128:     * on this interface: isCapturing, getTerminalReason, getElapsedMs, getLiveState, getLiveAfterMs,
app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:130:     * getEffectiveInputDevice, getTakePeakAmplitude and waitForFileReady are LEGACY with no production
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:67:        /** `getLiveState`: what the session owner waits on before it opens the pill. */
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:222:        override fun getLiveState(): Int {
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:248:        override fun isCapturing(): Boolean = this@AudioCaptureService.isRecording.get()
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:282:        override fun getElapsedMs(): Long {
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:292:        override fun waitForFileReady(timeoutMs: Long): Boolean =
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:293:            this@AudioCaptureService.waitForFileReady(timeoutMs)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:844:    fun waitForFileReady(timeoutMs: Long = 2_000L): Boolean {

exec
/bin/zsh -lc "rg -n \"pipeline\\.capture|capture\\.(listenForTake|startCaptureForTake|stopCapture|finishTake|listenForSpectrum)\" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
rg -n \"runBlocking|Thread\\.sleep|\\.join\\(|startPolling|waitForFileReady\" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt
rg -n \"await\\(\" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt app/src/main/java/com/envi/wispr/history/HistoryWriteQueue.kt" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
516:                capture.listenForTake(takeListener)
522:                    capture.startCaptureForTake(
543:                    runCatching { capture.stopCapture() }
563:        val capture = pipeline.capture ?: return false
858:            capture.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
1504:        val service = pipeline.capture
1539:            val held = runCatching { capture.finishTake() }.getOrDefault(false)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:292:        override fun waitForFileReady(timeoutMs: Long): Boolean =
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:293:            this@AudioCaptureService.waitForFileReady(timeoutMs)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:844:    fun waitForFileReady(timeoutMs: Long = 2_000L): Boolean {
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:847:            thread.join(timeoutMs.coerceAtLeast(0L))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:423:            if (admission != null && withTimeoutOrNull(JOURNAL_ADMISSION_DEADLINE_MS) { admission.await() } == null) {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1085:                    polishTimeout.await(takePreferences.policy)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1304:            val saveResult = saved.await()
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1691:        draftId.get().takeIf { it > 0L } ?: runCatching { draftCreation?.await() ?: 0L }.getOrDefault(0L)

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1498,1548p'
rg -n \"commandCapture\\(\" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
  1498	        surface.showProcessing()
  1499	        insertion.releasePinnedTarget()
  1500	        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
  1501	        host.vibrate(HapticCue.SESSION_CANCELED)
  1502	        // A cancel before the capture process was even bound has nothing to stop: the quiet finish the
  1503	        // old cancelStarting always took. Committed BEFORE the draft goes (G2 D2).
  1504	        val service = pipeline.capture
  1505	        if (service == null) {
  1506	            scope.launch {
  1507	                if (!arbiter.commit(cancel, cancelled)) return@launch
  1508	                discardDraft()
  1509	                finishSession()
  1510	            }
  1511	            return
  1512	        }
  1513	        // Otherwise the capture process is told to stop and the cancel is committed when it publishes the
  1514	        // ending with the closed file (`onTakeEnded`, #115); a close that never comes is ended by the
  1515	        // silence bound as CAPTURE_CLOSE_UNSAFE_ON_CANCEL, a failure and not a cancel, as before.
  1516	        if (endingConsumed.get()) {
  1517	            // The ending already arrived (a take that ended on its own as the cancel landed): commit now.
  1518	            scope.launch {
  1519	                if (!arbiter.commit(cancel, cancelled)) return@launch
  1520	                discardDraft()
  1521	                finishTakeOrStop()
  1522	                finishSession()
  1523	            }
  1524	            return
  1525	        }
  1526	        pendingCancelReason = cancelled
  1527	        pendingCancel = cancel
  1528	        commandCapture("stop for a cancel") { it.stopCapture() }
  1529	    }
  1530	
  1531	    /**
  1532	     * The take is over: let the capture service keep the earbuds warm if it can (it then owns its own
  1533	     * lifetime and the unbind must not stop it), otherwise stop it as before. Issued on the lane and never
  1534	     * awaited (#115 review round 1, F3): a process that answered its ending and then wedged must not hold
  1535	     * the transcription; a call that fails or is never issued stops the service by intent.
  1536	     */
  1537	    private fun finishTakeOrStop() {
  1538	        val issued = commandCapture("finishTake") { capture ->
  1539	            val held = runCatching { capture.finishTake() }.getOrDefault(false)
  1540	            if (!held) pipeline.stopAudioService()
  1541	        }
  1542	        if (!issued) pipeline.stopAudioService()
  1543	    }
  1544	
  1545	    /**
  1546	     * Cancel while the words are being transcribed or polished (#75). Claims publication first: if the
  1547	     * text is already on its way the cancel is too late and does nothing. Otherwise it takes the submission
  1548	     * lock so it either precedes the ledger open (no request is sent) or follows it (the open id is closed
515:            captureStarted = commandCapture("start") { capture ->
549:                commandCapture("stop after a failed start") { it.stopCapture() }
562:    private fun commandCapture(what: String, block: (CaptureLink) -> Unit): Boolean {
655:        commandCapture("stop at the live deadline") { it.stopCapture() }
759:                commandCapture("stop a take that went live after its ending") { it.stopCapture() }
857:        commandCapture("listen for the picture") { capture ->
945:        commandCapture("stop") { it.stopCapture() }
1528:        commandCapture("stop for a cancel") { it.stopCapture() }
1538:        val issued = commandCapture("finishTake") { capture ->
1795:            commandCapture("stop at destroy") { it.stopCapture() }

exec
/bin/zsh -lc 'rg -n "takeId" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt' in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:25:    fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long)
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:26:    fun onTick(takeId: String, elapsedMs: Long)
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:27:    fun onSilenceStatus(takeId: String, status: Int)
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:33: * the owner once asked for. The owner discards an ending whose [takeId] is not its take's: the publisher is
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:37:    val takeId: String,
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:54:    fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:79:    fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener)
app/src/main/java/com/envi/wispr/ui/PipelineLinks.kt:99:        takeId: String,
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:94:        val takeId: String?,
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:162:            takeId: String? = null,
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:169:                service.requestInsertion(transcriptId, text, previousClipboard, policy, takeId)
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:732:        takeId: String?,
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:757:            takeId = takeId,
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:1386:            Telemetry.breadcrumb("insertion", "outcome", mapOf("take_id" to pending.takeId, "result" to result, "target_app" to pending.targetPackage))
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:1387:            if (pending.takeId == null) return
app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:1390:                    takeId = pending.takeId,
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:130:        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String, keepEarbudsReady: Boolean, takeId: String): Boolean =
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:131:            service.startCaptureForTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:145:                override fun onLive(takeId: String?, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = listener.onLive(takeId.orEmpty(), forced, routeKind, routeReason, liveAfterMs)
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:146:                override fun onTick(takeId: String?, elapsedMs: Long) = listener.onTick(takeId.orEmpty(), elapsedMs)
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:147:                override fun onSilenceStatus(takeId: String?, status: Int) = listener.onSilenceStatus(takeId.orEmpty(), status)
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:148:                override fun onEnded(takeId: String?, terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) =
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:149:                    listener.onEnded(TakeEnding(takeId.orEmpty(), terminalReason, startFailure, audioFilePath?.takeIf { it.isNotEmpty() }, silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:161:        override fun transcribeFileForTake(audioFilePath: String, takeId: String, listener: SpeechListener) {
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:162:            service.transcribeFileForTake(audioFilePath, takeId, object : IAsrCallback.Stub() {
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:181:            takeId: String,
app/src/main/java/com/envi/wispr/ui/PipelineBindings.kt:191:                takeId,
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:53:        val takeId: String
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:55:        data class Live(override val takeId: String, val forced: Boolean, val routeKind: Int, val routeReason: Int, val liveAfterMs: Long) : Event
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:56:        data class Tick(override val takeId: String, val elapsedMs: Long) : Event
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:57:        data class SilenceStatus(override val takeId: String, val status: Int) : Event
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:59:            override val takeId: String,
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:88:    fun offerTick(takeId: String, elapsedMs: Long) {
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:95:        offer(Event.Tick(takeId, elapsedMs))
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:98:    fun publishLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:99:        offer(Event.Live(takeId, forced, routeKind, routeReason, liveAfterMs))
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:102:    fun publishSilenceStatus(takeId: String, status: Int) {
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:103:        offer(Event.SilenceStatus(takeId, status))
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:108:        takeId: String,
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:116:        offer(Event.Ended(takeId, terminalReason, startFailure, audioFilePath.orEmpty(), silenceStatus, takePeakAmplitude, effectiveInputDevice.orEmpty()))
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:165:                    is Event.Live -> target.onLive(event.takeId, event.forced, event.routeKind, event.routeReason, event.liveAfterMs)
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:166:                    is Event.Tick -> target.onTick(event.takeId, event.elapsedMs)
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:167:                    is Event.SilenceStatus -> target.onSilenceStatus(event.takeId, event.status)
app/src/main/java/com/envi/wispr/audio/TakeEventPublisher.kt:168:                    is Event.Ended -> target.onEnded(event.takeId, event.terminalReason, event.startFailure, event.audioFilePath, event.silenceStatus, event.takePeakAmplitude, event.effectiveInputDevice)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:89:        val takeId: String,
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:206:            this@AudioCaptureService.startRecording(autoStopOnSilence = false, pauseSeconds = 0f, pick = InputDevicePick.Auto, takeId = "")
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:209:            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.Auto, takeId = "")
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:212:            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), takeId = "")
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:215:            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady, takeId = "")
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:217:        override fun startCaptureForTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean =
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:218:            this@AudioCaptureService.startRecording(autoStopOnSilence, pauseSeconds, InputDevicePick.parse(inputDevicePick), keepEarbudsReady, takeId.orEmpty())
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:330:        takeId: String,
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:351:                publishStartRefused(takeId, START_FAILURE_OTHER)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:370:                publishStartRefused(takeId, lastStartFailure)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:393:                publishStartRefused(takeId, lastStartFailure)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:404:                publishStartRefused(takeId, lastStartFailure)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:465:                        onStatus = { status -> takeEvents.publishSilenceStatus(takeId, status) },
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:470:                    takeId = takeId,
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:504:                        takeId = newSession.takeId,
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:528:                    publishStartRefused(takeId, lastStartFailure)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:549:                failSetup(threadStarted, record, output, routeHold, takeId)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:553:                failSetup(threadStarted, record, output, routeHold, takeId)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:597:                takeEvents.offerTick(active.takeId, if (active.liveVisible) SystemClock.elapsedRealtime() - active.route.liveAtMs else 0L)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:614:                        active.takeId,
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:659:    private fun publishStartRefused(takeId: String, failure: Int) {
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:660:        takeEvents.publishEnded(takeId, TERMINAL_REASON_NONE, failure, null, SILENCE_STATUS_DISABLED, 0f, null)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:670:    private fun failSetup(threadStarted: Boolean, record: AudioRecord?, output: java.io.FileOutputStream?, routeHold: RouteHold, takeId: String) {
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:687:        publishStartRefused(takeId, lastStartFailure)
app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:797:            active.takeId,
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:163:    @Volatile private var takeId = ""
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:373:        takeId = UUID.randomUUID().toString().lowercase()
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:377:        val takeFacts = TakeFacts(takeId, trigger)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:382:        Telemetry.takeStarted(takeId)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:383:        Telemetry.breadcrumb("take", "admitted", mapOf("take_id" to takeId, "trigger_source" to trigger.wire))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:388:        val admission = Telemetry.journal?.admit(takeId, trigger)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:413:                Telemetry.breadcrumb("take", "settings_fallback", mapOf("take_id" to takeId, "settings_fallback" to token))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:514:            val id = takeId
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:520:                if (state.get() != SessionState.STARTING || takeId != id) return@commandCapture
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:540:                } else if (state.get() != SessionState.STARTING || takeId != id) {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:577:        private fun ours(eventTakeId: String): Boolean = eventTakeId == takeId
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:579:        override fun onLive(takeId: String, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:580:            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishLive(forced, routeKind, routeReason, liveAfterMs) } }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:583:        override fun onTick(takeId: String, elapsedMs: Long) {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:584:            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); onTakeTick(elapsedMs) } }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:587:        override fun onSilenceStatus(takeId: String, status: Int) {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:588:            host.postToMain { if (ours(takeId)) { rearmSilenceBound(); publishSilenceNoticeIfNeeded(status) } }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:592:            host.postToMain { if (ours(ending.takeId)) { rearmSilenceBound(); onTakeEnded(ending) } }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:769:            Telemetry.journal?.advance(takeId, TakeStage.RECORDING)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:772:                mapOf("take_id" to takeId, "route_kind" to takeFacts.routeKind?.name?.lowercase(), "live_after_ms" to takeFacts.liveAfterMs, "live_state" to takeFacts.liveState),
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:780:            val rowTakeId = takeFacts.takeId
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:981:                Telemetry.journal?.advance(takeId, TakeStage.PROCESSING)
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:984:                    mapOf("take_id" to takeId, "capture_terminal" to takeFacts.captureTerminal, "recording_s" to takeFacts.recordingSeconds, "silence_stop_status" to takeFacts.silenceStopStatus),
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1005:                speechService.transcribeFileForTake(audioFilePath, takeId, object : SpeechListener {
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1012:                        Telemetry.breadcrumb("take", "asr_done", mapOf("take_id" to takeId, "asr_ms" to takeFacts.asrMs, "asr_chars" to takeFacts.asrChars))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1101:                    takeId,
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1110:                                    Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to if (outcome == null) "null" else "mismatched"))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1136:                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_result"))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1143:                                Telemetry.defect(AppDefect.PolishProtocolViolation, mapOf("take_id" to takeId, "shape" to "v1_error"))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1236:        Telemetry.breadcrumb("take", "polish_done", mapOf("take_id" to takeId, "polish_reason" to reason.name, "polish_ms" to latencyMs, "polish_provider" to takeFacts.polishProvider))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1237:        TelemetryChannels.defectOf(reason)?.let { Telemetry.defect(it, mapOf("take_id" to takeId, "polish_status" to statusCode)) }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1311:                Telemetry.breadcrumb("take", "history_save_failed", mapOf("take_id" to takeId, "error_type" to error.javaClass.simpleName))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1312:                TelemetryChannels.historySaveDefect(error)?.let { Telemetry.defect(it, mapOf("take_id" to takeId)) }
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1335:                        takeId,
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1377:                        takeId = takeId, handoff = handoff, result = resultKind, route = InsertionRouteKind.of(resultKind),
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1382:                Telemetry.breadcrumb("take", "insertion_handed_off", mapOf("take_id" to takeId))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1816:    Telemetry.breadcrumb("take", "terminal", mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "result" to reason.result.wire))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1818:        Telemetry.defect(defect, mapOf("take_id" to takeFacts.takeId, "reason" to reason.name, "asr_failure_reason" to takeFacts.asrFailure?.name))
app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt:1821:    Telemetry.takeEnded(takeFacts.takeId)

exec
/bin/zsh -lc "git diff 87e07ca d3e54b5 -- app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '630,740p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 1411ms:
2026-09-21 20:29:01.502 xcodebuild[71136:78232620]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:29:01.616 xcodebuild[71136:78232619] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
git: warning: confstr() failed with code 5: couldn't get path of DARWIN_USER_TEMP_DIR; using /tmp instead
git: error: couldn't create cache file '/tmp/xcrun_db-NIunsQ6o' (errno=Operation not permitted)
2026-09-21 20:29:02.013 xcodebuild[71138:78232639]  DVTFilePathFSEvents: Failed to start fs event stream.
2026-09-21 20:29:02.120 xcodebuild[71138:78232637] [MT] DVTDeveloperPaths: Failed to get length of DARWIN_USER_CACHE_DIR from confstr(3), error = Error Domain=NSPOSIXErrorDomain Code=5 "Input/output error". Using NSCachesDirectory instead.
-                                stopAndTranscribe()
-                                log.log("Take ended at the duration cap")
-                                sayAfterRecording(DURATION_REACHED_NOTICE)
-                            }
-
-                            CaptureEnding.Manual,
-                            CaptureEnding.Silence -> stopAndTranscribe()
-                        }
-                        break
-                    }
-                    // Both of these sit BELOW the terminal check, and the position is the isolation.
-                    // The warning is a limb: it tells the user something useful and nothing depends on
-                    // it, so a failure in it must not carry the loop past the check that starts
-                    // transcription. Above the check, a throw here would cost the take.
-                    publishDurationWarningIfNeeded(elapsedMs)
-                } catch (_: Exception) {
-                    // A binder disconnect is handled by its ServiceConnection callback.
-                }
-                Thread.sleep(100)
-            }
-        }, "DictationPollingThread").start()
-        listenForPicture()
-    }
-
     /**
      * The recorder's live picture, PUSHED by the audio process for exactly as long as the take is open.
      *
@@ -694,16 +844,19 @@ internal class DictationSessionCoordinator(
      *
      * Take identity is the snapshot's serial, captured here after `show()` stamped it and stamped on every
      * picture; `updateBands` refuses a picture whose serial is not the visible take's, under its own lock.
-     * A picture that never arrives (a wedged audio process, #115) leaves the rail holding its last shape
-     * until the pill hides, as the timer holds its last second. `finishSession` unregisters.
+     * A picture that never arrives leaves the rail holding its last shape until the pill hides; a capture
+     * process that also stops publishing take events ends the take through the silence bound (#115). The
+     * registration dies with the binding.
      *
-     * Failing to register costs the picture only: the take and its polling thread carry on.
+     * Failing to register costs the picture only: the take carries on.
      */
     private fun listenForPicture() {
+        // The serial is read HERE, on main, right after show() stamped it; only the binder call moves to
+        // the lane (#115 review round 1, F1).
         val takeSerial = surface.currentTakeSerial()
-        runCatching {
-            pipeline.capture?.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
-        }.onFailure { log.warn("Live picture unavailable for this take: ${it.message}") }
+        commandCapture("listen for the picture") { capture ->
+            capture.listenForSpectrum { bands -> surface.updateBands(takeSerial, bands) }
+        }
     }
 
     /**
@@ -713,9 +866,9 @@ internal class DictationSessionCoordinator(
      * several seconds into one is an interruption for nothing. The floating recorder only exists while
      * the accessibility service runs, so clipboard-only mode gets the same sentence as a toast instead.
      */
-    private fun publishSilenceNoticeIfNeeded(service: CaptureLink) {
+    private fun publishSilenceNoticeIfNeeded(status: Int) {
         if (!sessionPreferences.autoStopOnSilence || silenceNoticeShown) return
-        val status = runCatching { service.silenceStopStatus() }.getOrNull() ?: return
+        if (state.get() != SessionState.RECORDING) return
         if (status != AudioCaptureService.SILENCE_STATUS_UNAVAILABLE) return
         silenceNoticeShown = true
         sayWhileRecording(SILENCE_UNAVAILABLE_NOTICE)
@@ -733,9 +886,8 @@ internal class DictationSessionCoordinator(
      * The tip's once-per-process allowance is spent only when the tip is actually said, so a take that
      * had to say something else leaves it for the next Bluetooth take (Codex review 5, 2026-09-17).
      */
-    private fun publishMicrophoneNoticesIfNeeded(service: CaptureLink) {
+    private fun publishMicrophoneNoticesIfNeeded(kind: Int) {
         if (silenceNoticeShown || forcedNoticeShown) return
-        val kind = runCatching { service.inputRouteKind() }.getOrNull() ?: return
         if (tipGate.shouldShow(kind, sessionPreferences.showBluetoothTips)) {
             log.log("Bluetooth tip shown")
             sayWhileRecording(CaptureNotices.BLUETOOTH_TIP)
@@ -783,51 +935,49 @@ internal class DictationSessionCoordinator(
         }
     }
 
+    /**
+     * RECORDING → PROCESSING, the user's stop. The capture process is told to stop; the transcription
+     * continues in [continueAfterEnding] when it publishes the ending with the closed file (#115). If it
+     * never does, the silence bound ends the take as the close that never came.
+     */
     private fun stopAndTranscribe() {
-        // Under publishLock: the live waiter publishes RECORDING (pill, draft) under the same lock, so a
-        // stop that follows its CAS cannot run ahead of its publication (Codex review 3, 2026-09-18).
+        if (!enterProcessing()) return
+        commandCapture("stop") { it.stopCapture() }
+    }
+
+    /** The one RECORDING → PROCESSING transition, under [publishLock] (Codex review 3, 2026-09-18). */
+    private fun enterProcessing(): Boolean {
+        // Under publishLock: live publishes RECORDING (pill, draft) under the same lock, so a stop that
+        // follows its CAS cannot run ahead of its publication.
         synchronized(publishLock) {
-            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return
+            if (!state.compareAndSet(SessionState.RECORDING, SessionState.PROCESSING)) return false
             surface.showProcessing()
         }
         host.updateSurfacePhase(DictationSurfaceState.Phase.PROCESSING)
         host.promoteToForeground(processing = true)
         host.vibrate(HapticCue.SESSION_TRANSITION)
         log.log("Stopping recording and starting transcription")
+        return true
+    }
 
-        Thread({
-            var audioReady = false
+    /**

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '184,198p'
nl -ba app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt | sed -n '822,842p'
nl -ba app/src/test/java/com/envi/wispr/audio/RecorderLeaseTest.kt | sed -n '27,45p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
   184	     * Serialises the final state check, the ledger open, the watchdog launch and the binder call against
   185	     * `cancelProcessing` (#75): without it the transcription thread can read PROCESSING, lose the CPU to a
   186	     * cancel that closes an empty ledger, and then send a request nothing will ever cancel.
   187	     */
   188	    private val polishSubmissionLock = Any()
   189	    private val teardownStarted = AtomicBoolean(false)
   190	    private val draftId = AtomicLong(0L)
   191	    /** The injected scope's job, read once; `destroy` cancels and joins exactly this. */
   192	    private val serviceJob: Job = requireNotNull(scope.coroutineContext[Job]) { "the session scope needs a Job" }
   193	
   194	    private var rawTranscript = ""
   195	    // What the START of this dictation saw when it tried to pin an editor. Read at the end,
   196	    // by which time the service may have been replaced. NO_TARGET until a session begins, so
   197	    // a value that outlived its session can only ever suppress an announcement, never invent
   198	    // one.
   822	            .onFailure { DebugLogger.warn(TAG, "Failed to flush audio file: ${it.message}") }
   823	        runCatching { output?.close() }
   824	            .onFailure { DebugLogger.warn(TAG, "Failed to close audio file: ${it.message}") }
   825	        runCatching { record?.stop() }
   826	            .onFailure {
   827	                if (it !is IllegalStateException) DebugLogger.warn(TAG, "Failed to stop AudioRecord: ${it.message}")
   828	            }
   829	        val released = record == null || runCatching { record.release() }
   830	            .onFailure { DebugLogger.warn(TAG, "Failed to release AudioRecord: ${it.message}") }
   831	            .isSuccess
   832	        // Every caller acquired the lease before creating the recorder (or failing to); released only
   833	        // AFTER the recorder was, so the next start in this process cannot open a second one first. A
   834	        // recorder whose release failed is still held by the native layer for all this code knows, so the
   835	        // lease stays held until the process dies (#115 review round 2).
   836	        if (released) {
   837	            RecorderLease.PROCESS.release()
   838	        } else {
   839	            DebugLogger.warn(TAG, "Recorder release failed; the process's recorder lease stays held")
   840	        }
   841	    }
   842	
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
    37	        assertTrue("the lease is released after the recorder is", close.indexOf("RecorderLease.PROCESS.release()") > close.indexOf("record.release()"))
    38	        assertTrue("and only when that release succeeded (or there was no recorder)", close.contains("val released = record == null || runCatching { record.release() }") && close.contains("if (released) {\n            RecorderLease.PROCESS.release()"))
    39	        assertTrue("and only there", Regex("RecorderLease\\.PROCESS\\.release\\(\\)").findAll(service).count() == 1)
    40	    }
    41	}

exec
/bin/zsh -lc "rg -n \"private fun handleServiceFailure|override fun onCaptureDisconnected|AUDIO_PROCESS_DIED\" app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '470,490p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1618,1635p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
451:    override fun onCaptureDisconnected() {
457:            handleServiceFailure(TerminalReason.AUDIO_PROCESS_DIED)
1635:    private fun handleServiceFailure(reason: TerminalReason) {
   470	            } else if (arbiter.commitNow(TerminalReason.ASR_PROCESS_DIED)) {
   471	                updateDraftStatus(TranscriptEntity.STATUS_ASR_ERROR, insertionResult = "asr_error")
   472	                endAsFailure(TerminalReason.ASR_PROCESS_DIED)
   473	            }
   474	        }
   475	    }
   476	
   477	    override fun onPolishConnected() {
   478	        // Warm at connect, measured and decided (#72): every later moment ends with the same two
   479	        // models resident, because the speech model stays loaded after it transcribes, and costs the
   480	        // user 0.9 to 3.1 s of wait. `architecture-rules.md` RULE: isolate-limbs carries the numbers.
   481	        runCatching { pipeline.polish?.warmUpWithPolicy(sessionPreferences.policy) }
   482	        log.log("Polish service connected")
   483	    }
   484	
   485	    override fun onPolishDisconnected() {
   486	        log.warn("Polish service disconnected")
   487	        if (state.get() == SessionState.PROCESSING) {
   488	            if (rawTranscript.isNotBlank()) {
   489	                publishFallback(rawTranscript, sessionPreferences, PolishReason.SERVICE_DIED)
   490	            } else if (arbiter.commitNow(TerminalReason.POLISH_PROCESS_DIED)) {
  1618	        log.warn("Take ended while starting: $reason")
  1619	        announceError(TakeNotices.line(reason))
  1620	        return true
  1621	    }
  1622	
  1623	    /** Tears the take down as a failure; says [line] when there is one. Teardown never depends on copy. */
  1624	    private fun announceError(line: String?) {
  1625	        cancelOpenPolishRequest()
  1626	        surface.showProcessing()
  1627	        insertion.releasePinnedTarget()
  1628	        host.updateSurfacePhase(DictationSurfaceState.Phase.IDLE)
  1629	        host.vibrate(HapticCue.FAILURE)
  1630	        if (line != null) host.postToMain { host.toastFromService(line) }
  1631	        pipeline.stopAudioService()
  1632	        finishSession()
  1633	    }
  1634	
  1635	    private fun handleServiceFailure(reason: TerminalReason) {

exec
/bin/zsh -lc "nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '445,462p'
nl -ba app/src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt | sed -n '1633,1643p'" in /Users/m4pro_sv/Developer/EnviousLabs/EnviousWispr-Android/.claude/worktrees/issue-115-push-not-poll
 succeeded in 0ms:
   445	
   446	    override fun onCaptureConnected() {
   447	        log.log("Audio capture connected")
   448	        tryStartRecording()
   449	    }
   450	
   451	    override fun onCaptureDisconnected() {
   452	        log.warn("Audio capture disconnected")
   453	        // STARTING too: since the live gate, capture runs while the lips spin, and a waiter whose
   454	        // binder vanished returns without ending the take (Codex review 2, 2026-09-18).
   455	        val seen = state.get()
   456	        if (seen == SessionState.RECORDING || seen == SessionState.STARTING) {
   457	            handleServiceFailure(TerminalReason.AUDIO_PROCESS_DIED)
   458	        }
   459	    }
   460	
   461	    override fun onSpeechConnected() {
   462	        log.log("Speech service connected")
  1633	    }
  1634	
  1635	    private fun handleServiceFailure(reason: TerminalReason) {
  1636	        if (state.get() == SessionState.RECORDING) discardDraft()
  1637	        showError(reason)
  1638	    }
  1639	
  1640	    private fun finishSession() {
  1641	        if (state.getAndSet(SessionState.FINISHING) == SessionState.FINISHING) return
  1642	        cancelOpenPolishRequest()
  1643	        surface.showProcessing()

codex
Whole #115 review — DONE

## Findings

1. **P2, REPRODUCIBLE: the approved plan no longer describes the implementation it is supposed to gate.** The largest mismatches are `issue-115-2026-09-21-push-not-poll.md:231-234` omitting `takeId`, `:283-284` claiming four listener members instead of two, `:360-373` describing a queued clean marker and instance-local recorder protection, `:418` describing an interrupted History row although `DictationSessionCoordinator.kt:1635-1637` discards the draft while RECORDING, and `:475-489` naming superseded or nonexistent tests. `DictationSessionCoordinator.kt:191` also still says destroy joins the service job.  
   **Drop-in fix:** update §3, §7, §11.2, and the field KDoc to the final listener signatures, command lane, two registration members, synchronous clean marker, process-scoped recorder lease, actual persisted-state behavior, and actual test names before the PR.

2. **P1, REPRODUCIBLE release gate: the whole-build hardware evidence required by §13 does not exist yet.** `issue-115-2026-09-21-push-not-poll.md:496-500` requires the emulator scenes on the completed change, but the supplied run used chunk A only; scene (e) has not exercised chunks B and C, and scene (g)’s transcription requirement was not met because of #204.  
   **Drop-in fix:** install one build from `d3e54b5` or its Phase 3 successor and run scenes (c), (d), (e), and (g); for (g), either prove transcription on a capable target or amend the #115 criterion to close only the pushed cap, warning, and no-false-bound behavior while #204 owns long-clip transcription.

## Axes (W1..W7)

- **W1: NOT FOUND.** `TakeArbiter` owns terminal first-wins, `endingConsumed` drops duplicate endings, `publishLock` serializes live, cancel, finalization, and destroy, and the silence-bound CAS permits one bound ending. Ending, disconnect, bound, cancel, and destroy races converge on one committed result.
- **W2: NOT FOUND.** All capture calls go through `commandCapture`; the only direct `pipeline.capture` read is the local null check before cancel. Main has no blocking wait. The queue worker only awaits the draft ID produced by an earlier queued insert. The save-result suspension is on IO and preserves the pre-existing durability-before-route contract.
- **W3: NOT FOUND.** The UUID flows through capture start, every pushed event, `ours`, journal association, ASR, polish, telemetry, and insertion handoff. History uses its database row ID, and the recorder picture uses its intentional surface serial; neither substitutes for take identity.
- **W4: NOT FOUND.** `IAudioCaptureService` only appends the take-listener pair, legacy transactions remain, and `ITakeListener` is new. The requested search found no production getter caller: remaining `app/src/main` hits are AIDL declarations, service Stub implementations, and comments; the other callers are the separately installed `androidTest` client.
- **W5: NOT FOUND.** The polling threads are gone, delayed callbacks are disarmed at finish and destroy, the command lane is shut down at destroy, the publisher parks without a timer, and the application History consumer suspends on its channel rather than owning an idle thread.
- **W6: FOUND.** Finding 1; production code is internally consistent, but the plan and one coordinator comment are stale.
- **W7: FOUND.** I would not ship or open the final PR until the whole-build emulator gate in Finding 2 is completed or explicitly narrowed with #204 named.

## COVERAGE

Read the full production diff for all 17 files: `IAudioCaptureService.aidl`, `ITakeListener.aidl`, `AudioCaptureService.kt`, `DetectorFeed.kt`, `RecorderLease.kt`, `TakeEventPublisher.kt`, `HistoryWriteQueue.kt`, `ModelBootstrapApplication.kt`, `PasteAccessibilityService.kt`, `TelemetryChannels.kt`, `DictationSessionCoordinator.kt`, `DictationSessionService.kt`, `PipelineBindings.kt`, `PipelineLinks.kt`, `SessionHost.kt`, `TakeNotices.kt`, and `TerminalReason.kt`.

Also read plan §3, §7, §11.2, and §13; ran the requested caller search; and checked the permitted `87e07ca` finalization path. Accepted all supplied test, receipt, visibility, and emulator facts without rerunning them.

## VERDICT: FINDINGS
tokens used
1,254,859
Whole #115 review — DONE

## Findings

1. **P2, REPRODUCIBLE: the approved plan no longer describes the implementation it is supposed to gate.** The largest mismatches are `issue-115-2026-09-21-push-not-poll.md:231-234` omitting `takeId`, `:283-284` claiming four listener members instead of two, `:360-373` describing a queued clean marker and instance-local recorder protection, `:418` describing an interrupted History row although `DictationSessionCoordinator.kt:1635-1637` discards the draft while RECORDING, and `:475-489` naming superseded or nonexistent tests. `DictationSessionCoordinator.kt:191` also still says destroy joins the service job.  
   **Drop-in fix:** update §3, §7, §11.2, and the field KDoc to the final listener signatures, command lane, two registration members, synchronous clean marker, process-scoped recorder lease, actual persisted-state behavior, and actual test names before the PR.

2. **P1, REPRODUCIBLE release gate: the whole-build hardware evidence required by §13 does not exist yet.** `issue-115-2026-09-21-push-not-poll.md:496-500` requires the emulator scenes on the completed change, but the supplied run used chunk A only; scene (e) has not exercised chunks B and C, and scene (g)’s transcription requirement was not met because of #204.  
   **Drop-in fix:** install one build from `d3e54b5` or its Phase 3 successor and run scenes (c), (d), (e), and (g); for (g), either prove transcription on a capable target or amend the #115 criterion to close only the pushed cap, warning, and no-false-bound behavior while #204 owns long-clip transcription.

## Axes (W1..W7)

- **W1: NOT FOUND.** `TakeArbiter` owns terminal first-wins, `endingConsumed` drops duplicate endings, `publishLock` serializes live, cancel, finalization, and destroy, and the silence-bound CAS permits one bound ending. Ending, disconnect, bound, cancel, and destroy races converge on one committed result.
- **W2: NOT FOUND.** All capture calls go through `commandCapture`; the only direct `pipeline.capture` read is the local null check before cancel. Main has no blocking wait. The queue worker only awaits the draft ID produced by an earlier queued insert. The save-result suspension is on IO and preserves the pre-existing durability-before-route contract.
- **W3: NOT FOUND.** The UUID flows through capture start, every pushed event, `ours`, journal association, ASR, polish, telemetry, and insertion handoff. History uses its database row ID, and the recorder picture uses its intentional surface serial; neither substitutes for take identity.
- **W4: NOT FOUND.** `IAudioCaptureService` only appends the take-listener pair, legacy transactions remain, and `ITakeListener` is new. The requested search found no production getter caller: remaining `app/src/main` hits are AIDL declarations, service Stub implementations, and comments; the other callers are the separately installed `androidTest` client.
- **W5: NOT FOUND.** The polling threads are gone, delayed callbacks are disarmed at finish and destroy, the command lane is shut down at destroy, the publisher parks without a timer, and the application History consumer suspends on its channel rather than owning an idle thread.
- **W6: FOUND.** Finding 1; production code is internally consistent, but the plan and one coordinator comment are stale.
- **W7: FOUND.** I would not ship or open the final PR until the whole-build emulator gate in Finding 2 is completed or explicitly narrowed with #204 named.

## COVERAGE

Read the full production diff for all 17 files: `IAudioCaptureService.aidl`, `ITakeListener.aidl`, `AudioCaptureService.kt`, `DetectorFeed.kt`, `RecorderLease.kt`, `TakeEventPublisher.kt`, `HistoryWriteQueue.kt`, `ModelBootstrapApplication.kt`, `PasteAccessibilityService.kt`, `TelemetryChannels.kt`, `DictationSessionCoordinator.kt`, `DictationSessionService.kt`, `PipelineBindings.kt`, `PipelineLinks.kt`, `SessionHost.kt`, `TakeNotices.kt`, and `TerminalReason.kt`.

Also read plan §3, §7, §11.2, and §13; ran the requested caller search; and checked the permitted `87e07ca` finalization path. Accepted all supplied test, receipt, visibility, and emulator facts without rerunning them.

## VERDICT: FINDINGS
