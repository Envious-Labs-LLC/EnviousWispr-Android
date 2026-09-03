# Issue #36 — Parakeet v3: one engine, 25 languages — 2026-09-02

GitHub issue: `#36`. Tier: MEDIUM. Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code — `app/src/main/java/com/envi/wispr/models/ModelManifest.kt`,
`app/src/main/java/com/envi/wispr/ui/TranscriptionScreen.kt`,
`app/src/test/java/com/envi/wispr/models/ModelManifestTest.kt`, plus `.claude/knowledge/` edits that ride
along. Detection is `Code` because `app/**` changes; the knowledge edits do not add a second lane token,
they are carried by the `Code` lane's obligations.

**PAR rows closed:** `none`. `PAR-030` moves but does not close: the user still gets ONE engine, so there is
no choice to make. `PAR-034`, `PAR-035`, `PAR-036`, `PAR-037` are untouched and §2.2 says why.

**Hardware UAT:** Y

Success on the S26: the founder dictates an English sentence into Messages and it lands exactly as it does
today, then dictates a German sentence and German words land instead of English nonsense. Both are checked
against the words actually spoken, not against the app not crashing.

## Preface — User Rubric

**Persona: Marcus Weber**, writer and journalist with early RSI, native German, drafting in a text editor.

1. **Who, in this moment?** Marcus is thirty seconds into a paragraph of a German feature draft. His wrists
   hurt. He wants the next two paragraphs out of his head and into the document without typing them.
2. **Why would he want this?** "Ich kann endlich auf Deutsch diktieren." Today he presses the button, speaks
   German, and the app writes English-looking garbage, so he stopped pressing it.
3. **How would he invoke it?** The same Samsung side button he already double-presses. Nothing new to learn.
   Reactive, mid-draft, already in the right app.
4. **What app?** His editor, plus Slack and email for the same draft's follow-ups.
5. **Natural input, in his voice:** *"Der Zug war schon wieder zwanzig Minuten zu spät."* ·
   *"Ich schreibe das Stück über die Wohnungskrise in Leipzig."* · *"Kannst du mir bis Freitag antworten?"* ·
   *"Das Zitat stammt aus dem Interview vom Dienstag."* · *"Neuer Absatz, und dann die Zahlen."*
6. **Success feels like** nothing. The German words are on screen and he keeps talking.
7. **Wrong-not-broken** looks like German that transcribes correctly and then gets mangled on the way out,
   because the tidy-up step still assumes English. He does not report it; he goes back to typing. §7 owns it.
8. **A power user hacks around this** by switching the phone keyboard to Gboard voice typing, which is
   exactly the product we are trying to replace.
9. **Control he would want:** the ladder here is off · automatic · pinned to one language. This change
   delivers automatic only, and §2.2 states that the pinned rung is not available on this engine.

### Cross-persona check

Priya Ramachandran, Diana Foster, Aaron Wu and Frank Chen dictate in English and must notice NOTHING; an
English regression is the failure that matters most to four of seven personas, and §11 makes English the
control, not an afterthought. Dr. Elena Vasquez gains nothing and loses nothing: the model still runs on the
phone and still downloads from the same pinned host. Meera Patel gains a 670 MB download she did not ask
for if she is mid-onboarding when this ships, which is a Stage 2 concern with no users today. Marcus Weber
is the only persona who gains, and the gain is total. **The tension to resolve is Marcus versus the four
English speakers**, and §3 resolves it by measuring English side by side before shipping rather than by
argument.

---

## 0. TL;DR

The phone runs Parakeet TDT 0.6b **v2**, which is English only, while Windows runs **v3**, which covers 25
European languages. The two models are the same NeMo TDT transducer family, ship the same four file names,
and differ by 9.3 MB. The fix is a manifest re-pin, the four on-screen sentences that promise English, one line in `AsrService`
pinning the decoding method the runtime already uses, and a delivery regression test.
MEDIUM tier, because it is the heart path and it invalidates the model already on the phone. Proof is
hardware: an English control and a German case dictated into a real editor on the S26.

## 1. Problem

`app/src/main/java/com/envi/wispr/models/ModelManifest.kt:27` pins
`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`. NVIDIA's `parakeet-tdt-0.6b-v2` is trained on
English only. `app/src/main/java/com/envi/wispr/ui/TranscriptionScreen.kt:106` tells the user so:
"EnviousWispr transcribes English today."

Windows ships v3 (`~/Developer/EnviousLabs/enviouswispr-windows/models/parakeet-tdt-0.6b-v3`) and macOS
offers Parakeet plus WhisperKit. Android is the only platform of the three that transcribes one language.
Nothing in the repository records a decision to take v2; the catalog's `decision` table has no row about it.

Issue #36 states the cost in reach terms: "English only puts a hard ceiling on that, and language support is
the first thing a non English speaker checks."

## 2. Goals & non-goals

### 2.1 Goals

1. Dictation transcribes a non-English language on the S26, verified for German against the words actually
   spoken. **The other 24 languages rest on NVIDIA's model card, not on evidence from this device**, and
   §13 says so rather than claiming them.
2. English transcription quality does not regress, verified on the S26 with the same spoken sentences run
   against both models.
