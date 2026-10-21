# Issue #37 — Unload model after (speech model) — 2026-10-21

GitHub issue: `#37` (reopened 2026-10-21; part of `#453`). Tier: MEDIUM (service lifecycle, new runtime behaviour
on the heart path's speech process). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

`mixed_pr: true`. `Code` (settings, holder, session host, Transcription page, tests): `tests`, `codex-review`,
`hardware-uat`. `Docs/dev-tooling` (this plan, `architecture-rules.md`, knowledge): `cited-symbols`.

**PAR rows closed:** none tracked; parity with the macOS setting `macos.audio.model-unload` (catalog feature
`model-unload-policy`, Android `absent`). The catalog row moves to Android `shipped` at merge.

**Hardware UAT:** Y. On the S26 through Play internal: with the default, two dictations a minute apart: the
second take's speech service is already loaded (no `Speech engine initialized` line for it, and its stop-to-text
wait is shorter than the first's). With "Immediately", every take loads again (today's behaviour). With
"After 2 minutes", a take three minutes after the last one loads again.

## Preface — User Rubric

1. **Who.** Meera Patel, one-handed iMessage between school pickups; she dictates "running 5 late" and wants it
   gone in a second.
2. **Why.** "Why does it think for so long on a two word message?"
3. **Invocation.** Unchanged. The setting lives on the Transcription page; most people never touch it.
4. **Apps.** All; the speech model sits before insertion.
5. **Input.** "Running five minutes late." / "Yes." / "Can you grab milk." / "On my way." / "Love you, see you soon."
6. **Success.** Short messages appear almost as she stops talking.
7. **Wrong-not-broken.** The phone feels sluggish because a 700 MB model stays loaded; she blames the phone.
8. **Hack.** None today; she would type short messages instead.
9. **Control.** The macOS ladder: Never (default), Immediately, After 2, 5, 10, 15 minutes, After 1 hour.

### Cross-persona check
Priya and Aaron want speed: default never serves them. Frank and Meera on 6 GB phones may prefer memory back:
the choice is one tap away, and Android reclaims a cached process anyway. Elena: no network effect.

---

