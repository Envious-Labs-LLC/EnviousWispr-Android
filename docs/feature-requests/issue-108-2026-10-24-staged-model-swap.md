# Issue #108 — Keep dictating on the previous model while its update downloads — 2026-10-24

GitHub issue: `#108`. Tier: LARGE (model delivery and both engines). Status: DRAFT, grounded review PROCEED-AS-PLANNED (round 7, 2026-10-25); awaiting Gate 2.

Gate 1 note: the founder set the session goal "keep banging out issues back to back till I tell you to stop"
(2026-10-24). That goal is taken as the go-ahead to PLAN only. Gate 2 still stops before any code.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. This is upgrade continuity, which no parity row covers.

**Hardware UAT:** Y. Setup: a phone holds a verified speech model at revision A, and a build whose manifest
pins revision B (with A listed as previous) is installed on mobile data, so the download cannot start. The
user double-presses the side button and dictates "send the deck tomorrow" into Messages. The words land, the
Transcription card says "Update available", and after Wi-Fi returns and the download verifies, the next
dictation runs on revision B.

## Preface — User Rubric

1. **Who.** Priya, the freelance designer persona, on her commute, on mobile data. Thirty seconds ago Play
   auto-updated the app; now she wants to answer a client in WhatsApp.
2. **Why.** "I updated an app and it stopped working until I got home to Wi-Fi? That's broken."
3. **How.** As always: the side button or the bubble. Reactive; she never thinks about models.
4. **What app.** WhatsApp, Messages, Gmail, Slack: her usual set.
5. **Natural input.** "running ten minutes late", "can we push to three", "sending the files tonight",
   "yes that works for me", "call you after the meeting".
6. **Success.** She notices nothing. Dictation works, and later the card quietly says the update finished.
7. **Wrong-not-broken.** Dictation works but on the old model with its old known issues. Acceptable; it
   is the model she already had yesterday.
8. **Power-user hack.** Today: tap "Use mobile data" to force a 670 MB download, or uninstall and
   reinstall, which loses nothing but costs the whole download.
9. **Control.** No new control. "Use mobile data" already exists for anyone who wants the new model now.

### Cross-persona check
None of the seven personas wants a broken app after an update. The one tension is storage: a phone near
full holds about 1.3 GB during a same-model update. That peak already exists today (the staging download
sits beside the final folder), so this change adds no storage.

## 0. TL;DR

When a pinned model revision changes, the readiness check compares the old files with the new descriptor and
fails, so dictation (or polish) stops until 670 MB (or 484 MB) download on Wi-Fi. The old files stay intact
for that whole window: downloads stage in a separate folder and swap in atomically at the end. Fix the pipe:
the manifest keeps the previous pinned descriptor or descriptors for the SAME model id, and engines load
whichever descriptor verifies, current first. Verification is not relaxed: it is the same full name, receipt,
size and hash check, against an older pinned descriptor. "Update available" and the download path stay
current-only. The speech engine reloads when the admitted model changes. Evidence: unit tests for selection
and for every reader, an emulator run of a manifest bump with the download blocked, and a phone UAT.

## 1. Problem

Code, current main (`f077201b`):
- `ModelDeliveryStore.isVerified` requires `receipt.readText() == receiptText(model)` for the COMPILED
  descriptor (`app/src/main/java/com/envi/wispr/models/ModelDelivery.kt:97-104`). A revision bump makes the
  admitted folder fail that check the moment the APK installs.
- `AsrService.initRecognizer` refuses to load: `if (!ModelStorage.isReady(this, ModelManifest.parakeet))`
  (`asr/AsrService.kt:278`). S1-mini is the same through `S1ModelSelector.resolve`
  (`polish/S1ModelSelection.kt:16-25`).
- The download runs under `NetworkType.UNMETERED` and storage-not-low (issue body), so on mobile data the gap
  lasts until the user reaches Wi-Fi.

Founder record: the #458 Parakeet Ultra swap accepted this gap by explicit approval (2026-10-20), and
`docs/maintenance.md` names #108 as the work to remove it before the next replacement.

## 2. Goals & non-goals

### 2.1 Goals
1. After a same-id revision bump, dictation keeps working on the previous verified model until the new one is
   admitted. The same holds for polish and S1-mini.
2. Verification strength is unchanged: a model loads only when it matches a pinned descriptor in full (names,
   receipt, sizes, SHA-256).
3. Once the new revision is admitted, the next take uses it, without an app restart.
4. "Update available" still shows while the previous model is in use, and the download still starts on its
   existing constraints.