3. Every on-screen sentence that promises English is true after the change.
4. An existing install with v2 admitted is offered `Update available` and reaches Ready on the new model
   without a manual wipe.

### 2.2 Non-goals

- **A second engine.** Whisper-class coverage of the other ~74 languages is separate work. Issue #36 stays
  open for it.
- **A language picker (`PAR-035`).** Parakeet v3 is a transducer with a unified tokenizer; the language is
  decoded, not selected. There is no parameter to pin.
- **Language detection or an observed-language readout (`PAR-034`).** The catalog states outright that
  Parakeet Auto is multilingual decoding WITHOUT language detection. `OfflineRecognizerResult.getLang()`
  (external) is deliberately unmeasured: `asr/AsrService.kt` keeps only `getText()`, and no runtime
  behaviour reads that field. §8 records the decision.
- **Engine cards showing measured tradeoffs (`PAR-031`).** There is one engine; a card comparing it to
  nothing is not honest.
- **Making the deterministic cleanup layer language-aware.** Real, out of scope, and filed as
  [issue #107](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/107). See §7.
- **Fixing the missing Parakeet entry in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`.** That file names no
  speech model today, which is issue #15's job, not a defect this change introduces.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

```
/usr/bin/grep -rn "ModelManifest.parakeet" app/src/main/
```

| Hop | Mechanism | Cite |
|---|---|---|
| Manifest declares the four files, sizes, hashes and pinned URLs | compiled-in `ModelDescriptor` | `models/ModelManifest.kt:33-38` |
| `ModelDeliveryWorker` downloads, verifies, admits atomically | WorkManager unique work per model id | `models/ModelDeliveryWorker.kt:222-238` |
| `ModelDeliveryStore.isVerified` gates readiness on the receipt AND an exact file-name set AND every byte count AND every SHA-256 | file system, synchronized | `models/ModelDelivery.kt:60-67` |
| `ModelDeliveryStore.needsUpdate` compares the on-disk receipt text to the manifest's | file system | `models/ModelDelivery.kt:69-75` |
| `ModelStorage.isReady` is the only thing `AsrService` asks | delegation | `models/ModelStorage.kt:10` |
| `AsrService.initRecognizer` builds the recognizer from FIXED file names and `modelType = "nemo_transducer"`, in process `:asr` | bound service, separate process | `asr/AsrService.kt:149-184` |
| Recognized text crosses to the app process | AIDL `IAsrCallback` | `asr/AsrService.kt:44-73` |
| `PolishPipeline.run` applies `DeterministicCleanup` FIRST, unconditionally, before any model | pure function | `cleanup/PolishPipeline.kt:36` |
| The UI reads delivery state and renders the card and the English sentences | Compose | `ui/TranscriptionScreen.kt:43-106` |

**The load-bearing hop is `AsrService.kt:159-170`: it names `encoder.int8.onnx`, `decoder.int8.onnx`,
`joiner.int8.onnx` and `tokens.txt` as literals and sets one model type.** v3 ships those same four names
and is the same `nemo_transducer` family, so **the model wiring in this file needs no change**. §10 adds one
unrelated line here — the decoding-method pin — which touches no file name and no model type. That is the
whole reason this is a re-pin and not an engine port.

### 2. Find the existing authority before proposing one

There is no new authority to propose. `ModelManifest` already owns model identity, `ModelDeliveryStore`
already owns verification and staleness, and `ModelDeliveryUi` already owns the `Update available` state
(`models/ModelDeliveryUi.kt:69-70`, `models/ModelDeliveryUi.kt:82-83`). The update path this change relies
on is `ModelDeliveryWorker.enqueueUpdate` at `models/ModelDeliveryWorker.kt:227`, already wired to the card's
`ModelUiAction.UPDATE` at `ui/TranscriptionScreen.kt:64`.

Negative sweep for a language capability, run over the working tree including the gitignored `.claude/`:

```
/usr/bin/grep -rniI "languagePicker\|autoDetectLanguage\|languageMode\|LanguageOption\|localeTag" app/src/
```

returns nothing, which matches issue #36's own sweep. Open-world phrasing: no language-selection
implementation was found in `app/src/`.

### 3. Read prior attempts and live direction

- Issue #36 is open, labelled `parity` and `priority:medium`, and had no comments before this change's Gate 0
  note.
- `.claude/knowledge/session-log.md` records no session that touched the speech engine; recent work is AI
  Polish (#103, #104, #105, #106).
- Catalog `decision`: *"Parakeet remains the fresh-install default final-recognition engine"* (2026-05-30).
  **This change honours that decision rather than redesigning it** — Parakeet stays the default; only its
  version moves.
- Catalog `decision` 2026-08-20: *"Protect er and um in locked German and protect er in locked Dutch,
  Danish, and Norwegian; do not infer protections for additional languages without a new grounded entry."*
  macOS solved the cleanup-versus-language collision **only for a LOCKED language**. Android has no lock and
  cannot get one on this engine, so that decision cannot be applied here. §7 carries the consequence.
- Catalog `decision` 2026-09-01: a deterministic-cleanup fix is not proven until real recogniser output has
  been through it. This change does not touch cleanup, so the decision binds the FOLLOW-UP issue §7 files,
  not this plan.
- Founder direction 2026-09-02, in this session: ship the stronger Parakeet as v1 and defer the
  Whisper-class second engine.

### 4. Lifecycle, trust and process boundaries a naive design would miss

| Boundary | Current | After |
|---|---|---|
| App process vs `:asr` | `:asr` holds the recognizer for the process lifetime; `initRecognizer` runs once on create (`asr/AsrService.kt:137`) | Unchanged. **A `:asr` process alive from before the model swap keeps the OLD recognizer object in memory even after the new files land.** The process must die before the new model is used. |
| Admitted model vs manifest | Receipt matches, `isVerified` true, dictation works | The instant the new build installs, `isVerified` is FALSE and `needsUpdate` is TRUE, because the receipt text embeds the revision and every size and hash. **Dictation stops working until 670 MB has downloaded and verified.** |
| Atomic admission | Staging directory renamed over the final one, old moved aside and deleted (`models/ModelDelivery.kt:183-187`) | Unchanged and relied upon: same four file names means the rename is the only thing preventing a mixed v2/v3 directory. |
| Download constraints | `NetworkType.UNMETERED` and `setRequiresStorageNotLow` (`models/ModelDeliveryWorker.kt:234`) | Unchanged. On mobile data the update will not start, and the card will sit at Queued. |
| Hand-placed model on the founder's phone | Stage 1 files placed by the `PROCEDURE: place-a-model-by-hand` recipe | Invalidated by this change. The recipe must be re-run with v3 values, receipt regenerated from the manifest. |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| v3 exists in sherpa-onnx form with the SAME four file names | Hugging Face API listing of `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`, plus `test_wavs/` and `.gitattributes` |
| Byte counts and hashes | encoder 652,184,281 / `acfc2b44…`; decoder 11,845,275 / `179e50c4…`; joiner 6,355,277 / `3164c13f…`; tokens 93,939 / `d5854467…` (downloaded and hashed locally with `shasum -a 256`, because `tokens.txt` is not LFS so the API carries no oid for it) |
| Head revision to pin | `2bda32ec70b097a55adaa07d9a7173915b43cc78`, last modified 2025-08-16 |
| v3 covers 25 languages, and WHICH | `nvidia/parakeet-tdt-0.6b-v3` card data: en, es, fr, de, bg, hr, cs, da, nl, et, fi, el, hu, it, lv, lt, mt, pl, pt, ro, sk, sl, sv, ru, uk |
| Licence is unchanged | `nvidia/parakeet-tdt-0.6b-v3` is `cc-by-4.0`, identical to the `CC-BY-4.0` already declared at `models/ModelManifest.kt:33` |
| Our pinned runtime can load it | The AAR reports `1.12.29` (`strings` on `libsherpa-onnx-jni.so`); the v3 repository was published 2025-08-16, well before it. **Expected, NOT measured — §11 measures it, and it is the first thing built.** |
| Parakeet cannot report a detected language | Catalog `catalog_gap` (external) on `automatic-language-detection`: *"Parakeet Auto is multilingual decoding without language detection; only WhisperKit recognition and Universal preview perform real LID."* `OfflineRecognizerResult.getLang()` (external) exists in the AAR but is a SenseVoice-shaped field. **Deliberately unmeasured** — see §2.2 and §8. |
| The cleanup layer is English-shaped | `cleanup/DeterministicCleanup.kt:16` fillers `um|uh|erm|err|ah|hmm|hm|mhm`; `cleanup/DeterministicCleanup.kt:34` maps the bare token `"o"` to the number 0; `cleanup/DeterministicCleanup.kt:405` converts standalone number-word runs |
| Cleanup runs on every dictation before anything else | `cleanup/PolishPipeline.kt:36`, unconditional, ahead of every early return |

**Upstream defect pass, run against the sherpa-onnx repository because the runtime is somebody else's
code.** Four known reports touch exactly this model and runtime, and each is resolved here rather than
carried as an unknown.

| Upstream | What it says | Resolution for us |
|---|---|---|
| PR `#2606` (external), merged 2025-09-18 as `9102f34179d1` | NeMo TDT greedy decoding dropped words until a per-frame token cap was added | **Contained, proven by ancestry rather than by dates.** A date only shows order, and says nothing about a revert. `GET /repos/k2-fsa/sherpa-onnx/compare/9102f34179d1...v1.12.29` returns `status: ahead`, `behind_by: 0`, which is the API's way of saying the tag contains the commit. No regression clip is needed. |
| Issue `#3267` (external), open, filed 2026-03-07 | `modified_beam_search` (external) with Parakeet TDT hallucinates or returns empty about 20% of the time; `greedy_search` (external) works | **Not reachable today, and pinned anyway.** `OfflineRecognizerConfig.decodingMethod` (external) defaults to `"greedy_search"` at the v1.12.29 tag (upstream `kotlin-api/OfflineRecognizer.kt` line 146, read from the tagged source) and `asr/AsrService.kt` never sets it. Because the next planned change is an AAR upgrade, §10 sets it EXPLICITLY so a changed upstream default cannot silently reach this model. **The pin has NO runtime oracle** — a successful transcription cannot distinguish an explicit greedy from a default greedy — so its evidence is the tagged-source read above, and §11 says plainly that nothing tests it. |
| Issue `#2842` (external), 2025-12-01 | Loading Parakeet v3 int8 threw an `Ort::Exception` on opset validation | **A named risk with an oracle, not a proven blocker.** The reporter built their own ONNX Runtime capped at opset 16; our AAR bundles its own runtime from 2026-03. §7 carries the row so that a first-load failure is read as an opset mismatch rather than as a corrupt file. |
| Issue `#2626` (external), 2025-09-24 | Parakeet v3 int8 measured at 1.23 GB RAM | **Expected to be unchanged from today, and observed rather than assumed.** The maintainer's answer is that loading alone is around 600 MB and computation adds more, and the 1.23 GB figure was the CoreML provider on iOS; we run CPU on Android, and v2's encoder is the same 652 MB. §11 records peak `:asr` memory instead of predicting it. |

No Codex problem-only consult was run before §3: the who-calls-whom question was answered by the trace in
§2.5.1 with no uncertainty left, and no negative claim here rests on local evidence alone.

## 3. Design

**Re-pin the model descriptor to v3, correct the four Transcription strings, add the delivery regression
test, and explicitly preserve greedy decoding in `AsrService`. The recognizer's file names and model type
stay exactly as they are.**

The delivery layer, the verification rules, the update path and the UI states all already work in terms of
the manifest, so the manifest is where the change belongs. The one line added to `AsrService` is not part of
the swap: it pins a value the runtime already uses, ahead of the AAR upgrade that could change it (§2.5.5).

**Resolving the §Cross-persona tension.** Four personas dictate English and must not regress; one gains
everything. The resolution is not an argument, it is a measurement: §11 dictates the SAME English sentences
through v2 and v3 on the same phone and compares the text, and the plan does not ship if English degrades
in a way the founder can hear.

**Alternatives rejected.**

- **Add v3 as a SECOND descriptor and let the user choose v2 or v3.** Rejected: it doubles resident model
  storage to 1.33 GB for a choice between "English" and "English plus 24 more", and `PAR-031` says an engine
  card must show an honest tradeoff. The honest card would have to compare English quality, latency and memory between two versions of one
  engine, and §11 measures exactly those instead of asserting them.
- **Wait and do this together with the Whisper second engine.** Rejected: it holds 24 languages hostage to
  a much larger change, and the founder's direction 2026-09-02 was explicitly to ship the Parakeet upgrade
  as v1.
- **Make cleanup language-aware in the same change.** Rejected: with no language signal from this engine
  there is nothing to switch on, so it would be a guess wearing a fix's clothes. §7 files it instead.

## 3b. Ownership justification

No coordinator is added or extended. Model identity already lives in `ModelManifest` because it is the one
compiled-in authority `ModelDeliveryStore`, `ModelDeliveryWorker`, `ModelDeliveryUi` and `AsrService` all
read; the alternative was a per-engine descriptor owned by the ASR layer, whose trade-off is that delivery
would then need to discover engines instead of being told about them.

## 4. Contract deltas

| Symbol | Semantic change |
|---|---|
| `ModelManifest.parakeet` | Same id `parakeet`, same four file names, same licence, same engine string. What changes is WHICH weights it names and therefore what the app can understand. Every consumer keying on the id is unaffected; every consumer comparing the receipt now sees a mismatch, by design. |
| `ModelDescriptor.pinnedRevision` for `parakeet` | `1ab93235…` becomes `2bda32ec…`. This value is embedded in the receipt text (`models/ModelDelivery.kt:200`), so the change alone flips `needsUpdate` to true and `isVerified` to false on every existing install. |

No type, signature or enum changes.

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every file naming `ModelManifest` | Producer sweep, pasted: `/usr/bin/grep -rln "ModelManifest" app/src` returns `asr/AsrService.kt`, `models/ModelBootstrapApplication.kt`, `models/ModelDeliveryNotification.kt`, `models/ModelDeliveryWorker.kt`, `models/ModelManifest.kt`, `polish/S1ModelSelection.kt`, `ui/AppShell.kt`, `ui/AppViewModel.kt`, `ui/ModelCards.kt`, `ui/PolishLadder.kt`, `ui/PolishScreen.kt`, `ui/SettingsActivity.kt`, `ui/TranscriptionScreen.kt`, plus the tests `models/ModelManifestTest.kt` and `ui/PolishLadderTest.kt`. Of those, the ones naming `ModelManifest.parakeet` specifically are `asr/AsrService.kt`, `models/ModelBootstrapApplication.kt`, `ui/AppShell.kt`, `ui/AppViewModel.kt`, `ui/ModelCards.kt`, `ui/TranscriptionScreen.kt` and `models/ModelManifestTest.kt`; the rest reach `ModelManifest.s1`. **None hard-codes a revision except the test.** |
| Every reachable state during the swap | From `models/ModelDeliveryUi.kt` and `DownloadState` in `models/ModelDelivery.kt`, all thirteen: Missing, Ready-on-v2, Update available, Queued, Downloading, Paused, Verifying, Checking, Ready-on-v3, Cancelled, Failed, Update failed, Repair needed. The plan's earlier six-state sketch was a description, not an enumeration. |
| Processes holding a recognizer across the swap | Exactly one, `:asr`, created at `asr/AsrService.kt:137` and released in `onDestroy` (`asr/AsrService.kt:140-147`). It has no reload path, so a live `:asr` survives the file swap holding v2 weights. Handled by §11's force-stop step, not by new code. |
| Every writer and every reader of the persisted engine label | Writers: the literal `"Parakeet"` at `ui/DictationSessionService.kt:393`, `:705` and `:801`. Reader: `ui/HistoryScreen.kt:263`, which renders `transcript.speechEngine` beside the timestamp as raw text. The literal is unchanged by this plan and no reader parses a version out of it, so **old rows stay truthful and no migration is needed**. |
| Anything hard-coding the old revision | `app/src/test/java/com/envi/wispr/models/ModelManifestTest.kt:12` and the recipe in `.claude/knowledge/model-delivery.md`, from `/usr/bin/grep -rn "1ab9323565ddb038682214b292f588070a538ce2" .` |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| New pinned revision | `ModelDeliveryStore.needsUpdate` | Receipt matches, false | Mismatches, true | No | `ModelDeliveryStoreTest` (existing) |
| New pinned revision | `ModelDeliveryUi` | Ready | `Update available` with `ModelUiAction.UPDATE` | No | `ModelDeliveryUiTest` (existing) |
| New pinned revision | `ModelManifestTest` | Asserts `1ab93235…` | Asserts `2bda32ec…` | **Yes** | Itself |
| New weights | `AsrService.initRecognizer` | Loads v2 | Loads v3 from identical file names and model type | No | §11 hardware run |
| New weights | `DeterministicCleanup` | Only ever sees English | Can now see 25 languages, and is still English-shaped | **No, deliberately** | §7 and the filed follow-up |
| Broader language claim | `ui/TranscriptionScreen.kt:54`, `:56`, `:104`, `:106` | Promises English, under an "Other languages" heading | Says what is now true | **Yes** | Compose text read on the device |
| Broader language claim | every other user-visible string | Sweep `/usr/bin/grep -rniI "english" app/src/main/java app/src/main/res` returns only the four `TranscriptionScreen.kt` lines above plus two code comments (`ui/ModelCards.kt:173`, `cleanup/DeterministicCleanup.kt:16`) | No further string changes | No | The pasted sweep |
| New weights | `ui/HistoryScreen.kt:263` | Renders the stored engine label as raw text | Unchanged; the label stays `Parakeet` | No | Reading a pre-swap row after the swap |
| Decoding method | `asr/AsrService.kt` | Never set, so the sherpa-onnx default `greedy_search` applies | Set EXPLICITLY to `greedy_search` | **Yes** | **Nothing.** It is unobservable from Kotlin and indistinguishable at runtime from the default it pins. It is justified as drift protection against the AAR upgrade, on the tagged-source evidence in §2.5.5, and §11.1 does not pretend the hardware run checks it. |
| New file sizes | `ui/ModelCards.kt` `formatModelBytes` | Renders v2 total | Renders 670.5 MB, decimal | No | Existing `PolishLadderTest` pins the decimal convention |
| Model on the founder's phone | hand-place recipe in `model-delivery.md` | v2 values | v3 values and a regenerated receipt | **Yes, doc** | §11 run itself |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| v3 rejected on ONNX opset validation | opset mismatch inside ONNX Runtime, the shape of upstream `#2842` (external). **It may raise a catchable exception OR terminate `:asr` natively** — the upstream report is a `terminate called after throwing`, which the Kotlin catch at `asr/AsrService.kt:180` cannot see | `AsrService.initRecognizer`, or the process itself | Speech engine not ready; dictation refuses. **First load fails if the recognizer does not reach Ready, OR the `:asr` process exits or restarts, OR logcat records the opset error** — all three are checked, because the catch alone would miss the crash | Files stay admitted; `modelReady` false, or the process is gone | **Kills the plan as scoped.** The fix would become the AAR upgrade, and §12's rollback restores v2. Named separately from a corrupt file so the logcat is read correctly. |
| v3 loads but decodes badly | model or decoding-method mismatch | `AsrService` | Text appears and is wrong | Wrong text is inserted and stored | §11's English control is what catches this; a crash would be easier |
| Update never starts | `NetworkType.UNMETERED` unmet (`models/ModelDeliveryWorker.kt:234`) | `ModelDeliveryWorker` | Card sits at Queued | Control state ACTIVE | Connect to Wi-Fi |
| Transport, HTTP, redirect or timeout failure | `models/ModelDelivery.kt` transfer path | `ModelDeliveryWorker` | `Failed`, or `Update failed` when updating, with RETRY or UPDATE offered | Resumable partial retained where the host allows ranges | RETRY |
| Byte-count or SHA-256 mismatch after transfer | verification, not transport | `ModelDeliveryWorker` | `Repair needed` | Quarantined | REPAIR |
| Insufficient storage during download or adoption | the space checks at `models/ModelDeliveryWorker.kt:63` and `:73` | `ModelDeliveryWorker` | Failure naming storage, before anything is copied | v2's FILES are left untouched, but they are **not Ready**: `ModelStorage.isReady` asks `isVerified` with the newly compiled v3 descriptor, which those files fail. The failed update renders **`Update failed`** with the UPDATE action (`models/ModelDeliveryUi.kt:55`, the `staleInstalled` branch), so the user has no working engine | Free space, retry. The threshold is REMAINING bytes plus 128 MiB, not the full total — `models/ModelDeliveryWorker.kt:77` subtracts any `.part` already on disk |
| Admission I/O failure | the atomic rename at `models/ModelDelivery.kt:183-187` | `ModelDeliveryWorker` | `Failed` | v2 restored from `.parakeet.old` | RETRY |
| `:asr` killed for memory while loading v3 | native allocation, the shape of upstream `#2626` (external) | Android LMK | Dictation appears to hang, then the engine is not ready | None | §11 records peak memory so this is observed, not guessed |
| Stale `:asr` process serves v2 after v3 lands | Process outlived the swap | `AsrService` | English-only results with a v3 model on disk, and nothing says why | None | Force-stop. **No code guards this; it is a Stage 1 dev-loop hazard, named so it is not misread as a model defect.** |
| **Foreign-language text mangled by English cleanup** | `cleanup/DeterministicCleanup.kt`, applied unconditionally at `cleanup/PolishPipeline.kt:36` | Every dictation | Correct foreign words come back subtly wrong. Marcus does not report it; he stops using it (§Rubric 7) | The mangled text is what got inserted and what history stores | **Not fixed here.** See below. |

**The cleanup collision is a PIPE problem, and this change deliberately ships no bucket for it.** The leak is
not the two examples first noticed (the filler list at `cleanup/DeterministicCleanup.kt:16`, and the bare
token `"o"` mapping to zero at `:34`). Enumerated from the producing code rather than from those two, the
English-shaped transformation families are: fillers, spoken emoji, spoken punctuation, email, URL, decimal
and `point`, money, percentage, time, date, ordinal, year, digit runs, digit scales, **ranges and
dimensions**, **dosage runs**, cardinal conversion, **magnitude preservation**, and sentence capitalization.
Plus the safety layer at `cleanup/DeterministicCleanup.kt:560-570`, which is **not only length ratios**: it
carries a word-count content-drop ratio and `looksLikeQuestion`, an English question detector built from
English auxiliaries and English leading fillers. Every one is reachable on foreign text because
`PolishPipeline.run` calls cleanup before every early return.

A per-item bucket would need nineteen of them. The pipe fix is to gate the English families on a known
language, and **this engine cannot supply one** — which is exactly why it is filed as
[issue #107](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/107) rather than guessed at
here. macOS already made the narrow version of this call (catalog decision 2026-08-20:
protect `er` and `um` in LOCKED German, and do not infer protections for further languages without new
grounded evidence), and it depended on a language LOCK that Parakeet cannot offer. Cloud polish is already
safe: `providers/ProviderPolishPrompt.kt:15-17` carries an unconditional rule to keep the text in the same
language and never translate.

## 8. Caller-visible signals audit

- **Receipt text (`models/ModelDelivery.kt:200`)** — its VALUE carries "which manifest generation is on
  disk". This change is entirely an act on that signal, and the whole update flow is downstream of it.
- **`modelReady` in `:asr`** — its value carries "the recognizer object exists", NOT "the files on disk are
  current". Its staleness is the §7 stale-process row.
- **`OfflineRecognizerResult.getLang()`** — present in the AAR and **deliberately unmeasured by this
  change**. `asr/AsrService.kt` keeps only `getText()`, and no runtime behaviour reads the field, so
  observing it would mean instrumenting the heart path to answer what the catalog already answers. **Its
  absence must not be read as "detection failed"**; this engine never reports one. Recorded here as a
  decision, so the next session reads it instead of re-deriving it.
- Every other signal: not present in this change.

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| v3 will not load | none | — | There is no degraded transcription to fall back to; a recognizer either exists or does not | — | `modelReady = false` and the existing not-ready UI, which is a BYPASS, not a fallback: nothing claims a transcription happened | `AsrService`, Transcription page |
| Update half-lands | **none qualifies** | — | The v2 directory survives byte-identical, because a hash or byte-count mismatch quarantines the staging copy at `models/ModelDelivery.kt:129-131` before `final` is touched. **But it is not a fallback**: production asks `isVerified` with the compiled v3 descriptor, which v2's bytes and receipt fail, so nothing can load it | — | `Repair needed` or `Update failed`, and NO transcription. The surviving files shorten the recovery, they do not degrade gracefully | `ModelDeliveryUi`, `AsrService` |

No new fallback expression is introduced.

## 10. File-by-file changes

1. **`app/src/main/java/com/envi/wispr/models/ModelManifest.kt`** — `parakeetRepo` to the v3 repository,
   `parakeetRevision` to `2bda32ec70b097a55adaa07d9a7173915b43cc78`, and the four `ModelFile` rows to the
   §2.5.5 byte counts and hashes. Nothing else in the file moves.
2. **`app/src/test/java/com/envi/wispr/models/ModelManifestTest.kt`** — the pinned-revision assertion.
3. **`app/src/main/java/com/envi/wispr/asr/AsrService.kt`** — ONE added line in `initRecognizer`, setting
   `config.decodingMethod` to `"greedy_search"` with a comment naming upstream `#3267` (external) and the
   date the default was read. This is the one deviation from "no engine change", adopted because the very
   next planned change is a sherpa-onnx upgrade, and a changed upstream default would otherwise reach this
   model silently. It pins the value the runtime already uses, so it alters no current behaviour.
4. **`app/src/main/java/com/envi/wispr/ui/TranscriptionScreen.kt`** — the sentences at lines 54, 56, 104 and
   106. Wording is drafted in §11's UAT step and checked against `content-brand.md`; the only hard
   constraint stated here is that the new text must not promise a language the model does not have, and
   must not claim detection (§8).
5. **`.claude/knowledge/model-delivery.md`** — the `FACT: what-ships-today` row and the hand-place recipe's
   URL and repository, edited in place per `RULE: promote-by-editing-not-appending`.
6. **`.claude/knowledge/current-state.md`** and **`.claude/knowledge/tech-stack.md`** — the Parakeet line in
   each, edited in place.

## 11. Testing

1. **Class of every new test.** `ModelManifestTest` is a **drift guard**: when it fails, the user sees a
   model that will not verify or a silent drift to a branch head. The new `ModelDeliveryStoreTest` case is a
   **product-outcome** test: when it fails, the user loses a working speech engine to a failed update. No
   unit test claims to prove transcription quality, because no unit test on this machine can hear anything.
2. **What revert would turn each red?** Named per row below, and each is performed, watched go red, and
   restored.
3. **Deliberately NOT tested.** Transcription accuracy across the 25 languages as a suite: there is no
   labelled multilingual corpus here, and a fixture built from the model's own `test_wavs/` would prove only
   that the model agrees with itself. Also not unit-tested: the decoding-method pin, because it is a field on
   a native config object with no observable Kotlin surface, and a successful transcription cannot distinguish
   an explicit greedy from a default greedy. **Nothing tests it.** Its evidence is the tagged-source read in
   §2.5.5, and its justification is drift protection ahead of the AAR upgrade.

### 11.1 Hardware UAT spec

- **Subsystem:** heart path — ASR.
- **Recipe:** `PROCEDURE: place-a-model-by-hand` in `.claude/knowledge/model-delivery.md`, re-run with v3
  values and a receipt GENERATED from the manifest by the script already in that file, never typed.
- **Order, and it matters.** Capture the v2 baseline BEFORE anything changes, because after the swap it
  cannot be recovered without a second 661 MB fetch.

**Step 0 — v2 baseline, on the current build.** Dictate these three sentences, verbatim, into a real
third-party editor, and save what lands:

1. *"Let's meet at quarter past three on September fourth to review the budget."*
2. *"Um, the total came to twenty four thousand five hundred dollars and thirty cents."*
3. *"Send it to me at saurabh dot v at envious labs dot co, thanks."*

They are chosen to exercise the cleanup families most likely to move: time, date, filler, money, and email.

**Step 1 — swap.** Build, install, force-stop the app so `:asr` cannot serve a stale recognizer (§7), place
the four v3 files and the regenerated receipt, confirm the Transcription page reads Ready.

**Step 2 — English control, the row that protects four of seven personas.** Dictate the same three sentences
into the same editor.

**The oracle is Step 0's saved v2 output, NOT the spoken words.** These sentences are chosen precisely
because correct cleanup rewrites them: "quarter past three" becomes a clock time, "twenty four thousand five
hundred dollars and thirty cents" becomes a currency figure, and the spoken email becomes an address. A
predicate demanding every spoken word survive would therefore be violated by the software working properly.

**Pass predicate, per sentence:** v3's output carries the same meaning as v2's saved output for that
sentence, with nothing dropped and nothing present that was not said. Formatting differences between v2 and
v3 are recorded, not failed. An empty result, a dropped clause, or an invented phrase is a failure.

**Step 3 — German case.** Dictate *"Der Zug war schon wieder zwanzig Minuten zu spät."* into the same editor.
**Pass predicate:** recognisable German words, judged against what was said. Non-empty text is not a pass.

**Step 4 — back-to-back.** Dictate twice in a row, because #43 and the current P1 both live there and a model
swap is exactly when a latent insertion bug gets blamed on the model.

**Step 5 — peak memory**, against upstream `#2626` (external). The metric is `VmHWM` (external) from the `:asr`
process's own status, read at three points: after model load, after the first decode, and after the
back-to-back pair. **The same three readings are taken in Step 0 on v2**, otherwise there is nothing to
compare against. Record the `:asr` PID alongside each reading. **Fail if the PID changes** — that is a
process restart, which is what an out-of-memory kill looks like from outside — **or if `:asr` aborts
natively.** The plan predicts no material change from v2, and this is where that prediction is falsifiable.

**Oracle for every step:** the target editor's own field content, read back. Never the clipboard, which is
the fallback route and not evidence of insertion.

**Phone state to restore:** the app is left installed and Ready on v3 with permissions and the accessibility
service as they were. If any developer setting, timeout or stay-awake flag is changed by the run, it is
restored per `RULE: revert-the-phone-after-a-session`.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `ModelManifestTest.productionDescriptorsCarryPinnedVerifiedReceipts` | drift guard | The v3 descriptor is well-formed on all five `isAvailable` axes and pins the intended revision | Restoring the v2 revision, or corrupting one hash |
| New case in `ModelDeliveryStoreTest`: admit an r1 payload, then apply a CORRUPT r2 | product outcome | The r1 directory is byte-identical afterwards and `REPAIR_NEEDED` is returned. The reason it survives is that a hash or byte-count mismatch quarantines the STAGING directory at `models/ModelDelivery.kt:129-131` and returns **before** `final` is ever renamed | Moving the admission of `final` ahead of the per-file verification loop. **Not** removing the `.old` restore at `models/ModelDelivery.kt:141` — that branch is only reachable when `staging.renameTo(final)` itself fails, which a corrupt download never reaches, so a test asserting it would be green with the behaviour gone |
| Second new case: admit r1, then apply a VALID r2 | product outcome | The directory holds r2's bytes and r2's receipt, and `isVerified` is true against the r2 descriptor | Skipping the receipt rewrite at `models/ModelDelivery.kt:136` |
| Emulator run of the SHIPPED update path | product outcome | Goal 4, exercised the way a real person meets it. On an arm64 emulator: install the BASELINE APK built from the current `main`, use its own in-app Download action to admit v2, keep app data, install the CANDIDATE APK over it, then tap Update and watch Update available → Queued → Downloading → Verifying → Ready **with no file placed by hand**. The emulator carries this rather than the phone so the daily driver does not take a second 670 MB transfer | Reverting the manifest revision, which removes the Update offer entirely |
| Full unit suite | regression | Nothing keyed on the model id or the engine label broke | — |

Suite count is taken with `--rerun-tasks` and the XML parser in `current-state.md`, never quoted from memory.

## 12. Blast radius & rollback

- **Touched:** `models/ModelManifest.kt`, ONE line in `asr/AsrService.kt`, one Compose screen, two unit
  tests, three knowledge files.
- **Deliberately NOT touched:** the recognizer's model wiring in `asr/AsrService.kt:159-170` — the file names
  and `nemo_transducer` type are untouched, and that negative space is the evidence this is a re-pin rather
  than an engine port. Also untouched: `cleanup/`, `polish/`, the delivery layer itself, the Room schema, and
  the sherpa-onnx AAR.
- **Rollback:** revert the manifest commit, rebuild, re-run the hand-place recipe with the v2 values and a
  receipt regenerated from the reverted manifest. About ten minutes plus a 661 MB fetch. No data is at risk;
  the phone's models are dev state per `CLAUDE.md` § Stage 1.

## 13. Ship criteria specific to THIS change

- [ ] Speaking German into a real editor on the S26 puts German words in the field.
- [ ] The three named English sentences carry the same meaning as the v2 output saved in Step 0, with
      nothing dropped and nothing invented. Formatting differences between the two versions are recorded,
      not failed, because correct cleanup rewrites those sentences by design.
- [ ] The `:asr` process survives loading and back-to-back decoding, with its peak memory written down beside
      the v2 figure.
- [ ] An install starting from the old model reaches the new one by tapping Update, with no file placed by
      hand. Confirmed on the emulator, not the phone, and §11.2 says why.
- [ ] No sentence in the app still promises English only.
- [ ] The claim made in public is "25 languages per NVIDIA's model card, one of them confirmed on this
      device", never "25 languages tested".

## 14. Open questions

1. **Does sherpa-onnx 1.12.29 load v3 without an AAR upgrade?** The two upstream defects that could have
   answered no are resolved in §2.5.5 — the TDT word-drop fix predates our build by six months, and the
   decoding default is already greedy. The opset report `#2842` (external) remains the live risk, and the
   first load is its oracle. If it fails, the plan becomes an AAR upgrade first and the tier is re-judged.
2. **Wording of the three sentences.** Drafted against `content-brand.md` at build time; the constraint is
   in §10.

## 15. Related

- Issue #36 — the parent, which stays OPEN after this change for the second engine.
- `PAR-030` moves, `PAR-031` / `PAR-034` / `PAR-035` / `PAR-036` / `PAR-037` untouched
  (`docs/enviouswispr-android-parity-spec.md:61-68`).
- Issue #15 — third-party notices, which names no speech model today.
- Catalog `dual-speech-engines`, `automatic-language-detection`, `locked-language`.
- [Issue #107](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/107), the cross-language
  cleanup collision, whose scope is the nineteen transformation families plus the safety layer enumerated
  in §7. Filed 2026-09-02 by this plan.
- Upstream sherpa-onnx `#2606`, `#2626`, `#2842`, `#3267` (all external), resolved in §2.5.5.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it
