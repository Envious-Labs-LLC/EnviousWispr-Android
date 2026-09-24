# Issue #176 — Telemetry: PostHog for behaviour, Sentry for defects — 2026-09-19

GitHub issue: `#176`. Tier: REFACTOR (founder 2026-09-19). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code. `mixed_pr: true`: `Code` (`app/**`, `app/build.gradle.kts`), `CI/workflow` (`.github/workflows/play-internal.yml`
and `pr-check.yml` gain two build variables), `Docs/dev-tooling` (`docs/play-data-safety-answers.md`, `.claude/knowledge/`).

**PAR rows closed:** `PAR-093` (content-free events with stable session and failure identities). Evidence: the sanitizer
tripwire test at the exact vendor payload seam, the closed `TerminalReason` (proposed) vocabulary, and one dictation on the
S26 visible in both vendors under one install id and one take id.

**Hardware UAT:** Y. The founder dictates one take into Messages from the bubble on the S26 (Play build). Within two minutes
PostHog shows one `dictation.terminal` row for that take with `result=completed`, the insertion route, and no text; Sentry shows
a session for the same install id and, after a forced test defect on a debug build, one error carrying the same
`analytics.distinct_id` and `dictation.take_id`. Nothing dictated appears in either vendor.

## Preface — User Rubric

1. **Who.** Priya Ramachandran, on her S26 in Slack, dictating a reply in a thread. Thirty seconds ago she held the bubble
   and spoke; now she wants the words in the field and to move on. The take fails ("Transcription failed"). She will not
   file a report; she will try once more and then type.
2. **Why.** "I want it fixed without having to explain it to anyone." The app should already know what went wrong on her
   phone.
3. **Invoke.** Never. Telemetry is reactive and invisible; it fires when she dictates and when the app starts.
4. **App.** Slack, GitHub, Notion, Chrome. The target app name is metadata we keep (as macOS keeps `target_app`).
5. **Natural input.** Not applicable: nothing she says is ever captured by this feature. Five samples are deliberately
   absent, because the feature's contract is that her words never leave the phone.
6. **Success.** She notices nothing. Two weeks later a build fixes the failure she hit, because 30 other people hit the
   same closed reason on the same build and the daily report ranked it.
7. **Wrong-not-broken.** Telemetry that records "Transcription failed" as a sentence: 30 people look like 30 different
   problems, nothing gets ranked, nothing gets fixed, and she quietly stops trusting the app.
8. **Power-user hack.** She turns on the debug log and sends it to us. That log is content-free by rule and stays the
   deep-dive channel; telemetry is the fleet channel.
9. **Control.** None in this change, matching macOS, which ships no telemetry toggle. Dr. Elena's ladder (off, on) is a
   product question for the founder (§14).

**Dr. Elena Vasquez** is the second persona this rubric is answered against: HIPAA-adjacent, must be able to say to IT
"nothing spoken leaves the phone". Her test is the Play Data Safety form and the in-app privacy screen agreeing with the
code. This change edits both (§10) and the tripwire test is her evidence.

### Cross-persona check

Marcus, Diana, Meera, Frank: invisible, same as Priya. Aaron: the trigger surface is recorded per take, so a foot-pedal or
side-button failure is visible as its own population. Elena is the tension: she would want a toggle; macOS shipped none and
the boundary is defended by the sanitizer, not by a switch. Resolved in §3 as "no toggle in this change, founder decides".

---

## 0. TL;DR

Android has no telemetry. Build it as one small `telemetry/` package with one facade, typed events only, Sentry in all five
processes and PostHog in the main process, one anonymous install id shared by both vendors and every process, one take id
per dictation, one sanitizer at the vendor payload seam, and one volume gate that keeps the wire at roughly two PostHog rows
per successful dictation. Two refactors make the data queryable: every way a take ends becomes a closed `TerminalReason`
(proposed) instead of a user sentence, and the ASR process reports a closed failure code instead of a free string.
Evidence: unit tests at the payload seam, one phone take visible in both vendors under the same ids, and the Mac daily
report unchanged after Android rows arrive.

**Consolidation.** Dominant root: a take's failure identity is a user SENTENCE chosen at the failing call site, so the
same failure has as many identities as call sites. One owner: `TerminalReason` (proposed) in `ui/TerminalReason.kt`,
with `ui/TakeNotices.kt` (proposed) deriving the sentence. Consolidation sites: the 18 `showError` /
`failWhileStarting` / `handleServiceFailure` literals in `ui/DictationSessionService.kt`, the 5 `onError(String)` call
sites in `asr/AsrService.kt` (which become `AsrFailureReason` codes), and the `onDestroy` teardown branch that ends a take
without a reason today. The History status and `insertionResult` writers are NOT consolidated: they already share one
vocabulary (`InsertionResults`) and the telemetry reads it.

## 1. Problem

- Zero telemetry: `docs/play-data-safety-answers.md` FACT: what-the-app-actually-sends. A crash on a stranger's phone is
  invisible; stage 2 ("a stranger's phone works with no hand-holding") cannot be judged.
- Failure identity is a sentence. `DictationSessionService.showError(String)` has 18 call sites, each passing a user
  sentence (`app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:284-921`). `IAsrCallback.onError(String)`
  carries `e.message` (`asr/AsrService.kt:86,142`). macOS shipped exactly this and paid for it: `pipeline.failed.error_code`
  carried a sentence before 2.4.0 and a token after, and a query on the token undercounted 13x
  (`~/Developer/EnviousLabs/EnviousWispr/.claude/knowledge/analytics-operations.md` RULE:
  enum-backed-properties-carry-retired-vocabularies-split-by-version).
- No install identity and no take identity, so even with vendors wired a Sentry error could not be joined to that phone's
  success count, which is the exact shape behind macOS #1788 and #1809 (two wrong diagnoses).

## 2. Goals & non-goals

### 2.1 Goals
1. A crash in any of the five processes reaches Sentry with release, environment, process name, install id and, in the
   main process, the take id.
2. Every dictation that ends leaves exactly one PostHog `dictation.terminal` row with a closed `result`, and one
   `insertion.terminal` row when insertion was attempted; a take that never ends (process death) becomes a
   `dictation.interrupted` row at the next launch.
3. No dictated content, prompt, editor text or key can reach either vendor: a test asserts the bytes at the vendor seam.
4. The Mac daily report and weekly digest keep reporting Mac numbers after Android rows arrive in the shared project.
5. The Play Data Safety answers and the in-app privacy copy say what the code now does.

