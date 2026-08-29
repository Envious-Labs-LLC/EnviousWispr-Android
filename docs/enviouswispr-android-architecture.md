# EnviousWispr Android Architecture

## Decision

Build one offline-first Android product with one dictation heart and several native control surfaces. The main app, floating recording overlay, Quick Settings tile, persistent notification, and Samsung side-button app shortcut all send commands to the same session coordinator. EnviousWispr never replaces the user's keyboard. None of those surfaces owns recording, transcription, cleanup, polish, history, or insertion logic.

This design targets the outcomes in `docs/enviouswispr-android-parity-spec.md`, which is pinned to macOS `main` at `544dae8be7ff07e7c1d7a5c2656d353711aaeed2`. The design is not evidence that those contracts are wired or shipped.

## Product boundaries

- Audio is local. It never leaves the phone.
- Offline transcription and deterministic cleanup work without an account or network.
- Text reaches a cloud polish provider only after the user selects it, enters a personal key, and sees the privacy boundary.
- Recording, transcription, safe text finalization, and insertion are the heart. Every optional feature fails open to the last successful text.
- App UI state survives rotation. User data and active recovery state survive process death.
- Heavy models are never loaded in the app UI process and are not left resident without a visible reason.
- Model files live in app-private storage. The prototype's broad external-storage permission is removed after migration.

## Runtime shape

```text
App / Side button / Overlay / Tile / Notification
                         |
                         v
                 DictationCoordinator
       +-----------------+------------------+
       |                 |                  |
       v                 v                  v
  AudioEngine       TranscriptPipeline   InsertionEngine
       |          ASR -> cleanup -> polish     |
       |                 |                     |
       +-------> Recovery + History <----------+
                         |
                         v
                 ReadinessRepository
```

### Process policy

| Process | Owns | Lifetime |
|---|---|---|
| Main app | Compose UI, repositories, Room, DataStore, coordinator facade | Only while a visible surface or active session needs it |
| `:audio` | microphone routing, pre-roll, VAD, PCM spool | Foreground only while capturing or explicitly warm |
| `:asr` | selected ASR adapter and streaming/final decode | Bound while warm or decoding, then unload by policy |
| `:polish` | S1-mini and safe local polish | Bound only while warm or polishing, then unload by policy |

Cloud polish uses cancellable repository calls from the main process. Keys stay behind an Android Keystore-backed secret store and never enter logs, Room, DataStore, saved state, or intents.

## Dictation state machine

The coordinator exposes one immutable `DictationSessionState` as a `StateFlow`. Commands are serialized through one coroutine scope and every state transition is recorded with a monotonic session ID.

```text
Idle
  -> Preparing
  -> Recording <-> LockedHandsFree
  -> FinalizingAudio
  -> Transcribing
  -> Cleaning
  -> Polishing
  -> ReadyToInsert
  -> Inserting
  -> Completed

Any active state -> Cancelling -> Cancelled
Any limb failure -> last successful text -> ReadyToInsert
Fatal heart failure -> RecoverableError with captured audio retained
```

Rules:

- Only one session can own the microphone.
- Start is idempotent. Stop and cancel use compare-and-set semantics.
- Every long-running limb has a deadline and cancellation path.
- An editor snapshot is captured at start and checked again before insertion.
- The recovery spool is admitted before recording becomes user-visible.
- A History draft is created before ASR and finalized atomically after insertion or copy-only completion.
- UI surfaces render coordinator state. They never invent their own phase.

Initial deadlines are measured on the S26 Ultra and adjusted from real receipts:

- bind/readiness: 3 seconds before an actionable retry state
- audio finalization: 3 seconds
- ASR final decode: max of 15 seconds or 2 times audio duration
- local polish: 15 seconds
- cloud polish: 20 seconds
- insertion acknowledgement: 2 seconds

## Layers and package ownership

```text
com.envi.wispr
  app/                 application graph and startup
  ui/
    shell/             edge-to-edge adaptive app shell
    home/              readiness and primary record action
    history/           adaptive list/detail history
    words/             Your Words and packs
    models/            ASR and polish setup
    settings/          focused settings destinations
    onboarding/        resumable setup and practice dictation
    components/        reusable Material 3 Expressive components
    theme/             dynamic color, fallback tokens, type, shape, motion
  dictation/           coordinator, state machine, session commands
  audio/               routing, capture, pre-roll, VAD, sound cues
  asr/                 adapter contract, model capability registry
  cleanup/             deterministic text transformations and validation
  polish/              local and cloud provider adapters
  insertion/           smart seam planner and insertion routes
  overlay/             floating recording overlay controller
  shortcuts/           side-button app shortcut, tile and notification entry
  history/             Room entities, DAO, repository, recovery spool
  vocabulary/          Room entities, import/export, contacts, packs
  models/              manifest, downloads, checksums, storage, unload policy
  settings/            Proto DataStore schema and repository
  secrets/             Keystore-backed provider credentials
  diagnostics/         content-free readiness, metrics, bounded debug logs
```