Consolidation: none. One new owner (`SpeechModelKeepAlive` (proposed), proposed) for one new behaviour; no existing owner
of speech-model residency exists to merge with (#37 2026-09-24: the unused `ModelLifecyclePolicy.kt` was
deleted in PR #303).

## 0. TL;DR

Android closes the speech model about 3 s after every take and reloads it at the next (4 to 7 s on Galaxy A35
and A15, 3.4 s on the S26). For short takes on budget phones that reload, not transcription (0.64 s warm for a
3 s take on the A35 with real-length encoding, #453), sets the wait. Port the macOS setting "Unload model
after" for the speech model, default **never** (founder 2026-10-21). An app-wide holder in the main process keeps
`AsrService` bound for the chosen time after each take whose transcript arrived, and lets go on timeout, on
"Immediately", on the setting changing, on low memory, and if `:asr` dies.

## 1. Problem
- Emulator 2026-10-20: `Speech engine initialized` at each take's bind; `AsrService destroyed` and `Speech engine
  closed` 3 s after the take (logcat, Play AVD, build from `feat/458-ultra-swap`).
- `AsrSpeedDeviceTest` 2026-10-20 (personal project, matrices `matrix-3itm29fr50b14`, `matrix-3iobq8fbftnko`):
  cold first request 6.1 to 8.8 s against 0.6 to 3.3 s warm.
- #72 decided warm-at-connect DURING a take; nothing keeps the model between takes. `EngineWarmUp` and
  `TakePolishController` comments claim "the session owner keeps both models resident after a take"; false
  since the per-take unbind, corrected here.

## 2. Goals & non-goals
### 2.1 Goals
1. A Transcription-page setting with the macOS options and labels; default never.
2. After a take whose transcript arrived, the speech model stays loaded for the chosen time.
3. "Immediately" reproduces today exactly (no extra binding).
4. Memory is released on the timer, on "Immediately", on a low-memory signal, and when `:asr` dies.
### 2.2 Non-goals
- The polish model (it already leaves with its process after each take, `PolishService`).
- Real-length or one-pass encoding (#453, separate branch).
- Showing per-option memory numbers (the macOS picker does not; a later issue if wanted).
- Keeping the model through process death: Android may kill a cached app; that is accepted.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Binding: `PipelineBindings.bind` (application context) at take start; `pipeline.unbind()` in
  `DictationSessionCoordinator.finishSession` and `destroy`; `EngineWarmUp` during setup only. No other binder.
- `DictationSessionService` stops itself after each take (`host.stopSelfNow()`), and it, its coordinator and its
  `PipelineBindings` are per Service instance, so none can hold a binding across takes. The holder must be
  process-wide in main: owned by `ModelBootstrapApplication`.
- `AsrService.onCreate` → `owner.load` → `initRecognizer`; `onDestroy` → `owner.close`. A closed
  `RecognizerOwner` never reopens, so unloading is unbinding (service destroy), never an in-service reload.
- Transcript arrival: `DictationSessionCoordinator` `SpeechListener.onResult` (after `wait.answer()`), which
  reaches `outcome.asrDone()`. That is the macOS "transcript ready" point (`RecordingSessionKernel.swift`,
  `shouldApplyUnload = transcriptReadyForDelivery`).
- Settings: `AppPreferences` (DataStore), enum precedent `BubbleLook` (`storageKey`, `fromStorage` falling back to
  `DEFAULT`); sessions freeze values in `SessionPreferences` via `SessionPreferencesSource`.

### 2. Existing authority
No owner of speech-model residency exists (grep `unload|keepAlive|residency` in `app/src/main/java`: only S1
runtime internals and comments). New authority proposed: `SpeechModelKeepAlive` (proposed).

### 3. Prior attempts
#37 closed 2026-09-24 as "no felt problem on Android"; reopened with the felt problem. #72: warm-at-connect during
a take, measured. #73 (open): releasing the speech model after transcription, the opposite lever; this setting
covers it as "Immediately". macOS: `ASRManager.noteTranscriptionComplete` (external) arms a one-shot timer; every recording
start cancels it; switching to never cancels it; cancelled, no-speech and failed takes do not re-arm.

### 4. Boundaries
- Holder in main; model in `:asr`. While the holder is bound, `:asr` takes main's importance; when main is cached
  or frozen the timer may fire late (NOT VERIFIED on device), and Android may kill either process; both are
  memory-safe outcomes.
- A take's own binding and the holder's binding coexist; the service lives while either holds it.
- `:asr` death (watchdog `killProcess`, crash): the holder must not resurrect it idle: on
  `onServiceDisconnected` (external) or `onBindingDied` (external) it unbinds.

### 5. High-risk premises
- Two bindings keep one service instance alive: standard bound-service semantics; the device run confirms by the
  absence of a second `Speech engine initialized` line.
- `RecognizerOwner` load-once with the instance kept: unchanged, because the instance is kept.

## 3. Design
1. **Setting.** `SpeechModelUnload` (proposed) (proposed enum, `settings/`): `NEVER` (default), `IMMEDIATELY`, `TWO_MINUTES` (proposed),
   `FIVE_MINUTES` (proposed), `TEN_MINUTES` (proposed), `FIFTEEN_MINUTES` (proposed), `SIXTY_MINUTES` (proposed); `storageKey` never renamed; unknown values
   fall back to the default; `holdMillis` (proposed) null for never and immediately. `AppPreferences` key
   `speech_model_unload` (proposed), field on `AppPreferencesState`, setter.
2. **Holder.** `SpeechModelKeepAlive` (proposed), in the `asr/` package, main process, created by
   `ModelBootstrapApplication` behind its main-process gate. It owns at most ONE registration to `AsrService`,
   made with `BIND_AUTO_CREATE` plus `BIND_WAIVE_PRIORITY` (external): waiving priority means the holder never raises
   `:asr` above cached importance, so when only the holder holds it Android can reclaim it under pressure; the
   take's own binding keeps a running take protected exactly as today.
   - **State, all confined to the main thread** (holder calls, the setting collector and connection callbacks):
     the current registration (connection, generation, pending or connected), the `activeTake` (proposed) id, the
     take's `succeeded` (proposed) flag, and the hold's `deadline` (proposed): none (nothing held), NEVER, or an
     elapsed-realtime instant. A timer message exists only for an instant deadline and is never the source of
     truth: when it fires it releases only if the deadline still equals the one it was scheduled for.
   - **`takeStarted(takeId)`**, called by the coordinator AFTER `pipeline.bind()` returned `BindResult.BOUND`
     (including a refused polish bind), never before it and never for an audio or speech bind failure: records
     `activeTake`, clears `succeeded`, and SUSPENDS (does not discard) any deadline by cancelling only its timer
     message. If the setting is Immediately it binds nothing; otherwise, with no registration, it binds now, while
     the take's own binding is creating or holding the service, so it never creates or resurrects `:asr` by
     itself. It never binds at any other moment (grounded r1 finding 1).
   - **`transcriptArrived(takeId)`**: for the active take only, sets `succeeded` and records the intended deadline
     NOW (NEVER, or now plus the hold time), so the countdown starts at the accepted transcript as on macOS
     (`noteTranscriptionComplete`), not after polish and insertion.
   - **`takeEnded(takeId)`**, from the coordinator's finish and destroy paths before `pipeline.unbind()`;
     idempotent, and a no-op for an id that is not `activeTake` (a stale posted finish from another service
     instance cannot touch the current take):
     - succeeded and a registration exists (pending or connected): the deadline recorded at the transcript is
       applied (release now if it has already passed); a pending registration keeps the decision and is released
       if it later dies or is refused;
     - not succeeded: the suspended deadline is restored; release now if it has passed; if there was none,
       release;
     - Immediately (current setting): release.
   - Each registration has its own `ServiceConnection` (external) object and generation number; a callback from an
     older connection never touches a newer one. Every `bindService` (external) result is handled: `false` or an
     exception calls `unbindService` (external) on that connection (Android requires it) and holds nothing.
   - `settingChanged(value)`: live, from `AppPreferences.state` collected in an application-owned main-process
     scope, mapped to this field with `distinctUntilChanged` (other settings never re-arm). Default never before
     the first read and on a read failure. Immediately: release now. Never: cancel the timer, keep any hold. A
     timed value: re-arm from now if holding.
   - `Application.onTrimMemory` (external): below API 34, release at `TRIM_MEMORY_RUNNING_CRITICAL` (external) and
     `TRIM_MEMORY_COMPLETE` (external); never at `TRIM_MEMORY_UI_HIDDEN` (external) or
     `TRIM_MEMORY_BACKGROUND` (external), because the app is backgrounded during almost every dictation. From API 34 those callbacks are
     not delivered; with the priority waived, Android's reclaiming of cached processes is the release path.
     **Never therefore means: kept until Android ends the app's processes or the user changes the setting.**
   - Release frees only the holder's registration; the model closes when every client has unbound and queued
     native work finishes (`AsrService.onDestroy`, `RecognizerOwner.close`).

   **ServiceConnection callbacks, enumerated (pre-committed consequence of grounded r1):**

   | Event on the holder's connection | Holder does |
   |---|---|
   | `onServiceConnected` (external), current generation | mark connected; any decision made while pending (deadline) already stands, nothing re-binds |
   | `onServiceConnected`, older generation | `unbindService` on that connection |
   | `onServiceDisconnected` (external) (`:asr` died) | unbind and forget: release; the next take binds afresh |
   | `onBindingDied` (external) | same as disconnected |
   | `onNullBinding` (external) | unbind and hold nothing (today `AsrService.onBind` never returns null) |
   | `bindService` returns false or throws | `unbindService` on that connection, hold nothing |

   A transient platform restart of `:asr` between its death and the holder's `onServiceDisconnected` is NOT
   VERIFIED and is checked in the emulator run (kill `:asr` with the holder alone, with a take running, and
   during setup).

3. **Trigger.** `SessionHost` gains `speechModelTakeStarted` (proposed), `speechModelUsed` (proposed) and
   `speechModelTakeEnded` (proposed), each carrying the take id, implemented by `DictationSessionService` against
   the application's holder. The coordinator calls them in `bindPipelineServices` after `BindResult.BOUND`, after
   `outcome.asrDone()` for a non-blank accepted result, and in the finish and destroy paths. An accepted non-blank result counts once per take, including setup practice takes and takes
   whose polish or insertion later fails or is interrupted; blank, duplicate and late results (`wait.answer()`
   false) do not. Legacy `transcribe`/`transcribeFile` clients of `AsrService` are outside this trigger (no app
   caller found; population NOT VERIFIED beyond `app/src/main`).
4. **UI.** Transcription page, new group "Speech model" under the model card: a radio list with the macOS labels
   ("Never", "Immediately", "After 2 minutes", "After 5 minutes", "After 10 minutes", "After 15 minutes",
   "After 1 hour"), title "Unload model after", line "Frees memory when you are not dictating." and, for
   Immediately, "Expect a reload delay on each dictation." (macOS copy, "recording" made "dictation").
5. **Rules.** In a take that uses LOCAL polish, the speech model is already resident while `:polish` loads S1
   (`PipelineBindings` binds both; `TakePolishController` warms polish; the speech binding lasts until
   `finishSession`; #72 measured `:asr` 1.5 to 1.9 GB PSS with `:polish` 1.58 GB on the S26). A retained speech model
   adds IDLE residency and does not create a new simultaneous-heavy state; whether the measured peak is unchanged
   is NOT VERIFIED and is read in the UAT (`dumpsys meminfo` of both processes during a second take). RULE
   isolate-limbs keeps its #72 wording; the no-idle-cost exception below names this setting.
   `architecture-rules.md` RULE no-idle-cost gains: "Exception (founder 2026-10-21, #37): the speech
   model stays resident for the user's 'Unload model after' choice, default never; nothing else stays resident."
   The stale residency sentence in `EngineWarmUp` is corrected; `TakePolishController`'s comment is accurate (the speech model stays loaded after transcription, within the take) and stays.

**Rejected.** (a) `START_STICKY` (external) or a started `AsrService`: background-service limits stop it within about a minute
and it would survive the user's choice. (b) Holding the binding in `DictationSessionService`: it stops after each
take. (c) An in-service unload/reload: `RecognizerOwner` never reopens after close.

## 3b. Ownership
`SpeechModelKeepAlive` (proposed) lives in main because only main can bind across takes and the per-take owners stop; its
alternative, `:asr` keeping itself alive, would need a started service that Android stops in the background.

## 4. Contract deltas
- New preference `speech_model_unload` (proposed) (string enum, default never).
- `SessionHost.speechModelUsed` (proposed): "a take's transcript arrived with text; residency may be extended".
- `AsrService` gains a second, long-lived client in main; its own behaviour is unchanged.

## 5. State and lifecycle audit

| Population | Enumerated |
|---|---|
| Take endings | transcript with text: arms; empty transcript, cancel, ASR failure, capture failure, destroy: no call. |
| Holder states | unbound; bound with timer; bound without timer (never). Transitions only from the five calls in §3.2. |
| `:asr` death | watchdog kill, crash, low-memory kill: `onServiceDisconnected` (external) → release; next take binds as today. |
| Main death | binding dropped by Android; `:asr` service destroyed; next start: unbound (no persisted residency). |
| Setting change mid-take | applies on the next `transcriptArrived` (proposed) or immediately via `settingChanged` (proposed); the take is unaffected. |
| Setup | `EngineWarmUp` unchanged; its practice takes call the same trigger, which is the user dictating. |
| Blocked phone (#449) | no takes run, so no trigger. |

## 6. Consumer matrix

| Delta | Consumer | Current | Required | Change | Verified by |
|---|---|---|---|---|---|
| setting | `AppPreferences`, Transcription page | none | enum + row | yes | `AppPreferencesStateTest`, unit |
| trigger | coordinator `onResult` → `SessionHost` | none | call on text | yes | coordinator test with the rig |
| holder | `ModelBootstrapApplication` | none | create, onTrimMemory | yes | holder unit tests with a fake binder |
| shape tests | `SessionOwnerShapeTest`, `AsrServiceShapeTest`, `WarmUpOffMainTest` | pinned | unchanged service; host gains a method | update `DictationSessionRig`'s `SessionHost` fake; re-read each for residency assumptions | tests |
| preference consumers | `AppPreferencesState` equality and defaults, `AppPreferencesStateTest`, `AppLaunchFacts` | n/a | new field with default never; no launch-fact property added | yes | tests |
| backup | `AndroidManifest.xml` | `allowBackup` off | none | none | manifest read |

## 7. Failure modes

| Failure | User sees | Retry |
|---|---|---|
| bind refused | nothing; next take loads as today | next transcript |
| `:asr` dies while held | nothing; next take loads | automatic |
| low memory | nothing; next take loads | automatic |
| timer late (cached main) | model stays longer, Android may kill it | none needed |

## 8. Signals
Log lines in main: "Speech model kept loaded: <setting>" and "Speech model released: <reason>"; no telemetry
property added (no user content either way).

## 9. Fallbacks
Every failure falls back to today's behaviour: the next take binds and loads.

## 10. Files
`settings/SpeechModelUnload.kt` (new), `settings/AppPreferences.kt`, `asr/SpeechModelKeepAlive.kt` (new),
`models/ModelBootstrapApplication.kt`, `ui/SessionHost.kt`, `ui/DictationSessionService.kt`,
`ui/DictationSessionCoordinator.kt`, `ui/TranscriptionScreen.kt`, `ui/AppViewModel.kt` (setter),
`ui/EngineWarmUp.kt` and `ui/TakePolishController.kt` (comments), test rigs and tests;
`.claude/rules/architecture-rules.md`; catalog row at merge.

## 11. Testing
1. Classes: holder tests and the trigger test are Product Outcome ("the next dictation waits for a reload" /
   "memory is never released"); the enum and preference tests are Drift Guards.
2. Reverts: drop the trigger call → coordinator test red; drop `release()` on disconnect → holder test red; make
   `IMMEDIATELY` bind → holder test red.
3. Not tested: Android's cached-process freezer timing (platform-owned).

### 11.1 Hardware UAT
Emulator first (Play AVD): two takes 30 s apart with the default: the second has no `Speech engine initialized`
line; set "Immediately": each take initializes; set "After 2 minutes", wait 2.5 minutes: `Speech model released:
timer` and `Speech engine closed`. Then the S26 via Play internal, the founder's pass.

### 11.2 Obligations

| Test | Class | Proves | Revert |
|---|---|---|---|
| `SpeechModelUnloadTest` (proposed) | Drift | labels, storage keys, default never, unknown → never, hold times | change a key |
| `SpeechModelKeepAliveTest` (proposed) | Product | bind once after BOUND only, re-arm, Never has no timer, Immediately never binds, release on timer/disconnect/trim/setting, A succeeds then B fails restores A's deadline, stale end is a no-op, success while pending keeps the decision, a hold of 2 minutes with polish ending 20 s after the transcript expires 120 s after the transcript | drop a branch |
| coordinator trigger test | Product | called once on text; not on empty, cancel, failure | drop the call |

## 12. Blast radius & rollback
Touched: settings, a new main-process holder, the session host, one coordinator line, the Transcription page.
Not touched: `AsrService`, recipe, model files, polish, insertion, AIDL. Rollback: revert; the next take loads
as today.

## 13. Ship criteria
- [ ] Back-to-back dictations on the S26 skip the reload with the default.
- [ ] "Immediately" behaves exactly like today.

## Grounded r3 disposition (PROCEED-WITH-REVISIONS)
Adopted: the deadline is recorded at the accepted transcript and applied at the matched take end (§3.2), with the
delayed-polish case in the test obligations.

## Grounded r2 disposition (PROCEED-WITH-REVISIONS)
1 adopted: suspended deadline restored when a later take fails (§3.2 `takeEnded`). 2 adopted: acquire only after
`BindResult.BOUND`, never for Immediately, take ids, idempotent and matched ends, main-thread confinement. 3
adopted: pending is not dead; the end-of-take decision stands for a pending registration.

## Grounded r1 disposition (PIVOT)
1 adopted: the holder binds only at take start, never at result arrival (§3.2), plus the enumerated callback
table (pre-committed consequence). 2 adopted: per-generation connections, cleanup on refused binds. 3 adopted:
`BIND_WAIVE_PRIORITY` (external), API-34 trim reality, and Never stated as kept until Android ends the app. 4 adopted: overlap
limited to local-polish takes, peak equality marked NOT VERIFIED, the accurate polish comment kept.

## Coverage r1 disposition
All eight gaps adopted: 1 trigger boundary (§3.3); 2 pending-bind state and generation (§3.2); 3 death and
surviving clients (§3.2, UAT kills `:asr` with the holder alone, with a take, and in setup); 4 release is not
immediate model close (§3.2); 5 trim levels by API, not UI_HIDDEN/BACKGROUND (§3.2); 6 peak memory and
isolate-limbs (§3.5); 7 live setting producer and failure (§3.2); 8 consumers and tests (§6).

## 14. Open questions
1. Founder (2026-10-21 asked that placement be decided as part of this): where the setting lives. Candidates:
   (a) Transcription page, a "Speech model" group under the model card (recommended: the macOS picker sits with
   its speech engine, `SpeechEngineSettingsView` (external); a user tuning speech speed is already on this page);
   (b) the Storage page beside the model sizes (the trade is memory);
   (c) an advanced or developer area (hidden from most users).
   §3.4 is written for (a); (b) or (c) changes only the row's file, not the behaviour.
The default is settled: never (founder 2026-10-21).

## 15. Related
#37, #453, #72, #73, #458; macOS `macos.audio.model-unload`.