### 2.2 Non-goals
- Saving the recording across a crash (#43).
- Feature flags, surveys, session replay, tracing, profiling, screenshots: all off, none wired.
- A telemetry toggle (product question, §14).
- Android rows in the Mac Discord notifier, TIK triage routine or version scorecard: a follow-up issue (§15).
- Native symbol upload for sherpa-onnx / GenieX frames: stage 2 follow-up (§14).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

**A take.** `VoiceInputActivity` (ASSIST, and the launcher the tile and bubble go through) / `DictationTileService` / the bubble
(`PasteAccessibilityService` via `BubbleRequests`) send `ACTION_START` or `ACTION_TOGGLE` to (the notification sends only STOP and CANCEL, Codex Q6)
`DictationSessionService` (`ui/DictationSessionService.kt:122-125,388-407`). The owner binds `:audio`, `:asr`, `:polish`
(`:516-538`), moves `IDLE→STARTING→RECORDING→PROCESSING→FINISHING` (`:143`), inserts a draft History row when capture goes
live (`:649`), receives the transcript over `IAsrCallback.onResult/onError` (`:902-921`), the polish over
`IPolishCallback.onOutcome` (`:972`), writes the final row (`publishResult`, `:1082`), hands insertion to
`PasteAccessibilityService.pasteWhenTargetReturns` (`:1147`) and stops itself (`finishSession`, `:1419`). The accessibility
service finishes insertion later and writes `insertionResult` to the same row (`InsertionResults.kt`,
`TranscriptDao.finalizeInsertionOutcome`). A dead process leaves `draft`/`processing`/`ready_for_insertion` rows that
`TranscriptDao.recoverStaleDrafts` / `recoverStaleReadyRows` (`history/TranscriptDao.kt:95-105`) repair at the next start.

**Interception points that can see the real payload on every path:** the state transitions of the owner (every terminal
goes through `showError`, `failWhileStarting`, `finishSession`, `cancel*`, or `onDestroy`), the one History row per take,
and `Application.onCreate` in every process (`models/ModelBootstrapApplication.kt:7-12` already runs there and gates on
process name).

**Failure identity across the binder** (Q2 of the Codex trace, `scratchpad/codex-trace.txt`): `:audio` reports closed int
codes (`getTerminalReason`, `getLastStartFailure`, `getInputRouteKind`, `getInputRouteReason`,
`aidl/.../IAudioCaptureService.aidl`); `:polish` reports `PolishOutcome` with the closed `PolishReason`
(`polish/PolishReason.kt:12`); `:asr` reports a free `String` (`IAsrCallback.onError(String)`); `:vad` reports over
`ISilenceVadService` getters plus process death. `IPolishService.getStatus()` and `localModelStatus()` return display
sentences (`polish/PolishService.kt:110,172,428,445`): local-only, never a telemetry identity (C1 G1).

### 2. Existing authority

- Content-free logging: `debug/DebugLogger.kt` (logcat plus an opt-in file). It takes free strings; it is the local channel,
  not a wire channel, and stays.
- Closed vocabularies that already exist and are reused verbatim as property values: `PolishReason`,
  `InsertionResults`, `InsertionHandoff`, `ServiceFallbackReason`, `ClipboardOutcome`, `InputRouteKind`,
  `InputRouteReason`, `AudioCaptureService.START_FAILURE_*`, `TranscriptEntity.STATUS_*`, `OnboardingStage`,
  `DownloadState`, `PolishProvider`.
- Privacy enforcers: `privacy/PrivacyDisclosure.kt` (exhaustive `when` over providers) and
  `cpp/geniex_log_silencer.cpp`. The new sanitizer is a third enforcer and is listed beside them in `CLAUDE.md`.
- No install id exists: `settings/AppPreferences.kt:204-221` keys are onboarding and settings only; `grep -rn
  "install_id\|installId\|anonymousId\|distinct" app/src/main/java` returns nothing (run 2026-09-19). `new authority
  proposed`: `telemetry/InstallIdentity.kt`.
- No trigger-source field on the START command today (Codex Q6).

### 3. Prior attempts and live direction

Gate 0 comment on #176. Catalog: `privacy-safe-telemetry` shipped on macOS and Windows; the Windows row's "gate refuses
any identifier-shaped property" is the same defence as the sanitizer here. Decisions binding on this plan: Sentry for
bugs, PostHog for behaviour (founder 2026-07-09); identity is not the boundary, content is (founder 2026-09-15); the PostHog
`app` tag is mandatory (shared project); "efficiency without losing the information we need" (founder 2026-09-15, #2958).

### 4. Boundaries a naive design would miss

| Boundary | Current | Planned |
|---|---|---|
| Five processes | `Application.onCreate` runs in each; only main does work | Sentry in each with `app.process` tag; PostHog main only; install id read from one file by all |
| Session owner stops itself after every take | Any state on the service resets per take | Take id and arbiter live in the owner instance for the take; the immutable take context is handed to insertion and to the journal before the service stops; process-scoped state (install id, bootstrap, process-run id) lives in `object`s |
| Insertion finishes after the owner is gone | Three writers: the owner for the never-handed-off outcomes (`keepOnClipboard` `:1229`, `keepInHistoryOnly` `:1288`, including the no-row clipboard path `:1237`), the accessibility service for accepted insertions (`PasteAccessibilityService.kt:879,1347,1389`), and stale-ready recovery (`TranscriptDao.kt:101`) | `insertion.terminal` has exactly those three emitters, each at its own terminal write, all keyed by take id (recovery keys by the row's take id column, see §4); the `handoff` value says which (C1 G3) |
| Process death mid-take | Room recovery repairs the History row silently, by age, from two call sites | The take journal (§3.3) owns the count and is read independently of History: an earlier-run entry with no DURABLE terminal is `dictation.interrupted{stage}` ("no durable terminal was recorded", never proof of a crash). A recovered `ready_for_insertion` History row proves that TEXT was persisted (`:1114`), not that the journal terminal committed (`:1135`) nor that any row was delivered; insertion recovery repairs it and emits `insertion.terminal{result=insertion_interrupted}` only when the journal's `transcriptId` association exists. Both recovered outcomes may exist for one take when death fell between `:1114` and `:1135`; neither claims insertion succeeded (C1 G4, G1 D3, G3 B1) |
| Dev vs Play build | `BuildConfig.DEBUG` | `environment=development` on debug, `production` on release; the founder's Play builds are production dogfood, as on Mac |
| Shared PostHog project | Mac workers filter `environment='production'` only | Android stamps `app=enviouswispr-android`; Mac workers add `coalesce(properties.app,'enviouswispr')='enviouswispr'` BEFORE the first Android production build (§13) |

### 5. High-risk premises

| Premise | Evidence |
|---|---|
| Sentry Android can be initialised per process | `ManifestMetadataReader.java` (external) key `io.sentry.auto-init`; per-process init in `Application.onCreate` (Codex known-problem pass, Q3) |
| PostHog Android has a pre-queue hook and an anonymous-id hook | `PostHogConfig.kt` (external) line 177 `getAnonymousId: ((UUID) -> UUID)`, `:527-545` `addBeforeSend(PostHogBeforeSend)` (source fetched 2026-09-19, core-v6.40.0) |
| PostHog autocapture defaults are ON and must be turned off | `PostHogAndroidConfig.kt:47-53` (external): `captureApplicationLifecycleEvents=true`, `captureDeepLinks=true`, `captureScreenViews=true`; `PostHogConfig.kt` (external) line 166 `sessionReplay=false`, `:62` `sendFeatureFlagEvent=true`, `:74` `preloadFeatureFlags=true` |
| PostHog exception autocapture is off by default | `PostHogErrorTrackingConfig.kt` (external) line 22 `autoCapture=false`, `:95` `captureNativeCrashes=false`; pinned explicitly, same reason as the Mac (#2958 note) |
| Sentry automatic breadcrumbs default ON | `SentryAndroidOptions.java:62-74` (external) (activity, app lifecycle, system events, app components, network events all `true`); `:184` `enableNdk=true`; `:34` `anrEnabled=true` |
| The draft row exists only from RECORDING | `DictationSessionService.kt:649` inside `publishLive`; a death in STARTING leaves no row (accepted gap, §5) |
| Sentry org has no Android project | `GET /organizations/envious-labs-llc/projects/` 2026-09-19: `enviousstaging-portal`, `enviouswispr` |
| Release builds are not minified | `app/build.gradle.kts` has no `buildTypes.release` block; `scripts/release/build.sh` runs `bundleRelease` |

Codex problem-only trace: `scratchpad/codex-trace.txt` (Q1-Q10), adjudicated in §2.5.6 below.

### 6. Codex trace adjudication (`scratchpad/codex-trace.txt`, 27 of 27 files read, 2026-09-19)

| Finding | Evidence | Adjudication |
|---|---|---|
| Sentry auto-init runs in the DEFAULT process only; helper processes get nothing unless the app initialises there | SDK manifest provider has no process declaration; `io.sentry.auto-init=false` + `SentryAndroid.init` is the documented manual shape | Adopted: per-process init in `Application.onCreate` above the process gate (§3.2). Vendor page for "each process" not retrievable by Codex; the SDK source is the authority |
| 36 take-ending routes: 16 closed, 18 sentence, 2 with no reason at all (empty ASR result `D:928,931`; both texts empty `D:1097`) | Q1 table | Adopted: `TerminalReason` covers all 36; the two silent routes become `asr_empty` (§3.1 ASR row) |
| The once-only guard is weaker than assumed: `showError` rejects only an already-ERROR state and ASR `onError` has no state guard (`D:909,1385,1420`); ASR can call `onError` after a throwing `onResult` (`AsrService.kt:139,142`) | Q1 guards, Q9 trap 5 | Superseded by G1 and G2: the reserve-then-commit arbiter in §3.2 is the authority; a first-wins latch is not an implementation instruction |
| ASR and polish deaths are indistinguishable (`SERVICE_DIED`, History `asr_error` for a polish death) | Q3(d) | Adopted: `TerminalReason` has `audio_process_died`, `asr_process_died`, `polish_process_died`; the History status is unchanged |
| `:audio` disconnect during PROCESSING has no terminal branch (`D:277`) | Q3(d) | Accepted as-is: the file is already on disk; a breadcrumb records it, no terminal |
| Polish kills itself 300 ms after `LOCAL_TIMEOUT`; VAD kills itself after a 2 s wedged call (`PolishService.kt:270`, `SilenceVadService.kt:168`) | Q3(c) | Superseded by G1 and G2: no network wait before a kill; §3.2 "Reporting before a deliberate death" is the authority. The owner's later disconnect is not a second terminal |
| `captureDevice` on the History row is a display label, not a code | Q5 | Adopted: never sent; `route_kind` / `route_reason` codes are sent instead |
| History is not the full population (draft only from live; silent and cancelled drafts deleted) | Q9 trap 10 | Superseded by G1: the take journal (§3.3) is the denominator and records admission before capture; post-live-only coverage is not an implementation instruction |
| No install id anywhere; no `BuildConfig` use; debuggable read via `FLAG_DEBUGGABLE` in polish | Q4 | Adopted: `InstallIdentity` is new; environment from `BuildConfig.DEBUG` |
| Notification has no START; tile and ASSIST both collapse to TOGGLE through `VoiceInputActivity`; app and practice senders unverified | Q6, then C1 G2 | Adopted: `TriggerSource` = `bubble_tap`, `bubble_hold`, `assist`, `tile`, `app`, `unknown`; `VoiceInputActivity` stamps `assist` from its action and forwards the tile's and bubble's extras. Senders enumerated by C1 (activity launchers as well as `sendCommand` callers): `ui/SettingsActivity.kt:66` (launcher intent → `app`), `paste/RecordingAccessibilityOverlay.kt:511` (the bubble's activity-fallback start keeps tap/hold identity through the extra), `shortcuts/DictationTileService.kt:43,47` (both paths → `tile`), `paste/PasteAccessibilityService.kt:427` (direct bubble start), `ui/VoiceInputActivity.kt:47` (ASSIST). Practice (`ui/OnboardingViewModel.kt:217,231`) observes a bubble take aimed at the admitted own field and is not a surface: modified from C1's paste, which mapped it to `practice` |
| Model delivery reasons are free strings and the host is only logged | Q7 | Adopted, bounded: a closed `DeliveryFailureReason` (proposed) chosen at the store's catch sites, plus `source_host`, both carried in the worker output beside the existing message |
| No energy summary crosses the audio binder, so `no_speech` vs `asr_empty_despite_audio` cannot be told apart | Q2 | Adopted: append `getTakePeakAmplitude()` to `IAudioCaptureService`; `asr_empty` splits on it. Absent means not measured, never zero (Mac #1809) |
| WorkManager placement in main is the supported reading, not verified | Q3(b) | Verified in chunk B by a log line carrying the PID from `ModelDeliveryWorker` |
| (G1 §2.5.6 note) the earlier `TranscriptEntity.takeId` column | G2 D10 | Superseded: the association lives in the journal's nullable `transcriptId` (§4); transcript columns are untouched |

### 7. Grounded review G1 adjudication (`scratchpad/codex-grounded.txt`, verdict PROCEED-WITH-REVISIONS, all ten axes found)

| Axis | Finding | Adjudication |
|---|---|---|
| D1 | Sentry's cache, outbox and scope files are per SDK instance; five processes sharing `cacheDir/sentry` would fight (`AndroidOptionsInitializer.java` (external) line 518, `EnvelopeCache.java` (external) line 61) | Adopted: `cacheDirPath = cacheDir/sentry-<process>` per process; helper caches are retried when that helper next starts |
| D2 | A single first-wins reason latch conflates CLAIMING an ending with KNOWING its outcome: `completed` is unknown until the History save returns (`D:1090,1110,1135`), `cancelled` is unknown until the safe close (`D:1329,1336`), the empty branches bypass `showError` (`D:928,1097`), a coroutine launched in `onDestroy` can be cancelled with the row (`D:1633`), `finishSession` cannot infer a reason (`D:931,1204,1326,1373,1411`) | Adopted: a reserve-then-commit arbiter (§3.2 refactor 1) with the exact insertion points Codex named |
| D3 | History is not a reliable denominator: the draft insert is async after RECORDING is published (`D:633-638`), wordless and cancelled drafts are deleted (`D:929,1339,1523`), recovery is age-based and runs from two places (`HistoryRecovery.kt:4`, `D:337`, `AppViewModel.kt:248`), a Room row does not prove the SDK accepted the row; SIGKILL and low-memory kills produce no Sentry crash | Adopted: a content-free local take journal (§3.3) records admission before capture, stage, and terminal facts with the event uuid; recovery keys on process-run identity, not row age; the reported denominator is stated as a measurement boundary. `dictation.started` stays OFF the wire |
| D3b, D3c | Two rows per take are justified; settings folded into `app.launched` are fine if read from persisted preferences, not UI defaults | Adopted as-is |
| D4 | The channel matrix was too broad: `CLEANUP_RECOVERED` is ours (D); `MALFORMED_RESPONSE`, `SERVICE_DIED`, `SERVICE_UNAVAILABLE`, `CALL_FAILED` (as a combined member) do not prove our fault (B); `LOCAL_TIMEOUT` is one defect reported by main; every `InsertionResults` and `InsertionHandoff` value is B; `over_limit` is ours (capture enforces the same ceiling, `AsrService.kt:64`); the ASR catch boundaries mislabel read vs decode vs delivery (`AsrService.kt:77,139`); `TerminalReason` had no member table | Adopted in full: §3.6 is the literal channel table; `AppDefect.PolishProtocolViolation` is raised at `D:979` beside `CALL_FAILED`; `StillRunning` after stop is D; the ASR catch sites are narrowed in chunk A |
| D5a | Atomic rename does not elect one UUID (both renames succeed; `AtomicFile` (external) documents no locking); minting a replacement on an unreadable file forks identity from PostHog's persisted id; `getAnonymousId` is consulted only when the SDK has nothing persisted (`PostHog.kt` (external) line 537) | Adopted: file lock plus process mutex, mint only when absent, unreadable disables telemetry for that bootstrap, SDK id verified equal to the file id before any capture, mismatch disables analytics |
| D5b | Time-based joins are ambiguous while an old helper is still exiting; polish `requestId` is a `Long` for arbitration (`PolishRequestLedger.kt:20`) | Adopted: the take UUID rides on appended versioned request methods for audio, ASR and polish, forwarded from audio to VAD; `requestId` stays for arbitration; helper Sentry tags use compare-and-clear |
| D6 | `Sentry.flush(1000)` waits for queue idleness and acknowledges nothing (`AsyncHttpTransport.java` (external) lines 113 and 248); VAD kills to release a wedged binder call, so waiting there is wrong | Adopted: never wait for the network before a deliberate kill. Main reports `LOCAL_TIMEOUT` as the one defect. A dying process writes a content-free pending-defect record atomically; any later bootstrap converts it with the same event id |
| D7 | Length-and-pattern redaction admits short transcripts and arbitrary URLs; `input_device_pick` is `type|name` with the product name (`AppPreferences.kt:219`, `InputDevicePick.kt:14,18`); Android paths and `content://` URIs; the HTTP client logs class names, not `e.message` (`ProviderPolishClient.kt:757`); native prose can carry the prompt (`geniex_log_silencer.cpp:9`) | Adopted: allowlist-first sanitizer (§3.7), settings projections, closed `source_host` enum, exception type kept and message dropped unless allowlisted, Android path and URI rules, final-payload tests. Native crash events pass the Java `beforeSend` (`OutboxSender.java` (external) line 147, verified 2026-09-19), non-event envelope items do not, so attachments stay off |
| D8 | `app.launched` in a UI owner misses service-only starts (`AndroidManifest.xml:54,70,115`); `setDefaultPersonProperties` is about flag person properties, not event context (`PostHogAndroidContext.kt` (external) line 27); app-component breadcrumbs are not service-binding breadcrumbs | Adopted: `app.launched` is owned by the main-process bootstrap once per process run; SDK-added event context is reduced to an allowlist in `beforeSend`; typed bind/connect/disconnect/unbind breadcrumbs at our three service connections |
| D9 | Most named reverts would not turn the named tests red (an added `else` changes no result; an unsampled policy makes the hash irrelevant; enum-generated populations shrink with the member; the bootstrap exemption proves too little) | Adopted: §11 rewritten with literal expected tables, isolated witnesses, cross-process races, and a device validation list per process; classes relabelled per `testing-philosophy.md` |
| D10 | An appended `onFailure` still breaks the old test APK because the service would stop sending the callback it implements (`VoicePipelineDeviceTest.kt:122`, `architecture-rules.md:49`); "remove the repository variable" is not a rollback for installed APKs; the STARTING gap conflicts with `code-design-rules.md:90` (a window needs a reason it cannot be closed) | Adopted: a versioned ASR request opts into typed failures, legacy transactions keep `onResult`/`onError`; rollback is a Play build; the journal closes the STARTING window |

### 8. Grounded review G2 adjudication (`scratchpad/codex-grounded-g2.txt`, PROCEED-WITH-REVISIONS: D1, D8 verified; the rest drifted or new)

| Finding | Adjudication |
|---|---|
| D2: "the winning owner commits" conflicts with destruction committing whenever nothing has committed yet; no revocation of an outstanding publication reservation; the arbiter must preserve `publishLock` + `polishLedger.open` relationships (`D:946,977,983,1110,1361`, `PolishRequestLedger.kt:33`) | Adopted: OPEN / RESERVED / COMMITTED with an ownership token; destruction revokes an uncommitted reservation atomically; one short lifecycle critical section; a publication worker revalidates after every suspension (§3.2 refactor 1) |
| D3: `beforeSend` runs before the SDK's asynchronous disk write (`PostHogQueue.kt` `add` → `executor.executeSafely`, verified 2026-09-19), so an `exported` mark there is premature; the dedup tuple is four fields, not the uuid alone | Adopted with a declared modification: the terminal is committed in Room BEFORE `capture`, `beforeSend` never marks anything, and the journal carries no `exported` flag and NO terminal replay. Codex proposed bounded replay of every unacknowledged terminal on the next run; with no SDK acknowledgment that re-sends every dictation once, doubling the wire to close an interval that is unbounded by contract and unmeasured (G3 corrected the earlier "tens of milliseconds" claim). The founder's requirement 2 (stingy) wins: the window between the Room commit and the SDK's disk write is a STATED coverage loss; chunk C measures its latency distribution on the pinned SDK. Only `dictation.interrupted` is emitted at recovery |
| D4: file-operation failures (`staging_failed`, `admission_failed`, History save) are not proven defects; `ASR_FAILED` collapsed the ASR reason on the wire; `asr_empty` is a tenth result | Adopted: those route B, a typed invariant violation is its own `AppDefect`; `asr_failure_reason` property required on `ASR_FAILED`; the wire result set is the macOS eight plus `interrupted` plus `asr_empty` |
| D5: `startForTake(String)` dropped the capture token and pause seconds (`ISilenceVadService.aidl:20`, `SilenceVadService.kt:55`) | Adopted: `int startForTake(long captureToken, float pauseSeconds, String takeId)`; the token stays the functional arbiter |
| D6: pending-defect claim needs collision-free names, a converter that can die, and must not block the single VAD watchdog thread (`SilenceVadService.kt:45,131`) | Adopted: file named by event UUID, temp write then atomic publish, MAIN is the sole converter once per run, record retained for bounded replay with the original timestamp (Sentry de-duplicates by event id + project + day, `event_manager.py` (external) line 458, verified 2026-09-19), the write never runs on the watchdog executor |
| D7: an approved exception type can still carry a short transcript in its message | Adopted: exception messages are ALWAYS dropped, including nested causes; only closed-enum or literal diagnostics pass; a test carries a transcript in an approved type |
| D9: killing `:vad` externally cannot exercise the wedge writer; a captured `beforeSend` output is not the wire | Adopted: two VAD device cases (debug-only stalled detector crossing the real deadline; external kill proving the observer path only); payload tests read a recording transport (PostHog `httpClient` interceptor, Sentry `ITransportFactory`); journal, pending-defect and arbiter stop-point tests listed in §11 |
| D10: superseded cells in §2.5.4 and §2.5.6 still prescribe the latch, `Sentry.flush(1000)`, post-live recovery and a transcript take-id column | Adopted: those cells now say "superseded by G1, authority §3.2 / §3.3 / §4" |
| New risk 1: database is at version 7 with exported schemas; "one migration" needs a preservation test and a rollback that keeps version 8 (`EnviousWisprDatabase.kt:12,31`, `EnviousWisprDatabaseMigrationTest.kt:14`) | Adopted (§10 chunk B, §12) |
| New risk 2: the journal declared no `transcriptId`; pruning could remove the only join; legacy rows have none | Adopted: nullable `transcriptId`, association retained while insertion is pending, legacy rows repaired locally without inventing a take id |
| New risk 3: journal I/O before foreground promotion would block admission on the main thread (`D:464`) | Adopted: in-memory arbiter and take id first, promote, then journal admission off the main thread under a bounded deadline; failure means "outside durable coverage", never a blocked take |
| Proportionality: hash sampling with no sampled event; helper-process conversion of pending defects | Adopted: the volume policy keeps its entry point and version stamp only; no bucket, stamps or fixture until an event is sampled; main-only conversion |
| Chunking: A mixes five concerns; the arbiter must not depend on the journal | Adopted: A1 copy, A2 arbiter with a no-op sink, A3 AIDL, B storage and boundary, C emitters, D production (§10) |

### 9. Grounded review G3 adjudication (`scratchpad/codex-grounded-g3.txt`, PROCEED-WITH-REVISIONS; Codex: "no additional architecture or transport is needed")

| Finding | Adjudication |
|---|---|
| E1: the §5.1 enumeration missed five records (insertion outcome including the recovery transaction; the owner-side insertion outcome with no History row; PostHog's own persisted identity; Sentry live scope tags and persisted session metadata; the process-run id and the immutable insertion context) and introduced an unenumerated cross-run retry counter | Adopted: the five rows added to §5.1 with Codex's cells; the three-run counter CUT: a pending-defect record is replayed at most once per main run and expires seven days after its original timestamp; temp files are never eligible and are cleaned by the writer at its next start |
| E2: the handled-Sentry row conflated capture with persistence (the cache write happens in the transport worker, `AsyncHttpTransport.java` (external) lines 221-252); Sentry's day-keyed de-duplication does not make issue counters exact | Adopted: row reworded; wedge analysis de-duplicates by event id and raw issue counts are never presented as exact wedge counts |
| E3: late journal writes were unordered (an admission that outlives its deadline could recreate OPEN after a terminal) | Adopted: one ordered process-owned journal writer; admission inserts only when absent; stage updates only while no terminal and never backward; the terminal commit is conditional; `capture` only after the terminal transaction succeeded; `dictation.interrupted` means "no durable terminal was recorded", never proof of a crash; §7 corrected |
| D3 timing: "tens of milliseconds" is not an established bound; a 50 ms kill is one fault-injection case | Adopted: the interval is stated as unbounded by contract and unmeasured; chunk C measures the Room-commit-to-SDK-file-write latency distribution on the pinned SDK (cold start, queued backlog) and reports device and build |
| B1: §2.5.4 and §3.3 still said a recovered ready History row "already HAD its `dictation.terminal`"; History finalisation (`D:1114`) precedes the terminal commit (`D:1135`), so a kill between them exists | Adopted: a ready row proves text was persisted, not a terminal; dictation recovery reads the journal independently; both recovered outcomes may exist for one take and neither claims insertion succeeded |
| B2: a ship criterion still asked an external `:vad` kill to produce a wedge defect | Adopted: three criteria replace it (§13) |
| Accepted by Codex: no terminal replay (the declared deviation), chunk order, migration and rollback, proportionality | Recorded |

## 3. Design

### 3.1 Parts of the software, and what each one needs to say

The founder's requirements 3 and 4: enumerate the parts, decide which need telemetry, what is measured, how it is read,
and whether it troubleshoots the issue we forecast. Every row names the macOS lesson it applies. "Read" is the query a
human runs. Channel: **E** Sentry error (our defect), **B** Sentry breadcrumb (free, travels only with an error),
**P** PostHog row.

| Part | Forecast issue | Signal and fields | Channel | Frequency | Read | macOS lesson |
|---|---|---|---|---|---|---|
| App start (main) | Which phones and OS versions have problems; the denominator for everything | `app.launched`: `device_model`, `os_version`, `app_build`, `is_fresh_install`, `models_ready` (both), `accessibility_granted`, `mic_granted`, `onboarding_complete`, plus the settings snapshot as explicit PROJECTIONS of persisted preferences (booleans as `on`/`off`, `silence_pause_seconds` as a number, `bubble_look` as its enum name, `input_device` as `auto`/`picked` plus the closed `InputRouteKind`; the product name in `input_device_pick` never leaves; a failed read is `unknown`) | P | once per main-process run, owned by the bootstrap so a service-only start counts; settings folded in, no second row | `GROUP BY device_model`; join by `distinct_id` | `app.launched` `hardware` read `arm64` for everyone: record the MODEL. Settings snapshot merged in, not a separate row |
| App start (all processes) | Crashes in a helper process | Sentry init, tags `app.process`, `app.build_type`, `analytics.distinct_id`; `enableNdk` on | E | per process start | Sentry issues by `app.process` | XPC helper crashes were untagged and invisible on Mac until fixed |
| Onboarding | Where strangers drop off (stage 2) | `onboarding.stage_reached`: `stage` (`OnboardingStage`), `elapsed_s`; `onboarding.completed`; `onboarding.practice`: `lesson`, `outcome` (`PracticeOutcome`) | P | per stage change, once | funnel `WELCOME→PRACTICE→completed` | `onboarding.started` is the install marker, never a state flag (#1910) |
| Model delivery | Downloads fail on strangers' phones; mirror vs Hugging Face | `model_delivery.terminal`: `model`, `outcome` (`DownloadState`), `source_host` (a closed enum `mirror`/`huggingface`/`unknown`, chosen from the descriptor's source list, new in the worker output), `reason` (**new closed `DeliveryFailureReason`** chosen at the store's catch sites: `manifest_unavailable`, `transport`, `http_status`, `redirect_refused`, `partial_response`, `integrity_mismatch`, `disk_full`, `staging_failed`, `admission_failed`, `cancelled`, `paused`), `bytes_bucket`, `duration_s`, `first_run` | P; every member B (a failed rename, a full disk, a corrupt download and a bad mirror are the world, not a proven defect); a typed invariant violation in our staging code is its own `AppDefect` | per attempt terminal, never progress | rate by `outcome × reason × source_host × app_build` | `attempt_completed` means verified and admitted, not bytes finished; today every `IOException` collapses to one sentence (`ModelDelivery.kt:191`) and the host is only logged (Codex Q7) |
| Trigger | Which surface people use; refused starts | `trigger_source` on the take (closed: `bubble_tap`, `bubble_hold`, `assist`, `tile`, `app`, `unknown`; the side button reaches us as `assist`, the notification has no START; a practice take is an ordinary bubble take whose target is our own field, visible as `target_app = com.envi.wispr` plus `onboarding.practice`, so `practice` is NOT a surface); `dictation.refused`: `reason` (busy, token mismatch) | P (folded on the terminal row); refusals their own row | per take | terminal rows `GROUP BY trigger_source` | RULE: keep-trigger-source-separate from input mode |
| Capture (`:audio`) | Earbuds silent, no mic, Bluetooth link never live (#26) | on the terminal row: `route_kind`, `route_reason`, `live_after_ms`, `live_state` (`ready`/`forced`), `start_failure` (removed) (closed), `capture_terminal` (closed), `silence_stop_status`, `recording_s`, `input_pick` (`auto`/`picked`) | P; B per stage | per take | `result × route_kind × live_state` | Three fields answer "which mic" and two of them do not: record the BOUND route, not the default device. A stall is a breadcrumb + row, not an error (#1810) |
| VAD (`:vad`) | sherpa `exit(-1)` kills the process silently; the service kills itself after a 2 s wedged call (`SilenceVadService.kt:168`) | `silence_stop_status` on the terminal row (`lost_after_ready` is the death signature); in `:vad`, `AppDefect.VadCallWedged` written as a pending-defect record (no network wait) before the self-kill and converted by the next bootstrap; sherpa's own `exit` is invisible and shows only as `lost_after_ready` | P + E | per occurrence | count by `app_build` | A native `exit` is invisible to a crash reporter; the OBSERVER must report it |
| ASR (`:asr`) | "Transcription failed", empty despite audio, model still loading | on the terminal row: `asr_ms`, `asr_chars`, `asr_cold_start` (removed), `peak_amplitude` (new audio getter); `result` ∈ `no_speech`, `asr_empty_despite_audio`, `asr_empty`, `failed{reason=ASR_FAILED, asr_failure_reason=<member>}`; **new closed `AsrFailureReason`** over the binder (`model_not_loaded`, `audio_missing`, `audio_unreadable`, `over_limit`, `decode_failed`) | P; E for `decode_failed` and `over_limit` (capture enforces the same ceiling, so exceeding it is ours); B for `model_not_loaded`, `audio_missing`, `audio_unreadable` | per take | eight-result query, `GROUP BY result, reason, app_build` | `asr.completed` emitted only on success, so there was NO failure rate (#1884); `asr_empty_despite_audio` is not a bug; an absent peak is "not measured", never zero (#1809) |
| Polish self-kill (`:polish`) | Local generation exceeded its deadline; the engine poisons itself and dies 300 ms later (`PolishService.kt:270`) | MAIN raises `AppDefect.LocalPolishDeadline` once, when it accepts the typed `LOCAL_TIMEOUT` outcome; the dying helper reports nothing (no duplicate, no network wait); the owner's `LOCAL_TIMEOUT` outcome is the PostHog record | E + P (via `polish_reason`) | per occurrence | `polish_reason=LOCAL_TIMEOUT × device_model` | A SIGKILL leaves no crash report; report before dying |
| Cleanup + vocabulary | Nothing forecast worth a row | `custom_words_count` on `app.launched` only | P | at launch | adoption | Counts only, never a word (`custom_words_imported`) |
| Polish (`:polish`, local S1 and cloud) | S1 not ready, watchdog timeouts, cloud key errors | on the terminal row: `polish_provider`, `polish_reason` (`PolishReason`), `polish_ms`, `polish_status` | P; **E only where our code owns the cause**: `CLEANUP_RECOVERED`, `LOCAL_FAILED`, `LOCAL_TIMEOUT` (once, from main), `UNEXPECTED`, `WATCHDOG_TIMEOUT`, plus `AppDefect.PolishProtocolViolation` raised beside `CALL_FAILED` when the reply is null or mismatched; everything else B, including `MALFORMED_RESPONSE`, `SERVICE_DIED`, `SERVICE_UNAVAILABLE` (§3.6) | per take | `polish_reason × provider × app_build` | One quota condition created 71 errors (#1401); `PolishFailureReason.telemetryChannel` routes; `llm.polish_failed` counts everything |
| Insertion (accessibility, main) | Words went to the clipboard instead of the field (#16), service dead, target never returned | `insertion.terminal`: `take_id`, `handoff` (`InsertionHandoff`), `result` (`InsertionResults`), `route` (`committed`/`pasted`/`clipboard`), `target_app` (package name), `latency_ms`, `clipboard` (`ClipboardOutcome`) | P; every `InsertionResults` value and every `InsertionHandoff` member is B (a clipboard write failing after a fallback and an unanswered main thread are symptoms, not proven defects; §3.6) | per attempted insertion | `result × target_app × app_build`; **exclude `target_app = com.envi.wispr`** | 42% of `clipboard_only` on Mac was our own window (#1070); paste is not proven by the clipboard |
| History (Room) | Save failures, stale-row recovery | `history_save` on the terminal row (`ok`/`failed`); `dictation.interrupted`: `stage`, one row per journal entry with no terminal from an earlier process run; recovered ready History rows go to `insertion.terminal` | P; a save failure is B (storage is the world); an invalid-SQL or schema-contract failure is a typed `AppDefect` | per take; recovery once per launch | interrupted ÷ (terminals + interrupted) | A started take with no terminal crashed or hung; make that population visible (#1884, #2787) |
| Settings | What people change | `settings.changed`: `setting` (closed key set), `from`, `to` (closed values) | P | on change, coalesced | reconstruct config per install | `SettingsChangeTelemetry` last-writer-wins |
| Providers | Key problems | `api_key.changed`: `provider`, `action`, `result`; `api_key.validation_completed`: `provider`, `result` | P | on change | adoption and failure | never key material (#1173) |
| Recorder overlay, tile, notification UI | Nothing forecast | none | | | | Stingy: no row without a reader |

### 3.2 Architecture

One new package, `app/src/main/java/com/envi/wispr/telemetry/`, and nothing telemetry-shaped outside it except calls to
its facade. Every file below is (proposed).

| File | Owns | Shape |
|---|---|---|
| `Telemetry.kt` | The facade: `bootstrap(app)`, `capture(event: AnalyticsEvent)`, `breadcrumb(crumb: Breadcrumb)`, `defect(d: AppDefect)`, `takeScope(takeId)`; the process-run id; `app.launched` once per main-process run | `object`; every method is a limb: never throws, no-op before bootstrap, with no keys, or when identity is disabled |
| `AnalyticsEvent.kt` | The closed event set, one sealed subclass per row in §3.1, typed properties, `name`, `properties()`, and the event `uuid` | `sealed class`; a string event name cannot be captured |
| `InstallIdentity.kt` | Reads or mints the one install UUID under a process-local mutex plus an OS file lock on a permanent lock file, writes through `AtomicFile` (external) in `filesDir/telemetry/`, publishes the value only after commit. Mints ONLY when the file is genuinely absent. An unreadable, corrupt or uncommittable file DISABLES telemetry for that bootstrap and never mints a replacement. Any process may initialise it, including a helper started before main | `object`; the value PostHog receives through `getAnonymousId` and Sentry through the `analytics.distinct_id` tag |
| `TakeIdentity.kt` | Per-take UUID minted by the owner at admission and carried on every request that leaves the main process (§4); Sentry scope tag `dictation.take_id` in main; request-local take context in helpers; helper native-crash tags set and cleared with compare-and-clear against the same take | value class + set/clear |
| `TakeJournal.kt` | The content-free local take ledger (§3.3): take id, process-run id, admitted-at, stage, nullable `transcriptId`, terminal result and reason, terminal event uuid and timestamp. A Room table `take_journal` in the existing database (version 7 → 8). Written off the main thread under a bounded deadline; a write failure never blocks a take | the ONLY denominator source; History is never read for counts |
| `PendingDefects.kt` | Content-free records a dying process writes (`filesDir/telemetry/pending-defects/<eventUuid>`: temp file then atomic publish; fields: event id, original timestamp, process, build, install id, optional take id, defect fingerprint). MAIN is the sole converter, once per process run, off the main thread; a record is replayed at most once per main run and expires seven days after its ORIGINAL timestamp (checked before conversion; no cross-run counter), because there is no SDK acknowledgment and Sentry de-duplicates by event id, project and the timestamp's day; converted events carry the ORIGINAL process, build, install and take metadata, never the converter's scope; temp files are never eligible and the writer deletes them at its next start. Wedge analysis de-duplicates by event id; raw Sentry issue counts are never presented as exact wedge counts (counters can inflate before storage de-dup, `event_manager.py` (external) line 430). The VAD writer never runs on the watchdog executor | replaces every "flush before kill" |
| `PayloadSanitizer.kt` | Allowlist-first (§3.7): known field names with known value kinds pass; every other string is dropped; pattern redaction (keys, hex runs, emails, Android paths, `content://` URIs, credential-bearing URLs, any string over 100 chars) is defence in depth on the allowed fields | pure `object`; runs in BOTH vendors' pre-send hooks and on every Sentry surface (message, exception values and causes, breadcrumbs, tags, extras, contexts) |
| `TelemetryVolumePolicy.kt` | The ONE entry point that decides whether a PostHog row leaves and stamps `telemetry_policy_version=1`; today every declared event is kept unsampled, and no hash bucket, sampling stamps or sampled fixture ship until an approved event is actually sampled (G2 proportionality; the Mac's `$sample_*` vocabulary is the contract when that day comes); ALSO reduces SDK-added context to the allowlist (`$os_name`, `$os_version`, `$device_manufacturer`, `$device_model`, `$app_version`, `$app_build`, `$locale`, `$lib`, `$lib_version`; `$user_agent`, `$carrier`, `$screen_*`, `$network_*` dropped) | pure |
| `DefectIdentity.kt` | `AppDefect` sealed hierarchy with pinned `fingerprint` and renameable `semanticId`; `Telemetry.defect` accepts only this type; the fingerprints are frozen in a committed snapshot file | the compiler is the gate |
| `TelemetryChannels.kt` | The exhaustive `when`s over `PolishReason`, `TerminalReason`, `InsertionResultKind` (a closed type parsed from the stored `InsertionResults` strings, unknown historical strings parse to `UNKNOWN` and are never a defect), `InsertionHandoff`, `AsrFailureReason`, `DeliveryFailureReason`; `Channel` ∈ `DEFECT`, `BREADCRUMB`; the literal table is §3.6 | no `else` branch anywhere |
| `SentryBootstrap.kt` | `SentryAndroid.init` per process: DSN from `BuildConfig`, release `com.envi.wispr@<versionName>+<versionCode>`, `dist=<versionCode>`, environment, **`cacheDirPath = cacheDir/sentry-<process>`** (one SDK instance per directory), `sendDefaultPii=false`, every automatic breadcrumb off, user-interaction off, `enableNdk=true` in every process, ANR main only, release-health sessions main only, tracing/profiling/replay/screenshot/view-hierarchy/attachments off, `enableFramesTracking=false`, `enableAutoActivityLifecycleTracing=false`; RETAINED on purpose: `collectAdditionalContext` (device and OS context, content-free) and `enableRootCheck`; `beforeSend` → sanitizer, `beforeBreadcrumb` → sanitizer; tags `app.process`, `app.build_type`, `analytics.distinct_id` | manifest `io.sentry.auto-init=false` |
| `PostHogBootstrap.kt` | Main process only: `getAnonymousId` installed BEFORE `setup` and returning the install UUID; after setup the SDK's anonymous and distinct ids are verified equal to the file UUID, and a mismatch disables analytics for the run (an explicit migration is a later decision, never silent); `captureApplicationLifecycleEvents=false`, `captureScreenViews=false`, `captureDeepLinks=false`, `capturePushNotificationSubscriptions=false`, `capturePushNotificationOpened=false`, `captureElementInteractions=false`, `sessionReplay=false`, `surveys=false`, `preloadFeatureFlags=false`, `sendFeatureFlagEvent=false`, `setDefaultPersonProperties=false`, `errorTracking.autoCapture=false`, `errorTracking.captureNativeCrashes=false`, `personProfiles=IDENTIFIED_ONLY`, never `identify`, never `reset`, `flushAt=20`, `flushIntervalSeconds=30`, `maxQueueSize=1000`; one `beforeSend` that stamps `app`, `environment`, `app_version`, `app_build`, runs the volume policy (drop, sample, reduce SDK context), then the sanitizer; it never touches the journal | remote-config traffic is the SDK's own and is accounted separately from analytics rows |

**Three refactors that make the data queryable (the "pipe", not a bucket):**

1. **One lifecycle arbiter per take** (`ui/TerminalReason.kt`, `ui/TakeArbiter.kt`, `ui/TakeNotices.kt`, all proposed).
   A take has one arbiter for publication, cancellation, failure and destruction, with three states: OPEN, RESERVED
   (with an ownership token), COMMITTED. CLAIMING an ending reserves its owner; it does not yet publish a reason. The
   owner freezes the terminal facts and COMMITS exactly one terminal; losing callbacks do no History, notification,
   insertion or terminal work. Destruction may atomically REVOKE an uncommitted reservation and commit `interrupted`;
   the displaced owner can no longer commit, announce or start insertion, and a publication worker revalidates its
   token after every suspension (an already-running History write may finish after revocation; teardown waits for or
   cancels it before its final History state, and the worker cannot restore the terminal afterwards). One short
   lifecycle critical section replaces today's `publishLock` and `polishSubmissionLock` for state transitions,
   reservation changes and the state-check-plus-`polishLedger.open` step (`D:946-983`); no database, binder, SDK or
   blocking cleanup runs while it is held. `PolishRequestLedger` still chooses one reply versus the watchdog for a
   request; winning that claim does not grant terminal ownership. The post-submission cancellation at `D:1020` stays.
   Admission initialises the in-memory arbiter and take id immediately after the successful `IDLE→STARTING`
   transition, promotes the service to foreground WITHOUT waiting for telemetry, then attempts the journal admission
   off the main thread under a bounded deadline (validated against short-take latency) before capture; expiry or
   failure lets capture proceed and marks the take outside durable coverage. Insertion points, from the G1 review: ASR failures claim at the top of the new `onFailure`
   (replacing the body at `D:909`) before deleting audio or scheduling the History update; STARTING failures keep the
   state check and `publishLock` exclusion in `failWhileStarting` and claim before changing state or announcing;
   empty ASR commits before `discardDraft` (`D:929`); empty final text commits before `finishSession` (`D:1098`);
   successful finalisation RESERVES where `D:1090` claims today and COMMITS `completed` after the History result is
   known (`D:1135`) and before the insertion handoff (`D:1145`), so `completed` means text finalisation completed,
   never insertion succeeded; capture cancellation reserves at the state transition and commits `cancelled` in the
   no-capture branch (before `D:1325`) or after the safe close (before `D:1339`), committing the close failure instead
   (before `D:1334`) when the close failed; processing cancellation reserves against publication (`D:1362-1364`) and
   commits before discarding the draft (`D:1372`); destruction atomically freezes the pre-destruction stage and
   commits `interrupted` only if no terminal was committed (revoking any reservation), BEFORE the blocking cleanup
   and the service-job cancellation (`D:1633`), synchronously; persistence of that commit is handed to the
   process-owned journal writer, never to a coroutine the dying service owns. `finishSession` performs
   teardown only and never invents success. Manual stop, silence stop and the duration cap continue into
   transcription and claim nothing. The user sentence is derived in ONE place (`TakeNotices`, the twin of macOS
   `DictationNarrator`), so copy and telemetry cannot disagree; the former vendor `e.message` sentences become the
   already-existing stable fallback "Speech recognition failed" (`D:913`). `result` on the wire is the macOS eight
   plus `interrupted` plus `asr_empty` (unmeasured); only `failed` carries `reason`, which IS the `TerminalReason`
   name, and `ASR_FAILED` also carries the required `asr_failure_reason`. The full member table is §3.6.
2. **Typed ASR failures over a versioned request.** `asr/AsrFailureReason.kt` (proposed) with an int code;
   `IAsrService.transcribeFileForTake(String path, String takeId, IAsrCallback callback)` and
   `IAsrCallback.onFailure(int reason, String detail)` APPENDED. Only the new request emits `onFailure`; the legacy
   `transcribeFile`/`transcribe` transactions keep sending `onResult`/`onError` exactly as today, so the separately
   installed instrumentation client (`VoicePipelineDeviceTest.kt:122`) keeps working, and an old client binary is run
   against the new service in chunk A's device check, including a failed request. `detail` is never sent anywhere; it
   is logged locally. The catch boundaries in `AsrService.kt:77,139` are narrowed so `audio_unreadable` is the file
   read, `decode_failed` is the decoder call, and a throwing callback delivery is its own local log line, never a
   second callback.
3. **The take id crosses the binder.** Appended: `IAudioCaptureService.startCaptureForTake(..., String takeId)`
   (the audio service forwards it to VAD through an appended `ISilenceVadService.startForTake(long captureToken, float
   pauseSeconds, String takeId)`, which keeps the capture token as the functional arbiter, the pause value, the return
   codes and the deadline behaviour of `start`, and binds the take id only after accepting the token under the existing
   session lock; `processBlock` and `finish` keep using the token), `IPolishService.polishRequestForTake(..., String takeId)` (the `Long requestId` stays for
   cancellation and reply arbitration and is bound to the take id in the request entry), and
   `IAudioCaptureService.getTakePeakAmplitude()` so an empty transcript can be split into `no_speech` and
   `asr_empty_despite_audio`. A helper's Sentry events carry the request-local take id; the process-global native-crash
   tag is set only while attribution is unambiguous and cleared with compare-and-clear against the same take.

**Trigger source:** `EXTRA_TRIGGER_SOURCE` (proposed) on every START/TOGGLE intent, a closed `TriggerSource` enum
(`bubble_tap`, `bubble_hold`, `assist`, `tile`, `app`, `unknown`); `VoiceInputActivity` stamps `assist`
from `ACTION_ASSIST` and forwards the tile's and bubble's extras; `SettingsActivity` stamps `app`; the bubble's
activity-fallback start carries the same extra as its direct start; the owner stamps the value on the take. A missing
extra reads `unknown` and is kept, never defaulted to a real surface.

**Reporting before a deliberate death:** a deliberate kill NEVER waits for the network. `PolishService` reports
nothing on its deadline path: main raises the one `LocalPolishDeadline` defect when it accepts the typed
`LOCAL_TIMEOUT` outcome, and polish destruction with a request in flight is a different, non-defect record (ordinary
cancellation must never become a deadline defect). `SilenceVadService` writes one `PendingDefects` record under a
process-wide claim (both the watchdog and the returning call can reach termination), on its own thread and never on
the single watchdog executor (`SilenceVadService.kt:45,131`), and then kills itself as today; the hard termination
stays independently armed, so a stalled write cannot keep a poisoned process alive. The next MAIN bootstrap converts
the record. This is best effort and is described as such.

**Typed service-connection breadcrumbs:** bind requested, connected, disconnected, unbound at the owner's three
`ServiceConnection`s (`D:271-330`) and at the audio service's VAD connection (`AudioCaptureService.kt:281-292`).
Sentry's automatic app-component breadcrumbs are configuration and memory callbacks and would not supply these.

### 3.3 Volume: what leaves the phone per dictation, and how a take is counted

| Row | When | Why it cannot be folded |
|---|---|---|
| `dictation.terminal` | at the arbiter's commit (completed after the History save, cancelled after the safe close, failed, `interrupted` from `onDestroy`) | the one row per take; carries trigger, capture, ASR, polish and history facts |
| `insertion.terminal` | at whichever of the three writers records the outcome: the owner (never handed off), the accessibility service (accepted), or stale-ready recovery at next launch | for accepted insertions the owner is already gone; the outcome arrives seconds later from another component. One immediate row could honestly say "text completed, insertion pending"; it could not say where the words went, which is the diagnosis #16 needs |
| `dictation.interrupted` | next launch, from the take journal: entries with no durable terminal whose process-run id is not the current one | nothing else can speak for a take that never recorded an ending; the row means "no durable terminal", not "crashed". Recovered `ready_for_insertion` History rows are NOT counted here: they prove persisted text, not a terminal, and go to `insertion.terminal` |

**The take journal is the denominator, never History.**  History deletes wordless and cancelled drafts
(`D:929,1339,1523`), inserts the draft asynchronously after RECORDING is published (`D:633-638`), and recovers by
row age from two call sites (`HistoryRecovery.kt`, `D:337`, `AppViewModel.kt:248`), so it can neither see every take
nor tell a death from a slow restart. The journal (`take_journal`, content-free) records: admission (off the main
thread, under a bounded deadline, before capture starts), the current stage at each transition, the nullable
`transcriptId` once the draft or ready row has an id, and at commit the terminal result, reason, event uuid and event
timestamp. **All journal mutations for a take go through ONE ordered, process-owned writer:** admission inserts only
when the row is absent and never replaces one; stage updates apply only while the row has no terminal and never move
its sequence backward; the terminal commit is conditional and cannot be overwritten by admission or stage work; a
caller's deadline expiry does not prove its database operation was cancelled, so a late admission passes through the
same writer and cannot recreate OPEN after a terminal. **Ordering: `capture` is invoked only after the terminal
transaction has completed successfully.** `beforeSend` validates and transforms only; it never marks anything. There
is NO export acknowledgment and NO replay of terminals: the PostHog SDK writes the row to its own disk queue on a
background executor (`PostHogQueue.kt` (external) `add`, verified 2026-09-19), so a kill between the Room commit and
that write loses the row. That interval is UNBOUNDED by the application contract and not yet measured; chunk C measures
the Room-commit-to-completed-SDK-file-write latency distribution on the pinned SDK (cold start and a queued backlog,
reported with device and build), and a 50 ms kill is one fault-injection case, never a bound or a fleet loss rate. (G2 proposed replaying every unacknowledged terminal on
the next run; without an SDK acknowledgment that re-sends every dictation once, which doubles the wire to close that
window, so it was declined under the founder's requirement 2.) Recovery at the next main bootstrap: an entry with no
terminal whose process-run id is not the current one becomes `dictation.interrupted{stage}`, committed in the same
Room transaction that closes the entry; current-run active takes are excluded; row age proves nothing. Terminal
entries are pruned after seven days; the transcript-to-take association is kept while an insertion is pending,
independently of that pruning.

No `dictation.started` row leaves the phone. The reported denominator is distinct take ids with a terminal or a
recovered interruption. It EXCLUDES, and the knowledge file will say so: a journal admission that failed or timed out
(telemetry never blocks dictation), a kill inside the unmeasured commit-to-SDK-disk interval, app data cleared before
the next launch, SDK queue eviction past 1000 rows, and installs that never come back online. Sentry is supporting evidence for
those, not a substitute denominator.

Two rows per successful take. At the Mac's measured 17 takes per install per day this is roughly 1,050 rows per install
per month against the Mac's 1,570 (#2958 measurement). Nothing on the take path is sampled; the volume policy exists from
day one so the first sampled event has a home, and it drops nothing yet except SDK context fields, since the SDK's own
lifecycle rows are off at the source.

Breadcrumbs (admission, bind and connect per service, record start, live, stop, ASR done, polish done, insertion handed
off) are Sentry-only, in-memory, and travel only with an error: full crash context at zero wire cost.

### 3.4 Identity and joins

- `distinct_id` = the install UUID from `InstallIdentity` (§3.2), a hyphenated lowercase UUID, handed to PostHog
  through `getAnonymousId` BEFORE setup (the hook is consulted only when the SDK has nothing persisted, so it is not a
  per-launch override) and to Sentry as the `analytics.distinct_id` tag in every process. After setup the SDK's ids
  are verified equal to the file; a mismatch disables analytics for the run. `reset()` is never called; with the same
  durable UUID it would regenerate the same id anyway. Sentry's own `user.id` stays the SDK's installation id
  (separate population, never summed), as on Mac.
- `take_id` = `TakeIdentity`, on every take-keyed row, as the Sentry scope tag in the main process, and as request-local
  context in `:audio`, `:vad`, `:asr` and `:polish` (§3.2 refactor 3). Startup and warm-up failures in a helper
  legitimately carry no take id.
- Process-run id: a UUID per main-process start, on the journal and on `app.launched`, so an interrupted take is
  attributed to the run that lost it.
- Version floor: `app_build` (Play version code) on every row; Sentry `release` carries the same number.

### 3.5 Alternatives rejected

- **Reuse the Mac Sentry project.** Every Mac worker, rule and routine assumes one platform; the `enviouswispr-android`
  project keeps grouping, symbols and alerts separate. Chosen: new project, no spend (sponsored plan, verified 2026-09-09).
- **Emit from `DebugLogger`.** It takes free strings; a wire emitter built on it inherits the sentence problem.
- **Different event names on Android.** Same names where the meaning is the same, so one query spans both platforms with
  an `app` filter; the Mac workers gain the filter (§13).
- **Three rows per take (started, terminal, insertion).** Dropped `started` for the local take journal (§3.3).
- **History as the denominator.** Rejected in G1: it deletes wordless drafts, inserts late and recovers by age.
- **`Sentry.flush` before a deliberate kill.** Rejected in G1: it waits for idleness and acknowledges nothing; a pending-defect record replaces it.
- **Atomic rename alone for the install id.** Rejected in G1: two renames both succeed; a file lock elects one.

### 3.6 Channel table (the literal contract `TelemetryChannelsTest` compares against)

**B** = breadcrumb plus the analytics outcome; **D** = one owned defect. No channel is inferred from a sentence or a name.

`PolishReason`: `POLISHED`, `OFF`, `NO_SPEECH`, `EMPTY_AFTER_CLEANUP`, `TOO_SHORT`, `LOCAL_NOT_READY`, `OUTPUT_REJECTED`,
`CLOUD_NOT_CONFIGURED`, `NO_API_KEY`, `NETWORK`, `TIMEOUT`, `CANCELLED`, `HTTP_ERROR`, `HTTP_KEY_REJECTED`,
`HTTP_OUT_OF_CREDITS`, `HTTP_INPUT_TOO_LONG`, `HTTP_CONTENT_BLOCKED`, `INVALID_CONFIGURATION`, `MALFORMED_RESPONSE`,
`RESPONSE_TOO_LARGE`, `REDIRECT_REJECTED`, `SERVICE_UNAVAILABLE`, `SERVICE_DIED`, `CALL_FAILED` → **B**.
`CLEANUP_RECOVERED` (our deterministic transform failed its own safety check, `DeterministicCleanup.kt:170`),
`LOCAL_FAILED` (poisoned follow-ons deduplicated against the first), `LOCAL_TIMEOUT` (once, from main), `UNEXPECTED`,
`WATCHDOG_TIMEOUT` (our reply contract, `PolishWatchdogBudget.kt:6`) → **D**. Beside `CALL_FAILED`, a null, mismatched
or malformed reply that the owner can PROVE (`D:979`) raises `AppDefect.PolishProtocolViolation` → **D**.

`InsertionResultKind` (parsed from `InsertionResults`): every value → **B**, including `INSERTION_FAILED` (a clipboard
write failed after a fallback) and `INSERTION_INTERRUPTED`; `UNKNOWN` (a historical string) → **B**.
`InsertionHandoff`: every member → **B**, including `SERVICE_DID_NOT_ANSWER` (a symptom; an ANR is the separate
evidence).

`AsrFailureReason`: `audio_missing`, `audio_unreadable`, `model_not_loaded` → **B**; `over_limit` (capture enforces the
same ceiling, `AsrService.kt:64`) and `decode_failed` (the decoder call itself) → **D**.

`DeliveryFailureReason`: every member → **B** (`manifest_unavailable`, `transport`, `http_status`, `redirect_refused`,
`partial_response`, `integrity_mismatch`, `disk_full`, `staging_failed`, `admission_failed`, `cancelled`, `paused`); a
failed rename or write is the world (`ModelDelivery.kt:184` throws plain `IOException`). A separately identified
invariant violation in our staging code raises its own typed `AppDefect`.

`TerminalReason`, one member per route in the Codex Q1 table, with its wire `result`:

| Member | Q1 rows | `result` | Channel |
|---|---|---|---|
| `COMPLETED` | 27 | `completed` | B |
| `CANCELLED_STARTING`, `CANCELLED_RECORDING`, `CANCELLED_PROCESSING` | 30, 31, 32 | `cancelled` | B |
| `NO_SPEECH` (empty transcript, peak below the floor) | 28 | `no_speech` | B |
| `ASR_EMPTY_DESPITE_AUDIO` (empty transcript, peak above the floor) | 28 | `asr_empty_despite_audio` | B |
| `ASR_EMPTY_UNMEASURED` (empty transcript, no peak available) | 28 | `asr_empty` | B (absent never converts to measured silence; the split is `peak < floor` vs `peak >= floor`) |
| `FINAL_TEXT_EMPTY` | 29 | `discarded` | B |
| `INTERRUPTED_STARTING`, `INTERRUPTED_RECORDING`, `INTERRUPTED_PROCESSING`, `INTERRUPTED_CANCELLING` | 33-36 | `interrupted` | B |
| `AUDIO_PROCESS_DIED` | 1 | `audio_interrupted` | B |
| `ASR_PROCESS_DIED` | 2 | `asr_interrupted` | B |
| `POLISH_PROCESS_DIED` | 3 | `failed` | B |
| `SETTINGS_UNAVAILABLE` | 4 | `failed` | B |
| `AUDIO_BIND_FAILED`, `ASR_BIND_FAILED`, `POLISH_BIND_FAILED` | 5, 6, 7 | `failed` | B |
| `CAPTURE_START_NO_MICROPHONE`, `CAPTURE_START_EARBUDS_REFUSED`, `CAPTURE_START_FAILED` | 8 | `failed` | B |
| `START_EXCEPTION` | 9 | `failed` | B (arbitrary exception; a typed owned invariant would be its own member) |
| `CAPTURE_ENDED_BEFORE_LIVE` | 10 | `failed` | B |
| `LIVE_WAIT_DEADLINE` | 11 | `failed` | B |
| `CAPTURE_FAILED_MID_TAKE` (`CaptureEnding` failure) | 12 | `audio_interrupted` | B |
| `CAPTURE_STILL_RUNNING_AFTER_STOP` (`StillRunning`, `D:693`, `CaptureEnding.kt:58`) | 12 | `failed` | **D** (our protocol) |
| `CAPTURE_CLOSE_UNSAFE`, `CAPTURE_CLOSE_UNSAFE_ON_CANCEL` | 13, 18 | `failed` | B |
| `AUDIO_FILE_MISSING` | 14 | `failed` | B |
| `ASR_NOT_READY` | 15 | `failed` | B |
| `ASR_FAILED` (required property `asr_failure_reason`) | 16 | `failed` | per the ASR table |
| `ASR_CALLBACK_EXCEPTION` | 17 | `failed` | B |

Rows 19-26 (polish outcomes after text exists) end as `COMPLETED` with the `PolishReason` on the row; the polish table
above decides their channel.

### 3.7 Sanitizer contract

Allowlist first, patterns second. A payload field passes only if its NAME is in the schema and its VALUE has the kind
the schema says (number, boolean, closed token, or one of the bounded dynamic strings); every other string is dropped,
whatever its length, and URLs get no exemption. The bounded dynamic strings are: validated install, take, process-run
and event UUIDs; the target PACKAGE name (`PasteAccessibilityService.kt:826`), never a display label; `Build.MANUFACTURER`,
`Build.MODEL` and the OS version, truncated to 64 characters; build-owned release metadata; approved exception TYPE
names and stack locations. Exception MESSAGES are ALWAYS dropped, including messages of approved types and every
nested cause, so sherpa, GenieX, Room and HTTP prose never travels; an application diagnostic string passes only when
it is generated from a closed enum or a fixed literal at a known producer, never copied from `Throwable.message`; the GenieX silencer (`geniex_log_silencer.cpp:9`) already refuses native prose and
this keeps the promise on the Java side. Settings are explicit projections (§3.1); `input_device_pick`'s product name,
provider endpoints, user-entered model ids, prompts, vocabulary, clipboard data and editor metadata are excluded by
construction because no event declares them.

Pattern redaction runs on the allowed fields as defence in depth: `sk-`, `phc_`, `sntrys_`, `key_`, `AIza` prefixes;
32+ hex runs; emails; `/data/user/<n>/`, `/data/data/`, `/storage/emulated/<n>/`, `/sdcard/` paths; `content://` URIs;
user-info, query and fragment in any URL; any string over 100 characters. Sentry surfaces covered: message, every
exception value and cause, breadcrumb messages and data, tags, extras, contexts, request data, nested structures, and
frame paths. Native crash EVENTS re-enter through the Java client and pass the same `beforeSend`
(`OutboxSender.java` (external) line 147); non-event envelope items do not, which is why attachments, screenshots, view
hierarchy and ANR thread dumps stay off. Tests assert the FINAL outbound representation with short multilingual
transcripts, renamed earbuds, private and shared-storage paths, content URIs, credential-bearing URLs, native exception
messages and Room failures as witnesses.

## 3b. Ownership justification

Telemetry will live in `telemetry/` because it is a cross-cutting limb with one wire and one privacy seam, and the seam must
have one reader; the alternative was per-package emitters (each service captures its own rows), but then five processes
would each need PostHog, the sanitizer would have five copies to drift, and the take id would have to cross the binder.

## 4. Contract deltas

| Type | Delta | Meaning to consumers |
|---|---|---|
| `IAsrService` (AIDL, append) | `+ transcribeFileForTake(String path, String takeId, IAsrCallback cb)` | Opts into typed failures; legacy `transcribeFile`/`transcribe` keep `onResult`/`onError` byte for byte for the separately installed instrumentation client |
| `IAsrCallback` (AIDL, append) | `+ onFailure(int reason, String detail)` | Emitted only for the versioned request; `detail` is local-only |
| `IAudioCaptureService` (AIDL, append) | `+ startCaptureForTake(boolean, float, String pick, boolean keepEarbuds, String takeId)`, `+ getTakePeakAmplitude()` | The take id reaches audio and, through it, VAD; the peak of the current or most recent take, 0 before the first take, read once at stop |
| `ISilenceVadService` (AIDL, append) | `+ int startForTake(long captureToken, float pauseSeconds, String takeId)` | Same codes, token ordering, stale-call rejection, pause configuration and deadline as `start`; the take id binds after the token is accepted; legacy `start` keeps its behaviour with no take context |
| `IPolishService` (AIDL, append) | `+ polishRequestForTake(long requestId, ..., String takeId)` | `requestId` stays for cancellation and arbitration; the entry binds both |
| `ModelDeliveryWorker` output | `+ KEY_FAILURE_REASON` (closed name), `+ KEY_SOURCE_HOST` (closed name) | The UI keeps reading the message; telemetry reads the reason |
| `DictationSessionService` START/TOGGLE intent | `+ EXTRA_TRIGGER_SOURCE` | Absent reads `unknown`; never changes admission |
| `TerminalReason`, `TakeArbiter` (new) | replace 18 sentence literals and the two silent endings; reserve-then-commit | Copy is derived, not authored; one terminal per take |
| `take_journal` (new Room table; database version 7 → 8, `MIGRATION_7_8`, `app/schemas/.../8.json` committed) | take id, process-run id, admitted-at, stage, nullable `transcriptId`, terminal result and reason, terminal event uuid and timestamp | The denominator; History is never counted; the migration creates the table and indexes only and touches no existing value |
| `TranscriptDao.recoverStaleReadyRows` | claims eligible rows and records the pending insertion outcome in ONE Room transaction, returning the rows with their take id from the journal join where one exists | A take-keyed `insertion.terminal` only when the association exists; legacy or untracked rows are repaired locally without inventing a take id; current-run active insertions are excluded |
| `Application` | `ModelBootstrapApplication` gains `Telemetry.bootstrap(this)` before its process gate | Every process boots Sentry with its own cache dir; only main boots PostHog, emits `app.launched`, and converts pending defects |
| `BuildConfig` | `+ TELEMETRY_POSTHOG_KEY`, `+ TELEMETRY_SENTRY_DSN` (empty when unset) | Empty means telemetry off; the app works identically. Compiled in: removing the variable later changes nothing on installed phones |

## 5. End-to-end state and lifecycle audit

| Population | Enumerated |
|---|---|
| Ways a take ends | 36 routes in Codex Q1, every one mapped to a `TerminalReason` in §3.6; the arbiter's reserve and commit points are listed per route in §3.2; `TakeNoticesTest` asserts one literal sentence per member |
| Process deaths mid-take | `:audio` → `handleServiceFailure` (`:284`), `:asr` → `showError` (`:303`), `:polish` → `showError` (`:327`), main → Room recovery at next start; `:vad` → Codex Q3(d) |
| Duplicate callbacks | `publicationStarted` (`:1090`) becomes the arbiter's publication RESERVATION; the error path, which has no guard today, claims through the same arbiter (Codex Q1 guards, G1 D2) |
| `onDestroy` with a session open | `:1602-1651`: freezes the stage and commits `interrupted` synchronously only if nothing was committed, BEFORE the blocking cleanup and the service-job cancellation at `:1633` |
| Take id lifetime | minted at admission (`:465`, after the CAS), cleared by the arbiter's commit postamble, the ONLY clearer; `finishSession` never clears it before the facts are handed to the journal |
| Install id races | two processes first-reading at once: the file lock serialises them, the second reads what the first committed; a test races two real processes with distinct candidate UUIDs and interrupts a write before commit |
| Journal windows | death before the journal admission lands: unrecorded (stated boundary); between the Room terminal commit and the SDK's background disk write: the row is lost and the take counts as ended (stated boundary, measured in chunk C); after the SDK disk write: the SDK delivers it later |
| Pending-defect conversion | main only, at most once per run; a converter that dies mid-way leaves the record for the next run; a replay carries the same event id and original timestamp, which Sentry de-duplicates within the day; expiry seven days after the original timestamp |
| Arbiter revocation | destruction during an outstanding History save: the reservation is revoked, `interrupted` commits, the save may finish, the worker cannot commit or insert afterwards |
| Bootstrap before keys | empty `BuildConfig` value, or identity disabled: `Telemetry` stays a no-op; every call site tolerates it |
| Stale generation | a polish outcome for a cancelled take: the owner already ignores it (`:947`); the emitter is inside the same guard |

### 5.1 Durability matrix (the class G1 and G2 kept finding one member of: a record that must survive a kill and be attributed once)

Every durable record this change introduces, on the same five axes. A new record must add a row here before it is
designed; a reviewer finding a missing cell means the row is wrong, not that a new row is needed.

| Record | Writer (process, thread) | Written BEFORE what | Kill before the write | Kill after the write, before the consumer | Duplicate or replay | Retention |
|---|---|---|---|---|---|---|
| Install id file | any process, first bootstrap, under mutex + file lock | any vendor setup | nothing exists; the next bootstrap mints | the id exists; the SDK adopts it via `getAnonymousId`; a mismatch disables analytics | none; never re-minted; corrupt disables | forever |
| Journal admission | main, the ordered journal writer, off the main thread, bounded deadline | capture start (best effort) | take outside durable coverage; capture proceeds | entry open; a later run reports `dictation.interrupted{stage}` | insert only when absent; a late admission after a terminal is a no-op | until terminal + 7 days |
| Journal stage updates | main, the ordered journal writer | the next stage's work | stage stays older; interruption reports the older stage | correct | only while no terminal; never backward | with the entry |
| Journal `transcriptId` | main | insertion handoff | recovered ready row has no take id; repaired locally, not emitted take-keyed | association usable | none | while insertion pending, independent of pruning |
| Journal terminal (result, reason, event uuid, timestamp) | main, the ordered journal writer, conditional commit, Room transaction | the PostHog `capture` call (invoked only after the transaction succeeded) | the next run reports the take as interrupted ("no durable terminal", not proof of a crash) | the SDK disk write is on a background executor: a kill in that unmeasured interval loses the row; the take still counts as ended; STATED loss, latency distribution measured in chunk C | none by design: no replay of terminals (a replay would double the wire); a terminal cannot be overwritten | 7 days |
| PostHog row on the SDK disk queue | SDK executor | the network | lost (the window above) | delivered later by the SDK; up to 1000 rows then oldest evicted (stated) | server de-dup on uuid+event+timestamp+distinct_id if ever replayed | SDK-owned |
| Sentry event (handled) | any process; `beforeSend` runs synchronously in the caller, the cache write happens later in the SDK transport worker (`AsyncHttpTransport.java` (external) lines 221-252) | the kill (best effort; capture return is not persistence) | lost | cached; retried by that process's next SDK instance (per-process cache dir), subject to SDK eviction and rejection | server de-dup by event id + project + the timestamp's day; issue counters may still inflate | SDK-owned |
| Sentry native crash | NDK in the crashing process, written at crash time | n/a | n/a | sent through the Java client at that process's next start, passing `beforeSend` | server de-dup | SDK-owned |
| Pending-defect record (VAD wedge) | `:vad`, its own thread, temp then atomic publish under the event uuid | the self-kill | lost; the owner still sees `lost_after_ready` | main converts at most once per run with the original metadata, timestamp and event id | Sentry de-dup by event id within the original day; retained across a dead converter; analysis de-dups by event id | 7 days after the original timestamp; temp files cleaned by the writer |
| Arbiter reservation | main, inside the lifecycle critical section | commit | in-memory only; `onDestroy` commits `interrupted` if the service is destroyed; a process kill leaves the journal entry open (interrupted by the next run) | the owner commits or is revoked by destruction | one commit per take by construction | in memory |
| Room schema version 8 | migration on first open after update | any read | the app opens against 7 next time and migrates then | done | idempotent migration | forever; rollback keeps 8 |
| Insertion outcome with a History row, including the recovery transaction | main, off-main Room transaction (the existing conditional update `TranscriptDao.kt:79`, today performed asynchronously from `PasteAccessibilityService.kt:1344`) | the `insertion.terminal` capture | History stays pending; later recovery reports an unknown destination, never a proven failure | History is final; the row can still be lost before SDK persistence; no replay | emitted only for the WINNING conditional update; recovery claims and finalises in one transaction | existing History retention; the take association kept until insertion is final |
| Owner insertion outcome with no History row (`D:1237`) | main, from the immutable take context | one capture attempt | lost | governed by the SDK queue row | once, under the take's owner; no recovery, no replay | in memory only |
| PostHog's own persisted anonymous and distinct ids and SDK preferences | main SDK bootstrap | analytics enabled | the SDK has no identity yet; the install file is authoritative and `getAnonymousId` supplies it | both SDK ids validated against the file; mismatch disables analytics | never mint a replacement install id to reconcile SDK state | SDK-owned; app data clearing removes it |
| Sentry live scope tags (take, process) and persisted scope and session metadata | each process's SDK in its private directory; only main tracks release-health sessions | an event snapshot or a native crash capture | the report lacks the latest context | cached reports keep their original context; a new process never adopts a previous take as live | SDK-owned; live take tags compare-and-cleared | live tags until the take's commit; persisted metadata SDK-owned |
| Main process-run id and the immutable insertion context | main, in memory; the run id copied into journal rows | admission, and the insertion handoff respectively | no attributable record yet | journal rows keep the old run id; insertion keeps its take context after the owner clears its tag | one run id per process, never restored as the live run | process lifetime, or insertion lifetime; persisted copies follow journal retention |

Sweep: `grep -n "Room transaction\|atomic\|AtomicFile\|off the main thread\|de-dup\|replay\|retained\|pruned" docs/feature-requests/issue-176-2026-09-19-telemetry.md` must resolve every hit to a row above; a hit with no row is a new member.

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| Android rows in project 354235 | Mac `workers/shared/posthog.js` `productionClauseFor` (the ONE owner of the production predicate; consumers `daily-report/src/index.js`, `daily-report/src/adoption.js`, `daily-report/src/version-scorecard.js`, `weekly-digest/src/index.js`) | filter `environment='production'` | also `coalesce(properties.app,'enviouswispr')='enviouswispr'` in that one clause | yes, Mac repo, one file, before the first production Android build | both `live-query-smoke.mjs` runs, numbers unchanged |
| Android rows | `workers/download-counter`, `hog-relay.hog` | read `download_clicked`/`download_redirect` only | unchanged: Android emits neither | none | event-name grep |
| Android rows | `sentry-triage` TOK routine | reads GitHub, not PostHog | unchanged | none | none |
| Android rows | `~/.claude/knowledge/infra/posthog.md` shared-project rule | `app` mandatory | Android tags `enviouswispr-android` | doc row | grep |
| New Sentry project | `sentry-triage` Worker, TIK routine | read `enviouswispr` slug | unchanged; Android issues NOT routed yet | none (follow-up issue) | none |
| `TerminalReason` | History screen copy, notification copy | sentence literals | derived from the enum | yes | `TakeNoticesTest` enumerates the enum |
| `IAsrService` / `IAsrCallback` appends | instrumentation APK (separately installed client, `VoicePipelineDeviceTest.kt:122`) | legacy `transcribeFile` + `onResult`/`onError` | legacy request methods retain legacy callbacks byte for byte | none for the client | an old instrumentation-client binary exercising success AND failure against the new service, plus an append-only AIDL diff |
| Data Safety form | `docs/play-data-safety-answers.md`, Play Console | "No to all" | App activity, App info & performance, Device or other IDs: collected, not shared, purpose analytics | doc, plus the Console form before the first closed track | doc review |
| Privacy screen (PAR-092) | `PrivacyDisclosure.kt` + privacy copy | provider disclosures | one added sentence: "EnviousWispr sends anonymous crash and usage data (never your words) to Envious Labs." | yes | catalog `user_copy` row |
| Store listing | `docs/play-listing/store-listing.md:97` ("Free, no account, no tracking; no analytics SDK") | claims no analytics | reword to "no account, no ads, anonymous crash and usage data only, never your words" | yes (doc) | grep `analytics SDK` returns nothing |
| Privacy policy | `docs/play-listing/privacy-policy-android-addendum.md:3,32` ("we collect nothing", "no analytics SDK") | same claim | add the telemetry section: what, why, anonymous, no content, vendors named as processors | yes (doc, then Termly) | doc review |
| Console declarations | `docs/play-listing/console-declarations.md:58-59` ("no other data") | Data Safety shape | same change as the Data Safety row | yes (doc) | doc review |
| Onboarding welcome copy | `ui/OnboardingWelcomeStory.kt:38-41` ("Your voice stays with you", "Free. Private. Yours.") | true today | still true: voice never leaves; unchanged, recorded as reviewed (C1 G7) | none | none |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | User sees | Persisted | Retry |
|---|---|---|---|---|---|
| Vendor SDK throws at bootstrap | SDK | `Telemetry.bootstrap` | nothing | none | no; telemetry off for this process start; logged locally |
| No network | phone | PostHog queue | nothing | queue on disk up to 1000 rows, oldest dropped | SDK retries; Sentry caches offline |
| Install id file unreadable or corrupt | storage | `InstallIdentity` | nothing | telemetry disabled for this bootstrap; NO replacement id is minted | next bootstrap; logged |
| SDK anonymous id differs from the file | SDK state | `PostHogBootstrap` | nothing | analytics disabled for the run; Sentry still on | never silently; a migration is a decision |
| Journal admission fails or times out | Room | `TakeJournal` | nothing; dictation proceeds | the take has no journal coverage (stated boundary) | none |
| Journal terminal write fails after admission succeeded | Room | `TakeJournal` | nothing | the row stays open and the next run reports it as interrupted ("no durable terminal"); the take is NOT uncounted | none |
| Sanitizer redacts a legitimate value | our rule | any emitter | nothing | row with `[REDACTED]` | fix the key-exempt list |
| Keys absent (local dev build) | build | `Telemetry` | nothing | none | none |

## 8. Caller-visible signals audit

| Field | Meaning beyond its type |
|---|---|
| `take_id` absent on a take-keyed row | never emitted (a defect in this change), never "no take" |
| `reason` present on `dictation.terminal` | only when `result=failed`; presence is the signal |
| `route` absent on `insertion.terminal` | insertion never reached an editor; `result` says why |
| `live_after_ms = 0` | still waiting; never "instant" |
| `$sample_threshold` present | the row is one of N; weight it |
| `app_build` | the version floor for every new field; absence means a pre-telemetry build, never a broken emitter |

## 9. Fallback source-of-truth audit

| Failure branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| Vendor keys missing | telemetry off | `BuildConfig` | build-time constant | non-empty string | no-op facade | every emitter |
| Trigger extra missing | `trigger_source=unknown` | intent | the sender did not say | closed enum parse | `unknown` kept, never mapped to a surface | terminal row readers |
| Install id file missing | mint | `InstallIdentity` | the phone owns identity | UUID parse | mint again | both vendors |

## 10. File-by-file changes

Chunked so the app stays usable on the phone after each chunk (`workflow-process.md` step 8). Order from G2.

**A1: static vocabulary and copy only.** `ui/TerminalReason.kt`, `ui/TakeNotices.kt` (new); the existing sentence sites
in `ui/DictationSessionService.kt` read their sentence from `TakeNotices`; lifecycle transitions and guards unchanged;
no journal, vendor, AIDL or model-delivery change. Tests: `TakeNoticesTest`. Device: one real dictation into a
third-party editor. This is the first chunk that can go to the founder's phone.

**A2: owner arbitration.** `ui/TakeArbiter.kt` (new); every terminal route in `ui/DictationSessionService.kt` through
reserve and commit; an immutable terminal result; a NO-OP telemetry sink, so the arbiter depends on no bootstrap,
keys or storage. Tests: `TakeArbiterTest`. Device: normal completion, both empty outcomes, cancellation in every phase,
destruction during finalisation, late callbacks.

**A3: versioned process contracts.** `aidl/.../IAsrService.aidl`, `IAsrCallback.aidl`, `IAudioCaptureService.aidl`,
`ISilenceVadService.aidl`, `IPolishService.aidl` (append only) with their implementations (`asr/AsrService.kt` with
`asr/AsrFailureReason.kt` (new) and narrowed catch sites; `audio/AudioCaptureService.kt` peak getter and take
forwarding; `vad/SilenceVadService.kt`; `polish/PolishService.kt`) and their callers in the owner, landed together.
Tests: `AsrFailureReasonTest`. Device: the OLD instrumentation client binary (success and failure) against the new
service; take propagation, cancellation and deadlines on audio, VAD and polish.

**B: storage and the telemetry boundary.** `telemetry/*` from §3.2 (`Telemetry`, `AnalyticsEvent`, `InstallIdentity`,
`TakeIdentity`, `TakeJournal`, `PendingDefects`, `PayloadSanitizer`, `TelemetryVolumePolicy`, `DefectIdentity`,
`TelemetryChannels`, `SentryBootstrap`, `PostHogBootstrap`); `history/EnviousWisprDatabase.kt` version 7 → 8 with
`MIGRATION_7_8` and `app/schemas/com.envi.wispr.history.EnviousWisprDatabase/8.json`;
`app/src/androidTest/java/com/envi/wispr/history/EnviousWisprDatabaseMigrationTest.kt` extended with a populated
version-7 database (transcripts and custom terms) validated for literal preservation and opened through the production
builder, plus the whole supported chain to 8; `app/build.gradle.kts` (two PINNED dependencies, two `BuildConfig` fields
from `TELEMETRY_POSTHOG_KEY` / `TELEMETRY_SENTRY_DSN` gradle properties, empty default); `AndroidManifest.xml`
(`io.sentry.auto-init=false`); `models/ModelBootstrapApplication.kt` (bootstrap above the process gate). Vendors stay
OFF (empty keys) in this chunk. The resolved SDK options and one final payload per vendor are re-read against the
PINNED SDK sources before chunk C. Tests: `PayloadSanitizerTest`, `TelemetryVolumePolicyTest`, `InstallIdentityTest`,
`TakeJournalTest`, `PendingDefectsTest`, `TelemetryFacadeTest`, `AppDefectTest`, `TelemetryChannelsTest`,
`AnalyticsEventTest`.

**C: emitters.** `DictationSessionService` (terminal row at commit, breadcrumbs, take scope, owner-side insertion
rows), `PasteAccessibilityService` (accepted insertion rows), `TakeJournal` recovery (interrupted rows),
`TranscriptRepository` (recovered-ready insertion rows in one transaction), `Telemetry.bootstrap` (`app.launched` with
persisted settings, pending-defect conversion), `SilenceVadService` (pending-defect record), `PolishService` (nothing
on the deadline path; the owner reports). Then, as separately reviewable changes: `EXTRA_TRIGGER_SOURCE` forwarding
(`ui/VoiceInputActivity.kt`, `ui/SettingsActivity.kt`, `shortcuts/DictationTileService.kt`,
`paste/PasteAccessibilityService.kt`, `paste/RecordingAccessibilityOverlay.kt`) and `DeliveryFailureReason` plus
`source_host` in `models/ModelDelivery.kt` / `models/ModelDeliveryWorker.kt`, `OnboardingViewModel` (onboarding rows),
`AppViewModel`/`SettingsActivity` (settings changed, api key events). Tests: `TriggerSourceTest`. Device: the §11.1
list, including the commit-to-SDK-disk loss measurement.

**D: production configuration and disclosures.** Only after the process, payload, offline-recovery and founder-phone
checks pass: the Sentry project `enviouswispr-android` (API, platform `android`); the DSN and PostHog key as GitHub
repository variables consumed by `play-internal.yml` and `pr-check.yml`; `docs/play-data-safety-answers.md`;
`docs/play-listing/store-listing.md`, `privacy-policy-android-addendum.md`, `console-declarations.md`; the privacy
screen sentence; `CLAUDE.md` enforcer list; `.claude/knowledge/telemetry.md` (new) with the event table, the read rules
and the stated coverage boundaries; the Mac `workers/shared/posthog.js` `app` filter (Mac repo PR, prerequisite for
production).

## 11. Testing

1. **Class.** Per `testing-philosophy.md`: the payload tests that protect the public privacy promise are product-outcome
   tests (when they fail, a user's words reach a vendor). Channel routing, event identity, trigger attribution, volume
   stamps and journal accounting are OBSERVABILITY CONTRACTS, labelled as such, not product coverage. Frozen copy,
   fingerprints and wire integers are drift guards. Fake-binder and fake-storage rigs are harness contracts; the real
   boundary checks are the device list below.
2. **Expected values are independent literals.** No expected value is produced by the production mapper, hash,
   serializer, channel function or event-declared schema (`validation-discipline.md` RULE:
   an-expectation-built-with-the-mechanism-under-test-cannot-fail).
3. **Not tested by unit tests, tested on the device:** all five processes booting Sentry into separate cache
   directories, main-only PostHog, a service-only cold start emitting `app.launched`, identity across restart, disabled
   collectors (inspect SDK-added properties on a real payload), offline capture followed by kill and restart, the old
   instrumentation client against the new ASR service. One take and one native abort are NOT sufficient evidence for
   these.

### 11.1 Hardware UAT
- **Subsystem:** heart path (the owner, the ASR request, the audio start) plus limb.
- **Recipe:** the Preface take into Messages from the bubble; then on a debug build: a forced `AppDefect` behind a
  debug-only settings row, a forced native abort in `:asr`, TWO VAD cases (a debug-only stalled detector operation
  that crosses the real watchdog deadline and must produce a committed wedge record; an external kill of `:vad`
  mid-take that must NOT produce `VadCallWedged` and must show `lost_after_ready`), airplane mode during a take then
  a force-stop then relaunch, kills at several offsets after a terminal commit (fault injection, not a bound) plus the
  latency-distribution measurement, and a service-only cold start (tile from a killed app).
- **Expected observation:** `SELECT * FROM events WHERE properties.take_id = '<id>'` returns exactly the terminal and
  insertion rows with no text property and only allowlisted SDK context; the Sentry error carries the same
  `dictation.take_id` and `analytics.distinct_id`; the native abort appears under `app.process=asr` with sanitized
  frames; the VAD wedge appears after relaunch from the pending-defect record; the offline take appears after
  relaunch with its original timestamp and no duplicate; the service-only start produced one `app.launched`.
- **Restore:** remove the debug settings row before the Play build; airplane mode off; no other phone setting changes.

### 11.2 Obligations

| Test | Class | Proves | Independent expectation | Revert that turns it red |
|---|---|---|---|---|
| `PayloadSanitizerTest` | product outcome | no content leaves either vendor | one isolated witness per rule, asserted on the SERIALIZED request at a recording transport (PostHog `httpClient` interceptor, Sentry `ITransportFactory`), including SDK-added fields and Sentry envelope item types; hook-output assertions are unit checks, not wire evidence; includes an approved exception type carrying a short transcript in its message | remove that rule: its witness passes through |
| `TelemetryChannelsTest` | observability contract | every member routes as §3.6 says | a literal member→channel table in the test | change one route; exhaustiveness is the compiler's, not a runtime `else` assertion |
| `TakeNoticesTest` | drift guard | copy unchanged for every static sentence; vendor prose replaced by the one approved fallback | literal sentences copied from today's source | edit a sentence |
| `TakeArbiterTest` | observability contract | exactly one commit per take across success, each cancellation phase, both empty branches, failure, destruction and a late callback, driven through the owner's routes with fake services; cancellation and destruction DURING an outstanding History save; a callback that wins the polish ledger but loses terminal ownership; a late completion attempting insertion after revocation | literal expected `(result, reason)` per route | remove a reserve, remove revocation, or commit before the History result |
| `InstallIdentityTest` | harness contract (real files, two real processes in the instrumented variant) | one id under a two-process race with distinct candidates; an interrupted write leaves the old id; a corrupt file disables, never mints | literal UUIDs planted per process | drop the lock, or mint on corrupt |
| `TakeJournalTest` | observability contract | admission before capture under the deadline; the terminal is committed BEFORE `capture` (execution stopped after the Room commit and before the SDK call, then inside `beforeSend`, then before the SDK disk write); an earlier-run entry with no terminal becomes interrupted in the same transaction; current-run entries excluded; association retained while insertion is pending; pruning | literal journal rows | call `capture` before the commit, or key recovery on age |
| `PendingDefectsTest` | harness contract | a record is published atomically under its event uuid, survives a simulated kill, is converted by main with the ORIGINAL metadata and event id, is retained after a converter death before capture and after capture before retirement, and expires by the stated bounds | literal record bytes | publish without the temp file, or inherit the converter's scope |
| `TelemetryVolumePolicyTest` | observability contract | every declared event is kept unsampled and stamped with the policy version; the SDK context allowlist | literal allowlist and expected properties per event | drop the stamp, or let `$carrier` through |
| `TelemetryFacadeTest` | product outcome (a limb never takes the heart down) | every facade entry is a no-op before bootstrap, with empty keys, with identity disabled; a throwing vendor adapter leaves a representative dictation's outcome unchanged | the dictation outcome asserted through the owner | make one entry throw |
| `AppDefectTest` | drift guard | every fingerprint equals a committed literal snapshot file | the snapshot | rename a fingerprint |
| `TriggerSourceTest` | observability contract | missing or garbage extra parses to `unknown`; each real forwarding path (tile launcher, bubble fallback, ASSIST, settings) stamps its value | literal extras | default to a surface, or drop a forwarder |
| `AsrFailureReasonTest` | drift guard + harness contract | literal wire integers and meanings; a real cross-process callback on the instrumented variant | literal integer table | change a code |
| `AnalyticsEventTest` | observability contract | each event's complete final payload equals an independent schema (names, kinds, the bounded dynamic strings), including SDK-added fields | a literal schema file | add a property |
| `SentryBootstrap`, `PostHogBootstrap` | device validation only (§11 item 3), never claimed as unit-tested | | | |

## 12. Blast radius & rollback

- Touched: `telemetry/` (new), `ui/DictationSessionService.kt`, `asr/`, `audio/AudioCaptureService.kt`,
  `vad/SilenceVadService.kt`, `polish/PolishService.kt`, five AIDL files (append only), entry-point senders,
  `history/TranscriptDao.kt`, `models/ModelBootstrapApplication.kt`, `app/build.gradle.kts`, manifest, two workflows, docs.
- Not touched: audio DSP, insertion rules, model manifest, the transcript columns. Room gains the `take_journal` table, one migration, and a schema JSON (`app/schemas/`).
- Rollback: a Play build that keeps database version 8, its entities and migration chain while disabling telemetry
  behaviour; never a literal revert of the migration (a version-7 app against a version-8 database) and never a
  destructive fallback. The keys are compiled in, so removing a repository variable changes nothing on installed
  phones; there is no remote kill switch in this change.

## 13. Ship criteria specific to THIS change

- [ ] One S26 take shows in both vendors under one install id and one take id, with no text.
- [ ] The Mac daily report's smoke run reports the same numbers before and after Android production rows exist.
- [ ] `docs/play-data-safety-answers.md` and the privacy screen name the new data categories.
- [ ] Every `TerminalReason` member has a sentence and a channel; every `PolishReason` member has a channel.
- [ ] The old instrumentation client binary passes a failed request against the new ASR service.
- [ ] A debug-stalled VAD operation crosses the real watchdog deadline, commits a pending wedge record and appears in Sentry after main restarts.
- [ ] An external `:vad` kill produces the observer's loss outcome (`lost_after_ready`) and does not invent `VadCallWedged`.
- [ ] An offline row confirmed written to the SDK queue survives a force-stop and is delivered after restart; a kill before that point follows the stated coverage-loss contract.
- [ ] The Room-commit-to-SDK-file-write latency distribution is measured and written into `telemetry.md` with device and build.

## 14. Open questions

1. **Founder:** a telemetry toggle in Settings? macOS has none. Elena would want one; Play does not require one.
2. Resolved in G1: helper processes carry the take id over appended versioned request methods (§3.2 refactor 3).
3. **Stage 2:** the first sampled event, if volume ever demands one, ships the Mac's `$sample_*` vocabulary and a
   literal bucket test (removed from this change by G2 proportionality).
4. **Stage 2:** native symbol upload (Sentry Gradle plugin, `sentry-auth-token` in the Play workflow) so sherpa/GenieX
   frames symbolicate.
5. **Stage 2:** Android issues into the Discord notifier and TIK.

## 15. Related

#176, #43, #26, #16, `PAR-093`, `PAR-092`; catalog `privacy-safe-telemetry`; macOS #1846, #1884, #1524, #1095, #2958,
#1446, #1810.

## Citation marks

Names this plan PROPOSES (types, tests, wire event and property names, closed values), none of which exist in the
tree yet; the marks are deleted when the plan moves to SHIPPED: `AnalyticsEvent` (proposed), `asr_failure_reason` (proposed), `DefectIdentity` (proposed), `MIGRATION_7_8` (proposed), `PayloadSanitizer` (proposed), `TelemetryChannels` (proposed), `TelemetryVolumePolicy` (proposed), `ASR_BIND_FAILED` (proposed), `ASR_CALLBACK_EXCEPTION` (proposed), `ASR_EMPTY_DESPITE_AUDIO` (proposed), `ASR_EMPTY_UNMEASURED` (proposed), `ASR_FAILED` (proposed), `ASR_NOT_READY` (proposed), `ASR_PROCESS_DIED` (proposed), `AUDIO_BIND_FAILED` (proposed), `AUDIO_FILE_MISSING` (proposed), `AUDIO_PROCESS_DIED` (proposed), `CANCELLED_PROCESSING` (proposed), `CANCELLED_RECORDING` (proposed), `CANCELLED_STARTING` (proposed), `CAPTURE_CLOSE_UNSAFE` (proposed), `CAPTURE_CLOSE_UNSAFE_ON_CANCEL` (proposed), `CAPTURE_ENDED_BEFORE_LIVE` (proposed), `CAPTURE_FAILED_MID_TAKE` (proposed), `CAPTURE_START_EARBUDS_REFUSED` (proposed), `CAPTURE_START_FAILED` (proposed), `CAPTURE_START_NO_MICROPHONE` (proposed), `CAPTURE_STILL_RUNNING_AFTER_STOP` (proposed), `FINAL_TEXT_EMPTY` (proposed), `input_device` (proposed), `InsertionResultKind` (proposed), `INTERRUPTED_CANCELLING` (proposed), `INTERRUPTED_PROCESSING` (proposed), `INTERRUPTED_RECORDING` (proposed), `INTERRUPTED_STARTING` (proposed), `LIVE_WAIT_DEADLINE` (proposed), `PendingDefects` (proposed), `PendingDefectsTest` (proposed), `POLISH_BIND_FAILED` (proposed), `POLISH_PROCESS_DIED` (proposed), `PolishProtocolViolation` (proposed), `SETTINGS_UNAVAILABLE` (proposed), `START_EXCEPTION` (proposed), `take_journal` (proposed), `TakeArbiter` (proposed), `TakeArbiterTest` (proposed), `TakeJournal` (proposed), `TakeJournalTest` (proposed), `AppDefectTest` (proposed), `PostHogBootstrap` (proposed), `SentryBootstrap` (proposed), `takeId` (proposed), `TakeIdentityTest` (proposed), `TelemetryFacadeTest` (proposed), `TriggerSourceTest` (proposed), `accessibility_granted` (proposed), `admission_failed` (proposed), `AnalyticsEventTest` (proposed), `app_build` (proposed), `app_version` (proposed), `AppDefect` (proposed), `asr_chars` (proposed), `asr_cold_start` (removed) (proposed), `asr_empty` (proposed), `asr_empty_despite_audio` (proposed), `asr_interrupted` (proposed), `asr_process_died` (proposed), `AsrFailureReason` (proposed), `AsrFailureReasonTest` (proposed), `assist` (proposed), `audio_interrupted` (proposed), `audio_missing` (proposed), `audio_process_died` (proposed), `audio_unreadable` (proposed), `BREADCRUMB` (proposed), `bubble_hold` (proposed), `bubble_tap` (proposed), `build_type` (proposed), `bytes_bucket` (proposed), `capture_terminal` (proposed), `Channel` (proposed), `custom_words_count` (proposed), `decode_failed` (proposed), `device_model` (proposed), `disk_full` (proposed), `duration_s` (proposed), `elapsed_s` (proposed), `first_run` (proposed), `getTakePeakAmplitude` (proposed), `history_save` (proposed), `http_status` (proposed), `input_pick` (proposed), `InstallIdentity` (proposed), `InstallIdentityTest` (proposed), `integrity_mismatch` (proposed), `is_fresh_install` (proposed), `latency_ms` (proposed), `live_after_ms` (proposed), `live_state` (proposed), `LocalPolishDeadline` (proposed), `lost_after_ready` (proposed), `manifest_unavailable` (proposed), `mic_granted` (proposed), `model_not_loaded` (proposed), `models_ready` (proposed), `os_version` (proposed), `over_limit` (proposed), `partial_response` (proposed), `PayloadSanitizerTest` (proposed), `peak_amplitude` (proposed), `polish_process_died` (proposed), `polish_provider` (proposed), `polish_reason` (proposed), `polish_status` (proposed), `recording_s` (proposed), `redirect_refused` (proposed), `route_kind` (proposed), `route_reason` (proposed), `semanticId` (proposed), `silence_stop_status` (proposed), `source_host` (proposed), `stage_reached` (proposed), `staging_failed` (proposed), `start_failure` (removed) (proposed), `take_id` (proposed), `TakeIdentity` (proposed), `TakeNotices` (proposed), `TakeNoticesTest` (proposed), `target_app` (proposed), `Telemetry` (proposed), `telemetry_policy_version` (proposed), `TELEMETRY_POSTHOG_KEY` (proposed), `TELEMETRY_SENTRY_DSN` (proposed), `TelemetryChannelsTest` (proposed), `TelemetryVolumePolicyTest` (proposed), `trigger_source` (proposed), `TriggerSource` (proposed), `VadCallWedged` (proposed).

Names defined outside this repository (vendor SDK options and key prefixes, macOS symbols and events cited as
precedent, PostHog's own column): `executeSafely` (external), `httpClient` (external), `ITransportFactory` (external), `setDefaultPersonProperties` (external), `collectAdditionalContext` (external), `enableRootCheck` (external), `download_clicked` (external), `download_redirect` (external), `productionClauseFor` (external), `ACTION_ASSIST` (external), `attempt_completed` (external), `beforeBreadcrumb` (external), `beforeSend` (external), `clipboard_only` (external), `custom_words_imported` (external), `DictationNarrator` (external), `distinct_id` (external), `enableNdk` (external), `error_code` (external), `getAnonymousId` (external), `key_` (external), `phc_` (external), `polish_failed` (external), `SettingsChangeTelemetry` (external), `sntrys_` (external), `telemetryChannel` (external), `validation_completed` (external).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written (Codex trace T1 adjudicated in §2.5.6)
- [x] §4-9 answered
- [x] Lane declared
- [ ] Self-reviewed to all-clear before any reviewer saw it