### 2.2 Non-goals
- **Cross-id replacements** (the #374 `parakeet` → … and #458 `parakeet-sq` → `parakeet-ultra` shape): the
  retired folder is a different model that the current engine code may not load. This plan does not load
  retired ids. Future replacements should keep the storage id whenever the current engine can load the old
  files; a replacement that cannot is the founder's call, as #458 was.
- Downloading on mobile data automatically, or changing the download constraints.
- Keeping more than the previous pinned revisions the current code can load.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- **Producer of an admitted model:** `ModelDeliveryStore.download` (`ModelDelivery.kt:130-234`) stages into
  `<root>/.<id>.download`, stamps `.staging-revision`, verifies every file, writes `.verified-receipt`, then
  renames final → `.<id>.old`, staging → final, and deletes `.old` (`:217-227`). `adoptExisting` does the same
  (`:247-276`).
- **The readiness owner:** `ModelDeliveryStore.isVerified` (`:97-104`), wrapped by `ModelStorage.isReady`
  (`models/ModelStorage.kt:10`).
- **Consumers** (`grep -rn "ModelStorage.isReady\|\.isVerified(\|needsUpdate(\|installationStamp(\|finalDirectory(" app/src/main/java`):
  - `asr/AsrService.kt:278` loads the speech engine;
  - `polish/S1ModelSelection.kt:14-25` and `polish/PolishService.kt:577` load S1;
  - `ui/ReadinessViewModel.kt:139,153` drive the screens' readiness;
  - `ui/OnboardingViewModel.kt:93` drives setup's verification;
  - `models/ModelDeliveryWorker.kt:53` skips a download when verified, `:91` is first run, `:381` is
    `needsUpdate`;
  - `models/SpeechModelUpgrade.kt:34` runs the #458 upgrade decision;
  - `telemetry/AppLaunchFacts.kt:37` reports `models_ready`;
  - `ui/ModelCards.kt:143` checks whether a folder exists.
- **Engines read fixed file names:** `ParakeetEngine.open(modelDirectory, …)` → `TdtModel.open`, and S1 uses
  `S1Config.MODEL_FILENAME` (`polish/S1Config.kt:8`).

### 2. Existing authority
`ModelDeliveryStore` owns verification and admission; `ModelManifest` owns descriptors. No owner of
"previous descriptor" exists: `grep -rn "previous" app/src/main/java/com/envi/wispr/models` finds none that is
a descriptor. New authority proposed: `ModelDescriptor.previous` (proposed) and
`ModelDeliveryStore.usableDescriptor` (proposed).

### 3. Prior attempts and live direction
- Issue #108 body and the 2026-09-02 Codex adjudication: a staged swap is accepted, verification is never
  relaxed, and the storage peak is inherent.
- #458 (2026-10-20): the founder accepted the gap for that swap only; `docs/maintenance.md` names #108 to remove
  it.
- `LegacyModelSweep` and `SpeechModelUpgrade` show the established pattern of deleting old files only after the
  current model delivered a result.

### 4. Boundaries
- **Processes:** readiness is read in the main process (screens, worker) and in `:asr` and `:polish`
  (loaders). Every one reads files directly with no shared cache, so all of them see the same disk.
- **Engine residency:** `:asr` loads once per service instance (`AsrService.kt:262`, `owner.load`) and keeps the engine
  under "Unload model after" (#37; default never). After admission the folder's files are renamed, not
  rewritten. Open handles keep the old inodes on Linux, so a loaded engine keeps working, but it never picks up
  the new files. S1 already re-resolves by `installationStamp` on each load (`PolishService.kt:577`).
- **Mid-swap reads (corrected by the coverage round):** the per-model lock (`ModelDelivery.kt:78-80`,
  `MODEL_OPERATION_LOCKS`, `:21`) is PROCESS-LOCAL. It is held for the whole transfer, and it is released before
  any native open. So `:asr` and `:polish` can race main-process admission, and a main-process readiness read
  can block behind a running download (a pre-existing behaviour). Final-folder access is therefore
  owned by the engine's process: only the owner mutates it (§13e). Stamps support resident reuse only.

### 5. High-risk premises, with evidence
- "The old files stay intact during the download": staging is `File(root, ".${model.id}.download")`
  (`:133`), and final is touched only at `:220-227`.
- "Engines need fixed names": shown above. So a previous descriptor is loadable only if its file-name set
  equals the current one (a test enforces this).
- "Hash cost does not grow": today the current descriptor's check fails at the receipt comparison before any
  hashing when the revision differs. The fallback then hashes once against the previous descriptor. That is one
  full hash, the same cost as a ready model today.
- A problem-only Codex consult on the engine reload path is part of the coverage round (step 4).

## 3. Design

1. `ModelDescriptor.previous: List<ModelDescriptor>` (proposed), default empty. These are older pinned
   descriptors for the SAME id that the current engine code loads unchanged. A unit test requires every
   previous descriptor to have the same `id` and `engineId` as its parent, the same file-name set, and a
   different `pinnedRevision`, with no nesting.
2. `ModelDeliveryStore.usableDescriptor(model): ModelDescriptor?` (proposed), run on the owner's model-operation queue
   (§13e), never under the staging mutex: the current descriptor when it fully verifies, otherwise the first of `model.previous` that
   passes the same full verification, otherwise null. `isVerified` keeps meaning "the CURRENT revision is admitted".
   `installationStamp` and `receiptText` are already per-descriptor.
3. `ModelStorage.usable(context, model)` (proposed) wraps it. The loaders switch to it:
   - `AsrService.initRecognizer` loads when usable is non-null;
   - `S1ModelSelector.resolve` uses usable and the stamp of the descriptor that verified.
4. Readiness is split into two values:
   - **Usability** ("can I dictate or polish") feeds `ReadinessViewModel` and the practice and navigation gates
     it drives, plus `AppLaunchFacts.modelsReady`.
   - **Current verification** (`ModelStorage.isReady`) keeps feeding every card, download and update surface:
     `OnboardingViewModel.modelFlow`, `ModelWorkViewModel`, `ModelDeliveryWorker:53`, `needsUpdate`,
     `SpeechModelUpgrade`.

   So the card says "Update available" while dictation works (§13b.2, 13c.4).
5. **Speech engine replacement on admission.** Before each take, `:asr` compares the cheap candidate
   `installationStamp` with its fully verified resident identity. On a difference, `RecognizerOwner` replaces the
   engine inside the existing request deadline (§13c.2, 13c.3).
6. **Cleanup** is unchanged: admission's `.old` delete removes the previous files the moment the new revision
   lands.

Rejected alternatives:
- **Relax verification to "any complete folder":** breaks `models-are-pinned-verified-and-app-private`.
- **Two folders per revision (`<id>@<rev>`):** a larger storage and migration change for the same result. The
  existing staging already gives two copies at the peak.
- **Keep the old model until a manual Update:** the user then has to act, which is the opposite of the goal.

## 3b. Ownership
Verification stays on `ModelDeliveryStore`, which already owns it. Mutation of the final folder moves to the
engine's owning process (§13e), because that process is the only one that opens the engine. The
alternative, each loader trying descriptors itself, would duplicate the verification rules in three places.

## 4. Contract deltas
- `ModelDescriptor.previous` (proposed): "older pinned revisions this build can load unchanged". Every
  consumer that iterates `ModelManifest.all` is unaffected, since `previous` is not in `all`.
- `ModelDeliveryStore.usableDescriptor` (proposed): "which pinned descriptor's files are on disk and fully
  verified, current preferred".
- `ModelStorage.isReady` keeps its meaning (current revision admitted) and stays the API for cards, downloads and
  updates. The "can dictate or polish" readers move to `usable`.
- Final-folder contract: only the owning process mutates it (install, admission, adoption, Remove, Repair), on
  one process-scoped queue. Main-process reads are observations or decision reads that use the explicit states
  of §16 (§13e).

## 5. State and lifecycle audit
- **Populations of model state on disk:** nothing; staging only; final at current; final at previous; final
  at previous plus staging (download in progress); final corrupt; final at an unknown revision. Each maps to:
  not usable; not usable; current; previous; previous; null (repair, as today); null (as today).
- **Process death mid-admission:** death between the admission renames (`ModelDelivery.kt:222-226`) can leave
  final absent, with previous in `.old` and current in staging. Neither is loadable through final, and the next
  download deletes `.old` (`:221`). Recovery stays a separate issue (§13b.6).
- **Engine loaded on previous when admission lands:** the per-take stamp check reloads it; the take in flight
  completes on the old engine, whose open handles stay valid.
- **The user removes the model while on previous:** `remove` deletes final and artifacts, as today; usable then
  answers null.

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| usable | `AsrService.initRecognizer` | loads current only | loads current or previous | yes | unit plus emulator |
| usable | `S1ModelSelector.resolve` | current only | current or previous, with that stamp | yes | unit |
| usable | `ReadinessViewModel` | current only | usable | yes | unit |
| current (unchanged) | `OnboardingViewModel.modelFlow` cards | current only | unchanged; practice and navigation gates get usability separately | yes (gates) | unit |
| current (unchanged) | `ModelWorkViewModel` cards | current only | unchanged, fed separately from usability | yes | unit |
| usable | `AppLaunchFacts.modelsReady` | current only | usable | yes | unit |
| owner queue | `PolishService` normal and qualification opens | stamp only | on the owner queue, after any pending admission | yes | instrumented |
| current plus usable | `ModelDeliveryUi` | Repair on staging failure | Update retry while previous is usable (§13c.6) | yes | unit |
| isVerified (unchanged) | `ModelDeliveryWorker:53` | skip when current | unchanged | no | existing tests |
| needsUpdate (unchanged) | card "Update available" | shown | unchanged, shown while previous runs | no | unit |
| stamp | `:asr` per-take reload | none | reload on change | yes | unit plus emulator |

## 7. Failure modes

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| previous fails its hash | corrupt disk | loaders | today's "model not ready" path | none | repair, as today |
| reload fails after admission | native load | `:asr` | today's load-failure ending | none | next take retries |
| download never starts (mobile data) | constraints | worker | dictation works; card says Update available | staging absent | Wi-Fi or "Use mobile data" |

## 8. Caller-visible signals
`installationStamp` identity now decides the `:asr` replacement; it is a resident-reuse signal, never a
synchronization signal. Usability and current verification are now two separate values (§6). The selected
descriptor's identity flows into processing qualification (§13b.5).

## 9. Fallback source of truth
The only fallback source is a descriptor compiled into this APK (`ModelDescriptor.previous`). Nothing on disk
can nominate itself.

## 10. File-by-file
- `models/ModelManifest.kt`: `previous` field.
- `models/ModelDelivery.kt`: `usableDescriptor`; write-once staged generations; admission, Remove and Repair
  callable only from the owner queue (§13e).
- `models/ModelStorage.kt`: `usable`.
- `asr/RecognizerOwner.kt`: worker-confined replacement (§13c.2).
- `asr/AsrService.kt`: load via usable on the process-scoped owner queue, plus the per-take resident check and
  owner admission.
- `polish/S1ModelSelection.kt`, `polish/PolishService.kt`: usable on the owner queue for normal and
  qualification opens, with owner admission, with the selected artifact identity carried through (§13b.5).
- `ui/ReadinessViewModel.kt` and the practice and navigation gates: usability. `ui/ModelWorkViewModel.kt` and
  `ui/OnboardingViewModel.kt` cards: current, fed separately.
- `models/ModelDeliveryUi.kt`: Update retry while previous is usable.
- `telemetry/AppLaunchFacts.kt`: usable.
- Tests: listed in §11.

## 11. Testing
1. Classes: `ModelDeliveryStoreTest` rows (Product Outcome: "when this fails, an update stops dictation"),
   a manifest drift guard for `previous`, and reader tests.
2. Reverts:
   - remove the previous loop in `usableDescriptor` → the "previous loads" row goes red;
   - make it skip hashing → the corrupt-previous row goes red;
   - remove the `:asr` stamp compare → the reload row goes red.
3. Not tested: cross-id replacement (a non-goal), and real 670 MB downloads in unit tests (the emulator UAT
   covers that).

### 11.1 Hardware UAT spec
- Subsystem: heart path.
- Recipe: install a debug build whose manifest bumps the Parakeet revision with the shipped one as previous,
  with the phone off Wi-Fi.
- Expected observation: a take lands in a real editor (the editor's text, not a log line); the card reads
  "Update available"; after Wi-Fi and admission, the next take logs the new revision.
- Restore: reinstall the Play build.

## 12. Blast radius & rollback
- Touched: model delivery, both loaders, and three readiness readers.
- Not touched: download transport, admission renames, `LegacyModelSweep`, insertion.
- Rollback: revert the PR. An empty `previous` list makes the new code behave exactly as today.

## 13. Ship criteria
- [ ] After a revision bump with Wi-Fi off, dictation still lands words on the phone.
- [ ] After the new model downloads, the next dictation uses it with no restart.

## 13b. Coverage round dispositions (Codex, 2026-10-24, one round)

Every gap was adopted. The design changes each one makes:

1. **Cross-process races (P1).** SUPERSEDED by §13e (owner-only mutation). Original text, kept for the record:
   each loader brackets with `installationStamp` of the selected descriptor:
   stamp, full verify, stamp (equal or abort), native open, stamp again (equal or release and retry once). This
   extends to `:asr` the pattern `S1ModelSelector.resolve` already uses. No lock is assumed across processes.
   Pinned verification and the ASR load bound are unchanged.
2. **Cards (P1).** Model health keeps meaning "current revision admitted". Usability is a separate value, so
   the card shows "Update available" while dictation works. The health consumers keep reading current
   verification: `ui/ModelWorkViewModel.kt:103`, `ui/ModelCards.kt:74,144`, `models/ModelDeliveryUi.kt:71,87`,
   `ui/TranscriptionScreen.kt:93`, `ui/PolishScreen.kt:166,173`, `PolishStatusChip:64`, `ui/PolishLadder.kt:237`.
   Only "can I dictate or polish" readers move to usable. The build enumerates these by grep and lists each
   disposition in the PR.
3. **Readiness readers (P2).** `ui/SettingsActivity.kt:222`, `ui/SettingsPages.kt:340,401,414,422`,
   `ui/OnboardingPolicy.kt:77,86`, `ui/OnboardingScreen.kt:129,207`. Each is classified "can dictate" (usable) or
   "download and repair" (current), with a test per class. Setup's download selection stays current, so an
   upgrade user in setup still downloads.
4. **S1 stamp consistency (P1).** `S1ModelSelector.installationStamp` and `resolve` both select the usable
   descriptor. Covered: resident reuse across previous → current (the stamp differs, so the model reloads);
   admission between warm-up and generation; files missing mid-load; unchanged-text fallback.
5. **Artifact identity (P1).** The selected descriptor's identity, revision and hash flow into
   `processing/ProcessingEnvironment.kt:21` and `PolishService.kt:265,482,638`, and so into qualification records
   (`providers/ProviderConfigurationRepository.kt:179,194`, `providers/ui/PolishSettingsViewModel.kt:130`,
   `ui/PolishScreen.kt:168,174`). A qualification made on the previous model is never read as one for the
   current model.
6. **Crash recovery (P1).** A pre-existing gap, now stated rather than claimed. Death between the two renames at
   `ModelDelivery.kt:222-226` can leave final absent and `.old` present, and the next download deletes `.old`
   (`:221`). This plan does not add recovery (non-goal). It adds a row recording that a crash there loses both
   models, exactly as today, and files a separate issue for admission recovery.
7. **Controls, quarantine, repair, adoption (P2).** Pause, cancel and resume touch only staging, so previous stays
   usable. Quarantine is the same. Repair deletes final first (`:240`), so previous usability ends; repair is
   offered only for a broken current, and the card says so. Adoption (`.adopt`) admits current only. The final
   folder is untouched only during ordinary download staging, and the plan says exactly that.
8. **Engine reload ownership (P1).** Replacement goes through `asr/RecognizerOwner.kt`. It serializes release
   then load (calling `load` again alone would overwrite without freeing, `:41`). Also covered: failed reload and
   retry, Remove while resident, `asr/SpeechModelKeepAlive.kt` timer expiry, the Immediate and Never settings,
   and service recreation. Loading is once per service instance, not per process.
9. **Bootstrap, BLOCKED, #458 (P2).** Preserved and tested:
   - empty-install acquisition (`models/ModelBootstrapApplication.kt:127`);
   - current-only adoption and upgrade decisions (`ModelDeliveryWorker.kt:35,46,73`,
     `SpeechModelUpgrade.kt:32,37,50`);
   - inherited Pause and Cancel;
   - BLOCKED refusal with Remove still allowed;
   - retired-file sweeping.
10. **Storage (P2).** Storage readers are unchanged: the final folder counts as the model, and staging, `.old`,
    `.adopt` and quarantine count as unclaimed (`ui/ModelCards.kt:204`, `ui/StoragePage.kt:51`,
    `models/ModelFootprint.kt:118`).

## 13c. Grounded review round 1 dispositions (PROCEED-WITH-REVISIONS; all adopted)

These supersede §3 step 5 and §13b.1, 13b.4 and 13b.8 where they differ.

1. **Cross-process lease (P1).** DELETED by §13e; kept for the record. A file lock, `java.nio.channels.FileChannel.lock` (external) on
   `<root>/.<id>.lease` (proposed), works across `:asr`, `:polish` and the main process.
   - Both loaders hold a SHARED lease through selection, full verification and native open.
   - Admission (the two renames), Remove and Repair take the EXCLUSIVE lease.
   - The download transfer holds neither, so dictation and readiness never wait behind a transfer for the
     lease.
   - Stamps support resident reuse only, never synchronization.
   - The shared lease is bounded by the existing load bound; a lease that cannot be taken in time is a load
     failure, through today's path.
2. **Replace inside RecognizerOwner (P1).** Add worker-confined replacement to `asr/RecognizerOwner.kt`: clear
   readiness and identity, free the old engine, then verify and open the replacement, all without closing the
   owner (today `load` overwrites without freeing, `:41`, and `close` is terminal, `:62`). Queued takes are kept
   and delivery stays exactly-once. Queueing, replacement, retries and decode share the existing request deadline
   (`AsrBounds.kt:11,28`); `LOAD_BOUND_MS` grants no extra request time. Reload plus decode is qualified against
   that deadline on the emulator and the phone, with the measured numbers in the PR.
3. **Cheap resident checks (P2).** Before each take, the resident check compares the cheap candidate
   `installationStamp` with the fully verified resident identity. Full `usableDescriptor` verification runs only
   when opening or replacing an engine. A cheap match never authorizes a first load. The same applies to S1
   (`S1ModelSelection.kt:14`, `PolishService.kt:574`).
4. **Setup and card state (P1).** `OnboardingViewModel`'s `verify` feeding `workUiState` (`:93`) stays
   current-only for the download cards. Usability is supplied separately to practice and navigation gates.
   `ModelWorkViewModel` likewise receives current verification separately from `ReadinessViewModel`'s
   usability.
5. **Stale text fixed (P2).** §5's crash row and §2.5's "once per service instance" are corrected above.
6. **No destructive Repair while previous works (P2).** When previous verifies and only staging failed, the card
   offers an Update retry and keeps final (`ModelDeliveryUi.kt:89`). Destructive Repair is offered only when no
   admitted pinned descriptor is usable.
7. **Falsifiable tests (P2).** §11 rows are specified as:
   - an exact previous receipt over same-size corrupted bytes, which kills the "skip hashing" revert;
   - the production request path driven through a previous load, then current admission, then a second take,
     asserting the replacement identity, release-before-load ordering, and exactly one callback per take, which
     kills the "stamp compare removed" revert;
   - admission raced against both loaders across processes (an instrumented row);
   - each mutation run with its failure recorded before any protection is claimed.

## 13d. (DELETED by §13e; kept for the record) The final-folder concurrency protocol

Round 2 repeated round 1's class: concurrency of final-folder access. Its members are enumerated here from the
producers (every caller of `ModelDeliveryStore` that touches the final folder, `grep -n "fun " ModelDelivery.kt`
plus the three loaders), not from the findings.

**Pre-committed consequence.** If a third round finds another member of this class, the lease design is DELETED
and replaced by the simpler shape: admission happens only in the main process, after it has asked `:asr` and
`:polish` to release their engines and confirmed they did. Then no reader can overlap a swap, at the cost of one
cancelled warm engine per admission.

**Actors.**
- Readers: the `:asr` load, the `:polish` normal and qualification loads, and main-process readiness.
- Writers: admission (download and adopt), Remove, Repair.
- Staging-only: transfer, pause, cancel, quarantine.

**Primitives, one owner.** `ModelDeliveryStore` owns:
- (a) the existing staging mutex (`MODEL_OPERATION_LOCKS`), now for staging operations only;
- (b) one per-model process gate, a `java.util.concurrent.locks.ReentrantReadWriteLock` (external), keyed by
  canonical path;
- (c) one `FileChannel` per lease file, opened once per process and never closed by anyone else, holding the OS
  lock while any local holder exists.

Lease files live at `<root>/.model-leases/<id>.lock` (proposed). They are created once and never unlinked or
replaced by Remove, Repair, admission or `cleanupArtifacts`. Their directory is outside the `.<id>.` prefix that
`cleanupArtifacts` matches (`ModelDelivery.kt:278`).

**Ordering.**
- Writers acquire, in order: staging mutex, local gate (write), OS lease (exclusive).
- Readers acquire, in order: local gate (read), OS lease (shared).
- No path acquires the staging mutex while holding the gate or the lease.
- A transfer holds only the staging mutex, so readiness completes during a transfer.
- Nested Repair → Remove calls protected helpers that assume the lease is held, never reacquiring it.

**Deadlines.**
- Gate and lease acquisition consume the caller's remaining deadline: the request deadline for `:asr` takes, the
  polish deadline for `:polish`, and a bounded readiness budget in the main process.
- Expiry interrupts a blocked `FileChannel.lock` (it is interruptible).
- An expired acquisition never opens or publishes an engine.
- Readiness acquisition is cancellable with the screen's scope.

**Process death.** The OS releases a dead process's file lock. The gate is in-memory and dies with the
process.

**Tests per member** (each with its revert recorded red before protection is claimed):
- same-process readers racing admission;
- cross-process `:asr` and `:polish` racing admission (instrumented);
- nested Repair → Remove;
- readiness completing while a transfer holds the staging mutex;
- the lease file surviving Remove, Repair and cleanup with the same inode;
- acquisition expiry with no late open.

## 13e. PIVOT (grounded round 3): owner-process admission replaces the lease

Round 3 found more members of the concurrency class: card and storage reads, resident checks, and the
first-reader/last-reader lifetime of an aggregate file lock. Per the pre-committed consequence, **§13c.1 and §13d
are DELETED.** There is no lease, no read/write gate and no `.model-leases` directory.

**Grounding for the new shape.** Each model has exactly ONE process that opens its engine:
- Parakeet only in `:asr` (`asr/AsrService.kt`, `asr/TdtModel.kt`);
- S1-mini only in `:polish` (`polish/PolishService.kt`, `polish/S1ModelSelection.kt`).

The manifest's `android:process` entries are at `AndroidManifest.xml:99,105`. The main process reads model
folders only for screens, decisions and storage measurements. It never opens an engine.

**Design.**
1. **The worker stages, and admits only a first install.** When the final folder is absent, the worker admits as
   today (no engine can hold files that do not exist). When a final folder exists, the worker stops after full
   verification. It writes the receipt into a revision-named folder `<root>/.<id>.staged-<revision>` (proposed),
   then a completion marker last, and never touches that folder again. It deletes only staged folders whose
   revision is not current, and it never deletes the current revision's staged folder.
2. **The engine's owner admits its own model**, on its own serialized worker:
   - `:asr` does it inside `RecognizerOwner`'s worker-confined replacement (§13c.2);
   - `:polish` does it on its load executor.

   Before selecting a descriptor, the owner checks cheaply for a COMPLETE current-revision staged folder. If one
   exists, it releases its engine, performs the two renames (final → `.<id>.old`, staged → final, then deletes
   `.old`) under the in-process staging mutex, then fully verifies and loads current. Because no other process
   opens this model's engine, no cross-process lock is needed for opens.
3. **Main-process reads are classified.**
   - Storage measurements (`StoragePage`, `ModelFootprint`) and existence checks (`ModelCards.kt:143`) are
     best-effort observations that never establish readiness.
   - The readiness and card decision reads treat "final absent, `.old` or a complete staged folder present" as
     the transient admission window, and re-read once after a short settle instead of reporting "not ready".
4. **When the owner never runs** (no dictation since the download), the staged folder waits. The card says the
   update is downloaded and applies at the next dictation. That copy is an open question for the founder (§14).
5. **The resident check stays cheap:** "is there a complete current staged folder?" (one directory and marker
   read) plus the stamp comparison. Full hashing happens only on load or replacement.

**Kept:** `previous` descriptors and `usableDescriptor` (for the window before admission), the split between
usability and current verification (§13c.4), cheap resident checks (§13c.3), RecognizerOwner replacement (§13c.2),
no destructive Repair while previous works (§13c.6), and falsifiable tests (§13c.7, re-pointed at owner
admission).

**Rollback correction (round 3, P2).** An empty `previous` list disables only descriptor fallback. Owner
admission, replacement and the readiness split stay active and need their full validation. Reverting the PR is
the rollback. §12 and §14.2 are corrected by this note.

**Tests per member** (each revert recorded red first):
- the worker never admits over an existing final;
- `:asr` admits during a take's replacement, and the accepted take still delivers exactly once;
- `:polish` admits between warm-up and generation;
- a staged folder without its marker is never admitted;
- a non-current staged folder is deleted only by the worker;
- a main-process readiness read inside the rename window re-reads and does not report "not ready";
- a crash between the renames leaves today's state (§13b.6, unchanged).

## 14. Open questions
1. Should Storage show that an older model is in use? Proposed: no new copy; "Update available" already says it.
2. Build now or defer to the next planned revision bump (§16). An empty `previous` disables only fallback;
   owner admission, the explicit states and engine replacement stay active, so shipping early is not inert.

## 15. Related
#36, #458, #37, `docs/maintenance.md`, `LegacyModelSweep`, `SpeechModelUpgrade`.

## 16. Status after grounded round 4 (2026-10-24): paused for a founder decision

Round 4 found the owner-admission direction sound (PROCEED-WITH-REVISIONS), and its revisions change the size
of the work:
- **Owner-only mutation of the final folder.** First install, adoption, Remove and Repair all route to the
  owning process (`:asr` or `:polish`). Remove and Repair are issued from the main process today, so this needs
  new cross-process commands (AIDL surface, REFACTOR tier).
- **Write-once staging.** Each download writes a private attempt folder and publishes an immutable generation;
  only the owner consumes or reclaims it.
- **One process-scoped model-operation queue** in each owner, outliving service instances.
- **Explicit decision states:** Downloaded, Applying, Ready, Missing, Interrupted. Readers keep their last proven
  result while Applying.
- The lease-era text in §2.5, §3.2, §3b, §4, §6, §10 and §14.2 has been rewritten to §13e. §13b.1, §13c.1 and
  §13d are marked superseded or deleted and kept for the record. The round-4 items (owner-only mutation
  including first install, write-once staging, the process-scoped queue, explicit states) are the remaining
  plan work if the founder chooses to build.

The decision before more planning: build this (about REFACTOR tier, AIDL plus both engines), or defer it until a
model revision bump is actually planned and accept the gap for that bump as #458 did.

## 17. Founder direction, 2026-10-25, and the revised design (supersedes §13e where they differ)

**Founder, verbatim.**
- "pause dictation during any engine swaps" (phones have less storage than Macs).
- "We need to add a setting for download over wifi only vs download over any setting. When an update needs a new
  model, if we detect they aren't on wifi, we should prompt them via a pop up, new model available, download now
  or wait for wifi. i'm sure there must be industry best practices here."

**Industry practice (researched 2026-10-25).**
- Google Play Asset Delivery parks large packs in `WAITING_FOR_WIFI`, both off Wi-Fi and when the user's Play
  setting is Wi-Fi-only. The app then shows Play's confirmation dialog, `showConfirmationDialog` (external;
  `showCellularDataConfirmation` before 2.2.0), and accepting lets the download continue on mobile data.
- Apple warns before large cellular downloads, around 200 MB.
- Chrome's built-in AI guidance: tell the user the size and time, and show progress.
- Common pattern: keep the existing capability working while the new model downloads.

Sources:
- https://developer.android.com/reference/com/google/android/play/core/assetpacks/AssetPackManager
- https://developer.chrome.com/docs/ai/inform-users-of-model-download
- https://www.sitepoint.com/ux-patterns-large-model-downloads/

**Already in the app.** `ModelDeliveryWorker.enqueueSetup(…, mobileData)` picks `CONNECTED` or `UNMETERED`
(`ModelDeliveryWorker.kt:308-314`). Setup has a one-off "Use mobile data" button (`OnboardingScreen.kt:212`) that
stores a setup-only choice (`OnboardingViewModel.kt:118-119`, `setOnboardingMobileData`). Updates are always
Wi-Fi only (`SpeechModelUpgrade.kt:38`, `ModelDeliveryWorker.kt:329,338`).

**Revised design.**
1. **Setting "Download models" (proposed):** "Wi-Fi only" (default) or "Wi-Fi or mobile data". It lives on the
   Storage page beside the models it governs. It replaces the setup-only flag, and a user who tapped "Use mobile
   data" in setup keeps that choice. It covers first downloads, updates and repairs.
2. **Update prompt (proposed).** When the app opens, an update is needed, the phone is on mobile data, and the
   setting is Wi-Fi only, show one dialog per model revision. Draft copy, for `content-brand.md` review:

   > **New speech model available** (670 MB)
   > Download now on mobile data, or wait for Wi-Fi. You can keep dictating with your current model meanwhile.
   > [Download now] [Wait for Wi-Fi]

   "Download now" queues this download on any network; it does not change the setting. "Wait for Wi-Fi" queues it
   Wi-Fi only. The card keeps both actions afterwards.
3. **The old model keeps working during the download:** `previous` descriptors and `usableDescriptor`, with the
   same strict checks (§3).
4. **Dictation pauses during the swap itself (founder).** The swap is two folder renames plus the engines picking
   up new files. It runs in the main process (where the download worker and the session owner live) under a
   **swap gate** (proposed) owned by the main process:
   - the gate closes only when no take is active;
   - while it is closed, a new dictation is refused with one calm line ("Updating the speech model. Try again in a
     moment.", draft), and polish qualification is refused;
   - Remove and Repair run under the same gate.

   An engine already resident keeps its open files (renames do not disturb open handles). The next take loads
   the new files: S1 by its existing per-load stamp, `:asr` by the per-take stamp check plus `RecognizerOwner`
   replacement (§13c.2, 13c.3).
5. **What this removes from §13e and round 4:**
   - owner-process admission;
   - the new cross-process commands (no AIDL change);
   - the process-scoped owner queue.

   One process owns every write, and the explicit "Applying" state comes from the gate, never from folder
   absence.
6. **Storage.** The prompt and card name the size. Downloads keep `setRequiresStorageNotLow`, and the download is
   refused with a clear line when free space is below the new model's size plus a margin. The previous model is
   deleted as soon as the new one is in place.

**Still to ground before Gate 2.**
- Whether `:asr` or `:polish` ever opens model files outside a take or qualification. Candidates are the connect
  warm-up and the "Unload model after" timer.
- Where the session owner checks the gate on every start path: side button, bubble, tile, in-app microphone,
  practice.
- How a gate held across process death is reset.

**Grounding note.** `ui/EngineWarmUp.kt:65,79,82` binds `:asr` and `:polish` and calls `warmUpWithPolicy` outside
a take, so the swap gate also refuses (or defers) engine warm-up while closed.

## 18. Round 5 dispositions (PROCEED-WITH-REVISIONS; all adopted, with one design choice)

1. **Draining remote engine work (P1): the swap stops the engine processes.** Refusing new work does not drain
   loads already admitted inside `:asr` and `:polish` (`AsrService.kt:262`, `EngineWarmUp.kt:65,79,87`,
   `TakePolishController.kt:168`, `PolishService.kt:214,235`), and service recreation reloads ASR on its own.
   Instead of new completion callbacks (AIDL), the swap does this:
   - closes the gate, so no new take, warm-up, qualification or bind starts;
   - unbinds every engine binding the main process holds;
   - stops `:asr` and `:polish` (same UID; `android.os.Process.killProcess` (external) on pids read from
     `ActivityManager.getRunningAppProcesses` (external));
   - confirms both are absent before any rename.

   Nothing can then open, read or re-initialize model files. The engines start cold on the next permitted take
   (about 2 to 3 s once, measured `Speech engine initialized in 2779ms` on the emulator). Process absence, not
   binder return or unbind, is the completion evidence. This keeps the no-AIDL property.
2. **Atomic admission (P1).** Gate admission and IDLE→STARTING become one atomic owner transition in
   `beginSession` (`DictationSessionCoordinator.kt:345`), before feedback, target pinning or binds. Every path
   converges there: tile (`DictationTileService.kt:42,46`), side button and app (`VoiceInputActivity.kt:78`,
   `SettingsActivity.kt:105`), bubble (`PasteAccessibilityService.kt:264`), practice (`OnboardingViewModel.kt:181`).
   The swap may close the gate only from IDLE, and a take's permit is released only after its engine work ends.
3. **One writer gate, stable reads (P1).** First install, adoption, update admission, Remove and Repair all hold
   the gate exclusively. Main readiness and card decisions read either a stable snapshot or "Applying". Remote
   stamp reads (`S1ModelSelection.kt:14`, `PolishService.kt:263,577`) cannot run because their processes are
   stopped (item 1). Keep-alive timer expiry is never taken as quiescence.
4. **Native files (P2).** ONNX sessions and the eager vocabulary load (`TdtModel.kt:129-148`) need no later path
   reads. Mappings survive renames, but deleting `.old` does not reclaim mapped space while an old engine lives.
   Item 1 ends those engines first, so space is reclaimed at the swap. GenieX's pathname behaviour after load is
   to be confirmed on the phone, though item 1 makes it moot for the swap.
5. **One download policy (P2).** One effective policy (setting plus any per-revision "Download now" override)
   feeds every enqueue path (`ModelDeliveryWorker.kt:308,329,338`, `SpeechModelUpgrade.kt:38`). The setup flag is
   migrated once. Overrides persist across retries and process recreation. Queued work is re-enqueued with new
   constraints without dropping valid partials or overriding an explicit Pause or Cancel (KEEP policy reviewed).
6. **Wi-Fi is not "unmetered" (P2).** The founder's "Wi-Fi only" is enforced as a transport check
   (`NetworkCapabilities.TRANSPORT_WIFI` (external)) for prompting and before each transfer, with `UNMETERED` kept
   as a scheduling hint. The prompt's model name, size and "keep dictating" line come from the descriptor and
   from verified usability. The line is shown only when the previous model actually verifies.
7. **Stale text and crash state (P2).** §2.2's "no constraint changes", the owner queues in §3.2, §3b, §4, §6 and
   §10, §13e, and §16's cross-process commands are historical; §17 and §18 are the active design. After
   main-process death the in-memory gate is gone, so at app start, before any engine work is admitted, the store
   reconciles final, staging and `.old` (#492 owns that recovery and becomes a dependency of this plan).

**Pre-committed consequence for round 6.** If round 6 finds another member of the "engine work during the swap"
class, the swap moves to app start only: before any engine process runs, and never mid-session. The only cost is
that an update applies at the next app start.

## 19. Round 6 dispositions (PROCEED-WITH-REVISIONS; all adopted; the startup-only consequence did not trigger)

Round 6 found no further restart actor: the engine services are bound-only. The four binding owners that must
unregister are `PipelineBindings`, `EngineWarmUp`, `SpeechModelKeepAlive` and `ProcessingCheckClient`.

1. **Termination evidence (P1).** Before unbinding, the swap links a death recipient to each engine binder it
   holds (`IBinder.linkToDeath` (external), no new AIDL method), then stops the processes. It requires a bounded
   Binder-death acknowledgement for every tracked instance before any rename. Process-list absence alone never
   authorizes a mutation, and an unidentified or still-starting instance defers admission. On timeout, final and
   staging are preserved and the gate reopens; the next idle moment retries.
2. **Permit lifetime (P1).** A take's permit is released at its terminal transition to IDLE. The swap then
   closes the gate atomically from IDLE and ends any remaining engine work by item 1. Nothing on disk changes
   before verified termination.
3. **Wi-Fi routing (P2).** Wi-Fi-only work uses WorkManager's `setRequiredNetworkRequest` (external, available
   in the pinned 2.10.1) with a WIFI plus INTERNET request, falling back to CONNECTED. Transfers open through the
   worker's assigned `Network`. On network loss the transfer closes and keeps its partials. An any-network override
   uses CONNECTED. This supersedes §18.6's "UNMETERED as a hint".
4. **No resident replacement (P2).** Every final-folder mutation stops both engine processes first, and later
   services cold-load through `usableDescriptor`. The per-take `:asr` stamp check and the `RecognizerOwner`
   replacement (§13c.2, 13c.3, §17.4's last sentence) are REMOVED from this change. S1's existing behaviour stays.

## 20. Build log

- **Chunk 0, #492 recovery.** `ModelDeliveryStore.reconcile` runs at main-process start and at the top of
  download and adoption. Codex round 1 P1 (a broken final cost the working `.old`) is fixed, with two rows added.
  Round 1 P2 (startup recovery is asynchronous, so a bound `:asr` can check final before it finishes) is deferred
  to chunk 3 by design: the swap gate stays closed until startup reconciliation completes, and engines are
  bound-only from the main process, so a closed gate keeps `:asr` and `:polish` from starting. Until chunk 3 the
  window exists only in the crash state, where a take already fails today.
- **Chunk 1, previous revision keeps working.** `ModelDescriptor.previous` and `usableDescriptor` feed the loaders
  and readiness; cards, downloads and updates still read current verification. S1's configuration helpers
  (`S1ModelSelector.loadConfiguration`, `resolvedConfiguration`, `qualificationContextId`) name the installed
  revision, so a resident engine on the previous revision is reused and polish is allowed until the swap. Codex
  ALL-CLEAR after three rounds.
- **Chunk 2, download policy and prompt.** `ModelDownloadPolicy` is the one network rule for every enqueue path
  (download, setup, update, repair, resume, the #458 upgrade, the new owed update). Wi-Fi only uses
  `setRequiredNetworkRequest` (WIFI plus INTERNET) and the transfer opens through the worker's `Network`; any
  network uses CONNECTED. The "Download models on mobile data" switch sits on the Storage page and reuses setup's
  stored key, so a setup choice carries over. `ModelUpdates.run` queues an owed update at start (KEEP, ACTIVE
  control only). The prompt shows once per revision off Wi-Fi; "Download now" approves that revision only. A
  queued download held to Wi-Fi while off it shows "Waiting for Wi-Fi" with "Download now on mobile data". A
  changed rule re-queues only ENQUEUED work, as its own kind (tag), never clearing Pause or Cancel.
