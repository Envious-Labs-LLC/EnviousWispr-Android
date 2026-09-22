# #194 — Local diagnostics are structurally content-free

## Preface — Lane + Hardware UAT declaration

**Lane:** Code
mixed_pr: true — `Code` (`app/**`: `tests`, `codex-review`, `hardware-uat`) and `Docs/dev-tooling` (this plan, the audits, `.claude/**`: `cited-symbols` conditionally).

**PAR rows closed:** none. PAR-089 (a diagnostics screen with no secrets or dictated content) is adjacent, not closed: no screen changes here.

**Hardware UAT:** Y. The change touches the log calls on the heart path (capture, ASR, polish, insertion), so one ordinary take runs on the emulator after the change and its logcat is swept for the markers (a transcript fragment, an exception message) as evidence that the words never reach the log; the founder's phone is excluded by his instruction (2026-09-21 morning). What success looks like for a person: nothing they can see changes; a support engineer reading their logcat sees "Polish failed (SocketTimeoutException at ProviderPolishClient.call:212)", never the prompt or the words.

## Preface — User Rubric

User Rubric: N/A — internal-only: no user-visible surface changes; the change is what the app writes into its own logs.

## 0. TL;DR
`DebugLogger` renders a `Throwable` as its class name and one frame location, never its message or full stack trace; the dormant shared-storage file sink is deleted; every `${error.message}` in a diagnostic line becomes the exception's class name; the 43 raw `android.util.Log` calls in the paste package, the 6 in the debug-only receivers and the 5 in the instrumentation tests go through `DebugLogger`; the debug insert receiver logs the text's length, not the text; the speech process's failure detail is the exception class, not its message; the model-delivery log names the source host's closed token, not the host; the llama.cpp log callback discards vendor text like the GenieX silencer; one JVM shape row over `app/src/main`, `app/src/debug` and `app/src/androidTest` refuses the reintroduction of any of these, and marker rows prove the rendering.

## 1. Problem
`DebugLogger.error(tag, message, throwable)` passes the throwable to `Log.e`, which prints `Throwable.toString()` (class plus MESSAGE) and the trace of every cause; a vendor or parser exception's message can carry the prompt, a URL with a key, or the dictated text (`code-gotchas.md` RULE: geniex-logs-the-prompt-so-silence-it-before-inference is the precedent). 29 diagnostic lines interpolate `${error.message}` directly. The off-by-default file sink writes to `/sdcard/EnviousWispr/debug.log` behind `MANAGE_EXTERNAL_STORAGE`, which the manifest does not declare (`enableFileLogging` refuses at runtime); it has no caller and would write the same text to shared storage. 43 sites in `paste/` call `android.util.Log` directly, three of them with a throwable argument.

