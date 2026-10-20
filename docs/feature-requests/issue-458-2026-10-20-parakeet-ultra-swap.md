# Issue #458 — Swap the Android speech model to Parakeet Ultra — 2026-10-20

GitHub issue: `#458` (part of `#453`). Tier: LARGE (model delivery, heart path ASR). Status: DRAFT (revision 2,
after grounded r1 PIVOT).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

`mixed_pr: true`. Lanes: `Code` (`app/**`: manifest, sweep, bootstrap, remove, notices, tests) with obligations
`tests`, `codex-review`, `hardware-uat`; `Docs/dev-tooling` (`.claude/knowledge`, this plan) with
`cited-symbols`.

**PAR rows closed:** none. Parakeet Ultra is the macOS speech model on `feat/3546-ultra-swap` (not yet released
on macOS, `EnviousWispr` `parakeet-supply-chain.md` FACT parakeet-ultra-supply-chain); this change moves Android
to the same model family. No PAR row tracks the speech-model identity.

**Hardware UAT:** Y. On the founder's S26, through a Play internal build, with `parakeet-sq` installed: the app
updates; on Wi-Fi, Parakeet Ultra starts downloading by itself with its notification; once it is ready, a take
is transcribed by Ultra (the speech service's log line names it); after that take the old model's space is
freed; a 20 s dictation into Messages keeps its last words.

## Preface — User Rubric

1. **Who.** Diana Foster, senior PM, dictating a 20 s status update into Slack between meetings; thirty seconds
   from now she is in the next meeting and will not reread it.
2. **Why.** "It keeps cutting off the end of what I said." She notices the last clause missing.
3. **Invocation.** Unchanged: side button, tile or bubble. The upgrade shows one download notification.
4. **Apps.** Every target app: the speech model sits before insertion, so Slack, Gmail, WhatsApp, Messages and
   Chrome all receive the improved text.
5. **Natural input.** "Okay so the vendor call moved to Thursday, can you push the design review to Friday and
   let Ana know." / "Quick quick, grab milk." / "Send the deck to Marcus, the one with the Q3 numbers." / a
   28 s paragraph read off a note / "Thanks, see you there."
6. **Success.** She notices nothing, except that the last sentence is always there.
7. **Wrong-not-broken.** The text arrives but the last clause is missing, so she quietly re-checks every
   message and stops trusting long dictation.
8. **Power-user hack.** Today: dictate in short bursts under 15 s. That is the workaround this removes.
9. **Control.** None wanted: one speech model, as today. The download respects Wi-Fi-only, storage-not-low,
   and the model card's existing Pause and Remove.

### Cross-persona check

Priya and Aaron: lower word error rate on every set measured (§1). Marcus: every word kept is the point. Elena:
audio still never leaves the phone; the download carries no user data. Meera and Frank: no prompt, the upgrade
starts by itself on Wi-Fi. Tension: an upgraded phone cannot dictate until the 668 MB download finishes (a few
minutes on Wi-Fi). Accepted in revision 2 (§3, rejected option a) to keep the heart path a single model; it is
the founder's call at Gate 2 (§14).

---

Consolidation: none. The change re-points one existing owner (`ModelManifest.parakeet`) and extends the existing
retired-folder sweep; no second owner of speech-model identity is created.

## 0. TL;DR

The shipped speech model (Parakeet v3 SmoothQuant int8, storage id `parakeet-sq`) drops the end of takes:
48 of 288 lost endings on the rebuilt endings set, and 131 of 272 lengths of one unedited 18 s sentence lose
their last 3 s (#458). Parakeet Ultra int8 ONNX, run through the app's own unchanged `TdtModel`/`TdtRecipe`,
loses 0 of 288 at 2.8% WER (today 12.6%). Same files, tokenizer and graph interface: a manifest change, not an
engine change. `ModelManifest.parakeet` becomes Ultra; the speech service loads only Ultra. An upgraded phone
gets Ultra's download enqueued automatically on Wi-Fi at app start; `parakeet-sq` is swept after the first
delivered Ultra result, or when the user removes the speech model. Proof: endings gate 0 lost, unit tests, the
S26 upgrade run.

## 1. Problem

Evidence, 2026-10-20, Mac M5 Max, the app's Kotlin through `scripts/eval/asr-recipe/golden` on desktop ORT
1.30, shipped files whose SHA-256 match `ModelManifest`, scorer and two-way control from #460:

| Engine, same 288 takes | Lost endings | WER |
|---|---|---|
| Android today, `parakeet-sq` | 48 | 12.6% |
| Standard v3 int8 ONNX (istupakov) | 66 | 19.2% |
| macOS FluidAudio v3 CoreML | 17 | 5.9% |
| macOS FluidAudio Ultra CoreML | 0 | 3.0% |
| **Ultra int8 ONNX, app recipe** | **0** | **2.8%** |

On 84 plain LibriSpeech sentences: today 2 lost endings, 4.3% WER; Ultra 0 lost, 2.0%. One-sentence sweep
(`ls-1320-122617-0035`, start trimmed in 10 ms steps): today 131 of 272 lose the last clause (the last window
decodes to zero tokens); Ultra 0 of 272. The control caught 288/288 injected lost endings on Ultra's output.
Not yet run on a phone.

## 2. Goals & non-goals

### 2.1 Goals
1. New installs download Ultra (onboarding, model card).
2. A phone with `parakeet-sq` on disk gets Ultra's download enqueued automatically at app start, Wi-Fi only,
   storage-not-low, unless the user paused or cancelled speech delivery.
3. `parakeet-sq` files leave the phone after the first delivered Ultra result, or with the user's Remove.
4. Endings gate: 0 lost endings for Ultra; WER no worse than 2.8% on the #460 set.
5. Notices and notification ids name Ultra.

### 2.2 Non-goals
- Serving `parakeet-sq` during the upgrade (revision 1's fallback, deleted per the pre-committed consequence).
- Real-length encoding and other speed levers (#453 next chunk); any `TdtRecipe` change.
- Lifting #449 device tiers; the w4a8 or fp16 Ultra variants.
- Hardening pre-existing recursive deletes in `ModelDelivery.kt` against nested symlinks (grounded r1 finding 4,
  HYPOTHETICAL, pre-existing): filed as a follow-up issue.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- `ModelManifest.parakeet` is the one speech descriptor every consumer reads (enumerated:
  `/usr/bin/grep -rn "ModelManifest\.parakeet\|ModelManifest\.all\|\"parakeet-sq\"" app/src/main app/src/debug app/src/androidTest`).
  Re-pointing it moves every consumer at once: `ModelWorkViewModel`, `ReadinessViewModel.speechModelReady`,
  `StoragePage`, `OnboardingViewModel`, `TranscriptionScreen`, `OnboardingScreen`, `ModelDeliveryWorker`,
  `AsrService.initRecognizer`, `PostHogSchema`/`SentrySchema`, `AppLaunchFacts`, `AsrSpeedDeviceTest`. No
  consumer needs a second meaning, so no reader changes (contrast revision 1, whose fallback needed a second
  readiness fact and missed its transfer in `OnboardingPolicy.kt` `withVerifiedModels`).
- Literal `"parakeet-sq"`: `ModelDeliveryNotification.notificationId` (`-> 1`), tests.
- Load: each take binds `AsrService` (`DictationSessionCoordinator.bindPipelineServices`) and `onCreate` loads
  `ModelManifest.parakeet` through `owner.load(AsrBounds.LOAD_BOUND_MS)`; unchanged.
- Delete: `deliverResult(..., onDelivered = ::sweepLegacyModelOnce)` → `LegacyModelSweep.sweep` (#374 names).
  Remove: `ModelDeliveryWorker` remove branch → `ModelDeliveryStore.remove(model)` + control clear.

### 2. Existing authority
Download, verify, admit: `ModelDeliveryWorker`, `ModelStorage` receipts. Constrained background download:
`ModelDeliveryWorker.enqueueSetup(context, model, mobileData = false)` (UNMETERED, storage-not-low, KEEP).
Retired folders: `LegacyModelSweep`. Cheap installed check without hashing:
`ModelDeliveryStore.installationStamp`. No new authority.

### 3. Prior attempts
#374 swapped in one build with no automatic delivery; users downloaded manually. macOS #3546 serves v3 during
the Ultra download; Android revision 1 tried that and was PIVOTed (grounded r1) for complexity on the readiness
path. Binding: one heavy model resident; warm-at-connect (#72).

### 4. Boundaries
- Bootstrap runs in the main process `Application.onCreate`; the new check runs on `Dispatchers.IO` in an
  application-owned scope and never blocks start (grounded r1 finding 2: `isVerified` hashes every file).
- The sweep runs in `:asr` after a delivered result and names only retired paths. The speech Remove runs in the
  main process worker and deletes Ultra on purpose, then sweeps the retired paths.
- A load that exceeds `LOAD_BOUND_MS` (60 s) makes the watchdog kill `:asr` (`AsrService` watchdog), as today;
  unchanged by this plan (grounded r1 finding 5 corrected the revision 1 claim).

### 5. High-risk premises
- Ultra on our code: measured on desktop ORT 1.30 (§1); Android ORT 1.30 already runs MatMulNBits 8-bit
  (`parakeet-sq`). NOT VERIFIED on a phone until the chunk 2 device run.
- Files: Ultra `int8/encoder-model.int8.onnx` 649,537,325 B
  `8a2b47169cf3f1b114010e12c0221bc6f07203289477a65a44847b9e3f1e01ed`; `int8/decoder_joint-model.int8.onnx`
  18,203,490 B `667ffde2242a560923cba6bc7f69ef03d736f8a36ca9588f2e6f9fa818cf2fc1`; `vocab.txt` 93,939 B
  `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` (identical to `parakeet-sq`'s).
  Repo `Olicorne/parakeet-tdt-0.6b-v3-ultra-onnx`, revision `3b80a92332b5d34c364a35a880ef049c7fede9f2`,
  CC-BY-4.0, base `moondream/parakeet-ultra` (post-train of `nvidia/parakeet-tdt-0.6b-v3`). Export changes:
  `<unk>` joint bias -1e4; encoder masks padded frames.

## 3. Design

1. **Manifest.** `ModelManifest.parakeet` becomes Ultra: id `parakeet-ultra`, display name "Parakeet Ultra",
   creator "NVIDIA, moondream", license CC-BY-4.0, files hosted under `parakeet-ultra/<revision>/` on
   `models.enviouslabs.co`, HF fallback for `vocab.txt` only (`validateModelSource` rejects the `int8/` path, as
   today). The SmoothQuant descriptor and its constants are deleted.
2. **Sweep.** `LegacyModelSweep.LEGACY` gains every retired path the old id can own (enumeration table below):
   `parakeet-sq`, `.parakeet-sq.download`, `.parakeet-sq.old`, `.parakeet-sq.adopt`,
   `.model-controls/parakeet-sq.state` and `.model-controls/.parakeet-sq.state.tmp`. It also sweeps direct
   children of the models root whose names begin `.parakeet-sq.download.quarantine-` (written by
   `ModelDelivery.quarantine` after an integrity mismatch) and leaves every other prefix untouched. It still runs only from `sweepLegacyModelOnce` after a delivered result,
   which can now only be an Ultra result, so a phone keeps `parakeet-sq` until Ultra has worked once (rollback
   safety, the #374 rule).
3. **Remove.** The worker's remove branch, for the speech descriptor, also calls `LegacyModelSweep.sweep`, so
   Remove frees the old model too and the upgrade trigger below stops firing (grounded r1 finding 3).
4. **Upgrade trigger.** `ModelBootstrapApplication`, main process, after the existing bootstrap enqueues,
   launches on `Dispatchers.IO` (application-owned scope) the pure check `upgradeOwed` (proposed): the folder
   `parakeet-sq` exists under the models root, `ModelDeliveryStore.installationStamp(ModelManifest.parakeet)`
   is null (Ultra not installed; no hashing; structural check only, full verification still gates loading), and
   `ModelDeliveryControlStore.read(ModelManifest.parakeet)` is `ACTIVE` after the control migration: under the control-store lock, an old PAUSED or CANCELLED value in
   `.model-controls/parakeet-sq.state` is inherited by Ultra only when Ultra has no record (otherwise Ultra's
   record is kept), and in either case the old record is then deleted; a migration interrupted between the two
   steps leaves both records, and the next run keeps Ultra's and finishes deleting the old one. So a user's earlier Pause or Cancel carries over
   and a later explicit Resume, Download or Update on the Ultra card (which clear or overwrite Ultra's record)
   still works. A queued old-id Remove that the new worker rejects (unknown id) leaves the old folder and a
   CANCELLED record; migration then keeps Ultra off, and the old folder stays until the user's Remove on the Ultra
   card (design 3) or a sweep. HYPOTHETICAL ordering; accepted. True → `enqueueSetup(context, ModelManifest.parakeet, mobileData = false)` (KEEP, so a running
   download is not restarted). A started worker that fails terminally returns failure; the next cold start
   re-checks and re-enqueues. A user's Pause or Cancel on the Ultra card writes PAUSED or CANCELLED and stops
   the trigger; Remove deletes `parakeet-sq` (design 3) and stops it.
5. **Old-id leftovers.** Same IO step: cancel unique work `model-download-parakeet-sq` and
   `model-adopt-parakeet-sq` by name (a surviving job would fail at the `ModelManifest.all` lookup before any
   mutation), and delete the notification channel `model_delivery_parakeet-sq`
   (`NotificationManager.deleteNotificationChannel` (external)). Notification id 43001 moves to
   `"parakeet-ultra" -> 1`, so an old notification is replaced.
6. **Notices.** The SmoothQuant entry in `THIRD_PARTY_NOTICES.txt` is replaced by the Ultra entry (moondream,
   NVIDIA, Olicorne export, changes: int8 MatMulNBits, `<unk>` bias, padding mask; used unmodified here).

**What an upgraded user sees.** After the update, until an unmetered connection is available, Transcription's
model card shows Queued (`ModelDeliveryUi`); Download and Retry on that card also require an unmetered
connection (`enqueue` constraints). Progress notifications appear once the worker starts, then Ready. A dictation
attempted before Ultra is ready ends on the existing model-not-loaded path, as any phone without a speech model
does today. Storage shows the old files under "Files no model claims" until the sweep.

**Rejected.** (a) Revision 1's transitional fallback (`parakeet-sq` serves until Ultra is ready): needed a
second readiness meaning across onboarding, card, controls and Storage; grounded r1 found a second missed
consumer of model identity after coverage found the first, triggering the pre-committed deletion. Cost
accepted: no dictation for a few minutes on Wi-Fi during the upgrade. (b) #374's manual-only download:
leaves upgraded users without dictation until they find the card. (c) Live reload inside `:asr`: deleted in
#374 after four review rounds. (d) Recipe patches for `parakeet-sq`: frame padding did not change the
failure (129/272).

## 3b. Ownership
The trigger lives in `ModelBootstrapApplication` beside the existing bootstrap enqueue (runs once per main
start); the retired-name list stays in `LegacyModelSweep`, its one owner.

## 4. Contract deltas
- `ModelManifest.parakeet` now describes Ultra (`parakeet-ultra`). `ModelManifest.all` = Ultra, S1.
- `LegacyModelSweep.LEGACY` also names the `parakeet-sq` folder, its partial download and its control record.
  Called after a delivered result (as today) and from the speech remove.
- Telemetry `model` values become `parakeet-ultra`, `s1-mini`; `parakeet-sq` can no longer be produced (the
  worker rejects ids outside `all` before emitting).

## 5. State and lifecycle audit

| Population | Enumerated |
|---|---|
| Phone states at first start of the new build | no speech model: onboarding downloads Ultra (unchanged path). `parakeet-sq` folder present, Ultra absent, control ACTIVE: Ultra enqueued on Wi-Fi. `parakeet-sq` partial only (`.parakeet-sq.download`, no folder): not enqueued (it was never usable); swept after Ultra's first result or on Remove. Ultra control PAUSED/CANCELLED (inherited by migration on first start, or set later by the user): not enqueued. Pre-#374 `parakeet` folder: unchanged sweep. |
| Takes | before Ultra is ready: model-not-loaded (existing); after: Ultra. Take in flight at admission: unaffected (it failed to load at bind). |
| Delete triggers | `sweepLegacyModelOnce` after a delivered result: retired paths only, never `parakeet-ultra`. Explicit speech Remove: deletes Ultra through `ModelDeliveryStore.remove` (intended) and also sweeps the retired paths; behaviour during a take unchanged from today. |
| Processes | main: bootstrap IO check, enqueue, remove worker; `:asr`: load, sweep. |
| Concurrency | remove worker (main) and sweep (`:asr`) can run together on the same retired names: each entry is deleted individually and absence is asserted (`LegacyModelSweep`); a double delete is a no-op. |
| Storage | peak during download: old 668 MB + Ultra 668 MB; `setRequiresStorageNotLow`. |

## 6. Consumer matrix

| Delta | Consumer | Current | Required | Change | Verified by |
|---|---|---|---|---|---|
| parakeet = Ultra | all §2.5.1 readers | parakeet-sq | Ultra | none (manifest only) | `ModelManifestTest`, emulator onboarding |
| retired names | `LegacyModelSweep`, `sweepLegacyModelOnce` | #374 names | + sq names | yes | `LegacyModelSweepTest` |
| remove frees old | `ModelDeliveryWorker` remove branch | Ultra only | + sweep for speech | yes | worker unit test |
| upgrade trigger | `ModelBootstrapApplication` | none | IO check + enqueue + old-work cancel + channel delete | yes | `upgradeOwed` unit test, device run |
| notification id | `ModelDeliveryNotification` | sq -> 1 | ultra -> 1 | yes | `ModelDeliveryNotificationTest` |
| notices | asset | SmoothQuant | Ultra | yes | `ThirdPartyNoticesTest` |
| telemetry pins | `PostHogSchemaTest`, `SentrySchemaTest` | sq | ultra | tests only | tests |
| device test | `AsrSpeedDeviceTest` | parakeet | parakeet (Ultra) | none | Test Lab |

## 7. Failure modes

| Failure | Origin | User sees | Persisted | Retry |
|---|---|---|---|---|
| No Wi-Fi after update | WorkManager constraint | card Queued, dictation unavailable (model-not-loaded) | none | when an unmetered connection appears; the card's Download/Retry also wait for it |
| Download fails terminally | worker | card failed state with its Retry | partial file | Retry, or next cold start |
| Ultra fails to load | `ParakeetEngine.open` | existing model-not-loaded; old files kept (no delivery) | none | next service creation retries; the card stays Ready while verification passes, so recovery is Remove then Download (no automatic Repair) |
| Load hangs past 60 s | watchdog | take ends as a lost process (today's behaviour) | none | next take |
| Sweep fails | file system | nothing; old files remain | none | next delivery, Remove |

## 8. Signals
- `AsrService` load log names the opened model id (new, content-free).
- `model_delivery` telemetry carries `parakeet-ultra`.

## 9. Fallbacks
No fallback model by design (§3 rejected a). The heart's existing floor for "no model" applies unchanged.

## 10. Chunks and files
- **Chunk 1, hosting:** upload the three Ultra files to `enviouslabs-models` under
  `parakeet-ultra/3b80a92332b5d34c364a35a880ef049c7fede9f2/` with a mint-use-revoke bucket token
  (`model-delivery.md`; `cloudflare.md` RULE r2-shared-across-projects: additive, new prefix; over-300 MiB path
  per the same file). Verify each object by ranged GET plus full SHA-256 of a full GET. Edge cache rule for the
  new prefix per `cloudflare.md` FACT model-delivery-edge-caching (small files; the encoder is over the 512 MB
  cap, like `parakeet-sq`'s). Cost: about 0.67 GB of R2, under one cent a month; founder approval (§14).
- **Chunk 2, app:** `ModelManifest.kt`, `LegacyModelSweep.kt`, `ModelDeliveryWorker.kt` (remove branch),
  `ModelBootstrapApplication.kt` (IO trigger), `ModelDeliveryNotification.kt`, `AsrService.kt` (log line),
  `THIRD_PARTY_NOTICES.txt`, comments naming SmoothQuant in `TdtModel.kt`, `TdtRecipe.kt`, `AsrService.kt`;
  tests in §11.
- **Chunk 3, knowledge:** `model-delivery.md`, `tech-stack.md`, `device-testing.md` (hf download recipe),
  `INDEX.md`, in place.

## 11. Testing
1. Classes: `upgradeOwed`, sweep and remove tests are Product Outcome ("an upgraded phone never gets Ultra" /
   "the speech model in use is deleted"); manifest, notification, notices and schema tests are Drift Guards;
   the endings gate is a Product Outcome evaluation outside the unit suite.
2. Reverts: drop any `upgradeOwed` clause → its row goes red; add `parakeet-ultra` to `LEGACY` → the "never
   names the current model" test goes red; drop the remove-branch sweep → the remove test goes red.
3. Not tested: WorkManager scheduling (library-owned); Android ORT numerics beyond the device run.

### 11.1 Hardware UAT
- Subsystem: heart path (ASR).
- S26 via Play internal, `parakeet-sq` installed, data kept: update; on Wi-Fi the Ultra notification appears by
  itself; after it completes, one take (log names `parakeet-ultra`, `AsrService created` per take); the sweep
  log line reports the freed bytes and Storage's total drops by about 668 MB. A 20 s take into Messages through
  the silent helper, judged by the editor's own text containing its last clause.
- Test Lab (free allowance): `AsrSpeedDeviceTest` with Ultra on A35 and A15: load time and decode time against
  today's numbers.
- Restore: nothing beyond the app update.

### 11.2 Obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `ModelManifestTest` (updated) | Drift | Ultra id, files, bytes, hashes, prefix, revision; no `parakeet-sq` in `all` | edit any pin |
| `upgradeOwed` test (proposed) | Product | true only for old folder present + Ultra absent + ACTIVE; old PAUSED and old CANCELLED migrate and block; a later explicit Ultra Resume unblocks; both records surviving an interrupted migration keeps Ultra's value and removes the old one | drop a clause or the migration |
| `LegacyModelSweepTest` (updated) | Product | removes the sq names, the control record and a `.parakeet-sq.download.quarantine-*` folder; never the current model's folder | add the current id or drop the prefix rule |
| remove-branch test (proposed) | Product | speech Remove also frees `parakeet-sq` | drop the call |
| `ModelDeliveryNotificationTest`, `ThirdPartyNoticesTest`, `PostHogSchemaTest`, `SentrySchemaTest` | Drift | new id | old id |
| Endings gate (#460) | Product (eval) | 0 lost endings, WER ≤ 2.8% | SmoothQuant files |

## 12. Blast radius & rollback
Touched: models package, bootstrap, `AsrService` log line, notices, tests, knowledge. Not touched: recipe,
engine, readiness, onboarding, UI code, audio, polish, insertion, AIDL. Rollback: revert the commit; a phone that
already swept `parakeet-sq` then shows Parakeet as Missing and downloads it again (the #374 state); mitigated by
internal testing and the founder phone pass before production.

## 13. Ship criteria
- [ ] On the S26 via Play internal, an upgraded install downloads Ultra by itself on Wi-Fi, then transcribes
      with Ultra and frees the old model's space.
- [ ] A 20 s dictation into Messages keeps its last clause.
- [ ] Endings gate: 0 lost endings for the shipped Ultra files.

## 14. Open questions (founder, Gate 2)
1. Approve the R2 upload (under one cent a month).
2. Approve the automatic Wi-Fi download for upgraded users, and the trade-off that they cannot dictate for the
   few minutes until it finishes.

## Id-keyed persisted state, enumerated from the producers (grounded r2 consequence)

From `/usr/bin/grep -rn "model\.id" app/src/main/java/com/envi/wispr/models` (plus `ModelStorage`):

| State keyed by id | Producer | Disposition for `parakeet-sq` |
|---|---|---|
| final folder `<id>` | `ModelDelivery.finalDirectory`, `ModelStorage.directory` | swept (§3.2) |
| `.<id>.download`, `.<id>.old`, `.<id>.adopt` | `ModelDelivery` staging, replace, adopt | swept (§3.2) |
| `.<id>.download.quarantine-<timestamp>` | `ModelDelivery.quarantine` (via `staging.name`) | prefix-swept at the same gates (§3.2) |
| `.model-controls/<id>.state`, `.<id>.state.tmp` | `ModelDeliveryControlStore` | migrated, then swept (§3.4, §3.2) |
| unique work `model-download-<id>`, `model-adopt-<id>`; queued work input `KEY_MODEL_ID` | `ModelDeliveryWorker` names and enqueues | names cancelled at bootstrap; any surviving job fails at the `all` lookup before mutation (§3.5) |
| notification channel `model_delivery_<id>`, id 43001, action extra `EXTRA_MODEL_ID` | `ModelDeliveryNotification` | channel deleted, id reused by Ultra, old actions return at the `all` lookup (§3.5) |
| external `models/<id>` adoption source | `ModelDeliveryWorker` adoption | none for `parakeet-sq` (never shipped there); Ultra looks for `models/parakeet-ultra`, absent; unchanged |
| folder-name ownership in Storage | `ModelFootprint.measureFolder` | old folder counts as unclaimed until swept (§3) |
| telemetry `model` value | `ModelDeliveryWorker` events | cannot be produced for the old id (§4) |

## Review dispositions
- Coverage r1: gaps 1-3 (old controls, old work, channel) adopted in §3.4-3.5; 4-7 and 10 were consequences of
  the revision 1 fallback and are moot after the pivot; 8 corrected in §2.5.4; 9 adopted in §3.4.
- Grounded r1 (PIVOT): 1 adopted (fallback deleted, pre-committed consequence); 2 adopted (IO scope, no hashing:
  `installationStamp`); 3 adopted (Remove sweeps the old model); 4 HYPOTHETICAL and pre-existing in
  `ModelDelivery.kt`, routed to a follow-up issue, the sweep keeps its existing link handling; 5 adopted
  (§2.5.4).
- Grounded r2 (PROCEED-WITH-REVISIONS): 1 adopted (control migration, §3.4) and the class enumerated above per
  the pre-committed consequence; 2 adopted (Remove deletes Ultra intentionally, §2.5.4, §5); 3 adopted (Queued,
  unmetered-only card actions, §3, §7); 4 adopted (§7).
- Grounded r3 (PROCEED-WITH-REVISIONS): 1 adopted (quarantine prefix, §3.2, table, test); 2 adopted (locked,
  idempotent migration, §3.4, §5, test).

## 15. Related
#458, #453, #460, #374, #449, #433; macOS #3546.
