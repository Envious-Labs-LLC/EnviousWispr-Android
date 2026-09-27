# Issue #378 — A full local log behind a hidden Developer switch in the one Play app — 2026-09-27

GitHub issue: `#378`. Tier: LARGE. Status: APPROVED (revision 2: one app, founder decision 2026-09-27; Gate 2 2026-09-27). Revision 1 (two apps) is kept at `.codex/2026-09-27-issue-378-plan-revision1.md`.

Consolidation: none. This change adds a surface (a file sink behind a switch, a Developer page, an archive) beside existing owners; it merges no duplicated logic. `DebugLogger` remains the only logcat door, and the new sink is fed only through it and through one content entry point.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (app/**: `tests`, `codex-review`, `hardware-uat`), `Docs/dev-tooling` (.claude/** rule and knowledge edits: `cited-symbols`).

**PAR rows closed:** `PAR-090` (local debug logging deliberately enabled, bounded rotation and a share action: closed by the Detailed log switch, the rotation and Share log, evidenced by the S26 run in §11.1); `PAR-091` (optional dictation audio archive with retention controls: closed by Keep recordings, evidenced by §11.1 step 5 and gated on the S26 link probe, D9). `PAR-089` (diagnostics screen with a copyable readiness report, no secrets or dictated content) is adjacent, NOT closed: this Developer page is a log surface that carries dictated content by design.

**Hardware UAT:** Y. Saurabh, on his Play internal build, opens the drawer, taps the version line seven times, opens Developer, turns Detailed log on, dictates three takes into Gmail and WhatsApp, taps Share log, sends it to himself, and the log shows for each take one take id on every line, the words at each step (raw speech, after cleanup, after polish, as inserted) and the step timings. With the switch off, nothing is written.

## Preface — User Rubric

Customers' behaviour does not change: the Developer page is hidden and both switches are off. History gains timing fields (§3 D8) that no customer screen shows. The rubric is answered for the founder as Priya Ramachandran (the technical daily user who wants sub-second, accurate dictation) because he dogfoods as her.

1. **Who and when.** Priya-as-founder dictated a Slack reply, a word came out wrong or slow, and thirty seconds from now she wants to know which step did it without plugging in a cable.
2. **Why.** "When it messes up I want to see what it heard, what cleanup did and what polish did, and how long each took, like on my Mac."
3. **How invoked.** Once: unlock Developer, turn Detailed log on. Then reactively, right after a bad take, or weekly to hand a log to Claude: Developer, Share log.
4. **Apps.** Dictation into Gmail, WhatsApp, Slack, Chrome; the log leaves through Gmail, Drive or Quick Share to the Mac.
5. **Natural input.** "that last one said polish twice", "why did Gmail get the clipboard", "the first take after lunch was slow", "send me today's log", "keep the audio of that one".
6. **Success.** The shared log reads like the Mac `app.log`: one take id, every step, words and milliseconds, one attachment.
7. **Wrong-not-broken.** The log exists but a process's lines are missing or its timings are nonsense (today's uptime-relative marks), so it cannot be trusted and is not used.
8. **Power-user hack.** `adb logcat` over wireless debugging, which is what exists today and is exactly what he does not want.
9. **Control ladder.** Hidden and off (every customer); Developer unlocked but off; Detailed log on; Keep recordings on. Clear saved recordings and Delete shared log ZIPs are buttons. The log itself is bounded by rotation (§14 Q2).

### Cross-persona check
Every customer persona (Marcus, Diana, Elena, Aaron, Meera, Frank) sees nothing new: the Developer entry is hidden and both switches are off. Dr. Elena Vasquez would object to a content-bearing file; she would have to find a hidden seven-tap unlock and turn it on herself, the file never leaves the phone unless she taps Share, and the Privacy page says so while it is on (D5). No tension left for §3.

---

## 0. TL;DR

Today the Play build keeps no local log: every line goes to logcat and is lost unless a cable is attached, History keeps only the polish time, and no audio is kept. The founder chose one app (2026-09-27): the Play internal build he dogfoods is the exact file customers later receive by promotion, and a hidden Developer page with a Detailed log switch gives him a Mac-style rotating on-device log per process, with take ids, per-step words and timings, a Share log button, and optional kept recordings. Customers get the same file with the page hidden and the switches off. Local debug builds (the emulator) default the switches on. History gains per-step timings. LARGE: every process's logging, a Room migration, the insertion path's logging. Evidence: unit and shape tests, emulator takes, and the S26 run in §11.1.

## 1. Problem

- The Play build is `:app:bundleRelease` (`scripts/release/build.sh:15`). `DebugLogger` writes logcat only; "There is no file sink" (`app/src/main/java/com/envi/wispr/debug/DebugLogger.kt:16`). Getting any line off the phone needs `adb`.
- History keeps `polishLatencyMs` but no speech, insertion, start-up or end-to-end time (`app/src/main/java/com/envi/wispr/history/TranscriptEntity.kt:12-40`). Those numbers exist in memory (`TakeFacts`) and in PostHog rows, never locally.
- `DebugLogger.startPipeline()` is called only in `:audio` (`app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:505`); `mark` in main (`ui/DictationSessionCoordinator.kt:851,862`) and `:asr` (`asr/AsrService.kt:170,201,218`) measures from device uptime.
- Captured audio is deleted at every take ending (`ui/CapturedAudioFiles.kt:20-28`).
- The Mac developer build has all of this: `app.log` 10 MB x 5 (`~/Library/Logs/EnviousWispr/`, 50 MB on this Mac 2026-09-27), `CORRECTION_DEBUG` (external) IN/OUT per step, `ExecutionMetrics` (external) per History row, `DictationAudioArchive` (external) newest 500.

## 2. Goals & non-goals

### 2.1 Goals
1. G1. A hidden Developer page, unlocked by seven taps on the drawer's version line, holding a Detailed log switch (default off in release builds, on in debug builds) and Keep recordings (same defaults).
2. G2. With Detailed log on, every process writes a rotating on-device log under app-private storage, every line carrying a wall time, a boot-relative time, the process, the level, the tag and the take id where one exists. With it off, nothing is written and no content string is built.
3. G3. The log carries the words at each step: raw speech, after vocabulary restore, after each deterministic cleanup family, the polish input and output (local and cloud), the final text, and the insertion outcome with its target app.
4. G4. The log carries per-step timings: accepted to live, recording length, speech model load, speech decode, polish model load, polish, insertion, end to end.
5. G5. The Developer page: log size, Share log (one zip through the share sheet), Delete shared log ZIPs, Keep recordings, recordings count and size, Share recent recordings, Clear saved recordings.
6. G6. History rows keep per-step timings (Room 9 to 10).
7. G7. Logcat stays content-free; PostHog and Sentry payloads are unchanged.
8. G8. Claude reads the log and kept recordings, and sets both switches, from the Mac over adb (the phone is reachable over Tailscale, `.claude/knowledge/device-testing.md`) with no tap from the founder (founder 2026-09-27: "I am personally never gonna look at the logs. That is something you do."). Only the adb shell's user id can use this door (D10).

### 2.2 Non-goals
- No second Play app, no build type, no pipeline change (the founder chose one app; the promoted production file is the dogfooded file).
- No change to what PostHog or Sentry receive.
- No readiness report (PAR-089), no benchmark suite, no log level picker.
- No Clear log (§3 D7, §14 Q2).
- No on-phone log viewer.

## 2.5 Grounding brief

### 1. Producer to owner to consumer

- **Log lines.** Producer: 200+ call sites of `DebugLogger.debug/log/warn/error/mark` across five processes (`:audio` 67, `:vad` 9, `:asr` 25, `:polish` about 26, main about 70, plus `telemetry/Telemetry.kt` in every process; attributed per file by package and hosting service in `app/src/main/AndroidManifest.xml:75-132`). Funnels ending in `DebugLogger`: `ui/SessionLog.kt` (`DebugSessionLog`), `paste/InsertionOutcomeLine.kt`, the `logInfo`/`logWarn` lambdas in `providers/ProviderPolishClient.kt:23-24`, `providers/ProviderModelDiscoveryClient.kt:29-30`, `providers/HttpProviderTransport.kt:73`. Owner: `debug/DebugLogger.kt`. Consumer: logcat only (`DebugLogger.kt:76-92`).
- **Take id.** Created at `ui/DictationSessionCoordinator.kt:340`; crosses AIDL in `IAudioTakeService.startCaptureForTake`, `ISilenceVadService.startForTake`, `IAsrService.transcribeFileForTake`, `IPolishService.polishRequestForTake`. Not in scope at `AsrService.doTranscribe`/`initRecognizer` (`asr/AsrService.kt:183-293`) nor `PolishService.run`/`polishWithS1`/`deliver` (`polish/PolishService.kt:376-597`); the registry entry holds it (`polish/PolishService.kt:194`, `polish/PolishRequestRegistry.kt:18`).
- **Words.** Raw speech `asr/AsrService.kt:215` (`:asr`), arriving in main at `ui/DictationSessionCoordinator.kt:854`; vocabulary restore `ui/TakePolishController.kt:214`; cleanup `cleanup/PolishPipeline.kt:37-38` and `cleanup/DeterministicCleanup.kt:109-189` (`:polish`); local polish prompt and output `polish/PolishService.kt:575-597`; cloud prompt `polish/PolishService.kt:411`, answer `providers/ProviderPolishClient.kt:140`; pipeline result `polish/PolishService.kt:439`; answer restore `ui/TakePolishController.kt:351`; fallback `ui/TakePolishController.kt:445-453`; final text `ui/DictationSessionCoordinator.kt:955-963`, handed to insertion at `ui/SessionFinalizer.kt:381`; insertion outcome `paste/AccessibilityInsertionRunner.kt:269-290,605-610`.
- **Timings.** Accepted `ui/DictationSessionCoordinator.kt:339`; live-after in `:audio` `audio/AudioCaptureService.kt:262-266`, recorded at coordinator :744; recording length coordinator :822; speech decode `asr/AsrService.kt:203-214`, speech init :257-287, round trip coordinator :852-860; polish latency `polish/PolishService.kt:239,439`, S1 load :540-547; insertion `paste/AccessibilityInsertionRunner.kt:132,285,609`. In memory: `telemetry/TakeFacts.kt:27-71`.
- **Audio.** `cacheDir/recording-take-<takeId>.pcm` (`audio/CaptureFiles.kt:16`), 16 kHz mono 16-bit PCM (`audio/PcmAudio.kt:5`, `audio/AudioCaptureService.kt:48-50`); closed in `closeResources` (`audio/AudioCaptureService.kt:833,877-880`) before `takeEvents.publishEnded` (:853); main learns the path only from that ending (`ui/DictationSessionCoordinator.kt:725,819,902`; `speechAudioPath` :844) and deletes it through `CapturedAudioFiles.delete` on its one worker (`ui/CapturedAudioFiles.kt:20-28`); `:audio` sweeps earlier take files at the next start (`audio/AudioCaptureService.kt:573,690-698`).
- **Settings.** `settings/AppPreferences.kt` (DataStore, main process only, :22); the drawer's version line `ui/AppNavigation.kt:264`; pages from `SettingsPage` (`ui/AppNavigation.kt:69-106`), rendered by the exhaustive `when` in `ui/AppShell.kt:202-235`.

### 2. Existing authority

- Logging: `DebugLogger` is the one owner (#194). No file sink, FileProvider or `ACTION_SEND` (external) anywhere in main or debug (negative sweep 2026-09-27: `/usr/bin/grep -rn "FileProvider\|ACTION_SEND\|createChooser\|<provider" app/src` returned nothing). New authority proposed: `LocalLog` (proposed).
- Cross-process settings: none; DataStore is main-only. New authority proposed: a mirror file written only by main (D2).
- History migrations: `history/EnviousWisprDatabase.kt` version 9, `MIGRATION_8_9` pattern (:108-114), `app/src/androidTest/java/com/envi/wispr/history/EnviousWisprDatabaseMigrationTest.kt`.

### 3. Prior attempts and live direction

- #194 (2026-09-22) deleted the shared-storage file sink and made every diagnostic content-free. Binding: `kotlin-patterns.md` RULE: no-content-in-diagnostics and `DiagnosticsShapeTest`. This plan narrows the rule for one surface, the app-private log file written only while the switch is on, and keeps logcat and telemetry as #194 left them (D5).
- CLAUDE.md privacy: the boundary is the network. A file that never leaves the phone except by the user's own share passes the test.
- Catalog `debug-local-log`: macOS compiles its log out of release; Windows ships a local log in every build. This plan takes the Windows shape (code in every build) behind a switch that is hidden and off for customers, because the founder chose one app whose dogfooded file is the promoted file.
- Revision 1 of this plan (two apps, a `dev` build type) passed nine Codex rounds; its file-concurrency work (D4, D6, D9, §5.1) carries over unchanged, its two-app parts are dropped.

### 4. Boundaries

- Five processes, each with its own `DebugLogger` object and its own file set; ordering across files uses `SystemClock.elapsedRealtime()`, one boot clock for the phone.
- Only main reads DataStore; helpers read the mirror file (D2).
- Process death: a line handed to the kernel by `write` survives the process; queued lines are lost.
- Customers and the founder run the same file; the only difference is two switch values.

### 5. High-risk premises

| Premise | Evidence |
|---|---|
| Console state, read live 2026-09-27 | One app "EnviousWispr" `com.envi.wispr`, Draft; internal testing Active, "Internal build 225 (2e97396)" released Sep 25, not reviewed; tester list "EnviousWispr Internal Testers", 2 users; production, open and closed testing inactive; setup tasks open; automatic protection on. Promotion to production needs the setup tasks and Google's review, which is launch work, not this change |
| A file's existence is an atomic, cross-process fact | `File.exists` is a `stat`; `createNewFile` (external) and `delete` are single syscalls; D2 relies on nothing more |
| `Os.link` (external) from `cacheDir` to `filesDir` works | same app data volume; verified on the S26 in chunk 3 before PAR-091 closes (D9) |
| Gmail accepts 25 MB | support.google.com/mail/answer/6584; the zip size is measured and shown, never promised (D6) |

## 3. Design

**D1. The hidden Developer page.** Seven taps on the drawer's version line (`ui/AppNavigation.kt:264`) within 3 seconds set `developerUnlocked` (proposed, a DataStore key) and show a toast "Developer options unlocked". `SettingsPage.Developer` (proposed), group SYSTEM, appears in the drawer only when unlocked (debug builds: always). The drawer filters it at render; `AppNavigationShapeTest` is updated to "entries minus hidden pages". English only (catalog decision 2026-09-25). Rejected: a separate build or a remote flag; the founder chose one file for everyone.

**D2. The switches and how every process learns them.** Two DataStore keys in main: `detailedLog` (proposed) and `keepRecordings` (proposed), each defaulting to on in a debuggable build and off otherwise. DataStore stays the authority and stays main-only. Main mirrors each switch into a flag file that exists exactly when the switch is on: `filesDir/flags/detailed-log` (proposed) and `filesDir/flags/keep-recordings` (proposed), created with `createNewFile` or removed with `delete`, on every toggle and at main cold start (repair, with the Off rule below). Helpers never read DataStore (Android's single-process rule). The flag file is the gate for writing, and it is checked where the writing happens, under the lock: each writer checks `detailed-log` under its process's lock immediately before evaluating any queued entry and writing a batch, and discards the batch if the flag is absent. Callers never touch disk: a process-local atomic hint decides whether a call enqueues at all. The hint is event-driven, never polled (`architecture-rules.md` RULE: no-idle-cost): each process holds one `FileObserver` (external) on `filesDir/flags/` and `filesDir/logs/` (the fence file), whose thread sleeps in the kernel until a flag or fence file is created, moved into place or deleted; on an event it re-reads the flag files into the hint and, for a fence, wakes the writer. The writer thread sleeps on its queue and wakes only for an enqueued line or such an event. With Detailed log Off and no fence, this change schedules no periodic wake: the log writer waits for queue work and the observer waits for filesystem events. Other app and Android threads may run. At each process start, create `filesDir/flags/` and `filesDir/logs/` before starting one strongly held `FileObserver(List<File>, CREATE|MOVED_TO|DELETE|DELETE_SELF|MOVE_SELF)` on those directories. Start watching, then re-read both flags and the current fence id. Wake the writer for an unacknowledged, non-cancellation fence. Filter events by the exact flag and fence names; after a watched directory is deleted or moved, recreate its watch and rescan. At each take admission, enqueue a nonblocking flag-and-fence refresh ahead of lazy take lines and allow those lines to queue until it resolves; the writer still checks the flag under its lock before evaluating them. This gives a lost observer event a recovery path without an idle timer or caller disk I/O; and every queued entry is lazy (a lambda over values the caller already holds), so no log string and no word string is built on the caller's thread and none is evaluated unless the writer, holding the lock, has just seen the flag present. A stale hint costs only a queued closure that is then discarded, or a moment of lines not logged just after On. Settling Off is a barrier, not a poll. Run the Detailed log Off barrier on a dedicated background switch worker, never the UI, log export, or audio worker. Acquire all five log locks in fixed order (main's own through the in-process mutex), each with the 2 s `tryLock` bound; while holding them, remove the flag, persist DataStore Off, and verify both states. Release every lock in `finally`, then show Off. A timeout or failed write leaves the page Pending or Error, never settled. At cold start, if DataStore says Off but the flag still exists, use the same barrier before showing a settled switch; never delete that flag through unbarriered repair. The page derives its settled state from both values, not DataStore alone. Serialize Detailed log On, Off, and cold-start mirror repair on the same dedicated switch worker. Process toggle requests in user-action order; only the latest request may publish a settled UI state. An On operation cannot create the flag after a later Off has completed. Off still takes the five-lock barrier. Serialize Keep recordings On, Off and cold-start mirror repair on the dedicated switch worker in user-action order, alongside Detailed log requests. Only the latest request may publish a settled state; its DataStore value and flag must match. Keep recordings needs no five-log-lock barrier, but an older request may never change its flag after a newer request completes. After Detailed log On settles, the page shows "Starting detailed log" until main's own hint has seen the flag, and states plainly that lines logged in the moment before each helper's observer delivers the event may be missing; a blocked observer thread can extend the gap. A writer mid-batch holds its lock, so main waits for that batch to finish before deleting; after the delete, every writer's next under-lock check sees the flag absent. If any lock is not obtained, the page stays Pending or shows an error; it never shows Off. Turning On, and Keep recordings in either direction, need no barrier: the page shows a switch settled only after the DataStore write and the flag change both succeeded (the flag's existence re-read equals the new value), and a mirror failure shows an error, never a settled state.

**D3. Off means off.** With `detailed-log` absent: the writer evaluates no queued entry and writes nothing, and callers enqueue nothing once the hint has caught up; `LocalLog.words` takes its text as a lambda that only the writer, under the lock with the flag present, ever invokes, so no content string is built while Off. Turning the switch off stops new bytes at confirmation (the barrier in D2). Files already written stay: with logging stopped, rotation cannot age them out, so they remain until later logging overwrites them or the app is uninstalled (no Clear, D7). The Developer page says so under the switch, and the Privacy page says so while they exist (D5).

**D4. The log file.** One owner per process, `LocalLog` (proposed), in `app/src/main`:
- Files: `filesDir/logs/<process>.log` and `<process>.1.log` to `.3.log`, 2.5 MB each: 10 MB per process, 50 MB total, the Mac's total. Each process rotates only its own files.
- Per-process lock: `filesDir/logs/<process>.lock`, a `FileChannel` (external) file lock (an `fcntl` lock, honoured across processes). Each process has one lock coordinator: an in-process mutex taken before the file lock, because a second overlapping lock in the same process throws `OverlappingFileLockException` (external). The writer holds its own process's lock for every append batch and for rotation. Only the Off barrier (D2, on main's switch worker) takes another process's lock, with `tryLock` (external) in a loop bounded at 2 s; a dead process holds nothing. Share never takes another process's lock: each writer pins its own snapshot (D6).
- Files are only ever appended, renamed (rotation) or unlinked (the oldest rotation); never truncated or rewritten, so bytes below a recorded length never change.
- Line: `2026-09-27T12:52:25.123-04:00 b=812220745123 [main] I [Tag] take=7837f3e0 message`. `b=` is `elapsedRealtime` at enqueue time, for ordering across files; the wall time is for people.
- One daemon writer thread per process fed by a bounded queue (8,192 lines, drop-oldest with a dropped-count line); it drains in batches with `FileOutputStream` in append mode, no `fsync`. Callers never touch disk. No other actor deletes or rewrites a log file.
- Crash hook: installed in each process only after `Telemetry.bootstrap` returns, wrapping whatever default handler is then current (Sentry's). On an uncaught exception it asks the writer to flush and waits at most 200 ms; the flush is an ordinary batch with the same under-lock flag check, so it cannot write queued content after Off confirmed; it never performs file I/O on the crashing thread; in `finally` it calls the captured handler exactly once, including when the flush fails or times out.
- `DebugLogger.debug/log/warn/error/mark` also hand their already-rendered line to `LocalLog`; logcat output is unchanged.
- Take id as structured metadata, fail closed: a take-scoped logger, `TakeLog` (proposed), is created from the take's id where each process first learns of the take (admission in main, replacing the singleton `DebugSessionLog` at `ui/SessionLog.kt:19` for take work; `startCaptureForTake` in `:audio`, handed to `TakeRoute`; `startForTake` in `:vad`; `transcribeFileForTake` in `:asr`; `polishRequestForTake` in `:polish`). It has no nullable id and forwards to `DebugLogger` with the id as a field, never parsed from the message and never read from process-global state; logcat output does not change. `DiagnosticsShapeTest` forbids a direct `DebugLogger` call in the take-scoped owners. Plain `DebugLogger` calls elsewhere are written `take=-`. A runtime oracle drives a real take through all five processes and fails if a line from a take-scoped owner between admission and ending carries `take=-`.
- Content goes through one entry point, `LocalLog.words(tag, takeId) { text }` (proposed): file only, never logcat. Its call sites are exactly the §2.5 word points, including one after each enabled `DeterministicCleanup` family (`cleanup/DeterministicCleanup.kt:121`) and a recovered result.
- Take id at the edges: `initRecognizer` also runs at `:asr` service creation before any take (`asr/AsrService.kt:238`), so those lines say `take=-`; `PolishService.run`, `polishWithS1` and `deliver` take the id from the request itself, because polish can answer before registration (`polish/PolishService.kt:180,194`).
- Take endings: every winning commit of the take's terminal arbiter logs the take id and terminal reason, including endings with no audio file (`ui/DictationSessionCoordinator.kt:983,1035,1087,1137`).
- The clock defect: `DebugLogger.startPipeline` is called at take admission in main and at request start in `:asr` and `:polish`, so every `mark` measures from its own process's start of the take.

**D5. The privacy boundary, stated as enforcement.** Logcat stays content-free in every build; `DiagnosticsShapeTest` gains checks that only `LocalLog.words` may carry a transcript-bearing value into the file and never calls `android.util.Log`, and that file I/O appears only in `LocalLog`. Every object interpolated into a `DebugLogger` line is pinned to a content-free rendering (for example `PolishOutcome.toString`, which prints `chars=` only, `polish/PolishOutcome.kt:30`, logged at `polish/PolishService.kt:378`). A sentinel test sends known dictated words through every word point and asserts they appear in the file when the switch is on, in no file when it is off, and never in logcat or a telemetry payload. The rule text becomes: logcat and telemetry describe shape; the app-private log file may carry content through `LocalLog.words` only, and only while Detailed log is on. The Privacy page (`ui/PrivacyPage.kt:22`, sentences in `privacy/PrivacyDisclosure.kt`) shows a detailed-log sentence whenever Detailed log is on OR a local log file or shared export remains, including after the switch is off: "Detailed log keeps what you dictate in a file on this phone. Turning it off stops new logging; earlier logs stay on this phone until later logging overwrites them or the app is uninstalled. A copy you shared stays in the app you shared it to." `PrivacyPage` receives the switch state and whether retained files exist; `PrivacyPageSentencesTest` covers on, off with files, and fresh off. The toggle handlers for the unlock and both switches deliberately emit no settings-changed telemetry event (the sanitizer's generic `setting`, `from` and `to` keys at `telemetry/PayloadSanitizer.kt:51` would otherwise admit one), and a test asserts that neither switch name nor value reaches PostHog or Sentry.

**D6. Share log.** `FileProvider` (external) with authority `${applicationId}.logs` in the main manifest, exposing only `cacheDir/share/`. Share runs on main's single-thread log worker, which also runs the export sweep and Delete shared log ZIPs. Before recording file lengths, Share requests a fence from each live process's writer. A fence is acknowledged only after that writer has processed every line queued before the Share request. Wait on the export worker, never a capture or insertion thread. If a process cannot acknowledge within a bound, mark it incomplete in `device.txt` and visibly on the Developer page; never present that ZIP as complete. Mechanism, with no new IPC and no liveness probe: Share writes `filesDir/logs/fence` (proposed; a new id, written to a temporary file and renamed into place). Every writer, main's included, is woken by its `FileObserver` when the fence file is moved into place (no timer). For a fence ID, each writer drains entries queued before that fence and, under its log lock, pins an immutable snapshot of its current rotated files and the active file's byte length before acknowledging. Use distinct per-fence snapshot paths; later rotation must not remove their bytes. (Pinning is a hard link per file, `Os.link` into `filesDir/logs/snapshots/<fenceId>/<process>/` (proposed), plus the recorded active length; a link keeps the inode's bytes through rotation, and bytes below the recorded length never change because files are only appended.) The acknowledgment, `filesDir/logs/fence-ack/<process>` (proposed, temp and rename), records `written`, `discarded-by-Off`, or `dropped`, including any queue-overflow count since that writer's previous acknowledged fence or its start. Share waits up to 2 s for all five acknowledgments and reads the pinned snapshots, not the later live filenames. The ZIP and Developer page report only each process's fence acknowledgment status and drop count. They never call a ZIP complete. Rotation can evict a line between the Share tap and that process's snapshot. A missing acknowledgment is reported as "did not confirm within 2 s: not running, or busy", never read as death. The 50 MB limit applies to live logs; snapshot pins use additional temporary storage (at most 50 MB, bounded below). On every Share exit, remove that fence's pins in `finally`. Pins are never written after creation, so removing them corrupts nothing; the only race is a writer that passed its fence check creating a pin just after a cleanup scan, which leaves an orphan. That race is bounded and disclosed rather than locked: at main startup, main first replaces the fence with a cancellation id that writers do not acknowledge or pin for, then removes every snapshot directory; at the start of every Share, after writing the new fence, main removes every snapshot directory except the new fence's; each writer rechecks the fence under its own lock immediately before pinning. Each of the five serial writers can create one stale snapshot after a cleanup scan, so up to five orphan fence directories can coexist. Each contains at most that writer's 10 MB log set, for at most 50 MB extra storage. A startup or Share whose cleanup begins after an orphan was created removes it. Share reads only pins bearing its own fence id. The active Share's pins are never removed before its ZIP read ends, because Share and cleanup run on the one log worker. `device.txt` also holds app version, build, phone model, Android version, free storage and the switch states. The zip is shared with a `content://` URI in `ACTION_SEND`, MIME `application/zip`, `EXTRA_STREAM` (external) and `FLAG_GRANT_READ_URI_PERMISSION` (external). Its size is measured and shown; Gmail delivery is not promised. Export lifetime is separate from log lifetime, because a receiving app may open the URI later: each Share writes a random UUID file name created with create-new semantics (retry on collision, never overwrite); a later Share never deletes an earlier export; exports older than 24 hours are removed on that worker before the next Share and at cold start; Delete shared log ZIPs runs on that worker after any Share queued before it, and warns that an app still opening one would fail. Its copy: "Delete shared log ZIPs removes only log exports. Shared recording WAVs remain for their stated 24-hour lifetime."

**D7. No Clear log.** Deleting another process's live log file has no safe moment without a proven process-wide stop barrier (revision 1, grounded round 4). While Detailed log is on, the log is bounded by rotation; while it is off, existing files remain unchanged until later logging overwrites them or the app is uninstalled. Clear saved recordings stays (D9).

**D8. History timings.** Room 9 to 10 adds nullable INTEGER columns `liveAfterMs`, `asrMs`, `insertionMs` (proposed) and `endToEndMs` (proposed) to `transcripts` (`polishLatencyMs` exists). `liveAfterMs` and `asrMs` are persisted from `TakeFacts` (`telemetry/TakeFacts.kt:27-71`) when the row is saved; `insertionMs` and `endToEndMs` by the later insertion-outcome update (`history/TranscriptDao.kt:94`), `endToEndMs` being admission (`ui/DictationSessionCoordinator.kt:339`) to the insertion ending, or to publication when no insertion runs. Model load times stay log lines in their own process (an AIDL result field would be REFACTOR tier). Null means not measured. Recorded regardless of the switches (numbers only, local). The expanded History card shows one timings line only while Developer is unlocked.

**D9. Keep recordings.** In `:audio`, immediately after `closeResources` closes the file (`audio/AudioCaptureService.kt:877-880`) and before `publishEnded` (:853), when `filesDir/flags/keep-recordings` exists, `:audio` hard-links the finished production take's PCM to `filesDir/recordings-pending/<takeId>.pcm` (proposed) with `Os.link`; a failed link is logged and that take is not kept. A pending file is created complete and never written again. The cache name keeps today's lifecycle (main's retirement delete, `:audio`'s next-take sweep). Main learns the path only from the ending published after the link, so every retirement runs after its link exists. Main's retirement on the `CapturedAudioFiles` worker additionally, when Keep recordings is on, renames the pending file to `filesDir/recordings/<takeId>.pcm` and writes `<takeId>.json` (take id, outcome, duration, sample rate, created time; no text); otherwise deletes it; newest 200 kept, never more than 1 GB. Pending files over an hour old (main died before the ending) are removed on that worker at main startup. Share recent recordings and Clear saved recordings run on that same worker; Share selects and opens its sources there, converts to WAV in `cacheDir/share-recordings/` (proposed; UUID, create-new, 24-hour lifetime swept on that worker) and closes every descriptor. Clear saved recordings removes the recordings saved when its task runs; a take in progress can be saved afterwards. A take is eligible for keeping only when `:audio` saw Keep recordings on at close; a later Off before retirement discards its pending link; a missing pending link is never shown as kept. The page explains that a toggle can affect a take already in progress. Acceptance: Keep recordings and PAR-091 are not done unless the S26 probe creates the link and reads identical bytes after the cache name is deleted.

**D10. The adb door.** A content provider, `DeveloperLogProvider` (proposed), in the main manifest with authority `${applicationId}.devlog`, exported, `android:readPermission` and `android:writePermission` both `android.permission.DUMP`. DUMP can be granted to another app through adb; the shell UID check is the door's additional restriction (Access boundary below). DUMP is the same gate the debug build's `DebugDumpReceiver` already relies on (`app/src/debug/AndroidManifest.xml:17-22`). It serves:
- `content://<authority>/log.zip`: `openFile` (external) runs exactly the Share pipeline of D6 (fence, pins, per-process status in `device.txt`) on the log worker and hands back the finished ZIP file (see ZIP delivery below); the binder thread waits with a bound, never the UI thread. Read from the Mac with `adb exec-out content read --uri content://com.envi.wispr.devlog/log.zip > log.zip`.
- `content://<authority>/recordings.zip?last=N`: the recordings share of D9 on the `CapturedAudioFiles` worker. The recording ZIP is built to a cache file and returned through a read-only descriptor.
- `call` methods `status`, `setDetailedLog` (proposed), `setKeepRecordings` (proposed): each first checks `checkCallingPermission(android.permission.DUMP)` (external) itself and throws `SecurityException` otherwise, because a provider's `call` is NOT covered by its read or write permission; the setters go through the one switch worker of D2 (the same ordering and the same Off barrier) and return the settled state, or Pending or Error.
Access boundary: keep the manifest DUMP permissions, and also require `Binder.getCallingUid() == Process.SHELL_UID` (external) in every `openFile` and `call` entry before reading or changing state. Reject every other UID with `SecurityException`, including an app explicitly granted DUMP. Accept only the two named read-only URIs, mode `r`, and the three named call methods.
ZIP delivery: build each ZIP to a unique cache file on its existing worker. `openFile` waits only for that file to finish, with a stated timeout (30 s), then returns a read-only `ParcelFileDescriptor` (external) for the completed file; it never waits for a pipe writer. On timeout or failure, return an error. The recording ZIP is built on the `CapturedAudioFiles` worker. Door ZIPs use `cacheDir/devlog-pulls/log/` (proposed) and `cacheDir/devlog-pulls/recordings/` (proposed), owned by the log and `CapturedAudioFiles` workers respectively, separate from UI Share exports. Each worker builds a UUID `.partial` file and publishes a completed ZIP only on success. After `openFile` opens the completed file read-only, unlink its pathname; the returned descriptor keeps its bytes available to the reader. On timeout, signal cancellation and arrange cleanup on the owning worker, never delete a file while that worker writes it. Each worker removes abandoned door files at startup and before its next door build.
Cold start: provider methods wait, with a bound, for main `Application.onCreate` to queue and finish cold-start switch repair before submitting a setter or reading settled status. Initialize the readiness signal before provider publication; do no waiting in provider `onCreate`. A timeout returns Pending or an error without changing a switch.
Mac receipt: pull ZIPs through a separate binary `adb exec-out` path. Write to a temporary Mac file, check the command result and ZIP integrity including `device.txt`, then rename it; never pass ZIP bytes through the text adb helpers (`scripts/uat/wispr_eyes.py:412-414,2730-2735` decode as text).
The door writes nothing new: it reads the same files and flips the same switches as the Developer page. `scripts/uat/wispr_eyes.py` gains `pull_log` (proposed), `pull_recordings` (proposed) and `set_detailed_log` (proposed) calls (`tools-and-apps.md` RULE: drive-the-phone-through-wispr-eyes-before-any-raw-adb); `device-testing.md` records the commands. Limitation, stated to the founder: the door needs a live adb connection, and wireless debugging can turn off after a phone restart or off WiFi; only he can turn it back on (`device-testing.md` rows at :28-30). Rejected: uploading the log to an Envious Labs server, which would send dictated words to us and break the CLAUDE.md privacy boundary.

## 3b. Ownership justification

The file sink lives in `LocalLog` because `DebugLogger` must stay the one logcat door that #194's test pins; the alternative, a file branch inside `DebugLogger`, would put file I/O in the object #194's check (d) keeps free of it. The switch mirror lives in main because DataStore is main's; the alternative, a multi-process store, would migrate every reader. The archive lives on `CapturedAudioFiles` because it already owns the take's audio file (#358), with `:audio` contributing only the link at the moment it closes the file it owns.

## 4. Contract deltas

- `DebugLogger`: every line also goes to `LocalLog`; logcat unchanged. `startPipeline` means "this process's start of this take".
- `LocalLog` (proposed): writes only while `detailed-log` exists; `LocalLog.words` the only path by which user words reach any sink.
- `TakeLog` (proposed): the only way a take-scoped owner logs.
- `AsrService.doTranscribe`: gains a `takeId` parameter (same process).
- `CapturedAudioFiles.delete`: still deletes the cache file; also keeps or drops that take's pending link. `:audio`'s close gains the conditional link.
- `TranscriptEntity`: four nullable timing columns; `TranscriptDao`'s insertion-outcome update also writes `insertionMs` and `endToEndMs`.
- `SettingsPage`: gains `Developer`, hidden until unlocked.
- `DeveloperLogProvider` (proposed): a DUMP-gated read of the log and recordings ZIPs and DUMP-gated switch calls; no new state.
- `AppPreferences`: gains `developerUnlocked` (proposed), `detailedLog`, `keepRecordings` and the mirror writes.

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Processes that log | main, `:audio`, `:vad`, `:asr`, `:polish` (manifest :75-132); each its own file set |
| Readers of the switches | main (DataStore); every process's `LocalLog` writer and `:audio`'s close (mirror files); enumerated, none other |
| Callers that can change a switch or read a ZIP | the Developer page and the adb door (D10), both through the one switch worker, log worker and `CapturedAudioFiles` worker; enumerated, none other |
| Writers of the mirror files | main only: at cold start and on toggle |
| Paths that end a take | every winning arbiter commit, enumerated from the arbiter's producer at build time; known members :725, 835, 846, 859, 870, 882, 902, 1166, 1193 (audio-retiring) and :983, 1035, 1087, 1137 (no audio); plus process death recovered as `dictation.interrupted` (`telemetry/TakeJournal.kt:74`) |
| Lines with no take | `:asr` model load at service creation, process start, telemetry bootstrap; written `take=-` |
| Word points | the §2.5 list; each one `LocalLog.words` call; the sentinel test pins them by outcome |
| Holders of a log file descriptor | each writer (its current file), Share (all files of a process, between its lock and the zip's end) |
| Holders of a shared export URI | any app shared to, for an unknown time; exports have their own lifetime (D6) |
| Audio files alive at once | the cache file per take (unchanged lifecycle), pending links, kept recordings; nothing but Clear saved recordings and the retention limits deletes kept recordings |

### 5.1 File concurrency, enumerated (carried from revision 1, closed at plan level by grounded round 9)

Actors: five writers (each touching only its own log files), main's log worker (Share, export sweep, Delete shared log ZIPs; only `filesDir/logs/` and `cacheDir/share/`), main's `CapturedAudioFiles` worker (retirement, archive, Clear saved recordings, Share recent recordings, stale pending cleanup; only `recording-take-*` in `cacheDir`, `filesDir/recordings-pending/`, `filesDir/recordings/`, `cacheDir/share-recordings/`), `:audio`'s link at close, main's mirror writer and Off barrier (only `filesDir/flags/`, and the five log locks while settling Off), and external apps opening an export after Share returns. The two workers share no file; a log lock wait never delays audio retirement.

| Pair | Overlap? | Outcome |
|---|---|---|
| append / append, append / rotate | no: one writer thread per file | n/a |
| append / snapshot pin | no: the writer pins its own snapshot under its own lock, between batches | whole batches only |
| append or rotate / Share reading a pinned snapshot | yes | the pin is hard links plus a recorded length; appended bytes lie past it and rotation cannot remove linked bytes |
| main's own writer / main's switch worker (Off barrier) | no: in-process mutex before the file lock | no `OverlappingFileLockException` |
| Share / a writer's queued lines | no: Share fences all five writers; each process's status and drop count are reported, a missing acknowledgment is named, never inferred to mean dead | The writer drains to the fence before pinning; Off and overflow may discard entries, and its acknowledgment reports the result. The ZIP never claims to be complete |
| rotation between the tap and a writer's pin | yes | a line in the oldest file can be evicted; the ZIP states this and never claims completeness |
| orphan pins (main died mid-Share; a writer pinning just after a cleanup scan) | yes | bounded: up to five orphan fence directories, at most one writer's 10 MB set each, 50 MB extra in all; a startup (which first cancels the fence) or Share whose cleanup begins after an orphan was created removes it; Share reads only its own fence's pins; pins are never written after creation |
| Share fence / a writer starting, dying, or under the Off barrier | yes | a writer that starts before the fence acknowledges it; one that dies without acknowledging is named; under Off it acknowledges `discarded-by-Off`, which the ZIP reports |
| acknowledged writer / its later rotation while Share waits for others | yes | Share reads the writer's pinned hard links and recorded length, which rotation cannot change |
| queue overflow / fence | yes | the acknowledgment carries the dropped count, which the ZIP reports |
| Share / later Share, Delete shared log ZIPs, export sweep | no: one worker; UUID create-new paths | an earlier export is never overwritten; a Delete queued after a Share runs after its hand-off |
| external app / export deletion | yes, by design | only the 24-hour sweep or the warned Delete removes an export |
| `:audio` link / retirement of the same take | no: the link precedes `publishEnded`, and main retires only a path learned from that ending | every retirement finds its link |
| `:audio` link / stale pending cleanup | yes | cleanup removes only pending files over an hour old; a link is created complete |
| archive / Share recent recordings / Clear saved recordings | no: one worker | a Share finds only recordings that exist when it runs |
| `:audio` next-take sweep / archive | yes | the sweep deletes a cache name; the archive holds its own hard link |
| Detailed log Off barrier / writer batch or crash flush | no: main holds all five log locks while it deletes the flag, and every batch and flush checks the flag under its own lock | a batch in progress finishes before the delete; every later batch sees Off and discards |
| Detailed log or Keep recordings On / Off / cold-start repair | no: one switch worker runs every switch request in user-action order; only the latest request publishes a settled state | the final DataStore value, flag and page match the last request, for each switch |
| Detailed log Off / a `LocalLog` call | yes | the call may enqueue a lazy entry on a stale hint; no string is built and the writer discards it under the lock |
| Keep recordings toggle / `:audio` close | yes | the link follows the state at close; retirement drops the pending link if Keep recordings is off by then |
| reboot / anything | files and flag files persist; main repairs the mirror at cold start | nothing to reconcile |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `DebugLogger` feeds `LocalLog` | every call site | logcat | logcat plus file while on | no | sentinel test |
| `LocalLog.words` | §2.5 word points | none | file line with take id while on | yes | sentinel test |
| mirror files | every `LocalLog`, `:audio` close | none | read flag | yes | `LocalLogSwitchTest` (proposed) |
| `CapturedAudioFiles` retire | coordinator sites | delete | delete, plus keep or drop the pending link | no (callers) | `CapturedAudioFilesTest` (proposed rows) |
| History columns | `TranscriptRepository`, History card, migration test | absent | nullable ints | yes | `MIGRATION_9_10` (proposed) test |
| `SettingsPage.Developer` | drawer, `AppNavigationShapeTest`, `AppRoutesTest` | none | hidden until unlocked | yes | those tests |
| `DeveloperLogProvider` | `wispr_eyes.py` pull and switch calls | none | DUMP-gated reads and calls | yes | `DeveloperLogProviderTest` (proposed) and `scripts/uat/test_wispr_eyes.py` |

## 7. Failure-mode × caller table

| # | Failure | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|---|
| L1 | Disk full or write error | writer | `LocalLog` | nothing; dictation unaffected; one logcat warning per process start | lines lost | next batch |
| L2 | Queue full | burst | callers | a `dropped=N` line | lines lost | none |
| L3 | Process killed with queued lines | OS | none | nothing | queued lines lost | none |
| L4 | Mirror file change fails | main | `AppPreferences` | the switch shows an error, never a settled state; the flag file's actual existence is what every process follows | DataStore value, flag unchanged | retry from the page; cold start repairs |
| L5 | Share with no target, or a target rejects the zip | Android | Developer page | the share sheet's own message; the page shows the zip size | export kept 24 h | user |
| D1 | Phone not reachable over adb (wireless debugging off, off WiFi, Tailscale down) | Android, network | Claude on the Mac | nothing on the phone; Claude reports that the log could not be pulled and what the founder must switch on | nothing | when reachable |
| D2 | A caller without DUMP opens or calls the provider | another app | Android, `call` check | `SecurityException`; nothing is read or changed | nothing | none |
| A1 | Pending-to-kept rename fails | worker | `CapturedAudioFiles` | nothing; pending deleted | nothing | none |
| A2 | Hard link fails in `:audio` | `:audio` | none | nothing; that take not kept; one log line | nothing | none |

Copy: Developer page labels only, English, in the design language; customer surfaces unchanged except the Privacy sentence, shown while Detailed log is on or while a local log file or shared export remains.

## 8. Caller-visible signals

- `take=-` means no take was in scope.
- A null timing column means not measured.
- A `dropped=N` line means the queue overflowed.
- The Developer drawer entry's presence means unlocked (or a debug build).
- A flag file's existence means that switch is on.

## 9. Fallback source of truth

| Failure branch | Candidate | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| L1 to L3 | logcat | `DebugLogger` | unchanged path | line in logcat | none | developer with adb |
| L4 | DataStore | `AppPreferences` | the switch's owner | repaired at cold start | old mirror state | helpers |
| A1, A2 | the cache file's lifecycle, unchanged | `CapturedAudioFiles`, the `:audio` sweep | today's owners | dictation unaffected | warning line | coordinator |

## 10. File-by-file changes

Chunk 1 (log and switch): `debug/DebugLogger.kt`; `app/src/main/java/com/envi/wispr/debug/LocalLog.kt` (proposed), `TakeLog.kt` (proposed); `settings/AppPreferences.kt`; `ui/AppNavigation.kt`, `ui/AppShell.kt`, a `DeveloperPage.kt` (proposed); `app/src/main/AndroidManifest.xml` and `res/xml` (the FileProvider); `models/ModelBootstrapApplication.kt` (mirror repair, crash hook after bootstrap); the word points in `asr/AsrService.kt`, `ui/TakePolishController.kt`, `cleanup/PolishPipeline.kt`, `cleanup/DeterministicCleanup.kt`, `polish/PolishService.kt`, `providers/ProviderPolishClient.kt`, `ui/DictationSessionCoordinator.kt`, `ui/SessionLog.kt`, `paste/AccessibilityInsertionRunner.kt`; the take-scoped owners in `:audio` and `:vad`; `privacy/PrivacyDisclosure.kt`; `DeveloperLogProvider.kt` (proposed) and its manifest entry; `scripts/uat/wispr_eyes.py` and `scripts/uat/test_wispr_eyes.py` (pull and switch calls); `DiagnosticsShapeTest.kt`; rule `kotlin-patterns.md`; knowledge `telemetry.md` and `device-testing.md` (how to pull a log).
Chunk 2 (History timings): `history/TranscriptEntity.kt`, `history/EnviousWisprDatabase.kt`, `history/TranscriptDao.kt`, `app/schemas/.../10.json`, the coordinator's History writes, `ui/HistoryScreen.kt` (timings line while unlocked), migration test.
Chunk 3 (recordings): `audio/AudioCaptureService.kt` (the link), `ui/CapturedAudioFiles.kt`, the Developer page rows, a WAV writer (proposed).

## 11. Testing

1. Classes: the sentinel test (product outcome: the founder's shared log lacks a step's words, or a customer's phone writes words with the switch off), `LocalLogSwitchTest` (product outcome: toggling does nothing, or off still writes), `DiagnosticsShapeTest` additions (drift guard), `CapturedAudioFilesTest` rows (product outcome: a kept recording is missing), `MIGRATION_9_10` (product outcome: History lost after update).
   Outcome assertions, not counts: a sentinel take is driven through `DebugLogger` and every word stage; with the switch on the file holds the sentinel's text and take id (a call left in place with empty text fails it); with it off no file holds it; logcat and telemetry never do. A completed History row holds each of `liveAfterMs`, `asrMs`, `polishLatencyMs`, `insertionMs` and `endToEndMs` by name.
   Crash oracle: a forced crash delegates to Sentry's handler and Android's crash handling even when the writer is stalled, and the hook waits no more than 200 ms; lines before the crash are asserted present only when the writer acknowledged its flush.
   Recordings and exports oracle: archive one recording, Clear saved recordings, assert storage holds none. Share twice with the clock held; assert two distinct exports and the first still opens. Queue Share then Delete shared log ZIPs; assert hand-off before deletion. Queue Share recent recordings then Clear saved recordings and the reverse. Every ending path (completed, cancelled during capture, speech not ready, speech unresponsive) finds its pending link. Delay the `CapturedAudioFiles` worker, finish take A, start take B so the sweep deletes A's cache name first; A is kept and B dictates. Kill main during a capture and restart; the pending file is untouched while under an hour old and removed later.
2. Reverts: remove the `LocalLog` hand-off in `DebugLogger` (sentinel red); ignore the flag file (switch test red); add a `LocalLog.words` call outside the pinned set or a `Log.i` inside it (shape test red); remove the link or always drop the pending link (archive row red); drop `MIGRATION_9_10` (migration test red). Each revert is performed and its red receipt recorded.
   Release default: a non-debuggable release build installed clean on the emulator, before any unlock: both DataStore switches off, both flag files absent, no log file and no pending link after a real take, no Developer entry, and the merged release manifest holds the log FileProvider exposing only `cacheDir/share/`. Separate from the debug-default and toggle tests.
   Off: pause a writer after its flag check but before its batch write, request Off, and assert the page cannot confirm Off until that batch completes; after confirmation no further bytes reach any file and no queued word lambda is evaluated (a counting lambda proves it); the same with a crash flush pending. With a writer blocked indefinitely, Off stays Pending then shows an error, never Off. A failed flag delete shows an error.
   Barrier placement and repair: hold a helper lock through an Off request and verify the UI remains responsive; then restart main after a failed Off barrier with the flag still On and verify repair cannot show Off or write after confirmation while that helper is mid-batch.
   Ordering: a delayed On followed by Off, and a delayed Off followed by On; the final DataStore value, flag, page state and actual file writes match the last request. Delay Keep recordings On before flag creation, then request Off; repeat with delayed Off followed by On. After each pair, assert DataStore, flag, page state, a completed take's archive result, and pending-link cleanup all follow the last request. Share immediately after a take's insertion outcome while one helper writer is held before draining; require either that final line in the ZIP or an explicit did-not-confirm result for that process. Share with one helper not running; the ZIP names it as not confirmed. Race a helper's first enqueue against the fence; no unmarked incomplete ZIP. Race Off against a pending fence, and rotate a process's full file set after its acknowledgment while another process is delayed; the ZIP reports each status and never claims completeness. Overflow a writer's queue before a fence; the ZIP reports the count. Kill main mid-Share; at the next start the orphan pins are removed and the log folder returns within the live 50 MB. Pause two writers on different fence ids, let a third Share clean up, then release both. Assert that two orphan directories can exist, stay within the per-process storage bound, do not enter the third ZIP, and are removed by the next Share. Delete shared log ZIPs leaves shared recording WAVs available, and its label says so. One take immediately after On: report which process lines were captured, and never call that take a full-log receipt if stages are missing.
   adb door, access: a second app granted DUMP through `adb shell pm grant` is still refused by the UID check on every entry, and an unknown URI, mode `w` or unknown method is refused.
   adb door, cold start: launch main cold through the provider with a mismatched DataStore value and flag, then a setter; the setter is the last action. Test a ZIP larger than 64 KiB: it arrives whole through `pull_log`. Test a timeout during writing, process death, repeated pulls, and a held read descriptor during another pull; no door pathname remains after completion, and a forced timeout leaves no partial file on the Mac.
   adb door: from a test app WITHOUT DUMP, opening `log.zip` and calling `setDetailedLog` both throw `SecurityException` and change nothing (the `call` check is removed in the revert, and the test goes red); from the adb shell, `set_detailed_log` returns the settled state and `pull_log` returns the same per-process statuses a Developer-page Share would; the door works on a non-debuggable release build.
   Observer: absent directories on a fresh install, changes immediately before and after watch registration, a lost event (recovered at the next take admission), and the observer idle oracle: after startup settles, record callback and log-writer wake counts separately in each of the five processes. With Detailed log Off, no take and no fence, assert those counts stay unchanged over a quiet minute. Then create a flag and a fence and assert the relevant counts advance. Do not assert that every process thread remains asleep.
   Hot path: chunk 1 measures the Off-state cost of a call on the capture and insertion threads on the S26 (the hint read plus, when stale, one closure allocation) and records it against the take's log-call count.
   Hidden page: a saved `Developer` route while locked resolves to the ordinary tab; after unlock the page is reachable. The History timings line is absent while locked and present after unlock.
3. Not tested: share targets' behaviour, the seven-tap timing on real fingers beyond one hardware pass.

### 11.1 Hardware UAT spec
- Subsystem: heart path (logging on capture, speech, polish, insertion) plus limb (Developer page, archive).
- Recipe: `device-testing.md` silent-audio injection on the S26 when the founder is away; otherwise his own takes.
  1. On the Play internal build, from the Mac over adb, with no tap on the phone: `set_detailed_log` on.
  2. Three takes into Gmail, one into WhatsApp, one cancelled.
  3. `pull_log` from the Mac (no tap). Oracle: every take's lines share one take id across all five process files; each completed take has raw, cleanup, polish and final words lines and every reached timing line, in `b=` order matching the pipeline; the cancelled take has its id, its last reached stage and its reason.
  4. `set_detailed_log` off; one take; `pull_log`; the take is absent. One Developer-page Share, to prove the backup path.
  5. Keep recordings on: link probe (identical bytes after the cache name is deleted), five kept recordings with metadata, Share recent recordings produces playable WAVs.
- Restore: switches as the founder wants them.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| emulator debug build writes `filesDir/logs/*.log` for all five processes with the switch defaulted on | product | G2 | disable the hand-off |

## 12. Blast radius & rollback

Touched: `:app` logging in all processes, History schema, the drawer, the Privacy page sentence (while on or while retained files exist). Not touched: the Play pipeline, PostHog and Sentry payloads, `PayloadSanitizer`, `Provider.disclosure`, the GenieX silencer, insertion decisions, `:llama-android`, `:accelerator-benchmark`. Rollback: revert the PR; files already written stay in app storage until uninstall (no reader); Room 10 keeps nullable columns (a rollback build never downgrades).

## 13. Ship criteria

- [ ] On the S26 from Play: Developer unlocked, Detailed log on, a shared log shows each take's words at every step and its timings under one take id.
- [ ] With the switch off, a take writes nothing.
- [ ] Kept recordings play back after sharing.

## 14. Open questions

1. Q1 (answered 2026-09-27): one app with a hidden switch; the promoted production file is the dogfooded file.
2. Q2 (founder, Gate 2): no Clear log. While on, the log keeps its last 50 MB; while off, existing files remain unchanged until later logging overwrites them or the app is uninstalled. Turning the switch off stops new logging at confirmation.

## 14b. Review log

- Revision 1 (two apps): consult, coverage round 1 and grounded rounds 1 to 9 (`.codex/2026-09-27-issue-378-*.txt.last`), ending PROCEED-AS-PLANNED. Carried into revision 2 unchanged: D4 (per-process files, locks, `TakeLog`, crash hook), D6 (snapshot, exports), D7 (no Clear), D8, D9 (hard-link archive and its ordering), §5.1. Dropped with the founder's one-app decision: the `dev` build type, source-set compile-out, AAB inspection, the second Play listing, the two-bundle pipeline, telemetry edition, the harness package override, side-by-side notification labels.

- Revision 2, grounded round 1 (overall 10, `.codex/2026-09-27-issue-378-grounded-r10.txt.last`, PROCEED-WITH-REVISIONS): direction confirmed; 3 blocking and 2 non-blocking, all adopted. Modified: for Off, instead of per-process acknowledgments, every call and every writer batch reads the flag file at the moment of use, so Off holds at the delete with no live-process protocol (Codex's own sentence "polling is a recovery path, not the privacy guarantee" is met by removing the polling).

- Revision 2, round 2 (overall 11, `.codex/2026-09-27-issue-378-grounded-r11.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (Off raced a batch already past its check and a call already past its check), 3 non-blocking; all adopted. Off now settles through all five log locks with the flag checked under the lock by every batch and crash flush; the per-call `stat` is replaced by Codex's cheaper design (a hint plus lazy entries evaluated only by the writer under the lock); stale "ages out" and "only while on" copy fixed; the telemetry claim replaced by no event plus a test.

- Revision 2, round 3 (overall 12, `.codex/2026-09-27-issue-378-grounded-r12.txt.last`, PROCEED-WITH-REVISIONS): no lock-order deadlock, helper-death or writer-startup path found; 1 blocking (the barrier had no thread, and cold-start repair could delete the flag without the barrier); adopted verbatim with its test.

- Revision 2, round 4 (overall 13, `.codex/2026-09-27-issue-378-grounded-r13.txt.last`, PROCEED-WITH-REVISIONS): the barrier has no deadlock; 1 blocking (On and Off not serialized) and 1 non-blocking (On readiness), both adopted verbatim.

- Revision 2, round 5 (overall 14, `.codex/2026-09-27-issue-378-grounded-r14.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (Keep recordings toggles not serialized), adopted verbatim with its test.

- Revision 2, round 6 (overall 15, `.codex/2026-09-27-issue-378-grounded-r15.txt.last`, PROCEED-WITH-REVISIONS): full request-pair sweep; 1 blocking (Share could snapshot before a writer drained its queue) and 1 non-blocking (Delete scope), both adopted verbatim; the fence mechanism is specified without new IPC (an `.alive` lock the kernel releases on death, a fence file, per-process acknowledgment files).

- Revision 2, round 7 (overall 16, `.codex/2026-09-27-issue-378-grounded-r16.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking, a second member of the Share-completeness class (the `.alive` liveness probe misclassified main and a starting writer). Modified: rather than patching the probe, the probe is deleted. Share waits for all five acknowledgments and presents the ZIP as complete only when all five arrived; a missing one is named, never read as death. This removes every liveness inference from the class.

- Revision 2, round 8 (overall 17, `.codex/2026-09-27-issue-378-grounded-r17.txt.last`, PROCEED-WITH-REVISIONS): the third member of the Share-completeness class (Off discarding a fenced line, rotation after acknowledgment). Adopted Codex's contract verbatim: writers pin per-fence snapshots under their lock and acknowledge a status; Share reads only pinned snapshots. Pre-committed consequence, written before round 18's verdict: if round 18 finds another member of this class, the "complete" label is DELETED; the ZIP then reports only each process's acknowledgment status and counts, and never calls itself complete.

- Revision 2, round 9 (overall 18, `.codex/2026-09-27-issue-378-grounded-r18.txt.last`, PROCEED-WITH-REVISIONS): a fourth member of the Share-completeness class (rotation evicting a pre-tap line before the pin). The pre-committed consequence is executed: the "complete" label is DELETED; the ZIP reports only per-process status and counts. Second blocking (orphan pins) adopted with a simpler cleanup: pins are never written after creation, so main removes every non-current fence's pins at startup and at each Share without taking the five locks.

- Revision 2, round 10 (overall 19, `.codex/2026-09-27-issue-378-grounded-r19.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (a writer can pin just after a cleanup scan; startup could skip the stale current fence's pins), 1 non-blocking (§5.1 wording, adopted verbatim). Modified: instead of a five-lock cleanup barrier, the orphan is bounded and disclosed (at most one extra set, removed at the next startup or Share), startup first cancels the fence and removes all snapshot directories, and the test pins the bound. Orphans cost storage only; pins are never written after creation.

- Revision 2, round 11 (overall 20, `.codex/2026-09-27-issue-378-grounded-r20.txt.last`, PROCEED-WITH-REVISIONS): the storage bound holds at 50 MB extra but the "one orphan set" count was wrong (up to five); bound and test replaced verbatim. No ZIP pollution and no other blocking finding.
- Revision 2, round 12 (overall 21, `.codex/2026-09-27-issue-378-grounded-r21.txt.last`): **VERDICT: PROCEED-AS-PLANNED**. Open for hardware: the S26 hard-link probe, the per-call cost measurement, real latency.

- Founder 2026-09-27 at Gate 2: "I am personally never gonna look at the logs. That is something you do. I want to confirm that you'll be able to access these logs without my intervention." Added D10 (the DUMP-gated adb door), G8, and the §7, §11 and §11.1 rows; the Developer page stays as the backup path.

- Revision 2, round 13 (overall 22, `.codex/2026-09-27-issue-378-grounded-r22.txt.last`, PROCEED-WITH-REVISIONS): 3 blocking (DUMP can be granted to an app by adb, so the door also checks the shell UID; a pipe filled before `openFile` returned, so ZIPs are built to a file first; a provider can start main before cold-start repair, so calls wait for it) and 1 non-blocking (binary pull on the Mac); all adopted verbatim with their tests.

- Revision 2, round 14 (overall 23, `.codex/2026-09-27-issue-378-grounded-r23.txt.last`, PROCEED-WITH-REVISIONS): four round 22 fixes landed; 1 blocking (door ZIP lifetime) and wording fixes, all adopted verbatim.
- Revision 2, round 15 (overall 24, `.codex/2026-09-27-issue-378-grounded-r24.txt.last`): **VERDICT: PROCEED-AS-PLANNED**.
- Gate 2, founder 2026-09-27: "As long as that is confirmed, I'm okay with the structure" (the confirmation: Claude reads the logs and sets the switches over adb with no tap from him, D10).

- Build-start self-review against `architecture-rules.md` RULE: no-idle-cost: the approved text had a writer waking every 250 ms and a hint refreshed once a second, which are idle timers. Replaced by one `FileObserver` per process on the flag and fence files (kernel inotify, no wakeups at idle); taken back to Codex before any code.

- Revision 2, round 16 (overall 25, `.codex/2026-09-27-issue-378-grounded-r25.txt.last`, PROCEED-WITH-REVISIONS): event-driven direction sound, no other idle waker; 1 blocking (directories must exist before the watch, a fence written before registration, lost events), adopted verbatim with its tests.

- Revision 2, round 17 (overall 26, `.codex/2026-09-27-issue-378-grounded-r26.txt.last`, PROCEED-WITH-REVISIONS): observer fix landed; 1 blocking (the idle oracle was untestable), replaced verbatim with wake-count assertions.
- Revision 2, round 18 (overall 27, `.codex/2026-09-27-issue-378-grounded-r27.txt.last`): **VERDICT: PROCEED-AS-PLANNED** after the build-start idle-cost fix.

## 15. Related

#194, #114, #358, #176, #288; PAR-089, PAR-090, PAR-091; catalog `debug-local-log`, `debug-audio-archive`, `privacy-safe-telemetry`.