## 2. Goals & non-goals
### 2.1 Goals
- Structurally: the ONLY renderer of a `Throwable` into a log line is `DebugLogger`, and it renders class + one frame, never message, `toString` or trace.
- Every diagnostic line in `app/src/main` is free of `.message`, `.localizedMessage`, `stackTraceToString`, `Throwable.toString` and string-templated throwables.
- No shared-storage sink; no raw `android.util.Log` outside `DebugLogger`.
- A static JVM row over the source tree keeps all three true; marker rows prove the renderer.
### 2.2 Non-goals
- Telemetry (`PayloadSanitizer`, Sentry, PostHog): unchanged, its own allowlist.
- User-facing error text (`AppViewModel.historyError`, `DictionaryScreen`, the model-delivery notification): those read `failure.message` into a SCREEN, which is a product surface, not a diagnostic; out of scope, named in §14.
- A closed enum of every diagnostic event name (the issue's "closed event name"): 180 sites of literal templates already read as shape; wrapping each in an enum member is the audit's -60 LOC budget times ten and pins nothing a shape row does not. The closed set this change enforces is the RENDERER's inputs (class name, frame, numbers, the literal template), not a catalogue of templates.
- The vendor silencer (`S1NativeLog`, `geniex_log_silencer.cpp`): unchanged.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end
Producers (coverage round C1/C2 added the last three groups): 21 `DebugLogger.error` sites (`AsrService` 5, `AudioCaptureService` 7, `PolishService` 3, `SessionLog` 1, `SilenceVadService` 2, `SileroVadSession` 3), 111 `DebugLogger.warn`/`log`, 43 raw `Log.*` in `PasteAccessibilityService` (36), `RecordingAccessibilityOverlay` (5), `EditorInputSession` (2), plus the session owner's `SessionLog` seam (`DebugSessionLog` → `DebugLogger`) and the provider clients' `logInfo`/`logWarn` lambdas (bound to `DebugLogger` at `ProviderModelDiscoveryClient.kt:29-30`, `ProviderPolishClient.kt:23-24`, `HttpProviderTransport.kt:73`); the debug-only receivers in `app/src/debug` (`DebugDumpReceiver.kt:32` passes a throwable to `Log.w`; `DebugInsertReceiver.kt:28` logs the inserted TEXT verbatim; `DebugTelemetryReceiver.kt:27`, `PasteProbeActivity.kt:25,41`); the instrumentation tests (`SilenceStoppedTakeTranscribesDeviceTest.kt:113` logs a transcript verbatim; `VoicePipelineDeviceTest.kt:162,198,318`, `PolishServiceDeviceTest.kt:96`); the native `llama_log_callback` in `llama-android/src/main/cpp/s1_jni.cpp:32-37`, which forwards every vendor string to logcat; and `ModelDeliveryWorker.kt:116`, which logs the serving HOST as a template value where `ModelSourceHost.of(host).wire` is the closed token. Owner of the rendering: `DebugLogger` (`android.util.Log` plus the file sink). Consumers: logcat (and the wispr-eyes harness, which greps template text: `Recording started`, `Take ended`, `Stopped by`, `insertion api=`), `AudioServiceShapeTest.everyLogTemplateAndThreadNameSurvivesTheMove` (a multiset of the audio service's templates), the coordinator rig's `FakeLog.awaitLine` (two literal lines, `Unable to load cleanup preferences` and `Unable to load custom terms`, `DictationSessionCoordinatorTest.kt:848-849`), and the harness's `DebugInsert: pin=` prefix (`wispr_eyes.py:2682`), which `debug_insert` reads. The message interpolations, measured on `69b4a6a`: 24 `DebugLogger`/`Log` lines and 5 `log.warn` lines (`grep -rn '\.message' app/src/main/java | grep 'DebugLogger\|Log\.[iwe]\|log\.\(warn\|error\|log\)'`). One more producer of exception text into a diagnostic: `AsrService.kt:133,181` send `e.message` as the AIDL failure `detail`, which the owner logs at `DictationSessionCoordinator.kt:1048` ("ASR failed: ... $detail"); the legacy callback's sentence for `AUDIO_UNREADABLE`/`DECODE_FAILED` also carries it (a user-facing sentence on the OLD callback only; the versioned callback carries a code).
### 2. Find the existing authority before proposing one
`DebugLogger` is the one diagnostic owner; `SessionLog` is the owner's seam over it (#186). Both stay; the change narrows what `DebugLogger` renders. `scripts/check-visibility.py` is the precedent for a source-tree check; `SessionOwnerShapeTest.onlyTheOwnerPinsTheTarget` (an inventory of every call in production source) is the precedent for a JVM row that sweeps the tree.
### 3. Read prior attempts and live direction
The catalog's `debug-local-log` row: Android's sink is off by default with no production caller and an undeclared storage requirement; macOS compiles every sink out of release; Windows ships a local log by design. No prior attempt to remove the Android sink; the audit (REF-10) asks for it.
### 4. Name the lifecycle, trust and process boundaries a naive design would miss
Four processes log; `DebugLogger` is an `object` per process, so the renderer is the same code everywhere. The AIDL `detail` string crosses `:asr` → main: making the PRODUCER send a class name makes every consumer content-free at once; the AIDL signature is unchanged (append-only rule untouched). The raw `Log` calls in `paste/` run in the default process's accessibility service; `DebugLogger` has no `Context` dependency, so the migration is a rename.
### 5. Prove the high-risk premises
- `Log.e(tag, msg, tr)` prints `tr`'s message: Android's `Log.e(String, String, Throwable)` appends `getStackTraceString(tr)`, which begins with `tr.toString()` = class + message, then every cause's. Verified by reading the platform contract; the marker row proves OUR renderer never calls it with a throwable.
- No caller enables the file sink: `grep -rn enableFileLogging app/src` → one hit, the definition. The manifest declares no `MANAGE_EXTERNAL_STORAGE` (grep, zero hits), so the sink could never have opened on a device.
- The harness greps template text, not exception text: `scripts/uat/wispr_eyes.py` `last_take` reads `Stopped by ([^.]+)\.`, `Recording started`, `insertion api=`; none of the migrated lines change a template the harness or a shape row reads (the templates stay; only `${x.message}` → `${x.javaClass.simpleName}` and the raw-Log → `DebugLogger` prefix change).

## 3. Design
D1. **`DebugLogger`** (`debug/DebugLogger.kt`): delete the file sink (`LOG_PATH`, `enableFileLogging`, `close`, `logWriter`, `writeToFile`, `truncateLog`, the `Environment`/`File`/`FileWriter` imports). `error(tag, message, throwable)` becomes `Log.e(tag, render(message, throwable))` with `render(message: String, throwable: Throwable?): String` (proposed) = `message` alone, or `"$message ($ClassSimpleName at $frame)"` where `frame` is the FIRST frame whose class starts with `com.envi.wispr.` (where our code met it), falling back to the throwable's top frame and then `"no frame"`, as `ClassSimpleName.method:line`; the CAUSE chain contributes only class names (`caused by X, Y`), walked iteratively with identity tracking and `MAX_CAUSE_CLASSES` (proposed) = 4, a repeat or a fifth cause ending the list with `…` (grounded round 1). The throwable's frames are read once, on an error path only. Add `debug(tag, message)` (proposed) → `Log.d` for the paste package's `Log.d` sites. `render` is `internal` so the marker rows call it on the JVM (the `Log` calls themselves are stubbed there).
D2. **Every `${x.message}` in a diagnostic line → `${x.javaClass.simpleName}`** (29 sites, enumerated in §10). Where the same information is already carried by the throwable argument (`DebugLogger.error(TAG, "…", e)`) nothing changes.
D3. **The paste package** (43 sites): `Log.i/w/d(TAG, …)` → `DebugLogger.log/warn/debug(TAG, …)`; `Log.w(TAG, "…", error)` (5 sites) → `DebugLogger.error(TAG, "…", error)`; the `import android.util.Log` lines go. **The debug source set** (`app/src/debug`, 6 sites, coverage F1): the same migration; `DebugDumpReceiver.kt:32` → `DebugLogger.error(TAG, "window tree read failed", failure)`; `DebugInsertReceiver.kt:28` → `DebugLogger.log("DebugInsert", "pin=$pin handoff=$handoff textChars=${text.length}")`, keeping the `DebugInsert: pin=` prefix the harness reads. **The instrumentation tests** (`app/src/androidTest`, 5 sites, coverage F3): the same migration; the transcript line at `SilenceStoppedTakeTranscribesDeviceTest.kt:113` becomes `DebugLogger.log("SilenceUat", "Silence-stopped take chars=${text.length}")`. **The native log callback** (`s1_jni.cpp:32-37`, coverage F3): `llama_log_callback` DISCARDS `text` (discard matches the GenieX silencer, `code-gotchas.md` RULE: geniex-logs-the-prompt-so-silence-it-before-inference). What remains observable (grounded round 1): a healthy load returns and `log_info` logs the model description, context size and thread count (`s1_jni.cpp:203-208`); a failed load returns one of the fixed, content-free JNI error strings (`s1_jni.cpp:172,182,193,200`); the detailed vendor failure text is the thing given up. `S1Native` has no app caller today. **The model-delivery host** (`ModelDeliveryWorker.kt:116`, coverage F5): `DebugLogger.log(TAG, "Model source: ${model.id}/$file from ${ModelSourceHost.of(host).wire}")`.
D4. **The speech failure detail**: `AsrService.kt:133,181` send `e.javaClass.simpleName` as `detail`; the owner's line at `DictationSessionCoordinator.kt:1048` stays (it now prints a class name). The legacy callback's sentence for `AUDIO_UNREADABLE`/`DECODE_FAILED` (`detail.ifBlank { … }`) therefore reads the class name; that callback is the pre-#176 client path with no production caller (the versioned callback carries the code); stated, not redesigned.
D5. **The static row** `DiagnosticsShapeTest` (proposed, `app/src/test/java/com/envi/wispr/debug/`): reads every `*.kt` under `app/src/main`, `app/src/debug` and `app/src/androidTest` (coverage F1, F3); (a) `import android.util.Log` and the fully qualified `android.util.Log.` appear only in `debug/DebugLogger.kt`; (b) across every scanned file, none of the platform's `getStackTraceString` (proposed) helper, `printStackTrace`, `stackTraceToString`, `.localizedMessage`, `.cause?.message`, `.cause!!.message` appears, and `.stackTrace` appears nowhere OUTSIDE `debug/DebugLogger.kt`, which is permitted exactly ONE `.stackTrace` read (the one `render` makes; grounded round 2, since D1 needs the frames and the row would otherwise fail its own implementation) (coverage F4); (c) no call to `DebugLogger.{log,warn,error,debug}`, `SessionLog`'s `log.{log,warn,error}`, or `logInfo`/`logWarn` carries `.message`, a throwable `.toString()`, a string template of a conventionally named throwable (`$e`, `$error`, `$it`, `$throwable`, `$failure`, `$exception`, `$cause`, `$t`, braced or not), or a `String.format(...)` / `"…".format(...)` whose VALUE arguments include one of those names (numeric formatting such as `String.format("%.1f", durationSec)` at `AsrService.kt:137,144,177` stays allowed; grounded round 1) in its argument text, the argument text being the balanced-paren span from the call token so a multi-line call is one span; a throwable passed as a bare ARGUMENT (`log.error("Transcription failed", error)`) is the renderer's input, not templating, and is allowed; (d) `DebugLogger.kt` contains no `Log.e(` / `Log.w(` call with a third argument, no `stackTraceToString`, no `Environment`, no `FileWriter`, and exactly one `.stackTrace`. **Stated limit** (coverage F4): the throwable-in-template check is name-based, not type-aware; a throwable bound to an unconventional name and templated bare (`"$oops"`) passes the scan, and a type-aware Kotlin rule (a compiler plugin or detekt with type resolution) is not in this change's budget; the reviewer's eye and the marker rows are the remaining guard for that shape, stated in the row's KDoc. A two-way control, enumerated: the POSITIVE fixture carries the `android.util.Log` import; the fully qualified `android.util.Log.`; the platform's stack-string helper; `printStackTrace`; `.stackTrace`; `stackTraceToString`; `.localizedMessage`; `.cause?.message`; `.cause!!.message`; `.message`; a throwable `.toString()`; `String.format("%s", failure)` and `"%s".format(error)`; every listed throwable name templated braced and unbraced; a three-argument `Log.e` and `Log.w`; `Environment`; `FileWriter`; and the scan must report each one. The ALLOWED fixture carries `String.format("%.1f", durationSec)` and `log.error("Transcription failed", error)` and the scan must report zero.
D6. **Marker rows** `DebugLoggerRenderTest` (proposed): `render("Polish failed", RuntimeException("TRANSCRIPT_MARKER https://k=SECRET"))` contains `RuntimeException`, contains a frame token, and contains neither marker; a cause chain `IllegalStateException("MARKER2")` under it contributes the class name only; `render("x", null)` is `"x"`; the frame rule, the empty stack and the bounded cause walk each have a row built from an explicitly SET stack-trace array or an explicitly built cause chain so every branch is deterministic (grounded round 2; §11.2).

## 3b. Ownership justification
No new owner. `DebugLogger` stays the one renderer; its surface narrows (the sink gone, `render` added, `debug` added).

## 4. Contract deltas
- `DebugLogger.enableFileLogging()` / `close()`: deleted (no caller).
- `DebugLogger.error(tag, message, throwable)`: same signature; the rendering changes (class + one frame, never message or full stack trace).
- `DebugLogger.debug(tag, message)`: new.
- `IAsrCallback.onFailure(reason, detail)`: unchanged signature; `detail` now carries the exception's class name where it carried its message.
- Log TEMPLATES: the 29 exception-message sites keep their literal templates and change only the interpolated value (message → class name). D3 deliberately changes two templates (`DebugInsert: pin=… textChars=…` and `SilenceUat`'s `Silence-stopped take chars=…`), removes the llama.cpp vendor lines, and replaces the model host value with its closed token. Every other raw-Log migration keeps its tag and its message template (grounded round 2).

## 5. End-to-end state and lifecycle audit
No state. The file sink's `logWriter` lifecycle is deleted with it.

## 6. Downstream consumer matrix
- Logcat readers (a human, the harness): the same templates; a class name where a message was.
- `AudioServiceShapeTest.everyLogTemplateAndThreadNameSurvivesTheMove`: its multiset lists templates with `${}` placeholders; the migrated lines keep their templates, so the row stays green (verified at build; a changed template is added there on purpose).
- `FakeLog.awaitLine`: two literal lines, `Unable to load cleanup preferences` and `Unable to load custom terms`; unchanged.
- The legacy `IAsrCallback` sentence: a class name instead of a message on two reasons (D4).
- Telemetry: untouched.

## 7. Failure-mode × caller table
| Failure mode | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| A throwable with a secret in its message reaches `DebugLogger.error` | a vendor SDK, a parser, OkHttp | any process | nothing; logcat shows the class and a frame | nothing | n/a |
| A throwable with no stack frames (a synthetic one) | a test, a stripped build | `render` | "(X at no frame)" | nothing | n/a |
| A new site interpolates `.message` | a future change | the static row | the unit suite is red with the file and line | n/a | fix the site |

## 8. Caller-visible signals audit
The only signal that changes is the text of diagnostic lines: a class name replaces a message. Every user-facing sentence stays (`TakeNotices`, `InsertionOutcomeMessages`, the screens).

## 9. Fallback source-of-truth audit
None: no fallback path reads a diagnostic.

## 10. File-by-file changes
- `app/src/main/java/com/envi/wispr/debug/DebugLogger.kt`: D1.
- `${x.message}` → `${x.javaClass.simpleName}` (29): `ui/PipelineBindings.kt:124`, `ui/EngineWarmUp.kt:51`, `ui/DictationSessionCoordinator.kt:277,1214,1317,1685`, `audio/DetectorFeed.kt:250,303,344`, `audio/TakeRoute.kt:113,204,245,329`, `audio/AudioCaptureService.kt:746,822,824,827,830` (five sites; §2.5's per-file count of seven is `DebugLogger.error` calls, a different population), `audio/WarmHoldOwner.kt:105,107,145`, `audio/PicturePublisher.kt:82,129`, `vad/SileroVadSession.kt:60`, `paste/PasteAccessibilityService.kt:667,688,1304,1411,1488` (these five also move to `DebugLogger`).
- `paste/PasteAccessibilityService.kt`, `paste/RecordingAccessibilityOverlay.kt`, `paste/EditorInputSession.kt`: D3.
- `app/src/debug/java/com/envi/wispr/debug/{DebugDumpReceiver,DebugInsertReceiver,DebugTelemetryReceiver,PasteProbeActivity}.kt`: D3.
- `app/src/androidTest/java/com/envi/wispr/{VoicePipelineDeviceTest,SilenceStoppedTakeTranscribesDeviceTest,polish/PolishServiceDeviceTest}.kt`: D3.
- `llama-android/src/main/cpp/s1_jni.cpp:32-37`: D3 (the callback discards `text`).
- `models/ModelDeliveryWorker.kt:116`: D3 (the closed host token).
- `asr/AsrService.kt:133,181`: D4.
- `app/src/test/java/com/envi/wispr/debug/DiagnosticsShapeTest.kt` (proposed, new), `DebugLoggerRenderTest.kt` (new).
- `docs/audits/2026-09-22-194-*.md`; the catalog's `debug-local-log` Android row (the sink is gone).

## 11. Testing
1. Classes: `DebugLoggerRenderTest` is Product Outcome for issue #194 and `kotlin-patterns.md` RULE: no-content-in-diagnostics (a logcat line is readable by anyone with the phone on a cable and by any app holding the platform's read-logs permission on a debug device). Play Data Safety governs data LEAVING the device and makes no local-log promise (`play-data-safety-answers.md` discloses the transcript's transfer when cloud polish is on; its content-free promise is about outbound telemetry); nothing there changes (coverage F6). `DiagnosticsShapeTest` (proposed) is a Drift Guard with a two-way control.
2. Reverts: §11.2.
3. Not tested: the vendor SDKs' own logging (owned by the silencer).
### 11.1 Hardware UAT spec
Emulator, wispr-eyes, debug build: one ordinary spoken take into Gmail (`dictate_emulator`) after the change; then `logcat -d` is swept for the dictated sentence's words and for `Exception:` followed by a colon-and-message shape from our tags (`DictationSession`, `AudioCapture`, `AsrService`, `PolishService`, `PasteService`): zero hits from our tags is the pass; the harness's own `insertion api=` and `Recording started` lines must still be found (the templates survived). The founder's phone: NOT RUN.
### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `DebugLoggerRenderTest.aThrowablesMessageNeverReachesTheLine` (proposed) | Product Outcome | the marker in the message and in a cause's message is absent; the class names and a frame are present | render `throwable.toString()` |
| `DebugLoggerRenderTest.noThrowableRendersTheMessageAlone` (proposed) | Drift Guard | `render("x", null) == "x"` | append a suffix |
| `DebugLoggerRenderTest.prefersTheFirstAppFrameAndFallsBackToTheTop` (proposed) | Drift Guard | a throwable whose stack-trace array is SET to `[lib.Frame.a:1, com.envi.wispr.x.Y.b:2]` renders `Y.b:2`; one set to `[lib.Frame.a:1]` renders `Frame.a:1` | take the top frame unconditionally |
| `DebugLoggerRenderTest.emptyStackRendersNoFrame` (proposed) | Drift Guard | a throwable whose stack-trace array is set to an empty array renders `no frame` | index `[0]` without the guard (throws) |
| `DebugLoggerRenderTest.causeWalkStopsAtARepeatOrFourClasses` (proposed) | Drift Guard | a self-referential cause (a two-node cause loop built with the platform's init-cause call) renders its classes once then `…`; a six-deep chain renders four class names then `…` | drop the identity set or the cap (a cycle then hangs; the row uses a timeout) |
| a `ModelSourceHost` row (grown in `DeliveryFailureReasonTest`) | Drift Guard | an unknown host renders `unknown`, never the supplied host | log `$host` again |
| `DiagnosticsShapeTest.onlyTheDiagnosticOwnerImportsAndroidLog` (proposed) | Drift Guard | one file imports `android.util.Log` | add an import elsewhere |
| `DiagnosticsShapeTest.noDiagnosticLineCarriesExceptionText` (proposed) | Drift Guard | zero forbidden shapes in any diagnostic call span; the fixture control finds all of them | reintroduce one `${e.message}` |
| `DiagnosticsShapeTest.theOwnerNeverHandsAThrowableToAndroidLog` (proposed) | Drift Guard | no three-argument `Log.e`/`Log.w`, no `stackTraceToString`, no file sink symbols in `DebugLogger.kt` | restore `Log.e(tag, message, throwable)` |
| `AudioServiceShapeTest.everyLogTemplateAndThreadNameSurvivesTheMove` (exists) | Drift Guard | the audio templates unchanged | change a template |

## 12. Blast radius & rollback
Every diagnostic line in four processes; no user-visible surface; no data. Rollback is the PR revert.

## 13. Ship criteria specific to THIS change
- Every row in §11.2 green; receipts red; the static row's two-way control found every fixture shape.
- The emulator take's logcat sweep: zero content or exception-message hits from our tags; the templates the harness reads still present.
- Codex all-clear with a confirming rerun.

## 14. Open questions
- The instrumentation tests are not run on the founder's phone (`device-testing.md` RULE: connectedAndroidTest-UNINSTALLS-the-app-so-never-point-it-at-the-daily-phone) and their migration compiles with the app's test APK (`./gradlew :app:compileDebugAndroidTestKotlin`); they are not executed in this change.
- The user-facing `failure.message` readers (`AppViewModel`, `DictionaryScreen`, `ModelDeliveryWorker`'s notification) show an exception's text ON SCREEN; that is a product-copy question (which sentence should a user see when history or the word list cannot be read), not a diagnostics one, and is not changed here; a follow-up issue is filed if Codex or the founder want it.

## 15. Related
REF-10 in `docs/audits/2026-09-20-senior-audit.md`; #176 (the typed ASR failure); `kotlin-patterns.md` RULE: no-content-in-diagnostics.
