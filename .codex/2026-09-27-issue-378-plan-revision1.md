# Issue #378 — A Dev app and a Consumer app from one source; the Dev app keeps a full local log — 2026-09-27

GitHub issue: `#378`. Tier: LARGE. Status: DRAFT.

Consolidation: none. This change adds a surface (a file sink, a build type, an archive) beside existing owners; it merges no duplicated logic. The one owner it leans on stays single: `DebugLogger` remains the only logcat door, and the new sink is fed only through it and through one content entry point.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (app/**: `tests`, `codex-review`, `hardware-uat`), `CI/workflow` (.github/workflows/play-internal.yml: `workflow-run`), `Docs/dev-tooling` (scripts/release/**, scripts/uat/wispr_eyes.py, .claude/**: `cited-symbols`).

**PAR rows closed:** `PAR-090` (local debug logging with bounded rotation and a share action: closed by the Dev app's log file, its rotation and the Share log button, evidenced by the S26 run in §11.1); `PAR-091` (optional dictation audio archive with retention controls: closed by Keep recordings, evidenced by §11.1 step 5). `PAR-089` (diagnostics screen with a copyable readiness report, no secrets or dictated content) is adjacent, NOT closed: the Diagnostics page here is a Dev-only log surface and carries dictated content by design.

**Hardware UAT:** Y. Saurabh installs "EnviousWispr Dev" from Play next to "EnviousWispr", dictates three takes into Gmail and WhatsApp from the Dev app's bubble, opens Diagnostics, taps Share log, sends it to himself, and the log shows, for each take, one take id on every line, the words at each step (raw speech, after cleanup, after polish, as inserted), and the step timings; the Consumer app still dictates and has no Diagnostics page.

## Preface — User Rubric

The Dev app is the founder's tool; the Consumer app's behaviour does not change apart from the History timing fields (§3 D8), which no screen of the Consumer app shows. The rubric is answered for the founder as Priya Ramachandran (the technical daily user who wants sub-second, accurate dictation) because he dogfoods as her.

1. **Who and when.** Priya-as-founder dictated a Slack reply, a word came out wrong or slow, and thirty seconds from now she wants to know which step did it without plugging in a cable.
2. **Why.** "When it messes up I want to see what it heard, what cleanup did and what polish did, and how long each took, like on my Mac."
3. **How invoked.** Reactively, right after a bad take, or weekly to hand a log to Claude. Diagnostics, then Share log.
4. **Apps.** Dictation into Gmail, WhatsApp, Slack, Chrome; the log leaves through Gmail, Drive or Quick Share to the Mac.
5. **Natural input.** "that last one said polish twice", "why did Gmail get the clipboard", "the first take after lunch was slow", "send me today's log", "keep the audio of that one".
6. **Success.** The shared log reads like the Mac `app.log`: one take id, every step, words and milliseconds, and it arrives as one attachment.
7. **Wrong-not-broken.** The log exists but a process's lines are missing or its timings are nonsense (today's uptime-relative marks), so it cannot be trusted and is not used.
8. **Power-user hack.** `adb logcat` over wireless debugging, which is what exists today and is exactly what he does not want.
9. **Control ladder.** The Dev app always logs (no switch: the whole point is having the log after the fact); Keep recordings is a switch (audio is large); Clear recordings and Delete shared exports are buttons. The log is bounded by rotation (§14 Q3). The Consumer app has no ladder: nothing is written.

### Cross-persona check
Every customer persona (Marcus, Diana, Elena, Aaron, Meera, Frank) only ever sees the Consumer app, which writes no local log and keeps no audio. Dr. Elena Vasquez is the one who would object to a content-bearing file; she never receives it, and the Dev app is internal testing only. No tension to resolve in §3.

---

## 0. TL;DR

Today the Play build is a release build with no local log: every line goes to logcat and is lost unless a cable is attached, History keeps only the polish time, and no audio is kept. The founder wants two apps from one source, both from Play, both on his phone: **EnviousWispr Dev** (`com.envi.wispr.dev`, a new release-grade `dev` build type) that writes a Mac-style rotating log per process with take ids, per-step words and timings, keeps optional recordings, and shares the log through the share sheet; and **EnviousWispr** (`com.envi.wispr`, the existing Play app) that compiles all of that out. History gains per-step timings in both apps. The Play pipeline builds and publishes both from one push. LARGE: app startup, every process, the insertion path's logging, a Room migration and the release pipeline. Evidence: unit and shape tests, an AAB inspection that fails the Consumer build if the log code is present, emulator takes, and the S26 run in §11.1.

## 1. Problem

- The Play build is `:app:bundleRelease` (`scripts/release/build.sh:15`). `DebugLogger` writes logcat only; "There is no file sink" (`app/src/main/java/com/envi/wispr/debug/DebugLogger.kt:16`). Getting any line off the phone needs `adb`.
- History keeps `polishLatencyMs` but no speech, insertion, start-up or end-to-end time (`app/src/main/java/com/envi/wispr/history/TranscriptEntity.kt:12-40`). Those numbers exist in memory (`TakeFacts`) and in PostHog rows, never locally.
- `DebugLogger.startPipeline()` is called only in `:audio` (`app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:505`); `mark` in main (`ui/DictationSessionCoordinator.kt:851,862`) and `:asr` (`asr/AsrService.kt:170,201,218`) measures from device uptime.
- Captured audio is deleted at every take ending (`ui/CapturedAudioFiles.kt:20-28`).
- The Mac developer build has all of this: `app.log` 10 MB x 5 (`~/Library/Logs/EnviousWispr/`, 50 MB on this Mac 2026-09-27), `CORRECTION_DEBUG` (external) IN/OUT per step, `ExecutionMetrics` (external) per History row, `DictationAudioArchive` (external) newest 500.

## 2. Goals & non-goals

### 2.1 Goals
1. G1. Two installable apps from one commit: `com.envi.wispr` "EnviousWispr" (Consumer) and `com.envi.wispr.dev` "EnviousWispr Dev", both non-debuggable, both published by the existing workflow to their own internal testing tracks, both installable side by side.
2. G2. The Dev app (and local debug builds) write a rotating on-device log per process under app-private storage, every line carrying a wall time, a boot-relative time, the process, the level, the tag and the take id where one exists.
3. G3. The Dev log carries the words at each step: raw speech, after vocabulary restore, after each deterministic cleanup step, the polish input and output (local and cloud), the final text, and the insertion outcome with its target app.
4. G4. The Dev log carries per-step timings: accepted to live, recording length, speech model load, speech decode, cleanup, polish model load, polish, insertion, end to end.
5. G5. A Diagnostics page in the Dev app: log size, Share log (one zip through the share sheet), Delete shared exports, Keep recordings switch, recordings count and size, Share recent recordings, Clear recordings.
6. G6. History rows keep per-step timings in both apps (Room 9 to 10).
7. G7. The Consumer bundle contains no file-log code and no audio-archive code, proven on the built AAB in the release pipeline.
8. G8. Logcat stays content-free in both apps; telemetry is unchanged except that the Dev app reports its own environment.

### 2.2 Non-goals
- No change to what PostHog or Sentry receive (property names, shapes, sanitizer).
- No Diagnostics page, log, or archive in the Consumer app.
- No readiness report (PAR-089), no benchmark suite, no log level picker (the Dev app always logs everything; a level picker exists on the Mac to tame `os_log` (external), which Android does not share).
- No migration of the founder's History, dictionary, keys or models from `com.envi.wispr` into the Dev app (stage 1 dev state, CLAUDE.md).
- No on-phone log viewer; the log is read after sharing.

## 2.5 Grounding brief

### 1. Producer to owner to consumer

- **Log lines.** Producer: 200+ call sites of `DebugLogger.debug/log/warn/error/mark` across five processes (counts per process: `:audio` 67, `:vad` 9, `:asr` 25, `:polish` about 26, main about 70, plus `telemetry/Telemetry.kt` in every process; attributed per file by package and hosting service in `app/src/main/AndroidManifest.xml:75-132`). Funnels that end in `DebugLogger`: `ui/SessionLog.kt` (`DebugSessionLog`, tag `DictationSession`), `paste/InsertionOutcomeLine.kt`, the `logInfo`/`logWarn` lambdas in `providers/ProviderPolishClient.kt:23-24`, `providers/ProviderModelDiscoveryClient.kt:29-30`, `providers/HttpProviderTransport.kt:73`. Owner: `debug/DebugLogger.kt`. Consumer: logcat only (`DebugLogger.kt:76-92`).
- **Take id.** Created at `ui/DictationSessionCoordinator.kt:340`; crosses AIDL in `IAudioTakeService.startCaptureForTake`, `ISilenceVadService.startForTake`, `IAsrService.transcribeFileForTake`, `IPolishService.polishRequestForTake`. Not in scope at `AsrService.doTranscribe`/`initRecognizer` (`asr/AsrService.kt:183-293`) nor at `PolishService.run`/`polishWithS1`/`deliver` (`polish/PolishService.kt:376-597`), which log `requestId`; the registry entry holds it (`polish/PolishService.kt:194`, `polish/PolishRequestRegistry.kt:18`).
- **Words.** Raw speech `asr/AsrService.kt:215` (`:asr`), arrives in main at `ui/DictationSessionCoordinator.kt:854`; vocabulary restore `ui/TakePolishController.kt:214`; cleanup steps `cleanup/PolishPipeline.kt:37-38` and `cleanup/DeterministicCleanup.kt:109-189` (`:polish`); local polish prompt and output `polish/PolishService.kt:575-597`; cloud prompt `polish/PolishService.kt:411`, answer `providers/ProviderPolishClient.kt:140`; pipeline result `polish/PolishService.kt:439`; answer restore `ui/TakePolishController.kt:351`; fallback path `ui/TakePolishController.kt:445-453` (main); final text `ui/DictationSessionCoordinator.kt:955-963`, handed to insertion at `ui/SessionFinalizer.kt:381`; insertion outcome `paste/AccessibilityInsertionRunner.kt:269-290,605-610`.
- **Timings.** Accepted `ui/DictationSessionCoordinator.kt:339`; live-after in `:audio` `audio/AudioCaptureService.kt:262-266`, recorded at coordinator :744; recording length coordinator :822; speech decode `asr/AsrService.kt:203-214`, speech init :257-287, round trip coordinator :852-860; polish latency `polish/PolishService.kt:239,439`, S1 load :540-547; insertion `paste/AccessibilityInsertionRunner.kt:132,285,609`. Cleanup and VAD are not timed. End to end has no single measure. In-memory: `telemetry/TakeFacts.kt:37-46`.
- **Audio.** `cacheDir/recording-take-<takeId>.pcm` (`audio/CaptureFiles.kt:16`), 16 kHz mono 16-bit headerless PCM (`audio/PcmAudio.kt:5`, `audio/AudioCaptureService.kt:48-50`); deleted only through `CapturedAudioFiles.delete` on its one worker (`ui/CapturedAudioFiles.kt:20-28`), called from the coordinator at :725, 835, 846, 859, 870, 882, 902, 1166, 1193; `:audio` sweeps leftovers at the next start (`audio/AudioCaptureService.kt:690-698`). No WAV writer exists.
- **Release.** `.github/workflows/play-internal.yml` on push to `internal-testing` runs `scripts/release/build.sh` (`:app:testReleaseUnitTest :app:bundleRelease`) then `scripts/release/publish.py`, which pins `PACKAGE = 'com.envi.wispr'` (:16), `TRACK = 'internal'` (:17), refuses a debuggable bundle (:46), and sends `status: 'completed'` (:125).

### 2. Existing authority

- Logging: `DebugLogger` is the one owner (#194). No file sink, no FileProvider, no `ACTION_SEND` (external) anywhere in main or debug (negative sweep: `/usr/bin/grep -rn "FileProvider\|ACTION_SEND\|createChooser\|<provider" app/src` returned nothing, 2026-09-27). New authority proposed: `LocalLog` (proposed), the file sink, fed only by `DebugLogger` and by one content entry point.
- Variants: `buildTypes.getByName("debug")` with an opt-in `.onboardingpreview` suffix (`app/build.gradle.kts:41-46`), unused outside that file. No product flavors. `manifestPlaceholders["applicationLabel"]` already drives the label (`app/build.gradle.kts:15`, manifest :27).
- Telemetry edition: `TelemetryConfig.environment` from `FLAG_DEBUGGABLE` (`telemetry/TelemetryConfig.kt:39`), used by `SentryBootstrap.kt:48,78` and `PostHogBootstrap.kt:90`.
- History migrations: `history/EnviousWisprDatabase.kt` version 9, `MIGRATION_8_9` pattern (:108-114), migration tests in `app/src/androidTest/java/com/envi/wispr/history/EnviousWisprDatabaseMigrationTest.kt`.
- Settings pages: `SettingsPage` enum drives the drawer (`ui/AppNavigation.kt:69-106`), exhaustive `when` in `ui/AppShell.kt:202-235`.

### 3. Prior attempts and live direction

- #194 (2026-09-22) deleted the shared-storage file sink and made every diagnostic content-free. Binding: `kotlin-patterns.md` RULE: no-content-in-diagnostics and `DiagnosticsShapeTest`. This plan narrows the rule for one surface, the Dev app's app-private file, and keeps logcat and telemetry exactly as #194 left them (§3 D5).
- CLAUDE.md privacy: the boundary is the network. A file that never leaves the phone except by the user's own share passes the test.
- Catalog: `debug-local-log` (macOS: DEBUG-only rotating `app.log` with transcript text; Windows: a local log in every build), `debug-audio-archive` (macOS newest 500, DEBUG-only; Windows 20), decision 2026-09-25 "the Diagnostics page is never translated". This plan follows the macOS shape: developer build only, compiled out of the customer build.
- Founder 2026-09-27: two apps, both from Play, both on the phone; the Dev app is the daily driver; the Consumer app relies on PostHog and Sentry.

### 4. Boundaries

- Five processes, each with its own `DebugLogger` object and its own file; ordering across files uses `SystemClock.elapsedRealtime()`, which is one system-wide boot clock (§3 D4).
- DataStore is single-process (`settings/AppPreferences.kt:22`); helpers never read it. The only runtime setting (Keep recordings) is read in main, where the archive runs (§3 D9).
- Process death: a line handed to the kernel by `write` survives the process; only lines still queued in the writer are lost. The queue is drained on the writer thread immediately, and an uncaught-exception hook drains it synchronously before the default handler (§3 D4).
- Two apps installed: two accessibility services, two bubbles, two ASSIST handlers, two tiles (§3 D3, §7).
- The Dev app is release-type: `app/src/debug` (debug receivers) is not in it, so the `wispr-eyes` fast screen read falls back to `uiautomator dump` there.

### 5. High-risk premises

| Premise | Evidence |
|---|---|
| A new build type keeps every existing task name | Codex consult 2026-09-27 §A (`.codex/2026-09-27-issue-378-consult.txt.last`), developer.android.com/build/build-variants; verified in chunk 1 by `./gradlew :app:tasks --all` listing `bundleDev` (proposed), `testDevUnitTest` (proposed) and the unchanged `testDebugUnitTest` |
| `:app` depends on no project module, so no `matchingFallbacks` (external) is needed | `app/build.gradle.kts:113-156` has no `project(` dependency (read 2026-09-27); re-checked by the chunk 1 build |
| A new app's first bundle must be uploaded through the Console; the API works only after that | developers.google.com/android-publisher/edits (grounded round 1); D12 does the first upload by hand, §7 row P2 |
| The same upload key can sign the second app | Research 2026-09-27 (Play App Signing docs): no restriction found. NOT VERIFIED; fallback in §7 row P3 |
| Console state, read live 2026-09-27 | One app, "EnviousWispr" `com.envi.wispr`, Draft; internal testing Active, latest "Internal build 225 (2e97396)" released Sep 25, not reviewed; tester list "EnviousWispr Internal Testers" with 2 users; production, open and closed testing inactive; all app-content and store-listing setup tasks still open; users: the founder and the `android-play-internal` service account; automatic protection (prevent unofficial installs) on. The Console states testers see the temporary name "com.envi.wispr (unreviewed)" until app setup is complete AND the app has been reviewed, so the Dev app will show "com.envi.wispr.dev (unreviewed)" for as long as it stays internal-only; filling the store listing alone does not change what testers see |
| Package names are permanent | support.google.com/googleplay/android-developer/answer/9859152 |
| Gmail accepts 25 MB | support.google.com/mail/answer/6584; the zip is bounded under it (§3 D6) |
| `Os.link` from `cacheDir` to `filesDir` works (one volume) | Both are under the app's data directory on `/data`; verified on the S26 in chunk 4 before relying on it; a failed link means that take is not kept (§7 row A2) |

## 3. Design

**D1. Two apps through a new build type, not a product flavor.** `buildTypes.create("dev") { initWith(getByName("release")); applicationIdSuffix = ".dev"; manifestPlaceholders["applicationLabel"] = "EnviousWispr Dev"; isDebuggable = false }` (proposed). `release` stays the Consumer app. Rejected: an `edition` (proposed) flavor dimension, which Google's docs present for separate listings, because it renames every task (`testDebugUnitTest` becomes `testConsumerDebugUnitTest` (proposed)) across about 90 live references in scripts, hooks, CI and rules, for no behaviour a build type cannot give. Cost accepted: "dev" names an edition, not a build configuration, and a Dev debug build does not exist (local debug builds carry the log instead, D2).

**D2. Compile-out by source set.** The sink lives in `app/src/locallog` (proposed): its `java`, its `AndroidManifest.xml` (the FileProvider) and its `res` (the provider paths) are all wired into the `debug` and `dev` source sets and into neither `release` set; `src/locallog` and `src/release` join the declared inputs of `tasks.withType<Test>` (`app/build.gradle.kts:103`) so an edit to either re-runs the shape test; `app/src/release/java` (proposed) holds a no-op object with the identical API. `main` references only the API. Content arguments are lambdas, so the no-op never builds a string. The Consumer AAB is inspected in `scripts/release/build.sh`: its dex must not contain the sink class, the archive class or the WAV writer, and its manifest must not declare the log provider; the Dev AAB must contain all four; any miss fails the build (G7). Rejected: a `BuildConfig` boolean, which leaves the code in an unshrunk bundle (release has no R8).

**D3. Side by side.** Hardcoded `taskAffinity="com.envi.wispr.dictation.launcher"` (manifest :64) becomes `${applicationId}.dictation.launcher`. The tile label becomes the application label. The listening notification's title and both notification channels' names (`shortcuts/DictationNotificationController.kt:41,93,150`) and the model download channel (`models/ModelDeliveryNotification.kt:43`) use the application label, so Consumer and Dev notifications say which app owns them. Both apps keep ASSIST and the accessibility service; the setup notes tell the founder to enable the bubble in one app at a time and map the side button to the Dev app. Nothing prevents both services running; §7 row S1 says what he sees.

**D4. The log file.** One owner per process, `LocalLog` (proposed):
- Files: `filesDir/logs/<process>.log` and `<process>.1.log` to `.3.log`, 2.5 MB each: 10 MB per process, 50 MB total, the Mac's total. Each process rotates only its own files, so no two writers ever rotate the same file.
- Per-process lock: `filesDir/logs/<process>.lock`, a `FileChannel` (external) file lock (an `fcntl` lock, honoured across processes). Each process has one lock coordinator: an in-process mutex taken before the file lock, because a second overlapping lock in the same process throws `OverlappingFileLockException` (external) instead of waiting. The writer holds its own process's lock for every append batch and for rotation. Main's log worker acquires another process's lock with `tryLock` (external) in a loop bounded at 2 s; a dead process holds nothing.
- Files are only ever appended, renamed (rotation) or unlinked (the oldest rotation); never truncated or rewritten, so bytes below a recorded length never change.
- Line: `2026-09-27T12:52:25.123-04:00 b=812220745123 [main] I [Tag] take=7837f3e0 message`. `b=` is `elapsedRealtime` at enqueue time (one boot clock for the whole phone) for ordering across files; the wall time is for people.
- One daemon writer thread per process fed by a bounded queue (8,192 lines, drop-oldest with a dropped-count line); it drains in batches with `FileOutputStream` in append mode, no `fsync`. Callers never touch disk. No other actor deletes or rewrites a log file.
- Crash hook: installed in each process only after `Telemetry.bootstrap` returns, wrapping whatever default handler is then current (Sentry's). On an uncaught exception it asks the writer thread to flush and waits at most 200 ms; it never performs file I/O on the crashing thread; in `finally` it calls the captured handler exactly once, including when the flush fails or times out.
- `DebugLogger.debug/log/warn/error/mark` also hand their already-rendered line to `LocalLog`; logcat output is unchanged.
- Take id as structured metadata, fail closed: a take-scoped logger, `TakeLog` (proposed), is created from the take's id at the point each process first learns of the take (admission in main, replacing the singleton `DebugSessionLog` at `ui/SessionLog.kt:19` for take work; `startCaptureForTake` in `:audio`, handed to `TakeRoute`; `startForTake` in `:vad`; `transcribeFileForTake` in `:asr`; `polishRequestForTake` in `:polish`). It has no nullable id and forwards to `DebugLogger` with the id as a field, never parsed from the message and never read from process-global state; logcat output does not change. Take-scoped components receive it in their constructor or call. `DiagnosticsShapeTest` forbids a direct `DebugLogger` call in the take-scoped owners, so a new take-scoped line cannot be written without an id. Plain `DebugLogger` calls elsewhere are written `take=-`. A runtime oracle drives a real take through all five processes and fails if a line from a take-scoped owner between admission and ending carries `take=-`.
- Content goes through one entry point, `LocalLog.words(tag, takeId) { text }` (proposed), which writes to the file only and never to logcat. Its call sites are exactly the §2.5 word points.
- Take id: `AsrService.doTranscribe` receives it as a parameter; `initRecognizer` also runs at service creation before any take exists (`asr/AsrService.kt:238`), so its lines say `take=-` then and carry the take when a take triggers the load. `PolishService.run`, `polishWithS1` and `deliver` take the supplied take id from the request itself, not only from the registry entry, because polish can answer before registration (`polish/PolishService.kt:180,194`). Lines without a take say `take=-`.
- Take endings: every winning commit of the take's terminal arbiter logs the take id and the terminal reason, independent of audio retirement, including endings with no audio file (cancel before binding, processing cancel, start-up failure, empty final text: `ui/DictationSessionCoordinator.kt:983,1035,1087,1137`).
- Cleanup words: `DeterministicCleanup` rewrites several families inside one call (`cleanup/DeterministicCleanup.kt:121`); a file-only words line is emitted after each enabled family, and for a recovered result.
- The clock defect: `DebugLogger.startPipeline` is called at take admission in main and at request start in `:asr` and `:polish`, so every `mark` measures from its own process's start of the take.

**D5. The privacy boundary, stated as enforcement.** Logcat stays content-free in every build; `DiagnosticsShapeTest` gains roots `app/src/locallog` and `app/src/release`, a check that only `LocalLog.words` may carry a transcript-bearing value into the file and that it never calls `android.util.Log`, a check that file I/O appears only in the sink, and a check that `app/src/release/java` holds the no-op. Every object interpolated into a `DebugLogger` line is pinned to a content-free rendering (for example `PolishOutcome.toString`, which prints `chars=` only, `polish/PolishOutcome.kt:30`, logged at `polish/PolishService.kt:378`), and a sentinel test sends known dictated words through every word point and asserts they appear in the file and never in logcat or in a telemetry payload. The rule text becomes: logcat and telemetry describe shape; the Dev app's app-private log file may carry content through `LocalLog.words` only. The Privacy page's sentences stay true for the Consumer app; the Dev app's Privacy page adds one sentence saying the Dev log keeps your words on this phone, in its last 50 MB of log, until newer lines replace them or the app is uninstalled; it leaves the phone only when you tap Share.

**D6. Share log.** `FileProvider` (external) with authority `${applicationId}.logs` declared in the `locallog` (proposed) manifest (proposed `app/src/locallog/AndroidManifest.xml`, merged into debug and dev only). Share runs on main's single-thread log worker (proposed `LocalLog` export worker), which also runs the log export sweep and Delete shared exports, so none of them interleave. It touches only log files and `cacheDir/share/`; recordings have their own worker and folder (D9), so a lock wait here never delays a take's audio. For each process it takes that process's lock (D4), opens every one of its log files and records each file's byte length, then releases the lock and reads exactly the recorded bytes from the held descriptors: later appends lie past the recorded length, and a later rename or unlink does not change what a held descriptor reads, so each process's snapshot is complete and fixed. A lock not obtained within 2 s is reported in the zip's `device.txt` as an incomplete process, never silently skipped. The zip also holds `device.txt` (app version, build, phone model, Android version, free storage, per-process snapshot status). It is shared with a FileProvider `content://` URI in `ACTION_SEND` (external), MIME type `application/zip`, `EXTRA_STREAM` (external), and `FLAG_GRANT_READ_URI_PERMISSION` (external). The finished zip's size is measured and shown; no Gmail delivery is promised from the uncompressed cap. Export lifetime is separate from log lifetime, because a receiving app may open the URI after Share returns: each Share writes a random UUID file name in `cacheDir/share/`, created with an operation that fails if the path already exists (retry on collision, never overwrite); a later Share never deletes an earlier export; exports older than 24 hours are removed on that worker before the next Share creates its zip, and at cold start; Delete shared exports runs on that worker after any Share queued before it has finished and handed off its URI; the page shows their storage use and offers a separately labelled Delete shared exports, which warns that an app still opening one would fail.

**D7. Diagnostics page.** `SettingsPage.Diagnostics` (proposed), group SYSTEM, shown only when `LocalLog.available` (proposed) is true, so the Consumer drawer is unchanged. It shows log size, Share log, shared exports' size with Delete shared exports, Keep recordings switch, recordings count and size, Share last 10 recordings, Clear saved recordings (it removes the recordings saved when its worker task runs; a take in progress can be saved afterwards). English only (catalog decision 2026-09-25).

No Clear log in this change. Grounded round 4 showed the cold-start Clear still races a helper that received `SIGKILL` but has not exited (a kill reports delivery, not termination), and a process list is a point-in-time read, not a barrier. Without a proven process-wide stop barrier there is no safe moment to delete another process's log, so the log is bounded by rotation alone (50 MB) and old lines age out. Clear recordings stays: recordings live only in main and are archived and cleared on the one `CapturedAudioFiles` worker, so no other process or thread touches them.

**D8. History timings (both apps).** Room 9 to 10 adds nullable INTEGER columns `liveAfterMs`, `asrMs`, `insertionMs` (proposed) and `endToEndMs` (proposed) to `transcripts` (`polishLatencyMs` already exists). `liveAfterMs` and `asrMs` are persisted from `TakeFacts` (`telemetry/TakeFacts.kt:27-71`) when the row is saved. `insertionMs` and `endToEndMs` are written by the later insertion-outcome update (`history/TranscriptDao.kt:94`, which today writes status and result only), `endToEndMs` being admission (`ui/DictationSessionCoordinator.kt:339`) to the insertion ending, or to publication when no insertion runs. Model load times are NOT History columns: they are measured in `:asr` and `:polish` and would need a new AIDL result field to reach main (an AIDL surface change is REFACTOR tier); they are log lines in the owning process's file instead, carrying the take id when a take triggered the load. Null means not measured (old rows, a take that never reached the step). The Dev app's expanded History card shows one timings line; the Consumer app shows nothing new.

**D9. Keep recordings (Dev only).** The archive never races the cache file's cleanup, because it never uses the cache name. In the Dev app, `:audio` hard-links each finished production take's PCM, immediately after closing its output (`closeResources`, `audio/AudioCaptureService.kt:877-880`) and before publishing the ending, to `filesDir/recordings-pending/<takeId>.pcm` (proposed) with `Os.link` (external; `cacheDir` and `filesDir` are on the same app data volume, verified on the S26 in chunk 4; a failed link is logged and that take simply is not kept). A pending file is created complete and is never written again. Everything that touches the cache name is unchanged in both apps: main's retirement deletes it as today, and `:audio`'s next-take sweep (`audio/AudioCaptureService.kt:573,690-698`) still runs. Main's retirement on the `CapturedAudioFiles` worker additionally, in the Dev app: when Keep recordings is on (main-process DataStore key, default ON in the Dev app), renames `recordings-pending/<takeId>.pcm` to `filesDir/recordings/<takeId>.pcm` and writes `<takeId>.json` (take id, outcome, duration, sample rate, created time; no text); otherwise deletes the pending file; newest 200 kept, oldest removed, and never more than 1 GB. Ordering, from the code: `:audio` closes the file in `closeResources` (`audio/AudioCaptureService.kt:833`) and publishes the take's ending LAST (`takeEvents.publishEnded`, :853); main learns the file path only from that ending (`ending.audioFilePath`, `ui/DictationSessionCoordinator.kt:725,819,902`; `speechAudioPath` is set from it at :844), and every retirement uses that path. The link is created between the close and `publishEnded`, so every retirement of any take, completed or cancelled, runs after its link exists; no retirement can precede it. A pending file whose take never retires (main died before handling the ending) is removed on that worker at main startup once it is more than an hour old. If capture dies before the close, there is no link and the take is reported in the log as not kept; the cache file follows its existing lifecycle. Acceptance: Keep recordings and PAR-091 are not marked done unless the S26 probe in chunk 4 creates the link and reads identical bytes after the cache name is deleted; if the link fails there, a working archive path is chosen before shipment, and a log line with no saved takes is not acceptance. Share recent recordings runs on the same worker as archive and Clear recordings: it selects and opens its sources there, converts them to WAV in `cacheDir/share-recordings/` (proposed; a UUID name, create-new, a 24-hour lifetime swept on this worker) and closes every descriptor. If Clear recordings runs first, Share finds nothing; if Share runs first, Clear waits for it.

**D10. Telemetry edition.** `BuildConfig.EDITION` (proposed) is `consumer` or `dev`. `TelemetryConfig.environment` becomes `development` when debuggable, `dev` for the Dev edition, else `production`. The Dev app gets the same keys through the same workflow variables, so PostHog and Sentry separate Dev rows from Consumer rows by environment and by release prefix (`com.envi.wispr.dev@…`, `telemetry/TelemetryConfig.kt:24`). `SentryBootstrap.kt:78`'s build-type tag reads `debug` only for `development`.

**D11. Pipeline.** `scripts/release/build.sh` runs `:app:testReleaseUnitTest :app:testDevUnitTest :app:bundleRelease :app:bundleDev`, inspects both AABs (D2), writes `dist/consumer.aab` and `dist/dev.aab` with one receipt. `publish.py` takes the package from the receipt row and, for each row, verifies that row's own artifact hash, manifest package, version code, non-debuggable flag, its track's existing maximum, upload hash, expected status and read-back; it signs and verifies both bundles, records their hashes and uploads both signed artifacts with a partial receipt BEFORE opening either Play edit (the workflow runs `publish.py --prepare` (proposed) to sign, verify and write both artifacts and the partial receipt, then its artifact upload step, and only then `publish.py --publish` (proposed) to open Play edits), so a Dev edit that fails still leaves its signed bundle for D12; it then publishes both to their own internal tracks in one run, persisting each signed, verified bundle and its per-app publication state even when the other app fails; the workflow uploads both signed bundles and the partial receipt with `if: always()`, and requires fresh nonzero Release and Dev test XML and uploads both report directories. The PR check (`scripts/ci/check.sh`) also runs `testDevUnitTest` (proposed), `bundleDev` (proposed) and `lintDev` (proposed) and requires a nonzero Dev test receipt, so the Dev app is proven before merge, not first at the Play run. No draft-status branch: the API can change an app only after its first artifact was uploaded through Play Console (developers.google.com/android-publisher/edits), so the Dev row publishes `completed` exactly as the Consumer row does once D12 is done; until then the Dev row fails loudly, the Consumer row still publishes, and the signed Dev bundle is kept as an artifact for D12. The confirmation read (`publish.py:136`) and receipt (:141) stay per row. Same version code for both (codes are per app).

**D12. One-time Play setup (outside code).** Create the app "EnviousWispr Dev" in Play Console and enrol it in Play App Signing; upload the first signed Dev bundle (the `published-play-bundle` artifact of the first pipeline run on this branch's merge, signed with the existing upload key) through the Console to internal testing and roll it out there, completing whatever setup the Console demands for that rollout; only after that first upload may `publish.py` open edits for later Dev bundles. Grant the service account "Release apps to testing tracks" and "View app information" on the new app, select the "EnviousWispr Internal Testers" list, open the opt-in link on the S26. Done through the browser by the session where the founder's signed-in Console allows, else by the founder with written click steps.

**D13. Harness and scripts.** `scripts/uat/wispr_eyes.py` `PACKAGE` reads an optional `WISPR_PACKAGE` (proposed) environment value, default `com.envi.wispr`; `debug.*` receiver actions stay under the debug build's package. The harness's persisted switch debts (`scripts/uat/wispr_eyes.py:1423`) are keyed by package as well as device, and a debt is never restored against another package. In this PR (no deferred cleanup): `play-console-operations.md` (the workflow publishes both packages), `device-testing.md` (setup for either package), `scripts/ci/check.sh` (its release-path description and the Dev tasks), and every rule line naming the single app are updated in place; superseded single-app commands and claims are removed.

## 3b. Ownership justification

The file sink will live in `LocalLog` inside the `locallog` source set because the Consumer bundle must not carry it (G7) and `DebugLogger` must stay the one logcat door that #194's test pins; the alternative was a file branch inside `DebugLogger` behind a flag, rejected because the code would ship in the Consumer bundle and #194's check (d) exists to keep file I/O out of that object. The archive lives on `CapturedAudioFiles` because it is already the one owner of the take's audio file (#358); the alternative, a copy in `:audio` at capture end, would duplicate that ownership across processes.

## 4. Contract deltas

- `DebugLogger`: every line also goes to `LocalLog` when available; logcat unchanged. `startPipeline` now means "this process's start of this take".
- `LocalLog.words` (proposed): the only path by which user words reach any sink; file only.
- `AsrService.doTranscribe`, `initRecognizer`: gain a `takeId` parameter (internal, same process).
- `CapturedAudioFiles.delete`: still deletes the cache file in both apps; in the Dev app it also keeps or deletes that take's pending hard link. `:audio`'s capture close gains the Dev-only hard link (D9).
- `TranscriptEntity`: four nullable timing columns (`liveAfterMs`, `asrMs`, `insertionMs`, `endToEndMs`); null means not measured.
- `TranscriptDao`'s insertion-outcome update also writes `insertionMs` and `endToEndMs`.
- `TakeLog` (proposed): the take-scoped logger each process builds from the take's id; the only way a take-scoped owner logs.
- `TelemetryConfig.environment`: gains the value `dev`.
- `publish.py`: publishes each bundle in the receipt, verified and recorded per row; one app's failure never blocks or hides the other's.
- `SettingsPage`: gains `Diagnostics`, filtered from the drawer when unavailable (`AppNavigationShapeTest` updated to say entries minus unavailable pages).

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Processes that log | main, `:audio`, `:vad`, `:asr`, `:polish` (manifest :75-132); each gets its own file set; enumerated, none shared |
| Paths that end a take (each must log its ending with the take id) | every winning commit of the terminal arbiter, enumerated from the arbiter's producer at build time rather than from the audio sites; known members include the audio-retiring sites (:725, 835, 846, 859, 870, 882, 902, 1166, 1193) and the no-audio endings (:983, 1035, 1087, 1137); plus process death recovered as `dictation.interrupted` (`telemetry/TakeJournal.kt:74`) |
| Log lines with no take | speech model load at `:asr` service creation (`asr/AsrService.kt:238`), process start, telemetry bootstrap; written `take=-` |
| Places that write user words today | the §2.5 word points; each gets one `LocalLog.words` call; `DiagnosticsShapeTest` pins the set by count |
| Files the Share zip reads while written | the current file of each process; torn last line accepted (D6) |
| Holders of a log file descriptor | each process's writer (its current file), Share (all files of a process, between its lock and the zip's end); enumerated, none other |
| Holders of a shared export URI | any app the user shared to, for an unknown time; exports have their own 24-hour lifetime and an explicit delete (D6) |
| Audio files alive at once | one per take, plus archived ones; the `:audio` next-take sweep deletes only `recording-take-*` in `cacheDir`, in both apps as today; the Dev archive holds its own hard links under `filesDir/recordings-pending/` and `filesDir/recordings/` (D9); nothing but Clear recordings and the retention limits deletes `filesDir/recordings` |
| Settings read outside main | none added (D4, D9) |

### 5.1 Log-file concurrency, enumerated (the class two review rounds found members of)

Actors: five writers (one per process, each touching only its own files), main's one log worker (Share, one at a time), and external apps that may open a shared export URI after Share returns. Export zip lifetime is reviewed separately from log-file lifetime (D6). Operations on a log file: append (writer, under lock), rotate by rename and unlink the oldest (writer, under lock), snapshot open plus length record (Share, under that process's lock), bounded read of held descriptors (Share, no lock). Nothing else deletes a log file. Main-side file operations run on two single-thread workers with DISJOINT files: the log worker (log Share, the log export sweep, Delete shared exports; it touches only `filesDir/logs/` and `cacheDir/share/`) and the existing `CapturedAudioFiles` worker (take PCM retirement, archive, Clear recordings, Share recent recordings, stale pending cleanup; it touches only `recording-take-*` in `cacheDir`, `filesDir/recordings-pending/`, `filesDir/recordings/` and `cacheDir/share-recordings/`). Operations on one worker never interleave; the two workers share no file, and a log lock wait never delays audio retirement. The archive reads only hard links `:audio` creates complete after closing a take's file, never the cache name (D9). Every pair:

| Pair | Can they overlap? | Outcome |
|---|---|---|
| append / append | no: one writer per file | n/a |
| append / rotate | no: same thread | n/a |
| append / snapshot open | no: same lock | the snapshot sees whole batches only |
| append / bounded read | yes | appended bytes lie past the recorded length; not read |
| rotate / bounded read | yes | rename and unlink leave held descriptors readable; bytes unchanged |
| dead writer / Share | lock free | closed files read |
| main's own writer / main's worker | no: in-process mutex before the file lock | no `OverlappingFileLockException` |
| log Share / Delete shared exports, log export sweep | no: the log worker runs them in order | a Delete queued after a Share runs after that Share's URI is handed off |
| archive / Share recent recordings / Clear recordings / stale pending cleanup | no: the `CapturedAudioFiles` worker runs them in order | a Share finds only recordings that exist when it runs |
| `:audio` link creation / retirement of the same take | no: the link precedes `publishEnded`, and main can retire only a path it learned from that ending | every retirement finds the link |
| `:audio` link creation / stale pending cleanup | yes | cleanup removes only pending files over an hour old; a link is created complete and never written again |
| `:audio` PCM writer / anything in main | writes only the cache name, which main's retirement deletes exactly as today | unchanged behaviour |
| log worker / `CapturedAudioFiles` worker | yes | no shared file, so no effect |
| `:audio` next-take sweep / archive | yes | the sweep deletes a cache name; the archive holds its own hard link, so its bytes survive |
| Share / later Share | no: one worker; a random UUID path created with create-new semantics | an earlier export is never overwritten or deleted by a later Share |
| external app / export deletion | yes, by design | only the 24-hour sweep or the warned Delete shared exports removes an export |
| reboot / anything | the only persisted state is the files | nothing to reconcile |

Pre-committed consequence, written before the round 3 verdict: if round 3 finds another defect in this class, the live Clear is DELETED and replaced by "Clear on next start". Round 3 found one (an external app opening an export after Clear deleted it), so the live Clear is deleted (D7) and exports got their own lifetime (D6). Round 4 then showed the cold-start replacement still races a killed-but-not-exited helper, so Clear log is removed from this change entirely (D7, §14 Q3).

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `DebugLogger` also feeds `LocalLog` | every call site | logcat | logcat plus file in debug and dev | no | `LocalLogTest` (proposed) |
| `LocalLog.words` | §2.5 word points | none | file line with take id | yes | `DiagnosticsShapeTest` count |
| `CapturedAudioFiles` retire | coordinator sites | delete | delete, plus keep or drop the pending link in Dev | no (callers) | `CapturedAudioFilesTest` (proposed rows) |
| History columns | `TranscriptRepository`, History card, migration test | absent | nullable ints | yes | migration test 9 to 10 |
| environment `dev` | Sentry tag, PostHog stamp, `PayloadSanitizer` label shape | two values | three | Sentry tag line | `TelemetryConfigTest` (proposed) (proposed rows) |
| two bundles | `publish.py`, workflow | one | two | yes | workflow run |
| package override | `wispr_eyes.py` | fixed | default unchanged | yes | `scripts/uat/test_wispr_eyes.py` |

## 7. Failure-mode × caller table

| # | Failure | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|---|
| L1 | Disk full or write error | writer thread | `LocalLog` | nothing; dictation unaffected; one logcat warning per process start | lines lost | next line tries again |
| L2 | Queue full | burst | callers | nothing; a `dropped=N` line | lines lost | none |
| L3 | Process killed with queued lines | OS | none | nothing | queued lines lost | none |
| L4 | Share with no share target | Android | Diagnostics | the share sheet's own empty state | zip in cacheDir, deleted at next share | user |
| L5 | Zip over 25 MB | Gmail | Diagnostics | Gmail offers Drive | none | user |
| A1 | Pending-to-kept rename fails | worker | `CapturedAudioFiles` | nothing; the pending file is deleted | nothing | none |
| A2 | Hard link fails in `:audio` | `:audio` | none | nothing; that take is not kept; one log line | nothing | none |
| P1 | Consumer AAB contains the sink | build | build.sh | red Play run; no upload | none | fix and push |
| P2 | Dev app has no Console-uploaded artifact yet (every run before D12, or D12 not done) | Play API | publish.py | red Dev row; Consumer published | signed Dev bundle kept as the run's artifact | D12: upload it once through the Console, then later runs publish |
| P3 | Upload key refused for the new app | Play | same | same | none | same, choosing the existing key in the Console |
| S1 | Both apps' bubbles enabled | founder | both services | two bubbles; each dictates into the field it pinned | none | turn one off in Permissions |
| S2 | Dev app has no models on first run | fresh install | setup | the existing model download screen (stage 2 path, unaccepted) | none | download; §14 Q2 |

Copy: Diagnostics labels only, English, in the design language; no customer surface changes.

## 8. Caller-visible signals

- `take=-` on a line means no take was in scope, not a missing value.
- A null timing column means not measured.
- `dropped=N` line means the queue overflowed.
- The Diagnostics drawer entry's presence means the build has the sink.
- `environment=dev` identifies Dev rows in Sentry and PostHog.

## 9. Fallback source of truth

| Failure branch | Candidate | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| A1, A2 | the cache file's lifecycle, unchanged | `CapturedAudioFiles` and the `:audio` sweep | today's owners | dictation unaffected | warning line | coordinator |
| P2, P3 | manual upload | the run's `published-play-bundle` artifact | the exact signed bytes the run built | Console accepts | founder decision | Play |
| L1 to L3 | logcat | `DebugLogger` | unchanged path | line present in logcat | none | developer with adb |

## 10. File-by-file changes

Chunk 1 (two apps): `app/build.gradle.kts` (dev build type, `EDITION` field, source sets), `app/src/main/AndroidManifest.xml` (:64 affinity, tile label), `telemetry/TelemetryConfig.kt`, `telemetry/SentryBootstrap.kt:78`, `scripts/release/build.sh`, `scripts/release/publish.py`, `scripts/release/test_receipt.py`, `.github/workflows/play-internal.yml`, `scripts/uat/wispr_eyes.py`, knowledge `play-console-operations.md`, `device-testing.md`.
Chunk 2 (log): `app/src/locallog/java/com/envi/wispr/debug/LocalLog.kt` (proposed), `app/src/release/java/com/envi/wispr/debug/LocalLog.kt` (proposed no-op), `app/src/locallog/AndroidManifest.xml` (proposed), `app/src/locallog/res/xml/log_paths.xml` (proposed), `debug/DebugLogger.kt`, the word points in `asr/AsrService.kt`, `ui/TakePolishController.kt`, `cleanup/PolishPipeline.kt`, `polish/PolishService.kt`, `providers/ProviderPolishClient.kt`, `ui/DictationSessionCoordinator.kt`, `paste/AccessibilityInsertionRunner.kt`; `ui/AppNavigation.kt`, `ui/AppShell.kt`, a `DiagnosticsPage.kt` (proposed), `privacy/PrivacyDisclosure.kt` (Dev sentence only), `DiagnosticsShapeTest.kt`, rule `kotlin-patterns.md`.
Chunk 3 (History timings): `history/TranscriptEntity.kt`, `history/EnviousWisprDatabase.kt`, `app/schemas/.../10.json`, the coordinator and History write path, `ui/HistoryScreen.kt` (Dev line), migration test.
Chunk 4 (recordings): `ui/CapturedAudioFiles.kt`, `audio/AudioCaptureService.kt` (the Dev hard link after `closeResources`), `settings/AppPreferences.kt`, the Diagnostics page rows, a WAV writer in the locallog source set (proposed).

## 11. Testing

1. Classes: `LocalLogTest` (product outcome: when it fails, the founder's shared log is missing lines or mis-ordered), `DiagnosticsShapeTest` additions (drift guard), the AAB inspection (drift guard: the customer app ships the log), `CapturedAudioFilesTest` rows (product outcome: a kept recording is missing), migration 9 to 10 (product outcome: History lost after update), `publish.py` receipt test (harness contract).
   Recordings and exports oracle: archive one recording, tap Clear recordings, assert storage holds no recording or metadata. Share twice with the clock held at the same value; assert two distinct exports and that the first still opens after the second. An export older than 24 hours is gone after the next Share; Delete shared exports removes all of them. Ordering: queue Share then Delete shared exports, assert the first export was handed off before deletion and the Delete removed it afterwards; queue Share recent recordings then Clear recordings and the reverse, assert the first yields playable WAVs and the second yields nothing to share. Retirement under load: delay the `CapturedAudioFiles` worker, finish take A, start take B so `:audio`'s sweep deletes A's cache name before A's retirement runs, then release; assert A's recording and metadata are archived and B still dictates. Order check: a retirement is attempted from every ending path (completed, cancelled during capture, speech not ready, speech unresponsive) and each finds its take's pending link. Kill main during a capture, restart, admit another take; assert the earlier take's pending file is untouched while under an hour old and removed at a later startup once older. Crash oracle: a forced crash delegates to Sentry's handler and Android's crash handling even when the writer is stalled, and the hook waits no more than 200 ms; lines logged just before the crash are asserted present only when the writer acknowledged its flush, and otherwise reported as best effort.
   Outcome assertions, not counts: a sentinel take is driven through `DebugLogger` and every word stage, and the test asserts the sentinel's actual text and take id in the file (a call left in place with empty text fails it); a completed History row is asserted to hold each of `liveAfterMs`, `asrMs`, `polishLatencyMs`, `insertionMs` and `endToEndMs` by name, the last two arriving after the row is written, so dropping any one measurement turns it red.
2. Reverts: remove the `LocalLog` hand-off in `DebugLogger` (the sentinel test goes red, not only a direct sink test); add a `LocalLog.words` call outside the pinned set, or a `Log.i` inside it (shape test red); add the sink to `app/src/main` (AAB check red on the release build); remove the hard link in `:audio` or make retirement always drop the pending link (archive row red); drop `MIGRATION_9_10` (proposed) (migration test red). Each revert is performed and its red receipt recorded.
3. Not tested: the share sheet's target apps (Android's surface), Play Console steps (manual, receipts are screenshots), cross-process ordering under clock change (boot clock by construction).

### 11.1 Hardware UAT spec
- Subsystem: heart path (logging on capture, speech, polish, insertion) plus limb (Diagnostics, archive).
- Recipe: `device-testing.md` silent-audio injection recipe for the S26 when the founder is away; otherwise his own takes.
  1. Both apps installed from Play; Dev app set up; bubble enabled in the Dev app only.
  2. Three takes into Gmail, one into WhatsApp, one cancelled.
  3. Share log to the Mac. Oracle: every take's lines share one take id across all five process files; each completed take has raw, cleanup, polish and final words lines and every reached timing line, in `b=` order matching the pipeline order; the cancelled take has its take id, its last reached stage and its terminal reason, and no stage after the cancel.
  4. Consumer app: one take into Gmail lands; no Diagnostics entry; `run-as` is unavailable so the oracle is the AAB check plus the absent drawer entry.
  5. Keep recordings on: five `.pcm` plus `.json` under the Dev app, Share last 10 produces playable WAVs.
- Restore: bubble and side-button mapping as the founder had them; Keep recordings left as he chooses.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| PR check runs `testDevUnitTest` and `bundleDev` with a nonzero Dev test count, and `testDebugUnitTest` unchanged | harness | D1 | remove the build type |
| emulator: debug build writes `filesDir/logs/*.log` for all five processes | product | G2 | disable the hand-off |

## 12. Blast radius & rollback

Touched: `:app` all processes (logging only), History schema, the Play pipeline. Not touched: PostHog and Sentry payloads, `PayloadSanitizer`, `Provider.disclosure`, the GenieX silencer, the insertion decision logic, `:llama-android`, `:accelerator-benchmark`. Rollback: revert the PR; the Consumer app returns to today's build; the Dev app stops updating (its listing stays, unpublished); Room 10 stays with nullable columns (a rollback build never downgrades, same as 8 to 9).

## 13. Ship criteria

- [ ] Two apps on the S26 from Play, "EnviousWispr" and "EnviousWispr Dev", both dictating.
- [ ] A shared Dev log shows each take's words at every step and its timings under one take id.
- [ ] The Consumer app has no Diagnostics entry and its AAB has no log code.

## 14. Open questions

1. Q1 (founder, Gate 2): package names. Recommended: Consumer keeps `com.envi.wispr`, Dev is `com.envi.wispr.dev`, matching the Mac. His current History, dictionary, keys and models stay in the Consumer app and do not move.
2. Q2 (founder, Gate 2): the Dev app starts empty, so its models come through the in-app download (built, never accepted on a fresh phone). If that fails on the S26, the Dev app cannot dictate until it is fixed; that becomes the first finding of this change.
3. Q3 (founder, Gate 2): no Clear log button in this change; the log keeps the last 50 MB and old lines age out on their own. A Clear log that is provably safe needs a way to stop every helper process first and is left for a later issue if he wants it.

## 14b. Review log

- Problem-only consult (`.codex/2026-09-27-issue-378-consult.txt.last`): build type versus flavor, per-process files, the main-only DataStore, FileProvider authority, compile-out, side-by-side traps, the edition-versus-debuggable telemetry trap. All folded into D1 to D10.
- Coverage round 1 (`.codex/2026-09-27-issue-378-coverage-r1.txt.last`): 12 gaps across all 7 axes, all adopted: take endings enumerated from the arbiter not the audio sites; pre-take speech load and pre-registration polish take ids; per-family cleanup words; harness debts keyed by package; partial-publication artifacts; per-row publish verification; locallog manifest and resources wiring plus Gradle test inputs; Dev tasks on the PR check; AAB check covers archive and provider; object interpolation pinned plus a sentinel test; notification labels; same-PR knowledge and CI updates; outcome assertions instead of counts; a cancelled take's oracle. Modified: the polish-outcome interpolation Codex could not verify is content-free today (`polish/PolishOutcome.kt:30` prints `chars=` only), so it becomes a pinned member of the sentinel test rather than a code fix.

- Grounded round 1 (`.codex/2026-09-27-issue-378-grounded-r1.txt.last`, PROCEED-WITH-REVISIONS): 6 blocking and 1 non-blocking, all adopted. D8 cut to the timings main already holds plus the later insertion write, model loads moved to log lines (modified: Codex offered an AIDL result field; rejected because an AIDL surface change is REFACTOR tier and the log already carries the number); D11 and D12 take the first Dev upload through the Console, the draft branch deleted; D4 carries the take id as structured metadata; D4 crash hook installed after Sentry, bounded, never I/O on the crashing thread; D6 snapshot through the per-process lock and held descriptors, plus the share intent contract; D7 Clear deletes under the lock and drops queued pre-clear lines; the zip size is measured, not promised; §11 gains Clear, crash and named-column oracles.

- Grounded round 2 (`.codex/2026-09-27-issue-378-grounded-r2.txt.last`, PROCEED-WITH-REVISIONS): 4 blocking and 1 non-blocking, all adopted. Two of them (snapshot, Clear) are the second round of one class, log-file concurrency, so the class is enumerated in §5.1 from the operations rather than patched per finding, with a consequence pre-committed. Modified: Codex's Clear fix used a persisted boot-count plus nanosecond cutoff; replaced by the writer's lock-held existence check and a whole-queue drop, which needs no clock and has nothing to survive a reboot. Adopted as written: the writer locks every batch, the in-process mutex plus `tryLock`, the recorded-length snapshot, Share and Clear on one worker, `TakeLog` with no nullable id replacing the singleton session logger for take work, signing and uploading both bundles before any Play edit, the narrowed crash oracle.

- Grounded round 3 (`.codex/2026-09-27-issue-378-grounded-r3.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (a third member of the log-file concurrency class: an external app opening an export after Clear deleted it) and 1 non-blocking (how the artifact upload precedes the edit). The pre-committed consequence was executed as written: the live Clear is deleted and replaced by a cold-start Clear (D7), exports got their own lifetime (D6), §5.1 re-enumerated with the external app as an actor. Adopted: `publish.py --prepare` then artifact upload then `publish.py --publish`.

- Grounded round 4 (`.codex/2026-09-27-issue-378-grounded-r4.txt.last`, PROCEED-WITH-REVISIONS): 2 blocking, both adopted as written. Clear log is removed from this change (no safe deletion moment without a proven stop barrier; the log is bounded by rotation); export names are random UUIDs created with create-new semantics.

- Grounded round 5 (`.codex/2026-09-27-issue-378-grounded-r5.txt.last`, PROCEED-WITH-REVISIONS): 2 blocking (Delete shared exports versus Share; Share recent recordings versus Clear recordings) and 1 non-blocking (stray Clear log wording), all adopted. Modified: rather than per-operation ordering rules, every main-side file operation of this change runs on the one existing `CapturedAudioFiles` worker thread, which closes the class by construction.

- Grounded round 6 (`.codex/2026-09-27-issue-378-grounded-r6.txt.last`, PROCEED-WITH-REVISIONS): round 5 fixes landed; the log portion of the class closed; 1 blocking (a log Share on the audio worker could delay retirement past `:audio`'s next-take sweep, losing a kept recording). Adopted Codex's fix (Dev `:audio` stops sweeping; the audio worker cleans abandoned PCM after pending retirements) and additionally split the log worker from the audio worker with disjoint files, so a log lock wait never delays retirement.

- Grounded round 7 (`.codex/2026-09-27-issue-378-grounded-r7.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (the Dev no-sweep plus admission cleanup could unlink a PCM still being written by an orphaned `:audio` capture). Modified rather than adding a capture-file lock: the round 6 no-sweep design is deleted and the archive no longer depends on the cache name at all. `:audio` hard-links the closed file into a pending folder; nothing ever writes a pending file, so no cleanup can race a writer, and the cache name keeps today's lifecycle in both apps. This removes the capture-start lock cost Codex asked to measure.

- Grounded round 8 (`.codex/2026-09-27-issue-378-grounded-r8.txt.last`, PROCEED-WITH-REVISIONS): 1 blocking (retirement before the link would lose a kept take), 1 acceptance addition (the S26 link probe gates PAR-091), 1 non-blocking (Clear saved recordings wording). Modified the blocking fix: no deferred-retirement protocol is needed, because the code already orders it: `:audio` publishes the ending last, after closing the file, and main learns the path only from that ending, so a link made between close and publish always precedes every retirement. Adopted the probe gate and the wording as written.
- Grounded round 9 (`.codex/2026-09-27-issue-378-grounded-r9.txt.last`): the retirement ordering verified against every `capturedAudio.delete` call; the file concurrency class closed at plan level; **VERDICT: PROCEED-AS-PLANNED**. Open for hardware: the S26 hard-link probe and real latency.

## 15. Related

#194, #114, #358, #176, #288; PAR-089, PAR-090, PAR-091; catalog `debug-local-log`, `debug-audio-archive`, `privacy-safe-telemetry`.