The current AIDL services remain usable during migration. Their contracts move behind Kotlin repository interfaces so UI code never binds to AIDL directly.

## Sources of truth

| Data | Source of truth |
|---|---|
| Settings and onboarding progress | Proto DataStore |
| Transcript history and recovery metadata | Room |
| Structured custom terms and vocabulary packs | Room |
| Provider secrets | Android Keystore-backed encrypted storage |
| Model catalog | Versioned manifest bundled with the app |
| Model installation state | Verified app-private files plus Room metadata |
| Active dictation | `DictationCoordinator` state and encrypted recovery spool |
| Editor context | Ephemeral Accessibility node identity and bounded selection context, never persisted as content |

Room tables start with:

- `transcript`: original, deterministic, polished and final text; timestamps; engine/provider/model; language; duration; insertion result; recovered/interrupted/kept flags.
- `custom_term`: exact value, aliases, category, priority, force-replace, case sensitivity, source, source identity, enabled, use count.
- `vocabulary_pack` and `vocabulary_pack_term`: versioned bundled or downloaded packs and enabled state.
- `model_install`: manifest ID, version, checksum, bytes, state, last verified, failure.
- `recovery_item`: spool identity, state, expiry and linked transcript. Audio bytes stay in an encrypted app-private file, not the database.

No dictated content, surrounding text, API key, or contact data enters diagnostics or telemetry.

## Text pipeline

Every stage returns both text and an outcome. A stage may enrich text but may not erase the last valid result.

1. ASR produces raw text and observed language.
2. Deterministic cleanup applies enabled filler, inverse text normalization, spoken punctuation, and emoji rules.
3. Vocabulary correction applies exact terms, aliases, case rules, priority, and force-replace rules.
4. Optional polish receives bounded instructions, vocabulary, and allowed editor context.
5. Output safety rejects blank, truncated, duplicated, structurally unsafe, or meaning-shifted results and falls back.
6. Smart insertion plans capitalization, prefix/suffix spacing, trailing space, seam de-duplication, and replacement range.
7. Accessibility returns to the tracked editor and first requests `ACTION_PASTE`. If the editor refuses paste, guarded `ACTION_SET_TEXT` preserves existing text and selection. Copy-only is the final safe fallback.

The selected target editor is represented by a non-content identity token. Password, payment, OTP, and other sensitive fields default to copy-only or no-context behavior.

## Model delivery

Model downloads use unique WorkManager work keyed by model ID. Each download writes to a partial file, supports pause/resume through HTTP ranges where the host permits it, validates byte count and SHA-256, then atomically admits the model. Failed files are quarantined and never loaded.

The model manifest defines:

- stable model and engine IDs
- display name, creator, license and notice
- source URL and pinned revision
- exact file list, byte counts and checksums
- language and streaming capabilities
- recommended memory and storage headroom
- unload compatibility and process owner

The UI reports Downloading, Paused, Verifying, Ready, Update available, Repair needed, Failed, and Removing. A model is Ready only after on-device verification.

## Native control surfaces

### Samsung side button and floating overlay

- The primary user path is a double-press of the Samsung right button mapped directly to `VoiceInputActivity` through Samsung's Open app shortcut.
- The assistant entry opens a transparent floating recorder over the current app while Gboard or the user's chosen keyboard remains unchanged.
- The overlay shows the current phase, cancel/stop, hands-free lock, level history, and optional partial text.
- The Accessibility service continuously remembers only the last focused editable node identity. It ignores EnviousWispr's own windows and never stores surrounding text in diagnostics.
- After the overlay closes, bounded event-assisted retries wait for the original package, window, field and cursor to return before inserting.
- Password and other sensitive fields refuse automatic insertion. A stale, missing or unsupported target keeps the transcript on the clipboard and shows a clear Paste action.
- Optional Quick Settings and notification controls call the same coordinator without changing the primary side-button experience.

### Quick Settings and notification

