# Issue #186 — The dictation owner is too large to change safely — 2026-09-20

GitHub issue: `#186`. Tier: LARGE. Status: APPROVED (Gate 2, 2026-09-20, founder standing approval of the audit refactors; Codex grounded review G5 PROCEED-AS-PLANNED after G1 to G4 revisions, all adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/main/java/com/envi/wispr/ui/**`, `app/src/test/**`, `app/src/androidTest/**`)

**PAR rows closed:** none. This is an internal extraction; no outcome row changes.

**Hardware UAT:** Y. The session owner is the heart path end to end (trigger, capture, ASR, finalization,
insertion). Success on the S26: the founder double-presses the side button in Chrome's address bar, says
"open the weather for tomorrow", the pill spins then shows the level rail, he presses again, the text lands
in the address bar once, exactly as on build 144 today. Then the same from the lips bubble into Gmail,
then a cancel mid-take, then a take with the earbuds. Nothing may look or feel different.

## Preface — User Rubric

User Rubric: N/A — this change moves code between files and introduces no user-visible behaviour change;
every sentence the user reads, every haptic, every History row and every insertion route is produced by
the same statements in the same order, with one declared internal deviation (§3.1: pipeline binding
ownership moves from the Service context to `applicationContext`). The rubric's persona questions have no new answer. What the user gets
is indirect: the fixes queued behind this change (#192, #193, #115) become small, reviewable diffs on a
testable owner instead of edits inside a 1,937-line service.

---

## 0. TL;DR

`ui/DictationSessionService.kt` is 1,937 lines and owns the Android lifecycle, command parsing, bubble
admission, preference collection, three binder connections, the take state machine, three worker
threads, the ASR and polish callbacks, publication, History writes, insertion handoff, cancel paths and
teardown. Nothing in it can be tested without an Android `Service`. This change splits it along
responsibility lines into a Service that owns the Android lifecycle, commands, foreground state and the
composition of the concrete Android adapters and an `internal` `DictationSessionCoordinator`
(proposed) that owns the take state machine and every decision, reached only through Kotlin interfaces
that a JVM test can fake. No user-visible behaviour changes. Statements retain their order except for one declared
internal deviation: pipeline binding ownership moves from the Service context to `applicationContext`,
allowing deferred destroyed-session cleanup to unbind without invoking a dead Service; the emulator and
physical-device validation cover that boundary. The diff is otherwise a move, not a rewrite. Evidence: the unit suite green with the twelve source-reading drift guards repointed, a new
JVM coordinator test that walks every terminal path of the state machine, `assembleDebug`, the emulator
UAT, Codex to all-clear, and the founder's phone pass on the four takes above.

## 1. Problem

Measured 2026-09-20 on `01f441e`:

```
$ wc -l app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
1937
```

The senior audit (`docs/audits/2026-09-20-senior-audit.md`, REF-03) graded Architecture C and named this
file as the worst violation of `architecture-rules.md` RULE: keep-central-types-thin: it has no
one-sentence purpose, it holds state plus orchestration plus business rules, and it constructs its own
repositories, database, provider configuration, language detector, handlers, threads and binders, so
the owner cannot be tested as one unit. Three audit fixes (REF-01 #192, REF-02 #193, REF-04 #115) each
edit this file's heart path; each would today be a diff inside a file nobody can exercise off the phone.

Concretely, the file today contains (line ranges on `01f441e`):

| Responsibility | Lines | Android-bound? |
|---|---|---|
| Companion: actions, `sendCommand` | 111-164 | yes (`Intent`, `ContextCompat`) |
| State, take fields, locks, ledger | 166-317 | no |
| Repositories, `lazy` construction | 319-323 | yes (`applicationContext`) |
| Three `ServiceConnection`s | 325-386 | yes |
| `onCreate`: detector, stale-row recovery, two collectors | 388-433 | yes |
| `onStartCommand`: foreground, bubble admission, dispatch | 435-525 | yes (`Intent`) |
| `beginSession`, `bindPipelineServices` | 531-627 | mixed |
| `tryStartRecording`, `waitForLive`, `publishLive` | 629-779 | `Looper` check, `Thread` |
| `startPolling`, `startMeter`, notices | 781-959 | `Toast` |
| `stopAndTranscribe` and the ASR callback | 961-1078 | `IAsrCallback.Stub` |
| `polishAndPublish`, fallback, ledger, watchdog | 1080-1239 | `IPolishCallback.Stub` |
| `publishResult`, History, insertion, clipboard | 1247-1507 | `ClipboardManager`, `Toast` |
| Cancel paths, error paths, `finishSession` | 1509-1698 | `Toast` |
| Foreground, draft status, discard, unbind | 1700-1811 | yes |
| `HapticCue`, `vibrate` | 1824-1859 | `Vibrator` |
| `onDestroy` | 1861-1936 | yes |

## 2. Goals & non-goals

### 2.1 Goals

1. `DictationSessionService` (kept) is the Android adapter only, with a one-sentence purpose, holding five
   fields and no state transition or decision about a take; its line count is reported, not gated.
2. `DictationSessionCoordinator` (proposed) owns the state machine, the arbiter, the take facts and every
   transition, and is constructible on the JVM with fakes for every Android and binder dependency.
3. A JVM suite covers every REPRODUCIBLE terminal of the state machine and every HYPOTHETICAL terminal
   whose failure would be silent, asserting the `TerminalReason` (kept) each one commits plus the History
   status and the insertion route it writes; loud hypothetical dependency failures are recorded as known
   limits with their producing site (coverage F6).
4. Every statement that runs today runs in the same order after the change, except the one declared
   internal deviation (§3.1, binding through `applicationContext`). The diff is reviewable as a move: the
   reviewer can pair each moved block with its origin.
5. The twelve drift guards that read the service source keep pinning the same statements, in their new
   file.

### 2.2 Non-goals

- No user-visible behaviour change, and no internal one beyond the declared binding-context deviation
  (§3.1). REF-01 (#192: the launcher's pre-command pin), REF-02 (#193: settings gate the take), REF-04
  (#115: `runBlocking` in `onDestroy`) and REF-05 (#187: the meter thread) are edits to the extracted owner
  and ship afterwards, each on its own branch.
- No package move. The design target names a `dictation/` package; the coordinator and its helpers stay
  in `ui/` beside `TakeArbiter`, `TerminalReason`, `TakeNotices`, `PolishRequestLedger`,
  `PolishWatchdogBudget`, `TriggerSource` and `CaptureNotices`. Moving all of them is one axis
  (packaging) and is routed to a follow-up issue so this change stays one axis (extraction).
- No change to the AIDL surface, the manifest, `VoiceInputActivity`, the bubble, the tile or the
  notification.
- No new abstraction over `Telemetry`: its methods already return early when telemetry is off, so a JVM
  test runs them as no-ops (`telemetry/Telemetry.kt:152-175`, verified below).

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

The subject is a dictation take. Its path today, with the file it runs in and the thread:

1. **Command in.** Six surfaces call `DictationSessionService.sendCommand` (`ui/DictationSessionService.kt:153`),
   which starts the service with an action, a bubble token and a trigger source. Android delivers it to
   `onStartCommand` (`:435`) on the main thread. Found with
   `grep -rln "DictationSessionService\.\(sendCommand\|ACTION\)" app/src/main/java`, five files:
   `ui/VoiceInputActivity.kt` (the side button and the launcher), `paste/RecordingAccessibilityOverlay.kt`
   (the pill's buttons), `paste/PasteAccessibilityService.kt` (the bubble), `shortcuts/DictationTileService.kt`,
   `shortcuts/DictationNotificationController.kt`. `ui/SettingsActivity.kt` (the app's own button) reaches the owner by
   starting `VoiceInputActivity`. None of these change.
2. **Admission.** `admitBubbleCommand` (`:482`) resolves a bubble token against `BubbleRequests`
   (`shortcuts/BubbleRequests.kt`); `beginSession` (`:531`) does the IDLE→STARTING CAS, mints `takeId`,
   builds `TakeFacts` and a fresh `TakeArbiter`, pins the target through
   `PasteAccessibilityService.pinTargetForDictation` (`paste/PasteAccessibilityService.kt:182`), shows the
   starting shape through `RecordingOverlayState.showStarting` (`shortcuts/RecordingOverlayState.kt:93`),
   promotes to foreground, then launches a coroutine that awaits the two readiness deferreds, freezes
   `SessionPreferences` (`:590`) and binds the three services (`:604`).
3. **Capture.** `audioConnection.onServiceConnected` (`:326`) calls `tryStartRecording` (`:629`), which calls
   `IAudioCaptureService.startCaptureForTake` over the binder and starts `LiveWaiter` (`:652`). The waiter
   polls `liveState` and posts `publishLive` (`:721`) to the main thread, which does the STARTING→RECORDING
   CAS under `publishLock`, creates the draft History row through `TranscriptRepository.insert`
   (`:742`), shows the pill, and starts `DictationPollingThread` (`:781`) and `DictationMeterThread` (`:869`).
4. **Stop.** A STOP command or the polling thread's terminal read calls `stopAndTranscribe` (`:961`), which
   does RECORDING→PROCESSING under `publishLock`, then on `TranscribeThread` stops capture, reads the file
   facts, and calls `IAsrService.transcribeFileForTake` with an `IAsrCallback.Stub` (`:1032`).
5. **Polish.** `onResult` calls `polishAndPublish` (`:1080`) on the binder thread; it opens the
   `PolishRequestLedger` under `polishSubmissionLock`, arms the watchdog, and calls
   `IPolishService.polishRequestForTake` with an `IPolishCallback.Stub` (`:1129`). Every outcome path lands in
   `publishResult` (`:1247`) or `publishFallback` (`:1194`).
6. **Publication.** `publishResult` writes the polish facts, reserves the arbiter, saves History
   (`transcriptRepository.finalize`, `:1292`), commits `COMPLETED`, then hands the text to
   `PasteAccessibilityService.pasteWhenTargetReturns` (`paste/PasteAccessibilityService.kt:159`) or falls
   back to the clipboard and announces (`:1355-1394`), then `finishSession` (`:1678`).
7. **Teardown.** `finishSession` joins pending History jobs, then on the main thread unbinds, stops
   foreground and `stopSelf`. `onDestroy` (`:1861`) handles the interrupted case.

Every hop between 2 and 7 is inside the one file, so the proposed interception point (the coordinator)
observes every payload on every path by construction: the code moves; the hops do not change.

### 2. Find the existing authority before proposing one

Capability searched: "a session owner or coordinator that is not the Service".

```
$ grep -rn "Coordinator\|SessionOwner\|SessionMachine\|DictationSession\b" app/src/main/java --include=*.kt
(no hits outside ui/DictationSessionService.kt)
$ grep -n "Coordinator" docs/enviouswispr-android-architecture.md | head -3
25:                 DictationCoordinator
51:The coordinator exposes one immutable `DictationSessionState` (external) as a `StateFlow`. ...
```

`DictationCoordinator` (external) and `DictationSessionState` exist only in the target design document
(`.claude/knowledge/architecture.md` says so explicitly). **New authority proposed:** the coordinator.

Existing primitives the coordinator keeps using unchanged (none is wrapped): `TakeArbiter`,
`PolishRequestLedger`, `PolishWatchdogBudget`, `TakeNotices`, `CaptureNotices`, `BluetoothTipGate`,
`TriggerSource`, `TerminalReason`, `TakeFacts`, `BubbleRequests`, `InsertionJudgement`,
`FallbackAnnouncement`, `HistoryPublicationPolicy`, `PolishPublicationFacts`, `PolishFallback`,
`SpeechEvidence`, `CaptureEnding`, `TextSafety`, `StructuredTermRestorer`.

The seams that exist today and are the precedent for the new ones: `ProviderPolishClient` takes
`logInfo`/`logWarn` lambdas so a JVM test never touches `android.util.Log`
(`providers/ProviderPolishClient.kt:157-158`); `PasteAccessibilityService.callOnMain` delegates to
`MainThreadHandoff` (`code-gotchas.md` RULE: the-handoff-timeout-must-abandon-the-work-not-race-it);
`LanguageDetector` is a `fun interface` (`cleanup/CleanupLanguage.kt:33`) with `MlKitLanguageDetector` as
the Android implementation.

### 3. Read prior attempts and live direction

- The issue (#186) carries the audit's fix shape: coordinator, pipeline bindings, preferences provider,
  publication and teardown types; moved logic deleted from the Service in the same change; no forwarding
  shim. The issue's wording leaves "binder connections" with the Service; this plan departs from that on
  one point (G4 D1): the Service retains lifecycle, command parsing, foreground state and concrete-adapter
  composition, and `PipelineBindings` owns the binder connections.
- Session log: no previous attempt to split this file. Every entry that touches it added to it (#26,
  #69, #75, #135, #141, #176).
- `architecture-rules.md` RULE: one-owner-for-the-session: "When the coordinator replaces today's session
  service, it lands in one change — old code removed, new code wired, no shim." Binding.
- `architecture-rules.md` RULE: keep-central-types-thin names this file as a standing extraction target.
- Catalog: no decision row on the Android session owner's shape (`sqlite3 ... "SELECT decision_text FROM
  decision WHERE decision_text LIKE '%coordinator%'"` returns nothing on 2026-09-20).
- The audit's own REF-03 estimate was 260 LOC; the honest count from the table in §1 is that about
  1,500 lines move and about 400 new lines of seams and adapters appear. The estimate was wrong; the
  shape was right.

### 4. Lifecycle, trust and process boundaries

| Boundary | Today | After |
|---|---|---|
| Service instance per take | The service `stopSelf`s after every take; the next command creates a fresh instance whose fields start at their initialisers (`finishSession` comment, `:1690`). | The coordinator is created in `onCreate` and dies with the instance. Same lifetime, same reset. |
| Main thread | `onStartCommand` arrives on main; `publishLive` asserts main; `mainHandler.post` marshals publication and teardown work to main. | The coordinator receives commands from the Service on main; `publishLive` asserts `host.onMainThread()` (proposed); `host.postToMain` (proposed) is the same `Handler(Looper.getMainLooper())`. |
| Binder threads | ASR and polish callbacks arrive on binder threads and call into the state machine directly. | Unchanged; the adapters in `PipelineBindings` forward on the same thread. |
| Worker threads | `LiveWaiter`, `DictationPollingThread`, `TranscribeThread`, `DictationMeterThread`, `StartCaptureFailureCleanup`, `DestroyedSessionCleanup`. | Unchanged, created by the coordinator with the same names. |
| `:audio`, `:asr`, `:polish` death | `ServiceConnection.onServiceDisconnected` on main. | `PipelineBindings` forwards to `PipelineController.Listener` on main. |
| Accessibility service alive vs enabled-but-crashed | `PasteAccessibilityService.isBound` read at three sites. | `InsertionGateway.isBound` reads the same `StateFlow`. |
| Process death of the main process mid-take | `onDestroy` marks the row interrupted synchronously with `runBlocking`. | Unchanged in this change (REF-04 owns it). |
| Stale completion | `TakeArbiter` first-wins; `PolishRequestLedger` claim; `takeSerial` on the meter. | Unchanged objects, unchanged sites. |

### 5. Prove the high-risk premises

**P1. The JVM rig cannot touch Android.** `app/build.gradle.kts` declares no `testOptions.unitTests` (external)
block and no Robolectric dependency:

```
$ grep -n "testOptions\|isReturnDefaultValues\|robolectric" app/build.gradle.kts
(no output)
```

So `android.util.Log`, `Handler`, `Looper`, `SystemClock`, `Toast`, `Binder` (external) and `Parcel` throw
`RuntimeException("Method ... not mocked")` in a unit test. Consequence: the coordinator cannot construct
`IAsrCallback.Stub` or `IPolishCallback.Stub` (both extend `android.os.Binder`) on a path a JVM test
executes, so the binder proxies are wrapped by Kotlin interfaces (§3) and the Stubs are built in the
adapters.

**P2. Which helpers are pure.** `grep -n "^import android"` on `TakeArbiter.kt`, `PolishRequestLedger.kt`,
`TerminalReason.kt`, `TakeNotices.kt`, `CaptureNotices.kt`, `TriggerSource.kt`, `TakeFacts.kt`,
`SpeechEvidence.kt`, `InsertionOutcomeMessages.kt`, `DictationTargetPin.kt`, `BubbleRequests.kt`,
`HistoryPublicationPolicy.kt`, `PolishPublicationFacts.kt`, `PolishFallback.kt`, `TelemetryChannels.kt`
returns nothing. `PolishPolicy.kt` and `PolishOutcome.kt` import `android.os.Parcel` but only in
`writeToParcel`; constructing them touches no Android class (`PolishWatchdogBudgetTest` constructs
`PolishPolicy` today).

**P3. `Telemetry` is a no-op off-device.** `Telemetry.capture` returns when `!postHogOn`
(`telemetry/Telemetry.kt:153`), `breadcrumb` and `defect` return when `!sentryOn` (`:162`, `:175`),
`takeStarted`/`takeEnded` only touch an `AtomicReference` before their guard (`:193-206`), `journal` is
null before bootstrap (`:94`). No seam needed.

**P4. `RecordingOverlayState` and `DebugLogger` are not.** `RecordingOverlayState` builds a
`Handler(Looper.getMainLooper())` in its object initialiser (`shortcuts/RecordingOverlayState.kt:68`);
`DebugLogger.log` calls `Log.i` unguarded (`debug/DebugLogger.kt:128`). Both need seams.

**P5. `PasteAccessibilityService`'s companion is the only insertion door the owner uses.**

```
$ grep -n "PasteAccessibilityService\." app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
555: targetPinAtStart = PasteAccessibilityService.pinTargetForDictation()
557: ... PasteAccessibilityService.pinnedFieldId() ...
945: if (PasteAccessibilityService.isBound.value) {
1087: PasteAccessibilityService.releasePinnedTarget()
1339: PasteAccessibilityService.pasteWhenTargetReturns(
1350: PasteAccessibilityService.releasePinnedTarget()
1492: serviceBound = PasteAccessibilityService.isBound.value,
1525: PasteAccessibilityService.releasePinnedTarget()
1586: PasteAccessibilityService.releasePinnedTarget()
1665: PasteAccessibilityService.releasePinnedTarget()
```

Five members: `pinTargetForDictation`, `pinnedFieldId`, `isBound`, `releasePinnedTarget`,
`pasteWhenTargetReturns`. The `InsertionGateway` (proposed) has exactly those five.

**P6. Twelve unit tests read the service as text** (`grep -rln "DictationSessionService" app/src/test/java`
then reading each): `audio/LiveAudioMeterWiringTest` (2 assertions), `audio/LiveGateWiringTest` (7),
`audio/RecordingCapWiringTest` (10), `audio/SilenceStopWiringTest` (7),
`insertion/InsertionOutcomeMessagesTest` (4), `paste/AutoPasteWiringTest` (23),
`polish/DeterministicFallbackTest` (4), `paste/LipsBubbleWiringTest` (20), `ui/CaptureNoticesTest` (8),
`ui/PolishPublicationRoutesTest` (3), `settings/SilenceStopSettingsTest` (6), `ui/TakeNoticesTest` (1).
`paste/RecorderBrandTest` and `telemetry/SentryPayloadTest` mention the class name in other files' text
or as a literal and do not read the service source. Each of the twelve is repointed in §10.

**P7. The Codex problem-only consult** (`docs/audits/2026-09-20-186-consult.md`, run before §3, seven
questions answered with `file:line`) is folded into §3, §5 and §14 where it named a trap; its answers are
cited there as `consult Q<n>`. Its ten traps, each with the plan's answer: duplicated `publishLock` (one
field, §3.1); ledger split from its lock (one field, §3.1); a fake overlay hides `RecordingAccessibilityOverlay`
and `OnboardingViewModel` as readers (they are covered by the emulator and phone pass, §11.3); an
always-posting main seam differs from `Main.immediate` (`mainDispatcher` (proposed), §3.1); non-volatile binder proxies (the old
non-volatile proxies are replaced by `@Volatile` link fields because the wrapper adds another publication
hop, §3.1; the former §14 deferral is closed); volatile `facts` reference with mutable members (moves whole, unchanged); a per-session
Bluetooth gate (process default, §3.1); callback Stubs are Binders (links, §3.1); the preference source needs
a Context (built objects injected, §3.1); twelve source-pinning tests (§10).

## 3. Design

### 3.1 The shape

Ten new `internal` files in `com.envi.wispr.ui`, plus the trimmed Service.

**`SessionHost`** (proposed), an interface the Service implements. Every Android call the moved code
makes through `this` as a `Context`, one method each, no logic:
`promoteToForeground(processing: Boolean)`, `updateSurfacePhase(phase: DictationSurfaceState.Phase)`,
`vibrate(cue: HapticCue)`, `toastFromService(line: String)` and `toastFromApplication(line: String)` (G1 D2:
today's four toasts use two different contexts, the Service at `:1283`, `:1480`, `:1668` and
`applicationContext` at `:956`; one method each keeps both), `showPolishNotice(notice: PolishNotice)`,
`copyToClipboard(text: String): Boolean`, `autoPasteAvailability(): AutoPasteAvailability`,
`removeForegroundAndDismiss()` (proposed) and `stopSelfNow()` (proposed) (G2 D2: two methods, not one, because
`finishSession:1683-1696` resets `admittedRequest` and `stopAfterRecording` and hides the surface BETWEEN the
notification dismissal and `stopSelf`, and one bundled method would move those resets to one side),
`postToMain(runnable)`, `onMainThread(): Boolean`, `elapsedRealtimeMs(): Long`.
`postToMain` is a contract, not a convenience: it is the Service's one `Handler(Looper.getMainLooper())`,
FIFO on the main looper, because six sites rely on that order (consult Q2: the live publication, the
polish notice before persistence, two toasts, the post-History teardown, the destroyed-capture unbind).
All three `Dispatchers.Main.immediate` sites, today's `:571`, `:588` and `:954` (G1 D4 counted them), use the
injected `mainDispatcher: CoroutineDispatcher`; production passes `Dispatchers.Main.immediate`, so nothing
changes on the phone. The JVM fixture uses a custom single-thread dispatcher whose `isDispatchNeeded` (external) returns
false on its owner thread (so it runs inline there, as `Main.immediate` does on main), while the fake
`postToMain` always enqueues on that same thread (as a `Handler` does). One test enters `publishLive` through
`postToMain`, reaches `sayAfterRecording` while already on that thread, and proves the `mainDispatcher` work
runs inline before the posted method continues; that sequence exists on the phone when `publishLive` is
posted at `:689` and an unbound insertion surface routes a notice through `launch(Main.immediate)` at
`:944-958`. `kotlinx-coroutines-test` is not a dependency and is not added.

**`RecorderSurface` (proposed)**, an interface over `RecordingOverlayState`'s eleven members the owner uses
(`showStarting`, `nameTarget`, `attachTranscript`, `show`, `showProcessing`, `showNotice`,
`updateElapsed`, `updateBands`, `hide`, `currentTakeSerial` (proposed), and `emptyBands` (proposed) returning
`RecordingOverlayState.NO_BANDS`, coverage F2: the meter's throwing-read fallback at `:875` reads that constant,
and touching the object initialises its `Handler`). One `object` implementation delegating
to `RecordingOverlayState`.

**`InsertionGateway`** (proposed), an interface over the five `PasteAccessibilityService` companion members
(P5). One `object` implementation delegating.

**`SessionLog`** (proposed), an interface over `DebugLogger`'s `log`, `warn`, `error`, `mark`,
`pipelineSummary`. One `object` implementation delegating.

**`PipelineController` (proposed)**, the coordinator-facing interface declared beside `PipelineBindings` (G2 D1):
nullable `capture`, `speech` and `polish` links plus `bind(listener)`, `unbind()`, `postUnbindToMain()` (proposed) and
`stopAudioService()` (proposed), and its own nested `Listener` (`onCaptureConnected` (proposed), `onCaptureDisconnected` (proposed), `onSpeechConnected` (proposed),
`onSpeechDisconnected` (proposed), `onPolishConnected` (proposed), `onPolishDisconnected` (proposed)) so the coordinator
implements `PipelineController.Listener` and never names the Android implementation (G3 D1). The two connected
events carry today's `:344-371` statements: `onSpeechConnected` logs "Speech service connected";
`onPolishConnected` runs `runCatching { pipeline.polish?.warmUpWithPolicy(sessionPreferences.policy) }` then
logs "Polish service connected" (G4 D3). `PipelineBindings` assigns the corresponding `@Volatile` link BEFORE
invoking any connected callback, synchronously on the platform's connection thread, exactly as the fields are
assigned before use today at `:327`, `:346`, `:366`. `PipelineBindings` implements it, constructed with
`applicationContext` and the Service's existing `mainHandler`; `postUnbindToMain()` is exactly
`mainHandler.post { unbind() }`. `DestroyedSessionCleanup` calls `postUnbindToMain()` after capture cleanup,
preserving the thread of today's `:1927`; the normal `finishSession` tail calls the synchronous `unbind()`
because it already runs inside the existing main-thread post (G3 D4). Both paths keep their current thread
and order without capturing the destroyed Service or `SessionHost`. The coordinator constructor takes
`pipeline: PipelineController` and calls `pipeline.bind(this)`; tests provide a fake controller. Binding,
unbinding and stopping the audio service are therefore NOT `SessionHost` methods.

**`CaptureLink` (proposed), `SpeechLink` (proposed), `PolishLink`** (proposed), Kotlin interfaces mirroring exactly the
`IAudioCaptureService`, `IAsrService` and `IPolishService` members the owner calls today (P1). The
production implementations wrap the AIDL proxy and build the `IAsrCallback.Stub` / `IPolishCallback.Stub`,
forwarding to a Kotlin listener (consult Q6: both Stubs extend `android.os.Binder` and call
`attachInterface` (external) in their constructor, so they cannot be built on a path a JVM test runs). Each speech or
polish request constructs one fresh anonymous callback Stub synchronously inside that adapter invocation,
after every argument has been evaluated and immediately before the proxy call; the adapter neither caches
the Stub nor stores the listener; every Stub callback invokes its Kotlin listener directly, with no coroutine
launch, executor, Handler or post; Stub-construction and proxy-call exceptions escape the adapter into the
caller's existing catch block (G1 D3: this keeps the ledger-open-then-watchdog-then-Stub order of
`:1103-1129`, the catch at `:1177-1179`, and the binder-thread identity `:1099-1102` relies on). The three
link fields on `PipelineBindings` are `@Volatile` (G1 D3: the proxies are non-volatile today at `:244-249`
and rely on incidental publication; a new field behind one more indirection does not inherit that
accident, so the new fields are declared volatile and the §14 note is closed). They live in **`PipelineBindings`** (proposed), which owns the three
`ServiceConnection`s and bound flags and calls the `PipelineController.Listener` (implemented by the
coordinator) on connect/disconnect, on the same thread the platform delivers them. It is constructed with
`applicationContext` and uses that same context for every `bindService`, `unbindService` and `stopService`
(G1 D5: today `onDestroy` lets `DestroyedSessionCleanup` post `unbindPipelineServices` after the Service has
returned from `onDestroy`, `:1920-1928`, which Android defines as a dead Service; binding through the
application context makes the late unbind legal instead of accidental). **This is the one declared deviation
from "no behaviour change"**: the binding's owning context moves from the Service to the application. It is
disclosed here and in the change summary (`architecture-rules.md` FACT: the-laws, "shortcuts must be
declared"). `DestroyedSessionCleanup` captures only the `CaptureLink`, the `PipelineController` and immutable
cleanup data; it never captures or invokes `PipelineBindings`, `SessionHost` or the Service after `onDestroy`
returns (G4 D1).

**`SessionPreferencesSource`** (proposed), constructed by the Service with the two FLOWS
(`AppPreferences.authoritativeState`, `CustomTermRepository.observeTerms()`) and the migration as a
`suspend () -> Unit` (consult Q5: the collectors need a `Context` today at `:399` and `:417`; the Service
supplies the built flows instead, and a JVM test feeds its own, built 2026-09-20): the two `onCreate`
collectors (`observeTerms`, `AppPreferences.authoritativeState`), the legacy custom-term migration that precedes the first, the
eight `@Volatile` preference fields, the two readiness deferreds, `SessionPreferences` (moved, still a
private-to-the-owner data class made `internal`), `suspend fun awaitReady(timeoutMs): Boolean` and
`fun freeze(termsSnapshot, matcher, policy): SessionPreferences`, which reads only `cleanupOptions` and
`clipboardPolicy ?: ClipboardInsertionPolicy()` live and takes the terms and the matcher as arguments, because
`beginSession` snapshots the terms BEFORE compiling the matcher (`:578-581`) and a `freeze` that re-read the
terms would change behaviour if the collector emitted in between (G1 D6). `start(scope)` calls `scope.launch`
twice and creates no scope, dispatcher, `SupervisorJob` or detached job of its own, so `destroy()`'s cancel
of the injected job cancels both collectors exactly as today (G1 D6). It exposes `clipboardPolicy: ClipboardInsertionPolicy?`
because `promoteToForeground` reads the live nullable field on purpose (`:1711` comment).

**`DictationSessionCoordinator`** (proposed): every other line. Constructor:
`(host: SessionHost, surface: RecorderSurface, insertion: InsertionGateway, log: SessionLog,
preferences: SessionPreferencesSource, transcripts: TranscriptRepository, languageDetector: LanguageDetector,
loadPolicy: suspend () -> PolishPolicy, pipeline: PipelineController, scope: CoroutineScope,
mainDispatcher: CoroutineDispatcher, polishTimeout: PolishTimeout = DelayPolishTimeout,
settingsWaitMs: Long = SETTINGS_WAIT_MS, tipGate: BluetoothTipGate = BluetoothTipGate.PROCESS,
polishLedger: PolishRequestLedger = PolishRequestLedger(), endingSink: (TakeFacts, TerminalReason) -> Unit)`
(built 2026-09-20: `settingsWaitMs` is the same kind of deadline seam as `PolishTimeout`, so the
settings-never-ready row runs in 200 ms instead of 10 s; `polishLedger` is injected because the shared
`PolishRequestIdSource` mints ids off `SystemClock`, which throws on the JVM; production passes neither)
(G2 D7: `PolishTimeout` (proposed) and its production `DelayPolishTimeout` (proposed), which delegates to
`delay(PolishWatchdogBudget.forPolicy(policy))`, are chunk 1 foundations so chunk 2 compiles against the
final design; chunk 3 adds only the fake).
The injected `scope` is the one `serviceScope`; `serviceJob` is read once as
`scope.coroutineContext[Job]` and never constructed separately, and `destroy()` cancels and joins that exact
job (coverage F3). `tipGate` (proposed) defaults to the process-scoped gate on purpose: its once-per-process allowance must outlive the
Service instance (consult Q3, `CaptureNotices.kt:40-45`), so a coordinator never owns one. The locks and
first-wins objects (`state`, `arbiter`, `publishLock`, `polishSubmissionLock`, `polishLedger`, `draftId`,
`teardownStarted`, `pendingHistoryUpdates`, `serviceJob`) are each ONE field on the coordinator, declared
once, never copied into a helper (consult Q1: `TakeArbiter` compares tokens by identity; `publishLock` joins
live publication, stop, both cancels, start failure and destruction; the ledger and its submission lock
close the cancel-before-register window). Every `@Volatile` keeps its annotation on the field it moves with. Public surface: `handleCommand(action, request, trigger)` (proposed),
`onCreated()` (proposed) (stale-row recovery and the preference source start), `destroy()` (today's `onDestroy` body),
`isProcessing` (proposed) (for the foreground promotion decision in `onStartCommand`), `implements PipelineController.Listener`.

**`HapticCue`** (moved) becomes a top-level `internal enum class` so both the coordinator (which names the
cue) and the Service (which fires it) see it.

The `finishSession` tail runs, in this exact order, on the main thread as today (G2 D2):
`pipeline.unbind()`, `host.removeForegroundAndDismiss()`, `admittedRequest = null`, `stopAfterRecording = false`,
`surface.hide()`, `host.stopSelfNow()`.

### 3.2 What the Service keeps

Companion reduced to the action strings, the extras and `sendCommand` (coverage F1: `SILENCE_UNAVAILABLE_NOTICE`,
`DURATION_WARNING_NOTICE`, `DURATION_REACHED_NOTICE`, `METER_INTERVAL_MS` and `JOURNAL_ADMISSION_DEADLINE_MS`
are used only by logic that moves, so they move to the coordinator's companion; `DebugSessionLog` (proposed)
owns the private `"DictationSession"` tag, so moved code never retains or duplicates `TAG`), `onCreate` (build `MlKitLanguageDetector`, the repositories,
the preferences source, the bindings, the coordinator; call `coordinator.onCreated()` (proposed)), `onStartCommand`
(read extras; `promoteToForeground` when the extra says so, exactly as today; `coordinator.handleCommand`),
`onBind`, the `SessionHost` implementation (each method one or two lines of Android), `promoteToForeground`
(moved verbatim, it builds the notification), `vibrate` (moved verbatim), `onDestroy`
(`languageDetector.close()`, `coordinator.destroy()`, `stopForeground`, dismiss, `super`). Teardown order is
fixed and matches `:1861-1935` today (G1 D5): close `languageDetector`; under `publishLock`, invalidate
STARTING/RECORDING, hide the surface, interrupt the arbiter; close the polish ledger; resolve the draft id
while its creation job is still alive; cancel and join the injected service job; write `interrupted`; start
the capture cleanup thread or unbind synchronously; then the Service calls `stopForeground`, dismisses the
notification and calls `super.onDestroy`. `onDestroy` checks `::coordinator.isInitialized` before calling
`destroy()`; this is a composition-failure guard only, and after a completed `onCreate` it executes the same call
in the same order (Codex code review C1). The `runBlocking` calls in that sequence are a known rule conflict
carried unchanged (`kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread); #115 owns their removal;
this extraction neither legitimises nor expands them (G1 D9).

### 3.3 Why interfaces and not `open` classes or `isReturnDefaultValues` (external)

- `isReturnDefaultValues = true` would make `Handler.post` return false and never run the runnable, so a
  JVM test would pass with publication work silently dropped (`validation-discipline.md` FACT:
  silent-empty-traps, plausible-value traps). Rejected.
- Robolectric would make the whole service constructible, at the cost of a 30 s per-class startup, a new
  dependency, and tests that exercise Android's own code paths as if they were ours. Rejected for this
  change; it may be right for the Compose rig (#48, #95) later.
- Subclassing `RecordingOverlayState` is impossible (it is an `object`), and a settable static on
  `PasteAccessibilityService` is a test seam on a guard (`validation-discipline.md` RULE:
  a-test-seam-on-a-GUARD-is-a-bypass). An interface the production code implements with a delegating
  object is neither.

### 3.4 Alternatives rejected

- **A `TakeRecord` (proposed) value holding all per-take fields** (takeId, facts, arbiter, draftId, rawTranscript,
  durations, labels, pin) instead of twenty `@Volatile` fields. Better shape, but it changes the
  memory-visibility story of every field at once and the reviewer could not pair blocks to origins. It is
  the right second step once the coordinator has a test; recorded in §14.
- **Coordinator as a `StateFlow<DictationSessionState>` publisher** as the design document sketches.
  That is a behaviour change to every surface (they read `RecordingOverlayState` and
  `DictationSurfaceState` today). Out of scope.
- **Splitting `publishResult` into a `SessionPublication` (proposed) type now**, as the audit suggested. It reads and
  writes eleven take fields; extracting it before those fields have one owner would add a second
  visibility contract. Same follow-up as the first bullet.

### 3.5 Build chunks (coverage F7)

**Chunk 1, compile-only foundations.** Add the interfaces, the links, `PipelineController`, the delegating
Android implementations, `PipelineBindings`, `SessionPreferencesSource`, `HapticCue`, `PolishTimeout` with
`DelayPolishTimeout`, and their focused tests,
without changing the active Service path: the Service still owns the session, so the app builds and every
phone entry point stays usable. Unit suite, `assembleDebug`.

**Chunk 2, atomic ownership cutover.** Add the whole coordinator, move the state and orchestration blocks,
wire exactly one coordinator from `onCreate`, trim the Service, and repoint all twelve drift guards in the
same commit; no commit leaves a half-owned state machine. Unit suite, `assembleDebug`, then an emulator
start, stop, cancel and insertion smoke pass through `wispr-eyes` before the chunk ends.

**Chunk 2** also wires `polishTimeout.await(policy)` at the watchdog site when the coordinator moves.

**Chunk 3, coverage and shape guards.** Add only the fake timeout, the coordinator terminal cases, the
known-limit row, `SessionOwnerShapeTest` (proposed) and the revert receipts. No production source changes. Full unit suite
and the emulator smoke pass again, leaving the branch ready for the review gate and the one hardware run.

## 3b. Ownership justification

The coordinator will live in `com.envi.wispr.ui` beside `TakeArbiter` and `TerminalReason` because that is
where every helper it delegates to already lives, so the move is one axis; the alternative was the design
document's `dictation/` package, but that adds a package move (twelve drift guards, imports in six
surfaces) to a change whose diff must read as a move of the same statements, and it is routed to a
follow-up (§14).

## 4. Contract deltas

| Type | Delta | What it now means to consumers |
|---|---|---|
| `DictationSessionService` | Loses every private member below `onStartCommand` except `promoteToForeground` and `vibrate`; gains the `SessionHost` implementation. | To the six surfaces: nothing; the companion and the actions are byte-identical. To Android: the same lifecycle callbacks, and the three helper bindings now owned by the application context (§3.1). |
| `DictationSessionCoordinator` (proposed) | New. | The one place a take's state changes. Callable from main (commands), binder threads (callbacks via the links) and its own workers, exactly as the service was. |
| `SessionHost` (proposed) | New. | The Service's promise to the coordinator: each method is one Android call with no decision in it. |
| `RecorderSurface`, `InsertionGateway`, `SessionLog` (proposed) | New. | Delegating views of three singletons. Production has one implementation each; tests fake them. |
| `CaptureLink`, `SpeechLink`, `PolishLink` (proposed) | New. | The subset of each AIDL interface the owner uses, as Kotlin. A binder exception propagates through them unchanged, so every `runCatching` in the moved code keeps its meaning. |
| `PipelineBindings` (proposed) | New. | Owns bind/unbind and the three connections; the coordinator never sees a `ServiceConnection`. |
| `SessionPreferencesSource` (proposed) | New. | Owns the two collectors and the readiness signals; `freeze` builds the same `SessionPreferences` from the same fields. |
| `HapticCue` | `private enum class` inside the service → top-level `internal enum class`. | Same three members, same values. |
| `SessionPreferences` | `private data class` inside the service → `internal data class` in `SessionPreferencesSource.kt`. | Same five fields, same defaults. |

## 5. End-to-end state and lifecycle audit

Population: the seven `SessionState` members × the commands and callbacks that read them. Every `when`
and CAS on `state` moves unchanged; the table lists each transition site and confirms its new home.

| Transition | Site today | New home | Lock/guard preserved |
|---|---|---|---|
| IDLE→STARTING | `beginSession:532` CAS | coordinator `beginSession` | none today, none after |
| STARTING→RECORDING | `publishLive:724` CAS under `publishLock` | coordinator `publishLive` | `publishLock` is a coordinator field |
| STARTING→CANCELLING | `cancelStarting:1597` CAS under `publishLock` | coordinator | same |
| STARTING→ERROR | `failWhileStarting:1651` CAS under `publishLock` + `commitNow` | coordinator | same |
| RECORDING→PROCESSING | `stopAndTranscribe:965` CAS under `publishLock` | coordinator | same |
| RECORDING→CANCELLING | `cancelRecording:1511` CAS under `publishLock` | coordinator | same |
| PROCESSING→CANCELLING | `cancelProcessing:1580` set under `polishSubmissionLock` after `reserve` | coordinator | `polishSubmissionLock` is a coordinator field |
| any→ERROR | `endAsFailure:1639` set | coordinator | arbiter commit precedes, as today |
| any→FINISHING | `finishSession:1679` `getAndSet` | coordinator | same |
| STARTING/RECORDING→ERROR on destroy | `onDestroy:1867` set under `publishLock` | coordinator `destroy` | same |
| polish request opening (no state change) | `polishAndPublish:1103` under `polishSubmissionLock` | coordinator | the same lock `cancelProcessing:1574` takes |
| pending History snapshot (no state change) | `finishSession:1683` synchronized on `pendingHistoryUpdates` | coordinator | the one list |
| destroyed-capture cleanup claim (no state change) | `onDestroy:1920` CAS on `teardownStarted` | coordinator `destroy` | the one flag |

Enumerated, none found: a state read or write outside the moved code. `grep -n "state\." ui/DictationSessionService.kt`
lists 32 sites; all 32 are in blocks that move to the coordinator.

Interrupted / Deleted / Mutated / Concurrent / Absent / Stale, per `code-design-rules.md` RULE:
async-edge-case-enumeration: each is answered today by a named guard (`TakeArbiter` for concurrent and
stale endings, `PolishRequestLedger` for a stale polish outcome, `takeSerial` for a stale meter reading,
`teardownStarted` for a double destroy cleanup, `pendingHistoryUpdates.joinAll` for a History write racing
teardown, `draftCreation` for the absent draft id). Every one is a field or object that moves whole to the
coordinator; none is split across the seam. Consult Q1 confirms no field's identity or `@Volatile` depends
on being declared on a `Service`.

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `DictationSessionService` companion | six surfaces (§2.5.1) | send actions | unchanged | no | `LipsBubbleWiringTest`, `RecorderBrandTest` still assert the action names in the surfaces |
| `RecordingOverlayState` writes | `paste/RecordingAccessibilityOverlay.kt` (renders), `ui/OnboardingViewModel.kt` (judges target and row identity, `:181-186`, `:222-239`) | render snapshots, judge the practice take | unchanged, same calls in same order | no | `CaptureNoticesTest` (repointed), emulator UAT including onboarding practice |
| `DictationSurfaceState` writes | `shortcuts/DictationTileService.kt`, notification | render phase | unchanged | no | emulator UAT: tile shows Listening/Processing |
| `PasteAccessibilityService` companion | the currently bound `PasteAccessibilityService` instance (the companion forwards to it, `paste/PasteAccessibilityService.kt:159-199`) | pin, paste, release | unchanged through `InsertionGateway` | no | `AutoPasteWiringTest` (repointed), phone pass |
| `TranscriptRepository` | History screen, recovery | rows written | unchanged, same calls | no | coordinator test asserts the status sequence on a fake DAO |
| `Telemetry` | PostHog, Sentry, and `telemetry/SentryBootstrap.kt:95-100` which reads the live take id `takeStarted`/`takeEnded` write | events per take, take tag on errors | unchanged | no | `SentryPayloadTest` unchanged; breadcrumbs read on the phone pass logcat |
| Foreground and polish notifications | Android and System UI; their actions route back to the Service's own actions | shown, tapped | unchanged | no | notification shade on the emulator |
| Clipboard fallback write | Android's clipboard and the user's destination app | the words, pasteable | unchanged | no | emulator UAT with the accessibility service disabled |
| Drift guards (P6) | twelve unit tests | read the service source | read the coordinator source | yes, path only | the suite is green |
| `DictationNotificationController` | the service | builds notifications with `clipboardPolicy` | unchanged, reads `preferences.clipboardPolicy` | no | notification text on the emulator |

## 7. Failure-mode × caller table

The change introduces no new failure mode. The population is today's `TerminalReason` members (33 on
`01f441e`, `ui/TerminalReason.kt:35-95`); each is committed at one site that moves whole. The rows below
are the failure modes the extraction itself could introduce.

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| A seam method throws where the static did not (e.g. the delegating object is not yet initialised) | new `object` implementations | coordinator | same as a static throwing today: the surrounding `runCatching` | none | none; the objects are stateless delegators, initialised on first touch |
| `host.postToMain` posts to a different looper than `mainHandler` did | `SessionHost` impl | `publishLive`, announcements, `finishSession` | would break the ordering comments rely on | none | prevented by construction: the Service keeps the same `Handler(Looper.getMainLooper())` field and `postToMain` is `mainHandler.post` |
| A `Stub` built in the adapter outlives the request | `PipelineBindings` adapters | binder | a late callback reaching a finished take | none; the ledger and arbiter refuse it exactly as they do today | none |
| A drift guard pins a line that no longer exists | test | CI | red suite | none | repoint, never delete (§10) |

## 8. Caller-visible signals audit

| Signal | Carried by | Reader | Change |
|---|---|---|---|
| `clipboardPolicy == null` means "not yet read" | `SessionPreferencesSource.clipboardPolicy` | the listening notification via `promoteToForeground` | not present in this change: the field moves, the nullability and the comment move with it |
| `targetPinAtStart == NO_TARGET` before any session | coordinator field | `InsertionJudgement.handoffToJudge` | not present in this change |
| `takeSerial` on the meter | `RecorderSurface.currentTakeSerial()` | the meter thread | the read moves behind the interface; same value |
| `PasteAccessibilityService.isBound` | `InsertionGateway.isBound` | notices, fallback announcement | same `StateFlow` value |
| `draftId == 0L` means no row yet | coordinator field | status updates, teardown | not present in this change |

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| polish fails | `deterministicFallback(rawText, prefs)` | `PolishFallback.deterministic` with the take's `LanguageDetector` | unchanged: the same call, the detector now injected | text non-blank | raw transcript (`publishResult:1272`) | History, insertion |
| History save fails | clipboard | `HistoryPublicationPolicy.route` | unchanged | copied | announce | user |

No fallback changes; the table exists to state that.

## 10. File-by-file changes

New files, all `internal`, all in `app/src/main/java/com/envi/wispr/ui/`:

| File | Contents | Origin lines |
|---|---|---|
| `SessionHost.kt` | the interface (§3.1) | new |
| `RecorderSurface.kt` | interface + `object OverlayRecorderSurface` | new |
| `InsertionGateway.kt` | interface + `object AccessibilityInsertionGateway` | new |
| `SessionLog.kt` | interface + `object DebugSessionLog` | new |
| `PipelineLinks.kt` | `CaptureLink`, `SpeechLink`, `PolishLink`, `SpeechListener` (proposed), `PolishListener` (proposed), `PipelineController` | new; member list = the AIDL members called on `01f441e` |
| `PolishTimeout.kt` | `PolishTimeout`, `DelayPolishTimeout` | new; delegates to `delay(PolishWatchdogBudget.forPolicy(policy))` (`:1112`) |
| `PipelineBindings.kt` | connections, bound flags, the `PipelineController` implementation, the three adapter classes that build the Stubs | 325-386, 604-627 (bind), 1800-1811 (unbind), 1032-1069 and 1129-1175 (the Stub bodies become adapter forwarding) |
| `SessionPreferencesSource.kt` | fields, collectors, readiness, `SessionPreferences`, `freeze` | 177-184, 270-299, 316-317, 322, 396-432 |
| `HapticCue.kt` | the enum | 1824-1837 |
| `DictationSessionCoordinator.kt` | everything else | 166-269, 300-315, 482-602, 629-1078 (minus the Stub shells), 1080-1798, 1861-1936 |

`DictationSessionService.kt` after: companion (the actions, the extras and `sendCommand` from 111-164; the five constants move with their users, coverage F1), `onCreate` wiring, `onStartCommand` (435-470 with
the `when` dispatch replaced by `coordinator.handleCommand`; the bubble admission moves to the coordinator
because it reads `state` and writes `admittedRequest`), `onBind`, `SessionHost` methods,
`promoteToForeground` (1700-1723), `vibrate` (1839-1859), `onDestroy` (the five statements in §3.2). Its line count is
measured at the end and reported, never cited as a target.

Tests repointed (P6), each by changing the path it reads and nothing else unless a pinned statement
gained an indirection:

| Test | Pins | Repoint to |
|---|---|---|
| `LiveAudioMeterWiringTest` | `startMeter`, `METER_INTERVAL_MS` | coordinator |
| `LiveGateWiringTest` | `waitForLive`, `publishLive`, `LIVE_WAIT_BOUND_MS`, the connections, the preference writes | coordinator + `PipelineBindings` + `SessionPreferencesSource` |
| `RecordingCapWiringTest` | duration notices, `RecordingLimits` reads | coordinator |
| `SilenceStopWiringTest` | `autoStopOnSilence` freeze, notice | preferences source + coordinator |
| `InsertionOutcomeMessagesTest` | announcement sites, the nullable clipboard policy, the listening notification | coordinator + `SessionPreferencesSource` + Service |
| `AutoPasteWiringTest` | `autoPasteAvailability`, `pasteWhenTargetReturns` call shape, `clipboardPolicy` nullability, the haptic gate | coordinator + `SessionPreferencesSource` + Service (`promoteToForeground`) + `HapticCue.kt` |
| `DeterministicFallbackTest` | `deterministicFallback` body, the detector's construction | coordinator + Service (`onCreate` builds `MlKitLanguageDetector`) |
| `LipsBubbleWiringTest` | `admitBubbleCommand`, `stopAfterRecording` | coordinator |
| `CaptureNoticesTest` | forced notice, tip gate | coordinator |
| `PolishPublicationRoutesTest` | the eight `publishFallback` producers | coordinator |
| `SilenceStopSettingsTest` | collector writes, capture arguments, notices and toast routing | `SessionPreferencesSource` + coordinator + Service (`toast`) |
| `TakeNoticesTest` | `showError` uses `TakeNotices.line` | coordinator |

Each repointed test may read several sources, and it must retain every statement it pins today or replace
that statement with the seam-equivalent assertion, named in the test's KDoc (coverage F5).

New tests (§11): `ui/DictationSessionCoordinatorTest.kt` (proposed) and `ui/SessionOwnerShapeTest.kt` (proposed).

## 11. Testing

1. **Class.** `DictationSessionCoordinatorTest` (proposed) is a Harness Contract suite (G1 D9: its links are fakes, so it
   proves the owner's decisions against the fakes, never that audio, speech, polish or insertion crossed a real
   boundary; `testing-philosophy.md` RULE: the-heart-crosses-a-real-boundary-at-least-once). Product Outcome
   coverage stays where it is today: the emulator and S26 heart-path runs in §11.1 and the device tests. The
   suite is still worth its cost because a wrong decision in the owner (a cancel that inserts, a failure with
   no sentence, a row left open) is exactly what the fakes can stage and the phone cannot stage on demand.
   `SessionOwnerShapeTest` (proposed) is a Drift Guard: it pins the Service's field set and the absence of the
   five tokens a state machine cannot exist without, and reports the line count without enforcing it. The twelve repointed tests keep
   their existing classes.
2. **Revert that turns it red.** For the coordinator test: reorder any CAS and its guard (e.g. move the
   `arbiter.reserve` in `cancelRecording` outside `publishLock`) and the concurrent cancel-versus-stop row
   fails; remove `discardDraft` from the empty-transcript path and the History row assertion fails. For
   the shape test: paste one `state.compareAndSet` back into the Service. Each revert is performed once
   during the build and its red run recorded in the validation folder.
3. **Deliberately not tested on the JVM.** Binder death delivery (`onServiceDisconnected` timing), the
   real accessibility insertion, the real microphone, foreground-service promotion. Those are the phone
   pass (§11.1) and the existing device tests.

**Two seams the concurrent rows need (G1 D7).** The polish watchdog waits 15 or 35 s in production
(`PolishWatchdogBudget.kt`), so the coordinator takes the `PolishTimeout` `fun interface { suspend fun
await(policy: PolishPolicy) }` from §3.1; production's `DelayPolishTimeout` delegates to
`delay(PolishWatchdogBudget.forPolicy(policy))` and the fake releases from a bounded latch. Concurrent rows use latch-gated link and repository fakes: the test waits
with a deadline until the request is registered or persistence begins, performs the cancel or destroy,
releases the gate, then awaits the coordinator's terminal signal through `endingSink` (proposed). Expected terminal
reasons, text, statuses, notices and routes are literals; they are never computed with `SpeechEvidence`,
`TakeNotices`, `PolishFallback` or `HistoryPublicationPolicy` (`validation-discipline.md` RULE:
an-expectation-built-with-the-mechanism-under-test-cannot-fail).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path.
- **Recipe:** `device-testing.md` RULE: drive-the-phone-with-wispr-eyes, the four takes in the Preface,
  driven through `wispr-eyes` on the emulator first (Gmail compose, the paste fixture), then by the
  founder on the S26 through Play.
- **Expected observation:** the editor's own text holds the dictated sentence exactly once after each
  take; the cancel take leaves the editor unchanged and History without a row; the earbud take names the
  earbuds on the History card. Oracle: the editor text read through the harness, not a log line.
- **Phone state to restore afterwards:** none changed.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `DictationSessionCoordinatorTest.completedTakeInsertsOnce` (proposed) | Harness Contract | start→live→stop→ASR→polish→History→handoff commits `COMPLETED` once | drop the `arbiter.commit(publication, COMPLETED)` |
| `...cancelWhileStartingLeavesNoRow` | Harness Contract | `CANCELLED_STARTING`, draft discarded, capture stopped | delete `arbiter.commit(cancel, cancelled)` in `cancelCaptureAndFinish` |
| `...cancelWhileRecordingLeavesNoRow` | Harness Contract | `CANCELLED_RECORDING` | skip `discardDraft` |
| `...cancelWhileProcessingBeatsLatePolish` | Harness Contract | a polish outcome after cancel does not publish, and the exact open request id reached `PolishLink.cancel` | delete the `arbiter.commit(cancel, CANCELLED_PROCESSING)` in `cancelProcessing` (deleting only `cancelOpenPolishRequest()` there stays green: `finishSession` runs the same idempotent backstop first, measured 2026-09-20) |
| `...audioDiedWhileRecordingEndsAsFailure` | Harness Contract | `AUDIO_PROCESS_DIED`, sentence shown, draft discarded | restrict the audio-disconnected branch to STARTING only |
| `...asrDiedWithRawTextFallsBack` | Harness Contract | `SERVICE_DIED` fallback publishes the deterministic text | route to `endAsFailure` instead |
| `...silenceLeavesNothing` | Harness Contract | empty ASR text with a low peak → `NO_SPEECH`, no row, target released | keep the row |
| `...audibleNonSpeechLeavesNothing` | Harness Contract | empty ASR text with a high peak → `ASR_EMPTY_DESPITE_AUDIO` | classify as silence |
| `...unmeasuredPeakLeavesNothing` | Harness Contract | empty ASR text, peak unreadable → `ASR_EMPTY_UNMEASURED` | classify as silence |
| `...refusedEarbudsEndsStarting` | Harness Contract | capture reports `START_FAILURE_EARBUDS` → `CAPTURE_START_EARBUDS_REFUSED` with its sentence | generic reason |
| `...captureStartFailureEndsStarting` | Harness Contract | `startCaptureForTake` false → `TakeNotices.startFailureReason` | change `started != true` to `started == null` |
| `...captureEndedBeforeLiveEndsStarting` | Harness Contract | capture stops during the live wait → `CAPTURE_ENDED_BEFORE_LIVE` | replace the reason with `START_EXCEPTION` |
| `...midTakeCaptureFailureEndsWithSentence` | Harness Contract | polling reads a `Failure` ending → `CAPTURE_FAILED_MID_TAKE`, draft discarded | transcribe |
| `...asrNotReadyEndsProcessing` | Harness Contract | stop with no speech binder → `ASR_NOT_READY`, row `asr_error` | delete `showError(ASR_NOT_READY)` |
| `...destroyWhileStartingMarksInterrupted` | Harness Contract | `INTERRUPTED_STARTING` | skip the interrupt |
| `...destroyWhileRecordingMarksInterrupted` | Harness Contract | `INTERRUPTED_RECORDING`, row `interrupted` | skip the status write |
| `...destroyWhileCancellingMarksInterrupted` | Harness Contract (latch-gated) | `INTERRUPTED_CANCELLING` | skip the interrupt |
| `...settingsNeverReadyEndsStartingWithSentence` | Harness Contract | today's `SETTINGS_UNAVAILABLE` behaviour (pins the bug #193 will change) | delete `showError(SETTINGS_UNAVAILABLE)` |
| `...watchdogFallsBackAndCancelsOnEngine` | Harness Contract | watchdog claim → `cancel(id)` on the engine → deterministic text | skip the cancel |
| `...historySaveFailureCopiesToClipboard` | Harness Contract | `COPY_ONLY` route → clipboard → announcement | route to auto-insert |
| `...destroyWhileProcessingMarksInterrupted` | Harness Contract (latch-gated) | `INTERRUPTED_PROCESSING`, row `interrupted` | skip the status write |
| `...mainImmediateRunsInlineOnTheOwnerThread` | Harness Contract | the §3.1 inline-versus-posted sequence | make the fixture dispatcher always dispatch |
| unreachable, not a row | none | `FINAL_TEXT_EMPTY` at `:1276`: blank raw text exits at `:1082-1089` before polish, and both `publishResult` callers come from a non-blank raw path, so `finalText` is never blank (G1 D7). Recorded as a defensive branch. | n/a |
| known limit, not tested on the JVM | none | the loud hypothetical reasons: the three bind failures (`bindPipelineServices:604-627`), `START_EXCEPTION` (`:663`), `LIVE_WAIT_DEADLINE` (`:709`), `CAPTURE_STILL_RUNNING_AFTER_STOP` (`:816`), both unsafe-close reasons (`:981`, `:1544`), `AUDIO_FILE_MISSING` (`:1020`), `ASR_FAILED` (`:1051`, `:1063`), `ASR_CALLBACK_EXCEPTION` (`:1075`), `ASR_PROCESS_DIED` and `POLISH_PROCESS_DIED` with no raw text (`:356`, `:380`). Each shows a sentence from `TakeNotices`, so a regression is loud (`testing-philosophy.md` RULE: dont-test-what-cannot-happen). | n/a |
| `SessionOwnerShapeTest.serviceOwnsOnlyItsAdapters` (proposed) | Drift Guard | the Service's instance fields are exactly `mainHandler`, `languageDetector`, `preferences`, `bindings` (proposed), `coordinator` (proposed); its source contains no `synchronized(`, `compareAndSet(`, `TakeArbiter`, `PolishRequestLedger` or `TerminalReason` token (G1 D8: a token check on `SessionState` alone is evaded by a rename, and a line ceiling fails on comments; the field set and the five tokens are what a state machine cannot exist without) | add one field or one token |
| `SessionOwnerShapeTest.serviceLineCountIsReported` (proposed) | Drift Guard (report only) | prints the line count into the test output; it is a metric, not a threshold | n/a |

## 12. Blast radius & rollback

- Touched: `app/src/main/java/com/envi/wispr/ui/` (one file trimmed, ten added), the twelve tests
  named in §10 (path repoints), two new tests.
- Not touched: AIDL, manifest, every surface that sends commands, `paste/`, `audio/`, `asr/`, `polish/`,
  `history/`, `settings/`, `shortcuts/`, `telemetry/`.
- Rollback: `git revert` of the squash commit restores the single file; no schema, no persisted format,
  no AIDL transaction changes, so a rollback build installs over the new one cleanly.

## 13. Ship criteria specific to THIS change

- [ ] The four takes in the Preface behave on the S26 exactly as on build 144.
- [ ] `DictationSessionCoordinatorTest` walks every terminal path in §11.2 and each revert listed was
      seen red once.
- [ ] The Service's instance fields are exactly the five adapters and its source carries none of the five state-machine tokens; its line count is printed, not gated.
- [ ] Every one of the twelve repointed drift guards asserts the same statements it asserted on
      `01f441e`, in the new file.

## 14. Open questions

Routed, not blocking:

- Package move of the whole dictation domain to `dictation/` (design target): new issue after this ships.
- `TakeRecord` per-take value replacing the volatile fields, and `SessionPublication` as its own type:
  new issue after this ships, with the coordinator test as its safety net.
- The line count of the Service is reported by `SessionOwnerShapeTest`, never gated (G1 D8); the macOS
  precedent of a LOC ceiling on `AppState` (external) failed on comment lines.

## 15. Related

#186 (this), #192 (REF-01), #193 (REF-02), #115 (REF-04), #187 (REF-05), #188 (REF-11);
`docs/audits/2026-09-20-senior-audit.md`; `architecture-rules.md` RULE: keep-central-types-thin and
RULE: one-owner-for-the-session; `docs/enviouswispr-android-architecture.md` (the `dictation/` target).

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas, or N/A with a specific reason
- [x] §2.5 grounded in real code before §3 was written, never the reverse
- [x] §4-9 answered, briefly or in full, none struck through
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it