- Both call the same start/stop/cancel coordinator commands as the side button.
- The microphone foreground notification exists only while recording or explicitly warm.
- The notification always exposes Stop and Cancel during capture.
- Quick Settings reflects real readiness and recording state.

## App UI

The main app becomes a single edge-to-edge `ComponentActivity` with Navigation 3 and `NavigationSuiteScaffold`.

Compact windows use a bottom navigation bar. Expanded windows use a navigation rail. History uses an adaptive list/detail layout when space permits. The primary destinations are:

1. Home
2. History
3. Your Words
4. Models and AI
5. Settings

Home shows one primary record action, current engine/provider, readiness, recent result, and actionable setup problems. It is not a settings dump.

Settings contains focused screens for Appearance, Transcription, Live Preview, Microphone, Sounds, Controls, AI Polish, Clipboard and Insertion, Permissions, Privacy, Diagnostics, About, What's New, and Updates.

### Material 3 Expressive rules

- Use Material 3 `1.4.0` stable and Material 3 Adaptive `1.3.0` stable, with a compatible stable Compose BOM verified before implementation.
- Enable dynamic color on Android 12+ and use a deliberate Envious purple fallback in light and dark themes.
- Draw edge-to-edge and consume status, navigation, cutout, gesture, and IME insets at the component that needs them.
- Use Material Symbols as local vector assets, not the retired all-icons library.
- Use expressive shapes, container transforms, emphasized motion, honest progress, and action-oriented system haptics.
- Respect the system haptic setting, animator duration scale, font scale, contrast, TalkBack, and switch access.
- Keep touch targets at least 48 dp and never communicate state through color alone.
- Validate portrait, landscape, split screen, keyboard-visible, large text, and dark/dynamic themes on the physical S26 Ultra.

## Battery and memory policy

- No periodic timer, amplitude poll, accessibility traversal, binder, wake lock, or resident model at idle.
- Audio levels are pushed from the capture process during an active session, not polled by each surface.
- Warm readiness has Off, 10 seconds, 30 seconds, 60 seconds, and Always options. Always includes an explicit battery warning.
- Only one heavy inference process is resident by default. The coordinator unloads before switching engines when memory headroom is unsafe.
- WorkManager downloads require storage headroom and use user-visible progress. Optional constraints are deliberate, not silently surprising.
- Recovery audio has a bounded retention policy and is deleted after success unless debug audio retention is explicitly enabled.

## Migration slices

Each slice must leave the current spoken POC usable.

1. Foundation: dependency baseline, theme, single app activity, adaptive shell, ViewModels, repositories, DataStore, Room, readiness model.
2. Native entry: Samsung side-button app shortcut, coordinator, overlay migration, accessibility target tracking, guarded insertion, tile and notification.
3. Audio: foreground service, routing, pre-roll, warm policy, VAD, interruptions and cues.
4. ASR: adapter registry, second engine, language system, streaming, model lifecycle.
5. Text: deterministic cleanup, structured Your Words, contacts, import/export, Quick Add and packs.
6. Polish: local model lifecycle, cloud/self-hosted providers, secrets, prompts and safety.
7. Product: live preview, history, recovery, diagnostics, privacy, onboarding, What's New and updates.
8. Critic: every parity ID, every target class, orientations/themes/accessibility, idle/repeat/long battery and memory.

## Proof gates

- Unit tests: state transitions, cleanup, vocabulary, safety, insertion seams, model manifests and settings migrations.
- Database tests: Room schema, migrations, FTS, concurrent vocabulary writes and recovery transactions.
- Integration tests: coordinator plus fake service adapters, deadlines, cancellation, process death and fallback.
- Compose tests: navigation, state restoration, large text, TalkBack semantics and adaptive window sizes.
- Physical phone: real side-button launch, microphone, shipped models, accessibility insertion, clipboard fallback, and plain/rich/browser/terminal/chat/unsupported targets while Gboard remains the default keyboard.
- Performance: idle baseline, ten short dictations, two-minute dictation, engine swap, model unload and overnight idle.
- Visual critic: portrait, landscape, split screen, light, dark, dynamic color, keyboard visible and supported font/display sizes.

Parity is not complete until all 103 contract rows have evidence and the final critic finds no missing outcome, unsafe fallback, battery leak, stale target insertion, accessibility blocker, or unpolished surface.

## Official implementation references

- [Android app architecture](https://developer.android.com/topic/architecture)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- [Edge-to-edge in Compose](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- [Accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android haptics](https://developer.android.com/develop/ui/views/haptics)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [Room](https://developer.android.com/training/data-storage/room)
